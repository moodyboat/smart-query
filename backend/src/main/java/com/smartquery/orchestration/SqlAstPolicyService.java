package com.smartquery.orchestration;

import com.smartquery.common.BusinessException;
import com.smartquery.tool.SqlSafetyValidator;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and locks the safe subset accepted by immutable SQL_AST versions. */
@Service
public class SqlAstPolicyService {
    private static final Pattern NAMED_PARAMETER = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern COMMENT = Pattern.compile("(--|/\\*|\\*/|(^|\\s)#)");
    private static final Pattern QUESTION_MARK = Pattern.compile("\\?");
    private static final Pattern FOR_UPDATE = Pattern.compile("\\bFOR\\s+UPDATE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_INTO = Pattern.compile("\\bINTO\\b", Pattern.CASE_INSENSITIVE);

    private final SqlSafetyValidator sqlSafetyValidator;
    private final DataSourceQueryPolicyService dataSourcePolicyService;

    public SqlAstPolicyService(SqlSafetyValidator sqlSafetyValidator,
                               DataSourceQueryPolicyService dataSourcePolicyService) {
        this.sqlSafetyValidator = sqlSafetyValidator;
        this.dataSourcePolicyService = dataSourcePolicyService;
    }

    public SqlAstSpec validate(Map<String, Object> payload) {
        Long dataSourceId = positiveLong(payload.get("dataSourceId"), "dataSourceId");
        dataSourcePolicyService.requireQueryable(dataSourceId);
        String sql = requiredText(payload.get("sql"), "SQL_AST.sql不能为空");
        if (sql.length() > 50_000) throw new BusinessException(422, "SQL_AST.sql不能超过50000字符");
        if (COMMENT.matcher(sql).find()) throw new BusinessException(422, "SQL_AST不允许注释");
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        if (trimmed.contains(";")) throw new BusinessException(422, "SQL_AST只允许单条语句");
        if (QUESTION_MARK.matcher(trimmed).find()) {
            throw new BusinessException(422, "SQL_AST只允许:name形式的命名参数，禁止?占位符");
        }
        if (FOR_UPDATE.matcher(trimmed).find() || SELECT_INTO.matcher(trimmed).find()) {
            throw new BusinessException(422, "SQL_AST禁止FOR UPDATE和SELECT INTO");
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(trimmed);
            if (!(statement instanceof Select)) {
                throw new BusinessException(422, "SQL_AST只允许可解析的SELECT/CTE查询");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(422, "SQL_AST解析失败: " + e.getMessage());
        }

        Set<String> allowedTables = normalizedTables(payload.get("allowedTables"));
        if (allowedTables.isEmpty()) throw new BusinessException(422, "SQL_AST必须声明非空allowedTables");
        SqlSafetyValidator.ValidationResult validation =
            sqlSafetyValidator.validateAgainstWhitelist(trimmed, allowedTables);
        if (!validation.safe()) throw new BusinessException(422, "SQL_AST安全检查未通过: " + validation.reason());
        Set<String> usedTables = sqlSafetyValidator.extractTableNames(trimmed);
        if (usedTables.isEmpty()) throw new BusinessException(422, "SQL_AST必须查询至少一张授权表");

        int maxRows = boundedInt(payload.get("maxRows"), 1000, 1, 10_000, "maxRows");
        int timeoutSeconds = boundedInt(payload.get("timeoutSeconds"), 30, 1, 120, "timeoutSeconds");
        Map<String, Object> defaults = map(payload.get("defaultParameters"), "defaultParameters");
        validateParameterValues(defaults);
        Set<String> requiredParameters = new LinkedHashSet<>();
        Matcher matcher = NAMED_PARAMETER.matcher(trimmed);
        while (matcher.find()) requiredParameters.add(matcher.group(1));
        List<String> sourceRefFields = stringList(payload.get("sourceRefFields"), "sourceRefFields", 20);
        sourceRefFields.forEach(field -> {
            if (!field.matches("^[\\p{L}_$][\\p{L}\\p{N}_$-]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$-]*)*$")) {
                throw new BusinessException(422, "sourceRefFields字段路径不合法: " + field);
            }
        });
        return new SqlAstSpec(dataSourceId, trimmed, Set.copyOf(allowedTables), Set.copyOf(usedTables),
            maxRows, timeoutSeconds, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(defaults)), Set.copyOf(requiredParameters),
            List.copyOf(sourceRefFields));
    }

    public Map<String, Object> parameters(SqlAstSpec spec, Map<String, Object> nodeConfig,
                                          Map<String, Object> runInput) {
        validateNodeConfig(nodeConfig);
        Map<String, Object> result = new LinkedHashMap<>(spec.defaultParameters());
        result.putAll(map(nodeConfig == null ? null : nodeConfig.get("parameters"), "节点parameters"));
        result.putAll(map(runInput == null ? null : runInput.get("parameters"), "试运行parameters"));
        validateParameterValues(result);
        List<String> missing = spec.requiredParameters().stream()
            .filter(name -> !result.containsKey(name)).sorted().toList();
        if (!missing.isEmpty()) throw new BusinessException(422, "SQL_AST缺少命名参数: " + missing);
        return result;
    }

    public void validateNodeConfig(Map<String, Object> config) {
        if (config == null) return;
        for (String field : List.of("sql", "dataSourceId", "allowedTables", "maxRows", "timeoutSeconds")) {
            if (config.containsKey(field)) throw new BusinessException(422, "SQL_AST节点不能覆盖版本字段: " + field);
        }
    }

    private void validateParameterValues(Map<String, Object> parameters) {
        if (parameters.size() > 100) throw new BusinessException(422, "SQL_AST参数不能超过100个");
        parameters.forEach((name, value) -> {
            if (!name.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
                throw new BusinessException(422, "SQL_AST参数名不合法: " + name);
            }
            if (!safeValue(value)) throw new BusinessException(422, "SQL_AST参数值只允许标量或标量数组: " + name);
        });
    }

    private boolean safeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return true;
        if (value instanceof List<?> list && list.size() <= 1000) return list.stream().allMatch(this::safeValue);
        return false;
    }

