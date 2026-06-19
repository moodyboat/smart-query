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
 * 表结构探索工具 — 查询数据库 schema 信息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaExploreTool implements LlmTool {

    private final DataSourceManager dataSourceManager;

    @org.springframework.beans.factory.annotation.Value("${smart-query.schema.explore-sample-rows:5}")
    private int exploreSampleRows;

    @Override
    public String getName() { return "schema_explore"; }

    @Override
    public String getDescription() {
        return "探索数据库表结构。可以列出所有表、查看表字段和注释、查看索引等。";
    }

    @Override
    public Map<String, Object> getJsonSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "action", Map.of("type", "string", "enum", List.of("list_tables", "describe_table", "sample_data"),
                    "description", "操作类型: list_tables-列出所有表, describe_table-查看表结构, sample_data-查看样例数据"),
                "table_name", Map.of("type", "string", "description", "表名 (describe_table/sample_data 时必填)"),
                "data_source_id", Map.of("type", "integer", "description", "数据源 ID")
            ),
            "required", List.of("action")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolExecutionContext context) {
        long start = System.currentTimeMillis();
        String action = (String) input.get("action");
        if (action == null || action.isBlank()) {
            action = "list_tables";  // 默认列所有表（避免 switch(null) NPE）
        }
        Long dsId = input.get("data_source_id") != null
            ? ((Number) input.get("data_source_id")).longValue()
            : context.dataSourceId();

        try {
            DataSourceContextHolder.set(dsId);
            JdbcTemplate jdbc = dataSourceManager.getJdbcTemplate(dsId);

            String result = switch (action) {
                case "list_tables" -> listTables(jdbc);
                case "describe_table" -> describeTable(jdbc, (String) input.get("table_name"));
                case "sample_data" -> sampleData(jdbc, (String) input.get("table_name"));
                default -> "未知的 action: " + action;
            };

            return ToolResult.ok(getName(), result, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.error(getName(), "Schema 探索错误: " + e.getMessage(), System.currentTimeMillis() - start);
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public boolean isConcurrencySafe() { return true; }

    private String listTables(JdbcTemplate jdbc) {
        List<Map<String, Object>> tables = jdbc.queryForList(
            "SELECT TABLE_NAME, TABLE_COMMENT, TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME"
        );
        StringBuilder sb = new StringBuilder("数据库表列表:\n\n");
        sb.append("| 表名 | 注释 | 行数 |\n| --- | --- | --- |\n");
        for (Map<String, Object> row : tables) {
            sb.append("| ").append(row.get("TABLE_NAME"))
              .append(" | ").append(row.get("TABLE_COMMENT"))
              .append(" | ").append(row.get("TABLE_ROWS"))
              .append(" |\n");
        }
        return sb.toString();
    }

    private String describeTable(JdbcTemplate jdbc, String tableName) {
        List<Map<String, Object>> columns = jdbc.queryForList(
            "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_COMMENT, EXTRA " +
            "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
            tableName
        );
        StringBuilder sb = new StringBuilder("表 `").append(tableName).append("` 结构:\n\n");
        sb.append("| 字段名 | 类型 | 可空 | 键 | 注释 | 额外 |\n| --- | --- | --- | --- | --- | --- |\n");
        for (Map<String, Object> row : columns) {
            sb.append("| ").append(row.get("COLUMN_NAME"))
              .append(" | ").append(row.get("COLUMN_TYPE"))
              .append(" | ").append(row.get("IS_NULLABLE"))
              .append(" | ").append(row.get("COLUMN_KEY"))
              .append(" | ").append(row.get("COLUMN_COMMENT"))
              .append(" | ").append(row.get("EXTRA"))
              .append(" |\n");
        }
        return sb.toString();
    }

    private String sampleData(JdbcTemplate jdbc, String tableName) {
        // Validate table name against actual tables to prevent injection
        if (!isValidTable(jdbc, tableName)) {
            return "表 `" + tableName + "` 不存在";
        }
        String safeName = tableName.replaceAll("[^a-zA-Z0-9_.]", "");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + safeName + " LIMIT " + exploreSampleRows);
        if (rows.isEmpty()) return "表 `" + tableName + "` 无数据";
        StringBuilder sb = new StringBuilder("表 `").append(tableName).append("` 样例数据 (前").append(exploreSampleRows).append("行):\n\n");
        Set<String> columns = rows.get(0).keySet();
        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
        sb.append("| ").append(columns.stream().map(c -> "---").reduce((a, b) -> a + " | " + b).orElse("")).append(" |\n");
        for (Map<String, Object> row : rows) {
            sb.append("| ");
            for (String col : columns) {
                sb.append(row.get(col) != null ? row.get(col).toString() : "NULL").append(" | ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private boolean isValidTable(JdbcTemplate jdbc, String tableName) {
        if (tableName == null || tableName.isBlank()) return false;
        List<String> tables = jdbc.queryForList(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
            String.class, tableName);
        return !tables.isEmpty();
    }
}
