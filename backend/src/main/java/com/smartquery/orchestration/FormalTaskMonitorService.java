package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.PermissionCodes;
import com.smartquery.entity.FlowDefinition;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.ModelExecution;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.entity.OutputArtifact;
import com.smartquery.entity.PredictionResult;
import com.smartquery.entity.ScheduleTask;
import com.smartquery.mapper.FlowDefinitionMapper;
import com.smartquery.mapper.FlowVersionMapper;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.ModelExecutionMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import com.smartquery.mapper.OutputArtifactMapper;
import com.smartquery.mapper.PredictionResultMapper;
import com.smartquery.mapper.ScheduleTaskMapper;
import com.smartquery.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read model for production monitoring. Only durable scheduler executions are eligible:
 * sandbox shaping, previews, manual prediction and TRIAL orchestration runs are excluded.
 */
@Service
@RequiredArgsConstructor
public class FormalTaskMonitorService {
    private static final int LIMIT = 200;

    private final OrchestrationRunMapper runMapper;
    private final OutputArtifactMapper artifactMapper;
    private final ModelExecutionMapper executionMapper;
    private final PredictionResultMapper predictionMapper;
    private final ScheduleTaskMapper scheduleTaskMapper;
    private final MiningModelMapper modelMapper;
    private final FlowVersionMapper flowVersionMapper;
    private final FlowDefinitionMapper flowMapper;
    private final StorageHotDataViewService hotDataViewService;
    private final ResourceAccessService resourceAccess;
    private final ObjectMapper objectMapper;

