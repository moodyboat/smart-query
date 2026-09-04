package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_algorithm")
public class Algorithm {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String algorithmId;
    private String name;
    private String description;
    private String modelTypes;
    private String paramsSchema;
    private String pythonCodeTemplate;
    private String aliases;
    private Integer isBuiltin;
    private Integer enabled;
    private Integer versionNo;
    private String icon;
    private String category;

    /** Governance-only, calculated from active models and pipelines. */
    @TableField(exist = false)
    private Long modelReferenceCount;

    @TableField(exist = false)
    private Long publishedModelReferenceCount;

    @TableField(exist = false)
    private Long pipelineReferenceCount;

    @TableField(exist = false)
    private Long totalReferenceCount;

    @TableField(exist = false)
    private Boolean deletable;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