    private Set<String> normalizedTables(Object raw) {
        List<String> tables = stringList(raw, "allowedTables", 100);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        tables.forEach(value -> {
            String normalized = SqlSafetyValidator.normalizeTableName(value);
            if (normalized == null || normalized.isBlank()) throw new BusinessException(422, "allowedTables包含空表名");
            result.add(normalized);
        });
        return result;
    }

    private List<String> stringList(Object raw, String field, int max) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new BusinessException(422, field + "必须是数组");
        if (list.size() > max) throw new BusinessException(422, field + "不能超过" + max + "项");
        List<String> result = new ArrayList<>();
        for (Object value : list) result.add(requiredText(value, field + "不能包含空值"));
        return result;
    }

    private Map<String, Object> map(Object raw, String field) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map)) throw new BusinessException(422, field + "必须是对象");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Long positiveLong(Object value, String field) {
        Long result = DagValidationService.toLong(value);
        if (result == null || result <= 0) throw new BusinessException(422, field + "必须是正整数");
        return result;
    }

    private int boundedInt(Object value, int fallback, int min, int max, String field) {
        if (value == null) return fallback;
        int result;
        try { result = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value)); }
        catch (Exception e) { throw new BusinessException(422, field + "必须是整数"); }
        if (result < min || result > max) throw new BusinessException(422, field + "必须在" + min + "到" + max + "之间");
        return result;
    }

    private String requiredText(Object value, String message) {
        String result = value == null ? "" : String.valueOf(value).trim();
        if (result.isEmpty()) throw new BusinessException(422, message);
        return result;
    }

    public record SqlAstSpec(Long dataSourceId, String sql, Set<String> allowedTables,
                             Set<String> usedTables, int maxRows, int timeoutSeconds,
                             Map<String, Object> defaultParameters, Set<String> requiredParameters,
                             List<String> sourceRefFields) {}
}
