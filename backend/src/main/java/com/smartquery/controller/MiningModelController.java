package com.smartquery.controller;

import com.smartquery.common.BusinessException;
import com.smartquery.common.ModelStatus;
import com.smartquery.common.RateLimiter;
import com.smartquery.common.Result;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.ModelExecution;
import com.smartquery.entity.PredictionResult;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.ModelExecutionMapper;
import com.smartquery.service.MiningService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/v1/mining/model")
@RequiredArgsConstructor
public class MiningModelController {

    private final MiningModelMapper miningModelMapper;
    private final ModelExecutionMapper modelExecutionMapper;

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.train-poll-interval-ms:2000}")
    private int trainPollIntervalMs;
    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.status-stream-timeout-ms:120000}")
    private long statusStreamTimeoutMs;
    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.rate-train:5}")
    private int rateTrain;
    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.rate-predict:20}")
    private int ratePredict;
    private final MiningService miningService;
    private final DataSourceManager dataSourceManager;
    private final ConversationEventLogger eventLogger;
    private final RateLimiter rateLimiter;

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
            throw new BusinessException("模型不存在: " + id);
        }
        return Result.ok(model);
    }

    @GetMapping("/by-pipeline/{pipelineId}")
    public Result<MiningModel> getByPipeline(@PathVariable Long pipelineId) {
        MiningModel model = miningModelMapper.selectOne(
            new LambdaQueryWrapper<MiningModel>()
                .eq(MiningModel::getPipelineId, pipelineId)
                .eq(MiningModel::getDeleted, 0));
        return Result.ok(model);
    }

    @PostMapping
    public Result<MiningModel> create(@RequestBody MiningModel model) {
        if (model.getSource() == null) model.setSource("manual");
        return Result.ok(miningService.createModel(model));
    }

    @PutMapping("/{id}")
    public Result<MiningModel> update(@PathVariable Long id, @RequestBody MiningModel updates) {
        return Result.ok(miningService.updateModel(id, updates));
    }

    @PutMapping("/{id}/predict-config")
    public Result<MiningModel> updatePredictConfig(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        MiningModel model = miningModelMapper.selectById(id);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            throw new BusinessException("模型不存在: " + id);
        }
        miningModelMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                .eq(MiningModel::getId, id)
                .set(MiningModel::getPredictInputTable, body.get("predictInputTable"))
                .set(MiningModel::getPredictInputFilter, body.get("predictInputFilter"))
                .set(MiningModel::getPredictResultTable, body.get("predictResultTable")));
        return Result.ok(miningModelMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        MiningModel model = miningModelMapper.selectById(id);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            throw new BusinessException("模型不存在: " + id);
        }
        if (ModelStatus.TRAINING.equals(model.getStatus())) {
            throw new BusinessException("模型正在训练中，无法删除");
        }
        miningModelMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                .eq(MiningModel::getId, id)
                .set(MiningModel::getDeleted, 1));
        return Result.ok();
    }

    @PostMapping("/{id}/train")
    public Result<MiningModel> train(@PathVariable Long id) {
        if (!rateLimiter.tryAcquire("train", rateTrain)) {
            throw new BusinessException(429, "训练请求过于频繁，请稍后重试");
        }
        return Result.ok(miningService.trainModel(id, "manual"));
    }

    @PostMapping("/{id}/publish")
    public Result<MiningModel> publish(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(miningService.publishModel(id, body));
    }

    @PostMapping("/{id}/offline")
    public Result<MiningModel> offline(@PathVariable Long id) {
        return Result.ok(miningService.offlineModel(id));
    }

    @PutMapping("/{id}/hyperparams")
    public Result<MiningModel> updateHyperparams(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String hyperparamsJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(body.get("hyperparameters"));
            return Result.ok(miningService.updateHyperparameters(id, hyperparamsJson));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BusinessException("超参数格式错误: " + e.getMessage());
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
            throw new BusinessException("模型不存在: " + id);
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
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inputRows = (List<Map<String, Object>>) body.get("input");
        if (inputRows == null || inputRows.isEmpty()) {
            throw new BusinessException("input 不能为空");
        }
        String saveTable = (String) body.get("saveTable");
        return Result.ok(miningService.predictModel(id, inputRows, saveTable));
    }

    @PostMapping("/{id}/batch-predict")
    public Result<Map<String, Object>> batchPredict(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!rateLimiter.tryAcquire("predict", ratePredict)) {
            throw new BusinessException(429, "预测请求过于频繁，请稍后重试");
        }
        String overrideTable = body != null ? (String) body.get("inputTable") : null;
        String overrideResult = body != null ? (String) body.get("resultTable") : null;
        String overrideFilter = body != null ? (String) body.get("inputFilter") : null;
        return Result.ok(miningService.batchPredictWithOverrides(id, overrideTable, overrideResult, overrideFilter));
    }

    @GetMapping("/{id}/validate")
    public Result<Map<String, Object>> validate(@PathVariable Long id) {
        return Result.ok(miningService.validateForTraining(id));
    }

    @GetMapping("/{id}/predictions")
    public Result<List<PredictionResult>> predictions(
            @PathVariable Long id,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(miningService.getPredictionResults(id, limit));
    }

    @GetMapping("/{id}/preview-result-table")
    public Result<Map<String, Object>> previewResultTable(
            @PathVariable Long id,
            @RequestParam String tableName,
            @RequestParam(defaultValue = "10") int limit) {
        MiningModel model = miningModelMapper.selectById(id);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            throw new BusinessException("模型不存在: " + id);
        }
        JdbcTemplate jdbc = dataSourceManager.getJdbcTemplate(model.getDataSourceId());
        com.smartquery.common.IdentifierValidator.validateTableName(tableName);
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
            Integer.class, tableName);
        if (count == null || count == 0) {
            throw new BusinessException("表不存在: " + tableName);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM `" + tableName + "` LIMIT ?",
            limit < 1 || limit > 100 ? 10 : limit);
        List<String> columns = rows.isEmpty() ? List.of() : List.copyOf(rows.get(0).keySet());
        return Result.ok(Map.of("rows", rows, "columns", columns, "tableName", tableName));
    }

    @PostMapping("/{id}/sync-pipeline")
    public Result<MiningModel> syncPipeline(@PathVariable Long id) {
        MiningModel model = miningModelMapper.selectById(id);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            throw new BusinessException("模型不存在: " + id);
        }
        if (model.getPipelineId() == null) {
            throw new BusinessException("模型未关联流程");
        }
        miningService.syncModelToPipeline(model.getPipelineId(), model);
        miningService.syncPipelineToModel(model.getPipelineId());
        model = miningModelMapper.selectById(id);
        return Result.ok(model);
    }

    @PostMapping("/{id}/rollback/{executionId}")
    public Result<MiningModel> rollback(@PathVariable Long id, @PathVariable Long executionId) {
        return Result.ok(miningService.rollbackToExecution(id, executionId));
    }

    @GetMapping("/{id}/lineage")
    public Result<List<Map<String, Object>>> lineage(@PathVariable Long id) {
        MiningModel model = miningModelMapper.selectById(id);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            throw new BusinessException("模型不存在: " + id);
        }
        return Result.ok(eventLogger.getConversationTrace(model.getConversationId()));
    }

    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(20, r -> {
        Thread t = new Thread(r, "model-sse");
        t.setDaemon(true);
        return t;
    });

    /**
     * SSE 端点 — 监听模型状态变化，训练完成后推送
     */
    @GetMapping(value = "/{id}/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter statusStream(@PathVariable Long id) {
        SseEmitter emitter = new SseEmitter(statusStreamTimeoutMs);
        sseExecutor.submit(() -> {
            try {
                String lastStatus = null;
                for (int i = 0; i < 60; i++) { // max 2 min (60 × 2s)
                    MiningModel model = miningModelMapper.selectById(id);
                    if (model == null) {
                        emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"模型不存在\"}"));
                        break;
                    }
                    String status = model.getStatus();
                    if (lastStatus == null) lastStatus = status;

                    if (!status.equals(lastStatus) || com.smartquery.common.ModelStatus.TRAINED.equals(status) || com.smartquery.common.ModelStatus.FAILED.equals(status) || com.smartquery.common.ModelStatus.PUBLISHED.equals(status)) {
                        Map<String, Object> data = new java.util.LinkedHashMap<>();
                        data.put("type", "model_status");
                        data.put("modelId", id);
                        data.put("status", status);
                        data.put("metrics", model.getMetrics());
                        emitter.send(SseEmitter.event().data(
                            new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)));
                        if (com.smartquery.common.ModelStatus.TRAINED.equals(status) || com.smartquery.common.ModelStatus.FAILED.equals(status) || com.smartquery.common.ModelStatus.PUBLISHED.equals(status)) {
                            break;
                        }
                    }
                    lastStatus = status;
                    Thread.sleep(trainPollIntervalMs);
                }
            } catch (Exception e) {
                if (!(e instanceof java.io.IOException)) {
                    log.warn("[SSE] status-stream error for model {}: {}", id, e.getMessage());
                }
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }
}
