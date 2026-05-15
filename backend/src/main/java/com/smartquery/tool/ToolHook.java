package com.smartquery.tool;

import java.util.Map;

/**
 * 工具 Hook — 适配 Claude Code PreToolUse/PostToolUse
 *
 * <p>Hook 可以:
 * <ul>
 *   <li>修改工具参数 (beforeToolCall)</li>
 *   <li>阻止工具执行 (beforeToolCall 返回 false)</li>
 *   <li>记录日志 (afterToolCall)</li>
 *   <li>修改返回结果 (afterToolCall)</li>
 * </ul>
 */
public interface ToolHook {

    /**
     * Hook 名称
     */
    String name();

    /**
     * Hook 优先级 (数值越小越先执行)
     */
    default int order() {
        return 100;
    }

    /**
     * 工具执行前调用
     *
     * @param toolName 工具名称
     * @param input    工具参数 (可修改)
     * @param context  执行上下文
     * @return true 允许执行, false 阻止执行
     */
    default boolean beforeToolCall(String toolName, Map<String, Object> input, ToolExecutionContext context) {
        return true;
    }

    /**
     * 工具执行后调用
     *
     * @param toolName 工具名称
     * @param input    工具参数
     * @param result   执行结果 (可修改)
     * @param context  执行上下文
     */
    default void afterToolCall(String toolName, Map<String, Object> input, ToolResult result, ToolExecutionContext context) {
    }
}
