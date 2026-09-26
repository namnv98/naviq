package com.naviq.completion.syntactic.engine.support;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Follow-set tính trước cho từng state đầu rule: toàn bộ token (thật) có thể xuất hiện từ state đó trở đi,
 * đi xuyên qua mọi rule con, KÈM đường gọi ({@link FollowSetWithPath#path()}) đã đi qua để tới từng nhóm token.
 * {@link Token#EPSILON} trong tập nghĩa là rule có thể rỗng (tới được cuối rule mà không cần token).
 * <p>
 * Chỉ phụ thuộc (Parser, state, ignoredTokens), không đụng tới token đã gõ nên cache tĩnh dùng chung được
 * giữa các lần gọi và giữa các luồng. Key theo NỘI DUNG của {@code ignoredTokens} (equals/hashCode của Map).
 */
public class FollowSetsByState {

    /** 1 nhóm token có thể xuất hiện, kèm các rule con đã đi qua và chuỗi token chắc chắn đi liền sau. */
    public record FollowSetWithPath(IntervalSet intervals, RuleCallStack path, List<Integer> following) {
    }

    public record FollowSetsHolder(List<FollowSetWithPath> sets, IntervalSet combined) {
    }

    private final Map<Integer, ConcurrentHashMap<Map<Integer, Boolean>, FollowSetsHolder>> cache = new ConcurrentHashMap<>();

    public FollowSetsHolder getOrCompute(Parser parser, ATNState start, Map<Integer, Boolean> ignoredTokens) {
        return cache.computeIfAbsent(start.stateNumber, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(ignoredTokens, k -> {
                    ATNState stop = parser.getATN().ruleToStopState[start.ruleIndex];
                    List<FollowSetWithPath> sets = computeFollowSets(parser, start, stop, ignoredTokens);
                    IntervalSet combined = new IntervalSet();
                    sets.forEach(s -> combined.addAll(s.intervals()));
                    return new FollowSetsHolder(sets, combined);
                });
    }

    // ── Tính follow-set ──────────────────────────────────────────────

    static List<FollowSetWithPath> computeFollowSets(Parser parser, ATNState start, ATNState stop, Map<Integer, Boolean> ignoredTokens) {
        List<FollowSetWithPath> out = new ArrayList<>();
        collectFollowSets(parser, start, stop, out, new IdentityHashMap<>(), new RuleCallStack(), ignoredTokens, new ArrayDeque<>());
        return out;
    }

    /**
     * Đệ quy đi qua epsilon / predicate / rule con, mỗi lần chạm transition khớp token thì ghi 1 {@link FollowSetWithPath}.
     * <p>
     * {@code returnStates}: các state sẽ quay về khi ra khỏi rule con (thay cho stack của parser thật).
     * {@code ruleStack} và {@code returnStates} luôn được COPY trước khi đệ quy vào rule con nên các nhánh
     * anh em không ảnh hưởng nhau. Rule đã có trên {@code ruleStack} thì không vào lại (đệ quy trái -> cắt nhánh).
     */
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
                collectFollowSets(parser, resume, stop, out, new IdentityHashMap<>(), ruleStack, ignoredTokens, rest);
                return;
            }
            IntervalSet eps = new IntervalSet();
            eps.add(Token.EPSILON);
            out.add(new FollowSetWithPath(eps, ruleStack.copy(), Collections.emptyList()));
            return;
        }

        ATN atn = parser.getATN();
        for (Transition t : s.getTransitions()) {
            if (t instanceof RuleTransition rt) {
                if (ruleStack.contains(rt.target.ruleIndex)) continue;

                RuleCallStack nextStack = ruleStack.copy();
                nextStack.push(rt.target.ruleIndex, RuleCallStack.RuleFrame.NO_TOKEN);
                Deque<ATNState> nextReturnStates = new ArrayDeque<>(returnStates);
                nextReturnStates.push(rt.followState);

                collectFollowSets(parser, t.target, stop, out, new IdentityHashMap<>(), nextStack, ignoredTokens, nextReturnStates);
            } else if (t instanceof PredicateTransition pt) {
                if (AtnPredicates.holds(parser, pt)) {
                    collectFollowSets(parser, t.target, stop, out, seen, ruleStack, ignoredTokens, returnStates);
                }
            } else if (t instanceof WildcardTransition) {
                out.add(new FollowSetWithPath(IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType), ruleStack.copy(), Collections.emptyList()));
            } else if (t.isEpsilon()) {
                collectFollowSets(parser, t.target, stop, out, seen, ruleStack, ignoredTokens, returnStates);
            } else {
                IntervalSet label = t.label();
                if (label == null || label.size() == 0) continue;
                if (t instanceof NotSetTransition) {
                    label = label.complement(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
                }
                out.add(new FollowSetWithPath(label, ruleStack.copy(), FollowingTokensFinder.getFollowingTokens(t, ignoredTokens)));
            }
        }
    }
}
