package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EdgeMappingServiceTest {
    private final EdgeMappingService service = new EdgeMappingService();

    @Test
    void projectsNestedFieldsAndAlwaysPreservesLineageAndEvidence() {
        Map<String, Object> record = Map.of(
            "order", Map.of("id", "P-1"),
            "probability", 0.93,
            LineageSupport.SOURCE_REFS, List.of("run:1:record:1"),
            LineageSupport.SOURCE_SNAPSHOTS, List.of(Map.of("rawId", "P-1")),
            LineageSupport.EVIDENCE, List.of(Map.of("kind", "MODEL_SCORE")));
        OperatorExecutionResult source = new OperatorExecutionResult(
            Map.of("records", List.of(record)), List.of(), "source");

        OperatorExecutionResult result = service.apply(Map.of(
            "mappingMode", "PROJECT",
            "fieldMappings", List.of(
                Map.of("from", "order.id", "to", "case.orderId", "required", true),
                Map.of("from", "probability", "to", "riskScore", "required", true))), source);

        Map<String, Object> mapped = records(result).get(0);
        assertEquals(Map.of("orderId", "P-1"), mapped.get("case"));
        assertEquals(0.93, mapped.get("riskScore"));
        assertEquals(record.get(LineageSupport.SOURCE_REFS), mapped.get(LineageSupport.SOURCE_REFS));
        assertEquals(record.get(LineageSupport.SOURCE_SNAPSHOTS), mapped.get(LineageSupport.SOURCE_SNAPSHOTS));
        assertEquals(record.get(LineageSupport.EVIDENCE), mapped.get(LineageSupport.EVIDENCE));
        assertFalse(mapped.containsKey("probability"));
    }

    @Test
    void mergeKeepsOriginalFieldsAndUsesDefaultForMissingSource() {
        Map<String, Object> record = Map.of(
            "orderId", "P-2",
            LineageSupport.SOURCE_REFS, List.of("run:1:record:2"),
            LineageSupport.SOURCE_SNAPSHOTS, List.of(Map.of("orderId", "P-2")));
        OperatorExecutionResult source = new OperatorExecutionResult(
            Map.of("records", List.of(record)), List.of(), "source");

        OperatorExecutionResult result = service.apply(Map.of(
            "fieldMappings", List.of(Map.of(
                "from", "missingScore", "to", "riskScore", "defaultValue", 0))), source);

        assertEquals("P-2", records(result).get(0).get("orderId"));
        assertEquals(0, records(result).get(0).get("riskScore"));
    }

    @Test
    void rejectsMissingRequiredSourceAtRuntime() {
        Map<String, Object> record = Map.of(
            LineageSupport.SOURCE_REFS, List.of("run:1:record:1"),
            LineageSupport.SOURCE_SNAPSHOTS, List.of(Map.of("id", "A")));
        OperatorExecutionResult source = new OperatorExecutionResult(
            Map.of("records", List.of(record)), List.of(), "source");

        assertThrows(BusinessException.class, () -> service.apply(Map.of(
            "fieldMappings", List.of(Map.of("from", "score", "to", "riskScore", "required", true))),
            source));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(OperatorExecutionResult result) {
        return (List<Map<String, Object>>) result.output().get("records");
    }
}
