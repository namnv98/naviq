package com.naviq.completion.syntactic.feature;

import com.naviq.completion.syntactic.model.CandidatesResult;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
 * từ nhiều luồng khác nhau.
 * <p>
 * CACHE: dùng {@code ConcurrentHashMap.computeIfAbsent} — atomic sẵn, không
 * cần tự viết double-checked locking bằng {@code ReentrantReadWriteLock} như
 * bản trước (dễ sai, nhất là quên unlock trong finally). Key phụ theo
 * {@code ignoredTokens} so sánh bằng NỘI DUNG (equals/hashCode của Map), KHÔNG
 * phải theo identity (bản trước dùng IdentityHashMap — chỉ "trúng" cache nếu
 * gọi đúng cùng 1 object ignoredTokens, dễ âm thầm mất hết lợi ích cache nếu
 * caller tạo mới Map mỗi lần gọi dù nội dung giống hệt).
 */
public class FollowSetsByState {

    public record FollowSetWithPath(IntervalSet intervals, RuleCallStack path, List<Integer> following) {
    }

    public record FollowSetsHolder(List<FollowSetWithPath> sets, IntervalSet combined) {
    }

    private final Map<Integer, ConcurrentHashMap<Map<Integer, Boolean>, FollowSetsHolder>> cache = new ConcurrentHashMap<>();

    public FollowSetsHolder get(int stateNumber, Map<Integer, Boolean> ignoredTokens) {
        var inner = cache.get(stateNumber);
        return inner == null ? null : inner.get(ignoredTokens);
    }

    public void ensureComputed(Parser parser, ATNState start, Map<Integer, Boolean> ignoredTokens) {
        cache.computeIfAbsent(start.stateNumber, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(ignoredTokens, k -> {
                    ATNState stop = parser.getATN().ruleToStopState[start.ruleIndex];
                    List<FollowSetWithPath> sets = computeFollowSets(parser, start, stop, ignoredTokens);
                    IntervalSet combined = new IntervalSet();
                    sets.forEach(s -> combined.addAll(s.intervals()));
                    return new FollowSetsHolder(sets, combined);
                });
    }

    // ── Tính follow-set (đệ quy, lặn xuyên qua mọi mê cung con) ─────────────

    static List<FollowSetWithPath> computeFollowSets(Parser parser, ATNState start, ATNState stop, Map<Integer, Boolean> ignoredTokens) {
        List<FollowSetWithPath> out = new ArrayList<>();
        collectFollowSets(parser, start, stop, out, new IdentityHashMap<>(), new RuleCallStack(), ignoredTokens, new ArrayDeque<>());
        return out;
    }

    /**
     * Đệ quy lặn xuyên qua mọi cửa miễn phí / cửa vào mê cung con, ghi nhận
     * mỗi lần chạm 1 cửa mật khẩu thật kèm theo "đường đi" (path) đã lặn qua.
     * <p>
     * LƯU Ý QUẢN LÝ {@code ruleStack}: đây là 1 instance MUTABLE dùng chung
     * xuyên suốt cả cây đệ quy — mỗi lần vào 1 mê cung con phải push() TRƯỚC
     * khi đệ quy, rồi pop() NGAY SAU KHI đệ quy đó quay về (để state đúng cho
     * các transition anh em còn lại của cùng 1 phòng). Từng có bug thật ở
     * đúng dòng pop() này (quên mất khi tách file) — sửa hàm này cần đặc biệt
     * cẩn thận, nên có test riêng bao case rule gọi rule con nhiều tầng.
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

        for (Transition t : s.getTransitions()) {
            if (t instanceof RuleTransition rt) {
                if (ruleStack.contains(rt.target.ruleIndex)) continue; // left-recursion -> cắt nhánh
                ruleStack.push(rt.target.ruleIndex, RuleCallStack.RuleFrame.NO_TOKEN);
                Deque<ATNState> nextReturnStates = new ArrayDeque<>(returnStates);
                nextReturnStates.push(rt.followState);
                collectFollowSets(parser, t.target, stop, out, new IdentityHashMap<>(), ruleStack, ignoredTokens, nextReturnStates);
                ruleStack.pop(); // BẮT BUỘC — quay lại đúng trạng thái stack trước khi vào rule con, để xét tiếp các transition anh em còn lại của state s
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
                out.add(new FollowSetWithPath(label, ruleStack.copy(), FollowingTokensFinder.getFollowingTokens(t, ignoredTokens)));
            }
        }
    }

    // ── Sinh gợi ý trực tiếp từ follow-set đã tính sẵn (không dò cửa sống) ──

    /**
     * Với 1 mê cung KHÔNG đặc biệt mà caret vừa chạm phải, sinh gợi ý thẳng từ
     * follow-set đã tính sẵn (không cần dò cửa sống). Với mỗi đường đi (path)
     * trong follow-set, trước tiên thử gộp về mê cung đặc biệt ngoài cùng nếu
     * path đó có đi xuyên qua 1 mê cung đặc biệt nào; nếu không, mới thêm các
     * token của path đó vào gợi ý.
     * <p>
     * Chuyển từ core sang đây vì đây thuần là cách "đọc" dữ liệu follow-set —
     * core chỉ cần gọi hàm này, không cần biết cấu trúc
     * {@code FollowSetWithPath}/{@code set.path()}/{@code set.following()} là gì cả.
     */
    public static void generateSuggestionsFromFollowSets(RuleCallStack stack,
                                                         FollowSetsHolder followSets,
                                                         Map<Integer, Boolean> ignoredTokens,
                                                         Map<Integer, Boolean> preferredRules,
                                                         CandidatesResult result) {
        for (FollowSetWithPath set : followSets.sets()) {
            RuleCallStack fullPath = stack.copy();
            fullPath.appendPath(set.path());
            if (PreferredRuleResolver.resolve(fullPath, preferredRules, result)) {
                continue; // path này quy về 1 mê cung đặc biệt rồi -> khỏi liệt kê token trần trụi
            }
            for (int sym : set.intervals().toList()) {
                if (ignoredTokens.containsKey(sym)) continue;
                if (!result.tokens.containsKey(sym)) {
                    result.tokens.put(sym, new ArrayList<>(set.following()));
                } else if (!result.tokens.get(sym).equals(set.following())) {
                    result.tokens.put(sym, Collections.emptyList());
                }
            }
        }
    }
}