package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One pageable visualization row, separated from artifact metadata to avoid oversized JSON blobs. */
@Data
@TableName("sq_output_artifact_row")
public class OutputArtifactRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long artifactId;
    private Integer rowIndex;
    private String resultData;
    private String sourceData;
    private String evidenceData;
    private String sourceRefs;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
