package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.orchestration.execution.LineageSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputSpecSandboxTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutputSpecSandbox sandbox = new OutputSpecSandbox(
        new ContentHashService(objectMapper), new LeadOutputPolicyService(objectMapper));

    @Test
    void shapesExcelAsLineageAwareGridAndDropsUnknownProperties() {
        OutputSpecSandbox.ShapeResult result = sandbox.shape(Map.of(
            "outputKind", "EXCEL",
            "contentSpec", Map.of(
                "title", "逾期预测结果",
                "columns", List.of(
                    Map.of("field", "customerName", "title", "客户"),
                    Map.of("field", "predictionProbability", "title", "逾期概率", "format", "percent")),
                "pageSize", 100,
                "theme", "untrusted-theme")));

        Map<String, Object> spec = map(result.spec().get("contentSpec"));
        assertEquals("EXCEL", result.spec().get("outputKind"));
        assertEquals("excel-grid-v1", result.renderer());
        assertEquals(true, spec.get("showLineage"));
        assertFalse(spec.containsKey("theme"));
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("theme")));
    }

    @Test
    void rejectsExecutableDisplayConfiguration() {
        assertThrows(BusinessException.class, () -> sandbox.shape(Map.of(
            "outputKind", "TABLE",
            "contentSpec", Map.of(
                "columns", List.of("orderId"),
                "formatter", "function(value){ return value; }"))));
    }

    @Test
    void previewValidatesColumnsAndNumericChartMeasuresAgainstLineageRecords() {
        Map<String, Object> shaped = sandbox.shape(Map.of(
            "outputKind", "CHART",
            "contentSpec", Map.of("chartType", "bar",
                "dimensions", List.of("customerName"),
                "measures", List.of("predictionProbability")))).spec();
        List<Map<String, Object>> records = LineageSupport.enrich(7L, List.of(
            Map.of("customerName", "甲公司", "predictionProbability", 0.82)));

        OutputSpecSandbox.PreviewValidation validation = sandbox.validatePreview(shaped, records);

        assertTrue(validation.valid());
        assertTrue(validation.errors().isEmpty());
    }

    private Map<String, Object> map(Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }
}
