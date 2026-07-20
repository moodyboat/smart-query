package com.smartquery.engine;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ReAct 状态 — 不可变 record，直译 Claude Code query.ts State 类型
 *
 * <p>翻译对照:
 * <pre>
 * TS: type State = { messages, turnCount, totalTokens, costTracker, steps, ... }
 * Java: ReActState record (不可变，每次 while 循环新建)
 * </pre>
 */
public record ReActState(
    List<Map<String, Object>> messages,
    int turnCount,
    int totalTokens,
    double totalCost,
    List<StepRecord> steps,
    boolean terminated,
    String terminationReason
) {
    public record StepRecord(
        int stepIndex,
        String action,
        String toolName,
        long durationMs,
        boolean success
    ) {}

    public static ReActState initial(List<Map<String, Object>> initialMessages) {
        return new ReActState(
            Collections.unmodifiableList(initialMessages),
            0,
            0,
            0.0,
            Collections.emptyList(),
            false,
            null
        );
    }

    public ReActState withMessages(List<Map<String, Object>> messages) {
        return new ReActState(
            Collections.unmodifiableList(messages), turnCount, totalTokens,
            totalCost, steps, terminated, terminationReason);
    }

    public ReActState withTurnIncremented() {
        return new ReActState(messages, turnCount + 1, totalTokens,
            totalCost, steps, terminated, terminationReason);
    }

    public ReActState withTokenUsage(int inputTokens, int outputTokens, double cost) {
        return new ReActState(messages, turnCount,
            totalTokens + inputTokens + outputTokens,
            totalCost + cost, steps, terminated, terminationReason);
    }

    public ReActState addStep(StepRecord step) {
        List<StepRecord> newSteps = new java.util.ArrayList<>(steps);
        newSteps.add(step);
        return new ReActState(messages, turnCount, totalTokens,
            totalCost, Collections.unmodifiableList(newSteps), terminated, terminationReason);
    }

    public ReActState withTerminated(String reason) {
        return new ReActState(messages, turnCount, totalTokens,
            totalCost, steps, true, reason);
    }
}
