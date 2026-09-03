package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import com.smartquery.orchestration.LeadOutputPolicyService;
import com.smartquery.orchestration.OperatorTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** First-class output node that describes a UI-renderable lead, chart or grid view. */
@Component
@RequiredArgsConstructor
public class OutputOperatorExecutor implements OperatorExecutor {
    private final LeadOutputPolicyService leadOutputPolicyService;

    @Override
    public String implementationType() {
        return "OUTPUT_RENDERER";
    }

    @Override
    public OperatorExecutionResult execute(OperatorExecutionContext context) {
        if (!OperatorTypes.OUTPUT.equals(context.operatorType())) {
            throw new BusinessException(422, "OUTPUT_RENDERER只能运行OUTPUT算子");
        }
        String kind = String.valueOf(context.implementationPayload().getOrDefault("outputKind", ""))
            .toUpperCase(Locale.ROOT);
        boolean authoredVersion = context.implementationPayload().containsKey("draftId");
        if (authoredVersion && (context.nodeConfig().containsKey("contentSpec")
                || context.nodeConfig().containsKey("leadPolicy"))) {
            throw new BusinessException(422, "已发布输出算子的展示规格和线索策略不可在DAG节点中覆盖");
        }
        Map<String, Object> contentSpec = map(context.implementationPayload().get("contentSpec"));
        List<Map<String, Object>> records = context.records();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("records", records);
        output.put("recordCount", records.size());
        output.put("outputKind", kind);
        output.put("artifact", artifact(kind, contentSpec, records.size()));

        List<LeadDraft> leads = List.of();
        if ("LEAD".equals(kind)) {
            Map<String, Object> policy = authoredVersion
                ? Map.of() : map(context.nodeConfig().get("leadPolicy"));
            if (policy.isEmpty()) policy = map(contentSpec.get("leadPolicy"));
            OperatorExecutionResult source = new OperatorExecutionResult(
                Map.of("records", records), List.of(), "");
            leads = leadOutputPolicyService.materialize(policy, Map.of(context.nodeId(), source));
            output.put("leadCount", leads.size());
        }
        return new OperatorExecutionResult(output, leads,
            "Rendered " + kind + " output for " + records.size() + " records");
    }

    private Map<String, Object> artifact(String kind, Map<String, Object> spec, int recordCount) {
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
            case "TABLE" -> {
                list(spec.get("columns"), "TABLE输出必须声明columns");
                artifact.put("renderer", "table-spec-v1");
            }
            case "EXCEL" -> {
                list(spec.get("columns"), "EXCEL输出必须声明columns");
                artifact.put("renderer", "excel-grid-v1");
                artifact.put("sheetName", String.valueOf(spec.getOrDefault("sheetName", "结果")));
            }
            default -> throw new BusinessException(422, "outputKind仅支持LEAD/CHART/TABLE/EXCEL");
        }
        return artifact;
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

    private Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
