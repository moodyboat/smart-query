package com.smartquery.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具执行结果 — 不可变 record
 */
public record ToolResult(
    String toolName,
    boolean success,
    String output,
    String error,
    long durationMs,
    List<Map<String, Object>> data,
    ToolError toolError
) {
    public static ToolResult ok(String toolName, String output, long durationMs) {
        return new ToolResult(toolName, true, output, null, durationMs, null, null);
    }

    public static ToolResult ok(String toolName, String output, long durationMs, List<Map<String, Object>> data) {
        return new ToolResult(toolName, true, output, null, durationMs, data, null);
    }

    public static ToolResult error(String toolName, String error, long durationMs) {
        return new ToolResult(toolName, false, null, error, durationMs, null,
            ToolError.of(ToolError.ErrorCode.UNKNOWN, error));
    }

    public static ToolResult error(String toolName, ToolError toolError, long durationMs) {
        return new ToolResult(toolName, false, null, toolError.message(), durationMs, null, toolError);
    }

    /** Convenience: get error code if present */
    public ToolError.ErrorCode errorCode() {
        return toolError != null ? toolError.code() : null;
    }

    /** Convenience: is this error retryable */
    public boolean isRetryable() {
        return toolError != null && toolError.retryable();
    }
}
