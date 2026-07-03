package com.naviq.completion.semantic;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.naviq.antlr4.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ test JUnit 5 cho SemanticScope - thay cho bản main()-based cũ (in PASS/FAIL thủ công). Chạy
 * được qua "mvn test", tích hợp IDE/CI, mỗi case là 1 @Test riêng nên fail case nào biết ngay case
 * đó, không cần đọc log từ đầu tới cuối.
 * <p>
 * Toàn bộ giá trị kỳ vọng giữ NGUYÊN từ bộ test main()-based đã chạy thực nghiệm và xác nhận đúng
 * qua nhiều vòng review trước đó - đây chỉ là đổi HÌNH THỨC chạy/assert, KHÔNG đổi ý nghĩa test.
 */
class SemanticScopeJUnitTest {

    /**
     * Kết quả resolve tại 1 vị trí cursor - dùng để assert gọn trong @Test.
     */
    private record Resolved(Map<String, String> aliases, String resolve) {

    }

    // =====================================================================
    // Helper dùng chung - mirror đúng logic của SemanticAnalyzer.analyze()
    // =====================================================================

    /**
     * Hỗ trợ nhiều dấu '|' trong 1 chuỗi - trả về list Resolved theo đúng thứ tự cursor.
     */
    private static List<Resolved> resolveAll(String rawWithCursors) {
        List<Integer> cursors = new ArrayList<>();
        StringBuilder cleanSql = new StringBuilder();
        for (int i = 0; i < rawWithCursors.length(); i++) {
            char ch = rawWithCursors.charAt(i);
            if (ch == '|') {
                cursors.add(cleanSql.length());
            } else {
                cleanSql.append(ch);
            }
        }
        String sql = cleanSql.toString();

        List<Resolved> results = new ArrayList<>();
        for (int cursor : cursors) {
            results.add(resolveSingle(sql, cursor));
        }
        return results;
    }

    /**
     * Case chỉ có đúng 1 dấu '|'.
     */
    private static Resolved resolveOne(String rawWithCursor) {
        List<Resolved> all = resolveAll(rawWithCursor);
        assertEquals(1, all.size(), "Test case này phải có đúng 1 dấu '|': " + rawWithCursor);
        return all.get(0);
    }

