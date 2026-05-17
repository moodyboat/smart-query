package com.smartquery.logging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全链路追踪 — 适配 Claude Code sessionTracing.ts
 *
 * <p>改进: spans 同步写入 JSONL (通过 ConversationEventLogger)，支持持久化回溯
 */
@Slf4j
@Component
public class QueryTracer {

    private final ConversationEventLogger eventLogger;
    private final Map<String, TraceContext> traces = new ConcurrentHashMap<>();

    @Value("${tracer.question-truncation:500}")
    private int questionTruncation;

    public QueryTracer(ConversationEventLogger eventLogger) {
        this.eventLogger = eventLogger;
    }

    public record TraceContext(
        String traceId,
        Long conversationId,
        String question,
        long startTimeMs,
        List<Span> spans
    ) {}

    public record Span(
        String spanId,
        String type,
        String name,
        long startTimeMs,
        Long endTimeMs,
        String status,
        Map<String, Object> metadata
    ) {}

    public String startTrace(Long conversationId, String question) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        traces.put(traceId, new TraceContext(traceId, conversationId, question,
            System.currentTimeMillis(), Collections.synchronizedList(new ArrayList<>())));

        // JSONL: 记录 trace 开始
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question.substring(0, Math.min(question.length(), questionTruncation)));
        eventLogger.logEvent(conversationId, traceId, "trace_start", payload);

        return traceId;
    }

    public String startSpan(String traceId, String type, String name) {
        TraceContext ctx = traces.get(traceId);
        if (ctx == null) return "unknown";
        String spanId = type + "-" + ctx.spans().size();
        ctx.spans().add(new Span(spanId, type, name, System.currentTimeMillis(),
            null, "running", Map.of()));

        // JSONL: 记录 span 开始
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("spanId", spanId);
        payload.put("type", type);
        payload.put("name", name);
        eventLogger.logEvent(ctx.conversationId(), traceId, "span_start", payload);

        return spanId;
    }

    public void endSpan(String traceId, String spanId, String status, Map<String, Object> metadata) {
        TraceContext ctx = traces.get(traceId);
        if (ctx == null) return;
        for (int i = 0; i < ctx.spans().size(); i++) {
            Span s = ctx.spans().get(i);
            if (s.spanId().equals(spanId)) {
                ctx.spans().set(i, new Span(s.spanId(), s.type(), s.name(),
                    s.startTimeMs(), System.currentTimeMillis(), status, metadata));

                // JSONL: 记录 span 结束
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("spanId", spanId);
                payload.put("status", status);
                payload.put("durationMs", System.currentTimeMillis() - s.startTimeMs());
                if (metadata != null && !metadata.isEmpty()) {
                    payload.put("metadata", metadata);
                }
                eventLogger.logEvent(ctx.conversationId(), traceId, "span_end", payload);
                break;
            }
        }
    }

    public TraceContext getTrace(String traceId) {
        return traces.get(traceId);
    }

    /**
     * 清理已完成追踪，防止内存泄漏
     */
    public void cleanupTrace(String traceId) {
        traces.remove(traceId);
    }

    /**
     * 清理指定对话的所有追踪
     */
    public void cleanupConversation(Long conversationId) {
        traces.entrySet().removeIf(e ->
            e.getValue().conversationId() != null
            && e.getValue().conversationId().equals(conversationId));
    }
}
