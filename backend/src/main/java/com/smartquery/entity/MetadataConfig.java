package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 元数据配置实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("sq_metadata_config")
public class MetadataConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long dataSourceId;

    private String tableName;

    private String columnName;

    private String configType;

    private String name;

    private String description;

    private String businessTerm;

    private String aliases;

    private String dataType;

    private Boolean isSensitive;

    private Boolean isFilterable;

    private Boolean isDimension;

    private Boolean isMetric;

    private String unit;

    private String format;

    private String dictionary;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}