package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import com.smartquery.orchestration.OperatorTypes;
import com.smartquery.orchestration.RuleRuntimeClient;
import com.smartquery.orchestration.RuntimeProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes a validated custom rule artifact in the dedicated Docker sandbox. */
@Component
@RequiredArgsConstructor
public class RuleSandboxOperatorExecutor implements OperatorExecutor {
    private final RuleRuntimeClient ruleRuntimeClient;
    private final RuntimeProfileService runtimeProfileService;

    @Override
    public String implementationType() {
        return "SANDBOX_EXTENSION";
    }

    @Override
    public OperatorExecutionResult execute(OperatorExecutionContext context) {
        if (!OperatorTypes.RULE.equals(context.operatorType())) {
            throw new BusinessException(422, "SANDBOX_EXTENSION执行器只能运行RULE算子");
        }
        if (!Boolean.TRUE.equals(context.implementationPayload().get("sandboxValidated"))) {
            throw new BusinessException(422, "自定义规则版本尚未通过沙箱测试门禁");
        }
        RuntimeProfileService.RuntimeBindingView binding = runtimeProfileService.requireRunnable(
            context.operatorVersion(), context.operatorType());
        Map<String, Object> artifact = new LinkedHashMap<>(context.implementationPayload());
        artifact.put("allowedModules", runtimeProfileService.allowedPythonModules(binding.profile()));
        Map<String, Object> parameters = map(context.nodeConfig().get("parameters"));
        RuleRuntimeClient.RuntimeResult result = ruleRuntimeClient.execute(
            artifact, context.records(), parameters,
            context.runId(), context.nodeId(), binding.profile());
        if (!result.successful()) {
            throw new BusinessException(422, "自定义规则执行失败: " + limit(result.errorMessage(), 2000));
        }
        List<Map<String, Object>> records = records(result.payload().get("records"));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("records", records);
        output.put("recordCount", records.size());
        output.put("sandboxTests", result.payload().getOrDefault("tests", List.of()));
        return new OperatorExecutionResult(output, List.of(),
            "Isolated rule completed in " + result.process().executionTimeMs() + "ms");
    }

    private List<Map<String, Object>> records(Object raw) {
        if (!(raw instanceof List<?> list)) throw new BusinessException(422, "规则沙箱输出缺少records数组");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new BusinessException(422, "规则沙箱输出记录必须是对象");
            result.add(map(map));
        }
        return result;
    }

    private Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
