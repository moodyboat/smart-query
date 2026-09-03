package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Platform-owned provenance fields that every executor must preserve. */
public final class LineageSupport {
    public static final String SOURCE_REFS = "__sourceRefs";
    public static final String SOURCE_SNAPSHOTS = "__sourceSnapshots";
    public static final String EVIDENCE = "__evidence";

    private LineageSupport() {
    }

    public static List<Map<String, Object>> enrich(Long runId, List<Map<String, Object>> records) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> raw = new LinkedHashMap<>(records.get(i));
            Map<String, Object> envelope = new LinkedHashMap<>(raw);
            envelope.put(SOURCE_REFS, List.of("run:" + runId + ":record:" + (i + 1)));
            envelope.put(SOURCE_SNAPSHOTS, List.of(raw));
            result.add(envelope);
        }
        return result;
    }

    public static void requirePreserved(Map<String, Object> output, String nodeId) {
        Object raw = output == null ? null : output.get("records");
        if (!(raw instanceof List<?> records)) {
            throw new BusinessException(422, "节点[" + nodeId + "]输出缺少records数组");
        }
        for (int i = 0; i < records.size(); i++) {
            if (!(records.get(i) instanceof Map<?, ?> record)
                    || !(record.get(SOURCE_REFS) instanceof List<?> refs) || refs.isEmpty()
                    || !(record.get(SOURCE_SNAPSHOTS) instanceof List<?> snapshots) || snapshots.isEmpty()) {
                throw new BusinessException(422, "节点[" + nodeId + "]第" + (i + 1)
                    + "条输出丢失sourceRef；所有算子必须保留原始输入血缘");
            }
        }
    }

    public static void mergeInto(Map<String, Object> target, List<Map<String, Object>> records) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        List<Map<String, Object>> snapshots = new ArrayList<>();
        LinkedHashSet<String> snapshotKeys = new LinkedHashSet<>();
        List<Object> evidence = new ArrayList<>();
        for (Map<String, Object> record : records) {
            if (record.get(SOURCE_REFS) instanceof List<?> values) {
                values.forEach(value -> refs.add(String.valueOf(value)));
            }
            if (record.get(SOURCE_SNAPSHOTS) instanceof List<?> values) {
                for (Object value : values) {
                    if (!(value instanceof Map<?, ?> raw)) continue;
                    Map<String, Object> snapshot = stringMap(raw);
                    String key = snapshot.toString();
                    if (snapshotKeys.add(key)) snapshots.add(snapshot);
                }
            }
            if (record.get(EVIDENCE) instanceof List<?> values) evidence.addAll(values);
        }
        target.put(SOURCE_REFS, List.copyOf(refs));
        target.put(SOURCE_SNAPSHOTS, List.copyOf(snapshots));
        if (!evidence.isEmpty()) target.put(EVIDENCE, List.copyOf(evidence));
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
