package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.IdentifierValidator;
import com.smartquery.common.ModelStatus;
import com.smartquery.common.PermissionCodes;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.FlowDefinition;
import com.smartquery.entity.FlowVersion;
import com.smartquery.entity.ScheduleTask;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.ScheduleTaskMapper;
import com.smartquery.orchestration.VersionCatalogService;
import com.smartquery.orchestration.VersionStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** CRUD and authorization boundary for durable production schedule definitions. */
@Service
@RequiredArgsConstructor
public class ScheduleTaskService {
    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";

    private final ScheduleTaskMapper taskMapper;
    private final MiningModelMapper modelMapper;
    private final ResourceAccessService resourceAccess;
    private final RoleService roleService;
    private final VersionCatalogService versionCatalogService;
    private final ObjectMapper objectMapper;

    public List<ScheduleTaskView> list() {
        requireView();
        LambdaQueryWrapper<ScheduleTask> query = new LambdaQueryWrapper<ScheduleTask>()
            .eq(ScheduleTask::getDeleted, 0)
            .orderByDesc(ScheduleTask::getCreatedAt);
        if (!resourceAccess.isAdmin()) query.eq(ScheduleTask::getOwnerUserId, resourceAccess.currentUserId());
        List<ScheduleTask> tasks = taskMapper.selectList(query);
        Map<Long, MiningModel> models = new LinkedHashMap<>();
        Map<Long, FlowVersion> flowVersions = new LinkedHashMap<>();
        Map<Long, FlowDefinition> flows = new LinkedHashMap<>();
        for (ScheduleTask task : tasks) {
            if (task.getModelId() != null && !models.containsKey(task.getModelId())) {
                models.put(task.getModelId(), modelMapper.selectById(task.getModelId()));
            }
            if (task.getFlowVersionId() != null && !flowVersions.containsKey(task.getFlowVersionId())) {
                FlowVersion version = versionCatalogService.requireFlowVersion(task.getFlowVersionId());
                flowVersions.put(version.getId(), version);
                flows.putIfAbsent(version.getFlowId(), versionCatalogService.requireFlow(version.getFlowId()));
            }
        }
        return tasks.stream().map(task -> {
            FlowVersion version = flowVersions.get(task.getFlowVersionId());
            return view(task, models.get(task.getModelId()),
                version == null ? null : flows.get(version.getFlowId()), version);
        }).toList();
    }

    @Transactional
    public ScheduleTaskView create(ScheduleTaskCommand command) {
        requireManage();
        ScheduleTask task = new ScheduleTask();
        String taskType = normalizeTaskType(command.taskType(), command.flowVersionId());
        MiningModel model = null;
        FlowVersion flowVersion = null;
        FlowDefinition flow = null;
        if ("FLOW".equals(taskType)) {
            flowVersion = requireSchedulableFlow(command.flowVersionId());
            flow = versionCatalogService.requireFlow(flowVersion.getFlowId());
            task.setName(normalizeFlowName(command.name(), flow));
            task.setFlowVersionId(flowVersion.getId());
            task.setOwnerUserId(flow.getOwnerUserId());
        } else {
            model = requireSchedulableModel(command.modelId(), command.scheduleMode());
            task.setName(normalizeName(command.name(), model, command.scheduleMode()));
            task.setModelId(model.getId());
            task.setOwnerUserId(model.getUserId());
        }
        task.setTaskType(taskType);
        task.setDeleted(0);
        apply(task, command);
        taskMapper.insert(task);
        return view(task, model, flow, flowVersion);
    }

