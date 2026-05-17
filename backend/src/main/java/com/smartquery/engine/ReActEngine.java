package com.smartquery.engine;

import com.smartquery.llm.LlmChunk;
import com.smartquery.llm.LlmService;
import com.smartquery.prompt.QueryContextAssembler;
import com.smartquery.tool.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * ReAct 推理引擎 — 直译 Claude Code query.ts 的 while(true) 核心循环
 *
 * <p>支持两种模式:
 * <ul>
 *   <li>runReActLoop() — 收集所有事件后返回（向后兼容）</li>
 *   <li>runReActLoopStreaming() — 实时回调每个事件（推荐，用于 SSE 流式输出）</li>
 * </ul>
 */
@Slf4j
@Component
public class ReActEngine {

    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final ToolOrchestrator toolOrchestrator;
    private final QueryContextAssembler contextAssembler;
    private final ContextCompactor contextCompactor;
    private final com.smartquery.logging.ConversationStatsService statsService;
    private final List<LifecycleHook> lifecycleHooks;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @org.springframework.beans.factory.annotation.Qualifier("llmExecutor")
    private final Executor llmExecutor;

    @Value("${llm.timeout-seconds:120}")
    private int llmTimeoutSeconds;

    @Value("${react.max-turns:20}")
    private int maxTurns;

    @Value("${react.max-token-budget:200000}")
    private int maxTokenBudget;

    @Value("${react.micro-compact-threshold:60000}")
    private int microCompactThreshold;

    @Value("${react.compact-threshold:80000}")
    private int compactThreshold;

