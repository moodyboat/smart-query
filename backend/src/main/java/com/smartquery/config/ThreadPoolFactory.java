package com.smartquery.config;

import com.smartquery.common.UserContextHolder;
import com.smartquery.engine.ConversationContextHolder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 统一构建 ThreadPoolTaskExecutor — 收敛散落在 WebConfig / MiningThreadPoolConfig 的 3 处独立构建逻辑。
 *
 * <p>所有线程池统一行为：
 * <ul>
 *   <li>等待任务完成后再关闭（setWaitForTasksToCompleteOnShutdown=true）</li>
 *   <li>统一 30 秒 awaitTermination（asyncExecutor 默认 30，llmExecutor 默认 10 → 这里取 30 兼容）</li>
 *   <li>拒绝策略由调用方指定（async 用 CallerRuns 兜底，llm/mining 用 Abort 快速失败）</li>
 * </ul>
 */
public final class ThreadPoolFactory {

    private ThreadPoolFactory() {}

    public static Executor build(String name, int core, int max, int queue, RejectedPolicy policy) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(name + "-");
        // ThreadLocal values do not cross executor boundaries. Capture them when
        // a task is submitted and restore/clear them around each worker task so
        // pooled threads can never leak one user's identity into another task.
        executor.setTaskDecorator(task -> {
            UserContextHolder.UserContext user = UserContextHolder.get();
            Long conversationId = ConversationContextHolder.getConversationId();
            Long dataSourceId = ConversationContextHolder.getDataSourceId();
            String traceId = ConversationContextHolder.getTraceId();
            return () -> {
                Long previousConversationId = ConversationContextHolder.getConversationId();
                Long previousDataSourceId = ConversationContextHolder.getDataSourceId();
                String previousTraceId = ConversationContextHolder.getTraceId();
                try (UserContextHolder.Scope ignored = UserContextHolder.open(user)) {
                    setConversationContext(conversationId, dataSourceId, traceId);
                    task.run();
                } finally {
                    setConversationContext(previousConversationId, previousDataSourceId, previousTraceId);
                }
            };
        });
        executor.setRejectedExecutionHandler(switch (policy) {
            case CALLER_RUNS -> new ThreadPoolExecutor.CallerRunsPolicy();
            case ABORT -> new ThreadPoolExecutor.AbortPolicy();
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    private static void setConversationContext(Long conversationId, Long dataSourceId, String traceId) {
        ConversationContextHolder.clear();
        if (conversationId != null) ConversationContextHolder.setConversationId(conversationId);
        if (dataSourceId != null) ConversationContextHolder.setDataSourceId(dataSourceId);
        if (traceId != null) ConversationContextHolder.setTraceId(traceId);
    }

    public enum RejectedPolicy {
        CALLER_RUNS,
        ABORT
    }
}
