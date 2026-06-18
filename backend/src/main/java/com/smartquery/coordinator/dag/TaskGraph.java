package com.smartquery.coordinator.dag;

import com.smartquery.coordinator.model.Task;
import com.smartquery.coordinator.model.TaskState;

import java.util.*;

/**
 * 任务图 - 用于管理任务依赖关系
 */
public class TaskGraph {
    private final Map<String, Task> tasks;
    private final Map<String, Set<String>> adjacencyList; // task -> dependents
    private final Map<String, Set<String>> reverseAdjacencyList; // task -> dependencies

    public TaskGraph(List<Task> tasks) {
        this.tasks = new LinkedHashMap<>();
        this.adjacencyList = new HashMap<>();
        this.reverseAdjacencyList = new HashMap<>();

        for (Task task : tasks) {
            this.tasks.put(task.getTaskId(), task);
            this.adjacencyList.put(task.getTaskId(), new HashSet<>());
            this.reverseAdjacencyList.put(task.getTaskId(), new HashSet<>());
        }

        // 构建依赖关系图
        for (Task task : tasks) {
            if (task.getDependencies() != null) {
                for (String depId : task.getDependencies()) {
                    if (this.tasks.containsKey(depId)) {
                        reverseAdjacencyList.get(task.getTaskId()).add(depId);
                        adjacencyList.get(depId).add(task.getTaskId());
                    }
                }
            }
        }
    }

    /**
     * 拓扑排序 - 返回按层级组织的任务列表
     * 每一层中的任务可以并行执行
     */
    public List<List<Task>> topologicalSort() {
        List<List<Task>> levels = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();

        // 找出所有没有依赖的任务（入度为0）
        for (Task task : tasks.values()) {
            if (reverseAdjacencyList.get(task.getTaskId()).isEmpty()) {
                queue.offer(task.getTaskId());
            }
        }

        while (!queue.isEmpty()) {
            List<Task> currentLevel = new ArrayList<>();
            int levelSize = queue.size();

            for (int i = 0; i < levelSize; i++) {
                String taskId = queue.poll();
                if (visited.contains(taskId)) {
                    continue;
                }

                visited.add(taskId);
                Task task = tasks.get(taskId);
                if (task != null && task.getState() != TaskState.COMPLETED) {
                    currentLevel.add(task);
                }

                // 将依赖当前任务的其他任务加入队列
                for (String dependent : adjacencyList.get(taskId)) {
                    Set<String> deps = reverseAdjacencyList.get(dependent);
                    if (deps != null && visited.containsAll(deps)) {
                        queue.offer(dependent);
                    }
                }
            }

            if (!currentLevel.isEmpty()) {
                levels.add(currentLevel);
            }
        }

        // 检查是否有循环依赖
        if (visited.size() != tasks.size()) {
            throw new IllegalStateException("检测到循环依赖，无法执行任务");
        }

        return levels;
    }

    /**
     * 检查任务是否可以执行（所有依赖都已完成）
     */
    public boolean isReadyToExecute(Task task) {
        Set<String> dependencies = reverseAdjacencyList.get(task.getTaskId());
        if (dependencies == null || dependencies.isEmpty()) {
            return true;
        }

        for (String depId : dependencies) {
            Task depTask = tasks.get(depId);
            if (depTask == null || !depTask.isCompleted()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 获取任务的所有依赖
     */
    public Set<String> getDependencies(String taskId) {
        return new HashSet<>(reverseAdjacencyList.getOrDefault(taskId, Collections.emptySet()));
    }

    /**
     * 获取依赖当前任务的所有任务
     */
    public Set<String> getDependents(String taskId) {
        return new HashSet<>(adjacencyList.getOrDefault(taskId, Collections.emptySet()));
    }

    /**
     * 获取所有任务
     */
    public Collection<Task> getAllTasks() {
        return tasks.values();
    }

    /**
     * 根据ID获取任务
     */
    public Task getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 更新任务状态
     */
    public void updateTaskState(String taskId, TaskState newState) {
        Task task = tasks.get(taskId);
        if (task != null) {
            task.setState(newState);
        }
    }

    /**
     * 设置任务结果
     */
    public void setTaskResult(String taskId, com.smartquery.coordinator.model.TaskResult result) {
        Task task = tasks.get(taskId);
        if (task != null) {
            task.setResult(result);
            task.setState(TaskState.COMPLETED);
        }
    }
}
