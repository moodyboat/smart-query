package com.smartquery.prompt;

import com.smartquery.entity.DataDict;
import com.smartquery.mapper.DataDictMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据字典上下文构建器 — 将已知的表结构注入 system prompt
 *
 * <p>参考 Claude Code 的 memory/env_info 动态段模式:
 * 将运行时上下文作为 system prompt 的一部分注入，减少不必要的工具调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaContextBuilder {

    private final DataDictMapper dataDictMapper;

    private static final int CHARS_PER_TOKEN = 4;

    /**
     * 构建数据库结构上下文 (无 token 限制)
     */
    public String buildSchemaContext(Long dataSourceId) {
        return buildSchemaContext(dataSourceId, Integer.MAX_VALUE);
    }

    /**
     * 构建数据库结构上下文 (带 token 预算)
     *
     * @param dataSourceId 数据源 ID
     * @param maxTokens schema 上下文允许的最大 token 数
     * @return 格式化的 Markdown 结构说明，如果无数据返回 null
     */
    public String buildSchemaContext(Long dataSourceId, int maxTokens) {
        if (dataSourceId == null) return null;

        List<DataDict> dicts = dataDictMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataDict>()
                .eq(DataDict::getDataSourceId, dataSourceId)
                .orderByAsc(DataDict::getTableName)
                .orderByAsc(DataDict::getColumnName));

        if (dicts == null || dicts.isEmpty()) {
            log.debug("[SCHEMA-CTX] no data dict for dataSourceId={}", dataSourceId);
            return null;
        }

        // 按表名分组
        Map<String, List<DataDict>> tableGroups = dicts.stream()
            .collect(Collectors.groupingBy(DataDict::getTableName, LinkedHashMap::new, Collectors.toList()));

        int maxChars = maxTokens * CHARS_PER_TOKEN;

        // 先尝试完整格式
        String fullContext = buildFullContext(dataSourceId, tableGroups);
        if (fullContext.length() <= maxChars) {
            log.debug("[SCHEMA-CTX] built full context for dataSourceId={}: {} tables, {} chars",
                dataSourceId, tableGroups.size(), fullContext.length());
            return fullContext;
        }

        // 超限: 切换到摘要模式 (只保留表名 + 列名)
        String summaryContext = buildSummaryContext(dataSourceId, tableGroups, maxChars);
        log.info("[SCHEMA-CTX] schema truncated to summary for dataSourceId={}: {} tables, full={} chars, summary={} chars, budget={} tokens",
            dataSourceId, tableGroups.size(), fullContext.length(), summaryContext.length(), maxTokens);
        return summaryContext;
    }

    private String buildFullContext(Long dataSourceId, Map<String, List<DataDict>> tableGroups) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 数据库结构 (数据源 ID: ").append(dataSourceId).append(")\n\n");
        sb.append("以下是已知的表结构信息，你可以直接基于这些信息生成 SQL，无需再调用 schema_explore。\n\n");

        for (Map.Entry<String, List<DataDict>> entry : tableGroups.entrySet()) {
            String tableName = entry.getKey();
            List<DataDict> columns = entry.getValue();
            String tableComment = columns.get(0).getTableComment();

            sb.append("## ").append(tableName);
            if (tableComment != null && !tableComment.isEmpty()) {
                sb.append(" (").append(tableComment).append(")");
            }
            sb.append("\n\n");

            sb.append("| 字段名 | 类型 | 注释 |\n");
            sb.append("| --- | --- | --- |\n");
            for (DataDict col : columns) {
                sb.append("| ").append(col.getColumnName())
                  .append(" | ").append(col.getColumnType() != null ? col.getColumnType() : "")
                  .append(" | ").append(col.getColumnComment() != null ? col.getColumnComment() : "")
                  .append(" |\n");
            }

            // 添加采样值（如果有）
            String sampleValues = columns.get(0).getSampleValues();
            if (sampleValues != null && !sampleValues.isEmpty()) {
                sb.append("\n样例数据: ").append(sampleValues.substring(0, Math.min(sampleValues.length(), 200)));
                if (sampleValues.length() > 200) sb.append("...");
                sb.append("\n");
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    private String buildSummaryContext(Long dataSourceId, Map<String, List<DataDict>> tableGroups, int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 数据库结构摘要 (数据源 ID: ").append(dataSourceId).append(")\n\n");
        sb.append("以下是表名和字段名列表（因库较大，已省略类型和注释。可用 schema_explore 工具查看详情）。\n\n");

        for (Map.Entry<String, List<DataDict>> entry : tableGroups.entrySet()) {
            String tableName = entry.getKey();
            List<DataDict> columns = entry.getValue();
            String tableComment = columns.get(0).getTableComment();

            sb.append("## ").append(tableName);
            if (tableComment != null && !tableComment.isEmpty()) {
                sb.append(" (").append(tableComment).append(")");
            }
            sb.append("\n");

            // 只列字段名，不列类型和注释
            String colNames = columns.stream()
                .map(DataDict::getColumnName)
                .collect(Collectors.joining(", "));
            sb.append("字段: ").append(colNames).append("\n\n");

            // 检查是否已超限
            if (sb.length() > maxChars) {
                sb.append("...(更多表已省略，共 ").append(tableGroups.size()).append(" 张表)\n");
                break;
            }
        }

        return sb.toString();
    }
}
