package com.smartquery.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class RateLimiter {

    @Value("${smart-query.rate-limit.cleanup-interval-ms:120000}")
    private long cleanupIntervalMs;

    @Value("${smart-query.rate-limit.bucket-expiry-ms:120000}")
    private long bucketExpiryMs;

    @Value("${smart-query.rate-limit.window-ms:60000}")
    private long windowMs;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @org.springframework.scheduling.annotation.Scheduled(fixedRateString = "${smart-query.rate-limit.cleanup-interval-ms:120000}")
    public void cleanup() {
        long now = System.currentTimeMillis();
        int before = buckets.size();
        buckets.entrySet().removeIf(e -> now - e.getValue().windowStart > bucketExpiryMs);
        int removed = before - buckets.size();
        if (removed > 0) {
            log.debug("[RATE] Cleaned up {} expired rate limiter buckets", removed);
        }
    }

    public record AcquireResult(boolean allowed, int remaining, int max) {}

    public AcquireResult tryAcquireTiered(String userId, String operation, int globalLimit, int userLimit, int operationLimit) {
        AcquireResult globalResult = tryAcquireWithInfo("global", globalLimit);
        if (!globalResult.allowed()) {
            log.warn("[RATE] Global limit reached: remaining={}/{}", globalResult.remaining(), globalLimit);
            return globalResult;
        }
        AcquireResult userResult = null;
        if (userId != null && !userId.isBlank()) {
            userResult = tryAcquireWithInfo("user:" + userId, userLimit);
            if (!userResult.allowed()) {
                rollbackAcquire("global");
                log.warn("[RATE] User {} limit reached: remaining={}/{}", userId, userResult.remaining(), userLimit);
                return userResult;
            }
        }
        AcquireResult opResult = null;
        if (operation != null && !operation.isBlank()) {
            opResult = tryAcquireWithInfo("op:" + userId + ":" + operation, operationLimit);
            if (!opResult.allowed()) {
                rollbackAcquire("global");
                if (userResult != null) rollbackAcquire("user:" + userId);
                log.warn("[RATE] Operation {} for user {}: remaining={}/{}", operation, userId, opResult.remaining(), operationLimit);
                return opResult;
            }
        }
        int minRemaining = globalResult.remaining();
        if (userResult != null) minRemaining = Math.min(minRemaining, userResult.remaining());
        if (opResult != null) minRemaining = Math.min(minRemaining, opResult.remaining());
        return new AcquireResult(true, minRemaining, globalLimit);
    }

    public AcquireResult tryAcquireWithInfo(String key, int maxPerMinute) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(maxPerMinute, windowMs));
        return bucket.tryAcquireWithInfo();
    }

    public boolean tryAcquire(String key, int maxPerMinute) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(maxPerMinute, windowMs));
        return bucket.tryAcquire();
    }

    private void rollbackAcquire(String key) {
        Bucket bucket = buckets.get(key);
        if (bucket != null) bucket.rollback();
    }

    private static class Bucket {
        final int maxPerMinute;
        final long windowMs;
        volatile long windowStart;
        final AtomicInteger count;

        Bucket(int maxPerMinute, long windowMs) {
            this.maxPerMinute = maxPerMinute;
            this.windowMs = windowMs;
            this.windowStart = System.currentTimeMillis();
            this.count = new AtomicInteger(0);
        }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if (now - windowStart > windowMs) {
                windowStart = now;
                count.set(0);
            }
            return count.incrementAndGet() <= maxPerMinute;
        }

        synchronized AcquireResult tryAcquireWithInfo() {
            long now = System.currentTimeMillis();
            if (now - windowStart > windowMs) {
                windowStart = now;
                count.set(0);
            }
            int current = count.incrementAndGet();
            boolean allowed = current <= maxPerMinute;
            return new AcquireResult(allowed, Math.max(0, maxPerMinute - current), maxPerMinute);
        }

        synchronized void rollback() {
            if (count.get() > 0) count.decrementAndGet();
        }
    }
}
