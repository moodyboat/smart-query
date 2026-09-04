package com.smartquery.controller;

import com.smartquery.common.BusinessException;
import com.smartquery.common.ModelStatus;
import com.smartquery.common.RateLimiter;
import com.smartquery.common.Result;
import com.smartquery.common.UserContextHolder;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.DataSource;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.ModelExecution;
import com.smartquery.entity.PredictionResult;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.mapper.DataSourceMapper;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.service.MiningService;
import com.smartquery.service.ResourceAccessService;
import com.smartquery.orchestration.MiningOperatorRegistrationService;
import com.smartquery.util.DbMetadataUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/mining/model")
@RequiredArgsConstructor
public class MiningModelController {

    private final MiningModelMapper miningModelMapper;
    private final com.smartquery.python.PythonCircuitBreaker circuitBreaker;

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.rate-train:5}")
    private int rateTrain;
    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.rate-predict:20}")
    private int ratePredict;
    private final MiningService miningService;
    private final DataSourceManager dataSourceManager;
    private final DataSourceMapper dataSourceMapper;
    private final ConversationEventLogger eventLogger;
    private final RateLimiter rateLimiter;
    private final ResourceAccessService resourceAccess;
    private final com.smartquery.service.ScheduleTaskService scheduleTaskService;
    private final MiningOperatorRegistrationService miningOperatorRegistrationService;
    private final com.smartquery.service.TaskEventService taskEventService;

    /**
     * 按 id 加载模型并校验归属当前用户；不存在或越权时抛 BusinessException。
     */
    private MiningModel loadOwned(Long id) {
        return resourceAccess.requireModel(id);
    }

    @GetMapping
    public Result<List<MiningModel>> list(
            @RequestParam(required = false) Long dataSourceId,
            @RequestParam(required = false) String status) {
        return Result.ok(resourceAccess.listModels(dataSourceId, status));
    }

    @GetMapping("/{id}")
    public Result<MiningModel> get(@PathVariable Long id) {
        return Result.ok(loadOwned(id));
    }

    @GetMapping("/by-pipeline/{pipelineId}")
    public Result<MiningModel> getByPipeline(@PathVariable Long pipelineId) {
        return Result.ok(resourceAccess.findModelByPipeline(pipelineId));
    }

    @PostMapping
    public Result<MiningModel> create(@RequestBody MiningModel model) {
        if (model.getSource() == null) model.setSource("manual");
        return Result.ok(miningService.createModel(model));
    }

    @PutMapping("/{id}")
    public Result<MiningModel> update(@PathVariable Long id, @RequestBody MiningModel updates) {
        resourceAccess.requireModel(id);
        return Result.ok(miningService.updateModel(id, updates));
    }

    @PutMapping("/{id}/predict-config")
    public Result<MiningModel> updatePredictConfig(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        MiningModel updates = new MiningModel();
        if (body.containsKey("predictInputTable")) updates.setPredictInputTable((String) body.get("predictInputTable"));
        if (body.containsKey("predictInputFilter")) updates.setPredictInputFilter((String) body.get("predictInputFilter"));
        if (body.containsKey("predictResultTable")) updates.setPredictResultTable((String) body.get("predictResultTable"));
        return Result.ok(miningService.updateModel(id, updates));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        miningService.deleteModel(id);
        return Result.ok();
    }

    /**
     * 强制删除模型（绕过所有状态检查，用于清理幽灵模型）
     */
    @DeleteMapping("/{id}/force")
    public Result<Void> forceDelete(@PathVariable Long id) {
        if (!resourceAccess.isAdmin()) {
            throw new BusinessException(403, "需要全局资源管理权限才能强制删除模型");
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
        miningOperatorRegistrationService.archiveOperator(model);

        return Result.ok();
    }

    @PostMapping("/{id}/train")
    public Result<MiningService.TrainingSubmission> train(@PathVariable Long id) {
        if (!rateLimiter.tryAcquire("train", rateTrain)) {
            throw new BusinessException(429, "训练请求过于频繁，请稍后重试");
        }
        resourceAccess.requireModel(id);
        return Result.ok(miningService.submitTraining(id, "manual"));
    }

    @GetMapping("/{id}/executions/{executionId}")
    public Result<ModelExecution> execution(@PathVariable Long id, @PathVariable Long executionId) {
        return Result.ok(resourceAccess.requireModelExecution(id, executionId));
    }

    @PostMapping("/{id}/executions/{executionId}/cancel")
    public Result<ModelExecution> cancelExecution(@PathVariable Long id, @PathVariable Long executionId) {
        return Result.ok(miningService.cancelTraining(id, executionId));
    }

    /**
     * 训练进度流式接口 (SSE)
     * 提供训练过程中的实时步骤更新
     */
    @GetMapping(value = "/{id}/train-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter trainStream(@PathVariable Long id,
                                  @RequestParam(required = false) Long executionId,
                                  @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        resourceAccess.requireModel(id);
        Long watchedExecutionId = executionId;
        if (watchedExecutionId == null) {
            List<ModelExecution> latest = resourceAccess.listModelExecutions(id, 1);
            if (latest.isEmpty()) throw new BusinessException("模型暂无训练执行记录");
            watchedExecutionId = latest.get(0).getId();
        }
        resourceAccess.requireModelExecution(id, watchedExecutionId);
        return taskEventService.subscribe(
            com.smartquery.service.TaskEventService.trainingTopic(watchedExecutionId),
            UserContextHolder.require().userId().toString(), lastEventId);
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

    @PostMapping("/{id}/publish")
    public Result<MiningModel> publish(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        resourceAccess.requireModel(id);
        return Result.ok(miningService.publishModel(id, body));
    }

    @GetMapping("/{id}/governance")
    public Result<MiningService.GovernanceReport> governance(@PathVariable Long id) {
        return Result.ok(miningService.governanceReport(id));
    }

    @PostMapping("/{id}/approve")
    public Result<MiningModel> approve(@PathVariable Long id) {
        return Result.ok(miningService.approveEvaluation(id));
    }

    @PostMapping("/{id}/drift-check")
    public Result<Map<String, Object>> driftCheck(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        String inputTable = body == null ? null : (String) body.get("inputTable");
        String inputFilter = body == null ? null : (String) body.get("inputFilter");
        return Result.ok(miningService.checkDrift(id, inputTable, inputFilter));
    }

    @PostMapping("/{id}/offline")
    public Result<MiningModel> offline(@PathVariable Long id) {
        resourceAccess.requireModel(id);
        return Result.ok(miningService.offlineModel(id));
    }

    @PutMapping("/{id}/hyperparams")
    public Result<MiningModel> updateHyperparams(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        resourceAccess.requireModel(id);
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
        return Result.ok(resourceAccess.listModelExecutions(id, 50));
    }

    @GetMapping("/{id}/artifact-status")
    public Result<Map<String, Object>> artifactStatus(@PathVariable Long id) {
        MiningModel model = resourceAccess.requireModel(id);
        boolean current = Integer.valueOf(com.smartquery.service.MiningRuntimeClient.ARTIFACT_SCHEMA_VERSION)
            .equals(model.getArtifactSchemaVersion());
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("modelId", id);
        status.put("current", current);
        status.put("artifactSchemaVersion", model.getArtifactSchemaVersion());
        status.put("requiredArtifactSchemaVersion",
            com.smartquery.service.MiningRuntimeClient.ARTIFACT_SCHEMA_VERSION);
        status.put("requiresRetraining", !current);
        status.put("message", current ? "模型制品版本可用" : "旧模型需要重新训练以生成完整 sklearn Pipeline");
        return Result.ok(status);
    }

    @PostMapping("/{id}/artifact-migration")
    public Result<MiningService.TrainingSubmission> migrateArtifact(@PathVariable Long id) {
        MiningModel model = resourceAccess.requireModel(id);
        if (Integer.valueOf(com.smartquery.service.MiningRuntimeClient.ARTIFACT_SCHEMA_VERSION)
                .equals(model.getArtifactSchemaVersion())) {
            throw new BusinessException("模型已经是最新版 Pipeline 制品");
        }
        return Result.ok(miningService.submitTraining(id, "artifact_migration"));
    }

    @PutMapping("/{id}/schedule")
    public Result<MiningModel> updateSchedule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String cron = body.containsKey("cron") ? (String) body.get("cron") : null;
        Boolean enabled = body.containsKey("enabled") ? Boolean.TRUE.equals(body.get("enabled")) : null;
        String mode = body.containsKey("mode") ? (String) body.get("mode") : null;
        miningService.updateSchedule(id, cron, enabled, mode);
        scheduleTaskService.upsertLegacyModelSchedule(id, cron, enabled, mode);
        MiningModel model = resourceAccess.requireModel(id);
        log.info("[MINING] Schedule updated for model {}: cron={}, enabled={}, mode={}",
            id, model.getScheduleCron(), model.getScheduleEnabled(), model.getScheduleMode());
        return Result.ok(model);
    }

    @PostMapping("/{id}/predict")
    public Result<Map<String, Object>> predict(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        resourceAccess.requireModel(id);
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
        resourceAccess.requireModel(id);
        String overrideTable = body != null ? (String) body.get("inputTable") : null;
        String overrideResult = body != null ? (String) body.get("resultTable") : null;
        String overrideFilter = body != null ? (String) body.get("inputFilter") : null;
        return Result.ok(miningService.batchPredictWithOverrides(id, overrideTable, overrideResult, overrideFilter));
    }

    @GetMapping("/{id}/validate")
    public Result<Map<String, Object>> validate(@PathVariable Long id) {
        resourceAccess.requireModel(id);
        return Result.ok(miningService.validateForTraining(id));
    }

    @GetMapping("/{id}/predictions")
    public Result<List<PredictionResult>> predictions(
            @PathVariable Long id,
            @RequestParam(defaultValue = "100") int limit) {
        resourceAccess.requireModel(id);
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
        DataSource dsCfg = dataSourceMapper.selectById(model.getDataSourceId());
        DbMetadataUtil.Dialect dialect = DbMetadataUtil.Dialect.of(dsCfg != null ? dsCfg.getType() : null);
        if (!DbMetadataUtil.tableExists(jdbc, dialect, tableName)) {
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
        resourceAccess.requirePipeline(model.getPipelineId());
        miningService.syncModelToPipeline(model.getPipelineId(), model);
        miningService.syncPipelineToModel(model.getPipelineId());
        model = resourceAccess.requireModel(id);
        return Result.ok(model);
    }

    @PostMapping("/{id}/rollback/{executionId}")
    public Result<MiningModel> rollback(@PathVariable Long id, @PathVariable Long executionId) {
        resourceAccess.requireModel(id);
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
        if (!resourceAccess.isAdmin()) throw new BusinessException(403, "需要全局资源管理权限");
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
        if (!resourceAccess.isAdmin()) throw new BusinessException(403, "需要全局资源管理权限");
        return Result.ok(Map.of(
            "state", circuitBreaker.getState(),
            "failures", circuitBreaker.getConsecutiveFailures()
        ));
    }

    /**
     * SSE 端点 — 监听模型状态变化，训练完成后推送
     */
    @GetMapping(value = "/{id}/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter statusStream(@PathVariable Long id,
                                   @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        resourceAccess.requireModel(id);
        return taskEventService.subscribe(
            com.smartquery.service.TaskEventService.modelTopic(id),
            UserContextHolder.require().userId().toString(), lastEventId);
    }
}
