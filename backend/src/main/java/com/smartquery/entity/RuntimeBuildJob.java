package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable hand-off between dependency approval and an external immutable-image builder. */
@Data
@TableName("sq_runtime_build_job")
public class RuntimeBuildJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobNo;
    private Long dependencyRequestId;
    private String runtimeType;
    private Long baseProfileId;
    private String buildSpec;
    private String status;
    private Integer attemptNo;
    private Integer maxAttempts;
    private String workerId;
    private String leaseTokenHash;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String resultManifest;
    private String revalidationReport;
    private String errorCode;
    private String errorMessage;
    private Long runtimeProfileId;
    private String requestedByUserId;
    private String approvedByUserId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
