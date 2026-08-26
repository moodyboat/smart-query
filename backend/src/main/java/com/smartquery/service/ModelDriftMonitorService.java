package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.common.ModelStatus;
import com.smartquery.entity.MiningModel;
import com.smartquery.mapper.MiningModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/** Periodically checks input/score drift for every published model. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDriftMonitorService {
    private final MiningModelMapper miningModelMapper;
    private final MiningService miningService;

    @Scheduled(fixedDelayString = "${smart-query.mining.drift-monitor-interval-ms:21600000}")
    public void monitorPublishedModels() {
        List<MiningModel> models = miningModelMapper.selectList(
            new LambdaQueryWrapper<MiningModel>()
                .eq(MiningModel::getStatus, ModelStatus.PUBLISHED)
                .eq(MiningModel::getDeleted, 0));
        for (MiningModel model : models) {
            try {
                var result = miningService.checkDriftForSchedule(model.getId());
                String status = String.valueOf(result.get("drift_status"));
                if (!"ok".equals(status)) {
                    log.warn("[DRIFT] modelId={} name={} status={} maxPsi={}",
                        model.getId(), model.getName(), status, result.get("max_psi"));
                }
            } catch (Exception e) {
                log.error("[DRIFT] modelId={} check failed: {}", model.getId(), e.getMessage());
            }
        }
    }
}
