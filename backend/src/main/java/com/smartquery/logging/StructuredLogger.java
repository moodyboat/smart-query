package com.smartquery.logging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 结构化日志 — 直译 Claude Code analytics/index.ts 的 Sink 模式
 */
@Slf4j
@Component
public class StructuredLogger {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public void logLlmRequest(String model, int messageCount, int toolCount, String requestId) {
        log.info("{} [LLM] type=request, model={}, messages={}, tools={}, requestId={}",
            LocalDateTime.now().format(FMT), model, messageCount, toolCount, requestId);
    }

    public void logLlmResponse(String model, long durationMs, int inputTokens, int outputTokens, String requestId) {
        log.info("{} [LLM] type=response, model={}, duration={}ms, tokens={}/{}, requestId={}",
            LocalDateTime.now().format(FMT), model, durationMs, inputTokens, outputTokens, requestId);
    }

    public void logToolExecution(String toolName, long durationMs, boolean success, String requestId) {
        log.info("{} [TOOL] name={}, duration={}ms, success={}, traceId={}",
            LocalDateTime.now().format(FMT), toolName, durationMs, success, requestId);
    }

    public void logSql(String sql, long durationMs, int rows, String requestId) {
        log.info("{} [SQL] duration={}ms, rows={}, traceId={}, sql={}",
            LocalDateTime.now().format(FMT), durationMs, rows, requestId,
            sql.substring(0, Math.min(sql.length(), 200)));
    }

    public void logReActStep(int step, String action, int totalTokens, double cost, String requestId) {
        log.info("{} [REACT] step={}, action={}, tokens={}, cost={}, traceId={}",
            LocalDateTime.now().format(FMT), step, action, totalTokens, String.format("%.4f", cost), requestId);
    }
}
