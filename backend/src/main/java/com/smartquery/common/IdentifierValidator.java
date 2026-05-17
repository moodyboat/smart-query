package com.smartquery.common;

import java.util.Set;
import java.util.regex.Pattern;

public final class IdentifierValidator {

    private static final Pattern VALID_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Pattern VALID_FILTER_TOKEN = Pattern.compile("^[a-zA-Z0-9_.\\-:${}()\\s=<>!,']+$");

    private static final Set<String> SQL_KEYWORDS = Set.of(
        "SELECT", "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER",
        "TRUNCATE", "EXEC", "EXECUTE", "UNION", "JOIN", "WHERE", "HAVING",
        "GROUP", "ORDER", "LIMIT", "OFFSET", "INTO", "VALUES", "SET",
        "GRANT", "REVOKE", "--", ";", "/*", "*/"
    );

    private IdentifierValidator() {}

    public static void validateTableName(String tableName) {
        if (tableName == null || !VALID_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }
        checkSqlKeywords(tableName);
    }

    public static void validateColumnName(String columnName) {
        if (columnName == null || !VALID_IDENTIFIER.matcher(columnName).matches()) {
            throw new IllegalArgumentException("Invalid column name: " + columnName);
        }
    }

    public static void validateFilter(String filter) {
        if (filter == null || filter.isBlank()) return;
        if (!VALID_FILTER_TOKEN.matcher(filter).matches()) {
            throw new IllegalArgumentException("Invalid filter contains disallowed characters: " + filter);
        }
        String upper = filter.toUpperCase();
        if (upper.contains("SELECT") || upper.contains("SUBSTRING") || upper.contains("CONCAT")
            || upper.contains("EXECUTE") || upper.contains("PREPARE") || upper.contains("CALL")) {
            throw new IllegalArgumentException("Filter contains forbidden SQL pattern: subqueries and function calls not allowed");
        }
        for (String kw : SQL_KEYWORDS) {
            if (upper.contains(kw) && !"WHERE".equals(kw) && !"AND".equals(kw) && !"OR".equals(kw)
                && !"IN".equals(kw) && !"IS".equals(kw) && !"NOT".equals(kw) && !"NULL".equals(kw)
                && !"LIKE".equals(kw) && !"BETWEEN".equals(kw) && !"EXISTS".equals(kw)) {
                if (upper.contains("--") || upper.contains(";") || upper.contains("/*")
                    || upper.contains("UNION") || upper.contains("DROP") || upper.contains("DELETE")
                    || upper.contains("INSERT") || upper.contains("UPDATE") || upper.contains("EXEC")) {
                    throw new IllegalArgumentException("Filter contains forbidden SQL keyword: " + kw);
                }
            }
        }
    }

    private static void checkSqlKeywords(String identifier) {
        String upper = identifier.toUpperCase();
        if (SQL_KEYWORDS.contains(upper)) {
            throw new IllegalArgumentException("Identifier cannot be a SQL keyword: " + identifier);
        }
    }
}
