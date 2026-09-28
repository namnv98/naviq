package com.sqlctx.completion.support;

import com.sqlctx.completion.suggestion.CompletionHistory;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * JUnit extension cho test completion:
 * <ul>
 *   <li>trước mỗi test: reset {@link CompletionHistory} - không có bước này, xếp hạng phụ thuộc
 *       lịch sử dùng THẬT của máy đang chạy test (~/.sqlctx/completion_history.properties);</li>
 *   <li>sau mỗi test: kiểm tra CHÍNH XÁC mọi {@link CompletionExpectation} tạo ra trong test (xem
 *       javadoc class đó) - test chỉ cần khai báo kỳ vọng, không cần (và không thể quên) gọi verify.</li>
 * </ul>
 */
public final class CompletionExpectations implements BeforeEachCallback, AfterEachCallback {

    private static final ThreadLocal<List<CompletionExpectation>> PENDING = ThreadLocal.withInitial(ArrayList::new);

    static void register(CompletionExpectation expectation) {
        PENDING.get().add(expectation);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        PENDING.get().clear();
        CompletionHistory.resetForTests();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        List<CompletionExpectation> pending = new ArrayList<>(PENDING.get());
        PENDING.get().clear();
        if (context.getExecutionException().isPresent()) {
            return; // test đã fail vì lý do khác - không chồng thêm lỗi
        }
        AssertionError first = null;
        for (CompletionExpectation e : pending) {
            try {
                e.verify();
            } catch (AssertionError err) {
                if (first == null) {
                    first = err;
                } else {
                    first.addSuppressed(err);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
