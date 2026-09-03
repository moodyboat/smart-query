package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.common.UserRoles;
import com.smartquery.entity.OutputArtifact;
import com.smartquery.entity.OutputArtifactRow;
import com.smartquery.mapper.OutputArtifactMapper;
import com.smartquery.mapper.OutputArtifactRowMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Portable typed filtering, allowlisted sorting and signed keyset pagination for output rows. */
@Service
public class OutputArtifactQueryService {
    private static final Set<String> OPERATORS = Set.of(
        "EQ", "NE", "CONTAINS", "STARTS_WITH", "GT", "GTE", "LT", "LTE",
        "BETWEEN", "IN", "IS_NULL", "NOT_NULL");
    private static final Set<String> STRING_OPERATORS = Set.of(
        "EQ", "NE", "CONTAINS", "STARTS_WITH", "GT", "GTE", "LT", "LTE", "BETWEEN", "IN");
    private static final Set<String> NUMBER_OPERATORS = Set.of(
        "EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IN");
    private static final Set<String> BOOLEAN_OPERATORS = Set.of("EQ", "NE", "IN");

    private final OutputArtifactMapper artifactMapper;
    private final OutputArtifactRowMapper rowMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ContentHashService contentHashService;
    private final OutputQueryCursorCodec cursorCodec;

    @Value("${smart-query.orchestration.output-query.max-filters:10}")
    private int maxFilters = 10;

    @Value("${smart-query.orchestration.output-query.max-in-values:50}")
    private int maxInValues = 50;

    @Value("${smart-query.orchestration.output-query.max-value-length:1000}")
    private int maxValueLength = 1000;

    public OutputArtifactQueryService(OutputArtifactMapper artifactMapper,
                                      OutputArtifactRowMapper rowMapper,
                                      JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                      ContentHashService contentHashService,
                                      OutputQueryCursorCodec cursorCodec) {
        this.artifactMapper = artifactMapper;
        this.rowMapper = rowMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.contentHashService = contentHashService;
        this.cursorCodec = cursorCodec;
    }

