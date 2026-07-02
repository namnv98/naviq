package com.naviq.completion.semantic;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.util.*;

/**
 * Bộ test dạng "expect vs actual" - mỗi cursor khai báo kỳ vọng cụ thể
 * (visibleAliases + resolve), tool tự so sánh và báo PASS/FAIL, không cần
 * đọc tay từng dòng output như trước. Giá trị kỳ vọng lấy từ kết quả THỰC TẾ
 * đã được xác nhận đúng qua nhiều vòng review thủ công trước đó - mục đích
 * từ đây là PHÁT HIỆN REGRESSION khi sửa code, không phải khám phá bug mới.
 */
public class SemanticScopeTest {

    /** Kỳ vọng cho 1 vị trí cursor. resolve=null nghĩa là "không phải member-access". */
    record Expect(Map<String, String> aliases, String resolve) {
        static Expect of(String resolve, String... kv) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
            return new Expect(m, resolve);
        }
    }

    private static int totalChecks = 0;
    private static int totalPass = 0;

    public static void main(String[] args) {
        // ---- case cơ bản ----
        run("select (select * from customers where u.| ) from users as u",
                Expect.of("users", "u", "users", "customers", "customers"));

        run("select t.| from t",
                Expect.of("t", "t", "t"));

        run("select * from orders o join customers c on o.customer_id = c.id where c.| ",
                Expect.of("customers", "o", "orders", "c", "customers"));

        run("select (select (select * from x where u.| ) from y) from users as u",
                Expect.of("users", "u", "users", "y", "y", "x", "x"));

        run("with c as (select * from customers) select c.| from c",
                Expect.of("<cte#1>", "c", "<cte#1>"));

        run("with a as (select * from t1), b as (select * from a where a.| ) select * from b",
                Expect.of("<cte#1>", "a", "<cte#1>", "b", "<cte#2>"));

        run("select s.| from (select * from orders) as s",
                Expect.of("<subquery#2>", "s", "<subquery#2>"));

        run("select * from users u where x.| ",
                Expect.of(null, "u", "users"));

        run("select * from a ja join b jb on ja.id = jb.a_id join c jc on jb.id = jc.b_id where jc.| ",
                Expect.of("c", "ja", "a", "jb", "b", "jc", "c"));

        run("select u.| , o.| from users u join orders o on u.id = o.user_id",
                Expect.of("users", "u", "users", "o", "orders"),
                Expect.of("orders", "u", "users", "o", "orders"));

        run("select t.| * from t",
                Expect.of(null, "t", "t"));

        run("select * from employees e1 join employees e2 on e1.manager_id = e2.id where e2.| ",
                Expect.of("employees", "e1", "employees", "e2", "employees"));

        run("select * from 123abc x where x.| ",
                Expect.of(null));

        // ---- nhóm mở rộng lần 1 ----
        run("update users u set u.name = 'x' where u.| ",
                Expect.of("users", "u", "users"));

        run("delete from users u where u.| ",
                Expect.of("users", "u", "users"));

        run("select * from orders o where exists (select 1 from customers c where c.id = o.| )",
                Expect.of("orders", "o", "orders", "c", "customers"));

        run("select * from a x join (select * from y where x.| ) z on true",
                Expect.of("a", "x", "a", "z", "<subquery#2>", "y", "y"));

        run("select o.| from myschema.orders o",
                Expect.of("myschema.orders", "o", "myschema.orders"));

        run("select * from a ja join b jb on ja.| = jb.id",
                Expect.of("a", "ja", "a", "jb", "b"));

        run("select (select * from customers c where c.| ) from orders c",
                Expect.of("customers", "c", "customers"));

        run("select * from t1 a join t2 b on a.id = b.id where (a.x = 1 and b.y = 2) and a.| ",
                Expect.of("t1", "a", "t1", "b", "t2"));

        // ---- nhóm placeholder (cursor ở vị trí trống hoàn toàn) ----
        run("select | from t",
                Expect.of(null, "t", "t"));

        run("select | from demo",
                Expect.of(null, "demo", "demo"));

        run("select | from demo d",
                Expect.of(null, "d", "demo"));

        run("select * from demo d where |",
                Expect.of(null, "d", "demo"));

        run("select (select | from x) from demo d",
                Expect.of(null, "d", "demo", "x", "x"));

        run("select d.| from demo d",
                Expect.of("demo", "d", "demo"));

        // ---- nhóm mở rộng lần 2 ----
        run("select a.id, count(*) from orders a group by a.| ",
                Expect.of("orders", "a", "orders"));

        run("select a.id from orders a group by a.id having a.| > 1",
                Expect.of("orders", "a", "orders"));

        run("select a.id from orders a order by a.| ",
                Expect.of("orders", "a", "orders"));

        run("select count(u.| ) from users u",
                Expect.of("users", "u", "users"));

        run("select case when u.| > 0 then 1 else 0 end from users u",
                Expect.of("users", "u", "users"));

        run("with a as (select * from t1), b as (select * from a), c as (select * from b where b.| ) select * from c",
                Expect.of("<cte#2>", "a", "<cte#1>", "b", "<cte#2>", "c", "<cte#3>"));

        run("select * from 999bad bad join users good on true where good.| ",
                Expect.of("users", "good", "users"));

        // ---- 2 case biết trước là giới hạn (không phải bug) - expect PHẢN ÁNH ĐÚNG
        //      giới hạn thực tế, KHÔNG phải kỳ vọng "lý tưởng chưa làm được" ----
        run("select id.| ",
                Expect.of(null)); // FROM thiếu hẳn - grammar không cứu được, đây là behavior đúng

        run("select a..| from t a",
                Expect.of(null, "a", "t")); // double-dot: alias "a" vẫn còn, riêng gợi ý tại "..": không có

        // ---- BUG ĐÃ FIX: cursor ở WHERE của outer query, SAU KHI 2 tầng subquery
        //      lồng trong FROM đã đóng - trước đây scopeAt() chọn nhầm scope con đã
        //      đóng (do chỉ so startTokenIndex, không check đã đóng chưa), lộ ra alias
        //      c1/c2 của các scope con không liên quan. PHẢI chỉ thấy "c" (alias của
        //      chính outer FROM), KHÔNG được thấy c1/c2 (thuộc scope con đã đóng). ----
        run("select * from (select (select id from contracts as c2) as id from contracts as c1) as c where |",
                Expect.of(null, "c", "<subquery#2>"));

        // ---- projectedColumns: CTE có tên cột RÕ RÀNG (từ alias hoặc columnName đơn) ----
        run("with c as (select id, name as full_name from t) select c.| from c",
                Expect.of("<cte#1>", "c", "<cte#1>"));
        assertProjection("with c as (select id, name as full_name from t) select c.| from c",
                List.of("id", "full_name"), false);

        // ---- projectedColumns: CTE dùng "SELECT *" - hasWildcard=true, projectedColumns rỗng ----
        assertProjection("with c as (select * from t) select c.| from c",
                List.of(), true);

        System.out.println("=====================================================");
        System.out.println("TỔNG: " + totalPass + "/" + totalChecks + " PASS");
        System.out.println("=====================================================");
    }

    /** Test riêng cho projectedColumns/hasWildcard - đi từ cursor -> resolve ra derivedRef -> tra scope đó. */
    private static void assertProjection(String rawWithCursor, List<String> expectedColumns, boolean expectedWildcard) {
        int cursor = rawWithCursor.indexOf('|');
        String sql = rawWithCursor.substring(0, cursor) + rawWithCursor.substring(cursor + 1);
        boolean rightAfterDot = cursor > 0 && sql.charAt(cursor - 1) == '.';

        var lexer = new com.example.PostgreSQLLexer(CharStreams.fromString(sql));
        var tokens = new CommonTokenStream(lexer);
        var parser = new com.example.PostgreSQLParser(tokens);
        parser.removeErrorListeners();

        ParseTree tree;
        try {
            tree = parser.query();
        } catch (RecognitionException ex) {
            System.out.println("  [PARSE FAILED HARD]: " + ex.getMessage());
            return;
        }

        SemanticScope model = new SemanticScope();
        ParseTreeWalker.DEFAULT.walk(model, tree);

        tokens.fill();
        int tokenIdx = 0;
        for (Token t : tokens.getTokens()) {
            if (t.getType() == Token.EOF || t.getStartIndex() > cursor) break;
            tokenIdx = t.getTokenIndex();
        }
        var scope = model.scopeAt(tokenIdx);
        var result = model.resolveAt(cursor, scope);
        var derivedScope = result.danglingQualifierScope();

        totalChecks++;
        List<String> actualColumns = derivedScope == null ? List.of() : derivedScope.projectedColumns;
        boolean actualWildcard = derivedScope != null && derivedScope.hasWildcard;
        boolean pass = actualColumns.equals(expectedColumns) && actualWildcard == expectedWildcard;
        if (pass) totalPass++;

        System.out.println("  projection(" + result.danglingQualifierResolvesTo() + ") = " + actualColumns
                + "  hasWildcard=" + actualWildcard + "  [" + (pass ? "PASS" : "FAIL") + "]");
        if (!pass) {
            System.out.println("    expected columns = " + expectedColumns + "  expected wildcard = " + expectedWildcard);
        }
    }

    private static void run(String rawWithCursor, Expect... expects) {
        List<Integer> cursors = new ArrayList<>();
        StringBuilder cleanSql = new StringBuilder();
        for (int i = 0; i < rawWithCursor.length(); i++) {
            char ch = rawWithCursor.charAt(i);
            if (ch == '|') {
                cursors.add(cleanSql.length());
            } else {
                cleanSql.append(ch);
            }
        }
        String sql = cleanSql.toString();
        System.out.println("SQL: " + sql);

        for (int i = 0; i < cursors.size(); i++) {
            Expect expect = i < expects.length ? expects[i] : null;
            runSingleCursor(sql, cursors.get(i), expect);
        }
        System.out.println();
    }

    private static void runSingleCursor(String originalSql, int cursorOffset, Expect expect) {
        boolean rightAfterDot = cursorOffset > 0 && originalSql.charAt(cursorOffset - 1) == '.';
        String parseSql = rightAfterDot ? originalSql : SemanticScope.withCursorPlaceholder(originalSql, cursorOffset);
        boolean patched = !parseSql.equals(originalSql);

        var lexer = new com.example.PostgreSQLLexer(CharStreams.fromString(parseSql));
        var tokens = new CommonTokenStream(lexer);
        var parser = new com.example.PostgreSQLParser(tokens);

        Set<Integer> offendingTokens = new HashSet<>();
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> r, Object offendingSymbol, int l, int c, String m, RecognitionException e) {
                if (offendingSymbol instanceof Token t) offendingTokens.add(t.getTokenIndex());
            }
        });

        ParseTree tree;
        try {
            tree = parser.query();
        } catch (RecognitionException ex) {
            System.out.println("  [PARSE FAILED HARD]: " + ex.getMessage());
            return;
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
                if (t.getType() == Token.EOF || t.getStartIndex() > cursorOffset) break;
                tokenIdx = t.getTokenIndex();
            }
        }

        var scope = model.scopeAt(tokenIdx);
        var result = model.resolveAt(cursorOffset, scope);
        Map<String, String> actualAliases = result.visibleAliases();
        String actualResolve = result.danglingQualifierResolvesTo();

        String line = "  @offset=" + cursorOffset + "  visibleAliases=" + actualAliases + "  resolve=" + actualResolve;

        if (expect == null) {
            System.out.println(line + "  [no expect]");
            return;
        }

        totalChecks++;
        boolean aliasesMatch = actualAliases.equals(expect.aliases());
        boolean resolveMatch = Objects.equals(actualResolve, expect.resolve());
        boolean pass = aliasesMatch && resolveMatch;
        if (pass) totalPass++;

        System.out.println(line + "  [" + (pass ? "PASS" : "FAIL") + "]");
        if (!pass) {
            if (!aliasesMatch) {
                System.out.println("    expected aliases = " + expect.aliases());
            }
            if (!resolveMatch) {
                System.out.println("    expected resolve = " + expect.resolve());
            }
        }
    }
}