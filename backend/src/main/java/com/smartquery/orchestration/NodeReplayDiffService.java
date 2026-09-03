package com.smartquery.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Produces a bounded, inspectable semantic diff instead of only comparing hashes. */
@org.springframework.stereotype.Service
@lombok.RequiredArgsConstructor
public class NodeReplayDiffService {
    private final ContentHashService contentHashService;

    public Map<String, Object> compare(Map<String, Object> original, Map<String, Object> replay,
                                       int sampleLimit) {
        List<Map<String, Object>> before = records(original.get("records"));
        List<Map<String, Object>> after = records(replay.get("records"));
        Map<String, Map<String, Object>> beforeByKey = indexed(before);
        Map<String, Map<String, Object>> afterByKey = indexed(after);
        Set<String> keys = new LinkedHashSet<>(beforeByKey.keySet());
        keys.addAll(afterByKey.keySet());
        int added = 0, removed = 0, changed = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> left = beforeByKey.get(key), right = afterByKey.get(key);
            String kind;
            if (left == null) { added++; kind = "ADDED"; }
            else if (right == null) { removed++; kind = "REMOVED"; }
            else if (!contentHashService.sha256(left).equals(contentHashService.sha256(right))) {
                changed++; kind = "CHANGED";
            } else continue;
            if (samples.size() < Math.max(0, sampleLimit)) {
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("key", key);
                sample.put("kind", kind);
                sample.put("changedFields", changedFields(left, right));
                sample.put("original", compactRecord(left));
                sample.put("replay", compactRecord(right));
                samples.add(sample);
            }
        }
        Map<String, Object> metricChanges = changedFields(withoutRecords(original), withoutRecords(replay));
        return Map.of("exactMatch", contentHashService.sha256(original).equals(contentHashService.sha256(replay)),
            "originalRecordCount", before.size(), "replayRecordCount", after.size(),
            "added", added, "removed", removed, "changed", changed,
            "metricChanges", metricChanges, "samples", samples);
    }

    private Map<String, Map<String, Object>> indexed(List<Map<String, Object>> records) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (int index = 0; index < records.size(); index++) {
            Map<String, Object> record = records.get(index);
            String base = identity(record, index);
            int occurrence = occurrences.compute(base, (key, count) -> count == null ? 1 : count + 1);
            result.put(base + "#" + occurrence, record);
        }
        return result;
    }

    private String identity(Map<String, Object> record, int index) {
        if (record.containsKey("__sourceRefs")) {
            return "source:" + contentHashService.sha256(record.get("__sourceRefs"));
        }
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String field = entry.getKey();
            String key = field.toLowerCase();
            boolean idField = "id".equals(key) || key.endsWith("_id")
                || field.endsWith("Id") || field.endsWith("ID");
            if (idField && entry.getValue() != null) {
                return entry.getKey() + ":" + entry.getValue();
            }
        }
        return "index:" + index;
    }

    private Map<String, Object> changedFields(Map<String, Object> left, Map<String, Object> right) {
        Set<String> keys = new LinkedHashSet<>();
        if (left != null) keys.addAll(left.keySet());
        if (right != null) keys.addAll(right.keySet());
        Map<String, Object> result = new LinkedHashMap<>();
        int changed = 0;
        for (String key : keys) {
            Object before = left == null ? null : left.get(key);
            Object after = right == null ? null : right.get(key);
            if (!contentHashService.sha256(before).equals(contentHashService.sha256(after))) {
                changed++;
                if (result.size() < 20) {
                    result.put(key, Map.of(
                        "original", before == null ? "<missing>" : compactValue(before),
                        "replay", after == null ? "<missing>" : compactValue(after)));
                }
            }
        }
        if (changed > result.size()) result.put("__truncatedFields", changed - result.size());
        return result;
    }

    private Map<String, Object> compactRecord(Map<String, Object> record) {
        if (record == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        int included = 0;
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            if (included++ >= 12) break;
            result.put(entry.getKey(), compactValue(entry.getValue()));
        }
        if (record.size() > result.size()) result.put("__truncatedFields", record.size() - result.size());
        return result;
    }

    private Object compactValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof CharSequence text) {
            String result = text.toString();
            return result.length() <= 160 ? result : result.substring(0, 160) + "…";
        }
        if (value instanceof Map<?, ?> map) {
            return Map.of("_type", "object", "size", map.size(),
                "hash", contentHashService.sha256(value).substring(0, 12));
        }
        if (value instanceof List<?> list) {
            return Map.of("_type", "array", "size", list.size(),
                "hash", contentHashService.sha256(value).substring(0, 12));
        }
        String result = String.valueOf(value);
        return result.length() <= 160 ? result : result.substring(0, 160) + "…";
    }

    private Map<String, Object> withoutRecords(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>(input);
        result.remove("records");
        return result;
    }

    private List<Map<String, Object>> records(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> record = new LinkedHashMap<>();
            map.forEach((key, value) -> record.put(String.valueOf(key), value));
            result.add(record);
        }
        return result;
    }
}
