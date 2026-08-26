package com.smartquery.controller;

import com.smartquery.common.RateLimiter;
import com.smartquery.common.UserContextHolder;
import com.smartquery.engine.ConversationContextHolder;
import com.smartquery.engine.QueryEngine;
import com.smartquery.engine.ReActEvent;
import com.smartquery.logging.ConversationStatsService;
import com.smartquery.service.ScenarioAuthService;
import com.smartquery.service.ResourceAccessService;
import com.smartquery.tool.ToolRegistry;
import com.smartquery.entity.ChatMessage;
import com.smartquery.logging.ConversationEventLogger;
import com.smartquery.logging.DiagnosticsTimer;
import com.smartquery.prompt.SchemaContextBuilder;
import com.smartquery.prompt.ToolPromptLoader;
import com.smartquery.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
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
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationEventLogger eventLogger;
    private final ConversationStatsService statsService;
    private final ToolRegistry toolRegistry;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Qualifier("asyncExecutor")
    private final Executor asyncExecutor;

    private final ConcurrentHashMap<Long, AtomicBoolean> activeConversations = new ConcurrentHashMap<>();
    private final ConversationContextHolder.SessionManager sessionManager;
    private final RateLimiter rateLimiter;
    private final ToolPromptLoader toolPromptLoader;
    private final SchemaContextBuilder schemaContextBuilder;
    private final Environment environment;
    private final ScenarioAuthService scenarioAuthService;
    private final com.smartquery.service.ScenarioService scenarioService;
    private final ResourceAccessService resourceAccess;

    @org.springframework.beans.factory.annotation.Value("${smart-query.llm.default-model:glm-5.1}")
    private String defaultModel;

    @org.springframework.beans.factory.annotation.Value("${smart-query.rate-limit.chat-per-minute:30}")
    private int chatRateLimit;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatPost(
        @RequestParam Long conversationId,
        @RequestBody Map<String, String> body,
        @RequestParam(required = false) Long dataSourceId,
        @RequestParam(defaultValue = "") String model,
        @RequestParam(defaultValue = "") String scenario
    ) {
        var rateResult = rateLimiter.tryAcquireWithInfo("chat:" + conversationId, chatRateLimit);
        if (!rateResult.allowed()) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().data(
                    "{\"type\":\"Error\",\"message\":\"请求过于频繁，请稍后重试\",\"retryAfter\":60,\"queueInfo\":\"当前分钟配额已用完，请等待约60秒\"}"));
            } catch (IOException ignored) {}
            emitter.complete();
            return emitter;
        }

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().data("{\"type\":\"Error\",\"message\":\"message 不能为空\"}"));
            } catch (IOException ignored) {}
            emitter.complete();
            return emitter;
        }
        return chat(conversationId, message, dataSourceId, model.isBlank() ? defaultModel : model, scenario);
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
        @RequestParam Long conversationId,
        @RequestParam String message,
        @RequestParam(required = false) Long dataSourceId,
        @RequestParam(defaultValue = "") String model,
        @RequestParam(defaultValue = "") String scenario
    ) {
        // Authenticate the resource before reading its datasource, taking its
        // per-conversation lock, or starting an asynchronous tool loop.
        var conversation = resourceAccess.requireConversation(conversationId);
        // 场景授权校验：防止前端绕过传未授权的 scenarioCode 越权使用敏感业务提示词
        if (scenario != null && !scenario.isBlank()) {
            UserContextHolder.UserContext ctx = UserContextHolder.get();
            String role = ctx == null ? null : ctx.role();
            if (!scenarioAuthService.canAccess(role, scenario)) {
                SseEmitter reject = new SseEmitter();
                try {
                    reject.send(SseEmitter.event().data(
                        "{\"type\":\"Error\",\"message\":\"无权使用该场景\",\"category\":\"NON_RECOVERABLE\",\"retryable\":false}"));
                } catch (IOException ignored) {}
                reject.complete();
                return reject;
            }
        }
        // Per-conversation lock: reject concurrent requests to the same conversation
        String resolvedModel = model.isBlank() ? defaultModel : model;
        // 数据源解析优先级：场景绑定 > 请求参数 > 会话兜底
        // 场景绑定了数据源时强制覆盖，前端禁用切换器只是 UX，这里是真正的安全边界
        Long dsId = null;
        if (scenario != null && !scenario.isBlank()) {
            try {
                com.smartquery.entity.Scenario s = scenarioService.getByCode(scenario);
                if (s != null && s.getDataSourceId() != null) {
                    dsId = s.getDataSourceId();
                }
            } catch (Exception e) {
                log.debug("[CHAT] failed to resolve scenario datasource: {}", e.getMessage());
            }
        }
        if (dsId == null) {
            dsId = dataSourceId;
        }
        if (dsId == null) {
            dsId = conversation.getDataSourceId();
        }
        final Long finalDataSourceId = dsId;
        AtomicBoolean active = activeConversations.computeIfAbsent(conversationId, k -> new AtomicBoolean(false));
        if (!active.compareAndSet(false, true)) {
            SseEmitter reject = new SseEmitter();
            try {
                reject.send(SseEmitter.event().data(
                    "{\"type\":\"Error\",\"message\":\"该对话正在处理中，请等待完成后再发送\",\"category\":\"NON_RECOVERABLE\",\"retryable\":false}"));
            } catch (IOException ignored) {}
            reject.complete();
            return reject;
        }

        SseEmitter emitter = new SseEmitter(
            Long.parseLong(environment.getProperty("smart-query.sse.timeout-ms", "300000")));
        AtomicBoolean aborted = new AtomicBoolean(false);

        Runnable releaseLock = () -> {
            if (aborted.compareAndSet(false, true)) {
                active.set(false);
                activeConversations.remove(conversationId, active);
            }
        };

        emitter.onCompletion(releaseLock);
        emitter.onTimeout(() -> {
            releaseLock.run();
            log.warn("[SSE] connection timed out: conversation={}", conversationId);
        });
        emitter.onError(e -> {
            releaseLock.run();
            log.warn("[SSE] connection error: {}", e.getMessage());
        });

        UserContextHolder.UserContext requestCtx = UserContextHolder.require();
        CompletableFuture.runAsync(() -> {
            try (UserContextHolder.Scope ignored = UserContextHolder.open(requestCtx)) {
                ConversationContextHolder.setConversationId(conversationId);
                ConversationContextHolder.setDataSourceId(finalDataSourceId);
                ConversationContextHolder.setTraceId(UUID.randomUUID().toString().substring(0, 8));
                sessionManager.register(conversationId, finalDataSourceId, requestCtx.userId().toString());
                try {
                    queryEngine.submitMessageStreaming(
                        conversationId, message, finalDataSourceId, resolvedModel, scenario, null,
                        aborted::get,
                        event -> {
                            if (aborted.get()) return;
                            try {
                                Map<String, Object> data = serializeEvent(event);
                                String json = objectMapper.writeValueAsString(data);
                                emitter.send(SseEmitter.event().data(json));
                            } catch (Exception e) {
                                aborted.set(true);
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
                } finally {
                    sessionManager.unregister(conversationId);
                    ConversationContextHolder.clear();
                }
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
        } else if (event instanceof ReActEvent.MiningModelEvent e) {
            data.put("action", e.action());
            if (e.modelId() != null) data.put("modelId", e.modelId());
            if (e.modelName() != null) data.put("modelName", e.modelName());
            if (e.algorithm() != null) data.put("algorithm", e.algorithm());
            data.put("success", e.success());
            data.put("message", e.message());
            if (e.details() != null && !e.details().isEmpty()) data.put("details", e.details());
        } else if (event instanceof ReActEvent.Done d) {
            data.put("totalSteps", d.totalSteps());
            data.put("totalTokens", d.totalTokens());
            data.put("cost", d.cost());
        } else if (event instanceof ReActEvent.Error e) {
            data.put("message", e.message());
            data.put("detail", e.detail());
            data.put("category", "NON_RECOVERABLE");
            data.put("retryable", false);
        }

        return data;
    }

    @GetMapping("/chat/history/{conversationId}")
    public List<ChatMessage> getHistory(@PathVariable Long conversationId) {
        resourceAccess.requireConversation(conversationId);
        return chatMessageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getCreatedAt));
    }

    @GetMapping("/traces/{conversationId}")
    public Map<String, Object> getTraces(
        @PathVariable Long conversationId,
        @RequestParam(required = false) String traceId,
        @RequestParam(defaultValue = "100") int limit,
        @RequestParam(defaultValue = "false") boolean tail
    ) {
        resourceAccess.requireConversation(conversationId);
        List<Map<String, Object>> events;
        if (traceId != null && !traceId.isBlank()) {
            events = eventLogger.getEventsByTraceId(conversationId, traceId);
        } else {
            events = eventLogger.getConversationTrace(conversationId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversationId);
        result.put("totalEvents", events.size());
        result.put("events", events);
        return result;
    }

    @GetMapping("/admin/sessions")
    public Map<String, Object> getActiveSessions() {
        resourceAccess.requireAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeConversations", sessionManager.activeCount());
        result.put("sessions", sessionManager.getActiveSessions());
        return result;
    }

    @GetMapping("/admin/stats")
    public Map<String, Object> getStats() {
        resourceAccess.requireAdmin();
        return statsService.getStats();
    }

    @GetMapping("/admin/tools")
    public Map<String, Object> getTools() {
        resourceAccess.requireAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", toolRegistry.getToolInfoList());
        result.put("total", toolRegistry.getAllTools().size());
        return result;
    }

    @PostMapping("/admin/tools/health-check")
    public Map<String, Object> healthCheckTools() {
        resourceAccess.requireAdmin();
        return toolRegistry.healthCheckAll();
    }

    @GetMapping("/admin/logs")
    public Map<String, Object> getLogFiles() {
        resourceAccess.requireAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", eventLogger.getLogFiles());
        return result;
    }

    @PostMapping("/admin/logs/maintain")
    public Map<String, Object> maintainLogs() {
        resourceAccess.requireAdmin();
        return eventLogger.performLogMaintenance();
    }

    @GetMapping("/admin/diagnostics")
    public Map<String, Object> getDiagnostics() {
        resourceAccess.requireAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operations", DiagnosticsTimer.getOperationStats());
        result.put("promptCache", Map.of("entries", toolPromptLoader.getCacheStats()));
        result.put("schemaCache", schemaContextBuilder.getCacheStats());
        result.put("activeSessions", sessionManager.activeCount());
        result.put("activeConversations", activeConversations.size());

        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("maxMb", runtime.maxMemory() / 1024 / 1024);
        mem.put("totalMb", runtime.totalMemory() / 1024 / 1024);
        mem.put("freeMb", runtime.freeMemory() / 1024 / 1024);
        mem.put("usedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        result.put("jvmMemory", mem);
        result.put("threads", Thread.activeCount());
        return result;
    }

    @GetMapping("/admin/prompts")
    public Map<String, Object> getPromptDebug() {
        resourceAccess.requireAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("promptCacheEntries", toolPromptLoader.getCacheStats());
        result.put("schemaCache", schemaContextBuilder.getCacheStats());
        return result;
    }
}
