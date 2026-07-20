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
 * 增强的自动修复 Hook — 在 SQL/Python 执行失败时，提供详细的修复指导
 *
 * <p>增强功能:
 * <ul>
 *   <li>提供具体的修复建议和示例</li>
 *   <li>分析错误模式并给出针对性解决方案</li>
 *   <li>鼓励LLM进行自我修正而不是放弃</li>
 * </ul>
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
            case "execute_sql" -> buildEnhancedSqlRepairHint(error, input, context);
            case "execute_python" -> buildEnhancedPythonRepairHint(error);
            case "generate_chart" -> buildChartRepairHint(error, input);
            default -> buildGenericRepairHint(error);
        };
    }

    @Override
    public void afterToolCall(String toolName, Map<String, Object> input, ToolResult result, ToolExecutionContext context) {
        if (result.success()) return;

        String hint = buildRepairHint(toolName, result.error(), input, context);
        if (hint != null && !hint.isBlank()) {
            log.info("[HOOK][auto-repair] {} repair hint generated", toolName);
        }
    }

    /**
     * 增强的SQL修复提示
     */
    private String buildEnhancedSqlRepairHint(String error, Map<String, Object> input, ToolExecutionContext context) {
        List<String> hints = new ArrayList<>();

        // 如果是安全检查错误，提供简洁的修复指导
        if (error.contains("不支持的 SQL 类型") || error.contains("包含禁止的关键字")) {
            hints.add("💡 SQL 安全检查未通过");
            if (error.contains("--")) {
                hints.add("• 建议：移除 SQL 开头的注释，或使用 /* */ 风格的注释");
            }
            hints.add("• 请确保 SQL 以允许的关键字开头: SELECT, SHOW, DESCRIBE, EXPLAIN");
            return String.join("\n", hints);
        }

        if (COLUMN_NOT_FOUND.matcher(error).find()) {
            hints.add(buildDetailedColumnHint(error, context));
        }

        if (TABLE_NOT_FOUND.matcher(error).find()) {
            hints.add(buildDetailedTableHint(error, context));
        }

        if (SQL_SYNTAX_ERROR.matcher(error).find()) {
            hints.add(buildSqlSyntaxHint(error, input));
        }

        if (error.contains("timeout") || error.contains("Timeout")) {
            hints.add("⏰ 查询超时。建议添加 LIMIT 限制返回行数，或优化 WHERE 条件。");
        }

        if (error.contains("Duplicate column") || error.contains("Ambiguous")) {
            hints.add("🔀 列名歧义。在多表 JOIN 时，请在列名前加上表名前缀，例如: t1.column_name, t2.column_name");
        }

        if (error.contains("GROUP BY") && error.contains("invalid")) {
            hints.add("📊 GROUP BY 错误。SELECT 中的非聚合列必须出现在 GROUP BY 子句中。");
        }

        if (hints.isEmpty()) {
            hints.add("💡 请根据错误提示修正后重试");
        }

        return String.join("\n", hints);
    }

    /**
     * 详细的列名错误提示
     */
    private String buildDetailedColumnHint(String error, ToolExecutionContext context) {
        Matcher m = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(error);
        if (!m.find()) {
            return "🔍 列名不存在 → 请使用 schema_explore 工具查看表的实际列名";
        }

        String wrongCol = m.group(1);

        if (context.dataSourceId() == null) {
            return String.format("🔍 列名 '%s' 不存在 → 请使用 schema_explore 工具查看正确的列名", wrongCol);
        }

        try {
            List<DataDict> dicts = dataDictMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataDict>()
                    .eq(DataDict::getDataSourceId, context.dataSourceId()));

            Set<String> allColumns = dicts.stream()
                .map(DataDict::getColumnName)
                .collect(Collectors.toSet());

            List<String> suggestions = allColumns.stream()
                .filter(col -> isSimilar(col, wrongCol))
                .limit(5)
                .collect(Collectors.toList());

            if (!suggestions.isEmpty()) {
                return String.format("🔍 列名 '%s' 不存在\n💡 您可能想用: %s\n📋 建议: 请仔细检查列名的拼写，特别注意大小写和下划线",
                    wrongCol, String.join(", ", suggestions));
            }
        } catch (Exception e) {
            log.debug("[HOOK][auto-repair] failed to query schema for column hint: {}", e.getMessage());
        }

        return String.format("🔍 列名 '%s' 不存在\n💡 建议: 使用 schema_explore 工具查看表结构，确认正确的列名", wrongCol);
    }

    /**
     * 详细的表名错误提示
     */
    private String buildDetailedTableHint(String error, ToolExecutionContext context) {
        Matcher m = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(error);
        if (!m.find()) {
            return "🔍 表名不存在 → 请使用 schema_explore 工具查看可用的表名";
        }

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
                    .limit(5)
                    .collect(Collectors.toList());

                if (!suggestions.isEmpty()) {
                    return String.format("🔍 表名 '%s' 不存在\n💡 您可能想用: %s\n📋 建议: 请仔细检查表名的拼写，特别注意大小写",
                        wrongTable, String.join(", ", suggestions));
                }
            } catch (Exception e) {
                log.debug("[HOOK][auto-repair] failed to query schema for table hint: {}", e.getMessage());
            }
        }

        return String.format("🔍 表名 '%s' 不存在\n💡 建议: 使用 schema_explore 工具查看数据库中的所有表名", wrongTable);
    }

    /**
     * SQL语法错误的具体提示
     */
    private String buildSqlSyntaxHint(String error, Map<String, Object> input) {
        List<String> syntaxHints = new ArrayList<>();

        String sql = (String) input.get("sql");
        if (sql != null) {
            // 检查常见的语法错误模式
            if (!sql.matches(".*[;]$")) {
                syntaxHints.add("📝 SQL语句可能缺少结尾的分号");
            }
            if (sql.chars().filter(ch -> ch == '(').count() != sql.chars().filter(ch -> ch == ')').count()) {
                syntaxHints.add("📝 括号数量不匹配，请检查每个开括号都有对应的闭括号");
            }
            if (sql.contains("'") && !sql.matches(".*'.*'")) {
                syntaxHints.add("📝 单引号可能未正确配对，确保每个字符串都有开始和结束的单引号");
            }
        }

        syntaxHints.add("🔧 常见SQL语法错误: 缺少逗号、括号不匹配、引号不匹配、关键词拼写错误");
        syntaxHints.add("💡 建议: 请仔细检查SQL语句结构，确保每个关键字、表名、字段名都正确");

        return String.join("\n", syntaxHints);
    }

    /**
     * 增强的Python修复提示
     */
    private String buildEnhancedPythonRepairHint(String error) {
        List<String> hints = new ArrayList<>();

        Matcher nameMatcher = PYTHON_NAME_ERROR.matcher(error);
        if (nameMatcher.find()) {
            hints.add(String.format("💡 NameError: '%s' 未定义，检查拼写或赋值", nameMatcher.group(1)));
        }

        Matcher keyMatcher = PYTHON_KEY_ERROR.matcher(error);
        if (keyMatcher.find()) {
            hints.add(String.format("💡 KeyError: 键 '%s' 不存在，用 print(df.columns) 查看可用键", keyMatcher.group(1)));
        }

        if (PYTHON_IMPORT_ERROR.matcher(error).find()) {
            hints.add("💡 导入错误: 当前已预装 pandas, numpy, scipy, sklearn, matplotlib");
        }

        if (error.contains("IndexError")) {
            hints.add("💡 索引越界: 使用 len() 检查有效范围");
        }

        if (error.contains("TypeError")) {
            hints.add("💡 类型错误: 使用 print(type(x)) 检查变量类型");
        }

        if (error.contains("ZeroDivisionError")) {
            hints.add("💡 除零错误: 使用 try-except 或检查分母");
        }

        if (error.contains("AttributeError")) {
            hints.add("💡 属性错误: 使用 dir() 查看可用属性");
        }

        if (hints.isEmpty()) {
            hints.add("💡 Python 错误，请根据提示修正代码");
        }

        return String.join("\n", hints);
    }

    /**
     * 图表生成错误的修复提示
     */
    private String buildChartRepairHint(String error, Map<String, Object> input) {
        List<String> hints = new ArrayList<>();

        if (error.contains("option") || error.contains("echarts")) {
            hints.add("💡 ECharts配置错误: 检查 option 结构和 data 格式");
        }

        if (error.contains("data") || error.contains("empty")) {
            hints.add("💡 数据为空: 确保传入的数据不为空且格式正确");
        }

        if (hints.isEmpty()) {
            hints.add("💡 图表生成失败，请检查配置和数据");
        }

        return String.join("\n", hints);
    }

    /**
     * 通用错误修复提示
     */
    private String buildGenericRepairHint(String error) {
        return "💡 工具执行失败: " + truncateString(error, 100) + "\n请分析错误原因后修正重试";
    }

    /**
     * 截断字符串用于显示
     */
    private String truncateString(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
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
