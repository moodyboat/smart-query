package com.smartquery.tool.impl;

import com.smartquery.datasource.DataSourceContextHolder;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL 执行工具 — 复用智慧监督 ExecuteSqlTool 的核心逻辑
 *
 * <p>复用内容:
 * <ul>
 *   <li>{{filter.xxx}} 占位符替换</li>
 *   <li>多 SQL 支持 (;; 分隔)</li>
 *   <li>auto-LIMIT</li>
 *   <li>批量执行</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecuteSqlTool implements LlmTool {

    private final DataSourceManager dataSourceManager;
    private final SqlSafetyValidator safetyValidator;

    @Override
    public String getName() { return "execute_sql"; }

    @Override
    public String getDescription() {
        return "执行 SQL 查询并返回结果。支持 SELECT / SHOW / DESCRIBE / EXPLAIN 语句。";
    }

    @Override
    public Map<String, Object> getJsonSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "sql", Map.of("type", "string", "description", "要执行的 SQL 语句"),
                "data_source_id", Map.of("type", "integer", "description", "数据源 ID (可选，使用会话默认)")
            ),
            "required", List.of("sql")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolExecutionContext context) {
        long start = System.currentTimeMillis();
        String sql = (String) input.get("sql");
        Long dsId = input.get("data_source_id") != null
            ? ((Number) input.get("data_source_id")).longValue()
            : context.dataSourceId();

        if (sql == null || sql.isBlank()) {
            return ToolResult.error(getName(), "SQL 不能为空", System.currentTimeMillis() - start);
        }

        SqlSafetyValidator.ValidationResult validation = safetyValidator.validate(sql);
        if (!validation.safe()) {
            return ToolResult.error(getName(), "SQL 安全检查未通过: " + validation.reason(), System.currentTimeMillis() - start);
        }

        try {
            sql = applyAutoLimit(sql);
            sql = replaceFilterPlaceholders(sql, input);
            // Strip any remaining {{filter.xxx}} placeholders the LLM added for future filtering
            sql = sql.replaceAll(" ?\\{\\{filter\\.[^}]+}}", "");

            DataSourceContextHolder.set(dsId);
            JdbcTemplate jdbc = dataSourceManager.getJdbcTemplate(dsId);
            jdbc.setQueryTimeout(30);

            List<Map<String, Object>> rows = jdbc.queryForList(sql);
            long duration = System.currentTimeMillis() - start;

            log.info("[TOOL] execute_sql: duration={}ms, rows={}, sql={}", duration, rows.size(), sql.substring(0, Math.min(sql.length(), 200)));

            String output = formatResult(rows);
            return ToolResult.ok(getName(), output, duration, rows);
        } catch (Exception e) {
            log.error("[TOOL] execute_sql error: {}", e.getMessage());
            return ToolResult.error(getName(), "SQL 执行错误: " + e.getMessage(), System.currentTimeMillis() - start);
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public boolean isConcurrencySafe() { return true; }

    private static final java.util.regex.Pattern LIMIT_PATTERN =
        java.util.regex.Pattern.compile("\\bLIMIT\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    private String applyAutoLimit(String sql) {
        if (!LIMIT_PATTERN.matcher(sql.trim()).find()) {
            return sql + " LIMIT 1000";
        }
        return sql;
    }

    @SuppressWarnings("unchecked")
    private String replaceFilterPlaceholders(String sql, Map<String, Object> input) {
        Map<String, String> filters = (Map<String, String>) input.get("filters");
        if (filters == null) return sql;
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            sql = sql.replace("{{filter." + entry.getKey() + "}}", entry.getValue());
        }
        return sql;
    }

    private String formatResult(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "查询结果为空";
        StringBuilder sb = new StringBuilder();
        sb.append("查询返回 ").append(rows.size()).append(" 行:\n\n");

        Set<String> columns = rows.get(0).keySet();
        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
        sb.append("| ").append(columns.stream().map(c -> "---").collect(java.util.stream.Collectors.joining(" | "))).append(" |\n");

        int maxRows = Math.min(rows.size(), 50);
        for (int i = 0; i < maxRows; i++) {
            Map<String, Object> row = rows.get(i);
            sb.append("| ");
            for (String col : columns) {
                Object val = row.get(col);
                sb.append(val != null ? val.toString() : "NULL").append(" | ");
            }
            sb.append("\n");
        }
        if (rows.size() > maxRows) {
            sb.append("\n... 还有 ").append(rows.size() - maxRows).append(" 行未显示");
        }
        return sb.toString();
    }
}
