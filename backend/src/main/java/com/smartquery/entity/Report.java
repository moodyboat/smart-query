package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sq_report")
public class Report extends BaseEntity {

    private Long conversationId;
    private Long messageId;
    private String title;
    private String sections;
    private String conclusion;
    private String status;
    private Long dataSourceId;
}
