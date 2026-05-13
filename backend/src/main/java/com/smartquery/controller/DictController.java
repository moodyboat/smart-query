package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.DataDict;
import com.smartquery.mapper.DataDictMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/dict")
@RequiredArgsConstructor
public class DictController {

    private final DataDictMapper dataDictMapper;
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

        // 先清除旧的
        dataDictMapper.delete(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataDict>()
                .eq(DataDict::getDataSourceId, dataSourceId));

        // 查询表注释
        Map<String, String> tableComments = new LinkedHashMap<>();
        jdbc.queryForList(
            "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES " +
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME NOT LIKE 'sq_%' " +
            "AND TABLE_NAME NOT LIKE 'flyway_%'")
            .forEach(row -> tableComments.put((String) row.get("TABLE_NAME"), (String) row.get("TABLE_COMMENT")));

        // 查询字段信息
        List<Map<String, Object>> columns = jdbc.queryForList(
            "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_COMMENT, COLUMN_TYPE " +
            "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
            "AND TABLE_NAME NOT LIKE 'sq_%' AND TABLE_NAME NOT LIKE 'flyway_%' " +
            "ORDER BY TABLE_NAME, ORDINAL_POSITION");

        int count = 0;
        for (Map<String, Object> col : columns) {
            String tableName = (String) col.get("TABLE_NAME");
            DataDict dict = new DataDict();
            dict.setDataSourceId(dataSourceId);
            dict.setTableName(tableName);
            dict.setTableComment(tableComments.getOrDefault(tableName, ""));
            dict.setColumnName((String) col.get("COLUMN_NAME"));
            dict.setColumnComment((String) col.get("COLUMN_COMMENT"));
            dict.setColumnType((String) col.get("COLUMN_TYPE"));
            dataDictMapper.insert(dict);
            count++;
        }

        // 采样每张表的示例值
        List<String> tables = jdbc.queryForList(
            "SELECT DISTINCT TABLE_NAME FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME NOT LIKE 'sq_%' " +
            "AND TABLE_NAME NOT LIKE 'flyway_%'", String.class);

        for (String table : tables) {
            try {
                List<Map<String, Object>> sampleRows = jdbc.queryForList(
                    "SELECT * FROM " + table + " LIMIT 3");
                if (!sampleRows.isEmpty()) {
                    String sampleJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(sampleRows);
                    // 更新该表的示例值到所有字段记录
                    jdbc.update("UPDATE sq_data_dict SET sample_values = ? " +
                        "WHERE data_source_id = ? AND table_name = ?",
                        sampleJson, dataSourceId, table);
                }
            } catch (Exception ignored) {}
        }

        return Result.ok("抽取完成: " + count + " 个字段，" + tables.size() + " 张表");
    }
}
