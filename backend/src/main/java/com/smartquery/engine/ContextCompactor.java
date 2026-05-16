package com.smartquery.engine;

import com.smartquery.llm.LlmService;
import com.smartquery.logging.ConversationEventLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 上下文压缩器 — 翻译 Claude Code compact.ts
 *
 * <p>策略:
 * <ul>
 *   <li>保留 system 消息 + 最近 N 轮完整消息</li>
 *   <li>旧消息使用 LLM 生成结构化摘要（失败时降级为截断）</li>
 *   <li>摘要保留: 关键决策、工具调用结果、数据探索结论</li>
 *   <li>压缩事件写入 JSONL 审计日志</li>
 * </ul>
 *
 * <p>触发机制: ReActEngine 使用 LLM API 返回的真实 token 数判断是否需要压缩，
 * 本类只负责执行压缩逻辑。
 */
@Slf4j
@Component
public class ContextCompactor {

    private final LlmService llmService;
    private final ConversationEventLogger eventLogger;

    @Value("${compactor.model:glm-4}")
    private String summarizationModel;

    @Value("${compactor.default-keep-recent-turns:4}")
    private int defaultKeepRecentTurns;

    @Value("${compactor.micro-compact-keep-turns:5}")
    private int microCompactKeepTurns;

    @Value("${compactor.max-summary-length:3000}")
    private int maxSummaryLength;

    private static final String SUMMARIZATION_PROMPT = """
        你是一个对话摘要助手。请将以下对话历史压缩为结构化摘要，保留以下关键信息：

        1. 用户的核心问题和意图
        2. 已执行的关键 SQL 查询（表名、条件、结果行数）
        3. 已执行的关键 Python 分析（算法、结论）
        4. 生成的可视化（图表类型、标题）
        5. 已创建的模型（算法、目标列、关键指标）
        6. 重要决策和中间结论

        用简洁的要点格式输出，不要输出多余内容。中文输出。
        """;

    public ContextCompactor(LlmService llmService, ConversationEventLogger eventLogger) {
        this.llmService = llmService;
        this.eventLogger = eventLogger;
    }

    /**
     * 压缩消息列表
     *
     * @param messages      当前消息列表
     * @param realTokenCount ReActEngine 中 LLM API 返回的真实 token 总数（用于日志）
     * @return 压缩后的消息列表
     */
    public List<Map<String, Object>> compact(List<Map<String, Object>> messages, int realTokenCount) {
        log.info("[COMPACT] triggering: realTokens={}, messages={}", realTokenCount, messages.size());

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
        int recentStart = Math.max(systemEnd, messages.size() - defaultKeepRecentTurns * 2);
        List<Map<String, Object>> oldMessages = messages.subList(systemEnd, recentStart);
        List<Map<String, Object>> recentMessages = messages.subList(recentStart, messages.size());

        // 旧消息生成摘要
        if (!oldMessages.isEmpty()) {
            String summary = buildLlmSummary(oldMessages);

            Map<String, Object> summaryMsg = new LinkedHashMap<>();
            summaryMsg.put("role", "system");
            summaryMsg.put("content", "以下是之前对话的摘要:\n" + summary);
            result.add(summaryMsg);
        }

        result.addAll(recentMessages);

        log.info("[COMPACT] completed: {} -> {} messages (realTokens={})",
            messages.size(), result.size(), realTokenCount);

        return result;
    }

    /**
     * 微压缩 — 选择性清除旧 tool_result 内容，避免触发昂贵的 LLM 全量摘要
     *
     * <p>策略（翻译自 Claude Code microCompact.ts）:
     * <ul>
     *   <li>保留 system 消息不变</li>
     *   <li>最近 N 轮的消息完全保留</li>
     *   <li>旧 tool_result 替换为简短摘要桩（保留行数、表名等关键信息）</li>
     *   <li>不调用 LLM，纯规则操作，毫秒级完成</li>
     * </ul>
     *
     * @param messages 当前消息列表
     * @return 压缩后的消息列表（可能和原始相同，表示无需压缩）
     */
    public List<Map<String, Object>> microCompact(List<Map<String, Object>> messages) {
        if (messages.size() <= microCompactKeepTurns * 2 + 2) {
            return messages;
        }

        int cutoffIndex = findCutoffIndex(messages);
        if (cutoffIndex <= 0) return messages;

        boolean changed = false;
        List<Map<String, Object>> result = new ArrayList<>(messages.size());

        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (i >= cutoffIndex) {
                result.add(msg);
                continue;
            }

            String role = String.valueOf(msg.get("role"));

            if ("tool".equals(role)) {
                String original = String.valueOf(msg.get("content"));
                String stub = buildToolResultStub(original);
                if (!stub.equals(original)) {
                    Map<String, Object> compacted = new LinkedHashMap<>(msg);
                    compacted.put("content", stub);
                    result.add(compacted);
                    changed = true;
                    continue;
                }
            }

            if ("assistant".equals(role)) {
                String original = String.valueOf(msg.get("content"));
                if (original != null && original.length() > 1500) {
                    String trimmed = trimAssistantContent(original);
                    if (trimmed.length() < original.length()) {
                        Map<String, Object> compacted = new LinkedHashMap<>(msg);
                        compacted.put("content", trimmed);
                        result.add(compacted);
                        changed = true;
                        continue;
                    }
                }
            }

            result.add(msg);
        }

