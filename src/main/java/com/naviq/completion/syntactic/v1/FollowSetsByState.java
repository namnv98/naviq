package com.naviq.completion.syntactic.v1;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * FEATURE: tính trước "follow-set" — với 1 phòng cho trước, đây là toàn bộ
 * token thật có thể xuất hiện từ phòng đó trở đi (lặn xuyên qua mọi mê cung
 * con), KÈM theo "đường đi" (path) đã lặn qua để tới được từng token đó.
 * <p>
 * KHÔNG thuộc lõi thuật toán — engine core vẫn chạy đúng nếu bỏ hẳn class này,
 * chỉ là sẽ phải dò cửa sống (walkRuleBody) mỗi lần thay vì tra cache, và mất
 * khả năng gộp gợi ý về mê cung đặc biệt ngoài cùng (vì mất "đường đi").
 * <p>
 * Hoàn toàn ĐỘC LẬP với engine: chỉ cần (Parser, ATNState, ignoredTokens) —
 * không đụng tới tokens đã gõ, không đụng tới ruleExitCache của engine. Đây là
 * lý do nó cache được DÙNG CHUNG giữa nhiều lần gọi collectCandidates, kể cả
 * từ nhiều luồng khác nhau (ReentrantReadWriteLock).
 */
public class FollowSetsByState {

    public record FollowSetWithPath(IntervalSet intervals, RuleCallStack path, List<Integer> following) {
    }

    public record FollowSetsHolder(List<FollowSetWithPath> sets, IntervalSet combined) {
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<Integer, IdentityHashMap<Map<Integer, Boolean>, FollowSetsHolder>> cache = new HashMap<>();

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
        lock.readLock().lock();
        try {
            var inner = cache.get(start.stateNumber);
            if (inner != null && inner.containsKey(ignoredTokens)) return;
        } finally {
            lock.readLock().unlock();
        }

        lock.writeLock().lock();
        try {
            var inner = cache.computeIfAbsent(start.stateNumber, k -> new IdentityHashMap<>());
            if (inner.containsKey(ignoredTokens)) return; // luồng khác đã tính xong trước rồi

            ATNState stop = parser.getATN().ruleToStopState[start.ruleIndex];
            List<FollowSetWithPath> sets = computeFollowSets(parser, start, stop, ignoredTokens);
            IntervalSet combined = new IntervalSet();
            sets.forEach(s -> combined.addAll(s.intervals()));
            inner.put(ignoredTokens, new FollowSetsHolder(sets, combined));
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Tính follow-set (đệ quy, lặn xuyên qua mọi mê cung con) ─────────────

    static List<FollowSetWithPath> computeFollowSets(Parser parser, ATNState start, ATNState stop, Map<Integer, Boolean> ignoredTokens) {
        List<FollowSetWithPath> out = new ArrayList<>();
        collectFollowSets(parser, start, stop, out, new IdentityHashMap<>(), new RuleCallStack(), ignoredTokens, new ArrayDeque<>());
        return out;
    }

    private static void collectFollowSets(Parser parser, ATNState s, ATNState stop,
                                          List<FollowSetWithPath> out,
                                          Map<ATNState, Boolean> seen,
                                          RuleCallStack ruleStack,
                                          Map<Integer, Boolean> ignoredTokens,
                                          Deque<ATNState> returnStates) {
        if (seen.containsKey(s)) return;
        seen.put(s, Boolean.TRUE);

        if (s == stop || s.getStateType() == ATNState.RULE_STOP) {
            if (!returnStates.isEmpty()) {
                Deque<ATNState> rest = new ArrayDeque<>(returnStates);
                ATNState resume = rest.pop();
                RuleCallStack ruleStackAfterExit = ruleStack.copy();
                // (không có cách rẻ để biết cần pop() bao nhiêu lần ở đây — xem ghi chú dưới)
                collectFollowSets(parser, resume, stop, out, new IdentityHashMap<>(), ruleStackAfterExit, ignoredTokens, rest);
                return;
            }
            IntervalSet eps = new IntervalSet();
            eps.add(Token.EPSILON);
            out.add(new FollowSetWithPath(eps, ruleStack.copy(), Collections.emptyList()));
            return;
        }

        for (Transition t : s.getTransitions()) {
            if (t instanceof RuleTransition rt) {
                if (ruleStack.contains(rt.target.ruleIndex)) continue; // left-recursion -> cắt nhánh
                ruleStack.push(rt.target.ruleIndex, RuleCallStack.RuleFrame.NO_TOKEN);
                Deque<ATNState> nextReturnStates = new ArrayDeque<>(returnStates);
                nextReturnStates.push(rt.followState);
                collectFollowSets(parser, t.target, stop, out, new IdentityHashMap<>(), ruleStack, ignoredTokens, nextReturnStates);
                ruleStack.pop(); // quay lại đúng trạng thái stack trước khi vào rule con, để xét tiếp các transition anh em còn lại của state s
            } else if (t instanceof PredicateTransition pt) {
                if (pt.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
                    collectFollowSets(parser, t.target, stop, out, seen, ruleStack, ignoredTokens, returnStates);
                }
            } else if (t instanceof WildcardTransition) {
                out.add(new FollowSetWithPath(IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, parser.getATN().maxTokenType), ruleStack.copy(), Collections.emptyList()));
            } else if (t.isEpsilon()) {
                collectFollowSets(parser, t.target, stop, out, seen, ruleStack, ignoredTokens, returnStates);
            } else {
                IntervalSet label = t.label();
                if (label == null || label.size() == 0) continue;
                if (t instanceof NotSetTransition) {
                    label = label.complement(Token.MIN_USER_TOKEN_TYPE, parser.getATN().maxTokenType);
                }
                out.add(new FollowSetWithPath(label, ruleStack.copy(), getFollowingTokens(t, ignoredTokens)));
            }
        }
    }

    // ── Dò chuỗi mật khẩu chắc chắn đi liền sau 1 cửa ───────────────────────
/*
Ý tưởng: nếu tại vị trí caret, transition chỉ có đúng 1 token khả dĩ (syms.size() == 1),
thì rất có thể phía sau token đó grammar cũng chỉ có đúng 1 lựa chọn tiếp theo, rồi lại đúng 1 lựa chọn tiếp theo nữa...
Cứ như vậy cho tới khi gặp chỗ rẽ nhánh (≥ 2 lựa chọn) thì dừng.
Ví dụ kinh điển: gõ tới DROP, gợi ý tiếp theo chỉ có TABLE (không có VIEW, INDEX gì khác vì đây là 1 rule cụ thể) → getFollowingTokens
sẽ tiếp tục dò xem sau TABLE có phải cũng chỉ có IF hoặc tên bảng... nếu chuỗi đó là "cứng" (không rẽ nhánh) thì trả về nguyên 1 danh sách [TABLE, IF, EXISTS] chẳng hạn,
để IDE gợi ý gõ luôn cả cụm DROP TABLE IF EXISTS thay vì bắt người dùng gõ từng chữ.
 */
    static List<Integer> getFollowingTokens(Transition transition, Map<Integer, Boolean> ignoredTokens) {
        List<Integer> result = new ArrayList<>();
        Deque<ATNState> queue = new ArrayDeque<>();
        queue.push(transition.target);
        while (!queue.isEmpty()) {
            for (Transition t : queue.pop().getTransitions()) {
                if (!(t instanceof AtomTransition)) continue;
                List<Integer> syms = t.label().toList();
                if (syms.size() == 1 && !ignoredTokens.containsKey(syms.get(0))) {
                    result.add(syms.get(0));
                    queue.push(t.target);
                }
            }
        }
        return result;
    }
}
