package com.smartquery.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 元数据配置 DTO
 */
@Data
public class MetadataConfigDTO {
    private Long id;
    private Long dataSourceId;
    private String tableName;
    private String columnName;
    private String configType;
    private String name;
    private String description;
    private String businessTerm;
    private List<String> aliases;
    private String dataType;
    private Boolean isSensitive;
    private Boolean isFilterable;
    private Boolean isDimension;
    private Boolean isMetric;
    private String unit;
    private String format;
    private List<Dictionary> dictionary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 字典配置
     */
    @Data
    public static class Dictionary {
        private String value;
        private String label;
    }
}