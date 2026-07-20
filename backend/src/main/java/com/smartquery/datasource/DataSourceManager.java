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

    /** 探测/权限校验 SQL — 集中常量，避免散落字符串字面量。 */
    private static final String SQL_PROBE = "SELECT 1";
    private static final String SQL_VERSION = "SELECT VERSION()";
    private static final String SQL_CURRENT_DB_MYSQL = "SELECT DATABASE()";
    private static final String SQL_CURRENT_SCHEMA_ANSI = "SELECT CURRENT_SCHEMA()";
    private static final String SQL_SHOW_TABLES = "SHOW TABLES LIMIT 1";
    private static final String SQL_DESCRIBE_SCHEMA_TABLES = "DESCRIBE information_schema.tables";
    private static final String SQL_EXPLAIN_PROBE = "EXPLAIN SELECT 1";

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
            jdbc.queryForObject(SQL_PROBE, Integer.class);
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
            jdbc.queryForObject(SQL_PROBE, Integer.class);
            long latency = System.currentTimeMillis() - startTime;
            result.put("success", true);
            result.put("latencyMs", latency);
            result.put("message", "连接成功");

            // 获取数据库版本
            try {
                String version = jdbc.queryForObject(SQL_VERSION, String.class);
                result.put("databaseVersion", version);
            } catch (Exception e) {
                result.put("databaseVersion", "Unknown");
            }

            // 获取当前数据库/模式（MySQL 用 DATABASE()，PostgreSQL/Oracle 用 CURRENT_SCHEMA()）
            try {
                String currentDb = jdbc.queryForObject(SQL_CURRENT_DB_MYSQL, String.class);
                result.put("currentSchema", currentDb);
            } catch (Exception e) {
                try {
                    String currentDb = jdbc.queryForObject(SQL_CURRENT_SCHEMA_ANSI, String.class);
                    result.put("currentSchema", currentDb);
                } catch (Exception ex) {
                    result.put("currentSchema", "Unknown");
                }
            }

            // 测试权限：SELECT 1 / SHOW TABLES / DESCRIBE 系统表 / EXPLAIN。
            // DESCRIBE 用 information_schema.tables（MySQL 跨库都有），不指定业务表名以保持跨库可移植。
            Map<String, Object> permissions = new LinkedHashMap<>();
            try {
                jdbc.queryForObject(SQL_PROBE, Integer.class);
                permissions.put("canSelect", true);
            } catch (Exception e) {
                permissions.put("canSelect", false);
            }

            try {
                jdbc.queryForList(SQL_SHOW_TABLES);
                permissions.put("canShow", true);
            } catch (Exception e) {
                permissions.put("canShow", false);
            }

            try {
                jdbc.queryForList(SQL_DESCRIBE_SCHEMA_TABLES);
                permissions.put("canDescribe", true);
            } catch (Exception e) {
                permissions.put("canDescribe", false);
            }

            try {
                jdbc.queryForList(SQL_EXPLAIN_PROBE);
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

        // extraConfig 是 JSONB 列：{"connectionInitSql":"SET SCHEMA ODS_DM", ...}
        // DM 业务库可用 connectionInitSql 切 schema；未来可扩展其他 HikariCP 属性。
        String extra = config.getExtraConfig();
        if (extra != null && !extra.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(extra);
                if (node.has("connectionInitSql")) {
                    String sql = node.get("connectionInitSql").asText("").trim();
                    if (!sql.isEmpty()) {
                        ds.setConnectionInitSql(sql);
                    }
                }
            } catch (Exception ignore) {
                // 非 JSON：兼容历史数据，直接当 SQL
                ds.setConnectionInitSql(extra.trim());
            }
        }

        return ds;
    }
}
