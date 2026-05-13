package com.smartquery;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.smartquery.mapper")
@EnableAsync
public class SmartQueryApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartQueryApplication.class, args);
    }
}
