package com.smartquery.tool;

import java.util.*;
import java.util.regex.Pattern;

/**
 * SQL 安全验证器 — 适配 Claude Code permissions.ts 的 3 层权限
 *
 * <p>翻译对照:
 * <pre>
 * TS: permissions.ts → PermissionRules + checkPermissions() + Classifier
 * Java: SqlSafetyValidator → allowedStatements + deniedKeywords + validate()
 * </pre>
 */
public class SqlSafetyValidator {

    private final Set<String> allowedStatements;
    private final Set<String> deniedKeywords;
    private final int maxRows;
    private final int queryTimeoutSeconds;

    public SqlSafetyValidator(Set<String> allowedStatements, Set<String> deniedKeywords,
                              int maxRows, int queryTimeoutSeconds) {
        this.allowedStatements = allowedStatements;
        this.deniedKeywords = deniedKeywords;
        this.maxRows = maxRows;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public static SqlSafetyValidator defaults() {
        return new SqlSafetyValidator(
            Set.of("SELECT", "SHOW", "DESCRIBE", "EXPLAIN"),
            Set.of("DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "CREATE",
                   "TRUNCATE", "GRANT", "REVOKE", "REPLACE", "RENAME",
                   "CALL", "EXEC", "EXECUTE", "LOAD DATA", "INTO OUTFILE", "INTO DUMPFILE"),
            Integer.getInteger("sql-safety.max-rows", 1000),
            Integer.getInteger("sql-safety.query-timeout-seconds", 30)
        );
    }

    public record ValidationResult(boolean safe, String reason) {
        public static ValidationResult ok() { return new ValidationResult(true, null); }
        public static ValidationResult unsafe(String reason) { return new ValidationResult(false, reason); }
    }

    public ValidationResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.unsafe("SQL 不能为空");
        }

        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase();

        // 跳过行首注释（-- 或 #），找到实际的 SQL 关键字
        String firstWord = extractFirstSqlKeyword(upper);
        if (firstWord != null && !allowedStatements.contains(firstWord)) {
            return ValidationResult.unsafe("不支持的 SQL 类型: " + firstWord + "。允许的类型: " + allowedStatements);
        }

        // 如果没找到有效关键字（全是注释），拒绝
        if (firstWord == null) {
            return ValidationResult.unsafe("SQL 只包含注释，没有实际语句");
        }

        for (String keyword : deniedKeywords) {
            if (upper.contains(keyword)) {
                Pattern p = Pattern.compile("\\b" + keyword + "\\b");
                if (p.matcher(upper).find()) {
                    return ValidationResult.unsafe("SQL 包含禁止的关键字: " + keyword);
                }
            }
        }

        if (upper.contains(";") && !upper.trim().endsWith(";")) {
            return ValidationResult.unsafe("不支持多语句执行");
        }

        return ValidationResult.ok();
    }

    /**
     * 提取 SQL 的第一个关键字，跳过行首注释
     */
    private String extractFirstSqlKeyword(String sql) {
        String[] lines = sql.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            // 跳过空行
            if (trimmed.isEmpty()) continue;
            // 跳过行注释（-- 或 #）
            if (trimmed.startsWith("--") || trimmed.startsWith("#")) continue;

            // 找到第一个非注释行，提取第一个词
            String[] parts = trimmed.split("\\s+");
            if (parts.length > 0) {
                return parts[0];
            }
        }
        return null;
    }
}
