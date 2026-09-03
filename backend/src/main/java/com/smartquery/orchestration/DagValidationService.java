package com.smartquery.orchestration;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/** Deterministic validation and topological planning for V2 flow edges. */
@Service
public class DagValidationService {

    public DagValidationReport validate(List<Map<String, Object>> nodes,
                                        List<Map<String, Object>> edges) {
        return validate(nodes, edges, true);
    }

    public DagValidationReport validate(List<Map<String, Object>> nodes,
                                        List<Map<String, Object>> edges,
                                        boolean requireOperatorVersionBinding) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();

        if (nodes == null || nodes.isEmpty()) {
            return new DagValidationReport(false, List.of("DAG至少需要一个节点"), List.of(), List.of(), List.of());
        }
        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> node = nodes.get(i);
            String id = text(node == null ? null : node.get("id"));
            if (id == null || id.isBlank()) {
                errors.add("节点#" + (i + 1) + "缺少id");
                continue;
            }
            if (byId.putIfAbsent(id, node) != null) errors.add("节点id重复: " + id);
            if (requireOperatorVersionBinding && toLong(node.get("operatorVersionId")) == null) {
                errors.add("节点[" + id + "]未绑定operatorVersionId");
            }
        }

        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, Set<String>> undirected = new HashMap<>();
        for (String id : byId.keySet()) {
            outgoing.put(id, new LinkedHashSet<>());
            indegree.put(id, 0);
            undirected.put(id, new HashSet<>());
        }

        Set<String> uniqueEdges = new HashSet<>();
        if (edges != null) {
            for (int i = 0; i < edges.size(); i++) {
                Map<String, Object> edge = edges.get(i);
                String source = text(edge == null ? null : edge.get("source"));
                String target = text(edge == null ? null : edge.get("target"));
                if (source == null || target == null) {
                    errors.add("边#" + (i + 1) + "缺少source或target");
                    continue;
                }
                if (!byId.containsKey(source)) errors.add("边引用不存在的源节点: " + source);
                if (!byId.containsKey(target)) errors.add("边引用不存在的目标节点: " + target);
                if (source.equals(target)) errors.add("节点不能连接自身: " + source);
                if (!byId.containsKey(source) || !byId.containsKey(target) || source.equals(target)) continue;
                String edgeKey = source + "\u0000" + target;
                if (!uniqueEdges.add(edgeKey)) {
                    errors.add("重复边不允许存在，字段映射会产生歧义: " + source + " -> " + target);
                    continue;
                }
                outgoing.get(source).add(target);
                indegree.put(target, indegree.get(target) + 1);
                undirected.get(source).add(target);
                undirected.get(target).add(source);
            }
        }

        List<List<String>> levels = new ArrayList<>();
        List<String> order = new ArrayList<>();
        List<String> current = indegree.entrySet().stream()
            .filter(entry -> entry.getValue() == 0)
            .map(Map.Entry::getKey).sorted().toList();
        while (!current.isEmpty()) {
            levels.add(List.copyOf(current));
            List<String> next = new ArrayList<>();
            for (String source : current) {
                order.add(source);
                for (String target : outgoing.get(source).stream().sorted().toList()) {
                    int remaining = indegree.compute(target, (key, value) -> value - 1);
                    if (remaining == 0) next.add(target);
                }
            }
            next.sort(Comparator.naturalOrder());
            current = next;
        }
        if (order.size() != byId.size()) {
            Set<String> cyclic = new LinkedHashSet<>(byId.keySet());
            cyclic.removeAll(order);
            errors.add("DAG存在环，涉及节点: " + String.join(", ", cyclic));
        }

        int components = connectedComponents(byId.keySet(), undirected);
        if (components > 1) warnings.add("DAG包含" + components + "个互不连通的子图");
        long roots = levels.isEmpty() ? 0 : levels.get(0).size();
        if (roots > 1) warnings.add("DAG包含" + roots + "个入口节点，将按同一层并行执行");

        return new DagValidationReport(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings),
            List.copyOf(order), levels.stream().map(List::copyOf).toList());
    }

    private int connectedComponents(Set<String> nodes, Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        int count = 0;
        for (String start : nodes) {
            if (!visited.add(start)) continue;
            count++;
            Queue<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                for (String next : graph.getOrDefault(queue.remove(), Set.of())) {
                    if (visited.add(next)) queue.add(next);
                }
            }
        }
        return count;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    public static Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    public record DagValidationReport(boolean valid, List<String> errors, List<String> warnings,
                                      List<String> topologicalOrder, List<List<String>> executionLevels) {}
}
