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
    /** 会话绑定的场景编码（如 sales_analysis）；NULL=通用对话，刷新页面后用于恢复场景上下文 */
    private String scenario;
}
