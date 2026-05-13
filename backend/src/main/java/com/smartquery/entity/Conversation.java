package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sq_conversation")
public class Conversation extends BaseEntity {

    private String title;
    private Long dataSourceId;
    private String userId;
    private Integer status;
}
