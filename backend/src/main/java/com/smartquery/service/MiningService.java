package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.ModelStatus;
import com.smartquery.common.UserContextHolder;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.*;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.logging.DiagnosticsTimer;
import com.smartquery.mapper.*;
import com.smartquery.orchestration.MiningOperatorRegistrationService;
import com.smartquery.python.PythonResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
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
    private final MiningRuntimeClient miningRuntimeClient;
    private final DataSourceManager dataSourceManager;
    private final AlgorithmService algorithmService;
    private final ObjectMapper objectMapper;
    private final MiningPredictionService predictionService;
    private final PipelineService pipelineService;
    private final ConversationEventLogger eventLogger;
    private final ResourceAccessService resourceAccess;
    private final TaskEventService taskEventService;
    private final MiningOperatorRegistrationService miningOperatorRegistrationService;
    private final Executor miningExecutor;

    public MiningService(
            MiningModelMapper miningModelMapper,
            MiningPipelineMapper miningPipelineMapper,
            ModelExecutionMapper modelExecutionMapper,
            DataSourceMapper dataSourceMapper,
            PredictionResultMapper predictionResultMapper,
            MiningRuntimeClient miningRuntimeClient,
            DataSourceManager dataSourceManager,
            AlgorithmService algorithmService,
            ObjectMapper objectMapper,
            MiningPredictionService predictionService,
            PipelineService pipelineService,
            ConversationEventLogger eventLogger,
            ResourceAccessService resourceAccess,
            TaskEventService taskEventService,
            @Lazy MiningOperatorRegistrationService miningOperatorRegistrationService,
            @Qualifier("miningExecutor") Executor miningExecutor
    ) {
        this.miningModelMapper = miningModelMapper;
        this.miningPipelineMapper = miningPipelineMapper;
        this.modelExecutionMapper = modelExecutionMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.predictionResultMapper = predictionResultMapper;
        this.miningRuntimeClient = miningRuntimeClient;
        this.dataSourceManager = dataSourceManager;
        this.algorithmService = algorithmService;
        this.objectMapper = objectMapper;
        this.predictionService = predictionService;
        this.pipelineService = pipelineService;
        this.eventLogger = eventLogger;
        this.resourceAccess = resourceAccess;
        this.taskEventService = taskEventService;
        this.miningOperatorRegistrationService = miningOperatorRegistrationService;
        this.miningExecutor = miningExecutor;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        trainingSemaphore = new Semaphore(maxConcurrentTraining);
    }

    // ======================== Model Lifecycle ========================

    @Transactional
    public MiningModel createModel(MiningModel model) {
        UserContextHolder.UserContext actor = UserContextHolder.require();
        if (model.getName() == null || model.getName().isBlank()) throw new IllegalArgumentException("模型名称不能为空");
        if (model.getDataSourceId() == null) throw new IllegalArgumentException("数据源不能为空");
        if (model.getConversationId() != null) {
            Conversation conversation = resourceAccess.requireConversation(model.getConversationId());
            if (conversation.getDataSourceId() != null
                    && !conversation.getDataSourceId().equals(model.getDataSourceId())) {
                throw new com.smartquery.common.BusinessException(403, "模型数据源与会话绑定的数据源不一致");
            }
        }
        if (model.getSourceTable() != null && !model.getSourceTable().isBlank()) {
            com.smartquery.common.IdentifierValidator.validateTableName(model.getSourceTable());
        }
        if (model.getTargetColumn() != null && !model.getTargetColumn().isBlank()) {
            com.smartquery.common.IdentifierValidator.validateColumnName(model.getTargetColumn());
        }
        // Request/tool callers may not choose an owner. Internal system calls
        // without an actor may preserve an owner explicitly inherited from a
        // pipeline/model; null remains reserved for legacy/system resources.
        model.setUserId(actor.userId().toString());
        model.setStatus(ModelStatus.DRAFT);
        model.setVersion(1);
        model.setDeleted(0);
        if (model.getHyperparameters() == null || model.getHyperparameters().isBlank()) {
            model.setHyperparameters("{}");
        }
        model.setFeatureColumns(normalizeToJsonArray(model.getFeatureColumns()));
        if (model.getAlgorithm() != null && !model.getAlgorithm().isBlank()) {
            algorithmService.applyBinding(model,
                algorithmService.activeBinding(model.getAlgorithm(), model.getModelType()));
        }
        miningModelMapper.insert(model);
        miningOperatorRegistrationService.ensureOperator(model);
        logMiningEvent(model, "model_created", Map.of(
            "sourceTable", model.getSourceTable() != null ? model.getSourceTable() : "",
            "algorithm", model.getAlgorithm() != null ? model.getAlgorithm() : ""
        ));
        return model;
    }

    @Transactional
    public MiningModel updateModel(Long id, MiningModel updates) {
        MiningModel existing = resourceAccess.requireModel(id);
        boolean algorithmDefinitionChanged = updates.getAlgorithm() != null || updates.getModelType() != null;

        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getAlgorithm() != null) existing.setAlgorithm(updates.getAlgorithm());
        if (updates.getHyperparameters() != null) existing.setHyperparameters(updates.getHyperparameters());
        if (updates.getDataSourceId() != null) {
            if (existing.getConversationId() != null) {
                Conversation conversation = resourceAccess.requireConversation(existing.getConversationId());
                if (conversation.getDataSourceId() != null
                        && !conversation.getDataSourceId().equals(updates.getDataSourceId())) {
                    throw new com.smartquery.common.BusinessException(403, "模型数据源与会话绑定的数据源不一致");
                }
            }
            existing.setDataSourceId(updates.getDataSourceId());
        }
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
        if (updates.getPositiveClass() != null) existing.setPositiveClass(updates.getPositiveClass());
        if (updates.getGroupColumns() != null) existing.setGroupColumns(normalizeToJsonArray(updates.getGroupColumns()));
        if (updates.getOosTable() != null) existing.setOosTable(updates.getOosTable());
        if (updates.getOosFilter() != null) existing.setOosFilter(updates.getOosFilter());
        if (updates.getCalibrationMethod() != null) existing.setCalibrationMethod(updates.getCalibrationMethod());
        if (updates.getThresholdPolicy() != null) existing.setThresholdPolicy(updates.getThresholdPolicy());
        if (updates.getGovernancePolicy() != null) existing.setGovernancePolicy(updates.getGovernancePolicy());

        if (algorithmDefinitionChanged && existing.getAlgorithm() != null && !existing.getAlgorithm().isBlank()) {
            algorithmService.applyBinding(existing,
                algorithmService.activeBinding(existing.getAlgorithm(), existing.getModelType()));
        }

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
        miningOperatorRegistrationService.ensureOperator(existing);

        if (existing.getPipelineId() != null) {
            syncModelToPipeline(existing.getPipelineId(), existing);
        }

        return existing;
    }

    @Transactional
    public void deleteModel(Long id) {
        MiningModel model = resourceAccess.requireModel(id);
        if (ModelStatus.TRAINING.equals(model.getStatus())) {
            throw new IllegalStateException("模型正在训练中，无法删除");
        }
        if (ModelStatus.PUBLISHED.equals(model.getStatus())) {
            throw new IllegalStateException("已发布的模型不能删除，请先下线");
        }
        int updated = miningModelMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                .eq(MiningModel::getId, id)
                .eq(MiningModel::getDeleted, 0)
                .set(MiningModel::getDeleted, 1));
        if (updated != 1) {
            throw new IllegalStateException("模型删除失败，请刷新后重试");
        }
        miningOperatorRegistrationService.archiveOperator(model);
    }

    // ======================== Validation ========================

    public Map<String, Object> validateForTraining(Long modelId) {
        MiningModel model = resourceAccess.requireModel(modelId);

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
        resourceAccess.requireModel(modelId);
        return trainModelInternal(modelId, triggerType, inputFilter, UserContextHolder.require());
    }

    /** Submit a user-triggered training run and return without waiting for Python. */
    public TrainingSubmission submitTraining(Long modelId, String triggerType) {
        MiningModel model = resourceAccess.requireModel(modelId);
        UserContextHolder.UserContext actor = UserContextHolder.require();
        validateTrainingConfiguration(model);
        String previousStatus = model.getStatus();

        int claimed = miningModelMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                .eq(MiningModel::getId, modelId)
                .ne(MiningModel::getStatus, ModelStatus.TRAINING)
                .set(MiningModel::getStatus, ModelStatus.TRAINING));
        if (claimed == 0) {
            throw new IllegalStateException("模型正在训练中，请等待完成: " + model.getName());
        }

        ModelExecution execution = new ModelExecution();
        execution.setModelId(modelId);
        execution.setTriggeredByUserId(actor.userId().toString());
        execution.setTriggerType(triggerType == null ? "manual" : triggerType);
        execution.setExecutionKind("TRAIN");
        execution.setStatus(ModelStatus.EXEC_QUEUED);
        execution.setHyperparameters(model.getHyperparameters());
        execution.setProgressPercent(0);
        execution.setCurrentStage("QUEUED");
        execution.setProgressMessage("训练任务已进入队列");
        execution.setCancelRequested(false);
        modelExecutionMapper.insert(execution);
        publishTrainingEvent(execution, "queued", Map.of(
            "modelId", modelId,
            "executionId", execution.getId(),
            "status", execution.getStatus(),
            "stage", execution.getCurrentStage(),
            "progress", execution.getProgressPercent(),
            "message", execution.getProgressMessage()), false);

        try {
            miningExecutor.execute(() -> {
                try (UserContextHolder.Scope ignored = UserContextHolder.open(actor)) {
                    try {
                        trainModelInternal(modelId, execution.getTriggerType(), null, actor,
                            execution.getId(), true);
                    } catch (Exception error) {
                        failPreparedExecution(modelId, execution.getId(), error);
                    }
                }
            });
        } catch (RuntimeException rejected) {
            execution.setStatus(ModelStatus.FAILED);
            execution.setCurrentStage("FAILED");
            execution.setProgressMessage("训练线程池拒绝任务: " + rejected.getMessage());
            execution.setFinishedAt(LocalDateTime.now());
            modelExecutionMapper.updateById(execution);
            restoreModelAfterCanceledTraining(modelId, previousStatus);
            throw rejected;
        }
        return new TrainingSubmission(modelId, execution.getId(), execution.getStatus(),
            execution.getCurrentStage(), execution.getProgressPercent());
    }

    public record TrainingSubmission(Long modelId, Long executionId, String status,
                                     String stage, Integer progressPercent) {}

    /** Trusted scheduler entry point. It never inherits or fabricates a user identity. */
    public MiningModel trainModelForSchedule(Long modelId, String inputFilter) {
        MiningModel model = requireScheduledModel(modelId);
        if (!ModelStatus.PUBLISHED.equals(model.getStatus()) && !ModelStatus.TRAINED.equals(model.getStatus())) {
            throw new IllegalStateException("定时训练只允许已训练或已发布模型");
        }
        return trainModelInternal(modelId, "schedule", inputFilter, null);
    }

    /** Persistent-task scheduler entry point. The task, rather than mutable model fields, is the source of truth. */
    public MiningModel trainModelForScheduleTask(Long modelId, String inputFilter, Long scheduleTaskId) {
        MiningModel model = requireSchedulableModel(modelId);
        if (!ModelStatus.PUBLISHED.equals(model.getStatus()) && !ModelStatus.TRAINED.equals(model.getStatus())) {
            throw new IllegalStateException("定期训练只允许已训练或已发布模型");
        }
        return trainModelInternal(modelId, "schedule", inputFilter, null, null, false, scheduleTaskId);
    }

    private MiningModel trainModelInternal(Long modelId, String triggerType, String inputFilter,
                                           UserContextHolder.UserContext triggerActor) {
        return trainModelInternal(modelId, triggerType, inputFilter, triggerActor, null, false);
    }

    private MiningModel trainModelInternal(Long modelId, String triggerType, String inputFilter,
                                           UserContextHolder.UserContext triggerActor,
                                           Long preparedExecutionId, boolean statusAlreadyClaimed) {
        return trainModelInternal(modelId, triggerType, inputFilter, triggerActor,
            preparedExecutionId, statusAlreadyClaimed, null);
    }

    private MiningModel trainModelInternal(Long modelId, String triggerType, String inputFilter,
                                           UserContextHolder.UserContext triggerActor,
                                           Long preparedExecutionId, boolean statusAlreadyClaimed,
                                           Long scheduleTaskId) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);

        if (!statusAlreadyClaimed && ModelStatus.TRAINING.equals(model.getStatus())) {
            throw new IllegalStateException("模型正在训练中，请等待完成: " + model.getName());
        }
        validateTrainingConfiguration(model);

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

        if (!statusAlreadyClaimed) {
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
        }
        // Re-read after CAS to get latest field values
        model = miningModelMapper.selectById(modelId);

        ModelExecution execution = preparedExecutionId == null
            ? new ModelExecution() : modelExecutionMapper.selectById(preparedExecutionId);
        if (execution == null) {
            trainingSemaphore.release();
            throw new IllegalStateException("训练执行记录不存在: " + preparedExecutionId);
        }
        if (preparedExecutionId == null) {
            execution.setModelId(modelId);
            execution.setScheduleTaskId(scheduleTaskId);
            if (triggerActor != null && triggerActor.userId() != null) {
                execution.setTriggeredByUserId(triggerActor.userId().toString());
            }
            execution.setTriggerType(triggerType != null ? triggerType : "manual");
            execution.setExecutionKind("TRAIN");
            execution.setHyperparameters(model.getHyperparameters());
            execution.setCancelRequested(false);
            execution.setStatus(ModelStatus.EXEC_RUNNING);
            execution.setCurrentStage("STARTING");
            execution.setProgressPercent(1);
            execution.setProgressMessage("训练进程正在启动");
            execution.setStartedAt(LocalDateTime.now());
            modelExecutionMapper.insert(execution);
        } else {
            int executionClaimed = modelExecutionMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ModelExecution>()
                    .eq(ModelExecution::getId, preparedExecutionId)
                    .eq(ModelExecution::getStatus, ModelStatus.EXEC_QUEUED)
                    .eq(ModelExecution::getCancelRequested, false)
                    .set(ModelExecution::getStatus, ModelStatus.EXEC_RUNNING)
                    .set(ModelExecution::getCurrentStage, "STARTING")
                    .set(ModelExecution::getProgressPercent, 1)
                    .set(ModelExecution::getProgressMessage, "训练进程正在启动")
                    .set(ModelExecution::getStartedAt, LocalDateTime.now()));
            if (executionClaimed == 0) {
                execution = modelExecutionMapper.selectById(preparedExecutionId);
                if (execution != null && Boolean.TRUE.equals(execution.getCancelRequested())) {
                    markExecutionCanceled(execution, "训练在开始前已取消");
                    restoreModelAfterCanceledTraining(modelId, null);
                    trainingSemaphore.release();
                    return miningModelMapper.selectById(modelId);
                }
                trainingSemaphore.release();
                throw new IllegalStateException("训练执行状态已变化: " + preparedExecutionId);
            }
            execution = modelExecutionMapper.selectById(preparedExecutionId);
        }
        final Long activeExecutionId = execution.getId();
        publishTrainingEvent(execution, "start", Map.of(
            "modelId", modelId,
            "executionId", activeExecutionId,
            "model", model.getName(),
            "stage", "STARTING",
            "progress", 1), false);

        logMiningEvent(model, "mining_training_start", Map.of(
            "algorithm", model.getAlgorithm(), "modelType", model.getModelType(),
            "sourceTable", model.getSourceTable(), "version", model.getVersion(),
            "queueAvailable", trainingSemaphore.availablePermits()
        ));

        try {
            if (isCancellationRequested(activeExecutionId)) {
                markExecutionCanceled(execution, "训练在进程启动前已取消");
                restoreModelAfterCanceledTraining(modelId, null);
                return miningModelMapper.selectById(modelId);
            }
            Long dsId = model.getDataSourceId();
            Map<String, Object> request = buildTrainingRequest(model, execution, inputFilter);
            execution.setAlgorithmId(model.getAlgorithm());
            execution.setAlgorithmVersion(model.getAlgorithmVersion());
            execution.setAlgorithmSnapshot(model.getAlgorithmSnapshot());
            modelExecutionMapper.updateById(execution);
            MiningRuntimeClient.RuntimeResult runtime = DiagnosticsTimer.timedSupply(
                "mining.train",
                () -> miningRuntimeClient.execute("train", request, dsId, pythonTimeoutMs,
                    activeExecutionId,
                    progress -> updateExecutionProgress(activeExecutionId, progress),
                    line -> appendExecutionLog(activeExecutionId, line))
            );
            PythonResult result = runtime.process();

            execution.setExecutionTimeMs(result.executionTimeMs());
            execution.setExecutionLog(truncateLog(result.stdout(), logTruncation));

            if (isCancellationRequested(execution.getId())) {
                markExecutionCanceled(execution, "训练已由用户取消");
                model.setStatus(restingStatus(model));
                model.setTrainingLog("训练已取消");
            } else if (runtime.successful()) {
                Map<String, Object> parsed = runtime.payload();
                if (!(parsed.get("metrics") instanceof Map<?, ?>)
                        || parsed.get("model_path") == null) {
                    throw new IllegalStateException("Python 训练结果缺少 metrics 或 model_path");
                }
                execution.setMetrics(toJson(parsed.get("metrics")));
                execution.setStatus(ModelStatus.EXEC_SUCCESS);
                execution.setCurrentStage("COMPLETED");
                execution.setProgressPercent(100);
                execution.setProgressMessage("训练完成");
                execution.setFinishedAt(LocalDateTime.now());
                execution.setArtifactPath(String.valueOf(parsed.get("model_path")));
                if (parsed.get("artifact_sha256") != null) {
                    execution.setArtifactSha256(String.valueOf(parsed.get("artifact_sha256")));
                }
                if (parsed.get("artifact_schema_version") instanceof Number schemaVersion) {
                    execution.setArtifactSchemaVersion(schemaVersion.intValue());
                }

                model.setStatus(ModelStatus.TRAINED);
                model.setVersion(model.getVersion() + 1);
                model.setMetrics(toJson(parsed.get("metrics")));
                model.setFeatureImportance(toJson(parsed.get("feature_importance")));
                if (parsed.get("feature_columns") instanceof List<?>) {
                    model.setFeatureColumns(toJson(parsed.get("feature_columns")));
                }
                model.setTrainingLog(truncateLog(result.stdout(), trainingLogTruncation));
                if (parsed.get("model_path") != null) model.setModelPath(String.valueOf(parsed.get("model_path")));
                model.setArtifactSha256(execution.getArtifactSha256());
                model.setArtifactSchemaVersion(execution.getArtifactSchemaVersion());
                if (parsed.get("validation") != null) model.setValidationMetrics(toJson(parsed.get("validation")));
                if (parsed.get("monitoring_baseline") != null) {
                    model.setMonitoringBaseline(toJson(parsed.get("monitoring_baseline")));
                }
                // Approval is bound to one trained artifact and is invalidated by retraining.
                model.setEvaluationStatus("pending");
                model.setApprovedByUserId(null);
                model.setApprovedAt(null);
                GovernanceReport governance = evaluateGovernance(model, false);
                // Machine-learning quality is an automated operator gate. Human review happens
                // later on the immutable ML operator version, not on the composed-model queue.
                model.setEvaluationStatus(governance.qualityPassed()
                    ? "evaluation_passed" : "evaluation_failed");

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
                String errorDetail = runtime.errorMessage();
                execution.setStatus(ModelStatus.FAILED);
                execution.setCurrentStage("FAILED");
                execution.setProgressPercent(100);
                execution.setProgressMessage(truncateLog(errorDetail, errorSummaryTruncation));
                execution.setFinishedAt(LocalDateTime.now());
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
                    .set(MiningModel::getFeatureColumns, model.getFeatureColumns())
                    .set(MiningModel::getModelPath, model.getModelPath())
                    .set(MiningModel::getArtifactSha256, model.getArtifactSha256())
                    .set(MiningModel::getArtifactSchemaVersion, model.getArtifactSchemaVersion())
                    .set(MiningModel::getTrainingLog, truncateLog(model.getTrainingLog(), trainingLogTruncation))
                    .set(MiningModel::getHyperparameters, model.getHyperparameters())
                    .set(MiningModel::getPreprocessing, model.getPreprocessing())
                    .set(MiningModel::getValidationMetrics, model.getValidationMetrics())
                    .set(MiningModel::getEvaluationStatus, model.getEvaluationStatus())
                    .set(MiningModel::getApprovedByUserId, model.getApprovedByUserId())
                    .set(MiningModel::getApprovedAt, model.getApprovedAt())
                    .set(MiningModel::getMonitoringBaseline, model.getMonitoringBaseline())
                    .set(MiningModel::getLastRunAt, model.getLastRunAt());
            if (model.getPipelineId() != null) {
                updateWrapper.set(MiningModel::getPipelineId, model.getPipelineId());
            }
            miningModelMapper.update(null, updateWrapper);
            modelExecutionMapper.updateById(execution);
            publishTerminalTrainingEvent(model, execution);
            return miningModelMapper.selectById(modelId);
        } catch (Exception e) {
            log.error("[MINING] Training exception for model {}: {}", modelId, e.getMessage(), e);
            logMiningError(model.getConversationId(), "training_failed", modelId, e.getMessage());
            if (isCancellationRequested(execution.getId())) {
                markExecutionCanceled(execution, "训练已由用户取消");
            } else {
                execution.setStatus(ModelStatus.FAILED);
                execution.setCurrentStage("FAILED");
                execution.setProgressPercent(100);
                execution.setProgressMessage(truncateLog(e.getMessage(), errorSummaryTruncation));
                execution.setExecutionLog(e.getMessage());
                execution.setFinishedAt(LocalDateTime.now());
            }
            modelExecutionMapper.updateById(execution);
            miningModelMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                    .eq(MiningModel::getId, modelId)
                    .set(MiningModel::getStatus, isCancellationRequested(execution.getId())
                        ? restingStatus(model) : ModelStatus.FAILED)
                    .set(MiningModel::getTrainingLog, isCancellationRequested(execution.getId())
                        ? "训练已取消" : e.getMessage()));
            publishTerminalTrainingEvent(model, execution);
            return miningModelMapper.selectById(modelId);
        } finally {
            trainingSemaphore.release();
        }
    }

    public ModelExecution cancelTraining(Long modelId, Long executionId) {
        ModelExecution execution = resourceAccess.requireModelExecution(modelId, executionId);
        if (ModelStatus.EXEC_SUCCESS.equals(execution.getStatus())
                || ModelStatus.FAILED.equals(execution.getStatus())
                || ModelStatus.EXEC_CANCELED.equals(execution.getStatus())) {
            return execution;
        }
        execution.setCancelRequested(true);
        execution.setProgressMessage("已请求取消训练");
        if (ModelStatus.EXEC_QUEUED.equals(execution.getStatus())
                || "pending".equals(execution.getStatus())) {
            markExecutionCanceled(execution, "训练在队列中被取消");
            restoreModelAfterCanceledTraining(modelId, null);
        } else {
            modelExecutionMapper.updateById(execution);
            for (int attempt = 0; attempt < 10; attempt++) {
                if (miningRuntimeClient.cancel(executionId)) break;
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return modelExecutionMapper.selectById(executionId);
    }

    private void validateTrainingConfiguration(MiningModel model) {
        if ("temporal".equals(model.getValidationMode())
                && (model.getTemporalColumn() == null || model.getTemporalColumn().isBlank())) {
            throw new IllegalStateException("时间外验证模式(temporal)需要指定时间列(temporal_column)");
        }
        if (model.getSourceTable() == null || model.getSourceTable().isBlank()) {
            throw new IllegalStateException("模型缺少源表(source_table)");
        }
        if ("oos".equals(model.getValidationMode())
                && (model.getOosTable() == null || model.getOosTable().isBlank())) {
            throw new IllegalStateException("真正的 OOS 验证必须配置独立 oos_table，不能再从训练表随机切分");
        }
    }

    private void updateExecutionProgress(Long executionId, MiningRuntimeClient.ProgressUpdate progress) {
        modelExecutionMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ModelExecution>()
                .eq(ModelExecution::getId, executionId)
                .eq(ModelExecution::getStatus, ModelStatus.EXEC_RUNNING)
                .set(ModelExecution::getCurrentStage, progress.stage())
                .set(ModelExecution::getProgressPercent, progress.progressPercent())
                .set(ModelExecution::getProgressMessage, progress.message()));
        ModelExecution execution = modelExecutionMapper.selectById(executionId);
        if (execution != null) {
            publishTrainingEvent(execution, "progress", Map.of(
                "executionId", executionId,
                "status", ModelStatus.EXEC_RUNNING,
                "stage", progress.stage(),
                "progress", progress.progressPercent(),
                "message", progress.message()), false);
        }
    }

    private void appendExecutionLog(Long executionId, String line) {
        if (line == null || line.isBlank()) return;
        ModelExecution current = modelExecutionMapper.selectById(executionId);
        if (current == null || !ModelStatus.EXEC_RUNNING.equals(current.getStatus())) return;
        String existing = current.getExecutionLog() == null ? "" : current.getExecutionLog();
        String updated = truncateLog(existing + line + "\n", logTruncation);
        modelExecutionMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ModelExecution>()
                .eq(ModelExecution::getId, executionId)
                .eq(ModelExecution::getStatus, ModelStatus.EXEC_RUNNING)
                .set(ModelExecution::getExecutionLog, updated));
        publishTrainingEvent(current, "log", Map.of(
            "executionId", executionId,
            "line", line), false);
    }

    private boolean isCancellationRequested(Long executionId) {
        ModelExecution current = executionId == null ? null : modelExecutionMapper.selectById(executionId);
        return current != null && Boolean.TRUE.equals(current.getCancelRequested());
    }

    private void markExecutionCanceled(ModelExecution execution, String message) {
        execution.setCancelRequested(true);
        execution.setStatus(ModelStatus.EXEC_CANCELED);
        execution.setCurrentStage("CANCELED");
        execution.setProgressPercent(100);
        execution.setProgressMessage(message);
        execution.setFinishedAt(LocalDateTime.now());
        modelExecutionMapper.updateById(execution);
        publishTrainingEvent(execution, "canceled", Map.of(
            "executionId", execution.getId(), "message", message, "progress", 100), true);
    }

    private String restingStatus(MiningModel model) {
        return model != null && model.getModelPath() != null && !model.getModelPath().isBlank()
            ? ModelStatus.TRAINED : ModelStatus.DRAFT;
    }

    private void restoreModelAfterCanceledTraining(Long modelId, String preferredStatus) {
        MiningModel current = miningModelMapper.selectById(modelId);
        if (current == null) return;
        String status = preferredStatus != null && !ModelStatus.TRAINING.equals(preferredStatus)
            ? preferredStatus : restingStatus(current);
        miningModelMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                .eq(MiningModel::getId, modelId)
                .eq(MiningModel::getStatus, ModelStatus.TRAINING)
                .set(MiningModel::getStatus, status));
    }

    private void failPreparedExecution(Long modelId, Long executionId, Exception error) {
        ModelExecution execution = modelExecutionMapper.selectById(executionId);
        if (execution == null || ModelStatus.EXEC_CANCELED.equals(execution.getStatus())) return;
        execution.setStatus(ModelStatus.FAILED);
        execution.setCurrentStage("FAILED");
        execution.setProgressPercent(100);
        execution.setProgressMessage(truncateLog(error.getMessage(), errorSummaryTruncation));
        execution.setExecutionLog(error.getMessage());
        execution.setFinishedAt(LocalDateTime.now());
        modelExecutionMapper.updateById(execution);
        miningModelMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                .eq(MiningModel::getId, modelId)
                .eq(MiningModel::getStatus, ModelStatus.TRAINING)
                .set(MiningModel::getStatus, ModelStatus.FAILED)
                .set(MiningModel::getTrainingLog, error.getMessage()));
        publishTrainingEvent(execution, "failed", Map.of(
            "executionId", executionId,
            "message", error.getMessage() == null ? "训练失败" : error.getMessage(),
            "progress", 100), true);
    }

    private void publishTerminalTrainingEvent(MiningModel model, ModelExecution execution) {
        if (ModelStatus.EXEC_CANCELED.equals(execution.getStatus())) return;
        if (ModelStatus.EXEC_SUCCESS.equals(execution.getStatus())) {
            publishTrainingEvent(execution, "complete", Map.of(
                "success", true,
                "modelId", model.getId(),
                "executionId", execution.getId(),
                "progress", 100,
                "metrics", parseJsonMap(execution.getMetrics())), true);
        } else {
            publishTrainingEvent(execution, "failed", Map.of(
                "modelId", model.getId(),
                "executionId", execution.getId(),
                "progress", 100,
                "message", execution.getProgressMessage() == null ? "模型训练失败" : execution.getProgressMessage()), true);
        }
        taskEventService.publish(TaskEventService.modelTopic(model.getId()), model.getUserId(),
            "model_status", Map.of(
                "type", "model_status",
                "modelId", model.getId(),
                "status", model.getStatus(),
                "metrics", model.getMetrics() == null ? Map.of() : parseJsonMap(model.getMetrics())), true);
    }

    private void publishTrainingEvent(ModelExecution execution, String eventName,
                                      Map<String, Object> payload, boolean terminal) {
        if (execution == null || execution.getTriggeredByUserId() == null) return;
        taskEventService.publish(TaskEventService.trainingTopic(execution.getId()),
            execution.getTriggeredByUserId(), eventName, payload, terminal);
    }

    // ======================== Lifecycle ========================

    public GovernanceReport governanceReport(Long modelId) {
        if (resourceAccess.hasPermission(com.smartquery.common.PermissionCodes.MODEL_REVIEW)) {
            MiningModel model = miningModelMapper.selectById(modelId);
            if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
                throw new com.smartquery.common.BusinessException(404, "模型不存在: " + modelId);
            }
            return evaluateGovernance(model);
        }
        return evaluateGovernance(resourceAccess.requireModel(modelId));
    }

    public MiningModel approveEvaluation(Long modelId) {
        resourceAccess.requirePermission(com.smartquery.common.PermissionCodes.MODEL_REVIEW,
            "无权限审批模型版本");
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            throw new com.smartquery.common.BusinessException(404, "模型不存在: " + modelId);
        }
        GovernanceReport report = evaluateGovernance(model, false);
        if (!report.qualityPassed()) {
            throw new IllegalStateException("模型存在质量硬失败，不能审批: " + String.join("；", report.hardFailures()));
        }
        model.setEvaluationStatus("approved");
        model.setApprovedByUserId(UserContextHolder.require().userId().toString());
        model.setApprovedAt(LocalDateTime.now());
        miningModelMapper.updateById(model);
        return model;
    }

    private GovernanceReport evaluateGovernance(MiningModel model) {
        return evaluateGovernance(model, true);
    }

    private GovernanceReport evaluateGovernance(MiningModel model, boolean checkApproval) {
        Map<String, Object> metrics = parseJsonMap(model.getMetrics());
        Map<String, Object> validation = parseJsonMap(model.getValidationMetrics());
        Map<String, Object> policy = parseJsonMap(model.getGovernancePolicy());
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> evaluated = new LinkedHashMap<>();

        if (!Integer.valueOf(MiningRuntimeClient.ARTIFACT_SCHEMA_VERSION).equals(model.getArtifactSchemaVersion())) {
            failures.add("模型制品版本不是当前 Pipeline 版本");
        }
        String modelType = model.getModelType() == null ? "" : model.getModelType().toLowerCase(Locale.ROOT);
        boolean supervised = modelType.contains("classification") || modelType.contains("regression");
        if (supervised) {
            String mode = validationMode(model);
            if (!Set.of("cv", "oos", "temporal").contains(mode)) {
                failures.add("必须使用 cv、独立 OOS 或时间外验证，普通随机切分不能发布");
            }
            if ("oos".equals(mode) && !Boolean.TRUE.equals(validation.get("oos_independent"))) {
                failures.add("OOS 不是独立数据快照");
            }
            if (Boolean.TRUE.equals(policy.get("requireGroupIsolation"))
                    && !Boolean.TRUE.equals(validation.get("group_isolation"))) {
                failures.add("治理策略要求按实体分组隔离，但训练未配置 group_columns");
            }
            if ("temporal".equals(mode)) {
                int windows = validation.get("rolling_windows") instanceof List<?> list ? list.size() : 0;
                int minimumWindows = intPolicy(policy, "minTemporalWindows", 3);
                evaluated.put("temporalWindows", windows);
                if (windows < minimumWindows) {
                    failures.add("时间滚动回测窗口不足: " + windows + " < " + minimumWindows);
                }
            }
            int testRows = number(validation.get("holdout_test_size"), 0).intValue();
            int minimumTestRows = intPolicy(policy, "minTestRows", 20);
            evaluated.put("testRows", testRows);
            if (testRows < minimumTestRows) {
                failures.add("独立测试样本量不足: " + testRows + " < " + minimumTestRows);
            }
        }

        if (modelType.contains("classification")) {
            double balanced = number(metrics.get("test_balanced_accuracy"), -1).doubleValue();
            double minBalanced = doublePolicy(policy, "minBalancedAccuracy", 0.55);
            evaluated.put("balancedAccuracy", balanced);
            if (balanced < minBalanced) failures.add(String.format(
                "平衡准确率未达标: %.4f < %.4f", balanced, minBalanced));
            if (metrics.get("risk_recall") instanceof Number riskRecall) {
                double minRiskRecall = doublePolicy(policy, "minRiskRecall", 0.50);
                evaluated.put("riskRecall", riskRecall.doubleValue());
                if (riskRecall.doubleValue() < minRiskRecall) failures.add(String.format(
                    "风险类召回率未达标: %.4f < %.4f", riskRecall.doubleValue(), minRiskRecall));
            }
            if (metrics.get("pr_auc") instanceof Number prAuc
                    && metrics.get("positive_rate") instanceof Number positiveRate) {
                double minPrAuc = doublePolicy(policy, "minPrAuc",
                    positiveRate.doubleValue() + 0.02);
                evaluated.put("prAuc", prAuc.doubleValue());
                if (prAuc.doubleValue() < minPrAuc) failures.add(String.format(
                    "PR-AUC 未明显超过随机基线: %.4f < %.4f", prAuc.doubleValue(), minPrAuc));
            }
            if (metrics.get("brier_score") instanceof Number brier) {
                double maxBrier = doublePolicy(policy, "maxBrierScore", 0.25);
                if (brier.doubleValue() > maxBrier) failures.add(String.format(
                    "概率校准误差过高(Brier): %.4f > %.4f", brier.doubleValue(), maxBrier));
            }
        } else if (modelType.contains("regression")) {
            double r2 = number(metrics.get("test_r2"), -999).doubleValue();
            double minR2 = doublePolicy(policy, "minR2", 0.0);
            evaluated.put("testR2", r2);
            if (r2 < minR2) failures.add(String.format("测试 R² 未达标: %.4f < %.4f", r2, minR2));
        } else if (modelType.contains("clustering")) {
            int clusters = number(metrics.get("n_clusters"), 0).intValue();
            double silhouette = number(metrics.get("silhouette_score"), -1).doubleValue();
            int minimumClusters = intPolicy(policy, "minClusters", 2);
            double minimumSilhouette = doublePolicy(policy, "minSilhouetteScore", 0.20);
            evaluated.put("clusters", clusters);
            evaluated.put("silhouetteScore", silhouette);
            if (clusters < minimumClusters) failures.add("有效聚类数量不足: " + clusters + " < " + minimumClusters);
            if (silhouette < minimumSilhouette) failures.add(String.format(
                "聚类轮廓系数未达标: %.4f < %.4f", silhouette, minimumSilhouette));
        } else if (modelType.contains("anomaly")) {
            Map<String, Object> sizes = parseJsonMap(objectMapper.valueToTree(
                metrics.getOrDefault("cluster_sizes", Map.of())).toString());
            int anomalyRows = number(sizes.get("-1"), 0).intValue();
            int normalRows = number(sizes.get("1"), 0).intValue();
            int totalRows = anomalyRows + normalRows;
            double anomalyRate = totalRows == 0 ? 0 : (double) anomalyRows / totalRows;
            double minimumRate = doublePolicy(policy, "minAnomalyRate", 0.001);
            double maximumRate = doublePolicy(policy, "maxAnomalyRate", 0.50);
            evaluated.put("anomalyRows", anomalyRows);
            evaluated.put("anomalyRate", anomalyRate);
            if (totalRows < intPolicy(policy, "minEvaluationRows", minTrainingRows)) {
                failures.add("异常检测评估样本量不足: " + totalRows);
            }
            if (anomalyRate < minimumRate || anomalyRate > maximumRate) failures.add(String.format(
                "异常占比超出治理范围: %.4f（要求 %.4f - %.4f）", anomalyRate, minimumRate, maximumRate));
        }

        if (metrics.get("overfitting_gap") instanceof Number gap
                && gap.doubleValue() > doublePolicy(policy, "maxOverfittingGap", overfittingGapThreshold)) {
            failures.add(String.format("训练/测试差距过大: %.4f", gap.doubleValue()));
        }
        if (validation.get("cv_std") instanceof Number cvStd) {
            double maxCvStd = doublePolicy(policy, "maxCvStd", 0.10);
            evaluated.put("cvStd", cvStd.doubleValue());
            if (cvStd.doubleValue() > maxCvStd) failures.add(String.format(
                "交叉验证波动过大: %.4f > %.4f", cvStd.doubleValue(), maxCvStd));
        }
        if (model.getMonitoringBaseline() == null || model.getMonitoringBaseline().isBlank()) {
            failures.add("缺少上线漂移监控基线");
        }
        if ("drift_critical".equals(model.getEvaluationStatus())) {
            failures.add("最近一次漂移检查为 Critical，必须分析或重新训练后才能发布");
        }

        boolean requireApproval = Boolean.TRUE.equals(policy.get("requireApproval"));
        boolean qualityPassed = failures.isEmpty();
        if (checkApproval && requireApproval && !"approved".equals(model.getEvaluationStatus())) {
            failures.add("治理策略要求具备模型审批权限的人员人工审批");
        }
        return new GovernanceReport(failures.isEmpty(), qualityPassed, requireApproval,
            List.copyOf(failures), List.copyOf(warnings), Map.copyOf(evaluated));
    }

    private Number number(Object value, Number fallback) {
        return value instanceof Number number ? number : fallback;
    }

    private double doublePolicy(Map<String, Object> policy, String key, double fallback) {
        return number(policy.get(key), fallback).doubleValue();
    }

    private int intPolicy(Map<String, Object> policy, String key, int fallback) {
        return number(policy.get(key), fallback).intValue();
    }

    public record GovernanceReport(boolean passed, boolean qualityPassed, boolean approvalRequired,
                                   List<String> hardFailures, List<String> warnings,
                                   Map<String, Object> evaluatedMetrics) {}

    @org.springframework.transaction.annotation.Transactional
    public MiningModel publishModel(Long modelId, Map<String, Object> config) {
        MiningModel model = resourceAccess.requireModel(modelId);
        if (!ModelStatus.TRAINED.equals(model.getStatus()) && !ModelStatus.OFFLINE.equals(model.getStatus())) {
            throw new IllegalStateException("只有训练完成或已下线的模型才能发布，当前状态: " + model.getStatus());
        }
        if (model.getModelPath() == null || model.getModelPath().isBlank()) {
            throw new IllegalStateException("模型文件不存在，请先训练模型");
        }
        if (!Integer.valueOf(MiningRuntimeClient.ARTIFACT_SCHEMA_VERSION)
                .equals(model.getArtifactSchemaVersion())) {
            throw new IllegalStateException("旧版模型制品不能发布，请先执行制品迁移（重新训练）");
        }

        // The immutable ML operator version receives the human approval. At this stage only the
        // objective training/artifact quality gates are enforced, avoiding duplicate approvals.
        GovernanceReport governance = evaluateGovernance(model, false);
        if (!governance.passed()) {
            throw new IllegalStateException("模型发布治理检查失败（force 不能绕过硬门槛）: "
                + String.join("；", governance.hardFailures()));
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
        miningOperatorRegistrationService.registerPublishedModel(model);
        logMiningEvent(model, "model_published", Map.of(
            "version", model.getVersion(),
            "scheduleEnabled", model.getScheduleEnabled(),
            "predictInputTable", model.getPredictInputTable() != null ? model.getPredictInputTable() : "",
            "predictResultTable", model.getPredictResultTable() != null ? model.getPredictResultTable() : ""
        ));
        return model;
    }

    public MiningModel offlineModel(Long modelId) {
        MiningModel model = resourceAccess.requireModel(modelId);
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
        MiningModel model = resourceAccess.requireModel(modelId);
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
        MiningModel model = resourceAccess.requireModel(modelId);
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
        resourceAccess.requireModel(modelId);
        return predictionService.predict(modelId, inputRows, saveTable);
    }

    /** Ownership-checked, side-effect-free prediction entry point for orchestration nodes. */
    public Map<String, Object> predictModelTransient(Long modelId, List<Map<String, Object>> inputRows) {
        resourceAccess.requireModel(modelId);
        return predictionService.predictTransient(modelId, inputRows);
    }

    public Map<String, Object> predictModelTransient(Long modelId, List<Map<String, Object>> inputRows,
                                                     String runtimeImage) {
        resourceAccess.requireModel(modelId);
        return predictionService.predictTransient(modelId, inputRows, runtimeImage);
    }

    public Map<String, Object> batchPredict(Long modelId) {
        resourceAccess.requireModel(modelId);
        return predictionService.batchPredict(modelId);
    }

    /** Trusted scheduler entry point; prediction records remain attached to the owned model. */
    public Map<String, Object> batchPredictForSchedule(Long modelId) {
        MiningModel model = requireScheduledModel(modelId);
        return runScheduledPrediction(model, null, null, null, null);
    }

    /** Persistent-task scheduler entry point with immutable execution-to-task provenance. */
    public Map<String, Object> batchPredictForScheduleTask(Long modelId, Long scheduleTaskId,
                                                           String inputTable, String resultTable,
                                                           String inputFilter) {
        MiningModel model = requireSchedulableModel(modelId);
        return runScheduledPrediction(model, scheduleTaskId, inputTable, resultTable, inputFilter);
    }

    private Map<String, Object> runScheduledPrediction(MiningModel model, Long scheduleTaskId,
                                                        String inputTable, String resultTable,
                                                        String inputFilter) {
        if (!ModelStatus.PUBLISHED.equals(model.getStatus())) {
            throw new IllegalStateException("定时预测只允许已发布模型");
        }
        ModelExecution execution = new ModelExecution();
        execution.setModelId(model.getId());
        execution.setScheduleTaskId(scheduleTaskId);
        execution.setTriggerType("schedule");
        execution.setExecutionKind("PREDICT");
        execution.setStatus(ModelStatus.EXEC_RUNNING);
        execution.setProgressPercent(10);
        execution.setCurrentStage("PREDICTING");
        execution.setProgressMessage("正式定时预测正在执行");
        execution.setCancelRequested(false);
        execution.setStartedAt(LocalDateTime.now());
        modelExecutionMapper.insert(execution);
        try {
            Map<String, Object> result = predictionService.batchPredictForSchedule(
                model.getId(), execution.getId(), inputTable, resultTable, inputFilter);
            execution.setStatus(ModelStatus.EXEC_SUCCESS);
            execution.setProgressPercent(100);
            execution.setCurrentStage("COMPLETED");
            execution.setProgressMessage("正式定时预测执行完成");
            execution.setOutputSummary(objectMapper.writeValueAsString(result));
            Object producedResultTable = result.get("resultTable");
            if (producedResultTable != null) execution.setArtifactPath(String.valueOf(producedResultTable));
            execution.setFinishedAt(LocalDateTime.now());
            modelExecutionMapper.updateById(execution);
            return result;
        } catch (Exception error) {
            execution.setStatus(ModelStatus.FAILED);
            execution.setProgressPercent(100);
            execution.setCurrentStage("FAILED");
            execution.setProgressMessage("正式定时预测失败");
            execution.setExecutionLog(error.getMessage());
            execution.setFinishedAt(LocalDateTime.now());
            modelExecutionMapper.updateById(execution);
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException("定时预测结果保存失败", error);
        }
    }

    public Map<String, Object> batchPredictWithOverrides(Long modelId, String inputTable, String resultTable, String inputFilter) {
        resourceAccess.requireModel(modelId);
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

    public Map<String, Object> checkDrift(Long modelId, String inputTable, String inputFilter) {
        MiningModel model = resourceAccess.requireModel(modelId);
        return checkDriftInternal(model, inputTable, inputFilter);
    }

    /** Scheduler entry point; model ownership is preserved and no user is fabricated. */
    public Map<String, Object> checkDriftForSchedule(Long modelId) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null || !ModelStatus.PUBLISHED.equals(model.getStatus())) {
            throw new IllegalStateException("漂移定时检查只允许已发布模型");
        }
        return checkDriftInternal(model, null, null);
    }

    private Map<String, Object> checkDriftInternal(MiningModel model, String inputTable, String inputFilter) {
        if (!Integer.valueOf(MiningRuntimeClient.ARTIFACT_SCHEMA_VERSION).equals(model.getArtifactSchemaVersion())) {
            throw new IllegalStateException("旧版模型没有监控基线，请重新训练");
        }
        String table = inputTable != null && !inputTable.isBlank()
            ? inputTable : model.getPredictInputTable();
        if (table == null || table.isBlank()) table = model.getSourceTable();
        com.smartquery.common.IdentifierValidator.validateTableName(table);
        String filter = inputFilter != null ? inputFilter : model.getPredictInputFilter();
        if (filter != null && !filter.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateFilter(filter);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("modelPath", model.getModelPath());
        request.put("inputTable", table);
        request.put("inputFilter", filter);
        MiningRuntimeClient.RuntimeResult runtime = miningRuntimeClient.execute(
            "drift", request, model.getDataSourceId(), pythonTimeoutMs);
        if (!runtime.successful()) {
            throw new IllegalStateException("漂移检查失败: " + runtime.errorMessage());
        }
        Map<String, Object> result = runtime.payload();
        model.setLastDriftMetrics(toJson(result));
        model.setLastDriftAt(LocalDateTime.now());
        String driftStatus = String.valueOf(result.getOrDefault("drift_status", "unknown"));
        if ("critical".equals(driftStatus)) model.setEvaluationStatus("drift_critical");
        else if ("warning".equals(driftStatus)) model.setEvaluationStatus("drift_warning");
        miningModelMapper.updateById(model);
        return result;
    }

    public List<PredictionResult> getPredictionResults(Long modelId, int limit) {
        resourceAccess.requireModel(modelId);
        return predictionService.getPredictionResults(modelId, limit);
    }

    public Map<String, Object> executePipeline(Long pipelineId) {
        resourceAccess.requirePipeline(pipelineId);
        return pipelineService.executePipeline(pipelineId);
    }

    public Executor getMiningExecutor() {
        return miningExecutor;
    }

    public void syncPipelineToModel(Long pipelineId) {
        MiningPipeline pipeline = resourceAccess.requirePipeline(pipelineId);

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
            if (model.getAlgorithm() != null && !model.getAlgorithm().isBlank()) {
                algorithmService.applyBinding(model,
                    algorithmService.activeBinding(model.getAlgorithm(), model.getModelType()));
            }
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
            model.setGroupColumns(toJson(cfg.groupColumns()));
            model.setOosTable(cfg.oosTable());
            model.setOosFilter(cfg.oosFilter());
            model.setPositiveClass(cfg.positiveClass());
            model.setCalibrationMethod(cfg.calibrationMethod());
            model.setThresholdPolicy(toJson(cfg.thresholdPolicy()));
            model.setGovernancePolicy(toJson(cfg.governancePolicy()));

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
        MiningModel model = resourceAccess.requireModel(modelId);
        ModelExecution execution = resourceAccess.requireModelExecution(modelId, executionId);
        if (!ModelStatus.EXEC_SUCCESS.equals(execution.getStatus())) {
            throw new IllegalArgumentException("只能回滚到成功的执行记录");
        }

        if (execution.getHyperparameters() != null) {
            model.setHyperparameters(execution.getHyperparameters());
        }
        if (execution.getMetrics() != null) {
            model.setMetrics(execution.getMetrics());
        }
        if (execution.getArtifactPath() == null || execution.getArtifactSchemaVersion() == null) {
            throw new IllegalArgumentException("该历史执行没有版本化 Pipeline 制品，不能安全回滚；请重新训练");
        }
        model.setModelPath(execution.getArtifactPath());
        model.setArtifactSha256(execution.getArtifactSha256());
        model.setArtifactSchemaVersion(execution.getArtifactSchemaVersion());
        if (execution.getAlgorithmSnapshot() != null && !execution.getAlgorithmSnapshot().isBlank()) {
            model.setAlgorithm(execution.getAlgorithmId());
            model.setAlgorithmVersion(execution.getAlgorithmVersion());
            model.setAlgorithmSnapshot(execution.getAlgorithmSnapshot());
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

    // ======================== Versioned Training Protocol ========================

    private Map<String, Object> buildTrainingRequest(MiningModel model, ModelExecution execution,
                                                     String inputFilter) {
        com.smartquery.common.IdentifierValidator.validateTableName(model.getSourceTable());
        if (model.getTargetColumn() != null && !model.getTargetColumn().isBlank()) {
            com.smartquery.common.IdentifierValidator.validateColumnName(model.getTargetColumn());
        }
        if (model.getTemporalColumn() != null && !model.getTemporalColumn().isBlank()) {
            com.smartquery.common.IdentifierValidator.validateColumnName(model.getTemporalColumn());
        }
        if (model.getOosTable() != null && !model.getOosTable().isBlank()) {
            com.smartquery.common.IdentifierValidator.validateTableName(model.getOosTable());
        }
        if (model.getOosFilter() != null && !model.getOosFilter().isBlank()) {
            com.smartquery.common.IdentifierValidator.validateFilter(model.getOosFilter());
        }
        List<String> groupColumns = parseJsonList(model.getGroupColumns());
        groupColumns.forEach(com.smartquery.common.IdentifierValidator::validateColumnName);

        String resolvedFilter = inputFilter;
        if ((resolvedFilter == null || resolvedFilter.isBlank())
                && model.getPredictInputFilter() != null && !model.getPredictInputFilter().isBlank()) {
            resolvedFilter = model.getPredictInputFilter();
        }
        resolvedFilter = resolveFilterVariables(resolvedFilter);
        if (resolvedFilter != null && !resolvedFilter.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateFilter(resolvedFilter);
        }

        boolean missingSnapshot = model.getAlgorithmSnapshot() == null || model.getAlgorithmSnapshot().isBlank();
        AlgorithmService.AlgorithmBinding algorithm = algorithmService.resolveModelBinding(model);
        algorithmService.applyBinding(model, algorithm);
        if (missingSnapshot) {
            // Upgrade legacy models once so subsequent retraining no longer depends on a mutable catalog row.
            miningModelMapper.updateById(model);
        }

        int nextVersion = model.getVersion() == null ? 1 : model.getVersion() + 1;
        String artifactName = "model_" + model.getId() + "_v" + nextVersion
            + "_execution_" + execution.getId() + ".joblib";

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("mode", validationMode(model));
        validation.put("cvFolds", model.getCvFolds() != null ? model.getCvFolds() : 5);
        validation.put("testSize", model.getTestSize() != null ? model.getTestSize() : 0.2);
        validation.put("temporalColumn", model.getTemporalColumn());
        validation.put("groupColumns", groupColumns);
        validation.put("oosTable", model.getOosTable());
        validation.put("oosFilter", model.getOosFilter());
        validation.put("randomState", randomState);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("modelId", model.getId());
        request.put("modelVersion", nextVersion);
        request.put("modelType", model.getModelType());
        request.put("algorithmId", algorithm.algorithmId());
        request.put("algorithmVersion", algorithm.versionNo());
        request.put("algorithmCode", algorithm.pythonCodeTemplate());
        request.put("sourceTable", model.getSourceTable());
        request.put("featureColumns", parseJsonList(model.getFeatureColumns()));
        request.put("targetColumn", model.getTargetColumn());
        Map<String, Object> preprocessing = parseJsonMap(model.getPreprocessing());
        request.put("preprocessing", preprocessing);
        request.put("targetPreprocessing",
            preprocessing.get("targetPreprocessing") instanceof Map<?, ?> targetConfig
                ? targetConfig : Map.of());
        request.put("hyperparameters", parseJsonMap(model.getHyperparameters()));
        request.put("validation", validation);
        request.put("inputFilter", resolvedFilter);
        request.put("artifactPath", Path.of(modelWorkspace, artifactName).toString());
        request.put("overfittingGapThreshold", overfittingGapThreshold);
        request.put("positiveClass", model.getPositiveClass());
        request.put("calibrationMethod", model.getCalibrationMethod() == null
            ? "none" : model.getCalibrationMethod());
        request.put("thresholdPolicy", parseJsonMap(model.getThresholdPolicy()));
        return request;
    }

    private String resolveFilterVariables(String filter) {
        if (filter == null || filter.isBlank()) return null;
        java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        java.time.LocalDate today = java.time.LocalDate.now();
        String todayValue = "'" + today.format(format) + "'";
        String yesterdayValue = "'" + today.minusDays(1).format(format) + "'";
        String resolved = filter
            .replace("'${etl_date}'", todayValue)
            .replace("'${today}'", todayValue)
            .replace("'${yesterday}'", yesterdayValue)
            .replace("${etl_date}", todayValue)
            .replace("${today}", todayValue)
            .replace("${yesterday}", yesterdayValue);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("'?\\$\\{today-(\\d+)\\}'?")
            .matcher(resolved);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            int daysAgo = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(buffer,
                java.util.regex.Matcher.quoteReplacement("'" + today.minusDays(daysAgo).format(format) + "'"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
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
        evConfig.put("groupColumns", parseJsonList(model.getGroupColumns()));
        if (model.getOosTable() != null) evConfig.put("oosTable", model.getOosTable());
        if (model.getOosFilter() != null) evConfig.put("oosFilter", model.getOosFilter());
        if (model.getPositiveClass() != null) evConfig.put("positiveClass", model.getPositiveClass());
        evConfig.put("calibrationMethod", model.getCalibrationMethod() == null ? "none" : model.getCalibrationMethod());
        evConfig.put("thresholdPolicy", parseJsonMap(model.getThresholdPolicy()));
        evConfig.put("governancePolicy", parseJsonMap(model.getGovernancePolicy()));
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
        pipeline.setUserId(model.getUserId());
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
        MiningPipeline ownedPipeline = resourceAccess.requirePipeline(pipelineId);
        MiningModel ownedModel = resourceAccess.requireModel(model == null ? null : model.getId());
        if (ownedPipeline.getUserId() != null && ownedModel.getUserId() != null
                && !ownedPipeline.getUserId().equals(ownedModel.getUserId())) {
            throw new com.smartquery.common.BusinessException(403, "模型与流水线不属于同一用户");
        }
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
                        config.put("groupColumns", parseJsonList(model.getGroupColumns()));
                        if (model.getOosTable() != null) config.put("oosTable", model.getOosTable());
                        if (model.getOosFilter() != null) config.put("oosFilter", model.getOosFilter());
                        if (model.getPositiveClass() != null) config.put("positiveClass", model.getPositiveClass());
                        config.put("calibrationMethod", model.getCalibrationMethod() == null ? "none" : model.getCalibrationMethod());
                        config.put("thresholdPolicy", parseJsonMap(model.getThresholdPolicy()));
                        config.put("governancePolicy", parseJsonMap(model.getGovernancePolicy()));
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

    private MiningModel requireScheduledModel(Long modelId) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            throw new IllegalArgumentException("模型不存在: " + modelId);
        }
        if (!Boolean.TRUE.equals(model.getScheduleEnabled())) {
            throw new IllegalStateException("模型未启用定时任务: " + modelId);
        }
        return model;
    }

    private MiningModel requireSchedulableModel(Long modelId) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null || Integer.valueOf(1).equals(model.getDeleted())) {
            throw new IllegalArgumentException("模型不存在: " + modelId);
        }
        return model;
    }

    // ======================== Utilities ========================

    public DataSource getDataSource(Long dataSourceId) { return dataSourceMapper.selectById(dataSourceId); }
    public JdbcTemplate getJdbcTemplate(DataSource ds) { return dataSourceManager.getJdbcTemplate(ds.getId()); }

    private boolean isNumericType(String dataType) {
        if (dataType == null) return false;
        return Set.of("int", "bigint", "tinyint", "smallint", "mediumint", "float", "double", "decimal", "numeric", "real")
            .contains(dataType.toLowerCase());
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
        return MiningLogUtils.toJson(obj, objectMapper);
    }

    private String validationMode(MiningModel model) {
        String mode = model.getValidationMode();
        return (mode != null && !mode.isBlank()) ? mode : "train_test";
    }

    private String truncateLog(String log, int maxLen) {
        return MiningLogUtils.truncateLog(log, maxLen, false);
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

    private void logMiningError(Long conversationId, String errorType, Long modelId, String error) {
        eventLogger.logEvent(conversationId, null, "mining_error",
            Map.of("errorType", errorType, "modelId", modelId, "error", error));
    }
}
