package com.sqlctx.sql.completion;

import com.sqlctx.sql.completion.suggestion.SuggestionSupport;
import com.sqlctx.sql.completion.syntactic.oracle.OracleSyntacticAnalyzer;
import com.sqlctx.sql.completion.syntactic.postgresql.PostgresSyntacticAnalyzer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class CandidatesCacheTest {

    /** Menu (caret lùi vào từ đang gõ) và dòng báo lỗi (câu bỏ từ đang gõ) cùng 1 phím: engine chỉ chạy 1 lần. */
    @Test
    void menuAndSyntaxHintShareOneEngineRun() {
        String sql = "select * from users u where u.id = 1 and wh";
        String withoutWord = "select * from users u where u.id = 1 and ";

        var menu = PostgresSyntacticAnalyzer.analyze(sql, SuggestionSupport.syntacticCursor(sql, sql.length()));
        var hint = PostgresSyntacticAnalyzer.analyze(withoutWord, withoutWord.length());
        assertSame(menu.candidates(), hint.candidates());

        var oraMenu = OracleSyntacticAnalyzer.analyze(sql, SuggestionSupport.syntacticCursor(sql, sql.length()));
        var oraHint = OracleSyntacticAnalyzer.analyze(withoutWord, withoutWord.length());
        assertSame(oraMenu.candidates(), oraHint.candidates());
    }

    @Test
    void differentTextBeforeCaretOrTooFewTokensIsNotShared() {
        var a = PostgresSyntacticAnalyzer.analyze("select * from users where ", 26);
        var b = PostgresSyntacticAnalyzer.analyze("select * from orders where ", 27);
        assertNotSame(a.candidates(), b.candidates());
        // < 2 token trước caret: không cache (predicate Oracle đọc 2 token đầu câu)
        var c = OracleSyntacticAnalyzer.analyze("sel", 2);
        var d = OracleSyntacticAnalyzer.analyze("", 0);
        assertNotSame(c.candidates(), d.candidates());
    }
}
