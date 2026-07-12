package com.naviq.oracle.semantic;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.List;
import java.util.Set;

/**
 * Phát hiện "cursor đứng ngay sau 1 dấu chấm cụt" (vd "u.") THUẦN TÚY qua danh sách TOKEN, KHÔNG
 * phụ thuộc cây parse hay bất kỳ patch grammar nào - khác với cách {@link SemanticScope}
 * (Postgres) làm qua {@code checkDanglingDot()}, vốn dựa vào 1 patch riêng cho grammar Postgres
 * (indirection_el: DOT (attr_name|STAR)??) cho phép "u." tự nó hợp lệ cú pháp mà không cần
 * error-recovery.
 * <p>
 * Grammar PL/SQL gốc (PlSqlParser.g4/PlSqlLexer.g4 - chưa patch) KHÔNG có cơ chế tương tự: "u."
 * với cursor ngay sau dấu chấm LUÔN LÀ lỗi cú pháp thật (general_element_part đòi hỏi id_expression
 * ngay sau PERIOD, không optional) - ANTLR phải error-recovery, chèn token ảo. Nhưng điều đó
 * KHÔNG SAO ở đây, vì việc phát hiện "đang gõ dở sau dấu chấm" được làm TRƯỚC/ĐỘC LẬP với việc
 * parse, dựa thẳng vào danh sách token đã lex xong + caretTokenIndex đã biết trước từ
 * {@link TokenStreamCursorPatcher} - không cần chờ (và không phụ thuộc) kết quả parse có lỗi hay
 * không.
 * <p>
 * Dùng chung được cho BẤT KỲ dialect nào (Oracle PL/SQL, hay dialect khác không có patch grammar
 * tương tự Postgres) miễn cung cấp đúng token-type của dấu chấm và tập token-type nào được coi là
 * "1 segment định danh".
 */
public final class DanglingDotDetector {

    private DanglingDotDetector() {
    }

    /**
     * @param tokens          token stream đã patch xong (từ {@link TokenStreamCursorPatcher#patch})
     * @param caretTokenIndex vị trí token cursor - lấy từ TokenStreamCursorPatcher.PatchResult
     * @param dotTokenType    token-type của dấu chấm "." trong dialect này
     * @param identifierTypes tập token-type được coi là "1 segment định danh" hợp lệ đứng NGAY
     *                        TRƯỚC dấu chấm (vd REGULAR_ID/DELIMITED_ID cho Oracle)
     * @return qualifier đứng NGAY TRƯỚC dấu chấm cụt (vd "u." -> "u"), hoặc null nếu cursor không
     * đứng ngay tại 1 dấu chấm, hoặc token thật gần nhất trước dấu chấm không phải identifier.
     */
    public static String detect(
            CommonTokenStream tokens,
            int caretTokenIndex,
            int dotTokenType,
            Set<Integer> identifierTypes
    ) {
        List<Token> all = tokens.getTokens();
        if (caretTokenIndex < 0 || caretTokenIndex >= all.size()) {
            return null;
        }
        Token caret = all.get(caretTokenIndex);
        if (caret.getType() != dotTokenType) {
            return null; // cursor không nằm ngay tại 1 dấu chấm -> không phải trường hợp này
        }
        // tìm token THẬT gần nhất TRƯỚC dấu chấm (bỏ qua hidden channel như whitespace/comment)
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            Token t = all.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            return identifierTypes.contains(t.getType()) ? t.getText() : null;
        }
        return null;
    }
}