    private static Resolved resolveSingle(String originalSql, int cursorOffset) {
        boolean rightAfterDot = cursorOffset > 0 && originalSql.charAt(cursorOffset - 1) == '.';
        String parseSql = rightAfterDot ? originalSql
            : SemanticScope.withCursorPlaceholder(originalSql, cursorOffset);
        boolean patched = !parseSql.equals(originalSql);

        var lexer = new PostgreSQLLexer(CharStreams.fromString(parseSql));
        var tokens = new CommonTokenStream(lexer);
        var parser = new PostgreSQLParser(tokens);

        Set<Integer> offendingTokens = new HashSet<>();
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object offendingSymbol, int l, int c,
                String m, RecognitionException e) {
                if (offendingSymbol instanceof Token t) {
                    offendingTokens.add(t.getTokenIndex());
                }
            }
        });

        ParseTree tree;
        try {
            tree = parser.query();
        } catch (RecognitionException ex) {
            fail("PARSE FAILED HARD cho SQL: " + originalSql + " -> " + ex.getMessage());
            return null; // unreachable, fail() đã throw
        }

        SemanticScope model = new SemanticScope();
        model.offendingTokenIndices.addAll(offendingTokens);
        ParseTreeWalker.DEFAULT.walk(model, tree);

        tokens.fill();
        int tokenIdx = 0;
        if (patched) {
            for (Token t : tokens.getTokens()) {
                if (SemanticScope.CURSOR_PLACEHOLDER.equals(t.getText())) {
                    tokenIdx = t.getTokenIndex();
                    break;
                }
            }
        } else {
            for (Token t : tokens.getTokens()) {
                if (t.getType() == Token.EOF || t.getStartIndex() > cursorOffset) {
                    break;
                }
                tokenIdx = t.getTokenIndex();
            }
        }

        var scope = model.scopeAt(tokenIdx);
        var result = model.resolveAt(cursorOffset, scope);
        return new Resolved(result.visibleAliases(), result.danglingQualifierResolvesTo());
    }

    /**
     * Trả về (projectedColumns, hasWildcard) của derived scope tại vị trí cursor - dùng riêng cho
     * nhóm test projection.
     */
    private record Projection(List<String> columns, boolean hasWildcard) {

    }

    private static Projection resolveProjection(String rawWithCursor) {
        int cursor = rawWithCursor.indexOf('|');
        String sql = rawWithCursor.substring(0, cursor) + rawWithCursor.substring(cursor + 1);

        var lexer = new PostgreSQLLexer(CharStreams.fromString(sql));
        var tokens = new CommonTokenStream(lexer);
        var parser = new PostgreSQLParser(tokens);
        parser.removeErrorListeners();

        ParseTree tree;
        try {
            tree = parser.query();
        } catch (RecognitionException ex) {
            fail("PARSE FAILED HARD cho SQL: " + sql + " -> " + ex.getMessage());
            return null;
        }

        SemanticScope model = new SemanticScope();
        ParseTreeWalker.DEFAULT.walk(model, tree);

        tokens.fill();
        int tokenIdx = 0;
        for (Token t : tokens.getTokens()) {
            if (t.getType() == Token.EOF || t.getStartIndex() > cursor) {
                break;
            }
            tokenIdx = t.getTokenIndex();
        }
        var scope = model.scopeAt(tokenIdx);
        var result = model.resolveAt(cursor, scope);
        var derivedScope = result.danglingQualifierScope();

        return derivedScope == null
            ? new Projection(List.of(), false)
            : new Projection(derivedScope.projectedColumns, derivedScope.hasWildcard);
    }

    private static Map<String, String> aliases(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // =====================================================================
    // Nhóm 1: case cơ bản
    // =====================================================================

    @Nested
    @DisplayName("Case cơ bản: alias, JOIN, correlated subquery, self-join")
    class BasicCases {

        @Test
        @DisplayName("correlated subquery - thấy được cả outer alias lẫn own alias")
        void correlatedSubquery() {
            var r = resolveOne("select (select * from customers where u.| ) from users as u");
            assertEquals(aliases("u", "users", "customers", "customers"), r.aliases());
            assertEquals("users", r.resolve());
        }

        @Test
        @DisplayName("bảng đơn giản không alias - mặc định lấy tên bảng làm alias")
        void simpleTableNoAlias() {
            var r = resolveOne("select t.| from t");
            assertEquals(aliases("t", "t"), r.aliases());
            assertEquals("t", r.resolve());
        }

        @Test
        @DisplayName("JOIN 2 bảng - thấy được cả 2 alias")
        void twoTableJoin() {
            var r = resolveOne(
                "select * from orders o join customers c on o.customer_id = c.id where c.| ");
            assertEquals(aliases("o", "orders", "c", "customers"), r.aliases());
            assertEquals("customers", r.resolve());
        }

        @Test
        @DisplayName("correlated subquery 3 tầng lồng nhau")
        void tripleNestedCorrelatedSubquery() {
            var r = resolveOne(
                "select (select (select * from x where u.| ) from y) from users as u");
            assertEquals(aliases("u", "users", "y", "y", "x", "x"), r.aliases());
            assertEquals("users", r.resolve());
        }

        @Test
        @DisplayName("CTE đơn giản - alias trỏ tới scope CTE")
        void simpleCte() {
            var r = resolveOne("with c as (select * from customers) select c.| from c");
            assertEquals(aliases("c", "<cte#1>"), r.aliases());
            assertEquals("<cte#1>", r.resolve());
        }

        @Test
        @DisplayName("CTE thứ 2 tham chiếu CTE thứ 1")
        void cteReferencingEarlierCte() {
            var r = resolveOne(
                "with a as (select * from t1), b as (select * from a where a.| ) select * from b");
            assertEquals(aliases("a", "<cte#1>", "b", "<cte#2>"), r.aliases());
            assertEquals("<cte#1>", r.resolve());
        }

        @Test
        @DisplayName("subquery trong FROM có alias")
        void subqueryInFromWithAlias() {
            var r = resolveOne("select s.| from (select * from orders) as s");
            assertEquals(aliases("s", "<subquery#2>"), r.aliases());
            assertEquals("<subquery#2>", r.resolve());
        }

        @Test
        @DisplayName("alias không tồn tại - resolve về null, không throw")
        void unknownAliasResolvesToNull() {
            var r = resolveOne("select * from users u where x.| ");
            assertEquals(aliases("u", "users"), r.aliases());
            assertNull(r.resolve());
        }

        @Test
        @DisplayName("3 JOIN liên tiếp - thấy được cả 3 alias")
        void threeTableJoinChain() {
            var r = resolveOne(
                "select * from a ja join b jb on ja.id = jb.a_id join c jc on jb.id = jc.b_id where jc.| ");
            assertEquals(aliases("ja", "a", "jb", "b", "jc", "c"), r.aliases());
            assertEquals("c", r.resolve());
        }

        @Test
        @DisplayName("2 dấu chấm cụt trong cùng 1 câu - tách đúng từng vị trí")
        void twoDanglingDotsInSameStatement() {
            var results = resolveAll(
                "select u.| , o.| from users u join orders o on u.id = o.user_id");
            assertEquals(2, results.size());
            assertEquals(aliases("u", "users", "o", "orders"), results.get(0).aliases());
            assertEquals("users", results.get(0).resolve());
            assertEquals(aliases("u", "users", "o", "orders"), results.get(1).aliases());
            assertEquals("orders", results.get(1).resolve());
        }

        @Test
        @DisplayName("t.* KHÔNG bị coi là dangling dot (DOT?? non-greedy hoạt động đúng)")
        void starAfterDotIsNotDanglingDot() {
            var r = resolveOne("select t.| * from t");
            assertEquals(aliases("t", "t"), r.aliases());
            assertNull(r.resolve());
        }

        @Test
        @DisplayName("self-join - 2 alias khác nhau cùng trỏ 1 bảng")
        void selfJoin() {
            var r = resolveOne(
                "select * from employees e1 join employees e2 on e1.manager_id = e2.id where e2.| ");
            assertEquals(aliases("e1", "employees", "e2", "employees"), r.aliases());
            assertEquals("employees", r.resolve());
        }

        @Test
        @DisplayName("lỗi cú pháp thật (token thừa) ngay trước tableName - isUnreliable() chặn đăng ký")
        void syntaxErrorBlocksUnreliableAlias() {
            var r = resolveOne("select * from 123abc x where x.| ");
            assertEquals(Map.of(), r.aliases());
            assertNull(r.resolve());
        }
    }

    // =====================================================================
    // Nhóm 2: mở rộng lần 1 - UPDATE/DELETE, EXISTS, JOIN-position subquery,...
    // =====================================================================

    @Nested
    @DisplayName("Mở rộng: UPDATE/DELETE, EXISTS, schema-qualified name, shadowing")
    class ExtendedCasesGroup1 {

        @Test
        @DisplayName("UPDATE ... WHERE - alias của chính UPDATE thấy được")
        void updateStatement() {
            var r = resolveOne("update users u set u.name = 'x' where u.| ");
            assertEquals(aliases("u", "users"), r.aliases());
            assertEquals("users", r.resolve());
        }

        @Test
        @DisplayName("DELETE ... WHERE - alias của chính DELETE thấy được")
        void deleteStatement() {
            var r = resolveOne("delete from users u where u.| ");
            assertEquals(aliases("u", "users"), r.aliases());
            assertEquals("users", r.resolve());
        }

        @Test
        @DisplayName("EXISTS subquery correlated với alias ngoài")
        void existsSubquery() {
            var r = resolveOne(
                "select * from orders o where exists (select 1 from customers c where c.id = o.| )");
            assertEquals(aliases("o", "orders", "c", "customers"), r.aliases());
            assertEquals("orders", r.resolve());
        }

        @Test
        @DisplayName("subquery lồng trong JOIN (không phải FROM đầu tiên)")
        void subqueryInJoinPosition() {
            var r = resolveOne("select * from a x join (select * from y where x.| ) z on true");
            assertEquals(aliases("x", "a", "z", "<subquery#2>", "y", "y"), r.aliases());
            assertEquals("a", r.resolve());
        }

        @Test
        @DisplayName("tên bảng có schema - alias mặc định lấy phần cuối")
        void schemaQualifiedTableName() {
            var r = resolveOne("select o.| from myschema.orders o");
            assertEquals(aliases("o", "myschema.orders"), r.aliases());
            assertEquals("myschema.orders", r.resolve());
        }

        @Test
        @DisplayName("dấu chấm cụt trong ON clause của JOIN, không phải WHERE")
        void danglingDotInOnClause() {
            var r = resolveOne("select * from a ja join b jb on ja.| = jb.id");
            assertEquals(aliases("ja", "a", "jb", "b"), r.aliases());
            assertEquals("a", r.resolve());
        }

        @Test
        @DisplayName("shadowing - alias trùng tên ở scope trong ghi đè scope ngoài")
        void aliasShadowing() {
            var r = resolveOne("select (select * from customers c where c.| ) from orders c");
            assertEquals(aliases("c", "customers"), r.aliases());
            assertEquals("customers", r.resolve());
        }

        @Test
        @DisplayName("WHERE phức tạp nhiều điều kiện + ngoặc lồng nhau trước dấu chấm cụt")
        void complexWhereClause() {
            var r = resolveOne(
                "select * from t1 a join t2 b on a.id = b.id where (a.x = 1 and b.y = 2) and a.| ");
            assertEquals(aliases("a", "t1", "b", "t2"), r.aliases());
            assertEquals("t1", r.resolve());
        }
    }

    // =====================================================================
    // Nhóm 3: cursor ở vị trí trống hoàn toàn - cần withCursorPlaceholder
    // =====================================================================

    @Nested
    @DisplayName("Cursor ở vị trí trống hoàn toàn (withCursorPlaceholder)")
    class PlaceholderCases {

        @Test
        @DisplayName("SELECT trống, chưa gõ gì - vẫn thấy được bảng trong FROM")
        void emptySelectListSeesTable() {
            var r = resolveOne("select | from t");
            assertEquals(aliases("t", "t"), r.aliases());
            assertNull(r.resolve());
        }

        @Test
        @DisplayName("SELECT trống với tên bảng dài hơn")
        void emptySelectListSeesLongerTableName() {
            var r = resolveOne("select | from demo");
            assertEquals(aliases("demo", "demo"), r.aliases());
            assertNull(r.resolve());
        }

        @Test
        @DisplayName("SELECT trống với bảng có alias")
        void emptySelectListWithAliasedTable() {
            var r = resolveOne("select | from demo d");
            assertEquals(aliases("d", "demo"), r.aliases());
            assertNull(r.resolve());
        }

        @Test
        @DisplayName("WHERE trống hoàn toàn")
        void emptyWhereClause() {
            var r = resolveOne("select * from demo d where |");
            assertEquals(aliases("d", "demo"), r.aliases());
            assertNull(r.resolve());
        }

        @Test
        @DisplayName("cursor trống bên trong subquery - vẫn thấy alias ngoài (correlated)")
        void emptyPositionInsideSubquerySeesOuterAlias() {
            var r = resolveOne("select (select | from x) from demo d");
            assertEquals(aliases("d", "demo", "x", "x"), r.aliases());
            assertNull(r.resolve());
        }

        @Test
        @DisplayName("đối chứng: cursor sau dấu chấm KHÔNG đi qua nhánh placeholder")
        void rightAfterDotDoesNotUsePlaceholder() {
            var r = resolveOne("select d.| from demo d");
            assertEquals(aliases("d", "demo"), r.aliases());
            assertEquals("demo", r.resolve());
        }

        @Test
        @DisplayName("BUG ĐÃ FIX: cursor ở WHERE ngoài cùng, SAU KHI 2 tầng subquery trong FROM đã đóng - không được lộ alias con")
        void scopeAtPicksInnermostContainingIntervalNotStaleChild() {
            var r = resolveOne(
                "select * from (select (select id from contracts as c2) as id from contracts as c1) as c where |");
            assertEquals(aliases("c", "<subquery#2>"), r.aliases());
            assertNull(r.resolve());
        }
    }

    // =====================================================================
    // Nhóm 4: mở rộng lần 2 - GROUP BY/HAVING/ORDER BY, function args, CASE, CTE 3 tầng
    // =====================================================================

    @Nested
    @DisplayName("Mở rộng: GROUP BY/HAVING/ORDER BY, function args, CASE WHEN, CTE 3 tầng, cô lập lỗi")
    class ExtendedCasesGroup2 {

        @Test
        @DisplayName("dấu chấm cụt trong GROUP BY")
        void danglingDotInGroupBy() {
            var r = resolveOne("select a.id, count(*) from orders a group by a.| ");
            assertEquals(aliases("a", "orders"), r.aliases());
            assertEquals("orders", r.resolve());
        }

        @Test
        @DisplayName("dấu chấm cụt trong HAVING")
        void danglingDotInHaving() {
            var r = resolveOne("select a.id from orders a group by a.id having a.| > 1");
            assertEquals(aliases("a", "orders"), r.aliases());
            assertEquals("orders", r.resolve());
        }

        @Test
        @DisplayName("dấu chấm cụt trong ORDER BY")
        void danglingDotInOrderBy() {
            var r = resolveOne("select a.id from orders a order by a.| ");
            assertEquals(aliases("a", "orders"), r.aliases());
            assertEquals("orders", r.resolve());
        }

        @Test
        @DisplayName("dấu chấm cụt trong tham số hàm")
        void danglingDotInFunctionArgument() {
            var r = resolveOne("select count(u.| ) from users u");
            assertEquals(aliases("u", "users"), r.aliases());
            assertEquals("users", r.resolve());
        }

        @Test
        @DisplayName("dấu chấm cụt trong CASE WHEN")
        void danglingDotInCaseWhen() {
            var r = resolveOne("select case when u.| > 0 then 1 else 0 end from users u");
            assertEquals(aliases("u", "users"), r.aliases());
            assertEquals("users", r.resolve());
        }

        @Test
        @DisplayName("CTE 3 tầng: c dùng b, b dùng a - resolve xuyên qua nhiều tầng")
        void threeLevelCteChain() {
            var r = resolveOne(
                "with a as (select * from t1), b as (select * from a), c as (select * from b where b.| ) select * from c");
            assertEquals(aliases("a", "<cte#1>", "b", "<cte#2>", "c", "<cte#3>"), r.aliases());
            assertEquals("<cte#2>", r.resolve());
        }

        @Test
        @DisplayName("isUnreliable() cô lập đúng - lỗi ở 1 bảng không làm mất alias hợp lệ của bảng khác")
        void unreliableErrorIsolatedToSingleTable() {
            var r = resolveOne("select * from 999bad bad join users good on true where good.| ");
            assertEquals(aliases("good", "users"), r.aliases());
            assertEquals("users", r.resolve());
        }
    }

    // =====================================================================
    // Nhóm 5: giới hạn đã biết (không phải bug) - expect PHẢN ÁNH ĐÚNG behavior thực tế
    // =====================================================================

    @Nested
    @DisplayName("Giới hạn đã biết - KHÔNG phải bug, chỉ ghi nhận behavior")
    class KnownLimitations {

        @Test
        @DisplayName("FROM thiếu hẳn - grammar không cứu được (khác với SELECT rỗng)")
        void missingFromClauseEntirely() {
            var r = resolveOne("select id.| ");
            assertEquals(Map.of(), r.aliases());
            assertNull(r.resolve());
        }

        @Test
        @DisplayName("double-dot - alias vẫn còn nguyên, riêng gợi ý tại '..' thì không có")
        void doubleDotDoesNotCrashButNoSuggestionThere() {
            var r = resolveOne("select a..| from t a");
            assertEquals(aliases("a", "t"), r.aliases());
            assertNull(r.resolve());
        }
    }

    // =====================================================================
    // Nhóm 6: projectedColumns / hasWildcard của subquery/CTE
    // =====================================================================

    @Nested
    @DisplayName("projectedColumns/hasWildcard - cột thật của subquery/CTE")
    class ProjectionColumns {

        @Test
        @DisplayName("CTE có tên cột rõ ràng - projectedColumns đúng thứ tự, hasWildcard=false")
        void cteWithExplicitColumnNames() {
            var p = resolveProjection(
                "with c as (select id, name as full_name from t) select c.| from c");
            assertEquals(List.of("id", "full_name"), p.columns());
            assertFalse(p.hasWildcard());
        }

        @Test
        @DisplayName("CTE dùng SELECT * - hasWildcard=true, projectedColumns rỗng")
        void cteWithSelectStar() {
            var p = resolveProjection("with c as (select * from t) select c.| from c");
            assertEquals(List.of(), p.columns());
            assertTrue(p.hasWildcard());
        }
    }

    // =====================================================================
    // Nhóm 7 (MỚI): biến thể loại JOIN, ORDER BY nhiều cột, quoted identifier,
    // subquery không AS, CTE 4 tầng, dấu chấm cụt lồng trong nhiều ngoặc.
    // ⚠️ Giá trị expect ở nhóm này là SUY LUẬN từ pattern các case đã verify ở
    // trên, CHƯA chạy thực nghiệm trực tiếp - chạy `mvn test` và chỉnh lại nếu
    // actual khác expected (đặc biệt các case đánh dấu TODO-VERIFY).
    // =====================================================================

    @Nested
    @DisplayName("Mở rộng lần 3: loại JOIN khác, ORDER BY nhiều cột, quoted identifier, CTE 4 tầng")
    class ExtendedCasesGroup3 {

        @Test
        @DisplayName("LEFT JOIN - alias 2 bên đều thấy được, không khác gì INNER JOIN")
        void leftJoin() {
            var r = resolveOne(
                "select * from orders o left join customers c on o.customer_id = c.id where c.| ");
            assertEquals(aliases("o", "orders", "c", "customers"), r.aliases());
            assertEquals("customers", r.resolve());
        }

        @Test
        @DisplayName("RIGHT JOIN - alias 2 bên đều thấy được")
        void rightJoin() {
            var r = resolveOne(
                "select * from orders o right join customers c on o.customer_id = c.id where o.| ");
            assertEquals(aliases("o", "orders", "c", "customers"), r.aliases());
            assertEquals("orders", r.resolve());
        }

        @Test
        @DisplayName("FULL OUTER JOIN - alias 2 bên đều thấy được")
        void fullOuterJoin() {
            var r = resolveOne(
                "select * from orders o full outer join customers c on o.customer_id = c.id where c.| ");
            assertEquals(aliases("o", "orders", "c", "customers"), r.aliases());
            assertEquals("customers", r.resolve());
        }

        @Test
        @DisplayName("CROSS JOIN - không có ON clause, alias 2 bên vẫn thấy được")
            // TODO-VERIFY: xác nhận grammar hỗ trợ CROSS JOIN không cần ON
        void crossJoin() {
            var r = resolveOne("select * from orders o cross join customers c where c.| ");
            assertEquals(aliases("o", "orders", "c", "customers"), r.aliases());
            assertEquals("customers", r.resolve());
        }

        @Test
        @DisplayName("4 JOIN liên tiếp - thấy được cả 4 alias, không giới hạn độ sâu")
        void fourTableJoinChain() {
            var r = resolveOne("select * from a ja join b jb on ja.id = jb.a_id " +
                "join c jc on jb.id = jc.b_id join d jd on jc.id = jd.c_id where jd.| ");
            assertEquals(aliases("ja", "a", "jb", "b", "jc", "c", "jd", "d"), r.aliases());
            assertEquals("d", r.resolve());
        }

        @Test
        @DisplayName("ORDER BY nhiều cột, dấu chấm cụt ở cột thứ 2")
        void danglingDotInSecondOrderByColumn() {
            var r = resolveOne(
                "select a.id, b.name from t1 a join t2 b on a.id = b.a_id order by a.id, b.| ");
            assertEquals(aliases("a", "t1", "b", "t2"), r.aliases());
            assertEquals("t2", r.resolve());
        }

        @Test
        @DisplayName("CTE 4 tầng: d dùng c, c dùng b, b dùng a")
        void fourLevelCteChain() {
            var r = resolveOne("with a as (select * from t1), " +
                "b as (select * from a), " +
                "c as (select * from b), " +
                "d as (select * from c where c.| ) select * from d");
            assertEquals(aliases("a", "<cte#1>", "b", "<cte#2>", "c", "<cte#3>", "d", "<cte#4>"),
                r.aliases());
            assertEquals("<cte#3>", r.resolve());
        }

        @Test
        @DisplayName("subquery trong FROM KHÔNG có từ khóa AS trước alias")
            // TODO-VERIFY: một số grammar bắt buộc AS, cần check
        void subqueryInFromWithoutAsKeyword() {
            var r = resolveOne("select s.| from (select * from orders) s");
            assertEquals(aliases("s", "<subquery#2>"), r.aliases());
            assertEquals("<subquery#2>", r.resolve());
        }

        @Test
        @DisplayName("tên cột/alias có double-quote (quoted identifier)")
            // TODO-VERIFY: cần xác nhận grammar PostgreSQL hỗ trợ quoted identifier ở vị trí alias
        void quotedIdentifierAlias() {
            var r = resolveOne("select \"U\".| from users as \"U\"");
            assertEquals(aliases("U", "users"), r.aliases());
            assertEquals("users", r.resolve());
        }

        @Test
        @DisplayName("dấu chấm cụt lồng trong 3 lớp ngoặc đơn liên tiếp")
        void danglingDotInsideTripleNestedParens() {
            var r = resolveOne("select * from t1 a where (((a.| ))) is not null");
            assertEquals(aliases("a", "t1"), r.aliases());
            assertEquals("t1", r.resolve());
        }

        @Test
        @DisplayName("dấu chấm cụt bên trong subquery ở SELECT list (không phải WHERE) - vẫn thấy alias ngoài")
        void danglingDotInScalarSubqueryInSelectList() {
            var r = resolveOne("select (select c.| from customers c) as sub from orders o");
            assertEquals(aliases("o", "orders", "c", "customers"), r.aliases());
            assertEquals("customers", r.resolve());
        }

        @Test
        @DisplayName("3 dấu chấm cụt trong 1 câu (JOIN 3 bảng) - tách đúng cả 3 vị trí")
        void threeDanglingDotsInSameStatement() {
            var results = resolveAll(
                "select a.| , b.| , c.| from t1 a join t2 b on a.id = b.a_id join t3 c on b.id = c.b_id");
            assertEquals(3, results.size());
            assertEquals("t1", results.get(0).resolve());
            assertEquals("t2", results.get(1).resolve());
            assertEquals("t3", results.get(2).resolve());
        }
    }

    // =====================================================================
    // Nhóm 8 (MỚI): INSERT...SELECT, LATERAL, WINDOW function, subquery
    // trong FROM không alias tường minh nhưng bị lỗi (fallback), CTE dùng
    // 2 lần ở 2 vị trí, WHERE với BETWEEN/IN chứa dấu chấm cụt.
    // ⚠️ Tương tự Nhóm 7: giá trị expect SUY LUẬN từ pattern đã verify, CHƯA
    // chạy thực nghiệm - chạy `mvn test` và chỉnh lại case nào actual khác
    // expected, đặc biệt case đánh dấu TODO-VERIFY.
    // =====================================================================

    @Nested
    @DisplayName("Mở rộng lần 4: INSERT...SELECT, LATERAL, BETWEEN/IN, CTE dùng 2 lần")
    class ExtendedCasesGroup4 {

        @Test
        @DisplayName("INSERT INTO ... SELECT ... - alias trong phần SELECT con vẫn resolve bình thường")
        void insertIntoSelectStatement() {
            var r = resolveOne("insert into archive select u.| from users u");
            assertEquals(aliases("u", "users"), r.aliases());
            assertEquals("users", r.resolve());
        }

        @Test
        @DisplayName("dấu chấm cụt trong vế trái BETWEEN")
        void danglingDotInBetweenLeftSide() {
            var r = resolveOne("select * from t1 a where a.| between 1 and 100");
            assertEquals(aliases("a", "t1"), r.aliases());
            assertEquals("t1", r.resolve());
        }

        @Test
        @DisplayName("dấu chấm cụt trong danh sách IN (...)")
        void danglingDotInInClause() {
            var r = resolveOne("select * from t1 a where a.| in (1, 2, 3)");
            assertEquals(aliases("a", "t1"), r.aliases());
            assertEquals("t1", r.resolve());
        }

        @Test
        @DisplayName("CTE được tham chiếu 2 lần trong 2 JOIN khác nhau ở statement chính - vẫn cùng 1 scope CTE")
        void cteReferencedTwiceViaSelfJoin() {
            var r = resolveOne(
                "with c as (select * from t1) select * from c c1 join c c2 on c1.id = c2.id where c2.| ");
            assertEquals(aliases("c1", "<cte#1>", "c2", "<cte#1>"), r.aliases());
            assertEquals("<cte#1>", r.resolve());
        }

        @Test
        @DisplayName("LATERAL subquery trong FROM - vẫn thấy được alias đứng trước nó cùng cấp FROM")
            // TODO-VERIFY: cần xác nhận grammar hỗ trợ từ khóa LATERAL
        void lateralSubqueryInFrom() {
            var r = resolveOne(
                "select * from orders o, lateral (select * from customers where id = o.customer_id and c.| ) c");
            assertEquals(aliases("o", "orders", "c", "<subquery#2>"), r.aliases());
            assertNull(r.resolve());
        }

        @Test
        @DisplayName("window function OVER (PARTITION BY ...) - dấu chấm cụt trong PARTITION BY")
            // TODO-VERIFY: cần xác nhận grammar hỗ trợ window function
        void danglingDotInsidePartitionBy() {
            var r = resolveOne(
                "select row_number() over (partition by u.| ) from users u");
            assertEquals(aliases("u", "users"), r.aliases());
            assertEquals("users", r.resolve());
        }

        @Test
        @DisplayName("dấu chấm cụt ngay sau AS trong tên cột alias (không phải table alias) - KHÔNG bị nhầm với table alias")
        void danglingDotIsNotConfusedWithColumnAliasAs() {
            var r = resolveOne("select u.id as user_id, u.| from users u");
            assertEquals(aliases("u", "users"), r.aliases());
            assertEquals("users", r.resolve());
        }

        @Test
        @DisplayName("2 CTE độc lập không tham chiếu nhau, dùng chung trong 1 JOIN ở statement chính")
        void twoIndependentCtesJoinedInMainStatement() {
            var r = resolveOne(
                "with a as (select * from t1), b as (select * from t2) select * from a join b on a.id = b.id where b.| ");
            assertEquals(aliases("a", "<cte#1>", "b", "<cte#2>"), r.aliases());
            assertEquals("<cte#2>", r.resolve());
        }

        @Test
        @DisplayName("dấu chấm cụt trong WHERE có phép toán số học trước nó (a.x + b.| )")
        void danglingDotAfterArithmeticExpression() {
            var r = resolveOne("select * from t1 a join t2 b on a.id = b.id where a.x + b.| > 0");
            assertEquals(aliases("a", "t1", "b", "t2"), r.aliases());
            assertEquals("t2", r.resolve());
        }

        @Test
        @DisplayName("subquery trong FROM có alias trùng tên với CTE (không xung đột, tách scope riêng)")
        void subqueryAliasSameNameAsCte() {
            var r = resolveOne(
                "with x as (select * from t1) select * from (select * from t2) x where x.| ");
            assertEquals(aliases("x", "<subquery#2>"), r.aliases());
            assertEquals("<subquery#2>", r.resolve());
        }
    }
}