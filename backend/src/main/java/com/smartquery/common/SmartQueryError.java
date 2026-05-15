package com.smartquery.common;

import lombok.Getter;

/**
 * 智能问数基础异常 — 所有业务异常的父类
 *
 * <p>子类按语义划分：AbortError(用户中断)、TimeoutError(超时)、
 * ValidationError(校验)、SecurityError(安全)、RateLimitError(限流)
 */
@Getter
public class SmartQueryError extends RuntimeException {

    private final String code;

    public SmartQueryError(String code, String message) {
        super(message);
        this.code = code;
    }

    public SmartQueryError(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 用于 JSON 序列化: { code, message, retryable } */
    public boolean isRetryable() {
        return false;
    }

    public int httpStatus() {
        return 400;
    }
}
