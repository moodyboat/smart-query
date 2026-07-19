package com.smartquery.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 应用状态 — 适配 Claude Code AppStateStore.ts
 *
 * <p>翻译对照:
 * <pre>
 * TS: type AppState = DeepImmutable&lt;{...50+ fields...}&gt;
 * Java: record AppState (保留核心字段，去掉 Claude Code 专属)
 * </pre>
 *
 * <p>不可变 record: 每次修改通过 withXxx() 返回新实例
 */
public record AppState(
    Long conversationId,
    Long dataSourceId,
    List<Map<String, Object>> messages,
    String currentModel,
    ModelUsage totalUsage,
    String toolPermissionContext,
    int turnCount,
    List<StepRecord> steps,
    boolean aborted
) {
    public record ModelUsage(
        int inputTokens,
        int outputTokens,
        double costUsd
    ) {
        public static ModelUsage zero() {
            return new ModelUsage(0, 0, 0.0);
        }

        public ModelUsage add(int inputTokens, int outputTokens, double costUsd) {
            return new ModelUsage(
                this.inputTokens + inputTokens,
                this.outputTokens + outputTokens,
                this.costUsd + costUsd
            );
        }
    }

    public record StepRecord(
        int stepIndex,
        String action,
        String toolName,
        long durationMs,
        boolean success
    ) {}

    public static AppState defaults() {
        return new AppState(
            null,
            null,
            new ArrayList<>(),
            null,
            ModelUsage.zero(),
            "normal",
            0,
            new ArrayList<>(),
            false
        );
    }

    public AppState withConversationId(Long conversationId) {
        return new AppState(conversationId, dataSourceId, messages, currentModel, totalUsage,
            toolPermissionContext, turnCount, steps, aborted);
    }

    public AppState withDataSourceId(Long dataSourceId) {
        return new AppState(conversationId, dataSourceId, messages, currentModel, totalUsage,
            toolPermissionContext, turnCount, steps, aborted);
    }

    public AppState withMessages(List<Map<String, Object>> messages) {
        return new AppState(conversationId, dataSourceId,
            Collections.unmodifiableList(new ArrayList<>(messages)),
            currentModel, totalUsage, toolPermissionContext, turnCount, steps, aborted);
    }

    public AppState withModel(String model) {
        return new AppState(conversationId, dataSourceId, messages, model, totalUsage,
            toolPermissionContext, turnCount, steps, aborted);
    }

    public AppState addUsage(int inputTokens, int outputTokens, double costUsd) {
        return new AppState(conversationId, dataSourceId, messages, currentModel,
            totalUsage.add(inputTokens, outputTokens, costUsd),
            toolPermissionContext, turnCount, steps, aborted);
    }

    public AppState incrementTurn() {
        return new AppState(conversationId, dataSourceId, messages, currentModel, totalUsage,
            toolPermissionContext, turnCount + 1, steps, aborted);
    }

    public AppState addStep(StepRecord step) {
        List<StepRecord> newSteps = new ArrayList<>(steps);
        newSteps.add(step);
        return new AppState(conversationId, dataSourceId, messages, currentModel, totalUsage,
            toolPermissionContext, turnCount, Collections.unmodifiableList(newSteps), aborted);
    }

    public AppState withAborted(boolean aborted) {
        return new AppState(conversationId, dataSourceId, messages, currentModel, totalUsage,
            toolPermissionContext, turnCount, steps, aborted);
    }
}
