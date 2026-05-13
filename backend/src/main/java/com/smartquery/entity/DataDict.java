package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_data_dict")
public class DataDict {

    private Long id;
    private Long dataSourceId;
    private String tableName;
    private String tableComment;
    private String columnName;
    private String columnComment;
    private String columnType;
    private Integer isDimension;
    private String sampleValues;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
