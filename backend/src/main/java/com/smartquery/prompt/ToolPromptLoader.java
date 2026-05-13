package com.smartquery.prompt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 工具级提示词加载器 - 直译 Claude Code tools/prompt.ts
 *
 * loadAndRender(templatePath, context) returns String with placeholder replacement
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolPromptLoader {

    private final ResourceLoader resourceLoader;

    /**
     * 加载工具提示词模板
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
     * 直译 renderPromptTemplate: 替换 {{key}} 占位符
     */
    public String renderPrompt(String template, Map<String, String> context) {
        String result = template;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
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
}
