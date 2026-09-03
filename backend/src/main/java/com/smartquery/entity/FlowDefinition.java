package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sq_flow_definition")
public class FlowDefinition extends BaseEntity {
    private String code;
    private String name;
    private String description;
    private String ownerUserId;
    private String status;
}
