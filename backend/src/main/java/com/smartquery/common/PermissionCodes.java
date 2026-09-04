package com.smartquery.common;

/**
 * Stable application capabilities checked by protected operations.
 *
 * <p>Roles are intentionally not represented here. Administrators compose roles
 * from these capabilities in the database, so no authorization decision depends
 * on a role name.</p>
 */
public final class PermissionCodes {

    public static final String RESOURCE_ACCESS_ALL = "resource.access.all";
    public static final String USER_MANAGE = "platform.user.manage";
    public static final String ROLE_MANAGE = "platform.role.manage";
    public static final String SCENARIO_MANAGE = "platform.scenario.manage";
    public static final String DATASOURCE_MANAGE = "platform.datasource.manage";
    public static final String ALGORITHM_MANAGE = "model.algorithm.manage";
    public static final String MODEL_REVIEW = "model.version.review";
    public static final String OPERATOR_REVIEW = "operator.version.review";
    public static final String RUNTIME_MANAGE = "governance.runtime.manage";
    public static final String MONITOR_VIEW = "platform.monitor.view";

    private PermissionCodes() {
    }
}
