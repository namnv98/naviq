package com.sqlctx.sql.completion.syntactic.engine.support;

import com.sqlctx.sql.completion.model.InputToken;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bảng "vào rule R ở token i thì ra được ở đâu" ({@code ruleExitCache} của engine) giữ lại giữa các lần gợi ý liên
 * tiếp - gõ ở cuối câu dài thì phần trước caret không đổi, engine khỏi đi lại từng rule con ở đó.
 * <p>
 * Vì sao đúng: engine chỉ cất kết quả của lần đi KHÔNG chạm caret, tức chỉ đọc các token trước caret của lần đó. Lần
 * sau có caret không lùi và đúng các token đó (so theo loại - engine chỉ khớp theo loại) thì lần đi y hệt cho kết quả y
 * hệt. Cần ít nhất 2 token trước caret: vài predicate của grammar Oracle đọc input.LT(1)/LT(2) tính từ đầu câu.
 * <p>
 * Không thread-safe - caller giữ khoá (1 bảng/dialect, dùng chung giữa REPL và Language Server).
 */
public final class IncrementalExitCache {

    /** Loại các token trước caret của lần chạy thành công gần nhất; null = không có gì để dùng lại. */
    private int[] prefixTypes;
    private final Map<Integer, Map<Integer, Set<Integer>>> exits = new HashMap<>();

    /** Bảng dùng cho lần chạy với {@code tokens} (caret là phần tử cuối): giữ nguyên nếu dùng lại được, không thì xoá. */
    public Map<Integer, Map<Integer, Set<Integer>>> begin(List<InputToken> tokens) {
        if (!reusable(tokens)) {
            exits.clear();
        }
        return exits;
    }

    /** Lần chạy xong trọn vẹn: nhớ phần đầu đã dùng. */
    public void commit(List<InputToken> tokens) {
        int caret = tokens.size() - 1;
        prefixTypes = new int[caret];
        for (int i = 0; i < caret; i++) {
            prefixTypes[i] = tokens.get(i).type();
        }
    }

    /** Lần chạy bỏ dở (hết giờ): bảng có thể còn đánh dấu tạm "ngõ cụt" - bỏ hết. */
    public void invalidate() {
        prefixTypes = null;
        exits.clear();
    }

    private boolean reusable(List<InputToken> tokens) {
        int caret = tokens.size() - 1;
        if (prefixTypes == null || prefixTypes.length < 2 || caret < prefixTypes.length) {
            return false;
        }
        for (int i = 0; i < prefixTypes.length; i++) {
            if (tokens.get(i).type() != prefixTypes[i]) {
                return false;
            }
        }
        return true;
    }
}
