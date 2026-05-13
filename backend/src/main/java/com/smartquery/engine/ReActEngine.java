package com.smartquery.engine;

import com.smartquery.llm.LlmChunk;
import com.smartquery.llm.LlmService;
import com.smartquery.prompt.QueryContextAssembler;
import com.smartquery.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
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
@RequiredArgsConstructor
public class ReActEngine {

    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final ToolOrchestrator toolOrchestrator;
    private final QueryContextAssembler contextAssembler;
    private final ContextCompactor contextCompactor;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private static final int MAX_TURNS = 15;
    private static final int MAX_TOKEN_BUDGET = 200000;
    private static final int COMPACT_THRESHOLD = 80000;

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
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.addAll(historyMessages);
        messages.add(Map.of("role", "user", "content", userMessage));

        // 2. 初始化状态
        ReActState state = ReActState.initial(messages);

        // 3. 获取工具定义
        List<Map<String, Object>> toolDefs = toolRegistry.getToolDefinitions();

        log.info("[REACT] starting loop: model={}, maxTurns={}, history={}", model, MAX_TURNS, historyMessages.size());

        // 4. while(true) 主循环
        while (!state.terminated()) {
            // 终止条件检查
            if (abortChecker.getAsBoolean()) {
                state = state.withTerminated("用户中断");
                eventConsumer.accept(new ReActEvent.Done(state.turnCount(), state.totalTokens(), state.totalCost()));
                break;
            }
            if (state.turnCount() >= MAX_TURNS) {
                state = state.withTerminated("达到最大轮次 " + MAX_TURNS);
                eventConsumer.accept(new ReActEvent.Done(state.turnCount(), state.totalTokens(), state.totalCost()));
                break;
            }
            if (state.totalTokens() >= MAX_TOKEN_BUDGET) {
                state = state.withTerminated("达到 token 预算 " + MAX_TOKEN_BUDGET);
                eventConsumer.accept(new ReActEvent.Done(state.turnCount(), state.totalTokens(), state.totalCost()));
                break;
            }

            // 上下文压缩
            if (contextCompactor.needsCompaction(state.messages(), COMPACT_THRESHOLD)) {
                List<Map<String, Object>> compacted = contextCompactor.compact(
                    state.messages(), COMPACT_THRESHOLD);
                state = state.withMessages(compacted);
                log.info("[REACT] context compacted: {} -> {} messages", state.messages().size(), compacted.size());
            }

            // 调用 LLM (流式文本 token)
            List<LlmChunk> chunks;
            try {
                chunks = llmService.chatWithToolsStreaming(model, state.messages(), toolDefs,
                    token -> eventConsumer.accept(new ReActEvent.ThinkingDelta(token)));
            } catch (Exception e) {
                log.error("[REACT] LLM call failed: {}", e.getMessage());
                eventConsumer.accept(new ReActEvent.Error("LLM 调用失败", e.getMessage()));
                break;
            }

            // 解析响应
            String assistantText = "";
            List<ToolOrchestrator.ToolCall> toolCalls = new ArrayList<>();
            int inputTokens = 0, outputTokens = 0;

            for (LlmChunk chunk : chunks) {
                if (chunk.isText() && chunk.text() != null) {
                    assistantText += chunk.text();
                }
                if (chunk.isToolCall()) {
                    try {
                        Map<String, Object> input = objectMapper
                            .readValue(chunk.toolInputJson(), Map.class);
                        toolCalls.add(new ToolOrchestrator.ToolCall(
                            chunk.toolCallId(), chunk.toolName(), input));
                    } catch (Exception e) {
                        log.warn("[REACT] failed to parse tool input: {}", e.getMessage());
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
            assistantMsg.put("content", assistantText.isEmpty() ? null : assistantText);
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

            state = state.withMessages(newMessages)
                .withTurnIncremented()
                .withTokenUsage(inputTokens, outputTokens, 0.0);

            // 实时发送 thinking 事件
            if (!assistantText.isEmpty()) {
                eventConsumer.accept(new ReActEvent.Thinking(assistantText));
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

            List<ToolResult> toolResults = toolOrchestrator.executeAll(toolCalls, ctx);

            // 构建 tool_result 消息 + 实时发出工具事件
            for (int i = 0; i < toolCalls.size() && i < toolResults.size(); i++) {
                ToolOrchestrator.ToolCall tc = toolCalls.get(i);
                ToolResult tr = toolResults.get(i);

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
            switch (toolName) {
                case "execute_sql" -> {
                    if (tr.success()) {
                        List<Map<String, Object>> rows = tr.data() != null ? tr.data() : List.of();
                        emitter.accept(new ReActEvent.Result(tr.output(), rows, rows.size(), null));
                    } else {
                        emitter.accept(new ReActEvent.Result(null, List.of(), 0, tr.error()));
                    }
                }
                case "execute_python" -> {
                    // Extract structured data from ToolResult if available
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
                }
                default -> {
                    if (tr.success()) {
                        tryEmitJsonToolEvent(emitter, toolName, tr);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[REACT] emitToolEvent skipped for {}: {}", toolName, e.getMessage());
        }
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
