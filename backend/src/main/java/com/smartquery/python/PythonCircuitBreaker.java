package com.smartquery.python;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class PythonCircuitBreaker {

    @Value("${smart-query.python.circuit-breaker.failure-threshold:5}")
    private int failureThreshold;

    @Value("${smart-query.python.circuit-breaker.recovery-timeout-ms:60000}")
    private long recoveryTimeoutMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private volatile State state = State.CLOSED;

    enum State { CLOSED, OPEN, HALF_OPEN }

    public synchronized boolean allowExecution() {
        if (state == State.CLOSED) return true;
        if (state == State.OPEN) {
            long elapsed = System.currentTimeMillis() - lastFailureTime.get();
            if (elapsed >= recoveryTimeoutMs) {
                state = State.HALF_OPEN;
                log.info("[CIRCUIT-BREAKER] transitioning to HALF_OPEN after {}ms", elapsed);
                return true;
            }
            return false;
        }
        return true;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures.set(0);
        if (state != State.CLOSED) {
            state = State.CLOSED;
            log.info("[CIRCUIT-BREAKER] recovered, state → CLOSED");
        }
    }

    public synchronized void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        if (failures >= failureThreshold && state != State.OPEN) {
            state = State.OPEN;
            log.warn("[CIRCUIT-BREAKER] threshold reached ({}/{}), state → OPEN", failures, failureThreshold);
        }
    }

    public String getState() {
        return state.name();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
