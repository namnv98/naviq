package com.sqlctx.completion.semantic.oracle;

import com.sqlctx.antlr4.oracle.PlSqlLexer;
import com.sqlctx.antlr4.oracle.PlSqlParser;
import com.sqlctx.completion.semantic.CaretToken;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * Chuẩn bị token stream để parse được câu đang gõ dở: caret đứng sau 1 định danh/dấu chấm thì dùng
 * luôn token đó làm mốc, đứng ở khoảng trống thì chèn {@link CaretToken}; rồi đóng các ngoặc còn mở.
 * Thao tác trên danh sách token, không sửa chuỗi SQL (chèn chữ vào chuỗi thì lexer dính nó vào định
 * danh liền kề).
 */
public final class OracleCursorTokenPatcher {

    private OracleCursorTokenPatcher() {
    }

    public record PatchResult(CommonTokenStream tokenStream, int caretTokenIndex) {
    }

    /** @param caretTokenType loại token giả chèn tại caret, do tầng cú pháp chọn */
    public static PatchResult patch(String sql, int cursorOffset, int caretTokenType) {
        CharStream input = CharStreams.fromString(sql);
        PlSqlLexer lexer = new PlSqlLexer(input);
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
        } else if (tokens.get(caretIdx).getType() == PlSqlParser.PERIOD) {
            // "o.|": grammar bắt buộc định danh sau dấu chấm, lỗi ở đây làm ANTLR bỏ cả subquery chứa nó
            // (mất alias o). Chèn định danh giả sau dấu chấm; caret vẫn trỏ dấu chấm vì
            // OracleSemanticAnalyzer.detect() dựa vào đó để nhận ra qualifier.
            int afterDot = tokens.get(caretIdx).getStopIndex() + 1;
            tokens.add(caretIdx + 1, new CaretToken(source, PlSqlParser.REGULAR_ID, afterDot));
        }

        for (int k = unclosedParens(tokens); k > 0; k--) {
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

    private static boolean isAnchor(Token t) {
        if (t.getType() == PlSqlParser.PERIOD) {
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
            if (t.getType() == PlSqlParser.LEFT_PAREN) {
                open++;
            } else if (t.getType() == PlSqlParser.RIGHT_PAREN) {
                open--;
            }
        }
        return open;
    }

    private static Token closeParen(Pair<TokenSource, CharStream> source, int offset) {
        CommonToken t = new CommonToken(source, PlSqlParser.RIGHT_PAREN, Token.DEFAULT_CHANNEL, offset, offset);
        t.setText(")");
        return t;
    }
}
