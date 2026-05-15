package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.ModelStatus;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.Algorithm;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.ModelExecution;
import com.smartquery.entity.DataSource;
import com.smartquery.entity.PredictionResult;
import com.smartquery.entity.MiningPipeline;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.MiningPipelineMapper;
import com.smartquery.mapper.ModelExecutionMapper;
import com.smartquery.mapper.DataSourceMapper;
import com.smartquery.mapper.PredictionResultMapper;
import com.smartquery.python.PythonExecutor;
import com.smartquery.python.PythonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MiningService {

    private static final String MODEL_WORKSPACE = System.getProperty("user.home") + "/smartquery-models";

    private final MiningModelMapper miningModelMapper;
    private final MiningPipelineMapper miningPipelineMapper;
    private final ModelExecutionMapper modelExecutionMapper;
    private final DataSourceMapper dataSourceMapper;
    private final PredictionResultMapper predictionResultMapper;
    private final PythonExecutor pythonExecutor;
    private final DataSourceManager dataSourceManager;
    private final AlgorithmService algorithmService;
    private final ObjectMapper objectMapper;

    public MiningModel createModel(MiningModel model) {
        model.setStatus(ModelStatus.DRAFT);
        model.setVersion(1);
        model.setDeleted(0);
        if (model.getHyperparameters() == null || model.getHyperparameters().isBlank()) {
            model.setHyperparameters("{}");
        }
        miningModelMapper.insert(model);
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
        if (updates.getFeatureColumns() != null) existing.setFeatureColumns(updates.getFeatureColumns());
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

        miningModelMapper.updateById(existing);
        return existing;
    }

    /**
     * 训练前数据校验 — 检查源表、特征列、目标列、数据量
     */
    @SuppressWarnings("unchecked")
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

        // Get column info
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

        // Get row count
        try {
            Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `" + model.getSourceTable() + "`", Integer.class);
            result.put("rowCount", rowCount);
            if (rowCount != null && rowCount < 20) {
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
                if (isNumericType(dt)) {
                    numericFeatures.add(fc);
                } else {
                    categoricalFeatures.add(fc);
                }
            }
        }
        if (!missingFeatures.isEmpty()) {
            errors.add("特征列不存在: " + String.join(", ", missingFeatures));
        }
        result.put("numericFeatures", numericFeatures);
        result.put("categoricalFeatures", categoricalFeatures);

        // Validate target column
        if (model.getTargetColumn() != null && !model.getTargetColumn().isBlank()) {
            if (!tableColumns.contains(model.getTargetColumn())) {
                errors.add("目标列 '" + model.getTargetColumn() + "' 不存在");
            } else {
                String targetType = columnTypes.get(model.getTargetColumn());
                result.put("targetType", targetType);

                // Check target value distribution
                try {
                    Integer uniqueValues = jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT `" + model.getTargetColumn() + "`) FROM `" + model.getSourceTable() + "`",
                        Integer.class);
                    result.put("targetUniqueValues", uniqueValues);
                    if ("classification".equals(model.getModelType()) && uniqueValues != null) {
                        if (uniqueValues < 2) {
                            errors.add("目标列只有 " + uniqueValues + " 个唯一值，无法进行分类任务（至少需要2个）");
                        } else if (uniqueValues > 50) {
                            warnings.add("目标列有 " + uniqueValues + " 个唯一值，可能是回归任务而非分类任务");
                        }
                    }
                } catch (Exception e) {
                    warnings.add("无法分析目标列分布: " + e.getMessage());
                }
            }
        } else if (!"clustering".equals(model.getModelType())) {
            warnings.add("未指定目标列，将以无监督模式训练");
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);
        return result;
    }

    private boolean isNumericType(String dataType) {
        if (dataType == null) return false;
        Set<String> numericTypes = Set.of(
            "int", "bigint", "tinyint", "smallint", "mediumint",
            "float", "double", "decimal", "numeric", "real");
        return numericTypes.contains(dataType.toLowerCase());
    }

    public MiningModel trainModel(Long modelId, String triggerType) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);

        log.info("[MINING] Starting training for model '{}' (id={}, algo={}, type={})",
            model.getName(), modelId, model.getAlgorithm(), model.getModelType());

        ModelExecution execution = new ModelExecution();
        execution.setModelId(modelId);
        execution.setTriggerType(triggerType != null ? triggerType : "manual");
        execution.setStatus("running");
        execution.setHyperparameters(model.getHyperparameters());
        modelExecutionMapper.insert(execution);

        model.setStatus(ModelStatus.TRAINING);
        miningModelMapper.updateById(model);

        try {
            String pythonCode = buildTrainingScript(model);
            PythonResult result = pythonExecutor.execute(pythonCode, model.getDataSourceId(), 300000);

            execution.setExecutionTimeMs(result.executionTimeMs());
            execution.setExecutionLog(truncateLog(result.stdout(), 50000));

            if (result.exitCode() == 0) {
                Map<String, Object> parsed = parseTrainingOutput(result.stdout());
                execution.setMetrics(toJson(parsed.get("metrics")));
                execution.setStatus("success");

                model.setStatus(ModelStatus.TRAINED);
                model.setMetrics(toJson(parsed.get("metrics")));
                model.setFeatureImportance(toJson(parsed.get("feature_importance")));
                model.setTrainingLog(truncateLog(result.stdout(), 20000));
                model.setVersion(model.getVersion() + 1);
                if (parsed.get("model_path") != null) {
                    model.setModelPath(String.valueOf(parsed.get("model_path")));
                }
                if (parsed.get("validation") != null) {
                    model.setValidationMetrics(toJson(parsed.get("validation")));
                }

                // Auto-create pipeline for chat-created models
                if (model.getPipelineId() == null) {
                    MiningPipeline autoPipeline = createAutoPipeline(model);
                    model.setPipelineId(autoPipeline.getId());
                }

                log.info("[MINING] Training succeeded for model '{}' v{}: metrics={}",
                    model.getName(), model.getVersion(), model.getMetrics());
            } else {
                String errorDetail = result.stderr().isBlank() ? result.stdout() : result.stderr();
                execution.setStatus(ModelStatus.FAILED);
                execution.setExecutionLog(truncateLog(errorDetail, 50000));
                model.setStatus(ModelStatus.FAILED);
                log.error("[MINING] Training failed for model '{}': exit={}, error={}",
                    model.getName(), result.exitCode(), truncateLog(errorDetail, 500));
            }

            model.setLastRunAt(LocalDateTime.now());
            miningModelMapper.updateById(model);
            modelExecutionMapper.updateById(execution);

            return model;
        } catch (Exception e) {
            log.error("[MINING] Training exception for model {}: {}", modelId, e.getMessage(), e);
            execution.setStatus(ModelStatus.FAILED);
            execution.setExecutionLog(e.getMessage());
            modelExecutionMapper.updateById(execution);

            model.setStatus(ModelStatus.FAILED);
            model.setTrainingLog(e.getMessage());
            miningModelMapper.updateById(model);
            return model;
        }
    }

    public MiningModel publishModel(Long modelId, Map<String, Object> config) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);
        if (!ModelStatus.TRAINED.equals(model.getStatus()) && !ModelStatus.OFFLINE.equals(model.getStatus())) {
            throw new IllegalStateException("只有训练完成或已下线的模型才能发布，当前状态: " + model.getStatus());
        }
        if (model.getModelPath() == null || model.getModelPath().isBlank()) {
            throw new IllegalStateException("模型文件不存在，请先训练模型");
        }

        model.setStatus(ModelStatus.PUBLISHED);
        log.info("[MINING] Model '{}' (id={}) published at version {}", model.getName(), modelId, model.getVersion());

        // Save publish configuration if provided
        if (config != null) {
            // Support both camelCase and snake_case aliases
            Object inputTable = config.getOrDefault("predictInputTable", config.getOrDefault("input_table", config.get("inputTable")));
            if (inputTable != null) {
                String tbl = (String) inputTable;
                if (!tbl.isBlank()) validateTableName(tbl);
                model.setPredictInputTable(tbl);
            }
            if (config.containsKey("predictInputFilter") || config.containsKey("input_filter")) {
                model.setPredictInputFilter((String) config.getOrDefault("predictInputFilter", config.get("input_filter")));
            }
            Object resultTable = config.getOrDefault("predictResultTable", config.getOrDefault("result_table", config.get("resultTable")));
            if (resultTable != null) {
                String tbl = (String) resultTable;
                if (!tbl.isBlank()) validateTableName(tbl);
                model.setPredictResultTable(tbl);
            }
            if (config.containsKey("scheduleCron")) {
                model.setScheduleCron((String) config.get("scheduleCron"));
            }
            if (config.containsKey("scheduleEnabled")) {
                model.setScheduleEnabled(Boolean.TRUE.equals(config.get("scheduleEnabled")));
            }
            if (config.containsKey("scheduleMode")) {
                model.setScheduleMode((String) config.get("scheduleMode"));
            }
        }

        // Set nextRunAt if schedule is enabled
        if (Boolean.TRUE.equals(model.getScheduleEnabled()) && model.getScheduleCron() != null) {
            try {
                String cron = model.getScheduleCron();
                long minutes = cron.startsWith("*/") ? Long.parseLong(cron.substring(2)) : 60;
                model.setNextRunAt(LocalDateTime.now().plusMinutes(minutes));
            } catch (Exception ignored) {}
        }

        miningModelMapper.updateById(model);
        return model;
    }

    public MiningModel offlineModel(Long modelId) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);
        model.setStatus(ModelStatus.OFFLINE);
        model.setScheduleEnabled(false);
        log.info("[MINING] Model '{}' (id={}) taken offline", model.getName(), modelId);
        miningModelMapper.updateById(model);
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
            syncHyperparamsToPipeline(model.getPipelineId(), hyperparametersJson);
        }

        return model;
    }

    /**
     * 单条/少量预测 — 前端表单输入
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> predictModel(Long modelId, List<Map<String, Object>> inputRows, String saveTable) {
        if (inputRows == null || inputRows.isEmpty()) {
            throw new IllegalArgumentException("预测输入数据不能为空");
        }
        MiningModel model = miningModelMapper.selectById(modelId);
        validateForPrediction(model);

        String pythonCode = buildPredictionScript(model, inputRows, saveTable, null);
        PythonResult result = pythonExecutor.execute(pythonCode, model.getDataSourceId(), 120000);

        if (result.exitCode() != 0) {
            throw new RuntimeException("预测执行失败: " + (result.stderr().isBlank() ? result.stdout() : result.stderr()));
        }

        Map<String, Object> parsed = parseResultMarker(result.stdout(), "[PREDICT_RESULT]");

        // Save to prediction_result table
        String batchId = "single_" + System.currentTimeMillis();
        savePredictionResults(model, inputRows, parsed, batchId);

        return parsed;
    }

    /**
     * 批量预测 — 从输入表读取全部数据，预测后写入结果表
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> batchPredict(Long modelId) {
        MiningModel model = miningModelMapper.selectById(modelId);
        validateForPrediction(model);

        String inputTable = model.getPredictInputTable();
        String resultTable = model.getPredictResultTable();
        if (inputTable == null || inputTable.isBlank()) {
            throw new IllegalStateException("未配置预测输入表，请在模型设置中指定");
        }

        String batchId = "batch_" + System.currentTimeMillis();
        log.info("[MINING] Batch predict: model='{}', input='{}', result='{}', batch={}",
            model.getName(), inputTable, resultTable, batchId);

        String pythonCode = buildBatchPredictionScript(model, inputTable, resultTable);
        PythonResult result = pythonExecutor.execute(pythonCode, model.getDataSourceId(), 300000);

        if (result.exitCode() != 0) {
            String err = result.stderr().isBlank() ? result.stdout() : result.stderr();
            log.error("[MINING] Batch predict failed: {}", truncateLog(err, 500));
            throw new RuntimeException("批量预测失败: " + truncateLog(err, 500));
        }

        Map<String, Object> parsed = parseResultMarker(result.stdout(), "[BATCH_PREDICT_RESULT]");

        // Also save summary to prediction_result
        int savedRows = parsed.containsKey("saved_rows") ? ((Number) parsed.get("saved_rows")).intValue() : 0;
        PredictionResult summary = new PredictionResult();
        summary.setModelId(modelId);
        summary.setModelName(model.getName());
        summary.setBatchId(batchId);
        summary.setInputData("{\"source\":\"" + inputTable + "\",\"total_rows\":" + savedRows + "}");
        summary.setPrediction("batch_summary");
        summary.setResultTable(resultTable);
        summary.setPredictedAt(LocalDateTime.now());
        predictionResultMapper.insert(summary);

        log.info("[MINING] Batch predict completed: {} rows predicted, saved to '{}'", savedRows, resultTable);
        return parsed;
    }

    /**
     * 查询预测结果
     */
    public List<PredictionResult> getPredictionResults(Long modelId, int limit) {
        return predictionResultMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PredictionResult>()
                .eq(PredictionResult::getModelId, modelId)
                .orderByDesc(PredictionResult::getPredictedAt)
                .last("LIMIT " + limit));
    }

    private void validateForPrediction(MiningModel model) {
        if (model == null) throw new IllegalArgumentException("模型不存在");
        if (model.getModelPath() == null || model.getModelPath().isBlank()) {
            throw new IllegalStateException("模型尚未训练或模型文件丢失");
        }
        if (!ModelStatus.PUBLISHED.equals(model.getStatus()) && !ModelStatus.TRAINED.equals(model.getStatus())) {
            throw new IllegalStateException("模型状态为 " + model.getStatus() + "，无法预测");
        }
    }

    @SuppressWarnings("unchecked")
    private void savePredictionResults(MiningModel model, List<Map<String, Object>> inputRows,
                                       Map<String, Object> parsed, String batchId) {
        List<Object> predictions = (List<Object>) parsed.get("predictions");
        List<List<Double>> probabilities = (List<List<Double>>) parsed.get("probabilities");

        if (predictions == null) return;

        for (int i = 0; i < predictions.size(); i++) {
            PredictionResult pr = new PredictionResult();
            pr.setModelId(model.getId());
            pr.setModelName(model.getName());
            pr.setBatchId(batchId);
            pr.setInputData(toJson(i < inputRows.size() ? inputRows.get(i) : null));
            pr.setPrediction(String.valueOf(predictions.get(i)));
            if (probabilities != null && i < probabilities.size()) {
                pr.setProbability(probabilities.get(i).stream()
                    .mapToDouble(Double::doubleValue).max().orElse(0.0));
            }
            pr.setPredictedAt(LocalDateTime.now());
            predictionResultMapper.insert(pr);
        }
    }

    /**
     * 解析筛选条件中的变量：
     * ${etl_date} → 今天 yyyy-MM-dd
     * ${today} → 今天
     * ${yesterday} → 昨天
     * ${today-N} → N天前
     */
    private String resolveFilterVariables(String filter) {
        if (filter == null || filter.isBlank()) return null;
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        java.time.LocalDate today = java.time.LocalDate.now();
        String resolved = filter;
        resolved = resolved.replace("${etl_date}", today.format(fmt));
        resolved = resolved.replace("${today}", today.format(fmt));
        resolved = resolved.replace("${yesterday}", today.minusDays(1).format(fmt));
        // Support ${today-N} pattern
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{today-(\\d+)\\}").matcher(resolved);
        while (m.find()) {
            int daysAgo = Integer.parseInt(m.group(1));
            resolved = resolved.replace(m.group(0), today.minusDays(daysAgo).format(fmt));
        }
        return resolved;
    }

    /**
     * 批量预测脚本 — 从输入表读数据 → 预测 → 写入结果表
     */
    private String buildBatchPredictionScript(MiningModel model, String inputTable, String resultTable) {
        DataSource ds = dataSourceMapper.selectById(model.getDataSourceId());
        String dbUrl = ds != null ? buildSqlalchemyUrl(ds) : "";

        StringBuilder sb = new StringBuilder();
        sb.append("import pandas as pd\n");
        sb.append("import numpy as np\n");
        sb.append("import json\n");
        sb.append("import joblib\n");
        sb.append("import os\n");
        sb.append("from sqlalchemy import create_engine, text\n\n");

        // Load model
        sb.append("model_path = '").append(model.getModelPath()).append("'\n");
        sb.append("if not os.path.exists(model_path):\n");
        sb.append("    print('[BATCH_PREDICT_RESULT] ' + json.dumps({'error': '模型文件不存在'}))\n");
        sb.append("    exit(1)\n");
        sb.append("clf = joblib.load(model_path)\n\n");

        // Load input data
        sb.append("engine = create_engine('").append(dbUrl).append("')\n");

        // Build query with optional filter
        String filter = resolveFilterVariables(model.getPredictInputFilter());
        if (filter != null && !filter.isBlank()) {
            sb.append("_filter = \"").append(filter.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
            sb.append("_query = 'SELECT * FROM `").append(inputTable).append("` WHERE ' + _filter\n");
            sb.append("print(f'[INFO] Query: SELECT * FROM ").append(inputTable).append(" WHERE {_filter}')\n");
            sb.append("df = pd.read_sql(_query, engine)\n");
        } else {
            sb.append("df = pd.read_sql('SELECT * FROM `").append(inputTable).append("`', engine)\n");
        }
        sb.append("print(f'[INFO] Loaded {len(df)} rows from ").append(inputTable).append("')\n\n");

        // Empty result guard
        String tableName = (resultTable != null && !resultTable.isBlank())
            ? resultTable
            : "predict_" + model.getAlgorithm() + "_" + inputTable;
        sb.append("if len(df) == 0:\n");
        sb.append("    print('[BATCH_PREDICT_RESULT] ' + json.dumps({'saved_to': '").append(tableName).append("', 'saved_rows': 0, 'warning': '筛选后无数据'}))\n");
        sb.append("    exit(0)\n\n");

        sb.append("feature_cols = ").append(model.getFeatureColumns()).append("\n");
        sb.append("if isinstance(feature_cols, str):\n");
        sb.append("    feature_cols = [c.strip() for c in feature_cols.split(',') if c.strip()]\n");
        sb.append("missing = [c for c in feature_cols if c not in df.columns]\n");
        sb.append("if missing:\n");
        sb.append("    print(f'[ERROR] Input table missing columns: {missing}')\n");
        sb.append("    exit(1)\n");
        sb.append("X = df[feature_cols].copy()\n\n");

        // Apply same preprocessing using saved encoders
        sb.append("_preproc_path = os.path.join(os.path.dirname(model_path), 'model_").append(model.getId()).append("_preprocessors.pkl')\n");
        sb.append("_preproc = joblib.load(_preproc_path) if os.path.exists(_preproc_path) else None\n");
        sb.append("preprocessing = ").append(model.getPreprocessing() != null ? model.getPreprocessing() : "{}").append("\n");
        sb.append("_dt_cols = X.select_dtypes(include=['datetime', 'datetimetz']).columns.tolist()\n");
        sb.append("if _dt_cols:\n");
        sb.append("    for c in _dt_cols:\n");
        sb.append("        X[c] = pd.to_numeric(X[c].astype('int64'), errors='coerce')\n");
        sb.append("    print(f'[INFO] Converted datetime columns to numeric: {_dt_cols}')\n");
        sb.append("_enc = preprocessing.get('encoding', 'label')\n");
        sb.append("cat_cols = X.select_dtypes(include=['object']).columns.tolist()\n");
        sb.append("if cat_cols:\n");
        sb.append("    if _enc == 'onehot' and _preproc and '_onehot_columns' in _preproc.get('encoders', {}):\n");
        sb.append("        X = pd.get_dummies(X, columns=cat_cols)\n");
        sb.append("        _train_cols = _preproc['encoders']['_onehot_columns']\n");
        sb.append("        for c in _train_cols:\n");
        sb.append("            if c not in X.columns: X[c] = 0\n");
        sb.append("        X = X[_train_cols]\n");
        sb.append("    elif _preproc and 'encoders' in _preproc:\n");
        sb.append("        for c in cat_cols:\n");
        sb.append("            if c in _preproc['encoders']:\n");
        sb.append("                _le = _preproc['encoders'][c]\n");
        sb.append("                X[c] = X[c].astype(str).map(lambda v: _le.transform([v])[0] if v in _le.classes_ else -1)\n");
        sb.append("            else:\n");
        sb.append("                from sklearn.preprocessing import LabelEncoder\n");
        sb.append("                X[c] = LabelEncoder().fit_transform(X[c].astype(str))\n");
        sb.append("    else:\n");
        sb.append("        from sklearn.preprocessing import LabelEncoder\n");
        sb.append("        le = LabelEncoder()\n");
        sb.append("        for c in cat_cols:\n");
        sb.append("            X[c] = le.fit_transform(X[c].astype(str))\n\n");

        sb.append("_sc = preprocessing.get('scaling', 'none')\n");
        sb.append("if _preproc and _preproc.get('scaler') is not None:\n");
        sb.append("    num_cols = X.select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("    if num_cols: X[num_cols] = _preproc['scaler'].transform(X[num_cols])\n");
        sb.append("elif _sc == 'standard':\n");
        sb.append("    from sklearn.preprocessing import StandardScaler\n");
        sb.append("    num_cols = X.select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("    if num_cols: X[num_cols] = StandardScaler().fit_transform(X[num_cols])\n");
        sb.append("elif _sc == 'minmax':\n");
        sb.append("    from sklearn.preprocessing import MinMaxScaler\n");
        sb.append("    num_cols = X.select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("    if num_cols: X[num_cols] = MinMaxScaler().fit_transform(X[num_cols])\n\n");

        // Predict
        sb.append("predictions = clf.predict(X)\n");
        sb.append("result_df = df.copy()\n");
        sb.append("result_df['prediction'] = predictions\n\n");

        sb.append("if hasattr(clf, 'predict_proba'):\n");
        sb.append("    try:\n");
        sb.append("        proba = clf.predict_proba(X)\n");
        sb.append("        result_df['prediction_proba'] = [max(p) for p in proba]\n");
        sb.append("    except: pass\n\n");

        // Write result table
        sb.append("result_df['predicted_at'] = pd.Timestamp.now()\n");
        sb.append("result_df.to_sql('").append(tableName).append("', engine, if_exists='replace', index=False)\n");
        sb.append("print(f'[INFO] Saved {len(result_df)} rows to ").append(tableName).append("')\n\n");

        sb.append("result = {\n");
        sb.append("    'saved_to': '").append(tableName).append("',\n");
        sb.append("    'saved_rows': len(result_df),\n");
        sb.append("    'columns': list(result_df.columns)\n");
        sb.append("}\n");
        sb.append("print('[BATCH_PREDICT_RESULT] ' + json.dumps(result))\n");

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String buildPredictionScript(MiningModel model, List<Map<String, Object>> inputRows,
                                                       String saveTable, String batchId) {
        DataSource ds = dataSourceMapper.selectById(model.getDataSourceId());
        String dbUrl = ds != null ? buildSqlalchemyUrl(ds) : "";

        StringBuilder sb = new StringBuilder();
        sb.append("import pandas as pd\n");
        sb.append("import numpy as np\n");
        sb.append("import json\n");
        sb.append("import joblib\n");
        sb.append("import os\n\n");

        sb.append("model_path = '").append(model.getModelPath()).append("'\n");
        sb.append("if not os.path.exists(model_path):\n");
        sb.append("    print('[PREDICT_RESULT] ' + json.dumps({'error': '模型文件不存在: ' + model_path}))\n");
        sb.append("    exit(1)\n");
        sb.append("clf = joblib.load(model_path)\n\n");

        sb.append("input_data = ").append(objectMapper.valueToTree(inputRows).toString()).append("\n");
        sb.append("df = pd.DataFrame(input_data)\n");
        sb.append("print(f'[INFO] Predicting {len(df)} rows')\n\n");

        sb.append("_preproc_path = os.path.join(os.path.dirname(model_path), 'model_").append(model.getId()).append("_preprocessors.pkl')\n");
        sb.append("_preproc = joblib.load(_preproc_path) if os.path.exists(_preproc_path) else None\n");
        sb.append("preprocessing = ").append(model.getPreprocessing() != null ? model.getPreprocessing() : "{}").append("\n");
        sb.append("_dt_cols = df.select_dtypes(include=['datetime', 'datetimetz']).columns.tolist()\n");
        sb.append("if _dt_cols:\n");
        sb.append("    for c in _dt_cols:\n");
        sb.append("        df[c] = pd.to_numeric(df[c].astype('int64'), errors='coerce')\n");
        sb.append("_enc = preprocessing.get('encoding', 'label')\n");
        sb.append("cat_cols = df.select_dtypes(include=['object']).columns.tolist()\n");
        sb.append("if cat_cols:\n");
        sb.append("    if _enc == 'onehot' and _preproc and '_onehot_columns' in _preproc.get('encoders', {}):\n");
        sb.append("        df = pd.get_dummies(df, columns=cat_cols)\n");
        sb.append("        _train_cols = _preproc['encoders']['_onehot_columns']\n");
        sb.append("        for c in _train_cols:\n");
        sb.append("            if c not in df.columns: df[c] = 0\n");
        sb.append("        df = df[_train_cols]\n");
        sb.append("    elif _preproc and 'encoders' in _preproc:\n");
        sb.append("        for c in cat_cols:\n");
        sb.append("            if c in _preproc['encoders']:\n");
        sb.append("                _le = _preproc['encoders'][c]\n");
        sb.append("                df[c] = df[c].astype(str).map(lambda v: _le.transform([v])[0] if v in _le.classes_ else -1)\n");
        sb.append("            else:\n");
        sb.append("                from sklearn.preprocessing import LabelEncoder\n");
        sb.append("                df[c] = LabelEncoder().fit_transform(df[c].astype(str))\n");
        sb.append("    else:\n");
        sb.append("        from sklearn.preprocessing import LabelEncoder\n");
        sb.append("        le = LabelEncoder()\n");
        sb.append("        for c in cat_cols:\n");
        sb.append("            df[c] = le.fit_transform(df[c].astype(str))\n\n");

        sb.append("_sc = preprocessing.get('scaling', 'none')\n");
        sb.append("if _preproc and _preproc.get('scaler') is not None:\n");
        sb.append("    num_cols = df.select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("    if num_cols: df[num_cols] = _preproc['scaler'].transform(df[num_cols])\n");
        sb.append("elif _sc == 'standard':\n");
        sb.append("    from sklearn.preprocessing import StandardScaler\n");
        sb.append("    num_cols = df.select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("    if num_cols: df[num_cols] = StandardScaler().fit_transform(df[num_cols])\n");
        sb.append("elif _sc == 'minmax':\n");
        sb.append("    from sklearn.preprocessing import MinMaxScaler\n");
        sb.append("    num_cols = df.select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("    if num_cols: df[num_cols] = MinMaxScaler().fit_transform(df[num_cols])\n\n");

        sb.append("predictions = clf.predict(df)\n");
        sb.append("result = {'predictions': predictions.tolist()}\n\n");

        sb.append("if hasattr(clf, 'predict_proba'):\n");
        sb.append("    try:\n");
        sb.append("        proba = clf.predict_proba(df)\n");
        sb.append("        result['probabilities'] = proba.tolist()\n");
        sb.append("    except: pass\n\n");

        if (saveTable != null && !saveTable.isBlank() && !dbUrl.isEmpty()) {
            sb.append("from sqlalchemy import create_engine\n");
            sb.append("engine = create_engine('").append(dbUrl).append("')\n");
            sb.append("save_df = pd.DataFrame(input_data)\n");
            sb.append("save_df['prediction'] = predictions\n");
            sb.append("if 'probabilities' in result:\n");
            sb.append("    import numpy as np\n");
            sb.append("    save_df['prediction_proba'] = [max(p) for p in result['probabilities']]\n");
            sb.append("save_df.to_sql('").append(saveTable).append("', engine, if_exists='append', index=False)\n");
            sb.append("result['saved_to'] = '").append(saveTable).append("'\n");
            sb.append("result['saved_rows'] = len(save_df)\n\n");
        }

        sb.append("print('[PREDICT_RESULT] ' + json.dumps(result))\n");
        return sb.toString();
    }

    private String buildTrainingScript(MiningModel model) {
        DataSource ds = dataSourceMapper.selectById(model.getDataSourceId());
        String dbUrl = ds != null ? buildSqlalchemyUrl(ds) : "";

        StringBuilder sb = new StringBuilder();
        sb.append("import pandas as pd\n");
        sb.append("import numpy as np\n");
        sb.append("import json\n");
        sb.append("import os\n");
        sb.append("from sqlalchemy import create_engine\n");
        sb.append("import joblib\n\n");

        sb.append("engine = create_engine('").append(dbUrl).append("')\n");
        sb.append("df = pd.read_sql('SELECT * FROM `").append(model.getSourceTable()).append("`', engine)\n");
        sb.append("print(f'[INFO] Loaded {len(df)} rows, {len(df.columns)} columns')\n\n");

        // Preprocessing
        sb.append("preprocessing = ").append(model.getPreprocessing() != null ? model.getPreprocessing() : "{}").append("\n");
        sb.append("_hm = preprocessing.get('handleMissing', 'drop')\n");
        sb.append("if _hm == 'drop':\n");
        sb.append("    df = df.dropna()\n");
        sb.append("elif _hm == 'fill_mean':\n");
        sb.append("    for c in df.select_dtypes(include=['number']).columns:\n");
        sb.append("        df[c] = df[c].fillna(df[c].mean())\n");
        sb.append("    df = df.dropna()\n");
        sb.append("elif _hm == 'fill_median':\n");
        sb.append("    for c in df.select_dtypes(include=['number']).columns:\n");
        sb.append("        df[c] = df[c].fillna(df[c].median())\n");
        sb.append("    df = df.dropna()\n");
        sb.append("print(f'[INFO] After handleMissing({_hm}): {len(df)} rows')\n\n");

        // Feature/target split
        sb.append("_fc_raw = ").append(model.getFeatureColumns()).append("\n");
        sb.append("if isinstance(_fc_raw, str):\n");
        sb.append("    feature_cols = [c.strip() for c in _fc_raw.split(',') if c.strip()]\n");
        sb.append("else:\n");
        sb.append("    feature_cols = list(_fc_raw)\n");
        sb.append("X = df[feature_cols]\n");

        if (model.getTargetColumn() != null && !model.getTargetColumn().isBlank()) {
            sb.append("y = df['").append(model.getTargetColumn()).append("']\n");
        } else {
            sb.append("y = None\n");
        }
        sb.append("\n");

        // Encoding — save per-column encoders for consistent prediction
        sb.append("_enc = preprocessing.get('encoding', 'label')\n");
        sb.append("_dt_cols = X.select_dtypes(include=['datetime', 'datetimetz']).columns.tolist()\n");
        sb.append("if _dt_cols:\n");
        sb.append("    for c in _dt_cols:\n");
        sb.append("        X[c] = pd.to_numeric(X[c].astype('int64'), errors='coerce')\n");
        sb.append("    print(f'[INFO] Converted datetime columns to numeric: {_dt_cols}')\n");
        sb.append("cat_cols = X.select_dtypes(include=['object']).columns.tolist()\n");
        sb.append("_encoders = {}\n");
        sb.append("if cat_cols:\n");
        sb.append("    if _enc == 'onehot':\n");
        sb.append("        X = pd.get_dummies(X, columns=cat_cols)\n");
        sb.append("        _encoders['_onehot_columns'] = list(X.columns)\n");
        sb.append("    else:\n");
        sb.append("        from sklearn.preprocessing import LabelEncoder\n");
        sb.append("        for c in cat_cols:\n");
        sb.append("            _le = LabelEncoder()\n");
        sb.append("            X[c] = _le.fit_transform(X[c].astype(str))\n");
        sb.append("            _encoders[c] = _le\n");
        sb.append("\n");

        // Scaling — save scaler for consistent prediction
        sb.append("_scaler = None\n");
        sb.append("_sc = preprocessing.get('scaling', 'none')\n");
        sb.append("num_cols = X.select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("if _sc == 'standard' and num_cols:\n");
        sb.append("    from sklearn.preprocessing import StandardScaler\n");
        sb.append("    _scaler = StandardScaler()\n");
        sb.append("    X[num_cols] = _scaler.fit_transform(X[num_cols])\n");
        sb.append("elif _sc == 'minmax' and num_cols:\n");
        sb.append("    from sklearn.preprocessing import MinMaxScaler\n");
        sb.append("    _scaler = MinMaxScaler()\n");
        sb.append("    X[num_cols] = _scaler.fit_transform(X[num_cols])\n");
        sb.append("\n");

        // Algorithm
        String hyperparams = model.getHyperparameters();
        if (hyperparams == null || "null".equals(hyperparams) || hyperparams.isBlank()) hyperparams = "{}";
        sb.append("params = ").append(hyperparams).append("\n");
        sb.append("_model_type = '").append(model.getModelType()).append("'\n\n");

        sb.append(buildAlgorithmBlock(model.getAlgorithm()));

        sb.append("\n# Train\n");
        sb.append("from sklearn.model_selection import train_test_split, cross_val_score\n");
        sb.append("_val_mode = '").append(validationMode(model)).append("'\n");
        sb.append("_cv_folds = ").append(model.getCvFolds() != null ? model.getCvFolds() : 5).append("\n");
        sb.append("_test_size = ").append(model.getTestSize() != null ? model.getTestSize() : 0.2).append("\n\n");

        // Split data
        sb.append("if y is not None:\n");
        sb.append("    if _val_mode == 'temporal' and '").append(model.getTemporalColumn() != null ? model.getTemporalColumn() : "").append("':\n");
        sb.append("        _tcol = '").append(model.getTemporalColumn() != null ? model.getTemporalColumn() : "").append("'\n");
        sb.append("        df_sorted = df.sort_values(_tcol).reset_index(drop=True)\n");
        sb.append("        X = df_sorted[feature_cols].copy()\n");
        sb.append("        y = df_sorted['").append(model.getTargetColumn()).append("']\n");
        sb.append("        # Re-apply encoding on sorted data using saved encoders\n");
        sb.append("        for c in X.select_dtypes(include=['object']).columns:\n");
        sb.append("            if c in _encoders:\n");
        sb.append("                X[c] = _encoders[c].transform(X[c].astype(str))\n");
        sb.append("            else:\n");
        sb.append("                from sklearn.preprocessing import LabelEncoder\n");
        sb.append("                X[c] = LabelEncoder().fit_transform(X[c].astype(str))\n");
        sb.append("        if _scaler is not None:\n");
        sb.append("            _num = X.select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("            if _num: X[_num] = _scaler.transform(X[_num])\n");
        sb.append("        _split_idx = int(len(X) * (1 - _test_size))\n");
        sb.append("        X_train, X_test = X.iloc[:_split_idx], X.iloc[_split_idx:]\n");
        sb.append("        y_train, y_test = y.iloc[:_split_idx], y.iloc[_split_idx:]\n");
        sb.append("        print(f'[INFO] Temporal split on {_tcol}: train={len(X_train)}, test={len(X_test)}')\n");
        sb.append("    else:\n");
        sb.append("        X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=_test_size, random_state=42)\n");
        sb.append("else:\n");
        sb.append("    X_train = X\n");
        sb.append("    X_test = None\n");
        sb.append("    y_train = None\n");
        sb.append("    y_test = None\n\n");

        sb.append("clf.fit(X_train, y_train if y is not None else X_train)\n\n");

        // Evaluation
        sb.append("import sklearn.metrics as metrics\n");
        sb.append("result = {}\n");
        sb.append("if y_test is not None:\n");
        sb.append("    y_pred = clf.predict(X_test)\n");
        sb.append("    if 'classification' in '").append(model.getModelType()).append("':\n");
        sb.append("        result['accuracy'] = round(metrics.accuracy_score(y_test, y_pred), 4)\n");
        sb.append("        result['precision'] = round(metrics.precision_score(y_test, y_pred, average='weighted', zero_division=0), 4)\n");
        sb.append("        result['recall'] = round(metrics.recall_score(y_test, y_pred, average='weighted', zero_division=0), 4)\n");
        sb.append("        result['f1'] = round(metrics.f1_score(y_test, y_pred, average='weighted', zero_division=0), 4)\n");
        sb.append("    else:\n");
        sb.append("        result['mse'] = round(metrics.mean_squared_error(y_test, y_pred), 4)\n");
        sb.append("        result['rmse'] = round(np.sqrt(metrics.mean_squared_error(y_test, y_pred)), 4)\n");
        sb.append("        result['r2'] = round(metrics.r2_score(y_test, y_pred), 4)\n");
        sb.append("else:\n");
        sb.append("    result['inertia'] = getattr(clf, 'inertia_', None)\n");
        sb.append("    if hasattr(clf, 'labels_'):\n");
        sb.append("        result['n_clusters'] = len(set(clf.labels_))\n\n");

        // Cross-validation (for cv or oos modes)
        sb.append("# Cross-validation\n");
        sb.append("val_result = {}\n");
        sb.append("if _val_mode in ('cv', 'oos') and y is not None:\n");
        sb.append("    _cv_scoring = 'f1_weighted' if 'classification' in '").append(model.getModelType()).append("' else 'r2'\n");
        sb.append("    try:\n");
        sb.append("        _cv_scores = cross_val_score(clf.__class__(**params) if params else clf.__class__(), X, y, cv=_cv_folds, scoring=_cv_scoring)\n");
        sb.append("        val_result['cv_mean'] = round(float(_cv_scores.mean()), 4)\n");
        sb.append("        val_result['cv_std'] = round(float(_cv_scores.std()), 4)\n");
        sb.append("        val_result['cv_folds'] = _cv_folds\n");
        sb.append("        val_result['cv_scores'] = [round(float(s), 4) for s in _cv_scores]\n");
        sb.append("        print(f'[INFO] CV {_cv_folds}-fold: mean={val_result[\"cv_mean\"]:.4f}, std={val_result[\"cv_std\"]:.4f}')\n");
        sb.append("    except Exception as _e:\n");
        sb.append("        val_result['cv_error'] = str(_e)\n");
        sb.append("        print(f'[WARN] CV failed: {_e}')\n");
        sb.append("elif _val_mode == 'temporal' and y_test is not None:\n");
        sb.append("    val_result['temporal_split'] = 'train={}/test={}'.format(len(X_train), len(X_test))\n");
        sb.append("    val_result['temporal_accuracy'] = result.get('accuracy', result.get('r2'))\n\n");

        // Feature importance
        sb.append("fi = {}\n");
        sb.append("if hasattr(clf, 'feature_importances_'):\n");
        sb.append("    fi = dict(zip(X_train.columns, [round(float(v), 4) for v in clf.feature_importances_]))\n");
        sb.append("elif hasattr(clf, 'coef_'):\n");
        sb.append("    fi = dict(zip(X_train.columns, [round(float(v), 4) for v in clf.coef_.flatten()]))\n\n");

        // Save model and preprocessors to persistent workspace
        sb.append("_workspace = '").append(MODEL_WORKSPACE).append("'\n");
        sb.append("os.makedirs(_workspace, exist_ok=True)\n");
        sb.append("_model_path = os.path.join(_workspace, 'model_").append(model.getId()).append("_v' + clf.__class__.__name__ + '.pkl')\n");
        sb.append("joblib.dump(clf, _model_path)\n");
        sb.append("_preproc_path = os.path.join(_workspace, 'model_").append(model.getId()).append("_preprocessors.pkl')\n");
        sb.append("joblib.dump({'encoders': _encoders, 'scaler': _scaler, 'feature_cols': list(X_train.columns), 'encoding': _enc, 'scaling': _sc}, _preproc_path)\n");
        sb.append("print(f'[INFO] Saved model to {_model_path}, preprocessors to {_preproc_path}')\n\n");

        sb.append("print('[TRAIN_RESULT] ' + json.dumps({'metrics': result, 'feature_importance': fi, 'model_path': _model_path, 'validation': val_result}))\n");

        return sb.toString();
    }

    private String buildAlgorithmBlock(String algorithmId) {
        Algorithm algo = algorithmService.getByAlgorithmId(algorithmId);
        if (algo != null && algo.getPythonCodeTemplate() != null && !algo.getPythonCodeTemplate().isBlank()) {
            return algo.getPythonCodeTemplate();
        }
        log.error("[MINING] Algorithm '{}' not found in DB or has no code template", algorithmId);
        throw new IllegalStateException("算法配置缺失: " + algorithmId + "，请检查算法注册表");
    }

    private String buildSqlalchemyUrl(DataSource ds) {
        String user = URLEncoder.encode(ds.getUsername(), StandardCharsets.UTF_8);
        String pass = URLEncoder.encode(ds.getPassword(), StandardCharsets.UTF_8);
        return "mysql+pymysql://%s:%s@%s:%d/%s".formatted(
            user, pass, ds.getHost(), ds.getPort(), ds.getDatabaseName());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseTrainingOutput(String stdout) {
        return parseResultMarker(stdout, "[TRAIN_RESULT]");
    }

    private Map<String, Object> parseResultMarker(String stdout, String marker) {
        Map<String, Object> result = new HashMap<>();
        if (stdout == null) return result;
        for (String line : stdout.split("\n")) {
            if (line.contains(marker)) {
                try {
                    String json = line.substring(line.indexOf(marker) + marker.length()).trim();
                    result = objectMapper.readValue(json, Map.class);
                } catch (Exception e) {
                    log.warn("[MINING] Failed to parse result marker '{}': {}", marker, e.getMessage());
                }
                break;
            }
        }
        return result;
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of(json.split(","));
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private String validationMode(MiningModel model) {
        String mode = model.getValidationMode();
        if (mode != null && !mode.isBlank()) return mode;
        return "train_test";
    }

    private String truncateLog(String log, int maxLen) {
        if (log == null || log.length() <= maxLen) return log;
        return log.substring(0, maxLen) + "\n... (truncated)";
    }

    private void validateTableName(String name) {
        if (name != null && !name.isBlank() && !name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("无效的表名: " + name);
        }
    }

    private void validateColumnName(String name) {
        if (name != null && !name.isBlank() && !name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("无效的列名: " + name);
        }
    }

    // ======================== Pipeline Execution ========================

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

        // Sort nodes: data_source → preprocessing → feature_engineering → training → evaluation
        List<String> typeOrder = List.of("data_source", "preprocessing", "feature_engineering", "training", "evaluation");
        nodes.sort((a, b) -> {
            int ia = typeOrder.indexOf(a.get("type"));
            int ib = typeOrder.indexOf(b.get("type"));
            if (ia < 0) ia = 99;
            if (ib < 0) ib = 99;
            return Integer.compare(ia, ib);
        });

        // Extract configuration from nodes
        String sourceTable = null;
        String filter = null;
        Map<String, Object> preprocessingConfig = new HashMap<>();
        List<String> featureColumns = null;
        String targetColumn = null;
        String modelType = null;
        String algorithm = null;
        Map<String, Object> hyperparams = new HashMap<>();
        double testSize = 0.2;
        int cvFold = 5;
        String validationMode = null;
        String temporalColumn = null;
        String outputTable = null;
        boolean outputAutoCreate = false;
        String outputMode = "append";
        List<Map<String, Object>> transforms = new ArrayList<>();

        for (Map<String, Object> node : nodes) {
            String type = String.valueOf(node.get("type"));
            Map<String, Object> config = (Map<String, Object>) node.get("config");
            if (config == null) config = Map.of();

            switch (type) {
                case "data_source" -> {
                    sourceTable = strVal(config.get("table"));
                    filter = strVal(config.get("filter"));
                }
                case "preprocessing" -> {
                    String handleMissing = strVal(config.get("handleMissing"));
                    if (handleMissing != null && !"none".equals(handleMissing)) {
                        preprocessingConfig.put("handleMissing", handleMissing);
                    }
                    String encoding = strVal(config.get("encoding"));
                    if (encoding != null && !"none".equals(encoding)) {
                        preprocessingConfig.put("encoding", encoding);
                    }
                    String scaling = strVal(config.get("scaling"));
                    if (scaling != null && !"none".equals(scaling)) {
                        preprocessingConfig.put("scaling", scaling);
                    }
                }
                case "fill_missing" -> {
                    preprocessingConfig.put("fillMissingStrategy", strVal(config.getOrDefault("strategy", "auto")));
                    Object fillCols = config.get("columns");
                    if (fillCols instanceof List && !((List<?>) fillCols).isEmpty()) {
                        preprocessingConfig.put("fillMissingColumns", fillCols);
                    }
                }
                case "feature_engineering" -> {
                    Object fc = config.get("featureColumns");
                    if (fc instanceof List) {
                        featureColumns = ((List<String>) fc);
                    } else if (fc instanceof String s && !s.isBlank()) {
                        try {
                            featureColumns = objectMapper.readValue(s, List.class);
                        } catch (Exception e2) {
                            featureColumns = List.of(s.split(","));
                            featureColumns = featureColumns.stream().map(String::trim).filter(s2 -> !s2.isEmpty()).toList();
                        }
                    }
                    targetColumn = strVal(config.get("targetColumn"));
                    Object tf = config.get("transforms");
                    if (tf instanceof List) transforms = (List<Map<String, Object>>) tf;
                }
                case "training" -> {
                    modelType = strVal(config.get("modelType"));
                    algorithm = strVal(config.get("algorithm"));
                    Object hp = config.get("hyperparams");
                    if (hp == null) hp = config.get("hyperparameters");
                    if (hp instanceof Map) hyperparams = (Map<String, Object>) hp;
                }
                case "evaluation" -> {
                    if (config.get("testSize") != null) {
                        double ts = ((Number) config.get("testSize")).doubleValue();
                        testSize = ts > 1 ? ts / 100.0 : ts;
                    }
                    if (config.get("cvFold") != null) {
                        cvFold = ((Number) config.get("cvFold")).intValue();
                    }
                    if (config.get("validationMode") != null) {
                        validationMode = strVal(config.get("validationMode"));
                    }
                    if (config.get("temporalColumn") != null) {
                        temporalColumn = strVal(config.get("temporalColumn"));
                    }
                }
                case "output" -> {
                    outputTable = strVal(config.get("table"));
                    if (config.get("autoCreate") != null) {
                        outputAutoCreate = Boolean.TRUE.equals(config.get("autoCreate"));
                    }
                    outputMode = strVal(config.getOrDefault("mode", "append"));
                }
            }
        }

        if (sourceTable == null || sourceTable.isBlank()) {
            throw new IllegalStateException("流水线缺少数据源节点或未配置表名");
        }
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalStateException("流水线缺少训练节点或未配置算法");
        }
        if (!"clustering".equals(modelType) && (targetColumn == null || targetColumn.isBlank())) {
            throw new IllegalStateException("分类/回归模型必须指定目标列，请在特征工程节点中配置");
        }

        // Resolve filter variables
        filter = resolveFilterVariables(filter);

        // Get data source
        DataSource ds = dataSourceMapper.selectById(pipeline.getDataSourceId());
        if (ds == null) throw new IllegalStateException("数据源不存在");

        // Validate source table exists
        try {
            JdbcTemplate jdbc = dataSourceManager.getJdbcTemplate(ds.getId());
            jdbc.queryForObject("SELECT 1 FROM " + sourceTable + " LIMIT 1", Integer.class);
        } catch (Exception e) {
            throw new IllegalStateException("源表 '" + sourceTable + "' 不存在或无法访问: " + e.getMessage());
        }

        String dbUrl = buildSqlalchemyUrl(ds);

        // Build algorithm block
        String algoBlock = buildAlgorithmBlock(algorithm);

        // Build model save path
        String modelFilename = "pipeline_" + pipelineId + "_v" + algorithm + ".pkl";
        String modelPath = MODEL_WORKSPACE + "/" + modelFilename;

        // Generate Python script
        String script = buildPipelineScript(dbUrl, sourceTable, filter, preprocessingConfig,
                featureColumns, targetColumn, modelType, algorithm, algoBlock, hyperparams,
                testSize, cvFold, validationMode, temporalColumn, modelPath, outputTable,
                outputAutoCreate, outputMode, transforms);

        // Update status to running
        pipeline.setStatus("running");
        pipeline.setExecutionLog(null);
        miningPipelineMapper.updateById(pipeline);

        log.info("[PIPELINE] Executing pipeline {}: {} nodes, table={}, algo={}",
                pipelineId, nodes.size(), sourceTable, algorithm);

        long pipelineStartMs = System.currentTimeMillis();
        String execLog;
        Map<String, Object> result;
        try {
            PythonResult pr = pythonExecutor.execute(script, pipeline.getDataSourceId(), 600000);
            execLog = pr.stdout();
            if (pr.exitCode() != 0) {
                String err = pr.stderr().isBlank() ? pr.stdout() : pr.stderr();
                throw new RuntimeException("Pipeline执行失败: " + truncateLog(err, 1000));
            }
            result = parseResultMarker(pr.stdout(), "[PIPELINE_RESULT]");
            result.put("modelPath", modelPath);
            result.put("algorithm", algorithm);
            result.put("sourceTable", sourceTable);
        } catch (Exception e) {
            pipeline.setStatus("failed");
            pipeline.setExecutionLog(truncateLog(e.getMessage(), 4000));
            pipeline.setLastExecutedAt(LocalDateTime.now());
            miningPipelineMapper.updateById(pipeline);
            throw e;
        }

        // Also create/update a model from this pipeline
        MiningModel model = createOrUpdatePipelineModel(pipeline, result, featureColumns,
                targetColumn, modelType, algorithm, hyperparams, preprocessingConfig, modelPath, sourceTable);

        // Create execution history record
        ModelExecution execution = new ModelExecution();
        execution.setModelId(model.getId());
        execution.setTriggerType("manual");
        execution.setStatus("success");
        execution.setHyperparameters(toJson(hyperparams));
        execution.setMetrics(toJson(result.get("metrics")));
        execution.setExecutionLog(truncateLog(execLog, 50000));
        execution.setExecutionTimeMs((int)(System.currentTimeMillis() - pipelineStartMs));
        modelExecutionMapper.insert(execution);

        result.put("modelId", model.getId());
        result.put("modelName", model.getName());

        pipeline.setStatus("completed");
        pipeline.setLastExecutedAt(LocalDateTime.now());
        pipeline.setExecutionLog(truncateLog(execLog, 4000));
        miningPipelineMapper.updateById(pipeline);

        log.info("[PIPELINE] Pipeline {} completed, model={}", pipelineId, model.getId());
        return result;
    }

    private String buildPipelineScript(String dbUrl, String sourceTable, String filter,
                                        Map<String, Object> preprocessing, List<String> featureColumns,
                                        String targetColumn, String modelType, String algorithm,
                                        String algoBlock, Map<String, Object> hyperparams,
                                        double testSize, int cvFold, String validationMode,
                                        String temporalColumn, String modelPath,
                                        String outputTable, boolean outputAutoCreate, String outputMode,
                                        List<Map<String, Object>> transforms) {
        StringBuilder sb = new StringBuilder();
        sb.append("import pandas as pd\n");
        sb.append("import numpy as np\n");
        sb.append("import json\n");
        sb.append("import os\n");
        sb.append("from sqlalchemy import create_engine, text\n\n");

        // Load data
        sb.append("engine = create_engine('").append(dbUrl).append("')\n");
        sb.append("_query = 'SELECT * FROM `").append(sourceTable).append("`'\n");
        if (filter != null && !filter.isBlank()) {
            sb.append("_filter = \"").append(filter.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
            sb.append("_query = 'SELECT * FROM `").append(sourceTable).append("` WHERE ' + _filter\n");
            sb.append("print(f'[INFO] Filter: {_filter}')\n");
        }
        sb.append("df = pd.read_sql(_query, engine)\n");
        sb.append("print(f'[INFO] Loaded {len(df)} rows from ").append(sourceTable).append("')\n\n");

        // Preprocessing
        String handleMissing = strVal(preprocessing.get("handleMissing"));
        if (handleMissing != null && !"none".equals(handleMissing)) {
            sb.append("# Handle missing values\n");
            switch (handleMissing) {
                case "drop" -> sb.append("df = df.dropna()\nprint(f'[INFO] After dropna: {len(df)} rows')\n");
                case "fill_mean" -> {
                    sb.append("for c in df.select_dtypes(include=['number']).columns:\n");
                    sb.append("    df[c] = df[c].fillna(df[c].mean())\n");
                    sb.append("for c in df.select_dtypes(include=['object']).columns:\n");
                    sb.append("    df[c] = df[c].fillna(df[c].mode().iloc[0] if len(df[c].mode()) > 0 else 'unknown')\n");
                }
                case "fill_median" -> {
                    sb.append("for c in df.select_dtypes(include=['number']).columns:\n");
                    sb.append("    df[c] = df[c].fillna(df[c].median())\n");
                    sb.append("for c in df.select_dtypes(include=['object']).columns:\n");
                    sb.append("    df[c] = df[c].fillna(df[c].mode().iloc[0] if len(df[c].mode()) > 0 else 'unknown')\n");
                }
            }
            sb.append("\n");
        }

        // Fill missing node (if present)
        String fillStrategy = strVal(preprocessing.get("fillMissingStrategy"));
        if (fillStrategy != null) {
            sb.append("# Fill missing values (dedicated node)\n");
            Object fillColsObj = preprocessing.get("fillMissingColumns");
            String fillColsJson = fillColsObj instanceof List ? objectMapper.valueToTree(fillColsObj).toString() : "[]";
            sb.append("_fill_cols = ").append(fillColsJson).append("\n");
            sb.append("_fill_df = df[_fill_cols] if _fill_cols else df\n");
            sb.append("_fill_num = _fill_df.select_dtypes(include=['number']).columns.tolist()\n");
            sb.append("_fill_cat = _fill_df.select_dtypes(include=['object']).columns.tolist()\n");
            sb.append("_target_cols = _fill_cols if _fill_cols else (_fill_num + _fill_cat)\n\n");
            switch (fillStrategy) {
                case "auto" -> {
                    sb.append("for c in _target_cols:\n");
                    sb.append("    if c in _fill_num:\n");
                    sb.append("        df[c] = df[c].fillna(df[c].mean())\n");
                    sb.append("    elif c in _fill_cat:\n");
                    sb.append("        df[c] = df[c].fillna(df[c].mode().iloc[0] if len(df[c].mode()) > 0 else 'unknown')\n");
                }
                case "mean" -> {
                    sb.append("for c in _target_cols:\n");
                    sb.append("    if c in _fill_num: df[c] = df[c].fillna(df[c].mean())\n");
                    sb.append("    elif c in _fill_cat: df[c] = df[c].fillna(df[c].mode().iloc[0] if len(df[c].mode()) > 0 else 'unknown')\n");
                }
                case "median" -> {
                    sb.append("for c in _target_cols:\n");
                    sb.append("    if c in _fill_num: df[c] = df[c].fillna(df[c].median())\n");
                    sb.append("    elif c in _fill_cat: df[c] = df[c].fillna(df[c].mode().iloc[0] if len(df[c].mode()) > 0 else 'unknown')\n");
                }
                case "mode" -> {
                    sb.append("for c in _target_cols:\n");
                    sb.append("    df[c] = df[c].fillna(df[c].mode().iloc[0] if len(df[c].mode()) > 0 else 'unknown')\n");
                }
            }
            sb.append("print(f'[INFO] Fill missing (").append(fillStrategy).append("): {df.isnull().sum().sum()} remaining nulls')\n\n");
        }
        String fcList = featureColumns != null
                ? objectMapper.valueToTree(featureColumns).toString()
                : "[]";
        sb.append("feature_cols = ").append(fcList).append("\n");
        sb.append("if isinstance(feature_cols, str):\n");
        sb.append("    feature_cols = [c.strip() for c in feature_cols.split(',') if c.strip()]\n");
        sb.append("if not feature_cols:\n");
        sb.append("    feature_cols = [c for c in df.columns if c != '").append(targetColumn != null ? targetColumn : "").append("' and c not in ('id','created_at','updated_at')]\n");
        sb.append("    print(f'[INFO] Auto-selected features: {feature_cols}')\n");
        sb.append("target_col = '").append(targetColumn != null ? targetColumn : "").append("'\n");
        sb.append("if target_col not in df.columns:\n");
        sb.append("    print('[ERROR] Target column not found: ' + target_col)\n");
        sb.append("    exit(1)\n\n");

        // Apply transforms
        if (transforms != null && !transforms.isEmpty()) {
            sb.append("# Feature transforms\n");
            for (int ti = 0; ti < transforms.size(); ti++) {
                Map<String, Object> tf = transforms.get(ti);
                String tfType = strVal(tf.get("type"));
                Object tfCols = tf.get("columns");
                String tfColsJson = tfCols instanceof List ? objectMapper.valueToTree(tfCols).toString() : "[]";
                switch (tfType) {
                    case "log" -> {
                        sb.append("_tf_cols = ").append(tfColsJson).append("\n");
                        sb.append("for c in _tf_cols:\n");
                        sb.append("    if c in df.columns and df[c].dtype in ['float64','int64']:\n");
                        sb.append("        df[c + '_log'] = np.log1p(df[c].clip(lower=0))\n");
                        sb.append("        feature_cols.append(c + '_log')\n");
                        sb.append("print(f'[INFO] Log transform applied to {_tf_cols}')\n");
                    }
                    case "polynomial" -> {
                        int degree = tf.get("degree") instanceof Number ? ((Number) tf.get("degree")).intValue() : 2;
                        sb.append("_tf_cols = ").append(tfColsJson).append("\n");
                        sb.append("from sklearn.preprocessing import PolynomialFeatures\n");
                        sb.append("_poly = PolynomialFeatures(degree=").append(degree).append(", include_bias=False)\n");
                        sb.append("_tf_valid = [c for c in _tf_cols if c in df.select_dtypes(include=['number']).columns]\n");
                        sb.append("if _tf_valid:\n");
                        sb.append("    _poly_arr = _poly.fit_transform(df[_tf_valid])\n");
                        sb.append("    _poly_names = [f'{c}_poly{d}' for d in range(_poly_arr.shape[1] - len(_tf_valid))] for c in _tf_valid]\n");
                        sb.append("    _poly_names = _poly.get_feature_names_out(_tf_valid)\n");
                        sb.append("    for i, pn in enumerate(_poly_names[len(_tf_valid):]):\n");
                        sb.append("        df[pn] = _poly_arr[:, len(_tf_valid) + i - len(_tf_valid) + len(_tf_valid)]\n");
                        sb.append("    for pn in _poly_names:\n");
                        sb.append("        if pn not in _tf_valid:\n");
                        sb.append("            df[pn] = _poly_arr[:, list(_poly_names).index(pn)]\n");
                        sb.append("            feature_cols.append(pn)\n");
                        sb.append("    print(f'[INFO] Polynomial(d=").append(degree).append(") features added')\n");
                    }
                    case "binning" -> {
                        int bins = tf.get("bins") instanceof Number ? ((Number) tf.get("bins")).intValue() : 5;
                        sb.append("_tf_cols = ").append(tfColsJson).append("\n");
                        sb.append("for c in _tf_cols:\n");
                        sb.append("    if c in df.select_dtypes(include=['number']).columns:\n");
                        sb.append("        df[c + '_bin'] = pd.cut(df[c], bins=").append(bins).append(", labels=False)\n");
                        sb.append("        feature_cols.append(c + '_bin')\n");
                        sb.append("print(f'[INFO] Binning(bins=").append(bins).append(") applied')\n");
                    }
                    case "standardize" -> {
                        sb.append("_tf_cols = ").append(tfColsJson).append("\n");
                        sb.append("from sklearn.preprocessing import StandardScaler\n");
                        sb.append("_sc = StandardScaler()\n");
                        sb.append("_tf_valid = [c for c in _tf_cols if c in df.select_dtypes(include=['number']).columns]\n");
                        sb.append("if _tf_valid:\n");
                        sb.append("    df[_tf_valid] = _sc.fit_transform(df[_tf_valid])\n");
                        sb.append("    print(f'[INFO] Standardized {_tf_valid}')\n");
                    }
                    case "interaction" -> {
                        sb.append("_tf_cols = ").append(tfColsJson).append("\n");
                        sb.append("_tf_valid = [c for c in _tf_cols if c in df.select_dtypes(include=['number']).columns]\n");
                        sb.append("for i in range(len(_tf_valid)):\n");
                        sb.append("    for j in range(i+1, len(_tf_valid)):\n");
                        sb.append("        col_name = _tf_valid[i] + '_x_' + _tf_valid[j]\n");
                        sb.append("        df[col_name] = df[_tf_valid[i]] * df[_tf_valid[j]]\n");
                        sb.append("        feature_cols.append(col_name)\n");
                        sb.append("print(f'[INFO] Interaction features added')\n");
                    }
                }
            }
            sb.append("\n");
        }
        String encoding = strVal(preprocessing.get("encoding"));
        if (encoding == null) encoding = "label";
        sb.append("_enc = '").append(encoding).append("'\n");
        sb.append("X = df[feature_cols].copy()\n");
        sb.append("y = df[target_col].copy()\n");
        sb.append("_dt_cols = X.select_dtypes(include=['datetime', 'datetimetz']).columns.tolist()\n");
        sb.append("if _dt_cols:\n");
        sb.append("    for c in _dt_cols:\n");
        sb.append("        X[c] = pd.to_numeric(X[c].astype('int64'), errors='coerce')\n");
        sb.append("    print(f'[INFO] Converted datetime columns to numeric: {_dt_cols}')\n");
        sb.append("cat_cols = X.select_dtypes(include=['object']).columns.tolist()\n");
        sb.append("if cat_cols:\n");
        sb.append("    if _enc == 'onehot':\n");
        sb.append("        X = pd.get_dummies(X, columns=cat_cols)\n");
        sb.append("    else:\n");
        sb.append("        from sklearn.preprocessing import LabelEncoder\n");
        sb.append("        le = LabelEncoder()\n");
        sb.append("        for c in cat_cols:\n");
        sb.append("            X[c] = le.fit_transform(X[c].astype(str))\n");
        sb.append("if y.dtype == 'object':\n");
        sb.append("    from sklearn.preprocessing import LabelEncoder\n");
        sb.append("    y = LabelEncoder().fit_transform(y.astype(str))\n\n");

        // Scaling
        String scaling = strVal(preprocessing.get("scaling"));
        if (scaling == null) scaling = "none";
        sb.append("_sc = '").append(scaling).append("'\n");
        sb.append("num_cols = X.select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("if _sc == 'standard' and num_cols:\n");
        sb.append("    from sklearn.preprocessing import StandardScaler\n");
        sb.append("    X[num_cols] = StandardScaler().fit_transform(X[num_cols])\n");
        sb.append("elif _sc == 'minmax' and num_cols:\n");
        sb.append("    from sklearn.preprocessing import MinMaxScaler\n");
        sb.append("    X[num_cols] = MinMaxScaler().fit_transform(X[num_cols])\n\n");

        // Split
        sb.append("from sklearn.model_selection import train_test_split, cross_val_score\n");
        sb.append("_val_mode = '").append(validationMode != null ? validationMode : "train_test").append("'\n");
        sb.append("_test_size = ").append(testSize).append("\n");
        sb.append("_cv_folds = ").append(cvFold).append("\n");
        if (temporalColumn != null) {
            sb.append("_temporal_col = '").append(temporalColumn).append("'\n");
        } else {
            sb.append("_temporal_col = None\n");
        }
        sb.append("\n");
        sb.append("if y is not None:\n");
        sb.append("    if _val_mode == 'temporal' and _temporal_col and _temporal_col in df.columns:\n");
        sb.append("        df_sorted = df.sort_values(_temporal_col).reset_index(drop=True)\n");
        sb.append("        _split_idx = int(len(df_sorted) * (1 - _test_size))\n");
        sb.append("        X = df_sorted[feature_cols].copy()\n");
        sb.append("        y_sorted = df_sorted[target_col].copy()\n");
        sb.append("        cat_cols_t = X.select_dtypes(include=['object']).columns.tolist()\n");
        sb.append("        if cat_cols_t:\n");
        sb.append("            from sklearn.preprocessing import LabelEncoder as _LE\n");
        sb.append("            _le = _LE()\n");
        sb.append("            for c in cat_cols_t: X[c] = _le.fit_transform(X[c].astype(str))\n");
        sb.append("        if y_sorted.dtype == 'object': y_sorted = _LE().fit_transform(y_sorted.astype(str))\n");
        sb.append("        X_train, X_test = X.iloc[:_split_idx], X.iloc[_split_idx:]\n");
        sb.append("        y_train, y_test = y_sorted.iloc[:_split_idx], y_sorted.iloc[_split_idx:]\n");
        sb.append("        print(f'[INFO] Temporal split on {_temporal_col}: train={len(X_train)}, test={len(X_test)}')\n");
        sb.append("    else:\n");
        sb.append("        X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=_test_size, random_state=42)\n");
        sb.append("        print(f'[INFO] Train: {len(X_train)}, Test: {len(X_test)}')\n");
        sb.append("\n");

        // Algorithm
        sb.append("# Training\n");
        String hpJson = hyperparams.isEmpty() ? "{}" : objectMapper.valueToTree(hyperparams).toString();
        sb.append("params = ").append(hpJson).append("\n");
        sb.append("_model_type = '").append(modelType).append("'\n");
        sb.append(algoBlock).append("\n");
        sb.append("clf.fit(X_train, y_train)\n\n");

        // Evaluate
        sb.append("from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score, mean_squared_error, r2_score\n");
        sb.append("from sklearn.model_selection import cross_val_score\n");
        sb.append("import sklearn.metrics as _mt\n");
        sb.append("y_pred = clf.predict(X_test)\n\n");

        sb.append("metrics = {}\n");
        sb.append("try:\n");
        sb.append("    if _model_type == 'classification':\n");
        sb.append("        metrics['accuracy'] = round(float(accuracy_score(y_test, y_pred)), 4)\n");
        sb.append("        metrics['precision'] = round(float(precision_score(y_test, y_pred, average='weighted', zero_division=0)), 4)\n");
        sb.append("        metrics['recall'] = round(float(recall_score(y_test, y_pred, average='weighted', zero_division=0)), 4)\n");
        sb.append("        metrics['f1'] = round(float(f1_score(y_test, y_pred, average='weighted', zero_division=0)), 4)\n");
        sb.append("    elif _model_type == 'regression':\n");
        sb.append("        metrics['mse'] = round(float(mean_squared_error(y_test, y_pred)), 4)\n");
        sb.append("        metrics['rmse'] = round(float(_mt.sqrt(mean_squared_error(y_test, y_pred))), 4)\n");
        sb.append("        metrics['r2'] = round(float(r2_score(y_test, y_pred)), 4)\n");
        sb.append("    else:\n");
        sb.append("        metrics['inertia'] = getattr(clf, 'inertia_', None)\n");
        sb.append("        if hasattr(clf, 'labels_'): metrics['n_clusters'] = len(set(clf.labels_))\n");
        sb.append("except Exception as e:\n");
        sb.append("    print(f'[WARN] Metrics error: {e}')\n\n");

        sb.append("try:\n");
        sb.append("    _cv_scoring = 'f1_weighted' if _model_type == 'classification' else ('r2' if _model_type == 'regression' else None)\n");
        sb.append("    if _cv_scoring:\n");
        sb.append("        cv_scores = cross_val_score(clf, X, y, cv=_cv_folds, scoring=_cv_scoring)\n");
        sb.append("        metrics['cv_mean'] = round(float(cv_scores.mean()), 4)\n");
        sb.append("        metrics['cv_std'] = round(float(cv_scores.std()), 4)\n");
        sb.append("except Exception as e:\n");
        sb.append("    print(f'[WARN] CV error: {e}')\n\n");

        // Feature importance
        sb.append("importance = {}\n");
        sb.append("try:\n");
        sb.append("    if hasattr(clf, 'feature_importances_'):\n");
        sb.append("        for fname, fi in zip(X.columns, clf.feature_importances_):\n");
        sb.append("            importance[fname] = round(float(fi), 4)\n");
        sb.append("        importance = dict(sorted(importance.items(), key=lambda x: -x[1])[:20])\n");
        sb.append("    elif hasattr(clf, 'coef_'):\n");
        sb.append("        for fname, fi in zip(X.columns, clf.coef_[0] if len(clf.coef_.shape) > 1 else clf.coef_):\n");
        sb.append("            importance[fname] = round(float(fi), 4)\n");
        sb.append("        importance = dict(sorted(importance.items(), key=lambda x: -abs(x[1]))[:20])\n");
        sb.append("except Exception as e:\n");
        sb.append("    print(f'[WARN] Importance error: {e}')\n\n");

        // Save model
        sb.append("import joblib\n");
        sb.append("os.makedirs(os.path.dirname('").append(modelPath).append("'), exist_ok=True)\n");
        sb.append("joblib.dump(clf, '").append(modelPath).append("')\n\n");

        // Output result
        sb.append("result = {\n");
        sb.append("    'status': 'success',\n");
        sb.append("    'metrics': metrics,\n");
        sb.append("    'feature_importance': importance,\n");
        sb.append("    'train_size': len(X_train),\n");
        sb.append("    'test_size': len(X_test),\n");
        sb.append("    'feature_count': len(X.columns),\n");
        sb.append("    'model_type': '").append(modelType != null ? modelType : "classification").append("',\n");
        sb.append("    'algorithm': '").append(algorithm).append("'\n");
        sb.append("}\n");

        // Output to database table
        if (outputTable != null && !outputTable.isBlank()) {
            sb.append("\n# Output predictions to database\n");
            sb.append("_out_table = '").append(outputTable.replace("'", "\\'")).append("'\n");
            sb.append("_out_mode = '").append(outputMode != null ? outputMode : "append").append("'\n");
            sb.append("print(f'[INFO] Writing predictions to {_out_table}')\n");
            sb.append("X_all = df[feature_cols].copy()\n");
            sb.append("cat_cols_all = X_all.select_dtypes(include=['object']).columns.tolist()\n");
            sb.append("if cat_cols_all:\n");
            sb.append("    if _enc == 'onehot':\n");
            sb.append("        X_all = pd.get_dummies(X_all, columns=cat_cols_all)\n");
            sb.append("    else:\n");
            sb.append("        from sklearn.preprocessing import LabelEncoder\n");
            sb.append("        _le2 = LabelEncoder()\n");
            sb.append("        for c in cat_cols_all:\n");
            sb.append("            X_all[c] = _le2.fit_transform(X_all[c].astype(str))\n");
            sb.append("if _sc == 'standard' and num_cols:\n");
            sb.append("    X_all[num_cols] = StandardScaler().fit_transform(X_all[num_cols])\n");
            sb.append("elif _sc == 'minmax' and num_cols:\n");
            sb.append("    X_all[num_cols] = MinMaxScaler().fit_transform(X_all[num_cols])\n");
            sb.append("_all_pred = clf.predict(X_all)\n");
            sb.append("_out_df = df.copy()\n");
            sb.append("_out_df['prediction'] = _all_pred\n");
            sb.append("if hasattr(clf, 'predict_proba'):\n");
            sb.append("    _all_proba = clf.predict_proba(X_all)\n");
            sb.append("    _out_df['prediction_proba'] = [round(max(p), 6) for p in _all_proba]\n");
            if (outputAutoCreate) {
                sb.append("with engine.connect() as conn:\n");
                sb.append("    _check = conn.execute(text('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = :t'), {'t': _out_table})\n");
                sb.append("    if _check.fetchone()[0] == 0:\n");
                sb.append("        _out_df.head(0).to_sql(_out_table, engine, if_exists='fail', index=False)\n");
                sb.append("        print(f'[INFO] Auto-created table {_out_table} with {len(_out_df.columns)} columns')\n");
            }
            sb.append("try:\n");
            sb.append("    if _out_mode == 'replace':\n");
            sb.append("        with engine.connect() as conn:\n");
            sb.append("            conn.execute(text(f'TRUNCATE TABLE `{_out_table}`'))\n");
            sb.append("            conn.commit()\n");
            sb.append("    _out_df.to_sql(_out_table, engine, if_exists='append', index=False)\n");
            sb.append("    print(f'[INFO] Written {len(_out_df)} rows to {_out_table}')\n");
            sb.append("    result['output_rows'] = len(_out_df)\n");
            sb.append("    result['output_table'] = _out_table\n");
            sb.append("except Exception as e:\n");
            sb.append("    print(f'[WARN] Output write failed: {e}')\n");
        }

        sb.append("print('[PIPELINE_RESULT] ' + json.dumps(result))\n");

        return sb.toString();
    }

    private MiningModel createOrUpdatePipelineModel(MiningPipeline pipeline, Map<String, Object> result,
                                                     List<String> featureColumns, String targetColumn,
                                                     String modelType, String algorithm,
                                                     Map<String, Object> hyperparams,
                                                     Map<String, Object> preprocessing,
                                                     String modelPath, String sourceTable) {
        // Check if model already exists for this pipeline
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MiningModel> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MiningModel>()
                        .eq(MiningModel::getPipelineId, pipeline.getId())
                        .eq(MiningModel::getDeleted, 0);
        MiningModel existing = miningModelMapper.selectOne(wrapper);

        MiningModel model = existing != null ? existing : new MiningModel();
        model.setPipelineId(pipeline.getId());
        model.setName(pipeline.getName());
        model.setDataSourceId(pipeline.getDataSourceId());
        model.setConversationId(pipeline.getConversationId());
        model.setModelType(modelType != null ? modelType : "classification");
        model.setAlgorithm(algorithm);
        model.setSourceTable(sourceTable != null ? sourceTable : "");  // Actual source table name
        model.setFeatureColumns(featureColumns != null ? toJson(featureColumns) : "[]");
        model.setTargetColumn(targetColumn);
        model.setPreprocessing(toJson(preprocessing));
        model.setHyperparameters(toJson(hyperparams));
        model.setMetrics(toJson(result.get("metrics")));
        model.setFeatureImportance(toJson(result.get("feature_importance")));
        model.setModelPath(modelPath);
        model.setStatus(ModelStatus.TRAINED);
        model.setScheduleEnabled(false);

        if (existing != null) {
            model.setVersion(existing.getVersion() + 1);
            miningModelMapper.updateById(model);
        } else {
            model.setVersion(1);
            model.setDeleted(0);
            miningModelMapper.insert(model);
        }
        return model;
    }

    private String strVal(Object val) {
        return val != null ? String.valueOf(val) : null;
    }

    public DataSource getDataSource(Long dataSourceId) {
        return dataSourceMapper.selectById(dataSourceId);
    }

    public JdbcTemplate getJdbcTemplate(DataSource ds) {
        return dataSourceManager.getJdbcTemplate(ds.getId());
    }

    private MiningPipeline createAutoPipeline(MiningModel model) {
        List<String> featureCols = parseJsonList(model.getFeatureColumns());
        Map<String, Object> preprocessing = parseJsonMap(model.getPreprocessing());

        List<Map<String, Object>> nodes = new ArrayList<>();
        // data_source node
        Map<String, Object> dsConfig = new LinkedHashMap<>();
        dsConfig.put("title", "数据接入");
        dsConfig.put("table", model.getSourceTable());
        nodes.add(Map.of("id", "ds_" + model.getId(), "type", "data_source", "config", dsConfig));

        // preprocessing node
        Map<String, Object> ppConfig = new LinkedHashMap<>();
        ppConfig.put("title", "数据预处理");
        ppConfig.put("handleMissing", preprocessing.getOrDefault("handleMissing", "drop"));
        ppConfig.put("encoding", preprocessing.getOrDefault("encoding", "label"));
        ppConfig.put("scaling", preprocessing.getOrDefault("scaling", "none"));
        nodes.add(Map.of("id", "pp_" + model.getId(), "type", "preprocessing", "config", ppConfig));

        // feature_engineering node
        Map<String, Object> feConfig = new LinkedHashMap<>();
        feConfig.put("title", "特征工程");
        feConfig.put("featureColumns", featureCols);
        feConfig.put("targetColumn", model.getTargetColumn());
        Object transforms = preprocessing.get("transforms");
        if (transforms != null) {
            feConfig.put("transforms", transforms);
        }
        nodes.add(Map.of("id", "fe_" + model.getId(), "type", "feature_engineering", "config", feConfig));

        // training node
        Map<String, Object> trConfig = new LinkedHashMap<>();
        trConfig.put("title", "模型训练");
        trConfig.put("modelType", model.getModelType());
        trConfig.put("algorithm", model.getAlgorithm());
        trConfig.put("hyperparams", parseJsonMap(model.getHyperparameters()));
        nodes.add(Map.of("id", "tr_" + model.getId(), "type", "training", "config", trConfig));

        // evaluation node
        Map<String, Object> evConfig = new LinkedHashMap<>();
        evConfig.put("title", "模型评估");
        evConfig.put("testSize", (int)((model.getTestSize() != null ? model.getTestSize() : 0.2) * 100));
        evConfig.put("cvFold", model.getCvFolds() != null ? model.getCvFolds() : 5);
        if (model.getValidationMode() != null) evConfig.put("validationMode", model.getValidationMode());
        if (model.getTemporalColumn() != null) evConfig.put("temporalColumn", model.getTemporalColumn());
        nodes.add(Map.of("id", "ev_" + model.getId(), "type", "evaluation", "config", evConfig));

        // output node (if predictResultTable configured)
        if (model.getPredictResultTable() != null && !model.getPredictResultTable().isBlank()) {
            Map<String, Object> outConfig = new LinkedHashMap<>();
            outConfig.put("title", "输出写入");
            outConfig.put("table", model.getPredictResultTable());
            outConfig.put("mode", "append");
            outConfig.put("autoCreate", true);
            nodes.add(Map.of("id", "out_" + model.getId(), "type", "output", "config", outConfig));
        }

        MiningPipeline pipeline = new MiningPipeline();
        pipeline.setName(model.getName());
        pipeline.setDataSourceId(model.getDataSourceId());
        pipeline.setConversationId(model.getConversationId());
        pipeline.setStatus("completed");
        pipeline.setNodes(toJson(nodes));
        pipeline.setEdges("[]");
        pipeline.setDeleted(0);
        miningPipelineMapper.insert(pipeline);
        return pipeline;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void syncHyperparamsToPipeline(Long pipelineId, String hyperparamsJson) {
        try {
            MiningPipeline pipeline = miningPipelineMapper.selectById(pipelineId);
            if (pipeline == null) return;

            List<Map<String, Object>> nodes = parseJsonNodeList(pipeline.getNodes());
            Map<String, Object> hp = parseJsonMap(hyperparamsJson);

            for (Map<String, Object> node : nodes) {
                if ("training".equals(node.get("type"))) {
                    Map<String, Object> config = (Map<String, Object>) node.get("config");
                    if (config != null) {
                        config.put("hyperparams", new LinkedHashMap<>(hp));
                    }
                }
            }

            pipeline.setNodes(objectMapper.writeValueAsString(nodes));
            miningPipelineMapper.updateById(pipeline);
        } catch (Exception e) {
            log.warn("[MINING] Failed to sync hyperparams to pipeline {}: {}", pipelineId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonNodeList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
