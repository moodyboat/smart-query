package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 挖掘/预测/Pipeline 三处共用的日志与结果解析工具。
 * <p>抽离以避免 MiningService / MiningPredictionService / PipelineService 各自维护同份实现导致漂移。
 */
@Slf4j
public final class MiningLogUtils {

    private MiningLogUtils() {}

    /**
     * 从 Python stdout 中解析形如 [MARKER]{json} 的结果行。
     * 找到第一个包含 marker 的行，尝试解析 marker 之后的 JSON；解析失败返回空 Map。
     *
     * @param logTag 日志前缀（如 "MINING"/"PREDICT"/"PIPELINE"），用于失败时定位日志
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseResultMarker(String stdout, String marker, String logTag, ObjectMapper objectMapper) {
        Map<String, Object> result = new HashMap<>();
        if (stdout == null) return result;
        for (String line : stdout.split("\n")) {
            if (line.contains(marker)) {
                try {
                    String json = line.substring(line.indexOf(marker) + marker.length()).trim();
                    result = objectMapper.readValue(json, Map.class);
                } catch (Exception e) {
                    log.warn("[{}] Failed to parse marker '{}': {}", logTag, marker, e.getMessage());
                }
                break;
            }
        }
        return result;
    }

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
