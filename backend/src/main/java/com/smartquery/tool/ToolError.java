package com.smartquery.tool;

import com.smartquery.common.*;

/**
 * 结构化工具错误 — 替代纯 String error，支持错误分类和重试判断
 */
public record ToolError(
    ErrorCode code,
    ErrorCategory category,
    String message,
    boolean retryable,
    String detail
) {
    public enum ErrorCode {
        SQL_ERROR,
        SQL_TIMEOUT,
        SQL_SAFETY_BLOCKED,
        PYTHON_ERROR,
        PYTHON_TIMEOUT,
        LLM_ERROR,
        TOOL_ERROR,
        TOOL_NOT_FOUND,
        TOOL_TIMEOUT,
        VALIDATION_ERROR,
        DB_CONNECTION_ERROR,
        ABORT,
        UNKNOWN
    }

    public enum ErrorCategory {
        RECOVERABLE,
        NON_RECOVERABLE,
        SECURITY
    }

    public static ToolError of(ErrorCode code, String message) {
        return new ToolError(code, defaultCategory(code), message, defaultRetryable(code), null);
    }

    public static ToolError of(ErrorCode code, String message, String detail) {
        return new ToolError(code, defaultCategory(code), message, defaultRetryable(code), detail);
    }

    public static ToolError recoverable(ErrorCode code, String message) {
        return new ToolError(code, ErrorCategory.RECOVERABLE, message, true, null);
    }

    public static ToolError nonRecoverable(ErrorCode code, String message) {
        return new ToolError(code, ErrorCategory.NON_RECOVERABLE, message, false, null);
    }

    public static ToolError security(String message) {
        return new ToolError(ErrorCode.SQL_SAFETY_BLOCKED, ErrorCategory.SECURITY, message, false, null);
    }

    public static ToolError abort(String message) {
        return new ToolError(ErrorCode.ABORT, ErrorCategory.NON_RECOVERABLE, message, false, null);
    }

    private static ErrorCategory defaultCategory(ErrorCode code) {
        return switch (code) {
            case SQL_TIMEOUT, PYTHON_TIMEOUT, TOOL_TIMEOUT, DB_CONNECTION_ERROR -> ErrorCategory.RECOVERABLE;
            case SQL_SAFETY_BLOCKED -> ErrorCategory.SECURITY;
            case ABORT -> ErrorCategory.NON_RECOVERABLE;
            default -> ErrorCategory.NON_RECOVERABLE;
        };
    }

    private static boolean defaultRetryable(ErrorCode code) {
        return code == ErrorCode.SQL_TIMEOUT || code == ErrorCode.PYTHON_TIMEOUT
            || code == ErrorCode.TOOL_TIMEOUT || code == ErrorCode.DB_CONNECTION_ERROR;
    }

    /**
     * 将 ToolError 转换为对应的异常，用于 API 层抛出
     */
    public RuntimeException toException() {
        return switch (code) {
            case ABORT -> new AbortError(message);
            case SQL_TIMEOUT, PYTHON_TIMEOUT, TOOL_TIMEOUT -> new TimeoutError(code.name(), message);
            case VALIDATION_ERROR -> new ValidationError(message);
            case SQL_SAFETY_BLOCKED -> new SecurityError(code.name(), message);
            default -> new com.smartquery.common.BusinessException(message);
        };
    }
}
