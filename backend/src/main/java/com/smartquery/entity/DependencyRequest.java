package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Auditable request for one dependency to enter a dedicated runtime family. */
@Data
@TableName("sq_dependency_request")
public class DependencyRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestNo;
    private String dependencyType;
    private String runtimeType;
    private String dependencyName;
    private String requestedVersion;
    private String resolvedVersion;
    private String sourceUri;
    private String checksumSha256;
    private String licenseName;
    private String licenseDecision;
    private Integer vulnerabilityCritical;
    private Integer vulnerabilityHigh;
    private Integer sourceVerified;
    private String reason;
    private String status;
    private String ownerUserId;
    private String reviewComment;
    private String reviewedByUserId;
    private LocalDateTime reviewedAt;
    private Long runtimeProfileId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
