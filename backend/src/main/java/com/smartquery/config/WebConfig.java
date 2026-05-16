package com.smartquery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${smart-query.thread-pool.core-size:4}")
    private int corePoolSize;

    @Value("${smart-query.thread-pool.max-size:8}")
    private int maxPoolSize;

    @Value("${smart-query.thread-pool.queue-capacity:100}")
    private int queueCapacity;

    @Value("${smart-query.python.artifact-dir:/tmp/smartquery-artifacts}")
    private String artifactDir;

    @Value("${smart-query.sse.timeout-ms:300000}")
    private long sseTimeoutMs;

    @Override
    public void configureAsyncSupport(org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(sseTimeoutMs);
    }

    @Bean("asyncExecutor")
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("sq-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/artifacts/**")
            .addResourceLocations("file:" + artifactDir + "/");
    }
}
