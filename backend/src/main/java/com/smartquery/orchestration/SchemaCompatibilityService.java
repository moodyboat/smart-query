package com.smartquery.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.OperatorVersion;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the record contract carried by one immutable DAG edge. */
@Service
public class SchemaCompatibilityService {
    public static final String MERGE = "MERGE";
    public static final String PROJECT = "PROJECT";
    private static final Set<String> MODES = Set.of(MERGE, PROJECT);
    private static final Set<String> RESERVED = Set.of("__sourceRefs", "__sourceSnapshots", "__evidence");
    private static final Pattern PATH_SEGMENT = Pattern.compile("^[\\p{L}_$][\\p{L}\\p{N}_$-]*$");

    private final ObjectMapper objectMapper;

    public SchemaCompatibilityService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EdgeContractReport validate(String sourceNodeId, OperatorVersion sourceVersion,
                                       String targetNodeId, OperatorVersion targetVersion,
                                       Map<String, Object> edge) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        ParsedSchema source = parse(sourceVersion == null ? null : sourceVersion.getOutputSchema(),
            "源算子输出Schema", errors);
        ParsedSchema target = parse(targetVersion == null ? null : targetVersion.getInputSchema(),
            "目标算子输入Schema", errors);
        String mode = text(edge == null ? null : edge.get("mappingMode"));
        mode = mode == null ? MERGE : mode.toUpperCase(Locale.ROOT);
        if (!MODES.contains(mode)) errors.add("mappingMode只支持MERGE或PROJECT");

        List<FieldMapping> mappings = mappings(edge, errors);
        if (PROJECT.equals(mode) && mappings.isEmpty()) {
            errors.add("PROJECT模式至少需要一个字段映射");
        }
        if (!source.known()) warnings.add("源算子输出Schema未声明记录字段，无法静态确认来源字段");
        if (!target.known()) warnings.add("目标算子输入Schema未声明记录字段，无法静态确认目标字段");

        Set<String> mappedTargets = new LinkedHashSet<>();
        for (FieldMapping mapping : mappings) {
            if (!validPath(mapping.from())) errors.add("来源字段路径格式不正确: " + mapping.from());
            if (!validPath(mapping.to())) errors.add("目标字段路径格式不正确: " + mapping.to());
            if (reserved(mapping.from()) || reserved(mapping.to())) {
                errors.add("平台血缘字段不能由边映射覆盖: " + mapping.from() + " -> " + mapping.to());
            }
            if (!mappedTargets.add(mapping.to())) errors.add("目标字段重复映射: " + mapping.to());
            FieldSpec sourceField = source.fields().get(mapping.from());
            FieldSpec targetField = target.fields().get(mapping.to());
            if (source.known() && sourceField == null) errors.add("源Schema不存在字段: " + mapping.from());
            if (target.known() && targetField == null) errors.add("目标Schema不存在字段: " + mapping.to());
            if (sourceField != null && targetField != null
                    && !compatible(sourceField.types(), targetField.types())) {
                errors.add("字段类型不兼容: " + mapping.from() + "(" + typeText(sourceField.types())
                    + ") -> " + mapping.to() + "(" + typeText(targetField.types()) + ")");
            }
        }

        for (String required : target.requiredPaths()) {
            if (satisfiedByMapping(required, mappings)) continue;
            if (MERGE.equals(mode)) {
                FieldSpec sourceField = source.fields().get(required);
                FieldSpec targetField = target.fields().get(required);
                if (sourceField != null && (targetField == null
                        || compatible(sourceField.types(), targetField.types()))) continue;
                if (!source.known()) {
                    warnings.add("无法静态确认目标必填字段是否存在: " + required);
                    continue;
                }
            }
            errors.add("目标必填字段没有可用来源或默认值: " + required);
        }

