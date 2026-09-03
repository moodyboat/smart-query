package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable operator snapshot. Updates are intentionally not exposed by V2 services. */
@Data
@TableName("sq_operator_version")
public class OperatorVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorId;
    private Integer versionNo;
    private String status;
    private String contentHash;
    private String inputSchema;
    private String outputSchema;
    private String parameterSchema;
    private String implementationType;
    private String implementationPayload;
    private String capabilityRequirements;
    private String validationReport;
    private String createdByUserId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
