package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sq_query_history")
public class QueryHistory {

    private Long id;
    private Long conversationId;
    private Long messageId;
    private String traceId;
    private String question;
    private String generatedSql;
    private Integer executionTimeMs;
    private Integer rowCount;
    private Integer totalTokens;
    private String model;
    private BigDecimal costUsd;
    private String status;
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
