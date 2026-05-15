package com.smartquery.tool.hook;

import com.smartquery.logging.CostTracker;
import com.smartquery.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * SQL 安全检查 Hook — 在 SQL 执行前检查安全性
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlSafetyHook implements ToolHook {

    private static final Set<String> DENIED_KEYWORDS = Set.of(
        "DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "CREATE",
        "TRUNCATE", "GRANT", "REVOKE", "REPLACE", "RENAME"
    );

    @Override
    public String name() { return "sql-safety"; }

    @Override
    public int order() { return 10; }

    @Override
    public boolean beforeToolCall(String toolName, Map<String, Object> input, ToolExecutionContext context) {
        if (!"execute_sql".equals(toolName)) return true;

        String sql = (String) input.get("sql");
        if (sql == null || sql.isBlank()) return true;

        String upperSql = sql.toUpperCase().trim();
        for (String keyword : DENIED_KEYWORDS) {
            if (upperSql.startsWith(keyword + " ") || upperSql.startsWith(keyword + "(")) {
                log.warn("[HOOK][sql-safety] blocked dangerous SQL: {}...", sql.substring(0, Math.min(sql.length(), 50)));
                return false;
            }
        }
        return true;
    }
}
