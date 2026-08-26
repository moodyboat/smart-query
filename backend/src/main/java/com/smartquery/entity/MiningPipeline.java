package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_mining_pipeline")
public class MiningPipeline {

    private Long id;
    private String name;
    private String description;
    private Long dataSourceId;
    private Long conversationId;
    /** Owning user; null historical rows are denied to non-admin users. */
    private String userId;
    private String status;
    private String nodes;
    private String edges;

    private String sourceType;
    private LocalDateTime lastSyncedAt;

    private LocalDateTime lastExecutedAt;
    private String executionLog;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Integer deleted;
}
