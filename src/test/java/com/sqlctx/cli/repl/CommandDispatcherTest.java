package com.sqlctx.cli.repl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** isMetaCommand() là logic thuần (chỉ so chuỗi) - không cần terminal/DB. */
class CommandDispatcherTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "help", "HELP", "\\?", "exit", "EXIT", "\\q",
            "\\l", "\\list", "\\dt", "\\dn", "\\df", "\\du",
            "\\o", "\\o out.csv", "\\e", "\\ctx", "\\context",
            "\\c mydb", "\\connect mydb", "\\d users", "\\i script.sql",
            "\\ctx prod", "\\context prod", "", "   "
    })
    void nhậnDiệnLệnhMeta(String line) {
        assertTrue(CommandDispatcher.isMetaCommand(line));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select 1", "\\dtx", "\\connect", "\\c", "\\d", "\\i",
            "insert into t values (1)", "\\dux"
    })
    void khôngPhảiLệnhMetaThìTrảVềFalse(String line) {
        // "\\connect"/"\\c"/"\\d"/"\\i" KHÔNG có khoảng trắng theo sau nên không khớp (chỉ dạng
        // "\\c <tên>" mới là lệnh; đứng một mình thì rơi vào nhánh câu SQL bình thường) - khác với
        // "\\ctx"/"\\context" đứng một mình, LẠI được coi là meta (xem ctxVàContextĐứngMộtMìnhVẫnLàMeta).
        assertFalse(CommandDispatcher.isMetaCommand(line));
    }

    @Test
    void ctxVàContextĐứngMộtMìnhVẫnLàMeta() {
        assertTrue(CommandDispatcher.isMetaCommand("\\ctx"));
        assertTrue(CommandDispatcher.isMetaCommand("\\context"));
    }

    @Test
    void khoảngTrắngXungQuanhKhôngẢnhHưởng() {
        assertTrue(CommandDispatcher.isMetaCommand("  \\q  "));
        assertTrue(CommandDispatcher.isMetaCommand("  help  "));
    }
}
