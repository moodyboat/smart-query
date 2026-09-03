package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable DAG snapshot whose nodes bind exact operator version ids. */
@Data
@TableName("sq_flow_version")
public class FlowVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long flowId;
    private Integer versionNo;
    private String status;
    private String contentHash;
    private String nodes;
    private String edges;
    private String parameterMappings;
    private String validationReport;
    private String createdByUserId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
