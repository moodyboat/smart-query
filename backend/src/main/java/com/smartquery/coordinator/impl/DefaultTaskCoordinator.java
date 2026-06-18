package com.smartquery.coordinator.impl;

import com.smartquery.coordinator.TaskCoordinator;
import com.smartquery.coordinator.dag.TaskDagExecutor;
import com.smartquery.coordinator.model.Task;
import com.smartquery.coordinator.model.TaskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认任务协调器实现
 *
 * <p>使用 DAG 执行器按依赖关系执行任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultTaskCoordinator implements TaskCoordinator {

    private final TaskDagExecutor dagExecutor;

    @Override
    public List<TaskResult> coordinate(String mainTask, List<Task> subTasks) {
        log.info("[COORDINATOR] Starting coordination for main task: {}, subTasks: {}",
                 mainTask, subTasks.size());

        try {
            // 使用 DAG 执行器执行任务
            List<TaskResult> results = dagExecutor.execute(subTasks);

            log.info("[COORDINATOR] Completed coordination for main task: {}, results: {}",
                     mainTask, results.size());

            return results;

        } catch (Exception e) {
            log.error("[COORDINATOR] Coordination failed for main task: {}", mainTask, e);

            // 返回失败结果
            List<TaskResult> failureResults = new ArrayList<>();
            for (Task task : subTasks) {
                if (task.getResult() == null) {
                    failureResults.add(TaskResult.failure(task.getTaskId(), e.getMessage()));
                } else {
                    failureResults.add(task.getResult());
                }
            }
            return failureResults;
        }
    }

    @Override
    public boolean supports(String taskType) {
        // 默认协调器支持所有任务类型
        return true;
    }

    @Override
    public String getName() {
        return "DefaultTaskCoordinator";
    }
}
