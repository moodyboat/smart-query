package com.smartquery.prompt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.PromptTemplate;
import com.smartquery.entity.Scenario;
import com.smartquery.service.PromptTemplateService;
import com.smartquery.service.ScenarioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 场景化提示词构建器 - 支持基于场景的动态提示词
 * 与 SystemPromptBuilder 配合使用，提供场景特定的提示词内容
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScenarioPromptBuilder {

    private final ScenarioService scenarioService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;

    private static final String DEFAULT_SCENARIO_CODE = "general";

    /**
     * 根据场景编码构建系统提示词
     *
     * @param scenarioCode 场景编码
     *param variables 变量映射
     *return 构建后的提示词内容
     */
    public String buildByScenario(String scenarioCode, Map<String, Object> variables) {
        Scenario scenario = scenarioService.getByCode(scenarioCode);
        if (scenario == null) {
            log.warn("[SCENARIO-PROMPT] 场景不存在: {}, 使用默认场景", scenarioCode);
            scenario = scenarioService.getByCode(DEFAULT_SCENARIO_CODE);
        }

        if (scenario == null || !scenario.getIsEnabled()) {
            log.warn("[SCENARIO-PROMPT] 场景不可用: {}, 返回空提示词", scenarioCode);
            return "";
        }

        PromptTemplate template = promptTemplateService.getDefaultPrompt(scenario.getId());
        if (template == null || !template.getIsEnabled()) {
            log.warn("[SCENARIO-PROMPT] 未找到可用提示词模板: {}", scenarioCode);
            return "";
        }

        String content = template.getContent();

        // 变量替换
        if (template.getVariables() != null && !template.getVariables().isEmpty()) {
            try {
                List<PromptVariable> variableConfigs = objectMapper.readValue(
                    template.getVariables(),
                    new TypeReference<List<PromptVariable>>() {}
                );

                for (PromptVariable varConfig : variableConfigs) {
                    String varName = varConfig.getName();
                    Object value = variables.get(varName);

                    if (value == null) {
                        value = varConfig.getDefaultValue();
                    }

                    if (value != null) {
                        content = content.replace("{{" + varName + "}}", String.valueOf(value));
                    }
                }
            } catch (Exception e) {
                log.error("[SCENARIO-PROMPT] 变量解析失败", e);
            }
        }

        log.info("[SCENARIO-PROMPT] 构建场景提示词: {}, 场景: {}, 长度: {}, 前100字符: {}",
            scenarioCode, scenario.getName(), content.length(), content.substring(0, Math.min(100, content.length())));

        return content;
    }

    /**
     * 变量配置内部类
     */
    private static class PromptVariable {
        private String name;
        private String type;
        private String defaultValue;
        private String description;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getDefaultValue() { return defaultValue; }
        public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}