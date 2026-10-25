package com.sqlctx.completion.hint;

import com.sqlctx.util.I18n;

import com.sqlctx.antlr4.oracle.PlSqlLexer;
import com.sqlctx.antlr4.oracle.PlSqlParser;
import com.sqlctx.antlr4.postgresql.PostgreSQLLexer;
import com.sqlctx.antlr4.postgresql.PostgreSQLParser;
import com.sqlctx.completion.CompletionBudget;
import com.sqlctx.completion.semantic.TwoStageParse;
import com.sqlctx.completion.syntactic.oracle.OracleSyntacticAnalyzer;
import com.sqlctx.completion.syntactic.postgresql.PostgresSyntacticAnalyzer;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import org.antlr.v4.runtime.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Dòng gợi ý phía trên status bar, tính lại ở mỗi lần vẽ ô nhập: chữ ký hàm khi caret nằm trong ngoặc của 1 lời gọi
 * hàm, không thì lỗi cú pháp nằm GIỮA câu - đoạn đã gõ không còn là phần đầu của câu hợp lệ nào (câu chỉ chưa gõ
 * xong thì không báo). Lexer/parser thật của
 * dialect; lỗi cú pháp cache theo nội dung buffer vì highlighter và status bar cùng hỏi ở mỗi phím.
 */
public final class InputHints {

    private InputHints() {
    }

    /** Parse để tìm lỗi cú pháp chạy ở mỗi phím nên có budget riêng, nhỏ hơn budget gợi ý. */
    static final long SYNTAX_BUDGET_MS = 100;

    /** Lời gọi hàm bao quanh caret. */
    public record CallAt(String function, int argumentIndex) {
    }

    /** Lỗi cú pháp tại [start, stop] (offset ký tự trong buffer, stop tính cả). */
    public record SyntaxProblem(int start, int stop, String tokenText) {
    }

