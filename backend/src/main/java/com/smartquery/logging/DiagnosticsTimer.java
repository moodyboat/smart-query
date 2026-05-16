package com.smartquery.logging;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Diagnostic timing utility — wraps operations with timing, logs at DEBUG,
 * and tracks per-operation aggregate stats (calls, duration, min, max, avg).
 */
@Slf4j
public final class DiagnosticsTimer {

    private static final ConcurrentHashMap<String, OperationStats> STATS = new ConcurrentHashMap<>();

    private DiagnosticsTimer() {}

    public record OperationStats(
        String operation,
        long totalCalls,
        long totalDurationMs,
        long minMs,
        long maxMs,
        double avgMs
    ) {
        public OperationStats record(long durationMs) {
            long newMin = Math.min(this.minMs, durationMs);
            long newMax = Math.max(this.maxMs, durationMs);
            long newTotal = this.totalDurationMs + durationMs;
            long newCalls = this.totalCalls + 1;
            return new OperationStats(operation, newCalls, newTotal, newMin, newMax, (double) newTotal / newCalls);
        }
    }

    /**
     * Wraps a Runnable with timing and records stats.
     */
    public static void timed(String operation, Runnable runnable) {
        long start = System.currentTimeMillis();
        try {
            runnable.run();
        } finally {
            long duration = System.currentTimeMillis() - start;
            log.debug("[DIAG] {} completed in {}ms", operation, duration);
            recordStats(operation, duration);
        }
    }

    /**
     * Wraps a Supplier with timing, records stats, and returns the result.
     */
    public static <T> T timedSupply(String operation, Supplier<T> supplier) {
        long start = System.currentTimeMillis();
        try {
            return supplier.get();
        } finally {
            long duration = System.currentTimeMillis() - start;
            log.debug("[DIAG] {} completed in {}ms", operation, duration);
            recordStats(operation, duration);
        }
    }

    public static List<OperationStats> getOperationStats() {
        return List.copyOf(STATS.values());
    }

    public static void resetStats() {
        STATS.clear();
    }

    private static void recordStats(String operation, long durationMs) {
        STATS.compute(operation, (key, existing) ->
            (existing != null ? existing : new OperationStats(key, 0, 0, Long.MAX_VALUE, 0, 0))
                .record(durationMs));
    }
}
