package com.sqlctx.cli.command;

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
}
