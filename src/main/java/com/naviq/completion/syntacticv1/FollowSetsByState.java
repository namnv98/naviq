package com.naviq.completion.syntacticv1;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Cache {@link FollowSetsHolder} theo (stateNumber, ignoredTokens), thread-safe.
 * <p>
 * QUAN TRỌNG: key tầng trong dùng {@link IdentityHashMap} (so sánh theo identity của
 * instance {@code ignoredTokens}, KHÔNG theo nội dung). Đây là lựa chọn bắt buộc, không
 * phải sơ suất: {@link FollowSetComputer} đánh giá {@code PredicateTransition} thông qua
 * {@code parser} — một instance dùng chung, có state mutable (ví dụ precedence stack cho
 * rule left-recursive). Nếu instance {@code ignoredTokens} là MỚI cho mỗi lần completion
 * (thường vậy, dù nội dung hay giống nhau), {@link IdentityHashMap} đảm bảo mỗi lần luôn
 * tính lại {@link FollowSetsHolder} tươi mới đúng theo parser-state hiện tại, thay vì tái
 * sử dụng nhầm kết quả đã tính từ một lần completion trước đó có nội dung ignoredTokens
 * trùng hợp giống nhau nhưng parser-state khác. Đổi sang so sánh theo nội dung
 * ({@code HashMap} thường) đã từng gây sai kết quả thực tế — xem lịch sử commit liên quan.
 */
public class FollowSetsByState {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<Integer, IdentityHashMap<Map<Integer, Boolean>, FollowSetsHolder>> cache = new java.util.HashMap<>();

    public FollowSetsHolder get(int stateNumber, Map<Integer, Boolean> ignoredTokens) {
        lock.readLock().lock();
        try {
            var inner = cache.get(stateNumber);
            return inner == null ? null : inner.get(ignoredTokens);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void ensureComputed(Parser parser, ATNState start, Map<Integer, Boolean> ignoredTokens) {
        // Fast path
        lock.readLock().lock();
        try {
            var inner = cache.get(start.stateNumber);
            if (inner != null && inner.containsKey(ignoredTokens)) return;
        } finally {
            lock.readLock().unlock();
        }

        // Slow path
        lock.writeLock().lock();
        try {
            var inner = cache.computeIfAbsent(start.stateNumber, k -> new IdentityHashMap<>());
            if (inner.containsKey(ignoredTokens)) return; // lost race

            ATNState stop = parser.getATN().ruleToStopState[start.ruleIndex];
            List<FollowSetWithPath> sets = FollowSetComputer.computeFollowSets(parser, start, stop, ignoredTokens);
            IntervalSet combined = new IntervalSet();
            sets.forEach(s -> combined.addAll(s.intervals()));
            inner.put(ignoredTokens, new FollowSetsHolder(sets, combined));
        } finally {
            lock.writeLock().unlock();
        }
    }
}
