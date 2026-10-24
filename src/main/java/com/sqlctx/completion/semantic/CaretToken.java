package com.sqlctx.completion.semantic;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.misc.Pair;

/**
 * Token giả mà *CursorTokenPatcher chèn tại con trỏ để parse đi qua được chỗ đang gõ dở. Parser chỉ
 * cần LOẠI token (do tầng cú pháp chọn), không cần text.
 * <p>
 * Span zero-width ({@code stop = start - 1}, giống token ANTLR tự "bịa" khi phục hồi lỗi): không chiếm
 * offset của token thật nào. Text rỗng: mọi tên mà ScopeBuilder suy ra từ nó (alias, bảng, cột...) đều
 * rỗng, bị {@link com.sqlctx.completion.model.Scope#dropUnnamedEntries()} bỏ đi sau khi walk.
 */
public final class CaretToken extends CommonToken {

    public CaretToken(Pair<TokenSource, CharStream> source, int type, int offset) {
        super(source, type, Token.DEFAULT_CHANNEL, offset, offset - 1);
        // BẮT BUỘC dù span đã zero-width: không có text thì CommonToken.getText() cắt từ chuỗi gốc, nhưng
        // trả "<EOF>" khi start >= độ dài chuỗi - tức đúng lúc con trỏ ở cuối câu (ca hay gặp nhất)
        setText("");
    }
}
