package com.smartquery.util;

public final class SqlUtil {

    private SqlUtil() {}

    public static String sanitizeIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("SQL identifier must not be empty");
        }
        if (!identifier.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
        return '`' + identifier + '`';
    }
}
