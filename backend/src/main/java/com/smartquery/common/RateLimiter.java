package com.smartquery.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(String key, int maxPerMinute) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(maxPerMinute));
        return bucket.tryAcquire();
    }

    /**
     * 尝试获取令牌，返回剩余容量信息
     */
    public AcquireResult tryAcquireWithInfo(String key, int maxPerMinute) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(maxPerMinute));
        return bucket.tryAcquireWithInfo();
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(e -> now - e.getValue().windowStart > 120_000);
    }

    public record AcquireResult(boolean allowed, int remaining, int max) {}

    private static class Bucket {
        final int maxPerMinute;
        volatile long windowStart;
        final AtomicInteger count;

        Bucket(int maxPerMinute) {
            this.maxPerMinute = maxPerMinute;
            this.windowStart = System.currentTimeMillis();
            this.count = new AtomicInteger(0);
        }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if (now - windowStart > 60_000) {
                windowStart = now;
                count.set(0);
            }
            return count.incrementAndGet() <= maxPerMinute;
        }

        synchronized AcquireResult tryAcquireWithInfo() {
            long now = System.currentTimeMillis();
            if (now - windowStart > 60_000) {
                windowStart = now;
                count.set(0);
            }
            int current = count.incrementAndGet();
            boolean allowed = current <= maxPerMinute;
            return new AcquireResult(allowed, Math.max(0, maxPerMinute - current), maxPerMinute);
        }
    }
}
