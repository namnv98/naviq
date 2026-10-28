package com.sqlctx.sql.format;

import com.sqlctx.sql.antlr4.oracle.PlSqlLexer;
import com.sqlctx.sql.antlr4.postgresql.PostgreSQLLexer;
import com.sqlctx.sql.lsp.StatementSlicer;
import com.sqlctx.sql.schema.Dialect;
import org.antlr.v4.runtime.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Định dạng SQL theo token của lexer thật (chuỗi, comment, định danh có quote, thân $$...$$ giữ nguyên từng ký tự),
 * kiểu pgFormatter: từ khoá mệnh đề (SELECT, FROM, WHERE...) đứng riêng 1 dòng, nội dung thụt vào 1 cấp, mỗi phần tử
 * danh sách 1 dòng, AND/OR của điều kiện xuống dòng, subquery thụt theo độ sâu. Trong ngoặc thường (lời gọi hàm,
 * IN (...), OVER (...)) giữ trên 1 dòng. Chỉ đổi khoảng trắng và hoa/thường của từ khoá - không đổi thứ tự token.
 * <p>
 * Khối PL/SQL của Oracle (BEGIN/DECLARE/CREATE PROCEDURE...) giữ nguyên: bố cục của chúng là code thủ tục, không phải
 * 1 câu truy vấn.
 */
public final class SqlFormatter {

    public enum KeywordCase { UPPER, LOWER, PRESERVE }

    /** @param indent số khoảng trắng mỗi cấp thụt lề */
    public record Options(int indent, KeywordCase keywordCase) {
        public static final Options DEFAULT = new Options(4, KeywordCase.UPPER);

        /** Từ cờ dòng lệnh --keyword-case=upper|lower|preserve và --indent=N (đã thành system property DB_*). */
        public static Options fromSystemProperties() {
            KeywordCase keywordCase = KeywordCase.UPPER;
            String kc = System.getProperty("DB_KEYWORD-CASE");
            if (kc != null) {
                keywordCase = KeywordCase.valueOf(kc.strip().toUpperCase());
            }
            int indent = Integer.parseInt(System.getProperty("DB_INDENT", "4").strip());
            return new Options(indent, keywordCase);
        }

        public Options withIndent(int newIndent) {
            return new Options(newIndent, keywordCase);
        }
    }

    private SqlFormatter() {
    }

    /** Định dạng cả đoạn SQL (nhiều câu, phân cách ";"); mỗi câu cách nhau 1 dòng trống. */
    public static String format(String sql, Dialect dialect, Options options) {
        List<String> out = new ArrayList<>();
        for (StatementSlicer.Slice slice : StatementSlicer.slices(sql, dialect)) {
            String statement = sql.substring(slice.start(), slice.end()).strip();
            if (statement.isEmpty()) {
                continue;
            }
            String rest = sql.substring(slice.end());
            boolean plsql = dialect == Dialect.ORACLE && StatementSlicer.isPlsqlBlock(statement);
            String formatted = plsql ? statement : new Layout(statement, dialect, options).run();
            if (plsql && rest.stripLeading().startsWith("/")) {
                formatted += "\n/";
            } else if (rest.startsWith(";")) {
                formatted += ";";
            }
            out.add(formatted);
        }
        return String.join("\n\n", out) + (sql.endsWith("\n") ? "\n" : "");
    }

    public static String format(String sql, Dialect dialect) {
        return format(sql, dialect, Options.DEFAULT);
    }

    // ── 1 câu ────────────────────────────────────────────────────────

