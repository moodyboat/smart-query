package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.ModelExecution;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.ModelExecutionMapper;
import com.smartquery.service.MiningService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/mining/model")
@RequiredArgsConstructor
public class MiningModelController {

    private final MiningModelMapper miningModelMapper;
    private final ModelExecutionMapper modelExecutionMapper;
    private final MiningService miningService;

    @GetMapping
    public Result<List<MiningModel>> list(
            @RequestParam(required = false) Long dataSourceId,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<MiningModel> wrapper = new LambdaQueryWrapper<MiningModel>()
                .eq(MiningModel::getDeleted, 0)
                .orderByDesc(MiningModel::getCreatedAt);
        if (dataSourceId != null) wrapper.eq(MiningModel::getDataSourceId, dataSourceId);
        if (status != null) wrapper.eq(MiningModel::getStatus, status);
        return Result.ok(miningModelMapper.selectList(wrapper));
    }

    @GetMapping("/{id}")
    public Result<MiningModel> get(@PathVariable Long id) {
        MiningModel model = miningModelMapper.selectById(id);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            return Result.error("模型不存在: " + id);
        }
        return Result.ok(model);
    }

    @PostMapping
    public Result<MiningModel> create(@RequestBody MiningModel model) {
        try {
            return Result.ok(miningService.createModel(model));
        } catch (Exception e) {
            log.error("[MINING] Create model failed: {}", e.getMessage());
            return Result.error("创建模型失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<MiningModel> update(@PathVariable Long id, @RequestBody MiningModel updates) {
        try {
            return Result.ok(miningService.updateModel(id, updates));
        } catch (Exception e) {
            log.error("[MINING] Update model failed: {}", e.getMessage());
            return Result.error("更新模型失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        miningModelMapper.deleteById(id);
        return Result.ok();
    }

    @PostMapping("/{id}/train")
    public Result<MiningModel> train(@PathVariable Long id) {
        try {
            return Result.ok(miningService.trainModel(id, "manual"));
        } catch (Exception e) {
            log.error("[MINING] Train model failed: {}", e.getMessage());
            return Result.error("训练失败: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/publish")
    public Result<MiningModel> publish(@PathVariable Long id) {
        try {
            return Result.ok(miningService.publishModel(id));
        } catch (Exception e) {
            log.error("[MINING] Publish model failed: {}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/{id}/offline")
    public Result<MiningModel> offline(@PathVariable Long id) {
        try {
            return Result.ok(miningService.offlineModel(id));
        } catch (Exception e) {
            log.error("[MINING] Offline model failed: {}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/{id}/hyperparams")
    public Result<MiningModel> updateHyperparams(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String hyperparamsJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(body.get("hyperparameters"));
            return Result.ok(miningService.updateHyperparameters(id, hyperparamsJson));
        } catch (Exception e) {
            log.error("[MINING] Update hyperparams failed: {}", e.getMessage());
            return Result.error("更新超参数失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/executions")
    public Result<List<ModelExecution>> executions(@PathVariable Long id) {
        return Result.ok(modelExecutionMapper.selectList(
                new LambdaQueryWrapper<ModelExecution>()
                        .eq(ModelExecution::getModelId, id)
                        .orderByDesc(ModelExecution::getCreatedAt)
                        .last("LIMIT 50")));
    }

    @PutMapping("/{id}/schedule")
    public Result<MiningModel> updateSchedule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        MiningModel model = miningModelMapper.selectById(id);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            return Result.error("模型不存在: " + id);
        }
        if (body.containsKey("cron")) {
            model.setScheduleCron((String) body.get("cron"));
        }
        if (body.containsKey("enabled")) {
            model.setScheduleEnabled(Boolean.TRUE.equals(body.get("enabled")));
        }
        miningModelMapper.updateById(model);
        log.info("[MINING] Schedule updated for model {}: cron={}, enabled={}", id, model.getScheduleCron(), model.getScheduleEnabled());
        return Result.ok(model);
    }
}
