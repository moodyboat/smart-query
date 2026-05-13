package com.smartquery.prompt;

/**
 * 提示词段 — 直译 Claude Code systemPromptSection()
 *
 * <p>翻译对照:
 * <pre>
 * TS: systemPromptSection(name, () => content) / DANGEROUS_uncachedSystemPromptSection(name, () => content, reason)
 * Java: PromptSection record with name, content, cacheable
 * </pre>
 */
public record PromptSection(
    String name,
    String content,
    boolean cacheable
) {
    public static PromptSection cached(String name, String content) {
        return new PromptSection(name, content, true);
    }

    public static PromptSection uncached(String name, String content) {
        return new PromptSection(name, content, false);
    }
}
