package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_chart")
public class Chart {

    private Long id;
    private Long conversationId;
    private Long messageId;
    private String title;
    private String chartType;
    private String echartsOption;
    private String imagePath; // 存储实际图表图片的路径
    private Long dataSourceId;
    private String baseSql;
    private String filterBindings;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
