package com.smartquery.orchestration;

public final class RuntimeBuildStatus {
    public static final String QUEUED = "QUEUED";
    public static final String BUILDING = "BUILDING";
    public static final String RETRYABLE = "RETRYABLE";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELED = "CANCELED";

    private RuntimeBuildStatus() {}
}
