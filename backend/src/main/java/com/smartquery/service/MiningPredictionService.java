package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.ModelStatus;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.PredictionResult;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.PredictionResultMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private final PredictionResultMapper predictionResultMapper;
    private final MiningRuntimeClient miningRuntimeClient;
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

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("mode", "rows");
        request.put("modelPath", model.getModelPath());
        request.put("inputRows", inputRows);
        request.put("resultTable", saveTable);
        MiningRuntimeClient.RuntimeResult runtime = miningRuntimeClient.execute(
            "predict", request, model.getDataSourceId(), predictTimeoutMs);

        if (!runtime.successful()) {
            String error = runtime.errorMessage();
            logPredictionEvent(model, "mining_prediction_complete", Map.of("status", "failed", "error", truncateLog(error, 300)));
            throw new RuntimeException("预测执行失败: " + truncateLog(error, predictErrorTruncation));
        }

        Map<String, Object> parsed = runtime.payload();
        if (!(parsed.get("predictions") instanceof List<?>)) {
            throw new IllegalStateException("Python 预测结果缺少 predictions");
        }
        String batchId = "single_" + System.currentTimeMillis();
        savePredictionResults(model, inputRows, parsed, batchId);

        logPredictionEvent(model, "mining_prediction_complete", Map.of(
            "status", "success", "predictions", parsed.getOrDefault("predictions", List.of()).hashCode(),
            "durationMs", runtime.process().executionTimeMs()
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

        MiningRuntimeClient.RuntimeResult runtime = executeBatchPrediction(
            model, inputTable, resultTable, model.getPredictInputFilter());

        if (!runtime.successful()) {
            String err = runtime.errorMessage();
            logPredictionEvent(model, "mining_prediction_complete", Map.of("status", "failed", "batchId", batchId, "error", truncateLog(err, 300)));
            throw new RuntimeException("批量预测失败: " + truncateLog(err, predictErrorTruncation));
        }

        Map<String, Object> parsed = runtime.payload();
        validateBatchPayload(parsed);

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
            "resultTable", resultTable, "durationMs", runtime.process().executionTimeMs()
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

        MiningRuntimeClient.RuntimeResult runtime = executeBatchPrediction(model, inputTable, resultTable, filter);

        if (!runtime.successful()) {
            String err = runtime.errorMessage();
            logPredictionEvent(model, "mining_prediction_complete", Map.of("status", "failed", "batchId", batchId, "error", truncateLog(err, 300)));
            throw new RuntimeException("批量预测失败: " + truncateLog(err, predictErrorTruncation));
        }

        Map<String, Object> parsed = runtime.payload();
        validateBatchPayload(parsed);
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
        if (!Integer.valueOf(MiningRuntimeClient.ARTIFACT_SCHEMA_VERSION)
                .equals(model.getArtifactSchemaVersion())) {
            throw new IllegalStateException("该模型使用旧版制品，未包含完整 sklearn Pipeline；请重新训练后再预测");
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

    private MiningRuntimeClient.RuntimeResult executeBatchPrediction(
            MiningModel model, String inputTable, String resultTable, String filter) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("mode", "batch");
        request.put("modelPath", model.getModelPath());
        request.put("inputTable", inputTable);
        request.put("inputFilter", resolveFilterVariables(filter));
        request.put("resultTable", resultTable);
        return miningRuntimeClient.execute(
            "predict", request, model.getDataSourceId(), batchPredictTimeoutMs);
    }

    private void validateBatchPayload(Map<String, Object> payload) {
        if (!(payload.get("saved_rows") instanceof Number) || payload.get("saved_to") == null) {
            throw new IllegalStateException("Python 批量预测结果缺少 saved_rows 或 saved_to");
        }
    }

    @SuppressWarnings("unchecked")
    private String toJson(Object obj) {
        return MiningLogUtils.toJson(obj, objectMapper);
    }

    private String truncateLog(String log, int maxLen) {
        return MiningLogUtils.truncateLog(log, maxLen, false);
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
