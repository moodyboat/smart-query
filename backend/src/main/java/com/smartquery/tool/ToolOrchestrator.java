package com.smartquery.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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

    public ToolOrchestrator(ToolRegistry toolRegistry,
                            @Qualifier("asyncExecutor") Executor asyncExecutor) {
        this.toolRegistry = toolRegistry;
        this.asyncExecutor = asyncExecutor;
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
     * 直译 runToolsConcurrently(): CompletableFuture.allOf 并发执行
     */
    private List<ToolResult> runConcurrently(List<ToolCall> toolCalls, ToolExecutionContext context) {
        List<CompletableFuture<ToolResult>> futures = toolCalls.stream()
            .map(tc -> CompletableFuture.supplyAsync(() -> executeSingle(tc, context), asyncExecutor))
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
        Optional<LlmTool> opt = toolRegistry.getTool(toolCall.toolName());
        if (opt.isEmpty()) {
            return ToolResult.error(toolCall.toolName(), "未找到工具: " + toolCall.toolName(), 0);
        }
        LlmTool tool = opt.get();
        log.debug("[ORCHESTRATOR] executing tool: {} (concurrencySafe={})", tool.getName(), tool.isConcurrencySafe());
        return tool.execute(toolCall.input(), context);
    }

    // --- Value types ---

    public record ToolCall(String toolCallId, String toolName, Map<String, Object> input) {}

    public record ToolPartition(List<ToolCall> concurrent, List<ToolCall> serial) {}
}
