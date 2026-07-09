package com.naviq.completion.syntactic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naviq.antlr4.PostgreSQLParser;
import com.naviq.completion.suggests.KeywordNoiseFilter;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CompletionRegressionTest {

    private record Case(String description, String sql, int cursorOffset, List<String> expectPresent, List<String> expectAbsent) {
        Case(String description, String sql, List<String> expectPresent,
            List<String> expectAbsent) {
            this(description, sql, sql.length(), expectPresent, expectAbsent);
        }
    }

    private static final List<Case> CASES = List.of(
        new Case(
            "FROM trống - phải gợi ý tên bảng mới",
            "select * from  ",
            List.of("qualified_name"),
            List.of()
        ),
        new Case(
            "FROM schema. (đang gõ dở, chờ tên bảng) - vẫn phải gợi ý bảng theo schema",
            "select * from public.",
            List.of("qualified_name"),
            List.of()
        ),
        new Case(
            "FROM schema.table đã gõ xong (có gap) - KHÔNG được gợi ý lại tên bảng",
            "select * from public.contracts ",
            List.of(),
            List.of("qualified_name")
        ),
        new Case(
            "FROM ... AS (chờ gõ alias) - phải gợi ý alias",
            "select * from public.users as ",
            List.of("table_alias"),
            List.of()
        ),
        new Case(
            "WHERE alias.col đã gõ xong (có gap) - KHÔNG được gợi ý lại cột",
            "select * from public.users u where u.id ",
            List.of(),
            List.of("columnref")
        ),
        new Case(
            "WHERE ... alias. (đang gõ dở, chờ tên cột) - phải gợi ý cột",
            "select * from public.users u right join public.orders o on u.id = o.customer_id where o.",
            List.of("columnref"),
            List.of()
        ),
        new Case("WHERE ... AND  (đang chờ vế biểu thức tiếp theo) - phải gợi ý cột/hàm",
            "select * from public.y where a.s and ",
            List.of("columnref", "func_name"),
            List.of()
        ),

        new Case(
            "Subquery lồng - alias trong FROM con, đang gõ dở tên cột",
            "select * from (select * from public.orders o where o.",
            List.of("columnref"),
            List.of()
        ),
        new Case(
            "Subquery lồng - alias đã gõ xong tên cột (có gap), KHÔNG gợi ý lại",
            "select * from (select * from public.orders o where o.customer_id ",
            List.of(),
            List.of("columnref")
        ),

        new Case(
            "CTE - đang gõ dở tên cột của CTE reference trong outer query",
            "with cte as (select * from public.orders) select * from cte join public.users u on cte.",
            List.of("columnref"),
            List.of()
        ),

        new Case(
            "Self-join - đang gõ dở tên cột qua alias thứ 2 của cùng 1 bảng",
            "select * from public.users u1 join public.users u2 on u1.id = u2.",
            List.of("columnref"),
            List.of()
        ),
        new Case(
            "Self-join - đã gõ xong tên bảng thứ 2 kèm alias (có gap), KHÔNG gợi ý lại tên bảng",
            "select * from public.users u1 join public.users u2 ",
            List.of(),
            List.of("qualified_name")
        ),

        new Case(
            "FROM list nhiều bảng - bảng 1 đã xong (gap), đang gõ dở schema. cho bảng 2 - PHẢI vẫn gợi ý bảng mới",
            "select * from public.a, public.",
            List.of("qualified_name"),
            List.of()
        ),
        new Case(
            "FROM list nhiều bảng - CẢ 2 bảng đã gõ xong (gap) - KHÔNG gợi ý lại bảng nào",
            "select * from public.a, public.b ",
            List.of(),
            List.of("qualified_name")
        ),

        new Case(
            "3 JOIN liên tiếp - đang gõ dở tên cột của bảng thứ 3",
            "select * from public.a a join public.b b on a.id = b.id join public.c c on b.id = c.",
            List.of("columnref"),
            List.of()
        ),

        new Case(
            "UNION - vế 2 vừa gõ xong tên bảng (có gap), KHÔNG gợi ý lại tên bảng cho vế này",
            "select * from public.a a where a.id = 1 union select * from public.b ",
            List.of(),
            List.of("qualified_name")
        ),
        new Case(
            "UNION - vế 2 đang gõ dở schema., phải gợi ý bảng theo schema",
            "select * from public.a a where a.id = 1 union select * from public.",
            List.of("qualified_name"),
            List.of()
        ),

        new Case(
            "Quoted identifier làm tên bảng - đang gõ dở tên cột sau alias",
            "select * from public.\"Users\" as u where u.",
            List.of("columnref"),
            List.of()
        ),

        new Case(
            "Có line-comment chen giữa FROM và WHERE - vẫn phải gợi ý cột đúng vị trí sau alias",
            "select * from public.users u -- lấy toàn bộ user\nwhere u.",
            List.of("columnref"),
            List.of()
        ),

        new Case(
            "2 câu lệnh cách nhau bởi ; - caret ở câu thứ 2, không bị ảnh hưởng bởi câu thứ nhất",
            "select 1; select * from public.users u where u.",
            List.of("columnref"),
            List.of()
        ),

        new Case(
            "Correlated subquery trong EXISTS - đang gõ dở tên cột của alias subquery",
            "select * from public.users u where exists (select 1 from public.orders o where o.customer_id = u.id and o.",
            List.of("columnref"),
            List.of()
        ),

        new Case(
            "INSERT...SELECT...FROM - đang gõ dở tên cột trong phần SELECT nguồn dữ liệu",
            "insert into public.users (id, name) select o.customer_id, o.customer_name from public.orders o where o.",
            List.of("columnref"),
            List.of()
        ),

        new Case(
            "DISTINCT ON - đang gõ dở tên cột sau alias trong WHERE",
            "select distinct on (a.id) * from public.users a where a.",
            List.of("columnref"),
            List.of()
        ),

        new Case(
            "UPDATE...SET - gợi ý cột",
            "update public.users set ",
            List.of("colid"),
            List.of()
        ),
        new Case(
            "INSERT...ON CONFLICT...DO UPDATE SET - gợi ý cột",
            "insert into public.users (id, name) values (1, 'a') on conflict (id) do update set ",
            List.of("colid"),
            List.of()
        ),
        new Case(
            "ALTER TABLE...DROP COLUMN - gợi ý cột",
            "alter table public.users drop column ",
            List.of("colid"),
            List.of()
        ),
        new Case(
            "PARTITION BY forward-reference tới bảng nằm SAU trong câu - gợi ý cột",
            "select row_number() over (partition by customer_id order by ",
            List.of("columnref"),
            List.of()
        ),
        new Case(
            "DELETE FROM schema.table đã gõ xong (có gap) - KHÔNG được gợi ý lại tên bảng, "
                + "nhưng PHẢI còn colid (đại diện vị trí alias, vì DELETE dùng "
                + "relation_expr_opt_alias: relation_expr (AS? colid)? thay vì "
                + "table_ref/opt_alias_clause/table_alias như SELECT)",
            "delete from public.contracts ",
            List.of("colid"),
            List.of("qualified_name")
        ),
        new Case(
            "CREATE INDEX ... ON table (|) - colid (tên cột lập index) phải match, "
                + "ancestor = index_elem (ngữ cảnh MỚI, khác alias/columnref/alter_table_cmd)",
            "create index idx1 on public.users (",
            List.of("colid"),
            List.of("qualified_name")
        ),

        new Case(
            "[Nhóm A] INSERT...ON CONFLICT...DO UPDATE SET - colid match đúng ancestor set_target "
                + "ở tầng syntactic, nhưng SemanticAnalyzer CHƯA biết map sang cột thật của bảng đích",
            "insert into public.users (id, name) values (1, 'a') on conflict (id) do update set ",
            List.of("colid"),
            List.of()
        ),

        new Case(
            "[Nhóm A] UPDATE...SET - colid match đúng ancestor set_target, "
                + "SemanticAnalyzer CHƯA biết bảng đích",
            "update public.users set ",
            List.of("colid"),
            List.of()
        ),

        new Case(
            "UPDATE...SET (col1, |) - item THỨ 2 trong set_target_list sau dấu phẩy - "
                + "KIỂM TRA RỦI RO genuine-continuation/sibling-branch suppress nhầm, "
                + "giống pattern đã từng lỗi ở FROM-list nhiều bảng (public.a, public.)",
            "update public.users set (name, ",
            List.of("colid"),
            List.of()
        )
    );

    static Stream<Case> provideCases() {
        return CASES.stream();
    }

    @ParameterizedTest
    @MethodSource("provideCases")
    void testCase(Case c) {
        System.out.println("\n---- " + c.description() + " ----");
        System.out.println("sql = \"" + c.sql() + "\"  cursorOffset=" + c.cursorOffset());

        var syntacticResults = SyntacticAnalyzer.analyze(c.sql(), c.cursorOffset());
        System.out.println("Raw rules matched: " + syntacticResults.candidates().rules.keySet().stream().map(id -> PostgreSQLParser.ruleNames[id]).toList());

        Set<String> matchedRuleNames = KeywordNoiseFilter.computeMatchedRuleNames(syntacticResults, c.cursorOffset());
        System.out.println("Final matchedRuleNames = " + matchedRuleNames);

        // Kiểm tra các rule bắt buộc phải có
        for (String expected : c.expectPresent()) {
            assertTrue(matchedRuleNames.contains(expected), "Thiếu rule bắt buộc phải có: " + expected + " trong case: " + c.description());
        }

        // Kiểm tra các rule phải vắng mặt
        for (String forbidden : c.expectAbsent()) {
            assertFalse(matchedRuleNames.contains(forbidden), "Có rule đáng lẽ phải bị suppress: " + forbidden + " trong case: " + c.description());
        }
    }
}