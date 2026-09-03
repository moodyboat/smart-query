package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.orchestration.OperatorTypes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuleDslOperatorExecutorTest {
    private final RuleDslOperatorExecutor executor = new RuleDslOperatorExecutor();

    @Test
    void composesKeywordGroupingCountAndThreshold() {
        Map<String, Object> payload = Map.of("steps", List.of(
            step("keywords", "text_match", Map.of("field", "content", "mode", "any",
                "keywords", List.of("投诉", "欺诈"))),
            step("company", "group_by", Map.of("fields", List.of("companyId"))),
            step("count", "aggregate", Map.of("metrics", List.of(
                Map.of("name", "hit_count", "function", "count")))),
            step("threshold", "threshold", Map.of("expression", "hit_count >= 2"))
        ));
        List<Map<String, Object>> records = List.of(
            Map.of("companyId", "A", "content", "收到投诉"),
            Map.of("companyId", "A", "content", "疑似欺诈"),
            Map.of("companyId", "B", "content", "正常")
        );

        OperatorExecutionResult result = executor.execute(context(payload, records));

        assertEquals(1, result.output().get("recordCount"));
        @SuppressWarnings("unchecked")
        Map<String, Object> output = ((List<Map<String, Object>>) result.output().get("records")).get(0);
        assertEquals("A", output.get("companyId"));
        assertEquals(2L, output.get("hit_count"));
        assertEquals("HIT", output.get("decisionResult"));
    }

    @Test
    void emitsStandardLeadWithSourceSnapshotAndEvidence() {
        Map<String, Object> payload = Map.of("steps", List.of(
            step("keywords", "text_match", Map.of("field", "content", "mode", "any",
                "keywords", List.of("投诉"))),
            step("lead", "lead_output", Map.of(
                "leadType", "COMPLAINT",
                "subjectMapping", Map.of("subjectType", Map.of("value", "COMPANY"),
                    "subjectId", "$companyId", "subjectName", "$companyName"),
                "sourceMapping", Map.of("primaryKeyColumn", Map.of("value", "eventId"),
                    "primaryKeyValue", "$eventId")
            ))
        ));

        OperatorExecutionResult result = executor.execute(context(payload, List.of(
            Map.of("eventId", "E-1", "companyId", "A", "companyName", "甲公司", "content", "投诉记录"))));

        assertEquals(1, result.leads().size());
        LeadDraft lead = result.leads().get(0);
        assertEquals("A", lead.subjectId());
        assertEquals("E-1", lead.primaryKeyValue());
        assertFalse(lead.sourceSnapshot().isEmpty());
        assertEquals(1, lead.evidence().size());
    }

    @Test
    void rejectsRegexUntilSandboxExecutorExists() {
        Map<String, Object> payload = Map.of("steps", List.of(
            step("regex", "text_match", Map.of("field", "content", "mode", "regex",
                "keywords", List.of(".*")))
        ));

        BusinessException error = assertThrows(BusinessException.class,
            () -> executor.execute(context(payload, List.of(Map.of("content", "anything")))));
        assertEquals(422, error.getCode());
    }

    private Map<String, Object> step(String id, String op, Map<String, Object> config) {
        return Map.of("id", id, "op", op, "config", config);
    }

    private OperatorExecutionContext context(Map<String, Object> payload,
                                             List<Map<String, Object>> records) {
        return new OperatorExecutionContext(1L, 2L, "rule", OperatorTypes.RULE,
            new OperatorVersion(), payload, Map.of(), Map.of("records", records), Map.of());
    }
}
