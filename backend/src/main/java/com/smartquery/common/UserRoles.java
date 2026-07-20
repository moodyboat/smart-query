package com.smartquery.common;

/**
 * 用户角色常量 — 全局唯一来源。
 *
 * <p>所有角色字面量比较必须使用这里的常量，禁止散落 "admin" / "user" 字符串。
 * 角色 value 写入 DB（sq_user.role），变更需迁移历史数据。
 */
public final class UserRoles {

    public static final String ADMIN = "admin";
    public static final String USER = "user";

    private UserRoles() {
    }
}
