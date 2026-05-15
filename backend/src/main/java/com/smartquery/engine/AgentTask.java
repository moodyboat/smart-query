package com.smartquery.engine;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子任务定义 — 可由 AgentTaskExecutor 在独立上下文中执行
 *
 * <p>支持 blocks/blockedBy 依赖声明（翻译自 Claude Code TodoWrite）:
 * <ul>
 *   <li>blockedBy: 本任务依赖的任务 ID 列表（必须先完成）</li>
 *   <li>blocks: 本任务完成后解除阻塞的任务 ID 列表</li>
 * </ul>
 */
public record AgentTask(
    String taskId,
    String prompt,
    List<String> toolNames,
    Map<String, Object> context,
    Long dataSourceId,
    String model,
    List<String> blockedBy,
    List<String> blocks
) {
    public static AgentTask of(String taskId, String prompt, Long dataSourceId) {
        return new AgentTask(taskId, prompt, List.of(), Map.of(), dataSourceId, null, List.of(), List.of());
    }

    public static AgentTask of(String taskId, String prompt, List<String> toolNames, Long dataSourceId) {
        return new AgentTask(taskId, prompt, toolNames, Map.of(), dataSourceId, null, List.of(), List.of());
    }

    public static AgentTask of(String taskId, String prompt, List<String> toolNames, Long dataSourceId,
                               List<String> blockedBy, List<String> blocks) {
        return new AgentTask(taskId, prompt, toolNames, Map.of(), dataSourceId, null,
            blockedBy != null ? blockedBy : List.of(),
            blocks != null ? blocks : List.of());
    }

    /**
     * 该任务是否被阻塞（存在未完成的依赖）
     */
    public boolean isBlockedBy(Set<String> completedTaskIds) {
        if (blockedBy == null || blockedBy.isEmpty()) return false;
        return !completedTaskIds.containsAll(blockedBy);
    }
}
