package com.smartquery.tool;

import com.smartquery.prompt.ToolPromptLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, LlmTool> toolMap = new ConcurrentHashMap<>();
    private final ToolPromptLoader promptLoader;
    private final Map<String, String> promptCache = new ConcurrentHashMap<>();

    public ToolRegistry(List<LlmTool> tools, ToolPromptLoader promptLoader) {
        this.promptLoader = promptLoader;
        for (LlmTool tool : tools) {
            register(tool);
        }
    }

    public void register(LlmTool tool) {
        toolMap.put(tool.getName(), tool);
    }

    public Optional<LlmTool> getTool(String name) {
        return Optional.ofNullable(toolMap.get(name));
    }

    public List<LlmTool> getAllTools() {
        return List.copyOf(toolMap.values());
    }

    /**
     * 生成 OpenAI function calling 格式的工具定义
     * 包含详细 prompt 作为 description，让 LLM 了解工具的完整使用方式
     */
    public List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (LlmTool tool : toolMap.values()) {
            if (!tool.isEnabled()) continue;
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("type", "function");
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.getName());
            function.put("description", getDetailedDescription(tool));
            function.put("parameters", tool.getJsonSchema());
            def.put("function", function);
            definitions.add(def);
        }
        return definitions;
    }

    private static final Map<String, String> TOOL_TO_PROMPT = Map.of(
        "execute_sql", "execute-sql",
        "execute_python", "python-execute",
        "generate_chart", "chart-generate",
        "generate_report", "report-generate",
        "generate_dashboard", "dashboard-generate",
        "generate_filter_widgets", "filter-widget",
        "schema_explore", "schema-explore"
    );

    private String getDetailedDescription(LlmTool tool) {
        String promptName = TOOL_TO_PROMPT.getOrDefault(tool.getName(), tool.getName());
        String detailed = promptCache.computeIfAbsent(tool.getName(), k -> {
            try {
                return promptLoader.loadToolPrompt(promptName);
            } catch (Exception e) {
                return "";
            }
        });
        if (detailed != null && !detailed.isBlank()) {
            return tool.getDescription() + "\n\n" + detailed;
        }
        return tool.getDescription();
    }
}
