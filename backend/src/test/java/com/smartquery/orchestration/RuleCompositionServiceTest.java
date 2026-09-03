package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.RulePrimitive;
import com.smartquery.mapper.RulePrimitiveMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleCompositionServiceTest {

    @Mock RulePrimitiveMapper mapper;
    private RuleCompositionService service;

    @BeforeEach
    void setUp() {
        when(mapper.selectList(any())).thenReturn(List.of(
            primitive("text_match", "field", "mode", "keywords"),
            primitive("time_window", "timeField", "range"),
            primitive("aggregate", "metrics"),
            primitive("threshold", "expression")));
        service = new RuleCompositionService(mapper, new DagValidationService(), new ObjectMapper());
    }

    @Test
    void validatesComposableKeywordCountRule() {
        Map<String, Object> rule = Map.of("steps", List.of(
            Map.of("id", "match", "op", "text_match", "config",
                Map.of("field", "content", "mode", "any", "keywords", List.of("投诉"))),
            Map.of("id", "window", "op", "time_window", "config",
                Map.of("timeField", "event_time", "range", "30d")),
            Map.of("id", "count", "op", "aggregate", "config",
                Map.of("metrics", List.of(Map.of("name", "hits", "function", "count")))),
            Map.of("id", "hit", "op", "threshold", "config",
                Map.of("expression", "hits >= 3"))));

        RuleCompositionService.RuleValidationReport result = service.validate(rule);

        assertTrue(result.supported());
        assertEquals(100, result.capabilityCoverage());
        assertEquals(List.of(List.of("match"), List.of("window"), List.of("count"), List.of("hit")),
            result.executionLevels());
    }

    @Test
    void reportsCapabilityGapInsteadOfPretendingRuleIsSupported() {
        Map<String, Object> rule = Map.of("steps", List.of(
            Map.of("id", "graph", "op", "graph_risk_propagation", "config", Map.of())));

        RuleCompositionService.RuleValidationReport result = service.validate(rule);

        assertFalse(result.supported());
        assertEquals(List.of("graph_risk_propagation"), result.missingCapabilities());
        assertEquals(0, result.capabilityCoverage());
    }

    private RulePrimitive primitive(String code, String... required) {
        RulePrimitive primitive = new RulePrimitive();
        primitive.setCode(code);
        primitive.setName(code);
        primitive.setCategory("test");
        primitive.setEnabled(1);
        primitive.setParameterSchema("{\"type\":\"object\",\"required\":[\""
            + String.join("\",\"", required) + "\"]}");
        return primitive;
    }
}
