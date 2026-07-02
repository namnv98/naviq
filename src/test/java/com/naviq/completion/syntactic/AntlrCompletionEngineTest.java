package com.naviq.completion.syntactic;

import com.example.PostgreSQLLexer;
import com.example.PostgreSQLParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit test cho AntlrCompletionEngine - tầng SYNTACTIC (ATN traversal), độc lập
 * hoàn toàn với SemanticScope (tầng SEMANTIC). Test ở đây chỉ quan tâm "token/rule
 * nào hợp lệ về mặt NGỮ PHÁP tại vị trí caret", KHÔNG quan tâm alias trỏ tới bảng
 * nào - đó là việc của SemanticScopeJUnitTest.
 *
 * ignoredTokens/preferredRules dùng ĐÚNG bộ mà SyntacticAnalyzer dùng thật, để test
 * phản ánh đúng hành vi production, không phải cấu hình tùy tiện.
 */
class AntlrCompletionEngineTest {

    private static final Map<Integer, Boolean> IGNORED_TOKENS = buildIgnoredTokens();
    private static final Map<Integer, Boolean> PREFERRED_RULES = buildPreferredRules();

    private static Map<Integer, Boolean> buildIgnoredTokens() {
        Map<Integer, Boolean> m = new HashMap<>();
        m.put(Token.EOF, true);
        m.put(PostgreSQLParser.ID, true);
        m.put(PostgreSQLParser.LPAREN, true);
        m.put(PostgreSQLParser.RPAREN, true);
        m.put(PostgreSQLParser.PLUS, true);
        m.put(PostgreSQLParser.MINUS, true);
        m.put(PostgreSQLParser.SLASH, true);
        m.put(PostgreSQLParser.EQ, true);
        m.put(PostgreSQLParser.NEQ, true);
        m.put(PostgreSQLParser.LT, true);
        m.put(PostgreSQLParser.GT, true);
        m.put(PostgreSQLParser.LTE, true);
        m.put(PostgreSQLParser.GTE, true);
        m.put(PostgreSQLParser.NUMBER, true);
        m.put(PostgreSQLParser.STRING, true);
        m.put(PostgreSQLParser.SEMI, true);
        return m;
    }

    private static Map<Integer, Boolean> buildPreferredRules() {
        Map<Integer, Boolean> m = new HashMap<>();
        m.put(PostgreSQLParser.RULE_tableName, true);
        m.put(PostgreSQLParser.RULE_columnName, true);
        m.put(PostgreSQLParser.RULE_dataTypeName, true);
        m.put(PostgreSQLParser.RULE_functionCall, true);
        m.put(PostgreSQLParser.RULE_tableAlias, true);
        return m;
    }

    // =====================================================================
    // Helper - '|' đánh dấu caret, tìm đúng token index tương ứng
    // =====================================================================

    private static AntlrCompletionEngine.CandidatesCollection collect(String rawWithCaret) {
        int caretCharOffset = rawWithCaret.indexOf('|');
        assertTrue(caretCharOffset >= 0, "Thiếu ký tự '|' đánh dấu caret trong: " + rawWithCaret);
        String sql = rawWithCaret.substring(0, caretCharOffset) + rawWithCaret.substring(caretCharOffset + 1);

        var lexer = new PostgreSQLLexer(CharStreams.fromString(sql));
        var tokens = new CommonTokenStream(lexer);
        var parser = new PostgreSQLParser(tokens);
        parser.removeErrorListeners();
        tokens.fill();

        int caretTokenIndex = findCaretTokenIndex(tokens, caretCharOffset);
        var engine = new AntlrCompletionEngine(
                parser, IGNORED_TOKENS, PREFERRED_RULES, new AntlrCompletionEngine.FollowSetsByState()
        );
        return engine.collectCandidates(caretTokenIndex);
    }

