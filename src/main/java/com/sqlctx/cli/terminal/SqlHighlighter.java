package com.sqlctx.cli.terminal;

import com.sqlctx.dialect.DialectAdapters;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.Set;

/**
 * Tô màu theo NỘI DUNG chữ của token (uppercase), không theo hằng số kiểu token của 1 lexer cụ thể -
 * vì vậy dùng chung được cho cả Postgres lẫn Oracle (2 lexer khác nhau hoàn toàn, số hiệu token khác
 * nhau, nhưng chữ "SELECT"/"FROM"/"WHERE"... giống hệt nhau ở cả 2 dialect). Lexer chỉ dùng để TÁCH
 * token (đúng ranh giới chuỗi/comment/định danh theo cú pháp thật của từng dialect), không dùng để
 * quyết định màu.
 */
public class SqlHighlighter implements Highlighter {

    private final Dialect dialect;

    public SqlHighlighter(Dialect dialect) {
        this.dialect = dialect;
    }

    private static final Set<String> DML = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "INTO", "VALUES", "SET", "MERGE");
    private static final Set<String> DDL = Set.of("CREATE", "TABLE", "DROP", "TRUNCATE", "ALTER", "ADD", "COLUMN", "RENAME");
    private static final Set<String> CLAUSE = Set.of("FROM", "WHERE", "GROUP", "BY", "ORDER", "HAVING",
            "LIMIT", "OFFSET", "AS", "WITH", "ON", "USING", "FETCH", "ROWNUM", "ROWID", "DUAL");
    private static final Set<String> JOIN = Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL");
    private static final Set<String> LOGIC = Set.of("AND", "OR", "NOT", "IN", "LIKE", "EXISTS", "IS", "NULL",
            "CASE", "WHEN", "THEN", "ELSE", "END");
    private static final Set<String> ORDERING = Set.of("ASC", "DESC");
    private static final Set<String> OPERATORS = Set.of("=", "!=", "<>", "<", ">", "<=", ">=", "+", "-", "*", "/", ":=");

    @Override
    public AttributedString highlight(LineReader lineReader, String s) {
        AttributedStringBuilder sb = new AttributedStringBuilder();

        CharStream input = CharStreams.fromString(s);
        Lexer lexer = DialectAdapters.of(dialect).createLexer(input);
        lexer.removeErrorListeners();

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();

        int pos = 0;
        for (Token t : tokens.getTokens()) {
            if (t.getType() == Token.EOF) break;

            if (t.getStartIndex() > pos) {
                sb.append(s.substring(pos, t.getStartIndex()));
            }

            String text = t.getText();
            AttributedStyle style = styleForText(text, isIdentifierToken(t));
            sb.style(style).append(text);

            pos = t.getStopIndex() + 1;
        }

        if (pos < s.length()) {
            sb.append(s.substring(pos));
        }

        return sb.toAttributedString();
    }

    /**
     * Từ khoá KHÔNG nằm trong danh sách bên dưới (vd "PRIMARY", "REFERENCES"...) phải giữ màu mặc định
     * như trước đây, KHÔNG bị tô nhầm thành màu định danh (bảng/cột/alias) - nên cần biết chắc token này
     * thật sự là 1 định danh (Identifier của Postgres, REGULAR_ID của Oracle) chứ không phải 1 từ khoá
     * khác mà danh sách trên chưa liệt kê tới.
     */
    private boolean isIdentifierToken(Token t) {
        return DialectAdapters.of(dialect).isIdentifierToken(t.getType());
    }

    private static AttributedStyle styleForText(String text, boolean isIdentifierToken) {
        if (text.isEmpty()) {
            return AttributedStyle.DEFAULT;
        }

        char c0 = text.charAt(0);

        if (c0 == '\'') {
            return AttributedStyle.DEFAULT.foreground(114);
        }
        if (Character.isDigit(c0)) {
            return AttributedStyle.DEFAULT.foreground(220);
        }
        if (OPERATORS.contains(text)) {
            return AttributedStyle.DEFAULT.foreground(203);
        }
        if (text.equals(".")) {
            return AttributedStyle.DEFAULT.foreground(244);
        }

        String upper = text.toUpperCase();
        if (DML.contains(upper)) {
            return AttributedStyle.BOLD.foreground(75);
        }
        if (DDL.contains(upper)) {
            return AttributedStyle.BOLD.foreground(208);
        }
        if (CLAUSE.contains(upper)) {
            return AttributedStyle.DEFAULT.foreground(75);
        }
        if (JOIN.contains(upper)) {
            return AttributedStyle.DEFAULT.foreground(111);
        }
        if (LOGIC.contains(upper)) {
            return AttributedStyle.DEFAULT.foreground(141);
        }
        if (ORDERING.contains(upper)) {
            return AttributedStyle.DEFAULT.foreground(75);
        }

        if (isIdentifierToken) {
            String key = text.toLowerCase();
            if (SchemaIndex.tableIndex.containsKey(key)) {
                return AttributedStyle.DEFAULT.foreground(214);
            }
            if (isSchema(key)) {
                return AttributedStyle.DEFAULT.foreground(109);
            }
            if (isColumn(key)) {
                return AttributedStyle.DEFAULT.foreground(150);
            }
            return AttributedStyle.DEFAULT.foreground(183);
        }

        return AttributedStyle.DEFAULT;
    }

    private static boolean isSchema(String text) {
        return SchemaIndex.schemas.stream()
                .anyMatch(s -> s.name().equals(text));
    }

    private static boolean isColumn(String text) {
        return SchemaIndex.tableIndex.values().stream()
                .anyMatch(t -> t.columns().stream()
                        .anyMatch(c -> c.name().equals(text)));
    }
}
