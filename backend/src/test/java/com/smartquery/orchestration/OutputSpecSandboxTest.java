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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutputSpecSandboxTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutputCapabilityRegistryService registry = mock(OutputCapabilityRegistryService.class);
    private final OutputSpecSandbox sandbox = new OutputSpecSandbox(
        new ContentHashService(objectMapper), new LeadOutputPolicyService(objectMapper), registry);

    OutputSpecSandboxTest() {
        when(registry.resolvePublished(anyString())).thenAnswer(invocation -> capability(invocation.getArgument(0)));
    }

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
        assertEquals("composable-output-v2", result.renderer());
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

    @Test
    void normalizesSingleSortObjectAndOrderAliasFromModelOutput() {
        OutputSpecSandbox.ShapeResult result = sandbox.shape(Map.of(
            "outputKind", "EXCEL",
            "contentSpec", Map.of(
                "columns", List.of("customerId"),
                "sort", Map.of("field", "customerId", "order", "desc"))));

        Map<String, Object> spec = map(result.spec().get("contentSpec"));
        assertEquals(List.of(Map.of("field", "customerId", "direction", "desc")), spec.get("sort"));
    }

    @Test
    void shapesMultipleDatabaseResolvedTargetsIntoImmutableSnapshots() {
        OutputSpecSandbox.ShapeResult result = sandbox.shape(Map.of(
            "specVersion", 2,
            "transformations", List.of(Map.of("id", "sort-risk", "capabilityCode", "transform.project",
                "config", Map.of("sort", List.of(Map.of("field", "risk", "direction", "desc"))))),
            "targets", List.of(
                Map.of("id", "table", "capabilityCode", "view.table", "config",
                    Map.of("columns", List.of("customer", "risk"))),
                Map.of("id", "xlsx", "capabilityCode", "export.xlsx", "config", Map.of("fileName", "risk.xlsx")))));

        assertEquals(2, ((List<?>) result.spec().get("targets")).size());
        assertEquals("composable-output-v2", result.renderer());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) result.spec().get("targets")).get(0);
        assertEquals(2L, first.get("capabilityVersionId"));
        assertEquals("a".repeat(64), first.get("contentHash"));
    }

    private Map<String, Object> map(Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    private OutputCapabilityRegistryService.CapabilitySnapshot capability(String code) {
        String implementation = switch (code) {
            case "action.lead" -> "LEAD";
            case "view.echarts" -> "ECHARTS";
            case "transform.project" -> "PROJECT";
            case "export.xlsx" -> "XLSX";
            default -> "TABLE";
        };
        String type = code.startsWith("transform.") ? "TRANSFORM"
            : code.startsWith("export.") ? "EXPORT"
            : code.startsWith("action.") ? "ACTION" : "VIEW";
        return new OutputCapabilityRegistryService.CapabilitySnapshot(1L, code, code, type, "ENABLED",
            null, 2L, 1, "PUBLISHED", "a".repeat(64), Map.of(), Map.of(), Map.of(),
            implementation, "builtin://test", "sha256:" + "a".repeat(64), List.of(),
            "OUTPUT_RENDERER", List.of(), Map.of());
    }
}
