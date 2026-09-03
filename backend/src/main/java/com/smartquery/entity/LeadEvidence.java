package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_lead_evidence")
public class LeadEvidence {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long leadId;
    private Long nodeRunId;
    private Long operatorVersionId;
    private String evidenceKind;
    private String evidenceName;
    private String fieldName;
    private String actualValue;
    private String conditionExpression;
    private Double contribution;
    private String snippet;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
