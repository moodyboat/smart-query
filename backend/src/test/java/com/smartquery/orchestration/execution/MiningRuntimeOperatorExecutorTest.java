package com.smartquery.orchestration.execution;

import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.RuntimeProfile;
import com.smartquery.orchestration.OperatorTypes;
import com.smartquery.orchestration.RuntimeProfileService;
import com.smartquery.service.MiningService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiningRuntimeOperatorExecutorTest {
    @Test
    void predictionAddsEvidenceWhileKeepingOriginalInputLineage() {
        MiningService miningService = mock(MiningService.class);
        RuntimeProfileService runtimeProfiles = mock(RuntimeProfileService.class);
        RuntimeProfile profile = new RuntimeProfile();
        profile.setImageRef("registry.example/ml@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        when(runtimeProfiles.requireRunnable(org.mockito.ArgumentMatchers.any(), eq(OperatorTypes.ML)))
            .thenReturn(new RuntimeProfileService.RuntimeBindingView(null, profile, List.of()));
        when(miningService.predictModelTransient(eq(21L), org.mockito.ArgumentMatchers.anyList(),
                eq(profile.getImageRef())))
            .thenReturn(Map.of("predictions", List.of("WILL_OVERDUE"),
                "probabilities", List.of(List.of(0.18, 0.82))));
        Map<String, Object> original = Map.of("loanId", "L-1", "amount", 500_000);
        Map<String, Object> input = new LinkedHashMap<>(original);
        input.put(LineageSupport.SOURCE_REFS, List.of("run:1:record:1"));
        input.put(LineageSupport.SOURCE_SNAPSHOTS, List.of(original));
        OperatorExecutionResult upstream = new OperatorExecutionResult(
            Map.of("records", List.of(input)), List.of(), "");
        OperatorExecutionContext context = new OperatorExecutionContext(
            1L, 2L, "model", OperatorTypes.ML, new OperatorVersion(),
            Map.of("modelId", 21, "predictionField", "riskPrediction",
                "probabilityField", "riskProbability", "probabilityIndex", 1),
            Map.of(), Map.of(), Map.of("input", upstream));

        OperatorExecutionResult result = new MiningRuntimeOperatorExecutor(miningService, runtimeProfiles).execute(context);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outputRecords =
            (List<Map<String, Object>>) result.output().get("records");
        Map<String, Object> record = outputRecords.get(0);
        assertEquals("WILL_OVERDUE", record.get("riskPrediction"));
        assertEquals(0.82, record.get("riskProbability"));
        assertEquals(List.of(original), record.get(LineageSupport.SOURCE_SNAPSHOTS));
        assertTrue(record.get(LineageSupport.EVIDENCE) instanceof List<?>);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> rows = ArgumentCaptor.forClass(List.class);
        verify(miningService).predictModelTransient(eq(21L), rows.capture(), eq(profile.getImageRef()));
        assertFalse(rows.getValue().get(0).containsKey(LineageSupport.SOURCE_REFS));
    }
}
