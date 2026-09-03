package com.smartquery.orchestration;

import com.smartquery.common.BusinessException;
import com.smartquery.orchestration.execution.LineageSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns untrusted LLM output into the small declarative vocabulary understood by the UI.
 * It never evaluates scripts, HTML, SQL, templates or arbitrary ECharts options.
 */
@Component
@RequiredArgsConstructor
public class OutputSpecSandbox {
    private static final Set<String> KINDS = Set.of("LEAD", "CHART", "TABLE", "EXCEL");
    private static final Set<String> COMMON = Set.of(
        "title", "description", "columns", "sort", "pageSize", "emptyText", "showLineage", "rowDetail");
    private static final Set<String> EXECUTABLE_KEYS = Set.of(
        "script", "javascript", "html", "formatter", "function", "template", "echartsoption", "url");

    private final ContentHashService contentHashService;
    private final LeadOutputPolicyService leadOutputPolicyService;

    public ShapeResult shape(Map<String, Object> raw) {
        String kind = text(raw.get("outputKind"));
        if (kind == null || !KINDS.contains(kind.toUpperCase(Locale.ROOT))) {
            throw new BusinessException(422, "输出草稿outputKind仅支持LEAD/CHART/TABLE/EXCEL");
        }
        kind = kind.toUpperCase(Locale.ROOT);
        Map<String, Object> source = map(raw.get("contentSpec"));
        rejectExecutableKeys(source, "contentSpec");
        Set<String> allowed = new LinkedHashSet<>(COMMON);
        if ("CHART".equals(kind)) allowed.addAll(Set.of("chartType", "dimensions", "measures", "legend", "stack"));
        if ("EXCEL".equals(kind)) allowed.addAll(Set.of("sheetName", "frozenColumns"));
        if ("LEAD".equals(kind)) allowed.add("leadPolicy");

        List<String> warnings = new ArrayList<>();
        source.keySet().stream().filter(key -> !allowed.contains(key))
            .forEach(key -> warnings.add("已移除不受支持的展示属性: " + key));

        Map<String, Object> spec = new LinkedHashMap<>();
        putText(spec, "title", source.get("title"), 160);
        putText(spec, "description", source.get("description"), 500);
        putText(spec, "emptyText", source.get("emptyText"), 160);
        spec.put("pageSize", boundedInt(source.get("pageSize"), 50, 1, 200, "pageSize"));
        spec.put("showLineage", true);
        spec.put("rowDetail", Map.of("includeOriginalInput", true, "includeEvidence", true));

        if (source.get("columns") != null) spec.put("columns", columns(source.get("columns")));
        if (source.get("sort") != null) spec.put("sort", sort(source.get("sort")));
        switch (kind) {
            case "TABLE" -> requireColumns(spec, "TABLE");
            case "EXCEL" -> {
                requireColumns(spec, "EXCEL");
                putText(spec, "sheetName", source.getOrDefault("sheetName", "结果"), 31);
                spec.put("frozenColumns", boundedInt(source.get("frozenColumns"), 0, 0, 10, "frozenColumns"));
            }
            case "CHART" -> shapeChart(source, spec);
            case "LEAD" -> shapeLead(source, spec);
            default -> throw new BusinessException(422, "不支持的输出类型");
        }
        if (spec.get("title") == null) spec.put("title", defaultTitle(kind));

        Map<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("outputKind", kind);
        shaped.put("contentSpec", spec);
        String hash = contentHashService.sha256(shaped);
        return new ShapeResult(shaped, List.copyOf(warnings), hash, renderer(kind));
    }

    public PreviewValidation validatePreview(Map<String, Object> shaped,
                                             List<Map<String, Object>> records) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (records == null || records.isEmpty()) errors.add("预览至少需要一条样例记录");
        Map<String, Object> spec = map(shaped.get("contentSpec"));
        for (Map<String, Object> record : records == null ? List.<Map<String, Object>>of() : records) {
            if (!(record.get(LineageSupport.SOURCE_REFS) instanceof List<?> refs) || refs.isEmpty()
                    || !(record.get(LineageSupport.SOURCE_SNAPSHOTS) instanceof List<?> snapshots)
                    || snapshots.isEmpty()) {
                errors.add("样例记录缺少原始输入血缘");
                break;
            }
        }

