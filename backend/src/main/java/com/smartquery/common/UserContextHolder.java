package com.smartquery.common;

/**
 * 当前登录用户上下文（请求线程内有效），由 AuthInterceptor 在鉴权通过后写入，
 * ThreadLocalCleanupFilter 在请求结束后清理。
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserContext> CURRENT = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(UserContext ctx) {
        CURRENT.set(ctx);
    }

    public static UserContext get() {
        return CURRENT.get();
    }

    public static Long getUserId() {
        UserContext ctx = CURRENT.get();
        return ctx == null ? null : ctx.userId();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record UserContext(Long userId, String username, String role) {
    }
}
