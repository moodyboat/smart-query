package com.smartquery.coordinator;

import com.smartquery.coordinator.model.Task;
import com.smartquery.coordinator.model.TaskResult;

import java.util.List;

/**
 * 任务协调器接口
 *
 * <p>负责分解复杂任务为子任务，按依赖关系执行，并汇总结果
 */
public interface TaskCoordinator {

    /**
     * 协调执行主任务和子任务
     *
     * @param mainTask 主任务描述
     * @param subTasks 子任务列表
     * @return 所有任务的执行结果
     */
    List<TaskResult> coordinate(String mainTask, List<Task> subTasks);

    /**
     * 检查是否支持协调该类型的任务
     *
     * @param taskType 任务类型
     * @return 是否支持
     */
    boolean supports(String taskType);

    /**
     * 获取协调器名称
     *
     * @return 协调器名称
     */
    String getName();
}
