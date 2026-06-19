package com.smartquery.coordinator.executor;

import com.smartquery.coordinator.model.Task;
import com.smartquery.coordinator.model.TaskResult;
import com.smartquery.service.MiningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
     *
     * <p>协调器对比路径已停用（见 {@link com.smartquery.engine.CoordinatorIntegration#needsCoordination}）。
     * 真实训练走 {@code MiningModelTool}（action=train）→ {@code MiningService.trainModel}。
     * 本方法不再返回任何模拟/假数据；若被误调用，明确失败。
     */
    public TaskResult executeModelTraining(Task task) {
        log.warn("[MODEL-TASK] executeModelTraining invoked but coordinator path is disabled (task={}). Use mining_model tool (action=train) instead.", task.getTaskId());
        return TaskResult.failure(task.getTaskId(),
            "协调器训练路径已停用，请通过 mining_model 工具（action=train）执行真实训练");
    }

    /**
     * 执行模型对比任务
     *
     * <p>协调器对比路径已停用。真实多算法对比走 {@code MiningModelTool}（action=compare，
     * 需 source_table + target_column）→ {@code MiningService.trainModel}。
     * 本方法不再返回任何模拟/假数据；若被误调用，明确失败。
     */
    public TaskResult executeModelComparison(Task task) {
        log.warn("[MODEL-TASK] executeModelComparison invoked but coordinator path is disabled (task={}). Use mining_model tool (action=compare) instead.", task.getTaskId());
        return TaskResult.failure(task.getTaskId(),
            "协调器对比路径已停用，请通过 mining_model 工具（action=compare，需 source_table + target_column）执行真实对比");
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