    public MonitorDashboard dashboard() {
        resourceAccess.requirePermission(PermissionCodes.RUNTIME_MANAGE, "需要运行治理权限");

        List<OrchestrationRun> formalRuns = runMapper.selectList(new LambdaQueryWrapper<OrchestrationRun>()
                .eq(OrchestrationRun::getRunMode, "FORMAL")
                .eq(OrchestrationRun::getTriggerType, "SCHEDULE")
                .orderByDesc(OrchestrationRun::getCreatedAt).last("LIMIT " + LIMIT))
            .stream().filter(this::isFormalScheduledRun).toList();
        Set<Long> runIds = ids(formalRuns, OrchestrationRun::getId);
        List<OutputArtifact> outputs = runIds.isEmpty() ? List.of() : artifactMapper.selectList(
            new LambdaQueryWrapper<OutputArtifact>()
                .in(OutputArtifact::getRunId, runIds)
                .eq(OutputArtifact::getArchiveStatus, StorageGovernanceService.ACTIVE)
                .eq(OutputArtifact::getStatus, "READY")
                .orderByDesc(OutputArtifact::getCreatedAt).last("LIMIT " + LIMIT));

        List<ModelExecution> scheduledExecutions = executionMapper.selectList(
                new LambdaQueryWrapper<ModelExecution>()
                    .eq(ModelExecution::getTriggerType, "schedule")
                    .orderByDesc(ModelExecution::getCreatedAt).last("LIMIT " + LIMIT))
            .stream().filter(value -> equalsIgnoreCase(value.getTriggerType(), "schedule")).toList();
        Set<Long> executionIds = ids(scheduledExecutions, ModelExecution::getId);
        List<PredictionResult> predictions = executionIds.isEmpty() ? List.of() : predictionMapper.selectList(
                new LambdaQueryWrapper<PredictionResult>()
                    .in(PredictionResult::getModelExecutionId, executionIds)
                    .eq(PredictionResult::getTriggerType, "schedule")
                    .eq(PredictionResult::getPrediction, "batch_summary")
                    .orderByDesc(PredictionResult::getPredictedAt).last("LIMIT " + LIMIT))
            .stream().filter(value -> equalsIgnoreCase(value.getTriggerType(), "schedule")
                && value.getModelExecutionId() != null).toList();

        Set<Long> modelIds = ids(scheduledExecutions, ModelExecution::getModelId);
        Map<Long, MiningModel> models = modelIds.isEmpty() ? Map.of()
            : byId(modelMapper.selectBatchIds(modelIds), MiningModel::getId);
        Map<Long, PredictionResult> predictionsByExecution = predictions.stream().collect(Collectors.toMap(
            PredictionResult::getModelExecutionId, Function.identity(), (first, ignored) -> first, LinkedHashMap::new));
        Set<Long> scheduleTaskIds = new LinkedHashSet<>(ids(scheduledExecutions, ModelExecution::getScheduleTaskId));
        scheduleTaskIds.addAll(ids(formalRuns, OrchestrationRun::getScheduleTaskId));
        Map<Long, ScheduleTask> scheduleTasks = scheduleTaskIds.isEmpty() ? Map.of()
            : byId(scheduleTaskMapper.selectBatchIds(scheduleTaskIds), ScheduleTask::getId);

        Set<Long> flowVersionIds = ids(formalRuns, OrchestrationRun::getFlowVersionId);
        Map<Long, FlowVersion> flowVersions = flowVersionIds.isEmpty() ? Map.of()
            : byId(flowVersionMapper.selectBatchIds(flowVersionIds), FlowVersion::getId);
        Set<Long> flowIds = ids(flowVersions.values(), FlowVersion::getFlowId);
        Map<Long, FlowDefinition> flows = flowIds.isEmpty() ? Map.of()
            : byId(flowMapper.selectBatchIds(flowIds), FlowDefinition::getId);
        Map<Long, Long> outputCounts = outputs.stream().collect(Collectors.groupingBy(
            OutputArtifact::getRunId, Collectors.counting()));

        List<FormalTaskView> tasks = new ArrayList<>();
        for (ModelExecution execution : scheduledExecutions) {
            MiningModel model = models.get(execution.getModelId());
            PredictionResult prediction = predictionsByExecution.get(execution.getId());
            ScheduleTask scheduleTask = execution.getScheduleTaskId() == null
                ? null : scheduleTasks.get(execution.getScheduleTaskId());
            String kind = equalsIgnoreCase(execution.getExecutionKind(), "PREDICT") ? "MODEL_PREDICT" : "MODEL_TRAIN";
            Map<String, Object> input = map(prediction == null ? null : prediction.getInputData());
            Long recordCount = longValue(input.get("total_rows"));
            String outputLocation = prediction != null ? prediction.getResultTable() : execution.getArtifactPath();
            tasks.add(new FormalTaskView("MODEL:" + execution.getId(), kind,
                "MODEL_PREDICT".equals(kind) ? "定时预测" : "定时训练",
                execution.getId(), execution.getScheduleTaskId(),
                scheduleTask == null ? null : scheduleTask.getName(), execution.getModelId(), null,
                model == null ? "模型 #" + execution.getModelId() : model.getName(),
                model == null ? null : model.getVersion(), model == null ? null : model.getAlgorithm(),
                model == null ? null : model.getSourceTable(), outputLocation, recordCount,
                outputLocation == null ? 0 : 1, execution.getStatus(), "SCHEDULE", "FORMAL",
                model == null ? null : model.getUserId(), execution.getStartedAt(), execution.getFinishedAt(),
                execution.getCreatedAt(), prediction == null ? null : prediction.getBatchId(),
                execution.getProgressMessage(), execution.getExecutionLog()));
        }
        for (OrchestrationRun run : formalRuns) {
            FlowVersion version = flowVersions.get(run.getFlowVersionId());
            FlowDefinition flow = version == null ? null : flows.get(version.getFlowId());
            int count = outputCounts.getOrDefault(run.getId(), 0L).intValue();
            ScheduleTask scheduleTask = run.getScheduleTaskId() == null ? null : scheduleTasks.get(run.getScheduleTaskId());
            tasks.add(new FormalTaskView("FLOW:" + run.getId(), "FLOW", "流程调度",
                null, run.getScheduleTaskId(), scheduleTask == null ? null : scheduleTask.getName(), null, run.getId(), flow == null ? "流程版本 #" + run.getFlowVersionId() : flow.getName(),
                version == null ? null : version.getVersionNo(), null, "调度输入快照",
                count + " 个持久化输出", null, count, run.getStatus(), run.getTriggerType(), run.getRunMode(),
                run.getOwnerUserId(), run.getStartedAt(), run.getFinishedAt(), run.getCreatedAt(), null,
                run.getOutputSummary(), run.getErrorMessage()));
        }
        tasks.sort(Comparator.comparing(FormalTaskView::observedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));

        long success = tasks.stream().filter(value -> isSuccess(value.status())).count();
        long failed = tasks.stream().filter(value -> isFailed(value.status())).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("formalTasks", tasks.size());
        summary.put("successTasks", success);
        summary.put("failedTasks", failed);
        summary.put("scheduledTraining", tasks.stream().filter(value -> "MODEL_TRAIN".equals(value.taskType())).count());
        summary.put("scheduledPrediction", tasks.stream().filter(value -> "MODEL_PREDICT".equals(value.taskType())).count());
        summary.put("formalFlowRuns", formalRuns.size());
        summary.put("outputArtifacts", outputs.size());
        summary.put("excludedData", List.of("TRIAL", "SANDBOX_SHAPING", "PREVIEW", "MANUAL_PREDICTION"));
        return new MonitorDashboard(summary, List.copyOf(tasks), hotDataViewService.views(outputs));
    }

