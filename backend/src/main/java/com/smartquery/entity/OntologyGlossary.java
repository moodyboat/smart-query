package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sq_ontology_glossary")
public class OntologyGlossary {

    private Long id;
    private Long dataSourceId;
    private String term;
    private String synonyms;
    private String definition;
    private Long mappedMetricId;
    private String mappedTable;
    private String mappedColumn;
    private String mappingRule;
    private String usageExamples;
    private String category;
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Integer deleted;
}
