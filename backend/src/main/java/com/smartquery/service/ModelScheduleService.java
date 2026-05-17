package com.smartquery.service;

import com.smartquery.common.ModelStatus;
import com.smartquery.engine.ConversationContextHolder;
import com.smartquery.entity.MiningModel;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.mapper.MiningModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelScheduleService {

    @org.springframework.beans.factory.annotation.Value("${mining.schedule.fallback-minutes:1440}")
    private int fallbackMinutes;

    private final MiningModelMapper miningModelMapper;
    private final MiningService miningService;
    private final ConversationEventLogger eventLogger;
    private final ConversationContextHolder.SessionManager sessionManager;

    private final ConcurrentHashMap<Long, AtomicBoolean> runningModels = new ConcurrentHashMap<>();

    @Scheduled(fixedRateString = "${mining.schedule.poll-interval-ms:60000}")
    public void checkScheduledModels() {
        List<MiningModel> models = miningModelMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MiningModel>()
                        .eq(MiningModel::getDeleted, 0)
                        .eq(MiningModel::getScheduleEnabled, true)
                        .isNotNull(MiningModel::getScheduleCron));

        LocalDateTime now = LocalDateTime.now();
        for (MiningModel model : models) {
            Long modelId = model.getId();
            AtomicBoolean lock = runningModels.computeIfAbsent(modelId, k -> new AtomicBoolean(false));
            if (!lock.compareAndSet(false, true)) {
                log.warn("[SCHEDULE] Skipping model {} — previous execution still running", modelId);
                continue;
            }
            try {
                if (!shouldRun(model, now)) {
                    log.debug("[SCHEDULE] Model {} not due yet (nextRun={})", modelId, model.getNextRunAt());
                    continue;
                }
                String mode = model.getScheduleMode();
                boolean success = true;
                String errorMsg = null;

                if ("predict".equals(mode)) {
                    if (!ModelStatus.PUBLISHED.equals(model.getStatus())) {
                        log.warn("[SCHEDULE] Skipping predict for non-published model: {} (status={})", model.getName(), model.getStatus());
                        continue;
                    }
                    log.info("[SCHEDULE] Running scheduled PREDICT for model: {} (id={})", model.getName(), model.getId());
                    try {
                        miningService.batchPredict(model.getId());
                    } catch (Exception e) {
                        success = false;
                        errorMsg = e.getMessage();
                    }
                } else {
                    if (!ModelStatus.PUBLISHED.equals(model.getStatus()) && !ModelStatus.TRAINED.equals(model.getStatus())) {
                        log.warn("[SCHEDULE] Skipping train for model {} in status: {}", model.getName(), model.getStatus());
                        continue;
                    }
                    log.info("[SCHEDULE] Running scheduled TRAIN for model: {} (id={})", model.getName(), model.getId());
                    try {
                        miningService.trainModel(model.getId(), "schedule", model.getPredictInputFilter());
                    } catch (Exception e) {
                        success = false;
                        errorMsg = e.getMessage();
                    }
                }

                model.setLastRunAt(now);
                model.setNextRunAt(estimateNextRun(model.getScheduleCron(), now));
                // Re-read to get latest version for optimistic lock
                MiningModel fresh = miningModelMapper.selectById(modelId);
                if (fresh != null) {
                    fresh.setLastRunAt(now);
                    fresh.setNextRunAt(model.getNextRunAt());
                    miningModelMapper.updateById(fresh);
                }

                // 记录调度执行事件
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("modelName", model.getName());
                payload.put("mode", mode);
                payload.put("success", success);
                if (!success) payload.put("error", errorMsg);
                payload.put("nextRunAt", model.getNextRunAt().toString());
                eventLogger.logEvent(null, null, success ? "schedule_execution_success" : "schedule_execution_failed", payload);

                if (!success) {
                    log.error("[SCHEDULE] Execution failed for model {}: {}", model.getId(), errorMsg);
                }
            } catch (Exception e) {
                log.error("[SCHEDULE] Failed to run model {}: {}", model.getId(), e.getMessage());
            } finally {
                lock.set(false);
            }
        }

        sessionManager.cleanupStaleSessions();
    }

    private boolean shouldRun(MiningModel model, LocalDateTime now) {
        LocalDateTime nextRun = model.getNextRunAt();
        if (nextRun == null) {
            return true;
        }
        return !now.isBefore(nextRun);
    }

    private LocalDateTime estimateNextRun(String cron, LocalDateTime from) {
        if (cron == null || cron.isBlank()) return from.plusMinutes(fallbackMinutes);
        String[] parts = cron.trim().split("\\s+");

        // Simple format: single number or */N
        if (parts.length == 1) {
            try {
                if (parts[0].startsWith("*/")) {
                    return from.plusMinutes(Long.parseLong(parts[0].substring(2)));
                }
                return from.plusMinutes(Long.parseLong(parts[0]));
            } catch (NumberFormatException e) {
                return from.plusMinutes(fallbackMinutes);
            }
        }

        // Standard 5-field cron: min hour day month dow
        if (parts.length == 5) {
            return calculateNextOccurrence(parts, from);
        }

        return from.plusMinutes(fallbackMinutes);
    }

    // Calculate next actual occurrence for standard 5-field cron.
    // Handles fixed time (0 6 * * *), intervals (* slash N * * * *), specific days.
    private LocalDateTime calculateNextOccurrence(String[] fields, LocalDateTime from) {
        try {
            int targetMinute = resolveCronValue(fields[0], 0, 59);
            int targetHour = resolveCronValue(fields[1], 0, 23);
            String dayField = fields[2];
            String dowField = fields[4];

            // Interval-based: */N in minute field → repeat every N minutes from now
            if (fields[0].startsWith("*/")) {
                long interval = Long.parseLong(fields[0].substring(2));
                return from.plusMinutes(interval);
            }

            // Interval-based: */N in hour field, minute is 0 → repeat every N hours
            if (fields[1].startsWith("*/") && targetMinute == 0) {
                long interval = Long.parseLong(fields[1].substring(2));
                return from.plusHours(interval);
            }

            // Fixed time pattern: specific hour and minute
            if (targetMinute >= 0 && targetMinute <= 59 && targetHour >= 0 && targetHour <= 23) {
                LocalDateTime next = from.toLocalDate().atTime(targetHour, targetMinute);
                // If today's time has passed, schedule for tomorrow
                if (!next.isAfter(from)) {
                    next = next.plusDays(1);
                }
                // Handle specific day-of-month or day-of-week
                if (!"*".equals(dayField) && !dayField.startsWith("*/")) {
                    int targetDay = Integer.parseInt(dayField);
                    while (next.getDayOfMonth() != targetDay) {
                        next = next.plusDays(1);
                    }
                } else if (!"*".equals(dowField)) {
                    int targetDow = Integer.parseInt(dowField);
                    // Java DayOfWeek: Monday=1..Sunday=7, cron: Sunday=0, Monday=1..Saturday=6
                    int javaDow = targetDow == 0 ? 7 : targetDow;
                    while (next.getDayOfWeek().getValue() != javaDow) {
                        next = next.plusDays(1);
                    }
                }
                return next;
            }
        } catch (Exception e) {
            log.warn("[SCHEDULE] Failed to calculate next occurrence for cron '{}': {}", String.join(" ", fields), e.getMessage());
        }
        return from.plusMinutes(fallbackMinutes);
    }

    private int resolveCronValue(String field, int min, int max) {
        if ("*".equals(field)) return -1;
        if (field.startsWith("*/")) return -1;
        int val = Integer.parseInt(field);
        return (val >= min && val <= max) ? val : -1;
    }
}