    public ReActEngine(
            LlmService llmService,
            ToolRegistry toolRegistry,
            ToolOrchestrator toolOrchestrator,
            QueryContextAssembler contextAssembler,
            ContextCompactor contextCompactor,
            com.smartquery.logging.ConversationStatsService statsService,
            List<LifecycleHook> lifecycleHooks,
            @org.springframework.beans.factory.annotation.Qualifier("llmExecutor") Executor llmExecutor
    ) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.toolOrchestrator = toolOrchestrator;
        this.contextAssembler = contextAssembler;
        this.contextCompactor = contextCompactor;
        this.statsService = statsService;
        this.lifecycleHooks = lifecycleHooks;
        this.llmExecutor = llmExecutor;
    }


    /**
     * 向后兼容: 收集所有事件后返回
     */
    public List<ReActEvent> runReActLoop(
        String model,
        Long dataSourceId,
        String userMessage,
        List<Map<String, Object>> historyMessages,
        BooleanSupplier abortChecker
    ) {
        return runReActLoop(null, model, dataSourceId, userMessage, historyMessages, abortChecker);
    }

    public List<ReActEvent> runReActLoop(
        Long conversationId,
        String model,
        Long dataSourceId,
        String userMessage,
        List<Map<String, Object>> historyMessages,
        BooleanSupplier abortChecker
    ) {
        List<ReActEvent> events = new ArrayList<>();
        runReActLoopStreaming(conversationId, model, dataSourceId, userMessage, historyMessages, abortChecker, events::add);
        return events;
    }

    /**
     * 实时流式输出: 每产生一个事件立即回调
     */
    public void runReActLoopStreaming(
        Long conversationId,
        String model,
        Long dataSourceId,
        String userMessage,
        List<Map<String, Object>> historyMessages,
        BooleanSupplier abortChecker,
        Consumer<ReActEvent> eventConsumer
    ) {
        // 1. 构建初始消息 (system + history + user)
        String systemPrompt = contextAssembler.fetchPromptParts(model, dataSourceId).systemPrompt();

        // LifecycleHook: onSessionStart — 注入附加上下文
        StringBuilder sessionExtras = new StringBuilder();
        for (LifecycleHook hook : lifecycleHooks) {
            try {
                String extra = hook.onSessionStart(Map.of("conversationId", conversationId != null ? conversationId : 0L, "dataSourceId", dataSourceId != null ? dataSourceId : 0L));
                if (extra != null && !extra.isBlank()) {
                    sessionExtras.append("\n\n").append(extra);
                }
            } catch (Exception e) {
                log.warn("[REACT] LifecycleHook {} onSessionStart failed: {}", hook.name(), e.getMessage());
            }
        }
        if (!sessionExtras.isEmpty()) {
            systemPrompt = systemPrompt + sessionExtras;
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.addAll(historyMessages);

        // LifecycleHook: onUserPrompt — 预处理用户输入
        String processedMessage = userMessage;
        for (LifecycleHook hook : lifecycleHooks) {
            try {
                String modified = hook.onUserPrompt(userMessage, Map.of("conversationId", conversationId != null ? conversationId : 0L));
                if (modified != null) processedMessage = modified;
            } catch (Exception e) {
                log.warn("[REACT] LifecycleHook {} onUserPrompt failed: {}", hook.name(), e.getMessage());
            }
        }

        messages.add(Map.of("role", "user", "content", processedMessage));

        // 2. 初始化状态
        ReActState state = ReActState.initial(messages);

        // 3. 获取工具定义 (无数据源时过滤掉 DB 依赖工具)
        List<Map<String, Object>> toolDefs = toolRegistry.getToolDefinitions(dataSourceId);

        log.info("[REACT] starting loop: model={}, maxTurns={}, history={}", model, maxTurns, historyMessages.size());

        // 4. while(true) 主循环
        while (!state.terminated()) {
            // 终止条件检查
            if (abortChecker.getAsBoolean()) {
                state = state.withTerminated("用户中断");
                eventConsumer.accept(new ReActEvent.Done(state.turnCount(), state.totalTokens(), state.totalCost()));
                break;
            }
            if (state.turnCount() >= maxTurns) {
                state = state.withTerminated("达到最大轮次 " + maxTurns);
                eventConsumer.accept(new ReActEvent.Done(state.turnCount(), state.totalTokens(), state.totalCost()));
                break;
            }
            if (state.totalTokens() >= maxTokenBudget) {
                state = state.withTerminated("达到 token 预算 " + maxTokenBudget);
                eventConsumer.accept(new ReActEvent.Done(state.turnCount(), state.totalTokens(), state.totalCost()));
                break;
            }

            // 微压缩 — 选择性清除旧 tool_result，延迟全量 LLM 压缩
            if (state.totalTokens() >= microCompactThreshold && state.totalTokens() < compactThreshold) {
                List<Map<String, Object>> microCompacted = contextCompactor.microCompact(state.messages());
                if (microCompacted.size() < state.messages().size()) {
                    state = state.withMessages(microCompacted);
                    log.info("[REACT] micro-compact: realTokens={}, messages {} -> {}",
                        state.totalTokens(), state.messages().size(), microCompacted.size());
                }
            }

            // 全量 LLM 上下文压缩 — 最后手段
            if (state.totalTokens() >= compactThreshold) {
                List<Map<String, Object>> compacted = contextCompactor.compact(
                    state.messages(), compactThreshold);
                state = state.withMessages(compacted);
                log.info("[REACT] context compacted: realTokens={}, messages {} -> {}",
                    state.totalTokens(), state.messages().size(), compacted.size());
            }

            // 调用 LLM (流式文本 token) with timeout protection
            final List<Map<String, Object>> currentMessages = state.messages();
            final List<Map<String, Object>> currentToolDefs = toolDefs;
            final Consumer<String> tokenConsumer = token -> eventConsumer.accept(new ReActEvent.ThinkingDelta(token));

            final long[] llmTiming = {0};
            List<LlmChunk> chunks;
            try {
                chunks = CompletableFuture.supplyAsync(() -> {
                    llmTiming[0] = System.currentTimeMillis();
                    return llmService.chatWithToolsStreaming(model, currentMessages, currentToolDefs, tokenConsumer);
                }, llmExecutor
                ).get(llmTimeoutSeconds, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                String msg = "LLM API call timed out after " + llmTimeoutSeconds + " seconds";
                log.error("[REACT] {}", msg);
                eventConsumer.accept(new ReActEvent.Error("LLM 调用超时", msg));
                eventConsumer.accept(new ReActEvent.Done(state.turnCount(), state.totalTokens(), state.totalCost()));
                break;
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("[REACT] LLM call failed: {}", cause.getMessage());
                eventConsumer.accept(new ReActEvent.Error("LLM 调用失败", cause.getMessage()));
                eventConsumer.accept(new ReActEvent.Done(state.turnCount(), state.totalTokens(), state.totalCost()));
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[REACT] LLM call interrupted");
                eventConsumer.accept(new ReActEvent.Error("LLM 调用被中断", e.getMessage()));
                eventConsumer.accept(new ReActEvent.Done(state.turnCount(), state.totalTokens(), state.totalCost()));
                break;
            } catch (Exception e) {
                log.error("[REACT] LLM call failed: {}", e.getMessage());
                eventConsumer.accept(new ReActEvent.Error("LLM 调用失败", e.getMessage()));
                eventConsumer.accept(new ReActEvent.Done(state.turnCount(), state.totalTokens(), state.totalCost()));
                break;
            }
            long llmDurationMs = System.currentTimeMillis() - llmTiming[0];

            // LLM 调用遥测
            statsService.recordLlmCall(model, llmDurationMs, state.turnCount());

            // 解析响应
            StringBuilder assistantText = new StringBuilder();
            List<ToolOrchestrator.ToolCall> toolCalls = new ArrayList<>();
            int inputTokens = 0, outputTokens = 0;

            for (LlmChunk chunk : chunks) {
                if (chunk.isText() && chunk.text() != null) {
                    assistantText.append(chunk.text());
                }
                if (chunk.isToolCall()) {
                    try {
                        Map<String, Object> input = objectMapper
                            .readValue(chunk.toolInputJson(), Map.class);
                        toolCalls.add(new ToolOrchestrator.ToolCall(
                            chunk.toolCallId(), chunk.toolName(), input));
                    } catch (Exception e) {
                        log.warn("[REACT] failed to parse tool input: {}", e.getMessage());
                        toolCalls.add(new ToolOrchestrator.ToolCall(
                            chunk.toolCallId(), chunk.toolName(), Map.of()));
                    }
                }
                if (chunk.isDone()) {
                    inputTokens = chunk.inputTokens();
                    outputTokens = chunk.outputTokens();
                }
            }

            // 更新状态
            List<Map<String, Object>> newMessages = new ArrayList<>(state.messages());
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", assistantText.isEmpty() ? null : assistantText.toString());
            if (!toolCalls.isEmpty()) {
                List<Map<String, Object>> serializedToolCalls = new ArrayList<>();
                for (ToolOrchestrator.ToolCall tc : toolCalls) {
                    Map<String, Object> tcMap = new LinkedHashMap<>();
                    tcMap.put("id", tc.toolCallId());
                    tcMap.put("type", "function");
                    Map<String, String> fnMap = new LinkedHashMap<>();
                    fnMap.put("name", tc.toolName());
                    fnMap.put("arguments", serializeToolInput(tc.input()));
                    tcMap.put("function", fnMap);
                    serializedToolCalls.add(tcMap);
                }
                assistantMsg.put("tool_calls", serializedToolCalls);
            }
            newMessages.add(assistantMsg);

            state = state.withMessages(new ArrayList<>(newMessages))
                .withTurnIncremented()
                .withTokenUsage(inputTokens, outputTokens, 0.0);

            // 实时发送 thinking 事件
            if (!assistantText.isEmpty()) {
                eventConsumer.accept(new ReActEvent.Thinking(assistantText.toString()));
            }

            // 无 tool_use → 终止
            if (toolCalls.isEmpty()) {
                state = state.withTerminated("LLM 未调用工具，回复完成");
                eventConsumer.accept(new ReActEvent.Done(state.turnCount(), state.totalTokens(), state.totalCost()));
                break;
            }

            // 执行工具 — 先发出工具输入事件（SQL/Python 代码预览）
            ToolExecutionContext ctx = new ToolExecutionContext(
                conversationId, dataSourceId, UUID.randomUUID().toString(), model, abortChecker, eventConsumer
            );

            for (ToolOrchestrator.ToolCall tc : toolCalls) {
                emitToolInputPreview(eventConsumer, tc.toolName(), tc.input());
            }

            long toolStartMs = System.currentTimeMillis();
            List<ToolResult> toolResults = toolOrchestrator.executeAll(toolCalls, ctx);
            long totalToolDuration = System.currentTimeMillis() - toolStartMs;

            // 构建 tool_result 消息 + 实时发出工具事件
            long perToolDuration = toolCalls.size() > 0 ? totalToolDuration / toolCalls.size() : 0;
            for (int i = 0; i < toolCalls.size() && i < toolResults.size(); i++) {
                ToolOrchestrator.ToolCall tc = toolCalls.get(i);
                ToolResult tr = toolResults.get(i);

                statsService.recordToolCall(tc.toolName(), tr.success(), perToolDuration);

                Map<String, Object> toolResultMsg = new LinkedHashMap<>();
                toolResultMsg.put("role", "tool");
                toolResultMsg.put("tool_call_id", tc.toolCallId());
                if (tr.success()) {
                    toolResultMsg.put("content", tr.output());
                } else {
                    String errorContent = "错误: " + tr.error();
                    if (tr.output() != null && !tr.output().isBlank()) {
                        errorContent += "\n\n执行输出(部分):\n" + tr.output();
                    }
                    toolResultMsg.put("content", errorContent);
                }
                newMessages.add(toolResultMsg);

                // 实时发送工具结果事件
                emitToolEvent(eventConsumer, tc.toolName(), tr);

                state = state.addStep(new ReActState.StepRecord(
                    state.turnCount(), "tool_call", tc.toolName(), tr.durationMs(), tr.success()));
            }

            // Fill missing tool results to avoid tool_call/tool_result mismatch
            for (int i = toolResults.size(); i < toolCalls.size(); i++) {
                ToolOrchestrator.ToolCall tc = toolCalls.get(i);
                Map<String, Object> toolResultMsg = new LinkedHashMap<>();
                toolResultMsg.put("role", "tool");
                toolResultMsg.put("tool_call_id", tc.toolCallId());
                toolResultMsg.put("content", "Error: tool execution result missing");
                newMessages.add(toolResultMsg);
                emitToolEvent(eventConsumer, tc.toolName(),
                    ToolResult.error(tc.toolName(), ToolError.nonRecoverable(
                        ToolError.ErrorCode.TOOL_ERROR, "result missing"), 0));
                log.warn("[REACT] filled missing tool_result for tool_call_id={}", tc.toolCallId());
            }

            state = state.withMessages(newMessages);

            log.info("[REACT] turn={}: {} tool calls executed", state.turnCount(), toolCalls.size());
        }

        log.info("[REACT] loop ended: turns={}, tokens={}, cost={}, reason={}",
            state.turnCount(), state.totalTokens(), state.totalCost(), state.terminationReason());
    }

    private void emitToolInputPreview(Consumer<ReActEvent> emitter, String toolName, Map<String, Object> input) {
        try {
            switch (toolName) {
                case "execute_sql" -> {
                    String sql = (String) input.get("sql");
                    if (sql != null) {
                        emitter.accept(new ReActEvent.SqlExecuting(sql));
                    }
                }
                case "execute_python" -> {
                    String code = (String) input.get("code");
                    if (code != null) {
                        emitter.accept(new ReActEvent.PythonExecuting(code, 0));
                    }
                }
                default -> {}
            }
        } catch (Exception e) {
            log.debug("[REACT] emitToolInputPreview skipped for {}: {}", toolName, e.getMessage());
        }
    }

    private void emitToolEvent(Consumer<ReActEvent> emitter, String toolName, ToolResult tr) {
        try {
            if ("execute_sql".equals(toolName)) {
                if (tr.success()) {
                    List<Map<String, Object>> rows = tr.data() != null ? tr.data() : List.of();
                    emitter.accept(new ReActEvent.Result(tr.output(), rows, rows.size(), null));
                } else {
                    emitter.accept(new ReActEvent.Result(null, List.of(), 0, tr.error()));
                }
            } else if ("execute_python".equals(toolName)) {
                String stdout = tr.success() ? tr.output() : "";
                String stderr = tr.success() ? "" : tr.error();
                int exitCode = tr.success() ? 0 : 1;
                List<String> artifacts = List.of();
                if (tr.data() != null && !tr.data().isEmpty()) {
                    Map<String, Object> pyData = tr.data().get(0);
                    if (pyData.get("stdout") instanceof String s) stdout = s;
                    if (pyData.get("stderr") instanceof String s && !s.isEmpty()) stderr = s;
                    if (pyData.get("exitCode") instanceof Number n) exitCode = n.intValue();
                    if (pyData.get("artifacts") instanceof List<?> arts) {
                        artifacts = arts.stream().map(Object::toString).toList();
                    }
                }
                emitter.accept(new ReActEvent.PythonResultEvent(stdout, stderr, exitCode, artifacts));
            } else if ("mining_model".equals(toolName)) {
                emitMiningModelEvent(emitter, tr);
            } else {
                if (tr.success()) {
                    tryEmitJsonToolEvent(emitter, toolName, tr);
                }
            }
        } catch (Exception e) {
            log.debug("[REACT] emitToolEvent skipped for {}: {}", toolName, e.getMessage());
        }
    }

    private void emitMiningModelEvent(Consumer<ReActEvent> emitter, ToolResult tr) {
        String output = tr.output();
        if (output == null) return;

        String action = "unknown";
        Long modelId = null;
        String modelName = null;
        String algorithm = null;
        String message = output;
        Map<String, Object> details = new java.util.LinkedHashMap<>();

        // Extract structured info from data
        if (tr.data() != null && !tr.data().isEmpty()) {
            Map<String, Object> data = tr.data().get(0);
            // Action from tool directly (canonical source)
            if (data.get("__action") instanceof String s && !s.isBlank()) action = s;
            if (data.get("modelId") instanceof Number n) modelId = n.longValue();
            if (data.get("name") instanceof String s) modelName = s;
            if (data.get("algorithmId") instanceof String s) algorithm = s;
            details.putAll(data);
            details.remove("__action");
        }

        // Fallback: parse action from output text only if not set by tool
        if ("unknown".equals(action) && output != null) {
            if (output.contains("模型") && output.contains("训练完成")) action = "train";
            else if (output.contains("已创建模型") || output.contains("已创建自定义算法")) action = "create";
            else if (output.contains("已更新")) action = "update";
            else if (output.contains("已发布")) action = "publish";
            else if (output.contains("已下线")) action = "offline";
            else if (output.contains("预测结果") || output.contains("批量预测完成")) action = "predict";
            else if (output.contains("验证结果") || output.contains("验证通过") || output.contains("验证未通过")) action = "validate";
            else if (output.contains("探索结果") || output.contains("总行数")) action = "explore_data";
            else if (output.contains("可用算法列表")) action = "list_algorithms";
            else if (output.contains("共有") && output.contains("个挖掘模型")) action = "list";
            else if (output.contains("模型:")) action = "get";
            else if (output.contains("执行历史")) action = "history";
        }

        emitter.accept(new ReActEvent.MiningModelEvent(
            action, modelId, modelName, algorithm, tr.success(), message, details));
    }

    private void tryEmitJsonToolEvent(Consumer<ReActEvent> emitter, String toolName, ToolResult tr) {
        try {
            Map<String, Object> data = objectMapper.readValue(tr.output(), Map.class);

            switch (toolName) {
                case "generate_filter_widgets" -> {
                    emitter.accept(new ReActEvent.FilterWidgetsGenerated(tr.output()));
                }
                case "generate_chart" -> {
                    Long chartId = toLong(data.get("chartId"));
                    emitter.accept(new ReActEvent.ChartGenerated(
                        chartId,
                        (String) data.getOrDefault("title", ""),
                        (String) data.getOrDefault("chartType", ""),
                        data.containsKey("echartsOption") ? objectMapper.writeValueAsString(data.get("echartsOption")) : ""
                    ));
                }
                case "generate_report" -> {
                    Long reportId = toLong(data.get("reportId"));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> sections = data.containsKey("sections")
                        ? (List<Map<String, Object>>) data.get("sections") : List.of();
                    emitter.accept(new ReActEvent.ReportGenerated(
                        reportId,
                        (String) data.getOrDefault("title", ""),
                        data.containsKey("sectionCount") ? toInt(data.get("sectionCount")) : 0,
                        sections != null ? sections : List.of(),
                        (String) data.getOrDefault("conclusion", "")
                    ));
                }
                case "generate_dashboard" -> {
                    Long dashId = toLong(data.get("dashboardId"));
                    List<Long> chartIds = data.containsKey("chartIds")
                        ? ((List<?>) data.get("chartIds")).stream().map(ReActEngine::toLong).toList()
                        : List.of();
                    emitter.accept(new ReActEvent.DashboardGenerated(
                        dashId,
                        (String) data.getOrDefault("title", ""),
                        (String) data.getOrDefault("layout", "grid-2col"),
                        chartIds
                    ));
                }
                default -> {}
            }
        } catch (Exception e) {
            log.warn("[REACT] JSON tool event parse failed for {}: {}", toolName, e.getMessage());
        }
    }

    private static Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private String serializeToolInput(Map<String, Object> input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return "{}";
        }
    }
}
