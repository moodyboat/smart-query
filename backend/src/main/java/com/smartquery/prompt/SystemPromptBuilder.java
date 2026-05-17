package com.smartquery.prompt;

import com.smartquery.tool.LlmTool;
import com.smartquery.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 系统提示词构建器 — 翻译 Claude Code constants/prompts.ts
 *
 * <p>改进: 引入优先级排序 + 条件注入 + Token 预算控制
 * <ul>
 *   <li>按优先级排序: OVERRIDE > COORDINATOR > AGENT > CUSTOM > DEFAULT > APPEND</li>
 *   <li>条件注入: 根据场景动态加载不同提示词段</li>
 *   <li>Token 预算: 低优先级段超预算时可截断或跳过</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPromptBuilder {

    private final ToolPromptLoader promptLoader;
    private final ToolRegistry toolRegistry;
    private final OntologyContextBuilder ontologyContextBuilder;

    @org.springframework.beans.factory.annotation.Value("${smart-query.prompt.system-token-budget:16000}")
    private int systemTokenBudget;
    private static final int CHARS_PER_TOKEN = com.smartquery.common.TokenConstants.CHARS_PER_TOKEN;

    /**
     * 构建系统提示词 (向后兼容)
     */
    public String build(String model) {
        return build(model, null, null);
    }

    /**
     * 构建系统提示词 (带条件上下文)
     */
    public String build(String model, Long dataSourceId, Boolean hasMiningModel) {
        PromptContext ctx = PromptContext.of(
            dataSourceId != null,
            Boolean.TRUE.equals(hasMiningModel),
            model,
            dataSourceId
        );

        List<PromptSection> sections = collectSections(ctx);

        // 按优先级排序 (高 → 低)
        sections.sort(Comparator.comparingInt(s -> -s.priority().weight()));

        // 条件过滤
        sections = sections.stream()
            .filter(s -> s.shouldInject(ctx))
            .toList();

        // Token 预算控制: 超预算时从低优先级开始截断
        sections = enforceTokenBudget(sections);

        String result = sections.stream()
            .map(PromptSection::content)
            .filter(c -> c != null && !c.isBlank())
            .collect(Collectors.joining("\n\n"));

        log.debug("[PROMPT] built: {} sections, {} chars, ~{} tokens",
            sections.size(), result.length(), result.length() / CHARS_PER_TOKEN);

        return result;
    }

    private List<PromptSection> collectSections(PromptContext ctx) {
        List<PromptSection> sections = new ArrayList<>();

        // DEFAULT: 核心系统段
        sections.add(PromptSection.of(PromptPriority.DEFAULT, "intro",
            promptLoader.loadSystemSection("intro")));
        sections.add(PromptSection.of(PromptPriority.DEFAULT, "capabilities",
            promptLoader.loadSystemSection("capabilities")));
        sections.add(PromptSection.of(PromptPriority.DEFAULT, "doing-tasks",
            promptLoader.loadSystemSection("doing-tasks")));
        sections.add(PromptSection.of(PromptPriority.DEFAULT, "actions",
            buildActionsSection(ctx)));
        sections.add(PromptSection.of(PromptPriority.DEFAULT, "using-tools",
            promptLoader.loadSystemSection("using-tools")));
        sections.add(PromptSection.of(PromptPriority.DEFAULT, "tone-style",
            promptLoader.loadSystemSection("tone-style")));

        // OVERRIDE: 安全规则 (最高优先级，不可截断)
        sections.add(PromptSection.of(PromptPriority.OVERRIDE, "safety-rules",
            promptLoader.loadSystemSection("safety-rules")));

        // CUSTOM: 有数据源时注入数据探索指引
        sections.add(PromptSection.conditional(
            PromptPriority.CUSTOM, "data-exploration",
            promptLoader.loadSystemSection("doing-tasks"),
            PromptContext::hasDataSource
        ));

        // CUSTOM: 有挖掘上下文时注入挖掘指导
        sections.add(PromptSection.conditional(
            PromptPriority.CUSTOM, "mining-guidance",
            promptLoader.loadSystemSection("mining-guidance"),
            PromptContext::hasMiningModel
        ));

        // CUSTOM: 建模工作流状态机（与 mining-guidance 配合，条件相同）
        sections.add(PromptSection.conditional(
            PromptPriority.CUSTOM, "mining-workflow",
            promptLoader.loadSystemSection("mining-workflow"),
            PromptContext::hasMiningModel
        ));

        // CUSTOM: 本体模型上下文（有数据源且配置了本体时注入）
        sections.add(PromptSection.conditional(
            PromptPriority.CUSTOM, "ontology-guidance",
            promptLoader.loadSystemSection("ontology-guidance"),
            pc -> pc.hasDataSource() && ontologyContextBuilder.hasOntology(pc.dataSourceId())
        ));

        return sections;
    }

    private List<PromptSection> enforceTokenBudget(List<PromptSection> sections) {
        int totalChars = sections.stream()
            .mapToInt(s -> s.content() != null ? s.content().length() : 0)
            .sum();
        int totalTokens = totalChars / CHARS_PER_TOKEN;

        if (totalTokens <= systemTokenBudget) {
            return sections;
        }

        log.info("[PROMPT] budget enforcement: {} tokens > budget {}, truncating from low priority",
            totalTokens, systemTokenBudget);

        List<PromptSection> result = new ArrayList<>(sections);
        // 从最低优先级 (APPEND) 开始截断
        for (int i = result.size() - 1; i >= 0 && totalTokens > systemTokenBudget; i--) {
            PromptSection s = result.get(i);
            // OVERRIDE 和 COORDINATOR 不可截断
            if (s.priority() == PromptPriority.OVERRIDE || s.priority() == PromptPriority.COORDINATOR) {
                continue;
            }
            if (s.tokenBudget() > 0 && s.content() != null) {
                int maxChars = s.tokenBudget() * CHARS_PER_TOKEN;
                if (s.content().length() > maxChars) {
                    String truncated = s.content().substring(0, maxChars) + "\n...(已截断)";
                    result.set(i, new PromptSection(s.name(), truncated, s.cacheable(),
                        s.priority(), s.condition(), s.tokenBudget()));
                    totalTokens = result.stream()
                        .mapToInt(sec -> sec.content() != null ? sec.content().length() : 0)
                        .sum() / CHARS_PER_TOKEN;
                }
            }
        }

        return result;
    }

    private String buildActionsSection(PromptContext ctx) {
        String base = promptLoader.loadSystemSection("actions");
        StringBuilder sb = new StringBuilder(base);

        List<LlmTool> tools = toolRegistry.getAllTools();
        if (!tools.isEmpty()) {
            sb.append("\n\n## 可用工具\n");
            for (LlmTool tool : tools) {
                if (tool.requireDatabase() && !ctx.hasDataSource()) continue;
                sb.append("- **").append(tool.getName()).append("**: ").append(tool.getDescription()).append("\n");
            }
        }
        return sb.toString();
    }
}
