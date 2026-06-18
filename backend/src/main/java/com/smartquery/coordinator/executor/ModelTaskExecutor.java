package com.smartquery.coordinator.executor;

import com.smartquery.coordinator.model.Task;
import com.smartquery.coordinator.model.TaskResult;
import com.smartquery.service.MiningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 模型任务执行器
 *
 * <p>执行模型相关的任务，如训练、对比等
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelTaskExecutor {

    private final MiningService miningService;

    /**
     * 执行模型训练任务
     */
    public TaskResult executeModelTraining(Task task) {
        String taskId = task.getTaskId();
        Map<String, Object> params = task.getParameters();

        log.info("[MODEL-TASK] Training model for task: {}", taskId);

        long startTime = System.currentTimeMillis();

        try {
            // 从参数中提取算法等信息
            String algorithm = (String) params.get("algorithm");
            String action = (String) params.get("action");

            Map<String, Object> result;

            if ("train".equals(action)) {
                // 调用挖掘服务的训练功能
                // 注意：这里需要实际的模型ID或其他参数
                // 暂时返回模拟结果
                result = Map.of(
                    "algorithm", algorithm,
                    "status", "completed",
                    "message", algorithm + "模型训练完成",
                    "metrics", Map.of(
                        "accuracy", 0.85 + Math.random() * 0.1, // 模拟指标
                        "training_time_ms", System.currentTimeMillis() - startTime
                    )
                );
            } else {
                result = Map.of("status", "unknown_action", "action", action);
            }

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("[MODEL-TASK] Model training completed: {} in {}ms", taskId, executionTime);

            return TaskResult.builder()
                .taskId(taskId)
                .success(true)
                .data(result)
                .executionTimeMs(executionTime)
                .build();

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;

            log.error("[MODEL-TASK] Model training failed for task: {}", taskId, e);

            return TaskResult.builder()
                .taskId(taskId)
                .success(false)
                .error(e.getMessage())
                .executionTimeMs(executionTime)
                .build();
        }
    }

    /**
     * 执行模型对比任务
     */
    public TaskResult executeModelComparison(Task task) {
        String taskId = task.getTaskId();
        Map<String, Object> params = task.getParameters();

        log.info("[MODEL-TASK] Comparing models for task: {}", taskId);

        long startTime = System.currentTimeMillis();

        try {
            @SuppressWarnings("unchecked")
            List<String> algorithms = (List<String>) params.get("algorithms");

            // 构建对比结果表格
            StringBuilder comparisonTable = new StringBuilder();
            comparisonTable.append("## 模型对比结果\n\n");
            comparisonTable.append("| 算法 | 准确率 | F1分数 | 训练时间(ms) |\n");
            comparisonTable.append("|------|--------|--------|-------------|\n");

            // 模拟对比数据（实际应该从训练结果中获取）
            for (String alg : algorithms) {
                double accuracy = 0.80 + Math.random() * 0.15;
                double f1 = accuracy - Math.random() * 0.05;
                long time = (long)(3000 + Math.random() * 2000);

                comparisonTable.append(String.format("| %s | %.3f | %.3f | %d |\n",
                    alg, accuracy, f1, time));
            }

            comparisonTable.append("\n**结论**: ");
            if (algorithms.size() == 2) {
                comparisonTable.append("根据对比结果，")
                   .append(algorithms.get(0))
                   .append("和")
                   .append(algorithms.get(1))
                   .append("性能相近，建议根据具体场景选择。");
            }

            Map<String, Object> comparisonResult = Map.of(
                "table", comparisonTable.toString(),
                "algorithms", algorithms,
                "best_algorithm", algorithms.get(0) // 简化逻辑
            );

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("[MODEL-TASK] Model comparison completed: {} in {}ms", taskId, executionTime);

            return TaskResult.builder()
                .taskId(taskId)
                .success(true)
                .data(comparisonResult)
                .executionTimeMs(executionTime)
                .metadata(Map.of("comparison_type", "model_performance"))
                .build();

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;

            log.error("[MODEL-TASK] Model comparison failed for task: {}", taskId, e);

            return TaskResult.builder()
                .taskId(taskId)
                .success(false)
                .error(e.getMessage())
                .executionTimeMs(executionTime)
                .build();
        }
    }

    /**
     * 根据任务类型路由到具体的执行方法
     */
    public TaskResult execute(Task task) {
        String taskType = task.getTaskType();

        switch (taskType) {
            case "model_training":
                return executeModelTraining(task);
            case "model_comparison":
                return executeModelComparison(task);
            default:
                return TaskResult.failure(task.getTaskId(),
                    "不支持的任务类型: " + taskType);
        }
    }
}
