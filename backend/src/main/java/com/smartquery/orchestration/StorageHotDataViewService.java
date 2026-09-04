package com.smartquery.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.PermissionCodes;
import com.smartquery.entity.FlowDefinition;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.NodeRun;
import com.smartquery.entity.OperatorDefinition;
import com.smartquery.entity.OperatorVersion;
import com.smartquery.entity.OrchestrationRun;
import com.smartquery.entity.OutputArtifact;
import com.smartquery.entity.ScheduleTask;
import com.smartquery.entity.User;
import com.smartquery.mapper.FlowDefinitionMapper;
import com.smartquery.mapper.FlowVersionMapper;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.NodeRunMapper;
import com.smartquery.mapper.OperatorDefinitionMapper;
import com.smartquery.mapper.OperatorVersionMapper;
import com.smartquery.mapper.OrchestrationRunMapper;
import com.smartquery.mapper.UserMapper;
import com.smartquery.mapper.ScheduleTaskMapper;
import com.smartquery.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds a permission-aware, human-readable view of hot output artifacts and their execution lineage. */
@Service
@RequiredArgsConstructor
public class StorageHotDataViewService {
    private final OrchestrationRunMapper runMapper;
    private final NodeRunMapper nodeRunMapper;
    private final FlowVersionMapper flowVersionMapper;
    private final FlowDefinitionMapper flowDefinitionMapper;
    private final OperatorVersionMapper operatorVersionMapper;
    private final OperatorDefinitionMapper operatorDefinitionMapper;
    private final MiningModelMapper miningModelMapper;
    private final UserMapper userMapper;
    private final ScheduleTaskMapper scheduleTaskMapper;
    private final ObjectMapper objectMapper;
    private final ResourceAccessService resourceAccess;

