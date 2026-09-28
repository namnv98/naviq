package com.sqlctx.completion.support;

import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.suggestion.CompletionInputPreparer;
import com.sqlctx.completion.suggestion.oracle.OracleSuggestionService;
import com.sqlctx.completion.suggestion.postgresql.PostgresSuggestionService;

import java.util.List;
import java.util.function.Function;

/**
 * Điểm vào DUY NHẤT của test completion: tự cài fixture schema của dialect, chạy ĐÚNG đường
 * production ({@code suggests(PrepareCompletionInput)}, có SuggestFilter xếp hạng/lọc) và trả về 1
 * {@link CompletionExpectation} - được {@link CompletionExpectations} kiểm tra chính xác sau test.
 * <p>
 * "|" trong câu SQL đánh dấu vị trí con trỏ.
 */
public final class Completion {

    private Completion() {
    }

    public static CompletionExpectation pg(String sqlWithCursor) {
        CompletionFixtures.installPostgres();
        return run("postgres", sqlWithCursor, input -> PostgresSuggestionService.suggests(input));
    }

    public static CompletionExpectation ora(String sqlWithCursor) {
        CompletionFixtures.installOracle();
        return run("oracle", sqlWithCursor, input -> OracleSuggestionService.suggests(input));
    }

    private static CompletionExpectation run(String dialect, String sqlWithCursor,
                                             Function<CompletionInputPreparer.PrepareCompletionInput, List<Suggestion>> service) {
        int cursor = sqlWithCursor.indexOf('|');
        if (cursor < 0) {
            throw new IllegalArgumentException("Thiếu '|' đánh dấu con trỏ: " + sqlWithCursor);
        }
        String sql = sqlWithCursor.substring(0, cursor) + sqlWithCursor.substring(cursor + 1);
        var expectation = new CompletionExpectation(dialect, sqlWithCursor, service.apply(CompletionInputPreparer.buildInput(sql, cursor)));
        CompletionExpectations.register(expectation);
        return expectation;
    }
}
