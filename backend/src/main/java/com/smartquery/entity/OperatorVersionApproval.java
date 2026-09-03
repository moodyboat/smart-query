package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Append-only human approval decision for one immutable operator version. */
@Data
@TableName("sq_operator_version_approval")
public class OperatorVersionApproval {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorId;
    private Long operatorVersionId;
    private String draftType;
    private Long draftId;
    private String status;
    private String requestComment;
    private String requestedByUserId;
    private String reviewerUserId;
    private String reviewComment;
    private LocalDateTime reviewedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
