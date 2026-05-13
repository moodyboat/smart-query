package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sq_dashboard")
public class Dashboard extends BaseEntity {

    private Long conversationId;
    private Long messageId;
    private String title;
    private String layout;
    private String chartIds;
    private String filterWidgets;
    private Long dataSourceId;
}
