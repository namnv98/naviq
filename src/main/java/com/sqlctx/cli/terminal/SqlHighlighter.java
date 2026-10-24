package com.sqlctx.cli.terminal;

import com.sqlctx.completion.hint.InputHints;
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

import java.util.List;
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
    private static final Set<String> LOGIC = Set.of("AND", "OR", "NOT", "IN", "LIKE", "EXISTS", "IS", "NULL", "TRUE", "FALSE",
            "CASE", "WHEN", "THEN", "ELSE", "END");
    private static final Set<String> ORDERING = Set.of("ASC", "DESC");
    private static final Set<String> OPERATORS = Set.of("=", "!=", "<>", "<", ">", "<=", ">=", "+", "-", "*", "/", ":=",
            "||", "%", "^", "::");

    @Override
    public AttributedString highlight(LineReader lineReader, String s) {
        AttributedStringBuilder sb = new AttributedStringBuilder();

        CharStream input = CharStreams.fromString(s);
        Lexer lexer = DialectAdapters.of(dialect).createLexer(input);
        lexer.removeErrorListeners();

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();

        // lỗi cú pháp giữa câu: gạch chân đỏ đúng token lỗi (lỗi ở cuối = đang gõ dở, InputHints đã bỏ qua)
        int cursor = lineReader == null ? s.length() : Math.min(lineReader.getBuffer().cursor(), s.length());
        InputHints.SyntaxProblem problem = lineReader != null && s.equals(lineReader.getBuffer().toString())
                ? InputHints.syntaxProblem(s, cursor, dialect) : null;

        List<Token> all = tokens.getTokens();
        int pos = 0;
        String prevReal = "";
        for (int i = 0; i < all.size(); i++) {
            Token t = all.get(i);
            if (t.getType() == Token.EOF) break;

            if (t.getStartIndex() > pos) {
                sb.append(s.substring(pos, t.getStartIndex()));
            }

            String text = t.getText();
            boolean real = t.getChannel() == Token.DEFAULT_CHANNEL;
            AttributedStyle style = real
                    ? styleForText(text, isIdentifierToken(t), prevReal, nextRealText(all, i))
                    : hiddenStyle(text);
            if (real) {
                prevReal = text;
            }
            if (problem != null && t.getStartIndex() == problem.start()) {
                style = style.underline().foreground(203);
            }
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

    // Bảng màu (256 màu): mỗi loại 1 sắc riêng - từ khoá cùng 1 họ xanh dương, lệnh (SELECT/CREATE...) in đậm.
    private static final int KEYWORD = 75;
    private static final int TABLE = 214;
    private static final int SCHEMA = 109;
    private static final int COLUMN = 152;
    private static final int ALIAS = 174;
    private static final int FUNCTION = 117;
    private static final int STRING = 114;
    private static final int NUMBER = 141;
    private static final int OPERATOR = 203;
    private static final int MUTED = 244;

    private static final Set<String> KEYWORDS = java.util.stream.Stream.of(CLAUSE, JOIN, LOGIC, ORDERING)
            .flatMap(Set::stream).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Token không phải code (comment, khoảng trắng). */
    private static AttributedStyle hiddenStyle(String text) {
        return text.startsWith("--") || text.startsWith("/*")
                ? AttributedStyle.DEFAULT.foreground(MUTED).italic()
                : AttributedStyle.DEFAULT;
    }

    private static String nextRealText(List<Token> tokens, int i) {
        for (int k = i + 1; k < tokens.size(); k++) {
            Token t = tokens.get(k);
            if (t.getType() == Token.EOF) {
                return "";
            }
            if (t.getChannel() == Token.DEFAULT_CHANNEL) {
                return t.getText();
            }
        }
        return "";
    }

    /**
     * @param prev token thật đứng trước ("." trước 1 từ: đó là tên cột/bảng, kể cả khi trùng từ khoá như "u.name")
     * @param next token thật đứng sau ("(" sau 1 từ không phải từ khoá: tên hàm)
     */
    private static AttributedStyle styleForText(String text, boolean isIdentifierToken, String prev, String next) {
        if (text.isEmpty()) {
            return AttributedStyle.DEFAULT;
        }

        char c0 = text.charAt(0);

        if (c0 == '\'' || c0 == '$' && text.length() > 1 && text.endsWith("$")) {
            return AttributedStyle.DEFAULT.foreground(STRING);
        }
        if (Character.isDigit(c0)) {
            return AttributedStyle.DEFAULT.foreground(NUMBER);
        }
        if (OPERATORS.contains(text)) {
            return AttributedStyle.DEFAULT.foreground(OPERATOR);
        }
        if (text.equals(".") || text.equals(",") || text.equals(";")) {
            return AttributedStyle.DEFAULT.foreground(MUTED);
        }

        boolean word = Character.isLetter(c0) || c0 == '_' || c0 == '"';
        if (!word) {
            return AttributedStyle.DEFAULT;
        }
        String upper = text.toUpperCase();
        boolean afterDot = prev.equals(".");
        if (!afterDot) {
            if (DML.contains(upper) || DDL.contains(upper)) {
                return AttributedStyle.BOLD.foreground(KEYWORD);
            }
            if (KEYWORDS.contains(upper)) {
                return AttributedStyle.DEFAULT.foreground(KEYWORD);
            }
        }

        String key = text.startsWith("\"") ? text.substring(1, Math.max(1, text.length() - 1)) : text.toLowerCase();
        Names names = names();
        if (afterDot) {
            // "u.cột", "schema.bảng": phía sau dấu chấm luôn là tên, kể cả trùng từ khoá không dành riêng
            if (names.columns.contains(key)) {
                return AttributedStyle.DEFAULT.foreground(COLUMN);
            }
            if (names.tables.contains(key)) {
                return AttributedStyle.DEFAULT.foreground(TABLE);
            }
            return AttributedStyle.DEFAULT.foreground(COLUMN);
        }
        if (next.equals("(") && !names.tables.contains(key)) {
            return AttributedStyle.DEFAULT.foreground(FUNCTION);
        }
        if (names.tables.contains(key)) {
            return AttributedStyle.DEFAULT.foreground(TABLE);
        }
        if (names.schemas.contains(key) && next.equals(".")) {
            return AttributedStyle.DEFAULT.foreground(SCHEMA);
        }
        if (names.columns.contains(key)) {
            return AttributedStyle.DEFAULT.foreground(COLUMN);
        }
        // định danh lạ (alias, tên chưa có trong schema); từ khoá không dành riêng khác ("type"...) giữ màu thường
        return isIdentifierToken ? AttributedStyle.DEFAULT.foreground(ALIAS) : AttributedStyle.DEFAULT;
    }

    /** Tên bảng/schema/cột dựng 1 lần cho mỗi phiên bản schema - highlight chạy ở mỗi phím, không duyệt lại cả schema. */
    private record Names(Object source, Set<String> tables, Set<String> schemas, Set<String> columns) {
    }

    private static volatile Names cachedNames;

    private static Names names() {
        var tableIndex = SchemaIndex.tableIndex;
        Names cached = cachedNames;
        if (cached != null && cached.source() == tableIndex) {
            return cached;
        }
        Set<String> tables = new java.util.HashSet<>();
        Set<String> columns = new java.util.HashSet<>();
        tableIndex.values().forEach(t -> {
            tables.add(t.name().toLowerCase());
            tables.add(t.name());
            t.columns().forEach(c -> {
                columns.add(c.name().toLowerCase());
                columns.add(c.name());
            });
        });
        Set<String> schemas = new java.util.HashSet<>();
        SchemaIndex.schemas.forEach(sc -> schemas.add(sc.name().toLowerCase()));
        Names fresh = new Names(tableIndex, tables, schemas, columns);
        cachedNames = fresh;
        return fresh;
    }

}