    private boolean isFormalScheduledRun(OrchestrationRun run) {
        return run != null && equalsIgnoreCase(run.getRunMode(), "FORMAL")
            && equalsIgnoreCase(run.getTriggerType(), "SCHEDULE");
    }

    private boolean isSuccess(String status) {
        return equalsIgnoreCase(status, "success") || equalsIgnoreCase(status, "completed");
    }

    private boolean isFailed(String status) {
        return equalsIgnoreCase(status, "failed") || equalsIgnoreCase(status, "timed_out")
            || equalsIgnoreCase(status, "canceled");
    }

    private boolean equalsIgnoreCase(String value, String expected) {
        return value != null && value.equalsIgnoreCase(expected);
    }

    private Map<String, Object> map(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try { return objectMapper.readValue(raw, new TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of(); }
    }

    private Long longValue(Object raw) {
        if (raw == null) return null;
        try { return Long.parseLong(String.valueOf(raw)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private <T> Set<Long> ids(Collection<T> values, Function<T, Long> getter) {
        if (values == null || values.isEmpty()) return Set.of();
        return values.stream().filter(Objects::nonNull).map(getter).filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private <T> Map<Long, T> byId(List<T> values, Function<T, Long> getter) {
        if (values == null || values.isEmpty()) return Map.of();
        return values.stream().filter(Objects::nonNull).collect(Collectors.toMap(getter,
            Function.identity(), (first, ignored) -> first, LinkedHashMap::new));
    }

    public record MonitorDashboard(Map<String, Object> summary, List<FormalTaskView> tasks,
                                   List<StorageHotDataViewService.HotOutputView> outputs) {}

    public record FormalTaskView(String key, String taskType, String taskTypeLabel,
                                 Long executionId, Long scheduleTaskId, String scheduleTaskName,
                                 Long modelId, Long flowRunId, String title,
                                 Integer version, String algorithm, String inputSource,
                                 String outputLocation, Long recordCount, Integer outputCount,
                                 String status, String triggerType, String runMode, String ownerUserId,
                                 LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime createdAt,
                                 String batchId, String message, String errorMessage) {
        public LocalDateTime observedAt() {
            return finishedAt != null ? finishedAt : startedAt != null ? startedAt : createdAt;
        }
    }
}
