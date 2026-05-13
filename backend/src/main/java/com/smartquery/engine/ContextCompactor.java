package com.smartquery.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 上下文压缩器 — 简化翻译 Claude Code compact.ts
 *
 * <p>翻译对照:
 * <pre>
 * TS: compact.ts → compactConversation()
 *   - token 超限时自动压缩历史消息为摘要
 *   - 保留最近 N 轮完整消息
 *   - 使用 LLM 生成摘要（简化版用截断替代）
 * Java: ContextCompactor → compact()
 *   - 按 token 估算值触发压缩
 *   - 保留 system + 最近 N 轮
 *   - 旧消息替换为摘要
 * </pre>
 */
@Slf4j
@Component
public class ContextCompactor {

    private static final int DEFAULT_KEEP_RECENT_TURNS = 4;
    private static final int ESTIMATED_CHARS_PER_TOKEN = 4;
    private static final int MAX_SUMMARY_LENGTH = 2000;

    /**
     * 压缩消息列表（翻译 compactConversation）
     *
     * @param messages  当前消息列表
     * @param tokenBudget token 预算
     * @return 压缩后的消息列表
     */
    public List<Map<String, Object>> compact(
        List<Map<String, Object>> messages,
        int tokenBudget
    ) {
        int estimatedTokens = estimateTokens(messages);
        if (estimatedTokens <= tokenBudget) {
            return messages;
        }

        log.info("[COMPACT] triggering: estimatedTokens={}, budget={}", estimatedTokens, tokenBudget);

        List<Map<String, Object>> result = new ArrayList<>();
        int systemEnd = 0;

        // 保留 system 消息
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).get("role"))) {
                result.add(messages.get(i));
                systemEnd = i + 1;
            } else {
                break;
            }
        }

        // 保留最近 N 轮
        int recentStart = Math.max(systemEnd, messages.size() - DEFAULT_KEEP_RECENT_TURNS * 2);
        List<Map<String, Object>> oldMessages = messages.subList(systemEnd, recentStart);
        List<Map<String, Object>> recentMessages = messages.subList(recentStart, messages.size());

        // 旧消息生成摘要
        if (!oldMessages.isEmpty()) {
            String summary = buildSummary(oldMessages);
            Map<String, Object> summaryMsg = new LinkedHashMap<>();
            summaryMsg.put("role", "system");
            summaryMsg.put("content", "以下是之前对话的摘要:\n" + summary);
            result.add(summaryMsg);
        }

        result.addAll(recentMessages);

        int newTokens = estimateTokens(result);
        log.info("[COMPACT] completed: {} -> {} messages, {} -> {} estimated tokens",
            messages.size(), result.size(), estimatedTokens, newTokens);

        return result;
    }

    /**
     * 检查是否需要压缩
     */
    public boolean needsCompaction(List<Map<String, Object>> messages, int tokenBudget) {
        return estimateTokens(messages) > tokenBudget;
    }

    private String buildSummary(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.get("role"));
            String content = String.valueOf(msg.get("content"));
            if (content == null || "null".equals(content)) continue;

            switch (role) {
                case "user" -> {
                    sb.append("用户: ").append(truncate(content, 150)).append("\n");
                }
                case "assistant" -> {
                    String extracted = extractToolContext(content);
                    if (!extracted.isEmpty()) {
                        sb.append("助手: ").append(extracted).append("\n");
                    }
                }
                case "tool" -> {
                    sb.append("工具结果: ").append(truncate(content, 100)).append("\n");
                }
            }

            if (sb.length() > MAX_SUMMARY_LENGTH) {
                sb.append("...(更多历史已省略)");
                break;
            }
        }
        return sb.toString();
    }

    private String extractToolContext(String content) {
        if (content == null || content.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        // Extract [SQL], [Python], [图表], [报告], [仪表盘] markers from enriched content
        for (String line : content.split("\n")) {
            if (line.startsWith("[SQL]") || line.startsWith("[Python]") || line.startsWith("[结果]")
                || line.startsWith("[图表]") || line.startsWith("[报告]") || line.startsWith("[仪表盘]")
                || line.startsWith("[输出]") || line.startsWith("[错误]")) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(truncate(line.strip(), 120));
            }
        }
        // If no markers found, show first 150 chars of text content
        if (sb.isEmpty()) {
            String textOnly = content.replaceAll("\\[\\w+\\]", "").strip();
            if (!textOnly.isEmpty()) {
                sb.append(truncate(textOnly, 150));
            }
        }
        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private int estimateTokens(List<Map<String, Object>> messages) {
        int chars = 0;
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (content != null) {
                chars += String.valueOf(content).length();
            }
            Object toolCalls = msg.get("tool_calls");
            if (toolCalls != null) {
                chars += String.valueOf(toolCalls).length();
            }
        }
        return chars / ESTIMATED_CHARS_PER_TOKEN;
    }
}
