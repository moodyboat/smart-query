package com.smartquery.prompt;

import com.smartquery.store.AppState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 查询上下文拼装器 — 直译 Claude Code utils/queryContext.ts
 *
 * <p>翻译对照:
 * <pre>
 * TS: fetchSystemPromptParts({tools, mainLoopModel, ...}) → {defaultSystemPrompt, userContext, systemContext}
 * Java: fetchPromptParts(model, dataSourceId) → PromptParts
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryContextAssembler {

    private final SystemPromptBuilder systemPromptBuilder;
    private final ScenarioPromptBuilder scenarioPromptBuilder;
    private final ToolPromptLoader promptLoader;
    private final SchemaContextBuilder schemaContextBuilder;
    private final OntologyContextBuilder ontologyContextBuilder;
    private final com.smartquery.tool.ToolRegistry toolRegistry;
    private final com.smartquery.service.ScenarioService scenarioService;

    @org.springframework.beans.factory.annotation.Value("${smart-query.prompt.system-token-budget:16000}")
    private int systemTokenBudget;
    private static final int CHARS_PER_TOKEN = com.smartquery.common.TokenConstants.CHARS_PER_TOKEN;

    public record PromptParts(
        String systemPrompt,
        Map<String, String> userContext,
        Map<String, String> systemContext
    ) {}

    /**
     * 直译 fetchSystemPromptParts()
     */
    public PromptParts fetchPromptParts(String model, Long dataSourceId) {
        return fetchPromptParts(model, dataSourceId, null, null);
    }

    /**
     * 支持场景化的提示词构建
     */
    public PromptParts fetchPromptParts(String model, Long dataSourceId, String scenarioCode, Map<String, Object> scenarioVariables) {
        String systemPrompt;

        // 反查场景配置（数据源/schema 名/表白名单），用于白名单过滤和变量注入
        com.smartquery.entity.Scenario scenarioObj = null;
        java.util.Set<String> allowedTables = null;
        if (scenarioCode != null && !scenarioCode.isBlank()) {
            try {
                scenarioObj = scenarioService.getByCode(scenarioCode);
            } catch (Exception e) {
                log.debug("[CTX-ASM] failed to load scenario {}: {}", scenarioCode, e.getMessage());
            }
            if (scenarioObj != null && scenarioObj.getAllowedTables() != null && !scenarioObj.getAllowedTables().isBlank()) {
                allowedTables = java.util.Arrays.stream(scenarioObj.getAllowedTables().split(","))
                    .map(SchemaContextBuilder::normalizeTableName)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            }
        }

        // 如果指定了场景编码，使用场景化提示词
        if (scenarioCode != null && !scenarioCode.isBlank()) {
            boolean hasMining = toolRegistry.getTool("mining_model").isPresent();

            // 构建场景提示词的变量
            Map<String, Object> variables = new HashMap<>();
            if (scenarioVariables != null) {
                variables.putAll(scenarioVariables);
            }

            // 添加数据库schema变量（按场景白名单过滤）
            if (dataSourceId != null) {
                String schemaContext = schemaContextBuilder.buildSchemaContext(dataSourceId, scenarioCode, allowedTables, systemTokenBudget);
                if (schemaContext != null) {
                    variables.put("database_schema", schemaContext);
                }

                // 添加本体上下文
                String ontologyContext = ontologyContextBuilder.buildOntologyContext(dataSourceId);
                if (ontologyContext != null) {
                    variables.put("ontology_context", ontologyContext);
                }
            }

            // 场景 schema 名作为变量供提示词模板使用
            if (scenarioObj != null && scenarioObj.getSchemaName() != null && !scenarioObj.getSchemaName().isBlank()) {
                variables.put("schema_name", scenarioObj.getSchemaName());
            }

            // 添加当前日期
            variables.put("current_date", java.time.LocalDate.now().toString());

            // 使用场景化提示词构建器
            String scenarioPrompt = scenarioPromptBuilder.buildByScenario(scenarioCode, variables);

            // 如果场景提示词为空，回退到默认构建器
            if (!scenarioPrompt.isBlank()) {
                systemPrompt = scenarioPrompt;
                log.info("[CTX-ASM] using scenario prompt: {} (allowedTables={})", scenarioCode, allowedTables);
            } else {
                log.warn("[CTX-ASM] scenario prompt empty, falling back to default");
                systemPrompt = systemPromptBuilder.build(model, dataSourceId, hasMining);
            }
        } else {
            // 使用默认的系统提示词构建器
            boolean hasMining = toolRegistry.getTool("mining_model").isPresent();
            systemPrompt = systemPromptBuilder.build(model, dataSourceId, hasMining);
        }

        Map<String, String> context = new LinkedHashMap<>();
        context.put("currentDate", java.time.LocalDate.now().toString());
        if (dataSourceId != null) {
            context.put("dataSourceId", dataSourceId.toString());
        }

        // 渲染动态占位符
        systemPrompt = promptLoader.renderPrompt(systemPrompt, context);

        // 如果不是场景化提示词，注入数据字典上下文 — 带 token 预算控制（场景化分支已通过变量注入，不重复）
        if (scenarioCode == null || scenarioCode.isBlank()) {
            int systemTokens = systemPrompt.length() / CHARS_PER_TOKEN;
            int remainingTokens = Math.max(0, systemTokenBudget - systemTokens);
            String schemaContext = schemaContextBuilder.buildSchemaContext(dataSourceId, remainingTokens);
            if (schemaContext != null) {
                systemPrompt = systemPrompt + "\n\n" + schemaContext;
            }

            // 注入本体模型上下文 (指标/维度/术语)
            String ontologyContext = ontologyContextBuilder.buildOntologyContext(dataSourceId);
            if (ontologyContext != null) {
                systemPrompt = systemPrompt + "\n\n" + ontologyContext;
            }
        }

        int finalTokens = systemPrompt.length() / CHARS_PER_TOKEN;
        log.debug("[CTX-ASM] system prompt: {} chars, ~{} tokens (budget {})",
            systemPrompt.length(), finalTokens, systemTokenBudget);

        return new PromptParts(systemPrompt, context, context);
    }

    /**
     * 组装最终发送给 LLM 的消息列表
     */
    public List<Map<String, String>> assembleMessages(
        String systemPrompt,
        List<Map<String, Object>> historyMessages,
        String userMessage
    ) {
        List<Map<String, String>> messages = new ArrayList<>();

        messages.add(Map.of("role", "system", "content", systemPrompt));

        for (Map<String, Object> msg : historyMessages) {
            String role = (String) msg.get("role");
            String content = (String) msg.get("content");
            if (role != null && content != null) {
                messages.add(Map.of("role", role, "content", content));
            }
        }

        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }
}
