package com.smartquery.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 场景 DTO
 */
@Data
public class ScenarioDTO {
    private Long id;
    private String name;
    private String code;
    private String description;
    private String icon;
    private String category;
    private Boolean isSystem;
    private Boolean isEnabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<PromptTemplateDTO> promptTemplates;
}