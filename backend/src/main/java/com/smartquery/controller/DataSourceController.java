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

    @PostMapping
    public Result<DataSource> create(@RequestBody DataSource ds) {
        dataSourceMapper.insert(ds);
        return Result.ok(ds);
    }

    @GetMapping
    public Result<List<DataSource>> list() {
        return Result.ok(dataSourceMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getDeleted, 0)));
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
}
