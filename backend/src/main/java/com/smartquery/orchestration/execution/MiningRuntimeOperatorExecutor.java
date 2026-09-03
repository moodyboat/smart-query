package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import com.smartquery.orchestration.OperatorTypes;
import com.smartquery.orchestration.RuntimeProfileService;
import com.smartquery.service.MiningService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapts a published mining model to the immutable orchestration operator contract. */
@Component
@RequiredArgsConstructor
public class MiningRuntimeOperatorExecutor implements OperatorExecutor {
    private final MiningService miningService;
    private final RuntimeProfileService runtimeProfileService;

    @Override
    public String implementationType() {
        return "MINING_RUNTIME";
    }

    @Override
    public OperatorExecutionResult execute(OperatorExecutionContext context) {
        if (!OperatorTypes.ML.equals(context.operatorType())) {
            throw new BusinessException(422, "MINING_RUNTIME执行器只能运行ML算子");
        }
        Long modelId = positiveLong(context.implementationPayload().get("modelId"), "modelId");
        String predictionField = field(context.implementationPayload().get("predictionField"), "prediction");
        String probabilityField = field(context.implementationPayload().get("probabilityField"), "predictionProbability");
        Integer probabilityIndex = optionalIndex(context.implementationPayload().get("probabilityIndex"));

        List<Map<String, Object>> input = context.records().stream().map(this::publicFields).toList();
        RuntimeProfileService.RuntimeBindingView runtime = runtimeProfileService.requireRunnable(
            context.operatorVersion(), context.operatorType());
        Map<String, Object> response = miningService.predictModelTransient(
            modelId, input, runtime.profile().getImageRef());
        List<?> predictions = list(response.get("predictions"), "模型输出缺少predictions数组");
        if (predictions.size() != context.records().size()) {
            throw new BusinessException(422, "模型预测数量与输入数量不一致");
        }
        List<?> probabilities = response.get("probabilities") instanceof List<?> list ? list : List.of();

        List<Map<String, Object>> records = new ArrayList<>();
        for (int index = 0; index < predictions.size(); index++) {
            Map<String, Object> output = new LinkedHashMap<>(context.records().get(index));
            Object prediction = predictions.get(index);
            Double probability = index < probabilities.size()
                ? probability(probabilities.get(index), probabilityIndex) : null;
            output.put(predictionField, prediction);
            if (probability != null) output.put(probabilityField, probability);
            appendEvidence(output, modelId, predictionField, prediction, probability);
            records.add(output);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("records", records);
        output.put("recordCount", records.size());
        output.put("modelId", modelId);
        output.put("predictionField", predictionField);
        output.put("probabilityField", probabilityField);
        if (response.get("classes") != null) output.put("classes", response.get("classes"));
        return new OperatorExecutionResult(output, List.of(),
            "Model " + modelId + " predicted " + records.size() + " records");
    }

    private Map<String, Object> publicFields(Map<String, Object> record) {
        Map<String, Object> result = new LinkedHashMap<>();
        record.forEach((key, value) -> {
            if (!key.startsWith("__")) result.put(key, value);
        });
        return result;
    }

    private void appendEvidence(Map<String, Object> record, Long modelId, String predictionField,
                                Object prediction, Double probability) {
        List<Object> evidence = new ArrayList<>();
        if (record.get(LineageSupport.EVIDENCE) instanceof List<?> existing) evidence.addAll(existing);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "MODEL_PREDICTION");
        item.put("name", "模型预测 #" + modelId);
        item.put("field", predictionField);
        item.put("actualValue", String.valueOf(prediction));
        item.put("condition", "modelId=" + modelId);
        if (probability != null) item.put("contribution", probability);
        evidence.add(item);
        record.put(LineageSupport.EVIDENCE, evidence);
    }

    private Double probability(Object raw, Integer index) {
        if (raw instanceof Number number) return number.doubleValue();
        if (!(raw instanceof List<?> values) || values.isEmpty()) return null;
        if (index != null) {
            if (index >= values.size()) throw new BusinessException(422, "probabilityIndex超出模型概率列范围");
            return number(values.get(index));
        }
        Double result = null;
        for (Object value : values) {
            Double candidate = number(value);
            if (candidate != null && (result == null || candidate > result)) result = candidate;
        }
        return result;
    }

    private Double number(Object value) {
        if (value == null) return null;
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException e) { return null; }
    }

    private List<?> list(Object raw, String message) {
        if (!(raw instanceof List<?> list)) throw new BusinessException(422, message);
        return list;
    }

    private Long positiveLong(Object raw, String name) {
        try {
            long value = Long.parseLong(String.valueOf(raw));
            if (value > 0) return value;
        } catch (Exception ignored) {}
        throw new BusinessException(422, name + "必须是正整数");
    }

    private Integer optionalIndex(Object raw) {
        if (raw == null) return null;
        try {
            int value = Integer.parseInt(String.valueOf(raw));
            if (value >= 0) return value;
        } catch (Exception ignored) {}
        throw new BusinessException(422, "probabilityIndex必须是非负整数");
    }

    private String field(Object raw, String fallback) {
        String value = raw == null ? fallback : String.valueOf(raw).trim();
        if (!value.matches("^[A-Za-z_][A-Za-z0-9_]{0,99}$") || value.startsWith("__")) {
            throw new BusinessException(422, "模型输出字段名不合法: " + value);
        }
        return value;
    }
}
