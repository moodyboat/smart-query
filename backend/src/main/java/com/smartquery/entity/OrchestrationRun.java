package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One immutable-input execution of an exact flow version. */
@Data
@TableName("sq_orchestration_run")
public class OrchestrationRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long flowVersionId;
    private Long scheduleTaskId;
    private String ownerUserId;
    private String actorRole;
    private String triggerType;
    private String runMode;
    private String status;
    private String inputSnapshot;
    private String outputSummary;
    private String errorMessage;
    private String leaseOwner;
    private String leaseToken;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime heartbeatAt;
    private Integer attemptNo;
    private Integer recoveryCount;
    private LocalDateTime cancelRequestedAt;
    private String cancelRequestedBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