        return new EdgeContractReport(sourceNodeId, targetNodeId, mode, errors.isEmpty(),
            List.copyOf(errors), List.copyOf(warnings), views(source), views(target));
    }

    private ParsedSchema parse(String raw, String label, List<String> errors) {
        if (raw == null || raw.isBlank()) return ParsedSchema.unknown();
        try {
            Map<String, Object> root = objectMapper.readValue(raw, new TypeReference<>() {});
            Map<String, Object> record = recordSchema(root);
            Map<String, FieldSpec> fields = new LinkedHashMap<>();
            Set<String> required = new LinkedHashSet<>();
            flatten(record, "", fields, required, 0);
            boolean known = !fields.isEmpty();
            return new ParsedSchema(fields, required, known);
        } catch (Exception e) {
            errors.add(label + "无法解析: " + e.getMessage());
            return ParsedSchema.unknown();
        }
    }

    private Map<String, Object> recordSchema(Map<String, Object> root) {
        if ("array".equals(type(root)) && root.get("items") instanceof Map<?, ?> items) {
            return stringMap(items);
        }
        Map<String, Object> properties = map(root.get("properties"));
        if (properties.get("records") instanceof Map<?, ?> records) {
            Map<String, Object> recordsSchema = stringMap(records);
            if (recordsSchema.get("items") instanceof Map<?, ?> items) return stringMap(items);
            return recordsSchema;
        }
        if (properties.isEmpty() && strings(root.get("required")).contains("records")) {
            return Map.of();
        }
        return root;
    }

    private void flatten(Map<String, Object> schema, String prefix,
                         Map<String, FieldSpec> fields, Set<String> requiredPaths, int depth) {
        if (depth > 12) return;
        Map<String, Object> properties = map(schema.get("properties"));
        Set<String> required = new LinkedHashSet<>(strings(schema.get("required")));
        if (properties.isEmpty()) {
            for (String name : required) {
                if (!validPath(name)) continue;
                String path = prefix.isEmpty() ? name : prefix + "." + name;
                fields.putIfAbsent(path, new FieldSpec(path, Set.of(), true));
                requiredPaths.add(path);
            }
            return;
        }
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> rawChild)) continue;
            String name = entry.getKey();
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            Map<String, Object> child = stringMap(rawChild);
            boolean isRequired = required.contains(name);
            fields.put(path, new FieldSpec(path, types(child), isRequired));
            if (isRequired) requiredPaths.add(path);
            flatten(child, path, fields, requiredPaths, depth + 1);
        }
    }

    private List<FieldMapping> mappings(Map<String, Object> edge, List<String> errors) {
        Object raw = edge == null ? null : edge.get("fieldMappings");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            errors.add("fieldMappings必须是数组");
            return List.of();
        }
        List<FieldMapping> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> value)) {
                errors.add("字段映射#" + (i + 1) + "必须是对象");
                continue;
            }
            Map<String, Object> mapping = stringMap(value);
            String from = text(mapping.get("from"));
            String to = text(mapping.get("to"));
            if (from == null || to == null) {
                errors.add("字段映射#" + (i + 1) + "缺少from或to");
                continue;
            }
            result.add(new FieldMapping(from, to,
                Boolean.parseBoolean(String.valueOf(mapping.getOrDefault("required", false))),
                mapping.containsKey("defaultValue")));
        }
        return result;
    }

    private boolean satisfiedByMapping(String required, List<FieldMapping> mappings) {
        for (FieldMapping mapping : mappings) {
            if (mapping.to().equals(required) || mapping.to().startsWith(required + ".")) {
                return mapping.hasDefault() || mapping.required();
            }
        }
        return false;
    }

    private boolean compatible(Set<String> source, Set<String> target) {
        if (source.isEmpty() || target.isEmpty()) return true;
        if (target.contains("any") || source.contains("any")) return true;
        for (String type : source) {
            if (target.contains(type)) return true;
            if ("integer".equals(type) && target.contains("number")) return true;
            if ("null".equals(type)) continue;
        }
        return false;
    }

    private List<FieldView> views(ParsedSchema schema) {
        return schema.fields().values().stream()
            .map(field -> new FieldView(field.path(), List.copyOf(field.types()), field.required()))
            .toList();
    }

    private Set<String> types(Map<String, Object> schema) {
        Object raw = schema.get("type");
        if (raw instanceof String value) return Set.of(value.toLowerCase(Locale.ROOT));
        if (raw instanceof List<?> list) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            list.forEach(value -> result.add(String.valueOf(value).toLowerCase(Locale.ROOT)));
            return result;
        }
        return Set.of();
    }

    private String type(Map<String, Object> schema) {
        Object raw = schema.get("type");
        return raw instanceof String value ? value.toLowerCase(Locale.ROOT) : null;
    }

    private String typeText(Set<String> types) {
        return types.isEmpty() ? "未声明" : String.join("|", types);
    }

    private boolean validPath(String path) {
        if (path == null || path.isBlank()) return false;
        for (String segment : path.split("\\.", -1)) {
            if (!PATH_SEGMENT.matcher(segment).matches()) return false;
        }
        return true;
    }

    private boolean reserved(String path) {
        if (path == null) return false;
        String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        return RESERVED.contains(root) || root.startsWith("__");
    }

    private Map<String, Object> map(Object raw) {
        return raw instanceof Map<?, ?> value ? stringMap(value) : Map.of();
    }

    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private record ParsedSchema(Map<String, FieldSpec> fields, Set<String> requiredPaths,
                                boolean known) {
        private static ParsedSchema unknown() {
            return new ParsedSchema(Map.of(), Set.of(), false);
        }
    }

    private record FieldSpec(String path, Set<String> types, boolean required) {}
    private record FieldMapping(String from, String to, boolean required, boolean hasDefault) {}

    public record FieldView(String path, List<String> types, boolean required) {}
    public record EdgeContractReport(String sourceNodeId, String targetNodeId, String mappingMode,
                                     boolean compatible, List<String> errors, List<String> warnings,
                                     List<FieldView> sourceFields, List<FieldView> targetFields) {}
}
