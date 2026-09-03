package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Transactionally maintained logical storage usage for one owner. */
@Data
@TableName("sq_storage_usage")
public class StorageUsage {
    @TableId(type = IdType.INPUT)
    private String ownerUserId;
    private Long hotBytes;
    private Long archiveBytes;
    private Long outputHotBytes;
    private Long replayHotBytes;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
