package com.sqlctx.completion.semantic.postgresql;

import com.sqlctx.antlr4.postgresql.PostgreSQLLexer;
import com.sqlctx.antlr4.postgresql.PostgreSQLParser;
import com.sqlctx.completion.semantic.CaretToken;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Pair;

import java.util.*;

/**
 * Chuẩn bị token stream để parse được câu đang gõ dở: caret đứng sau 1 định danh/dấu chấm thì dùng
 * luôn token đó làm mốc, đứng ở khoảng trống thì chèn {@link CaretToken}; rồi đóng các ngoặc còn mở.
 * Thao tác trên danh sách token, không sửa chuỗi SQL (chèn chữ vào chuỗi thì lexer dính nó vào định
 * danh liền kề).
 */
public final class PostgresCursorTokenPatcher {

    private static final Set<Integer> CLAUSE_START_TOKENS = Set.of(
            PostgreSQLParser.FROM, PostgreSQLParser.WHERE, PostgreSQLParser.GROUP_P, PostgreSQLParser.ORDER,
            PostgreSQLParser.HAVING, PostgreSQLParser.LIMIT, PostgreSQLParser.OFFSET, PostgreSQLParser.UNION,
            PostgreSQLParser.INTERSECT, PostgreSQLParser.EXCEPT, PostgreSQLParser.WINDOW, PostgreSQLParser.RETURNING);

    private PostgresCursorTokenPatcher() {
    }

    public record PatchResult(CommonTokenStream tokenStream, int caretTokenIndex) {
    }

    /** @param caretTokenType loại token giả chèn tại caret, do tầng cú pháp chọn */
    public static PatchResult patch(String sql, int cursorOffset, int caretTokenType) {
        CharStream input = CharStreams.fromString(sql);
        PostgreSQLLexer lexer = new PostgreSQLLexer(input);
        lexer.removeErrorListeners(); // không thì ký tự lạ (vd '\' của meta-command) in lỗi ra stderr, vỡ terminal
        CommonTokenStream raw = new CommonTokenStream(lexer);
        raw.fill();
        List<Token> tokens = new ArrayList<>(raw.getTokens());
        // Token tự tạo phải có source thật: khi phục hồi lỗi gần nó, DefaultErrorStrategy.getMissingSymbol()
        // đọc getTokenSource().getInputStream() - source rỗng thì NPE, hỏng cả lần parse.
        Pair<TokenSource, CharStream> source = new Pair<>(lexer, input);

        int caretIdx = anchorTokenIndex(tokens, cursorOffset);
        if (caretIdx < 0) {
            caretIdx = gapIndex(tokens, cursorOffset);
            tokens.add(caretIdx, new CaretToken(source, caretTokenType, cursorOffset));
        }

        int unclosed = unclosedParens(tokens);
        // Ngoặc lời gọi hàm mà sau caret còn cả 1 mệnh đề ("count(| from t"): đóng ngay sau caret - đóng ở
        // cuối thì "from t" bị nuốt vào trong ngoặc. Ngoặc subquery/danh sách vẫn đóng ở cuối.
        int closeAfterCaret = Math.min(functionParensBeforeClause(tokens, caretIdx), unclosed);
        for (int k = 0; k < closeAfterCaret; k++) {
            tokens.add(caretIdx + 1, closeParen(source, cursorOffset));
        }
        for (int k = closeAfterCaret; k < unclosed; k++) {
            tokens.add(tokens.size() - 1, closeParen(source, cursorOffset)); // trước EOF
        }

        CommonTokenStream stream = new CommonTokenStream(new ListTokenSource(tokens));
        stream.fill();
        return new PatchResult(stream, caretIdx);
    }

    /** Token thật ngay trước caret mà caret đang "dính" vào (định danh đang gõ, hoặc dấu chấm); -1 nếu không có. */
    private static int anchorTokenIndex(List<Token> tokens, int cursorOffset) {
        for (int i = 0; i < tokens.size() && tokens.get(i).getType() != Token.EOF; i++) {
            Token t = tokens.get(i);
            if (t.getChannel() == Token.DEFAULT_CHANNEL && t.getStartIndex() < cursorOffset
                    && cursorOffset - 1 <= t.getStopIndex() && isAnchor(t)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * DOT luôn là mốc: grammar patch "DOT (attr_name|STAR)??" làm "u." tự hợp lệ; chèn token sau nó thì
     * token đó thành attr_name, checkDanglingDot() không còn thấy dấu chấm cụt.
     */
    private static boolean isAnchor(Token t) {
        if (t.getType() == PostgreSQLParser.DOT) {
            return true;
        }
        String text = t.getText();
        if (text == null || text.isEmpty()) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        return Character.isLetterOrDigit(last) || last == '_';
    }

    /** Vị trí chèn: ngay sau token cuối cùng kết thúc trước caret. */
    private static int gapIndex(List<Token> tokens, int cursorOffset) {
        int at = 0;
        for (int i = 0; i < tokens.size() && tokens.get(i).getType() != Token.EOF; i++) {
            if (tokens.get(i).getStopIndex() < cursorOffset) {
                at = i + 1;
            }
        }
        return at;
    }

    private static int unclosedParens(List<Token> tokens) {
        int open = 0;
        for (Token t : tokens) {
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            if (t.getType() == PostgreSQLParser.OPEN_PAREN) {
                open++;
            } else if (t.getType() == PostgreSQLParser.CLOSE_PAREN) {
                open--;
            }
        }
        return Math.max(open, 0);
    }

    /**
     * Số ngoặc "(" chưa đóng bao quanh caret, từ trong ra ngoài, mà đứng ngay sau tên hàm - chỉ khi token
     * thật kế tiếp sau caret mở 1 mệnh đề (FROM/WHERE/...). Dừng ở ngoặc đầu tiên không phải của hàm.
     */
    private static int functionParensBeforeClause(List<Token> tokens, int caretIdx) {
        int next = nextRealToken(tokens, caretIdx);
        if (next < 0 || !CLAUSE_START_TOKENS.contains(tokens.get(next).getType())) {
            return 0;
        }
        Deque<Integer> unclosed = new ArrayDeque<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            if (t.getType() == PostgreSQLParser.OPEN_PAREN) {
                unclosed.push(i);
            } else if (t.getType() == PostgreSQLParser.CLOSE_PAREN && !unclosed.isEmpty()) {
                unclosed.pop();
            }
        }
        int count = 0;
        for (int openIdx : unclosed) { // trong cùng trước
            if (openIdx >= caretIdx) {
                continue;
            }
            int prev = openIdx - 1;
            while (prev >= 0 && tokens.get(prev).getChannel() != Token.DEFAULT_CHANNEL) {
                prev--;
            }
            if (prev < 0 || tokens.get(prev).getType() != PostgreSQLParser.Identifier) {
                break;
            }
            count++;
        }
        return count;
    }

    private static int nextRealToken(List<Token> tokens, int fromIdx) {
        for (int i = fromIdx + 1; i < tokens.size() && tokens.get(i).getType() != Token.EOF; i++) {
            if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
                return i;
            }
        }
        return -1;
    }

    private static Token closeParen(Pair<TokenSource, CharStream> source, int offset) {
        CommonToken t = new CommonToken(source, PostgreSQLParser.CLOSE_PAREN, Token.DEFAULT_CHANNEL, offset, offset);
        t.setText(")");
        return t;
    }
}
