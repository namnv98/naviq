package com.sqlctx.sql.format;

import com.sqlctx.sql.antlr4.oracle.PlSqlLexer;
import com.sqlctx.sql.antlr4.postgresql.PostgreSQLLexer;
import com.sqlctx.sql.schema.Dialect;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlFormatterTest {

    @Test
    void selectWithJoinWhereGroupOrder() {
        String in = "select u.id, count(*) as n from users u left join orders o on o.user_id = u.id and o.status = 'paid' "
                + "where u.id > -1 and u.created between now() - interval '7 days' and now() group by u.id order by n desc limit 10;";
        assertEquals("""
                SELECT
                    u.id,
                    count(*) AS n
                FROM
                    users u
                    LEFT JOIN orders o ON o.user_id = u.id
                        AND o.status = 'paid'
                WHERE
                    u.id > -1
                    AND u.created BETWEEN now() - INTERVAL '7 days' AND now()
                GROUP BY
                    u.id
                ORDER BY
                    n DESC
                LIMIT 10;""", SqlFormatter.format(in, Dialect.POSTGRES));
    }

    @Test
    void subqueryIndentsByDepthAndCallsStayInline() {
        String in = "select * from users where id in (select user_id from orders where total > 0) and lower(name) like 'a%'";
        assertEquals("""
                SELECT
                    *
                FROM
                    users
                WHERE
                    id IN (
                        SELECT
                            user_id
                        FROM
                            orders
                        WHERE
                            total > 0
                    )
                    AND lower(name) LIKE 'a%'""", SqlFormatter.format(in, Dialect.POSTGRES));
    }

    @Test
    void keywordCaseOptionAndCommentsKept() {
        String in = "SELECT a -- note\nFROM t /* x */ WHERE b = 'Select'";
        assertEquals("""
                select
                    a -- note
                from
                    t /* x */
                where
                    b = 'Select'""", SqlFormatter.format(in, Dialect.POSTGRES, new SqlFormatter.Options(4, SqlFormatter.KeywordCase.LOWER)));
    }

    @Test
    void oracleKeepsPlsqlBlocksAndGluedOperators() {
        String in = "select * from dual where rownum <= 5;\nbegin\n  null;\nend;\n/\n";
        assertEquals("""
                SELECT
                    *
                FROM
                    dual
                WHERE
                    rownum <= 5;

                begin
                  null;
                end;
                /
                """, SqlFormatter.format(in, Dialect.ORACLE));
    }

    @Test
    void longCaseSplitsWhenElseEndShortCaseStaysInline() {
        String in = "select sum(case when o.status = 'paid' then 1 else 0 end) as paid, "
                + "case when o.total > 1000 then 'large' when o.total > 100 then 'medium' else case when o.total = 0 then 'zero' else 'small' end end as size "
                + "from orders o";
        assertEquals("""
                SELECT
                    sum(CASE WHEN o.status = 'paid' THEN 1 ELSE 0 END) AS paid,
                    CASE
                        WHEN o.total > 1000 THEN 'large'
                        WHEN o.total > 100 THEN 'medium'
                        ELSE CASE WHEN o.total = 0 THEN 'zero' ELSE 'small' END
                    END AS size
                FROM
                    orders o""", SqlFormatter.format(in, Dialect.POSTGRES));

        // CASE dài trong ngoặc hàm + subquery trong WHEN: không đổi token, ổn định khi format lại
        String nested = "select coalesce(case when exists (select 1 from orders x where x.user_id = u.id and x.total > 0) "
                + "then 'buyer' when u.created < now() - interval '1 year' then 'old' else null end, 'none') from users u";
        for (Dialect d : Dialect.values()) {
            for (String q : List.of(in, nested)) {
                String once = SqlFormatter.format(q, d);
                assertEquals(tokens(q, d), tokens(once, d), once);
                assertEquals(once, SqlFormatter.format(once, d), once);
            }
        }
    }

    @Test
    void dollarQuotedBodyKeptVerbatim() {
        String in = "create function f() returns int as $$\n  select  1;\n$$ language sql;";
        String out = SqlFormatter.format(in, Dialect.POSTGRES);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("$$\n  select  1;\n$$"), out);
        assertEquals(out, SqlFormatter.format(out, Dialect.POSTGRES));
    }

    /** ~200 câu đủ loại (DDL, DML, CTE, MERGE, GRANT...): format không được đổi token nào và phải ổn định. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void formattingPreservesTokensAndIsIdempotent(Dialect dialect) throws Exception {
        List<String> queries = Files.readAllLines(Path.of("tools/intellij-compare/queries.txt")).stream()
                .filter(l -> !l.isBlank() && !l.strip().startsWith("#"))
                .map(l -> l.replace("|", ""))
                .toList();
        for (String q : queries) {
            String once = SqlFormatter.format(q, dialect);
            assertEquals(tokens(q, dialect), tokens(once, dialect), "đổi token: " + q + "\n---\n" + once);
            assertEquals(once, SqlFormatter.format(once, dialect), "không ổn định: " + q);
        }
    }

    /** Token thật + comment; từ (không quote) so không phân biệt hoa/thường vì formatter đổi hoa/thường từ khoá. */
    private static List<String> tokens(String sql, Dialect dialect) {
        CharStream input = CharStreams.fromString(sql);
        Lexer lexer = dialect == Dialect.ORACLE ? new PlSqlLexer(input) : new PostgreSQLLexer(input);
        lexer.removeErrorListeners();
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();
        List<String> out = new ArrayList<>();
        for (Token t : stream.getTokens()) {
            String text = t.getText();
            if (t.getType() == Token.EOF || (t.getChannel() != Token.DEFAULT_CHANNEL && !text.startsWith("--") && !text.startsWith("/*"))) {
                continue;
            }
            boolean word = !text.isEmpty() && (Character.isLetter(text.charAt(0)) || text.charAt(0) == '_');
            out.add(word ? text.toUpperCase() : text.strip());
        }
        return out;
    }
}
