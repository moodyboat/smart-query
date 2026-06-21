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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}