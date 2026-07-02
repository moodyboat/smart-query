package com.smartquery.controller;

import com.smartquery.common.BusinessException;
import com.smartquery.common.ModelStatus;
import com.smartquery.common.Ownership;
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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final com.smartquery.python.PythonCircuitBreaker circuitBreaker;

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
    private final Ownership ownership;

    /**
     * 按 id 加载模型并校验归属当前用户；不存在或越权时抛 BusinessException。
     */
    private MiningModel loadOwned(Long id) {
        MiningModel model = miningModelMapper.selectById(id);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            throw new BusinessException("模型不存在: " + id);
        }
        if (!ownership.modelOwnedBy(model)) {
            throw new BusinessException(403, "无权访问该模型");
        }
        return model;
    }

    @GetMapping
    public Result<List<MiningModel>> list(
            @RequestParam(required = false) Long dataSourceId,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<MiningModel> wrapper = new LambdaQueryWrapper<MiningModel>()
                .eq(MiningModel::getDeleted, 0)
                .orderByDesc(MiningModel::getCreatedAt);
        if (!ownership.isAdmin()) {
            String uid = ownership.currentUserIdString();
            if (uid == null) return Result.ok(List.of());
            wrapper.eq(MiningModel::getUserId, uid);
        }
        if (dataSourceId != null) wrapper.eq(MiningModel::getDataSourceId, dataSourceId);
        if (status != null) wrapper.eq(MiningModel::getStatus, status);
        return Result.ok(miningModelMapper.selectList(wrapper));
    }

    @GetMapping("/{id}")
    public Result<MiningModel> get(@PathVariable Long id) {
        return Result.ok(loadOwned(id));
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
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
        return Result.ok(miningService.updateModel(id, updates));
    }

    @PutMapping("/{id}/predict-config")
    public Result<MiningModel> updatePredictConfig(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        MiningModel model = loadOwned(id);
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
        MiningModel model = loadOwned(id);
        if (ModelStatus.TRAINING.equals(model.getStatus())) {
            throw new BusinessException("模型正在训练中，无法删除");
        }
        miningModelMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                .eq(MiningModel::getId, id)
                .set(MiningModel::getDeleted, 1));
        return Result.ok();
    }

    /**
     * 强制删除模型（绕过所有状态检查，用于清理幽灵模型）
     */
    @DeleteMapping("/{id}/force")
    public Result<Void> forceDelete(@PathVariable Long id) {
        if (!ownership.isAdmin()) {
            throw new BusinessException(403, "仅管理员可强制删除模型");
        }
        MiningModel model = miningModelMapper.selectById(id);
        if (model == null) {
            throw new BusinessException("模型不存在: " + id);
        }

        // 记录强制删除操作
        log.warn("[FORCE-DELETE] 强制删除模型: id={}, name={}, status={}, path={}",
            model.getId(), model.getName(), model.getStatus(), model.getModelPath());

        // 强制逻辑删除，不检查任何状态
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
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
        return Result.ok(miningService.trainModel(id, "manual"));
    }

    /**
     * 训练进度流式接口 (SSE)
     * 提供训练过程中的实时步骤更新
     */
    @GetMapping(value = "/{id}/train-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter trainStream(@PathVariable Long id) {
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
        SseEmitter emitter = new SseEmitter(statusStreamTimeoutMs);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            try {
                MiningModel model = miningModelMapper.selectById(id);
                if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"message\": \"模型不存在: " + id + "\"}"));
                    emitter.complete();
                    return;
                }

                // 发送训练开始事件
                log.info("[TRAIN-STREAM] 开始模型训练流: modelId={}, model={}", id, model.getName());
                emitter.send(SseEmitter.event()
                    .name("start")
                    .data("{\"modelId\": " + id + ", \"model\": \"" + model.getName() + "\"}"));

                // 轮询模型状态，直到训练完成或失败
                int pollCount = 0;
                int maxPolls = (int) (statusStreamTimeoutMs / trainPollIntervalMs);

                while (pollCount < maxPolls) {
                    Thread.sleep(trainPollIntervalMs);

                    MiningModel currentModel = miningModelMapper.selectById(id);
                    String status = currentModel.getStatus();

                    // 如果模型状态不是training，说明训练已经结束（成功或失败）
                    if (!ModelStatus.TRAINING.equals(status)) {
                        // 根据最终状态发送完成事件
                        if (ModelStatus.TRAINED.equals(status)) {
                            Map<String, Object> metrics = parseMetrics(currentModel.getMetrics());
                            log.info("[TRAIN-STREAM] 模型训练已完成: modelId={}, metrics={}", id, metrics);
                            emitter.send(SseEmitter.event()
                                .name("complete")
                                .data("{\"success\": true, \"metrics\": " +
                                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metrics) + "}"));
                        } else if (ModelStatus.FAILED.equals(status)) {
                            log.error("[TRAIN-STREAM] 模型训练失败: modelId={}", id);
                            emitter.send(SseEmitter.event()
                                .name("error")
                                .data("{\"message\": \"模型训练失败，请检查日志\"}"));
                        }
                        emitter.complete();
                        return;
                    }

                    // 根据训练时间估算当前步骤
                    TrainingStep step = estimateTrainingStep(currentModel, pollCount);
                    if (step != null) {
                        emitter.send(SseEmitter.event()
                            .name("step")
                            .data(String.format("{\"stepId\": \"%s\", \"label\": \"%s\", \"progress\": %d}",
                                step.getId(), step.getLabel(), step.getProgress())));
                    }

                    pollCount++;
                }

                // 超时
                emitter.send(SseEmitter.event()
                    .name("timeout")
                    .data("{\"message\": \"训练超时\"}"));
                emitter.complete();

            } catch (Exception e) {
                log.error("[TRAIN-STREAM] Error streaming training progress for model {}", id, e);
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"message\": \"服务器错误: " + e.getMessage() + "\"}"));
                } catch (IOException ioException) {
                    log.error("[TRAIN-STREAM] Error sending error event", ioException);
                }
                emitter.completeWithError(e);
            } finally {
                executor.shutdown();
            }
        });

        return emitter;
    }

    /**
     * 根据模型状态返回对应的训练步骤
     */
    private TrainingStep getTrainingStep(String status, MiningModel model) {
        if (model == null) return null;

        return switch (status) {
            case ModelStatus.TRAINING -> new TrainingStep("model_training", "模型训练中", 50);
            case ModelStatus.TRAINED -> new TrainingStep("model_evaluation", "模型评估完成", 100);
            case ModelStatus.FAILED -> new TrainingStep("error", "训练失败", 0);
            default -> new TrainingStep("preparing", "准备中", 10);
        };
    }

    /**
     * 根据训练轮次估算当前步骤
     */
    private TrainingStep estimateTrainingStep(MiningModel model, int pollCount) {
        // 改进的步骤估算逻辑：基于训练时间动态计算
        // 假设各阶段大致耗时：数据加载(10%) -> 预处理(20%) -> 特征工程(25%) -> 分割(15%) -> 训练(25%) -> 评估(5%)

        int progress = Math.min(95, pollCount * 2); // 每次轮询增加2%，最高95%

        if (progress < 10) {
            return new TrainingStep("data_loading", "数据加载中", progress);
        } else if (progress < 30) {
            return new TrainingStep("data_preprocessing", "数据预处理中", progress);
        } else if (progress < 55) {
            return new TrainingStep("feature_engineering", "特征工程中", progress);
        } else if (progress < 70) {
            return new TrainingStep("train_test_split", "数据分割中", progress);
        } else if (progress < 90) {
            return new TrainingStep("model_training", "模型训练中", progress);
        } else {
            return new TrainingStep("model_evaluation", "模型评估中", progress);
        }
    }

    /**
     * 解析模型指标
     */
    private Map<String, Object> parseMetrics(String metricsJson) {
        try {
            if (metricsJson == null || metricsJson.isEmpty()) {
                return Map.of();
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(metricsJson, Map.class);
        } catch (Exception e) {
            log.error("[METRICS] Error parsing metrics: {}", metricsJson, e);
            return Map.of("error", "无法解析指标");
        }
    }

    /**
     * 训练步骤记录类
     */
    private static class TrainingStep {
        private final String id;
        private final String label;
        private final int progress;

        public TrainingStep(String id, String label, int progress) {
            this.id = id;
            this.label = label;
            this.progress = progress;
        }

        public String getId() { return id; }
        public String getLabel() { return label; }
        public int getProgress() { return progress; }
    }

    @PostMapping("/{id}/publish")
    public Result<MiningModel> publish(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
        return Result.ok(miningService.publishModel(id, body));
    }

    @PostMapping("/{id}/offline")
    public Result<MiningModel> offline(@PathVariable Long id) {
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
        return Result.ok(miningService.offlineModel(id));
    }

    @PutMapping("/{id}/hyperparams")
    public Result<MiningModel> updateHyperparams(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
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
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
        return Result.ok(modelExecutionMapper.selectList(
                new LambdaQueryWrapper<ModelExecution>()
                        .eq(ModelExecution::getModelId, id)
                        .orderByDesc(ModelExecution::getCreatedAt)
                        .last("LIMIT 50")));
    }

    @PutMapping("/{id}/schedule")
    public Result<MiningModel> updateSchedule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        MiningModel model = loadOwned(id);
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
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
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
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
        String overrideTable = body != null ? (String) body.get("inputTable") : null;
        String overrideResult = body != null ? (String) body.get("resultTable") : null;
        String overrideFilter = body != null ? (String) body.get("inputFilter") : null;
        return Result.ok(miningService.batchPredictWithOverrides(id, overrideTable, overrideResult, overrideFilter));
    }

    @GetMapping("/{id}/validate")
    public Result<Map<String, Object>> validate(@PathVariable Long id) {
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
        return Result.ok(miningService.validateForTraining(id));
    }

    @GetMapping("/{id}/predictions")
    public Result<List<PredictionResult>> predictions(
            @PathVariable Long id,
            @RequestParam(defaultValue = "100") int limit) {
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
        return Result.ok(miningService.getPredictionResults(id, limit));
    }

    @GetMapping("/{id}/preview-result-table")
    public Result<Map<String, Object>> previewResultTable(
            @PathVariable Long id,
            @RequestParam String tableName,
            @RequestParam(defaultValue = "10") int limit) {
        MiningModel model = loadOwned(id);
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
        MiningModel model = loadOwned(id);
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
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
        return Result.ok(miningService.rollbackToExecution(id, executionId));
    }

    @GetMapping("/{id}/lineage")
    public Result<List<Map<String, Object>>> lineage(@PathVariable Long id) {
        MiningModel model = loadOwned(id);
        return Result.ok(eventLogger.getConversationTrace(model.getConversationId()));
    }

    /**
     * 重置 Python 熔断器状态
     */
    @PostMapping("/admin/reset-circuit-breaker")
    public Result<Map<String, Object>> resetCircuitBreaker() {
        circuitBreaker.reset();
        return Result.ok(Map.of(
            "message", "熔断器已重置",
            "state", circuitBreaker.getState(),
            "failures", circuitBreaker.getConsecutiveFailures()
        ));
    }

    /**
     * 获取熔断器状态
     */
    @GetMapping("/admin/circuit-breaker-status")
    public Result<Map<String, Object>> getCircuitBreakerStatus() {
        return Result.ok(Map.of(
            "state", circuitBreaker.getState(),
            "failures", circuitBreaker.getConsecutiveFailures()
        ));
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
        if (!ownership.model(id)) throw new BusinessException(403, "无权访问该模型");
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
