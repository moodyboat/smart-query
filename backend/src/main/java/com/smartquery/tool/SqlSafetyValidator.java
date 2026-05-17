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

        String firstWord = upper.split("\\s+")[0];
        if (!allowedStatements.contains(firstWord)) {
            return ValidationResult.unsafe("不支持的 SQL 类型: " + firstWord + "。允许的类型: " + allowedStatements);
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
}
