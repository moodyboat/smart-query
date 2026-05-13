package com.smartquery.config;

import com.smartquery.tool.SqlSafetyValidator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class SmartQueryConfig {

    @Bean
    public SqlSafetyValidator sqlSafetyValidator() {
        return SqlSafetyValidator.defaults();
    }
}
