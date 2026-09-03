package com.smartquery.orchestration;

import java.util.Set;

public final class RunStatus {
    public static final String QUEUED = "QUEUED";
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMMITTING = "COMMITTING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCELED = "CANCELED";
    public static final String TIMED_OUT = "TIMED_OUT";
    public static final Set<String> TERMINAL = Set.of(SUCCESS, FAILED, CANCELED);

    private RunStatus() {
    }
}
