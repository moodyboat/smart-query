package com.smartquery.prompt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Compiled prompt cache — avoids re-rendering unchanged templates */
    private final ConcurrentHashMap<String, CachedPrompt> promptCache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Value("${prompt.cache-ttl-ms:300000}")
    private long cacheTtlMs;

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
     * 直译 renderPromptTemplate: 替换 {{key}} 占位符 + 处理条件块
     * 带编译缓存：相同 template+context 5 分钟内直接返回缓存结果
     */
    public String renderPrompt(String template, Map<String, String> context) {
        String cacheKey = buildCacheKey(template, context);

        CachedPrompt cached = promptCache.get(cacheKey);
        if (cached != null && !cached.isExpired(cacheTtlMs)) {
            return cached.content();
        }

        String result = doRender(template, context);

        promptCache.put(cacheKey, new CachedPrompt(result, extractFrontmatterMeta(template), System.currentTimeMillis()));
        return result;
    }

    /**
     * 清除所有编译缓存
     */
    public void clearCache() {
        promptCache.clear();
        log.debug("Prompt cache cleared");
    }

    /**
     * 返回当前缓存条目数量
     */
    public int getCacheStats() {
        return promptCache.size();
    }

    // ---- cache internals ----

    private String buildCacheKey(String template, Map<String, String> context) {
        int paramsHash = context != null ? context.hashCode() : 0;
        int templateHash = template != null ? template.hashCode() : 0;
        return templateHash + "_" + paramsHash;
    }

    private Map<String, String> extractFrontmatterMeta(String content) {
        if (content == null || content.isBlank()) return Map.of();
        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (!m.find()) return Map.of();

        Map<String, String> meta = new LinkedHashMap<>();
        String yaml = m.group(1);
        for (String line : yaml.split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                meta.put(line.substring(0, colonIdx).trim(), line.substring(colonIdx + 1).trim());
            }
        }
        return Collections.unmodifiableMap(meta);
    }

    /** Actual rendering logic (extracted from renderPrompt) */
    private String doRender(String template, Map<String, String> context) {
        String result = template;
        result = processConditionals(result, context);
        if (context != null) {
            for (Map.Entry<String, String> entry : context.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }
        return result;
    }

    /** Cached compiled prompt entry */
    record CachedPrompt(String content, Map<String, String> frontmatter, long cachedAt) {
        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - cachedAt > ttlMs;
        }
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
}
