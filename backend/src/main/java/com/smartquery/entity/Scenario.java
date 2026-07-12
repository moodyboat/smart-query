package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 场景实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("sq_scenario")
public class Scenario {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    private String code;

    private String description;

    private String icon;

    private String category;

    private Boolean isSystem;

    private Boolean isEnabled;

    private Integer sortOrder;

    /**
     * 前端 UI 配置（JSON 字符串）：theme/avatar/welcome/capabilities/examples
     * 由 ObjectMapper 序列化，前端读取后渲染主题色、欢迎语、能力卡片等。
     */
    private String uiConfig;

    /**
     * 场景锁定的数据源 ID；NULL 表示不锁定（用户运行时可自由切换）。
     * 锁定后 ChatController 强制使用此数据源，覆盖前端传参。
     */
    private Long dataSourceId;

    /**
     * 数据库 schema 标识（仅配置元数据，注入提示词供 LLM 参考；不切换连接 schema）。
     */
    private String schemaName;

    /**
     * 表白名单（逗号分隔）；NULL 或空表示该数据源全部表可见。
     * 同时用于 SchemaContextBuilder 过滤和 ExecuteSqlTool 运行时拦截。
     */
    private String allowedTables;

    /**
     * 场景级 system prompt 覆盖（预留扩展点，本期不暴露 UI）。
     */
    private String promptOverride;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}