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
    private final ToolPromptLoader promptLoader;
    private final SchemaContextBuilder schemaContextBuilder;

    public record PromptParts(
        String systemPrompt,
        Map<String, String> userContext,
        Map<String, String> systemContext
    ) {}

    /**
     * 直译 fetchSystemPromptParts()
     */
    public PromptParts fetchPromptParts(String model, Long dataSourceId) {
        String systemPrompt = systemPromptBuilder.build(model);

        Map<String, String> context = new LinkedHashMap<>();
        context.put("currentDate", java.time.LocalDate.now().toString());
        if (dataSourceId != null) {
            context.put("dataSourceId", dataSourceId.toString());
        }

        // 渲染动态占位符
        systemPrompt = promptLoader.renderPrompt(systemPrompt, context);

        // 注入数据字典上下文 — 让 LLM 直接知道表结构，无需 schema_explore
        String schemaContext = schemaContextBuilder.buildSchemaContext(dataSourceId);
        if (schemaContext != null) {
            systemPrompt = systemPrompt + "\n\n" + schemaContext;
        }

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
