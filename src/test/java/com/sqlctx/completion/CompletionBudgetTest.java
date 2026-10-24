package com.sqlctx.completion;

import com.sqlctx.completion.input.CompletionInputPreparer;
import com.sqlctx.completion.model.SuggestionType;
import com.sqlctx.completion.suggestion.oracle.OracleSuggestionService;
import com.sqlctx.completion.suggestion.postgresql.PostgresSuggestionService;
import com.sqlctx.completion.support.CompletionFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionBudgetTest {

    private final long savedBudget = CompletionBudget.millis;

    @AfterEach
    void restoreBudget() {
        CompletionBudget.millis = savedBudget;
    }

    /** Câu dài ~150 dòng: trước khi có budget, Oracle mất ~800ms mỗi phím gõ. */
    private static String longQuery() {
        StringBuilder sb = new StringBuilder("select u.id from users u\n");
        for (int i = 0; i < 150; i++) {
            sb.append("join orders o").append(i).append(" on o").append(i).append(".user_id = u.id and o").append(i)
                    .append(".status in (select status from contracts c where c.amount > ").append(i).append(")\n");
        }
        return sb.append("where u.id > 1 and ").toString();
    }

    @Test
    void exhaustedBudgetReturnsQuicklyWithoutKeywords() {
        CompletionBudget.millis = 0;
        String sql = longQuery();
        var input = CompletionInputPreparer.buildInput(sql, sql.length());

        CompletionFixtures.installOracle();
        long start = System.nanoTime();
        var oracle = OracleSuggestionService.suggests(input);
        CompletionFixtures.installPostgres();
        var postgres = PostgresSuggestionService.suggests(input);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // không bịa keyword từ kết quả cú pháp dở dang
        assertTrue(oracle.stream().noneMatch(s -> s.getType() == SuggestionType.KEYWORD), oracle.toString());
        assertTrue(postgres.stream().noneMatch(s -> s.getType() == SuggestionType.KEYWORD), postgres.toString());
        // budget 0 thì mỗi tầng dừng ở lần kiểm tra đầu tiên - dư rộng cho máy CI chậm
        assertTrue(elapsedMs < 400, "hết budget mà vẫn chạy " + elapsedMs + "ms");
    }
}
