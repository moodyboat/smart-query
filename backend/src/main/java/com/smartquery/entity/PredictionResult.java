package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_prediction_result")
public class PredictionResult {

    private Long id;
    private Long modelId;
    private String modelName;
    private String batchId;
    private String inputData;
    private String prediction;
    private Double probability;
    private String resultTable;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime predictedAt;
}
