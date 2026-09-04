package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import com.smartquery.orchestration.LeadOutputPolicyService;
import com.smartquery.orchestration.OperatorTypes;
import com.smartquery.orchestration.OutputCapabilityRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Executes an immutable, database-resolved set of output targets. */
@Component
@RequiredArgsConstructor
public class OutputOperatorExecutor implements OperatorExecutor {
    private final LeadOutputPolicyService leadOutputPolicyService;
    private final OutputCapabilityRegistryService outputCapabilityRegistryService;

    @Override
    public String implementationType() { return "OUTPUT_RENDERER"; }

    @Override
    public OperatorExecutionResult execute(OperatorExecutionContext context) {
        if (!OperatorTypes.OUTPUT.equals(context.operatorType())) {
            throw new BusinessException(422, "OUTPUT_RENDERER只能运行OUTPUT算子");
        }
        Map<String, Object> payload = context.implementationPayload();
        if ("2".equals(String.valueOf(payload.get("specVersion"))) && payload.get("targets") instanceof List<?>) {
            return executeComposable(context, payload);
        }
        return executeLegacy(context, payload);
    }

    private OperatorExecutionResult executeComposable(OperatorExecutionContext context, Map<String, Object> payload) {
        boolean authoredVersion = payload.containsKey("draftId");
        if (authoredVersion && (context.nodeConfig().containsKey("contentSpec")
                || context.nodeConfig().containsKey("leadPolicy") || context.nodeConfig().containsKey("targets")
                || context.nodeConfig().containsKey("transformations"))) {
            throw new BusinessException(422, "已发布输出算子的转换、目标和策略不可在DAG节点中覆盖");
        }
        List<Map<String, Object>> transformations = maps(payload.get("transformations"));
        List<Map<String, Object>> targets = maps(payload.get("targets"));
        if (targets.isEmpty()) throw new BusinessException(422, "输出算子至少需要一个输出目标");

        List<Map<String, Object>> records = new ArrayList<>(context.records());
        for (Map<String, Object> transformation : transformations) {
            OutputCapabilityRegistryService.CapabilitySnapshot capability =
                outputCapabilityRegistryService.requireRunnableSnapshot(transformation);
            if (!"TRANSFORM".equals(capability.capabilityType())) {
                throw new BusinessException(422, capability.code() + "不是转换能力");
            }
            records = applyTransformation(capability.implementationType(), map(transformation.get("config")), records);
        }

        List<Map<String, Object>> artifacts = new ArrayList<>();
        List<LeadDraft> leads = new ArrayList<>();
        for (Map<String, Object> target : targets) {
            OutputCapabilityRegistryService.CapabilitySnapshot capability =
                outputCapabilityRegistryService.requireRunnableSnapshot(target);
            if ("TRANSFORM".equals(capability.capabilityType())) {
                throw new BusinessException(422, capability.code() + "不能作为输出目标");
            }
            Map<String, Object> config = map(target.get("config"));
            artifacts.add(artifact(target, capability, config, records.size()));
            if ("LEAD".equals(capability.implementationType())) {
                OperatorExecutionResult source = new OperatorExecutionResult(
                    Map.of("records", records), List.of(), "");
                leads.addAll(leadOutputPolicyService.materialize(map(config.get("leadPolicy")),
                    Map.of(context.nodeId(), source)));
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("specVersion", 2);
        output.put("records", List.copyOf(records));
        output.put("recordCount", records.size());
        output.put("targetCount", artifacts.size());
        output.put("artifacts", List.copyOf(artifacts));
        output.put("leadCount", leads.size());
        if (!artifacts.isEmpty()) {
            output.put("outputKind", artifacts.get(0).get("kind"));
            output.put("artifact", artifacts.get(0));
        }
        return new OperatorExecutionResult(output, List.copyOf(leads),
            "Executed " + artifacts.size() + " output target(s) for " + records.size() + " records");
    }

    private OperatorExecutionResult executeLegacy(OperatorExecutionContext context, Map<String, Object> payload) {
        String kind = String.valueOf(payload.getOrDefault("outputKind", "")).toUpperCase(Locale.ROOT);
        boolean authoredVersion = payload.containsKey("draftId");
        if (authoredVersion && (context.nodeConfig().containsKey("contentSpec")
                || context.nodeConfig().containsKey("leadPolicy"))) {
            throw new BusinessException(422, "已发布输出算子的展示规格和线索策略不可在DAG节点中覆盖");
        }
        Map<String, Object> contentSpec = map(payload.get("contentSpec"));
        List<Map<String, Object>> records = context.records();
        Map<String, Object> artifact = legacyArtifact(kind, contentSpec, records.size());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("records", records);
        output.put("recordCount", records.size());
        output.put("outputKind", kind);
        output.put("artifact", artifact);
        output.put("artifacts", List.of(artifact));

        List<LeadDraft> leads = List.of();
        if ("LEAD".equals(kind)) {
            Map<String, Object> policy = authoredVersion ? Map.of() : map(context.nodeConfig().get("leadPolicy"));
            if (policy.isEmpty()) policy = map(contentSpec.get("leadPolicy"));
            OperatorExecutionResult source = new OperatorExecutionResult(Map.of("records", records), List.of(), "");
            leads = leadOutputPolicyService.materialize(policy, Map.of(context.nodeId(), source));
            output.put("leadCount", leads.size());
        }
        return new OperatorExecutionResult(output, leads,
            "Rendered legacy " + kind + " output for " + records.size() + " records");
    }

    private List<Map<String, Object>> applyTransformation(String implementationType,
                                                           Map<String, Object> config,
                                                           List<Map<String, Object>> input) {
        if (!"PROJECT".equals(implementationType)) {
            throw new BusinessException(422, "当前平台没有可信转换执行器: " + implementationType);
        }
        List<Map<String, Object>> result = new ArrayList<>(input);
        List<Map<String, Object>> sort = maps(config.get("sort"));
        if (!sort.isEmpty()) {
            Comparator<Map<String, Object>> comparator = null;
            for (Map<String, Object> item : sort) {
                String field = String.valueOf(item.get("field"));
                boolean desc = "desc".equalsIgnoreCase(String.valueOf(item.get("direction")));
                Comparator<Map<String, Object>> next = Comparator.comparing(
                    row -> comparable(resolve(viewRoot(row), field)), Comparator.nullsLast(Comparator.naturalOrder()));
                if (desc) next = next.reversed();
                comparator = comparator == null ? next : comparator.thenComparing(next);
            }
            result.sort(comparator);
        }
        int limit = intValue(config.get("limit"), result.size());
        if (limit < result.size()) result = new ArrayList<>(result.subList(0, Math.max(0, limit)));
        List<Map<String, Object>> columns = maps(config.get("columns"));
        if (!columns.isEmpty()) {
            List<Map<String, Object>> projected = new ArrayList<>();
            for (Map<String, Object> row : result) {
                Map<String, Object> next = new LinkedHashMap<>();
                for (Map<String, Object> column : columns) {
                    String field = String.valueOf(column.get("field"));
                    setPath(next, field, resolve(viewRoot(row), field));
                }
                row.forEach((key, value) -> { if (key.startsWith("__")) next.put(key, value); });
                projected.add(next);
            }
            result = projected;
        }
        return result;
    }

    private Map<String, Object> artifact(Map<String, Object> target,
                                         OutputCapabilityRegistryService.CapabilitySnapshot capability,
                                         Map<String, Object> config, int recordCount) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("targetId", target.get("id"));
        artifact.put("capabilityCode", capability.code());
        artifact.put("capabilityType", capability.capabilityType());
        artifact.put("capabilityVersionId", capability.versionId());
        artifact.put("contentHash", capability.contentHash());
        artifact.put("artifactSha256", capability.artifactSha256());
        artifact.put("kind", outputKind(capability, config));
        artifact.put("contentSpec", config);
        artifact.put("recordCount", recordCount);
        artifact.put("renderer", renderer(capability.implementationType()));
        artifact.put("interactionEvents", capability.interactionEvents());
        artifact.put("securityPolicy", capability.securityPolicy());
        if ("ECHARTS".equals(capability.implementationType())) {
            required(config, "chartType", "图表输出必须声明chartType");
            if (!config.containsKey("dimensions") || !config.containsKey("measures")) {
                throw new BusinessException(422, "图表输出必须声明dimensions和measures");
            }
        }
        if ("TABLE".equals(capability.implementationType())) list(config.get("columns"), "表格输出必须声明columns");
        return Map.copyOf(artifact);
    }

    private String outputKind(OutputCapabilityRegistryService.CapabilitySnapshot capability,
                              Map<String, Object> config) {
        String legacy = string(config.get("legacyOutputKind"));
        if (legacy != null) return legacy;
        return switch (capability.implementationType()) {
            case "LEAD" -> "LEAD";
            case "ECHARTS" -> "CHART";
            case "TABLE" -> "TABLE";
            case "COMPOSED_PAGE" -> "DASHBOARD";
            case "RUN_ARTIFACT" -> "ARTIFACT";
            case "TEMP_ARTIFACT" -> "TEMP_RESULT";
            case "XLSX", "CSV", "PDF", "JSON", "PNG" -> "EXPORT_" + capability.implementationType();
            default -> capability.capabilityType() + "_" + capability.implementationType();
        };
    }

    private String renderer(String implementationType) {
        return switch (implementationType) {
            case "LEAD" -> "standard-lead-v2";
            case "ECHARTS" -> "echarts-safe-spec-v2";
            case "TABLE" -> "table-spec-v2";
            case "COMPOSED_PAGE" -> "trusted-composition-v1";
            case "RUN_ARTIFACT", "TEMP_ARTIFACT" -> "artifact-metadata-v1";
            case "XLSX", "CSV", "PDF", "JSON", "PNG" -> "governed-export-v1";
            default -> "registered-output-v1";
        };
    }

    private Map<String, Object> legacyArtifact(String kind, Map<String, Object> spec, int recordCount) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("kind", kind);
        artifact.put("contentSpec", spec);
        artifact.put("recordCount", recordCount);
        switch (kind) {
            case "LEAD" -> artifact.put("renderer", "standard-lead-v1");
            case "CHART" -> {
                required(spec, "chartType", "CHART输出必须声明chartType");
                if (!spec.containsKey("dimensions") && !spec.containsKey("measures")) {
                    throw new BusinessException(422, "CHART输出必须声明dimensions或measures");
                }
                artifact.put("renderer", "chart-spec-v1");
            }
            case "TABLE" -> { list(spec.get("columns"), "TABLE输出必须声明columns"); artifact.put("renderer", "table-spec-v1"); }
            case "EXCEL" -> {
                list(spec.get("columns"), "EXCEL输出必须声明columns");
                artifact.put("renderer", "excel-grid-v1");
                artifact.put("sheetName", String.valueOf(spec.getOrDefault("sheetName", "结果")));
            }
            default -> throw new BusinessException(422, "outputKind仅支持LEAD/CHART/TABLE/EXCEL");
        }
        return Map.copyOf(artifact);
    }

