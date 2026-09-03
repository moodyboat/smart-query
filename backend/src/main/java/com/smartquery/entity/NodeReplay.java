package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Side-effect-free re-execution of one historical node snapshot. */
@Data
@TableName("sq_node_replay")
public class NodeReplay {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String replayNo;
    private Long sourceRunId;
    private Long sourceNodeRunId;
    private Long snapshotId;
    private Long flowVersionId;
    private String flowContentHash;
    private String nodeId;
    private Long operatorVersionId;
    private String operatorVersionContentHash;
    private Long runtimeProfileId;
    private String runtimeImageDigest;
    private String inputHash;
    private String expectedOutputHash;
    private String status;
    private Integer attemptNo;
    private Integer timeoutSeconds;
    private String leaseToken;
    private LocalDateTime leaseExpiresAt;
    private String ownerUserId;
    private String actorRole;
    private String outputHash;
    private String archiveStatus;
    private Long payloadBytes;
    private Integer usageAccounted;
    private LocalDateTime retentionUntil;
    private LocalDateTime archivedAt;
    private String outputSummary;
    private String diffSummary;
    private String executionLog;
    private String errorMessage;
    private Long executionTimeMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
