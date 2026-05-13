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
        ds.setId(id);
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
}
