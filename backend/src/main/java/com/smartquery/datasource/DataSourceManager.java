package com.smartquery.datasource;

import com.smartquery.entity.DataSource;
import com.smartquery.mapper.DataSourceMapper;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceManager {

    private final DataSourceMapper dataSourceMapper;
    private final Map<Long, HikariDataSource> dataSourcePool = new ConcurrentHashMap<>();
    private final Map<Long, JdbcTemplate> jdbcTemplatePool = new ConcurrentHashMap<>();

    @Value("${smart-query.datasource.dynamic.max-pool-size:5}")
    private int dynamicPoolMaxSize;
    @Value("${smart-query.datasource.dynamic.min-idle:1}")
    private int dynamicPoolMinIdle;
    @Value("${smart-query.datasource.dynamic.connection-timeout-ms:10000}")
    private int dynamicPoolConnectionTimeout;
    @Value("${smart-query.datasource.dynamic.idle-timeout-ms:300000}")
    private int dynamicPoolIdleTimeout;
    @Value("${smart-query.datasource.dynamic.max-lifetime-ms:600000}")
    private int dynamicPoolMaxLifetime;

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
     * 测试连接（简单版本）
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

    /**
     * 测试连接并返回详细信息
     */
    public Map<String, Object> testConnectionDetailed(Long dataSourceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            JdbcTemplate jdbc = getJdbcTemplate(dataSourceId);

            // 测试基本连接
            jdbc.queryForObject("SELECT 1", Integer.class);
            long latency = System.currentTimeMillis() - startTime;
            result.put("success", true);
            result.put("latencyMs", latency);
            result.put("message", "连接成功");

            // 获取数据库版本
            try {
                String version = jdbc.queryForObject("SELECT VERSION()", String.class);
                result.put("databaseVersion", version);
            } catch (Exception e) {
                result.put("databaseVersion", "Unknown");
            }

            // 获取当前数据库/模式
            try {
                String currentDb = jdbc.queryForObject("SELECT DATABASE()", String.class);
                result.put("currentSchema", currentDb);
            } catch (Exception e) {
                try {
                    String currentDb = jdbc.queryForObject("SELECT CURRENT_SCHEMA()", String.class);
                    result.put("currentSchema", currentDb);
                } catch (Exception ex) {
                    result.put("currentSchema", "Unknown");
                }
            }

            // 测试权限
            Map<String, Object> permissions = new LinkedHashMap<>();
            try {
                jdbc.queryForObject("SELECT 1", Integer.class);
                permissions.put("canSelect", true);
            } catch (Exception e) {
                permissions.put("canSelect", false);
            }

            try {
                jdbc.queryForList("SHOW TABLES LIMIT 1");
                permissions.put("canShow", true);
            } catch (Exception e) {
                permissions.put("canShow", false);
            }

            try {
                jdbc.queryForObject("DESCRIBE sq_conversation", String.class);
                permissions.put("canDescribe", true);
            } catch (Exception e) {
                permissions.put("canDescribe", false);
            }

            try {
                jdbc.queryForList("EXPLAIN SELECT 1");
                permissions.put("canExplain", true);
            } catch (Exception e) {
                permissions.put("canExplain", false);
            }

            result.put("permissions", permissions);

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
            result.put("latencyMs", System.currentTimeMillis() - startTime);
        }

        return result;
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
        ds.setMaximumPoolSize(dynamicPoolMaxSize);
        ds.setMinimumIdle(dynamicPoolMinIdle);
        ds.setConnectionTimeout(dynamicPoolConnectionTimeout);
        ds.setIdleTimeout(dynamicPoolIdleTimeout);
        ds.setMaxLifetime(dynamicPoolMaxLifetime);
        ds.setPoolName("sq-ds-" + dataSourceId);

        return ds;
    }

    public int cleanupStalePools() {
        int removed = 0;
        var iter = dataSourcePool.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            HikariDataSource ds = entry.getValue();
            if (ds.isClosed()) {
                iter.remove();
                jdbcTemplatePool.remove(entry.getKey());
                removed++;
            } else if (ds.getHikariPoolMXBean() != null
                    && ds.getHikariPoolMXBean().getActiveConnections() == 0
                    && ds.getHikariPoolMXBean().getIdleConnections() == ds.getMaximumPoolSize()) {
                ds.close();
                iter.remove();
                jdbcTemplatePool.remove(entry.getKey());
                removed++;
                log.info("[DATASOURCE] evicted idle pool: id={}", entry.getKey());
            }
        }
        return removed;
    }

    public int poolCount() {
        return dataSourcePool.size();
    }
}
