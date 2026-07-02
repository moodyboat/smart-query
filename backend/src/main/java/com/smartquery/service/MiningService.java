package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.ModelStatus;
import com.smartquery.common.UserContextHolder;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.*;
import com.smartquery.util.DbUrlUtil;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.logging.DiagnosticsTimer;
import com.smartquery.mapper.*;
import com.smartquery.python.PythonExecutor;
import com.smartquery.python.PythonResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MiningService {

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.workspace:${user.home}/smartquery-models}")
    private String modelWorkspace;

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.max-concurrent-training:4}")
    private int maxConcurrentTraining;

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.queue-timeout-minutes:5}")
    private long trainingQueueTimeoutMinutes;

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.python-timeout-ms:300000}")
    private int pythonTimeoutMs;

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.overfitting-gap-threshold:0.15}")
    private double overfittingGapThreshold;

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.min-training-rows:20}")
    private int minTrainingRows;

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.random-state:42}")
    private int randomState;

    @org.springframework.beans.factory.annotation.Value("${mining.log-truncation:50000}")
    private int logTruncation;

    @org.springframework.beans.factory.annotation.Value("${mining.training-log-truncation:20000}")
    private int trainingLogTruncation;

    @org.springframework.beans.factory.annotation.Value("${mining.error-summary-truncation:500}")
    private int errorSummaryTruncation;

    @org.springframework.beans.factory.annotation.Value("${mining.schedule.default-cron-minutes:60}")
    private int defaultCronMinutes;

    private Semaphore trainingSemaphore;

    private final MiningModelMapper miningModelMapper;
    private final MiningPipelineMapper miningPipelineMapper;
    private final ModelExecutionMapper modelExecutionMapper;
    private final DataSourceMapper dataSourceMapper;
    private final PredictionResultMapper predictionResultMapper;
    private final PythonExecutor pythonExecutor;
    private final DataSourceManager dataSourceManager;
    private final AlgorithmService algorithmService;
    private final ObjectMapper objectMapper;
    private final MiningPredictionService predictionService;
    private final PipelineService pipelineService;
    private final ConversationEventLogger eventLogger;
    private final Executor miningExecutor;

    public MiningService(
            MiningModelMapper miningModelMapper,
            MiningPipelineMapper miningPipelineMapper,
            ModelExecutionMapper modelExecutionMapper,
            DataSourceMapper dataSourceMapper,
            PredictionResultMapper predictionResultMapper,
            PythonExecutor pythonExecutor,
            DataSourceManager dataSourceManager,
            AlgorithmService algorithmService,
            ObjectMapper objectMapper,
            MiningPredictionService predictionService,
            PipelineService pipelineService,
            ConversationEventLogger eventLogger,
            @Qualifier("miningExecutor") Executor miningExecutor
    ) {
        this.miningModelMapper = miningModelMapper;
        this.miningPipelineMapper = miningPipelineMapper;
        this.modelExecutionMapper = modelExecutionMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.predictionResultMapper = predictionResultMapper;
        this.pythonExecutor = pythonExecutor;
        this.dataSourceManager = dataSourceManager;
        this.algorithmService = algorithmService;
        this.objectMapper = objectMapper;
        this.predictionService = predictionService;
        this.pipelineService = pipelineService;
        this.eventLogger = eventLogger;
        this.miningExecutor = miningExecutor;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        trainingSemaphore = new Semaphore(maxConcurrentTraining);
    }

    // ======================== Model Lifecycle ========================

    public MiningModel createModel(MiningModel model) {
        if (model.getName() == null || model.getName().isBlank()) throw new IllegalArgumentException("模型名称不能为空");
        if (model.getDataSourceId() == null) throw new IllegalArgumentException("数据源不能为空");
        if (model.getSourceTable() != null && !model.getSourceTable().isBlank()) {
            com.smartquery.common.IdentifierValidator.validateTableName(model.getSourceTable());
        }
        if (model.getTargetColumn() != null && !model.getTargetColumn().isBlank()) {
            com.smartquery.common.IdentifierValidator.validateColumnName(model.getTargetColumn());
        }
        // 注入归属：手动创建走当前登录用户；Pipeline 自动触发（无 user context）时留空，admin 可见
        if (model.getUserId() == null) {
            UserContextHolder.UserContext ctx = UserContextHolder.get();
            if (ctx != null && ctx.userId() != null) {
                model.setUserId(ctx.userId().toString());
            }
        }
        model.setStatus(ModelStatus.DRAFT);
        model.setVersion(1);
        model.setDeleted(0);
        if (model.getHyperparameters() == null || model.getHyperparameters().isBlank()) {
            model.setHyperparameters("{}");
        }
        model.setFeatureColumns(normalizeToJsonArray(model.getFeatureColumns()));
        miningModelMapper.insert(model);
        logMiningEvent(model, "model_created", Map.of(
            "sourceTable", model.getSourceTable() != null ? model.getSourceTable() : "",
            "algorithm", model.getAlgorithm() != null ? model.getAlgorithm() : ""
        ));
        return model;
    }

    public MiningModel updateModel(Long id, MiningModel updates) {
        MiningModel existing = miningModelMapper.selectById(id);
        if (existing == null) throw new IllegalArgumentException("模型不存在: " + id);

        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getAlgorithm() != null) existing.setAlgorithm(updates.getAlgorithm());
        if (updates.getHyperparameters() != null) existing.setHyperparameters(updates.getHyperparameters());
        if (updates.getDataSourceId() != null) existing.setDataSourceId(updates.getDataSourceId());
        if (updates.getSourceTable() != null) existing.setSourceTable(updates.getSourceTable());
        if (updates.getFeatureColumns() != null) existing.setFeatureColumns(normalizeToJsonArray(updates.getFeatureColumns()));
        if (updates.getTargetColumn() != null) existing.setTargetColumn(updates.getTargetColumn());
        if (updates.getPreprocessing() != null) existing.setPreprocessing(updates.getPreprocessing());
        if (updates.getModelType() != null) existing.setModelType(updates.getModelType());
        if (updates.getScheduleCron() != null) existing.setScheduleCron(updates.getScheduleCron());
        if (updates.getScheduleEnabled() != null) existing.setScheduleEnabled(updates.getScheduleEnabled());
        if (updates.getScheduleMode() != null) existing.setScheduleMode(updates.getScheduleMode());
        if (updates.getPredictInputTable() != null) existing.setPredictInputTable(updates.getPredictInputTable());
        if (updates.getPredictInputFilter() != null) existing.setPredictInputFilter(updates.getPredictInputFilter());
        if (updates.getPredictResultTable() != null) existing.setPredictResultTable(updates.getPredictResultTable());
        if (updates.getValidationMode() != null) existing.setValidationMode(updates.getValidationMode());
        if (updates.getCvFolds() != null) existing.setCvFolds(updates.getCvFolds());
        if (updates.getTestSize() != null) existing.setTestSize(updates.getTestSize());
        if (updates.getTemporalColumn() != null) existing.setTemporalColumn(updates.getTemporalColumn());

        // Recompute nextRunAt when schedule config changes
        if (Boolean.TRUE.equals(existing.getScheduleEnabled()) && existing.getScheduleCron() != null) {
            try {
                existing.setNextRunAt(computeNextRun(existing.getScheduleCron()));
            } catch (Exception e) {
                log.warn("[MINING] Failed to compute nextRun for model {}: {}", id, e.getMessage());
            }
        } else if (!Boolean.TRUE.equals(existing.getScheduleEnabled())) {
            existing.setNextRunAt(null);
        }

        miningModelMapper.updateById(existing);

        if (existing.getPipelineId() != null) {
            syncModelToPipeline(existing.getPipelineId(), existing);
        }

        return existing;
    }

    public void deleteModel(Long id) {
        MiningModel model = miningModelMapper.selectById(id);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + id);
        if (ModelStatus.PUBLISHED.equals(model.getStatus())) {
            throw new IllegalStateException("已发布的模型不能删除，请先下线");
        }
        model.setDeleted(1);
        miningModelMapper.updateById(model);
    }

    // ======================== Validation ========================

    public Map<String, Object> validateForTraining(Long modelId) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);

        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        result.put("modelName", model.getName());

        validateTableName(model.getSourceTable());
        if (model.getTargetColumn() != null && !model.getTargetColumn().isBlank()) {
            validateColumnName(model.getTargetColumn());
        }

        DataSource ds = dataSourceMapper.selectById(model.getDataSourceId());
        if (ds == null) {
            errors.add("数据源不存在 (id=" + model.getDataSourceId() + ")");
            result.put("valid", false);
            result.put("errors", errors);
            return result;
        }

        JdbcTemplate jdbc = dataSourceManager.getJdbcTemplate(ds.getId());
        if (jdbc == null) {
            errors.add("无法连接到数据源");
            result.put("valid", false);
            result.put("errors", errors);
            return result;
        }

        // Check source table exists
        try {
            Integer tableExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class, ds.getDatabaseName(), model.getSourceTable());
            if (tableExists == null || tableExists == 0) {
                errors.add("源数据表 '" + model.getSourceTable() + "' 不存在");
            }
        } catch (Exception e) {
            errors.add("无法查询表信息: " + e.getMessage());
        }

        if (!errors.isEmpty()) {
            result.put("valid", false);
            result.put("errors", errors);
            return result;
        }

        List<Map<String, Object>> columns = jdbc.queryForList(
            "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ORDINAL_POSITION",
            ds.getDatabaseName(), model.getSourceTable());

        Set<String> tableColumns = new HashSet<>();
        Map<String, String> columnTypes = new LinkedHashMap<>();
        for (Map<String, Object> col : columns) {
            String colName = (String) col.get("COLUMN_NAME");
            String dataType = (String) col.get("DATA_TYPE");
            tableColumns.add(colName);
            columnTypes.put(colName, dataType);
        }
        result.put("tableColumns", columnTypes);

        try {
            Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `" + model.getSourceTable() + "`", Integer.class);
            result.put("rowCount", rowCount);
            if (rowCount != null && rowCount < minTrainingRows) {
                warnings.add("数据量仅 " + rowCount + " 行，训练结果可能不可靠（建议至少100行）");
            }
        } catch (Exception e) {
            warnings.add("无法统计数据行数: " + e.getMessage());
        }

        // Validate feature columns
        List<String> featureCols = parseJsonList(model.getFeatureColumns());
        List<String> missingFeatures = new ArrayList<>();
        List<String> numericFeatures = new ArrayList<>();
        List<String> categoricalFeatures = new ArrayList<>();
        for (String fc : featureCols) {
            if (!tableColumns.contains(fc)) {
                missingFeatures.add(fc);
            } else {
                String dt = columnTypes.get(fc);
                if (isNumericType(dt)) numericFeatures.add(fc);
                else categoricalFeatures.add(fc);
            }
        }
        if (!missingFeatures.isEmpty()) errors.add("特征列不存在: " + String.join(", ", missingFeatures));
        result.put("numericFeatures", numericFeatures);
        result.put("categoricalFeatures", categoricalFeatures);

        // Validate target column
        if (model.getTargetColumn() != null && !model.getTargetColumn().isBlank()) {
            if (!tableColumns.contains(model.getTargetColumn())) {
                errors.add("目标列 '" + model.getTargetColumn() + "' 不存在");
            } else {
                result.put("targetType", columnTypes.get(model.getTargetColumn()));
                try {
                    Integer uniqueValues = jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT `" + model.getTargetColumn() + "`) FROM `" + model.getSourceTable() + "`",
                        Integer.class);
                    result.put("targetUniqueValues", uniqueValues);
                    if ("classification".equals(model.getModelType()) && uniqueValues != null) {
                        if (uniqueValues < 2) errors.add("目标列只有 " + uniqueValues + " 个唯一值，无法进行分类任务");
                        else if (uniqueValues > 50) warnings.add("目标列有 " + uniqueValues + " 个唯一值，可能是回归任务");
                    }
                } catch (Exception e) {
                    warnings.add("无法分析目标列分布: " + e.getMessage());
                }
            }
        } else if (!isUnsupervisedType(model.getModelType())) {
            warnings.add("未指定目标列，将以无监督模式训练");
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);
        return result;
    }

    // ======================== Training ========================

    public MiningModel trainModel(Long modelId, String triggerType) {
        return trainModel(modelId, triggerType, null);
    }

    public MiningModel trainModel(Long modelId, String triggerType, String inputFilter) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);

        if (ModelStatus.TRAINING.equals(model.getStatus())) {
            throw new IllegalStateException("模型正在训练中，请等待完成: " + model.getName());
        }

        // Guard: temporal validation mode requires temporal column
        if ("temporal".equals(model.getValidationMode()) &&
            (model.getTemporalColumn() == null || model.getTemporalColumn().isBlank())) {
            throw new IllegalStateException("时间外验证模式(temporal)需要指定时间列(temporal_column)。请先设置 temporal_column，例如: update model set temporal_column = 'created_at'");
        }
        if (model.getSourceTable() == null || model.getSourceTable().isBlank()) {
            throw new IllegalStateException("模型缺少源表(source_table)。请指定数据表，例如: source_table = 'loan'");
        }

        log.info("[MINING] Starting training for model '{}' (id={}, algo={}, type={})",
            model.getName(), modelId, model.getAlgorithm(), model.getModelType());

        boolean acquired;
        try {
            acquired = trainingSemaphore.tryAcquire(trainingQueueTimeoutMinutes, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("训练等待被中断");
        }
        if (!acquired) {
            throw new RuntimeException("训练队列已满，请稍后重试（当前最多 " + maxConcurrentTraining + " 个并发训练）");
        }

        // DB-level atomic status check: only proceed if status is not TRAINING
        int updated = miningModelMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                .eq(MiningModel::getId, modelId)
                .ne(MiningModel::getStatus, ModelStatus.TRAINING)
                .set(MiningModel::getStatus, ModelStatus.TRAINING));
        if (updated == 0) {
            trainingSemaphore.release();
            throw new IllegalStateException("模型正在训练中，请等待完成: " + model.getName());
        }
        // Re-read after CAS to get latest field values
        model = miningModelMapper.selectById(modelId);

        ModelExecution execution = new ModelExecution();
        execution.setModelId(modelId);
        execution.setTriggerType(triggerType != null ? triggerType : "manual");
        execution.setStatus(ModelStatus.EXEC_RUNNING);
        execution.setHyperparameters(model.getHyperparameters());
        modelExecutionMapper.insert(execution);

        logMiningEvent(model, "mining_training_start", Map.of(
            "algorithm", model.getAlgorithm(), "modelType", model.getModelType(),
            "sourceTable", model.getSourceTable(), "version", model.getVersion(),
            "queueAvailable", trainingSemaphore.availablePermits()
        ));

        try {
            String pythonCode = buildTrainingScript(model, inputFilter);
            Long dsId = model.getDataSourceId();
            PythonResult result = DiagnosticsTimer.timedSupply("mining.train", () -> pythonExecutor.execute(pythonCode, dsId, pythonTimeoutMs));

            execution.setExecutionTimeMs(result.executionTimeMs());
            execution.setExecutionLog(truncateLog(result.stdout(), logTruncation));

            if (result.exitCode() == 0) {
                Map<String, Object> parsed = parseTrainingOutput(result.stdout());
                execution.setMetrics(toJson(parsed.get("metrics")));
                execution.setStatus(ModelStatus.EXEC_SUCCESS);

                model.setStatus(ModelStatus.TRAINED);
                model.setVersion(model.getVersion() + 1);
                model.setMetrics(toJson(parsed.get("metrics")));
                model.setFeatureImportance(toJson(parsed.get("feature_importance")));
                model.setTrainingLog(truncateLog(result.stdout(), trainingLogTruncation));
                if (parsed.get("model_path") != null) model.setModelPath(String.valueOf(parsed.get("model_path")));
                if (parsed.get("validation") != null) model.setValidationMetrics(toJson(parsed.get("validation")));

                if (model.getPipelineId() == null) {
                    MiningPipeline autoPipeline = createAutoPipeline(model);
                    model.setPipelineId(autoPipeline.getId());
                }

                log.info("[MINING] Training succeeded for model '{}' v{}: metrics={}",
                    model.getName(), model.getVersion(), model.getMetrics());

                logMiningEvent(model, "mining_training_complete", Map.of(
                    "status", ModelStatus.EXEC_SUCCESS, "version", model.getVersion(),
                    "durationMs", result.executionTimeMs(),
                    "metrics", parsed.get("metrics") != null ? parsed.get("metrics") : Map.of(),
                    "featureImportance", parsed.get("feature_importance") != null ? parsed.get("feature_importance") : Map.of(),
                    "validation", parsed.get("validation") != null ? parsed.get("validation") : Map.of()
                ));
            } else {
                String errorDetail = result.stderr().isBlank() ? result.stdout() : result.stderr();
                execution.setStatus(ModelStatus.FAILED);
                execution.setExecutionLog(truncateLog(errorDetail, logTruncation));
                model.setStatus(ModelStatus.FAILED);
                model.setTrainingLog(truncateLog(errorDetail, trainingLogTruncation));
                log.error("[MINING] Training failed for model '{}': exit={}, error={}",
                    model.getName(), result.exitCode(), truncateLog(errorDetail, errorSummaryTruncation));

                logMiningEvent(model, "mining_training_complete", Map.of(
                    "status", ModelStatus.FAILED, "exitCode", result.exitCode(),
                    "error", truncateLog(errorDetail, errorSummaryTruncation)
                ));
            }

            model.setLastRunAt(LocalDateTime.now());
            // Use atomic update to avoid overwriting stale fields
            var updateWrapper = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                    .eq(MiningModel::getId, modelId)
                    .eq(MiningModel::getStatus, ModelStatus.TRAINING)
                    .set(MiningModel::getStatus, model.getStatus())
                    .set(MiningModel::getVersion, model.getVersion())
                    .set(MiningModel::getMetrics, model.getMetrics())
                    .set(MiningModel::getFeatureImportance, model.getFeatureImportance())
                    .set(MiningModel::getModelPath, model.getModelPath())
                    .set(MiningModel::getTrainingLog, truncateLog(model.getTrainingLog(), trainingLogTruncation))
                    .set(MiningModel::getHyperparameters, model.getHyperparameters())
                    .set(MiningModel::getPreprocessing, model.getPreprocessing())
                    .set(MiningModel::getValidationMetrics, model.getValidationMetrics())
                    .set(MiningModel::getLastRunAt, model.getLastRunAt());
            if (model.getPipelineId() != null) {
                updateWrapper.set(MiningModel::getPipelineId, model.getPipelineId());
            }
            miningModelMapper.update(null, updateWrapper);
            modelExecutionMapper.updateById(execution);
            return miningModelMapper.selectById(modelId);
        } catch (Exception e) {
            log.error("[MINING] Training exception for model {}: {}", modelId, e.getMessage(), e);
            logMiningError(model.getConversationId(), "training_failed", modelId, e.getMessage());
            execution.setStatus(ModelStatus.FAILED);
            execution.setExecutionLog(e.getMessage());
            modelExecutionMapper.updateById(execution);
            miningModelMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                    .eq(MiningModel::getId, modelId)
                    .set(MiningModel::getStatus, ModelStatus.FAILED)
                    .set(MiningModel::getTrainingLog, e.getMessage()));
            return miningModelMapper.selectById(modelId);
        } finally {
            trainingSemaphore.release();
        }
    }

    // ======================== Lifecycle ========================

    public MiningModel publishModel(Long modelId, Map<String, Object> config) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);
        if (!ModelStatus.TRAINED.equals(model.getStatus()) && !ModelStatus.OFFLINE.equals(model.getStatus())) {
            throw new IllegalStateException("只有训练完成或已下线的模型才能发布，当前状态: " + model.getStatus());
        }
        if (model.getModelPath() == null || model.getModelPath().isBlank()) {
            throw new IllegalStateException("模型文件不存在，请先训练模型");
        }

        // 发布前验证检查: 必须经过样本外/时间外验证
        if (model.getValidationMode() == null || "none".equals(model.getValidationMode())) {
            throw new IllegalStateException(
                "模型尚未进行样本外验证。请先设置验证模式 (validation_mode: cv/oos/temporal) 并重新训练，确保模型泛化能力。\n" +
                "操作: 1) update 设置 validation_mode  2) train 重新训练  3) 确认指标达标后再发布");
        }
        if (model.getMetrics() != null && !model.getMetrics().isBlank()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> metrics = om.readValue(model.getMetrics(), Map.class);
                Object overfittingGap = metrics.get("overfitting_gap");
                boolean force = config != null && Boolean.TRUE.equals(config.get("force"));
                if (overfittingGap instanceof Number gap && gap.doubleValue() > overfittingGapThreshold && !force) {
                    throw new IllegalStateException(
                        String.format("过拟合风险过高 (gap=%.2f > %.2f)。建议: 减少特征、增加正则化或收集更多数据后再发布。可传 force=true 强制发布。",
                            gap.doubleValue(), overfittingGapThreshold));
                }
            } catch (IllegalStateException e) { throw e; }
            catch (Exception e) {
                log.warn("[MINING] Failed to parse metrics for overfitting check on model {}: {}", modelId, e.getMessage());
            }
        }

        model.setStatus(ModelStatus.PUBLISHED);
        log.info("[MINING] Model '{}' (id={}) published at version {}", model.getName(), modelId, model.getVersion());

        if (config != null) {
            Object inputTable = config.getOrDefault("predictInputTable", config.getOrDefault("input_table", config.get("inputTable")));
            if (inputTable != null) { String tbl = (String) inputTable; if (!tbl.isBlank()) validateTableName(tbl); model.setPredictInputTable(tbl); }
        }
        // Fallback: if no predictInputTable set, use sourceTable
        if (model.getPredictInputTable() == null || model.getPredictInputTable().isBlank()) {
            model.setPredictInputTable(model.getSourceTable());
        }
        if (config != null) {
            if (config.containsKey("predictInputFilter") || config.containsKey("input_filter"))
                model.setPredictInputFilter((String) config.getOrDefault("predictInputFilter", config.get("input_filter")));
            Object resultTable = config.getOrDefault("predictResultTable", config.getOrDefault("result_table", config.get("resultTable")));
            if (resultTable != null) { String tbl = (String) resultTable; if (!tbl.isBlank()) validateTableName(tbl); model.setPredictResultTable(tbl); }
            String cron = (String) config.getOrDefault("scheduleCron", config.get("schedule_cron"));
            if (cron != null) model.setScheduleCron(cron);
            if (config.containsKey("scheduleEnabled") || config.containsKey("schedule_enabled"))
                model.setScheduleEnabled(Boolean.TRUE.equals(config.getOrDefault("scheduleEnabled", config.get("schedule_enabled"))));
            String mode = (String) config.getOrDefault("scheduleMode", config.get("schedule_mode"));
            if (mode != null) model.setScheduleMode(mode);
        }

        if (Boolean.TRUE.equals(model.getScheduleEnabled()) && model.getScheduleCron() != null) {
            try {
                java.time.LocalDateTime nextRun = computeNextRun(model.getScheduleCron());
                model.setNextRunAt(nextRun);
                log.info("[MINING] Schedule set: cron='{}', nextRun={}", model.getScheduleCron(), nextRun);
            } catch (Exception e) {
                log.warn("[MINING] Failed to parse cron '{}': {}", model.getScheduleCron(), e.getMessage());
                model.setNextRunAt(LocalDateTime.now().plusMinutes(defaultCronMinutes));
            }
        }

        int updated = miningModelMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                .eq(MiningModel::getId, modelId)
                .in(MiningModel::getStatus, ModelStatus.TRAINED, ModelStatus.OFFLINE)
                .set(MiningModel::getStatus, ModelStatus.PUBLISHED)
                .set(MiningModel::getPredictInputTable, model.getPredictInputTable())
                .set(MiningModel::getPredictInputFilter, model.getPredictInputFilter())
                .set(MiningModel::getPredictResultTable, model.getPredictResultTable())
                .set(MiningModel::getScheduleCron, model.getScheduleCron())
                .set(MiningModel::getScheduleEnabled, model.getScheduleEnabled())
                .set(MiningModel::getScheduleMode, model.getScheduleMode())
                .set(MiningModel::getNextRunAt, model.getNextRunAt()));
        if (updated == 0) throw new IllegalStateException("发布失败，模型状态已变更，请重试");
        if (model.getPipelineId() != null) {
            try { syncModelToPipeline(model.getPipelineId(), model); } catch (Exception e) {
                log.warn("[MINING] Failed to sync pipeline after publish: {}", e.getMessage());
            }
        }
        logMiningEvent(model, "model_published", Map.of(
            "version", model.getVersion(),
            "scheduleEnabled", model.getScheduleEnabled(),
            "predictInputTable", model.getPredictInputTable() != null ? model.getPredictInputTable() : "",
            "predictResultTable", model.getPredictResultTable() != null ? model.getPredictResultTable() : ""
        ));
        return model;
    }

    public MiningModel offlineModel(Long modelId) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);
        int updated = miningModelMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                .eq(MiningModel::getId, modelId)
                .eq(MiningModel::getStatus, ModelStatus.PUBLISHED)
                .set(MiningModel::getStatus, ModelStatus.OFFLINE)
                .set(MiningModel::getScheduleEnabled, false));
        if (updated == 0) throw new IllegalStateException("下线失败，模型状态已变更，请重试");
        model.setStatus(ModelStatus.OFFLINE);
        model.setScheduleEnabled(false);
        log.info("[MINING] Model '{}' (id={}) taken offline", model.getName(), modelId);
        return model;
    }

    public MiningModel updateHyperparameters(Long modelId, String hyperparametersJson) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);
        if (ModelStatus.PUBLISHED.equals(model.getStatus())) {
            throw new IllegalStateException("已发布的模型不能修改超参数，请先下线");
        }
        if (hyperparametersJson == null || "null".equals(hyperparametersJson) || hyperparametersJson.isBlank()) {
            hyperparametersJson = "{}";
        }
        model.setHyperparameters(hyperparametersJson);
        miningModelMapper.updateById(model);

        if (model.getPipelineId() != null) {
            syncModelToPipeline(model.getPipelineId(), model);
        }
        return model;
    }

    public void updateSchedule(Long modelId, String cron, Boolean enabled, String mode) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);
        if (cron != null) model.setScheduleCron(cron);
        if (enabled != null) model.setScheduleEnabled(enabled);
        if (mode != null) model.setScheduleMode(mode);
        if (Boolean.TRUE.equals(model.getScheduleEnabled()) && model.getScheduleCron() != null) {
            try {
                long minutes = model.getScheduleCron().startsWith("*/") ? Long.parseLong(model.getScheduleCron().substring(2)) : defaultCronMinutes;
                model.setNextRunAt(LocalDateTime.now().plusMinutes(minutes));
            } catch (Exception ignored) {}
        }
        miningModelMapper.updateById(model);
    }

    // ======================== Delegation ========================

    public Map<String, Object> predictModel(Long modelId, List<Map<String, Object>> inputRows, String saveTable) {
        return predictionService.predict(modelId, inputRows, saveTable);
    }

    public Map<String, Object> batchPredict(Long modelId) {
        return predictionService.batchPredict(modelId);
    }

    public Map<String, Object> batchPredict(Long modelId, String resultTable) {
        return predictionService.batchPredict(modelId, resultTable);
    }

    public Map<String, Object> batchPredictWithOverrides(Long modelId, String inputTable, String resultTable, String inputFilter) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);
        // Apply overrides to a transient copy — do NOT persist to model entity
        if (inputTable != null && !inputTable.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateTableName(inputTable);
        }
        if (resultTable != null && !resultTable.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateTableName(resultTable);
        }
        if (inputFilter != null && !inputFilter.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateFilter(inputFilter);
        }
        return predictionService.batchPredictWithConfig(modelId, inputTable, resultTable, inputFilter);
    }

    public List<PredictionResult> getPredictionResults(Long modelId, int limit) {
        return predictionService.getPredictionResults(modelId, limit);
    }

    public Map<String, Object> executePipeline(Long pipelineId) {
        return pipelineService.executePipeline(pipelineId);
    }

    public Executor getMiningExecutor() {
        return miningExecutor;
    }

    public void syncPipelineToModel(Long pipelineId) {
        MiningPipeline pipeline = miningPipelineMapper.selectById(pipelineId);
        if (pipeline == null) return;

        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MiningModel>()
            .eq(MiningModel::getPipelineId, pipelineId)
            .eq(MiningModel::getDeleted, 0);
        MiningModel model = miningModelMapper.selectOne(wrapper);
        if (model == null) return;

        try {
            PipelineService.PipelineConfig cfg = pipelineService.extractConfigFromNodes(pipeline.getNodes());
            if (cfg == null) return;

            if (cfg.sourceTable() != null && !cfg.sourceTable().isBlank()) model.setSourceTable(cfg.sourceTable());
            if (cfg.featureColumns() != null && !cfg.featureColumns().isEmpty())
                model.setFeatureColumns(toJson(cfg.featureColumns()));
            if (cfg.targetColumn() != null && !cfg.targetColumn().isBlank()) model.setTargetColumn(cfg.targetColumn());
            if (cfg.modelType() != null) model.setModelType(cfg.modelType());
            if (cfg.algorithm() != null) model.setAlgorithm(cfg.algorithm());
            if (!cfg.hyperparams().isEmpty()) model.setHyperparameters(toJson(cfg.hyperparams()));
            if (!cfg.preprocessing().isEmpty()) model.setPreprocessing(toJson(cfg.preprocessing()));
            if (cfg.transforms() != null && !cfg.transforms().isEmpty()) {
                Map<String, Object> pp = parseJsonMap(model.getPreprocessing());
                pp.put("transforms", cfg.transforms());
                model.setPreprocessing(toJson(pp));
            }
            if (cfg.validationMode() != null) model.setValidationMode(cfg.validationMode());
            if (cfg.cvFold() > 0) model.setCvFolds(cfg.cvFold());
            if (cfg.testSize() > 0) model.setTestSize(cfg.testSize());
            if (cfg.temporalColumn() != null) model.setTemporalColumn(cfg.temporalColumn());

            LocalDateTime now = LocalDateTime.now();
            model.setLastSyncedAt(now);
            miningModelMapper.updateById(model);

            pipeline.setLastSyncedAt(now);
            miningPipelineMapper.updateById(pipeline);
        } catch (Exception e) {
            log.warn("[MINING] Failed to sync pipeline to model for pipeline {}: {}", pipelineId, e.getMessage());
        }
    }

    public MiningModel rollbackToExecution(Long modelId, Long executionId) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            throw new IllegalArgumentException("模型不存在: " + modelId);
        }

        ModelExecution execution = modelExecutionMapper.selectById(executionId);
        if (execution == null || !execution.getModelId().equals(modelId)) {
            throw new IllegalArgumentException("执行记录不存在或不属于该模型: " + executionId);
        }
        if (!ModelStatus.EXEC_SUCCESS.equals(execution.getStatus())) {
            throw new IllegalArgumentException("只能回滚到成功的执行记录");
        }

        if (execution.getHyperparameters() != null) {
            model.setHyperparameters(execution.getHyperparameters());
        }
        if (execution.getMetrics() != null) {
            model.setMetrics(execution.getMetrics());
        }
        model.setStatus(ModelStatus.TRAINED);
        miningModelMapper.updateById(model);

        if (model.getPipelineId() != null) {
            try { syncModelToPipeline(model.getPipelineId(), model); } catch (Exception e) {
                log.warn("[MINING] Failed to sync after rollback for model {}: {}", modelId, e.getMessage());
            }
        }

        log.info("[MINING] Model {} rolled back to execution {}, version={}", modelId, executionId, model.getVersion());
        return model;
    }

    // ======================== Training Script Generation ========================

    private String buildTrainingScript(MiningModel model, String inputFilter) {
        com.smartquery.common.IdentifierValidator.validateTableName(model.getSourceTable());
        if (model.getTargetColumn() != null) com.smartquery.common.IdentifierValidator.validateColumnName(model.getTargetColumn());
        if (model.getTemporalColumn() != null) com.smartquery.common.IdentifierValidator.validateColumnName(model.getTemporalColumn());

        DataSource ds = dataSourceMapper.selectById(model.getDataSourceId());
        String dbUrl = ds != null ? DbUrlUtil.buildSqlalchemyUrl(ds) : "";

        String resolvedFilter = inputFilter;
        if (resolvedFilter == null && model.getPredictInputFilter() != null && !model.getPredictInputFilter().isBlank()) {
            resolvedFilter = model.getPredictInputFilter();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("import pandas as pd\nimport numpy as np\nimport json\nimport os\nimport warnings\nwarnings.filterwarnings('ignore', category=FutureWarning)\nfrom sqlalchemy import create_engine\nimport joblib\n\n");
        sb.append("engine = create_engine('").append(dbUrl).append("')\n");
        String sqlQuery = "SELECT * FROM `" + model.getSourceTable() + "`";
        if (resolvedFilter != null && !resolvedFilter.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateFilter(resolvedFilter);
            String resolved = resolvedFilter.replace("${etl_date}", java.time.LocalDate.now().toString());
            sqlQuery += " WHERE " + resolved;
        }
        sb.append("df = pd.read_sql('").append(sqlQuery).append("', engine)\n");
        sb.append("print(f'[INFO] Loaded {len(df)} rows, {len(df.columns)} columns')\n\n");

        sb.append("preprocessing = ").append(safeJsonEmbed(model.getPreprocessing())).append("\n");
        sb.append("_hm = preprocessing.get('handleMissing', 'drop')\n");
        sb.append("_col_strats = preprocessing.get('columnStrategies', {})\n");
        sb.append("if _col_strats:\n");
        sb.append("    for _col, _strat in _col_strats.items():\n");
        sb.append("        if _strat == 'inherit' or _strat == 'none' or _col not in df.columns: continue\n");
        sb.append("        if _strat == 'drop': df = df.dropna(subset=[_col])\n");
        sb.append("        elif _strat == 'fill_mean':\n");
        sb.append("            if df[_col].dtype in ['float64','int64','int32']: df[_col] = df[_col].fillna(df[_col].mean())\n");
        sb.append("        elif _strat == 'fill_median':\n");
        sb.append("            if df[_col].dtype in ['float64','int64','int32']: df[_col] = df[_col].fillna(df[_col].median())\n");
        sb.append("        elif _strat == 'fill_mode':\n");
        sb.append("            _m = df[_col].mode(); df[_col] = df[_col].fillna(_m.iloc[0] if len(_m) > 0 else None)\n");
        sb.append("    _exc = set(_col_strats.keys())\n");
        sb.append("    if _hm == 'drop':\n");
        sb.append("        for c in [c for c in df.columns if c not in _exc and df[c].isnull().any()]: df = df.dropna(subset=[c])\n");
        sb.append("    elif _hm == 'fill_mean':\n");
        sb.append("        for c in df.columns:\n");
        sb.append("            if c not in _exc and df[c].isnull().any():\n");
        sb.append("                if df[c].dtype in ['float64','int64','int32']: df[c] = df[c].fillna(df[c].mean())\n");
        sb.append("                else: df[c] = df[c].fillna(df[c].mode().iloc[0] if len(df[c].mode()) > 0 else 'unknown')\n");
        sb.append("    elif _hm == 'fill_median':\n");
        sb.append("        for c in df.columns:\n");
        sb.append("            if c not in _exc and df[c].isnull().any():\n");
        sb.append("                if df[c].dtype in ['float64','int64','int32']: df[c] = df[c].fillna(df[c].median())\n");
        sb.append("                else: df[c] = df[c].fillna(df[c].mode().iloc[0] if len(df[c].mode()) > 0 else 'unknown')\n");
        sb.append("    df = df.dropna()\n");
        sb.append("else:\n");
        sb.append("    if _hm == 'drop': df = df.dropna()\n");
        sb.append("    elif _hm == 'fill_mean':\n");
        sb.append("        for c in df.select_dtypes(include=['number']).columns: df[c] = df[c].fillna(df[c].mean())\n");
        sb.append("        df = df.dropna()\n");
        sb.append("    elif _hm == 'fill_median':\n");
        sb.append("        for c in df.select_dtypes(include=['number']).columns: df[c] = df[c].fillna(df[c].median())\n");
        sb.append("        df = df.dropna()\n");
        sb.append("print(f'[INFO] After handleMissing({_hm}): {len(df)} rows')\n\n");

        sb.append("_fc_raw = ").append(model.getFeatureColumns()).append("\nif isinstance(_fc_raw, str): feature_cols = [c.strip() for c in _fc_raw.split(',') if c.strip()]\nelse: feature_cols = list(_fc_raw)\n");
        sb.append("X = df[feature_cols].copy()\n");
        if (model.getTargetColumn() != null && !model.getTargetColumn().isBlank()) {
            sb.append("y = df['").append(model.getTargetColumn()).append("']\n_y_le = None\n");
            sb.append("if y.dtype == 'object':\n    from sklearn.preprocessing import LabelEncoder\n    _y_le = LabelEncoder(); y = pd.Series(_y_le.fit_transform(y.astype(str)), index=y.index)\n    print(f'[INFO] Encoded target column with {len(_y_le.classes_)} classes: {list(_y_le.classes_)}')\n");
        } else {
            sb.append("y = None\n");
        }
        sb.append("\n");

        sb.append("_enc = preprocessing.get('encoding', 'label')\n_dt_cols = X.select_dtypes(include=['datetime', 'datetimetz']).columns.tolist()\n");
        sb.append("if _dt_cols:\n    for c in _dt_cols: X[c] = pd.to_numeric(X[c].astype('int64'), errors='coerce')\n    print(f'[INFO] Converted datetime columns: {_dt_cols}')\n");
        sb.append("cat_cols = X.select_dtypes(include=['object']).columns.tolist()\n_encoders = {}\nif cat_cols:\n");
        sb.append("    if _enc == 'onehot': X = pd.get_dummies(X, columns=cat_cols); _encoders['_onehot_columns'] = list(X.columns)\n");
        sb.append("    else:\n        from sklearn.preprocessing import LabelEncoder\n        for c in cat_cols: _le = LabelEncoder(); X[c] = _le.fit_transform(X[c].astype(str)); _encoders[c] = _le\n\n");

        sb.append("_scaler = None\n_sc = preprocessing.get('scaling', 'none')\nnum_cols = X.select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("if _sc == 'standard' and num_cols:\n    from sklearn.preprocessing import StandardScaler\n    _scaler = StandardScaler(); X[num_cols] = X[num_cols].astype(float); X[num_cols] = _scaler.fit_transform(X[num_cols])\n");
        sb.append("elif _sc == 'minmax' and num_cols:\n    from sklearn.preprocessing import MinMaxScaler\n    _scaler = MinMaxScaler(); X[num_cols] = X[num_cols].astype(float); X[num_cols] = _scaler.fit_transform(X[num_cols])\n\n");

        // Feature transforms (log, binning, polynomial, etc.)
        sb.append("_transforms = preprocessing.get('transforms', [])\n");
        sb.append("for _t in _transforms:\n");
        sb.append("    _ttype = _t.get('type', '')\n");
        sb.append("    _tcols = [c for c in _t.get('columns', []) if c in X.columns]\n");
        sb.append("    if not _tcols: continue\n");
        sb.append("    if _ttype == 'log':\n");
        sb.append("        for c in _tcols: X[c] = np.log1p(X[c].clip(lower=0))\n");
        sb.append("        print(f'[INFO] Applied log transform to {_tcols}')\n");
        sb.append("    elif _ttype == 'binning':\n");
        sb.append("        _bins = _t.get('bins', 5)\n");
        sb.append("        for c in _tcols: X[c] = pd.cut(X[c], bins=_bins, labels=False, duplicates='drop')\n");
        sb.append("        print(f'[INFO] Applied binning ({_bins} bins) to {_tcols}')\n");
        sb.append("    elif _ttype == 'polynomial':\n");
        sb.append("        from sklearn.preprocessing import PolynomialFeatures\n");
        sb.append("        _deg = _t.get('degree', 2)\n");
        sb.append("        _pf = PolynomialFeatures(degree=_deg, include_bias=False)\n");
        sb.append("        _pf_data = _pf.fit_transform(X[_tcols].fillna(0))\n");
        sb.append("        _pf_names = [f'{c}_poly{i}' for i, c in enumerate(_pf.get_feature_names_out(_tcols))]\n");
        sb.append("        X = X.drop(columns=_tcols)\n");
        sb.append("        for i, n in enumerate(_pf_names): X[n] = _pf_data[:, i]\n");
        sb.append("        print(f'[INFO] Applied polynomial (degree={_deg}) to {_tcols}, generated {len(_pf_names)} features')\n");
        sb.append("    elif _ttype == 'date_extract':\n");
        sb.append("        for c in _tcols:\n");
        sb.append("            _ds = pd.to_datetime(df[c], errors='coerce')\n");
        sb.append("            X[f'{c}_year'] = _ds.dt.year\n");
        sb.append("            X[f'{c}_month'] = _ds.dt.month\n");
        sb.append("            X[f'{c}_day'] = _ds.dt.day\n");
        sb.append("            X[f'{c}_dow'] = _ds.dt.dayofweek\n");
        sb.append("            X = X.drop(columns=[c])\n");
        sb.append("        print(f'[INFO] Extracted date features from {_tcols}')\n");
        sb.append("    elif _ttype == 'interaction':\n");
        sb.append("        import itertools\n");
        sb.append("        for c1, c2 in itertools.combinations(_tcols, 2):\n");
        sb.append("            X[f'{c1}_x_{c2}'] = X[c1].fillna(0) * X[c2].fillna(0)\n");
        sb.append("        print(f'[INFO] Created interaction features from {_tcols}')\n");
        sb.append("    elif _ttype == 'target_encode' and y is not None:\n");
        sb.append("        for c in _tcols:\n");
        sb.append("            _te_map = y.groupby(X[c]).mean()\n");
        sb.append("            X[c] = X[c].map(_te_map).fillna(y.mean())\n");
        sb.append("        print(f'[INFO] Applied target encoding to {_tcols}')\n");
        sb.append("    elif _ttype == 'frequency_encode':\n");
        sb.append("        for c in _tcols:\n");
        sb.append("            _freq = X[c].value_counts(normalize=True)\n");
        sb.append("            X[c] = X[c].map(_freq).fillna(0)\n");
        sb.append("        print(f'[INFO] Applied frequency encoding to {_tcols}')\n");
        sb.append("\n");

        String hyperparams = model.getHyperparameters();
        if (hyperparams == null || "null".equals(hyperparams) || hyperparams.isBlank()) hyperparams = "{}";
        sb.append("params = ").append(safeJsonEmbed(hyperparams)).append("\n_model_type = '").append(model.getModelType()).append("'\n\n");
        sb.append(buildAlgorithmBlock(model.getAlgorithm()));

        sb.append("\nfrom sklearn.model_selection import train_test_split, cross_val_score\n");
        sb.append("_val_mode = '").append(validationMode(model)).append("'\n");
        sb.append("_cv_folds = ").append(model.getCvFolds() != null ? model.getCvFolds() : 5).append("\n");
        sb.append("_test_size = ").append(model.getTestSize() != null ? model.getTestSize() : 0.2).append("\n\n");

        sb.append("if y is not None:\n");
        // Temporal validation: sort by time column first, then split sequentially
        if ("temporal".equals(validationMode(model)) && model.getTemporalColumn() != null && !model.getTemporalColumn().isBlank()) {
            sb.append("    _tcol = '").append(model.getTemporalColumn()).append("'\n");
            sb.append("    _sort_idx = df.sort_values(_tcol).index\n");
            sb.append("    X = X.loc[_sort_idx].reset_index(drop=True)\n    y = y.loc[_sort_idx].reset_index(drop=True)\n");
            sb.append("    _split_idx = int(len(X) * (1 - _test_size))\n    X_train, X_test = X.iloc[:_split_idx], X.iloc[_split_idx:]\n    y_train, y_test = y.iloc[:_split_idx], y.iloc[_split_idx:]\n");
            sb.append("    print(f'[INFO] Temporal split: train={len(X_train)}, test={len(X_test)}, sorted by {_tcol}')\n");
        } else {
            sb.append("    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=_test_size, random_state=").append(randomState).append(")\n");
        }
        sb.append("else:\n    X_train = X; X_test = None; y_train = None; y_test = None\n\n");
        sb.append("clf.fit(X_train, y_train if y is not None else X_train)\n\n");

        sb.append("import sklearn.metrics as metrics\nresult = {}\nif y_test is not None:\n    y_pred = clf.predict(X_test)\n");
        sb.append("    y_train_pred = clf.predict(X_train)\n");
        sb.append("    if 'classification' in '").append(model.getModelType()).append("':\n");
        sb.append("        result['test_accuracy'] = round(metrics.accuracy_score(y_test, y_pred), 4)\n        result['test_precision'] = round(metrics.precision_score(y_test, y_pred, average='weighted', zero_division=0), 4)\n");
        sb.append("        result['test_recall'] = round(metrics.recall_score(y_test, y_pred, average='weighted', zero_division=0), 4)\n        result['test_f1'] = round(metrics.f1_score(y_test, y_pred, average='weighted', zero_division=0), 4)\n");
        sb.append("        result['train_accuracy'] = round(metrics.accuracy_score(y_train, y_train_pred), 4)\n");
        sb.append("        _gap = round(abs(result['train_accuracy'] - result['test_accuracy']), 4)\n        result['overfitting_gap'] = _gap\n");
        sb.append("        if _gap > " + overfittingGapThreshold + ": result['overfitting_warning'] = f'训练精度({result[\"train_accuracy\"]})与测试精度({result[\"test_accuracy\"]})差值{_gap}>" + overfittingGapThreshold + "，可能过拟合'\n");
        sb.append("        try:\n            _cm = metrics.confusion_matrix(y_test, y_pred)\n            result['confusion_matrix'] = _cm.tolist()\n");
        sb.append("            _cls = sorted(set(list(y_test.astype(str)) + list(y_pred.astype(str))))\n            result['class_labels'] = _cls\n        except: pass\n");
        sb.append("    else:\n");
        sb.append("        result['test_mse'] = round(metrics.mean_squared_error(y_test, y_pred), 4)\n        result['test_rmse'] = round(np.sqrt(metrics.mean_squared_error(y_test, y_pred)), 4)\n        result['test_r2'] = round(metrics.r2_score(y_test, y_pred), 4)\n");
        sb.append("        result['test_mae'] = round(metrics.mean_absolute_error(y_test, y_pred), 4)\n");
        sb.append("        result['train_r2'] = round(metrics.r2_score(y_train, y_train_pred), 4)\n");
        sb.append("        _gap = round(abs(result['train_r2'] - result['test_r2']), 4)\n        result['overfitting_gap'] = _gap\n");
        sb.append("        if _gap > " + overfittingGapThreshold + ": result['overfitting_warning'] = f'训练R²({result[\"train_r2\"]})与测试R²({result[\"test_r2\"]})差值{_gap}>" + overfittingGapThreshold + "，可能过拟合'\n");
        sb.append("else:\n    result['inertia'] = getattr(clf, 'inertia_', None)\n    _labels = getattr(clf, 'labels_', clf.predict(X)) if hasattr(clf, 'predict') else getattr(clf, 'labels_', None)\n");
        sb.append("    if _labels is not None:\n        result['n_clusters'] = len(set(_labels))\n        _uk, _uv = np.unique(_labels, return_counts=True)\n        result['cluster_sizes'] = {int(k): int(v) for k, v in zip(_uk, _uv)}\n");
        sb.append("        try: result['silhouette_score'] = round(float(metrics.silhouette_score(X, _labels, sample_size=min(5000, len(X)))), 4)\n        except: pass\n\n");

        // Sample size warning
        sb.append("if len(df) < 100: result['sample_warning'] = f'样本量仅{len(df)}行，模型结果可能不可靠，建议至少200行以上'\n");
        sb.append("if y is not None and 'classification' in '").append(model.getModelType()).append("':\n");
        sb.append("    _counts = y.value_counts()\n    if len(_counts) >= 2:\n        _ratio = _counts.min() / _counts.max()\n");
        sb.append("        if _ratio < 0.1: result['imbalance_warning'] = f'类别不平衡比为{round(_ratio,3)}，少数类仅{_counts.min()}条，建议增加数据或使用class_weight参数'\n\n");

        sb.append("val_result = {}\nif _val_mode == 'cv' and y is not None:\n");
        sb.append("    _cv_scoring = 'f1_weighted' if 'classification' in '").append(model.getModelType()).append("' else 'r2'\n");
        sb.append("    try:\n");
        sb.append("        from sklearn.model_selection import StratifiedKFold, KFold\n");
        sb.append("        _cv = StratifiedKFold(n_splits=_cv_folds, shuffle=True, random_state=").append(randomState).append(") if 'classification' in '").append(model.getModelType()).append("' else KFold(n_splits=_cv_folds, shuffle=True, random_state=").append(randomState).append(")\n");
        sb.append("        _cv_scores = cross_val_score(clf.__class__(**params) if params else clf.__class__(), X, y, cv=_cv, scoring=_cv_scoring)\n");
        sb.append("        val_result['cv_mean'] = round(float(_cv_scores.mean()), 4)\n        val_result['cv_std'] = round(float(_cv_scores.std()), 4)\n        val_result['cv_folds'] = _cv_folds\n");
        sb.append("    except Exception as _e: val_result['cv_error'] = str(_e)\n");
        sb.append("elif _val_mode == 'oos' and y is not None:\n");
        sb.append("    _cv_scoring = 'f1_weighted' if 'classification' in '").append(model.getModelType()).append("' else 'r2'\n");
        sb.append("    try:\n");
        sb.append("        from sklearn.model_selection import StratifiedKFold, KFold\n");
        sb.append("        _cv = StratifiedKFold(n_splits=_cv_folds, shuffle=True, random_state=").append(randomState).append(") if 'classification' in '").append(model.getModelType()).append("' else KFold(n_splits=_cv_folds, shuffle=True, random_state=").append(randomState).append(")\n");
        sb.append("        _cv_scores = cross_val_score(clf.__class__(**params) if params else clf.__class__(), X_train, y_train, cv=_cv, scoring=_cv_scoring)\n");
        sb.append("        val_result['cv_mean'] = round(float(_cv_scores.mean()), 4)\n        val_result['cv_std'] = round(float(_cv_scores.std()), 4)\n        val_result['cv_folds'] = _cv_folds\n");
        sb.append("        val_result['oos_train_size'] = len(X_train)\n        val_result['oos_test_size'] = len(X_test)\n");
        sb.append("        val_result['oos_test_accuracy'] = result.get('test_accuracy')\n        val_result['oos_test_f1'] = result.get('test_f1')\n");
        sb.append("        val_result['oos_test_r2'] = result.get('test_r2')\n        val_result['oos_overfitting_gap'] = result.get('overfitting_gap')\n");
        sb.append("        if result.get('overfitting_warning'): val_result['oos_overfitting_warning'] = result['overfitting_warning']\n");
        sb.append("    except Exception as _e: val_result['cv_error'] = str(_e)\n");
        sb.append("elif _val_mode == 'temporal' and y_test is not None:\n    val_result['temporal_split'] = 'train={}/test={}'.format(len(X_train), len(X_test))\n    val_result['temporal_test_accuracy'] = result.get('test_accuracy')\n    val_result['temporal_test_f1'] = result.get('test_f1')\n\n");

        sb.append("fi = {}\nif hasattr(clf, 'feature_importances_'): fi = dict(zip(X_train.columns, [round(float(v), 4) for v in clf.feature_importances_]))\n");
        sb.append("elif hasattr(clf, 'coef_'): fi = dict(zip(X_train.columns, [round(float(v), 4) for v in clf.coef_.flatten()]))\n\n");

        sb.append("_workspace = r'").append(modelWorkspace).append("'\nos.makedirs(_workspace, exist_ok=True)\n");
        sb.append("_model_path = os.path.join(_workspace, 'model_").append(model.getId()).append("_v' + clf.__class__.__name__ + '.pkl')\n");
        sb.append("joblib.dump(clf, _model_path)\n");
        sb.append("_preproc_path = os.path.join(_workspace, 'model_").append(model.getId()).append("_preprocessors.pkl')\n");
        sb.append("joblib.dump({'encoders': _encoders, 'scaler': _scaler, 'target_encoder': _y_le, 'feature_cols': list(X_train.columns), 'encoding': _enc, 'scaling': _sc}, _preproc_path)\n\n");

        sb.append("print('[TRAIN_RESULT] ' + json.dumps({'metrics': result, 'feature_importance': fi, 'model_path': _model_path, 'validation': val_result}))\n");
        return sb.toString();
    }

    private String buildAlgorithmBlock(String algorithmId) {
        Algorithm algo = algorithmService.getByAlgorithmId(algorithmId);
        if (algo != null && algo.getPythonCodeTemplate() != null && !algo.getPythonCodeTemplate().isBlank()) {
            return algo.getPythonCodeTemplate();
        }
        throw new IllegalStateException("算法配置缺失: " + algorithmId);
    }

    private MiningPipeline createAutoPipeline(MiningModel model) {
        List<String> featureCols = parseJsonList(model.getFeatureColumns());
        Map<String, Object> preprocessing = parseJsonMap(model.getPreprocessing());

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        // Node 1: Data source
        Map<String, Object> dsConfig = new LinkedHashMap<>();
        dsConfig.put("title", "数据接入");
        dsConfig.put("table", model.getSourceTable());
        dsConfig.put("filter", "");
        String dsId = "ds_" + model.getId();
        nodes.add(Map.of("id", dsId, "type", "data_source", "config", dsConfig));

        // Node 2: Preprocessing
        Map<String, Object> ppConfig = new LinkedHashMap<>();
        ppConfig.put("title", "数据预处理");
        ppConfig.put("handleMissing", preprocessing.getOrDefault("handleMissing", "drop"));
        ppConfig.put("encoding", preprocessing.getOrDefault("encoding", "label"));
        ppConfig.put("scaling", preprocessing.getOrDefault("scaling", "none"));
        String ppId = "pp_" + model.getId();
        nodes.add(Map.of("id", ppId, "type", "preprocessing", "config", ppConfig));
        edges.add(Map.of("source", dsId, "target", ppId));

        String prevId = ppId;

        // Node 3 (conditional): Fill missing — when handleMissing or fillMissing strategy is set
        String handleMissing = (String) preprocessing.getOrDefault("handleMissing", "drop");
        Object fillMissingObj = preprocessing.get("fillMissing");
        String fillStrategy = handleMissing;
        if (fillMissingObj instanceof Map<?, ?> fmMap) {
            Object strat = fmMap.get("strategy");
            if (strat instanceof String s && !s.isBlank()) fillStrategy = s;
        }
        boolean needsFillMissing = !"none".equals(fillStrategy) && !"drop".equals(fillStrategy);
        if (needsFillMissing) {
            Map<String, Object> fmConfig = new LinkedHashMap<>();
            fmConfig.put("title", "填充缺失值");
            fmConfig.put("strategy", fillStrategy.startsWith("fill_") ? fillStrategy.substring(5) : fillStrategy);
            Object fillCols = preprocessing.get("fillMissingColumns");
            if (fillCols instanceof List) fmConfig.put("columns", fillCols);
            else fmConfig.put("columns", List.of());
            String fmId = "fm_" + model.getId();
            nodes.add(Map.of("id", fmId, "type", "fill_missing", "config", fmConfig));
            edges.add(Map.of("source", prevId, "target", fmId));
            prevId = fmId;
        }

        // Node 4: Feature engineering — include transforms from preprocessing
        Map<String, Object> feConfig = new LinkedHashMap<>();
        feConfig.put("title", "特征工程");
        feConfig.put("featureColumns", featureCols);
        feConfig.put("targetColumn", model.getTargetColumn() != null ? model.getTargetColumn() : "");
        Object transforms = preprocessing.get("transforms");
        if (transforms instanceof List && !((List<?>) transforms).isEmpty()) {
            feConfig.put("transforms", transforms);
        }
        String feId = "fe_" + model.getId();
        nodes.add(Map.of("id", feId, "type", "feature_engineering", "config", feConfig));
        edges.add(Map.of("source", prevId, "target", feId));

        // Node 5: Training
        Map<String, Object> trConfig = new LinkedHashMap<>();
        trConfig.put("title", "模型训练");
        trConfig.put("modelType", model.getModelType());
        trConfig.put("algorithm", model.getAlgorithm());
        trConfig.put("hyperparams", parseJsonMap(model.getHyperparameters()));
        String trId = "tr_" + model.getId();
        nodes.add(Map.of("id", trId, "type", "training", "config", trConfig));
        edges.add(Map.of("source", feId, "target", trId));

        // Node 6: Evaluation
        Map<String, Object> evConfig = new LinkedHashMap<>();
        evConfig.put("title", "模型评估");
        evConfig.put("testSize", (int)((model.getTestSize() != null ? model.getTestSize() : 0.2) * 100));
        evConfig.put("cvFold", model.getCvFolds() != null ? model.getCvFolds() : 5);
        if (model.getValidationMode() != null) evConfig.put("validationMode", model.getValidationMode());
        if (model.getTemporalColumn() != null) evConfig.put("temporalColumn", model.getTemporalColumn());
        String evId = "ev_" + model.getId();
        nodes.add(Map.of("id", evId, "type", "evaluation", "config", evConfig));
        edges.add(Map.of("source", trId, "target", evId));

        // Node 7 (conditional): Output — only when predictResultTable is set
        if (model.getPredictResultTable() != null && !model.getPredictResultTable().isBlank()) {
            Map<String, Object> outConfig = new LinkedHashMap<>();
            outConfig.put("title", "输出写入");
            outConfig.put("table", model.getPredictResultTable());
            outConfig.put("mode", "append");
            outConfig.put("autoCreate", true);
            String outId = "out_" + model.getId();
            nodes.add(Map.of("id", outId, "type", "output", "config", outConfig));
            edges.add(Map.of("source", evId, "target", outId));
        }

        // Add output node if not present (always include for completeness)
        boolean hasOutputNode = nodes.stream().anyMatch(n -> "output".equals(n.get("type")));
        if (!hasOutputNode) {
            String lastId = nodes.get(nodes.size() - 1).get("id").toString();
            Map<String, Object> outConfig = new LinkedHashMap<>();
            outConfig.put("title", "输出写入");
            outConfig.put("table", model.getPredictResultTable() != null ? model.getPredictResultTable() : model.getSourceTable() + "_predict");
            outConfig.put("mode", "append");
            outConfig.put("autoCreate", true);
            String outId = "out_" + model.getId();
            nodes.add(Map.of("id", outId, "type", "output", "config", outConfig));
            edges.add(Map.of("source", lastId, "target", outId));
        }

        // Add position coordinates for visual layout (horizontal flow, 320px spacing)
        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> node = new LinkedHashMap<>(nodes.get(i));
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("x", 60 + i * 320);
            pos.put("y", 200);
            node.put("position", pos);
            nodes.set(i, node);
        }

        // Enrich training node with metrics if available
        if (model.getMetrics() != null && !model.getMetrics().isBlank()) {
            for (Map<String, Object> node : nodes) {
                if ("training".equals(node.get("type"))) {
                    Map<String, Object> cfg = new LinkedHashMap<>((Map<String, Object>) node.get("config"));
                    cfg.put("metrics", parseJsonMap(model.getMetrics()));
                    node.put("config", cfg);
                }
            }
        }

        // Enrich feature engineering node with feature importance
        if (model.getFeatureImportance() != null && !model.getFeatureImportance().isBlank()) {
            for (Map<String, Object> node : nodes) {
                if ("feature_engineering".equals(node.get("type"))) {
                    Map<String, Object> cfg = new LinkedHashMap<>((Map<String, Object>) node.get("config"));
                    cfg.put("featureImportance", parseJsonMap(model.getFeatureImportance()));
                    node.put("config", cfg);
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        MiningPipeline pipeline = new MiningPipeline();
        pipeline.setName(model.getName());
        pipeline.setDataSourceId(model.getDataSourceId() != null ? model.getDataSourceId() : 0L);
        pipeline.setConversationId(model.getConversationId());
        pipeline.setStatus(ModelStatus.PIPELINE_COMPLETED);
        pipeline.setSourceType("chat");
        pipeline.setNodes(toJson(nodes));
        pipeline.setEdges(toJson(edges));
        pipeline.setLastSyncedAt(now);
        pipeline.setDeleted(0);
        miningPipelineMapper.insert(pipeline);

        model.setLastSyncedAt(now);
        miningModelMapper.updateById(model);
        return pipeline;
    }

    public void syncModelToPipeline(Long pipelineId, MiningModel model) {
        try {
            MiningPipeline pipeline = miningPipelineMapper.selectById(pipelineId);
            if (pipeline == null) return;
            List<Map<String, Object>> nodes = parseJsonNodeList(pipeline.getNodes());
            Map<String, Object> preprocessing = parseJsonMap(model.getPreprocessing());
            List<String> featureCols = parseJsonList(model.getFeatureColumns());
            List<Map<String, Object>> edges = parseJsonNodeList(pipeline.getEdges());

            boolean nodesChanged = false;

            // Ensure fill_missing node exists if preprocessing has fill strategy
            String handleMissing = (String) preprocessing.getOrDefault("handleMissing", "drop");
            boolean needsFillMissing = !"none".equals(handleMissing) && !"drop".equals(handleMissing);
            if (needsFillMissing) {
                boolean hasFillNode = nodes.stream().anyMatch(n -> "fill_missing".equals(n.get("type")));
                if (!hasFillNode) {
                    int ppIdx = -1;
                    for (int i = 0; i < nodes.size(); i++) {
                        if ("preprocessing".equals(nodes.get(i).get("type"))) { ppIdx = i; break; }
                    }
                    if (ppIdx >= 0) {
                        String fmId = "fm_" + model.getId() + "_" + System.currentTimeMillis();
                        Map<String, Object> fmConfig = new LinkedHashMap<>();
                        fmConfig.put("title", "填充缺失值");
                        fmConfig.put("strategy", handleMissing.startsWith("fill_") ? handleMissing.substring(5) : handleMissing);
                        Object fillCols = preprocessing.get("fillMissingColumns");
                        fmConfig.put("columns", fillCols instanceof List ? fillCols : List.of());
                        Map<String, Object> fmNode = new LinkedHashMap<>();
                        fmNode.put("id", fmId);
                        fmNode.put("type", "fill_missing");
                        fmNode.put("config", fmConfig);
                        nodes.add(ppIdx + 1, fmNode);

                        // Fix edges: pp→next becomes pp→fm→next
                        String ppId = (String) nodes.get(ppIdx).get("id");
                        edges.removeIf(e -> ppId.equals(e.get("source")));
                        // Find what was after preprocessing
                        int nextIdx = ppIdx + 2; // fm is at ppIdx+1 now
                        String nextId = nextIdx < nodes.size() ? (String) nodes.get(nextIdx).get("id") : null;
                        edges.add(Map.of("source", ppId, "target", fmId));
                        if (nextId != null) edges.add(Map.of("source", fmId, "target", nextId));
                        nodesChanged = true;
                    }
                }
            }

            // Ensure output node exists if predictResultTable is set
            if (model.getPredictResultTable() != null && !model.getPredictResultTable().isBlank()) {
                boolean hasOutputNode = nodes.stream().anyMatch(n -> "output".equals(n.get("type")));
                if (!hasOutputNode) {
                    String outId = "out_" + model.getId() + "_" + System.currentTimeMillis();
                    Map<String, Object> outConfig = new LinkedHashMap<>();
                    outConfig.put("title", "输出写入");
                    outConfig.put("table", model.getPredictResultTable());
                    outConfig.put("mode", "append");
                    outConfig.put("autoCreate", true);
                    Map<String, Object> outNode = new LinkedHashMap<>();
                    outNode.put("id", outId);
                    outNode.put("type", "output");
                    outNode.put("config", outConfig);
                    nodes.add(outNode);

                    // Find last node and connect to output
                    String lastId = (String) nodes.get(nodes.size() - 2).get("id"); // node before output
                    if (lastId != null) edges.add(Map.of("source", lastId, "target", outId));
                    nodesChanged = true;
                }
            }

            for (Map<String, Object> node : nodes) {
                String type = (String) node.get("type");
                @SuppressWarnings("unchecked")
                Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) node.getOrDefault("config", Map.of()));

                switch (type) {
                    case "data_source" -> {
                        if (model.getSourceTable() != null) config.put("table", model.getSourceTable());
                    }
                    case "preprocessing" -> {
                        if (preprocessing.containsKey("handleMissing"))
                            config.put("handleMissing", preprocessing.get("handleMissing"));
                        if (preprocessing.containsKey("encoding"))
                            config.put("encoding", preprocessing.get("encoding"));
                        if (preprocessing.containsKey("scaling"))
                            config.put("scaling", preprocessing.get("scaling"));
                    }
                    case "fill_missing" -> {
                        if (preprocessing.containsKey("fillMissingStrategy"))
                            config.put("strategy", preprocessing.get("fillMissingStrategy"));
                        if (preprocessing.containsKey("fillMissingColumns"))
                            config.put("columns", preprocessing.get("fillMissingColumns"));
                    }
                    case "feature_engineering" -> {
                        if (!featureCols.isEmpty()) config.put("featureColumns", featureCols);
                        if (model.getTargetColumn() != null) config.put("targetColumn", model.getTargetColumn());
                        Object transforms = preprocessing.get("transforms");
                        if (transforms instanceof List) config.put("transforms", transforms);
                    }
                    case "training" -> {
                        if (model.getModelType() != null) config.put("modelType", model.getModelType());
                        if (model.getAlgorithm() != null) config.put("algorithm", model.getAlgorithm());
                        if (model.getHyperparameters() != null) {
                            Map<String, Object> hp = parseJsonMap(model.getHyperparameters());
                            config.put("hyperparams", new LinkedHashMap<>(hp));
                        }
                    }
                    case "evaluation" -> {
                        if (model.getValidationMode() != null) config.put("validationMode", model.getValidationMode());
                        if (model.getCvFolds() != null) config.put("cvFold", model.getCvFolds());
                        if (model.getTestSize() != null) config.put("testSize", (int)(model.getTestSize() * 100));
                        if (model.getTemporalColumn() != null) config.put("temporalColumn", model.getTemporalColumn());
                    }
                    case "output" -> {
                        if (model.getPredictResultTable() != null) config.put("table", model.getPredictResultTable());
                    }
                }
                node.put("config", config);
            }

            pipeline.setNodes(objectMapper.writeValueAsString(nodes));
            pipeline.setEdges(objectMapper.writeValueAsString(edges));
            LocalDateTime now = LocalDateTime.now();
            pipeline.setLastSyncedAt(now);
            miningPipelineMapper.updateById(pipeline);

            model.setLastSyncedAt(now);
            miningModelMapper.updateById(model);
        } catch (Exception e) {
            log.warn("[MINING] Failed to sync model to pipeline {}: {}", pipelineId, e.getMessage());
        }
    }

    // ======================== Utilities ========================

    public DataSource getDataSource(Long dataSourceId) { return dataSourceMapper.selectById(dataSourceId); }
    public JdbcTemplate getJdbcTemplate(DataSource ds) { return dataSourceManager.getJdbcTemplate(ds.getId()); }

    private boolean isNumericType(String dataType) {
        if (dataType == null) return false;
        return Set.of("int", "bigint", "tinyint", "smallint", "mediumint", "float", "double", "decimal", "numeric", "real")
            .contains(dataType.toLowerCase());
    }

    private Map<String, Object> parseTrainingOutput(String stdout) { return parseResultMarker(stdout, "[TRAIN_RESULT]"); }

    private Map<String, Object> parseResultMarker(String stdout, String marker) {
        Map<String, Object> result = new HashMap<>();
        if (stdout == null) return result;
        for (String line : stdout.split("\n")) {
            if (line.contains(marker)) {
                try { result = objectMapper.readValue(line.substring(line.indexOf(marker) + marker.length()).trim(), Map.class); }
                catch (Exception e) { log.warn("[MINING] Failed to parse marker '{}': {}", marker, e.getMessage()); }
                break;
            }
        }
        return result;
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try { return objectMapper.readValue(json, List.class); }
        catch (Exception e) { return List.of(json.split(",")); }
    }

    private boolean isUnsupervisedType(String modelType) {
        if (modelType == null) return false;
        return "clustering".equals(modelType) || "anomaly_detection".equals(modelType);
    }

    private String normalizeToJsonArray(String value) {
        if (value == null || value.isBlank()) return value;
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) return trimmed;
        List<String> items = Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        try { return objectMapper.writeValueAsString(items); }
        catch (Exception e) { return value; }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    private List<Map<String, Object>> parseJsonNodeList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try { return objectMapper.readValue(json, List.class); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return String.valueOf(obj); }
    }

    private String validationMode(MiningModel model) {
        String mode = model.getValidationMode();
        return (mode != null && !mode.isBlank()) ? mode : "train_test";
    }

    private String truncateLog(String log, int maxLen) {
        if (log == null) return null;
        // Strip control characters that break JSON serialization
        String cleaned = log.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "");
        if (cleaned.length() <= maxLen) return cleaned;
        return cleaned.substring(0, maxLen) + "\n... (truncated)";
    }

    private void validateTableName(String name) {
        com.smartquery.common.IdentifierValidator.validateTableName(name);
    }

    private void validateColumnName(String name) {
        com.smartquery.common.IdentifierValidator.validateColumnName(name);
    }

    // Compute the next run time from a 5-field cron expression.
    // Handles: every-N-minutes, daily-at-H, weekly, monthly patterns.
    private java.time.LocalDateTime computeNextRun(String cron) {
        if (cron == null || cron.isBlank()) return LocalDateTime.now().plusMinutes(defaultCronMinutes);
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) return LocalDateTime.now().plusMinutes(defaultCronMinutes);

        // "*/N * * * *" — every N minutes
        if (parts[0].startsWith("*/")) {
            long mins = Long.parseLong(parts[0].substring(2));
            return LocalDateTime.now().plusMinutes(mins);
        }

        // Standard 5-field cron: minute hour day month weekday
        int minute = Integer.parseInt(parts[0]);
        int hour = Integer.parseInt(parts[1]);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);

        // Weekly: "0 H * * D"
        if (!parts[4].equals("*")) {
            int targetDay = Integer.parseInt(parts[4]);
            int currentDay = next.getDayOfWeek().getValue() % 7;
            int daysUntil = (targetDay - currentDay + 7) % 7;
            if (daysUntil == 0 && !next.isAfter(now)) daysUntil = 7;
            next = next.plusDays(daysUntil);
        }

        // Monthly: "0 H D * *"
        if (!parts[2].equals("*") && parts[4].equals("*")) {
            int targetDay = Integer.parseInt(parts[2]);
            next = next.withDayOfMonth(Math.min(targetDay, next.getMonth().length(next.toLocalDate().isLeapYear())));
            if (!next.isAfter(now)) next = next.plusMonths(1);
        }

        return next;
    }

    private void logMiningEvent(MiningModel model, String eventType, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modelId", model.getId());
        payload.put("modelName", model.getName());
        payload.put("algorithm", model.getAlgorithm());
        payload.put("modelType", model.getModelType());
        payload.put("dataSourceId", model.getDataSourceId());
        payload.putAll(extra);
        eventLogger.logEvent(model.getConversationId(), null, eventType, payload);
    }

    private String safeJsonEmbed(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) return "{}";
        try {
            return objectMapper.readTree(json).toString();
        } catch (Exception e) {
            log.warn("[MINING] Invalid JSON rejected for Python embedding: {}", e.getMessage());
            return "{}";
        }
    }

    private void logMiningError(Long conversationId, String errorType, Long modelId, String error) {
        eventLogger.logEvent(conversationId, null, "mining_error",
            Map.of("errorType", errorType, "modelId", modelId, "error", error));
    }
}
