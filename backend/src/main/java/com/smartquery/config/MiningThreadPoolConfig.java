package com.smartquery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

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
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("mining-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
