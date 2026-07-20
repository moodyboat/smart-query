package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.DataSource;
import com.smartquery.entity.DataDict;
import com.smartquery.mapper.DataDictMapper;
import com.smartquery.mapper.DataSourceMapper;
import com.smartquery.util.DbMetadataUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/dict")
@RequiredArgsConstructor
public class DictController {

    private static final String SYSTEM_TABLE_PREFIX = "sq_";
    private static final String FLYWAY_TABLE_PREFIX = "flyway_";

    private final DataDictMapper dataDictMapper;
    private final DataSourceMapper dataSourceMapper;
    private final DataSourceManager dataSourceManager;

    @GetMapping("/{dataSourceId}")
    public Result<List<DataDict>> list(@PathVariable Long dataSourceId) {
        return Result.ok(dataDictMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataDict>()
                .eq(DataDict::getDataSourceId, dataSourceId)));
    }

    @PostMapping("/{dataSourceId}")
    public Result<String> extract(@PathVariable Long dataSourceId) {
        JdbcTemplate jdbc = dataSourceManager.getJdbcTemplate(dataSourceId);
        DataSource dsCfg = dataSourceMapper.selectById(dataSourceId);
        DbMetadataUtil.Dialect dialect = DbMetadataUtil.Dialect.of(dsCfg != null ? dsCfg.getType() : null);

        // 先清除旧的
        dataDictMapper.delete(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataDict>()
                .eq(DataDict::getDataSourceId, dataSourceId));

        // 跨库元数据：MySQL 走 information_schema，DM/GBase 走 ALL_TABLES/ALL_TAB_COLUMNS
        List<Map<String, Object>> tables = DbMetadataUtil.listTables(jdbc, dialect).stream()
            .filter(t -> !String.valueOf(t.get("name")).startsWith(SYSTEM_TABLE_PREFIX))
            .filter(t -> !String.valueOf(t.get("name")).startsWith(FLYWAY_TABLE_PREFIX))
            .toList();

        int count = 0;
        List<String> tableNames = new ArrayList<>();
        for (Map<String, Object> tableInfo : tables) {
            String tableName = String.valueOf(tableInfo.get("name"));
            String tableComment = tableInfo.get("comment") != null ? String.valueOf(tableInfo.get("comment")) : "";
            tableNames.add(tableName);

            List<Map<String, Object>> columns = DbMetadataUtil.listColumns(jdbc, dialect, tableName);
            for (Map<String, Object> col : columns) {
                DataDict dict = new DataDict();
                dict.setDataSourceId(dataSourceId);
                dict.setTableName(tableName);
                dict.setTableComment(tableComment);
                dict.setColumnName(String.valueOf(col.get("name")));
                dict.setColumnComment(col.get("comment") != null ? String.valueOf(col.get("comment")) : "");
                dict.setColumnType(col.get("type") != null ? String.valueOf(col.get("type")) : "");
                dataDictMapper.insert(dict);
                count++;
            }
        }

        // 采样每张表的示例值（前 3 行）
        for (String table : tableNames) {
            try {
                String safeTable = "`" + table.replace("`", "``") + "`";
                List<Map<String, Object>> sampleRows = jdbc.queryForList(
                    "SELECT * FROM " + safeTable + " LIMIT 3");
                if (!sampleRows.isEmpty()) {
                    String sampleJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(sampleRows);
                    jdbc.update("UPDATE sq_data_dict SET sample_values = ? " +
                        "WHERE data_source_id = ? AND table_name = ?",
                        sampleJson, dataSourceId, table);
                }
            } catch (Exception e) {
                // 采样失败不阻塞整体抽取（DM/GBase LIMIT 语法或权限限制）
            }
        }

        return Result.ok("抽取完成: " + count + " 个字段，" + tableNames.size() + " 张表");
    }
}
