package com.smartquery.tool.hook;

import com.smartquery.entity.DataDict;
import com.smartquery.mapper.DataDictMapper;
import com.smartquery.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 自动修复 Hook — 在 SQL/Python 执行失败时，注入修复提示到 tool_result 中
 *
 * <p>工作原理: 不直接修复代码，而是在 tool_result 中附加诊断提示，
 * 让 LLM 在下一轮自行修正。这比自动重写更安全且更灵活。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoRepairHook implements ToolHook {

    private final DataDictMapper dataDictMapper;

    private static final Pattern COLUMN_NOT_FOUND =
        Pattern.compile("(?i)(unknown column|column ['\"]([^'\"]+)['\"].*not found|cannot find|no column)");
    private static final Pattern TABLE_NOT_FOUND =
        Pattern.compile("(?i)(table ['\"]([^'\"]+)['\"].*doesn'?t exist|unknown table|table not found)");
    private static final Pattern SQL_SYNTAX_ERROR =
        Pattern.compile("(?i)(syntax error|you have an error in your sql|sql syntax)");
    private static final Pattern PYTHON_NAME_ERROR =
        Pattern.compile("(?i)NameError:\\s*name ['\"]([^'\"]+)['\"]");
    private static final Pattern PYTHON_KEY_ERROR =
        Pattern.compile("(?i)KeyError:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern PYTHON_IMPORT_ERROR =
        Pattern.compile("(?i)(ImportError|ModuleNotFoundError)");

    @Override
    public String name() { return "auto-repair"; }

    @Override
    public int order() { return 50; }

    /**
     * 公开方法: 构建修复提示，供 ReActEngine 在构建 tool_result 时注入
     */
    public String buildRepairHint(String toolName, String error, Map<String, Object> input, ToolExecutionContext context) {
        if (error == null || error.isBlank()) return null;
        return switch (toolName) {
            case "execute_sql" -> buildSqlRepairHint(error, input, context);
            case "execute_python" -> buildPythonRepairHint(error);
            default -> null;
        };
    }

    @Override
    public void afterToolCall(String toolName, Map<String, Object> input, ToolResult result, ToolExecutionContext context) {
        if (result.success()) return;

        String hint = buildRepairHint(toolName, result.error(), input, context);
        if (hint != null && !hint.isBlank()) {
            log.info("[HOOK][auto-repair] {} hint: {}", toolName, hint.substring(0, Math.min(hint.length(), 100)));
        }
    }

    private String buildSqlRepairHint(String error, Map<String, Object> input, ToolExecutionContext context) {
        List<String> hints = new ArrayList<>();

        if (COLUMN_NOT_FOUND.matcher(error).find()) {
            hints.add(buildColumnHint(error, context));
        }

        if (TABLE_NOT_FOUND.matcher(error).find()) {
            hints.add(buildTableHint(error, context));
        }

        if (SQL_SYNTAX_ERROR.matcher(error).find()) {
            hints.add("SQL 语法错误。常见原因: 缺少逗号、括号不匹配、关键词拼写错误、字符串引号不匹配。请检查 SQL 语句的结构。");
        }

        if (error.contains("timeout") || error.contains("Timeout")) {
            hints.add("查询超时。建议: 添加 LIMIT 限制返回行数, 或添加 WHERE 条件缩小查询范围, 或使用索引字段过滤。");
        }

        if (error.contains("Duplicate column") || error.contains("Ambiguous")) {
            String sql = (String) input.get("sql");
            if (sql != null) {
                hints.add("列名歧义。当多表 JOIN 时，请在列名前加上表名前缀 (如 t1.column_name)。");
            }
        }

        return hints.isEmpty() ? null : String.join(" ", hints);
    }

    private String buildColumnHint(String error, ToolExecutionContext context) {
        // Try to extract the column name from error
        Matcher m = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(error);
        if (!m.find()) return "列名不存在。请使用 schema_explore 工具查看表的实际列名。";

        String wrongCol = m.group(1);
        if (context.dataSourceId() == null) {
            return String.format("列名 '%s' 不存在。请使用 schema_explore 工具查看正确的列名。", wrongCol);
        }

        // Find similar column names from the schema
        try {
            List<DataDict> dicts = dataDictMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataDict>()
                    .eq(DataDict::getDataSourceId, context.dataSourceId()));

            Set<String> allColumns = dicts.stream()
                .map(DataDict::getColumnName)
                .collect(Collectors.toSet());

            List<String> suggestions = allColumns.stream()
                .filter(col -> isSimilar(col, wrongCol))
                .limit(3)
                .collect(Collectors.toList());

            if (!suggestions.isEmpty()) {
                return String.format("列名 '%s' 不存在。你可能想用: %s。请确认后重试。",
                    wrongCol, String.join(", ", suggestions));
            }
        } catch (Exception e) {
            log.debug("[HOOK][auto-repair] failed to query schema for column hint: {}", e.getMessage());
        }

        return String.format("列名 '%s' 不存在。请使用 schema_explore 工具查看表的实际列名。", wrongCol);
    }

    private String buildTableHint(String error, ToolExecutionContext context) {
        Matcher m = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(error);
        if (!m.find()) return "表不存在。请使用 schema_explore 工具查看可用的表名。";

        String wrongTable = m.group(1);

        if (context.dataSourceId() != null) {
            try {
                List<DataDict> dicts = dataDictMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataDict>()
                        .eq(DataDict::getDataSourceId, context.dataSourceId())
                        .groupBy(DataDict::getTableName)
                        .select(DataDict::getTableName));

                Set<String> allTables = dicts.stream()
                    .map(DataDict::getTableName)
                    .collect(Collectors.toSet());

                List<String> suggestions = allTables.stream()
                    .filter(t -> isSimilar(t, wrongTable))
                    .limit(3)
                    .collect(Collectors.toList());

                if (!suggestions.isEmpty()) {
                    return String.format("表 '%s' 不存在。你可能想用: %s。", wrongTable, String.join(", ", suggestions));
                }
            } catch (Exception e) {
                log.debug("[HOOK][auto-repair] failed to query schema for table hint: {}", e.getMessage());
            }
        }

        return String.format("表 '%s' 不存在。请使用 schema_explore 工具查看可用的表名。", wrongTable);
    }

    private String buildPythonRepairHint(String error) {
        List<String> hints = new ArrayList<>();

        Matcher nameMatcher = PYTHON_NAME_ERROR.matcher(error);
        if (nameMatcher.find()) {
            hints.add(String.format("NameError: 变量 '%s' 未定义。请检查: 是否拼写正确、是否已赋值、是否在正确的作用域中。", nameMatcher.group(1)));
        }

        Matcher keyMatcher = PYTHON_KEY_ERROR.matcher(error);
        if (keyMatcher.find()) {
            hints.add(String.format("KeyError: 键 '%s' 不存在。请检查 DataFrame 列名或字典键名是否正确。可用 df.columns 查看所有列名。", keyMatcher.group(1)));
        }

        if (PYTHON_IMPORT_ERROR.matcher(error).find()) {
            hints.add("导入错误。当前环境已预装: pandas, numpy, scipy, sklearn, matplotlib, sqlalchemy。如需其他库请确认是否可用。");
        }

        if (error.contains("IndexError")) {
            hints.add("索引越界。请检查数组/列表访问索引是否在有效范围内。");
        }

        if (error.contains("TypeError")) {
            hints.add("类型错误。常见原因: 对 None 值操作、数据类型不匹配、函数参数类型错误。建议用 print(type(x)) 检查变量类型。");
        }

        if (error.contains("ZeroDivisionError")) {
            hints.add("除零错误。请在除法运算前检查分母是否为零。");
        }

        return hints.isEmpty() ? null : String.join(" ", hints);
    }

    /**
     * 简单的相似度检查: 编辑距离 <= 2 或包含子串
     */
    private boolean isSimilar(String actual, String wrong) {
        String a = actual.toLowerCase();
        String w = wrong.toLowerCase();
        if (a.contains(w) || w.contains(a)) return true;
        if (Math.abs(a.length() - w.length()) > 2) return false;
        return levenshteinDistance(a, w) <= 2;
    }

    private int levenshteinDistance(String s1, String s2) {
        int[] prev = new int[s2.length() + 1];
        for (int j = 0; j <= s2.length(); j++) prev[j] = j;

        for (int i = 1; i <= s1.length(); i++) {
            int[] curr = new int[s2.length() + 1];
            curr[0] = i;
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = curr;
        }
        return prev[s2.length()];
    }
}
