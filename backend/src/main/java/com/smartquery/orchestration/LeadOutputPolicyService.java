package com.smartquery.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.orchestration.execution.LeadDraft;
import com.smartquery.orchestration.execution.LineageSupport;
import com.smartquery.orchestration.execution.OperatorExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Converts terminal outputs from any operator family into the standard lead contract. */
@Service
@RequiredArgsConstructor
public class LeadOutputPolicyService {
    private final ObjectMapper objectMapper;

    public Map<String, Object> defaultPolicy() {
        return Map.of(
            "leadType", "FLOW_RESULT",
            "mode", "ALL",
            "subjectMapping", Map.of("subjectType", Map.of("value", "SOURCE_RECORD"),
                "subjectId", "$__sourceRefs.0")
        );
    }

    public PolicyValidation validate(Map<String, Object> policy) {
        List<String> errors = new ArrayList<>();
        String leadType = text(policy == null ? null : policy.get("leadType"));
        String mode = text(policy == null ? null : policy.getOrDefault("mode", "ALL"));
        if (leadType == null) errors.add("leadPolicy.leadType不能为空");
        if (mode == null || !List.of("ALL", "CONDITION").contains(mode.toUpperCase(Locale.ROOT))) {
            errors.add("leadPolicy.mode仅支持ALL或CONDITION");
        }
        if ("CONDITION".equalsIgnoreCase(mode) && !(policy.get("condition") instanceof Map<?, ?>)) {
            errors.add("CONDITION模式必须提供结构化condition");
        }
        return new PolicyValidation(errors.isEmpty(), List.copyOf(errors));
    }

    public List<LeadDraft> materialize(Map<String, Object> policy,
                                       Map<String, OperatorExecutionResult> terminalOutputs) {
        Map<String, Object> effective = policy == null || policy.isEmpty() ? defaultPolicy() : policy;
        PolicyValidation validation = validate(effective);
        if (!validation.valid()) throw new BusinessException(422, String.join("；", validation.errors()));
        List<String> selectedNodes = strings(effective.get("terminalNodeIds"));
        List<LeadDraft> result = new ArrayList<>();
        terminalOutputs.forEach((nodeId, output) -> {
            if (!selectedNodes.isEmpty() && !selectedNodes.contains(nodeId)) return;
            for (Map<String, Object> record : records(output.output().get("records"))) {
                if (matches(effective, record)) result.add(toDraft(effective, nodeId, record));
            }
        });
        return result;
    }

    private LeadDraft toDraft(Map<String, Object> policy, String nodeId, Map<String, Object> record) {
        Map<String, Object> subject = map(policy.get("subjectMapping"));
        Map<String, Object> decision = map(policy.get("decisionMapping"));
        Map<String, Object> source = map(policy.get("sourceMapping"));
        Map<String, Object> attributes = new LinkedHashMap<>();
        record.forEach((key, value) -> { if (!key.startsWith("__")) attributes.put(key, value); });
        attributes.put("terminalNodeId", nodeId);
        List<Map<String, Object>> snapshots = records(record.get(LineageSupport.SOURCE_SNAPSHOTS));
        Map<String, Object> snapshot = snapshots.size() == 1 ? snapshots.get(0)
            : Map.of("records", snapshots, "sourceRefs", list(record.get(LineageSupport.SOURCE_REFS)));
        List<LeadDraft.EvidenceDraft> evidence = evidence(record, nodeId);
        return new LeadDraft(
            String.valueOf(policy.get("leadType")),
            mapped(record, subject.get("subjectType")),
            mapped(record, subject.get("subjectId")),
            mapped(record, subject.get("subjectName")),
            number(resolve(record, decision.get("score"))),
            first(mapped(record, decision.get("level")), "UNSPECIFIED"),
            number(resolve(record, decision.get("threshold"))),
            first(mapped(record, decision.get("result")), "HIT"),
            longNumber(resolve(record, source.get("dataSourceId"))),
            mapped(record, source.get("sourceTable")),
            mapped(record, source.get("primaryKeyColumn")),
            mapped(record, source.get("primaryKeyValue")),
            snapshot, attributes, evidence,
            dateTime(resolve(record, policy.get("occurredAt")))
        );
    }