        List<String> fields = new ArrayList<>();
        Object rawColumns = spec.get("columns");
        if (rawColumns instanceof List<?> columns) {
            for (Object raw : columns) fields.add(String.valueOf(map(raw).get("field")));
        }
        if ("CHART".equals(shaped.get("outputKind"))) {
            fields.addAll(strings(spec.get("dimensions")));
            List<String> measures = strings(spec.get("measures"));
            fields.addAll(measures);
            for (String measure : measures) {
                boolean numeric = records != null && records.stream()
                    .map(record -> resolve(viewRoot(record), measure)).anyMatch(Number.class::isInstance);
                if (!numeric) errors.add("图表指标在样例中不是数值: " + measure);
            }
        }
        for (String field : fields.stream().distinct().toList()) {
            boolean present = records != null && records.stream()
                .anyMatch(record -> containsPath(viewRoot(record), field));
            if (!present) errors.add("展示字段在样例中不存在: " + field);
        }
        if (records != null && records.size() == 1) warnings.add("仅使用1条样例，建议增加边界数据确认展示效果");
        return new PreviewValidation(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }

    public Map<String, Object> viewRow(Map<String, Object> record, int index) {
        Map<String, Object> result = new LinkedHashMap<>();
        record.forEach((key, value) -> { if (!key.startsWith("__")) result.put(key, value); });
        List<Map<String, Object>> sources = maps(record.get(LineageSupport.SOURCE_SNAPSHOTS));
        Map<String, Object> display = new LinkedHashMap<>();
        if (sources.size() == 1) display.putAll(sources.get(0));
        display.putAll(result);
        return Map.of(
            "rowIndex", index,
            "display", display,
            "result", result,
            "sources", sources,
            "evidence", list(record.get(LineageSupport.EVIDENCE)),
            "sourceRefs", list(record.get(LineageSupport.SOURCE_REFS))
        );
    }

    private void shapeChart(Map<String, Object> source, Map<String, Object> spec) {
        String type = first(text(source.get("chartType")), "bar").toLowerCase(Locale.ROOT);
        if (!Set.of("bar", "line", "pie", "scatter").contains(type)) {
            throw new BusinessException(422, "chartType仅支持bar/line/pie/scatter");
        }
        List<String> dimensions = fieldList(source.get("dimensions"), 1, 3, "dimensions");
        List<String> measures = fieldList(source.get("measures"), 1, 20, "measures");
        spec.put("chartType", type);
        spec.put("dimensions", dimensions);
        spec.put("measures", measures);
        if (source.get("legend") != null) spec.put("legend", Boolean.parseBoolean(String.valueOf(source.get("legend"))));
        if (source.get("stack") != null) putText(spec, "stack", source.get("stack"), 80);
    }

    private void shapeLead(Map<String, Object> source, Map<String, Object> spec) {
        Map<String, Object> policy = map(source.get("leadPolicy"));
        if (policy.isEmpty()) policy = leadOutputPolicyService.defaultPolicy();
        LeadOutputPolicyService.PolicyValidation validation = leadOutputPolicyService.validate(policy);
        if (!validation.valid()) {
            throw new BusinessException(422, "线索展示策略无效: " + String.join("；", validation.errors()));
        }
        spec.put("leadPolicy", policy);
    }

