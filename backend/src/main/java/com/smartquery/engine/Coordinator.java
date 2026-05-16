package com.smartquery.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 子任务协调器 — 按依赖 DAG 执行子任务，无依赖的并行执行
 *
 * <p>使用方式:
 * <pre>
 *   Coordinator.Plan plan = coordinator.plan(mainTask, List.of(subTask1, subTask2));
 *   Coordinator.Result result = coordinator.coordinate(plan);
 * </pre>
 */
@Slf4j
@Component
public class Coordinator {

    private final AgentTaskExecutor taskExecutor;

    public Coordinator(AgentTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    /**
     * 创建执行计划
     */
    public Plan plan(AgentTask mainTask, List<AgentTask> subTasks) {
        return new Plan(mainTask, subTasks != null ? subTasks : List.of());
    }

    /**
     * 创建带依赖关系的执行计划
     */
    public Plan plan(AgentTask mainTask, List<AgentTask> subTasks, Map<String, List<String>> dependencies) {
        return new Plan(mainTask, subTasks != null ? subTasks : List.of(), dependencies);
    }

    /**
     * 执行协调（无事件透传）
     */
    public Result coordinate(Plan plan) {
        return coordinate(plan, null);
    }

    /**
     * 执行协调: 并行运行子任务，汇总结果后注入主任务上下文
     * @param progressConsumer 子任务进度事件透传到主 SSE 连接
     */
    public Result coordinate(Plan plan, java.util.function.Consumer<ReActEvent> progressConsumer) {
        long start = System.currentTimeMillis();
        log.info("[COORDINATOR] Starting coordination: {} sub-tasks", plan.subTasks.size());

        detectCycle(plan);

        List<AgentResult> subResults;

        if (plan.subTasks.isEmpty()) {
            subResults = List.of();
        } else if (plan.dependencies == null || plan.dependencies.isEmpty()) {
            subResults = taskExecutor.executeAll(plan.subTasks, progressConsumer);
        } else {
            subResults = executeWithDeps(plan, progressConsumer);
        }

        // 汇总子任务结果
        String summary = buildSummary(subResults);
        log.info("[COORDINATOR] Sub-tasks completed: {}/{} success",
            subResults.stream().filter(AgentResult::success).count(), subResults.size());

        // 将汇总结果注入主任务上下文
        AgentTask enhancedMain = new AgentTask(
            plan.mainTask.taskId(),
            plan.mainTask.prompt() + "\n\n## 子任务执行结果\n" + summary,
            plan.mainTask.toolNames(),
            plan.mainTask.context(),
            plan.mainTask.dataSourceId(),
            plan.mainTask.model(),
            plan.mainTask.blockedBy(),
            plan.mainTask.blocks()
        );

        AgentResult mainResult = taskExecutor.execute(enhancedMain);
        long totalDuration = System.currentTimeMillis() - start;

        log.info("[COORDINATOR] Coordination completed in {}ms", totalDuration);
        return new Result(mainResult, subResults, totalDuration);
    }

    private List<AgentResult> executeWithDeps(Plan plan, java.util.function.Consumer<ReActEvent> progressConsumer) {
        Map<String, AgentResult> completed = new ConcurrentHashMap<>();
        List<AgentResult> results = Collections.synchronizedList(new ArrayList<>());
        Map<String, List<String>> deps = plan.dependencies;

        Set<String> remaining = new LinkedHashSet<>();
        for (AgentTask t : plan.subTasks) remaining.add(t.taskId());

        int maxIterations = plan.subTasks.size() + 1;
        int iteration = 0;

        while (!remaining.isEmpty() && iteration < maxIterations) {
            iteration++;
            List<AgentTask> ready = new ArrayList<>();
            for (AgentTask t : plan.subTasks) {
                if (!remaining.contains(t.taskId())) continue;

                // 优先使用 Plan-level dependencies，回退到 AgentTask.blockedBy
                List<String> taskDeps = deps != null
                    ? deps.getOrDefault(t.taskId(), List.of())
                    : List.of();

                if (taskDeps.isEmpty() && !t.blockedBy().isEmpty()) {
                    taskDeps = t.blockedBy();
                }

                if (completed.keySet().containsAll(taskDeps)) {
                    ready.add(t);
                }
            }

            if (ready.isEmpty()) {
                log.warn("[COORDINATOR] Deadlock detected, executing remaining tasks directly");
                for (String tid : remaining) {
                    plan.subTasks.stream()
                        .filter(t -> t.taskId().equals(tid))
                        .findFirst()
                        .ifPresent(t -> {
                            AgentResult r = taskExecutor.execute(t);
                            results.add(r);
                            completed.put(t.taskId(), r);
                        });
                }
                break;
            }

            // 并行执行当前层级的所有就绪任务
            List<AgentResult> batchResults = taskExecutor.executeAll(ready, progressConsumer);
            for (AgentResult r : batchResults) {
                results.add(r);
                completed.put(r.taskId(), r);
                remaining.remove(r.taskId());
            }
        }

        return results;
    }

    // --- DAG cycle detection (Kahn's algorithm) ---

    /**
     * 使用 Kahn 算法检测依赖图中的环。
     * 如果存在环则抛出 IllegalStateException，包含参与环的任务 ID。
     */
    private void detectCycle(Plan plan) {
        if (plan.subTasks.isEmpty()) return;

        // Collect all task IDs
        Set<String> allTaskIds = plan.subTasks.stream()
            .map(AgentTask::taskId)
            .collect(Collectors.toSet());

        // Build adjacency list and in-degree map
        Map<String, Set<String>> adjacency = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : allTaskIds) {
            adjacency.put(id, new HashSet<>());
            inDegree.put(id, 0);
        }

        // Populate edges from plan-level dependencies
        Map<String, List<String>> deps = plan.dependencies;
        for (AgentTask task : plan.subTasks) {
            String taskId = task.taskId();
            List<String> taskDeps = (deps != null)
                ? deps.getOrDefault(taskId, List.of())
                : List.of();

            // Fall back to AgentTask.blockedBy if no plan-level deps
            if (taskDeps.isEmpty() && !task.blockedBy().isEmpty()) {
                taskDeps = task.blockedBy();
            }

            for (String depId : taskDeps) {
                if (allTaskIds.contains(depId)) {
                    // Edge: depId -> taskId (depId must complete before taskId)
                    if (adjacency.get(depId).add(taskId)) {
                        inDegree.merge(taskId, 1, Integer::sum);
                    }
                }
            }
        }

        // Kahn's algorithm
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visited.add(current);
            for (String neighbor : adjacency.getOrDefault(current, Set.of())) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (visited.size() < allTaskIds.size()) {
            Set<String> cycleNodes = new LinkedHashSet<>(allTaskIds);
            cycleNodes.removeAll(visited);
            String cycleDesc = cycleNodes.stream()
                .map(id -> {
                    List<String> taskDeps = (deps != null)
                        ? deps.getOrDefault(id, List.of())
                        : List.of();
                    AgentTask task = plan.subTasks.stream()
                        .filter(t -> t.taskId().equals(id))
                        .findFirst().orElse(null);
                    if ((taskDeps == null || taskDeps.isEmpty()) && task != null && !task.blockedBy().isEmpty()) {
                        taskDeps = task.blockedBy();
                    }
                    return id + " -> " + taskDeps;
                })
                .collect(Collectors.joining(", "));
            throw new IllegalStateException(
                "Dependency cycle detected among tasks: [" + cycleDesc + "]");
        }

        log.debug("[COORDINATOR] DAG validation passed, no cycles detected");
    }

    private String buildSummary(List<AgentResult> results) {
        if (results.isEmpty()) return "(无子任务)";

        StringBuilder sb = new StringBuilder();
        for (AgentResult r : results) {
            sb.append("- 任务 ").append(r.taskId()).append(": ");
            if (r.success()) {
                sb.append("成功 (").append(r.durationMs()).append("ms, ")
                  .append(r.tokenUsage()).append(" tokens)\n");
                if (r.output() != null && !r.output().isBlank()) {
                    String out = r.output();
                    if (out.length() > 500) out = out.substring(0, 500) + "...";
                    sb.append("  ").append(out).append("\n");
                }
            } else {
                sb.append("失败 (").append(r.error()).append(")\n");
            }
        }
        return sb.toString();
    }

    public record Plan(AgentTask mainTask, List<AgentTask> subTasks, Map<String, List<String>> dependencies) {
        public Plan(AgentTask mainTask, List<AgentTask> subTasks) {
            this(mainTask, subTasks, null);
        }
    }

    public record Result(AgentResult mainResult, List<AgentResult> subResults, long totalDurationMs) {}
}
