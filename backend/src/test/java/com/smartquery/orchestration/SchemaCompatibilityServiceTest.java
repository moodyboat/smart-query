package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.OperatorVersion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaCompatibilityServiceTest {
    private final SchemaCompatibilityService service = new SchemaCompatibilityService(new ObjectMapper());

    @Test
    void acceptsRequiredRenameWithCompatibleTypes() {
        OperatorVersion source = version(null, """
            {"type":"object","properties":{"probability":{"type":"number"},"orderId":{"type":"string"}}}
            """);
        OperatorVersion target = version("""
            {"type":"object","required":["riskScore"],"properties":{"riskScore":{"type":"number"}}}
            """, null);

        var result = service.validate("model", source, "output", target, Map.of(
            "mappingMode", "PROJECT",
            "fieldMappings", List.of(Map.of(
                "from", "probability", "to", "riskScore", "required", true))));

        assertTrue(result.compatible());
    }

    @Test
    void rejectsTypeMismatchAndUnfulfilledRequiredTarget() {
        OperatorVersion source = version(null, """
            {"type":"object","properties":{"amount":{"type":"string"}}}
            """);
        OperatorVersion target = version("""
            {"type":"object","required":["score","caseId"],"properties":{"score":{"type":"number"},"caseId":{"type":"string"}}}
            """, null);

        var result = service.validate("rule", source, "output", target, Map.of(
            "fieldMappings", List.of(Map.of("from", "amount", "to", "score", "required", true))));

        assertFalse(result.compatible());
        assertTrue(result.errors().stream().anyMatch(message -> message.contains("类型不兼容")));
        assertTrue(result.errors().stream().anyMatch(message -> message.contains("caseId")));
    }

    @Test
    void treatsEnvelopeRecordsSchemaAsUnknownRecordShape() {
        OperatorVersion source = version(null, "{\"type\":\"object\",\"required\":[\"records\"]}");
        OperatorVersion target = version("{\"type\":\"object\",\"required\":[\"records\"]}", null);

        var result = service.validate("source", source, "lead", target, Map.of());

        assertTrue(result.compatible());
        assertTrue(result.warnings().stream().anyMatch(message -> message.contains("无法静态确认")));
    }

    private OperatorVersion version(String inputSchema, String outputSchema) {
        OperatorVersion version = new OperatorVersion();
        version.setInputSchema(inputSchema == null ? "{}" : inputSchema);
        version.setOutputSchema(outputSchema == null ? "{}" : outputSchema);
        return version;
    }
}
