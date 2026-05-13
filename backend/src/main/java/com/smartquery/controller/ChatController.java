package com.smartquery.controller;

import com.smartquery.engine.QueryEngine;
import com.smartquery.engine.ReActEvent;
import com.smartquery.entity.ChatMessage;
import com.smartquery.mapper.ChatMessageMapper;
import com.smartquery.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ChatController {

    private final QueryEngine queryEngine;
    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Qualifier("asyncExecutor")
    private final Executor asyncExecutor;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatPost(
        @RequestParam Long conversationId,
        @RequestBody Map<String, String> body,
        @RequestParam(required = false) Long dataSourceId,
        @RequestParam(defaultValue = "glm-5.1") String model
    ) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().data("{\"type\":\"Error\",\"message\":\"message 不能为空\"}"));
            } catch (IOException ignored) {}
            emitter.complete();
            return emitter;
        }
        return chat(conversationId, message, dataSourceId, model);
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
        @RequestParam Long conversationId,
        @RequestParam String message,
        @RequestParam(required = false) Long dataSourceId,
        @RequestParam(defaultValue = "glm-5.1") String model
    ) {
        SseEmitter emitter = new SseEmitter(300000L);
        AtomicBoolean aborted = new AtomicBoolean(false);

        emitter.onCompletion(() -> aborted.set(true));
        emitter.onTimeout(() -> {
            aborted.set(true);
            log.warn("[SSE] connection timed out: conversation={}", conversationId);
        });
        emitter.onError(e -> {
            aborted.set(true);
            log.warn("[SSE] connection error: {}", e.getMessage());
        });

        CompletableFuture.runAsync(() -> {
            try {
                queryEngine.submitMessageStreaming(
                    conversationId, message, dataSourceId, model,
                    aborted::get,
                    event -> {
                        try {
                            Map<String, Object> data = serializeEvent(event);
                            String json = objectMapper.writeValueAsString(data);
                            emitter.send(SseEmitter.event().data(json));
                        } catch (IOException e) {
                            aborted.set(true);
                            throw new RuntimeException("SSE send failed", e);
                        }
                    }
                );
                emitter.complete();
            } catch (Exception e) {
                if (e.getCause() instanceof IOException || aborted.get()) {
                    log.debug("[SSE] client disconnected during streaming");
                } else {
                    log.error("[SSE] error: {}", e.getMessage());
                }
                emitter.completeWithError(e);
            }
        }, asyncExecutor);

        return emitter;
    }

    private Map<String, Object> serializeEvent(ReActEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", event.getClass().getSimpleName());

        if (event instanceof ReActEvent.ThinkingDelta t) {
            data.put("content", t.content());
        } else if (event instanceof ReActEvent.Thinking t) {
            data.put("content", t.content());
        } else if (event instanceof ReActEvent.SqlExecuting e) {
            data.put("sql", e.sql());
        } else if (event instanceof ReActEvent.Result e) {
            data.put("summary", e.summary());
            data.put("data", e.data());
            data.put("totalRows", e.totalRows());
            if (e.error() != null) data.put("error", e.error());
        } else if (event instanceof ReActEvent.PythonExecuting e) {
            data.put("code", e.code());
        } else if (event instanceof ReActEvent.PythonProgress e) {
            data.put("output", e.output());
            data.put("elapsedMs", e.elapsedMs());
        } else if (event instanceof ReActEvent.PythonResultEvent e) {
            data.put("stdout", e.stdout());
            data.put("stderr", e.stderr());
            data.put("exitCode", e.exitCode());
            data.put("artifacts", e.artifacts());
        } else if (event instanceof ReActEvent.ChartGenerated e) {
            data.put("chartId", e.chartId());
            data.put("title", e.title());
            data.put("chartType", e.chartType());
            data.put("echartsOption", e.echartsOption());
        } else if (event instanceof ReActEvent.DashboardGenerated e) {
            data.put("dashboardId", e.dashboardId());
            data.put("title", e.title());
            data.put("layout", e.layout());
            data.put("chartIds", e.chartIds());
        } else if (event instanceof ReActEvent.ReportGenerated e) {
            data.put("reportId", e.reportId());
            data.put("title", e.title());
            data.put("sectionCount", e.sectionCount());
            if (e.sections() != null && !e.sections().isEmpty()) {
                data.put("sections", e.sections());
            }
            if (e.conclusion() != null && !e.conclusion().isBlank()) {
                data.put("conclusion", e.conclusion());
            }
        } else if (event instanceof ReActEvent.FilterWidgetsGenerated e) {
            data.put("widgetsJson", e.widgetsJson());
            try {
                Map<String, Object> parsed = objectMapper.readValue(e.widgetsJson(), Map.class);
                data.put("widgets", parsed.get("widgets"));
                data.put("bindings", parsed.get("bindings"));
                data.put("baseSql", parsed.get("baseSql"));
                data.put("targetType", parsed.get("targetType"));
                data.put("targetId", parsed.get("targetId"));
            } catch (Exception ex) {
                log.warn("[SSE] Failed to parse FilterWidgetsGenerated: {}", ex.getMessage());
            }
        } else if (event instanceof ReActEvent.Done d) {
            data.put("totalSteps", d.totalSteps());
            data.put("totalTokens", d.totalTokens());
            data.put("cost", d.cost());
        } else if (event instanceof ReActEvent.Error e) {
            data.put("message", e.message());
            data.put("detail", e.detail());
        }

        return data;
    }

    @GetMapping("/chat/history/{conversationId}")
    public List<ChatMessage> getHistory(@PathVariable Long conversationId) {
        return chatMessageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getCreatedAt));
    }
}
