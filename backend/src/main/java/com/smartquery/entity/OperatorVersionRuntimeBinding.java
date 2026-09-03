package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Append-only binding that freezes an operator version to a runtime and image digest. */
@Data
@TableName("sq_operator_version_runtime")
public class OperatorVersionRuntimeBinding {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorVersionId;
    private Long runtimeProfileId;
    private String runtimeType;
    private String imageDigest;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