    private boolean matches(Map<String, Object> policy, Map<String, Object> record) {
        if (!"CONDITION".equalsIgnoreCase(String.valueOf(policy.getOrDefault("mode", "ALL")))) return true;
        Map<String, Object> condition = map(policy.get("condition"));
        Object actual = field(record, required(condition, "field"));
        Object expected = condition.get("value");
        String operator = required(condition, "operator");
        int comparison = compare(actual, expected);
        return switch (operator) {
            case "==" -> Objects.equals(normalize(actual), normalize(expected));
            case "!=" -> !Objects.equals(normalize(actual), normalize(expected));
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            default -> throw new BusinessException(422, "leadPolicy.condition.operator不支持: " + operator);
        };
    }

    private List<LeadDraft.EvidenceDraft> evidence(Map<String, Object> record, String nodeId) {
        List<LeadDraft.EvidenceDraft> result = new ArrayList<>();
        if (record.get(LineageSupport.EVIDENCE) instanceof List<?> list) {
            for (Object raw : list) {
                Map<String, Object> item = map(raw);
                result.add(new LeadDraft.EvidenceDraft(text(item.get("kind")), text(item.get("name")),
                    text(item.get("field")), text(item.get("actualValue")), text(item.get("condition")),
                    number(item.get("contribution")), text(item.get("snippet"))));
            }
        }
        result.add(new LeadDraft.EvidenceDraft("FLOW_OUTPUT", "流程终点输出", null,
            nodeId, "terminalNode=" + nodeId, null, null));
        return result;
    }

    private Object resolve(Map<String, Object> record, Object mapping) {
        if (mapping == null) return null;
        if (mapping instanceof Map<?, ?> raw) {
            Map<String, Object> value = map(raw);
            if (value.containsKey("field")) return field(record, String.valueOf(value.get("field")));
            return value.get("value");
        }
        if (mapping instanceof String text && text.startsWith("$")) return field(record, text.substring(1));
        return mapping;
    }

    private String mapped(Map<String, Object> record, Object mapping) {
        Object value = resolve(record, mapping);
        return value == null ? null : String.valueOf(value);
    }

    private Object field(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) current = map.get(part);
            else if (current instanceof List<?> list) {
                try { current = list.get(Integer.parseInt(part)); }
                catch (Exception e) { return null; }
            } else return null;
        }
        return current;
    }

    private int compare(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        Double leftNumber = number(left);
        Double rightNumber = number(right);
        if (leftNumber != null && rightNumber != null) return Double.compare(leftNumber, rightNumber);
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private Object normalize(Object value) {
        Double number = number(value);
        return number == null ? value : number;
    }

    private Double number(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private Long longNumber(Object value) {
        Double number = number(value);
        return number == null ? null : number.longValue();
    }

    private LocalDateTime dateTime(Object value) {
        if (value == null) return null;
        try { return LocalDateTime.parse(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private String required(Map<String, Object> map, String field) {
        String value = text(map.get(field));
        if (value == null) throw new BusinessException(422, "leadPolicy." + field + "不能为空");
        return value;
    }

    private String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private String first(String value, String fallback) { return value == null ? fallback : value; }

    private List<Object> list(Object raw) { return raw instanceof List<?> list ? new ArrayList<>(list) : List.of(); }

    private List<String> strings(Object raw) {
        return raw instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private List<Map<String, Object>> records(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?>) result.add(map(item));
        return result;
    }

    private Map<String, Object> map(Object raw) {
        if (raw == null) return Map.of();
        if (raw instanceof String json) {
            try { return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}); }
            catch (Exception e) { throw new BusinessException(422, "leadPolicy无法解析"); }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    public record PolicyValidation(boolean valid, List<String> errors) {}
}
