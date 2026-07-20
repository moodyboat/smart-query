package com.smartquery.prompt;

/**
 * 提示词段 — 增强版，支持优先级和条件注入
 *
 * <p>翻译对照:
 * <pre>
 * TS: systemPromptSection(name, () => content) / DANGEROUS_uncachedSystemPromptSection(name, () => content, reason)
 * Java: PromptSection record with name, content, priority, condition
 * </pre>
 */
public record PromptSection(
    String name,
    String content,
    boolean cacheable,
    PromptPriority priority,
    PromptCondition condition,
    int tokenBudget
) {
    public static PromptSection of(PromptPriority priority, String name, String content) {
        return new PromptSection(name, content, true, priority, null, 0);
    }

    public static PromptSection conditional(PromptPriority priority, String name, String content, PromptCondition condition) {
        return new PromptSection(name, content, false, priority, condition, 0);
    }

    public boolean shouldInject(PromptContext ctx) {
        return condition == null || condition.test(ctx);
    }
}
