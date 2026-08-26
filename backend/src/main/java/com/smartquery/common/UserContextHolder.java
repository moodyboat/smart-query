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

    /**
     * Fail-closed access to the authenticated actor. Background system jobs must
     * use their dedicated service entry points instead of being treated as an
     * anonymous administrator.
     */
    public static UserContext require() {
        UserContext ctx = CURRENT.get();
        if (ctx == null || ctx.userId() == null) {
            throw new BusinessException(401, "用户身份已丢失，请重新登录");
        }
        return ctx;
    }

    /**
     * Bind an explicitly captured actor to the current worker thread and restore
     * the previous value when the scope closes.
     */
    public static Scope open(UserContext context) {
        UserContext previous = CURRENT.get();
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
        return new Scope(previous);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static final class Scope implements AutoCloseable {
        private final UserContext previous;
        private boolean closed;

        private Scope(UserContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public record UserContext(Long userId, String username, String role) {
    }
}
