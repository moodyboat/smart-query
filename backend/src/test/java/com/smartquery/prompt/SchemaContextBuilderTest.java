package com.smartquery.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SchemaContextBuilder — 表名 normalize 工具方法测试。
 * 完整的 buildSchemaContext 集成测试需要 MyBatis-Plus + DataDictMapper mock，本期只覆盖纯函数。
 */
class SchemaContextBuilderTest {

    @Test
    void normalizeTableName_stripsSchemaPrefix() {
        assertEquals("users", SchemaContextBuilder.normalizeTableName("ods_dm.users"));
        assertEquals("orders", SchemaContextBuilder.normalizeTableName("public.orders"));
    }

    @Test
    void normalizeTableName_stripsWrappingChars() {
        assertEquals("order", SchemaContextBuilder.normalizeTableName("`Order`"));
        assertEquals("users", SchemaContextBuilder.normalizeTableName("[Users]"));
        assertEquals("users", SchemaContextBuilder.normalizeTableName("\"Users\""));
    }

    @Test
    void normalizeTableName_lowercase() {
        assertEquals("orders", SchemaContextBuilder.normalizeTableName("ORDERS"));
        assertEquals("orders", SchemaContextBuilder.normalizeTableName("Orders"));
    }

    @Test
    void normalizeTableName_plainName() {
        assertEquals("orders", SchemaContextBuilder.normalizeTableName("orders"));
    }

    @Test
    void normalizeTableName_null() {
        assertNull(SchemaContextBuilder.normalizeTableName(null));
    }

    @Test
    void normalizeTableName_emptyAndBlank() {
        assertEquals("", SchemaContextBuilder.normalizeTableName(""));
    }
}
