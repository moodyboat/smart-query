package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
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
    private Integer version;

    private String scheduleCron;
    private Boolean scheduleEnabled;
    private LocalDateTime lastRunAt;
    private LocalDateTime nextRunAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Integer deleted;
}
