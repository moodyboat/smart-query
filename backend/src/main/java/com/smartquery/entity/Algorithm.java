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
    private Integer isBuiltin;
    private String icon;
    private String category;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
