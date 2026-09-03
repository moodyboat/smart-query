package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable runtime build manifest; only lifecycle status may later become DEPRECATED. */
@Data
@TableName("sq_runtime_profile")
public class RuntimeProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String runtimeType;
    private String imageRef;
    private String imageDigest;
    private String dependencyLock;
    private String buildManifest;
    private String securityReport;
    private String status;
    private Integer defaultProfile;
    private Long baseProfileId;
    private String createdByUserId;
    private String approvedByUserId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
