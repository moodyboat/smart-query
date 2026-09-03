package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Conversation-authored output specification as it moves through shaping and preview gates. */
@Data
@TableName("sq_output_draft")
public class OutputDraft {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorId;
    private Long conversationId;
    private Long basedOnVersionId;
    private String instructionText;
    private String rawSpec;
    private String shapedSpec;
    private String inputSchema;
    private String outputSchema;
    private String parameterSchema;
    private String explanation;
    private String status;
    private String shapingReport;
    private String previewData;
    private String previewReport;
    private Long candidateVersionId;
    private Long publishedVersionId;
    private String createdByUserId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
