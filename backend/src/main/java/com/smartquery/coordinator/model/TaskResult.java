package com.smartquery.coordinator.model;

import java.util.Map;

/**
 * 任务执行结果
 */
public class TaskResult {
    private final String taskId;
    private final boolean success;
    private final Object data;
    private final String error;
    private final long executionTimeMs;
    private final Map<String, Object> metadata;

    public TaskResult(String taskId, boolean success, Object data,
                      String error, long executionTimeMs, Map<String, Object> metadata) {
        this.taskId = taskId;
        this.success = success;
        this.data = data;
        this.error = error;
        this.executionTimeMs = executionTimeMs;
        this.metadata = metadata;
    }

    public String getTaskId() {
        return taskId;
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getData() {
        return data;
    }

    public String getError() {
        return error;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public static TaskResult success(String taskId, Object data) {
        return new TaskResult(taskId, true, data, null, 0, null);
    }

    public static TaskResult success(String taskId, Object data, long executionTimeMs) {
        return new TaskResult(taskId, true, data, null, executionTimeMs, null);
    }

    public static TaskResult failure(String taskId, String error) {
        return new TaskResult(taskId, false, null, error, 0, null);
    }

    public static TaskResult failure(String taskId, String error, long executionTimeMs) {
        return new TaskResult(taskId, false, null, error, executionTimeMs, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String taskId;
        private boolean success;
        private Object data;
        private String error;
        private long executionTimeMs;
        private Map<String, Object> metadata;

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder data(Object data) {
            this.data = data;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public Builder executionTimeMs(long executionTimeMs) {
            this.executionTimeMs = executionTimeMs;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public TaskResult build() {
            return new TaskResult(taskId, success, data, error, executionTimeMs, metadata);
        }
    }
}
