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

    private String modelType;
    private String algorithm;

    private String sourceTable;
    private String featureColumns;
    private String targetColumn;
    private String preprocessing;

    private String hyperparameters;

    private String metrics;
    private String featureImportance;
    private String trainingLog;
    private String modelPath;

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

    private LocalDateTime lastSyncedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Integer deleted;
}
