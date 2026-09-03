package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class OperatorExecutorRegistry {
    private final Map<String, OperatorExecutor> executors;

    public OperatorExecutorRegistry(List<OperatorExecutor> candidates) {
        Map<String, OperatorExecutor> registered = new LinkedHashMap<>();
        for (OperatorExecutor executor : candidates) {
            String key = executor.implementationType().toUpperCase(Locale.ROOT);
            if (registered.putIfAbsent(key, executor) != null) {
                throw new IllegalStateException("重复的算子执行器: " + key);
            }
        }
        this.executors = Map.copyOf(registered);
    }

    public OperatorExecutor require(String implementationType) {
        String key = implementationType == null ? "" : implementationType.toUpperCase(Locale.ROOT);
        OperatorExecutor executor = executors.get(key);
        if (executor == null) {
            throw new BusinessException(422, "实现方式[" + key
                + "]尚未启用安全执行器；请改用已支持的实现或接入隔离沙箱");
        }
        return executor;
    }

    public List<String> enabledImplementationTypes() {
        return executors.keySet().stream().sorted().toList();
    }
}
