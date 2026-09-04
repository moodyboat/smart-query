package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.ModelStatus;
import com.smartquery.common.NodeType;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.*;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.logging.DiagnosticsTimer;
import com.smartquery.mapper.*;
import com.smartquery.python.PythonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    /** Pipeline 节点类型的标准顺序（排序/校验共用） */
    private static final List<String> NODE_TYPE_ORDER = List.of(
        NodeType.DATA_SOURCE, NodeType.PREPROCESSING, NodeType.FILL_MISSING, NodeType.FEATURE_ENGINEERING, NodeType.TRAINING, NodeType.EVALUATION, NodeType.OUTPUT);

    @Value("${smart-query.mining.workspace:${user.home}/smartquery-models}")
    private String modelWorkspace;

    private final MiningPipelineMapper miningPipelineMapper;
    private final MiningModelMapper miningModelMapper;
    private final ModelExecutionMapper modelExecutionMapper;
    private final DataSourceMapper dataSourceMapper;
    private final MiningRuntimeClient miningRuntimeClient;
    private final DataSourceManager dataSourceManager;
    private final AlgorithmService algorithmService;
    private final ObjectMapper objectMapper;
    private final ConversationEventLogger eventLogger;
    private final ResourceAccessService resourceAccess;

    @Value("${smart-query.pipeline.execution-timeout-ms:600000}")
    private int executionTimeoutMs;

    @Value("${smart-query.mining.random-state:42}")
    private int randomState;

    @Value("${smart-query.mining.overfitting-gap-threshold:0.15}")
    private double overfittingGapThreshold;

    @Value("${smart-query.pipeline.preview-timeout-ms:120000}")
    private int previewTimeoutMs;

    @Value("${smart-query.pipeline.default-cv-folds:5}")
    private int defaultCvFolds;

    @Value("${smart-query.pipeline.default-test-size:0.2}")
    private double defaultTestSize;

    @Value("${smart-query.pipeline.default-bins:5}")
    private int defaultBins;

    @Value("${smart-query.pipeline.sample-rows:10}")
    private int sampleRows;

    @Value("${smart-query.pipeline.error-truncation:5000}")
    private int errorTruncation;

    @Value("${smart-query.pipeline.log-truncation:4000}")
    private int logTruncation;

    @Value("${smart-query.pipeline.event-error-truncation:300}")
    private int eventErrorTruncation;

    @Value("${smart-query.pipeline.execution-log-truncation:50000}")
    private int executionLogTruncation;

    @Value("${smart-query.pipeline.preview-error-truncation:2000}")
    private int previewErrorTruncation;

    @SuppressWarnings("unchecked")
    public Map<String, Object> executePipeline(Long pipelineId) {
        MiningPipeline pipeline = resourceAccess.requirePipeline(pipelineId);

        List<Map<String, Object>> nodes;
        try {
            nodes = objectMapper.readValue(pipeline.getNodes(), List.class);
        } catch (Exception e) {
            throw new RuntimeException("流水线节点解析失败: " + e.getMessage());
        }
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalStateException("流水线没有节点");
        }

        // Sort nodes by type order
        List<String> typeOrder = NODE_TYPE_ORDER;
        nodes.sort((a, b) -> {
            int ia = typeOrder.indexOf(a.get("type"));
            int ib = typeOrder.indexOf(b.get("type"));
            return Integer.compare(ia < 0 ? 99 : ia, ib < 0 ? 99 : ib);
        });

        // Extract configuration from nodes
        PipelineConfig cfg = extractConfig(nodes);
        validateConfig(cfg);

        // Validate pipeline structure before execution
        Map<String, Object> validationResult = validatePipelineStructure(nodes, pipeline.getDataSourceId());
        if (!(boolean) validationResult.get("valid")) {
            List<String> errs = (List<String>) validationResult.get("errors");
            throw new IllegalStateException("Pipeline验证失败: " + String.join("; ", errs));
        }

        DataSource ds = dataSourceMapper.selectById(pipeline.getDataSourceId());
        if (ds == null) throw new IllegalStateException("数据源不存在");

        // Validate source table exists
        com.smartquery.common.IdentifierValidator.validateTableName(cfg.sourceTable);
        try {
            JdbcTemplate jdbc = dataSourceManager.getJdbcTemplate(ds.getId());
            jdbc.queryForObject("SELECT 1 FROM `" + cfg.sourceTable + "` LIMIT 1", Integer.class);
        } catch (Exception e) {
            throw new IllegalStateException("源表 '" + cfg.sourceTable + "' 不存在或无法访问: " + e.getMessage());
        }

        String modelFilename = "pipeline_" + pipelineId + "_run_" + System.currentTimeMillis() + ".joblib";
        String modelPath = Path.of(modelWorkspace, modelFilename).toString();
        Map<String, Object> runtimeRequest = buildPipelineTrainingRequest(cfg, modelPath);

        // Atomic status check: only proceed if not already running
        int updated = miningPipelineMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningPipeline>()
                .eq(MiningPipeline::getId, pipelineId)
                .ne(MiningPipeline::getStatus, com.smartquery.common.ModelStatus.EXEC_RUNNING)
                .set(MiningPipeline::getStatus, com.smartquery.common.ModelStatus.EXEC_RUNNING)
                .set(MiningPipeline::getExecutionLog, null));
        if (updated == 0) {
            throw new IllegalStateException("流水线正在执行中，请等待完成");
        }

        log.info("[PIPELINE] Executing pipeline {}: {} nodes, table={}, algo={}",
            pipelineId, nodes.size(), cfg.sourceTable, cfg.algorithm);

        // Log per-node trace events
        for (Map<String, Object> node : nodes) {
            String nodeId = String.valueOf(node.getOrDefault("id", "unknown"));
            String nodeType = String.valueOf(node.getOrDefault("type", "unknown"));
            eventLogger.logEvent(pipeline.getConversationId(), null, "pipeline_node_start", Map.of(
                "pipelineId", pipelineId, "nodeId", nodeId, "nodeType", nodeType
            ));
        }

        long pipelineStartMs = System.currentTimeMillis();
        String execLog;
        Map<String, Object> result;
        try {
            MiningRuntimeClient.RuntimeResult runtime = DiagnosticsTimer.timedSupply(
                "pipeline.execute",
                () -> miningRuntimeClient.execute("train", runtimeRequest,
                    pipeline.getDataSourceId(), executionTimeoutMs)
            );
            PythonResult pr = runtime.process();
            execLog = pr.stdout();
            if (!runtime.successful()) {
                String err = runtime.errorMessage();
                log.error("[PIPELINE] Python runtime failed for pipeline {} (exit={}, time={}ms): {}",
                    pipelineId, pr.exitCode(), pr.executionTimeMs(), truncateLog(err, errorTruncation));
                throw new RuntimeException("Pipeline执行失败: " + truncateLog(err, errorTruncation));
            }
            result = new LinkedHashMap<>(runtime.payload());
            if (!(result.get("metrics") instanceof Map<?, ?>) || result.get("model_path") == null) {
                throw new IllegalStateException("Python 流水线结果缺少 metrics 或 model_path");
            }
            result.put("modelPath", modelPath);
            result.put("algorithm", cfg.algorithm);
            result.put("sourceTable", cfg.sourceTable);
        } catch (Exception e) {
            miningPipelineMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningPipeline>()
                    .eq(MiningPipeline::getId, pipelineId)
                    .set(MiningPipeline::getStatus, com.smartquery.common.ModelStatus.FAILED)
                    .set(MiningPipeline::getExecutionLog, truncateLog(e.getMessage(), logTruncation))
                    .set(MiningPipeline::getLastExecutedAt, LocalDateTime.now()));
            // Log node completion events with failure
            long failDur = System.currentTimeMillis() - pipelineStartMs;
            for (Map<String, Object> node : nodes) {
                eventLogger.logEvent(pipeline.getConversationId(), null, "pipeline_node_end", Map.of(
                    "pipelineId", pipelineId,
                    "nodeId", String.valueOf(node.getOrDefault("id", "unknown")),
                    "nodeType", String.valueOf(node.getOrDefault("type", "unknown")),
                    "status", com.smartquery.common.ModelStatus.FAILED, "durationMs", failDur,
                    "error", truncateLog(e.getMessage(), eventErrorTruncation)
                ));
            }
            throw e;
        }

        MiningModel model = createOrUpdatePipelineModel(pipeline, result, cfg, modelPath, runtimeRequest);

        ModelExecution execution = new ModelExecution();
        execution.setModelId(model.getId());
        execution.setTriggeredByUserId(com.smartquery.common.UserContextHolder.require().userId().toString());
        execution.setTriggerType("manual");
        execution.setStatus(ModelStatus.EXEC_SUCCESS);
        execution.setHyperparameters(toJson(cfg.hyperparams));
        execution.setAlgorithmId(model.getAlgorithm());
        execution.setAlgorithmVersion(model.getAlgorithmVersion());
        execution.setAlgorithmSnapshot(model.getAlgorithmSnapshot());
        execution.setMetrics(toJson(result.get("metrics")));
        execution.setExecutionLog(truncateLog(execLog, executionLogTruncation));
        execution.setExecutionTimeMs((int)(System.currentTimeMillis() - pipelineStartMs));
        execution.setProgressPercent(100);
        execution.setCurrentStage("COMPLETED");
        execution.setProgressMessage("流程训练完成");
        execution.setArtifactPath(modelPath);
        if (result.get("artifact_sha256") != null) execution.setArtifactSha256(String.valueOf(result.get("artifact_sha256")));
        if (result.get("artifact_schema_version") instanceof Number version) execution.setArtifactSchemaVersion(version.intValue());
        execution.setStartedAt(LocalDateTime.now().minusNanos((System.currentTimeMillis() - pipelineStartMs) * 1_000_000));
        execution.setFinishedAt(LocalDateTime.now());
        modelExecutionMapper.insert(execution);

        result.put("modelId", model.getId());
        result.put("modelName", model.getName());
        result.put("status", com.smartquery.common.ModelStatus.TRAINED);
        result.put("modelType", model.getModelType());
        result.put("featureImportance", result.get("feature_importance"));

        pipeline.setStatus(com.smartquery.common.ModelStatus.PIPELINE_COMPLETED);
        pipeline.setLastExecutedAt(LocalDateTime.now());
        pipeline.setExecutionLog(truncateLog(execLog, logTruncation));
        miningPipelineMapper.updateById(pipeline);

        log.info("[PIPELINE] Pipeline {} completed, model={}", pipelineId, model.getId());

        // Log per-node completion events
        long totalDur = System.currentTimeMillis() - pipelineStartMs;
        int nodeDur = nodes.isEmpty() ? 0 : (int)(totalDur / nodes.size());
        for (Map<String, Object> node : nodes) {
            eventLogger.logEvent(pipeline.getConversationId(), null, "pipeline_node_end", Map.of(
                "pipelineId", pipelineId,
                "nodeId", String.valueOf(node.getOrDefault("id", "unknown")),
                "nodeType", String.valueOf(node.getOrDefault("type", "unknown")),
                "status", "success", "durationMs", nodeDur
            ));
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> executePipelineStreamed(Long pipelineId, java.util.function.BiConsumer<String, Map<String, Object>> eventConsumer) {
        MiningPipeline pipeline = resourceAccess.requirePipeline(pipelineId);
        List<Map<String, Object>> nodes;
        try {
            nodes = objectMapper.readValue(pipeline.getNodes(), List.class);
        } catch (Exception e) {
            throw new RuntimeException("流水线节点解析失败: " + e.getMessage());
        }
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalStateException("流水线没有节点");
        }

        List<String> typeOrder = NODE_TYPE_ORDER;
        nodes.sort((a, b) -> {
            int ia = typeOrder.indexOf(a.get("type"));
            int ib = typeOrder.indexOf(b.get("type"));
            return Integer.compare(ia < 0 ? 99 : ia, ib < 0 ? 99 : ib);
        });

        PipelineConfig cfg = extractConfig(nodes);
        validateConfig(cfg);

        Map<String, Object> validationResult = validatePipelineStructure(nodes, pipeline.getDataSourceId());
        if (!(boolean) validationResult.get("valid")) {
            List<String> errs = (List<String>) validationResult.get("errors");
            throw new IllegalStateException("Pipeline验证失败: " + String.join("; ", errs));
        }

        DataSource ds = dataSourceMapper.selectById(pipeline.getDataSourceId());
        if (ds == null) throw new IllegalStateException("数据源不存在");

        com.smartquery.common.IdentifierValidator.validateTableName(cfg.sourceTable);
        try {
            JdbcTemplate jdbc = dataSourceManager.getJdbcTemplate(ds.getId());
            jdbc.queryForObject("SELECT 1 FROM `" + cfg.sourceTable + "` LIMIT 1", Integer.class);
        } catch (Exception e) {
            throw new IllegalStateException("源表 '" + cfg.sourceTable + "' 不存在或无法访问: " + e.getMessage());
        }

        String modelFilename = "pipeline_" + pipelineId + "_run_" + System.currentTimeMillis() + ".joblib";
        String modelPath = Path.of(modelWorkspace, modelFilename).toString();
        Map<String, Object> runtimeRequest = buildPipelineTrainingRequest(cfg, modelPath);

        int updated = miningPipelineMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningPipeline>()
                .eq(MiningPipeline::getId, pipelineId)
                .ne(MiningPipeline::getStatus, com.smartquery.common.ModelStatus.EXEC_RUNNING)
                .set(MiningPipeline::getStatus, com.smartquery.common.ModelStatus.EXEC_RUNNING)
                .set(MiningPipeline::getExecutionLog, null));
        if (updated == 0) {
            throw new IllegalStateException("流水线正在执行中，请等待完成");
        }

        long pipelineStartMs = System.currentTimeMillis();
        try {
            for (Map<String, Object> node : nodes) {
                eventConsumer.accept("node_progress", Map.of(
                    "nodeId", String.valueOf(node.getOrDefault("id", "unknown")),
                    "nodeType", String.valueOf(node.getOrDefault("type", "unknown")),
                    "status", "running"
                ));
            }
            MiningRuntimeClient.RuntimeResult runtime = DiagnosticsTimer.timedSupply(
                "pipeline.execute",
                () -> miningRuntimeClient.execute("train", runtimeRequest,
                    pipeline.getDataSourceId(), executionTimeoutMs)
            );
            PythonResult pr = runtime.process();

            if (!runtime.successful()) {
                String err = runtime.errorMessage();
                Map<String, Object> errorData = new LinkedHashMap<>();
                errorData.put("error", truncateLog(err, errorTruncation));
                eventConsumer.accept("pipeline_error", errorData);

                miningPipelineMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningPipeline>()
                        .eq(MiningPipeline::getId, pipelineId)
                        .set(MiningPipeline::getStatus, com.smartquery.common.ModelStatus.FAILED)
                        .set(MiningPipeline::getExecutionLog, truncateLog(err, logTruncation))
                        .set(MiningPipeline::getLastExecutedAt, LocalDateTime.now()));

                long failDur = System.currentTimeMillis() - pipelineStartMs;
                for (Map<String, Object> node : nodes) {
                    eventLogger.logEvent(pipeline.getConversationId(), null, "pipeline_node_end", Map.of(
                        "pipelineId", pipelineId,
                        "nodeId", String.valueOf(node.getOrDefault("id", "unknown")),
                        "nodeType", String.valueOf(node.getOrDefault("type", "unknown")),
                        "status", com.smartquery.common.ModelStatus.FAILED, "durationMs", failDur
                    ));
                }
                throw new RuntimeException("Pipeline执行失败: " + truncateLog(err, errorTruncation));
            }

            Map<String, Object> result = new LinkedHashMap<>(runtime.payload());
            if (!(result.get("metrics") instanceof Map<?, ?>) || result.get("model_path") == null) {
                throw new IllegalStateException("Python 流水线结果缺少 metrics 或 model_path");
            }
            result.put("modelPath", modelPath);
            result.put("algorithm", cfg.algorithm);
            result.put("sourceTable", cfg.sourceTable);

            MiningModel model = createOrUpdatePipelineModel(pipeline, result, cfg, modelPath, runtimeRequest);

            ModelExecution execution = new ModelExecution();
            execution.setModelId(model.getId());
            execution.setTriggeredByUserId(com.smartquery.common.UserContextHolder.require().userId().toString());
            execution.setTriggerType("manual");
            execution.setStatus(ModelStatus.EXEC_SUCCESS);
            execution.setHyperparameters(toJson(cfg.hyperparams));
            execution.setAlgorithmId(model.getAlgorithm());
            execution.setAlgorithmVersion(model.getAlgorithmVersion());
            execution.setAlgorithmSnapshot(model.getAlgorithmSnapshot());
            execution.setMetrics(toJson(result.get("metrics")));
            execution.setExecutionLog(truncateLog(pr.stdout(), executionLogTruncation));
            execution.setExecutionTimeMs((int)(System.currentTimeMillis() - pipelineStartMs));
            execution.setProgressPercent(100);
            execution.setCurrentStage("COMPLETED");
            execution.setProgressMessage("流程训练完成");
            execution.setArtifactPath(modelPath);
            if (result.get("artifact_sha256") != null) execution.setArtifactSha256(String.valueOf(result.get("artifact_sha256")));
            if (result.get("artifact_schema_version") instanceof Number version) execution.setArtifactSchemaVersion(version.intValue());
            execution.setFinishedAt(LocalDateTime.now());
            modelExecutionMapper.insert(execution);

            // Update pipeline status
            miningPipelineMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningPipeline>()
                    .eq(MiningPipeline::getId, pipelineId)
                    .set(MiningPipeline::getStatus, ModelStatus.TRAINED)
                    .set(MiningPipeline::getExecutionLog, truncateLog(pr.stdout(), logTruncation))
                    .set(MiningPipeline::getLastSyncedAt, LocalDateTime.now())
                    .set(MiningPipeline::getLastExecutedAt, LocalDateTime.now()));

            long totalDur = System.currentTimeMillis() - pipelineStartMs;
            int nodeDur = nodes.isEmpty() ? 0 : (int)(totalDur / nodes.size());
            for (Map<String, Object> node : nodes) {
                eventConsumer.accept("node_progress", Map.of(
                    "nodeId", String.valueOf(node.getOrDefault("id", "unknown")),
                    "nodeType", String.valueOf(node.getOrDefault("type", "unknown")),
                    "status", "completed",
                    "durationMs", nodeDur
                ));
                eventLogger.logEvent(pipeline.getConversationId(), null, "pipeline_node_end", Map.of(
                    "pipelineId", pipelineId,
                    "nodeId", String.valueOf(node.getOrDefault("id", "unknown")),
                    "nodeType", String.valueOf(node.getOrDefault("type", "unknown")),
                    "status", "success", "durationMs", nodeDur
                ));
            }

            result.put("modelId", model.getId());
            result.put("modelName", model.getName());
            eventConsumer.accept("pipeline_complete", result);

            return result;
        } catch (Exception e) {
            if (!(e instanceof RuntimeException && e.getMessage() != null && e.getMessage().startsWith("Pipeline执行失败"))) {
                miningPipelineMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningPipeline>()
                        .eq(MiningPipeline::getId, pipelineId)
                        .set(MiningPipeline::getStatus, com.smartquery.common.ModelStatus.FAILED)
                        .set(MiningPipeline::getExecutionLog, truncateLog(e.getMessage(), logTruncation))
                        .set(MiningPipeline::getLastExecutedAt, LocalDateTime.now()));
                Map<String, Object> errorData = new LinkedHashMap<>();
                errorData.put("error", truncateLog(e.getMessage(), errorTruncation));
                eventConsumer.accept("pipeline_error", errorData);
            }
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    // ======================== Validation ========================

    @SuppressWarnings("unchecked")
    public Map<String, Object> validatePipeline(Long pipelineId) {
        MiningPipeline pipeline = resourceAccess.requirePipeline(pipelineId);

        List<Map<String, Object>> nodes;
        try {
            nodes = objectMapper.readValue(pipeline.getNodes(), List.class);
        } catch (Exception e) {
            return Map.of("valid", false, "errors", List.of("流水线节点解析失败: " + e.getMessage()));
        }
        if (nodes == null || nodes.isEmpty()) {
            return Map.of("valid", false, "errors", List.of("流水线没有节点"));
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> nodeTypes = new HashSet<>();

        for (Map<String, Object> node : nodes) {
            String type = String.valueOf(node.get("type"));
            String id = String.valueOf(node.get("id"));
            nodeTypes.add(type);
            Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());

            switch (type) {
                case NodeType.DATA_SOURCE -> {
                    String table = strVal(config.get("table"));
                    if (table == null || table.isBlank()) errors.add("数据源节点 [" + id + "] 未配置表名");
                    else {
                        DataSource ds = dataSourceMapper.selectById(pipeline.getDataSourceId());
                        if (ds != null) {
                            try {
                                JdbcTemplate jdbc = dataSourceManager.getJdbcTemplate(ds.getId());
                            com.smartquery.common.IdentifierValidator.validateTableName(table);
                                jdbc.queryForObject("SELECT 1 FROM `" + table + "` LIMIT 1", Integer.class);
                            } catch (Exception e) {
                                errors.add("源表 '" + table + "' 不存在或无法访问");
                            }
                        }
                    }
                }
                case NodeType.FEATURE_ENGINEERING -> {
                    if (config.get("featureColumns") == null) warnings.add("特征工程节点 [" + id + "] 未配置特征列（将自动选择）");
                    if (strVal(config.get("targetColumn")) == null) warnings.add("特征工程节点 [" + id + "] 未配置目标列");
                }
                case NodeType.TRAINING -> {
                    String algo = strVal(config.get("algorithm"));
                    if (algo == null || algo.isBlank()) errors.add("训练节点 [" + id + "] 未配置算法");
                    else if (algorithmService.getByAlgorithmId(algo) == null) errors.add("算法 '" + algo + "' 不存在");
                    if (strVal(config.get("modelType")) == null) errors.add("训练节点 [" + id + "] 未配置模型类型");
                }
                case NodeType.OUTPUT -> {
                    String table = strVal(config.get("table"));
                    if (table == null || table.isBlank()) warnings.add("输出节点 [" + id + "] 未配置目标表");
                }
            }
        }

        if (!nodeTypes.contains(NodeType.DATA_SOURCE)) errors.add("缺少数据源节点");
        if (!nodeTypes.contains(NodeType.TRAINING)) errors.add("缺少训练节点");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("nodeCount", nodes.size());
        result.put("nodeTypes", nodeTypes);
        return result;
    }

    // ===== Config extraction =====

    record PipelineConfig(
        String sourceTable, String filter,
        Map<String, Object> preprocessing,
        List<String> featureColumns, String targetColumn,
        String modelType, String algorithm,
        Map<String, Object> hyperparams,
        double testSize, int cvFold,
        String validationMode, String temporalColumn,
        List<String> groupColumns, String oosTable, String oosFilter,
        String positiveClass, String calibrationMethod,
        Map<String, Object> thresholdPolicy, Map<String, Object> governancePolicy,
        String outputTable, boolean outputAutoCreate, String outputMode,
        List<Map<String, Object>> transforms,
        Map<String, Object> targetPreprocessing
    ) {}

    @SuppressWarnings("unchecked")
    public PipelineConfig extractConfigFromNodes(String nodesJson) {
        try {
            List<Map<String, Object>> nodes = objectMapper.readValue(nodesJson, List.class);
            return extractConfig(nodes);
        } catch (Exception e) {
            log.warn("[PIPELINE] extractConfigFromNodes 解析失败，返回 null: {}", e.getMessage());
            return null;
        }
    }

    private PipelineConfig extractConfig(List<Map<String, Object>> nodes) {
        String sourceTable = null, filter = null, targetColumn = null;
        String modelType = null, algorithm = null, validationMode = null, temporalColumn = null;
        String oosTable = null, oosFilter = null, positiveClass = null, calibrationMethod = "none";
        String outputTable = null, outputMode = "append";
        boolean outputAutoCreate = false;
        double testSize = this.defaultTestSize;
        int cvFold = this.defaultCvFolds;
        Map<String, Object> preprocessing = new HashMap<>(), hyperparams = new HashMap<>();
        List<String> featureColumns = null;
        List<String> groupColumns = new ArrayList<>();
        Map<String, Object> thresholdPolicy = new LinkedHashMap<>(), governancePolicy = new LinkedHashMap<>();
        List<Map<String, Object>> transforms = new ArrayList<>();
        Map<String, Object> targetPreprocessing = null;

        for (Map<String, Object> node : nodes) {
            String type = String.valueOf(node.get("type"));
            Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());

            switch (type) {
                case NodeType.DATA_SOURCE -> {
                    sourceTable = strVal(config.get("table"));
                    filter = strVal(config.get("filter"));
                }
                case NodeType.PREPROCESSING -> {
                    String hm = strVal(config.get("handleMissing"));
                    if (hm != null && !"none".equals(hm)) preprocessing.put("handleMissing", hm);
                    String enc = strVal(config.get("encoding"));
                    if (enc != null && !"none".equals(enc)) preprocessing.put("encoding", enc);
                    String sc = strVal(config.get("scaling"));
                    if (sc != null && !"none".equals(sc)) preprocessing.put("scaling", sc);
                    Object colStrats = config.get("columnStrategies");
                    if (colStrats instanceof Map && !((Map<?, ?>) colStrats).isEmpty()) {
                        preprocessing.put("columnStrategies", colStrats);
                    }
                }
                case NodeType.FILL_MISSING -> {
                    preprocessing.put("fillMissingStrategy", strVal(config.getOrDefault("strategy", "auto")));
                    Object fillCols = config.get("columns");
                    if (fillCols instanceof List && !((List<?>) fillCols).isEmpty()) {
                        preprocessing.put("fillMissingColumns", fillCols);
                    }
                }
                case NodeType.FEATURE_ENGINEERING -> {
                    Object fc = config.get("featureColumns");
                    if (fc instanceof List) featureColumns = (List<String>) fc;
                    else if (fc instanceof String s && !s.isBlank()) {
                        try { featureColumns = objectMapper.readValue(s, List.class); }
                        catch (Exception e2) { featureColumns = Arrays.stream(s.split(",")).map(String::trim).filter(s2 -> !s2.isEmpty()).toList(); }
                    }
                    targetColumn = strVal(config.get("targetColumn"));
                    Object tf = config.get("transforms");
                    if (tf instanceof List) transforms = (List<Map<String, Object>>) tf;
                    Object tp = config.get("targetPreprocessing");
                    if (tp instanceof Map) targetPreprocessing = (Map<String, Object>) tp;
                }
                case NodeType.TRAINING -> {
                    modelType = strVal(config.get("modelType"));
                    algorithm = strVal(config.get("algorithm"));
                    Object hp = config.getOrDefault("hyperparams", config.get("hyperparameters"));
                    if (hp instanceof Map) hyperparams = (Map<String, Object>) hp;
                }
                case NodeType.EVALUATION -> {
                    if (config.get("testSize") != null) { double ts = ((Number) config.get("testSize")).doubleValue(); testSize = ts > 1 ? ts / 100.0 : ts; }
                    if (config.get("cvFold") != null) cvFold = ((Number) config.get("cvFold")).intValue();
                    if (config.get("validationMode") != null) validationMode = strVal(config.get("validationMode"));
                    if (config.get("temporalColumn") != null) temporalColumn = strVal(config.get("temporalColumn"));
                    if (config.get("groupColumns") instanceof List<?> groups)
                        groupColumns = groups.stream().map(String::valueOf).toList();
                    if (config.get("oosTable") != null) oosTable = strVal(config.get("oosTable"));
                    if (config.get("oosFilter") != null) oosFilter = strVal(config.get("oosFilter"));
                    if (config.get("positiveClass") != null) positiveClass = strVal(config.get("positiveClass"));
                    if (config.get("calibrationMethod") != null) calibrationMethod = strVal(config.get("calibrationMethod"));
                    if (config.get("thresholdPolicy") instanceof Map<?, ?> map)
                        thresholdPolicy = (Map<String, Object>) map;
                    if (config.get("governancePolicy") instanceof Map<?, ?> map)
                        governancePolicy = (Map<String, Object>) map;
                }
                case NodeType.OUTPUT -> {
                    outputTable = strVal(config.get("table"));
                    if (config.get("autoCreate") != null) outputAutoCreate = Boolean.TRUE.equals(config.get("autoCreate"));
                    outputMode = strVal(config.getOrDefault("mode", "append"));
                }
            }
        }

        return new PipelineConfig(sourceTable, filter, preprocessing, featureColumns, targetColumn,
            modelType, algorithm, hyperparams, testSize, cvFold, validationMode, temporalColumn,
            groupColumns, oosTable, oosFilter, positiveClass, calibrationMethod,
            thresholdPolicy, governancePolicy,
            outputTable, outputAutoCreate, outputMode, transforms, targetPreprocessing);
    }

    private void validateConfig(PipelineConfig cfg) {
        if (cfg.sourceTable == null || cfg.sourceTable.isBlank())
            throw new IllegalStateException("流水线缺少数据源节点或未配置表名");
        if (cfg.algorithm == null || cfg.algorithm.isBlank())
            throw new IllegalStateException("流水线缺少训练节点或未配置算法");
        if (!isUnsupervised(cfg.modelType) && (cfg.targetColumn == null || cfg.targetColumn.isBlank()))
            throw new IllegalStateException("分类/回归模型必须指定目标列，请在特征工程节点中配置");
        if ("oos".equals(cfg.validationMode) && (cfg.oosTable == null || cfg.oosTable.isBlank()))
            throw new IllegalStateException("OOS 验证必须在评估节点配置独立 oosTable");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validatePipelineStructure(List<Map<String, Object>> nodes, Long dataSourceId) {
        List<String> errors = new ArrayList<>();
        Set<String> types = new HashSet<>();
        for (Map<String, Object> node : nodes) {
            String type = String.valueOf(node.get("type"));
            types.add(type);
            Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
            if (NodeType.DATA_SOURCE.equals(type)) {
                String table = strVal(config.get("table"));
                if (table != null && dataSourceId != null) {
                    DataSource ds = dataSourceMapper.selectById(dataSourceId);
                    if (ds != null) {
                        try {
                            com.smartquery.common.IdentifierValidator.validateTableName(table);
                            JdbcTemplate jdbc = dataSourceManager.getJdbcTemplate(ds.getId());
                            jdbc.queryForObject("SELECT 1 FROM `" + table + "` LIMIT 1", Integer.class);
                        } catch (Exception e) { errors.add("源表 '" + table + "' 不存在或无法访问"); }
                    }
                }
            }
            if (NodeType.TRAINING.equals(type)) {
                String algo = strVal(config.get("algorithm"));
                if (algo != null && algorithmService.getByAlgorithmId(algo) == null) errors.add("算法 '" + algo + "' 不存在");
            }
        }
        if (!types.contains(NodeType.DATA_SOURCE)) errors.add("缺少数据源节点");
        if (!types.contains(NodeType.TRAINING)) errors.add("缺少训练节点");
        return Map.of("valid", errors.isEmpty(), "errors", errors);
    }

    private Map<String, Object> buildPipelineTrainingRequest(PipelineConfig cfg, String modelPath) {
        com.smartquery.common.IdentifierValidator.validateTableName(cfg.sourceTable);
        if (cfg.outputTable != null && !cfg.outputTable.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateTableName(cfg.outputTable);
        }
        if (cfg.targetColumn != null && !cfg.targetColumn.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateColumnName(cfg.targetColumn);
        }
        if (cfg.featureColumns != null) {
            for (String column : cfg.featureColumns) {
                com.smartquery.common.IdentifierValidator.validateColumnName(column);
            }
        }
        if (cfg.temporalColumn != null && !cfg.temporalColumn.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateColumnName(cfg.temporalColumn);
        }
        if (cfg.oosTable != null && !cfg.oosTable.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateTableName(cfg.oosTable);
        }
        if (cfg.oosFilter != null && !cfg.oosFilter.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateFilter(cfg.oosFilter);
        }
        cfg.groupColumns.forEach(com.smartquery.common.IdentifierValidator::validateColumnName);

        String filter = resolveFilterVariables(cfg.filter);
        if (filter != null && !filter.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateFilter(filter);
        }
        String modelType = cfg.modelType != null ? cfg.modelType : "classification";
        AlgorithmService.AlgorithmBinding algorithm = algorithmService.activeBinding(cfg.algorithm, modelType);

        Map<String, Object> preprocessing = new LinkedHashMap<>(cfg.preprocessing);
        if (cfg.transforms != null && !cfg.transforms.isEmpty()) {
            preprocessing.put("transforms", cfg.transforms);
        }
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("mode", cfg.validationMode != null ? cfg.validationMode : "train_test");
        validation.put("cvFolds", cfg.cvFold);
        validation.put("testSize", cfg.testSize);
        validation.put("temporalColumn", cfg.temporalColumn);
        validation.put("groupColumns", cfg.groupColumns);
        validation.put("oosTable", cfg.oosTable);
        validation.put("oosFilter", cfg.oosFilter);
        validation.put("randomState", randomState);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("modelType", modelType);
        request.put("algorithmId", algorithm.algorithmId());
        request.put("algorithmVersion", algorithm.versionNo());
        request.put("algorithmSnapshot", algorithm.snapshot());
        request.put("algorithmCode", algorithm.pythonCodeTemplate());
        request.put("sourceTable", cfg.sourceTable);
        request.put("featureColumns", cfg.featureColumns);
        request.put("targetColumn", cfg.targetColumn);
        request.put("preprocessing", preprocessing);
        request.put("targetPreprocessing", cfg.targetPreprocessing != null ? cfg.targetPreprocessing : Map.of());
        request.put("hyperparameters", cfg.hyperparams);
        request.put("validation", validation);
        request.put("inputFilter", filter);
        request.put("artifactPath", modelPath);
        request.put("outputTable", cfg.outputTable);
        request.put("outputMode", cfg.outputMode);
        request.put("overfittingGapThreshold", overfittingGapThreshold);
        request.put("positiveClass", cfg.positiveClass);
        request.put("calibrationMethod", cfg.calibrationMethod);
        request.put("thresholdPolicy", cfg.thresholdPolicy);
        return request;
    }

    // ===== Legacy preview script generation (execution uses the fixed runtime) =====

    // ===== Pipeline → Model sync =====

    private MiningModel createOrUpdatePipelineModel(MiningPipeline pipeline, Map<String, Object> result,
                                                     PipelineConfig cfg, String modelPath,
                                                     Map<String, Object> runtimeRequest) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MiningModel>()
            .eq(MiningModel::getPipelineId, pipeline.getId())
            .eq(MiningModel::getDeleted, 0);
        MiningModel existing = miningModelMapper.selectOne(wrapper);

        MiningModel model = existing != null ? existing : new MiningModel();
        model.setPipelineId(pipeline.getId());
        model.setUserId(pipeline.getUserId());
        model.setName(pipeline.getName());
        model.setDataSourceId(pipeline.getDataSourceId());
        model.setConversationId(pipeline.getConversationId());
        model.setModelType(cfg.modelType != null ? cfg.modelType : "classification");
        model.setAlgorithm(String.valueOf(runtimeRequest.get("algorithmId")));
        if (runtimeRequest.get("algorithmVersion") instanceof Number version) {
            model.setAlgorithmVersion(version.intValue());
        }
        model.setAlgorithmSnapshot(String.valueOf(runtimeRequest.get("algorithmSnapshot")));
        model.setSourceTable(cfg.sourceTable != null ? cfg.sourceTable : "");
        model.setFeatureColumns(result.get("feature_columns") instanceof List<?>
            ? toJson(result.get("feature_columns"))
            : (cfg.featureColumns != null ? toJson(cfg.featureColumns) : "[]"));
        model.setTargetColumn(cfg.targetColumn);
        Map<String, Object> preprocessing = new LinkedHashMap<>(cfg.preprocessing);
        if (cfg.transforms != null && !cfg.transforms.isEmpty()) {
            preprocessing.put("transforms", cfg.transforms);
        }
        model.setPreprocessing(toJson(preprocessing));
        model.setHyperparameters(toJson(cfg.hyperparams));
        model.setMetrics(toJson(result.get("metrics")));
        model.setFeatureImportance(toJson(result.get("feature_importance")));
        model.setModelPath(modelPath);
        if (result.get("artifact_sha256") != null) model.setArtifactSha256(String.valueOf(result.get("artifact_sha256")));
        if (result.get("artifact_schema_version") instanceof Number version) model.setArtifactSchemaVersion(version.intValue());
        model.setStatus(ModelStatus.TRAINED);
        model.setScheduleEnabled(false);
        model.setValidationMode(cfg.validationMode != null ? cfg.validationMode : "train_test");
        model.setCvFolds(cfg.cvFold);
        model.setTestSize(cfg.testSize);
        model.setTemporalColumn(cfg.temporalColumn);
        model.setGroupColumns(toJson(cfg.groupColumns));
        model.setOosTable(cfg.oosTable);
        model.setOosFilter(cfg.oosFilter);
        model.setPositiveClass(cfg.positiveClass);
        model.setCalibrationMethod(cfg.calibrationMethod);
        model.setThresholdPolicy(toJson(cfg.thresholdPolicy));
        model.setGovernancePolicy(toJson(cfg.governancePolicy));
        model.setValidationMetrics(toJson(result.get("validation")));
        model.setMonitoringBaseline(toJson(result.get("monitoring_baseline")));
        model.setEvaluationStatus("pending");

        if (existing != null) {
            model.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
            miningModelMapper.updateById(model);
        } else {
            model.setVersion(1);
            model.setDeleted(0);
            miningModelMapper.insert(model);
        }
        return model;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> previewStep(Long pipelineId, String nodeId) {
        MiningPipeline pipeline = resourceAccess.requirePipeline(pipelineId);
        List<Map<String, Object>> nodes = parsePipelineNodes(pipeline);
        String nodeType = nodes.stream()
            .filter(node -> nodeId.equals(String.valueOf(node.get("id"))))
            .map(node -> String.valueOf(node.get("type")))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + nodeId));
        return executePreview(pipeline, extractConfig(nodes), nodeId, nodeType);
    }

    public String getStepScript(Long pipelineId, String nodeId) {
        MiningPipeline pipeline = resourceAccess.requirePipeline(pipelineId);
        List<Map<String, Object>> nodes = parsePipelineNodes(pipeline);
        String nodeType = nodes.stream()
            .filter(node -> nodeId.equals(String.valueOf(node.get("id"))))
            .map(node -> String.valueOf(node.get("type")))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + nodeId));
        Map<String, Object> request = buildPipelinePreviewRequest(extractConfig(nodes), nodeType);
        request.put("nodeId", nodeId);
        request.computeIfPresent("algorithmCode", (key, value) ->
            "<stored estimator template: " + request.get("algorithmId") + ">");
        try {
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("runtime", "classpath:python/mining_runtime.py");
            description.put("action", "preview");
            description.put("transport", "JSON request/result files; stdout is logs only");
            description.put("request", request);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(description);
        } catch (Exception e) {
            throw new IllegalStateException("运行协议序列化失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> previewTrialMissingStrategy(Long pipelineId,
                                                           Map<String, String> trialStrategies) {
        MiningPipeline pipeline = resourceAccess.requirePipeline(pipelineId);
        List<Map<String, Object>> nodes = parsePipelineNodes(pipeline);
        List<Map<String, Object>> trialNodes = new ArrayList<>();
        boolean found = false;
        for (Map<String, Object> node : nodes) {
            Map<String, Object> copy = new LinkedHashMap<>(node);
            if (NodeType.PREPROCESSING.equals(node.get("type"))) {
                Map<String, Object> config = new LinkedHashMap<>(
                    (Map<String, Object>) node.getOrDefault("config", Map.of()));
                config.put("columnStrategies", trialStrategies);
                copy.put("config", config);
                found = true;
            }
            trialNodes.add(copy);
        }
        if (!found) throw new IllegalArgumentException("流水线没有预处理节点");
        Map<String, Object> result = executePreview(
            pipeline, extractConfig(trialNodes), "trial", NodeType.PREPROCESSING);
        result.put("columnStrategies", trialStrategies);
        return result;
    }

    public Map<String, Object> getSegmentedScript(Long pipelineId) {
        MiningPipeline pipeline = resourceAccess.requirePipeline(pipelineId);
        List<Map<String, Object>> nodes = parsePipelineNodes(pipeline);
        PipelineConfig cfg = extractConfig(nodes);
        Map<String, String> titleMap = Map.of(
            NodeType.DATA_SOURCE, "数据接入",
            NodeType.PREPROCESSING, "数据预处理",
            NodeType.FILL_MISSING, "填充缺失值",
            NodeType.FEATURE_ENGINEERING, "特征工程",
            NodeType.TRAINING, "模型训练与评估",
            NodeType.EVALUATION, "模型评估",
            NodeType.OUTPUT, "输出写入"
        );
        List<Map<String, Object>> segments = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            String nodeType = String.valueOf(node.get("type"));
            Map<String, Object> request = buildPipelinePreviewRequest(cfg, nodeType);
            request.put("nodeId", String.valueOf(node.getOrDefault("id", "unknown")));
            request.computeIfPresent("algorithmCode", (key, value) ->
                "<stored estimator template: " + request.get("algorithmId") + ">");
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("nodeType", nodeType);
            segment.put("title", titleMap.getOrDefault(nodeType, nodeType));
            segment.put("code", prettyJson(request));
            segments.add(segment);
        }
        Map<String, Object> fullRequest = buildPipelineTrainingRequest(
            cfg, Path.of(modelWorkspace, "pipeline_" + pipelineId + "_<execution>.joblib").toString());
        fullRequest.computeIfPresent("algorithmCode", (key, value) ->
            "<stored estimator template: " + fullRequest.get("algorithmId") + ">");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("segments", segments);
        result.put("fullScript", prettyJson(Map.of(
            "runtime", "classpath:python/mining_runtime.py",
            "action", "train",
            "request", fullRequest
        )));
        return result;
    }

    private Map<String, Object> executePreview(MiningPipeline pipeline, PipelineConfig cfg,
                                               String nodeId, String nodeType) {
        log.info("[PIPELINE] Preview via fixed runtime pipeline={}, node={} type={}",
            pipeline.getId(), nodeId, nodeType);
        try {
            MiningRuntimeClient.RuntimeResult runtime = miningRuntimeClient.execute(
                "preview", buildPipelinePreviewRequest(cfg, nodeType),
                pipeline.getDataSourceId(), previewTimeoutMs);
            if (!runtime.successful()) {
                return previewFailure(nodeId, nodeType, runtime.errorMessage());
            }
            Map<String, Object> result = new LinkedHashMap<>(runtime.payload());
            result.put("nodeId", nodeId);
            result.put("nodeType", nodeType);
            return result;
        } catch (Exception e) {
            return previewFailure(nodeId, nodeType, e.getMessage());
        }
    }

    private Map<String, Object> buildPipelinePreviewRequest(PipelineConfig cfg, String nodeType) {
        if (cfg.sourceTable == null || cfg.sourceTable.isBlank()) {
            throw new IllegalStateException("流水线缺少数据源表");
        }
        com.smartquery.common.IdentifierValidator.validateTableName(cfg.sourceTable);
        String filter = resolveFilterVariables(cfg.filter);
        if (filter != null && !filter.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateFilter(filter);
        }

        Map<String, Object> preprocessing = new LinkedHashMap<>(cfg.preprocessing);
        if (cfg.transforms != null && !cfg.transforms.isEmpty()) {
            preprocessing.put("transforms", cfg.transforms);
        }
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("mode", cfg.validationMode != null ? cfg.validationMode : "train_test");
        validation.put("cvFolds", cfg.cvFold);
        validation.put("testSize", cfg.testSize);
        validation.put("temporalColumn", cfg.temporalColumn);
        validation.put("randomState", randomState);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("nodeType", nodeType);
        request.put("sampleRows", Math.max(sampleRows, 100));
        request.put("sourceTable", cfg.sourceTable);
        request.put("inputFilter", filter);
        request.put("featureColumns", cfg.featureColumns != null ? cfg.featureColumns : List.of());
        request.put("targetColumn", cfg.targetColumn);
        request.put("modelType", cfg.modelType != null ? cfg.modelType : "classification");
        request.put("preprocessing", preprocessing);
        request.put("targetPreprocessing", cfg.targetPreprocessing != null ? cfg.targetPreprocessing : Map.of());
        request.put("hyperparameters", cfg.hyperparams);
        request.put("validation", validation);
        request.put("outputTable", cfg.outputTable);
        request.put("overfittingGapThreshold", overfittingGapThreshold);
        if (cfg.algorithm != null && !cfg.algorithm.isBlank()) {
            AlgorithmService.AlgorithmBinding algorithm = algorithmService.activeBinding(
                cfg.algorithm, cfg.modelType != null ? cfg.modelType : "classification");
            request.put("algorithmId", algorithm.algorithmId());
            request.put("algorithmVersion", algorithm.versionNo());
            request.put("algorithmCode", algorithm.pythonCodeTemplate());
        }
        return request;
    }

    private List<Map<String, Object>> parsePipelineNodes(MiningPipeline pipeline) {
        try {
            List<Map<String, Object>> nodes = objectMapper.readValue(pipeline.getNodes(), List.class);
            if (nodes == null || nodes.isEmpty()) throw new IllegalStateException("流水线没有节点");
            return nodes;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("流水线节点解析失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> previewFailure(String nodeId, String nodeType, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", nodeId);
        result.put("nodeType", nodeType);
        result.put("status", "error");
        result.put("error", truncateLog(error, previewErrorTruncation));
        return result;
    }

    private String prettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }
    // ===== Utilities =====

    private String strVal(Object val) { return val != null ? String.valueOf(val) : null; }

    private boolean isUnsupervised(String modelType) {
        if (modelType == null) return false;
        return "clustering".equals(modelType) || "anomaly_detection".equals(modelType);
    }

    private String resolveFilterVariables(String filter) {
        if (filter == null || filter.isBlank()) return null;
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        java.time.LocalDate today = java.time.LocalDate.now();
        String todayStr = "'" + today.format(fmt) + "'";
        String yesterdayStr = "'" + today.minusDays(1).format(fmt) + "'";
        String resolved = filter;
        // Handle quoted variable patterns first to avoid double-quoting
        resolved = resolved.replace("'${etl_date}'", todayStr);
        resolved = resolved.replace("'${today}'", todayStr);
        resolved = resolved.replace("'${yesterday}'", yesterdayStr);
        // Then handle unquoted variable patterns
        resolved = resolved.replace("${etl_date}", todayStr);
        resolved = resolved.replace("${today}", todayStr);
        resolved = resolved.replace("${yesterday}", yesterdayStr);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("'?\\$\\{today-(\\d+)\\}'?").matcher(resolved);
        while (m.find()) {
            int daysAgo = Integer.parseInt(m.group(1));
            resolved = resolved.replace(m.group(0), "'" + today.minusDays(daysAgo).format(fmt) + "'");
        }
        return resolved;
    }

    private String toJson(Object obj) { return MiningLogUtils.toJson(obj, objectMapper); }
    private String truncateLog(String log, int maxLen) {
        return MiningLogUtils.truncateLog(log, maxLen, true);
    }
}
