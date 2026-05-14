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
    private String triggerType;
    private String status;
    private String hyperparameters;
    private String metrics;
    private String executionLog;
    private Integer executionTimeMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
