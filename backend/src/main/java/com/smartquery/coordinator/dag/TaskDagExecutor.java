package com.smartquery.coordinator.dag;

import com.smartquery.coordinator.executor.ModelTaskExecutor;
import com.smartquery.coordinator.model.Task;
import com.smartquery.coordinator.model.TaskResult;
import com.smartquery.coordinator.model.TaskState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 任务 DAG 执行器
 *
 * <p>按任务依赖关系执行，支持并行执行无依赖的任务
 */
@Slf4j
@Component
public class TaskDagExecutor {

    private final Executor executor;
    private final ModelTaskExecutor modelTaskExecutor;

    public TaskDagExecutor(
            @Qualifier("coordinatorExecutor") Executor executor,
            ModelTaskExecutor modelTaskExecutor) {
        this.executor = executor;
        this.modelTaskExecutor = modelTaskExecutor;
    }

    /**
     * 执行任务列表
     *
     * @param tasks 任务列表
     * @return 所有任务的执行结果
     */
    public List<TaskResult> execute(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return new ArrayList<>();
        }

        log.info("[DAG-EXECUTOR] Executing {} tasks", tasks.size());

        // 构建任务图
        TaskGraph graph = new TaskGraph(tasks);

        // 拓扑排序获取层级
        List<List<Task>> levels = graph.topologicalSort();

        log.info("[DAG-EXECUTOR] Task execution organized into {} levels", levels.size());

        // 按层级执行任务
        List<TaskResult> allResults = new ArrayList<>();

        for (int i = 0; i < levels.size(); i++) {
            List<Task> level = levels.get(i);
            log.info("[DAG-EXECUTOR] Executing level {} with {} tasks", i + 1, level.size());

            // 并行执行当前层级的所有任务
            List<CompletableFuture<TaskResult>> futures = new ArrayList<>();

            for (Task task : level) {
                CompletableFuture<TaskResult> future = CompletableFuture.supplyAsync(() -> {
                    return executeTask(task, graph);
                }, executor);

                futures.add(future);
            }

            // 等待当前层级所有任务完成
            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );

            try {
                allOf.join(); // 等待所有任务完成

                // 收集结果
                for (CompletableFuture<TaskResult> future : futures) {
                    TaskResult result = future.join();
                    allResults.add(result);
                    graph.setTaskResult(result.getTaskId(), result);
                }

            } catch (Exception e) {
                log.error("[DAG-EXECUTOR] Error executing level {}", i + 1, e);
                throw new RuntimeException("任务执行失败: " + e.getMessage(), e);
            }
        }

        log.info("[DAG-EXECUTOR] Completed {} tasks, {} results",
                 tasks.size(), allResults.size());

        return allResults;
    }

    /**
     * 执行单个任务
     */
    private TaskResult executeTask(Task task, TaskGraph graph) {
        String taskId = task.getTaskId();

        log.info("[DAG-EXECUTOR] Executing task: {} ({})", taskId, task.getDescription());

        // 更新任务状态为运行中
        graph.updateTaskState(taskId, TaskState.RUNNING);

        long startTime = System.currentTimeMillis();

        try {
            // 检查依赖是否都已完成
            if (!graph.isReadyToExecute(task)) {
                throw new IllegalStateException("任务依赖未满足: " + taskId);
            }

            // 根据任务类型执行不同的逻辑
            TaskResult result = executeByTaskType(task);

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("[DAG-EXECUTOR] Task completed: {} in {}ms", taskId, executionTime);

            return result;

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;

            log.error("[DAG-EXECUTOR] Task failed: {} after {}ms", taskId, executionTime, e);

            graph.updateTaskState(taskId, TaskState.FAILED);

            return TaskResult.builder()
                .taskId(taskId)
                .success(false)
                .error(e.getMessage())
                .executionTimeMs(executionTime)
                .build();
        }
    }

    /**
     * 根据任务类型执行不同的逻辑
     */
    private TaskResult executeByTaskType(Task task) {
        String taskType = task.getTaskType();

        // 路由到模型任务执行器
        if (taskType.startsWith("model_") || "mining_task".equals(taskType)) {
            return modelTaskExecutor.execute(task);
        }

        // 临时实现：返回成功结果
        return TaskResult.builder()
            .taskId(task.getTaskId())
            .success(true)
            .data("Task executed: " + task.getDescription())
            .executionTimeMs(0)
            .build();
    }
}
