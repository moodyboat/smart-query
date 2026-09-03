package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Auditable execution record for one DAG node. */
@Data
@TableName("sq_node_run")
public class NodeRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private String nodeId;
    private Long operatorVersionId;
    private String status;
    private String inputHash;
    private String outputHash;
    private String outputSummary;
    private String executionLog;
    private String errorMessage;
    private Long executionTimeMs;
    private Integer attemptNo;
    private String leaseToken;
    private Integer timeoutSeconds;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
