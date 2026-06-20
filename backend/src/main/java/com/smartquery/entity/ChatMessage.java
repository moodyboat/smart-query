package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_chat_message")
public class ChatMessage {

    private Long id;
    private Long conversationId;
    private String role;
    private String content;
    private String toolName;
    private String toolCallId;
    private Integer tokenCount;
    @TableField("\"MODEL\"")
    private String model;
    private String traceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private String metadata;
}
