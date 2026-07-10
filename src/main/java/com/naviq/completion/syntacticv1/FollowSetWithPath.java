package com.naviq.completion.syntacticv1;

import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.List;

/**
 * Một nhánh follow-set tính được từ {@link FollowSetComputer}: tập token có thể
 * xuất hiện tiếp theo ({@code intervals}), đường đi các rule đã đi qua để tới
 * được nhánh này ({@code path}), và danh sách token chắc chắn theo sau nếu
 * {@code intervals} chỉ có đúng 1 token ({@code following} — dùng để gợi ý
 * "gõ tiếp" nhiều token liên tiếp, ví dụ từ khóa ghép).
 */
public record FollowSetWithPath(IntervalSet intervals, RuleCallStack path, List<Integer> following) {
}
