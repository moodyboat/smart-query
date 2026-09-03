package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Typed scalar projection used for portable, parameter-bound result filtering and sorting. */
@Data
@TableName("sq_output_artifact_cell")
public class OutputArtifactCell {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long artifactId;
    private Integer rowIndex;
    private String fieldPath;
    private String valueType;
    private String textValue;
    private String textSortValue;
    private BigDecimal numberValue;
    private Integer booleanValue;
    private String valueHash;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
