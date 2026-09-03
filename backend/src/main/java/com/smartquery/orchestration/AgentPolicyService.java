package com.smartquery.orchestration;

import com.smartquery.common.BusinessException;
import com.smartquery.llm.LlmService;
import com.smartquery.tool.LlmTool;
import com.smartquery.tool.SqlSafetyValidator;
import com.smartquery.tool.ToolOrchestrator;
import com.smartquery.tool.ToolRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates immutable agent policy and exposes only its pinned read-only tools. */
@Service
public class AgentPolicyService {
    private static final Set<String> RESERVED = Set.of("__sourceRefs", "__sourceSnapshots", "__evidence");

    private final ToolRegistry toolRegistry;
    private final LlmService llmService;
    private final DataSourceQueryPolicyService dataSourcePolicyService;

    public AgentPolicyService(ToolRegistry toolRegistry, LlmService llmService,
                              DataSourceQueryPolicyService dataSourcePolicyService) {
        this.toolRegistry = toolRegistry;
        this.llmService = llmService;
        this.dataSourcePolicyService = dataSourcePolicyService;
    }

    public AgentPolicySpec validate(Map<String, Object> payload) {
        String model = requiredText(payload.get("model"), "AGENT_POLICY.model不能为空");
        if (!llmService.isAvailable(model)) throw new BusinessException(422, "AGENT_POLICY模型不可用: " + model);
        String instruction = requiredText(payload.get("instruction"), "AGENT_POLICY.instruction不能为空");
        if (instruction.length() > 8_000) throw new BusinessException(422, "AGENT_POLICY.instruction不能超过8000字符");
        List<String> allowedTools = stringList(payload.get("allowedTools"), "allowedTools", 8);
        LinkedHashSet<String> uniqueTools = new LinkedHashSet<>(allowedTools);
        if (uniqueTools.size() != allowedTools.size()) throw new BusinessException(422, "allowedTools不能重复");
        boolean requiresDatabase = false;
        for (String name : allowedTools) {
            LlmTool tool = toolRegistry.getTool(name)
                .orElseThrow(() -> new BusinessException(422, "AGENT_POLICY工具未注册: " + name));
            if (!tool.isEnabled()) throw new BusinessException(422, "AGENT_POLICY工具未启用: " + name);
            if (!tool.isReadOnly() || tool.isDestructive()) {
                throw new BusinessException(422, "生产DAG智能体只允许只读工具: " + name);
            }
            requiresDatabase |= tool.requireDatabase();
        }

        Long dataSourceId = optionalLong(payload.get("dataSourceId"));
        Set<String> allowedTables = normalizedTables(payload.get("allowedTables"));
        if (requiresDatabase) {
            if (dataSourceId == null) throw new BusinessException(422, "数据库工具必须锁定dataSourceId");
            if (allowedTables.isEmpty()) throw new BusinessException(422, "数据库工具必须声明非空allowedTables");
            dataSourcePolicyService.requireQueryable(dataSourceId);
        } else if (dataSourceId != null) {
            dataSourcePolicyService.requireQueryable(dataSourceId);
        }

        String responseField = outputField(payload.getOrDefault("responseField", "agentDecision"), "responseField");
        String traceField = outputField(payload.getOrDefault("traceField", "agentToolTrace"), "traceField");
        if (responseField.equals(traceField)) throw new BusinessException(422, "responseField和traceField不能相同");
        int maxTurns = boundedInt(payload.get("maxTurns"), 3, 1, 6, "maxTurns");
        int maxToolCalls = boundedInt(payload.get("maxToolCalls"), 4, 0, 20, "maxToolCalls");
        int maxInputRecords = boundedInt(payload.get("maxInputRecords"), 20, 1, 100, "maxInputRecords");
        int maxTotalTokens = boundedInt(payload.get("maxTotalTokens"), 8_000, 128, 32_000, "maxTotalTokens");
        boolean failOnToolError = !Boolean.FALSE.equals(payload.get("failOnToolError"));
        return new AgentPolicySpec(model, instruction, List.copyOf(allowedTools), dataSourceId,
            Set.copyOf(allowedTables), maxTurns, maxToolCalls, maxInputRecords, maxTotalTokens,
            responseField, traceField, failOnToolError);
    }

