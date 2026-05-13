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
    List<Map<String, Object>> data
) {
    public static ToolResult ok(String toolName, String output, long durationMs) {
        return new ToolResult(toolName, true, output, null, durationMs, null);
    }

    public static ToolResult ok(String toolName, String output, long durationMs, List<Map<String, Object>> data) {
        return new ToolResult(toolName, true, output, null, durationMs, data);
    }

    public static ToolResult error(String toolName, String error, long durationMs) {
        return new ToolResult(toolName, false, null, error, durationMs, null);
    }
}
