package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.ModelStatus;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.*;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.mapper.*;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MiningService {

    private static final String MODEL_WORKSPACE = System.getProperty("user.home") + "/smartquery-models";
    private static final int MAX_CONCURRENT_TRAINING = 2;
    private static final long TRAINING_QUEUE_TIMEOUT_MINUTES = 5;
    private final Semaphore trainingSemaphore = new Semaphore(MAX_CONCURRENT_TRAINING);

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

    // ======================== Model Lifecycle ========================

    public MiningModel createModel(MiningModel model) {
        model.setStatus(ModelStatus.DRAFT);
        model.setVersion(1);
        model.setDeleted(0);
        if (model.getHyperparameters() == null || model.getHyperparameters().isBlank()) {
            model.setHyperparameters("{}");
        }
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
        } else if (!"clustering".equals(model.getModelType())) {
            warnings.add("未指定目标列，将以无监督模式训练");
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);
        return result;
    }

    // ======================== Training ========================

    public MiningModel trainModel(Long modelId, String triggerType) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);

        log.info("[MINING] Starting training for model '{}' (id={}, algo={}, type={})",
            model.getName(), modelId, model.getAlgorithm(), model.getModelType());

        boolean acquired;
        try {
            acquired = trainingSemaphore.tryAcquire(TRAINING_QUEUE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("训练等待被中断");
        }
        if (!acquired) {
            throw new RuntimeException("训练队列已满，请稍后重试（当前最多 " + MAX_CONCURRENT_TRAINING + " 个并发训练）");
        }

        ModelExecution execution = new ModelExecution();
        execution.setModelId(modelId);
        execution.setTriggerType(triggerType != null ? triggerType : "manual");
        execution.setStatus("running");
        execution.setHyperparameters(model.getHyperparameters());
        modelExecutionMapper.insert(execution);

        model.setStatus(ModelStatus.TRAINING);
        miningModelMapper.updateById(model);

        logMiningEvent(model, "mining_training_start", Map.of(
            "algorithm", model.getAlgorithm(), "modelType", model.getModelType(),
            "sourceTable", model.getSourceTable(), "version", model.getVersion(),
            "queueAvailable", trainingSemaphore.availablePermits()
        ));

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
                if (parsed.get("model_path") != null) model.setModelPath(String.valueOf(parsed.get("model_path")));
                if (parsed.get("validation") != null) model.setValidationMetrics(toJson(parsed.get("validation")));

                if (model.getPipelineId() == null) {
                    MiningPipeline autoPipeline = createAutoPipeline(model);
                    model.setPipelineId(autoPipeline.getId());
                }

                log.info("[MINING] Training succeeded for model '{}' v{}: metrics={}",
                    model.getName(), model.getVersion(), model.getMetrics());

                logMiningEvent(model, "mining_training_complete", Map.of(
                    "status", "success", "version", model.getVersion(),
                    "durationMs", result.executionTimeMs(),
                    "metrics", parsed.get("metrics") != null ? parsed.get("metrics") : Map.of(),
                    "featureImportance", parsed.get("feature_importance") != null ? parsed.get("feature_importance") : Map.of(),
                    "validation", parsed.get("validation") != null ? parsed.get("validation") : Map.of()
                ));
            } else {
                String errorDetail = result.stderr().isBlank() ? result.stdout() : result.stderr();
                execution.setStatus(ModelStatus.FAILED);
                execution.setExecutionLog(truncateLog(errorDetail, 50000));
                model.setStatus(ModelStatus.FAILED);
                log.error("[MINING] Training failed for model '{}': exit={}, error={}",
                    model.getName(), result.exitCode(), truncateLog(errorDetail, 500));

                logMiningEvent(model, "mining_training_complete", Map.of(
                    "status", "failed", "exitCode", result.exitCode(),
                    "error", truncateLog(errorDetail, 500)
                ));
            }

            model.setLastRunAt(LocalDateTime.now());
            miningModelMapper.updateById(model);
            modelExecutionMapper.updateById(execution);
            return model;
        } catch (Exception e) {
            log.error("[MINING] Training exception for model {}: {}", modelId, e.getMessage(), e);
            logMiningError(model.getConversationId(), "training_failed", modelId, e.getMessage());
            execution.setStatus(ModelStatus.FAILED);
            execution.setExecutionLog(e.getMessage());
            modelExecutionMapper.updateById(execution);
            model.setStatus(ModelStatus.FAILED);
            model.setTrainingLog(e.getMessage());
            miningModelMapper.updateById(model);
            return model;
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
                if (overfittingGap instanceof Number gap && gap.doubleValue() > 0.15) {
                    throw new IllegalStateException(
                        String.format("过拟合风险过高 (gap=%.2f > 0.15)。建议: 减少特征、增加正则化或收集更多数据后再发布。",
                            gap.doubleValue()));
                }
            } catch (IllegalStateException e) { throw e; }
            catch (Exception ignored) {}
        }

        model.setStatus(ModelStatus.PUBLISHED);
        log.info("[MINING] Model '{}' (id={}) published at version {}", model.getName(), modelId, model.getVersion());

        if (config != null) {
            Object inputTable = config.getOrDefault("predictInputTable", config.getOrDefault("input_table", config.get("inputTable")));
            if (inputTable != null) { String tbl = (String) inputTable; if (!tbl.isBlank()) validateTableName(tbl); model.setPredictInputTable(tbl); }
            if (config.containsKey("predictInputFilter") || config.containsKey("input_filter"))
                model.setPredictInputFilter((String) config.getOrDefault("predictInputFilter", config.get("input_filter")));
            Object resultTable = config.getOrDefault("predictResultTable", config.getOrDefault("result_table", config.get("resultTable")));
            if (resultTable != null) { String tbl = (String) resultTable; if (!tbl.isBlank()) validateTableName(tbl); model.setPredictResultTable(tbl); }
            if (config.containsKey("scheduleCron")) model.setScheduleCron((String) config.get("scheduleCron"));
            if (config.containsKey("scheduleEnabled")) model.setScheduleEnabled(Boolean.TRUE.equals(config.get("scheduleEnabled")));
            if (config.containsKey("scheduleMode")) model.setScheduleMode((String) config.get("scheduleMode"));
        }

        if (Boolean.TRUE.equals(model.getScheduleEnabled()) && model.getScheduleCron() != null) {
            try {
                String cron = model.getScheduleCron();
                long minutes = cron.startsWith("*/") ? Long.parseLong(cron.substring(2)) : 60;
                model.setNextRunAt(LocalDateTime.now().plusMinutes(minutes));
            } catch (Exception ignored) {}
        }

        miningModelMapper.updateById(model);
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

    public void updateSchedule(Long modelId, String cron, Boolean enabled, String mode) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);
        if (cron != null) model.setScheduleCron(cron);
        if (enabled != null) model.setScheduleEnabled(enabled);
        if (mode != null) model.setScheduleMode(mode);
        if (Boolean.TRUE.equals(model.getScheduleEnabled()) && model.getScheduleCron() != null) {
            try {
                long minutes = model.getScheduleCron().startsWith("*/") ? Long.parseLong(model.getScheduleCron().substring(2)) : 60;
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

    public List<PredictionResult> getPredictionResults(Long modelId, int limit) {
        return predictionService.getPredictionResults(modelId, limit);
    }

    public Map<String, Object> executePipeline(Long pipelineId) {
        return pipelineService.executePipeline(pipelineId);
    }

    // ======================== Training Script Generation ========================

    private String buildTrainingScript(MiningModel model) {
        DataSource ds = dataSourceMapper.selectById(model.getDataSourceId());
        String dbUrl = ds != null ? buildSqlalchemyUrl(ds) : "";

        StringBuilder sb = new StringBuilder();
        sb.append("import pandas as pd\nimport numpy as np\nimport json\nimport os\nfrom sqlalchemy import create_engine\nimport joblib\n\n");
        sb.append("engine = create_engine('").append(dbUrl).append("')\n");
        sb.append("df = pd.read_sql('SELECT * FROM `").append(model.getSourceTable()).append("`', engine)\n");
        sb.append("print(f'[INFO] Loaded {len(df)} rows, {len(df.columns)} columns')\n\n");

        sb.append("preprocessing = ").append(model.getPreprocessing() != null ? model.getPreprocessing() : "{}").append("\n");
        sb.append("_hm = preprocessing.get('handleMissing', 'drop')\n");
        sb.append("if _hm == 'drop': df = df.dropna()\n");
        sb.append("elif _hm == 'fill_mean':\n    for c in df.select_dtypes(include=['number']).columns: df[c] = df[c].fillna(df[c].mean())\n    df = df.dropna()\n");
        sb.append("elif _hm == 'fill_median':\n    for c in df.select_dtypes(include=['number']).columns: df[c] = df[c].fillna(df[c].median())\n    df = df.dropna()\n");
        sb.append("print(f'[INFO] After handleMissing({_hm}): {len(df)} rows')\n\n");

        sb.append("_fc_raw = ").append(model.getFeatureColumns()).append("\nif isinstance(_fc_raw, str): feature_cols = [c.strip() for c in _fc_raw.split(',') if c.strip()]\nelse: feature_cols = list(_fc_raw)\n");
        sb.append("X = df[feature_cols]\n");
        if (model.getTargetColumn() != null && !model.getTargetColumn().isBlank()) {
            sb.append("y = df['").append(model.getTargetColumn()).append("']\n");
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
        sb.append("if _sc == 'standard' and num_cols:\n    from sklearn.preprocessing import StandardScaler\n    _scaler = StandardScaler(); X[num_cols] = _scaler.fit_transform(X[num_cols])\n");
        sb.append("elif _sc == 'minmax' and num_cols:\n    from sklearn.preprocessing import MinMaxScaler\n    _scaler = MinMaxScaler(); X[num_cols] = _scaler.fit_transform(X[num_cols])\n\n");

        String hyperparams = model.getHyperparameters();
        if (hyperparams == null || "null".equals(hyperparams) || hyperparams.isBlank()) hyperparams = "{}";
        sb.append("params = ").append(hyperparams).append("\n_model_type = '").append(model.getModelType()).append("'\n\n");
        sb.append(buildAlgorithmBlock(model.getAlgorithm()));

        sb.append("\nfrom sklearn.model_selection import train_test_split, cross_val_score\n");
        sb.append("_val_mode = '").append(validationMode(model)).append("'\n");
        sb.append("_cv_folds = ").append(model.getCvFolds() != null ? model.getCvFolds() : 5).append("\n");
        sb.append("_test_size = ").append(model.getTestSize() != null ? model.getTestSize() : 0.2).append("\n\n");

        sb.append("if y is not None:\n");
        sb.append("    if _val_mode == 'temporal' and '").append(model.getTemporalColumn() != null ? model.getTemporalColumn() : "").append("':\n");
        sb.append("        _tcol = '").append(model.getTemporalColumn() != null ? model.getTemporalColumn() : "").append("'\n");
        sb.append("        df_sorted = df.sort_values(_tcol).reset_index(drop=True)\n        X = df_sorted[feature_cols].copy()\n        y = df_sorted['").append(model.getTargetColumn()).append("']\n");
        sb.append("        for c in X.select_dtypes(include=['object']).columns:\n            if c in _encoders: X[c] = _encoders[c].transform(X[c].astype(str))\n            else: X[c] = LabelEncoder().fit_transform(X[c].astype(str))\n");
        sb.append("        if _scaler is not None:\n            _num = X.select_dtypes(include=['number']).columns.tolist()\n            if _num: X[_num] = _scaler.transform(X[_num])\n");
        sb.append("        _split_idx = int(len(X) * (1 - _test_size))\n        X_train, X_test = X.iloc[:_split_idx], X.iloc[_split_idx:]\n        y_train, y_test = y.iloc[:_split_idx], y.iloc[_split_idx:]\n");
        sb.append("    else:\n        X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=_test_size, random_state=42)\n");
        sb.append("else:\n    X_train = X; X_test = None; y_train = None; y_test = None\n\n");
        sb.append("clf.fit(X_train, y_train if y is not None else X_train)\n\n");

        sb.append("import sklearn.metrics as metrics\nresult = {}\nif y_test is not None:\n    y_pred = clf.predict(X_test)\n");
        sb.append("    y_train_pred = clf.predict(X_train)\n");
        sb.append("    if 'classification' in '").append(model.getModelType()).append("':\n");
        sb.append("        result['test_accuracy'] = round(metrics.accuracy_score(y_test, y_pred), 4)\n        result['test_precision'] = round(metrics.precision_score(y_test, y_pred, average='weighted', zero_division=0), 4)\n");
        sb.append("        result['test_recall'] = round(metrics.recall_score(y_test, y_pred, average='weighted', zero_division=0), 4)\n        result['test_f1'] = round(metrics.f1_score(y_test, y_pred, average='weighted', zero_division=0), 4)\n");
        sb.append("        result['train_accuracy'] = round(metrics.accuracy_score(y_train, y_train_pred), 4)\n");
        sb.append("        _gap = round(abs(result['train_accuracy'] - result['test_accuracy']), 4)\n        result['overfitting_gap'] = _gap\n");
        sb.append("        if _gap > 0.15: result['overfitting_warning'] = f'训练精度({result[\"train_accuracy\"]})与测试精度({result[\"test_accuracy\"]})差值{_gap}>0.15，可能过拟合'\n");
        sb.append("        try:\n            _cm = metrics.confusion_matrix(y_test, y_pred)\n            result['confusion_matrix'] = _cm.tolist()\n");
        sb.append("            _cls = sorted(set(list(y_test.astype(str)) + list(y_pred.astype(str))))\n            result['class_labels'] = _cls\n        except: pass\n");
        sb.append("    else:\n");
        sb.append("        result['test_mse'] = round(metrics.mean_squared_error(y_test, y_pred), 4)\n        result['test_rmse'] = round(np.sqrt(metrics.mean_squared_error(y_test, y_pred)), 4)\n        result['test_r2'] = round(metrics.r2_score(y_test, y_pred), 4)\n");
        sb.append("        result['test_mae'] = round(metrics.mean_absolute_error(y_test, y_pred), 4)\n");
        sb.append("        result['train_r2'] = round(metrics.r2_score(y_train, y_train_pred), 4)\n");
        sb.append("        _gap = round(abs(result['train_r2'] - result['test_r2']), 4)\n        result['overfitting_gap'] = _gap\n");
        sb.append("        if _gap > 0.15: result['overfitting_warning'] = f'训练R²({result[\"train_r2\"]})与测试R²({result[\"test_r2\"]})差值{_gap}>0.15，可能过拟合'\n");
        sb.append("else:\n    result['inertia'] = getattr(clf, 'inertia_', None)\n    if hasattr(clf, 'labels_'): result['n_clusters'] = len(set(clf.labels_))\n\n");

        // Sample size warning
        sb.append("if len(df) < 100: result['sample_warning'] = f'样本量仅{len(df)}行，模型结果可能不可靠，建议至少200行以上'\n");
        sb.append("if y is not None and 'classification' in '").append(model.getModelType()).append("':\n");
        sb.append("    _counts = y.value_counts()\n    if len(_counts) >= 2:\n        _ratio = _counts.min() / _counts.max()\n");
        sb.append("        if _ratio < 0.1: result['imbalance_warning'] = f'类别不平衡比为{round(_ratio,3)}，少数类仅{_counts.min()}条，建议增加数据或使用class_weight参数'\n\n");

        sb.append("val_result = {}\nif _val_mode in ('cv', 'oos') and y is not None:\n");
        sb.append("    _cv_scoring = 'f1_weighted' if 'classification' in '").append(model.getModelType()).append("' else 'r2'\n");
        sb.append("    try:\n        _cv_scores = cross_val_score(clf.__class__(**params) if params else clf.__class__(), X, y, cv=_cv_folds, scoring=_cv_scoring)\n");
        sb.append("        val_result['cv_mean'] = round(float(_cv_scores.mean()), 4)\n        val_result['cv_std'] = round(float(_cv_scores.std()), 4)\n        val_result['cv_folds'] = _cv_folds\n");
        sb.append("    except Exception as _e: val_result['cv_error'] = str(_e)\n");
        sb.append("elif _val_mode == 'temporal' and y_test is not None:\n    val_result['temporal_split'] = 'train={}/test={}'.format(len(X_train), len(X_test))\n\n");

        sb.append("fi = {}\nif hasattr(clf, 'feature_importances_'): fi = dict(zip(X_train.columns, [round(float(v), 4) for v in clf.feature_importances_]))\n");
        sb.append("elif hasattr(clf, 'coef_'): fi = dict(zip(X_train.columns, [round(float(v), 4) for v in clf.coef_.flatten()]))\n\n");

        sb.append("_workspace = '").append(MODEL_WORKSPACE).append("'\nos.makedirs(_workspace, exist_ok=True)\n");
        sb.append("_model_path = os.path.join(_workspace, 'model_").append(model.getId()).append("_v' + clf.__class__.__name__ + '.pkl')\n");
        sb.append("joblib.dump(clf, _model_path)\n");
        sb.append("_preproc_path = os.path.join(_workspace, 'model_").append(model.getId()).append("_preprocessors.pkl')\n");
        sb.append("joblib.dump({'encoders': _encoders, 'scaler': _scaler, 'feature_cols': list(X_train.columns), 'encoding': _enc, 'scaling': _sc}, _preproc_path)\n\n");

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
        Map<String, Object> dsConfig = new LinkedHashMap<>();
        dsConfig.put("title", "数据接入");
        dsConfig.put("table", model.getSourceTable());
        nodes.add(Map.of("id", "ds_" + model.getId(), "type", "data_source", "config", dsConfig));

        Map<String, Object> ppConfig = new LinkedHashMap<>();
        ppConfig.put("title", "数据预处理");
        ppConfig.put("handleMissing", preprocessing.getOrDefault("handleMissing", "drop"));
        ppConfig.put("encoding", preprocessing.getOrDefault("encoding", "label"));
        ppConfig.put("scaling", preprocessing.getOrDefault("scaling", "none"));
        nodes.add(Map.of("id", "pp_" + model.getId(), "type", "preprocessing", "config", ppConfig));

        Map<String, Object> feConfig = new LinkedHashMap<>();
        feConfig.put("title", "特征工程");
        feConfig.put("featureColumns", featureCols);
        feConfig.put("targetColumn", model.getTargetColumn());
        nodes.add(Map.of("id", "fe_" + model.getId(), "type", "feature_engineering", "config", feConfig));

        Map<String, Object> trConfig = new LinkedHashMap<>();
        trConfig.put("title", "模型训练");
        trConfig.put("modelType", model.getModelType());
        trConfig.put("algorithm", model.getAlgorithm());
        trConfig.put("hyperparams", parseJsonMap(model.getHyperparameters()));
        nodes.add(Map.of("id", "tr_" + model.getId(), "type", "training", "config", trConfig));

        Map<String, Object> evConfig = new LinkedHashMap<>();
        evConfig.put("title", "模型评估");
        evConfig.put("testSize", (int)((model.getTestSize() != null ? model.getTestSize() : 0.2) * 100));
        evConfig.put("cvFold", model.getCvFolds() != null ? model.getCvFolds() : 5);
        if (model.getValidationMode() != null) evConfig.put("validationMode", model.getValidationMode());
        if (model.getTemporalColumn() != null) evConfig.put("temporalColumn", model.getTemporalColumn());
        nodes.add(Map.of("id", "ev_" + model.getId(), "type", "evaluation", "config", evConfig));

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

    private void syncHyperparamsToPipeline(Long pipelineId, String hyperparamsJson) {
        try {
            MiningPipeline pipeline = miningPipelineMapper.selectById(pipelineId);
            if (pipeline == null) return;
            List<Map<String, Object>> nodes = parseJsonNodeList(pipeline.getNodes());
            Map<String, Object> hp = parseJsonMap(hyperparamsJson);
            for (Map<String, Object> node : nodes) {
                if ("training".equals(node.get("type"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> config = (Map<String, Object>) node.get("config");
                    if (config != null) config.put("hyperparams", new LinkedHashMap<>(hp));
                }
            }
            pipeline.setNodes(objectMapper.writeValueAsString(nodes));
            miningPipelineMapper.updateById(pipeline);
        } catch (Exception e) {
            log.warn("[MINING] Failed to sync hyperparams to pipeline {}: {}", pipelineId, e.getMessage());
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

    private String buildSqlalchemyUrl(DataSource ds) {
        return "mysql+pymysql://%s:%s@%s:%d/%s".formatted(
            URLEncoder.encode(ds.getUsername(), StandardCharsets.UTF_8),
            URLEncoder.encode(ds.getPassword(), StandardCharsets.UTF_8),
            ds.getHost(), ds.getPort(), ds.getDatabaseName());
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
        if (log == null || log.length() <= maxLen) return log;
        return log.substring(0, maxLen) + "\n... (truncated)";
    }

    private void validateTableName(String name) {
        if (name != null && !name.isBlank() && !name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$"))
            throw new IllegalArgumentException("无效的表名: " + name);
    }

    private void validateColumnName(String name) {
        if (name != null && !name.isBlank() && !name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$"))
            throw new IllegalArgumentException("无效的列名: " + name);
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
