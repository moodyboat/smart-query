package com.smartquery.service;

import com.smartquery.dto.DataSourceUsageStats;
import com.smartquery.entity.Conversation;
import com.smartquery.entity.DataSource;
import com.smartquery.mapper.ConversationMapper;
import com.smartquery.mapper.DataSourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceUsageService {

    private final ConversationMapper conversationMapper;
    private final DataSourceMapper dataSourceMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 获取数据源使用统计
     */
    public List<DataSourceUsageStats> getUsageStats(String timeRange) {
        String timeCondition = getTimeCondition(timeRange);

        String sql = """
            SELECT
                ds.id AS dataSourceId,
                ds.name AS dataSourceName,
                COUNT(DISTINCT c.id) AS totalQueries,
                SUM(CASE WHEN c.status = 1 THEN 1 ELSE 0 END) AS activeQueries,
                MAX(c.updated_at) AS lastUsedAt,
                AVG(CASE
                    WHEN qh.execution_time_ms IS NOT NULL THEN qh.execution_time_ms
                    ELSE 0
                END) AS avgQueryTimeMs
            FROM sq_data_source ds
            LEFT JOIN sq_conversation c ON ds.id = c.data_source_id AND c.deleted = 0
            LEFT JOIN sq_query_history qh ON c.id = qh.conversation_id
                AND qh.status = 'success'
                AND qh.created_at >= DATE_SUB(NOW(), INTERVAL ?)
            WHERE ds.deleted = 0
            GROUP BY ds.id, ds.name
            ORDER BY totalQueries DESC, lastUsedAt DESC
            """;

        String interval = getTimeIntervalSql(timeRange);
        String finalSql = sql.replace("INTERVAL ?", "INTERVAL " + interval);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(finalSql);

        List<DataSourceUsageStats> stats = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long totalQueries = ((Number) row.get("totalQueries")).longValue();
            Long activeQueries = ((Number) row.getOrDefault("activeQueries", 0)).longValue();
            Object avgQueryTimeObj = row.get("avgQueryTimeMs");
            Long avgQueryTime = null;
            if (avgQueryTimeObj != null) {
                avgQueryTime = ((Number) avgQueryTimeObj).longValue();
            }

            Long lastUsedAt = null;
            Object lastUsedAtObj = row.get("lastUsedAt");
            if (lastUsedAtObj != null) {
                if (lastUsedAtObj instanceof java.sql.Timestamp) {
                    lastUsedAt = ((java.sql.Timestamp) lastUsedAtObj).getTime();
                } else if (lastUsedAtObj instanceof java.time.LocalDateTime) {
                    lastUsedAt = ((java.time.LocalDateTime) lastUsedAtObj)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
                }
            }

            stats.add(DataSourceUsageStats.builder()
                .dataSourceId(((Number) row.get("dataSourceId")).longValue())
                .dataSourceName((String) row.get("dataSourceName"))
                .totalQueries(totalQueries)
                .successfulQueries(activeQueries)
                .failedQueries(totalQueries - activeQueries)
                .successRate(totalQueries > 0 ? (activeQueries * 100.0 / totalQueries) : 0)
                .lastUsedAt(lastUsedAt)
                .avgQueryTimeMs(avgQueryTime)
                .timeRange(timeRange)
                .calculatedAt(LocalDateTime.now())
                .build());
        }

        return stats;
    }

    /**
     * 获取最常用的数据源
     */
    public List<DataSource> getMostUsedDataSources(int limit, String timeRange) {
        List<DataSourceUsageStats> stats = getUsageStats(timeRange);
        List<Long> topIds = stats.stream()
            .limit(limit)
            .map(DataSourceUsageStats::getDataSourceId)
            .toList();

        if (topIds.isEmpty()) {
            return Collections.emptyList();
        }

        return dataSourceMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataSource>()
                .in(DataSource::getId, topIds)
                .eq(DataSource::getDeleted, 0)
                .orderBy(true, true, DataSource::getId)
        );
    }

    /**
     * 记录数据源使用
     */
    public void recordUsage(Long dataSourceId, boolean success, long executionTimeMs) {
        // 可以通过 sq_query_history 表自动统计，这里可以添加额外的统计逻辑
        // 例如：记录到 Redis 缓存中以便实时查询
        log.debug("Recorded usage for datasource {}: success={}, time={}ms",
            dataSourceId, success, executionTimeMs);
    }

    private String getTimeCondition(String timeRange) {
        return switch (timeRange.toLowerCase()) {
            case "daily" -> "1 DAY";
            case "weekly" -> "7 DAY";
            case "monthly" -> "30 DAY";
            default -> "7 DAY";
        };
    }

    private String getTimeIntervalSql(String timeRange) {
        return switch (timeRange.toLowerCase()) {
            case "daily" -> "1 DAY";
            case "weekly" -> "7 DAY";
            case "monthly" -> "30 DAY";
            default -> "7 DAY";
        };
    }
}
