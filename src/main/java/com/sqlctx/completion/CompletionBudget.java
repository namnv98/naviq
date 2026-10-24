package com.sqlctx.completion;

/**
 * Thời gian tối đa cho MỖI tầng phân tích của 1 lần gợi ý. Gợi ý chạy đồng bộ ở mỗi phím gõ (MenuCompleter), nên
 * câu rất dài không được làm đơ ô nhập: hết giờ thì tầng đó bỏ cuộc (cú pháp: không có keyword; ngữ nghĩa: không có
 * scope) thay vì chặn tiếp. Đổi bằng {@code -Dsqlctx.completion.budgetMs=...}.
 */
public final class CompletionBudget {

    /** Đổi được lúc chạy (test đặt rất nhỏ để kiểm tra đường hết giờ). */
    public static volatile long millis = Long.getLong("sqlctx.completion.budgetMs", 300);

    private CompletionBudget() {
    }

    /** Mốc System.nanoTime() mà tầng bắt đầu từ bây giờ phải xong trước. */
    public static long deadlineFromNow() {
        return System.nanoTime() + millis * 1_000_000;
    }

    /** Ném khi quá {@link #deadlineFromNow()} - không mang stack trace (ném ở đường nóng, chỉ để thoát nhanh). */
    public static final class Exceeded extends RuntimeException {
        public Exceeded() {
            super("completion time budget exceeded", null, false, false);
        }
    }
}
