package com.smartquery.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.Algorithm;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.ModelExecution;
import com.smartquery.entity.DataSource;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.service.AlgorithmService;
import com.smartquery.service.MiningService;
import com.smartquery.service.MiningRuntimeClient;
import com.smartquery.service.ResourceAccessService;
import com.smartquery.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class MiningModelTool implements LlmTool {

    private final MiningModelMapper miningModelMapper;
    private final MiningService miningService;
    private final ResourceAccessService resourceAccess;
    private final AlgorithmService algorithmService;
    private final ObjectMapper objectMapper;

    @Value("${smart-query.mining.tool-timeout-ms:300000}")
    private long miningToolTimeoutMs;

    @Value("${smart-query.mining.compare.max-algorithms:5}")
    private int compareMaxAlgorithms;

    @Value("${smart-query.mining.tune.max-combinations:12}")
    private int tuneMaxCombinations;

    @Value("${smart-query.mining.history-limit:20}")
    private int historyLimit;

    private static final Set<String> MODEL_SCOPED_ACTIONS = Set.of(
        "get", "update", "update_params", "train", "retrain", "tune",
        "sync_pipeline", "publish", "offline", "predict", "batch_predict",
        "validate", "history", "monitor", "cancel", "governance", "drift_check"
    );

    @Override
    public String getName() { return "mining_model"; }

    @Override
    public String getDescription() {
        return "数据建模助手: 在当前用户和会话的数据源权限内探索数据，协助设计预处理、特征、算法和超参数，完成训练、评估分析、过程监控、模板固化与流程图同步，并支持修改学习率等参数后重新训练。";
    }

    @Override
    public Map<String, Object> getJsonSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", Map.of("type", "string", "description",
            "操作类型: list/get/create/update/update_params/train/retrain/tune/monitor/cancel/sync_pipeline/validate/governance/drift_check/publish/offline/predict/batch_predict/explore_data/list_algorithms/create_algorithm/compare/history"));
        props.put("model_id", Map.of("type", "integer", "description", "模型ID (list操作不需要)"));
        props.put("execution_id", Map.of("type", "integer", "description", "训练执行ID (monitor/cancel时可使用，train会返回)"));
        props.put("name", Map.of("type", "string", "description", "模型名称"));
        props.put("algorithm", Map.of("type", "string", "description", "算法标识 (可通过 list_algorithms 查看所有可用算法，包括自定义算法)"));
        props.put("algorithms", Map.of("type", "array", "description", "对比训练时使用的算法列表 (compare时使用), 如 ['random_forest','xgboost']"));
        props.put("model_type", Map.of("type", "string", "description", "类型: classification, regression, clustering"));
        props.put("source_table", Map.of("type", "string", "description", "源数据表名"));
        props.put("feature_columns", Map.of("type", "string", "description", "特征列名,逗号分隔"));
        props.put("target_column", Map.of("type", "string", "description", "目标列名"));
        props.put("hyperparameters", Map.of("type", "object", "description", "超参数"));
        props.put("description", Map.of("type", "string", "description", "模型描述"));
        props.put("predict_input", Map.of("type", "array", "description", "预测输入数据(JSON数组), 每个元素是一个特征键值对"));
        props.put("save_table", Map.of("type", "string", "description", "预测结果保存到的表名, 留空则不保存"));
        props.put("input_table", Map.of("type", "string", "description", "批量预测的输入表名 (batch_predict时使用)"));
        props.put("result_table", Map.of("type", "string", "description", "批量预测结果写入的表名 (batch_predict时使用)"));
        props.put("input_filter", Map.of("type", "string", "description", "输入表筛选条件 (publish时使用), 支持${etl_date}变量(自动替换为当天日期), 如 application_date <= ${etl_date}"));
        props.put("schedule_enabled", Map.of("type", "boolean", "description", "发布时是否启用定时调度 (publish时使用)"));
        props.put("schedule_cron", Map.of("type", "string", "description", "标准5字段cron表达式 (publish时使用), 如 */30 * * * *=每30分钟, 0 6 * * *=每天6:00, 0 8 * * 1=每周一8:00"));
        props.put("schedule_mode", Map.of("type", "string", "description", "调度模式 (publish时使用): train=定期重训, predict=定期预测"));
        props.put("validation_mode", Map.of("type", "string", "description", "验证模式: cv=交叉验证, oos=独立外部样本表, temporal=滚动时间外验证；普通train_test不能发布"));
        props.put("cv_folds", Map.of("type", "integer", "description", "交叉验证折数(默认5)"));
        props.put("test_size", Map.of("type", "number", "description", "测试集比例(默认0.2)"));
        props.put("temporal_column", Map.of("type", "string", "description", "时间列名(用于时序验证,如created_at,date等)"));
        props.put("group_columns", Map.of("type", "array", "description", "实体隔离列，如企业ID/客户ID/合同ID；同一实体不会跨训练测试集"));
        props.put("oos_table", Map.of("type", "string", "description", "独立且锁定的OOS数据表，validation_mode=oos时必填"));
        props.put("oos_filter", Map.of("type", "string", "description", "OOS数据快照筛选条件"));
        props.put("positive_class", Map.of("type", "string", "description", "二分类中业务风险正类标签；未配置时使用少数类并在结果中固化"));
        props.put("calibration_method", Map.of("type", "string", "description", "概率校准: none/sigmoid/isotonic"));
        props.put("threshold_policy", Map.of("type", "object", "description", "阈值策略: default/fixed/max_f1/min_recall/min_cost，可配置targetRecall、误报漏报成本"));
        props.put("governance_policy", Map.of("type", "object", "description", "发布门槛，如minBalancedAccuracy/minRiskRecall/maxCvStd/minTestRows/requireApproval"));
        props.put("preprocessing", Map.of("type", "object", "description", "预处理配置 (create/update时使用): {\"handleMissing\":\"drop|fill_mean|fill_median\",\"encoding\":\"label|onehot\",\"scaling\":\"none|standard|minmax\"}"));
        props.put("feature_transforms", Map.of("type", "array", "description", "特征变换列表 (create/retrain时使用), 每个元素: {\"type\":\"...\",\"columns\":[\"col1\"]}。类型: log(对数), binning(分箱), polynomial(多项式), interaction(交互), date_extract(日期提取), target_encode(目标编码), frequency_encode(频率编码)"));
        props.put("table_name", Map.of("type", "string", "description", "数据表名 (explore_data时使用)"));
        props.put("algorithm_id", Map.of("type", "string", "description", "自定义算法英文标识 (create_algorithm时必填)"));
        props.put("python_code_template", Map.of("type", "string", "description", "自定义算法估计器模板 (create_algorithm时必填)。可用 params、X、y、df、_model_type；只能构造并赋值 sklearn 兼容的 clf，不要在模板中执行 fit 或预处理"));
        props.put("model_types", Map.of("type", "array", "description", "自定义算法支持的模型类型 (create_algorithm时使用), 如['classification','regression']"));
        props.put("params_schema", Map.of("type", "array", "description", "自定义算法参数定义 (create_algorithm时使用), 每个参数包含key,label,type,defaultValue等"));
        props.put("param_grid", Map.of("type", "object", "description", "参数网格 (tune时使用), 键为参数名, 值为候选值数组, 如 {\"n_estimators\": [50, 100, 200], \"max_depth\": [5, 10]}"));
        return Map.of("type", "object", "properties", props, "required", List.of("action"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolExecutionContext context) {
        long start = System.currentTimeMillis();
        String action = (String) input.get("action");

        try {
            // Tool calls have exactly the same authorization boundary as REST
            // calls. Never rely on the LLM to choose only visible model IDs.
            com.smartquery.common.UserContextHolder.require();
            if (context.conversationId() != null && context.conversationId() > 0) {
                var conversation = resourceAccess.requireConversation(context.conversationId());
                if (conversation.getDataSourceId() != null && context.dataSourceId() != null
                        && !conversation.getDataSourceId().equals(context.dataSourceId())) {
                    throw new com.smartquery.common.BusinessException(403,
                        "建模数据源与当前会话绑定的数据源不一致");
                }
            }
            if (action != null && MODEL_SCOPED_ACTIONS.contains(action)) {
                Long modelId = toLong(input.get("model_id"));
                if (modelId != null) resourceAccess.requireModel(modelId);
            }
            ToolResult result = switch (action) {
                case "list" -> handleList(input, start);
                case "get" -> handleGet(input, start);
                case "create" -> handleCreate(input, context, start);
                case "update" -> handleUpdate(input, start);
                case "update_params" -> handleUpdateParams(input, start);
                case "train" -> handleTrain(input, start);
                case "retrain" -> handleRetrain(input, context, start);
                case "tune" -> handleTune(input, context, start);
                case "sync_pipeline" -> handleSyncPipeline(input, start);
                case "publish" -> handlePublish(input, start);
                case "offline" -> handleOffline(input, start);
                case "predict" -> handlePredict(input, start);
                case "batch_predict" -> handleBatchPredict(input, start);
                case "validate" -> handleValidate(input, start);
                case "explore_data" -> handleExploreData(input, context, start);
                case "list_algorithms" -> handleListAlgorithms(start);
                case "history" -> handleHistory(input, start);
                case "monitor" -> handleMonitor(input, start);
                case "cancel" -> handleCancel(input, start);
                case "governance" -> handleGovernance(input, start);
                case "drift_check" -> handleDriftCheck(input, start);
                case "create_algorithm" -> handleCreateAlgorithm(input, start);
                case "compare" -> handleCompare(input, context, start);
                default -> ToolResult.error(getName(), "未知操作: " + action, System.currentTimeMillis() - start);
            };
            return injectAction(result, action);
        } catch (Exception e) {
            log.error("[MINING-TOOL] Action {} failed: {}", action, e.getMessage());
            ToolResult err = ToolResult.error(getName(), action + " 失败: " + e.getMessage(), System.currentTimeMillis() - start);
            return injectAction(err, action);
        }
    }

    private ToolResult injectAction(ToolResult result, String action) {
        if (action == null) return result;
        List<Map<String, Object>> data = result.data();
        if (data == null || data.isEmpty()) {
            data = List.of(new LinkedHashMap<>(Map.of("__action", action)));
        } else {
            Map<String, Object> first = new LinkedHashMap<>(data.get(0));
            first.put("__action", action);
            List<Map<String, Object>> newData = new ArrayList<>();
            newData.add(first);
            for (int i = 1; i < data.size(); i++) newData.add(new LinkedHashMap<>(data.get(i)));
            data = newData;
        }
        return new ToolResult(result.toolName(), result.success(), result.output(), result.error(), result.durationMs(), data, result.toolError());
    }

    private ToolResult handleList(Map<String, Object> input, long start) {
        List<MiningModel> models = resourceAccess.listModels(null, null);

        StringBuilder sb = new StringBuilder("当前共有 ").append(models.size()).append(" 个挖掘模型:\n\n");
        for (MiningModel m : models) {
            sb.append("- **").append(m.getName()).append("** (ID: ").append(m.getId()).append(")\n");
            sb.append("  状态: ").append(m.getStatus());
            sb.append(" | 算法: ").append(m.getAlgorithm());
            sb.append(" | 类型: ").append(m.getModelType());
            sb.append(" | 表: ").append(m.getSourceTable());
            sb.append(" | 版本: v").append(m.getVersion()).append("\n");
            if (m.getHyperparameters() != null) {
                sb.append("  超参数: ").append(m.getHyperparameters()).append("\n");
            }
            if (m.getMetrics() != null) {
                sb.append("  评估指标: ").append(m.getMetrics()).append("\n");
            }
            sb.append("\n");
        }
        return ToolResult.ok(getName(), sb.toString(), System.currentTimeMillis() - start);
    }

    private ToolResult handleGet(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);
        MiningModel model = resourceAccess.requireModel(id);

        StringBuilder sb = new StringBuilder();
        sb.append("模型: ").append(model.getName()).append("\n");
        sb.append("ID: ").append(model.getId()).append("\n");
        sb.append("状态: ").append(model.getStatus()).append("\n");
        sb.append("算法: ").append(model.getAlgorithm()).append("\n");
        sb.append("类型: ").append(model.getModelType()).append("\n");
        sb.append("源表: ").append(model.getSourceTable()).append("\n");
        sb.append("目标列: ").append(model.getTargetColumn()).append("\n");
        sb.append("特征列: ").append(model.getFeatureColumns()).append("\n");
        sb.append("超参数: ").append(model.getHyperparameters()).append("\n");
        sb.append("版本: v").append(model.getVersion()).append("\n");
        if (model.getPipelineId() != null) sb.append("关联流程ID: ").append(model.getPipelineId()).append("\n");
        if (!Integer.valueOf(MiningRuntimeClient.ARTIFACT_SCHEMA_VERSION)
                .equals(model.getArtifactSchemaVersion())) {
            sb.append("制品状态: 旧版或未生成完整 Pipeline，需要重新训练后才能预测/发布\n");
        } else {
            sb.append("制品版本: ").append(model.getArtifactSchemaVersion()).append("\n");
        }
        if (model.getMetrics() != null) sb.append("评估指标: ").append(model.getMetrics()).append("\n");
        if (model.getFeatureImportance() != null) sb.append("特征重要性: ").append(model.getFeatureImportance()).append("\n");
        if (model.getPreprocessing() != null && !model.getPreprocessing().isBlank() && !model.getPreprocessing().equals("{}"))
            sb.append("预处理: ").append(model.getPreprocessing()).append("\n");
        if (model.getValidationMode() != null) sb.append("验证模式: ").append(model.getValidationMode()).append("\n");
        return ToolResult.ok(getName(), sb.toString(), System.currentTimeMillis() - start);
    }

    private ToolResult handleCreate(Map<String, Object> input, ToolExecutionContext context, long start) {
        MiningModel model = new MiningModel();
        model.setName(getString(input, "name", "新模型"));
        model.setAlgorithm(getString(input, "algorithm", "random_forest"));
        model.setModelType(getString(input, "model_type", "classification"));
        model.setSourceTable(getString(input, "source_table", null));
        model.setTargetColumn(getString(input, "target_column", null));
        model.setDataSourceId(context.dataSourceId());
        model.setConversationId(context.conversationId());
        model.setSource("chat");

        Object features = input.get("feature_columns");
        if (features != null) {
            try {
                Object normalized = features;
                if (features instanceof String s) {
                    normalized = Arrays.stream(s.split(","))
                            .map(String::trim)
                            .filter(c -> !c.isEmpty())
                            .toList();
                }
                model.setFeatureColumns(objectMapper.writeValueAsString(normalized));
            } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
        }
        Object params = input.get("hyperparameters");
        if (params != null) {
            try { model.setHyperparameters(objectMapper.writeValueAsString(params)); } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
        }
        if (input.get("description") != null) model.setDescription((String) input.get("description"));

        Object preprocessing = input.get("preprocessing");
        if (preprocessing != null) {
            try { model.setPreprocessing(objectMapper.writeValueAsString(preprocessing)); } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
        }

        Object transforms = input.get("feature_transforms");
        if (transforms != null) {
            try {
                Map<String, Object> pp = model.getPreprocessing() != null && !model.getPreprocessing().isBlank()
                    ? objectMapper.readValue(model.getPreprocessing(), Map.class) : new java.util.HashMap<>();
                pp.put("transforms", transforms);
                model.setPreprocessing(objectMapper.writeValueAsString(pp));
            } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
        }

        if (input.get("validation_mode") != null) model.setValidationMode((String) input.get("validation_mode"));
        if (input.get("cv_folds") != null) model.setCvFolds(((Number) input.get("cv_folds")).intValue());
        if (input.get("test_size") != null) model.setTestSize(((Number) input.get("test_size")).doubleValue());
        if (input.get("temporal_column") != null) model.setTemporalColumn((String) input.get("temporal_column"));
        applyEvaluationConfig(model, input);

        MiningModel created = miningService.createModel(model);
        return ToolResult.ok(getName(), "已创建模型「" + created.getName() + "」(ID: " + created.getId() + ")。可以在管理页面查看或训练。",
            System.currentTimeMillis() - start, List.of(Map.of("modelId", created.getId(), "name", created.getName())));
    }

    private ToolResult handleUpdate(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);

        MiningModel updates = new MiningModel();
        if (input.get("name") != null) updates.setName((String) input.get("name"));
        if (input.get("algorithm") != null) updates.setAlgorithm((String) input.get("algorithm"));
        if (input.get("model_type") != null) updates.setModelType((String) input.get("model_type"));
        if (input.get("source_table") != null) updates.setSourceTable((String) input.get("source_table"));
        if (input.get("target_column") != null) updates.setTargetColumn((String) input.get("target_column"));
        if (input.get("validation_mode") != null) updates.setValidationMode((String) input.get("validation_mode"));
        if (input.get("cv_folds") != null) updates.setCvFolds(((Number) input.get("cv_folds")).intValue());
        if (input.get("test_size") != null) updates.setTestSize(((Number) input.get("test_size")).doubleValue());
        if (input.get("temporal_column") != null) updates.setTemporalColumn((String) input.get("temporal_column"));
        applyEvaluationConfig(updates, input);

        Object preprocessing = input.get("preprocessing");
        if (preprocessing != null) {
            try { updates.setPreprocessing(objectMapper.writeValueAsString(preprocessing)); } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
        }
        Object transforms = input.get("feature_transforms");
        if (transforms != null) {
            try {
                Map<String, Object> pp = preprocessing != null
                    ? (Map<String, Object>) objectMapper.readValue(objectMapper.writeValueAsString(preprocessing), Map.class)
                    : new java.util.HashMap<>();
                pp.put("transforms", transforms);
                updates.setPreprocessing(objectMapper.writeValueAsString(pp));
            } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
        }

        MiningModel updated = miningService.updateModel(id, updates);
        return ToolResult.ok(getName(), "已更新模型「" + updated.getName() + "」",
            System.currentTimeMillis() - start);
    }

    private ToolResult handleUpdateParams(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);

        Object params = input.get("hyperparameters");
        if (params == null) return ToolResult.error(getName(), "需要 hyperparameters", System.currentTimeMillis() - start);

        try {
            String json = objectMapper.writeValueAsString(params);
            MiningModel updated = miningService.updateHyperparameters(id, json);
            return ToolResult.ok(getName(),
                "已更新模型「" + updated.getName() + "」的超参数为: " + json,
                System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.error(getName(), "参数序列化失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private ToolResult handleTrain(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);

        // Apply any validation overrides passed with train
        MiningModel overrides = new MiningModel();
        boolean hasOverrides = false;
        if (input.get("validation_mode") != null) { overrides.setValidationMode((String) input.get("validation_mode")); hasOverrides = true; }
        if (input.get("cv_folds") != null) { overrides.setCvFolds(((Number) input.get("cv_folds")).intValue()); hasOverrides = true; }
        if (input.get("test_size") != null) { overrides.setTestSize(((Number) input.get("test_size")).doubleValue()); hasOverrides = true; }
        if (input.get("temporal_column") != null) { overrides.setTemporalColumn((String) input.get("temporal_column")); hasOverrides = true; }
        hasOverrides = applyEvaluationConfig(overrides, input) || hasOverrides;
        if (hasOverrides) miningService.updateModel(id, overrides);

        MiningService.TrainingSubmission submission = miningService.submitTraining(id, "chat");
        StringBuilder msg = new StringBuilder("训练任务已提交，执行ID: ")
            .append(submission.executionId())
            .append("。可使用 monitor 查询实际训练阶段、百分比和结果；请勿重复提交 train。");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("modelId", submission.modelId());
        data.put("executionId", submission.executionId());
        data.put("status", submission.status());
        data.put("stage", submission.stage());
        data.put("progressPercent", submission.progressPercent());
        return ToolResult.ok(getName(), msg.toString(), System.currentTimeMillis() - start, List.of(data));
    }

    private ToolResult handlePublish(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);

        Map<String, Object> config = new HashMap<>();
        if (input.get("input_table") != null) config.put("predictInputTable", input.get("input_table"));
        if (input.get("result_table") != null) config.put("predictResultTable", input.get("result_table"));
        if (input.get("input_filter") != null) config.put("predictInputFilter", input.get("input_filter"));
        if (input.containsKey("schedule_enabled")) config.put("scheduleEnabled", input.get("schedule_enabled"));
        if (input.get("schedule_cron") != null) config.put("scheduleCron", input.get("schedule_cron"));
        if (input.get("schedule_mode") != null) config.put("scheduleMode", input.get("schedule_mode"));

        MiningModel model = miningService.publishModel(id, config.isEmpty() ? null : config);
        StringBuilder msg = new StringBuilder("模型「").append(model.getName()).append("」已发布");
        if (Boolean.TRUE.equals(model.getScheduleEnabled())) {
            String mode = model.getScheduleMode() != null ? model.getScheduleMode() : "predict";
            msg.append("，定时调度已启用 (").append(mode).append(", cron: ").append(model.getScheduleCron()).append(")");
        }
        return ToolResult.ok(getName(), msg.toString(),
            System.currentTimeMillis() - start);
    }

    private ToolResult handleOffline(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);
        MiningModel model = miningService.offlineModel(id);
        return ToolResult.ok(getName(), "模型「" + model.getName() + "」已下线", System.currentTimeMillis() - start);
    }

    @SuppressWarnings("unchecked")
    private ToolResult handlePredict(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);
        Object inputObj = input.get("predict_input");
        if (inputObj == null) return ToolResult.error(getName(), "需要 predict_input (JSON数组)", System.currentTimeMillis() - start);

        List<Map<String, Object>> inputRows;
        if (inputObj instanceof List) {
            inputRows = (List<Map<String, Object>>) inputObj;
        } else {
            try { inputRows = objectMapper.readValue(objectMapper.writeValueAsString(inputObj), List.class); }
            catch (Exception e) { return ToolResult.error(getName(), "predict_input 格式错误", System.currentTimeMillis() - start); }
        }

        String saveTable = input.get("save_table") != null ? input.get("save_table").toString() : null;
        try {
            Map<String, Object> result = miningService.predictModel(id, inputRows, saveTable);
            StringBuilder sb = new StringBuilder("预测结果:\n");
            List<Object> preds = (List<Object>) result.get("predictions");
            for (int i = 0; i < inputRows.size() && i < preds.size(); i++) {
                sb.append("  第").append(i + 1).append("条: ").append(inputRows.get(i)).append(" → 预测值: ").append(preds.get(i));
                if (result.get("probabilities") != null) {
                    List<List<Number>> probs = (List<List<Number>>) result.get("probabilities");
                    if (i < probs.size()) {
                        double maxP = probs.get(i).stream().mapToDouble(Number::doubleValue).max().orElse(0);
                        sb.append(" (置信度: ").append(String.format("%.1f%%", maxP * 100)).append(")");
                    }
                }
                sb.append("\n");
            }
            if (result.get("saved_to") != null) {
                sb.append("\n已保存 ").append(result.get("saved_rows")).append(" 条到表 ").append(result.get("saved_to"));
            }
            return ToolResult.ok(getName(), sb.toString(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.error(getName(), "预测失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return null; }
    }

    private String getString(Map<String, Object> input, String key, String defaultVal) {
        Object v = input.get(key);
        return v != null ? v.toString() : defaultVal;
    }

    private ToolResult handleBatchPredict(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);

        String inputTable = input.get("input_table") instanceof String s && !s.isBlank() ? s : null;
        String resultTable = input.get("result_table") instanceof String s && !s.isBlank() ? s : null;
        String inputFilter = input.get("input_filter") instanceof String s && !s.isBlank() ? s : null;

        try {
            Map<String, Object> result = miningService.batchPredictWithOverrides(id, inputTable, resultTable, inputFilter);
            StringBuilder sb = new StringBuilder("批量预测完成!\n");
            String usedInputTable = inputTable != null ? inputTable : String.valueOf(result.getOrDefault("sourceTable", "未知"));
            sb.append("- 输入表: ").append(usedInputTable).append("\n");
            sb.append("- 结果写入: ").append(result.get("saved_to")).append("\n");
            sb.append("- 预测行数: ").append(result.get("saved_rows")).append("\n");
            sb.append("- 结果列: ").append(result.get("columns")).append("\n");
            sb.append("\n用户可以查询结果表 `").append(result.get("saved_to")).append("` 查看预测详情。");
            return ToolResult.ok(getName(), sb.toString(), System.currentTimeMillis() - start, List.of(result));
        } catch (Exception e) {
            return ToolResult.error(getName(), "批量预测失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @SuppressWarnings("unchecked")
    private ToolResult handleValidate(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);

        try {
            Map<String, Object> result = miningService.validateForTraining(id);
            StringBuilder sb = new StringBuilder("模型验证结果:\n\n");

            Boolean valid = (Boolean) result.get("valid");
            sb.append(valid ? "**验证通过**" : "**验证未通过**").append("\n\n");

            List<String> errors = (List<String>) result.get("errors");
            if (errors != null && !errors.isEmpty()) {
                sb.append("问题:\n");
                for (String e : errors) sb.append("- ").append(e).append("\n");
                sb.append("\n");
            }

            List<String> warnings = (List<String>) result.get("warnings");
            if (warnings != null && !warnings.isEmpty()) {
                sb.append("警告:\n");
                for (String w : warnings) sb.append("- ").append(w).append("\n");
                sb.append("\n");
            }

            if (result.get("rowCount") != null) {
                sb.append("数据行数: ").append(result.get("rowCount")).append("\n");
            }
            if (result.get("tableColumns") != null) {
                sb.append("表列: ").append(result.get("tableColumns")).append("\n");
            }

            sb.append("\n建议: ");
            if (valid) {
                sb.append("数据质量良好，可以开始训练。建议先确认特征选择的合理性，训练后检查评估指标，确认准确率和F1是否达标后再发布。");
            } else {
                sb.append("请先解决上述问题再训练。常见解决方案: 补充缺失数据、选择合适的特征列、确保目标列无缺失值。");
            }

            return ToolResult.ok(getName(), sb.toString(), System.currentTimeMillis() - start, List.of(result));
        } catch (Exception e) {
            return ToolResult.error(getName(), "验证失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private ToolResult handleExploreData(Map<String, Object> input, ToolExecutionContext context, long start) {
        String tableName = getString(input, "table_name", null);
        if (tableName == null) return ToolResult.error(getName(), "需要 table_name", System.currentTimeMillis() - start);
        com.smartquery.common.IdentifierValidator.validateTableName(tableName);

        try {
            DataSource ds = miningService.getDataSource(context.dataSourceId());
            if (ds == null) return ToolResult.error(getName(), "数据源不存在", System.currentTimeMillis() - start);

            JdbcTemplate jdbc = miningService.getJdbcTemplate(ds);
            if (jdbc == null) return ToolResult.error(getName(), "无法连接数据源", System.currentTimeMillis() - start);

            StringBuilder sb = new StringBuilder("数据表 `").append(tableName).append("` 探索结果:\n\n");

            // Row count
            Integer rowCount = jdbc.queryForObject("SELECT COUNT(*) FROM `" + tableName + "`", Integer.class);
            sb.append("**总行数**: ").append(rowCount).append("\n\n");

            // Column info
            List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_KEY FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? ORDER BY ORDINAL_POSITION",
                ds.getDatabaseName(), tableName);

            sb.append("**列信息** (").append(columns.size()).append("列):\n");
            for (Map<String, Object> col : columns) {
                sb.append("- `").append(col.get("COLUMN_NAME")).append("` (")
                  .append(col.get("DATA_TYPE")).append(")")
                  .append("YES".equals(col.get("IS_NULLABLE")) ? " 可空" : " 非空")
                  .append("PRI".equals(col.get("COLUMN_KEY")) ? " [主键]" : "")
                  .append("\n");
            }

            // Numeric column stats
            sb.append("\n**数值列统计**:\n");
            for (Map<String, Object> col : columns) {
                String colName = (String) col.get("COLUMN_NAME");
                com.smartquery.common.IdentifierValidator.validateColumnName(colName);
                String dataType = (String) col.get("DATA_TYPE");
                if (dataType.matches("int|bigint|decimal|double|float|tinyint|smallint|mediumint|numeric")) {
                    try {
                        Map<String, Object> stats = jdbc.queryForMap(
                            "SELECT MIN(`" + colName + "`) as min_val, MAX(`" + colName + "`) as max_val, " +
                            "AVG(`" + colName + "`) as avg_val, COUNT(*) - COUNT(`" + colName + "`) as null_count " +
                            "FROM `" + tableName + "`");
                        sb.append("- `").append(colName).append("`: 范围[").append(stats.get("min_val"))
                          .append(", ").append(stats.get("max_val")).append("], 均值=")
                          .append(String.format("%.2f", ((Number) stats.get("avg_val")).doubleValue()))
                          .append(", 缺失=").append(stats.get("null_count")).append("\n");
                    } catch (Exception e) { log.warn("[MINING-TOOL] Column stats query failed for {}.{}: {}", tableName, colName, e.getMessage()); }
                }
            }
            List<String> candidateTargets = new ArrayList<>();
            for (Map<String, Object> col : columns) {
                String colName = (String) col.get("COLUMN_NAME");
                String colLower = colName.toLowerCase();
                boolean isLikelyTarget = colLower.contains("label") || colLower.contains("flag")
                    || colLower.contains("target") || colLower.contains("class")
                    || colLower.contains("is_") || colLower.contains("churn")
                    || colLower.contains("default") || colLower.contains("fraud")
                    || colLower.contains("status") || colLower.contains("type")
                    || colLower.contains("category") || colLower.contains("result")
                    || colLower.contains("outcome") || colLower.contains("risk");
                if (isLikelyTarget) candidateTargets.add(colName);
            }
            // Also check for low-cardinality categorical columns as potential targets
            for (Map<String, Object> col : columns) {
                String colName = (String) col.get("COLUMN_NAME");
                String dataType = (String) col.get("DATA_TYPE");
                if (!candidateTargets.contains(colName) && !dataType.matches("int|bigint|decimal|double|float|tinyint|smallint|mediumint|numeric")) {
                    com.smartquery.common.IdentifierValidator.validateColumnName(colName);
                    try {
                        Integer uniqueCount = jdbc.queryForObject(
                            "SELECT COUNT(DISTINCT `" + colName + "`) FROM `" + tableName + "`", Integer.class);
                        if (uniqueCount != null && uniqueCount >= 2 && uniqueCount <= 20) {
                            candidateTargets.add(colName);
                        }
                    } catch (Exception e) { log.warn("[MINING-TOOL] Distinct count query failed for {}.{}: {}", tableName, colName, e.getMessage()); }
                }
            }
            for (String colName : candidateTargets) {
                com.smartquery.common.IdentifierValidator.validateColumnName(colName);
                try {
                    List<Map<String, Object>> dist = jdbc.queryForList(
                        "SELECT `" + colName + "`, COUNT(*) as cnt FROM `" + tableName + "` GROUP BY `" + colName + "` ORDER BY cnt DESC LIMIT 10");
                    sb.append("\n**").append(colName).append(" 分布** (候选目标列):\n");
                    for (Map<String, Object> d : dist) {
                        sb.append("  ").append(d.get(colName)).append(": ").append(d.get("cnt")).append(" (")
                          .append(String.format("%.1f%%", ((Number) d.get("cnt")).doubleValue() / rowCount * 100))
                          .append(")\n");
                    }
                } catch (Exception e) { log.warn("[MINING-TOOL] Distribution query failed for {}.{}: {}", tableName, colName, e.getMessage()); }
            }

            sb.append("\n**候选目标列**: ").append(candidateTargets.isEmpty() ? "未自动识别，请用户指定" : String.join(", ", candidateTargets));
            sb.append("\n**建议**: 基于以上数据，可以开始特征工程。选择与目标相关、缺失值少的列作为特征，排除ID和时间列。");

            return ToolResult.ok(getName(), sb.toString(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.error(getName(), "探索失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private ToolResult handleListAlgorithms(long start) {
        List<Algorithm> algos = algorithmService.getAll();
        StringBuilder sb = new StringBuilder("可用算法列表 (共").append(algos.size()).append("个):\n\n");
        for (Algorithm a : algos) {
            sb.append("- **").append(a.getName()).append("** (").append(a.getAlgorithmId()).append(")");
            sb.append(a.getIsBuiltin() == 1 ? " [内置]" : " [自定义]");
            try {
                List<?> types = objectMapper.readValue(a.getModelTypes(), List.class);
                sb.append(" | 支持: ").append(types);
            } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
            sb.append("\n  ").append(a.getDescription());
            try {
                List<?> params = objectMapper.readValue(a.getParamsSchema(), List.class);
                if (!params.isEmpty()) {
                    sb.append("\n  参数: ");
                    for (Object p : params) {
                        if (p instanceof Map<?, ?> m) {
                            sb.append(m.get("key")).append("(").append(m.get("label")).append(" 默认:").append(m.get("defaultValue")).append(") ");
                        }
                    }
                }
            } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
            sb.append("\n\n");
        }
        return ToolResult.ok(getName(), sb.toString(), System.currentTimeMillis() - start);
    }

    private ToolResult handleHistory(Map<String, Object> input, long start) {
        Long modelId = toLong(input.get("model_id"));
        if (modelId == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);

        MiningModel model = resourceAccess.requireModel(modelId);
        List<ModelExecution> executions = resourceAccess.listModelExecutions(modelId, historyLimit);

        if (executions.isEmpty()) {
            return ToolResult.ok(getName(), "模型「" + model.getName() + "」暂无训练记录", System.currentTimeMillis() - start);
        }

        StringBuilder sb = new StringBuilder("模型「").append(model.getName()).append("」的训练历史 (共").append(executions.size()).append("次):\n\n");
        for (int i = 0; i < executions.size(); i++) {
            ModelExecution e = executions.get(i);
            sb.append("**第 ").append(executions.size() - i).append(" 次训练** (").append(e.getCreatedAt()).append(")\n");
            sb.append("  状态: ").append(e.getStatus());
            sb.append(" | 触发: ").append(e.getTriggerType());
            sb.append(" | 耗时: ").append(e.getExecutionTimeMs() != null ? e.getExecutionTimeMs() + "ms" : "-").append("\n");
            if (e.getHyperparameters() != null) sb.append("  超参数: ").append(e.getHyperparameters()).append("\n");
            if (e.getMetrics() != null) sb.append("  指标: ").append(e.getMetrics()).append("\n");
            sb.append("\n");
        }

        if (executions.size() >= 2) {
            ModelExecution latest = executions.get(0);
            ModelExecution prev = executions.get(1);
            if (com.smartquery.common.ModelStatus.EXEC_SUCCESS.equals(latest.getStatus()) && com.smartquery.common.ModelStatus.EXEC_SUCCESS.equals(prev.getStatus())
                    && latest.getMetrics() != null && prev.getMetrics() != null) {
                sb.append("**最近两次对比:**\n");
                try {
                    Map<String, Object> lm = objectMapper.readValue(latest.getMetrics(), Map.class);
                    Map<String, Object> pm = objectMapper.readValue(prev.getMetrics(), Map.class);
                    for (String key : lm.keySet()) {
                        if (pm.containsKey(key)) {
                            double lv = ((Number) lm.get(key)).doubleValue();
                            double pv = ((Number) pm.get(key)).doubleValue();
                            double diff = lv - pv;
                            String arrow = diff > 0.001 ? "↑" : diff < -0.001 ? "↓" : "→";
                            sb.append("  ").append(key).append(": ").append(String.format("%.4f", pv))
                              .append(" → ").append(String.format("%.4f", lv)).append(" ").append(arrow).append("\n");
                        }
                    }
                } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
            }
        }

        return ToolResult.ok(getName(), sb.toString(), System.currentTimeMillis() - start);
    }

    /**
     * Lightweight polling endpoint for the agent. Training remains owned by the
     * service; this action only exposes authorized model/execution state and does
     * not create a second execution or bypass the model controller boundary.
     */
    private ToolResult handleMonitor(Map<String, Object> input, long start) {
        Long modelId = toLong(input.get("model_id"));
        if (modelId == null) {
            return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);
        }

        MiningModel model = resourceAccess.requireModel(modelId);
        Long executionId = toLong(input.get("execution_id"));
        ModelExecution latest;
        if (executionId != null) {
            latest = resourceAccess.requireModelExecution(modelId, executionId);
        } else {
            List<ModelExecution> executions = resourceAccess.listModelExecutions(modelId, 1);
            latest = executions.isEmpty() ? null : executions.get(0);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("modelId", model.getId());
        data.put("modelName", model.getName());
        data.put("modelStatus", model.getStatus());
        data.put("version", model.getVersion());
        if (model.getPipelineId() != null) data.put("pipelineId", model.getPipelineId());
        if (model.getLastRunAt() != null) data.put("lastRunAt", model.getLastRunAt().toString());
        if (latest != null) {
            data.put("executionId", latest.getId());
            data.put("executionStatus", latest.getStatus());
            data.put("stage", latest.getCurrentStage());
            data.put("progressPercent", latest.getProgressPercent() == null ? 0 : latest.getProgressPercent());
            if (latest.getProgressMessage() != null) data.put("progressMessage", latest.getProgressMessage());
            if (latest.getTriggerType() != null) data.put("triggerType", latest.getTriggerType());
            if (latest.getExecutionTimeMs() != null) data.put("executionTimeMs", latest.getExecutionTimeMs());
            if (latest.getMetrics() != null) data.put("metrics", latest.getMetrics());
            if (latest.getStartedAt() != null) data.put("startedAt", latest.getStartedAt().toString());
            if (latest.getFinishedAt() != null) data.put("finishedAt", latest.getFinishedAt().toString());
            if (latest.getArtifactPath() != null) data.put("artifactPath", latest.getArtifactPath());
            if (latest.getArtifactSchemaVersion() != null) data.put("artifactSchemaVersion", latest.getArtifactSchemaVersion());
            if (latest.getExecutionLog() != null && !latest.getExecutionLog().isBlank()) {
                String log = latest.getExecutionLog();
                data.put("logTail", log.length() > 2000 ? log.substring(log.length() - 2000) : log);
            }
        }

        StringBuilder message = new StringBuilder("模型「").append(model.getName())
            .append("」当前状态: ").append(model.getStatus());
        if (latest != null) {
            message.append("；最近执行 #").append(latest.getId())
                .append(" 状态: ").append(latest.getStatus())
                .append("，阶段: ").append(latest.getCurrentStage())
                .append("，进度: ").append(latest.getProgressPercent() == null ? 0 : latest.getProgressPercent())
                .append("%");
        }
        if (model.getPipelineId() != null) {
            message.append("；可编辑流程ID: ").append(model.getPipelineId());
        }
        return ToolResult.ok(getName(), message.toString(),
            System.currentTimeMillis() - start, List.of(data));
    }

    private ToolResult handleCancel(Map<String, Object> input, long start) {
        Long modelId = toLong(input.get("model_id"));
        Long executionId = toLong(input.get("execution_id"));
        if (modelId == null || executionId == null) {
            return ToolResult.error(getName(), "cancel 需要 model_id 和 execution_id",
                System.currentTimeMillis() - start);
        }
        ModelExecution execution = miningService.cancelTraining(modelId, executionId);
        return ToolResult.ok(getName(), "训练执行 #" + executionId + " 当前状态: " + execution.getStatus(),
            System.currentTimeMillis() - start, List.of(Map.of(
                "modelId", modelId,
                "executionId", executionId,
                "status", execution.getStatus())));
    }

    private ToolResult handleCreateAlgorithm(Map<String, Object> input, long start) {
        resourceAccess.requireAdmin();
        String algorithmId = getString(input, "algorithm_id", null);
        String name = getString(input, "name", null);
        String description = getString(input, "description", "");
        String pythonCode = getString(input, "python_code_template", null);

        if (algorithmId == null || name == null || pythonCode == null) {
            return ToolResult.error(getName(),
                "create_algorithm 必填: algorithm_id, name, python_code_template",
                System.currentTimeMillis() - start);
        }

        try {
            Algorithm algo = new Algorithm();
            algo.setAlgorithmId(algorithmId);
            algo.setName(name);
            algo.setDescription(description);
            algo.setPythonCodeTemplate(pythonCode);

            Object modelTypes = input.get("model_types");
            if (modelTypes != null) {
                algo.setModelTypes(objectMapper.writeValueAsString(modelTypes));
            } else {
                algo.setModelTypes("[\"classification\"]");
            }

            Object paramsSchema = input.get("params_schema");
            if (paramsSchema != null) {
                algo.setParamsSchema(objectMapper.writeValueAsString(paramsSchema));
            } else {
                algo.setParamsSchema("[]");
            }

            Algorithm created = algorithmService.createCustomAlgorithm(algo);
            return ToolResult.ok(getName(),
                "已创建自定义算法「" + created.getName() + "」(ID: " + created.getAlgorithmId()
                    + ")。该算法现在可以在流程编排和模型管理中使用。",
                System.currentTimeMillis() - start,
                List.of(Map.of("algorithmId", created.getAlgorithmId(), "name", created.getName())));
        } catch (Exception e) {
            return ToolResult.error(getName(), "创建算法失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override
    public boolean isConcurrencySafe() { return false; }

    @Override
    public boolean requireDatabase() { return false; }

    @Override
    public long getTimeoutMs() { return miningToolTimeoutMs; }

    @SuppressWarnings("unchecked")
    private ToolResult handleCompare(Map<String, Object> input, ToolExecutionContext context, long start) {
        Object algosObj = input.get("algorithms");
        if (algosObj == null) {
            return ToolResult.error(getName(), "compare 必须提供 algorithms 数组，如 ['random_forest','xgboost']", System.currentTimeMillis() - start);
        }

        List<String> algorithms;
        if (algosObj instanceof List<?> list) {
            algorithms = list.stream().map(Object::toString).toList();
        } else {
            return ToolResult.error(getName(), "algorithms 必须是数组格式", System.currentTimeMillis() - start);
        }

        if (algorithms.size() < 2 || algorithms.size() > compareMaxAlgorithms) {
            return ToolResult.error(getName(), "compare 支持 2~" + compareMaxAlgorithms + " 个算法对比", System.currentTimeMillis() - start);
        }

        if (context.dataSourceId() == null) {
            return ToolResult.error(getName(), "无法确定数据源，请在对话中指定", System.currentTimeMillis() - start);
        }
        String baseName = getString(input, "name", "对比实验");
        String modelType = getString(input, "model_type", "classification");
        String sourceTable = getString(input, "source_table", null);
        String targetColumn = getString(input, "target_column", null);
        if (sourceTable == null || sourceTable.isBlank()) {
            return ToolResult.error(getName(), "compare 需要指定 source_table", System.currentTimeMillis() - start);
        }
        if (targetColumn == null || targetColumn.isBlank()) {
            return ToolResult.error(getName(), "compare 需要指定 target_column", System.currentTimeMillis() - start);
        }
        Object features = input.get("feature_columns");
        String featureColumnsJson = null;
        if (features != null) {
            try {
                Object normalized = features instanceof String s
                    ? Arrays.stream(s.split(",")).map(String::trim).filter(c -> !c.isEmpty()).toList()
                    : features;
                featureColumnsJson = objectMapper.writeValueAsString(normalized);
            } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
        }

        List<MiningModel> models = new ArrayList<>();
        for (String algo : algorithms) {
            MiningModel model = new MiningModel();
            model.setName(baseName + " - " + algo);
            model.setAlgorithm(algo);
            model.setModelType(modelType);
            model.setSourceTable(sourceTable);
            model.setTargetColumn(targetColumn);
            model.setFeatureColumns(featureColumnsJson);
            model.setDataSourceId(context.dataSourceId());
            model.setConversationId(context.conversationId());
            if (input.get("validation_mode") != null) model.setValidationMode((String) input.get("validation_mode"));
            if (input.get("cv_folds") != null) model.setCvFolds(((Number) input.get("cv_folds")).intValue());
            if (input.get("test_size") != null) model.setTestSize(((Number) input.get("test_size")).doubleValue());
            Object pp = input.get("preprocessing");
            if (pp != null) { try { model.setPreprocessing(objectMapper.writeValueAsString(pp)); } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); } }
            MiningModel created = miningService.createModel(model);
            models.add(created);
        }

        java.util.concurrent.Executor miningExecutor = miningService.getMiningExecutor();

        List<CompletableFuture<Map<String, Object>>> futures = models.stream()
            .map(m -> CompletableFuture.supplyAsync(() -> {
                try {
                    MiningModel trained = miningService.trainModel(m.getId(), "compare");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("algorithm", m.getAlgorithm());
                    row.put("modelId", m.getId());
                    row.put("status", trained.getStatus());
                    if (trained.getMetrics() != null && !trained.getMetrics().isBlank()) {
                        try { row.put("metrics", objectMapper.readValue(trained.getMetrics(), Map.class)); } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
                    }
                    return row;
                } catch (Exception e) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("algorithm", m.getAlgorithm());
                    row.put("modelId", m.getId());
                    row.put("status", com.smartquery.common.ModelStatus.FAILED);                    row.put("error", e.getMessage());
                    return row;
                }
            }, miningExecutor))
            .toList();

        List<Map<String, Object>> results;
        try {
            results = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        } catch (java.util.concurrent.CompletionException e) {
            return ToolResult.error(getName(), "训练队列已满，请稍后重试: " + e.getCause().getMessage(), System.currentTimeMillis() - start);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 模型对比结果\n\n");
        sb.append("| 算法 | 模型ID | 状态 | 指标 |\n|---|---|---|---|\n");
        for (Map<String, Object> r : results) {
            String metricsStr = "";
            Object m = r.get("metrics");
            if (m instanceof Map<?, ?> metrics) {
                metricsStr = metrics.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            }
            if (r.get("error") != null) metricsStr = "错误: " + r.get("error");
            sb.append("| ").append(r.get("algorithm")).append(" | ").append(r.get("modelId"))
              .append(" | ").append(r.get("status")).append(" | ").append(metricsStr).append(" |\n");
        }
        sb.append("\n已创建 ").append(models.size()).append(" 个模型并并行训练完成。");

        // Keep best variant, clean up the rest
        Map<String, Object> best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Map<String, Object> r : results) {
            Object metricsObj = r.get("metrics");
            if (metricsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metrics = (Map<String, Object>) metricsObj;
                double score = metrics.containsKey("test_accuracy") ? ((Number) metrics.get("test_accuracy")).doubleValue() :
                               metrics.containsKey("test_r2") ? ((Number) metrics.get("test_r2")).doubleValue() :
                               metrics.containsKey("accuracy") ? ((Number) metrics.get("accuracy")).doubleValue() :
                               metrics.containsKey("r2") ? ((Number) metrics.get("r2")).doubleValue() : -1;
                if (score > bestScore) { bestScore = score; best = r; }
            }
        }
        for (MiningModel m : models) {
            boolean isBest = best != null && best.get("modelId") != null &&
                ((Number) best.get("modelId")).longValue() == m.getId();
            if (!isBest) {
                try {
                    miningModelMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                            .eq(MiningModel::getId, m.getId())
                            .set(MiningModel::getDeleted, 1));
                    log.info("[COMPARE] Cleaned non-best variant model {}", m.getId());
                } catch (Exception e) {
                    log.warn("[COMPARE] Failed to cleanup variant {}: {}", m.getId(), e.getMessage());
                }
            }
        }
        if (best != null) {
            sb.append("\n最佳模型: ID=").append(best.get("modelId")).append(" (").append(best.get("algorithm"))
              .append(", 得分=").append(String.format("%.4f", bestScore)).append(")");
        }

        return ToolResult.ok(getName(), sb.toString(), System.currentTimeMillis() - start, results);
    }

    private ToolResult handleRetrain(Map<String, Object> input, ToolExecutionContext context, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);

        MiningModel before = resourceAccess.requireModel(id);
        // Auto-offline published model before retraining
        if (com.smartquery.common.ModelStatus.PUBLISHED.equals(before.getStatus())) {
            miningService.offlineModel(id);
            log.info("[MINING-TOOL] Auto-offlined model {} for retrain", id);
        }

        MiningModel updates = new MiningModel();
        boolean hasUpdates = false;
        if (input.get("source_table") != null) { updates.setSourceTable((String) input.get("source_table")); hasUpdates = true; }
        if (input.get("feature_columns") != null) {
            try {
                Object normalized = input.get("feature_columns") instanceof String s
                    ? Arrays.stream(s.split(",")).map(String::trim).filter(c -> !c.isEmpty()).toList()
                    : input.get("feature_columns");
                updates.setFeatureColumns(objectMapper.writeValueAsString(normalized));
                hasUpdates = true;
            } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
        }
        if (input.get("target_column") != null) { updates.setTargetColumn((String) input.get("target_column")); hasUpdates = true; }
        if (input.get("algorithm") != null) { updates.setAlgorithm((String) input.get("algorithm")); hasUpdates = true; }
        if (input.get("hyperparameters") != null) {
            try { updates.setHyperparameters(objectMapper.writeValueAsString(input.get("hyperparameters"))); hasUpdates = true; }
            catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
        }
        if (input.get("validation_mode") != null) { updates.setValidationMode((String) input.get("validation_mode")); hasUpdates = true; }
        if (input.get("cv_folds") != null) { updates.setCvFolds(((Number) input.get("cv_folds")).intValue()); hasUpdates = true; }
        if (input.get("test_size") != null) { updates.setTestSize(((Number) input.get("test_size")).doubleValue()); hasUpdates = true; }
        if (input.get("temporal_column") != null) { updates.setTemporalColumn((String) input.get("temporal_column")); hasUpdates = true; }
        hasUpdates = applyEvaluationConfig(updates, input) || hasUpdates;
        if (input.get("preprocessing") != null) {
            try { updates.setPreprocessing(objectMapper.writeValueAsString(input.get("preprocessing"))); hasUpdates = true; }
            catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
        }
        Object ft = input.get("feature_transforms");
        if (ft != null) {
            try {
                Map<String, Object> pp = updates.getPreprocessing() != null && !updates.getPreprocessing().isBlank()
                    ? objectMapper.readValue(updates.getPreprocessing(), Map.class)
                    : (before.getPreprocessing() != null && !before.getPreprocessing().isBlank()
                        ? objectMapper.readValue(before.getPreprocessing(), Map.class) : new java.util.HashMap<>());
                pp.put("transforms", ft);
                updates.setPreprocessing(objectMapper.writeValueAsString(pp));
                hasUpdates = true;
            } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
        }

        if (hasUpdates) miningService.updateModel(id, updates);

        MiningService.TrainingSubmission submission = miningService.submitTraining(id, "chat");
        return ToolResult.ok(getName(),
            "模型配置和关联流程已同步，重新训练任务已提交。执行ID: "
                + submission.executionId() + "；完成后使用 monitor/history 对比新旧指标。",
            System.currentTimeMillis() - start,
            List.of(Map.of("modelId", submission.modelId(),
                "executionId", submission.executionId(),
                "status", submission.status())));
    }

    @SuppressWarnings("unchecked")
    private ToolResult handleTune(Map<String, Object> input, ToolExecutionContext context, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);

        Object gridObj = input.get("param_grid");
        if (gridObj == null) return ToolResult.error(getName(), "tune 必须提供 param_grid, 如 {\"n_estimators\": [50, 100, 200]}", System.currentTimeMillis() - start);

        MiningModel baseModel = resourceAccess.requireModel(id);

        Map<String, List<Object>> paramGrid;
        try {
            if (!(gridObj instanceof Map<?, ?> gridMap)) {
                return ToolResult.error(getName(), "param_grid 必须是 JSON 对象", System.currentTimeMillis() - start);
            }
            paramGrid = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : gridMap.entrySet()) {
                if (!(entry.getValue() instanceof List<?> listVal)) {
                    return ToolResult.error(getName(), "param_grid 键 '" + entry.getKey() + "' 的值必须是数组, 实际: " + (entry.getValue() != null ? entry.getValue().getClass().getSimpleName() : "null"), System.currentTimeMillis() - start);
                }
                paramGrid.put(String.valueOf(entry.getKey()), new ArrayList<>(listVal));
            }
        } catch (ClassCastException e) {
            return ToolResult.error(getName(), "param_grid 格式错误, 键为参数名, 值为候选值数组", System.currentTimeMillis() - start);
        }

        List<Map<String, Object>> combinations = generateCombinations(paramGrid);
        if (combinations.isEmpty()) return ToolResult.error(getName(), "param_grid 为空或无法生成组合", System.currentTimeMillis() - start);
        if (combinations.size() > tuneMaxCombinations) combinations = new ArrayList<>(combinations.subList(0, tuneMaxCombinations));

        java.util.concurrent.Executor miningExecutor = miningService.getMiningExecutor();

        List<CompletableFuture<Map<String, Object>>> futures = combinations.stream()
            .map(params -> CompletableFuture.supplyAsync(() -> {
                MiningModel created = null;
                try {
                    MiningModel variant = new MiningModel();
                    variant.setName(baseModel.getName() + " [tune]");
                    variant.setAlgorithm(baseModel.getAlgorithm());
                    variant.setModelType(baseModel.getModelType());
                    variant.setSourceTable(baseModel.getSourceTable());
                    variant.setTargetColumn(baseModel.getTargetColumn());
                    variant.setFeatureColumns(baseModel.getFeatureColumns());
                    variant.setDataSourceId(context.dataSourceId() != null ? context.dataSourceId() : baseModel.getDataSourceId());
                    variant.setConversationId(context.conversationId());
                    variant.setPreprocessing(baseModel.getPreprocessing());
                    if (baseModel.getValidationMode() != null) variant.setValidationMode(baseModel.getValidationMode());
                    if (baseModel.getCvFolds() != null) variant.setCvFolds(baseModel.getCvFolds());
                    if (baseModel.getTestSize() != null) variant.setTestSize(baseModel.getTestSize());

                    Map<String, Object> mergedParams = new LinkedHashMap<>(parseJsonMap(baseModel.getHyperparameters()));
                    mergedParams.putAll(params);
                    variant.setHyperparameters(objectMapper.writeValueAsString(mergedParams));

                    created = miningService.createModel(variant);
                    MiningModel trained = miningService.trainModel(created.getId(), "tune");

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("params", params);
                    row.put("modelId", created.getId());
                    row.put("status", trained.getStatus());
                    if (trained.getMetrics() != null && !trained.getMetrics().isBlank()) {
                        try { row.put("metrics", objectMapper.readValue(trained.getMetrics(), Map.class)); } catch (Exception e) { log.warn("[MINING-TOOL] JSON serialization failed: {}", e.getMessage()); }
                    }
                    return row;
                } catch (Exception e) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("params", params);
                    if (created != null) row.put("modelId", created.getId());
                    row.put("status", com.smartquery.common.ModelStatus.FAILED);
                    row.put("error", e.getMessage());
                    return row;
                }
            }, miningExecutor))
            .toList();

        List<Map<String, Object>> results;
        try {
            results = futures.stream().map(CompletableFuture::join).toList();
        } catch (java.util.concurrent.CompletionException e) {
            return ToolResult.error(getName(), "训练队列已满，请稍后重试: " + e.getCause().getMessage(), System.currentTimeMillis() - start);
        }

        StringBuilder sb = new StringBuilder("## 参数调优结果\n\n");
        sb.append("基于模型「").append(baseModel.getName()).append("」(ID: ").append(id).append(") 探索了 ").append(combinations.size()).append(" 种参数组合\n\n");
        sb.append("| 参数组合 | 模型ID | 状态 | 指标 |\n|---|---|---|---|\n");
        for (Map<String, Object> r : results) {
            String paramsStr = r.get("params") instanceof Map ? ((Map<String, Object>) r.get("params")).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("") : String.valueOf(r.get("params"));
            String metricsStr = "";
            Object m = r.get("metrics");
            if (m instanceof Map<?, ?> metrics) {
                metricsStr = metrics.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            }
            if (r.get("error") != null) metricsStr = "错误: " + r.get("error");
            sb.append("| ").append(paramsStr).append(" | ").append(r.get("modelId"))
              .append(" | ").append(r.get("status")).append(" | ").append(metricsStr).append(" |\n");
        }
        sb.append("\n已创建 ").append(results.size()).append(" 个调优变体。可发布表现最佳的模型。");

        // Keep best tune variant, clean up the rest
        Map<String, Object> best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Map<String, Object> r : results) {
            Object metricsObj = r.get("metrics");
            if (metricsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metrics = (Map<String, Object>) metricsObj;
                double score = metrics.containsKey("test_accuracy") ? ((Number) metrics.get("test_accuracy")).doubleValue() :
                               metrics.containsKey("test_r2") ? ((Number) metrics.get("test_r2")).doubleValue() :
                               metrics.containsKey("accuracy") ? ((Number) metrics.get("accuracy")).doubleValue() :
                               metrics.containsKey("r2") ? ((Number) metrics.get("r2")).doubleValue() : -1;
                if (score > bestScore) { bestScore = score; best = r; }
            }
        }
        for (Map<String, Object> r : results) {
            Object modelId = r.get("modelId");
            boolean isBest = best != null && best.get("modelId") != null &&
                best.get("modelId").equals(modelId);
            if (!isBest && modelId != null) {
                try {
                    Long mid = ((Number) modelId).longValue();
                    miningModelMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MiningModel>()
                            .eq(MiningModel::getId, mid)
                            .set(MiningModel::getDeleted, 1));
                    log.info("[TUNE] Cleaned non-best variant model {}", mid);
                } catch (Exception e) {
                    log.warn("[TUNE] Failed to cleanup variant {}: {}", modelId, e.getMessage());
                }
            }
        }
        if (best != null) {
            sb.append("\n最佳调优模型: ID=").append(best.get("modelId")).append(" (得分=").append(String.format("%.4f", bestScore)).append(")");
        }

        return ToolResult.ok(getName(), sb.toString(), System.currentTimeMillis() - start, results);
    }

    private ToolResult handleSyncPipeline(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);

        MiningModel model = resourceAccess.requireModel(id);
        if (model.getPipelineId() == null) return ToolResult.error(getName(), "模型没有关联的流程", System.currentTimeMillis() - start);
        resourceAccess.requirePipeline(model.getPipelineId());

        miningService.syncModelToPipeline(model.getPipelineId(), model);
        miningService.syncPipelineToModel(model.getPipelineId());

        MiningModel refreshed = resourceAccess.requireModel(id);
        return ToolResult.ok(getName(), "模型与流程已同步",
            System.currentTimeMillis() - start,
            List.of(Map.of("modelId", refreshed.getId(), "pipelineId", refreshed.getPipelineId(),
                "lastSyncedAt", refreshed.getLastSyncedAt() != null ? refreshed.getLastSyncedAt().toString() : "")));
    }

    private ToolResult handleGovernance(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);
        MiningService.GovernanceReport report = miningService.governanceReport(id);
        Map<String, Object> data = objectMapper.convertValue(report, Map.class);
        String message = report.passed()
            ? "模型已通过发布治理检查"
            : "模型未通过发布治理检查: " + String.join("；", report.hardFailures());
        return ToolResult.ok(getName(), message, System.currentTimeMillis() - start, List.of(data));
    }

    private ToolResult handleDriftCheck(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);
        Map<String, Object> result = miningService.checkDrift(id,
            getString(input, "input_table", null), getString(input, "input_filter", null));
        return ToolResult.ok(getName(), "漂移检查完成，状态: " + result.get("drift_status"),
            System.currentTimeMillis() - start, List.of(result));
    }

    private boolean applyEvaluationConfig(MiningModel model, Map<String, Object> input) {
        boolean changed = false;
        try {
            if (input.get("group_columns") != null) {
                model.setGroupColumns(objectMapper.writeValueAsString(input.get("group_columns")));
                changed = true;
            }
            if (input.get("threshold_policy") != null) {
                model.setThresholdPolicy(objectMapper.writeValueAsString(input.get("threshold_policy")));
                changed = true;
            }
            if (input.get("governance_policy") != null) {
                model.setGovernancePolicy(objectMapper.writeValueAsString(input.get("governance_policy")));
                changed = true;
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("评估治理配置不是合法 JSON: " + e.getMessage(), e);
        }
        if (input.get("oos_table") != null) { model.setOosTable((String) input.get("oos_table")); changed = true; }
        if (input.get("oos_filter") != null) { model.setOosFilter((String) input.get("oos_filter")); changed = true; }
        if (input.get("positive_class") != null) { model.setPositiveClass(String.valueOf(input.get("positive_class"))); changed = true; }
        if (input.get("calibration_method") != null) { model.setCalibrationMethod((String) input.get("calibration_method")); changed = true; }
        return changed;
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> generateCombinations(Map<String, List<Object>> paramGrid) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> keys = new ArrayList<>(paramGrid.keySet());
        if (keys.isEmpty()) return result;

        int[] indices = new int[keys.size()];
        int[] sizes = keys.stream().mapToInt(k -> paramGrid.get(k).size()).toArray();
        for (int i = 0; i < sizes.length; i++) if (sizes[i] == 0) return result;

        while (true) {
            if (result.size() >= tuneMaxCombinations) break;
            Map<String, Object> combo = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                combo.put(keys.get(i), paramGrid.get(keys.get(i)).get(indices[i]));
            }
            result.add(combo);

            int idx = keys.size() - 1;
            while (idx >= 0) {
                indices[idx]++;
                if (indices[idx] < sizes[idx]) break;
                indices[idx] = 0;
                idx--;
            }
            if (idx < 0) break;
        }
        return result;
    }
}
