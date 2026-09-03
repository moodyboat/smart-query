package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_archive_chunk")
public class ArchiveChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long archiveId;
    private Integer chunkIndex;
    private String payloadText;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
