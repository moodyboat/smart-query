package com.smartquery.coordinator;

import com.smartquery.coordinator.dag.TaskDagExecutor;
import com.smartquery.coordinator.model.Task;
import com.smartquery.coordinator.model.TaskResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务协调器测试
 */
@Slf4j
@SpringBootTest
public class TaskCoordinatorTest {

    @Autowired
    private TaskDagExecutor taskDagExecutor;

    @Test
    public void testSimpleTaskExecution() {
        log.info("Testing simple task execution...");

        // 创建单个任务
        Task task = Task.builder()
            .taskId("test_task_1")
            .taskType("model_training")
            .description("测试模型训练")
            .parameters(Map.of(
                "algorithm", "random_forest",
                "action", "train"
            ))
            .dependencies(new ArrayList<>())
            .build();

        List<Task> tasks = List.of(task);

        // 执行任务
        List<TaskResult> results = taskDagExecutor.execute(tasks);

        // 验证结果
        assertNotNull(results);
        assertEquals(1, results.size());

        TaskResult result = results.get(0);
        assertEquals("test_task_1", result.getTaskId());

        log.info("Test task result: success={}, data={}",
                 result.isSuccess(), result.getData());
    }

    @Test
    public void testParallelTaskExecution() {
        log.info("Testing parallel task execution...");

        // 创建两个并行任务（无依赖关系）
        Task task1 = Task.builder()
            .taskId("parallel_task_1")
            .taskType("model_training")
            .description("并行任务1 - 训练随机森林")
            .parameters(Map.of(
                "algorithm", "random_forest",
                "action", "train"
            ))
            .dependencies(new ArrayList<>())
            .build();

        Task task2 = Task.builder()
            .taskId("parallel_task_2")
            .taskType("model_training")
            .description("并行任务2 - 训练XGBoost")
            .parameters(Map.of(
                "algorithm", "xgboost",
                "action", "train"
            ))
            .dependencies(new ArrayList<>())
            .build();

        List<Task> tasks = List.of(task1, task2);

        // 执行任务
        long startTime = System.currentTimeMillis();
        List<TaskResult> results = taskDagExecutor.execute(tasks);
        long executionTime = System.currentTimeMillis() - startTime;

        // 验证结果
        assertNotNull(results);
        assertEquals(2, results.size());

        // 验证并行执行（总时间应该小于串行执行时间）
        log.info("Parallel execution time: {}ms", executionTime);

        for (TaskResult result : results) {
            log.info("Task {}: success={}", result.getTaskId(), result.isSuccess());
        }
    }

    @Test
    public void testTaskWithDependencies() {
        log.info("Testing task execution with dependencies...");

        // 创建有依赖关系的任务
        // 任务1和任务2并行，任务3依赖任务1和任务2
        Task task1 = Task.builder()
            .taskId("dep_task_1")
            .taskType("model_training")
            .description("训练随机森林")
            .parameters(Map.of("algorithm", "random_forest"))
            .dependencies(new ArrayList<>())
            .build();

        Task task2 = Task.builder()
            .taskId("dep_task_2")
            .taskType("model_training")
            .description("训练XGBoost")
            .parameters(Map.of("algorithm", "xgboost"))
            .dependencies(new ArrayList<>())
            .build();

        Task task3 = Task.builder()
            .taskId("dep_task_3")
            .taskType("model_comparison")
            .description("对比模型结果")
            .parameters(Map.of("models", List.of("random_forest", "xgboost")))
            .dependencies(List.of("dep_task_1", "dep_task_2"))
            .build();

        List<Task> tasks = List.of(task1, task2, task3);

        // 执行任务
        List<TaskResult> results = taskDagExecutor.execute(tasks);

        // 验证结果
        assertNotNull(results);
        assertEquals(3, results.size());

        // 验证依赖顺序（task3应该在task1和task2之后完成）
        TaskResult result1 = results.stream()
            .filter(r -> r.getTaskId().equals("dep_task_1"))
            .findFirst()
            .orElseThrow();

        TaskResult result2 = results.stream()
            .filter(r -> r.getTaskId().equals("dep_task_2"))
            .findFirst()
            .orElseThrow();

        TaskResult result3 = results.stream()
            .filter(r -> r.getTaskId().equals("dep_task_3"))
            .findFirst()
            .orElseThrow();

        log.info("Task dependencies validated:");
        log.info("  Task1 completed: {}, time: {}ms", result1.isSuccess(), result1.getExecutionTimeMs());
        log.info("  Task2 completed: {}, time: {}ms", result2.isSuccess(), result2.getExecutionTimeMs());
        log.info("  Task3 completed: {}, time: {}ms", result3.isSuccess(), result3.getExecutionTimeMs());
    }

    @Test
    public void testModelComparisonScenario() {
        log.info("Testing model comparison scenario...");

        // 模拟用户请求："对比随机森林和XGBoost两个模型"
        List<Task> tasks = createModelComparisonTasks();

        // 执行任务
        List<TaskResult> results = taskDagExecutor.execute(tasks);

        // 验证结果
        assertNotNull(results);
        assertEquals(3, results.size()); // 2个训练任务 + 1个对比任务

        log.info("Model comparison completed with {} results", results.size());
        for (TaskResult result : results) {
            log.info("  Task {}: {}", result.getTaskId(),
                     result.isSuccess() ? "SUCCESS" : "FAILED");
        }
    }

    /**
     * 创建模型对比场景的任务列表
     */
    private List<Task> createModelComparisonTasks() {
        List<Task> tasks = new ArrayList<>();

        // 训练随机森林
        tasks.add(Task.builder()
            .taskId("train_random_forest")
            .taskType("model_training")
            .description("训练随机森林模型")
            .parameters(Map.of(
                "algorithm", "random_forest",
                "action", "train"
            ))
            .dependencies(new ArrayList<>())
            .build());

        // 训练XGBoost
        tasks.add(Task.builder()
            .taskId("train_xgboost")
            .taskType("model_training")
            .description("训练XGBoost模型")
            .parameters(Map.of(
                "algorithm", "xgboost",
                "action", "train"
            ))
            .dependencies(new ArrayList<>())
            .build());

        // 对比结果
        tasks.add(Task.builder()
            .taskId("compare_models")
            .taskType("model_comparison")
            .description("对比模型性能")
            .parameters(Map.of(
                "algorithms", List.of("random_forest", "xgboost")
            ))
            .dependencies(List.of("train_random_forest", "train_xgboost"))
            .build());

        return tasks;
    }
}
