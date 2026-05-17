package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_ontology_dimension")
public class OntologyDimension {

    private Long id;
    private Long dataSourceId;
    private String name;
    private String businessName;
    private String description;
    private String sourceTable;
    private String sourceColumn;
    private String dimensionType;
    private Long parentDimensionId;
    private Integer hierarchyLevel;
    private String hierarchyPath;
    private String rollupColumn;
    private String dateFormat;
    private String fiscalYearStart;
    private Integer sortOrder;
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Integer deleted;
}
