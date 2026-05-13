package com.smartquery.logging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全链路追踪 — 适配 Claude Code sessionTracing.ts
 */
@Slf4j
@Component
public class QueryTracer {

    private final Map<String, TraceContext> traces = new ConcurrentHashMap<>();

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
            System.currentTimeMillis(), new ArrayList<>()));
        return traceId;
    }

    public String startSpan(String traceId, String type, String name) {
        TraceContext ctx = traces.get(traceId);
        if (ctx == null) return "unknown";
        String spanId = type + "-" + ctx.spans().size();
        ctx.spans().add(new Span(spanId, type, name, System.currentTimeMillis(),
            null, "running", Map.of()));
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
                break;
            }
        }
    }

    public TraceContext getTrace(String traceId) {
        return traces.get(traceId);
    }
}
