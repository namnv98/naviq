package com.naviq.completion.syntacticv1;


import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;

/**
 * Tính follow-set tĩnh cho một ATN state: xuất phát từ {@code start}, đi theo mọi
 * transition (epsilon, predicate, rule-call, wildcard, hoặc token thật) cho tới khi
 * gặp {@code stop} hoặc hết đường đi, không đụng gì tới luồng token thật đang được
 * gõ — đây thuần túy là thông tin cấu trúc của grammar tại một điểm cụ thể.
 * <p>
 * Kết quả của lớp này được {@link FollowSetsByState} cache lại theo (state, ignoredTokens),
 * vì nó không phụ thuộc token thật/call-stack nên an toàn để tính 1 lần dùng nhiều lần.
 */
public final class FollowSetComputer {

    private FollowSetComputer() {
    }

    static List<FollowSetWithPath> computeFollowSets(Parser parser, ATNState start, ATNState stop,
                                                     Map<Integer, Boolean> ignoredTokens) {
        List<FollowSetWithPath> result = new ArrayList<>();
        collectFollowSets(parser, start, stop, result, new IdentityHashMap<>(), new RuleCallStack(),
                ignoredTokens, new ArrayDeque<>());
        return result;
    }

    private static void collectFollowSets(Parser parser, ATNState s, ATNState stop,
                                          List<FollowSetWithPath> out,
                                          Map<ATNState, Boolean> seen,
                                          RuleCallStack ruleStack,
                                          Map<Integer, Boolean> ignoredTokens,
                                          Deque<ATNState> returnStates) {
        if (seen.containsKey(s)) {
            return;
        }
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

        for (Transition t : s.getTransitions()) {
            if (t instanceof RuleTransition rt) {
                if (ruleStack.contains(rt.target.ruleIndex)) {
                    continue;
                }
                ruleStack.push(rt.target.ruleIndex, RuleFrame.NO_TOKEN);
                Deque<ATNState> nextReturnStates = new ArrayDeque<>(returnStates);
                nextReturnStates.push(rt.followState);
                collectFollowSets(parser, t.target, stop, out, new IdentityHashMap<>(), ruleStack, ignoredTokens, nextReturnStates);
                ruleStack.pop();
            } else if (t instanceof PredicateTransition pt) {
                if (pt.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
                    collectFollowSets(parser, t.target, stop, out, seen, ruleStack, ignoredTokens, returnStates);
                }
            } else if (t instanceof WildcardTransition) {
                out.add(new FollowSetWithPath(
                        IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, parser.getATN().maxTokenType),
                        ruleStack.copy(), Collections.emptyList()));
            } else if (t.isEpsilon()) {
                collectFollowSets(parser, t.target, stop, out, seen, ruleStack, ignoredTokens, returnStates);
            } else {
                IntervalSet label = t.label();
                if (label == null || label.size() == 0) {
                    continue;
                }
                if (t instanceof NotSetTransition) {
                    label = label.complement(Token.MIN_USER_TOKEN_TYPE, parser.getATN().maxTokenType);
                }
                out.add(new FollowSetWithPath(label, ruleStack.copy(), getFollowingTokens(t, ignoredTokens)));
            }
        }
    }

    /**
     * Với một transition tiêu thụ đúng 1 token cụ thể, dò tiếp xem có chuỗi token
     * cố định nào chắc chắn theo sau không (ví dụ từ khóa ghép nhiều từ) — để gợi ý
     * "gõ tiếp" cả chuỗi thay vì chỉ 1 token.
     */
    public static List<Integer> getFollowingTokens(Transition transition, Map<Integer, Boolean> ignoredTokens) {
        List<Integer> result = new ArrayList<>();
        Deque<ATNState> queue = new ArrayDeque<>();
        queue.push(transition.target);
        while (!queue.isEmpty()) {
            for (Transition t : queue.pop().getTransitions()) {
                if (!(t instanceof AtomTransition)) {
                    continue;
                }
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
