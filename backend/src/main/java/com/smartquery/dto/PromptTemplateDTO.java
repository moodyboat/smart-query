package com.smartquery.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提示词模板 DTO
 */
@Data
public class PromptTemplateDTO {
    private Long id;
    private Long scenarioId;
    private String name;
    private String code;
    private String description;
    private String type;
    private String content;
    private List<VariableConfig> variables;
    private ModelConfig modelConfig;
    private Boolean isDefault;
    private Boolean isSystem;
    private Boolean isEnabled;
    private String version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 变量配置
     */
    @Data
    public static class VariableConfig {
        private String name;
        private String type;
        private String defaultValue;
        private String description;
    }

    /**
     * 模型配置
     */
    @Data
    public static class ModelConfig {
        private String model;
        private Double temperature;
        private Integer maxTokens;
    }
}