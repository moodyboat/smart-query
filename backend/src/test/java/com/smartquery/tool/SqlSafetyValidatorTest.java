package com.smartquery.tool;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlSafetyValidator — 场景表白名单拦截的边界测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>extractTableNames：JSqlParser 正常路径（含 CTE / 子查询 / JOIN / schema 前缀 / 反引号）</li>
 *   <li>extractTableNames：parser 失败回退正则</li>
 *   <li>normalizeTableName：去前缀 / 去包裹符 / 小写</li>
 *   <li>validateAgainstWhitelist：白名单命中/越界/无白名单/解析失败保守拒绝</li>
 *   <li>validate() 旧行为不破坏</li>
 * </ul>
 */
class SqlSafetyValidatorTest {

    private final SqlSafetyValidator validator = SqlSafetyValidator.defaults();

    // ---------------- extractTableNames ----------------

    @Test
    void extractTableNames_simpleSelect() {
        Set<String> tables = validator.extractTableNames("SELECT * FROM orders");
        assertEquals(Set.of("orders"), tables);
    }

    @Test
    void extractTableNames_multiTableJoin() {
        Set<String> tables = validator.extractTableNames(
            "SELECT a.* FROM orders a JOIN customers b ON a.customer_id = b.id");
        assertEquals(Set.of("orders", "customers"), tables);
    }

    @Test
    void extractTableNames_subquery() {
        Set<String> tables = validator.extractTableNames(
            "SELECT * FROM (SELECT id FROM payments) t");
        assertTrue(tables.contains("payments"));
    }

    @Test
    void extractTableNames_cteWith() {
        Set<String> tables = validator.extractTableNames(
            "WITH recent AS (SELECT * FROM orders WHERE dt > '2026-01-01') " +
            "SELECT * FROM recent JOIN customers ON recent.cid = customers.id");
        assertTrue(tables.contains("orders"));
        assertTrue(tables.contains("customers"));
    }

    @Test
    void extractTableNames_schemaPrefix() {
        Set<String> tables = validator.extractTableNames("SELECT * FROM ods_dm.users");
        // normalize 后去前缀
        assertTrue(tables.contains("users"));
        assertFalse(tables.contains("ods_dm.users"));
    }

    @Test
    void extractTableNames_backticks() {
        Set<String> tables = validator.extractTableNames("SELECT * FROM `Order`");
        assertTrue(tables.contains("order"));
    }

    @Test
    void extractTableNames_unparseableFallsBackToRegex() {
        // 不合法 SQL → JSqlParser 抛异常 → 正则兜底仍能提到 orders
        Set<String> tables = validator.extractTableNames("BLABLA FROM orders WHERE");
        assertTrue(tables.contains("orders"));
    }

    @Test
    void extractTableNames_blankReturnsEmpty() {
        assertTrue(validator.extractTableNames("").isEmpty());
        assertTrue(validator.extractTableNames(null).isEmpty());
        assertTrue(validator.extractTableNames("   ").isEmpty());
    }

    // ---------------- normalizeTableName ----------------

    @Test
    void normalizeTableName_stripsSchemaPrefix() {
        assertEquals("users", SqlSafetyValidator.normalizeTableName("ods_dm.users"));
        assertEquals("orders", SqlSafetyValidator.normalizeTableName("schema.orders"));
    }

    @Test
    void normalizeTableName_stripsWrappingChars() {
        assertEquals("order", SqlSafetyValidator.normalizeTableName("`Order`"));
        assertEquals("users", SqlSafetyValidator.normalizeTableName("[Users]"));
        assertEquals("users", SqlSafetyValidator.normalizeTableName("\"Users\""));
    }

    @Test
    void normalizeTableName_lowercaseAndTrim() {
        assertEquals("orders", SqlSafetyValidator.normalizeTableName("  Orders "));
        assertEquals("users", SqlSafetyValidator.normalizeTableName("USERS"));
    }

    @Test
    void normalizeTableName_null() {
        assertNull(SqlSafetyValidator.normalizeTableName(null));
    }

