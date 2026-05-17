package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.DataSource;
import com.smartquery.mapper.DataSourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/datasource")
@RequiredArgsConstructor
public class DataSourceController {

    private final DataSourceMapper dataSourceMapper;
    private final DataSourceManager dataSourceManager;

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}")
    private String systemDatasourceUrl;

    private String extractSystemDbName() {
        String url = systemDatasourceUrl;
        // Find the path segment between the last ":" port and "?" query params
        // e.g. jdbc:mysql://localhost:3306/smart_query?useUnicode=...
        int queryStart = url.indexOf('?');
        String pathPart = queryStart > 0 ? url.substring(0, queryStart) : url;
        int lastSlash = pathPart.lastIndexOf('/');
        return lastSlash >= 0 ? pathPart.substring(lastSlash + 1) : url;
    }

    @PostMapping
    public Result<DataSource> create(@RequestBody DataSource ds) {
        dataSourceMapper.insert(ds);
        return Result.ok(ds);
    }

    @GetMapping
    public Result<List<DataSource>> list() {
        String systemDb = extractSystemDbName();
        List<DataSource> list = dataSourceMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getDeleted, 0));
        list.forEach(ds -> ds.setSystem(systemDb.equals(ds.getDatabaseName())));
        return Result.ok(list);
    }

    @GetMapping("/{id}")
    public Result<DataSource> get(@PathVariable Long id) {
        return Result.ok(dataSourceMapper.selectById(id));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody DataSource ds) {
        DataSource existing = dataSourceMapper.selectById(id);
        if (existing == null) return Result.error("数据源不存在");
        ds.setId(id);
        if (ds.getName() == null) ds.setName(existing.getName());
        if (ds.getType() == null) ds.setType(existing.getType());
        if (ds.getHost() == null) ds.setHost(existing.getHost());
        if (ds.getPort() == null) ds.setPort(existing.getPort());
        if (ds.getDatabaseName() == null) ds.setDatabaseName(existing.getDatabaseName());
        if (ds.getUsername() == null) ds.setUsername(existing.getUsername());
        if (ds.getPassword() == null) ds.setPassword(existing.getPassword());
        if (ds.getExtraConfig() == null) ds.setExtraConfig(existing.getExtraConfig());
        if (ds.getStatus() == null) ds.setStatus(existing.getStatus());
        dataSourceMapper.updateById(ds);
        dataSourceManager.destroyDataSource(id);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dataSourceManager.destroyDataSource(id);
        dataSourceMapper.deleteById(id);
        return Result.ok();
    }

    @PostMapping("/{id}/test")
    public Result<Boolean> testConnection(@PathVariable Long id) {
        boolean ok = dataSourceManager.testConnection(id);
        return Result.ok(ok);
    }

    @GetMapping("/{id}/tables")
    public Result<List<java.util.Map<String, Object>>> listTables(@PathVariable Long id) {
        var jdbc = dataSourceManager.getJdbcTemplate(id);
        var tables = jdbc.queryForList(
            "SELECT TABLE_NAME AS name, TABLE_COMMENT AS comment, TABLE_ROWS AS `rows` " +
            "FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME");
        return Result.ok(tables);
    }

    @GetMapping("/{id}/tables/{table}/columns")
    public Result<List<java.util.Map<String, Object>>> listColumns(
            @PathVariable Long id, @PathVariable String table) {
        var jdbc = dataSourceManager.getJdbcTemplate(id);
        var validated = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
            Integer.class, table);
        if (validated == null || validated == 0) {
            return Result.error("表不存在: " + table);
        }
        var columns = jdbc.queryForList(
            "SELECT COLUMN_NAME AS name, COLUMN_TYPE AS type, IS_NULLABLE AS nullable, " +
            "COLUMN_KEY AS `key`, COLUMN_COMMENT AS comment " +
            "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
            table);
        return Result.ok(columns);
    }

    @GetMapping("/{id}/tables/{table}/preview")
    public Result<java.util.Map<String, Object>> previewTable(
            @PathVariable Long id, @PathVariable String table,
            @RequestParam(defaultValue = "20") int limit) {
        var jdbc = dataSourceManager.getJdbcTemplate(id);
        com.smartquery.common.IdentifierValidator.validateTableName(table);
        var validated = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
            Integer.class, table);
        if (validated == null || validated == 0) {
            return Result.error("表不存在: " + table);
        }
        limit = Math.max(1, Math.min(limit, 100));
        var columns = jdbc.queryForList(
            "SELECT COLUMN_NAME AS name, COLUMN_TYPE AS type " +
            "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
            table);
        var columnNames = columns.stream()
            .map(c -> (String) c.get("name"))
            .toList();
        var rows = jdbc.queryForList(
            "SELECT * FROM " + com.smartquery.util.SqlUtil.sanitizeIdentifier(table) + " LIMIT ?",
            limit);

        var totalCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + com.smartquery.util.SqlUtil.sanitizeIdentifier(table), Integer.class);

        java.util.List<java.util.Map<String, Object>> columnStats = new java.util.ArrayList<>();
        for (var col : columns) {
            var colName = (String) col.get("name");
            var colType = (String) col.get("type");
            var stat = new java.util.LinkedHashMap<String, Object>();
            stat.put("name", colName);
            stat.put("type", colType);

            try {
                var nullCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + com.smartquery.util.SqlUtil.sanitizeIdentifier(table) + " WHERE `" + colName + "` IS NULL",
                    Integer.class);
                stat.put("nulls", nullCount);
                stat.put("nullPct", totalCount != null && totalCount > 0 && nullCount != null
                    ? Math.round(nullCount * 10000.0 / totalCount) / 100.0 : 0);
            } catch (Exception e) {
                stat.put("nulls", 0);
                stat.put("nullPct", 0);
            }

            boolean isNumeric = colType != null && colType.matches("(?i).*(int|decimal|float|double|numeric|number).*");
            if (isNumeric) {
                try {
                    var numStats = jdbc.queryForMap(
                        "SELECT MIN(`" + colName + "`) AS min_val, MAX(`" + colName + "`) AS max_val, " +
                        "ROUND(AVG(`" + colName + "`), 2) AS avg_val " +
                        "FROM " + com.smartquery.util.SqlUtil.sanitizeIdentifier(table) + " WHERE `" + colName + "` IS NOT NULL");
                    stat.put("min", numStats.get("min_val"));
                    stat.put("max", numStats.get("max_val"));
                    stat.put("avg", numStats.get("avg_val"));
                } catch (Exception ignored) {}
            } else {
                try {
                    var uniqueCount = jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT `" + colName + "`) FROM " + com.smartquery.util.SqlUtil.sanitizeIdentifier(table),
                        Integer.class);
                    stat.put("unique", uniqueCount);
                    var topValues = jdbc.queryForList(
                        "SELECT `" + colName + "` AS val, COUNT(*) AS cnt FROM " + com.smartquery.util.SqlUtil.sanitizeIdentifier(table) +
                        " WHERE `" + colName + "` IS NOT NULL GROUP BY `" + colName + "` ORDER BY cnt DESC LIMIT 3");
                    stat.put("topValues", topValues);
                } catch (Exception ignored) {}
            }
            columnStats.add(stat);
        }

        return Result.ok(java.util.Map.of(
            "columns", columnNames,
            "rows", rows,
            "totalCount", totalCount != null ? totalCount : 0,
            "columnStats", columnStats
        ));
    }
}