    @Transactional
    public ScheduleTaskView update(Long id, ScheduleTaskCommand command) {
        requireManage();
        ScheduleTask task = requireOwned(id);
        if ("FLOW".equals(task.getTaskType())) {
            Long versionId = command.flowVersionId() == null ? task.getFlowVersionId() : command.flowVersionId();
            FlowVersion version = requireSchedulableFlow(versionId);
            FlowDefinition flow = versionCatalogService.requireFlow(version.getFlowId());
            task.setFlowVersionId(version.getId());
            task.setModelId(null);
            task.setOwnerUserId(flow.getOwnerUserId());
            task.setName(normalizeFlowName(command.name(), flow));
            apply(task, command);
            taskMapper.updateById(task);
            return view(task, null, flow, version);
        }
        Long modelId = command.modelId() == null ? task.getModelId() : command.modelId();
        String mode = blankToNull(command.scheduleMode()) == null ? task.getScheduleMode() : command.scheduleMode();
        MiningModel model = requireSchedulableModel(modelId, mode);
        task.setModelId(model.getId());
        task.setOwnerUserId(model.getUserId());
        task.setName(normalizeName(command.name(), model, mode));
        apply(task, new ScheduleTaskCommand(task.getName(), "MODEL", modelId, null, mode,
            command.cronExpression(), command.inputTable(), command.inputFilter(),
            command.outputTable(), null, command.status()));
        taskMapper.updateById(task);
        return view(task, model, null, null);
    }

    @Transactional
    public ScheduleTaskView changeStatus(Long id, String requestedStatus) {
        requireManage();
        ScheduleTask task = requireOwned(id);
        String status = normalizeStatus(requestedStatus);
        if (ACTIVE.equals(status)) {
            if ("FLOW".equals(task.getTaskType())) requireSchedulableFlow(task.getFlowVersionId());
            else requireSchedulableModel(task.getModelId(), task.getScheduleMode());
        }
        task.setStatus(status);
        task.setNextRunAt(ACTIVE.equals(status) ? next(task.getCronExpression(), LocalDateTime.now()) : null);
        taskMapper.updateById(task);
        if ("FLOW".equals(task.getTaskType())) {
            FlowVersion version = versionCatalogService.requireFlowVersion(task.getFlowVersionId());
            return view(task, null, versionCatalogService.requireFlow(version.getFlowId()), version);
        }
        return view(task, modelMapper.selectById(task.getModelId()), null, null);
    }

    @Transactional
    public void delete(Long id) {
        requireManage();
        ScheduleTask task = requireOwned(id);
        task.setStatus(PAUSED);
        task.setNextRunAt(null);
        task.setDeleted(1);
        taskMapper.updateById(task);
    }

    /** Compatibility bridge for older clients that still call the per-model schedule endpoint. */
    @Transactional
    public ScheduleTaskView upsertLegacyModelSchedule(Long modelId, String cron, Boolean enabled, String mode) {
        requireManage();
        MiningModel model = resourceAccess.requireModel(modelId);
        String normalizedMode = normalizeMode(mode == null ? model.getScheduleMode() : mode);
        ScheduleTask existing = taskMapper.selectOne(new LambdaQueryWrapper<ScheduleTask>()
            .eq(ScheduleTask::getModelId, modelId)
            .eq(ScheduleTask::getScheduleMode, normalizedMode)
            .eq(ScheduleTask::getDeleted, 0)
            .last("LIMIT 1"));
        ScheduleTaskCommand command = new ScheduleTaskCommand(
            existing == null ? null : existing.getName(), modelId, normalizedMode,
            cron == null ? model.getScheduleCron() : cron,
            model.getPredictInputTable(), model.getPredictInputFilter(), model.getPredictResultTable(),
            Boolean.TRUE.equals(enabled == null ? model.getScheduleEnabled() : enabled) ? ACTIVE : PAUSED);
        return existing == null ? create(command) : update(existing.getId(), command);
    }

    public ScheduleTask requireOwned(Long id) {
        ScheduleTask task = id == null ? null : taskMapper.selectById(id);
        if (task == null || Integer.valueOf(1).equals(task.getDeleted())) {
            throw new BusinessException(404, "调度任务不存在: " + id);
        }
        if ("FLOW".equals(task.getTaskType())) versionCatalogService.requireFlowVersion(task.getFlowVersionId());
        else resourceAccess.requireModel(task.getModelId());
        return task;
    }

