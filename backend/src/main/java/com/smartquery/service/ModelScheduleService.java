package com.smartquery.service;

import com.smartquery.common.ModelStatus;
import com.smartquery.entity.MiningModel;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.mapper.MiningModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelScheduleService {

    private final MiningModelMapper miningModelMapper;
    private final MiningService miningService;
    private final ConversationEventLogger eventLogger;

    @Scheduled(fixedRateString = "${mining.schedule.poll-interval-ms:60000}")
    public void checkScheduledModels() {
        List<MiningModel> models = miningModelMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MiningModel>()
                        .eq(MiningModel::getDeleted, 0)
                        .eq(MiningModel::getScheduleEnabled, true)
                        .isNotNull(MiningModel::getScheduleCron));

        LocalDateTime now = LocalDateTime.now();
        for (MiningModel model : models) {
            try {
                if (!shouldRun(model, now)) continue;
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
                        miningService.trainModel(model.getId(), "schedule");
                    } catch (Exception e) {
                        success = false;
                        errorMsg = e.getMessage();
                    }
                }

                model.setLastRunAt(now);
                model.setNextRunAt(estimateNextRun(model.getScheduleCron(), now));
                miningModelMapper.updateById(model);

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
            }
        }
    }

    private boolean shouldRun(MiningModel model, LocalDateTime now) {
        LocalDateTime nextRun = model.getNextRunAt();
        if (nextRun == null) {
            return true;
        }
        return !now.isBefore(nextRun);
    }

    private LocalDateTime estimateNextRun(String cron, LocalDateTime from) {
        long minutes = parseCronToMinutes(cron);
        return from.plusMinutes(minutes);
    }

    // Supports: simple minutes "60", "*/60", or standard 5-field cron "0 */2 * * *"
    private long parseCronToMinutes(String cron) {
        if (cron == null || cron.isBlank()) return 1440;
        String[] parts = cron.trim().split("\\s+");

        // 简单格式: 单个数字或 */N
        if (parts.length == 1) {
            try {
                if (parts[0].startsWith("*/")) {
                    return Long.parseLong(parts[0].substring(2));
                }
                return Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                log.warn("[SCHEDULE] Invalid simple cron '{}', defaulting to 1440 minutes", cron);
                return 1440;
            }
        }

        // 标准 5 位 cron: 分 时 日 月 周
        if (parts.length == 5) {
            try {
                long minutePart = parseCronField(parts[0], 60);
                long hourPart = parseCronField(parts[1], 24);
                if (minutePart > 0 && hourPart == 0) return minutePart;
                if (hourPart > 0 && minutePart == 0) return hourPart * 60;
                if (hourPart > 0) return hourPart * 60 + minutePart;
            } catch (Exception e) {
                log.warn("[SCHEDULE] Invalid standard cron '{}': {}", cron, e.getMessage());
            }
        }

        return 1440;
    }

    private long parseCronField(String field, int max) {
        if ("*".equals(field)) return 0;
        if (field.startsWith("*/")) {
            return Long.parseLong(field.substring(2));
        }
        return Long.parseLong(field);
    }
}