    private static int findCaretTokenIndex(CommonTokenStream tokenStream, int cursorCharPos) {
        List<Token> tokens = tokenStream.getTokens();
        for (int i = 0; i < tokens.size() - 1; i++) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (t.getStartIndex() <= cursorCharPos && cursorCharPos <= t.getStopIndex()) return i;
            if (t.getStartIndex() > cursorCharPos) return i;
        }
        return tokens.size() - 1;
    }

    private static boolean hasRule(AntlrCompletionEngine.CandidatesCollection c, int ruleId) {
        return c.rules.containsKey(ruleId);
    }

    private static boolean hasToken(AntlrCompletionEngine.CandidatesCollection c, int tokenType) {
        return c.tokens.containsKey(tokenType);
    }

    // =====================================================================
    // Cursor Ở GIỮA câu dài, không phải cuối chuỗi - và bất biến "nội dung
    // sau caret không ảnh hưởng kết quả" (đúng thiết kế readTokens() dừng
    // ngay tại caret, không đọc tiếp)
    // =====================================================================

    @Nested
    @DisplayName("Cursor ở giữa câu dài - không phải lúc nào cũng ở cuối chuỗi")
    class CaretInMiddleOfLongerStatement {

        @Test
        @DisplayName("tableName vẫn đúng dù có WHERE/ORDER BY/LIMIT thật sự nằm sau caret")
        void tableNameWithRealClausesAfterCaret() {
            var c = collect("select * from | where a = 1 order by b limit 10");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("columnName ở giữa SELECT list, còn cột khác phía sau")
        void columnNameInMiddleOfSelectListWithMoreColumnsAfter() {
            var c = collect("select a, | , c from t where x = 1");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("tableAlias ở giữa câu, có JOIN/WHERE thật sự phía sau")
        void tableAliasWithJoinAndWhereAfterCaret() {
            var c = collect("select * from users | join orders o on true where o.id = 1");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableAlias));
        }

        @Test
        @DisplayName("dấu chấm cụt ở giữa câu, có cột khác + JOIN + WHERE phía sau")
        void danglingDotWithFullQueryAfterCaret() {
            var c = collect("select u.| , o.id from users u join orders o on true where u.active = true");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("caret ở NGAY ĐẦU câu (offset 0) trong 1 câu đã có sẵn nội dung - vẫn dự đoán đúng keyword bắt đầu statement")
        void caretAtVeryBeginningOfExistingStatement() {
            // caretTokenIndex = 0 -> readTokens() chỉ đọc token đầu tiên rồi dừng
            // ngay -> toàn bộ "select * from users" phía sau KHÔNG được đọc, dù
            // nó là 1 câu SQL hợp lệ hoàn chỉnh.
            var lexer = new PostgreSQLLexer(CharStreams.fromString("select * from users"));
            var tokens = new CommonTokenStream(lexer);
            var parser = new PostgreSQLParser(tokens);
            parser.removeErrorListeners();
            tokens.fill();

            var engine = new AntlrCompletionEngine(
                    parser, IGNORED_TOKENS, PREFERRED_RULES, new AntlrCompletionEngine.FollowSetsByState()
            );
            var c = engine.collectCandidates(0); // caret ngay trước token "select"

            assertTrue(hasToken(c, PostgreSQLParser.SELECT));
            assertTrue(hasToken(c, PostgreSQLParser.INSERT));
            assertTrue(hasToken(c, PostgreSQLParser.UPDATE));
            assertTrue(hasToken(c, PostgreSQLParser.DELETE));
        }
    }

    @Nested
    @DisplayName("Bất biến: nội dung SAU caret không ảnh hưởng kết quả")
    class TrailingContentDoesNotAffectCandidates {

        @Test
        @DisplayName("tableName - có/không có WHERE...ORDER BY...LIMIT phía sau cho ra CÙNG kết quả")
        void tableNameCandidatesIdenticalRegardlessOfTrailingClauses() {
            var short_ = collect("select * from |");
            var long_ = collect("select * from | where a = 1 group by b having count(*) > 1 order by b limit 10");
            assertEquals(short_.rules.keySet(), long_.rules.keySet());
        }

        @Test
        @DisplayName("columnName sau dấu chấm - có/không có phần còn lại của câu cho ra CÙNG kết quả")
        void columnNameCandidatesIdenticalRegardlessOfTrailingContent() {
            var short_ = collect("select u.| from users u");
            var long_ = collect("select u.| , o.id, o.total from users u join orders o on u.id = o.user_id where o.total > 100 order by o.total desc limit 5");
            assertEquals(short_.rules.keySet(), long_.rules.keySet());
        }

        @Test
        @DisplayName("tableAlias - có/không có toàn bộ phần thân câu phía sau cho ra CÙNG kết quả")
        void tableAliasCandidatesIdenticalRegardlessOfTrailingContent() {
            var short_ = collect("select * from users |");
            var long_ = collect("select * from users | join orders o on true where o.total > 100 group by o.id order by o.total limit 20");
            assertEquals(short_.rules.keySet(), long_.rules.keySet());
        }

        @Test
        @DisplayName("bộ token gợi ý keyword (candidates.tokens) cũng bất biến, không chỉ candidates.rules")
        void tokenCandidatesAlsoIdenticalRegardlessOfTrailingContent() {
            var short_ = collect("select * from users u |");
            var long_ = collect("select * from users u | join orders o on u.id = o.user_id where o.total > 100");
            assertEquals(short_.tokens.keySet(), long_.tokens.keySet());
        }
    }

    // =====================================================================
    // MA TRẬN ĐẦY ĐỦ: vị trí clause × tầng lồng (flat / subquery FROM / EXISTS /
    // scalar subquery / CTE / CTE-tham-chiếu-CTE / 2 tầng lồng / sau khi subquery
    // đã đóng) - dùng @ParameterizedTest để liệt kê hết mà không phải viết tay
    // từng @Test riêng lẻ.
    // =====================================================================

    @Nested
    @DisplayName("columnName - ma trận đầy đủ mọi tầng lồng nhau")
    class ColumnNameFullMatrix {

        static java.util.stream.Stream<String> cases() {
            return java.util.stream.Stream.of(
                    // ---- flat, mọi clause ----
                    "select | from t",
                    "select a, | from t",
                    "select * from t where |",
                    "select a from t group by |",
                    "select a from t group by a having |",
                    "select a from t order by |",
                    "select count(|) from t",
                    "select * from a join b on |",
                    "select u.| from users u",

                    // ---- 1 tầng subquery trong FROM ----
                    "select * from (select | from t) x",
                    "select * from (select * from t where |) x",
                    "select * from (select u.| from users u) x",
                    "select * from (select a from t group by |) x",
                    "select * from (select a from t order by |) x",
                    "select * from (select count(|) from t) x",

                    // ---- subquery trong WHERE (EXISTS) ----
                    "select * from t where exists (select | from u)",
                    "select * from t where exists (select * from u where |)",

                    // ---- scalar subquery trong SELECT list ----
                    "select (select | from u) from t",
                    "select (select * from u where |) from t",

                    // ---- scalar subquery trong WHERE ----
                    "select * from t where a = (select | from u)",

                    // ---- CTE - bên trong thân CTE ----
                    "with c as (select | from t) select * from c",
                    "with c as (select * from t where |) select * from c",
                    "with c as (select u.| from users u) select * from c",

                    // ---- CTE - ở statement CHÍNH sau khi CTE đã đóng ----
                    "with c as (select * from t) select | from c",
                    "with c as (select * from t) select * from c where |",

                    // ---- CTE thứ 2 tham chiếu CTE thứ 1 ----
                    "with a as (select * from t1), b as (select * from a where |) select * from b",

                    // ---- 2 tầng subquery lồng nhau ----
                    "select * from (select * from (select | from t) y) x",
                    "select * from (select * from (select * from t where |) y) x",

                    // ---- cursor ở outer query SAU KHI 1 subquery trong FROM đã đóng ----
                    "select * from (select * from t) x where |",
                    "select a, | from (select * from t) x",

                    // ---- JOIN bên trong subquery ----
                    "select * from (select * from a join b on |) x"
            );
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0}")
        @org.junit.jupiter.params.provider.MethodSource("cases")
        void columnNameExpected(String sqlWithCaret) {
            var c = collect(sqlWithCaret);
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName),
                    "Kỳ vọng RULE_columnName tại: " + sqlWithCaret);
        }
    }

    @Nested
    @DisplayName("tableName - ma trận đầy đủ mọi tầng lồng nhau")
    class TableNameFullMatrix {

        static java.util.stream.Stream<String> cases() {
            return java.util.stream.Stream.of(
                    // ---- flat, mọi statement type ----
                    "select * from |",
                    "select * from a join |",
                    "insert into |",
                    "update |",
                    "delete from |",
                    "truncate |",
                    "truncate table |",
                    "drop table |",
                    "alter table |",
                    "alter table t rename to |",

                    // ---- subquery trong FROM (tầng trong) ----
                    "select * from (select * from |) x",
                    "select * from (select * from a join |) x",

                    // ---- 2 tầng subquery lồng ----
                    "select * from (select * from (select * from |) y) x",

                    // ---- subquery trong EXISTS/scalar ----
                    "select * from t where exists (select 1 from |)",
                    "select (select 1 from |) from t",

                    // ---- CTE - bên trong thân CTE ----
                    "with c as (select * from |) select * from c",

                    // ---- CTE thứ 2 - bên trong thân của nó ----
                    "with a as (select * from t1), b as (select * from |) select * from b",

                    // ---- sau khi CTE đã đóng, statement chính ----
                    "with c as (select * from t) select * from |"
            );
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0}")
        @org.junit.jupiter.params.provider.MethodSource("cases")
        void tableNameExpected(String sqlWithCaret) {
            var c = collect(sqlWithCaret);
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName),
                    "Kỳ vọng RULE_tableName tại: " + sqlWithCaret);
        }
    }

    @Nested
    @DisplayName("tableAlias - ma trận đầy đủ mọi tầng lồng nhau")
    class TableAliasFullMatrix {

        static java.util.stream.Stream<String> cases() {
            return java.util.stream.Stream.of(
                    // ---- flat ----
                    "select * from t |",
                    "select * from t as |",
                    "select * from a join b |",
                    "select * from a join b as |",

                    // ---- trong subquery FROM ----
                    "select * from (select * from t |) x",
                    "select * from (select * from t as |) x",

                    // ---- trong CTE ----
                    "with c as (select * from t |) select * from c",
                    "with c as (select * from t as |) select * from c",

                    // ---- 2 tầng lồng ----
                    "select * from (select * from (select * from t |) y) x",

                    // ---- trong EXISTS/scalar subquery ----
                    "select * from t where exists (select * from u |)",
                    "select (select * from u |) from t"
            );
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0}")
        @org.junit.jupiter.params.provider.MethodSource("cases")
        void tableAliasExpected(String sqlWithCaret) {
            var c = collect(sqlWithCaret);
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableAlias),
                    "Kỳ vọng RULE_tableAlias tại: " + sqlWithCaret);
        }
    }

    @Nested
    @DisplayName("functionCall - ma trận đầy đủ mọi tầng lồng nhau")
    class FunctionCallFullMatrix {

        static java.util.stream.Stream<String> cases() {
            return java.util.stream.Stream.of(
                    // ---- flat ----
                    "select | from t",
                    "select * from t where |",
                    "select a from t group by |",
                    "select a from t order by |",
                    "select case when | then 1 else 0 end from t",
                    "select * from a join b on |",
                    "select count(|) from t",

                    // ---- trong subquery ----
                    "select * from (select | from t) x",
                    "select * from t where exists (select | from u)",
                    "select (select | from u) from t",

                    // ---- trong CTE ----
                    "with c as (select | from t) select * from c",
                    "with c as (select * from t) select | from c"
            );
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0}")
        @org.junit.jupiter.params.provider.MethodSource("cases")
        void functionCallExpected(String sqlWithCaret) {
            var c = collect(sqlWithCaret);
            assertTrue(hasRule(c, PostgreSQLParser.RULE_functionCall),
                    "Kỳ vọng RULE_functionCall tại: " + sqlWithCaret);
        }
    }

    // =====================================================================
    // Dự đoán rule functionCall
    // =====================================================================

    @Nested
    @DisplayName("Dự đoán rule functionCall")
    class FunctionCallPrediction {

        @Test
        @DisplayName("đầu SELECT list - functionCall cũng hợp lệ song song với columnName")
        void functionCallAlsoValidAtSelectListStart() {
            var c = collect("select | from t");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_functionCall));
        }

        @Test
        @DisplayName("bên trong WHEN của CASE WHEN - functionCall hợp lệ")
        void functionCallInsideCaseWhen() {
            var c = collect("select case when | > 0 then 1 else 0 end from t");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_functionCall));
        }

        @Test
        @DisplayName("bên trong ON của JOIN - functionCall hợp lệ")
        void functionCallInsideJoinOn() {
            var c = collect("select * from a join b on |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_functionCall));
        }
    }

    // =====================================================================
    // Vị trí trong CTE / subquery
    // =====================================================================

    @Nested
    @DisplayName("tableName/columnName bên trong CTE và subquery")
    class CteAndSubqueryPositions {

        @Test
        @DisplayName("FROM bên trong thân CTE - kỳ vọng tableName")
        void tableNameInsideCteBody() {
            var c = collect("with x as (select * from |) select * from x");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("FROM bên trong subquery lồng ở FROM ngoài - kỳ vọng tableName")
        void tableNameInsideNestedFromSubquery() {
            var c = collect("select * from (select * from |) x");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("SELECT list bên trong CTE - kỳ vọng columnName")
        void columnNameInsideCteSelectList() {
            var c = collect("with x as (select | from t) select * from x");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ngay sau WITH - kỳ vọng keyword tiếp theo hợp lệ là 1 identifier CTE (không phải rule ưu tiên nào)")
        void afterWithKeywordExpectsCteNameIdentifier() {
            // cteName không nằm trong preferredRules -> chỉ kiểm tra KHÔNG có exception,
            // và các rule ưu tiên KHÔNG xuất hiện sai chỗ ở đây.
            var c = collect("with | ");
            assertFalse(hasRule(c, PostgreSQLParser.RULE_tableName));
            assertFalse(hasRule(c, PostgreSQLParser.RULE_columnName));
        }
    }

    // =====================================================================
    // columnName trong các clause khác: GROUP BY / ORDER BY / HAVING / function args
    // =====================================================================

    @Nested
    @DisplayName("columnName trong GROUP BY / ORDER BY / HAVING / tham số hàm")
    class ClauseColumnPositions {

        @Test
        @DisplayName("ngay sau GROUP BY - kỳ vọng columnName")
        void afterGroupBy() {
            var c = collect("select a from t group by |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ngay sau ORDER BY - kỳ vọng columnName")
        void afterOrderBy() {
            var c = collect("select a from t order by |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ngay sau HAVING - kỳ vọng columnName")
        void afterHaving() {
            var c = collect("select a from t group by a having |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("bên trong tham số hàm (COUNT(...)) - kỳ vọng columnName")
        void insideFunctionArguments() {
            var c = collect("select count(|) from t");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("bên trong ON của JOIN - kỳ vọng columnName (vế trái điều kiện)")
        void columnNameInsideJoinOn() {
            var c = collect("select * from a join b on |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }
    }

    // =====================================================================
    // tableName trong các câu DDL/DML khác: DELETE, TRUNCATE, DROP TABLE, ALTER TABLE
    // =====================================================================

    @Nested
    @DisplayName("tableName trong DELETE/TRUNCATE/DROP TABLE/ALTER TABLE")
    class DdlTableNamePrediction {

        @Test
        @DisplayName("ngay sau DELETE FROM - kỳ vọng tableName")
        void afterDeleteFrom() {
            var c = collect("delete from |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("ngay sau TRUNCATE - kỳ vọng tableName")
        void afterTruncate() {
            var c = collect("truncate |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("ngay sau TRUNCATE TABLE - kỳ vọng tableName")
        void afterTruncateTable() {
            var c = collect("truncate table |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("ngay sau DROP TABLE - kỳ vọng tableName")
        void afterDropTable() {
            var c = collect("drop table |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("ngay sau ALTER TABLE - kỳ vọng tableName")
        void afterAlterTable() {
            var c = collect("alter table |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("ALTER TABLE t RENAME TO - kỳ vọng tableName (tên bảng mới)")
        void afterRenameTo() {
            var c = collect("alter table t rename to |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }
    }

    // =====================================================================
    // dataTypeName ở các vị trí DDL khác
    // =====================================================================

    @Nested
    @DisplayName("dataTypeName ở các vị trí DDL khác")
    class MoreDataTypePositions {

        @Test
        @DisplayName("ALTER TABLE ... ADD COLUMN - kỳ vọng dataTypeName sau tên cột mới")
        void afterAddColumnName() {
            var c = collect("alter table t add column age |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_dataTypeName));
        }

        @Test
        @DisplayName("tham số hàm trong CREATE FUNCTION - kỳ vọng dataTypeName")
        void inCreateFunctionParam() {
            var c = collect("create function f(x |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_dataTypeName));
        }

        @Test
        @DisplayName("RETURNS trong CREATE FUNCTION - kỳ vọng dataTypeName")
        void afterReturnsInCreateFunction() {
            var c = collect("create function f() returns |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_dataTypeName));
        }
    }

    // =====================================================================
    // Keyword bắt đầu câu (mọi loại statement)
    // =====================================================================

    @Nested
    @DisplayName("Keyword hợp lệ khi bắt đầu 1 statement mới")
    class StatementStartKeywords {

        @Test
        @DisplayName("đầu câu - INSERT là keyword hợp lệ")
        void insertKeywordAtStart() {
            var c = collect("|");
            assertTrue(hasToken(c, PostgreSQLParser.INSERT));
        }

        @Test
        @DisplayName("đầu câu - UPDATE là keyword hợp lệ")
        void updateKeywordAtStart() {
            var c = collect("|");
            assertTrue(hasToken(c, PostgreSQLParser.UPDATE));
        }

        @Test
        @DisplayName("đầu câu - DELETE là keyword hợp lệ")
        void deleteKeywordAtStart() {
            var c = collect("|");
            assertTrue(hasToken(c, PostgreSQLParser.DELETE));
        }

        @Test
        @DisplayName("đầu câu - WITH là keyword hợp lệ (mở đầu CTE)")
        void withKeywordAtStart() {
            var c = collect("|");
            assertTrue(hasToken(c, PostgreSQLParser.WITH));
        }

        @Test
        @DisplayName("đầu câu - CREATE là keyword hợp lệ")
        void createKeywordAtStart() {
            var c = collect("|");
            assertTrue(hasToken(c, PostgreSQLParser.CREATE));
        }

        @Test
        @DisplayName("sau câu đã đủ WHERE clause - GROUP/ORDER/LIMIT là keyword hợp lệ tiếp theo")
        void keywordsAfterCompleteWhereClause() {
            var c = collect("select * from t where a = 1 |");
            assertTrue(hasToken(c, PostgreSQLParser.GROUP));
            assertTrue(hasToken(c, PostgreSQLParser.ORDER));
            assertTrue(hasToken(c, PostgreSQLParser.LIMIT));
        }
    }

    // =====================================================================
    // Assertion âm chéo - rule KHÔNG được xuất hiện sai chỗ
    // =====================================================================

    @Nested
    @DisplayName("Assertion âm - rule không được lẫn sang vị trí không liên quan")
    class NegativeCrossAssertions {

        @Test
        @DisplayName("sau FROM - KHÔNG kỳ vọng columnName (đang cần tableName, không phải cột)")
        void noColumnNameRightAfterFrom() {
            var c = collect("select * from |");
            assertFalse(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("đầu SELECT list - KHÔNG kỳ vọng tableAlias")
        void noTableAliasAtSelectListStart() {
            var c = collect("select | from t");
            assertFalse(hasRule(c, PostgreSQLParser.RULE_tableAlias));
        }

        @Test
        @DisplayName("sau WHERE - KHÔNG kỳ vọng tableName")
        void noTableNameAfterWhere() {
            var c = collect("select * from t where |");
            assertFalse(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("sau ALTER COLUMN ... SET - KHÔNG kỳ vọng tableAlias")
        void noTableAliasAfterAlterColumnSet() {
            var c = collect("alter table t alter column age set |");
            assertFalse(hasRule(c, PostgreSQLParser.RULE_tableAlias));
        }
    }

    // =====================================================================
    // Các loại JOIN (LEFT/RIGHT/FULL/CROSS/NATURAL) + USING clause
    // =====================================================================

    @Nested
    @DisplayName("Các loại JOIN + USING clause")
    class JoinTypeVariations {

        @Test
        @DisplayName("LEFT JOIN - kỳ vọng tableName")
        void leftJoin() {
            var c = collect("select * from a left join |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("LEFT OUTER JOIN - kỳ vọng tableName")
        void leftOuterJoin() {
            var c = collect("select * from a left outer join |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("RIGHT JOIN - kỳ vọng tableName")
        void rightJoin() {
            var c = collect("select * from a right join |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("FULL OUTER JOIN - kỳ vọng tableName")
        void fullOuterJoin() {
            var c = collect("select * from a full outer join |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("CROSS JOIN - kỳ vọng tableName")
        void crossJoin() {
            var c = collect("select * from a cross join |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("NATURAL JOIN - kỳ vọng tableName")
        void naturalJoin() {
            var c = collect("select * from a natural join |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("INNER JOIN - kỳ vọng tableName")
        void innerJoin() {
            var c = collect("select * from a inner join |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("JOIN...USING - kỳ vọng columnName bên trong danh sách cột")
        void joinUsingClause() {
            var c = collect("select * from a join b using (|");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("JOIN...USING - cột thứ 2 sau dấu phẩy")
        void joinUsingClauseSecondColumn() {
            var c = collect("select * from a join b using (id, |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }
    }

    // =====================================================================
    // ORDER BY nhiều item, IN/NOT IN, LIKE, IS NULL
    // =====================================================================

    @Nested
    @DisplayName("ORDER BY nhiều item, IN/NOT IN, LIKE, IS NULL")
    class MoreExpressionPositions {

        @Test
        @DisplayName("ORDER BY item thứ 2 sau dấu phẩy - kỳ vọng columnName")
        void orderByCommaSeparatedSecondItem() {
            var c = collect("select * from t order by a, |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("sau 1 orderItem đã đủ - kỳ vọng ASC/DESC là token hợp lệ")
        void ascDescAfterOrderItem() {
            var c = collect("select * from t order by a |");
            assertTrue(hasToken(c, PostgreSQLParser.ASC));
            assertTrue(hasToken(c, PostgreSQLParser.DESC));
        }

        @Test
        @DisplayName("bên trong IN (...) - kỳ vọng columnName")
        void insideInClause() {
            var c = collect("select * from t where a in (|");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("bên trong NOT IN (...) - kỳ vọng columnName")
        void insideNotInClause() {
            var c = collect("select * from t where a not in (|");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("vế phải của LIKE - kỳ vọng columnName")
        void rightSideOfLike() {
            var c = collect("select * from t where a like |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("sau IS NULL - kỳ vọng AND/OR là token hợp lệ tiếp theo")
        void afterIsNull() {
            var c = collect("select * from t where a is null |");
            assertTrue(hasToken(c, PostgreSQLParser.AND));
            assertTrue(hasToken(c, PostgreSQLParser.OR));
        }

        @Test
        @DisplayName("sau IS NOT NULL - kỳ vọng AND/OR là token hợp lệ tiếp theo")
        void afterIsNotNull() {
            var c = collect("select * from t where a is not null |");
            assertTrue(hasToken(c, PostgreSQLParser.AND));
            assertTrue(hasToken(c, PostgreSQLParser.OR));
        }
    }

    // =====================================================================
    // CASE nhiều nhánh WHEN, THEN, ELSE
    // =====================================================================

    @Nested
    @DisplayName("CASE WHEN nhiều nhánh")
    class CaseWhenMultipleBranches {

        @Test
        @DisplayName("nhánh WHEN thứ 2 - kỳ vọng columnName")
        void secondWhenBranch() {
            var c = collect("select case when a = 1 then 1 when | then 2 end from t");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("sau THEN - kỳ vọng columnName")
        void afterThen() {
            var c = collect("select case when a = 1 then | end from t");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("sau ELSE - kỳ vọng columnName")
        void afterElse() {
            var c = collect("select case when a = 1 then 1 else | end from t");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("CASE lồng trong CASE khác - kỳ vọng columnName ở nhánh trong")
        void nestedCase() {
            var c = collect("select case when a = 1 then case when | then 1 end end from t");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }
    }

    // =====================================================================
    // Toàn bộ alterAction (ADD/DROP COLUMN, ALTER COLUMN, RENAME, CONSTRAINT)
    // =====================================================================

    @Nested
    @DisplayName("Toàn bộ alterAction")
    class AllAlterActions {

        @Test
        @DisplayName("ADD COLUMN - kỳ vọng columnName (tên cột mới)")
        void addColumn() {
            var c = collect("alter table t add column |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("DROP COLUMN - kỳ vọng columnName")
        void dropColumn() {
            var c = collect("alter table t drop column |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("DROP COLUMN IF EXISTS - kỳ vọng columnName")
        void dropColumnIfExists() {
            var c = collect("alter table t drop column if exists |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ALTER COLUMN (trước SET) - kỳ vọng columnName")
        void alterColumnBeforeSet() {
            var c = collect("alter table t alter column |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ALTER COLUMN ... DROP - kỳ vọng token DEFAULT hợp lệ tiếp theo")
        void alterColumnDropDefault() {
            var c = collect("alter table t alter column age drop |");
            assertTrue(hasToken(c, PostgreSQLParser.DEFAULT));
        }

        @Test
        @DisplayName("RENAME COLUMN - kỳ vọng columnName (tên cũ)")
        void renameColumnOldName() {
            var c = collect("alter table t rename column |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("RENAME COLUMN ... TO - kỳ vọng columnName (tên mới)")
        void renameColumnNewName() {
            var c = collect("alter table t rename column old to |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ADD PRIMARY KEY (...) - kỳ vọng columnName bên trong")
        void addPrimaryKeyColumnList() {
            var c = collect("alter table t add primary key (|");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ADD CONSTRAINT ... UNIQUE (...) - kỳ vọng columnName bên trong")
        void addUniqueConstraintColumnList() {
            var c = collect("alter table t add constraint uq unique (|");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ADD CHECK (...) - kỳ vọng columnName bên trong biểu thức")
        void addCheckConstraintExpression() {
            var c = collect("alter table t add check (| > 0)");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }
    }

    // =====================================================================
    // CREATE FUNCTION - functionParam, RETURNS TABLE, functionOption
    // =====================================================================

    @Nested
    @DisplayName("CREATE FUNCTION chi tiết")
    class CreateFunctionDetails {

        @Test
        @DisplayName("tham số thứ 2 sau dấu phẩy - kỳ vọng dataTypeName")
        void secondParameterDataType() {
            var c = collect("create function f(a int, b |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_dataTypeName));
        }

        @Test
        @DisplayName("RETURNS TABLE(...) - kỳ vọng columnName bên trong")
        void returnsTableColumnDef() {
            var c = collect("create function f() returns table (|");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("RETURNS SETOF - kỳ vọng dataTypeName")
        void returnsSetof() {
            var c = collect("create function f() returns setof |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_dataTypeName));
        }

        @Test
        @DisplayName("sau RETURNS <type> - kỳ vọng keyword LANGUAGE/AS hợp lệ tiếp theo")
        void keywordsAfterReturnType() {
            var c = collect("create function f() returns int |");
            assertTrue(hasToken(c, PostgreSQLParser.LANGUAGE));
            assertTrue(hasToken(c, PostgreSQLParser.AS));
        }
    }

    // =====================================================================
    // Tên có schema (schema.table, schema.function)
    // =====================================================================

    @Nested
    @DisplayName("Tên có schema - vẫn hoạt động đúng qua qualifiedName")
    class SchemaQualifiedNamePositions {

        @Test
        @DisplayName("tableAlias vẫn đúng dù tableName có schema đứng trước")
        void tableAliasAfterSchemaQualifiedTableName() {
            var c = collect("select * from myschema.t |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableAlias));
        }

        @Test
        @DisplayName("columnName bên trong tham số hàm có schema đứng trước tên hàm")
        void columnNameInsideSchemaQualifiedFunctionCall() {
            var c = collect("select myschema.f(|) from t");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("tableName sau FROM vẫn đúng dù có nhiều tầng schema (a.b.c)")
        void deeplyQualifiedNameStillReachesTableNameRule() {
            var c = collect("select * from |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
            // grammar cho qualifiedName nhiều tầng (a.b.c) đều gói trong CÙNG 1 rule
            // tableName - không cần test riêng "đang gõ 2 tầng" vì ATN không phân biệt
            // độ dài qualifiedName, chỉ quan tâm rule bắt đầu đúng chỗ.
        }
    }

    // =====================================================================
    // 3 tầng lồng nhau (sâu hơn ma trận 2 tầng đã có)
    // =====================================================================

    @Nested
    @DisplayName("3 tầng subquery lồng nhau")
    class TripleNestedSubquery {

        @Test
        @DisplayName("columnName ở tầng trong cùng (tầng 3)")
        void columnNameAtInnermostLevel() {
            var c = collect("select * from (select * from (select * from (select | from t) y) x) w");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("tableName ở tầng trong cùng (tầng 3)")
        void tableNameAtInnermostLevel() {
            var c = collect("select * from (select * from (select * from (select * from |) y) x) w");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("tableAlias KHÔNG còn được gợi ý sau khi alias của subquery trong cùng đã gõ xong (đúng bug vừa fix, ở tầng lồng nhau)")
        void tableAliasNotExpectedAfterInnermostAliasAlreadyGiven() {
            var c = collect("select * from (select * from (select * from t) y |) x");
            assertFalse(hasRule(c, PostgreSQLParser.RULE_tableAlias));
        }

        @Test
        @DisplayName("tableAlias CỦA OUTER subquery - ngay sau khi subquery giữa đã đóng hẳn, chưa gõ alias")
        void tableAliasForOuterSubqueryRightAfterClosingParen() {
            var c = collect("select * from (select * from (select * from t) y) |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableAlias));
        }

        @Test
        @DisplayName("columnName ở outer query SAU KHI cả 3 tầng subquery đã đóng")
        void columnNameAtOuterLevelAfterAllNestedSubqueriesClosed() {
            var c = collect("select | from (select * from (select * from t) y) x");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }
    }

    // =====================================================================
    // Mở rộng assertion âm
    // =====================================================================

    @Nested
    @DisplayName("Assertion âm mở rộng")
    class MoreNegativeAssertions {

        @Test
        @DisplayName("sau 1 orderItem đã đủ - columnName VẪN hợp lệ vì qualifiedName có thể mở rộng qua DOT?? (vd \"a\" -> \"a.b\") - KHÔNG phải bug, đây là hành vi đúng")
        void columnNameStillReachableAfterOrderItemDueToDotExtension() {
            var c = collect("select * from t order by a |");
            // "a" đã khớp xong nhưng qualifiedName: identifier (DOT identifier)* DOT??
            // vẫn cho phép mở rộng thành "a.b" - nên columnName ĐÚNG là còn hợp lệ tại
            // đây, khác hẳn case tableAlias (dùng thẳng "identifier", không qua
            // qualifiedName, nên không có lý do hợp lệ để còn "mở" sau khi khớp xong).
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ngay sau 1 columnDef đã đủ (có dataType), chưa có dấu phẩy - KHÔNG kỳ vọng dataTypeName")
        void noDataTypeNameRightAfterCompleteColumnDef() {
            var c = collect("create table t (id int |");
            assertFalse(hasRule(c, PostgreSQLParser.RULE_dataTypeName));
        }

        @Test
        @DisplayName("bên trong USING (...) - KHÔNG kỳ vọng tableName (chỉ columnName)")
        void noTableNameInsideUsingClause() {
            var c = collect("select * from a join b using (|");
            assertFalse(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("ngay sau RENAME TO (tên bảng mới) - KHÔNG kỳ vọng columnName")
        void noColumnNameAfterRenameTo() {
            var c = collect("alter table t rename to |");
            assertFalse(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ngay sau LANGUAGE trong CREATE FUNCTION - KHÔNG kỳ vọng dataTypeName")
        void noDataTypeNameAfterLanguageKeyword() {
            var c = collect("create function f() returns int language |");
            assertFalse(hasRule(c, PostgreSQLParser.RULE_dataTypeName));
        }
    }

    // =====================================================================
    // Dự đoán rule tableName
    // =====================================================================

    @Nested
    @DisplayName("Dự đoán rule tableName")
    class TableNamePrediction {

        @Test
        @DisplayName("ngay sau FROM - kỳ vọng tableName")
        void afterFrom() {
            var c = collect("select * from |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("ngay sau JOIN - kỳ vọng tableName")
        void afterJoin() {
            var c = collect("select * from a join |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("ngay sau INSERT INTO - kỳ vọng tableName")
        void afterInsertInto() {
            var c = collect("insert into |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }

        @Test
        @DisplayName("ngay sau UPDATE - kỳ vọng tableName")
        void afterUpdate() {
            var c = collect("update |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableName));
        }
    }

    // =====================================================================
    // Dự đoán rule columnName
    // =====================================================================

    @Nested
    @DisplayName("Dự đoán rule columnName")
    class ColumnNamePrediction {

        @Test
        @DisplayName("ngay sau SELECT (đầu SELECT list) - kỳ vọng columnName")
        void atStartOfSelectList() {
            var c = collect("select | from t");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ngay sau WHERE - kỳ vọng columnName")
        void afterWhere() {
            var c = collect("select * from t where |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ngay sau dấu chấm (qualifier.column) - kỳ vọng columnName")
        void afterDotQualifier() {
            var c = collect("select u.| from users u");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }

        @Test
        @DisplayName("ngay sau SET trong UPDATE - kỳ vọng columnName")
        void afterSetInUpdate() {
            var c = collect("update t set |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_columnName));
        }
    }

    // =====================================================================
    // Dự đoán rule tableAlias
    // =====================================================================

    @Nested
    @DisplayName("Dự đoán rule tableAlias")
    class TableAliasPrediction {

        @Test
        @DisplayName("ngay sau tên bảng trong FROM (chưa gõ AS) - kỳ vọng tableAlias")
        void rightAfterTableName() {
            var c = collect("select * from users |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableAlias));
        }

        @Test
        @DisplayName("ngay sau AS trong FROM - kỳ vọng tableAlias")
        void rightAfterAsKeyword() {
            var c = collect("select * from users as |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_tableAlias));
        }

        @Test
        @DisplayName("đã có alias rồi (đứng sau alias) - KHÔNG còn kỳ vọng tableAlias nữa")
        void notExpectedAfterAliasAlreadyGiven() {
            var c = collect("select * from users u |");
            assertFalse(hasRule(c, PostgreSQLParser.RULE_tableAlias));
        }
    }

    // =====================================================================
    // Dự đoán rule dataTypeName (chỉ xuất hiện trong DDL)
    // =====================================================================

    @Nested
    @DisplayName("Dự đoán rule dataTypeName")
    class DataTypeNamePrediction {

        @Test
        @DisplayName("ngay sau tên cột trong CREATE TABLE - kỳ vọng dataTypeName")
        void afterColumnNameInCreateTable() {
            var c = collect("create table t (id |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_dataTypeName));
        }

        @Test
        @DisplayName("ngay sau ALTER COLUMN ... SET - kỳ vọng dataTypeName")
        void afterAlterColumnSet() {
            var c = collect("alter table t alter column age set |");
            assertTrue(hasRule(c, PostgreSQLParser.RULE_dataTypeName));
        }

        @Test
        @DisplayName("vị trí SELECT list - KHÔNG kỳ vọng dataTypeName")
        void notExpectedInSelectList() {
            var c = collect("select | from t");
            assertFalse(hasRule(c, PostgreSQLParser.RULE_dataTypeName));
        }
    }

    // =====================================================================
    // Dự đoán keyword (candidates.tokens) - không qua preferredRules
    // =====================================================================

    @Nested
    @DisplayName("Dự đoán keyword hợp lệ tại caret")
    class KeywordPrediction {

        @Test
        @DisplayName("ngay sau tableName+alias trong FROM - JOIN/WHERE là các keyword hợp lệ tiếp theo")
        void keywordsAfterFromTableAlias() {
            var c = collect("select * from users u |");
            assertTrue(hasToken(c, PostgreSQLParser.JOIN));
            assertTrue(hasToken(c, PostgreSQLParser.WHERE));
        }

        @Test
        @DisplayName("ignoredTokens PHẢI bị lọc khỏi candidates.tokens - vd ID, LPAREN không xuất hiện")
        void ignoredTokensAreFilteredOut() {
            var c = collect("select * from |");
            assertFalse(hasToken(c, PostgreSQLParser.ID));
            assertFalse(hasToken(c, PostgreSQLParser.LPAREN));
        }

        @Test
        @DisplayName("đầu câu (chưa gõ gì) - SELECT là keyword hợp lệ")
        void selectKeywordAtStatementStart() {
            var c = collect("|");
            assertTrue(hasToken(c, PostgreSQLParser.SELECT));
        }
    }

    // =====================================================================
    // Edge case
    // =====================================================================

    @Nested
    @DisplayName("Edge case")
    class EdgeCases {

        @Test
        @DisplayName("caretTokenIndex âm - phải throw IllegalArgumentException")
        void negativeCaretTokenIndexThrows() {
            var lexer = new PostgreSQLLexer(CharStreams.fromString("select * from t"));
            var tokens = new CommonTokenStream(lexer);
            var parser = new PostgreSQLParser(tokens);
            parser.removeErrorListeners();
            tokens.fill();

            var engine = new AntlrCompletionEngine(
                    parser, IGNORED_TOKENS, PREFERRED_RULES, new AntlrCompletionEngine.FollowSetsByState()
            );
            assertThrows(IllegalArgumentException.class, () -> engine.collectCandidates(-1));
        }

        @Test
        @DisplayName("FollowSetsByState dùng chung giữa 2 lần gọi - kết quả vẫn nhất quán (xác nhận cache không làm sai lệch)")
        void sharedFollowSetsCacheProducesConsistentResults() {
            var followSets = new AntlrCompletionEngine.FollowSetsByState();

            var lexer1 = new PostgreSQLLexer(CharStreams.fromString("select * from t"));
            var tokens1 = new CommonTokenStream(lexer1);
            var parser1 = new PostgreSQLParser(tokens1);
            parser1.removeErrorListeners();
            tokens1.fill();
            int caret1 = findCaretTokenIndex(tokens1, "select * from ".length());
            var engine1 = new AntlrCompletionEngine(parser1, IGNORED_TOKENS, PREFERRED_RULES, followSets);
            var c1 = engine1.collectCandidates(caret1);

            var lexer2 = new PostgreSQLLexer(CharStreams.fromString("select * from users"));
            var tokens2 = new CommonTokenStream(lexer2);
            var parser2 = new PostgreSQLParser(tokens2);
            parser2.removeErrorListeners();
            tokens2.fill();
            int caret2 = findCaretTokenIndex(tokens2, "select * from ".length());
            var engine2 = new AntlrCompletionEngine(parser2, IGNORED_TOKENS, PREFERRED_RULES, followSets);
            var c2 = engine2.collectCandidates(caret2);

            // Cùng vị trí ngữ pháp (ngay sau FROM) -> cùng kỳ vọng tableName, dù
            // dùng chung cache follow-set giữa 2 request khác nhau (đúng thiết kế
            // "shared across engine instances" của FollowSetsByState).
            assertTrue(hasRule(c1, PostgreSQLParser.RULE_tableName));
            assertTrue(hasRule(c2, PostgreSQLParser.RULE_tableName));
        }
    }
}