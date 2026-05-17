package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_ontology_metric")
public class OntologyMetric {

    private Long id;
    private Long dataSourceId;
    private String name;
    private String businessName;
    private String description;
    private String metricType;
    private String sourceTable;
    private String sourceColumn;
    private String aggregation;
    private String formula;
    private String formulaSqlTemplate;
    private String dimensions;
    private String defaultGrain;
    private String timeColumn;
    private String filterCondition;
    private String unit;
    private String formatPattern;
    private Integer sortOrder;
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Integer deleted;
}
