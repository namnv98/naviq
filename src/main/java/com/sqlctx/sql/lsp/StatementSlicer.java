package com.sqlctx.sql.lsp;

import com.sqlctx.sql.antlr4.oracle.PlSqlLexer;
import com.sqlctx.sql.antlr4.postgresql.PostgreSQLLexer;
import com.sqlctx.sql.schema.Dialect;
import org.antlr.v4.runtime.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tách 1 file SQL thành từng câu, để gợi ý/báo lỗi chỉ phân tích câu đang cần thay vì cả file (engine đi từ đầu
 * input tới caret - file dài thì chậm và dễ hết budget).
 * <p>
 * Ranh giới là ";" ngoài chuỗi/comment (dùng lexer thật nên ";" trong '...' hay $$...$$ không tính). Oracle: khối
 * PL/SQL (BEGIN/DECLARE/CREATE PROCEDURE...) có ";" bên trong, kết thúc bằng dòng chỉ có "/" như SQL*Plus.
 */
public final class StatementSlicer {

    private StatementSlicer() {
    }

    /** Đoạn [start, end) của file. */
    public record Slice(int start, int end) {
        public boolean contains(int offset) {
            return offset >= start && offset <= end;
        }
    }

    private static final Pattern ORACLE_SLASH_LINE = Pattern.compile("(?m)^[ \\t]*/[ \\t]*$");
    private static final Pattern ORACLE_PLSQL_START = Pattern.compile(
            "(?is)^\\s*(begin|declare|create\\s+(or\\s+replace\\s+)?(editionable\\s+|noneditionable\\s+)?"
                    + "(procedure|function|package|trigger|type\\s+body|type))\\b");

    public static List<Slice> slices(String text, Dialect dialect) {
        if (dialect != Dialect.ORACLE) {
            return splitBySemicolon(text, 0, text.length(), dialect);
        }
        List<Slice> out = new ArrayList<>();
        var m = ORACLE_SLASH_LINE.matcher(text);
        int chunkStart = 0;
        while (true) {
            boolean found = m.find();
            int chunkEnd = found ? m.start() : text.length();
            // tách theo ";" cho tới câu đầu tiên mở khối PL/SQL - từ đó tới "/" là 1 câu (có ";" bên trong)
            for (Slice part : splitBySemicolon(text, chunkStart, chunkEnd, dialect)) {
                if (ORACLE_PLSQL_START.matcher(text.substring(part.start(), part.end())).find()) {
                    out.add(new Slice(part.start(), chunkEnd));
                    break;
                }
                out.add(part);
            }
            if (!found) {
                break;
            }
            chunkStart = m.end();
        }
        return out;
    }

    /** Khối PL/SQL của Oracle (BEGIN/DECLARE/CREATE PROCEDURE...) - có ";" bên trong, không phải 1 câu truy vấn. */
    public static boolean isPlsqlBlock(String statement) {
        return ORACLE_PLSQL_START.matcher(statement).find();
    }

    /** Câu chứa {@code offset} (caret đứng ngay sau ";" vẫn tính cho câu trước - đang gõ nốt câu đó). */
    public static Slice sliceAt(String text, int offset, Dialect dialect) {
        for (Slice s : slices(text, dialect)) {
            if (s.contains(offset)) {
                return s;
            }
        }
        return new Slice(0, text.length());
    }

    private static List<Slice> splitBySemicolon(String text, int from, int to, Dialect dialect) {
        List<Slice> out = new ArrayList<>();
        CharStream input = CharStreams.fromString(text.substring(from, to));
        Lexer lexer = dialect == Dialect.ORACLE ? new PlSqlLexer(input) : new PostgreSQLLexer(input);
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        int start = from;
        for (Token t : tokens.getTokens()) {
            if (t.getType() != Token.EOF && t.getChannel() == Token.DEFAULT_CHANNEL && ";".equals(t.getText())) {
                int end = from + t.getStartIndex(); // không gồm ";"
                out.add(new Slice(start, end));
                start = end + 1;
            }
        }
        out.add(new Slice(start, to));
        return out;
    }
}
