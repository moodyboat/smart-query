package com.smartquery.service;

import com.smartquery.llm.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话摘要服务
 *
 * 功能：
 * 1. 长对话自动摘要 - 保留关键决策（表名、列名、模型参数、SQL结果）
 * 2. Token 预算控制 - 超出预算时生成摘要替换早期消息
 * 3. 摘要缓存 - 避免 LLM 重复生成相同摘要
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSummaryService {

    private final LlmService llmService;

    @Value("${smart-query.summary.enabled:true}")
    private boolean summaryEnabled;

    @Value("${smart-query.summary.model:#{null}}")
    private String summaryModel;

    @Value("${smart-query.summary.max-tokens:2000}")
    private int summaryMaxTokens;

    @Value("${smart-query.summary.cache-ttl-minutes:30}")
    private int cacheTtlMinutes;

    // 摘要缓存：conversationId -> (timestamp, summary)
    private final Map<Long, CacheEntry> summaryCache = new ConcurrentHashMap<>();

    private static final int CHARS_PER_TOKEN = 4;

    /**
     * 为对话历史生成摘要
     *
     * @param conversationId 会话ID
     * @param messages 需要摘要的消息列表
     * @param model 默认模型（如果 summaryModel 未配置）
     * @return 摘要文本，如果摘要失败返回 null
     */
    public String generateSummary(Long conversationId, List<Map<String, Object>> messages, String model) {
        if (!summaryEnabled || messages == null || messages.isEmpty()) {
            return null;
        }

        // 检查缓存
        CacheEntry cached = summaryCache.get(conversationId);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.timestamp) < cacheTtlMinutes * 60 * 1000L) {
            log.debug("[SUMMARY] Using cached summary for conversation {}", conversationId);
            return cached.summary;
        }

        try {
            String summaryModelToUse = summaryModel != null ? summaryModel : model;
            log.info("[SUMMARY] Generating summary for conversation {} with {} messages using model {}",
                conversationId, messages.size(), summaryModelToUse);

            // 构建摘要提示词
            String summaryPrompt = buildSummaryPrompt(messages);

            // 调用 LLM 生成摘要
            List<Map<String, String>> llmMessages = new ArrayList<>();

            Map<String, String> systemMsg = new LinkedHashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", "你是一个智能对话助手，专门负责总结数据分析对话的历史记录。");
            llmMessages.add(systemMsg);

            Map<String, String> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", summaryPrompt);
            llmMessages.add(userMsg);

            String summary = llmService.chat(summaryModelToUse, llmMessages);

            if (summary != null && !summary.isBlank()) {
                // 缓存摘要
                summaryCache.put(conversationId, new CacheEntry(now, summary));
                log.info("[SUMMARY] Generated summary ({} chars) for conversation {}",
                    summary.length(), conversationId);
                return summary;
            }

            log.warn("[SUMMARY] Empty summary returned for conversation {}", conversationId);
            return null;

        } catch (Exception e) {
            log.error("[SUMMARY] Failed to generate summary for conversation {}: {}",
                conversationId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 构建摘要提示词
     */
    private String buildSummaryPrompt(List<Map<String, Object>> messages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请简洁地总结以下数据分析对话的历史记录，重点保留：\n");
        prompt.append("1. 讨论过的表名和列名\n");
        prompt.append("2. 生成的 SQL 查询及其结果概要\n");
        prompt.append("3. 模型训练参数和特征工程决策\n");
        prompt.append("4. 重要的业务结论和洞察\n");
        prompt.append("5. 用户明确要求的格式或偏好\n\n");
        prompt.append("对话记录：\n");

        // 限制输入长度，避免超过模型上下文
        int maxInputChars = summaryMaxTokens * CHARS_PER_TOKEN / 2; // 为输出留空间
        int currentChars = 0;

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            String content = msg.get("content") != null ? msg.get("content").toString() : "";

            // 简化消息内容
            if (content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }

            String msgText = "[" + role + "]: " + content + "\n\n";

            if (currentChars + msgText.length() > maxInputChars) {
                prompt.append("...(部分历史记录已省略)\n");
                break;
            }

            prompt.append(msgText);
            currentChars += msgText.length();
        }

        prompt.append("\n请用简洁的中文总结上述对话，突出关键信息。");
        return prompt.toString();
    }

    /**
     * 获取缓存大小（供管理接口使用）
     */
    public int getCacheSize() {
        return summaryCache.size();
    }

    /**
     * 检查摘要功能是否启用
     */
    public boolean isEnabled() {
        return summaryEnabled;
    }

    /**
     * 清除过期的缓存条目
     */
    public void evictExpiredCache() {
        long now = System.currentTimeMillis();
        long ttlMs = cacheTtlMinutes * 60 * 1000L;

        summaryCache.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue().timestamp) > ttlMs;
            if (expired) {
                log.debug("[SUMMARY] Evicted expired cache for conversation {}", entry.getKey());
            }
            return expired;
        });
    }

    /**
     * 清除指定会话的摘要缓存
     */
    public void evictCache(Long conversationId) {
        summaryCache.remove(conversationId);
        log.debug("[SUMMARY] Evicted cache for conversation {}", conversationId);
    }

    /**
     * 估算消息列表的 token 数
     */
    public int estimateTokens(List<Map<String, Object>> messages) {
        int totalChars = 0;
        for (Map<String, Object> msg : messages) {
            String content = msg.get("content") != null ? msg.get("content").toString() : "";
            totalChars += content.length();
        }
        return totalChars / CHARS_PER_TOKEN;
    }

    /**
     * 应用摘要策略：保留最近 N 条完整消息 + 早期消息摘要
     *
     * @param messages 完整消息列表
     * @param maxTokens 最大 token 预算
     * @param keepRecent 保留最近消息数
     * @param conversationId 会话ID（用于缓存摘要）
     * @param model 默认模型
     * @return 处理后的消息列表（包含摘要消息）
     */
    public List<Map<String, Object>> applySummaryStrategy(
            List<Map<String, Object>> messages,
            int maxTokens,
            int keepRecent,
            Long conversationId,
            String model) {

        int estimatedTokens = estimateTokens(messages);

        // 如果在预算内，直接返回
        if (estimatedTokens <= maxTokens || messages.size() <= keepRecent) {
            return messages;
        }

        log.info("[SUMMARY] Token budget exceeded: ~{} tokens > {}, messages: {}",
            estimatedTokens, maxTokens, messages.size());

        // 分割为早期消息和最近消息
        int recentStart = Math.max(0, messages.size() - keepRecent);
        List<Map<String, Object>> earlyMessages = messages.subList(0, recentStart);
        List<Map<String, Object>> recentMessages = messages.subList(recentStart, messages.size());

        // 为早期消息生成摘要
        String summary = generateSummary(conversationId, earlyMessages, model);

        List<Map<String, Object>> result = new ArrayList<>();

        if (summary != null && !summary.isBlank()) {
            // 添加摘要作为 system 消息
            Map<String, Object> summaryMsg = new LinkedHashMap<>();
            summaryMsg.put("role", "system");
            summaryMsg.put("content", buildSummaryMessage(summary, earlyMessages.size()));
            result.add(summaryMsg);
            log.info("[SUMMARY] Replaced {} early messages with summary ({} chars)",
                earlyMessages.size(), summary.length());
        } else {
            // 摘要失败，保留截断的早期消息
            log.warn("[SUMMARY] Summary generation failed, keeping truncated early messages");
            for (Map<String, Object> msg : earlyMessages) {
                Map<String, Object> truncated = new LinkedHashMap<>(msg);
                String content = msg.get("content") != null ? msg.get("content").toString() : "";
                if (content.length() > 200) {
                    truncated.put("content", content.substring(0, 200) + "...(已截断)");
                }
                result.add(truncated);
            }
        }

        // 添加完整的最近消息
        result.addAll(recentMessages);

        return result;
    }

    /**
     * 构建摘要消息
     */
    private String buildSummaryMessage(String summary, int originalMessageCount) {
        return String.format(
            "[对话摘要] 以下是基于之前 %d 条消息的摘要：\n\n%s\n\n" +
            "你可以参考这些历史信息，但优先关注最近的消息。如果需要更详细的历史信息，请明确指出。",
            originalMessageCount, summary
        );
    }

    /**
     * 缓存条目
     */
    private static class CacheEntry {
        final long timestamp;
        final String summary;

        CacheEntry(long timestamp, String summary) {
            this.timestamp = timestamp;
            this.summary = summary;
        }
    }
}
