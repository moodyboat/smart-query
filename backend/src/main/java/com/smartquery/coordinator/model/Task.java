package com.smartquery.coordinator.model;

import java.util.Map;
import java.util.List;

/**
 * 协调任务定义
 */
public class Task {
    private final String taskId;
    private final String taskType;
    private final String description;
    private final Map<String, Object> parameters;
    private final List<String> dependencies;
    private TaskState state;
    private TaskResult result;

    public Task(String taskId, String taskType, String description,
                Map<String, Object> parameters, List<String> dependencies) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.description = description;
        this.parameters = parameters;
        this.dependencies = dependencies;
        this.state = TaskState.PENDING;
        this.result = null;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public TaskState getState() {
        return state;
    }

    public void setState(TaskState state) {
        this.state = state;
    }

    public TaskResult getResult() {
        return result;
    }

    public void setResult(TaskResult result) {
        this.result = result;
    }

    public boolean isReady() {
        return state == TaskState.PENDING &&
               (dependencies == null || dependencies.isEmpty());
    }

    public boolean isCompleted() {
        return state == TaskState.COMPLETED;
    }

    public boolean isFailed() {
        return state == TaskState.FAILED;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String taskId;
        private String taskType;
        private String description;
        private Map<String, Object> parameters;
        private List<String> dependencies;

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder taskType(String taskType) {
            this.taskType = taskType;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder dependencies(List<String> dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        public Task build() {
            return new Task(taskId, taskType, description, parameters, dependencies);
        }
    }
}
