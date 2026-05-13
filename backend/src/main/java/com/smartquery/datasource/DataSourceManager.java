package com.smartquery.datasource;

import com.smartquery.entity.DataSource;
import com.smartquery.mapper.DataSourceMapper;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态数据源管理器 — 适配 Claude Code mcp/types.ts 的多连接管理
 *
 * <p>运行时注册/销毁 HikariCP 连接池
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceManager {

    private final DataSourceMapper dataSourceMapper;
    private final Map<Long, HikariDataSource> dataSourcePool = new ConcurrentHashMap<>();
    private final Map<Long, JdbcTemplate> jdbcTemplatePool = new ConcurrentHashMap<>();

    /**
     * 获取 JdbcTemplate (懒加载)
     */
    public JdbcTemplate getJdbcTemplate(Long dataSourceId) {
        return jdbcTemplatePool.computeIfAbsent(dataSourceId, this::createJdbcTemplate);
    }

    /**
     * 注册数据源
     */
    public void registerDataSource(Long dataSourceId) {
        createJdbcTemplate(dataSourceId);
        log.info("[DATASOURCE] registered: id={}", dataSourceId);
    }

    /**
     * 销毁数据源
     */
    public void destroyDataSource(Long dataSourceId) {
        HikariDataSource ds = dataSourcePool.remove(dataSourceId);
        if (ds != null && !ds.isClosed()) {
            ds.close();
            log.info("[DATASOURCE] destroyed: id={}", dataSourceId);
        }
        jdbcTemplatePool.remove(dataSourceId);
    }

    /**
     * 测试连接
     */
    public boolean testConnection(Long dataSourceId) {
        try {
            JdbcTemplate jdbc = getJdbcTemplate(dataSourceId);
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.warn("[DATASOURCE] test connection failed: id={}, error={}", dataSourceId, e.getMessage());
            return false;
        }
    }

    private JdbcTemplate createJdbcTemplate(Long dataSourceId) {
        HikariDataSource ds = dataSourcePool.computeIfAbsent(dataSourceId, this::createHikariDataSource);
        return new JdbcTemplate(ds);
    }

    private HikariDataSource createHikariDataSource(Long dataSourceId) {
        DataSource config = dataSourceMapper.selectById(dataSourceId);
        if (config == null) {
            throw new IllegalArgumentException("数据源不存在: " + dataSourceId);
        }

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(config.getJdbcUrl());
        ds.setDriverClassName(config.getDriverClassName());
        ds.setUsername(config.getUsername());
        ds.setPassword(config.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(10000);
        ds.setIdleTimeout(300000);
        ds.setMaxLifetime(600000);
        ds.setPoolName("sq-ds-" + dataSourceId);

        return ds;
    }
}
