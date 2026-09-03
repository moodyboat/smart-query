package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.RulePrimitive;
import com.smartquery.mapper.RulePrimitiveMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates whether a structured rule can be expressed by registered primitives. */
@Service
@RequiredArgsConstructor
public class RuleCompositionService {

    private final RulePrimitiveMapper rulePrimitiveMapper;
    private final DagValidationService dagValidationService;
    private final ObjectMapper objectMapper;

    public List<RulePrimitive> listCapabilities() {
        return rulePrimitiveMapper.selectList(new LambdaQueryWrapper<RulePrimitive>()
            .eq(RulePrimitive::getEnabled, 1)
            .orderByAsc(RulePrimitive::getCategory)
            .orderByAsc(RulePrimitive::getCode));
    }

    @SuppressWarnings("unchecked")
    public RuleValidationReport validate(Map<String, Object> composition) {
        Object rawSteps = composition == null ? null : composition.get("steps");
        if (!(rawSteps instanceof List<?> list) || list.isEmpty()) {
            return new RuleValidationReport(false, 0, List.of(), List.of("规则组合至少需要一个step"),
                List.of(), List.of());
        }

        Map<String, RulePrimitive> capabilities = new LinkedHashMap<>();
        for (RulePrimitive primitive : listCapabilities()) capabilities.put(primitive.getCode(), primitive);

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        List<Map<String, Object>> dagNodes = new ArrayList<>();
        List<Map<String, Object>> dagEdges = new ArrayList<>();
        int supported = 0;
        String previousStepId = null;

        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> raw)) {
                errors.add("step#" + (i + 1) + "必须是对象");
                previousStepId = null;
                continue;
            }
            Map<String, Object> step = (Map<String, Object>) raw;
            String id = text(step.get("id"));
            String op = text(step.get("op"));
            if (id == null) id = "step_" + (i + 1);
            dagNodes.add(Map.of("id", id));
            if (op == null) {
                errors.add("步骤[" + id + "]缺少op");
            } else if (!capabilities.containsKey(op)) {
                missing.add(op);
            } else {
                supported++;
                validateRequiredConfig(id, step, capabilities.get(op), errors);
            }
            Object dependencies = step.get("dependsOn");
            if (dependencies instanceof List<?> dependencyList) {
                for (Object dependency : dependencyList) {
                    if (dependency != null) dagEdges.add(Map.of("source", String.valueOf(dependency), "target", id));
                }
            } else if (dependencies != null) {
                dagEdges.add(Map.of("source", String.valueOf(dependencies), "target", id));
            } else if (previousStepId != null) {
                dagEdges.add(Map.of("source", previousStepId, "target", id));
            }
            previousStepId = id;
        }

        DagValidationService.DagValidationReport dag = dagValidationService.validate(dagNodes, dagEdges, false);
        errors.addAll(dag.errors());
        warnings.addAll(dag.warnings());
        if (!missing.isEmpty()) {
            errors.add("缺少规则能力: " + String.join(", ", missing));
        }
        int coverage = list.isEmpty() ? 0 : (int) Math.round(supported * 100.0 / list.size());
        return new RuleValidationReport(errors.isEmpty(), coverage, List.copyOf(missing),
            List.copyOf(errors), List.copyOf(warnings), dag.executionLevels());
    }

    @SuppressWarnings("unchecked")
    private void validateRequiredConfig(String stepId, Map<String, Object> step,
                                        RulePrimitive primitive, List<String> errors) {
        try {
            Map<String, Object> schema = objectMapper.readValue(primitive.getParameterSchema(), Map.class);
            Object rawRequired = schema.get("required");
            if (!(rawRequired instanceof List<?> required)) return;
            Map<String, Object> config = step.get("config") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : step;
            for (Object field : required) {
                String name = String.valueOf(field);
                Object value = config.get(name);
                if (value == null || (value instanceof String text && text.isBlank())
                        || (value instanceof List<?> list && list.isEmpty())) {
                    errors.add("步骤[" + stepId + "]的" + primitive.getCode() + "缺少参数: " + name);
                }
            }
        } catch (Exception e) {
            errors.add("规则能力[" + primitive.getCode() + "]参数Schema无效");
        }
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public record RuleValidationReport(boolean supported, int capabilityCoverage,
                                       List<String> missingCapabilities, List<String> errors,
                                       List<String> warnings, List<List<String>> executionLevels) {}
}
