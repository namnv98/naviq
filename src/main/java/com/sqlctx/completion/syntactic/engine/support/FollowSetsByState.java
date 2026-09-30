package com.sqlctx.completion.syntactic.engine.support;

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
 * Chỉ phụ thuộc (ATN, state, ignoredTokens), không đụng tới token đã gõ nên cache tĩnh dùng chung được
 * giữa các lần gọi và giữa các luồng. Key theo NỘI DUNG của {@code ignoredTokens} (equals/hashCode của Map).
 * Giả định predicate của grammar cho cùng kết quả với mọi parser: predicate đọc trạng thái parser (vd cờ
 * version, token hiện tại) thì cache giữ kết quả của lần tính đầu tiên.
 */
public class FollowSetsByState {

    /** 1 nhóm token có thể xuất hiện, kèm các rule con đã đi qua và chuỗi token chắc chắn đi liền sau. */
    public record FollowSetWithPath(IntervalSet intervals, RuleCallStack path, List<Integer> following) {
    }

    public record FollowSetsHolder(List<FollowSetWithPath> sets, IntervalSet combined) {
    }

    /** Có ATN trong key (so sánh identity) vì stateNumber chỉ duy nhất trong 1 grammar. */
    private record Key(ATN atn, int stateNumber, Map<Integer, Boolean> ignoredTokens) {
    }

    /** Chỗ quay về khi ra khỏi rule con: state tiếp theo trong caller và đường gọi của caller. */
    private record ReturnTo(ATNState state, RuleCallStack callerStack) {
    }

    private final Map<Key, FollowSetsHolder> cache = new ConcurrentHashMap<>();

    public FollowSetsHolder getOrCompute(Parser parser, ATNState start, Map<Integer, Boolean> ignoredTokens) {
        return cache.computeIfAbsent(new Key(parser.getATN(), start.stateNumber, ignoredTokens), k -> {
            List<FollowSetWithPath> sets = computeFollowSets(parser, start, ignoredTokens);
            IntervalSet combined = new IntervalSet();
            sets.forEach(s -> combined.addAll(s.intervals()));
            return new FollowSetsHolder(sets, combined);
        });
    }

    // ── Tính follow-set ──────────────────────────────────────────────

    static List<FollowSetWithPath> computeFollowSets(Parser parser, ATNState start, Map<Integer, Boolean> ignoredTokens) {
        List<FollowSetWithPath> out = new ArrayList<>();
        collectFollowSets(parser, start, out, new HashSet<>(), new RuleCallStack(), ignoredTokens, new ArrayDeque<>());
        return out;
    }

    /**
     * Đệ quy đi qua epsilon / predicate / rule con, mỗi lần chạm transition khớp token thì ghi 1 {@link FollowSetWithPath}.
     * <p>
     * {@code returns}: chỗ quay về khi ra khỏi rule con (thay cho stack của parser thật); ra khỏi rule con thì
     * {@code ruleStack} trở lại đường gọi của caller. {@code ruleStack} và {@code returns} luôn được COPY trước khi
     * đệ quy vào rule con nên các nhánh anh em không ảnh hưởng nhau. Rule đang nằm trên {@code ruleStack} thì
     * không vào lại (đệ quy trái -> cắt nhánh).
     */
    private static void collectFollowSets(Parser parser, ATNState s,
                                          List<FollowSetWithPath> out,
                                          Set<ATNState> seen,
                                          RuleCallStack ruleStack,
                                          Map<Integer, Boolean> ignoredTokens,
                                          Deque<ReturnTo> returns) {
        if (!seen.add(s)) return;

        if (s.getStateType() == ATNState.RULE_STOP) {
            if (returns.isEmpty()) {
                out.add(new FollowSetWithPath(IntervalSet.of(Token.EPSILON), ruleStack.copy(), Collections.emptyList()));
                return;
            }
            Deque<ReturnTo> rest = new ArrayDeque<>(returns);
            ReturnTo back = rest.pop();
            collectFollowSets(parser, back.state(), out, new HashSet<>(), back.callerStack(), ignoredTokens, rest);
            return;
        }

        ATN atn = parser.getATN();
        for (Transition t : s.getTransitions()) {
            if (t instanceof RuleTransition rt) {
                if (ruleStack.contains(rt.target.ruleIndex)) continue;

                RuleCallStack nextStack = ruleStack.copy();
                nextStack.push(rt.target.ruleIndex, RuleCallStack.RuleFrame.NO_TOKEN);
                Deque<ReturnTo> nextReturns = new ArrayDeque<>(returns);
                nextReturns.push(new ReturnTo(rt.followState, ruleStack));

                collectFollowSets(parser, t.target, out, new HashSet<>(), nextStack, ignoredTokens, nextReturns);
            } else if (t instanceof PredicateTransition pt) {
                if (AtnPredicates.holds(parser, pt)) {
                    collectFollowSets(parser, t.target, out, seen, ruleStack, ignoredTokens, returns);
                }
            } else if (t instanceof WildcardTransition) {
                out.add(new FollowSetWithPath(IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType), ruleStack.copy(), Collections.emptyList()));
            } else if (t.isEpsilon()) {
                collectFollowSets(parser, t.target, out, seen, ruleStack, ignoredTokens, returns);
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
