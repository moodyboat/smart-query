package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Human approval decision for one immutable composed-model (flow) version. */
@Data
@TableName("sq_model_version_approval")
public class ModelVersionApproval {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long flowId;
    private Long flowVersionId;
    private String status;
    private String requestComment;
    private String requestedByUserId;
    private String reviewerUserId;
    private String reviewComment;
    private LocalDateTime reviewedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
