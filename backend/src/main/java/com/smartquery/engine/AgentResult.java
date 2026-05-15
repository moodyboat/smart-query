package com.smartquery.engine;

import java.util.List;
import java.util.Map;

/**
 * 子任务执行结果
 */
public record AgentResult(
    String taskId,
    boolean success,
    String output,
    Map<String, Object> artifacts,
    long durationMs,
    int tokenUsage,
    String error
) {
    public static AgentResult success(String taskId, String output, long durationMs, int tokenUsage) {
        return new AgentResult(taskId, true, output, Map.of(), durationMs, tokenUsage, null);
    }

    public static AgentResult success(String taskId, String output, Map<String, Object> artifacts,
                                       long durationMs, int tokenUsage) {
        return new AgentResult(taskId, true, output, artifacts, durationMs, tokenUsage, null);
    }

    public static AgentResult failure(String taskId, String error, long durationMs) {
        return new AgentResult(taskId, false, null, Map.of(), durationMs, 0, error);
    }
}
