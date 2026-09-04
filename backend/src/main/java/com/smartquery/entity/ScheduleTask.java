package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Persistent definition of a production schedule. Executions are stored separately. */
@Data
@TableName("sq_schedule_task")
public class ScheduleTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String taskType;
    private Long modelId;
    private Long flowVersionId;
    private String scheduleMode;
    private String cronExpression;
    private String inputTable;
    private String inputFilter;
    private String outputTable;
    private String inputPayload;
    private String status;
    private String ownerUserId;
    private LocalDateTime lastRunAt;
    private LocalDateTime nextRunAt;
    private String lastStatus;
    private String lastError;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    private Integer deleted;
}
