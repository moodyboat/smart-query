package com.smartquery.tool;

import com.smartquery.common.UserContextHolder;
import com.smartquery.logging.ConversationEventLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 工具编排器 — 直译 Claude Code toolOrchestration.ts
 *
 * <p>翻译对照:
 * <pre>
 * TS: partitionToolCalls(toolCalls) → {concurrent, serial}
 * TS: runToolsConcurrently(toolCalls) → Promise.all(results)
 * TS: runToolsSerially(toolCalls) → sequential results
 * Java: ToolOrchestrator with CompletableFuture.allOf / sequential
 * </pre>
 */
@Slf4j
@Component
public class ToolOrchestrator {

    private final ToolRegistry toolRegistry;
    private final Executor asyncExecutor;
    private final List<ToolHook> hooks;
    private final ConversationEventLogger eventLogger;

    @Value("${tool.default-timeout-ms:30000}")
    private long defaultToolTimeoutMs;

    @Value("${tool.retry.max-attempts:2}")
    private int retryMaxAttempts;

    @Value("${tool.retry.backoff-ms:1000}")
    private long retryBackoffMs;

    public ToolOrchestrator(ToolRegistry toolRegistry,
                            @Qualifier("asyncExecutor") Executor asyncExecutor,
                            List<ToolHook> hooks,
                            ConversationEventLogger eventLogger) {
        this.toolRegistry = toolRegistry;
        this.asyncExecutor = asyncExecutor;
        this.eventLogger = eventLogger;
        this.hooks = hooks.stream()
            .sorted(java.util.Comparator.comparingInt(ToolHook::order))
            .toList();
    }

    /**
     * 直译 partitionToolCalls(): 按并发安全性分区
     */
    public ToolPartition partition(List<ToolCall> toolCalls) {
        List<ToolCall> concurrent = toolCalls.stream()
            .filter(tc -> {
                LlmTool tool = toolRegistry.getTool(tc.toolName()).orElse(null);
                return tool != null && tool.isConcurrencySafe();
            })
            .toList();

        List<ToolCall> serial = toolCalls.stream()
            .filter(tc -> {
                LlmTool tool = toolRegistry.getTool(tc.toolName()).orElse(null);
                return tool == null || !tool.isConcurrencySafe();
            })
            .toList();

        return new ToolPartition(concurrent, serial);
    }

