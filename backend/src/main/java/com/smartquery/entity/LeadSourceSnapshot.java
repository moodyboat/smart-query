package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_lead_source_snapshot")
public class LeadSourceSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long dataSourceId;
    private String sourceTable;
    private String primaryKeyColumn;
    private String primaryKeyValue;
    private String snapshotData;
    private String snapshotHash;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
