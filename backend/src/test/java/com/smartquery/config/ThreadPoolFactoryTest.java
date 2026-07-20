package com.smartquery.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ThreadPoolFactory — 统一构建 ThreadPoolTaskExecutor 的工厂测试。
 * 覆盖 WebConfig.asyncExecutor / WebConfig.llmExecutor / MiningThreadPoolConfig.miningExecutor
 * 三处合并前的同形实现。
 */
class ThreadPoolFactoryTest {

    @Test
    void build_returnsNonNullExecutor() {
        Executor exec = ThreadPoolFactory.build("test", 1, 2, 10, ThreadPoolFactory.RejectedPolicy.ABORT);
        assertNotNull(exec);
    }

    @Test
    void build_executesSubmittedTask() throws Exception {
        Executor exec = ThreadPoolFactory.build("test", 1, 2, 10, ThreadPoolFactory.RejectedPolicy.CALLER_RUNS);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        exec.execute(() -> {
            ran.set(true);
            latch.countDown();
        });
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertTrue(ran.get());
    }

    @Test
    void build_callerRunsPolicy_runsInCallerThreadWhenQueueFull() throws Exception {
        // 队列容量 0 + 单线程核心：第一个任务占线程，第二个任务必须由调用线程执行
        Executor exec = ThreadPoolFactory.build("test", 1, 1, 0, ThreadPoolFactory.RejectedPolicy.CALLER_RUNS);
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch startedLatch = new CountDownLatch(1);
        exec.execute(() -> {
            startedLatch.countDown();
            try { blockLatch.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(startedLatch.await(2, TimeUnit.SECONDS));

        String callerName = Thread.currentThread().getName();
        AtomicReference<String> runnerName = new AtomicReference<>("");
        exec.execute(() -> runnerName.set(Thread.currentThread().getName()));

        assertEquals(callerName, runnerName.get());
        blockLatch.countDown();
    }

    @Test
    void build_abortPolicy_throwsOnQueueFull() throws Exception {
        Executor exec = ThreadPoolFactory.build("test", 1, 1, 0, ThreadPoolFactory.RejectedPolicy.ABORT);
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch startedLatch = new CountDownLatch(1);
        exec.execute(() -> {
            startedLatch.countDown();
            try { blockLatch.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(startedLatch.await(2, TimeUnit.SECONDS));

        assertThrows(RejectedExecutionException.class, () -> exec.execute(() -> {}));
        blockLatch.countDown();
    }
}
