package com.smartquery.common;

public final class ModelStatus {
    private ModelStatus() {}

    public static final String DRAFT = "draft";
    public static final String TRAINING = "training";
    public static final String TRAINED = "trained";
    public static final String PUBLISHED = "published";
    public static final String FAILED = "failed";
    public static final String OFFLINE = "offline";

    // Execution status
    public static final String EXEC_QUEUED = "queued";
    public static final String EXEC_RUNNING = "running";
    public static final String EXEC_SUCCESS = "success";
    public static final String EXEC_CANCELED = "canceled";

    // Pipeline status
    public static final String PIPELINE_READY = "ready";
    public static final String PIPELINE_COMPLETED = "completed";
}