    @Transactional(readOnly = true)
    public QueryResult query(Long artifactId, QueryRequest request) {
        OutputArtifact artifact = requireArtifact(artifactId);
        QueryRequest raw = request == null ? new QueryRequest(null, null, List.of(), null) : request;
        int pageSize = Math.max(1, Math.min(raw.pageSize() == null ? 50 : raw.pageSize(), 200));
        boolean indexed = "READY".equals(artifact.getQueryIndexStatus());
        List<QueryField> queryFields = indexed ? fieldCatalog(artifactId) : List.of();
        Map<String, QueryField> fieldsByName = new LinkedHashMap<>();
        queryFields.forEach(field -> fieldsByName.put(field.field(), field));
        List<NormalizedFilter> filters = normalizeFilters(raw.filters(), fieldsByName, indexed);
        NormalizedSort sort = normalizeSort(raw.sort(), fieldsByName, indexed);
        String queryHash = contentHashService.sha256(Map.of(
            "artifactId", artifactId, "pageSize", pageSize,
            "filters", filters, "sort", sort));
        OutputQueryCursorCodec.CursorState cursor = raw.cursor() == null || raw.cursor().isBlank()
            ? null : cursorCodec.decode(raw.cursor(), artifactId, queryHash);

        WhereSql where = whereSql(artifactId, filters);
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sq_output_artifact_row r"
            + where.sql(), Long.class, where.arguments().toArray());
        List<RowKey> keys = sort.field() == null
            ? queryByRowIndex(where, sort, cursor, pageSize + 1)
            : queryByField(artifactId, where, sort, cursor, pageSize + 1);
        boolean hasMore = keys.size() > pageSize;
        if (hasMore) keys = new ArrayList<>(keys.subList(0, pageSize));
        List<QueryRow> rows = loadRows(artifactId, keys);
        String nextCursor = hasMore && !keys.isEmpty()
            ? cursorCodec.encode(artifactId, keys.get(keys.size() - 1).rowIndex(), queryHash) : null;
        Map<String, Object> contentSpec = parseMap(artifact.getContentSpec());
        return new QueryResult(artifact, contentSpec, parseMap(artifact.getArtifactData()),
            pageSize, count == null ? 0 : count, indexed, nextCursor, hasMore,
            columns(contentSpec, queryFields, rows), queryFields, rows);
    }

    private List<RowKey> queryByRowIndex(WhereSql where, NormalizedSort sort,
                                         OutputQueryCursorCodec.CursorState cursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT r.row_index FROM sq_output_artifact_row r")
            .append(where.sql());
        List<Object> arguments = new ArrayList<>(where.arguments());
        if (cursor != null) {
            sql.append(" AND r.row_index ").append("DESC".equals(sort.direction()) ? "<" : ">")
                .append(" ?");
            arguments.add(cursor.rowIndex());
        }
        sql.append(" ORDER BY r.row_index ").append(sort.direction()).append(" LIMIT ?");
        arguments.add(limit);
        return jdbcTemplate.query(sql.toString(),
            (result, row) -> new RowKey(result.getInt("row_index")), arguments.toArray());
    }

    private List<RowKey> queryByField(Long artifactId, WhereSql where, NormalizedSort sort,
                                      OutputQueryCursorCodec.CursorState cursor, int limit) {
        SortColumn column = sortColumn(sort.valueType());
        String bucket = "CASE WHEN s.value_type = '" + sort.valueType() + "' AND s."
            + column.column() + " IS NOT NULL THEN 0 ELSE 1 END";
        StringBuilder sql = new StringBuilder("SELECT q.row_index, q.sort_bucket, q.sort_value FROM (")
            .append("SELECT r.row_index, ").append(bucket).append(" AS sort_bucket, s.")
            .append(column.column()).append(" AS sort_value FROM sq_output_artifact_row r ")
            .append("LEFT JOIN sq_output_artifact_cell s ON s.artifact_id = r.artifact_id ")
            .append("AND s.row_index = r.row_index AND s.field_path = ?")
            .append(where.sql()).append(") q");
        List<Object> arguments = new ArrayList<>();
        arguments.add(sort.field());
        arguments.addAll(where.arguments());
        if (cursor != null) {
            SortAnchor anchor = sortAnchor(artifactId, cursor.rowIndex(), sort, column);
            appendCursorPredicate(sql, arguments, sort, anchor);
        }
        sql.append(" ORDER BY q.sort_bucket ASC, q.sort_value ").append(sort.direction())
            .append(", q.row_index ").append(sort.direction()).append(" LIMIT ?");
        arguments.add(limit);
        return jdbcTemplate.query(sql.toString(),
            (result, row) -> new RowKey(result.getInt("row_index")), arguments.toArray());
    }

    private SortAnchor sortAnchor(Long artifactId, int rowIndex, NormalizedSort sort,
                                  SortColumn column) {
        String bucket = "CASE WHEN s.value_type = '" + sort.valueType() + "' AND s."
            + column.column() + " IS NOT NULL THEN 0 ELSE 1 END";
        String sql = "SELECT " + bucket + " AS sort_bucket, s." + column.column()
            + " AS sort_value FROM sq_output_artifact_row r LEFT JOIN sq_output_artifact_cell s"
            + " ON s.artifact_id = r.artifact_id AND s.row_index = r.row_index AND s.field_path = ?"
            + " WHERE r.artifact_id = ? AND r.row_index = ?";
        List<SortAnchor> anchors = jdbcTemplate.query(sql, (result, row) ->
            new SortAnchor(result.getInt("sort_bucket"), result.getObject("sort_value"), rowIndex),
            sort.field(), artifactId, rowIndex);
        if (anchors.size() != 1) throw new BusinessException(400, "结果查询游标指向的行不存在");
        return anchors.get(0);
    }

    private void appendCursorPredicate(StringBuilder sql, List<Object> arguments,
                                       NormalizedSort sort, SortAnchor anchor) {
        String comparator = "DESC".equals(sort.direction()) ? "<" : ">";
        sql.append(" WHERE (q.sort_bucket > ? OR (q.sort_bucket = ? AND ");
        arguments.add(anchor.bucket());
        arguments.add(anchor.bucket());
        if (anchor.bucket() == 0) {
            sql.append("(q.sort_value ").append(comparator)
                .append(" ? OR (q.sort_value = ? AND q.row_index ")
                .append(comparator).append(" ?))" );
            Object value = normalizeJdbcValue(anchor.value(), sort.valueType());
            arguments.add(value);
            arguments.add(value);
            arguments.add(anchor.rowIndex());
        } else {
            sql.append("q.row_index ").append(comparator).append(" ?");
            arguments.add(anchor.rowIndex());
        }
        sql.append("))");
    }

    private WhereSql whereSql(Long artifactId, List<NormalizedFilter> filters) {
        StringBuilder sql = new StringBuilder(" WHERE r.artifact_id = ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(artifactId);
        for (int index = 0; index < filters.size(); index++) {
            NormalizedFilter filter = filters.get(index);
            String alias = "f" + index;
            if ("IS_NULL".equals(filter.operator())) {
                sql.append(" AND NOT EXISTS (SELECT 1 FROM sq_output_artifact_cell ").append(alias)
                    .append(" WHERE ").append(alias).append(".artifact_id = r.artifact_id AND ")
                    .append(alias).append(".row_index = r.row_index AND ").append(alias)
                    .append(".field_path = ? AND ").append(alias).append(".value_type <> 'NULL')");
                arguments.add(filter.field());
                continue;
            }
            if ("NOT_NULL".equals(filter.operator())) {
                sql.append(" AND EXISTS (SELECT 1 FROM sq_output_artifact_cell ").append(alias)
                    .append(" WHERE ").append(alias).append(".artifact_id = r.artifact_id AND ")
                    .append(alias).append(".row_index = r.row_index AND ").append(alias)
                    .append(".field_path = ? AND ").append(alias).append(".value_type <> 'NULL')");
                arguments.add(filter.field());
                continue;
            }
            sql.append(" AND EXISTS (SELECT 1 FROM sq_output_artifact_cell ").append(alias)
                .append(" WHERE ").append(alias).append(".artifact_id = r.artifact_id AND ")
                .append(alias).append(".row_index = r.row_index AND ").append(alias)
                .append(".field_path = ? AND ").append(alias).append(".value_type = '")
                .append(filter.valueType()).append("' AND ");
            arguments.add(filter.field());
            appendValuePredicate(sql, arguments, alias, filter);
            sql.append(")");
        }
        return new WhereSql(sql.toString(), arguments);
    }

    private void appendValuePredicate(StringBuilder sql, List<Object> arguments,
                                      String alias, NormalizedFilter filter) {
        String column = "STRING".equals(filter.valueType())
            ? "text_sort_value" : sortColumn(filter.valueType()).column();
        String expression = alias + "." + column;
        switch (filter.operator()) {
            case "EQ" -> appendEqual(sql, arguments, alias, filter.valueType(), filter.value(), false);
            case "NE" -> appendEqual(sql, arguments, alias, filter.valueType(), filter.value(), true);
            case "CONTAINS" -> {
                sql.append(alias).append(".text_value LIKE ? ESCAPE '!'");
                arguments.add("%" + escapeLike(String.valueOf(filter.value())) + "%");
            }
            case "STARTS_WITH" -> {
                sql.append(alias).append(".text_value LIKE ? ESCAPE '!'");
                arguments.add(escapeLike(String.valueOf(filter.value())) + "%");
            }
            case "GT", "GTE", "LT", "LTE" -> {
                String comparator = Map.of("GT", ">", "GTE", ">=", "LT", "<", "LTE", "<=")
                    .get(filter.operator());
                sql.append(expression).append(' ').append(comparator).append(" ?");
                arguments.add(filter.value());
            }
            case "BETWEEN" -> {
                sql.append(expression).append(" BETWEEN ? AND ?");
                arguments.add(filter.value());
                arguments.add(filter.secondValue());
            }
            case "IN" -> appendIn(sql, arguments, alias, filter);
            default -> throw new BusinessException(422, "不支持的结果筛选操作符: " + filter.operator());
        }
    }

    private void appendEqual(StringBuilder sql, List<Object> arguments, String alias,
                             String type, Object value, boolean negate) {
        if (negate) sql.append("NOT (");
        if ("STRING".equals(type)) {
            sql.append(alias).append(".value_hash = ? AND ").append(alias).append(".text_value = ?");
            arguments.add(contentHashService.sha256(value));
            arguments.add(value);
        } else {
            sql.append(alias).append('.').append(sortColumn(type).column()).append(" = ?");
            arguments.add(value);
        }
        if (negate) sql.append(')');
    }

    @SuppressWarnings("unchecked")
    private void appendIn(StringBuilder sql, List<Object> arguments, String alias,
                          NormalizedFilter filter) {
        List<Object> values = (List<Object>) filter.value();
        if ("STRING".equals(filter.valueType())) {
            sql.append('(');
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) sql.append(" OR ");
                sql.append('(').append(alias).append(".value_hash = ? AND ").append(alias)
                    .append(".text_value = ?)");
                arguments.add(contentHashService.sha256(values.get(index)));
                arguments.add(values.get(index));
            }
            sql.append(')');
            return;
        }
        sql.append(alias).append('.').append(sortColumn(filter.valueType()).column()).append(" IN (");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) sql.append(',');
            sql.append('?');
            arguments.add(values.get(index));
        }
        sql.append(')');
    }

    private List<NormalizedFilter> normalizeFilters(List<FilterRequest> raw,
                                                     Map<String, QueryField> fields,
                                                     boolean indexed) {
        List<FilterRequest> input = raw == null ? List.of() : raw;
        if (input.size() > Math.max(0, maxFilters)) {
            throw new BusinessException(422, "结果筛选条件不能超过" + maxFilters + "个");
        }
        if (!indexed && !input.isEmpty()) throw legacyIndexRequired();
        List<NormalizedFilter> result = new ArrayList<>();
        for (FilterRequest item : input) {
            if (item == null) throw new BusinessException(422, "结果筛选条件不能为空");
            String field = requiredField(item.field(), fields);
            QueryField queryField = fields.get(field);
            String operator = text(item.operator()).toUpperCase(Locale.ROOT);
            if (!OPERATORS.contains(operator)) throw new BusinessException(422, "结果筛选操作符不受支持: " + operator);
            if (Set.of("IS_NULL", "NOT_NULL").contains(operator)) {
                result.add(new NormalizedFilter(field, operator, queryField.valueType(), null, null));
                continue;
            }
            if (!queryField.filterable()) throw new BusinessException(422, "字段类型不稳定，不能筛选: " + field);
            validateOperator(queryField.valueType(), operator);
            Object value = "IN".equals(operator)
                ? normalizeList(item.value(), queryField.valueType())
                : normalizeValue(item.value(), queryField.valueType());
            Object second = "BETWEEN".equals(operator)
                ? normalizeValue(item.secondValue(), queryField.valueType()) : null;
            result.add(new NormalizedFilter(field, operator, queryField.valueType(), value, second));
        }
        return List.copyOf(result);
    }

    private NormalizedSort normalizeSort(SortRequest raw, Map<String, QueryField> fields,
                                         boolean indexed) {
        String direction = raw == null || raw.direction() == null
            ? "ASC" : raw.direction().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) {
            throw new BusinessException(422, "排序方向只能是ASC或DESC");
        }
        if (raw == null || raw.field() == null || raw.field().isBlank()) {
            return new NormalizedSort(null, direction, "ROW_INDEX");
        }
        if (!indexed) throw legacyIndexRequired();
        String field = requiredField(raw.field(), fields);
        QueryField queryField = fields.get(field);
        if (!queryField.sortable()) throw new BusinessException(422, "字段类型不稳定，不能排序: " + field);
        return new NormalizedSort(field, direction, queryField.valueType());
    }

    private void validateOperator(String type, String operator) {
        Set<String> allowed = switch (type) {
            case "NUMBER" -> NUMBER_OPERATORS;
            case "BOOLEAN" -> BOOLEAN_OPERATORS;
            default -> STRING_OPERATORS;
        };
        if (!allowed.contains(operator)) {
            throw new BusinessException(422, type + "字段不支持" + operator + "筛选");
        }
    }

    private Object normalizeValue(Object raw, String type) {
        if (raw == null) throw new BusinessException(422, "筛选值不能为空");
        return switch (type) {
            case "NUMBER" -> decimal(raw);
            case "BOOLEAN" -> bool(raw);
            default -> boundedText(raw);
        };
    }

    private List<Object> normalizeList(Object raw, String type) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException(422, "IN筛选值必须是非空数组");
        }
        if (list.size() > Math.max(1, maxInValues)) {
            throw new BusinessException(422, "IN筛选值不能超过" + maxInValues + "项");
        }
        return list.stream().map(value -> normalizeValue(value, type)).toList();
    }

    private BigDecimal decimal(Object raw) {
        try { return new BigDecimal(String.valueOf(raw)); }
        catch (NumberFormatException error) { throw new BusinessException(422, "数值筛选条件格式错误"); }
    }

    private Integer bool(Object raw) {
        if (raw instanceof Boolean value) return value ? 1 : 0;
        if ("true".equalsIgnoreCase(String.valueOf(raw))) return 1;
        if ("false".equalsIgnoreCase(String.valueOf(raw))) return 0;
        throw new BusinessException(422, "布尔筛选值只能是true或false");
    }

    private String boundedText(Object raw) {
        String value = String.valueOf(raw);
        if (value.length() > Math.max(1, maxValueLength)) {
            throw new BusinessException(422, "筛选值超过" + maxValueLength + "字符限制");
        }
        return value;
    }

    private String requiredField(String raw, Map<String, QueryField> fields) {
        String field = text(raw);
        if (field.length() > 300 || field.indexOf('\u0000') >= 0 || field.startsWith("__")) {
            throw new BusinessException(422, "结果查询字段不合法");
        }
        if (!fields.containsKey(field)) throw new BusinessException(422, "结果查询字段不在白名单中: " + field);
        return field;
    }

    private List<QueryField> fieldCatalog(Long artifactId) {
        String sql = "SELECT field_path, value_type, COUNT(*) AS observed_count "
            + "FROM sq_output_artifact_cell WHERE artifact_id = ? "
            + "GROUP BY field_path, value_type ORDER BY field_path, value_type";
        Map<String, FieldAccumulator> fields = new LinkedHashMap<>();
        jdbcTemplate.query(sql, result -> {
            String field = result.getString("field_path");
            fields.computeIfAbsent(field, ignored -> new FieldAccumulator())
                .add(result.getString("value_type"), result.getLong("observed_count"));
        }, artifactId);
        return fields.entrySet().stream().limit(300).map(entry -> entry.getValue().view(entry.getKey())).toList();
    }

    private List<QueryRow> loadRows(Long artifactId, List<RowKey> keys) {
        if (keys.isEmpty()) return List.of();
        List<Integer> indexes = keys.stream().map(RowKey::rowIndex).toList();
        List<OutputArtifactRow> stored = rowMapper.selectList(new LambdaQueryWrapper<OutputArtifactRow>()
            .eq(OutputArtifactRow::getArtifactId, artifactId)
            .in(OutputArtifactRow::getRowIndex, indexes));
        Map<Integer, OutputArtifactRow> byIndex = new LinkedHashMap<>();
        stored.forEach(row -> byIndex.put(row.getRowIndex(), row));
        List<QueryRow> result = new ArrayList<>();
        for (Integer index : indexes) {
            OutputArtifactRow row = byIndex.get(index);
            if (row == null) throw new BusinessException(409, "输出查询索引与结果行不一致");
            result.add(toQueryRow(row));
        }
        return List.copyOf(result);
    }

    private QueryRow toQueryRow(OutputArtifactRow row) {
        Map<String, Object> result = parseMap(row.getResultData());
        List<Map<String, Object>> sources = parseMaps(row.getSourceData());
        Map<String, Object> display = new LinkedHashMap<>();
        if (sources.size() == 1) display.putAll(sources.get(0));
        display.putAll(result);
        return new QueryRow(row.getRowIndex(), display, result, sources,
            parseMaps(row.getEvidenceData()), parseStrings(row.getSourceRefs()));
    }

    private List<Map<String, Object>> columns(Map<String, Object> spec,
                                              List<QueryField> fields, List<QueryRow> rows) {
        Object configured = spec.get("columns");
        List<Map<String, Object>> result = new ArrayList<>();
        if (configured instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof String field && !field.isBlank()) {
                    result.add(Map.of("field", field, "title", field));
                } else if (raw instanceof Map<?, ?> map && map.get("field") != null) {
                    Map<String, Object> column = new LinkedHashMap<>();
                    map.forEach((key, value) -> column.put(String.valueOf(key), value));
                    column.putIfAbsent("title", String.valueOf(column.get("field")));
                    result.add(column);
                }
            }
        }
        if (!result.isEmpty()) return List.copyOf(result);
        if (!fields.isEmpty()) {
            return fields.stream().limit(50).map(field -> Map.<String, Object>of(
                "field", field.field(), "title", field.field(), "type", field.valueType())).toList();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        rows.forEach(row -> row.display().forEach((key, value) -> {
            if (names.size() < 50 && !(value instanceof Map<?, ?>) && !(value instanceof List<?>)) names.add(key);
        }));
        return names.stream().map(field -> Map.<String, Object>of("field", field, "title", field)).toList();
    }

    private OutputArtifact requireArtifact(Long artifactId) {
        OutputArtifact artifact = artifactId == null ? null : artifactMapper.selectById(artifactId);
        if (artifact == null) throw new BusinessException(404, "输出结果不存在: " + artifactId);
        String userId = UserContextHolder.require().userId().toString();
        if (!UserRoles.ADMIN.equals(UserContextHolder.require().role())
                && !userId.equals(artifact.getOwnerUserId())) {
            throw new BusinessException(403, "无权访问该输出结果");
        }
        if (StorageGovernanceService.ARCHIVED.equals(artifact.getArchiveStatus())) {
            throw new BusinessException(409, "输出结果已归档，请由管理员恢复后查询");
        }
        return artifact;
    }

    private SortColumn sortColumn(String type) {
        return switch (type) {
            case "NUMBER" -> new SortColumn("number_value");
            case "BOOLEAN" -> new SortColumn("boolean_value");
            case "STRING" -> new SortColumn("text_sort_value");
            default -> throw new BusinessException(422, "字段类型不能用于结果查询: " + type);
        };
    }

    private Object normalizeJdbcValue(Object value, String type) {
        if (value == null) return null;
        if ("NUMBER".equals(type)) return decimal(value);
        if ("BOOLEAN".equals(type)) return value instanceof Boolean bool ? (bool ? 1 : 0) : value;
        return String.valueOf(value);
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private String text(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new BusinessException(422, "结果查询字段或操作符不能为空");
        return raw.trim();
    }

    private BusinessException legacyIndexRequired() {
        return new BusinessException(409, "该历史结果没有字段查询索引，请重新运行对应流程后再筛选或排序");
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception error) { throw new BusinessException(409, "输出视图数据损坏"); }
    }

    private List<Map<String, Object>> parseMaps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception error) { throw new BusinessException(409, "输出视图明细损坏"); }
    }

    private List<String> parseStrings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception error) { throw new BusinessException(409, "输出血缘数据损坏"); }
    }

    public record QueryRequest(Integer pageSize, String cursor,
                               List<FilterRequest> filters, SortRequest sort) {}
    public record FilterRequest(String field, String operator, Object value, Object secondValue) {}
    public record SortRequest(String field, String direction) {}
    public record QueryField(String field, String valueType, List<String> observedTypes,
                             long observedCount, boolean filterable, boolean sortable) {}
    public record QueryRow(int rowIndex, Map<String, Object> display, Map<String, Object> result,
                           List<Map<String, Object>> sources, List<Map<String, Object>> evidence,
                           List<String> sourceRefs) {}
    public record QueryResult(OutputArtifact artifact, Map<String, Object> contentSpec,
                              Map<String, Object> summary, int pageSize, long totalRows,
                              boolean queryIndexReady, String nextCursor, boolean hasMore,
                              List<Map<String, Object>> columns, List<QueryField> queryFields,
                              List<QueryRow> rows) {}

    private record NormalizedFilter(String field, String operator, String valueType,
                                    Object value, Object secondValue) {}
    private record NormalizedSort(String field, String direction, String valueType) {}
    private record WhereSql(String sql, List<Object> arguments) {}
    private record RowKey(int rowIndex) {}
    private record SortColumn(String column) {}
    private record SortAnchor(int bucket, Object value, int rowIndex) {}

    private static final class FieldAccumulator {
        private final Set<String> types = new LinkedHashSet<>();
        private long count;

        private void add(String type, long observed) {
            types.add(type);
            count += observed;
        }

        private QueryField view(String field) {
            Set<String> nonNull = new LinkedHashSet<>(types);
            nonNull.remove("NULL");
            String valueType = nonNull.size() == 1 ? nonNull.iterator().next()
                : nonNull.isEmpty() ? "NULL" : "MIXED";
            boolean stable = Set.of("STRING", "NUMBER", "BOOLEAN").contains(valueType);
            return new QueryField(field, valueType, List.copyOf(types), count, stable, stable);
        }
    }
}
