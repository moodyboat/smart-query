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

    public SqlSafetyValidator(Set<String> allowedStatements, Set<String> deniedKeywords) {
        this.allowedStatements = allowedStatements;
        this.deniedKeywords = deniedKeywords;
    }

    public static SqlSafetyValidator defaults() {
        return new SqlSafetyValidator(
            Set.of("SELECT", "SHOW", "DESCRIBE", "EXPLAIN"),
            Set.of("DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "CREATE",
                   "TRUNCATE", "GRANT", "REVOKE", "REPLACE", "RENAME",
                   "CALL", "EXEC", "EXECUTE", "LOAD DATA", "INTO OUTFILE", "INTO DUMPFILE")
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

    // ------------------------------------------------------------------
    // 场景表白名单：在 validate() 基础上叠加"SQL 涉及的表必须全部在白名单内"
    // ------------------------------------------------------------------

    private static final Pattern FROM_JOIN_PATTERN = Pattern.compile(
        "\\b(?:FROM|JOIN|INTO|UPDATE)\\s+[`\\[\"?]?(\\w+)[`\\]\"?]?",
        Pattern.CASE_INSENSITIVE);

    /**
     * 提取 SQL 涉及的所有表名（已 normalize：去 schema 前缀、去包裹符、小写）。
     * 优先用 JSqlParser；解析失败回退正则。
     */
    public Set<String> extractTableNames(String sql) {
        if (sql == null || sql.isBlank()) return Collections.emptySet();
        try {
            net.sf.jsqlparser.statement.Statement stmt = net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql);
            List<String> raw = new net.sf.jsqlparser.util.TablesNamesFinder().getTableList(stmt);
            Set<String> normalized = new LinkedHashSet<>();
            for (String t : raw) {
                String n = normalizeTableName(t);
                if (n != null && !n.isEmpty()) normalized.add(n);
            }
            return normalized;
        } catch (Exception e) {
            // JSqlParser 不支持的方言或语法异常 → 正则兜底
            Set<String> fallback = new LinkedHashSet<>();
            java.util.regex.Matcher m = FROM_JOIN_PATTERN.matcher(sql);
            while (m.find()) {
                String n = normalizeTableName(m.group(1));
                if (n != null && !n.isEmpty()) fallback.add(n);
            }
            return fallback;
        }
    }

    /**
     * 表名 normalize：去 schema 前缀、去反引号/方括号/双引号、小写。
     */
    public static String normalizeTableName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) s = s.substring(dot + 1);
        if ((s.startsWith("`") && s.endsWith("`"))
            || (s.startsWith("[") && s.endsWith("]"))
            || (s.startsWith("\"") && s.endsWith("\""))) {
            s = s.substring(1, s.length() - 1);
        }
        return s.toLowerCase();
    }

    /**
     * 在基础安全校验之上叠加表白名单校验。
     * @param sql 待校验 SQL
     * @param allowedTables 白名单（normalize 后的小写表名集合）；null/empty 表示不限
     */
    public ValidationResult validateAgainstWhitelist(String sql, Set<String> allowedTables) {
        ValidationResult base = validate(sql);
        if (!base.safe()) return base;
        if (allowedTables == null || allowedTables.isEmpty()) return base;

        Set<String> used = extractTableNames(sql);
        if (used.isEmpty()) {
            // 解析不出表名（如 SHOW TABLES），保守拒绝，避免绕过
            return ValidationResult.unsafe("无法解析 SQL 涉及的表名，场景白名单模式下拒绝执行");
        }
        Set<String> violating = new LinkedHashSet<>();
        for (String t : used) {
            if (!allowedTables.contains(t)) violating.add(t);
        }
        if (!violating.isEmpty()) {
            return ValidationResult.unsafe("SQL 引用了未授权表: " + violating);
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
