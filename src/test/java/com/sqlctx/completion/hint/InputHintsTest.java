package com.sqlctx.completion.hint;

import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import com.sqlctx.util.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InputHintsTest {

    private Map<String, String> savedSignatures;

    @BeforeEach
    void setUp() {
        savedSignatures = SchemaIndex.functionSignatures;
        SchemaIndex.functionSignatures = Map.of("date_trunc", "(text, timestamp) → timestamp +3");
        InputHints.clearCache();
        I18n.set(I18n.Lang.EN, false); // so khớp thông báo lỗi tiếng Anh ("syntax error near ...")
    }

    @AfterEach
    void tearDown() {
        SchemaIndex.functionSignatures = savedSignatures;
        InputHints.clearCache();
        I18n.set(I18n.Lang.VI, false);
    }

    private static String at(String sqlWithCursor, Dialect dialect) {
        int c = sqlWithCursor.indexOf('|');
        return InputHints.hintLine(sqlWithCursor.substring(0, c) + sqlWithCursor.substring(c + 1), c, dialect);
    }

    @Test
    void signatureMarksArgumentBeingTyped() {
        assertEquals("date_trunc([text], timestamp) → timestamp +3", at("select date_trunc(| from t", Dialect.POSTGRES));
        assertEquals("date_trunc(text, [timestamp]) → timestamp +3", at("select date_trunc('day', |) from t", Dialect.POSTGRES));
        // ngoặc lồng bên trong tham số không làm lệch vị trí tham số
        assertEquals("date_trunc(text, [timestamp]) → timestamp +3", at("select date_trunc('day', coalesce(a, b) |", Dialect.POSTGRES));
        // caret trong hàm lồng không có chữ ký đã biết: không hiện chữ ký của hàm ngoài
        assertNull(at("select date_trunc('day', coalesce(a, b|)", Dialect.POSTGRES));
    }

    @Test
    void noHintOutsideCallsOrForIncompleteStatement() {
        assertNull(at("select * from users where |", Dialect.POSTGRES)); // câu chưa xong: không phải lỗi
        assertNull(at("select date_trunc('day', x) |", Dialect.POSTGRES)); // ngoặc đã đóng
        assertNull(at("select * from users u join or|", Dialect.POSTGRES)); // đang gõ dở 1 từ
    }

    @Test
    void reportsSyntaxErrorInMiddleOfStatement() {
        String hint = at("select * from users wher id = 1 order by |", Dialect.POSTGRES);
        assertNotNull(hint);
        assertTrue(hint.startsWith("syntax error near 'id'"), hint);
        assertTrue(hint.contains("Ln 1, Col 26"), hint);
    }

    @Test
    void oracleSyntaxErrorAndMetaCommandsIgnored() {
        assertNotNull(at("select * from dual wher x = 1 order by |", Dialect.ORACLE));
        assertNull(at("\\d users|", Dialect.POSTGRES));
    }
}
