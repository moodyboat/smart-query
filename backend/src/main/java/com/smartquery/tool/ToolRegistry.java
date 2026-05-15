package com.smartquery.tool;

import com.smartquery.prompt.ToolPromptLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, LlmTool> toolMap = new ConcurrentHashMap<>();
    private final ToolPromptLoader promptLoader;
    private final Map<String, String> promptCache = new ConcurrentHashMap<>();
    private final Map<String, ToolMeta> toolMetaMap = new ConcurrentHashMap<>();

    public record ToolMeta(
        String name,
        String category,
        String version,
        boolean healthy,
        Instant lastHealthCheck,
        Instant registeredAt
    ) {}

    public ToolRegistry(List<LlmTool> tools, ToolPromptLoader promptLoader) {
        this.promptLoader = promptLoader;
        for (LlmTool tool : tools) {
            register(tool);
        }
    }

    public void register(LlmTool tool) {
        if (toolMap.putIfAbsent(tool.getName(), tool) != null) {
            log.warn("[TOOL-REGISTRY] duplicate tool name '{}', keeping first registration", tool.getName());
        } else {
            toolMetaMap.put(tool.getName(), new ToolMeta(
                tool.getName(),
                inferCategory(tool),
                "1.0",
                true,
                Instant.now(),
                Instant.now()
            ));
            log.info("[TOOL-REGISTRY] registered tool '{}'", tool.getName());
        }
    }

    public boolean unregister(String toolName) {
        LlmTool removed = toolMap.remove(toolName);
        promptCache.remove(toolName);
        toolMetaMap.remove(toolName);
        if (removed != null) {
            log.info("[TOOL-REGISTRY] unregistered tool '{}'", toolName);
            return true;
        }
        return false;
    }

    public Optional<LlmTool> getTool(String name) {
        return Optional.ofNullable(toolMap.get(name));
    }

    public List<LlmTool> getAllTools() {
        return List.copyOf(toolMap.values());
    }

    /**
     * 健康检查 — 验证工具可用性
     */
    public Map<String, Object> healthCheckAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (LlmTool tool : toolMap.values()) {
            boolean healthy = checkToolHealth(tool);
            ToolMeta old = toolMetaMap.get(tool.getName());
            if (old != null) {
                toolMetaMap.put(tool.getName(), new ToolMeta(
                    old.name(), old.category(), old.version(),
                    healthy, Instant.now(), old.registeredAt()));
            }
            result.put(tool.getName(), Map.of(
                "healthy", healthy,
                "enabled", tool.isEnabled(),
                "category", old != null ? old.category() : "unknown"
            ));
        }
        return result;
    }

    public List<Map<String, Object>> getToolInfoList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (LlmTool tool : toolMap.values()) {
            ToolMeta meta = toolMetaMap.get(tool.getName());
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", tool.getName());
            info.put("description", tool.getDescription());
            info.put("enabled", tool.isEnabled());
            info.put("category", meta != null ? meta.category() : "unknown");
            info.put("version", meta != null ? meta.version() : "unknown");
            info.put("healthy", meta != null ? meta.healthy() : true);
            info.put("requireDatabase", tool.requireDatabase());
            info.put("concurrencySafe", tool.isConcurrencySafe());
            info.put("timeoutMs", tool.getTimeoutMs());
            info.put("registeredAt", meta != null ? meta.registeredAt().toString() : "unknown");
            list.add(info);
        }
        return list;
    }

    private boolean checkToolHealth(LlmTool tool) {
        try {
            return tool.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private String inferCategory(LlmTool tool) {
        String name = tool.getName();
        if (name.contains("sql") || name.contains("schema")) return "data";
        if (name.contains("python")) return "compute";
        if (name.contains("chart") || name.contains("dashboard") || name.contains("report")) return "visualization";
        if (name.contains("mining")) return "mining";
        if (name.contains("filter")) return "ui";
        return "general";
    }

    /**
     * 生成 OpenAI function calling 格式的工具定义
     */
    public List<Map<String, Object>> getToolDefinitions() {
        return getToolDefinitions(null);
    }

    /**
     * 条件化工具定义 — 无数据源时过滤掉 requireDatabase() 的工具
     */
    public List<Map<String, Object>> getToolDefinitions(Long dataSourceId) {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (LlmTool tool : toolMap.values()) {
            if (!tool.isEnabled()) continue;
            if (tool.requireDatabase() && dataSourceId == null) continue;
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

    private String getDetailedDescription(LlmTool tool) {
        String promptName = tool.getPromptFileName();
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
