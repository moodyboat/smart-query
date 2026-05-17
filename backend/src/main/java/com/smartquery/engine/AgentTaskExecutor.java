package com.smartquery.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 子任务执行器 — 在独立线程中运行 ReAct 循环，产出 AgentResult
 *
 * <p>限制并发数: 最多 N 个并行子任务，超出排队等待
 */
@Slf4j
@Component
public class AgentTaskExecutor {

    private final ReActEngine reActEngine;
    private ExecutorService executor;
    private Semaphore concurrencyLimiter;

    @Value("${agent-task.max-concurrency:3}")
    private int maxConcurrency;

    @Value("${agent-task.timeout-minutes:10}")
    private int timeoutMinutes;

    @Value("${agent-task.shutdown-timeout-seconds:30}")
    private int shutdownTimeoutSeconds;

    public AgentTaskExecutor(ReActEngine reActEngine) {
        this.reActEngine = reActEngine;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        this.concurrencyLimiter = new Semaphore(maxConcurrency);
        this.executor = Executors.newFixedThreadPool(maxConcurrency, r -> {
            Thread t = new Thread(r, "agent-task");
            t.setDaemon(true);
            return t;
        });
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 执行单个子任务，阻塞等待结果
     */
    public AgentResult execute(AgentTask task) {
        return execute(task, timeoutMinutes);
    }

    /**
     * 执行单个子任务，指定超时
     */
    public AgentResult execute(AgentTask task, int timeoutMinutes) {
        int queueTimeout = Math.max(1, timeoutMinutes / 2);
        boolean acquired;
        try {
            acquired = concurrencyLimiter.tryAcquire(queueTimeout, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AgentResult.failure(task.taskId(), "任务等待被中断", 0);
        }
        if (!acquired) {
            return AgentResult.failure(task.taskId(),
                "子任务排队超时（等待超过 " + queueTimeout + " 分钟）", 0);
        }

        Future<AgentResult> future = executor.submit(() -> runTask(task));
        try {
            return future.get(timeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            future.cancel(true);
            return AgentResult.failure(task.taskId(), "子任务执行超时", timeoutMinutes * 60_000L);
        } catch (Exception e) {
            return AgentResult.failure(task.taskId(), e.getMessage(), 0);
        } finally {
            concurrencyLimiter.release();
        }
    }

    /**
     * 并行执行多个子任务，等待全部完成
     */
    public List<AgentResult> executeAll(List<AgentTask> tasks) {
        return executeAll(tasks, null);
    }

    /**
     * 并行执行多个子任务，通过事件消费者实时上报进度
     */
    public List<AgentResult> executeAll(List<AgentTask> tasks, Consumer<ReActEvent> progressConsumer) {
        int queueTimeout = Math.max(1, timeoutMinutes / 2);
        List<Future<AgentResult>> futures = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            final AgentTask task = tasks.get(i);
            futures.add(executor.submit(() -> {
                boolean acquired = false;
                try {
                    acquired = concurrencyLimiter.tryAcquire(queueTimeout, TimeUnit.MINUTES);
                    if (!acquired) {
                        return AgentResult.failure(task.taskId(), "排队超时", 0);
                    }
                    return runTask(task, progressConsumer);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return AgentResult.failure(task.taskId(), "被中断", 0);
                } finally {
                    if (acquired) concurrencyLimiter.release();
                }
            }));
        }

        List<AgentResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get(timeoutMinutes, TimeUnit.MINUTES));
            } catch (Exception e) {
                String taskId = i < tasks.size() ? tasks.get(i).taskId() : "unknown";
                results.add(AgentResult.failure(taskId, e.getMessage(), 0));
            }
        }
        return results;
    }

    private AgentResult runTask(AgentTask task) {
        return runTask(task, null);
    }

    private AgentResult runTask(AgentTask task, Consumer<ReActEvent> progressConsumer) {
        long start = System.currentTimeMillis();
        task.setStatus(TaskState.RUNNING);
        log.info("[AGENT-TASK] Starting task '{}' with {} tools", task.taskId(), task.toolNames().size());

        try {
            String model = task.model() != null ? task.model() : "default";
            List<Map<String, Object>> emptyHistory = List.of();

            // Set context for sub-task thread — ConversationContextHolder + DataSourceContextHolder
            Object convId = task.context().get("conversationId");
            if (convId instanceof Long cid) {
                ConversationContextHolder.setConversationId(cid);
            }
            if (task.dataSourceId() != null) {
                com.smartquery.datasource.DataSourceContextHolder.set(task.dataSourceId());
            }

            List<ReActEvent> events = reActEngine.runReActLoop(
                model,
                task.dataSourceId(),
                task.prompt(),
                emptyHistory,
                () -> false
            );

            StringBuilder output = new StringBuilder();
            int tokens = 0;

            for (ReActEvent event : events) {
                if (event instanceof ReActEvent.Done d) {
                    tokens = d.totalTokens();
                    if (d.totalSteps() > 0) output.append("完成，共 ").append(d.totalSteps()).append(" 步");
                }
            }

            if (output.isEmpty() && !events.isEmpty()) {
                output.append("子任务执行完毕，产生 ").append(events.size()).append(" 个事件");
            }

            long duration = System.currentTimeMillis() - start;
            task.setStatus(TaskState.COMPLETED);
            log.info("[AGENT-TASK] Task '{}' completed in {}ms, {} tokens", task.taskId(), duration, tokens);

            return AgentResult.success(task.taskId(), output.toString(), Map.of(), duration, tokens);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            task.setStatus(TaskState.FAILED);
            log.error("[AGENT-TASK] Task '{}' failed: {}", task.taskId(), e.getMessage());
            return AgentResult.failure(task.taskId(), e.getMessage(), duration);
        } finally {
            ConversationContextHolder.clear();
            com.smartquery.datasource.DataSourceContextHolder.clear();
        }
    }

    public int availableSlots() {
        return concurrencyLimiter.availablePermits();
    }
}
