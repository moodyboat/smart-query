package com.smartquery.orchestration.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.orchestration.LeadOutputPolicyService;
import com.smartquery.orchestration.OperatorTypes;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.smartquery.common.BusinessException;

class OutputOperatorExecutorTest {
    private final OutputOperatorExecutor executor = new OutputOperatorExecutor(
        new LeadOutputPolicyService(new ObjectMapper()));

    @Test
    void modelPredictionBecomesLeadWithOriginalInputSnapshot() {
        Map<String, Object> original = Map.of(
            "loanId", "L-1", "customerName", "张三", "daysPastDue", 0,
            "contractAmount", 500_000);
        Map<String, Object> predicted = new LinkedHashMap<>(original);
        predicted.put("overdueProbability", 0.82);
        predicted.put("prediction", "WILL_OVERDUE");
        predicted.put(LineageSupport.SOURCE_REFS, List.of("run:1:record:1"));
        predicted.put(LineageSupport.SOURCE_SNAPSHOTS, List.of(original));
        Map<String, Object> policy = Map.of(
            "leadType", "OVERDUE_RISK",
            "mode", "CONDITION",
            "condition", Map.of("field", "overdueProbability", "operator", ">=", "value", 0.7),
            "subjectMapping", Map.of("subjectType", Map.of("value", "LOAN"),
                "subjectId", "$loanId", "subjectName", "$customerName"),
            "decisionMapping", Map.of("score", "$overdueProbability",
                "threshold", Map.of("value", 0.7), "result", "$prediction")
        );
        OperatorExecutionContext context = context("LEAD", Map.of(),
            Map.of("leadPolicy", policy), List.of(predicted));

        OperatorExecutionResult result = executor.execute(context);

        assertEquals(1, result.leads().size());
        LeadDraft lead = result.leads().get(0);
        assertEquals(0.82, lead.decisionScore());
        assertEquals("L-1", lead.subjectId());
        assertEquals(original, lead.sourceSnapshot());
        assertFalse(lead.sourceSnapshot().containsKey("overdueProbability"));
    }

    @Test
    void chartOutputKeepsContentRequirementsAsArtifact() {
        Map<String, Object> spec = Map.of("chartType", "bar",
            "dimensions", List.of("month"), "measures", List.of("amount"),
            "title", "月度金额");
        Map<String, Object> record = new LinkedHashMap<>(Map.of("month", "2026-08", "amount", 12));
        record.put(LineageSupport.SOURCE_REFS, List.of("source-1"));
        record.put(LineageSupport.SOURCE_SNAPSHOTS, List.of(Map.of("month", "2026-08", "amount", 12)));

        OperatorExecutionResult result = executor.execute(context("CHART", spec, Map.of(), List.of(record)));

        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) result.output().get("artifact");
        assertEquals("chart-spec-v1", artifact.get("renderer"));
        assertEquals(spec, artifact.get("contentSpec"));
    }

    @Test
    void authoredOutputVersionCannotBeOverriddenByDagNode() {
        OperatorExecutionResult upstream = new OperatorExecutionResult(
            Map.of("records", List.of(Map.of())), List.of(), "");
        OperatorExecutionContext context = new OperatorExecutionContext(
            1L, 2L, "output", OperatorTypes.OUTPUT, new OperatorVersion(),
            Map.of("outputKind", "TABLE", "draftId", 7,
                "contentSpec", Map.of("columns", List.of(Map.of("field", "id", "title", "ID")))),
            Map.of("contentSpec", Map.of("title", "篡改")), Map.of(), Map.of("input", upstream));

        assertThrows(BusinessException.class, () -> executor.execute(context));
    }

    private OperatorExecutionContext context(String kind, Map<String, Object> contentSpec,
                                             Map<String, Object> nodeConfig,
                                             List<Map<String, Object>> records) {
        OperatorExecutionResult upstream = new OperatorExecutionResult(
            Map.of("records", records), List.of(), "");
        return new OperatorExecutionContext(1L, 2L, "output", OperatorTypes.OUTPUT,
            new OperatorVersion(), Map.of("outputKind", kind, "contentSpec", contentSpec),
            nodeConfig, Map.of(), Map.of("model", upstream));
    }
}
