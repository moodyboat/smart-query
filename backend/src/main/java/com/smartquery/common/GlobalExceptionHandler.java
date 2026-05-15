package com.smartquery.common;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SmartQueryError.class)
    public Result<Map<String, Object>> handleSmartQueryError(SmartQueryError ex, WebRequest request, HttpServletResponse response) {
        log.warn("[{}] {}", ex.getCode(), ex.getMessage());
        response.setStatus(ex.httpStatus());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", ex.getCode());
        detail.put("message", ex.getMessage());
        detail.put("retryable", ex.isRetryable());
        if (ex instanceof ValidationError ve) detail.put("violations", ve.getViolations());
        if (ex instanceof SecurityError se) detail.put("rule", se.getRule());
        if (ex instanceof TimeoutError te) detail.put("resource", te.getResource());
        return Result.error(ex.httpStatus(), ex.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public Result<Map<String, Object>> handleBusiness(BusinessException ex, WebRequest request, HttpServletResponse response) {
        log.warn("[BUSINESS] {}", ex.getMessage());
        response.setStatus(ex.getCode());
        return Result.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[BAD_REQUEST] {}", ex.getMessage());
        return Result.error(400, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("[CONFLICT] {}", ex.getMessage());
        return Result.error(409, ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Map<String, Object>> handleNotFound(NoResourceFoundException ex) {
        return Result.error(404, "资源不存在");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Map<String, Object>> handleUnknown(Exception ex) {
        log.error("[UNHANDLED] {}", ex.getMessage(), ex);
        return Result.error(500, "服务内部错误，请稍后重试");
    }
}
