package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.ModelStatus;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.DataSource;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.PredictionResult;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.mapper.DataSourceMapper;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.PredictionResultMapper;
import com.smartquery.python.PythonExecutor;
import com.smartquery.python.PythonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MiningPredictionService {

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.predict-timeout-ms:120000}")
    private int predictTimeoutMs;

    @org.springframework.beans.factory.annotation.Value("${mining.batch-predict-timeout-ms:300000}")
    private int batchPredictTimeoutMs;

    @org.springframework.beans.factory.annotation.Value("${mining.predict-error-truncation:2000}")
    private int predictErrorTruncation;

    private final MiningModelMapper miningModelMapper;
    private final DataSourceMapper dataSourceMapper;
    private final PredictionResultMapper predictionResultMapper;
    private final PythonExecutor pythonExecutor;
    private final DataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final ConversationEventLogger eventLogger;

    public Map<String, Object> predict(Long modelId, List<Map<String, Object>> inputRows, String saveTable) {
        if (inputRows == null || inputRows.isEmpty()) {
            throw new IllegalArgumentException("预测输入数据不能为空");
        }
        MiningModel model = miningModelMapper.selectById(modelId);
        validateForPrediction(model);
        if (saveTable != null && !saveTable.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateTableName(saveTable);
        }

        logPredictionEvent(model, "mining_prediction_start", Map.of("inputRows", inputRows.size(), "mode", "single"));

        String pythonCode = buildPredictionScript(model, inputRows, saveTable);
        PythonResult result = pythonExecutor.execute(pythonCode, model.getDataSourceId(), predictTimeoutMs);

        if (result.exitCode() != 0) {
            logPredictionEvent(model, "mining_prediction_complete", Map.of("status", "failed", "error", truncateLog(result.stderr(), 300)));
            throw new RuntimeException("预测执行失败: " + (result.stderr().isBlank() ? result.stdout() : result.stderr()));
        }

        Map<String, Object> parsed = parseResultMarker(result.stdout(), "[PREDICT_RESULT]");
        String batchId = "single_" + System.currentTimeMillis();
        savePredictionResults(model, inputRows, parsed, batchId);

        logPredictionEvent(model, "mining_prediction_complete", Map.of(
            "status", "success", "predictions", parsed.getOrDefault("predictions", List.of()).hashCode(),
            "durationMs", result.executionTimeMs()
        ));
        return parsed;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> batchPredict(Long modelId) {
        return batchPredict(modelId, null);
    }

    public Map<String, Object> batchPredict(Long modelId, String overrideResultTable) {
        MiningModel model = miningModelMapper.selectById(modelId);
        validateForPrediction(model);

        String inputTable = model.getPredictInputTable() != null && !model.getPredictInputTable().isBlank()
            ? model.getPredictInputTable() : model.getSourceTable();
        String resultTable = overrideResultTable != null && !overrideResultTable.isBlank()
            ? overrideResultTable : model.getPredictResultTable();
        if (resultTable == null || resultTable.isBlank()) {
            resultTable = model.getSourceTable() + "_predict";
        }
        if (inputTable == null || inputTable.isBlank()) {
            throw new IllegalStateException("未配置预测输入表且模型无源表，请在模型设置中指定");
        }
        com.smartquery.common.IdentifierValidator.validateTableName(inputTable);
        if (resultTable != null && !resultTable.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateTableName(resultTable);
        }
        if (model.getPredictInputFilter() != null && !model.getPredictInputFilter().isBlank()) {
            com.smartquery.common.IdentifierValidator.validateFilter(model.getPredictInputFilter());
        }

        String batchId = "batch_" + System.currentTimeMillis();
        log.info("[PREDICT] Batch predict: model='{}', input='{}', result='{}', batch={}",
            model.getName(), inputTable, resultTable, batchId);

        logPredictionEvent(model, "mining_prediction_start", Map.of("inputTable", inputTable, "mode", "batch", "batchId", batchId));

        String pythonCode = buildBatchPredictionScript(model, inputTable, resultTable);
        PythonResult result = pythonExecutor.execute(pythonCode, model.getDataSourceId(), batchPredictTimeoutMs);

        if (result.exitCode() != 0) {
            String err = result.stderr().isBlank() ? result.stdout() : result.stderr();
            logPredictionEvent(model, "mining_prediction_complete", Map.of("status", "failed", "batchId", batchId, "error", truncateLog(err, 300)));
            throw new RuntimeException("批量预测失败: " + truncateLog(err, predictErrorTruncation));
        }

        Map<String, Object> parsed = parseResultMarker(result.stdout(), "[BATCH_PREDICT_RESULT]");

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

        log.info("[PREDICT] Batch predict completed: {} rows predicted, saved to '{}'", savedRows, resultTable);

        logPredictionEvent(model, "mining_prediction_complete", Map.of(
            "status", "success", "batchId", batchId, "savedRows", savedRows,
            "resultTable", resultTable, "durationMs", result.executionTimeMs()
        ));
        return parsed;
    }

    public Map<String, Object> batchPredictWithConfig(Long modelId, String overrideInputTable, String overrideResultTable, String overrideFilter) {
        MiningModel model = miningModelMapper.selectById(modelId);
        validateForPrediction(model);

        String inputTable = (overrideInputTable != null && !overrideInputTable.isBlank())
            ? overrideInputTable
            : (model.getPredictInputTable() != null && !model.getPredictInputTable().isBlank()
                ? model.getPredictInputTable() : model.getSourceTable());
        String resultTable = (overrideResultTable != null && !overrideResultTable.isBlank())
            ? overrideResultTable : model.getPredictResultTable();
        if (resultTable == null || resultTable.isBlank()) {
            resultTable = model.getSourceTable() + "_predict";
        }
        String filter = (overrideFilter != null && !overrideFilter.isBlank())
            ? overrideFilter : model.getPredictInputFilter();

        if (inputTable == null || inputTable.isBlank()) {
            throw new IllegalStateException("未配置预测输入表且模型无源表");
        }
        com.smartquery.common.IdentifierValidator.validateTableName(inputTable);
        com.smartquery.common.IdentifierValidator.validateTableName(resultTable);
        if (filter != null && !filter.isBlank()) {
            com.smartquery.common.IdentifierValidator.validateFilter(filter);
        }

        String batchId = "batch_" + System.currentTimeMillis();
        log.info("[PREDICT] Batch predict with overrides: model='{}', input='{}', result='{}', batch={}",
            model.getName(), inputTable, resultTable, batchId);

        logPredictionEvent(model, "mining_prediction_start", Map.of("inputTable", inputTable, "mode", "batch_override", "batchId", batchId));

        String pythonCode = buildBatchPredictionScript(model, inputTable, resultTable, filter);
        PythonResult result = pythonExecutor.execute(pythonCode, model.getDataSourceId(), batchPredictTimeoutMs);

        if (result.exitCode() != 0) {
            String err = result.stderr().isBlank() ? result.stdout() : result.stderr();
            logPredictionEvent(model, "mining_prediction_complete", Map.of("status", "failed", "batchId", batchId, "error", truncateLog(err, 300)));
            throw new RuntimeException("批量预测失败: " + truncateLog(err, predictErrorTruncation));
        }

        Map<String, Object> parsed = parseResultMarker(result.stdout(), "[BATCH_PREDICT_RESULT]");
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

        log.info("[PREDICT] Batch predict with overrides completed: {} rows to '{}'", savedRows, resultTable);
        return parsed;
    }

    public List<PredictionResult> getPredictionResults(Long modelId, int limit) {
        return predictionResultMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PredictionResult>()
                .eq(PredictionResult::getModelId, modelId)
                .orderByDesc(PredictionResult::getPredictedAt)
                .last("LIMIT " + limit));
    }

    // ===== Internal =====

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

    private String resolveFilterVariables(String filter) {
        if (filter == null || filter.isBlank()) return null;
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        java.time.LocalDate today = java.time.LocalDate.now();
        String todayStr = "'" + today.format(fmt) + "'";
        String yesterdayStr = "'" + today.minusDays(1).format(fmt) + "'";
        String resolved = filter;
        // Handle quoted variable patterns first: '${etl_date}' -> '2026-05-16' (not ''2026-05-16'')
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

    @SuppressWarnings("unchecked")
    private String buildPredictionScript(MiningModel model, List<Map<String, Object>> inputRows, String saveTable) {
        DataSource ds = dataSourceMapper.selectById(model.getDataSourceId());
        String dbUrl = ds != null ? buildSqlalchemyUrl(ds) : "";

        StringBuilder sb = new StringBuilder();
        sb.append("import pandas as pd\nimport numpy as np\nimport json\nimport joblib\nimport os\n\n");
        sb.append("model_path = '").append(model.getModelPath()).append("'\n");
        sb.append("if not os.path.exists(model_path):\n");
        sb.append("    print('[PREDICT_RESULT] ' + json.dumps({'error': '模型文件不存在: ' + model_path}))\n    exit(1)\n");
        sb.append("clf = joblib.load(model_path)\n\n");
        sb.append("input_data = ").append(objectMapper.valueToTree(inputRows).toString()).append("\n");
        sb.append("df = pd.DataFrame(input_data)\nprint(f'[INFO] Predicting {len(df)} rows')\n\n");

        sb.append("_preproc_path = os.path.join(os.path.dirname(model_path), 'model_").append(model.getId()).append("_preprocessors.pkl')\n");
        sb.append("_preproc = joblib.load(_preproc_path) if os.path.exists(_preproc_path) else None\n");
        sb.append("preprocessing = ").append(safeJsonEmbed(model.getPreprocessing())).append("\n");
        appendPreprocessingBlock(sb, "df");

        sb.append("predictions = clf.predict(df)\nresult = {'predictions': predictions.tolist()}\n\n");
        sb.append("if hasattr(clf, 'predict_proba'):\n    try:\n");
        sb.append("        proba = clf.predict_proba(df)\n        result['probabilities'] = proba.tolist()\n    except: pass\n\n");

        if (saveTable != null && !saveTable.isBlank() && !dbUrl.isEmpty()) {
            sb.append("from sqlalchemy import create_engine\n");
            sb.append("engine = create_engine('").append(dbUrl).append("')\n");
            sb.append("save_df = pd.DataFrame(input_data)\nsave_df['prediction'] = predictions\n");
            sb.append("if 'probabilities' in result:\n    import numpy as np\n    save_df['prediction_proba'] = [max(p) for p in result['probabilities']]\n");
            sb.append("save_df.to_sql('").append(saveTable).append("', engine, if_exists='append', index=False)\n");
            sb.append("result['saved_to'] = '").append(saveTable).append("'\nresult['saved_rows'] = len(save_df)\n\n");
        }
        sb.append("print('[PREDICT_RESULT] ' + json.dumps(result))\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String buildBatchPredictionScript(MiningModel model, String inputTable, String resultTable) {
        return buildBatchPredictionScript(model, inputTable, resultTable, model.getPredictInputFilter());
    }

    private String buildBatchPredictionScript(MiningModel model, String inputTable, String resultTable, String filterOverride) {
        DataSource ds = dataSourceMapper.selectById(model.getDataSourceId());
        String dbUrl = ds != null ? buildSqlalchemyUrl(ds) : "";

        StringBuilder sb = new StringBuilder();
        sb.append("import pandas as pd\nimport numpy as np\nimport json\nimport joblib\nimport os\n");
        sb.append("from sqlalchemy import create_engine, text\n\n");
        sb.append("model_path = '").append(model.getModelPath()).append("'\n");
        sb.append("if not os.path.exists(model_path):\n");
        sb.append("    print('[BATCH_PREDICT_RESULT] ' + json.dumps({'error': '模型文件不存在'}))\n    exit(1)\n");
        sb.append("clf = joblib.load(model_path)\n\nengine = create_engine('").append(dbUrl).append("')\n");

        String filter = resolveFilterVariables(filterOverride);
        if (filter != null && !filter.isBlank()) {
            sb.append("_filter = \"").append(filter.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
            sb.append("_query = 'SELECT * FROM `").append(inputTable).append("` WHERE ' + _filter\n");
            sb.append("print(f'[INFO] Query: SELECT * FROM ").append(inputTable).append(" WHERE {_filter}')\n");
            sb.append("df = pd.read_sql(_query, engine)\n");
        } else {
            sb.append("df = pd.read_sql('SELECT * FROM `").append(inputTable).append("`', engine)\n");
        }
        sb.append("print(f'[INFO] Loaded {len(df)} rows from ").append(inputTable).append("')\n\n");

        String tableName = (resultTable != null && !resultTable.isBlank())
            ? resultTable : "predict_" + model.getAlgorithm() + "_" + inputTable;
        sb.append("if len(df) == 0:\n");
        sb.append("    print('[BATCH_PREDICT_RESULT] ' + json.dumps({'saved_to': '").append(tableName).append("', 'saved_rows': 0, 'warning': '筛选后无数据'}))\n    exit(0)\n\n");

        sb.append("feature_cols = ").append(model.getFeatureColumns()).append("\n");
        sb.append("if isinstance(feature_cols, str):\n    feature_cols = [c.strip() for c in feature_cols.split(',') if c.strip()]\n");
        sb.append("missing = [c for c in feature_cols if c not in df.columns]\n");
        sb.append("if missing:\n    print(f'[ERROR] Input table missing columns: {missing}')\n    exit(1)\n");
        sb.append("X = df[feature_cols].copy()\n\n");

        sb.append("_preproc_path = os.path.join(os.path.dirname(model_path), 'model_").append(model.getId()).append("_preprocessors.pkl')\n");
        sb.append("_preproc = joblib.load(_preproc_path) if os.path.exists(_preproc_path) else None\n");
        sb.append("preprocessing = ").append(safeJsonEmbed(model.getPreprocessing())).append("\n");
        appendPreprocessingBlock(sb, "X");

        sb.append("predictions = clf.predict(X)\nresult_df = df.copy()\nresult_df['prediction'] = predictions\n\n");
        sb.append("if hasattr(clf, 'predict_proba'):\n    try:\n");
        sb.append("        proba = clf.predict_proba(X)\n        result_df['prediction_proba'] = [max(p) for p in proba]\n    except: pass\n\n");
        sb.append("result_df['predicted_at'] = pd.Timestamp.now()\n");
        sb.append("for c in result_df.columns:\n");
        sb.append("    if result_df[c].dtype == 'object': result_df[c] = result_df[c].astype(str)\n");
        sb.append("    elif str(result_df[c].dtype).startswith('datetime'): result_df[c] = result_df[c].astype(str)\n");
        // Auto-create output table if it doesn't exist
        sb.append("_check_sql = \"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = :tname\"\n");
        sb.append("with engine.connect() as _conn:\n");
        sb.append("    _exists = _conn.execute(text(_check_sql), {'tname': '").append(tableName).append("'}).fetchone()[0]\n");
        sb.append("    if _exists == 0:\n");
        sb.append("        result_df.head(0).to_sql('").append(tableName).append("', engine, if_exists='fail', index=False)\n");
        sb.append("        print(f'[INFO] Auto-created table ").append(tableName).append("')\n");
        sb.append("    _conn.commit()\n");
        sb.append("result_df.to_sql('").append(tableName).append("', engine, if_exists='append', index=False)\n");
        sb.append("print(f'[INFO] Saved {len(result_df)} rows to ").append(tableName).append("')\n\n");
        sb.append("result = {\n    'saved_to': '").append(tableName).append("',\n    'saved_rows': len(result_df),\n    'columns': list(result_df.columns)\n}\n");
        sb.append("print('[BATCH_PREDICT_RESULT] ' + json.dumps(result))\n");
        return sb.toString();
    }

    private void appendPreprocessingBlock(StringBuilder sb, String varName) {
        sb.append("_dt_cols = ").append(varName).append(".select_dtypes(include=['datetime', 'datetimetz']).columns.tolist()\n");
        sb.append("if _dt_cols:\n    for c in _dt_cols:\n        ").append(varName).append("[c] = pd.to_numeric(").append(varName).append("[c].astype('int64'), errors='coerce')\n");
        sb.append("_enc = preprocessing.get('encoding', 'label')\n");
        sb.append("cat_cols = ").append(varName).append(".select_dtypes(include=['object']).columns.tolist()\n");
        sb.append("if cat_cols:\n");
        sb.append("    if _enc == 'onehot' and _preproc and '_onehot_columns' in _preproc.get('encoders', {}):\n");
        sb.append("        ").append(varName).append(" = pd.get_dummies(").append(varName).append(", columns=cat_cols)\n");
        sb.append("        _train_cols = _preproc['encoders']['_onehot_columns']\n");
        sb.append("        for c in _train_cols:\n            if c not in ").append(varName).append(".columns: ").append(varName).append("[c] = 0\n");
        sb.append("        ").append(varName).append(" = ").append(varName).append("[_train_cols]\n");
        sb.append("    elif _preproc and 'encoders' in _preproc:\n");
        sb.append("        for c in cat_cols:\n");
        sb.append("            if c in _preproc['encoders']:\n");
        sb.append("                _le = _preproc['encoders'][c]\n");
        sb.append("                ").append(varName).append("[c] = ").append(varName).append("[c].astype(str).map(lambda v: _le.transform([v])[0] if v in _le.classes_ else -1)\n");
        sb.append("            else:\n                from sklearn.preprocessing import LabelEncoder\n                ").append(varName).append("[c] = LabelEncoder().fit_transform(").append(varName).append("[c].astype(str))\n");
        sb.append("    else:\n        from sklearn.preprocessing import LabelEncoder\n        le = LabelEncoder()\n        for c in cat_cols:\n            ").append(varName).append("[c] = le.fit_transform(").append(varName).append("[c].astype(str))\n\n");
        sb.append("_sc = preprocessing.get('scaling', 'none')\n");
        sb.append("if _preproc and _preproc.get('scaler') is not None:\n");
        sb.append("    num_cols = ").append(varName).append(".select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("    if num_cols: ").append(varName).append("[num_cols] = _preproc['scaler'].transform(").append(varName).append("[num_cols])\n");
        sb.append("elif _sc == 'standard':\n    from sklearn.preprocessing import StandardScaler\n");
        sb.append("    num_cols = ").append(varName).append(".select_dtypes(include=['number']).columns.tolist()\n    if num_cols: ").append(varName).append("[num_cols] = StandardScaler().fit_transform(").append(varName).append("[num_cols])\n");
        sb.append("elif _sc == 'minmax':\n    from sklearn.preprocessing import MinMaxScaler\n");
        sb.append("    num_cols = ").append(varName).append(".select_dtypes(include=['number']).columns.tolist()\n    if num_cols: ").append(varName).append("[num_cols] = MinMaxScaler().fit_transform(").append(varName).append("[num_cols])\n\n");
    }

    private String buildSqlalchemyUrl(DataSource ds) {
        String user = URLEncoder.encode(ds.getUsername(), StandardCharsets.UTF_8);
        String pass = URLEncoder.encode(ds.getPassword(), StandardCharsets.UTF_8);
        String type = ds.getType() != null ? ds.getType().toLowerCase() : "mysql";
        String driver = switch (type) {
            case "postgresql" -> "postgresql+psycopg2";
            default -> "mysql+pymysql";
        };
        return "%s://%s:%s@%s:%d/%s".formatted(driver, user, pass, ds.getHost(), ds.getPort(), ds.getDatabaseName());
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
                    log.warn("[PREDICT] Failed to parse marker '{}': {}", marker, e.getMessage());
                }
                break;
            }
        }
        return result;
    }

    private String safeJsonEmbed(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) return "{}";
        try {
            return objectMapper.readTree(json).toString();
        } catch (Exception e) {
            log.warn("[PREDICT] Invalid JSON rejected for Python embedding: {}", e.getMessage());
            return "{}";
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return String.valueOf(obj); }
    }

    private String truncateLog(String log, int maxLen) {
        if (log == null) return null;
        String cleaned = log.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "");
        if (cleaned.length() <= maxLen) return cleaned;
        return cleaned.substring(0, maxLen) + "\n... (truncated)";
    }

    private void logPredictionEvent(MiningModel model, String eventType, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modelId", model.getId());
        payload.put("modelName", model.getName());
        payload.put("algorithm", model.getAlgorithm());
        payload.putAll(extra);
        eventLogger.logEvent(model.getConversationId(), null, eventType, payload);
    }
}