    // ---------------- validateAgainstWhitelist ----------------

    @Test
    void whitelist_nullOrEmptyMeansNoRestriction() {
        // 白名单为 null/empty → 不限（等同现状）
        assertEquals(true, validator.validateAgainstWhitelist("SELECT * FROM anything", null).safe());
        assertEquals(true, validator.validateAgainstWhitelist("SELECT * FROM anything", Set.of()).safe());
    }

    @Test
    void whitelist_inRange() {
        Set<String> allowed = Set.of("orders", "customers");
        var r = validator.validateAgainstWhitelist("SELECT * FROM orders", allowed);
        assertTrue(r.safe());
    }

    @Test
    void whitelist_violating() {
        Set<String> allowed = Set.of("orders", "customers");
        var r = validator.validateAgainstWhitelist("SELECT * FROM payments", allowed);
        assertFalse(r.safe());
        assertTrue(r.reason().contains("payments"));
    }

    @Test
    void whitelist_joinPartialViolation() {
        Set<String> allowed = Set.of("orders");
        var r = validator.validateAgainstWhitelist(
            "SELECT * FROM orders a JOIN payments b ON a.id = b.oid", allowed);
        assertFalse(r.safe());
        assertTrue(r.reason().contains("payments"));
    }

    @Test
    void whitelist_caseInsensitive() {
        Set<String> allowed = Set.of("orders");  // 小写
        var r = validator.validateAgainstWhitelist("SELECT * FROM ORDERS", allowed);
        assertTrue(r.safe());
    }

    @Test
    void whitelist_schemaPrefixNormalized() {
        Set<String> allowed = Set.of("users");  // 不带前缀
        var r = validator.validateAgainstWhitelist("SELECT * FROM ods_dm.users", allowed);
        assertTrue(r.safe());
    }

    @Test
    void whitelist_showTablesUnparseable_rejectedWhenWhitelistActive() {
        // SHOW TABLES 解析不出表名，白名单激活时保守拒绝
        Set<String> allowed = Set.of("orders");
        var r = validator.validateAgainstWhitelist("SHOW TABLES", allowed);
        assertFalse(r.safe());
        assertTrue(r.reason().contains("无法解析"));
    }

    @Test
    void whitelist_showTablesOkWhenNoWhitelist() {
        // 没白名单 → SHOW TABLES 通过（等同现状）
        var r = validator.validateAgainstWhitelist("SHOW TABLES", null);
        assertTrue(r.safe());
    }

    @Test
    void whitelist_deniedKeywordStillCaughtFirst() {
        // 基础校验先跑：DROP 即便有白名单也被拒
        Set<String> allowed = Set.of("orders");
        var r = validator.validateAgainstWhitelist("DROP TABLE orders", allowed);
        assertFalse(r.safe());
        // 命中黑名单而不是白名单错误
        assertTrue(r.reason().contains("禁止") || r.reason().contains("不支持"));
    }

    @Test
    void whitelist_multiStatementRejected() {
        Set<String> allowed = Set.of("orders");
        var r = validator.validateAgainstWhitelist(
            "SELECT * FROM orders; DROP TABLE payments", allowed);
        assertFalse(r.safe());
    }

    @Test
    void whitelist_emptySqlUnsafe() {
        Set<String> allowed = Set.of("orders");
        var r = validator.validateAgainstWhitelist("", allowed);
        assertFalse(r.safe());
    }

    // ---------------- validate() 旧行为不破坏 ----------------

    @Test
    void validate_selectOk() {
        assertTrue(validator.validate("SELECT 1").safe());
    }

    @Test
    void validate_dropRejected() {
        assertFalse(validator.validate("DROP TABLE users").safe());
    }

    @Test
    void validate_multiStatementRejected() {
        assertFalse(validator.validate("SELECT 1; DROP TABLE x").safe());
    }

    @Test
    void validate_blankRejected() {
        assertFalse(validator.validate("").safe());
        assertFalse(validator.validate("   ").safe());
    }
}
