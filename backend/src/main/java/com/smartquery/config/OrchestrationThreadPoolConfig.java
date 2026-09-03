package com.smartquery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated pools prevent orchestration trial runs from starving model training. */
@Configuration
public class OrchestrationThreadPoolConfig {

    @Value("${smart-query.orchestration.thread-pool.run-core-size:2}")
    private int runCoreSize;

    @Value("${smart-query.orchestration.thread-pool.run-max-size:4}")
    private int runMaxSize;

    @Value("${smart-query.orchestration.thread-pool.node-core-size:4}")
    private int nodeCoreSize;

    @Value("${smart-query.orchestration.thread-pool.node-max-size:8}")
    private int nodeMaxSize;

    @Value("${smart-query.orchestration.thread-pool.queue-capacity:50}")
    private int queueCapacity;

    @Bean("orchestrationExecutor")
    public Executor orchestrationExecutor() {
        return ThreadPoolFactory.build("orchestration-run", runCoreSize, runMaxSize,
            queueCapacity, ThreadPoolFactory.RejectedPolicy.ABORT);
    }

    @Bean("orchestrationNodeExecutor")
    public Executor orchestrationNodeExecutor() {
        return ThreadPoolFactory.build("orchestration-node", nodeCoreSize, nodeMaxSize,
            queueCapacity, ThreadPoolFactory.RejectedPolicy.CALLER_RUNS);
    }

    @Bean(name = "orchestrationWatchdog", destroyMethod = "shutdownNow")
    public ScheduledExecutorService orchestrationWatchdog() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newScheduledThreadPool(2, task -> {
            Thread thread = new Thread(task, "orchestration-watchdog-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
