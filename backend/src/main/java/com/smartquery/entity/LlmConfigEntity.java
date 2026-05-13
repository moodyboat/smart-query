package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sq_llm_config")
public class LlmConfigEntity extends BaseEntity {

    private String modelCode;
    private String modelName;
    private String apiUrl;
    private String apiKey;
    private Integer maxTokens;
    private BigDecimal temperature;
    private Integer isDefault;
    private Integer status;
}
