package com.smartquery.datasource;

/**
 * 数据源上下文切换 — ThreadLocal 方式
 */
public class DataSourceContextHolder {

    private static final ThreadLocal<Long> CONTEXT = new ThreadLocal<>();

    public static void set(Long dataSourceId) {
        CONTEXT.set(dataSourceId);
    }

    public static Long get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
