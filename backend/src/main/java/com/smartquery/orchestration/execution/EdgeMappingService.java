package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import com.smartquery.orchestration.SchemaCompatibilityService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Applies an edge's immutable field mapping while keeping platform provenance intact. */
@Service
public class EdgeMappingService {
    private static final Object MISSING = new Object();

    public OperatorExecutionResult apply(Map<String, Object> edge, OperatorExecutionResult source) {
        if (source == null) throw new BusinessException(422, "上游节点尚未产生输出");
        String mode = String.valueOf(edge.getOrDefault("mappingMode", SchemaCompatibilityService.MERGE))
            .toUpperCase(Locale.ROOT);
        if (!SchemaCompatibilityService.MERGE.equals(mode)
                && !SchemaCompatibilityService.PROJECT.equals(mode)) {
            throw new BusinessException(422, "边mappingMode只支持MERGE或PROJECT");
        }
        List<Map<String, Object>> mappings = mappings(edge.get("fieldMappings"));
        if (SchemaCompatibilityService.PROJECT.equals(mode) && mappings.isEmpty()) {
            throw new BusinessException(422, "PROJECT边至少需要一个字段映射");
        }

        Map<String, Object> output = new LinkedHashMap<>(source.output());
        Object rawRecords = source.output().get("records");
        if (!(rawRecords instanceof List<?> records)) {
            throw new BusinessException(422, "上游输出缺少records数组");
        }
        List<Map<String, Object>> mappedRecords = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            if (!(records.get(i) instanceof Map<?, ?> rawRecord)) {
                throw new BusinessException(422, "上游第" + (i + 1) + "条record不是对象");
            }
            Map<String, Object> record = stringMap(rawRecord);
            Map<String, Object> mapped = SchemaCompatibilityService.MERGE.equals(mode)
                ? new LinkedHashMap<>(record) : new LinkedHashMap<>();
            for (Map<String, Object> mapping : mappings) {
                String from = requiredText(mapping.get("from"), "字段映射from不能为空");
                String to = requiredText(mapping.get("to"), "字段映射to不能为空");
                rejectReserved(from, to);
                Object value = lookup(record, from);
                if (value == MISSING && mapping.containsKey("defaultValue")) value = mapping.get("defaultValue");
                if (value == MISSING) {
                    if (Boolean.parseBoolean(String.valueOf(mapping.getOrDefault("required", false)))) {
                        throw new BusinessException(422, "第" + (i + 1) + "条记录缺少边必填来源字段: " + from);
                    }
                    continue;
                }
                putPath(mapped, to, value);
            }
            preserveLineage(record, mapped);
            mappedRecords.add(mapped);
        }
        output.put("records", List.copyOf(mappedRecords));
        String log = "edgeMapping=" + mode + " fields=" + mappings.size();
        return new OperatorExecutionResult(output, source.leads(), log);
    }

    private List<Map<String, Object>> mappings(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new BusinessException(422, "边fieldMappings必须是数组");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new BusinessException(422, "边fieldMappings元素必须是对象");
            result.add(stringMap(map));
        }
        return result;
    }

    private Object lookup(Map<String, Object> record, String path) {
        Object current = record;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) return MISSING;
            current = map.get(segment);
        }
        return current;
    }

    private void putPath(Map<String, Object> target, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = target;
        for (int i = 0; i < segments.length - 1; i++) {
            Object child = current.get(segments[i]);
            if (!(child instanceof Map<?, ?> rawChild)) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(segments[i], created);
                current = created;
            } else {
                Map<String, Object> mutableChild = stringMap(rawChild);
                current.put(segments[i], mutableChild);
                current = mutableChild;
            }
        }
        current.put(segments[segments.length - 1], value);
    }

    private void preserveLineage(Map<String, Object> source, Map<String, Object> target) {
        copy(source, target, LineageSupport.SOURCE_REFS);
        copy(source, target, LineageSupport.SOURCE_SNAPSHOTS);
        if (source.containsKey(LineageSupport.EVIDENCE)) copy(source, target, LineageSupport.EVIDENCE);
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String field) {
        if (!source.containsKey(field)) throw new BusinessException(422, "边映射前记录缺少平台血缘字段: " + field);
        target.put(field, source.get(field));
    }

    private void rejectReserved(String from, String to) {
        if (root(from).startsWith("__") || root(to).startsWith("__")) {
            throw new BusinessException(422, "平台血缘字段不能由边映射覆盖");
        }
    }

    private String root(String path) {
        return path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
    }

    private String requiredText(Object value, String message) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty()) throw new BusinessException(422, message);
        return text;
    }

    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
