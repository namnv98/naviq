package com.sqlctx.completion.semantic.oracle;

import com.sqlctx.antlr4.oracle.PlSqlLexer;
import com.sqlctx.antlr4.oracle.PlSqlParser;
import com.sqlctx.completion.semantic.CaretToken;
import org.antlr.v4.runtime.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Thay thế cho OracleScopeBuilder.withCursorPlaceholder (thao tác trên String, dễ gây lexer
 * NỐI CHỮ khi cursor đứng sát 1 identifier - xem lịch sử bug). Cách này thao tác trực
 * tiếp trên DANH SÁCH TOKEN đã lex xong từ sql GỐC (không sửa 1 ký tự nào của sql):
 * <p>
 * - Nếu cursor rơi vào GIỮA span của 1 token thật thuộc DEFAULT_CHANNEL (kể cả đứng
 * NGAY SAU token đó, tức "đã gõ xong, cursor ở cuối") -> KHÔNG chèn gì cả, dùng thẳng
 * token đó làm điểm mốc.
 * <p>
 * - Nếu cursor rơi vào KHOẢNG TRỐNG giữa 2 token (không thuộc token DEFAULT_CHANNEL nào)
 * -> chèn 1 {@link CaretToken} (kiểu REGULAR_ID) vào ĐÚNG vị trí đó trong danh sách token,
 * rồi dựng lại token stream từ danh sách đã chèn qua ListTokenSource.
 * <p>
 * SỬA LẦN NÀY: bản trước dùng nhầm PostgreSQLLexer/PostgreSQLParser (chỉ đổi tên class/package,
 * CHƯA đổi nội dung thật) - lex SQL Oracle bằng lexer Postgres khiến toàn bộ token-type sai
 * lệch, PlSqlParser nhận token rác -> parse thất bại/ném exception bị nuốt im lặng ở
 * OracleSemanticAnalyzer -> mọi kết quả rỗng. Đã đổi đúng sang PlSqlLexer/PlSqlParser với token-type
 * THẬT của Oracle (REGULAR_ID/LEFT_PAREN/RIGHT_PAREN/PERIOD, không phải Identifier/OPEN_PAREN/
 * CLOSE_PAREN/DOT của Postgres).
 */
public final class OracleCursorTokenPatcher {
    private OracleCursorTokenPatcher() {
    }

    public record PatchResult(
            CommonTokenStream tokenStream,
            int caretTokenIndex,
            boolean patched
    ) {
    }