    private Object required(Map<String, Object> map, String field, String message) {
        Object value = map.get(field);
        if (value == null || String.valueOf(value).isBlank()) throw new BusinessException(422, message);
        return value;
    }
    private List<Object> list(Object raw, String message) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) throw new BusinessException(422, message);
        return new ArrayList<>(values);
    }
    private List<Map<String, Object>> maps(Object raw) {
        if (!(raw instanceof List<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) if (value instanceof Map<?, ?>) result.add(map(value));
        return result;
    }
    private Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Map<String, Object> viewRoot(Map<String, Object> record) {
        Map<String, Object> result = new LinkedHashMap<>();
        record.forEach((key, value) -> { if (!key.startsWith("__")) result.put(key, value); });
        Object snapshots = record.get(LineageSupport.SOURCE_SNAPSHOTS);
        Map<String, Object> root = new LinkedHashMap<>();
        if (snapshots instanceof List<?> list && list.size() == 1 && list.get(0) instanceof Map<?, ?> source) {
            source.forEach((key, value) -> root.put(String.valueOf(key), value));
        }
        root.putAll(result);
        root.put("result", result);
        root.put("sources", snapshots instanceof List<?> list ? list : List.of());
        return root;
    }
    private Object resolve(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) current = map.get(part);
            else if (current instanceof List<?> list && part.matches("\\d+")) {
                int index = Integer.parseInt(part);
                current = index < list.size() ? list.get(index) : null;
            } else return null;
        }
        return current;
    }
    @SuppressWarnings("unchecked")
    private void setPath(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int index = 0; index < parts.length - 1; index++) {
            Object existing = current.get(parts[index]);
            if (!(existing instanceof Map<?, ?>)) {
                existing = new LinkedHashMap<String, Object>();
                current.put(parts[index], existing);
            }
            current = (Map<String, Object>) existing;
        }
        current.put(parts[parts.length - 1], value);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Comparable comparable(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Comparable comparable) return comparable;
        return String.valueOf(value);
    }
    private int intValue(Object raw, int fallback) {
        if (raw == null) return fallback;
        try { return Integer.parseInt(String.valueOf(raw)); }
        catch (Exception ignored) { return fallback; }
    }
    private String string(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
}
