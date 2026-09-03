package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_draft_dependency")
public class DraftDependency {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String draftType;
    private Long draftId;
    private Long requestId;
    private String dependencyType;
    private String dependencyName;
    private String versionConstraint;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
