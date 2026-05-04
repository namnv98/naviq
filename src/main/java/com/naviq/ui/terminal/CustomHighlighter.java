package com.naviq.ui.terminal;

import com.example.PostgreSQLLexer;
import com.naviq.completion.sql.PostgresCompletionEngine;
import com.naviq.datasource.SchemaLoader;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.Set;
import java.util.stream.Collectors;


public class CustomHighlighter implements Highlighter {
    private static final Set<String> ALL_COLUMNS = PostgresCompletionEngine.TABLE_INDEX
            .values().stream()
            .flatMap(t -> t.columns().stream().map(SchemaLoader.DBColumnInfo::name))
            .collect(Collectors.toSet());

    @Override
    public AttributedString highlight(LineReader lineReader, String s) {
        AttributedStringBuilder sb = new AttributedStringBuilder();

        CharStream input = CharStreams.fromString(s);
        PostgreSQLLexer lexer = new PostgreSQLLexer(input);
        lexer.removeErrorListeners();

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();

        int pos = 0;
        for (Token t : tokens.getTokens()) {
            if (t.getType() == Token.EOF) break;

            // Điền khoảng trắng giữa các token
            if (t.getStartIndex() > pos) {
                sb.append(s.substring(pos, t.getStartIndex()));
            }

            String text = t.getText();
            AttributedStyle style = styleForToken(t);
            sb.style(style).append(text);

            pos = t.getStopIndex() + 1;
        }

        // Phần còn lại
        if (pos < s.length()) {
            sb.append(s.substring(pos));
        }

        return sb.toAttributedString();
    }

    private static AttributedStyle styleForToken(Token t) {
        int type = t.getType();

        // Keywords DML
        if (type == PostgreSQLLexer.SELECT || type == PostgreSQLLexer.INSERT
                || type == PostgreSQLLexer.UPDATE || type == PostgreSQLLexer.DELETE
                || type == PostgreSQLLexer.INTO || type == PostgreSQLLexer.VALUES
                || type == PostgreSQLLexer.SET) {
            return AttributedStyle.BOLD.foreground(75); // xanh dương đậm
        }

        // Keywords DDL
        if (type == PostgreSQLLexer.CREATE || type == PostgreSQLLexer.TABLE
                || type == PostgreSQLLexer.DROP || type == PostgreSQLLexer.TRUNCATE
                || type == PostgreSQLLexer.ALTER || type == PostgreSQLLexer.ADD
                || type == PostgreSQLLexer.COLUMN || type == PostgreSQLLexer.RENAME) {
            return AttributedStyle.BOLD.foreground(208); // cam
        }

        // Keywords clause
        if (type == PostgreSQLLexer.FROM || type == PostgreSQLLexer.WHERE
                || type == PostgreSQLLexer.GROUP || type == PostgreSQLLexer.BY
                || type == PostgreSQLLexer.ORDER || type == PostgreSQLLexer.HAVING
                || type == PostgreSQLLexer.LIMIT || type == PostgreSQLLexer.OFFSET
                || type == PostgreSQLLexer.AS || type == PostgreSQLLexer.WITH
                || type == PostgreSQLLexer.ON || type == PostgreSQLLexer.USING) {
            return AttributedStyle.DEFAULT.foreground(75); // xanh dương
        }

        // Keywords JOIN
        if (type == PostgreSQLLexer.JOIN || type == PostgreSQLLexer.INNER
                || type == PostgreSQLLexer.LEFT || type == PostgreSQLLexer.RIGHT
                || type == PostgreSQLLexer.FULL || type == PostgreSQLLexer.OUTER
                || type == PostgreSQLLexer.CROSS || type == PostgreSQLLexer.NATURAL) {
            return AttributedStyle.DEFAULT.foreground(111); // xanh nhạt
        }

        // Keywords logic
        if (type == PostgreSQLLexer.AND || type == PostgreSQLLexer.OR
                || type == PostgreSQLLexer.NOT || type == PostgreSQLLexer.IN
                || type == PostgreSQLLexer.LIKE || type == PostgreSQLLexer.EXISTS
                || type == PostgreSQLLexer.IS || type == PostgreSQLLexer.NULL_
                || type == PostgreSQLLexer.CASE || type == PostgreSQLLexer.WHEN
                || type == PostgreSQLLexer.THEN || type == PostgreSQLLexer.ELSE
                || type == PostgreSQLLexer.END) {
            return AttributedStyle.DEFAULT.foreground(141); // tím
        }

        // ASC / DESC
        if (type == PostgreSQLLexer.ASC || type == PostgreSQLLexer.DESC) {
            return AttributedStyle.DEFAULT.foreground(75);
        }

        // String literal
        if (type == PostgreSQLLexer.STRING) {
            return AttributedStyle.DEFAULT.foreground(114); // xanh lá
        }

        // Number
        if (type == PostgreSQLLexer.NUMBER) {
            return AttributedStyle.DEFAULT.foreground(220); // vàng
        }

        // Operator
        if (type == PostgreSQLLexer.EQ || type == PostgreSQLLexer.NEQ
                || type == PostgreSQLLexer.LT || type == PostgreSQLLexer.GT
                || type == PostgreSQLLexer.LTE || type == PostgreSQLLexer.GTE
                || type == PostgreSQLLexer.PLUS || type == PostgreSQLLexer.MINUS
                || type == PostgreSQLLexer.STAR || type == PostgreSQLLexer.SLASH) {
            return AttributedStyle.DEFAULT.foreground(203); // đỏ nhạt
        }

        // DOT
        if (type == PostgreSQLLexer.DOT) {
            return AttributedStyle.DEFAULT.foreground(244);
        }

        // Identifier — table / column / alias
        if (type == PostgreSQLLexer.ID) {
            String text = t.getText().toLowerCase();

            if (PostgresCompletionEngine.TABLE_INDEX.containsKey(text)) {
                return AttributedStyle.DEFAULT.foreground(214); // cam — table
            }
            if (isSchema(text)) {
                return AttributedStyle.DEFAULT.foreground(109); // cyan nhạt — schema
            }
            if (ALL_COLUMNS.contains(text)) {
                return AttributedStyle.DEFAULT.foreground(150); // xanh lá nhạt — column
            }
            return AttributedStyle.DEFAULT.foreground(183); // tím nhạt — alias/unknown
        }

        return AttributedStyle.DEFAULT;
    }


    private static boolean isSchema(String text) {
        return PostgresCompletionEngine.DB_SCHEMA.stream()
                .anyMatch(s -> s.name().equals(text));
    }

    private static boolean isColumn(String text) {
        return PostgresCompletionEngine.TABLE_INDEX.values().stream()
                .anyMatch(t -> t.columns().stream()
                        .anyMatch(c -> c.name().equals(text)));
    }
}
