package com.smartquery.common;

/**
 * 安全错误 — SQL 注入、危险操作被拦截
 */
public class SecurityError extends SmartQueryError {

    private final String rule;

    public SecurityError(String rule, String message) {
        super("SECURITY", message);
        this.rule = rule;
    }

    public String getRule() {
        return rule;
    }

    @Override
    public int httpStatus() {
        return 403;
    }
}
