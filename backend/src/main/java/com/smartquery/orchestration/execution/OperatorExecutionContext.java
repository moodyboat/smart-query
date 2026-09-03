package com.smartquery.orchestration.execution;

import com.smartquery.entity.OperatorVersion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record OperatorExecutionContext(Long runId, Long nodeRunId, String nodeId,
                                       String operatorType, OperatorVersion operatorVersion,
                                       Map<String, Object> implementationPayload,
                                       Map<String, Object> nodeConfig,
                                       Map<String, Object> runInput,
                                       Map<String, OperatorExecutionResult> upstream) {

    /** Normalizes source and upstream outputs into an isolated record list. */
    public List<Map<String, Object>> records() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (upstream != null && !upstream.isEmpty()) {
            upstream.values().forEach(value -> appendRecords(result, value.output().get("records")));
        } else if (runInput != null) {
            appendRecords(result, runInput.get("records"));
        }
        return result;
    }

    private static void appendRecords(List<Map<String, Object>> result, Object raw) {
        if (!(raw instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            result.add(copy);
        }
    }
}