    /** Từ khoá đổi hoa/thường. Chỉ từ khoá cấu trúc - tên hàm/kiểu (count, varchar...) giữ nguyên như người dùng viết. */
    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "IS", "NULL", "AS", "ON", "JOIN", "LEFT", "RIGHT",
            "INNER", "OUTER", "FULL", "CROSS", "NATURAL", "LATERAL", "USING", "GROUP", "BY", "ORDER", "HAVING", "LIMIT",
            "OFFSET", "FETCH", "FIRST", "NEXT", "ROWS", "ROW", "ONLY", "UNION", "ALL", "DISTINCT", "INTERSECT", "EXCEPT",
            "MINUS", "CASE", "WHEN", "THEN", "ELSE", "END", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "MERGE", "MATCHED", "RETURNING", "CREATE", "TABLE", "VIEW", "INDEX", "ALTER", "DROP", "TRUNCATE", "ADD",
            "COLUMN", "CONSTRAINT", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "UNIQUE", "CHECK", "DEFAULT", "WITH",
            "RECURSIVE", "EXISTS", "BETWEEN", "LIKE", "ILIKE", "ASC", "DESC", "NULLS", "OVER", "PARTITION", "WINDOW",
            "FILTER", "CAST", "TRUE", "FALSE", "IF", "REPLACE", "GRANT", "REVOKE", "TO", "EXPLAIN", "ANALYZE",
            "INTERVAL", "CONFLICT", "DO", "NOTHING", "FOR", "OF", "ESCAPE", "SOME", "ANY", "MATERIALIZED",
            "CONNECT", "PRIOR", "START", "NOCYCLE");

    /** Mệnh đề mà nội dung xuống dòng, thụt 1 cấp; dấu phẩy ở cấp mệnh đề tách mỗi phần tử 1 dòng. */
    private static final Set<String> BLOCK_CLAUSES = Set.of(
            "SELECT", "FROM", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "SET", "VALUES", "RETURNING", "WINDOW");
    /** Mệnh đề giữ nội dung trên cùng dòng. */
    private static final Set<String> INLINE_CLAUSES = Set.of("LIMIT", "OFFSET", "FETCH");
    private static final Set<String> SET_OPERATIONS = Set.of("UNION", "INTERSECT", "EXCEPT", "MINUS");
    /** Từ mở đầu 1 câu - chỉ tính là mở đầu khi đứng đầu câu/subquery ("FOR UPDATE" không phải câu UPDATE). */
    private static final Set<String> STATEMENT_STARTS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "WITH");
    private static final Set<String> JOIN_WORDS = Set.of("LEFT", "RIGHT", "FULL", "INNER", "CROSS", "NATURAL", "OUTER", "JOIN");
    /** Sau các từ này "(" có khoảng trắng; sau định danh khác thì là lời gọi hàm: "count(*)". */
    private static final Set<String> SPACE_BEFORE_PAREN = Set.of(
            "IN", "VALUES", "AS", "ON", "USING", "EXISTS", "AND", "OR", "NOT", "FROM", "JOIN", "WHERE", "SELECT",
            "OVER", "FILTER", "WITH", "THEN", "ELSE", "WHEN", "BY", "SET", "RETURNING", "HAVING", "ANY", "SOME", "ALL",
            "LATERAL", "IS", "BETWEEN", "LIKE", "ILIKE", "CASE", "UNION", "INTERSECT", "EXCEPT", "MINUS", "DO",
            "CONFLICT", "PRIOR");

    /** 1 cấp ngoặc. {@code inline}: ngoặc thường - bên trong không xuống dòng (trừ subquery lồng). */
    private static final class Frame {
        final boolean inline;
        final boolean subquery;
        /** Ngoặc danh sách cột của CREATE TABLE: mỗi phần tử 1 dòng. */
        final boolean list;
        /** Cấp thụt của từ khoá mệnh đề trong frame này. */
        final int base;
        /** Cấp thụt của dòng chứa "(" - ")" đóng ở cấp này. */
        final int openLineLevel;
        String clause;
        String firstWord;
        boolean betweenPending;
        boolean afterDelete;
        /** Khối CASE nhiều dòng: WHEN/ELSE xuống dòng ở {@link #base}, END ở {@link #openLineLevel}. */
        boolean caseBlock;
        /** Số CASE ngắn (1 dòng) đang mở bên trong khối CASE này - WHEN/ELSE/END của chúng không xuống dòng. */
        int inlineCases;

        Frame(boolean inline, boolean subquery, boolean list, int base, int openLineLevel) {
            this.inline = inline;
            this.subquery = subquery;
            this.list = list;
            this.base = base;
            this.openLineLevel = openLineLevel;
        }
    }

    private static final class Layout {
        private final List<Token> tokens;
        private final Options options;
        private final StringBuilder out = new StringBuilder();
        private final Deque<Frame> frames = new ArrayDeque<>();
        private int lineLevel;
        /** Token thật (không phải comment) vừa ghi ra, dạng chữ hoa. */
        private String prevUpper = "";
        private String prevPrevUpper = "";
        private boolean prevIsWord;

        Layout(String statement, Dialect dialect, Options options) {
            this.options = options;
            CharStream input = CharStreams.fromString(statement);
            Lexer lexer = dialect == Dialect.ORACLE ? new PlSqlLexer(input) : new PostgreSQLLexer(input);
            lexer.removeErrorListeners();
            CommonTokenStream stream = new CommonTokenStream(lexer);
            stream.fill();
            List<Token> kept = new ArrayList<>();
            List<Token> all = stream.getTokens();
            for (int k = 0; k < all.size(); k++) {
                Token t = all.get(k);
                if (t.getType() == Token.EOF) {
                    break;
                }
                if (t.getType() == PostgreSQLLexer.BeginDollarStringConstant && dialect != Dialect.ORACLE) {
                    // $$...$$ là 1 chuỗi (thân hàm/DO) nhưng lexer tách nhiều token - gộp lại, chép nguyên văn
                    int end = k;
                    while (end + 1 < all.size() && all.get(end).getType() != PostgreSQLLexer.EndDollarStringConstant
                            && all.get(end + 1).getType() != Token.EOF) {
                        end++;
                    }
                    CommonToken whole = new CommonToken(t);
                    whole.setText(statement.substring(t.getStartIndex(), all.get(end).getStopIndex() + 1));
                    whole.setStopIndex(all.get(end).getStopIndex());
                    kept.add(whole);
                    k = end;
                    continue;
                }
                String text = t.getText();
                if (t.getChannel() == Token.DEFAULT_CHANNEL || isComment(text)) {
                    kept.add(t);
                }
            }
            this.tokens = kept;
            frames.push(new Frame(false, false, false, 0, 0));
        }

        String run() {
            for (int i = 0; i < tokens.size(); i++) {
                i = emit(i);
            }
            return out.toString().strip();
        }

        /** Ghi token thứ i (có thể ăn luôn token sau, vd "GROUP BY"); trả index token cuối đã dùng. */
        private int emit(int i) {
            Token t = tokens.get(i);
            String text = t.getText();
            String upper = text.toUpperCase();
            Frame frame = frames.peek();

            if (isComment(text)) {
                space();
                out.append(text.stripTrailing());
                if (text.startsWith("--")) {
                    newline(lineLevel);
                }
                return i;
            }
            if (text.equals("(")) {
                openParen(i);
                return i;
            }
            if (text.equals(")")) {
                closeParen();
                return i;
            }
            if (text.equals(",")) {
                out.append(',');
                remember(",", false);
                if (frame.list) {
                    newline(frame.base + 1);
                } else if (!frame.inline && frame.clause != null && (BLOCK_CLAUSES.contains(frame.clause) || frame.clause.equals("WITH"))) {
                    newline(frame.clause.equals("WITH") ? frame.base : frame.base + 1);
                }
                return i;
            }

            boolean word = isWord(text);
            if (word && !frame.inline && !prevUpper.equals(".")) {
                int consumed = clauseKeyword(i, upper, frame);
                if (consumed >= 0) {
                    return consumed;
                }
            }

            if (word && !prevUpper.equals(".")) {
                int handled = caseKeyword(i, upper, frame, t);
                if (handled >= 0) {
                    return handled;
                }
            }
            if (word && frame.firstWord == null) {
                frame.firstWord = upper;
            }
            if (upper.equals("BETWEEN")) {
                frame.betweenPending = true;
            }
            writeToken(text, word, i > 0 && isSymbol(text) && isSymbol(tokens.get(i - 1).getText())
                    && tokens.get(i - 1).getStopIndex() + 1 == t.getStartIndex());
            return i;
        }

        /** Từ khoá mệnh đề/JOIN/AND/OR ở cấp mệnh đề; trả index token cuối đã ghi, -1 nếu không phải. */
        private int clauseKeyword(int i, String upper, Frame frame) {
            String next = upperAt(i + 1);
            boolean frameStart = frame.firstWord == null;

            if (frameStart && STATEMENT_STARTS.contains(upper)) {
                frame.firstWord = upper;
                startClause(frame, upper, false);
                writeToken(tokens.get(i).getText(), true);
                if (upper.equals("DELETE")) {
                    frame.afterDelete = true;
                }
                return i;
            }
            if ((upper.equals("GROUP") || upper.equals("ORDER")) && next.equals("BY")) {
                if (frame.firstWord == null) {
                    frame.firstWord = upper;
                }
                startClause(frame, upper + " BY", true);
                writeToken(tokens.get(i).getText(), true);
                writeToken(tokens.get(i + 1).getText(), true);
                newline(frame.base + 1);
                return i + 1;
            }
            // Oracle CONNECT BY / START WITH: dòng riêng, nội dung cùng dòng
            if ((upper.equals("CONNECT") && next.equals("BY")) || (upper.equals("START") && next.equals("WITH"))) {
                startClause(frame, upper + " " + next, true);
                writeToken(tokens.get(i).getText(), true);
                writeToken(tokens.get(i + 1).getText(), true);
                return i + 1;
            }
            // INSERT ... ON CONFLICT: dòng riêng ở cấp câu
            if (upper.equals("ON") && next.equals("CONFLICT") && "INSERT".equals(frame.firstWord)) {
                startClause(frame, "ON CONFLICT", true);
                writeToken(tokens.get(i).getText(), true);
                return i;
            }
            if (SET_OPERATIONS.contains(upper)) {
                newline(frame.base);
                writeToken(tokens.get(i).getText(), true);
                int last = i;
                if (next.equals("ALL") || next.equals("DISTINCT")) {
                    writeToken(tokens.get(i + 1).getText(), true);
                    last = i + 1;
                }
                frame.clause = null;
                frame.firstWord = null; // câu SELECT tiếp theo mở đầu lại
                return last;
            }
            boolean blockClause = BLOCK_CLAUSES.contains(upper) && clauseAllowed(upper, frame);
            if (blockClause) {
                if (frame.firstWord == null) {
                    frame.firstWord = upper;
                }
                if (upper.equals("FROM") && frame.afterDelete) {
                    frame.afterDelete = false;
                    return -1; // DELETE FROM t: cùng dòng
                }
                startClause(frame, upper, true);
                writeToken(tokens.get(i).getText(), true);
                int last = i;
                if (upper.equals("SELECT") && (next.equals("DISTINCT") || next.equals("ALL"))) {
                    writeToken(tokens.get(i + 1).getText(), true);
                    last = i + 1;
                }
                newline(frame.base + 1);
                return last;
            }
            if (INLINE_CLAUSES.contains(upper) && frame.clause != null) {
                startClause(frame, upper, true);
                writeToken(tokens.get(i).getText(), true);
                return i;
            }
            if ("FROM".equals(frame.clause) && JOIN_WORDS.contains(upper) && !JOIN_WORDS.contains(prevUpper)
                    && (upper.equals("JOIN") || isJoinPhrase(i))) {
                newline(frame.base + 1);
                writeToken(tokens.get(i).getText(), true);
                return i;
            }
            if ((upper.equals("AND") || upper.equals("OR")) && frame.clause != null) {
                if (frame.betweenPending && upper.equals("AND")) {
                    frame.betweenPending = false;
                    return -1;
                }
                if (frame.clause.equals("WHERE") || frame.clause.equals("HAVING")) {
                    newline(frame.base + 1);
                } else if (frame.clause.equals("FROM")) {
                    newline(frame.base + 2); // điều kiện ON nhiều vế
                } else {
                    return -1;
                }
                writeToken(tokens.get(i).getText(), true);
                return i;
            }
            return -1;
        }

        /** CASE dài hơn bấy nhiêu ký tự (viết trên 1 dòng) thì tách WHEN/ELSE/END mỗi cái 1 dòng. */
        private static final int CASE_INLINE_MAX = 60;

        /** CASE/WHEN/ELSE/END của khối CASE nhiều dòng; trả index đã ghi, -1 nếu không phải. */
        private int caseKeyword(int i, String upper, Frame frame, Token t) {
            switch (upper) {
                case "CASE" -> {
                    if (inlineLength(i) <= CASE_INLINE_MAX) {
                        if (frame.caseBlock) {
                            frame.inlineCases++;
                        }
                        return -1; // ghi như từ thường
                    }
                    writeToken(t.getText(), true);
                    Frame block = new Frame(true, false, false, lineLevel + 1, lineLevel);
                    block.caseBlock = true;
                    frames.push(block);
                    return i;
                }
                case "WHEN", "ELSE" -> {
                    if (frame.caseBlock && frame.inlineCases == 0) {
                        newline(frame.base);
                        writeToken(t.getText(), true);
                        return i;
                    }
                    return -1;
                }
                case "END" -> {
                    if (frame.caseBlock) {
                        if (frame.inlineCases > 0) {
                            frame.inlineCases--;
                            return -1;
                        }
                        frames.pop();
                        newline(frame.openLineLevel);
                        writeToken(t.getText(), true);
                        return i;
                    }
                    return -1;
                }
                default -> {
                    return -1;
                }
            }
        }

        /** Độ dài khi viết CASE (từ token i tới END tương ứng) trên 1 dòng, cách nhau 1 khoảng trắng. */
        private int inlineLength(int start) {
            int depth = 0;
            int length = 0;
            for (int k = start; k < tokens.size(); k++) {
                String text = tokens.get(k).getText();
                if (isComment(text)) {
                    return Integer.MAX_VALUE; // có comment bên trong: tách dòng cho comment dòng khỏi nuốt phần sau
                }
                length += text.length() + 1;
                String u = text.toUpperCase();
                if (u.equals("CASE")) {
                    depth++;
                } else if (u.equals("END") && --depth == 0) {
                    return length - 1;
                }
            }
            return length; // thiếu END (câu đang gõ dở): coi độ dài tới cuối câu
        }

        /** SET chỉ là mệnh đề của UPDATE/MERGE/ON CONFLICT; VALUES của INSERT hoặc câu VALUES đứng riêng. */
        private boolean clauseAllowed(String upper, Frame frame) {
            return switch (upper) {
                case "SET" -> "UPDATE".equals(frame.firstWord) || "MERGE".equals(frame.firstWord)
                        || "INSERT".equals(frame.firstWord);
                case "VALUES" -> frame.firstWord == null || "INSERT".equals(frame.firstWord);
                case "FROM", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "RETURNING", "WINDOW", "SELECT" ->
                        frame.firstWord == null || !Set.of("CREATE", "ALTER", "DROP", "GRANT", "REVOKE").contains(frame.firstWord)
                                || upper.equals("SELECT");
                default -> true;
            };
        }

        /** LEFT/RIGHT/FULL/INNER/CROSS/NATURAL [OUTER] JOIN - không phải hàm left(...)/right(...). */
        private boolean isJoinPhrase(int i) {
            for (int k = i + 1; k < tokens.size() && k <= i + 3; k++) {
                String u = upperAt(k);
                if (u.equals("JOIN")) {
                    return true;
                }
                if (!JOIN_WORDS.contains(u)) {
                    return false;
                }
            }
            return false;
        }

        private void startClause(Frame frame, String clause, boolean ownLine) {
            frame.clause = clause;
            frame.betweenPending = false;
            if (ownLine) {
                newline(frame.base);
            }
        }

        private void openParen(int i) {
            Frame parent = frames.peek();
            String next = upperAt(i + 1);
            boolean subquery = next.equals("SELECT") || next.equals("WITH") || (next.equals("VALUES") && !parent.inline);
            boolean list = !subquery && !parent.inline && "CREATE".equals(parent.firstWord)
                    && prevPrevUpper.equals("TABLE") && prevIsWord;
            boolean call = prevIsWord && !SPACE_BEFORE_PAREN.contains(prevUpper)
                    && !(prevPrevUpper.equals("INTO") || prevPrevUpper.equals("TABLE") || prevPrevUpper.equals("VIEW"));
            if (!call) {
                space();
            }
            out.append('(');
            remember("(", false);
            if (subquery) {
                frames.push(new Frame(false, true, false, lineLevel + 1, lineLevel));
            } else if (list) {
                frames.push(new Frame(false, false, true, lineLevel, lineLevel));
                newline(lineLevel + 1);
            } else {
                frames.push(new Frame(true, false, false, parent.base, lineLevel));
            }
        }

        private void closeParen() {
            Frame closed = frames.size() > 1 ? frames.pop() : frames.peek();
            if (closed.subquery || closed.list) {
                newline(closed.openLineLevel);
            }
            out.append(')');
            remember(")", false);
        }

        private void writeToken(String text, boolean word) {
            writeToken(text, word, false);
        }

        /**
         * @param glued token ký hiệu vốn sát token ký hiệu trước trong câu gốc - giữ sát: lexer Oracle tách "<=" thành
         *              "<" "=", chèn khoảng trắng vào giữa là đổi nghĩa câu
         */
        private void writeToken(String text, boolean word, boolean glued) {
            String upper = text.toUpperCase();
            boolean noSpaceBefore = glued || text.equals(".") || text.equals("::") || text.equals("[") || text.equals("]")
                    || prevUpper.equals(".") || prevUpper.equals("::") || prevUpper.equals("(") || prevUpper.equals("[")
                    || (isUnarySign(prevUpper) && prevUnary);
            if (!noSpaceBefore) {
                space();
            }
            boolean keyword = word && KEYWORDS.contains(upper) && !prevUpper.equals(".");
            out.append(keyword ? applyCase(text) : text);
            boolean unary = (text.equals("-") || text.equals("+")) && isOperatorPosition();
            remember(upper, word);
            prevUnary = unary;
        }

        private boolean prevUnary;

        private static boolean isUnarySign(String s) {
            return s.equals("-") || s.equals("+");
        }

        /** "-"/"+" đứng sau toán tử, "(", ",", từ khoá hoặc đầu câu là dấu của số: "= -1", không phải "= - 1". */
        private boolean isOperatorPosition() {
            if (prevUpper.isEmpty() || prevUpper.equals("(") || prevUpper.equals(",")) {
                return true;
            }
            if (prevIsWord) {
                return KEYWORDS.contains(prevUpper);
            }
            return !prevUpper.equals(")") && !prevUpper.equals("]") && !Character.isLetterOrDigit(prevUpper.charAt(prevUpper.length() - 1))
                    && !prevUpper.endsWith("'") && !prevUpper.endsWith("\"");
        }

        private String applyCase(String text) {
            return switch (options.keywordCase()) {
                case UPPER -> text.toUpperCase();
                case LOWER -> text.toLowerCase();
                case PRESERVE -> text;
            };
        }

        private void remember(String upper, boolean word) {
            prevPrevUpper = prevUpper;
            prevUpper = upper;
            prevIsWord = word;
            if (!upper.equals("-") && !upper.equals("+")) {
                prevUnary = false;
            }
        }

        private String upperAt(int i) {
            for (int k = i; k < tokens.size(); k++) {
                String text = tokens.get(k).getText();
                if (!isComment(text)) {
                    return text.toUpperCase();
                }
            }
            return "";
        }

        private void space() {
            if (out.isEmpty()) {
                return;
            }
            char last = out.charAt(out.length() - 1);
            if (last != ' ' && last != '\n') {
                out.append(' ');
            }
        }

        private void newline(int level) {
            int end = out.length();
            while (end > 0 && out.charAt(end - 1) == ' ') {
                end--;
            }
            out.setLength(end);
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            } else if (!out.isEmpty()) {
                // đã ở đầu dòng (vừa xuống dòng): chỉ đổi thụt lề
            }
            if (!out.isEmpty()) {
                out.append(" ".repeat(Math.max(0, level) * options.indent()));
            }
            lineLevel = level;
        }
    }

    /** Toán tử/ký hiệu (không phải từ, số, chuỗi, định danh quote, ngoặc, dấu phẩy). */
    private static boolean isSymbol(String text) {
        if (text.isEmpty() || text.equals("(") || text.equals(")") || text.equals(",")) {
            return false;
        }
        char c = text.charAt(0);
        return !Character.isLetterOrDigit(c) && c != '_' && c != '\'' && c != '"' && c != '$' && !isComment(text);
    }

    private static boolean isComment(String text) {
        return text.startsWith("--") || text.startsWith("/*");
    }

    private static boolean isWord(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char c = text.charAt(0);
        return Character.isLetter(c) || c == '_';
    }
}
