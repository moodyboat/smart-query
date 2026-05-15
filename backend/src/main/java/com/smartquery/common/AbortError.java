package com.smartquery.common;

/**
 * 用户中断 — ReAct 循环被 abortChecker 检测到
 */
public class AbortError extends SmartQueryError {

    public AbortError(String message) {
        super("ABORT", message);
    }

    @Override
    public boolean isRetryable() {
        return false;
    }

    @Override
    public int httpStatus() {
        return 499; // Nginx-style client closed request
    }
}
