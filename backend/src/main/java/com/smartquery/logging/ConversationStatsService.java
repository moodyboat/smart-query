package com.smartquery.logging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话统计服务 — 聚合 token 使用、工具调用耗时、成功率
 *
 * <p>翻译 Claude Code analytics/telemetry:
 * 每次对话结束记录摘要，提供管理 API 聚合查询。
 */
@Slf4j
@Component
public class ConversationStatsService {

    private final CostTracker costTracker;
    private final Map<String, ToolMetric> toolMetrics = new ConcurrentHashMap<>();
    private final Map<LocalDate, DailySummary> dailySummaries = new ConcurrentHashMap<>();

    public ConversationStatsService(CostTracker costTracker) {
        this.costTracker = costTracker;
    }

    public record ToolMetric(
        String toolName,
        long totalCalls,
        long successCount,
        long failureCount,
        long totalDurationMs
    ) {
        public ToolMetric record(boolean success, long durationMs) {
            return new ToolMetric(toolName,
                totalCalls + 1,
                successCount + (success ? 1 : 0),
                failureCount + (success ? 0 : 1),
                totalDurationMs + durationMs);
        }
    }

    public record DailySummary(
        LocalDate date,
        int conversationCount,
        long totalTokens,
        double totalCost,
        int trainingCount
    ) {
        public DailySummary addTokens(long tokens, double cost) {
            return new DailySummary(date, conversationCount,
                totalTokens + tokens, totalCost + cost, trainingCount);
        }

        public DailySummary addConversation() {
            return new DailySummary(date, conversationCount + 1,
                totalTokens, totalCost, trainingCount);
        }

        public DailySummary addTraining() {
            return new DailySummary(date, conversationCount,
                totalTokens, totalCost, trainingCount + 1);
        }
    }

    public void recordToolCall(String toolName, boolean success, long durationMs) {
        toolMetrics.compute(toolName, (k, existing) ->
            (existing != null ? existing : new ToolMetric(toolName, 0, 0, 0, 0))
                .record(success, durationMs));
    }

    public void recordConversationComplete(Long conversationId, int totalTokens, double cost) {
        LocalDate today = LocalDate.now();
        dailySummaries.compute(today, (d, existing) ->
            (existing != null ? existing : new DailySummary(d, 0, 0, 0.0, 0))
                .addConversation()
                .addTokens(totalTokens, cost));
    }

    public void recordTraining() {
        LocalDate today = LocalDate.now();
        dailySummaries.compute(today, (d, existing) ->
            (existing != null ? existing : new DailySummary(d, 0, 0, 0.0, 0))
                .addTraining());
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("totalCost", costTracker.getTotalCost());
        stats.put("totalTokens", costTracker.getTotalTokens());
        stats.put("modelUsage", costTracker.getModelUsageMap());

        List<Map<String, Object>> toolStats = new ArrayList<>();
        toolMetrics.forEach((name, m) -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("toolName", name);
            t.put("totalCalls", m.totalCalls());
            t.put("successRate", m.totalCalls() > 0
                ? String.format("%.1f%%", m.successCount() * 100.0 / m.totalCalls()) : "N/A");
            t.put("avgDurationMs", m.totalCalls() > 0
                ? m.totalDurationMs() / m.totalCalls() : 0);
            toolStats.add(t);
        });
        stats.put("toolMetrics", toolStats);

        stats.put("dailySummaries", dailySummaries);

        return stats;
    }
}
