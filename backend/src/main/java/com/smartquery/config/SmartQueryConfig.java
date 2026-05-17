package com.smartquery.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.smartquery.tool.SqlSafetyValidator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class SmartQueryConfig {

    @Bean
    public SqlSafetyValidator sqlSafetyValidator(
        org.springframework.core.env.Environment env
    ) {
        int maxRows = env.getProperty("sql-safety.max-rows", Integer.class, 1000);
        int queryTimeout = env.getProperty("sql-safety.query-timeout-seconds", Integer.class, 30);
        return new SqlSafetyValidator(
            Set.of("SELECT", "SHOW", "DESCRIBE", "EXPLAIN"),
            Set.of("DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "CREATE",
                   "TRUNCATE", "GRANT", "REVOKE", "REPLACE", "RENAME",
                   "CALL", "EXEC", "EXECUTE", "LOAD DATA", "INTO OUTFILE", "INTO DUMPFILE"),
            maxRows, queryTimeout
        );
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
