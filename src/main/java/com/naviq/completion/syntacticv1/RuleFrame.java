package com.naviq.completion.syntacticv1;

/**
 * Một frame trong {@link RuleCallStack}: rule nào đang được gọi, và tại vị trí
 * token nào khi bước vào rule đó.
 * <p>
 * {@link #NO_TOKEN} là giá trị đặc biệt dùng khi frame này không đến từ một lần
 * gọi thật sự trong luồng token (ví dụ: được tổng hợp tĩnh từ follow-set closure,
 * không tiêu thụ token nào) — xem thêm ý nghĩa của nó trong
 * {@code AntlrCompletionEngine1.isMoreRelevant}.
 */
public record RuleFrame(int ruleId, int tokenIndex) {
    public static final int NO_TOKEN = -1;
}
