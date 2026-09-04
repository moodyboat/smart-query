package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_mining_model")
public class MiningModel {

    private Long id;
    private Long pipelineId;
    private String name;
    private String description;
    private Long dataSourceId;
    private Long conversationId;

    private String source;

    /** 归属用户 ID（与 sq_conversation.user_id 对齐，String 类型）。
     *  admin 可见全部；其他用户只能访问自己的模型。 */
    private String userId;

    private String modelType;
    private String algorithm;
    /** Immutable copy of the algorithm definition used for this model version. */
    private Integer algorithmVersion;
    private String algorithmSnapshot;

    private String sourceTable;
    private String featureColumns;
    private String targetColumn;
    private String preprocessing;

    private String hyperparameters;

    private String metrics;
    private String featureImportance;
    private String trainingLog;
    private String modelPath;
    private String artifactSha256;
    private Integer artifactSchemaVersion;

    private String status;

    @Version
    private Integer version;

    private String scheduleCron;
    private Boolean scheduleEnabled;
    private String scheduleMode;
    private LocalDateTime lastRunAt;
    private LocalDateTime nextRunAt;

    private String predictInputTable;
    private String predictInputFilter;
    private String predictResultTable;

    private String validationMode;
    private Integer cvFolds;
    private Double testSize;
    private String temporalColumn;
    private String validationMetrics;

    /** Evaluation and governance configuration. JSON fields are intentionally
     * stored with the model so an agent-created flow remains reproducible. */
    private String positiveClass;
    private String groupColumns;
    private String oosTable;
    private String oosFilter;
    private String calibrationMethod;
    private String thresholdPolicy;
    private String governancePolicy;
    private String evaluationStatus;
    private String approvedByUserId;
    private LocalDateTime approvedAt;
    private String monitoringBaseline;
    private String lastDriftMetrics;
    private LocalDateTime lastDriftAt;

    private LocalDateTime lastSyncedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Integer deleted;
}
