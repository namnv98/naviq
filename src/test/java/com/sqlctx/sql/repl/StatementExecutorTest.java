package com.sqlctx.sql.repl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** splitStatements() là logic thuần, không cần DB - test trực tiếp không qua CLI/terminal. */
class StatementExecutorTest {

    @Test
    void mộtCâuĐơnGiản() {
        assertEquals(List.of("select 1"), StatementExecutor.splitStatements("select 1"));
    }

    @Test
    void nhiềuCâuCáchNhauBằngDấuChấmPhẩy() {
        assertEquals(List.of("select 1", " select 2"),
                StatementExecutor.splitStatements("select 1; select 2"));
    }

    @Test
    void dấuChấmPhẩyCuốiCùngKhôngSinhCâuRỗng() {
        assertEquals(List.of("select 1"), StatementExecutor.splitStatements("select 1;"));
    }

    @Test
    void haiDấuChấmPhẩyLiềnNhauSinhCâuRỗngỞGiữa() {
        // Chỉ mẩu CUỐI CÙNG bị bỏ nếu rỗng (dấu ';' thừa ở cuối) - rỗng ở GIỮA vẫn được giữ nguyên,
        // để lỗi cú pháp "hai dấu ';' liền nhau" báo đúng ra thay vì bị nuốt âm thầm.
        assertEquals(List.of("select 1", "", " select 2"),
                StatementExecutor.splitStatements("select 1;; select 2"));
    }

    @Test
    void dấuChấmPhẩyTrongChuỗiKhôngBịTách() {
        assertEquals(List.of("select ';' as a"),
                StatementExecutor.splitStatements("select ';' as a"));
    }

    @Test
    void dollarQuoteRỗngBọcĐượcDấuChấmPhẩy() {
        String sql = "create function f() returns void as $$ select 1; select 2; $$ language sql";
        assertEquals(List.of(sql), StatementExecutor.splitStatements(sql));
    }

    @Test
    void dollarQuoteCóTênBọcĐượcDấuChấmPhẩy() {
        String sql = "create function f() returns void as $body$ select 1; $body$ language sql";
        assertEquals(List.of(sql), StatementExecutor.splitStatements(sql));
    }

    @Test
    void dollarQuoteĐóngRồiVẫnTáchĐượcCâuSau() {
        String sql = "create function f() returns void as $$ select 1; $$ language sql; select 2";
        assertEquals(List.of("create function f() returns void as $$ select 1; $$ language sql", " select 2"),
                StatementExecutor.splitStatements(sql));
    }

    @Test
    void thamSốViTríKiểuDollar1KhôngBịHiểuNhầmLàDollarQuote() {
        // "$1" không phải dollar-quote (tag phải bắt đầu bằng chữ/gạch dưới, không phải chữ số)
        String sql = "select $1 from t where id = $2";
        assertEquals(List.of(sql), StatementExecutor.splitStatements(sql));
    }

    @Test
    void chuỗiRỗngKhôngSinhCâuNào() {
        assertEquals(List.of(), StatementExecutor.splitStatements(""));
    }

    @Test
    void chỉToànKhoảngTrắngKhôngSinhCâuNào() {
        assertEquals(List.of(), StatementExecutor.splitStatements("   \n  "));
    }

    @org.junit.jupiter.api.Test
    void destructiveDetectionSeesThroughCommentsParensAndWritingCtes() {
        org.junit.jupiter.api.Assertions.assertTrue(StatementExecutor.isDestructive("-- dọn dẹp\nDELETE FROM t"));
        org.junit.jupiter.api.Assertions.assertTrue(StatementExecutor.isDestructive("/* x */ update t set a = 1"));
        org.junit.jupiter.api.Assertions.assertTrue(StatementExecutor.isDestructive("merge into t using s on (1=1) when matched then delete"));
        org.junit.jupiter.api.Assertions.assertTrue(StatementExecutor.isDestructive("with gone as (delete from t returning *) select count(*) from gone"));
        org.junit.jupiter.api.Assertions.assertFalse(StatementExecutor.isDestructive("select 'delete from t' as s"));
        org.junit.jupiter.api.Assertions.assertFalse(StatementExecutor.isDestructive("with x as (select 1) select * from x"));
        org.junit.jupiter.api.Assertions.assertFalse(StatementExecutor.isDestructive("insert into t values (1)"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("drop table t"),
                StatementExecutor.destructiveStatements(java.util.List.of("select 1", " drop table t ", "")));
    }

    // ------------------------------------------------------------------ chữ trong ô (cellText)

    /** ResultSet giả 1 cột: getObject trả {@code value}, getString trả {@code text}, kiểu cột {@code typeName}. */
    private static java.sql.ResultSet rs(Object value, String text) {
        return (java.sql.ResultSet) java.lang.reflect.Proxy.newProxyInstance(StatementExecutorTest.class.getClassLoader(),
                new Class<?>[]{java.sql.ResultSet.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getObject" -> value;
                    case "getString" -> text;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static java.sql.ResultSetMetaData meta(String typeName) {
        return (java.sql.ResultSetMetaData) java.lang.reflect.Proxy.newProxyInstance(StatementExecutorTest.class.getClassLoader(),
                new Class<?>[]{java.sql.ResultSetMetaData.class}, (proxy, m, args) -> typeName);
    }

    private static String cell(Object value, String text, String typeName, com.sqlctx.sql.schema.Dialect d) throws Exception {
        return StatementExecutor.cellText(rs(value, text), meta(typeName), 1, d);
    }

    @Test
    void byteHiệnDạngHexTheoDialect() throws Exception {
        byte[] b = {(byte) 0xde, (byte) 0xad};
        assertEquals("\\xdead", cell(b, null, "bytea", com.sqlctx.sql.schema.Dialect.POSTGRES));
        assertEquals("DEAD", cell(b, null, "RAW", com.sqlctx.sql.schema.Dialect.ORACLE));
    }

    @Test
    void lobHiệnNộiDung() throws Exception {
        assertEquals("xin chào", cell(new javax.sql.rowset.serial.SerialClob("xin chào".toCharArray()), null, "CLOB",
                com.sqlctx.sql.schema.Dialect.ORACLE));
        assertEquals("CAFE", cell(new javax.sql.rowset.serial.SerialBlob(new byte[]{(byte) 0xca, (byte) 0xfe}), null, "BLOB",
                com.sqlctx.sql.schema.Dialect.ORACLE));
    }

    @Test
    void kiểuCóMúiGiờVàIntervalLấyChữCủaDriver() throws Exception {
        assertEquals("2026-10-25 16:21:33+07", cell(new java.sql.Timestamp(0), "2026-10-25 16:21:33+07", "timestamptz",
                com.sqlctx.sql.schema.Dialect.POSTGRES));
        assertEquals("2 days 03:00:00", cell(new Object(), "2 days 03:00:00", "interval", com.sqlctx.sql.schema.Dialect.POSTGRES));
        assertEquals("<null>", cell(null, null, "int4", com.sqlctx.sql.schema.Dialect.POSTGRES));
        assertEquals("true", cell(Boolean.TRUE, "t", "bool", com.sqlctx.sql.schema.Dialect.POSTGRES), "boolean giữ true/false");
    }
}