    public ScheduleTask requireRunnable(Long id) {
        requireManage();
        ScheduleTask task = requireOwned(id);
        if ("FLOW".equals(task.getTaskType())) requireSchedulableFlow(task.getFlowVersionId());
        else requireSchedulableModel(task.getModelId(), task.getScheduleMode());
        return task;
    }

    public static LocalDateTime next(String fiveFieldCron, LocalDateTime from) {
        try {
            LocalDateTime value = CronExpression.parse(toSpringCron(fiveFieldCron)).next(from);
            if (value == null) throw new IllegalArgumentException("无法计算下一次执行时间");
            return value;
        } catch (RuntimeException error) {
            throw new BusinessException("Cron 表达式无效: " + fiveFieldCron);
        }
    }

    private void apply(ScheduleTask task, ScheduleTaskCommand command) {
        boolean flowTask = "FLOW".equals(task.getTaskType());
        String mode = flowTask ? "FLOW" : normalizeMode(command.scheduleMode());
        String cron = requireText(command.cronExpression(), "调度周期不能为空");
        next(cron, LocalDateTime.now());
        String input = blankToNull(command.inputTable());
        String output = blankToNull(command.outputTable());
        String filter = blankToNull(command.inputFilter());
        if ("PREDICT".equals(mode) && input == null) throw new BusinessException("定期预测必须选择输入表");
        if (input != null) IdentifierValidator.validateTableName(input);
        if (output != null) IdentifierValidator.validateTableName(output);
        if (filter != null) IdentifierValidator.validateFilter(filter);
        String status = normalizeStatus(command.status());
        task.setScheduleMode(mode);
        task.setCronExpression(cron);
        task.setInputTable(input);
        task.setInputFilter(filter);
        task.setOutputTable(output);
        task.setInputPayload(flowTask ? normalizePayload(command.inputPayload()) : null);
        task.setStatus(status);
        task.setNextRunAt(ACTIVE.equals(status) ? next(cron, LocalDateTime.now()) : null);
    }

    private FlowVersion requireSchedulableFlow(Long flowVersionId) {
        FlowVersion version = versionCatalogService.requireFlowVersion(flowVersionId);
        if (!VersionStatus.PUBLISHED.equals(version.getStatus())) {
            throw new BusinessException("完整流程调度只能选择审批通过的已发布模型版本");
        }
        return version;
    }

    private MiningModel requireSchedulableModel(Long modelId, String mode) {
        MiningModel model = resourceAccess.requireModel(modelId);
        String normalizedMode = normalizeMode(mode);
        if ("PREDICT".equals(normalizedMode) && !ModelStatus.PUBLISHED.equals(model.getStatus())) {
            throw new BusinessException("定期预测只能选择已发布模型");
        }
        if ("TRAIN".equals(normalizedMode)
                && !ModelStatus.PUBLISHED.equals(model.getStatus())
                && !ModelStatus.TRAINED.equals(model.getStatus())) {
            throw new BusinessException("定期重训只能选择已训练或已发布模型");
        }
        return model;
    }

    private ScheduleTaskView view(ScheduleTask task, MiningModel model,
                                  FlowDefinition flow, FlowVersion flowVersion) {
        return new ScheduleTaskView(task.getId(), task.getName(), task.getTaskType(), task.getModelId(),
            model == null ? "模型 #" + task.getModelId() : model.getName(),
            model == null ? null : model.getVersion(), model == null ? null : model.getStatus(),
            model == null ? null : model.getAlgorithm(), task.getFlowVersionId(),
            flow == null ? null : flow.getName(), flowVersion == null ? null : flowVersion.getVersionNo(),
            task.getScheduleMode(), task.getCronExpression(),
            task.getInputTable(), task.getInputFilter(), task.getOutputTable(), task.getStatus(),
            task.getInputPayload(),
            task.getOwnerUserId(), task.getLastRunAt(), task.getNextRunAt(), task.getLastStatus(),
            task.getLastError(), task.getCreatedAt(), task.getUpdatedAt());
    }

