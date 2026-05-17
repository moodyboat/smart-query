package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_ontology_indicator_config")
public class OntologyIndicatorConfig {

    private Long id;
    private Long dataSourceId;
    private String configName;
    private String indicatorTable;
    private String nameColumn;
    private String formulaColumn;
    private String categoryColumn;
    private String unitColumn;
    private String descriptionColumn;
    private String detailTableColumn;
    private String detailFilterColumn;
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Integer deleted;
}
