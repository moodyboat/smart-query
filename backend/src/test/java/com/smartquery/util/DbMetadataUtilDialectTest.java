package com.smartquery.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DbMetadataUtil.Dialect — 业务库类型到方言的映射测试。
 * 覆盖 DictController/MetadataConfigController/MiningModelController 改造后的方言判断。
 */
class DbMetadataUtilDialectTest {

    @Test
    void dialect_of_recognizesDm() {
        assertEquals(DbMetadataUtil.Dialect.DM, DbMetadataUtil.Dialect.of("dm"));
        assertEquals(DbMetadataUtil.Dialect.DM, DbMetadataUtil.Dialect.of("DM"));
    }

    @Test
    void dialect_of_recognizesGbase() {
        assertEquals(DbMetadataUtil.Dialect.GBASE, DbMetadataUtil.Dialect.of("gbase"));
        assertEquals(DbMetadataUtil.Dialect.GBASE, DbMetadataUtil.Dialect.of("GBASE"));
    }

    @Test
    void dialect_of_defaultsToMysql() {
        assertEquals(DbMetadataUtil.Dialect.MYSQL, DbMetadataUtil.Dialect.of("mysql"));
        assertEquals(DbMetadataUtil.Dialect.MYSQL, DbMetadataUtil.Dialect.of("postgresql"));
        assertEquals(DbMetadataUtil.Dialect.MYSQL, DbMetadataUtil.Dialect.of("oracle"));
    }

    @Test
    void dialect_of_handlesNullAsMysql() {
        assertEquals(DbMetadataUtil.Dialect.MYSQL, DbMetadataUtil.Dialect.of(null));
    }
}
