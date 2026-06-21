package com.smartquery.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 场景 DTO
 */
@Data
public class ScenarioDTO {
    private Long id;
    private String name;
    private String code;
    private String description;
    private String icon;
    private String category;
    private Boolean isSystem;
    private Boolean isEnabled;
    private Integer sortOrder;
    private ScenarioUiConfig uiConfig;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<PromptTemplateDTO> promptTemplates;

    /**
     * 前端 UI 配置（与 frontend/src/config/scenarios.js 结构对齐）
     */
    @Data
    public static class ScenarioUiConfig {
        private UiTheme theme;
        private UiAvatar avatar;
        private UiWelcome welcome;
        private List<UiCapability> capabilities;
        private List<String> examples;
    }

    @Data
    public static class UiTheme {
        private String primary;
        private String gradient;
        private String background;
        private String headerBg;
        private String cardBg;
    }

    @Data
    public static class UiAvatar {
        private String emoji;
        private String fallbackColor;
        private String size;
    }

    @Data
    public static class UiWelcome {
        private String title;
        private String subtitle;
        private String description;
    }

    @Data
    public static class UiCapability {
        private String icon;
        private String iconColor;
        private String title;
        private String description;
    }
}