    /**
     * @param caretTokenType loại token giả chèn vào khoảng trống tại caret - do tầng cú pháp chọn theo
     *                       những gì grammar cho phép ở đó (xem OracleSyntacticAnalyzer.caretTokenTypeToInsert).
     *                       Sau dấu chấm cụt thì luôn là định danh.
     */
    public static PatchResult patch(String sql, int cursorOffset, int caretTokenType) {
        CharStream input = CharStreams.fromString(sql);
        PlSqlLexer lexer = new PlSqlLexer(input);
        // Không tắt thì ký tự lạ (vd '\' của meta-command CLI) khiến ANTLR in "token recognition error..."
        // thẳng ra System.err, đè lên màn hình terminal.
        lexer.removeErrorListeners();
        CommonTokenStream rawStream = new CommonTokenStream(lexer);
        rawStream.fill();
        List<Token> tokens = new ArrayList<>(rawStream.getTokens());

        // BUG FIX: PHẢI gắn source thật (lexer + input) cho MỌI token tự tạo. Constructor
        // rút gọn "new CommonToken(type, text)" gán source = CommonToken.EMPTY_SOURCE
        // (Pair<null,null>) -> getTokenSource() trả null -> nếu ANTLR cần error-recovery
        // (single-token insertion) NGAY TẠI hoặc GẦN token này (vd thiếu ")" thật sự phía
        // sau placeholder), DefaultErrorStrategy.getMissingSymbol() gọi thẳng
        // currentSymbol.getTokenSource().getInputStream() -> NPE, crash toàn bộ parse
        // thay vì chỉ parse lỗi cục bộ như bình thường.
        org.antlr.v4.runtime.misc.Pair<TokenSource, CharStream> tokenSource =
                new org.antlr.v4.runtime.misc.Pair<>(lexer, input);

        int gapInsertAt = 0;
        int caretIdx = -1;
        boolean reuseRealToken = false;

        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.getType() == Token.EOF) {
                break;
            }
            int start = t.getStartIndex();
            int stop = t.getStopIndex();
            if (t.getChannel() == Token.DEFAULT_CHANNEL && start <= cursorOffset - 1 && cursorOffset - 1 <= stop && isReusableCaretAnchor(t)) {
                caretIdx = i;
                reuseRealToken = true;
                break;
            }
            if (stop < cursorOffset) {
                gapInsertAt = i + 1;
            }
        }

        List<Token> working = new ArrayList<>(tokens);
        int finalCaretIdx;
        boolean patched;

        if (reuseRealToken && tokens.get(caretIdx).getType() == PlSqlParser.PERIOD) {
            // "o.|" - dấu chấm CỤT: grammar Oracle bắt buộc id_expression ngay sau PERIOD, nên nếu
            // để nguyên thì lỗi cú pháp ở đây khiến ANTLR bỏ cả subquery đang chứa nó (bug thật:
            // "... IN (select 1 from orders o where o.|)" mất alias o). Chèn CaretToken NGAY SAU dấu
            // chấm để thành "o.<định danh>" hợp lệ; caret vẫn trỏ vào dấu chấm vì
            // OracleSemanticAnalyzer.detect() dựa vào đó để nhận ra qualifier.
            Token dot = tokens.get(caretIdx);
            working.add(caretIdx + 1, new CaretToken(tokenSource, PlSqlParser.REGULAR_ID, dot.getStopIndex() + 1));
            finalCaretIdx = caretIdx;
            patched = true;
        } else if (reuseRealToken) {
            finalCaretIdx = caretIdx;
            patched = false;
        } else {
            working.add(gapInsertAt, new CaretToken(tokenSource, caretTokenType, cursorOffset));
            finalCaretIdx = gapInsertAt;
            patched = true;
        }

        int openCount = 0;
        for (Token t : working) {
            if (t.getType() == Token.EOF) break;
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (t.getType() == PlSqlParser.LEFT_PAREN) openCount++;
            else if (t.getType() == PlSqlParser.RIGHT_PAREN) openCount--;
        }
        if (openCount > 0) {
            int eofIdx = working.size() - 1;
            for (int k = 0; k < openCount; k++) {
                CommonToken closeParen = new CommonToken(
                        tokenSource, PlSqlParser.RIGHT_PAREN, Token.DEFAULT_CHANNEL,
                        cursorOffset, cursorOffset);
                closeParen.setText(")");
                working.add(eofIdx, closeParen);
            }
        }

        CommonTokenStream finalStream = new CommonTokenStream(new ListTokenSource(working));
        finalStream.fill();
        return new PatchResult(finalStream, finalCaretIdx, patched);
    }

    private static boolean isReusableCaretAnchor(Token t) {
        // ĐẶC CÁCH cho PERIOD: dù grammar Oracle KHÔNG patch cho "u." tự hợp lệ cú pháp (khác
        // Postgres), việc tái dùng THẲNG token PERIOD làm caret ở đây vẫn đúng và cần thiết -
        // đây chính là điều kiện mà DanglingDotDetector dựa vào để nhận diện "cursor đứng ngay
        // sau 1 dấu chấm" (kiểm tra token tại caretTokenIndex có type == PERIOD hay không). Nếu
        // coi PERIOD là "không growable" rồi chèn placeholder NGAY SAU nó, DanglingDotDetector
        // sẽ không còn thấy PERIOD ở đúng caretTokenIndex nữa -> vô hiệu hoá phát hiện dấu chấm
        // cụt hoàn toàn.
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
}