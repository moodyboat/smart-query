package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable rule-code proposal generated from a conversation turn. */
@Data
@TableName("sq_rule_draft")
public class RuleDraft {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorId;
    private Long conversationId;
    private Long basedOnVersionId;
    private String instructionText;
    private String sourceLanguage;
    private String entrypoint;
    private String sourceCode;
    private String inputSchema;
    private String outputSchema;
    private String parameterSchema;
    private String testCases;
    private String explanation;
    private String status;
    private String validationReport;
    private Long candidateVersionId;
    private String createdByUserId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
