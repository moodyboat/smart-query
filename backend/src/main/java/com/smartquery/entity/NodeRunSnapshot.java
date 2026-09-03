package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Exact replay boundary captured for the currently authoritative node-run attempt. */
@Data
@TableName("sq_node_run_snapshot")
public class NodeRunSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long nodeRunId;
    private Long runId;
    private Long flowVersionId;
    private String flowContentHash;
    private String nodeId;
    private Long operatorVersionId;
    private String operatorVersionContentHash;
    private String operatorType;
    private String implementationType;
    private Long runtimeProfileId;
    private String runtimeImageDigest;
    private String inputHash;
    private String outputHash;
    private Integer attemptNo;
    private String leaseToken;
    private String status;
    private Long snapshotBytes;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
