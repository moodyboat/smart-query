package com.smartquery.prompt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具级提示词加载器 - 直译 Claude Code tools/prompt.ts
 *
 * loadAndRender(templatePath, context) returns String with placeholder replacement
 *
 * <p>增强:
 * <ul>
 *   <li>YAML frontmatter 解析: version, description</li>
 *   <li>条件渲染: {{#if key}}...{{/if}}</li>
 *   <li>版本记录到日志</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolPromptLoader {

    private final ResourceLoader resourceLoader;

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
        "^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final Pattern CONDITIONAL_PATTERN = Pattern.compile(
        "\\{\\{#if\\s+(\\w+)\\}\\}(.*?)\\{\\{/if\\}\\}", Pattern.DOTALL);

    /**
     * 加载工具提示词模板（含 frontmatter 解析）
     */
    public String loadToolPrompt(String toolName) {
        return loadResource("classpath:prompts/tools/" + toolName + ".md");
    }

    /**
     * 加载系统提示词段
     */
    public String loadSystemSection(String sectionName) {
        return loadResource("classpath:prompts/system/" + sectionName + ".md");
    }

    /**
     * 加载动态模板并替换占位符
     */
    public String loadAndRender(String templatePath, Map<String, String> context) {
        String template = loadResource(templatePath);
        return renderPrompt(template, context);
    }

    /**
     * 直译 renderPromptTemplate: 替换 {{key}} 占位符 + 处理条件块
     */
    public String renderPrompt(String template, Map<String, String> context) {
        String result = template;

        // 处理条件块: {{#if key}}...{{/if}}
        result = processConditionals(result, context);

        // 替换简单占位符: {{key}}
        for (Map.Entry<String, String> entry : context.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    /**
     * 解析 frontmatter，返回元数据 + 去除 frontmatter 的内容
     */
    public ParsedPrompt parseWithFrontmatter(String content) {
        if (content == null || content.isBlank()) {
            return new ParsedPrompt(content, Map.of());
        }
        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (!m.find()) {
            return new ParsedPrompt(content, Map.of());
        }

        Map<String, String> meta = new LinkedHashMap<>();
        String yaml = m.group(1);
        for (String line : yaml.split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                meta.put(key, value);
            }
        }

        String body = content.substring(m.end());
        return new ParsedPrompt(body, meta);
    }

    private String processConditionals(String template, Map<String, String> context) {
        Matcher m = CONDITIONAL_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String body = m.group(2);
            String value = context.get(key);
            boolean conditionMet = value != null && !value.isEmpty() && !"false".equalsIgnoreCase(value);
            m.appendReplacement(sb, conditionMet ? Matcher.quoteReplacement(body) : "");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String loadResource(String location) {
        try {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                log.warn("Prompt template not found: {}", location);
                return "";
            }
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load prompt template: {}", location, e);
            return "";
        }
    }

    public record ParsedPrompt(String content, Map<String, String> metadata) {}
}