    public List<HotOutputView> views(List<OutputArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return List.of();

        Set<Long> runIds = ids(artifacts, OutputArtifact::getRunId);
        Map<Long, OrchestrationRun> runs = runIds.isEmpty() ? Map.of()
            : byId(runMapper.selectBatchIds(runIds), OrchestrationRun::getId);
        Set<Long> scheduleTaskIds = ids(runs.values(), OrchestrationRun::getScheduleTaskId);
        Map<Long, ScheduleTask> scheduleTasks = scheduleTaskIds.isEmpty() ? Map.of()
            : byId(scheduleTaskMapper.selectBatchIds(scheduleTaskIds), ScheduleTask::getId);
        List<NodeRun> nodeRuns = runs.isEmpty() ? List.of() : nodeRunMapper.selectList(
            new LambdaQueryWrapper<NodeRun>().in(NodeRun::getRunId, runs.keySet())
                .orderByAsc(NodeRun::getRunId).orderByAsc(NodeRun::getId));
        Map<Long, List<NodeRun>> nodesByRun = nodeRuns.stream().collect(Collectors.groupingBy(
            NodeRun::getRunId, LinkedHashMap::new, Collectors.toList()));

        Set<Long> flowVersionIds = ids(runs.values(), OrchestrationRun::getFlowVersionId);
        Map<Long, FlowVersion> flowVersions = flowVersionIds.isEmpty() ? Map.of()
            : byId(flowVersionMapper.selectBatchIds(flowVersionIds), FlowVersion::getId);
        Set<Long> flowIds = ids(flowVersions.values(), FlowVersion::getFlowId);
        Map<Long, FlowDefinition> flows = flowIds.isEmpty() ? Map.of()
            : byId(flowDefinitionMapper.selectBatchIds(flowIds), FlowDefinition::getId);
        Set<Long> operatorVersionIds = ids(nodeRuns, NodeRun::getOperatorVersionId);
        Map<Long, OperatorVersion> operatorVersions = operatorVersionIds.isEmpty() ? Map.of()
            : byId(operatorVersionMapper.selectBatchIds(operatorVersionIds), OperatorVersion::getId);
        Set<Long> operatorIds = ids(operatorVersions.values(), OperatorVersion::getOperatorId);
        Map<Long, OperatorDefinition> operators = operatorIds.isEmpty() ? Map.of()
            : byId(operatorDefinitionMapper.selectBatchIds(operatorIds), OperatorDefinition::getId);

        Set<Long> modelIds = new LinkedHashSet<>();
        for (OperatorVersion version : operatorVersions.values()) {
            OperatorDefinition definition = operators.get(version.getOperatorId());
            if (definition != null && OperatorTypes.ML.equals(definition.getOperatorType())) {
                Long modelId = longValue(map(version.getImplementationPayload()).get("modelId"));
                if (modelId != null) modelIds.add(modelId);
            }
        }
        Map<Long, MiningModel> models = modelIds.isEmpty() ? Map.of()
            : byId(miningModelMapper.selectBatchIds(modelIds), MiningModel::getId);
        Set<Long> ownerIds = userIds(artifacts);
        Map<Long, User> users = ownerIds.isEmpty() ? Map.of()
            : byId(userMapper.selectBatchIds(ownerIds), User::getId);

        boolean globalRead = resourceAccess.isAdmin();
        boolean runtimeManage = resourceAccess.hasPermission(PermissionCodes.RUNTIME_MANAGE);
        String currentUserId = resourceAccess.currentUserId();
        List<HotOutputView> result = new ArrayList<>();
        for (OutputArtifact artifact : artifacts) {
            OrchestrationRun run = runs.get(artifact.getRunId());
            ScheduleTask scheduleTask = run == null || run.getScheduleTaskId() == null
                ? null : scheduleTasks.get(run.getScheduleTaskId());
            FlowVersion flowVersion = run == null ? null : flowVersions.get(run.getFlowVersionId());
            FlowDefinition flow = flowVersion == null ? null : flows.get(flowVersion.getFlowId());
            User owner = users.get(longValue(artifact.getOwnerUserId()));
            Map<String, Object> spec = map(artifact.getContentSpec());
            Map<String, Object> summary = map(artifact.getArtifactData());
            String targetId = text(summary.get("targetId"));
            String capabilityCode = text(summary.get("capabilityCode"));
            String kindLabel = kindLabel(artifact.getOutputKind());
            String title = first(text(spec.get("title")), text(spec.get("sheetName")),
                text(spec.get("fileName")), targetId == null ? null : kindLabel + " · " + targetId,
                kindLabel + " #" + artifact.getId());
            boolean ownerMatched = Objects.equals(currentUserId, artifact.getOwnerUserId());
            boolean canView = ownerMatched || globalRead;
            String permissionBasis = ownerMatched ? "OWNER" : globalRead ? "RESOURCE_ACCESS_ALL" : "METADATA_ONLY";
            List<ExecutionStepView> steps = executionSteps(nodesByRun.getOrDefault(artifact.getRunId(), List.of()),
                operatorVersions, operators, models);
            List<ModelReference> modelReferences = steps.stream().map(ExecutionStepView::model)
                .filter(Objects::nonNull).distinct().toList();
            result.add(new HotOutputView(artifact.getId(), artifact.getRunId(), artifact.getNodeRunId(),
                artifact.getOutputKind(), kindLabel, title, targetId, capabilityCode,
                number(summary.get("recordCount")), artifact.getStatus(), artifact.getArchiveStatus(),
                artifact.getPayloadBytes(), artifact.getRetentionUntil(), artifact.getCreatedAt(),
                artifact.getOwnerUserId(), owner == null ? null : owner.getUsername(),
                owner == null ? null : owner.getDisplayName(), owner == null ? null : owner.getRole(),
                permissionBasis, canView, runtimeManage,
                "所有者可查看；跨用户查看需要 " + PermissionCodes.RESOURCE_ACCESS_ALL
                    + "；归档需要 " + PermissionCodes.RUNTIME_MANAGE,
                run == null ? null : run.getStatus(), flow == null ? null : flow.getId(),
                flow == null ? null : flow.getCode(), flow == null ? null : flow.getName(),
                flowVersion == null ? null : flowVersion.getId(),
                flowVersion == null ? null : flowVersion.getVersionNo(),
                run == null ? null : run.getScheduleTaskId(), scheduleTask == null ? null : scheduleTask.getName(),
                steps, executionEdges(flowVersion), modelReferences));
        }
        return List.copyOf(result);
    }

