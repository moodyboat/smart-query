package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.PermissionCodes;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.ModelExecution;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.entity.OutputArtifact;
import com.smartquery.entity.PredictionResult;
import com.smartquery.mapper.FlowDefinitionMapper;
import com.smartquery.mapper.FlowVersionMapper;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.ModelExecutionMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import com.smartquery.mapper.OutputArtifactMapper;
import com.smartquery.mapper.PredictionResultMapper;
import com.smartquery.mapper.ScheduleTaskMapper;
import com.smartquery.service.ResourceAccessService;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FormalTaskMonitorServiceTest {
    private final OrchestrationRunMapper runs = mock(OrchestrationRunMapper.class);
    private final OutputArtifactMapper artifacts = mock(OutputArtifactMapper.class);
    private final ModelExecutionMapper executions = mock(ModelExecutionMapper.class);
    private final PredictionResultMapper predictions = mock(PredictionResultMapper.class);
    private final ScheduleTaskMapper scheduleTasks = mock(ScheduleTaskMapper.class);
    private final MiningModelMapper models = mock(MiningModelMapper.class);
    private final FlowVersionMapper flowVersions = mock(FlowVersionMapper.class);
    private final FlowDefinitionMapper flows = mock(FlowDefinitionMapper.class);
    private final StorageHotDataViewService hotViews = mock(StorageHotDataViewService.class);
    private final ResourceAccessService access = mock(ResourceAccessService.class);
    private final FormalTaskMonitorService service = new FormalTaskMonitorService(runs, artifacts,
        executions, predictions, scheduleTasks, models, flowVersions, flows, hotViews, access, new ObjectMapper());

    @Test
    @SuppressWarnings("unchecked")
    void onlyIncludesFormalScheduledRunsAndScheduledModelExecutions() {
        OrchestrationRun formal = run(1L, "FORMAL", "SCHEDULE");
        OrchestrationRun trial = run(2L, "TRIAL", "API");
        when(runs.selectList(any())).thenReturn(List.of(trial, formal));

        OutputArtifact output = new OutputArtifact();
        output.setId(9L); output.setRunId(1L); output.setStatus("READY"); output.setArchiveStatus("ACTIVE");
        when(artifacts.selectList(any())).thenReturn(List.of(output));

        ModelExecution scheduled = execution(11L, "schedule", "PREDICT");
        ModelExecution manual = execution(12L, "manual", "TRAIN");
        when(executions.selectList(any())).thenReturn(List.of(manual, scheduled));

        PredictionResult scheduledResult = prediction(21L, 11L, "schedule");
        PredictionResult manualResult = prediction(22L, 12L, "manual");
        when(predictions.selectList(any())).thenReturn(List.of(manualResult, scheduledResult));

        MiningModel model = new MiningModel();
        model.setId(5L); model.setName("客户风险模型"); model.setVersion(3);
        model.setAlgorithm("logistic_regression"); model.setSourceTable("customer_risk"); model.setUserId("7");
        when(models.selectBatchIds(any(Collection.class))).thenReturn(List.of(model));
        when(flowVersions.selectBatchIds(any(Collection.class))).thenReturn(List.of());
        when(hotViews.views(any())).thenReturn(List.of());

        FormalTaskMonitorService.MonitorDashboard dashboard = service.dashboard();

        verify(access).requirePermission(PermissionCodes.RUNTIME_MANAGE, "需要运行治理权限");
        assertEquals(2, dashboard.tasks().size());
        assertTrue(dashboard.tasks().stream().noneMatch(item -> "TRIAL".equals(item.runMode())));
        FormalTaskMonitorService.FormalTaskView prediction = dashboard.tasks().stream()
            .filter(item -> "MODEL_PREDICT".equals(item.taskType())).findFirst().orElseThrow();
        assertEquals("客户风险模型", prediction.title());
        assertEquals("risk_prediction_result", prediction.outputLocation());
        assertEquals(42L, prediction.recordCount());
        assertEquals(1, dashboard.summary().get("outputArtifacts"));
    }

    private OrchestrationRun run(Long id, String mode, String trigger) {
        OrchestrationRun value = new OrchestrationRun();
        value.setId(id); value.setRunMode(mode); value.setTriggerType(trigger);
        value.setFlowVersionId(100L + id); value.setStatus("SUCCESS");
        return value;
    }

    private ModelExecution execution(Long id, String trigger, String kind) {
        ModelExecution value = new ModelExecution();
        value.setId(id); value.setModelId(5L); value.setTriggerType(trigger);
        value.setExecutionKind(kind); value.setStatus("success");
        return value;
    }

    private PredictionResult prediction(Long id, Long executionId, String trigger) {
        PredictionResult value = new PredictionResult();
        value.setId(id); value.setModelId(5L); value.setModelExecutionId(executionId);
        value.setTriggerType(trigger); value.setPrediction("batch_summary");
        value.setBatchId("batch-" + id); value.setInputData("{\"source\":\"risk_input\",\"total_rows\":42}");
        value.setResultTable("risk_prediction_result");
        return value;
    }
}
