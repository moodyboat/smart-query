package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_runtime_dependency")
public class RuntimeDependency {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runtimeProfileId;
    private Long requestId;
    private String dependencyType;
    private String dependencyName;
    private String dependencyVersion;
    private String sourceUri;
    private String checksumSha256;
    private String licenseName;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