    public List<Map<String, Object>> toolDefinitions(AgentPolicySpec spec) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : spec.allowedTools()) {
            LlmTool tool = toolRegistry.getTool(name).orElseThrow();
            result.add(Map.of("type", "function", "function", Map.of(
                "name", tool.getName(), "description", tool.getDescription(),
                "parameters", tool.getJsonSchema())));
        }
        return List.copyOf(result);
    }

    /** Public authoring catalog contains only tools eligible for immutable production policies. */
    public List<AgentToolView> eligibleTools() {
        return toolRegistry.getAllTools().stream()
            .filter(LlmTool::isEnabled)
            .filter(LlmTool::isReadOnly)
            .filter(tool -> !tool.isDestructive())
            .sorted(java.util.Comparator.comparing(LlmTool::getName))
            .map(tool -> new AgentToolView(tool.getName(), tool.getDescription(),
                tool.requireDatabase(), tool.getTimeoutMs(), tool.getJsonSchema()))
            .toList();
    }

    public List<ToolOrchestrator.ToolCall> authorizeCalls(AgentPolicySpec spec,
                                                           List<ToolOrchestrator.ToolCall> calls) {
        List<ToolOrchestrator.ToolCall> result = new ArrayList<>();
        for (ToolOrchestrator.ToolCall call : calls) {
            if (!spec.allowedTools().contains(call.toolName())) {
                throw new BusinessException(422, "智能体尝试调用版本白名单外工具: " + call.toolName());
            }
            LlmTool tool = toolRegistry.getTool(call.toolName())
                .orElseThrow(() -> new BusinessException(422, "智能体工具运行时不存在: " + call.toolName()));
            if (!tool.isReadOnly() || tool.isDestructive()) {
                throw new BusinessException(422, "智能体工具运行时不再满足只读策略: " + call.toolName());
            }
            Map<String, Object> input = new LinkedHashMap<>(call.input() == null ? Map.of() : call.input());
            input.remove("filters");
            input.remove("data_source_id");
            if (tool.requireDatabase()) input.put("data_source_id", spec.dataSourceId());
            result.add(new ToolOrchestrator.ToolCall(call.toolCallId(), call.toolName(),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(input))));
        }
        return List.copyOf(result);
    }

    public void validateNodeConfig(Map<String, Object> config) {
        if (config == null) return;
        for (String field : List.of("model", "instruction", "allowedTools", "dataSourceId", "allowedTables",
                "maxTurns", "maxToolCalls", "maxInputRecords", "maxTotalTokens", "responseField", "traceField")) {
            if (config.containsKey(field)) throw new BusinessException(422, "AGENT_POLICY节点不能覆盖版本字段: " + field);
        }
    }

    private Set<String> normalizedTables(Object raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String table : stringList(raw, "allowedTables", 100)) {
            String normalized = SqlSafetyValidator.normalizeTableName(table);
            if (normalized == null || normalized.isBlank()) throw new BusinessException(422, "allowedTables包含空表名");
            result.add(normalized);
        }
        return result;
    }

    private String outputField(Object raw, String label) {
        String value = requiredText(raw, label + "不能为空");
        if (!value.matches("^[\\p{L}_$][\\p{L}\\p{N}_$-]*$")) {
            throw new BusinessException(422, label + "不是合法字段名");
        }
        if (value.startsWith("__") || RESERVED.contains(value)) {
            throw new BusinessException(422, label + "不能覆盖平台血缘字段");
        }
        return value;
    }

    private List<String> stringList(Object raw, String field, int max) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new BusinessException(422, field + "必须是数组");
        if (list.size() > max) throw new BusinessException(422, field + "不能超过" + max + "项");
        List<String> result = new ArrayList<>();
        for (Object value : list) result.add(requiredText(value, field + "不能包含空值"));
        return result;
    }

    private Long optionalLong(Object value) {
        if (value == null) return null;
        Long result = DagValidationService.toLong(value);
        if (result == null || result <= 0) throw new BusinessException(422, "dataSourceId必须是正整数");
        return result;
    }

    private int boundedInt(Object value, int fallback, int min, int max, String field) {
        if (value == null) return fallback;
        int result;
        try { result = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value)); }
        catch (Exception e) { throw new BusinessException(422, field + "必须是整数"); }
        if (result < min || result > max) throw new BusinessException(422, field + "必须在" + min + "到" + max + "之间");
        return result;
    }

    private String requiredText(Object value, String message) {
        String result = value == null ? "" : String.valueOf(value).trim();
        if (result.isEmpty()) throw new BusinessException(422, message);
        return result;
    }

    public record AgentPolicySpec(String model, String instruction, List<String> allowedTools,
                                  Long dataSourceId, Set<String> allowedTables,
                                  int maxTurns, int maxToolCalls, int maxInputRecords,
                                  int maxTotalTokens, String responseField, String traceField,
                                  boolean failOnToolError) {}

    public record AgentToolView(String name, String description, boolean requireDatabase,
                                long timeoutMs, Map<String, Object> inputSchema) {}
}
