package com.smartquery.common;

import java.util.List;

/**
 * 校验错误 — 输入参数不合法
 */
public class ValidationError extends SmartQueryError {

    private final List<String> violations;

    public ValidationError(String message) {
        super("VALIDATION", message);
        this.violations = List.of(message);
    }

    public ValidationError(List<String> violations) {
        super("VALIDATION", String.join("; ", violations));
        this.violations = violations;
    }

    public List<String> getViolations() {
        return violations;
    }

    @Override
    public int httpStatus() {
        return 422;
    }
}
