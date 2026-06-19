package com.smartquery.common;

/**
 * 认证异常（HTTP 401），由 GlobalExceptionHandler 统一处理。
 */
public class AuthenticationException extends BusinessException {

    public AuthenticationException(String message) {
        super(401, message);
    }
}
