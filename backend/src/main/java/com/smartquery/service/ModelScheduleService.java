package com.smartquery.service;

import com.smartquery.common.ModelStatus;
import com.smartquery.entity.MiningModel;
import com.smartquery.mapper.MiningModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelScheduleService {

    private final MiningModelMapper miningModelMapper;
    private final MiningService miningService;

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
                if ("predict".equals(mode)) {
                    if (!ModelStatus.PUBLISHED.equals(model.getStatus())) {
                        log.warn("[SCHEDULE] Skipping predict for non-published model: {} (status={})", model.getName(), model.getStatus());
                        continue;
                    }
                    log.info("[SCHEDULE] Running scheduled PREDICT for model: {} (id={})", model.getName(), model.getId());
                    miningService.batchPredict(model.getId());
                } else {
                    if (!ModelStatus.PUBLISHED.equals(model.getStatus()) && !ModelStatus.TRAINED.equals(model.getStatus())) {
                        log.warn("[SCHEDULE] Skipping train for model {} in status: {}", model.getName(), model.getStatus());
                        continue;
                    }
                    log.info("[SCHEDULE] Running scheduled TRAIN for model: {} (id={})", model.getName(), model.getId());
                    miningService.trainModel(model.getId(), "schedule");
                }
                model.setLastRunAt(now);
                model.setNextRunAt(estimateNextRun(model.getScheduleCron(), now));
                miningModelMapper.updateById(model);
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

    private long parseCronToMinutes(String cron) {
        if (cron == null || cron.isBlank()) return 1440;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length >= 1) {
            try {
                if (parts[0].startsWith("*/")) {
                    return Long.parseLong(parts[0].substring(2));
                }
                return Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                log.warn("[SCHEDULE] Invalid cron format '{}', defaulting to 1440 minutes", cron);
            }
        }
        return 1440;
    }
}
