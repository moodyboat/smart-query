package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_python_execution")
public class PythonExecution {

    private Long id;
    private Long conversationId;
    private Long messageId;
    private String code;
    private String stdout;
    private String stderr;
    private Integer exitCode;
    private Integer executionTimeMs;
    private String status;
    private Long dataSourceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
