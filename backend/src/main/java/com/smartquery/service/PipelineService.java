package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.ModelStatus;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.*;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.logging.DiagnosticsTimer;
import com.smartquery.mapper.*;
import com.smartquery.python.PythonExecutor;
import com.smartquery.python.PythonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    @Value("${smart-query.mining.workspace:${user.home}/smartquery-models}")
    private String modelWorkspace;

    private final MiningPipelineMapper miningPipelineMapper;
    private final MiningModelMapper miningModelMapper;
    private final ModelExecutionMapper modelExecutionMapper;
    private final DataSourceMapper dataSourceMapper;
    private final PythonExecutor pythonExecutor;
    private final DataSourceManager dataSourceManager;
    private final AlgorithmService algorithmService;
    private final ObjectMapper objectMapper;
    private final ConversationEventLogger eventLogger;

    @Value("${pipeline.execution-timeout-ms:600000}")
    private int executionTimeoutMs;

    @Value("${pipeline.preview-timeout-ms:120000}")
    private int previewTimeoutMs;

    @Value("${pipeline.default-cv-folds:5}")
    private int defaultCvFolds;

    @Value("${pipeline.default-test-size:0.2}")
    private double defaultTestSize;

    @Value("${pipeline.default-bins:5}")
    private int defaultBins;

    @Value("${pipeline.sample-rows:10}")
    private int sampleRows;

    @SuppressWarnings("unchecked")
    public Map<String, Object> executePipeline(Long pipelineId) {
        MiningPipeline pipeline = miningPipelineMapper.selectById(pipelineId);
        if (pipeline == null || Integer.valueOf(1).equals(pipeline.getDeleted())) {
            throw new IllegalArgumentException("流水线不存在: " + pipelineId);
        }

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
        List<String> typeOrder = List.of("data_source", "preprocessing", "fill_missing", "feature_engineering", "training", "evaluation", "output");
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

        String dbUrl = buildSqlalchemyUrl(ds);
        String algoBlock = buildAlgorithmBlock(cfg.algorithm);
        String modelFilename = "pipeline_" + pipelineId + "_v" + cfg.algorithm + ".pkl";
        String modelPath = modelWorkspace + "/" + modelFilename;

        String script = buildPipelineScript(dbUrl, cfg, algoBlock, modelPath);
        log.debug("[PIPELINE] Generated script for pipeline {} ({} chars):\n{}", pipelineId, script.length(), script);

        // Atomic status check: only proceed if not already running
        int updated = miningPipelineMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningPipeline>()
                .eq(MiningPipeline::getId, pipelineId)
                .ne(MiningPipeline::getStatus, "running")
                .set(MiningPipeline::getStatus, "running")
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
            PythonResult pr = DiagnosticsTimer.timedSupply("pipeline.execute", () -> pythonExecutor.execute(script, pipeline.getDataSourceId(), executionTimeoutMs));
            execLog = pr.stdout();
            if (pr.exitCode() != 0) {
                String err = pr.stderr().isBlank() ? pr.stdout() : pr.stderr();
                log.error("[PIPELINE] Python execution failed for pipeline {} (exit={}, time={}ms)\n--- STDERR ---\n{}\n--- STDOUT ---\n{}",
                    pipelineId, pr.exitCode(), pr.executionTimeMs(), pr.stderr(), pr.stdout());
                throw new RuntimeException("Pipeline执行失败: " + truncateLog(err, 5000));
            }
            result = parseResultMarker(pr.stdout(), "[PIPELINE_RESULT]");
            result.put("modelPath", modelPath);
            result.put("algorithm", cfg.algorithm);
            result.put("sourceTable", cfg.sourceTable);
        } catch (Exception e) {
            pipeline.setStatus("failed");
            pipeline.setExecutionLog(truncateLog(e.getMessage(), 4000));
            pipeline.setLastExecutedAt(LocalDateTime.now());
            miningPipelineMapper.updateById(pipeline);
            // Log node completion events with failure
            long failDur = System.currentTimeMillis() - pipelineStartMs;
            for (Map<String, Object> node : nodes) {
                eventLogger.logEvent(pipeline.getConversationId(), null, "pipeline_node_end", Map.of(
                    "pipelineId", pipelineId,
                    "nodeId", String.valueOf(node.getOrDefault("id", "unknown")),
                    "nodeType", String.valueOf(node.getOrDefault("type", "unknown")),
                    "status", "failed", "durationMs", failDur,
                    "error", truncateLog(e.getMessage(), 300)
                ));
            }
            throw e;
        }

        MiningModel model = createOrUpdatePipelineModel(pipeline, result, cfg, modelPath);

        ModelExecution execution = new ModelExecution();
        execution.setModelId(model.getId());
        execution.setTriggerType("manual");
        execution.setStatus("success");
        execution.setHyperparameters(toJson(cfg.hyperparams));
        execution.setMetrics(toJson(result.get("metrics")));
        execution.setExecutionLog(truncateLog(execLog, 50000));
        execution.setExecutionTimeMs((int)(System.currentTimeMillis() - pipelineStartMs));
        modelExecutionMapper.insert(execution);

        result.put("modelId", model.getId());
        result.put("modelName", model.getName());
        result.put("status", "trained");
        result.put("modelType", model.getModelType());
        result.put("featureImportance", result.get("feature_importance"));

        pipeline.setStatus("completed");
        pipeline.setLastExecutedAt(LocalDateTime.now());
        pipeline.setExecutionLog(truncateLog(execLog, 4000));
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

    // ======================== Validation ========================

    @SuppressWarnings("unchecked")
    public Map<String, Object> validatePipeline(Long pipelineId) {
        MiningPipeline pipeline = miningPipelineMapper.selectById(pipelineId);
        if (pipeline == null || Integer.valueOf(1).equals(pipeline.getDeleted())) {
            return Map.of("valid", false, "errors", List.of("流水线不存在: " + pipelineId));
        }

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
                case "data_source" -> {
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
                case "feature_engineering" -> {
                    if (config.get("featureColumns") == null) warnings.add("特征工程节点 [" + id + "] 未配置特征列（将自动选择）");
                    if (strVal(config.get("targetColumn")) == null) warnings.add("特征工程节点 [" + id + "] 未配置目标列");
                }
                case "training" -> {
                    String algo = strVal(config.get("algorithm"));
                    if (algo == null || algo.isBlank()) errors.add("训练节点 [" + id + "] 未配置算法");
                    else if (algorithmService.getByAlgorithmId(algo) == null) errors.add("算法 '" + algo + "' 不存在");
                    if (strVal(config.get("modelType")) == null) errors.add("训练节点 [" + id + "] 未配置模型类型");
                }
                case "output" -> {
                    String table = strVal(config.get("table"));
                    if (table == null || table.isBlank()) warnings.add("输出节点 [" + id + "] 未配置目标表");
                }
            }
        }

        if (!nodeTypes.contains("data_source")) errors.add("缺少数据源节点");
        if (!nodeTypes.contains("training")) errors.add("缺少训练节点");

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
        String outputTable, boolean outputAutoCreate, String outputMode,
        List<Map<String, Object>> transforms
    ) {}

    @SuppressWarnings("unchecked")
    public PipelineConfig extractConfigFromNodes(String nodesJson) {
        try {
            List<Map<String, Object>> nodes = objectMapper.readValue(nodesJson, List.class);
            return extractConfig(nodes);
        } catch (Exception e) {
            return null;
        }
    }

    private PipelineConfig extractConfig(List<Map<String, Object>> nodes) {
        String sourceTable = null, filter = null, targetColumn = null;
        String modelType = null, algorithm = null, validationMode = null, temporalColumn = null;
        String outputTable = null, outputMode = "append";
        boolean outputAutoCreate = false;
        double testSize = this.defaultTestSize;
        int cvFold = this.defaultCvFolds;
        Map<String, Object> preprocessing = new HashMap<>(), hyperparams = new HashMap<>();
        List<String> featureColumns = null;
        List<Map<String, Object>> transforms = new ArrayList<>();

        for (Map<String, Object> node : nodes) {
            String type = String.valueOf(node.get("type"));
            Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());

            switch (type) {
                case "data_source" -> {
                    sourceTable = strVal(config.get("table"));
                    filter = strVal(config.get("filter"));
                }
                case "preprocessing" -> {
                    String hm = strVal(config.get("handleMissing"));
                    if (hm != null && !"none".equals(hm)) preprocessing.put("handleMissing", hm);
                    String enc = strVal(config.get("encoding"));
                    if (enc != null && !"none".equals(enc)) preprocessing.put("encoding", enc);
                    String sc = strVal(config.get("scaling"));
                    if (sc != null && !"none".equals(sc)) preprocessing.put("scaling", sc);
                }
                case "fill_missing" -> {
                    preprocessing.put("fillMissingStrategy", strVal(config.getOrDefault("strategy", "auto")));
                    Object fillCols = config.get("columns");
                    if (fillCols instanceof List && !((List<?>) fillCols).isEmpty()) {
                        preprocessing.put("fillMissingColumns", fillCols);
                    }
                }
                case "feature_engineering" -> {
                    Object fc = config.get("featureColumns");
                    if (fc instanceof List) featureColumns = (List<String>) fc;
                    else if (fc instanceof String s && !s.isBlank()) {
                        try { featureColumns = objectMapper.readValue(s, List.class); }
                        catch (Exception e2) { featureColumns = Arrays.stream(s.split(",")).map(String::trim).filter(s2 -> !s2.isEmpty()).toList(); }
                    }
                    targetColumn = strVal(config.get("targetColumn"));
                    Object tf = config.get("transforms");
                    if (tf instanceof List) transforms = (List<Map<String, Object>>) tf;
                }
                case "training" -> {
                    modelType = strVal(config.get("modelType"));
                    algorithm = strVal(config.get("algorithm"));
                    Object hp = config.getOrDefault("hyperparams", config.get("hyperparameters"));
                    if (hp instanceof Map) hyperparams = (Map<String, Object>) hp;
                }
                case "evaluation" -> {
                    if (config.get("testSize") != null) { double ts = ((Number) config.get("testSize")).doubleValue(); testSize = ts > 1 ? ts / 100.0 : ts; }
                    if (config.get("cvFold") != null) cvFold = ((Number) config.get("cvFold")).intValue();
                    if (config.get("validationMode") != null) validationMode = strVal(config.get("validationMode"));
                    if (config.get("temporalColumn") != null) temporalColumn = strVal(config.get("temporalColumn"));
                }
                case "output" -> {
                    outputTable = strVal(config.get("table"));
                    if (config.get("autoCreate") != null) outputAutoCreate = Boolean.TRUE.equals(config.get("autoCreate"));
                    outputMode = strVal(config.getOrDefault("mode", "append"));
                }
            }
        }

        return new PipelineConfig(sourceTable, filter, preprocessing, featureColumns, targetColumn,
            modelType, algorithm, hyperparams, testSize, cvFold, validationMode, temporalColumn,
            outputTable, outputAutoCreate, outputMode, transforms);
    }

    private void validateConfig(PipelineConfig cfg) {
        if (cfg.sourceTable == null || cfg.sourceTable.isBlank())
            throw new IllegalStateException("流水线缺少数据源节点或未配置表名");
        if (cfg.algorithm == null || cfg.algorithm.isBlank())
            throw new IllegalStateException("流水线缺少训练节点或未配置算法");
        if (!"clustering".equals(cfg.modelType) && (cfg.targetColumn == null || cfg.targetColumn.isBlank()))
            throw new IllegalStateException("分类/回归模型必须指定目标列，请在特征工程节点中配置");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validatePipelineStructure(List<Map<String, Object>> nodes, Long dataSourceId) {
        List<String> errors = new ArrayList<>();
        Set<String> types = new HashSet<>();
        for (Map<String, Object> node : nodes) {
            String type = String.valueOf(node.get("type"));
            types.add(type);
            Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
            if ("data_source".equals(type)) {
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
            if ("training".equals(type)) {
                String algo = strVal(config.get("algorithm"));
                if (algo != null && algorithmService.getByAlgorithmId(algo) == null) errors.add("算法 '" + algo + "' 不存在");
            }
        }
        if (!types.contains("data_source")) errors.add("缺少数据源节点");
        if (!types.contains("training")) errors.add("缺少训练节点");
        return Map.of("valid", errors.isEmpty(), "errors", errors);
    }

    // ===== Pipeline script generation =====

    private String buildPipelineScript(String dbUrl, PipelineConfig cfg, String algoBlock, String modelPath) {
        com.smartquery.common.IdentifierValidator.validateTableName(cfg.sourceTable);
        if (cfg.outputTable != null && !cfg.outputTable.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateTableName(cfg.outputTable);
        }
        String resolvedFilter = cfg.filter;
        if (resolvedFilter != null && !resolvedFilter.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateFilter(resolvedFilter);
            resolvedFilter = resolveFilterVariables(resolvedFilter);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("import pandas as pd\nimport numpy as np\nimport json\nimport os\n");
        sb.append("from sqlalchemy import create_engine, text\n\n");
        sb.append("engine = create_engine('").append(dbUrl).append("')\n");
        sb.append("_query = 'SELECT * FROM `").append(cfg.sourceTable).append("`'\n");
        if (resolvedFilter != null && !resolvedFilter.isBlank()) {
            sb.append("_filter = \"").append(resolvedFilter.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
            sb.append("_query = 'SELECT * FROM `").append(cfg.sourceTable).append("` WHERE ' + _filter\n");
        }
        sb.append("df = pd.read_sql(_query, engine)\nprint(f'[INFO] Loaded {len(df)} rows from ").append(cfg.sourceTable).append("')\n\n");

        appendPreprocessing(sb, cfg);
        appendFeatureEngineering(sb, cfg);
        appendEncodingScaling(sb, cfg);
        appendTrainEval(sb, cfg, algoBlock, modelPath);
        appendOutput(sb, cfg);

        sb.append("print('[PIPELINE_RESULT] ' + json.dumps(result))\n");
        return sb.toString();
    }

    private void appendPreprocessing(StringBuilder sb, PipelineConfig cfg) {
        String handleMissing = strVal(cfg.preprocessing.get("handleMissing"));
        if (handleMissing != null && !"none".equals(handleMissing)) {
            switch (handleMissing) {
                case "drop" -> sb.append("df = df.dropna()\nprint(f'[INFO] After dropna: {len(df)} rows')\n");
                case "fill_mean" -> {
                    sb.append("for c in df.select_dtypes(include=['number']).columns:\n    df[c] = df[c].fillna(df[c].mean())\n");
                    sb.append("for c in df.select_dtypes(include=['object']).columns:\n    df[c] = df[c].fillna(df[c].mode().iloc[0] if len(df[c].mode()) > 0 else 'unknown')\n");
                }
                case "fill_median" -> {
                    sb.append("for c in df.select_dtypes(include=['number']).columns:\n    df[c] = df[c].fillna(df[c].median())\n");
                    sb.append("for c in df.select_dtypes(include=['object']).columns:\n    df[c] = df[c].fillna(df[c].mode().iloc[0] if len(df[c].mode()) > 0 else 'unknown')\n");
                }
            }
            sb.append("\n");
        }

        String fillStrategy = strVal(cfg.preprocessing.get("fillMissingStrategy"));
        if (fillStrategy != null) {
            sb.append("# Fill missing (dedicated node)\n");
            Object fillColsObj = cfg.preprocessing.get("fillMissingColumns");
            String fillColsJson = fillColsObj instanceof List ? objectMapper.valueToTree(fillColsObj).toString() : "[]";
            sb.append("_fill_cols = ").append(fillColsJson).append("\n_fill_df = df[_fill_cols] if _fill_cols else df\n");
            sb.append("_fill_num = _fill_df.select_dtypes(include=['number']).columns.tolist()\n");
            sb.append("_fill_cat = _fill_df.select_dtypes(include=['object']).columns.tolist()\n");
            sb.append("_target_cols = _fill_cols if _fill_cols else (_fill_num + _fill_cat)\n\n");
            sb.append("for c in _target_cols:\n    if c in _fill_num: df[c] = df[c].fillna(df[c].mean())\n");
            sb.append("    elif c in _fill_cat: df[c] = df[c].fillna(df[c].mode().iloc[0] if len(df[c].mode()) > 0 else 'unknown')\n");
            sb.append("print(f'[INFO] Fill missing done: {df.isnull().sum().sum()} remaining nulls')\n\n");
        }
    }

    @SuppressWarnings("unchecked")
    private void appendFeatureEngineering(StringBuilder sb, PipelineConfig cfg) {
        String fcList = cfg.featureColumns != null ? objectMapper.valueToTree(cfg.featureColumns).toString() : "[]";
        sb.append("feature_cols = ").append(fcList).append("\nif isinstance(feature_cols, str): feature_cols = [c.strip() for c in feature_cols.split(',') if c.strip()]\n");
        sb.append("_missing = [c for c in feature_cols if c not in df.columns]\nif _missing: raise ValueError(f'特征列不存在于表中: {_missing}. 可用列: {list(df.columns)}')\n");
        sb.append("if not feature_cols:\n    feature_cols = [c for c in df.columns if c != '").append(cfg.targetColumn != null ? cfg.targetColumn : "").append("' and c not in ('id','created_at','updated_at')]\n");
        sb.append("target_col = '").append(cfg.targetColumn != null ? cfg.targetColumn : "").append("'\n");
        sb.append("if target_col and target_col not in df.columns: raise ValueError(f'目标列 \"{target_col}\" 不存在于表中. 可用列: {list(df.columns)}')\n\n");

        if (cfg.transforms != null && !cfg.transforms.isEmpty()) {
            sb.append("# Feature transforms\n");
            appendTransformsBlock(sb, cfg);
        }

        sb.append("X = df[feature_cols].copy()\ny = df[target_col].copy() if target_col in df.columns else None\n\n");
    }

    @SuppressWarnings("unchecked")
    private void appendTransformsBlock(StringBuilder sb, PipelineConfig cfg) {
        for (Map<String, Object> tf : cfg.transforms) {
            String tfType = strVal(tf.get("type"));
            Object tfCols = tf.get("columns");
            String tfColsJson = tfCols instanceof List ? objectMapper.valueToTree(tfCols).toString() : "[]";
            switch (tfType) {
                    case "log" -> {
                        sb.append("# Log transform\n");
                        sb.append("for c in ").append(tfColsJson).append(":\n");
                        sb.append("    if c in df.columns and df[c].dtype in ['float64','int64']:\n");
                        sb.append("        df[c + '_log'] = np.log1p(df[c].clip(lower=0)); feature_cols.append(c + '_log')\n");
                    }
                    case "binning" -> {
                        int bins = tf.get("bins") instanceof Number ? ((Number) tf.get("bins")).intValue() : 5;
                        String binStrategy = strVal(tf.getOrDefault("strategy", "equal_width"));
                        sb.append("# Binning (").append(binStrategy).append(", bins=").append(bins).append(")\n");
                        switch (binStrategy) {
                            case "equal_freq" -> {
                                sb.append("for c in ").append(tfColsJson).append(":\n");
                                sb.append("    if c in df.select_dtypes(include=['number']).columns:\n");
                                sb.append("        try: df[c + '_bin'] = pd.qcut(df[c], q=").append(bins).append(", labels=False, duplicates='drop'); feature_cols.append(c + '_bin')\n");
                                sb.append("        except: pass\n");
                            }
                            case "custom" -> {
                                Object edgesObj = tf.get("edges");
                                String edgesJson = edgesObj instanceof List ? objectMapper.valueToTree(edgesObj).toString() : "[]";
                                sb.append("_bin_edges = ").append(edgesJson).append("\n");
                                sb.append("for c in ").append(tfColsJson).append(":\n");
                                sb.append("    if c in df.select_dtypes(include=['number']).columns and len(_bin_edges) >= 2:\n");
                                sb.append("        df[c + '_bin'] = pd.cut(df[c], bins=_bin_edges, labels=False); feature_cols.append(c + '_bin')\n");
                            }
                            case "optimal" -> {
                                sb.append("from sklearn.tree import DecisionTreeClassifier, DecisionTreeRegressor\n");
                                sb.append("_opt_bins = ").append(bins).append("\n");
                                sb.append("for c in ").append(tfColsJson).append(":\n");
                                sb.append("    if c in df.select_dtypes(include=['number']).columns and target_col in df.columns:\n");
                                sb.append("        try:\n");
                                sb.append("            _y_tmp = df[target_col]\n");
                                sb.append("            if _y_tmp.dtype == 'object': _y_tmp = LabelEncoder().fit_transform(_y_tmp.astype(str))\n");
                                sb.append("            _dt = DecisionTreeClassifier(max_leaf_nodes=_opt_bins, random_state=42) if 'classification' in '").append(cfg.modelType != null ? cfg.modelType : "classification").append("' else DecisionTreeRegressor(max_leaf_nodes=_opt_bins, random_state=42)\n");
                                sb.append("            _dt.fit(df[[c]].fillna(0), _y_tmp)\n");
                                sb.append("            _th = sorted(set([-np.inf] + list(_dt.tree_.threshold[_dt.tree_.children_left != -1]) + [np.inf]))\n");
                                sb.append("            if len(_th) >= 2: df[c + '_bin'] = pd.cut(df[c], bins=_th, labels=False); feature_cols.append(c + '_bin')\n");
                                sb.append("        except: pass\n");
                            }
                            default -> { // equal_width
                                sb.append("for c in ").append(tfColsJson).append(":\n");
                                sb.append("    if c in df.select_dtypes(include=['number']).columns: df[c + '_bin'] = pd.cut(df[c], bins=").append(bins).append(", labels=False); feature_cols.append(c + '_bin')\n");
                            }
                        }
                    }
                    case "polynomial" -> {
                        int degree = tf.get("degree") instanceof Number ? ((Number) tf.get("degree")).intValue() : 2;
                        sb.append("# Polynomial features (degree=").append(degree).append(")\n");
                        sb.append("from sklearn.preprocessing import PolynomialFeatures as _PF\n");
                        sb.append("_tf_valid = [c for c in ").append(tfColsJson).append(" if c in df.select_dtypes(include=['number']).columns]\n");
                        sb.append("if _tf_valid and len(_tf_valid) <= 5:\n");
                        sb.append("    _pf = _PF(degree=").append(degree).append(", include_bias=False)\n");
                        sb.append("    _pf_arr = _pf.fit_transform(df[_tf_valid])\n");
                        sb.append("    _pf_names = _pf.get_feature_names_out(_tf_valid)\n");
                        sb.append("    for j, pn in enumerate(_pf_names):\n");
                        sb.append("        if pn not in _tf_valid: df[pn] = _pf_arr[:, j]; feature_cols.append(pn)\n");
                    }
                    case "standardize" -> {
                        sb.append("_tf_valid = [c for c in ").append(tfColsJson).append(" if c in df.select_dtypes(include=['number']).columns]\n");
                        sb.append("if _tf_valid:\n    from sklearn.preprocessing import StandardScaler\n    df[_tf_valid] = StandardScaler().fit_transform(df[_tf_valid])\n");
                    }
                    case "interaction" -> {
                        sb.append("_tf_valid = [c for c in ").append(tfColsJson).append(" if c in df.select_dtypes(include=['number']).columns]\n");
                        sb.append("for i in range(len(_tf_valid)):\n    for j in range(i+1, len(_tf_valid)):\n        col_name = _tf_valid[i] + '_x_' + _tf_valid[j]; df[col_name] = df[_tf_valid[i]] * df[_tf_valid[j]]; feature_cols.append(col_name)\n");
                    }
                    case "date_extract" -> {
                        String parts = strVal(tf.getOrDefault("parts", "year,month,day"));
                        sb.append("_date_parts = '").append(parts).append("'.split(',')\n");
                        sb.append("for c in ").append(tfColsJson).append(":\n");
                        sb.append("    if c in df.columns:\n");
                        sb.append("        try:\n");
                        sb.append("            _ds = pd.to_datetime(df[c])\n");
                        sb.append("            for p in _date_parts:\n");
                        sb.append("                p = p.strip()\n");
                        sb.append("                if p == 'year': df[c + '_year'] = _ds.dt.year; feature_cols.append(c + '_year')\n");
                        sb.append("                elif p == 'month': df[c + '_month'] = _ds.dt.month; feature_cols.append(c + '_month')\n");
                        sb.append("                elif p == 'day': df[c + '_day'] = _ds.dt.day; feature_cols.append(c + '_day')\n");
                        sb.append("                elif p == 'weekday': df[c + '_weekday'] = _ds.dt.weekday; feature_cols.append(c + '_weekday')\n");
                        sb.append("                elif p == 'quarter': df[c + '_quarter'] = _ds.dt.quarter; feature_cols.append(c + '_quarter')\n");
                        sb.append("        except: pass\n");
                    }
                    case "target_encode" -> {
                        sb.append("# Target encoding\n");
                        sb.append("for c in ").append(tfColsJson).append(":\n");
                        sb.append("    if c in df.select_dtypes(include=['object']).columns and target_col in df.columns:\n");
                        sb.append("        _te_map = df.groupby(c)[target_col].mean().to_dict()\n");
                        sb.append("        df[c + '_te'] = df[c].map(_te_map).fillna(df[target_col].mean()); feature_cols.append(c + '_te')\n");
                    }
                    case "frequency_encode" -> {
                        sb.append("# Frequency encoding\n");
                        sb.append("for c in ").append(tfColsJson).append(":\n");
                        sb.append("    if c in df.columns:\n");
                        sb.append("        _fe_map = df[c].value_counts(normalize=True).to_dict()\n");
                        sb.append("        df[c + '_freq'] = df[c].map(_fe_map); feature_cols.append(c + '_freq')\n");
                    }
                }
            }
        sb.append("\n");
    }

    private void appendEncodingScaling(StringBuilder sb, PipelineConfig cfg) {
        sb.append("_dt_cols = X.select_dtypes(include=['datetime', 'datetimetz']).columns.tolist()\nif _dt_cols:\n    for c in _dt_cols: X[c] = pd.to_numeric(X[c].astype('int64'), errors='coerce')\n");
        String encoding = strVal(cfg.preprocessing.getOrDefault("encoding", "label"));
        sb.append("_enc = '").append(encoding).append("'\n");
        sb.append("cat_cols = X.select_dtypes(include=['object']).columns.tolist()\nif cat_cols:\n    if _enc == 'onehot': X = pd.get_dummies(X, columns=cat_cols)\n");
        sb.append("    else:\n        from sklearn.preprocessing import LabelEncoder\n        le = LabelEncoder()\n        for c in cat_cols: X[c] = le.fit_transform(X[c].astype(str))\n");
        sb.append("if y is not None and y.dtype == 'object':\n    from sklearn.preprocessing import LabelEncoder as _LE\n    y = _LE().fit_transform(y.astype(str))\n\n");

        String scaling = strVal(cfg.preprocessing.getOrDefault("scaling", "none"));
        sb.append("_sc = '").append(scaling).append("'\nnum_cols = X.select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("if _sc == 'standard' and num_cols:\n    from sklearn.preprocessing import StandardScaler\n    X[num_cols] = StandardScaler().fit_transform(X[num_cols])\n");
        sb.append("elif _sc == 'minmax' and num_cols:\n    from sklearn.preprocessing import MinMaxScaler\n    X[num_cols] = MinMaxScaler().fit_transform(X[num_cols])\n\n");
    }

    private void appendTrainEval(StringBuilder sb, PipelineConfig cfg, String algoBlock, String modelPath) {
        sb.append("from sklearn.model_selection import train_test_split, cross_val_score\n");
        sb.append("_val_mode = '").append(cfg.validationMode != null ? cfg.validationMode : "train_test").append("'\n");
        sb.append("_test_size = ").append(cfg.testSize).append("\n_cv_folds = ").append(cfg.cvFold).append("\n");
        sb.append("_temporal_col = ").append(cfg.temporalColumn != null ? "'" + cfg.temporalColumn + "'" : "None").append("\n\n");

        sb.append("if y is not None:\n");
        sb.append("    if _val_mode == 'temporal' and _temporal_col and _temporal_col in df.columns:\n");
        sb.append("        df_sorted = df.sort_values(_temporal_col).reset_index(drop=True)\n        _split_idx = int(len(df_sorted) * (1 - _test_size))\n");
        sb.append("        X_train_df = X.iloc[df_sorted.index[:_split_idx]]\n        X_test_df = X.iloc[df_sorted.index[_split_idx:]]\n");
        sb.append("        y_train = y.iloc[df_sorted.index[:_split_idx]]\n        y_test = y.iloc[df_sorted.index[_split_idx:]]\n");
        sb.append("    else:\n        X_train_df, X_test_df, y_train, y_test = train_test_split(X, y, test_size=_test_size, random_state=42)\n\n");
        sb.append("    params = ").append(cfg.hyperparams.isEmpty() ? "{}" : objectMapper.valueToTree(cfg.hyperparams).toString()).append("\n");
        sb.append("    _model_type = '").append(cfg.modelType != null ? cfg.modelType : "classification").append("'\n");
        sb.append("    ").append(algoBlock.replace("\n", "\n    ")).append("\n    clf.fit(X_train_df, y_train)\n\n");
        sb.append("    from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score, mean_squared_error, r2_score\n");
        sb.append("    y_pred = clf.predict(X_test_df)\n    metrics = {}\n");
        sb.append("    try:\n        if _model_type == 'classification':\n");
        sb.append("            metrics['test_accuracy'] = round(float(accuracy_score(y_test, y_pred)), 4)\n");
        sb.append("            metrics['test_precision'] = round(float(precision_score(y_test, y_pred, average='weighted', zero_division=0)), 4)\n");
        sb.append("            metrics['test_recall'] = round(float(recall_score(y_test, y_pred, average='weighted', zero_division=0)), 4)\n");
        sb.append("            metrics['test_f1'] = round(float(f1_score(y_test, y_pred, average='weighted', zero_division=0)), 4)\n");
        sb.append("        elif _model_type == 'regression':\n");
        sb.append("            metrics['test_mse'] = round(float(mean_squared_error(y_test, y_pred)), 4)\n            metrics['test_r2'] = round(float(r2_score(y_test, y_pred)), 4)\n");
        sb.append("    except Exception as e: print(f'[WARN] Metrics error: {e}')\n\n");
        sb.append("    try:\n        _cv_scoring = 'f1_weighted' if _model_type == 'classification' else ('r2' if _model_type == 'regression' else None)\n");
        sb.append("        if _cv_scoring: cv_scores = cross_val_score(clf, X, y, cv=_cv_folds, scoring=_cv_scoring); metrics['cv_mean'] = round(float(cv_scores.mean()), 4); metrics['cv_std'] = round(float(cv_scores.std()), 4)\n");
        sb.append("    except Exception as e: print(f'[WARN] CV error: {e}')\n\n");
        sb.append("    importance = {}\n    try:\n        if hasattr(clf, 'feature_importances_'): importance = dict(zip(X.columns, [round(float(v), 4) for v in clf.feature_importances_]))\n");
        sb.append("        elif hasattr(clf, 'coef_'): importance = dict(zip(X.columns, [round(float(v), 4) for v in (clf.coef_[0] if len(clf.coef_.shape) > 1 else clf.coef_)]))\n");
        sb.append("    except: pass\n\n");
        sb.append("    import joblib\n    os.makedirs('").append(modelWorkspace).append("', exist_ok=True)\n");
        sb.append("    joblib.dump(clf, '").append(modelPath).append("')\n\n");
        sb.append("    result = {'status': 'success', 'metrics': metrics, 'feature_importance': importance, 'train_size': len(X_train_df), 'test_size': len(X_test_df), 'feature_count': len(X.columns), 'model_type': _model_type, 'algorithm': '").append(cfg.algorithm).append("'}\n");
        sb.append("else:\n");
        sb.append("    params = ").append(cfg.hyperparams.isEmpty() ? "{}" : objectMapper.valueToTree(cfg.hyperparams).toString()).append("\n");
        sb.append("    ").append(algoBlock.replace("\n", "\n    ")).append("\n    clf.fit(X)\n\n");
        sb.append("    metrics = {}\n    try:\n        from sklearn.metrics import silhouette_score\n        if len(X) >= 2: metrics['silhouette'] = round(float(silhouette_score(X, clf.labels_ if hasattr(clf, 'labels_') else clf.predict(X))), 4)\n");
        sb.append("    except Exception as e: print(f'[WARN] Clustering metrics error: {e}')\n");
        sb.append("    if hasattr(clf, 'inertia_'): metrics['inertia'] = round(float(clf.inertia_), 4)\n");
        sb.append("    if hasattr(clf, 'labels_'): metrics['n_clusters'] = len(set(clf.labels_))\n");
        sb.append("    print(f'[INFO] Clustering done, metrics: {metrics}')\n");
        sb.append("    import joblib\n    os.makedirs('").append(modelWorkspace).append("', exist_ok=True)\n");
        sb.append("    joblib.dump(clf, '").append(modelPath).append("')\n\n");
        sb.append("    result = {'status': 'success', 'metrics': metrics, 'feature_count': len(X.columns), 'train_size': len(X), 'test_size': 0, 'model_type': 'clustering', 'algorithm': '").append(cfg.algorithm).append("'}\n");
    }

    private void appendOutput(StringBuilder sb, PipelineConfig cfg) {
        if (cfg.outputTable == null || cfg.outputTable.isBlank()) return;
        sb.append("\n# Output predictions to database\n_out_table = '").append(cfg.outputTable.replace("'", "\\'")).append("'\n");
        sb.append("_out_mode = '").append(cfg.outputMode != null ? cfg.outputMode : "append").append("'\n");
        sb.append("X_all = df[feature_cols].copy()\nfrom sklearn.preprocessing import LabelEncoder as _LE_out\nfor c in X_all.select_dtypes(include=['object']).columns: X_all[c] = _LE_out().fit_transform(X_all[c].astype(str))\n");
        sb.append("if _sc == 'standard' and num_cols: X_all[num_cols] = StandardScaler().fit_transform(X_all[num_cols])\n");
        sb.append("elif _sc == 'minmax' and num_cols: X_all[num_cols] = MinMaxScaler().fit_transform(X_all[num_cols])\n");
        sb.append("_all_pred = clf.predict(X_all)\n_out_df = df.copy()\n_out_df['prediction'] = _all_pred\n");
        sb.append("if hasattr(clf, 'predict_proba'): _out_df['prediction_proba'] = [round(max(p), 6) for p in clf.predict_proba(X_all)]\n");
        if (cfg.outputAutoCreate) {
            sb.append("with engine.connect() as conn:\n");
            sb.append("    _check = conn.execute(text('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = :t'), {'t': _out_table})\n");
            sb.append("    if _check.fetchone()[0] == 0:\n");
            sb.append("        _out_df.head(0).to_sql(_out_table, engine, if_exists='fail', index=False)\n");
            sb.append("        print(f'[INFO] Auto-created table {_out_table}')\n");
            sb.append("    else:\n");
            sb.append("        _existing = [r[0] for r in conn.execute(text(f'SHOW COLUMNS FROM `{_out_table}`'))]\n");
            sb.append("        for _col in _out_df.columns:\n");
            sb.append("            if _col not in _existing:\n");
            sb.append("                _dt = 'VARCHAR(255)'\n");
            sb.append("                if pd.api.types.is_numeric_dtype(_out_df[_col]): _dt = 'DOUBLE'\n");
            sb.append("                conn.execute(text(f'ALTER TABLE `{_out_table}` ADD COLUMN `{_col}` {_dt}'))\n");
            sb.append("                print(f'[INFO] Added column {_col} to {_out_table}')\n");
            sb.append("        conn.commit()\n");
        }
        sb.append("try:\n    if _out_mode == 'replace':\n        with engine.connect() as conn: conn.execute(text(f'TRUNCATE TABLE `{_out_table}`')); conn.commit()\n");
        sb.append("    _out_df.to_sql(_out_table, engine, if_exists='append', index=False)\n    result['output_rows'] = len(_out_df); result['output_table'] = _out_table\n");
        sb.append("except Exception as e: print(f'[WARN] Output write failed: {e}')\n");
    }

    // ===== Pipeline → Model sync =====

    private MiningModel createOrUpdatePipelineModel(MiningPipeline pipeline, Map<String, Object> result,
                                                     PipelineConfig cfg, String modelPath) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MiningModel>()
            .eq(MiningModel::getPipelineId, pipeline.getId())
            .eq(MiningModel::getDeleted, 0);
        MiningModel existing = miningModelMapper.selectOne(wrapper);

        MiningModel model = existing != null ? existing : new MiningModel();
        model.setPipelineId(pipeline.getId());
        model.setName(pipeline.getName());
        model.setDataSourceId(pipeline.getDataSourceId());
        model.setConversationId(pipeline.getConversationId());
        model.setModelType(cfg.modelType != null ? cfg.modelType : "classification");
        model.setAlgorithm(cfg.algorithm);
        model.setSourceTable(cfg.sourceTable != null ? cfg.sourceTable : "");
        model.setFeatureColumns(cfg.featureColumns != null ? toJson(cfg.featureColumns) : "[]");
        model.setTargetColumn(cfg.targetColumn);
        model.setPreprocessing(toJson(cfg.preprocessing));
        model.setHyperparameters(toJson(cfg.hyperparams));
        model.setMetrics(toJson(result.get("metrics")));
        model.setFeatureImportance(toJson(result.get("feature_importance")));
        model.setModelPath(modelPath);
        model.setStatus(ModelStatus.TRAINED);
        model.setScheduleEnabled(false);

        if (existing != null) {
            miningModelMapper.updateById(model);
        } else {
            model.setVersion(null);
            model.setDeleted(0);
            miningModelMapper.insert(model);
        }
        return model;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> previewStep(Long pipelineId, String nodeId) {
        MiningPipeline pipeline = miningPipelineMapper.selectById(pipelineId);
        if (pipeline == null || Integer.valueOf(1).equals(pipeline.getDeleted())) {
            throw new IllegalArgumentException("流水线不存在: " + pipelineId);
        }

        List<Map<String, Object>> nodes;
        try {
            nodes = objectMapper.readValue(pipeline.getNodes(), List.class);
        } catch (Exception e) {
            throw new RuntimeException("流水线节点解析失败: " + e.getMessage());
        }

        // Find target node and preceding nodes
        int targetIdx = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if (String.valueOf(nodes.get(i).get("id")).equals(nodeId)) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx < 0) {
            throw new IllegalArgumentException("节点不存在: " + nodeId);
        }

        Map<String, Object> targetNode = nodes.get(targetIdx);
        String nodeType = String.valueOf(targetNode.get("type"));

        // Build config from all nodes up to target
        List<Map<String, Object>> nodesUpToTarget = nodes.subList(0, targetIdx + 1);
        PipelineConfig cfg = extractConfig(nodesUpToTarget);

        DataSource ds = dataSourceMapper.selectById(pipeline.getDataSourceId());
        if (ds == null) throw new IllegalStateException("数据源不存在");

        String dbUrl = buildSqlalchemyUrl(ds);
        String script = buildPreviewScript(dbUrl, cfg, nodeType);

        log.info("[PIPELINE] Preview step pipeline={}, node={} type={}", pipelineId, nodeId, nodeType);

        try {
            PythonResult pr = pythonExecutor.execute(script, pipeline.getDataSourceId(), previewTimeoutMs);
            if (pr.exitCode() != 0) {
                String err = pr.stderr().isBlank() ? pr.stdout() : pr.stderr();
                Map<String, Object> failResult = new LinkedHashMap<>();
                failResult.put("nodeId", nodeId);
                failResult.put("nodeType", nodeType);
                failResult.put("status", "error");
                failResult.put("error", truncateLog(err, 2000));
                return failResult;
            }
            Map<String, Object> result = parseResultMarker(pr.stdout(), "[PREVIEW_RESULT]");
            result.putIfAbsent("nodeId", nodeId);
            result.putIfAbsent("nodeType", nodeType);
            result.putIfAbsent("status", "success");
            return result;
        } catch (Exception e) {
            Map<String, Object> failResult = new LinkedHashMap<>();
            failResult.put("nodeId", nodeId);
            failResult.put("nodeType", nodeType);
            failResult.put("status", "error");
            failResult.put("error", truncateLog(e.getMessage(), 2000));
            return failResult;
        }
    }

    private String buildPreviewScript(String dbUrl, PipelineConfig cfg, String nodeType) {
        StringBuilder sb = new StringBuilder();
        sb.append("import pandas as pd\nimport numpy as np\nimport json\n");
        sb.append("from sqlalchemy import create_engine, text\n\n");
        sb.append("engine = create_engine('").append(dbUrl).append("')\n");

        // Always load data
        if (cfg.sourceTable != null && !cfg.sourceTable.isBlank()) {
            sb.append("df = pd.read_sql('SELECT * FROM `").append(cfg.sourceTable).append("`', engine)\n");
            sb.append("print(f'[INFO] Loaded {len(df)} rows from ").append(cfg.sourceTable).append("')\n\n");
        } else {
            sb.append("result = {'status': 'error', 'error': '数据源未配置'}\nprint('[PREVIEW_RESULT] ' + json.dumps(result))\n");
            return sb.toString();
        }

        // Apply preprocessing steps
        appendPreprocessing(sb, cfg);

        switch (nodeType) {
            case "data_source" -> appendDataSourcePreview(sb, cfg);
            case "preprocessing", "fill_missing" -> appendPreprocessingPreview(sb, cfg);
            case "feature_engineering" -> appendFeaturePreview(sb, cfg);
            case "training", "evaluation" -> appendTrainingPreview(sb, cfg);
            case "output" -> appendOutputPreview(sb, cfg);
            default -> appendDataSourcePreview(sb, cfg);
        }

        return sb.toString();
    }

    private void appendDataSourcePreview(StringBuilder sb, PipelineConfig cfg) {
        sb.append("result = {\n");
        sb.append("  'status': 'success', 'nodeType': 'data_source',\n");
        sb.append("  'tableName': '").append(cfg.sourceTable).append("',\n");
        sb.append("  'rowCount': len(df), 'columnCount': len(df.columns),\n");
        sb.append("  'columns': [{'name': c, 'dtype': str(df[c].dtype), 'nulls': int(df[c].isnull().sum()), 'sample': str(df[c].iloc[0]) if len(df) > 0 else None} for c in df.columns],\n");
        sb.append("  'sampleRows': json.loads(df.head(10).to_json(orient='records', date_format='iso')),\n");
        sb.append("  'nullSummary': {c: int(df[c].isnull().sum()) for c in df.columns if df[c].isnull().sum() > 0},\n");
        sb.append("  'numericStats': {c: {'mean': round(float(df[c].mean()), 2), 'min': round(float(df[c].min()), 2), 'max': round(float(df[c].max()), 2)} for c in df.select_dtypes(include=['number']).columns}\n");
        sb.append("}\nprint('[PREVIEW_RESULT] ' + json.dumps(result, default=str))\n");
    }

    private void appendPreprocessingPreview(StringBuilder sb, PipelineConfig cfg) {
        sb.append("result = {\n");
        sb.append("  'status': 'success', 'nodeType': 'preprocessing',\n");
        sb.append("  'rowCount': len(df), 'columnCount': len(df.columns),\n");
        sb.append("  'remainingNulls': {c: int(df[c].isnull().sum()) for c in df.columns if df[c].isnull().sum() > 0},\n");
        sb.append("  'sampleRows': json.loads(df.head(10).to_json(orient='records', date_format='iso')),\n");
        sb.append("  'columns': [{'name': c, 'dtype': str(df[c].dtype)} for c in df.columns]\n");
        sb.append("}\nprint('[PREVIEW_RESULT] ' + json.dumps(result, default=str))\n");
    }

    @SuppressWarnings("unchecked")
    private void appendFeaturePreview(StringBuilder sb, PipelineConfig cfg) {
        String fcList = cfg.featureColumns != null ? objectMapper.valueToTree(cfg.featureColumns).toString() : "[]";
        sb.append("feature_cols = ").append(fcList).append("\n");
        sb.append("if isinstance(feature_cols, str): feature_cols = [c.strip() for c in feature_cols.split(',') if c.strip()]\n");
        sb.append("target_col = '").append(cfg.targetColumn != null ? cfg.targetColumn : "").append("'\n");
        sb.append("if not feature_cols:\n    feature_cols = [c for c in df.columns if c != target_col and c not in ('id','created_at','updated_at')]\n\n");

        // Apply transforms — reuse main transform logic
        if (cfg.transforms != null && !cfg.transforms.isEmpty()) {
            sb.append("# Feature transforms\n");
            appendTransformsBlock(sb, cfg);
        }

        sb.append("X = df[feature_cols].copy()\n");
        sb.append("y = df[target_col].copy() if target_col in df.columns else None\n\n");
        sb.append("# Encode categorical columns for stats\n");
        sb.append("from sklearn.preprocessing import LabelEncoder\n");
        sb.append("cat_cols = X.select_dtypes(include=['object']).columns.tolist()\n");
        sb.append("for c in cat_cols: X[c] = LabelEncoder().fit_transform(X[c].astype(str))\n");
        sb.append("if y is not None and y.dtype == 'object': y = LabelEncoder().fit_transform(y.astype(str))\n\n");

        sb.append("result = {\n");
        sb.append("  'status': 'success', 'nodeType': 'feature_engineering',\n");
        sb.append("  'featureCount': len(feature_cols), 'targetColumn': target_col,\n");
        sb.append("  'featureColumns': feature_cols,\n");
        sb.append("  'sampleShape': [len(X), len(X.columns)],\n");
        sb.append("  'sampleRows': json.loads(X.head(5).to_json(orient='records')),\n");
        if (cfg.targetColumn != null) {
            sb.append("  'targetDistribution': df['").append(cfg.targetColumn).append("'].value_counts().to_dict() if target_col in df.columns else {},\n");
        }
        sb.append("  'numericSummary': {c: {'mean': round(float(X[c].mean()), 2), 'std': round(float(X[c].std()), 2)} for c in X.select_dtypes(include=['number']).columns[:10]}\n");
        sb.append("}\nprint('[PREVIEW_RESULT] ' + json.dumps(result, default=str))\n");
    }

    private void appendTrainingPreview(StringBuilder sb, PipelineConfig cfg) {
        // Full pipeline up to training + evaluation
        appendFeatureEngineering(sb, cfg);
        appendEncodingScaling(sb, cfg);

        String algoBlock = buildAlgorithmBlock(cfg.algorithm);
        sb.append("from sklearn.model_selection import train_test_split\n");
        sb.append("from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score, mean_squared_error, r2_score\n\n");

        sb.append("if y is not None:\n");
        sb.append("    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=").append(cfg.testSize).append(", random_state=42)\n");
        sb.append("    params = ").append(cfg.hyperparams.isEmpty() ? "{}" : objectMapper.valueToTree(cfg.hyperparams).toString()).append("\n");
        sb.append("    ").append(algoBlock.replace("\n", "\n    ")).append("\n    clf.fit(X_train, y_train)\n\n");

        sb.append("    y_pred = clf.predict(X_test)\n");
        sb.append("    metrics = {}\n");
        sb.append("    _model_type = '").append(cfg.modelType != null ? cfg.modelType : "classification").append("'\n");
        sb.append("    if _model_type == 'classification':\n");
        sb.append("        metrics['test_accuracy'] = round(float(accuracy_score(y_test, y_pred)), 4)\n");
        sb.append("        metrics['test_precision'] = round(float(precision_score(y_test, y_pred, average='weighted', zero_division=0)), 4)\n");
        sb.append("        metrics['test_recall'] = round(float(recall_score(y_test, y_pred, average='weighted', zero_division=0)), 4)\n");
        sb.append("        metrics['test_f1'] = round(float(f1_score(y_test, y_pred, average='weighted', zero_division=0)), 4)\n");
        sb.append("    else:\n");
        sb.append("        metrics['test_mse'] = round(float(mean_squared_error(y_test, y_pred)), 4)\n");
        sb.append("        metrics['test_r2'] = round(float(r2_score(y_test, y_pred)), 4)\n\n");

        sb.append("    importance = {}\n");
        sb.append("    if hasattr(clf, 'feature_importances_'): importance = dict(zip(X.columns, [round(float(v), 4) for v in clf.feature_importances_]))\n");
        sb.append("    elif hasattr(clf, 'coef_'): importance = dict(zip(X.columns, [round(float(v), 4) for v in (clf.coef_[0] if len(clf.coef_.shape) > 1 else clf.coef_)]))\n\n");

        sb.append("    result = {'status': 'success', 'nodeType': 'training',\n");
        sb.append("      'metrics': metrics, 'featureImportance': importance,\n");
        sb.append("      'trainSize': len(X_train), 'testSize': len(X_test),\n");
        sb.append("      'featureCount': len(X.columns), 'algorithm': '").append(cfg.algorithm != null ? cfg.algorithm : "").append("',\n");
        sb.append("      'modelType': _model_type}\n");
        sb.append("else:\n");
        sb.append("    result = {'status': 'error', 'error': '未配置目标列'}\n");
        sb.append("print('[PREVIEW_RESULT] ' + json.dumps(result, default=str))\n");
    }

    private void appendOutputPreview(StringBuilder sb, PipelineConfig cfg) {
        appendFeatureEngineering(sb, cfg);
        appendEncodingScaling(sb, cfg);

        String algoBlock = buildAlgorithmBlock(cfg.algorithm);
        sb.append("params = ").append(cfg.hyperparams.isEmpty() ? "{}" : objectMapper.valueToTree(cfg.hyperparams).toString()).append("\n");
        sb.append(algoBlock).append("\nclf.fit(X, y)\n\n");
        sb.append("_all_pred = clf.predict(X)\n_out_df = df.copy()\n_out_df['prediction'] = _all_pred\n");
        sb.append("if hasattr(clf, 'predict_proba'): _out_df['prediction_proba'] = [round(max(p), 6) for p in clf.predict_proba(X)]\n\n");

        sb.append("result = {\n");
        sb.append("  'status': 'success', 'nodeType': 'output',\n");
        sb.append("  'outputTable': '").append(cfg.outputTable != null ? cfg.outputTable : "").append("',\n");
        sb.append("  'totalRows': len(_out_df),\n");
        sb.append("  'sampleRows': json.loads(_out_df.head(10).to_json(orient='records', date_format='iso')),\n");
        sb.append("  'columns': list(_out_df.columns),\n");
        sb.append("  'predictionDistribution': _out_df['prediction'].value_counts().to_dict()\n");
        sb.append("}\nprint('[PREVIEW_RESULT] ' + json.dumps(result, default=str))\n");
    }

    // ===== Utilities =====

    private String buildAlgorithmBlock(String algorithmId) {
        Algorithm algo = algorithmService.getByAlgorithmId(algorithmId);
        if (algo != null && algo.getPythonCodeTemplate() != null && !algo.getPythonCodeTemplate().isBlank()) {
            return algo.getPythonCodeTemplate();
        }
        throw new IllegalStateException("算法配置缺失: " + algorithmId);
    }

    private String buildSqlalchemyUrl(DataSource ds) {
        String user = URLEncoder.encode(ds.getUsername(), StandardCharsets.UTF_8);
        String pass = URLEncoder.encode(ds.getPassword(), StandardCharsets.UTF_8);
        return "mysql+pymysql://%s:%s@%s:%d/%s".formatted(user, pass, ds.getHost(), ds.getPort(), ds.getDatabaseName());
    }

    private Map<String, Object> parseResultMarker(String stdout, String marker) {
        Map<String, Object> result = new HashMap<>();
        if (stdout == null) return result;
        for (String line : stdout.split("\n")) {
            if (line.contains(marker)) {
                try { result = objectMapper.readValue(line.substring(line.indexOf(marker) + marker.length()).trim(), Map.class); }
                catch (Exception e) { log.warn("[PIPELINE] Failed to parse marker: {}", e.getMessage()); }
                break;
            }
        }
        return result;
    }

    private String strVal(Object val) { return val != null ? String.valueOf(val) : null; }

    private String resolveFilterVariables(String filter) {
        if (filter == null || filter.isBlank()) return null;
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        java.time.LocalDate today = java.time.LocalDate.now();
        String todayStr = "'" + today.format(fmt) + "'";
        String resolved = filter;
        resolved = resolved.replace("${etl_date}", todayStr);
        resolved = resolved.replace("${today}", todayStr);
        resolved = resolved.replace("${yesterday}", "'" + today.minusDays(1).format(fmt) + "'");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{today-(\\d+)\\}").matcher(resolved);
        while (m.find()) {
            int daysAgo = Integer.parseInt(m.group(1));
            resolved = resolved.replace(m.group(0), "'" + today.minusDays(daysAgo).format(fmt) + "'");
        }
        return resolved;
    }
    private String toJson(Object obj) { try { return obj == null ? null : objectMapper.writeValueAsString(obj); } catch (Exception e) { return String.valueOf(obj); } }
    private String truncateLog(String log, int maxLen) {
        if (log == null) return null;
        String cleaned = log.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "");
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen) + "\n... (truncated)";
    }
}
