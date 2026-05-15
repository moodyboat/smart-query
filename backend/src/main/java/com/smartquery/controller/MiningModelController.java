package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.ModelExecution;
import com.smartquery.entity.PredictionResult;
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
    public Result<MiningModel> publish(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        try {
            return Result.ok(miningService.publishModel(id, body));
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
        if (body.containsKey("mode")) {
            model.setScheduleMode((String) body.get("mode"));
        }
        miningModelMapper.updateById(model);
        log.info("[MINING] Schedule updated for model {}: cron={}, enabled={}, mode={}",
            id, model.getScheduleCron(), model.getScheduleEnabled(), model.getScheduleMode());
        return Result.ok(model);
    }

    @PostMapping("/{id}/predict")
    public Result<Map<String, Object>> predict(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> inputRows = (List<Map<String, Object>>) body.get("input");
            if (inputRows == null || inputRows.isEmpty()) {
                return Result.error("input 不能为空");
            }
            String saveTable = (String) body.get("saveTable");
            Map<String, Object> result = miningService.predictModel(id, inputRows, saveTable);
            return Result.ok(result);
        } catch (Exception e) {
            log.error("[MINING] Predict failed for model {}: {}", id, e.getMessage());
            return Result.error("预测失败: " + e.getMessage());
        }
    }

    /**
     * 批量预测 — 从输入表读取数据，预测后写入结果表
     */
    @PostMapping("/{id}/batch-predict")
    public Result<Map<String, Object>> batchPredict(@PathVariable Long id) {
        try {
            Map<String, Object> result = miningService.batchPredict(id);
            return Result.ok(result);
        } catch (Exception e) {
            log.error("[MINING] Batch predict failed for model {}: {}", id, e.getMessage());
            return Result.error("批量预测失败: " + e.getMessage());
        }
    }

    /**
     * 训练前校验 — 检查源表、特征列、目标列、数据量
     */
    @GetMapping("/{id}/validate")
    public Result<Map<String, Object>> validate(@PathVariable Long id) {
        try {
            return Result.ok(miningService.validateForTraining(id));
        } catch (Exception e) {
            log.error("[MINING] Validation failed for model {}: {}", id, e.getMessage());
            return Result.error("校验失败: " + e.getMessage());
        }
    }

    /**
     * 查询预测结果
     */
    @GetMapping("/{id}/predictions")
    public Result<List<PredictionResult>> predictions(
            @PathVariable Long id,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(miningService.getPredictionResults(id, limit));
    }
}
