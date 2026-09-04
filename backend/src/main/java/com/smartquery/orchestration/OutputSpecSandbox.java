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
    private final OutputCapabilityRegistryService outputCapabilityRegistryService;

    public ShapeResult shape(Map<String, Object> raw) {
        if (raw != null && (raw.containsKey("targets") || "2".equals(String.valueOf(raw.get("specVersion"))))) {
            return shapeComposable(raw);
        }
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
        String capabilityCode = switch (kind) {
            case "LEAD" -> "action.lead";
            case "CHART" -> "view.echarts";
            case "TABLE", "EXCEL" -> "view.table";
            default -> throw new BusinessException(422, "不支持的输出类型");
        };
        OutputCapabilityRegistryService.CapabilitySnapshot capability =
            outputCapabilityRegistryService.resolvePublished(capabilityCode);
        Map<String, Object> target = capabilitySnapshot(capability);
        target.put("id", "target-1");
        Map<String, Object> migratedConfig = new LinkedHashMap<>(spec);
        if ("EXCEL".equals(kind)) {
            migratedConfig.put("excelStyle", true);
            migratedConfig.put("legacyOutputKind", "EXCEL");
        }
        target.put("config", Map.copyOf(migratedConfig));
        shaped.put("specVersion", 2);
        shaped.put("transformations", List.of());
        shaped.put("targets", List.of(Map.copyOf(target)));
        shaped.put("governance", Map.of("permissionCheck", "REQUIRED", "audit", "REQUIRED",
            "lineage", "REQUIRED", "minimumTargets", 1));
        String hash = contentHashService.sha256(shaped);
        return new ShapeResult(shaped, List.copyOf(warnings), hash, "composable-output-v2");
    }

    private ShapeResult shapeComposable(Map<String, Object> raw) {
        rejectExecutableKeys(raw, "outputSpec");
        List<Map<String, Object>> transformations = shapeEntries(raw.get("transformations"), true, 10);
        List<Map<String, Object>> targets = hydrateCompositionReferences(
            shapeEntries(raw.get("targets"), false, 20));
        if (targets.isEmpty()) throw new BusinessException(422, "输出算子至少需要选择一个输出目标");
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> item : targets) {
            if (!ids.add(String.valueOf(item.get("id")))) {
                throw new BusinessException(422, "输出目标id不能重复: " + item.get("id"));
            }
        }
        Map<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("specVersion", 2);
        shaped.put("transformations", transformations);
        shaped.put("targets", targets);
        shaped.put("governance", Map.of("permissionCheck", "REQUIRED", "audit", "REQUIRED",
            "lineage", "REQUIRED", "minimumTargets", 1));
        String hash = contentHashService.sha256(shaped);
        return new ShapeResult(shaped, List.of(), hash, "composable-output-v2");
    }

    private List<Map<String, Object>> shapeEntries(Object raw, boolean transformation, int max) {
        if (raw == null && transformation) return List.of();
        if (!(raw instanceof List<?> list) || list.size() > max) {
            throw new BusinessException(422, (transformation ? "transformations" : "targets")
                + "必须是最多" + max + "项的数组");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        int index = 0;
        for (Object item : list) {
            Map<String, Object> entry = map(item);
            String code = text(entry.get("capabilityCode"));
            OutputCapabilityRegistryService.CapabilitySnapshot capability =
                outputCapabilityRegistryService.resolvePublished(code);
            boolean isTransform = "TRANSFORM".equals(capability.capabilityType());
            if (transformation != isTransform) {
                throw new BusinessException(422, capability.code() + (transformation
                    ? "不是数据转换能力" : "只能放在transformations中"));
            }
            String id = first(text(entry.get("id")), (transformation ? "transform-" : "target-") + (++index));
            if (!id.matches("^[A-Za-z][A-Za-z0-9_-]{0,79}$")) {
                throw new BusinessException(422, "输出目标id格式不正确: " + id);
            }
            Map<String, Object> config = shapeCapabilityConfig(capability, map(entry.get("config")));
            Map<String, Object> shaped = capabilitySnapshot(capability);
            shaped.put("id", id);
            shaped.put("config", config);
            result.add(Map.copyOf(shaped));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> capabilitySnapshot(OutputCapabilityRegistryService.CapabilitySnapshot capability) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capabilityId", capability.capabilityId());
        result.put("capabilityCode", capability.code());
        result.put("capabilityName", capability.name());
        result.put("capabilityType", capability.capabilityType());
        result.put("capabilityVersionId", capability.versionId());
        result.put("capabilityVersionNo", capability.versionNo());
        result.put("contentHash", capability.contentHash());
        result.put("artifactSha256", capability.artifactSha256());
        result.put("implementationType", capability.implementationType());
        result.put("implementationRef", capability.implementationRef());
        result.put("runtimeType", capability.runtimeType());
        result.put("dependencies", capability.dependencies());
        result.put("interactionEvents", capability.interactionEvents());
        result.put("securityPolicy", capability.securityPolicy());
        if (capability.requiredPermission() != null) result.put("requiredPermission", capability.requiredPermission());
        return result;
    }

    private Map<String, Object> shapeCapabilityConfig(
            OutputCapabilityRegistryService.CapabilitySnapshot capability, Map<String, Object> source) {
        rejectExecutableKeys(source, capability.code() + ".config");
        Map<String, Object> spec = commonSpec(source);
        switch (capability.implementationType()) {
            case "PROJECT" -> {
                Object projection = source.get("columns") != null ? source.get("columns") : source.get("fields");
                if (projection != null) spec.put("columns", columns(projection));
                if (source.get("sort") != null) spec.put("sort", sort(source.get("sort")));
                spec.put("limit", boundedInt(source.get("limit"), 10000, 1, 100000, "limit"));
            }
            case "TABLE" -> {
                if (source.get("columns") != null) spec.put("columns", columns(source.get("columns")));
                requireColumns(spec, "表格展示");
                spec.put("excelStyle", Boolean.parseBoolean(String.valueOf(source.getOrDefault("excelStyle", false))));
            }
            case "ECHARTS" -> shapeChart(source, spec);
            case "COMPOSED_PAGE" -> shapeComposedPage(source, spec);
            case "LEAD" -> shapeLead(source, spec);
            case "RUN_ARTIFACT" -> spec.put("retentionClass", "PLATFORM_POLICY");
            case "TEMP_ARTIFACT" -> {
                spec.put("retentionClass", "TEMPORARY");
                spec.put("retentionDays", boundedInt(source.get("retentionDays"), 7, 1, 30, "retentionDays"));
            }
            case "XLSX" -> {
                if (source.get("columns") != null) spec.put("columns", columns(source.get("columns")));
                putText(spec, "sheetName", source.getOrDefault("sheetName", "结果"), 31);
                putText(spec, "fileName", source.getOrDefault("fileName", "result.xlsx"), 160);
            }
            case "CSV" -> putText(spec, "fileName", source.getOrDefault("fileName", "result.csv"), 160);
            case "PDF" -> putText(spec, "fileName", source.getOrDefault("fileName", "result.pdf"), 160);
            case "JSON" -> putText(spec, "fileName", source.getOrDefault("fileName", "result.json"), 160);
            case "PNG" -> {
                putText(spec, "fileName", source.getOrDefault("fileName", "result.png"), 160);
                if (source.get("chartType") != null) shapeChart(source, spec);
            }
            default -> throw new BusinessException(422, "当前平台没有可信执行适配器: " + capability.code());
        }
        if (Set.of("CSV", "PDF", "JSON", "PNG").contains(capability.implementationType())
                && source.get("columns") != null) {
            spec.put("columns", columns(source.get("columns")));
        }
        return Map.copyOf(spec);
    }

    private Map<String, Object> commonSpec(Map<String, Object> source) {
        Map<String, Object> spec = new LinkedHashMap<>();
        putText(spec, "title", source.get("title"), 160);
        putText(spec, "description", source.get("description"), 500);
        putText(spec, "emptyText", source.get("emptyText"), 160);
        spec.put("pageSize", boundedInt(source.get("pageSize"), 50, 1, 200, "pageSize"));
        spec.put("showLineage", true);
        spec.put("rowDetail", Map.of("includeOriginalInput", true, "includeEvidence", true));
        if (source.get("sort") != null) spec.put("sort", sort(source.get("sort")));
        return spec;
    }

    private void shapeComposedPage(Map<String, Object> source, Map<String, Object> spec) {
        Object raw = source.get("widgets");
        if (!(raw instanceof List<?>)) {
            List<Map<String, Object>> shorthand = new ArrayList<>();
            for (String type : List.of("metric", "chart", "table", "filter", "container")) {
                Map<String, Object> widget = map(source.get(type));
                if (!widget.isEmpty()) {
                    widget.put("type", type);
                    widget.putIfAbsent("id", type + "-1");
                    shorthand.add(widget);
                }
            }
            raw = shorthand;
        }
        if (!(raw instanceof List<?> widgets) || widgets.isEmpty() || widgets.size() > 30) {
            throw new BusinessException(422, "组合页面widgets必须包含1到30个可信组件");
        }
        List<Map<String, Object>> shapedWidgets = new ArrayList<>();
        int index = 0;
        for (Object rawWidget : widgets) {
            Map<String, Object> widget = map(rawWidget);
            String type = first(text(widget.get("type")), "table").toLowerCase(Locale.ROOT);
            if (!Set.of("metric", "chart", "table", "filter", "container").contains(type)) {
                throw new BusinessException(422, "组合页面仅支持metric/chart/table/filter/container可信组件");
            }
            Map<String, Object> shaped = new LinkedHashMap<>();
            shaped.put("id", first(text(widget.get("id")), "widget-" + (++index)));
            shaped.put("type", type);
            putText(shaped, "title", widget.get("title"), 120);
            if (widget.get("field") != null) shaped.put("field", validField(widget.get("field"), "widget.field"));
            if ("metric".equals(type)) {
                String aggregation = first(text(widget.get("aggregation")), "sum").toLowerCase(Locale.ROOT);
                if (!Set.of("sum", "avg", "count", "min", "max").contains(aggregation)) {
                    throw new BusinessException(422, "metric.aggregation仅支持sum/avg/count/min/max");
                }
                shaped.put("aggregation", aggregation);
            }
            if ("chart".equals(type)) {
                if (widget.get("ref") != null) {
                    shaped.put("refTargetId", safeText(widget.get("ref"), 80, "widget.ref"));
                } else {
                    Map<String, Object> chart = new LinkedHashMap<>();
                    shapeChart(widget, chart);
                    shaped.putAll(chart);
                }
            }
            if ("table".equals(type)) {
                if (widget.get("ref") != null) shaped.put("refTargetId", safeText(widget.get("ref"), 80, "widget.ref"));
                else if (widget.get("columns") != null) shaped.put("columns", columns(widget.get("columns")));
            }
            if ("filter".equals(type)) {
                shaped.put("fields", fieldList(widget.get("fields"), 1, 20, "widget.fields"));
            }
            shapedWidgets.add(Map.copyOf(shaped));
        }
        spec.put("widgets", List.copyOf(shapedWidgets));
        String layout = first(text(source.get("layout")), "grid").toLowerCase(Locale.ROOT);
        spec.put("layout", Set.of("grid", "vertical").contains(layout) ? layout : "grid");
    }

    private List<Map<String, Object>> hydrateCompositionReferences(List<Map<String, Object>> targets) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        targets.forEach(target -> byId.put(String.valueOf(target.get("id")), target));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> target : targets) {
            if (!"COMPOSED_PAGE".equals(target.get("implementationType"))) {
                result.add(target);
                continue;
            }
            Map<String, Object> next = new LinkedHashMap<>(target);
            Map<String, Object> config = new LinkedHashMap<>(map(target.get("config")));
            List<Map<String, Object>> widgets = new ArrayList<>();
            for (Map<String, Object> widget : maps(config.get("widgets"))) {
                Map<String, Object> hydrated = new LinkedHashMap<>(widget);
                String ref = text(widget.get("refTargetId"));
                if (ref != null) {
                    Map<String, Object> referenced = byId.get(ref);
                    if (referenced == null || referenced == target) {
                        throw new BusinessException(422, "组合页面引用的输出目标不存在: " + ref);
                    }
                    Map<String, Object> referencedConfig = map(referenced.get("config"));
                    referencedConfig.forEach(hydrated::putIfAbsent);
                    hydrated.put("resolvedCapabilityCode", referenced.get("capabilityCode"));
                }
                widgets.add(Map.copyOf(hydrated));
            }
            config.put("widgets", List.copyOf(widgets));
            next.put("config", Map.copyOf(config));
            result.add(Map.copyOf(next));
        }
        return List.copyOf(result);
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

        if ("2".equals(String.valueOf(shaped.get("specVersion")))) {
            validateComposableFields(shaped, records, errors);
            if (records != null && records.size() == 1) warnings.add("仅使用1条样例，建议增加边界数据确认输出效果");
            return new PreviewValidation(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
        }

        List<String> fields = new ArrayList<>();
        Object rawColumns = spec.get("columns");
        if (rawColumns instanceof List<?> columns) {
            for (Object raw : columns) fields.add(String.valueOf(map(raw).get("field")));
        }
        if ("CHART".equals(shaped.get("outputKind"))) {
            fields.addAll(strings(spec.get("dimensions")));
            List<String> measures = measureFields(spec.get("measures"));
            fields.addAll(measures);
            for (String measure : measures) {
                if (!"count".equals(measureAggregation(spec.get("measures"), measure))) {
                    boolean numeric = records != null && records.stream()
                        .map(record -> resolve(viewRoot(record), measure)).anyMatch(Number.class::isInstance);
                    if (!numeric) errors.add("图表指标在样例中不是数值: " + measure);
                }
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

    private void validateComposableFields(Map<String, Object> shaped, List<Map<String, Object>> records,
                                          List<String> errors) {
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.addAll(maps(shaped.get("transformations")));
        entries.addAll(maps(shaped.get("targets")));
        for (Map<String, Object> entry : entries) {
            Map<String, Object> config = map(entry.get("config"));
            List<String> fields = new ArrayList<>();
            Object rawColumns = config.get("columns");
            if (rawColumns instanceof List<?> columns) {
                for (Object raw : columns) fields.add(String.valueOf(map(raw).get("field")));
            }
            if ("ECHARTS".equals(entry.get("implementationType"))) {
                fields.addAll(strings(config.get("dimensions")));
                List<String> measures = measureFields(config.get("measures"));
                fields.addAll(measures);
                for (String measure : measures) {
                    if (!"count".equals(measureAggregation(config.get("measures"), measure))) {
                        boolean numeric = records != null && records.stream()
                            .map(record -> resolve(viewRoot(record), measure)).anyMatch(Number.class::isInstance);
                        if (!numeric) errors.add("图表指标在样例中不是数值: " + measure);
                    }
                }
            }
            for (String field : fields.stream().filter(value -> value != null && !"null".equals(value)).distinct().toList()) {
                boolean present = records != null && records.stream().anyMatch(record -> containsPath(viewRoot(record), field));
                if (!present) errors.add("输出字段在样例中不存在: " + field);
            }
        }
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
        if (!Set.of("bar", "line", "pie", "scatter", "radar", "heatmap", "graph", "map",
                "sankey", "treemap", "sunburst").contains(type)) {
            throw new BusinessException(422, "chartType不在平台治理的通用图表白名单中");
        }
        List<String> dimensions = fieldList(source.get("dimensions"), 1, 3, "dimensions");
        List<Map<String, Object>> measures = measures(source.get("measures"));
        if (Set.of("sankey", "graph", "heatmap").contains(type) && dimensions.size() < 2) {
            throw new BusinessException(422, type + "图至少需要两个维度字段");
        }
        spec.put("chartType", type);
        spec.put("dimensions", dimensions);
        spec.put("measures", measures);
        if (source.get("legend") != null) spec.put("legend", Boolean.parseBoolean(String.valueOf(source.get("legend"))));
        if (source.get("stack") != null) putText(spec, "stack", source.get("stack"), 80);
        if ("map".equals(type)) putText(spec, "mapName", source.getOrDefault("mapName", "china"), 80);
    }

    private void shapeLead(Map<String, Object> source, Map<String, Object> spec) {
        Map<String, Object> requested = map(source.get("leadPolicy"));
        Map<String, Object> policy = new LinkedHashMap<>(leadOutputPolicyService.defaultPolicy());
        policy.putAll(requested);
        if (requested.containsKey("condition") && !requested.containsKey("mode")) policy.put("mode", "CONDITION");
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
        // A single sort object is a common LLM representation. Normalize it to
        // the platform list form instead of rejecting an otherwise safe spec.
        List<?> list = raw instanceof List<?> values ? values
            : raw instanceof Map<?, ?> ? List.of(raw) : null;
        if (list == null || list.size() > 10) throw new BusinessException(422, "sort必须是单个对象或最多10项的数组");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> value = map(item);
            String field = validField(value.get("field"), "sort.field");
            String direction = first(text(value.get("direction")), first(text(value.get("order")), "asc"))
                .toLowerCase(Locale.ROOT);
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

    private List<Map<String, Object>> measures(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 20) {
            throw new BusinessException(422, "measures数量必须在1到20之间");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> value = item instanceof String field
                ? new LinkedHashMap<>(Map.of("field", field)) : map(item);
            String field = validField(value.get("field"), "measures.field");
            String aggregation = first(text(value.get("aggregation")), "none").toLowerCase(Locale.ROOT);
            if (!Set.of("none", "sum", "avg", "count", "min", "max").contains(aggregation)) {
                throw new BusinessException(422, "measures.aggregation仅支持none/sum/avg/count/min/max");
            }
            result.add(Map.of("field", field, "aggregation", aggregation));
        }
        return List.copyOf(result);
    }

    private List<String> measureFields(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(item -> item instanceof Map<?, ?> ? text(map(item).get("field")) : text(item))
            .filter(java.util.Objects::nonNull).toList();
    }

    private String measureAggregation(Object raw, String field) {
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> value = map(item);
                if (field.equals(text(value.get("field")))) return first(text(value.get("aggregation")), "none");
            }
        }
        return "none";
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