    /** Hàm có ngoặc mở chưa đóng gần caret nhất, và caret đang ở tham số thứ mấy (từ 0); null nếu không trong lời gọi hàm. */
    public static CallAt callAt(String sql, int cursor, Dialect dialect) {
        List<Token> tokens = defaultChannelTokens(sql.substring(0, Math.max(0, Math.min(cursor, sql.length()))), dialect);
        int depth = 0;
        int argument = 0;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            String text = tokens.get(i).getText();
            switch (text) {
                case ")" -> depth++;
                case "," -> {
                    if (depth == 0) {
                        argument++;
                    }
                }
                case "(" -> {
                    if (depth > 0) {
                        depth--;
                        continue;
                    }
                    if (i == 0 || !isWord(tokens.get(i - 1).getText())) {
                        return null; // ngoặc subquery/danh sách, không phải lời gọi hàm
                    }
                    return new CallAt(tokens.get(i - 1).getText().toLowerCase(), argument);
                }
                case ";" -> {
                    return null;
                }
                default -> {
                }
            }
        }
        return null;
    }

    /**
     * Chữ ký của hàm bao quanh caret, tham số đang gõ đặt trong [ ]: "date_trunc(text, [timestamp]) → timestamp +3";
     * null nếu caret không trong lời gọi hàm có chữ ký đã biết.
     */
    public static String signatureHint(String sql, int cursor, Dialect dialect) {
        CallAt call = callAt(sql, cursor, dialect);
        String signature = call == null ? null : SchemaIndex.functionSignatures.get(call.function());
        if (signature == null || !signature.startsWith("(")) {
            return null;
        }
        int close = matchingParen(signature);
        if (close < 0) {
            return call.function() + signature;
        }
        List<String> args = splitTopLevel(signature.substring(1, close));
        StringBuilder out = new StringBuilder(call.function()).append('(');
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(i == call.argumentIndex() ? "[" + args.get(i) + "]" : args.get(i));
        }
        return out.append(signature.substring(close)).toString();
    }

    private static String cachedSql;
    private static Dialect cachedDialect;
    private static SyntaxProblem cachedProblem;

    /**
     * Lỗi cú pháp đầu tiên không phải do câu đang gõ dở: bỏ lỗi tại EOF (câu chưa xong); đang gõ ở cuối buffer thì
     * kiểm tra câu đã bỏ đi từ đang gõ dở - "join or|" (định gõ "orders") làm ANTLR báo lỗi ngay ở "join", không
     * phải ở từ đang gõ. null nếu không có lỗi, là meta-command, hoặc parse quá budget.
     */
    public static synchronized SyntaxProblem syntaxProblem(String sql, int cursor, Dialect dialect) {
        String checked = sql;
        if (cursor >= 0 && cursor <= sql.length() && sql.substring(cursor).isBlank()) {
            int wordStart = cursor;
            while (wordStart > 0 && (Character.isLetterOrDigit(sql.charAt(wordStart - 1)) || sql.charAt(wordStart - 1) == '_')) {
                wordStart--;
            }
            checked = sql.substring(0, wordStart);
        }
        if (!(checked.equals(cachedSql) && dialect == cachedDialect)) {
            cachedSql = checked;
            cachedDialect = dialect;
            cachedProblem = firstProblem(checked, dialect);
        }
        return cachedProblem;
    }

    /**
     * Lỗi cú pháp thật trong 1 câu hoàn chỉnh (không có khái niệm caret/từ đang gõ) - cho Language Server báo lỗi
     * từng câu trong file. Không cache.
     */
    public static SyntaxProblem syntaxProblemIn(String sql, Dialect dialect) {
        return firstProblem(sql, dialect);
    }

    private static SyntaxProblem firstProblem(String sql, Dialect dialect) {
        if (sql.isBlank() || sql.stripLeading().startsWith("\\")) {
            return null;
        }
        try {
            // engine gợi ý không phục hồi lỗi: còn tới được cuối câu thì mọi lỗi parser báo chỉ là "chưa gõ xong"
            // (ANTLR hay đặt lỗi đó ở token bắt đầu quyết định, vd "join" trong "from users u join ", không ở EOF)
            var candidates = dialect == Dialect.ORACLE
                    ? OracleSyntacticAnalyzer.analyze(sql, sql.length()).candidates()
                    : PostgresSyntacticAnalyzer.analyze(sql, sql.length()).candidates();
            if (candidates.timedOut || candidates.reachedCaret()) {
                return null;
            }
            // chỉ để chỉ ra chỗ sai
            CommonTokenStream tokens = new CommonTokenStream(lexer(sql, dialect));
            tokens.fill();
            var parsed = dialect == Dialect.ORACLE
                    ? TwoStageParse.parse(tokens, PlSqlParser::new, PlSqlParser::sql_script, SYNTAX_BUDGET_MS)
                    : TwoStageParse.parse(tokens, PostgreSQLParser::new, PostgreSQLParser::root, SYNTAX_BUDGET_MS);
            return parsed.offendingTokens().stream()
                    .sorted()
                    .map(tokens::get)
                    .filter(t -> t.getType() != Token.EOF)
                    .findFirst()
                    .map(t -> new SyntaxProblem(t.getStartIndex(), t.getStopIndex(), t.getText()))
                    .orElse(null);
        } catch (CompletionBudget.Exceeded e) {
            return null;
        } catch (RuntimeException e) {
            return null; // gợi ý không được làm hỏng ô nhập
        }
    }

    /** Dòng hiển thị: chữ ký hàm nếu có, không thì lỗi cú pháp; null nếu không có gì để nói. */
    public static String hintLine(String sql, int cursor, Dialect dialect) {
        String signature = signatureHint(sql, cursor, dialect);
        if (signature != null) {
            return signature;
        }
        SyntaxProblem problem = syntaxProblem(sql, cursor, dialect);
        if (problem == null) {
            return null;
        }
        int line = 1;
        int col = 1;
        for (int i = 0; i < problem.start(); i++) {
            if (sql.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return I18n.t("sql.hint.syntaxError", problem.tokenText(), line, col);
    }

    private static Lexer lexer(String sql, Dialect dialect) {
        CharStream input = CharStreams.fromString(sql);
        Lexer lexer = dialect == Dialect.ORACLE ? new PlSqlLexer(input) : new PostgreSQLLexer(input);
        lexer.removeErrorListeners();
        return lexer;
    }

    private static List<Token> defaultChannelTokens(String sql, Dialect dialect) {
        CommonTokenStream stream = new CommonTokenStream(lexer(sql, dialect));
        stream.fill();
        List<Token> out = new ArrayList<>();
        for (Token t : stream.getTokens()) {
            if (t.getType() != Token.EOF && t.getChannel() == Token.DEFAULT_CHANNEL) {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean isWord(String text) {
        return !text.isEmpty() && (Character.isLetter(text.charAt(0)) || text.charAt(0) == '_' || text.charAt(0) == '"');
    }

    private static int matchingParen(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '(') {
                depth++;
            } else if (s.charAt(i) == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Tách theo dấu phẩy không nằm trong ngoặc: "numeric(10, 2), text" -> [numeric(10, 2), text]. */
    private static List<String> splitTopLevel(String args) {
        List<String> out = new ArrayList<>();
        if (args.isBlank()) {
            return out;
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(args.substring(start, i).strip());
                start = i + 1;
            }
        }
        out.add(args.substring(start).strip());
        return out;
    }

    /** Chỉ cho test: xoá cache lỗi cú pháp. */
    static synchronized void clearCache() {
        cachedSql = null;
        cachedDialect = null;
        cachedProblem = null;
    }
}
