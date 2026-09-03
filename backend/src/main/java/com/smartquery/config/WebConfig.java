package com.smartquery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Value("${smart-query.thread-pool.core-size:8}")
    private int corePoolSize;

    @Value("${smart-query.thread-pool.max-size:16}")
    private int maxPoolSize;

    @Value("${smart-query.thread-pool.queue-capacity:200}")
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
        return ThreadPoolFactory.build("sq", corePoolSize, maxPoolSize, queueCapacity, ThreadPoolFactory.RejectedPolicy.CALLER_RUNS);
    }

    @Value("${smart-query.thread-pool.llm.core-size:8}")
    private int llmCorePoolSize;

    @Value("${smart-query.thread-pool.llm.max-size:16}")
    private int llmMaxPoolSize;

    @Value("${smart-query.thread-pool.llm.queue-capacity:100}")
    private int llmQueueCapacity;

    @Bean("llmExecutor")
    public Executor llmExecutor() {
        return ThreadPoolFactory.build("llm", llmCorePoolSize, llmMaxPoolSize, llmQueueCapacity, ThreadPoolFactory.RejectedPolicy.ABORT);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/artifacts/**")
            .addResourceLocations("file:" + artifactDir + "/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/api/**", "/artifacts/**")
            .excludePathPatterns(
                "/api/v1/auth/login",
                "/api/v1/auth/register",
                "/api/v2/runtime-build-worker/**",
                "/swagger-ui/**",
                "/v3/api-docs/**",
                "/swagger-resources/**",
                "/webjars/**"
            );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
            .allowedHeaders("*")
            .exposedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600);
    }
}
