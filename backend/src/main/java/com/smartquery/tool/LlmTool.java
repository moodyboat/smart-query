package com.smartquery.tool;

import java.util.List;
import java.util.Map;

/**
 * LLM 工具接口 — 直译 Claude Code Tool.ts
 *
 * <p>翻译对照:
 * <pre>
 * TS: type Tool&lt;Input, Output, P&gt; = {
 *   name: string
 *   description: () => string | Promise&lt;string&gt;
 *   inputSchema: Schema
 *   call: (input, context) => Promise&lt;Output&gt;
 *   isEnabled?: () => boolean
 *   isConcurrencySafe?: () => boolean
 *   isReadOnly?: () => boolean
 *   isDestructive?: () => boolean
 * }
 * Java: LlmTool interface with matching methods
 * </pre>
 */
public interface LlmTool {

    /** 工具名称 (对应 TS name) */
    String getName();

    /** 工具描述 (对应 TS description) */
    String getDescription();

    /** JSON Schema 输入定义 (对应 TS inputSchema) */
    Map<String, Object> getJsonSchema();

    /** 执行工具 (对应 TS call()) */
    ToolResult execute(Map<String, Object> input, ToolExecutionContext context);

    /** 是否启用 (对应 TS isEnabled) */
    default boolean isEnabled() { return true; }

    /**
     * 是否并发安全 (对应 TS isConcurrencySafe)
     * 翻译 Tool.ts buildTool() 默认值: isConcurrencySafe → false
     */
    default boolean isConcurrencySafe() { return false; }

    /**
     * 是否只读 (对应 TS isReadOnly)
     * 翻译 Tool.ts buildTool() 默认值: isReadOnly → false
     */
    default boolean isReadOnly() { return false; }

    /** 是否破坏性 (对应 TS isDestructive) */
    default boolean isDestructive() { return false; }

    /** 是否需要数据库连接 */
    default boolean requireDatabase() { return true; }

    /** 超时时间(ms) */
    default long getTimeoutMs() { return 30000; }
}
