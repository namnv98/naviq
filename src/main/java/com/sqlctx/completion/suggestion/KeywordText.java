package com.sqlctx.completion.suggestion;

import org.antlr.v4.runtime.Vocabulary;

import java.util.List;

/**
 * Chữ hiển thị của 1 gợi ý keyword từ token ANTLR - CHỈ cho token là 1 TỪ khoá thật.
 * <p>
 * Lexer định nghĩa keyword bằng chuỗi cố định ({@code SELECT : 'SELECT'}) nên có literal name. Bug thật
 * (phát hiện khi so với IntelliJ): trước đây dùng display name của MỌI token làm keyword, lọt ra menu:
 * <ul>
 *   <li>token định nghĩa bằng pattern, không có literal - display name là tên NỘI BỘ của grammar
 *       ("plsqlvariablename", "param");</li>
 *   <li>token ký hiệu/toán tử ("*", "::", "%", "&lt;&lt;", ":=", "||"...) - không phải keyword.</li>
 * </ul>
 */
public final class KeywordText {

    private KeywordText() {
    }

    /**
     * Keyword cho {@code token} kèm các token chắc chắn theo sau (vd NOT + [EXISTS] -> "not exists"), hoặc
     * null nếu {@code token} không phải từ khoá. Chuỗi theo sau dừng ở token đầu tiên không phải từ khoá.
     */
    public static String of(Vocabulary vocabulary, int token, List<Integer> following) {
        String first = word(vocabulary, token);
        if (first == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(first);
        if (following != null) {
            for (int f : following) {
                String next = word(vocabulary, f);
                if (next == null) {
                    break;
                }
                text.append(' ').append(next);
            }
        }
        return text.toString();
    }

    private static String word(Vocabulary vocabulary, int token) {
        String literal = vocabulary.getLiteralName(token);
        if (literal == null || literal.length() < 3) {
            return null;
        }
        String text = literal.substring(1, literal.length() - 1); // bỏ cặp nháy đơn của literal ANTLR
        return text.matches("[A-Za-z][A-Za-z0-9_]*") ? text.toLowerCase() : null;
    }
}
