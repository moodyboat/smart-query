package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sq_lead")
public class Lead extends BaseEntity {
    private String leadNo;
    private String leadType;
    private String ownerUserId;
    private String subjectType;
    private String subjectId;
    private String subjectName;
    private Double decisionScore;
    private String decisionLevel;
    private Double decisionThreshold;
    private String decisionResult;
    private Long flowVersionId;
    private Long runId;
    private Long sourceSnapshotId;
    private String attributesData;
    private String status;
    private String assigneeUserId;
    private LocalDateTime occurredAt;
}
