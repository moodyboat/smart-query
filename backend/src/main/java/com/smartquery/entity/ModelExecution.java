package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_model_execution")
public class ModelExecution {

    private Long id;
    private Long modelId;
    /** User who triggered this run; null is reserved for scheduler/system runs. */
    private String triggeredByUserId;
    private String triggerType;
    private String status;
    private String hyperparameters;
    private String metrics;
    private String executionLog;
    private Integer executionTimeMs;
    private Integer progressPercent;
    private String currentStage;
    private String progressMessage;
    private Boolean cancelRequested;
    private String artifactPath;
    private String artifactSha256;
    private Integer artifactSchemaVersion;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