    private List<Map<String, Object>> columns(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 100) {
            throw new BusinessException(422, "columns必须包含1到100列");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> column = item instanceof String field
                ? new LinkedHashMap<>(Map.of("field", field, "title", field)) : map(item);
            String field = validField(column.get("field"), "columns.field");
            Map<String, Object> shaped = new LinkedHashMap<>();
            shaped.put("field", field);
            shaped.put("title", safeText(column.getOrDefault("title", field), 100, "columns.title"));
            if (column.get("format") != null) shaped.put("format", safeText(column.get("format"), 50, "columns.format"));
            if (column.get("width") != null) shaped.put("width", boundedInt(column.get("width"), 140, 80, 600, "columns.width"));
            result.add(shaped);
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> sort(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() > 10) throw new BusinessException(422, "sort最多10项");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> value = map(item);
            String field = validField(value.get("field"), "sort.field");
            String direction = first(text(value.get("direction")), "asc").toLowerCase(Locale.ROOT);
            if (!Set.of("asc", "desc").contains(direction)) throw new BusinessException(422, "sort.direction仅支持asc/desc");
            result.add(Map.of("field", field, "direction", direction));
        }
        return List.copyOf(result);
    }

    private List<String> fieldList(Object raw, int min, int max, String name) {
        if (!(raw instanceof List<?> list) || list.size() < min || list.size() > max) {
            throw new BusinessException(422, name + "数量必须在" + min + "到" + max + "之间");
        }
        return list.stream().map(value -> validField(value, name)).toList();
    }

    private String validField(Object raw, String name) {
        String field = text(raw);
        if (field == null || field.contains("__")
                || !field.matches("^[A-Za-z_][A-Za-z0-9_-]*(\\.(?:[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*$")) {
            throw new BusinessException(422, name + "不是合法的展示字段路径");
        }
        return field;
    }

    private void rejectExecutableKeys(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (EXECUTABLE_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                    throw new BusinessException(422, "输出规格禁止可执行或外链属性: " + path + "." + key);
                }
                rejectExecutableKeys(entry.getValue(), path + "." + key);
            }
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) rejectExecutableKeys(list.get(index), path + "[" + index + "]");
        }
    }

    private Map<String, Object> viewRoot(Map<String, Object> record) {
        Map<String, Object> result = new LinkedHashMap<>();
        record.forEach((key, value) -> { if (!key.startsWith("__")) result.put(key, value); });
        List<Map<String, Object>> sources = maps(record.get(LineageSupport.SOURCE_SNAPSHOTS));
        Map<String, Object> root = new LinkedHashMap<>();
        if (sources.size() == 1) root.putAll(sources.get(0));
        root.putAll(result);
        root.put("result", result);
        root.put("sources", sources);
        return root;
    }

    private boolean containsPath(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(part)) return false;
                current = map.get(part);
            } else if (current instanceof List<?> list && part.matches("\\d+")) {
                int index = Integer.parseInt(part);
                if (index >= list.size()) return false;
                current = list.get(index);
            } else return false;
        }
        return true;
    }

    private Object resolve(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) current = map.get(part);
            else if (current instanceof List<?> list && part.matches("\\d+")) {
                int index = Integer.parseInt(part);
                current = index < list.size() ? list.get(index) : null;
            } else return null;
        }
        return current;
    }

    private void requireColumns(Map<String, Object> spec, String kind) {
        if (!(spec.get("columns") instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException(422, kind + "输出必须声明columns");
        }
    }

    private int boundedInt(Object raw, int fallback, int min, int max, String name) {
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(String.valueOf(raw));
            if (value >= min && value <= max) return value;
        } catch (Exception ignored) {}
        throw new BusinessException(422, name + "必须在" + min + "到" + max + "之间");
    }

    private void putText(Map<String, Object> target, String key, Object raw, int max) {
        if (raw != null) target.put(key, safeText(raw, max, key));
    }

    private String safeText(Object raw, int max, String name) {
        String value = text(raw);
        if (value == null || value.length() > max || value.chars().anyMatch(ch -> ch < 32 && ch != '\n' && ch != '\t')) {
            throw new BusinessException(422, name + "不是合法文本或长度超过" + max);
        }
        return value;
    }

    private List<String> strings(Object raw) {
        return raw instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private List<Map<String, Object>> maps(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : list) if (value instanceof Map<?, ?>) result.add(map(value));
        return result;
    }

    private List<?> list(Object raw) { return raw instanceof List<?> list ? List.copyOf(list) : List.of(); }
    private String renderer(String kind) { return switch (kind) {
        case "LEAD" -> "standard-lead-v1";
        case "CHART" -> "chart-spec-v1";
        case "TABLE" -> "table-spec-v1";
        case "EXCEL" -> "excel-grid-v1";
        default -> "unknown";
    }; }
    private String defaultTitle(String kind) { return switch (kind) {
        case "LEAD" -> "流程线索";
        case "CHART" -> "分析图表";
        case "TABLE" -> "结果表";
        case "EXCEL" -> "Excel 表格视图";
        default -> "流程输出";
    }; }
    private String first(String value, String fallback) { return value == null ? fallback : value; }
    private String text(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
    private Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    public record ShapeResult(Map<String, Object> spec, List<String> warnings,
                              String contentHash, String renderer) {}
    public record PreviewValidation(boolean valid, List<String> errors, List<String> warnings) {}
}
