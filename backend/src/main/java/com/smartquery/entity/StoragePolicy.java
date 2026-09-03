package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Global hot/archive retention and quota policy. */
@Data
@TableName("sq_storage_policy")
public class StoragePolicy {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Integer outputRetentionDays;
    private Integer replayRetentionDays;
    private Long hotQuotaBytesPerUser;
    private Long archiveQuotaBytesPerUser;
    private Integer warningPercent;
    private Integer autoArchiveEnabled;
    private String updatedByUserId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
