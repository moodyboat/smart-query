package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable event emitted by an asynchronous model or pipeline task. */
@Data
@TableName("sq_task_event")
public class TaskEvent {
    @TableId
    private Long id;
    private String topic;
    private String ownerUserId;
    private String eventName;
    private String payload;
    private Boolean terminal;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
