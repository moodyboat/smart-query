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

    /**
     * 场景锁定的数据源 ID；NULL 表示不锁定（用户运行时可自由切换）。
     */
    private Long dataSourceId;

    /**
     * 数据库 schema 标识（仅配置元数据，不切换连接 schema）。
     */
    private String schemaName;

    /**
     * 表白名单（逗号分隔原始字符串）；前端编辑用 {@link #allowedTableList}。
     */
    private String allowedTables;

    /**
     * 表白名单（前端友好的 List 形式）；保存时由 Controller join 回 {@link #allowedTables}。
     * NULL 或空表示该数据源全部表可见。
     */
    private List<String> allowedTableList;

    /**
     * 场景级 system prompt 覆盖（预留扩展点）。
     */
    private String promptOverride;

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