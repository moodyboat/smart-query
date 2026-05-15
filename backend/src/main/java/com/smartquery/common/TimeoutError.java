package com.smartquery.common;

/**
 * 超时 — SQL/Python/工具执行超时
 */
public class TimeoutError extends SmartQueryError {

    private final String resource;

    public TimeoutError(String resource, String message) {
        super("TIMEOUT", message);
        this.resource = resource;
    }

    public String getResource() {
        return resource;
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

    @Override
    public int httpStatus() {
        return 408;
    }
}
