package com.sqlctx.completion.syntactic;

import com.sqlctx.completion.model.CandidatesResult;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Kết quả engine cú pháp cho vài lần gọi gần nhất. Mỗi phím gõ có 2 lần hỏi cùng 1 vị trí - menu gợi ý (cả câu, caret
 * lùi vào từ đang gõ) và dòng báo lỗi (câu đã bỏ từ đang gõ) - input khác nhau nhưng engine chỉ đọc token TRƯỚC token
 * caret, nên khoá theo đúng đoạn văn bản đó cho cả 2 lần trúng cache.
 * <p>
 * Điều kiện: có ít nhất 2 token thật trước caret - vài predicate của grammar Oracle đọc input.LT(1)/LT(2) tính từ đầu
 * câu (engine không tiêu token nên luôn là 2 token đầu); ít hơn thì 2 token đó có thể nằm sau caret. Câu ngắn như
 * vậy thì tính lại cũng rẻ.
 */
public final class CandidatesCache {

    private CandidatesCache() {
    }

    private record Entry(String key, CandidatesResult candidates) {
    }

    private static final int SIZE = 4;
    private static final Deque<Entry> entries = new ArrayDeque<>();

    /** Khoá cho lần gọi này; null nếu không đủ điều kiện dùng cache. */
    public static String key(String dialect, String sql, CommonTokenStream tokens, int caretTokenIndex) {
        int real = 0;
        for (int i = 0; i < caretTokenIndex && real < 2; i++) {
            if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
                real++;
            }
        }
        if (real < 2) {
            return null;
        }
        int caretStart = tokens.get(caretTokenIndex).getStartIndex();
        int end = caretStart < 0 || caretStart > sql.length() ? sql.length() : caretStart;
        return dialect + '\u0000' + sql.substring(0, end);
    }

    public static synchronized CandidatesResult get(String key) {
        if (key == null) {
            return null;
        }
        for (Entry e : entries) {
            if (e.key().equals(key)) {
                return e.candidates();
            }
        }
        return null;
    }

    /** Kết quả hết giờ không cache: lần sau có thể đủ thời gian. */
    public static synchronized void put(String key, CandidatesResult candidates) {
        if (key == null || candidates.timedOut) {
            return;
        }
        entries.removeIf(e -> e.key().equals(key));
        entries.addFirst(new Entry(key, candidates));
        while (entries.size() > SIZE) {
            entries.removeLast();
        }
    }
}
