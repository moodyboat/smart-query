package com.smartquery.llm;

/**
 * LLM 流式响应块 — 适配 Claude Code SSE 事件
 *
 * <p>翻译对照:
 * <pre>
 * TS: content_block_start/content_block_delta/message_delta 等 SSE 事件
 * Java: LlmChunk record 统一封装
 * </pre>
 */
public record LlmChunk(
    String type,
    String text,
    String toolCallId,
    String toolName,
    String toolInputJson,
    String finishReason,
    int inputTokens,
    int outputTokens
) {
    public static LlmChunk text(String text) {
        return new LlmChunk("text", text, null, null, null, null, 0, 0);
    }

    public static LlmChunk toolCall(String toolCallId, String toolName, String toolInputJson) {
        return new LlmChunk("tool_call", null, toolCallId, toolName, toolInputJson, null, 0, 0);
    }

    public static LlmChunk done(String finishReason, int inputTokens, int outputTokens) {
        return new LlmChunk("done", null, null, null, null, finishReason, inputTokens, outputTokens);
    }

    public boolean isToolCall() {
        return "tool_call".equals(type);
    }

    public boolean isDone() {
        return "done".equals(type);
    }

    public boolean isText() {
        return "text".equals(type);
    }
}
