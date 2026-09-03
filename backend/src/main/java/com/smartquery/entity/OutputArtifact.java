package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_output_artifact")
public class OutputArtifact {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long nodeRunId;
    private String ownerUserId;
    private String outputKind;
    private String status;
    private String queryIndexStatus;
    private String archiveStatus;
    private Long payloadBytes;
    private Integer usageAccounted;
    private LocalDateTime retentionUntil;
    private LocalDateTime archivedAt;
    private String contentSpec;
    private String artifactData;
    private String filePath;
    private String mimeType;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
