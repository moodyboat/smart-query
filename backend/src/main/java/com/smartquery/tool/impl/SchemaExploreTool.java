package com.smartquery.tool.impl;

import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.DataSource;
import com.smartquery.mapper.DataSourceMapper;
import com.smartquery.tool.*;
import com.smartquery.util.DbMetadataUtil;
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
    private final DataSourceMapper dataSourceMapper;

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

        Set<String> allowed = context.allowedTables();

        // describe_table / sample_data 在执行前先做白名单校验（list_tables 在结果上过滤）
        if (allowed != null && !allowed.isEmpty()) {
            String tableName = (String) input.get("table_name");
            if (tableName != null && !tableName.isBlank()) {
                String norm = SqlSafetyValidator.normalizeTableName(tableName);
                if (norm == null || !allowed.contains(norm)) {
                    return ToolResult.error(getName(),
                        "表 `" + tableName + "` 不在当前场景授权范围", System.currentTimeMillis() - start);
                }
            }
        }

        try {
            JdbcTemplate jdbc = dataSourceManager.getJdbcTemplate(dsId);
            DataSource dsCfg = dataSourceMapper.selectById(dsId);
            DbMetadataUtil.Dialect dialect = DbMetadataUtil.Dialect.of(dsCfg != null ? dsCfg.getType() : null);

            String result = switch (action) {
                case "list_tables" -> listTables(jdbc, dialect, allowed);
                case "describe_table" -> describeTable(jdbc, dialect, (String) input.get("table_name"));
                case "sample_data" -> sampleData(jdbc, dialect, (String) input.get("table_name"));
                default -> "未知的 action: " + action;
            };

            return ToolResult.ok(getName(), result, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.error(getName(), "Schema 探索错误: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public boolean isConcurrencySafe() { return true; }

    private String listTables(JdbcTemplate jdbc, DbMetadataUtil.Dialect dialect, Set<String> allowed) {
        List<Map<String, Object>> tables = DbMetadataUtil.listTables(jdbc, dialect);
        // 场景白名单过滤（normalize 后小写比较）
        if (allowed != null && !allowed.isEmpty()) {
            tables = tables.stream()
                .filter(r -> {
                    String n = SqlSafetyValidator.normalizeTableName((String) r.get("name"));
                    return n != null && allowed.contains(n);
                })
                .collect(java.util.stream.Collectors.toList());
        }
        StringBuilder sb = new StringBuilder("数据库表列表:\n\n");
        sb.append("| 表名 | 注释 | 行数 |\n| --- | --- | --- |\n");
        for (Map<String, Object> row : tables) {
            sb.append("| ").append(row.get("name"))
              .append(" | ").append(row.getOrDefault("comment", ""))
              .append(" | ").append(row.getOrDefault("rows", ""))
              .append(" |\n");
        }
        return sb.toString();
    }

    private String describeTable(JdbcTemplate jdbc, DbMetadataUtil.Dialect dialect, String tableName) {
        List<Map<String, Object>> columns = DbMetadataUtil.listColumns(jdbc, dialect, tableName);
        StringBuilder sb = new StringBuilder("表 `").append(tableName).append("` 结构:\n\n");
        sb.append("| 字段名 | 类型 | 可空 | 键 | 注释 |\n| --- | --- | --- | --- | --- |\n");
        for (Map<String, Object> row : columns) {
            sb.append("| ").append(row.get("name"))
              .append(" | ").append(row.get("type"))
              .append(" | ").append(row.get("nullable"))
              .append(" | ").append(row.getOrDefault("key", ""))
              .append(" | ").append(row.getOrDefault("comment", ""))
              .append(" |\n");
        }
        return sb.toString();
    }

    private String sampleData(JdbcTemplate jdbc, DbMetadataUtil.Dialect dialect, String tableName) {
        // Validate table name against actual tables to prevent injection
        if (!DbMetadataUtil.tableExists(jdbc, dialect, tableName)) {
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
}
