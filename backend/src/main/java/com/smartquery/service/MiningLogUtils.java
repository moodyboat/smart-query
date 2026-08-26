package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
/**
 * 挖掘/预测/Pipeline 三处共用的日志工具。
 * 结构化结果使用 MiningRuntimeClient 的文件协议，不再从 stdout 解析。
 */
public final class MiningLogUtils {

    private MiningLogUtils() {}

    /**
     * 截断日志：去掉控制字符 + 截断到 maxLen。
     * Pipeline 路径需要额外脱敏凭据，调用方传 sanitize=true。
     */
    public static String truncateLog(String log, int maxLen, boolean sanitize) {
        if (log == null) return null;
        String cleaned = log.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "");
        if (sanitize) {
            cleaned = cleaned.replaceAll("mysql\\+pymysql://([^:]+):[^@]+@", "mysql+pymysql://$1:***@")
                             .replaceAll("postgresql\\+psycopg2://([^:]+):[^@]+@", "postgresql+psycopg2://$1:***@")
                             .replaceAll("dm\\+dmPython://([^:]+):[^@]+@", "dm+dmPython://$1:***@");
        }
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen) + "\n... (truncated)";
    }

    /** 序列化为 JSON；失败时降级到 String.valueOf，绝不抛异常。 */
    public static String toJson(Object obj, ObjectMapper objectMapper) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
