package com.sqlctx.completion.semantic.postgresql;

import com.sqlctx.antlr4.postgresql.PostgreSQLLexer;
import com.sqlctx.antlr4.postgresql.PostgreSQLParser;
import com.sqlctx.completion.semantic.CaretToken;
import org.antlr.v4.runtime.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Thay thế cho PostgresScopeBuilder.withCursorPlaceholder (thao tác trên String, dễ gây lexer
 * NỐI CHỮ khi cursor đứng sát 1 identifier - xem lịch sử bug). Cách này thao tác trực
 * tiếp trên DANH SÁCH TOKEN đã lex xong từ sql GỐC (không sửa 1 ký tự nào của sql):
 * <p>
 * - Nếu cursor rơi vào GIỮA span của 1 token thật thuộc DEFAULT_CHANNEL (kể cả đứng
 * NGAY SAU token đó, tức "đã gõ xong, cursor ở cuối") -> KHÔNG chèn gì cả, dùng thẳng
 * token đó làm điểm mốc (mirror "!patched" của bản cũ, nhưng tổng quát hơn: cover luôn
 * cả case "ngay sau dấu chấm" LẪN "ngay sau ký tự identifier" bằng 1 điều kiện DUY NHẤT
 * dựa trên khoảng số [start, stop], không cần liệt kê ký tự nào là chữ/số/_/dấu chấm).
 * <p>
 * - Nếu cursor rơi vào KHOẢNG TRỐNG giữa 2 token (không thuộc token DEFAULT_CHANNEL nào)
 * -> chèn 1 {@link CaretToken} (kiểu Identifier) vào ĐÚNG vị trí đó trong danh sách token,
 * rồi dựng lại token stream từ danh sách đã chèn qua ListTokenSource. Vì đây là chèn
 * TOKEN (không phải nối chuỗi ký tự), token giả KHÔNG BAO GIỜ bị lexer gộp dính vào
 * token thật liền kề, bất kể chèn bên trái hay bên phải 1 identifier - loại bỏ tận gốc
 * lớp bug "lexer nối chữ" mà cách chèn vào String mắc phải.
 */
public final class PostgresCursorTokenPatcher {
    private PostgresCursorTokenPatcher() {
    }

    public record PatchResult(
            CommonTokenStream tokenStream,
            int caretTokenIndex,
            boolean patched
    ) {
    }

    /**
     * @param caretTokenType loại token giả chèn vào khoảng trống tại caret - do tầng cú pháp chọn theo
     *                       những gì grammar cho phép ở đó (xem PostgresSyntacticAnalyzer.caretTokenTypeToInsert)
     */
    public static PatchResult patch(String sql, int cursorOffset, int caretTokenType) {
        CharStream input = CharStreams.fromString(sql);
        PostgreSQLLexer lexer = new PostgreSQLLexer(input);
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

        if (reuseRealToken) {
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
            if (t.getType() == PostgreSQLParser.OPEN_PAREN) openCount++;
            else if (t.getType() == PostgreSQLParser.CLOSE_PAREN) openCount--;
        }
        // Ngoặc của LỜI GỌI HÀM chưa đóng mà phía sau caret còn cả 1 mệnh đề (vd "count(| from t"):
        // ")" phải nằm NGAY SAU vùng đang gõ, không phải cuối input - đóng ở cuối làm
        // "from t" bị nuốt vào trong ngoặc, parse lỗi và mất hẳn bảng FROM. Ngoặc subquery/danh
        // sách (đứng sau SELECT/FROM/IN/...) vẫn đóng ở cuối như cũ.
        int closeAfterCaret = Math.min(countFunctionParensToCloseAfterCaret(working, finalCaretIdx), openCount);
        if (closeAfterCaret > 0) {
            for (int k = 0; k < closeAfterCaret; k++) {
                CommonToken closeParen = new CommonToken(
                        tokenSource, PostgreSQLParser.CLOSE_PAREN, Token.DEFAULT_CHANNEL,
                        cursorOffset, cursorOffset);
                closeParen.setText(")");
                working.add(finalCaretIdx + 1, closeParen);
            }
            openCount -= closeAfterCaret;
        }
        if (openCount > 0) {
            int eofIdx = working.size() - 1;
            for (int k = 0; k < openCount; k++) {
                CommonToken closeParen = new CommonToken(
                        tokenSource, PostgreSQLParser.CLOSE_PAREN, Token.DEFAULT_CHANNEL,
                        cursorOffset, cursorOffset);
                closeParen.setText(")");
                working.add(eofIdx, closeParen);
            }
        }

        CommonTokenStream finalStream = new CommonTokenStream(new ListTokenSource(working));
        finalStream.fill();
        return new PatchResult(finalStream, finalCaretIdx, patched);
    }