    /**
     * 执行所有工具调用，保持与 toolCalls 相同的顺序
     */
    public List<ToolResult> executeAll(List<ToolCall> toolCalls, ToolExecutionContext context) {
        if (toolCalls.isEmpty()) return List.of();

        ToolPartition partition = partition(toolCalls);

        // Execute both groups
        Map<ToolCall, ToolResult> concurrentResults = new LinkedHashMap<>();
        if (!partition.concurrent().isEmpty()) {
            List<ToolResult> results = runConcurrently(partition.concurrent(), context);
            for (int i = 0; i < partition.concurrent().size(); i++) {
                concurrentResults.put(partition.concurrent().get(i), results.get(i));
            }
        }

        Map<ToolCall, ToolResult> serialResults = new LinkedHashMap<>();
        if (!partition.serial().isEmpty()) {
            List<ToolResult> results = runSerially(partition.serial(), context);
            for (int i = 0; i < partition.serial().size(); i++) {
                serialResults.put(partition.serial().get(i), results.get(i));
            }
        }

        // Reassemble in original order
        return toolCalls.stream()
            .map(tc -> {
                ToolResult r = concurrentResults.get(tc);
                return r != null ? r : serialResults.get(tc);
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * 直译 runToolsConcurrently(): CompletableFuture.allOf 并发执行 + timeout
     */
    private List<ToolResult> runConcurrently(List<ToolCall> toolCalls, ToolExecutionContext context) {
        List<CompletableFuture<ToolResult>> futures = toolCalls.stream()
            .map(tc -> CompletableFuture.supplyAsync(() -> executeSingle(tc, context), asyncExecutor)
                .orTimeout(getTimeoutForTool(tc.toolName()), TimeUnit.MILLISECONDS)
                .exceptionally(ex -> handleExecutionException(tc, ex)))
            .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        return futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
    }

    /**
     * 直译 runToolsSerially(): 串行执行
     */
    private List<ToolResult> runSerially(List<ToolCall> toolCalls, ToolExecutionContext context) {
        return toolCalls.stream()
            .map(tc -> executeSingle(tc, context))
            .collect(Collectors.toList());
    }

    private ToolResult executeSingle(ToolCall toolCall, ToolExecutionContext context) {
        // The actor is part of the immutable tool context. Re-bind it even when a
        // future executor implementation does not propagate ThreadLocal values.
        try (UserContextHolder.Scope ignored = UserContextHolder.open(context.actor())) {
            return executeSingleAsActor(toolCall, context);
        }
    }

    private ToolResult executeSingleAsActor(ToolCall toolCall, ToolExecutionContext context) {
        Optional<LlmTool> opt = toolRegistry.getTool(toolCall.toolName());
        if (opt.isEmpty()) {
            return ToolResult.error(toolCall.toolName(),
                ToolError.nonRecoverable(ToolError.ErrorCode.TOOL_NOT_FOUND, "未找到工具: " + toolCall.toolName()), 0);
        }

        // Abort check
        if (context.isAborted()) {
            return ToolResult.error(toolCall.toolName(),
                ToolError.abort("用户已中断"), 0);
        }

        // PreToolUse hooks — fail-closed on exception
        Map<String, Object> input = new java.util.LinkedHashMap<>(toolCall.input());
        for (ToolHook hook : hooks) {
            try {
                if (!hook.beforeToolCall(toolCall.toolName(), input, context)) {
                    log.info("[ORCHESTRATOR] tool {} blocked by hook {}", toolCall.toolName(), hook.name());
                    return ToolResult.error(toolCall.toolName(),
                        ToolError.security("工具调用被安全策略阻止: " + hook.name()), 0);
                }
            } catch (Exception e) {
                log.error("[ORCHESTRATOR] hook {} beforeToolCall failed, blocking tool {} for safety", hook.name(), toolCall.toolName(), e);
                return ToolResult.error(toolCall.toolName(),
                    ToolError.nonRecoverable(ToolError.ErrorCode.TOOL_ERROR,
                        "安全检查异常，工具调用已阻止: " + e.getMessage()), 0);
            }
        }

        LlmTool tool = opt.get();

        // Validate required parameters
        List<String> violations = validateParameters(tool, input);
        if (!violations.isEmpty()) {
            return ToolResult.error(toolCall.toolName(),
                ToolError.of(ToolError.ErrorCode.VALIDATION_ERROR,
                    "参数校验失败: " + String.join("; ", violations)), 0);
        }

        log.debug("[ORCHESTRATOR] executing tool: {} (concurrencySafe={}, timeout={}ms)", tool.getName(), tool.isConcurrencySafe(), tool.getTimeoutMs());

        ToolResult result = executeWithRetry(tool, input, context, toolCall);

        // PostToolUse hooks — log but don't block on failure
        for (ToolHook hook : hooks) {
            try {
                hook.afterToolCall(toolCall.toolName(), input, result, context);
            } catch (Exception e) {
                log.warn("[ORCHESTRATOR] hook {} afterToolCall error: {}", hook.name(), e.getMessage());
            }
        }

        return result;
    }

    private ToolResult executeWithRetry(LlmTool tool, Map<String, Object> input,
                                         ToolExecutionContext context, ToolCall toolCall) {
        Exception lastException = null;
        for (int attempt = 0; attempt <= retryMaxAttempts; attempt++) {
            try {
                ToolResult result = tool.execute(input, context);
                if (attempt > 0) {
                    log.info("[ORCHESTRATOR] tool {} succeeded on attempt {}", tool.getName(), attempt + 1);
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                if (attempt < retryMaxAttempts) {
                    long delay = retryBackoffMs * (1L << attempt);
                    log.warn("[ORCHESTRATOR] tool {} failed (attempt {}/{}), retrying in {}ms: {}",
                        tool.getName(), attempt + 1, retryMaxAttempts + 1, delay, e.getMessage());
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("[ORCHESTRATOR] tool {} failed after {} attempts", tool.getName(), retryMaxAttempts + 1, lastException);
        eventLogger.logEvent(context.conversationId(), context.traceId(), "tool_error",
            Map.of("tool", tool.getName(), "error", lastException != null ? lastException.getMessage() : "unknown",
                   "category", lastException != null ? lastException.getClass().getSimpleName() : "",
                   "attempts", retryMaxAttempts + 1,
                   "input_keys", input.keySet()));
        return ToolResult.error(toolCall.toolName(),
            ToolError.of(ToolError.ErrorCode.TOOL_ERROR,
                tool.getName() + " 执行失败(" + (retryMaxAttempts + 1) + "次重试): " +
                (lastException != null ? lastException.getMessage() : "unknown"),
                lastException != null ? lastException.getClass().getSimpleName() : ""), 0);
    }

    private long getTimeoutForTool(String toolName) {
        return toolRegistry.getTool(toolName).map(LlmTool::getTimeoutMs).orElse(defaultToolTimeoutMs);
    }

    @SuppressWarnings("unchecked")
    private List<String> validateParameters(LlmTool tool, Map<String, Object> input) {
        List<String> violations = new ArrayList<>();
        Map<String, Object> schema = tool.getJsonSchema();
        if (schema == null) return violations;

        Object requiredObj = schema.get("required");
        if (requiredObj instanceof List<?> requiredList) {
            for (Object fieldObj : requiredList) {
                String field = String.valueOf(fieldObj);
                Object value = input.get(field);
                if (value == null) {
                    violations.add(field + " 不能为空");
                } else if (value instanceof String s && s.isBlank()) {
                    violations.add(field + " 不能为空字符串");
                }
            }
        }

        Object propertiesObj = schema.get("properties");
        if (propertiesObj instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                String fieldName = (String) entry.getKey();
                Object value = input.get(fieldName);
                if (value == null) continue;

                if (entry.getValue() instanceof Map<?, ?> fieldSchema) {
                    String expectedType = (String) fieldSchema.get("type");
                    violations.addAll(validateType(fieldName, value, expectedType));
                }
            }
        }
        return violations;
    }

    private List<String> validateType(String fieldName, Object value, String expectedType) {
        if (expectedType == null) return List.of();
        return switch (expectedType) {
            case "string" -> value instanceof String ? List.of() : List.of(fieldName + " 应为字符串类型");
            case "integer" -> value instanceof Number ? List.of() : List.of(fieldName + " 应为整数类型");
            case "number" -> value instanceof Number ? List.of() : List.of(fieldName + " 应为数字类型");
            case "boolean" -> value instanceof Boolean ? List.of() : List.of(fieldName + " 应为布尔类型");
            case "array" -> value instanceof List ? List.of() : List.of(fieldName + " 应为数组类型");
            case "object" -> value instanceof Map ? List.of() : List.of(fieldName + " 应为对象类型");
            default -> List.of();
        };
    }

    private ToolResult handleExecutionException(ToolCall tc, Throwable ex) {
        Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
        String errorType = cause instanceof TimeoutException ? "timeout" : "execution_error";
        String errorMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        eventLogger.logEvent(null, null, "tool_error",
            Map.of("tool", tc.toolName(), "error", errorMsg, "category", errorType));
        if (cause instanceof TimeoutException) {
            return ToolResult.error(tc.toolName(),
                ToolError.recoverable(ToolError.ErrorCode.TOOL_TIMEOUT,
                    tc.toolName() + " 执行超时"), getTimeoutForTool(tc.toolName()));
        }
        return ToolResult.error(tc.toolName(),
            ToolError.nonRecoverable(ToolError.ErrorCode.TOOL_ERROR,
                tc.toolName() + " 执行异常: " + errorMsg), 0);
    }

    // --- Value types ---

    public record ToolCall(String toolCallId, String toolName, Map<String, Object> input) {}

    public record ToolPartition(List<ToolCall> concurrent, List<ToolCall> serial) {}
}
