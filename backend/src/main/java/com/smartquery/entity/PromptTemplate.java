package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 提示词模板实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("sq_prompt_template")
public class PromptTemplate {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long scenarioId;

    private String name;

    private String code;

    private String description;

    private String type;

    private String content;

    private String variables;

    private String modelConfig;

    private Boolean isDefault;

    private Boolean isSystem;

    private Boolean isEnabled;

    private String version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}