    private List<ExecutionEdgeView> executionEdges(FlowVersion flowVersion) {
        if (flowVersion == null) return List.of();
        Object raw = parse(flowVersion.getEdges());
        if (!(raw instanceof List<?> list)) return List.of();
        List<ExecutionEdgeView> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> edge)) continue;
            String source = text(edge.get("source"));
            String target = text(edge.get("target"));
            if (source != null && target != null) {
                result.add(new ExecutionEdgeView(source, target, first(text(edge.get("mappingMode")), "MERGE")));
            }
        }
        return List.copyOf(result);
    }

    private Object parse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try { return objectMapper.readValue(raw, Object.class); }
        catch (Exception ignored) { return List.of(); }
    }

    private List<ExecutionStepView> executionSteps(List<NodeRun> nodeRuns,
                                                   Map<Long, OperatorVersion> versions,
                                                   Map<Long, OperatorDefinition> operators,
                                                   Map<Long, MiningModel> models) {
        List<ExecutionStepView> result = new ArrayList<>();
        for (NodeRun node : nodeRuns) {
            OperatorVersion version = versions.get(node.getOperatorVersionId());
            OperatorDefinition operator = version == null ? null : operators.get(version.getOperatorId());
            ModelReference model = null;
            if (operator != null && OperatorTypes.ML.equals(operator.getOperatorType())) {
                Long modelId = longValue(map(version.getImplementationPayload()).get("modelId"));
                MiningModel value = models.get(modelId);
                model = value == null ? new ModelReference(modelId, "模型 #" + modelId, null, null,
                    "UNKNOWN", null) : new ModelReference(value.getId(), value.getName(), value.getAlgorithm(),
                    value.getAlgorithmVersion(), value.getStatus(), value.getArtifactSha256());
            }
            result.add(new ExecutionStepView(node.getId(), node.getNodeId(), node.getStatus(),
                node.getExecutionTimeMs(), node.getOperatorVersionId(),
                version == null ? null : version.getVersionNo(),
                version == null ? null : version.getStatus(),
                version == null ? null : version.getImplementationType(),
                operator == null ? null : operator.getId(), operator == null ? null : operator.getCode(),
                operator == null ? "未知算子" : operator.getName(),
                operator == null ? "UNKNOWN" : operator.getOperatorType(), model));
        }
        return List.copyOf(result);
    }

    private Set<Long> userIds(List<OutputArtifact> artifacts) {
        Set<Long> result = new LinkedHashSet<>();
        artifacts.stream().map(OutputArtifact::getOwnerUserId).map(this::longValue)
            .filter(Objects::nonNull).forEach(result::add);
        return result;
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

    private Long number(Object raw) { return longValue(raw); }
    private String text(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? null : value;
    }
    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "未命名输出";
    }
    private String kindLabel(String kind) {
        return switch (kind == null ? "" : kind.toUpperCase(Locale.ROOT)) {
            case "LEAD" -> "业务线索";
            case "CHART" -> "分析图表";
            case "TABLE" -> "结果表";
            case "EXCEL" -> "Excel 表格视图";
            case "DASHBOARD" -> "组合页面";
            case "ARTIFACT" -> "平台运行制品";
            case "TEMP_RESULT" -> "临时结果";
            case "EXPORT_XLSX" -> "XLSX 导出";
            case "EXPORT_CSV" -> "CSV 导出";
            case "EXPORT_PDF" -> "PDF 导出";
            case "EXPORT_JSON" -> "JSON 导出";
            case "EXPORT_PNG" -> "PNG 导出";
            default -> kind == null ? "未知输出" : kind;
        };
    }

    public record HotOutputView(Long id, Long runId, Long nodeRunId, String outputKind,
                                String kindLabel, String title, String targetId, String capabilityCode,
                                Long recordCount, String status, String archiveStatus, Long payloadBytes,
                                LocalDateTime retentionUntil, LocalDateTime createdAt,
                                String ownerUserId, String ownerUsername, String ownerDisplayName,
                                String ownerRole, String permissionBasis, boolean canViewDetails,
                                boolean canArchive, String permissionDescription, String runStatus,
                                Long flowId, String flowCode, String flowName, Long flowVersionId,
                                Integer flowVersionNo, Long scheduleTaskId, String scheduleTaskName,
                                List<ExecutionStepView> executionPath, List<ExecutionEdgeView> executionEdges,
                                List<ModelReference> models) {}

    public record ExecutionEdgeView(String source, String target, String mappingMode) {}

    public record ExecutionStepView(Long nodeRunId, String nodeId, String status, Long executionTimeMs,
                                    Long operatorVersionId, Integer operatorVersionNo,
                                    String operatorVersionStatus, String implementationType,
                                    Long operatorId, String operatorCode, String operatorName,
                                    String operatorType, ModelReference model) {}

    public record ModelReference(Long id, String name, String algorithm, Integer algorithmVersion,
                                 String status, String artifactSha256) {}
}
