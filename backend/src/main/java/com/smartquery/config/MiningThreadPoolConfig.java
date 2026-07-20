package com.smartquery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;

@Configuration
public class MiningThreadPoolConfig {

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.thread-pool.core-size:2}")
    private int corePoolSize;

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.thread-pool.max-size:4}")
    private int maxPoolSize;

    @org.springframework.beans.factory.annotation.Value("${smart-query.mining.thread-pool.queue-capacity:20}")
    private int queueCapacity;

    @Bean("miningExecutor")
    public Executor miningExecutor() {
        return ThreadPoolFactory.build("mining", corePoolSize, maxPoolSize, queueCapacity, ThreadPoolFactory.RejectedPolicy.ABORT);
    }
}