    private static final java.util.Set<Integer> CLAUSE_START_TOKENS = java.util.Set.of(
            PostgreSQLParser.FROM, PostgreSQLParser.WHERE, PostgreSQLParser.GROUP_P, PostgreSQLParser.ORDER,
            PostgreSQLParser.HAVING, PostgreSQLParser.LIMIT, PostgreSQLParser.OFFSET, PostgreSQLParser.UNION,
            PostgreSQLParser.INTERSECT, PostgreSQLParser.EXCEPT, PostgreSQLParser.WINDOW, PostgreSQLParser.RETURNING);

    /**
     * Số ngoặc "(" chưa đóng bao quanh caret, tính từ ngoặc TRONG CÙNG ra ngoài, mà mỗi ngoặc đứng
     * ngay sau 1 tên hàm (Identifier) - và token thật kế tiếp sau vùng caret là 1 keyword mở mệnh
     * đề (FROM/WHERE/...). Dừng ở ngoặc đầu tiên không thỏa (ngoặc subquery/danh sách).
     */
    private static int countFunctionParensToCloseAfterCaret(List<Token> working, int caretIdx) {
        int next = -1;
        for (int i = caretIdx + 1; i < working.size(); i++) {
            Token t = working.get(i);
            if (t.getType() == Token.EOF) break;
            if (t.getChannel() == Token.DEFAULT_CHANNEL) {
                next = i;
                break;
            }
        }
        if (next < 0 || !CLAUSE_START_TOKENS.contains(working.get(next).getType())) {
            return 0;
        }
        java.util.Deque<Integer> unclosed = new java.util.ArrayDeque<>();
        for (int i = 0; i < working.size(); i++) {
            Token t = working.get(i);
            if (t.getType() == Token.EOF) break;
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (t.getType() == PostgreSQLParser.OPEN_PAREN) unclosed.push(i);
            else if (t.getType() == PostgreSQLParser.CLOSE_PAREN && !unclosed.isEmpty()) unclosed.pop();
        }
        int count = 0;
        for (int openIdx : unclosed) { // innermost trước
            if (openIdx >= caretIdx) continue;
            int prev = openIdx - 1;
            while (prev >= 0 && working.get(prev).getChannel() != Token.DEFAULT_CHANNEL) prev--;
            if (prev < 0 || working.get(prev).getType() != PostgreSQLParser.Identifier) break;
            count++;
        }
        return count;
    }

    private static boolean isReusableCaretAnchor(Token t) {
        // ĐẶC CÁCH BẮT BUỘC cho DOT: nhờ patch grammar "indirection_el: DOT
        // (attr_name|STAR)??", "u." tự nó ĐÃ hợp lệ về cú pháp mà KHÔNG cần token nào theo
        // sau. Nếu coi DOT là "không growable" rồi chèn placeholder NGAY SAU nó (như mọi
        // gap khác), placeholder sẽ bị hiểu thành chính "attr_name" của dấu chấm đó ->
        // checkDanglingDot() thấy attr_name KHÔNG null nữa -> tưởng đây là "a.b" hoàn
        // chỉnh, không phải "a." cụt -> KHÔNG ghi vào danglingDotQualifier -> vô hiệu hoá
        // toàn bộ cơ chế phát hiện "gõ dở sau dấu chấm" (lý do duy nhất grammar được patch
        // DOT?? ngay từ đầu). Phải tái dùng THẲNG token DOT làm caret, không chèn gì cả.
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
}