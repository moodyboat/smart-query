package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Recoverable archive envelope for output or replay detail payloads. */
@Data
@TableName("sq_archive_record")
public class ArchiveRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String targetType;
    private Long targetId;
    private String ownerUserId;
    private String state;
    private String payloadFormat;
    private Long originalBytes;
    private Long storedBytes;
    private String checksum;
    private Integer chunkCount;
    private String reason;
    private String archivedByUserId;
    private LocalDateTime archivedAt;
    private String restoredByUserId;
    private LocalDateTime restoredAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
