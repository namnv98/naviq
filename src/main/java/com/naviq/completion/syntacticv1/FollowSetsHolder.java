package com.naviq.completion.syntacticv1;

import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.List;

/**
 * Toàn bộ follow-set đã tính cho một ATN state: danh sách chi tiết từng nhánh
 * ({@code sets}, dùng khi cần biết ĐƯỜNG ĐI/RULE nào dẫn tới token đó — ví dụ để
 * dịch ngược sang preferred-rule), và tập hợp tất cả token khả dĩ đã gộp lại
 * ({@code combined}, dùng để kiểm tra nhanh "token này có nằm trong follow-set
 * không" mà không cần lặp qua từng nhánh).
 */
public record FollowSetsHolder(List<FollowSetWithPath> sets, IntervalSet combined) {
}
