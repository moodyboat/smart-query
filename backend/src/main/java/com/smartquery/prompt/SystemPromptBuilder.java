package com.smartquery.prompt;

import com.smartquery.tool.LlmTool;
import com.smartquery.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统提示词构建器 — 直译 Claude Code constants/prompts.ts
 *
 * <p>翻译对照:
 * <pre>
 * TS: getSystemPrompt(tools, model) → string[]
 * Java: build(tools, model) → String
 *
 * TS: getSimpleIntroSection(), getSimpleSystemSection(), getSimpleDoingTasksSection() ...
 * Java: getIntroSection(), getCapabilitiesSection(), getDoingTasksSection() ...
 * </pre>
 *
 * <p>架构: 静态段 (cacheable) + 动态段
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPromptBuilder {

    private final ToolPromptLoader promptLoader;
    private final ToolRegistry toolRegistry;

    /**
     * 直译 getSystemPrompt(): 组装最终系统提示词
     * 对应 Claude Code 的分段组合模式:
     * [intro, system, doingTasks, actions, usingTools, toneStyle, outputEfficiency, safetyRules, ...dynamicSections]
     */
    public String build(String model) {
        List<String> sections = new ArrayList<>();

        sections.add(getIntroSection());
        sections.add(getCapabilitiesSection());
        sections.add(getDoingTasksSection());
        sections.add(getActionsSection());
        sections.add(getUsingToolsSection());
        sections.add(getToneStyleSection());
        sections.add(getSafetyRulesSection());

        return sections.stream()
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.joining("\n\n"));
    }

    /**
     * 直译 getSimpleIntroSection() — 角色介绍
     */
    private String getIntroSection() {
        return promptLoader.loadSystemSection("intro");
    }

    /**
     * 直译 getSimpleSystemSection() — 能力说明
     */
    private String getCapabilitiesSection() {
        return promptLoader.loadSystemSection("capabilities");
    }

    /**
     * 直译 getSimpleDoingTasksSection() — 任务执行指引
     */
    private String getDoingTasksSection() {
        return promptLoader.loadSystemSection("doing-tasks");
    }

    /**
     * 直译 getActionsSection() — 可用动作
     */
    private String getActionsSection() {
        String base = promptLoader.loadSystemSection("actions");
        StringBuilder sb = new StringBuilder(base);

        List<LlmTool> tools = toolRegistry.getAllTools();
        if (!tools.isEmpty()) {
            sb.append("\n\n## 可用工具\n");
            for (LlmTool tool : tools) {
                sb.append("- **").append(tool.getName()).append("**: ").append(tool.getDescription()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 直译 getUsingYourToolsSection() — 工具使用指引
     */
    private String getUsingToolsSection() {
        return promptLoader.loadSystemSection("using-tools");
    }

    /**
     * 直译 getSimpleToneAndStyleSection()
     */
    private String getToneStyleSection() {
        return promptLoader.loadSystemSection("tone-style");
    }

    private String getSafetyRulesSection() {
        return promptLoader.loadSystemSection("safety-rules");
    }
}
