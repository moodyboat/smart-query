package com.smartquery.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.MiningModel;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.service.MiningService;
import com.smartquery.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class MiningModelTool implements LlmTool {

    private final MiningModelMapper miningModelMapper;
    private final MiningService miningService;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() { return "mining_model"; }

    @Override
    public String getDescription() {
        return "管理数据挖掘模型: 查看已有模型列表、修改模型参数(如树的数量、深度)、创建新模型、触发训练、发布/下线模型。用户可能会用自然语言说「把随机森林的树数量改成200」或「训练客户流失预测模型」等。";
    }

    @Override
    public Map<String, Object> getJsonSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", Map.of("type", "string", "description",
            "操作类型: list=查看模型列表, get=查看模型详情, create=创建新模型, update=更新模型配置, update_params=修改超参数, train=训练, publish=发布, offline=下线"));
        props.put("model_id", Map.of("type", "integer", "description", "模型ID (list操作不需要)"));
        props.put("name", Map.of("type", "string", "description", "模型名称"));
        props.put("algorithm", Map.of("type", "string", "description", "算法: random_forest, xgboost, decision_tree, logistic_regression, svm, knn, kmeans"));
        props.put("model_type", Map.of("type", "string", "description", "类型: classification, regression, clustering"));
        props.put("source_table", Map.of("type", "string", "description", "源数据表名"));
        props.put("feature_columns", Map.of("type", "string", "description", "特征列名,逗号分隔"));
        props.put("target_column", Map.of("type", "string", "description", "目标列名"));
        props.put("hyperparameters", Map.of("type", "object", "description", "超参数"));
        props.put("description", Map.of("type", "string", "description", "模型描述"));
        return Map.of("type", "object", "properties", props, "required", List.of("action"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolExecutionContext context) {
        long start = System.currentTimeMillis();
        String action = (String) input.get("action");

        try {
            return switch (action) {
                case "list" -> handleList(input, start);
                case "get" -> handleGet(input, start);
                case "create" -> handleCreate(input, context, start);
                case "update" -> handleUpdate(input, start);
                case "update_params" -> handleUpdateParams(input, start);
                case "train" -> handleTrain(input, start);
                case "publish" -> handlePublish(input, start);
                case "offline" -> handleOffline(input, start);
                default -> ToolResult.error(getName(), "未知操作: " + action, System.currentTimeMillis() - start);
            };
        } catch (Exception e) {
            log.error("[MINING-TOOL] Action {} failed: {}", action, e.getMessage());
            return ToolResult.error(getName(), action + " 失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private ToolResult handleList(Map<String, Object> input, long start) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MiningModel>()
                .eq(MiningModel::getDeleted, 0)
                .orderByDesc(MiningModel::getCreatedAt);
        List<MiningModel> models = miningModelMapper.selectList(wrapper);

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
        return new ToolResult(getName(), true, sb.toString(), null, System.currentTimeMillis() - start, List.of());
    }

    private ToolResult handleGet(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);
        MiningModel model = miningModelMapper.selectById(id);
        if (model == null) return ToolResult.error(getName(), "模型不存在: " + id, System.currentTimeMillis() - start);

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
        if (model.getMetrics() != null) sb.append("评估指标: ").append(model.getMetrics()).append("\n");
        if (model.getFeatureImportance() != null) sb.append("特征重要性: ").append(model.getFeatureImportance()).append("\n");
        return new ToolResult(getName(), true, sb.toString(), null, System.currentTimeMillis() - start, List.of());
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
            } catch (Exception ignored) {}
        }
        Object params = input.get("hyperparameters");
        if (params != null) {
            try { model.setHyperparameters(objectMapper.writeValueAsString(params)); } catch (Exception ignored) {}
        }
        if (input.get("description") != null) model.setDescription((String) input.get("description"));

        MiningModel created = miningService.createModel(model);
        return new ToolResult(getName(), true, "已创建模型「" + created.getName() + "」(ID: " + created.getId() + ")。可以在管理页面查看或训练。",
            null, System.currentTimeMillis() - start, List.of(Map.of("modelId", created.getId(), "name", created.getName())));
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

        MiningModel updated = miningService.updateModel(id, updates);
        return new ToolResult(getName(), true, "已更新模型「" + updated.getName() + "」",
            null, System.currentTimeMillis() - start, List.of());
    }

    private ToolResult handleUpdateParams(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);

        Object params = input.get("hyperparameters");
        if (params == null) return ToolResult.error(getName(), "需要 hyperparameters", System.currentTimeMillis() - start);

        try {
            String json = objectMapper.writeValueAsString(params);
            MiningModel updated = miningService.updateHyperparameters(id, json);
            return new ToolResult(getName(), true,
                "已更新模型「" + updated.getName() + "」的超参数为: " + json,
                null, System.currentTimeMillis() - start, List.of());
        } catch (Exception e) {
            return ToolResult.error(getName(), "参数序列化失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private ToolResult handleTrain(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);

        MiningModel result = miningService.trainModel(id, "chat");
        String msg = result.getStatus().equals("trained")
            ? "模型「" + result.getName() + "」训练完成！指标: " + result.getMetrics()
            : result.getStatus().equals("training")
            ? "模型「" + result.getName() + "」正在训练中..."
            : "模型「" + result.getName() + "」训练失败。";
        return new ToolResult(getName(), true, msg, null, System.currentTimeMillis() - start, List.of());
    }

    private ToolResult handlePublish(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);
        MiningModel model = miningService.publishModel(id);
        return new ToolResult(getName(), true, "模型「" + model.getName() + "」已发布",
            null, System.currentTimeMillis() - start, List.of());
    }

    private ToolResult handleOffline(Map<String, Object> input, long start) {
        Long id = toLong(input.get("model_id"));
        if (id == null) return ToolResult.error(getName(), "需要 model_id", System.currentTimeMillis() - start);
        MiningModel model = miningService.offlineModel(id);
        return new ToolResult(getName(), true, "模型「" + model.getName() + "」已下线",
            null, System.currentTimeMillis() - start, List.of());
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

    @Override
    public boolean isConcurrencySafe() { return false; }

    @Override
    public long getTimeoutMs() { return 300000; }
}
