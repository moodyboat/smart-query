package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_node_replay_chunk")
public class NodeReplayChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long replayId;
    private Integer attemptNo;
    private String payloadKind;
    private Integer chunkIndex;
    private String payloadText;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
