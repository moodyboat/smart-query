package com.smartquery.util;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨库元数据查询工具：MySQL 用 information_schema，DM 用 ALL_TABLES / ALL_TAB_COLUMNS。
 * 收敛散落在 DataSourceController/DictController/MetadataConfigController/SchemaExploreTool 等
 * 6 处的 information_schema 调用，避免每处都判断数据库类型。
 */
public final class DbMetadataUtil {

    private DbMetadataUtil() {}

    public enum Dialect {
        MYSQL,
        DM,
        GBASE,
        OTHER;

        public static Dialect of(String type) {
            if (type == null) return MYSQL;
            return switch (type.toLowerCase()) {
                case "dm" -> DM;
                case "gbase" -> GBASE;
                default -> MYSQL;
            };
        }
    }

    /** 列出当前 schema 下所有表：name + comment + rows。 */
    public static List<Map<String, Object>> listTables(JdbcTemplate jdbc, Dialect dialect) {
        List<Map<String, Object>> raw = switch (dialect) {
            case DM, GBASE -> jdbc.queryForList(
                "SELECT T.TABLE_NAME AS \"name\", C.COMMENTS AS \"comment\", T.NUM_ROWS AS \"rows\" " +
                "FROM ALL_TABLES T LEFT JOIN USER_TAB_COMMENTS C " +
                "ON C.TABLE_NAME = T.TABLE_NAME AND C.TABLE_TYPE = 'TABLE' " +
                "WHERE T.OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') " +
                "ORDER BY T.TABLE_NAME");
            default -> jdbc.queryForList(
                "SELECT TABLE_NAME AS name, TABLE_COMMENT AS comment, TABLE_ROWS AS `rows` " +
                "FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME");
        };
        // DM 联 USER_TAB_COMMENTS 可能产生重复行，按 name 去重保留首条
        if (dialect == Dialect.DM || dialect == Dialect.GBASE) {
            Map<String, Map<String, Object>> dedup = new java.util.LinkedHashMap<>();
            for (Map<String, Object> row : raw) {
                dedup.putIfAbsent(String.valueOf(row.get("name")), row);
            }
            return new ArrayList<>(dedup.values());
        }
        return raw;
    }

    /** 表是否存在。 */
    public static boolean tableExists(JdbcTemplate jdbc, Dialect dialect, String table) {
        Integer cnt = switch (dialect) {
            case DM, GBASE -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM ALL_TABLES " +
                "WHERE OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND TABLE_NAME = ?",
                Integer.class, table.toUpperCase());
            default -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Integer.class, table);
        };
        return cnt != null && cnt > 0;
    }

    /** 列出表所有列：name + type + nullable + comment + key。 */
    public static List<Map<String, Object>> listColumns(JdbcTemplate jdbc, Dialect dialect, String table) {
        return switch (dialect) {
            case DM, GBASE -> {
                String t = table.toUpperCase();
                List<Map<String, Object>> raw = jdbc.queryForList(
                    "SELECT C.COLUMN_NAME AS \"name\", C.DATA_TYPE AS \"type\", C.NULLABLE AS \"nullable\", " +
                    "CC.COMMENTS AS \"comment\" " +
                    "FROM ALL_TAB_COLUMNS C " +
                    "LEFT JOIN USER_COL_COMMENTS CC " +
                    "ON CC.TABLE_NAME = C.TABLE_NAME AND CC.COLUMN_NAME = C.COLUMN_NAME " +
                    "WHERE C.OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND C.TABLE_NAME = ? " +
                    "ORDER BY C.COLUMN_ID",
                    t);
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> row : raw) {
                    Map<String, Object> norm = new HashMap<>();
                    norm.put("name", row.get("name"));
                    norm.put("type", row.get("type"));
                    Object nullable = row.get("nullable");
                    norm.put("nullable", "N".equals(nullable) ? "NO" : "YES");
                    norm.put("key", "");
                    norm.put("comment", row.get("comment"));
                    out.add(norm);
                }
                yield out;
            }
            default -> jdbc.queryForList(
                "SELECT COLUMN_NAME AS name, COLUMN_TYPE AS type, IS_NULLABLE AS nullable, " +
                "COLUMN_KEY AS `key`, COLUMN_COMMENT AS comment " +
                "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                "ORDER BY ORDINAL_POSITION",
                table);
        };
    }
}
