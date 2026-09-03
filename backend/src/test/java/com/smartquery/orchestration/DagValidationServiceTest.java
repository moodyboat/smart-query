package com.smartquery.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagValidationServiceTest {

    private final DagValidationService service = new DagValidationService();

    @Test
    void createsParallelExecutionLevelsFromActualEdges() {
        List<Map<String, Object>> nodes = List.of(
            Map.of("id", "source", "operatorVersionId", 1),
            Map.of("id", "rule_a", "operatorVersionId", 2),
            Map.of("id", "rule_b", "operatorVersionId", 3),
            Map.of("id", "lead", "operatorVersionId", 4));
        List<Map<String, Object>> edges = List.of(
            Map.of("source", "source", "target", "rule_a"),
            Map.of("source", "source", "target", "rule_b"),
            Map.of("source", "rule_a", "target", "lead"),
            Map.of("source", "rule_b", "target", "lead"));

        DagValidationService.DagValidationReport result = service.validate(nodes, edges);

        assertTrue(result.valid());
        assertEquals(List.of(
            List.of("source"), List.of("rule_a", "rule_b"), List.of("lead")),
            result.executionLevels());
    }

    @Test
    void rejectsCyclesAndMissingVersionBindings() {
        List<Map<String, Object>> nodes = List.of(Map.of("id", "a"), Map.of("id", "b"));
        List<Map<String, Object>> edges = List.of(
            Map.of("source", "a", "target", "b"),
            Map.of("source", "b", "target", "a"));

        DagValidationService.DagValidationReport result = service.validate(nodes, edges);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(message -> message.contains("operatorVersionId")));
        assertTrue(result.errors().stream().anyMatch(message -> message.contains("存在环")));
    }

    @Test
    void rejectsDuplicateEdgesBecauseTheirFieldMappingsWouldBeAmbiguous() {
        List<Map<String, Object>> nodes = List.of(
            Map.of("id", "a", "operatorVersionId", 1),
            Map.of("id", "b", "operatorVersionId", 2));
        List<Map<String, Object>> edges = List.of(
            Map.of("source", "a", "target", "b"),
            Map.of("source", "a", "target", "b", "mappingMode", "PROJECT"));

        DagValidationService.DagValidationReport result = service.validate(nodes, edges);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(message -> message.contains("字段映射会产生歧义")));
    }
}