    private void requireView() {
        roleService.requireCurrentUserAny("无权限查看调度任务",
            PermissionCodes.RUNTIME_MANAGE, PermissionCodes.MONITOR_VIEW);
    }

    private void requireManage() {
        roleService.requireCurrentUser(PermissionCodes.RUNTIME_MANAGE, "需要运行治理权限才能管理调度任务");
    }

    private static String normalizeMode(String value) {
        String mode = requireText(value, "调度模式不能为空").toUpperCase(Locale.ROOT);
        if (!List.of("TRAIN", "PREDICT").contains(mode)) throw new BusinessException("不支持的调度模式: " + value);
        return mode;
    }

    private static String normalizeTaskType(String value, Long flowVersionId) {
        String type = blankToNull(value);
        if (type == null) type = flowVersionId == null ? "MODEL" : "FLOW";
        type = type.toUpperCase(Locale.ROOT);
        if (!List.of("MODEL", "FLOW").contains(type)) throw new BusinessException("不支持的调度目标: " + value);
        return type;
    }

    private String normalizePayload(String value) {
        String payload = blankToNull(value);
        if (payload == null) return "{\"records\":[]}";
        try {
            Map<String, Object> parsed = objectMapper.readValue(payload, new TypeReference<>() {});
            return objectMapper.writeValueAsString(parsed);
        } catch (Exception error) {
            throw new BusinessException("流程输入必须是 JSON 对象");
        }
    }

    private static String normalizeStatus(String value) {
        String status = blankToNull(value) == null ? ACTIVE : value.toUpperCase(Locale.ROOT);
        if (!List.of(ACTIVE, PAUSED).contains(status)) throw new BusinessException("不支持的调度状态: " + value);
        return status;
    }

    private static String normalizeName(String value, MiningModel model, String mode) {
        String name = blankToNull(value);
        if (name != null) return name.length() > 200 ? name.substring(0, 200) : name;
        return model.getName() + ("TRAIN".equalsIgnoreCase(mode) ? "-定期重训" : "-定期预测");
    }

    private static String normalizeFlowName(String value, FlowDefinition flow) {
        String name = blankToNull(value);
        if (name != null) return name.length() > 200 ? name.substring(0, 200) : name;
        return flow.getName() + "-正式流程调度";
    }

    private static String requireText(String value, String message) {
        String normalized = blankToNull(value);
        if (normalized == null) throw new BusinessException(message);
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String toSpringCron(String cron) {
        String normalized = requireText(cron, "调度周期不能为空");
        String[] parts = normalized.split("\\s+");
        if (parts.length != 5) throw new BusinessException("Cron 必须使用 5 段格式");
        return "0 " + normalized;
    }

    public record ScheduleTaskCommand(String name, String taskType, Long modelId, Long flowVersionId,
                                      String scheduleMode, String cronExpression, String inputTable,
                                      String inputFilter, String outputTable, String inputPayload,
                                      String status) {
        public ScheduleTaskCommand(String name, Long modelId, String scheduleMode,
                                   String cronExpression, String inputTable, String inputFilter,
                                   String outputTable, String status) {
            this(name, "MODEL", modelId, null, scheduleMode, cronExpression, inputTable,
                inputFilter, outputTable, null, status);
        }
    }

    public record ScheduleTaskView(Long id, String name, String taskType, Long modelId,
                                   String modelName, Integer modelVersion, String modelStatus,
                                   String algorithm, Long flowVersionId, String flowName,
                                   Integer flowVersionNo, String scheduleMode, String cronExpression,
                                   String inputTable, String inputFilter, String outputTable,
                                   String status, String inputPayload, String ownerUserId, LocalDateTime lastRunAt,
                                   LocalDateTime nextRunAt, String lastStatus, String lastError,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
