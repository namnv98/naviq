package com.sqlctx.completion;

import com.sqlctx.antlr4.oracle.PlSqlParser;
import com.sqlctx.antlr4.postgresql.PostgreSQLParser;
import com.sqlctx.completion.semantic.oracle.OracleSemanticAnalyzer;
import com.sqlctx.completion.semantic.postgresql.PostgresSemanticAnalyzer;
import com.sqlctx.completion.syntactic.oracle.OracleSyntacticAnalyzer;
import com.sqlctx.completion.syntactic.postgresql.PostgresSyntacticAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Token giả tại caret (CaretToken) không được để lại dấu vết trong scope, và phải đúng LOẠI mà grammar cho phép ở đó (tầng cú pháp chọn, tầng ngữ nghĩa chèn).
 * Trước đây luôn chèn định danh: ở chỗ chỉ nhận literal (vd "varchar(|)") parse vỡ và ANTLR bỏ luôn
 * subquery bao ngoài - mất hết alias (Oracle còn sinh alias rác từ chính token giả).
 * <p>
 * Kiểm ở tầng ngữ nghĩa (alias nhìn thấy tại caret), không qua gợi ý: ở những chỗ chỉ nhận literal
 * thì không gợi ý tên nào nên lỗi này không lộ ra danh sách gợi ý.
 */
class CaretTokenTest {

    // Không có "x": caret nằm TRONG subquery x, mà subquery trong FROM không thấy mục FROM cùng cấp (kể cả chính
    // nó) - Postgres từ chối tham chiếu x ở đó. Oracle chưa áp quy tắc này nên vẫn còn x.
    private static final Map<String, String> PG_ALIASES = Map.of("u", "users");
    private static final Map<String, String> ORA_ALIASES = Map.of("x", "<subquery#2>", "u", "users");

    @Test
    @DisplayName("PG 'varchar(|)' chỉ nhận số nguyên: chèn literal số, subquery và alias không bị mất")
    void pgIntegerOnlyPosition() {
        assertPg("select * from (select * from users u where u.name::varchar(|) = 'a') x",
                PostgreSQLParser.Integral, PG_ALIASES);
    }

    @Test
    @DisplayName("PG 'interval |' chỉ nhận chuỗi: chèn literal chuỗi, subquery và alias không bị mất")
    void pgStringOnlyPosition() {
        assertPg("select * from (select * from users u where u.created_at > interval |) x",
                PostgreSQLParser.StringConstant, PG_ALIASES);
    }

    @Test
    @DisplayName("PG chỗ nhận biểu thức: vẫn chèn định danh như cũ")
    void pgExpressionPositionStillIdentifier() {
        assertPg("select * from (select * from users u where u.id = |) x",
                PostgreSQLParser.Identifier, PG_ALIASES);
    }

    @Test
    @DisplayName("PG lỗi cú pháp TRƯỚC caret (tầng cú pháp không đoán được gì): quay về định danh")
    void pgFallsBackToIdentifierWhenNothingPredicted() {
        assertPg("select * from (select * from users u where u.id = = |) x",
                PostgreSQLParser.Identifier, null);
    }

    @Test
    @DisplayName("Oracle 'varchar2(|)' chỉ nhận số: chèn literal số, không sinh alias rác từ token giả")
    void oracleIntegerOnlyPosition() {
        assertOra("select * from (select cast(u.name as varchar2(|)) c from users u) x",
                PlSqlParser.UNSIGNED_INTEGER, ORA_ALIASES);
    }

    @Test
    @DisplayName("Oracle lỗi cú pháp TRƯỚC caret: quay về định danh")
    void oracleFallsBackToIdentifierWhenNothingPredicted() {
        assertOra("select * from (select * from users u where u.id = = |) x",
                PlSqlParser.REGULAR_ID, null);
    }

    // ---- token giả không được thành alias/bảng (trước đây: {zzzcursorzzz=users}, {zzzcursorzzz=zzzcursorzzz}...) ----

    @Test
    @DisplayName("'from users |' (định gõ alias/keyword): bảng vẫn nhìn thấy theo tên, không có alias giả")
    void caretAfterTableIsNotAnAlias() {
        assertAliases("select * from users |", Map.of("users", "users"));
    }

    @Test
    @DisplayName("'join |' / 'from |' / ', |': token giả không thành bảng")
    void caretAtTablePositionIsNotATable() {
        assertAliases("select * from |", Map.of());
        assertAliases("select * from users u join |", Map.of("u", "users"));
        assertAliases("select * from users u, |", Map.of("u", "users"));
        assertAliases("select * from users u where u.id in (select id from |)", Map.of("u", "users"));
    }

    @Test
    @DisplayName("'(subquery) |': token giả không thành alias của subquery")
    void caretAfterSubqueryIsNotAnAlias() {
        assertAliases("select * from (select * from users) |", Map.of());
    }

    @Test
    @DisplayName("DML: 'update |' không có bảng giả, 'delete from users |' vẫn thấy users")
    void caretInDmlTarget() {
        assertAliases("update |", Map.of());
        assertAliases("delete from users |", Map.of("users", "users"));
    }

    /** Cả 2 dialect, đi đúng đường production (tầng cú pháp chọn loại token -> tầng ngữ nghĩa). */
    private static void assertAliases(String sqlWithCaret, Map<String, String> expected) {
        int caret = sqlWithCaret.indexOf('|');
        String sql = sqlWithCaret.replace("|", "");
        int pgType = PostgresSyntacticAnalyzer.caretTokenTypeToInsert(PostgresSyntacticAnalyzer.analyze(sql, caret).candidates());
        assertEquals(expected, PostgresSemanticAnalyzer.analyze(sql, caret, pgType).visibleAliases(), "postgres: " + sqlWithCaret);
        int oraType = OracleSyntacticAnalyzer.caretTokenTypeToInsert(OracleSyntacticAnalyzer.analyze(sql, caret).candidates());
        assertEquals(expected, OracleSemanticAnalyzer.analyze(sql, caret, oraType).visibleAliases(), "oracle: " + sqlWithCaret);
    }

    /** @param expectedAliases null = chỉ kiểm loại token được chọn */
    private static void assertPg(String sqlWithCaret, int expectedType, Map<String, String> expectedAliases) {
        int caret = sqlWithCaret.indexOf('|');
        String sql = sqlWithCaret.replace("|", "");
        int type = PostgresSyntacticAnalyzer.caretTokenTypeToInsert(PostgresSyntacticAnalyzer.analyze(sql, caret).candidates());
        assertEquals(PostgreSQLParser.VOCABULARY.getSymbolicName(expectedType), PostgreSQLParser.VOCABULARY.getSymbolicName(type));
        if (expectedAliases != null) {
            assertEquals(expectedAliases, PostgresSemanticAnalyzer.analyze(sql, caret, type).visibleAliases());
        }
    }

    private static void assertOra(String sqlWithCaret, int expectedType, Map<String, String> expectedAliases) {
        int caret = sqlWithCaret.indexOf('|');
        String sql = sqlWithCaret.replace("|", "");
        int type = OracleSyntacticAnalyzer.caretTokenTypeToInsert(OracleSyntacticAnalyzer.analyze(sql, caret).candidates());
        assertEquals(PlSqlParser.VOCABULARY.getSymbolicName(expectedType), PlSqlParser.VOCABULARY.getSymbolicName(type));
        if (expectedAliases != null) {
            assertEquals(expectedAliases, OracleSemanticAnalyzer.analyze(sql, caret, type).visibleAliases());
        }
    }
}