        if (changed) {
            log.info("[MICRO-COMPACT] cleaned old tool results: {} messages processed", messages.size());
        }
        return changed ? result : messages;
    }

    private int findCutoffIndex(List<Map<String, Object>> messages) {
        int systemEnd = 0;
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).get("role"))) {
                systemEnd = i + 1;
            } else {
                break;
            }
        }
        return Math.max(systemEnd, messages.size() - microCompactKeepTurns * 2);
    }

    private String buildToolResultStub(String content) {
        if (content == null || "null".equals(content) || content.length() < 200) {
            return content;
        }

        StringBuilder stub = new StringBuilder("[旧结果已清除] ");

        // 提取关键信息：行数
        if (content.contains("行") || content.contains("rows")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*(行|rows|条)")
                .matcher(content);
            if (m.find()) stub.append(m.group()).append("; ");
        }

        // 提取表名
        java.util.regex.Matcher tableMatcher = java.util.regex.Pattern.compile(
            "(?:FROM|from|table|表)\\s+[`\"]?(\\w+)[`\"]?")
            .matcher(content);
        int tableCount = 0;
        while (tableMatcher.find() && tableCount < 2) {
            stub.append("表=").append(tableMatcher.group(1)).append("; ");
            tableCount++;
        }

        // 保留前100字符作为摘要
        String prefix = content.substring(0, Math.min(100, content.length()));
        if (!prefix.isBlank()) {
            stub.append("摘要: ").append(truncate(prefix, 80));
        }

        return stub.toString();
    }

    private String trimAssistantContent(String content) {
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        int keptLines = 0;
        int maxLines = 30;

        for (String line : lines) {
            if (line.startsWith("[SQL]") || line.startsWith("[Python]")
                || line.startsWith("[结果]") || line.startsWith("[图表]")
                || line.startsWith("[报告]") || line.startsWith("[仪表盘]")
                || line.startsWith("[输出]") || line.startsWith("[错误]")
                || line.startsWith("[模型]")) {
                sb.append(line).append("\n");
                keptLines++;
            } else if (keptLines < maxLines / 2) {
                sb.append(line).append("\n");
                keptLines++;
            }

            if (keptLines >= maxLines) {
                sb.append("...(内容已精简)");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 使用 LLM 生成结构化摘要，失败时降级为截断摘要
     */
    private String buildLlmSummary(List<Map<String, Object>> messages) {
        String rawContent = extractRawContent(messages);

        // 先尝试 LLM 摘要
        try {
            String summary = callLlmForSummary(rawContent);
            if (summary != null && !summary.isBlank()) {
                log.info("[COMPACT] LLM summary generated: {} chars", summary.length());
                return truncate(summary, maxSummaryLength);
            }
        } catch (Exception e) {
            log.warn("[COMPACT] LLM summarization failed, falling back to truncation: {}", e.getMessage());
        }

        // 降级: 使用截断摘要
        return buildTruncationSummary(messages);
    }

    private String callLlmForSummary(String rawContent) {
        if (!llmService.isAvailable(summarizationModel)) {
            return null;
        }

        String userContent = "对话历史:\n" + truncate(rawContent, 6000);
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", SUMMARIZATION_PROMPT),
            Map.of("role", "user", "content", userContent)
        );

        return llmService.chat(summarizationModel, messages);
    }

    private String extractRawContent(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.get("role"));
            String content = String.valueOf(msg.get("content"));
            if (content == null || "null".equals(content)) continue;

            switch (role) {
                case "user" -> sb.append("用户: ").append(truncate(content, 300)).append("\n");
                case "assistant" -> {
                    String extracted = extractToolContext(content);
                    if (!extracted.isEmpty()) {
                        sb.append("助手: ").append(extracted).append("\n");
                    }
                }
                case "tool" -> sb.append("工具结果: ").append(truncate(content, 200)).append("\n");
            }

            if (sb.length() > 8000) break;
        }
        return sb.toString();
    }

    private String buildTruncationSummary(List<Map<String, Object>> messages) {
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

            if (sb.length() > maxSummaryLength) {
                sb.append("...(更多历史已省略)");
                break;
            }
        }
        return sb.toString();
    }

    private String extractToolContext(String content) {
        if (content == null || content.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            if (line.startsWith("[SQL]") || line.startsWith("[Python]") || line.startsWith("[结果]")
                || line.startsWith("[图表]") || line.startsWith("[报告]") || line.startsWith("[仪表盘]")
                || line.startsWith("[输出]") || line.startsWith("[错误]")) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(truncate(line.strip(), 120));
            }
        }
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
}
