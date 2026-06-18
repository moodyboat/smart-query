package com.smartquery.prompt;

/**
 * 提示词注入上下文 — 用于条件判断
 */
public record PromptContext(
    boolean hasDataSource,
    boolean hasMiningModel,
    boolean isStreaming,
    String model,
    Long dataSourceId
) {
    public static PromptContext of(boolean hasDataSource, String model, Long dataSourceId) {
        return new PromptContext(hasDataSource, false, true, model, dataSourceId);
    }

    public static PromptContext of(boolean hasDataSource, boolean hasMiningModel, String model, Long dataSourceId) {
        return new PromptContext(hasDataSource, hasMiningModel, true, model, dataSourceId);
    }
}
