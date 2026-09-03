package com.smartquery.orchestration;

/** Separate version lifecycle from mutable definition and execution states. */
public final class VersionStatus {
    private VersionStatus() {}

    public static final String CANDIDATE = "CANDIDATE";
    public static final String VALIDATED = "VALIDATED";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String REJECTED = "REJECTED";
    public static final String DEPRECATED = "DEPRECATED";
}
