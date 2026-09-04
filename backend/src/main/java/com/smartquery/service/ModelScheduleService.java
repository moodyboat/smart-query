package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.common.ModelStatus;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.User;
import com.smartquery.engine.ConversationContextHolder;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.ScheduleTask;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.ScheduleTaskMapper;
import com.smartquery.mapper.UserMapper;
import com.smartquery.orchestration.OrchestrationRunService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes persistent schedule definitions; run records are written by MiningService. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelScheduleService {

    @org.springframework.beans.factory.annotation.Value("${mining.schedule.fallback-minutes:1440}")
    private int fallbackMinutes;

    private final ScheduleTaskMapper scheduleTaskMapper;
    private final MiningModelMapper miningModelMapper;
    private final MiningService miningService;
    private final ConversationEventLogger eventLogger;
    private final ConversationContextHolder.SessionManager sessionManager;

    /** A model cannot train and predict concurrently even with multiple schedule definitions. */
    private final ConcurrentHashMap<String, AtomicBoolean> runningTargets = new ConcurrentHashMap<>();
    private final OrchestrationRunService orchestrationRunService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final ScheduleTaskService scheduleTaskService;

    public ScheduleRunResult runNow(Long taskId) {
        ScheduleTask task = scheduleTaskService.requireRunnable(taskId);
        return executeWithLock(task, LocalDateTime.now(), false);
    }

    @Scheduled(fixedRateString = "${mining.schedule.poll-interval-ms:60000}")
    public void checkScheduledModels() {
        List<ScheduleTask> tasks = scheduleTaskMapper.selectList(
            new LambdaQueryWrapper<ScheduleTask>()
                .eq(ScheduleTask::getDeleted, 0)
                .eq(ScheduleTask::getStatus, ScheduleTaskService.ACTIVE)
                .isNotNull(ScheduleTask::getCronExpression)
                .orderByAsc(ScheduleTask::getNextRunAt));

        LocalDateTime now = LocalDateTime.now();
        for (ScheduleTask task : tasks) {
            if (!shouldRun(task, now)) continue;
            try {
                executeWithLock(task, now, true);
            } catch (com.smartquery.common.BusinessException busy) {
                log.warn("[SCHEDULE] Task {} skipped: {}", task.getId(), busy.getMessage());
            }
        }
        sessionManager.cleanupStaleSessions();
    }

    private ScheduleRunResult executeWithLock(ScheduleTask task, LocalDateTime startedAt,
                                              boolean advanceSchedule) {
        String targetKey = "FLOW".equalsIgnoreCase(task.getTaskType())
            ? "FLOW:" + task.getFlowVersionId() : "MODEL:" + task.getModelId();
        AtomicBoolean lock = runningTargets.computeIfAbsent(targetKey, ignored -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            throw new com.smartquery.common.BusinessException("该调度目标正在执行，请稍后再试");
        }
        try {
            return runTask(task, startedAt, advanceSchedule);
        } finally {
            lock.set(false);
        }
    }

    private ScheduleRunResult runTask(ScheduleTask task, LocalDateTime startedAt,
                                      boolean advanceSchedule) {
        MiningModel model = task.getModelId() == null ? null : miningModelMapper.selectById(task.getModelId());
        boolean success = false;
        String errorMessage = null;
        task.setLastStatus("RUNNING");
        task.setLastError(null);
        scheduleTaskMapper.updateById(task);
        try {
            User owner = userMapper.selectById(Long.parseLong(task.getOwnerUserId()));
            if (owner == null || Integer.valueOf(1).equals(owner.getDeleted()) || Integer.valueOf(0).equals(owner.getEnabled())) {
                throw new IllegalStateException("调度任务所有者不存在或已停用");
            }
            try (UserContextHolder.Scope ignored = UserContextHolder.open(
                    new UserContextHolder.UserContext(owner.getId(), owner.getUsername(), owner.getRole()))) {
                if ("FLOW".equalsIgnoreCase(task.getTaskType())) {
                    orchestrationRunService.submitScheduled(task.getFlowVersionId(), flowInput(task), task.getId());
                } else {
                    if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
                        throw new IllegalStateException("调度目标模型不存在");
                    }
                    if ("PREDICT".equalsIgnoreCase(task.getScheduleMode())) {
                        if (!ModelStatus.PUBLISHED.equals(model.getStatus())) {
                            throw new IllegalStateException("定期预测只允许已发布模型");
                        }
                        miningService.batchPredictForScheduleTask(model.getId(), task.getId(),
                            task.getInputTable(), task.getOutputTable(), task.getInputFilter());
                    } else {
                        if (!ModelStatus.PUBLISHED.equals(model.getStatus())
                                && !ModelStatus.TRAINED.equals(model.getStatus())) {
                            throw new IllegalStateException("定期重训只允许已训练或已发布模型");
                        }
                        miningService.trainModelForScheduleTask(model.getId(), task.getInputFilter(), task.getId());
                    }
                }
            }
            success = true;
        } catch (Exception error) {
            errorMessage = error.getMessage();
            log.error("[SCHEDULE] Task {} failed for model {}: {}", task.getId(), task.getModelId(), errorMessage);
        }

        LocalDateTime nextRun;
        try {
            nextRun = ScheduleTaskService.next(task.getCronExpression(), startedAt);
        } catch (Exception ignored) {
            nextRun = startedAt.plusMinutes(fallbackMinutes);
        }
        ScheduleTask fresh = scheduleTaskMapper.selectById(task.getId());
        if (fresh != null && !Integer.valueOf(1).equals(fresh.getDeleted())) {
            fresh.setLastRunAt(startedAt);
            fresh.setLastStatus(success ? "SUCCESS" : "FAILED");
            fresh.setLastError(errorMessage);
            if (advanceSchedule) {
                fresh.setNextRunAt(ScheduleTaskService.ACTIVE.equals(fresh.getStatus()) ? nextRun : null);
            }
            scheduleTaskMapper.updateById(fresh);
        }
        if (model != null && advanceSchedule) {
            model.setLastRunAt(startedAt);
            model.setNextRunAt(nextRun);
            miningModelMapper.updateById(model);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scheduleTaskId", task.getId());
        payload.put("scheduleTaskName", task.getName());
        payload.put("modelId", task.getModelId());
        payload.put("flowVersionId", task.getFlowVersionId());
        payload.put("modelName", model == null ? "未知模型" : model.getName());
        payload.put("mode", task.getScheduleMode());
        payload.put("success", success);
        if (errorMessage != null) payload.put("error", errorMessage);
        payload.put("nextRunAt", nextRun.toString());
        eventLogger.logEvent(null, null,
            success ? "schedule_execution_success" : "schedule_execution_failed", payload);
        return new ScheduleRunResult(task.getId(), success, errorMessage, startedAt,
            fresh == null ? null : fresh.getNextRunAt());
    }

    private Map<String, Object> flowInput(ScheduleTask task) {
        try {
            if (task.getInputPayload() == null || task.getInputPayload().isBlank()) return Map.of("records", List.of());
            return objectMapper.readValue(task.getInputPayload(), new TypeReference<>() {});
        } catch (Exception error) {
            throw new IllegalStateException("流程调度输入不是有效 JSON 对象", error);
        }
    }

    private boolean shouldRun(ScheduleTask task, LocalDateTime now) {
        return task.getNextRunAt() == null || !now.isBefore(task.getNextRunAt());
    }

    public record ScheduleRunResult(Long scheduleTaskId, boolean success, String errorMessage,
                                    LocalDateTime startedAt, LocalDateTime nextRunAt) {}
}
