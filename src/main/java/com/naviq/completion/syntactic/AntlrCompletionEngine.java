package com.naviq.completion.syntactic;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ATN-based autocomplete engine for ANTLR4 grammars.
 * <p>
 * Guarantees:
 * - Every suggested token/rule is valid at caret (no false positives).
 * - Every valid token/rule at caret is suggested (no omissions).
 * <p>
 * Thread safety: FollowSetsByState is thread-safe and shareable.
 * AntlrCompletionEngine itself is NOT thread-safe; one instance per request.
 */
public class AntlrCompletionEngine {

    private static final int MAX_DEPTH = 100;

    public final Map<Integer, Boolean> ignoredTokens;
    public final Map<Integer, Boolean> preferredRules;

    private final Parser parser;
    private final ATN atn;
    private final FollowSetsByState followSetsByState;

    // Per-call state
    private CandidatesCollection candidates;
    private List<InputToken> tokens;
    private RuleCallStack callStack;

    public AntlrCompletionEngine(Parser parser,
                                 Map<Integer, Boolean> ignoredTokens,
                                 Map<Integer, Boolean> preferredRules,
                                 FollowSetsByState followSetsByState) {
        this.parser = Objects.requireNonNull(parser);
        this.atn = parser.getATN();
        this.ignoredTokens = Objects.requireNonNull(ignoredTokens);
        this.preferredRules = Objects.requireNonNull(preferredRules);
        this.followSetsByState = Objects.requireNonNull(followSetsByState);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public CandidatesCollection collectCandidates(int caretTokenIndex) {
        if (caretTokenIndex < 0)
            throw new IllegalArgumentException("caretTokenIndex must be >= 0");

        candidates = new CandidatesCollection();
        tokens = readTokens(parser.getTokenStream(), caretTokenIndex);
        callStack = new RuleCallStack();
        traverseATN(atn.ruleToStartState[0], 0);
        return candidates;
    }

    // ── Token stream ──────────────────────────────────────────────────────────

    private static List<InputToken> readTokens(TokenStream stream, int caretTokenIndex) {
        int saved = stream.index();
        stream.seek(0);
        List<InputToken> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            var t = stream.LT(i);
            result.add(new InputToken(t.getType(), t.getStartIndex()));
            if (t.getTokenIndex() >= caretTokenIndex || t.getType() == org.antlr.v4.runtime.Token.EOF)
                break;
        }
        stream.seek(saved);
        return result;
    }

    // ── ATN traversal ─────────────────────────────────────────────────────────

    /**
     * Returns token indices where this rule can end (so caller can follow).
     */
    private Set<Integer> traverseATN(ATNState start, int tokenIndex) {
        if (callStack.size() >= MAX_DEPTH) return Collections.emptySet();

        followSetsByState.ensureComputed(parser, start, ignoredTokens);
        FollowSetsHolder followSets = followSetsByState.get(start.stateNumber, ignoredTokens);

        callStack.push(start.ruleIndex, tokenIndex);
        try {
            if (tokenIndex >= tokens.size() - 1) {   // at caret
                collectAtCaret(followSets);
                // BUG FIX: nếu rule này CÓ THỂ hoàn thành RỖNG (nullable) ngay tại vị trí caret
                // (followSets.combined chứa EPSILON), phải trả về 1 end-index KHÔNG RỖNG để caller
                // (processTransition's RuleTransition handling) tiếp tục khám phá phần theo sau lời
                // gọi rule này qua rt.followState - nếu trả về rỗng vô điều kiện như trước, mọi
                // candidate nằm NGAY SAU 1 rule nullable sẽ bị bỏ sót hoàn toàn (vd "CREATE FUNCTION
                // f() RETURNS int |" thiếu LANGUAGE/AS vì nhóm "(RETURNS ...)?" hoàn thành ngay tại
                // caret, không có gì báo cho createfunctionstmt biết để tiếp tục sang
                // createfunc_opt_list theo sau). "end index" ở đây = tokenIndex KHÔNG ĐỔI, vì rule
                // hoàn thành mà không tiêu thụ thêm token nào.
                return followSets.combined.contains(org.antlr.v4.runtime.Token.EPSILON)
                        ? Collections.singleton(tokenIndex)
                        : Collections.emptySet();
            }

            // Prune branches that can't match the current token
            InputToken cur = tokens.get(tokenIndex);
            boolean canProceed = followSets.combined.contains(org.antlr.v4.runtime.Token.EPSILON)
                    || followSets.combined.contains(cur.type);
            if (!canProceed) return Collections.emptySet();

            return runBFS(start, tokenIndex);
        } finally {
            callStack.pop();
        }
    }

    /**
     * Collect suggestions when we've reached the caret position.
     */
    private void collectAtCaret(FollowSetsHolder followSets) {
        translateToRuleIndex(callStack);

        for (FollowSetWithPath set : followSets.sets) {
            RuleCallStack fullPath = callStack.copy();
            fullPath.appendPath(set.path);
            // BUG FIX: trước đây gọi translateToRuleIndex(fullPath) nhưng BỎ QUA kết quả
            // trả về, nên dù đánh dấu 1 rule là "preferred" (vd unreserved_keyword,
            // type_func_name_keyword...), token bên trong rule đó VẪN bị thêm thẳng vào
            // candidates.tokens như thường - khiến việc đánh dấu preferred hoàn toàn vô
            // tác dụng cho các token này. Nhánh XỬ LÝ TRANSITION THƯỜNG (processTransition,
            // xem "if (!translateToRuleIndex(callStack))" ở chỗ khác trong file) đã làm
            // ĐÚNG (chỉ thêm token khi KHÔNG match rule preferred nào) - collectAtCaret
            // (nhánh xử lý RULE_STOP/epsilon-closure) lại thiếu chính xác điều kiện này.
            // Giờ đồng bộ lại: nếu path này CÓ match 1 preferred rule, rule đó đã được
            // ghi vào candidates.rules rồi - KHÔNG cần (và không nên) nổ thêm token riêng
            // lẻ của rule đó ra candidates.tokens nữa.
            boolean matchedPreferredRule = translateToRuleIndex(fullPath);
            if (matchedPreferredRule) {
                continue;
            }

            for (int sym : set.intervals.toList()) {
                if (ignoredTokens.containsKey(sym)) continue;
                if (!candidates.tokens.containsKey(sym)) {
                    candidates.tokens.put(sym, new ArrayList<>(set.following));
                } else {
                    // Same token reachable via multiple paths → following is ambiguous
                    if (!candidates.tokens.get(sym).equals(set.following))
                        candidates.tokens.put(sym, Collections.emptyList());
                }
            }
        }
    }

    /**
     * BFS over ATN transitions.
     */
    private Set<Integer> runBFS(ATNState start, int startTokenIndex) {
        Set<Integer> endIndices = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<PipelineEntry> queue = new ArrayDeque<>();
        queue.push(new PipelineEntry(start, startTokenIndex));

        while (!queue.isEmpty()) {
            PipelineEntry cur = queue.pop();
            if (!visited.add(cur.state.stateNumber + ":" + cur.tokenIndex)) continue;

            boolean atCaret = cur.tokenIndex >= tokens.size() - 1;

            if (cur.state.getStateType() == ATNState.RULE_STOP) {
                endIndices.add(cur.tokenIndex);
                continue;
            }
            if (atCaret) candidates.caretStates.add(cur.state);

            for (Transition t : cur.state.getTransitions())
                processTransition(t, cur, atCaret, queue, endIndices);
        }
        return endIndices;
    }

    // NOTE: RuleTransition and PredicateTransition must come before isEpsilon()
    // because both return true for isEpsilon().
    private void processTransition(Transition t, PipelineEntry cur, boolean atCaret,
                                   Deque<PipelineEntry> queue, Set<Integer> endIndices) {
        if (t instanceof RuleTransition rt) {
            for (int end : traverseATN(rt.target, cur.tokenIndex))
                queue.push(new PipelineEntry(rt.followState, end));

        } else if (t instanceof PredicateTransition pt) {
            if (checkPredicate(pt))
                queue.push(new PipelineEntry(t.target, cur.tokenIndex));

        } else if (t instanceof WildcardTransition) {
            if (atCaret) {
                if (!translateToRuleIndex(callStack)) {
                    IntervalSet all = IntervalSet.of(org.antlr.v4.runtime.Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
                    for (int sym : all.toList())
                        if (!ignoredTokens.containsKey(sym))
                            candidates.tokens.putIfAbsent(sym, Collections.emptyList());
                }
            } else {
                queue.push(new PipelineEntry(t.target, cur.tokenIndex + 1));
            }

        } else if (t.isEpsilon()) {
            queue.push(new PipelineEntry(t.target, cur.tokenIndex));

        } else {
            // Labeled: AtomTransition, SetTransition, NotSetTransition, RangeTransition
            IntervalSet label = t.label();
            if (label == null || label.size() == 0) return;
            if (t instanceof NotSetTransition)
                label = label.complement(org.antlr.v4.runtime.Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);

            if (atCaret) {
                if (!translateToRuleIndex(callStack)) {
                    List<Integer> syms = label.toList();
                    List<Integer> following = syms.size() == 1
                            ? getFollowingTokens(t) : Collections.emptyList();
                    for (int sym : syms)
                        if (!ignoredTokens.containsKey(sym))
                            candidates.tokens.put(sym, following);
                }
            } else {
                if (label.contains(tokens.get(cur.tokenIndex).type))
                    queue.push(new PipelineEntry(t.target, cur.tokenIndex + 1));
            }
        }
    }

    // ── Rule index translation ────────────────────────────────────────────────

    /**
     * Scans the full call stack and records every preferred rule found.
     */
    private boolean translateToRuleIndex(RuleCallStack stack) {
        if (preferredRules.isEmpty()) return false;
        boolean matched = false;
        List<RuleFrame> frames = stack.frames();

        for (int i = 0; i < frames.size(); i++) {
            RuleFrame frame = frames.get(i);
            if (!preferredRules.containsKey(frame.ruleId)) continue;

            List<RuleFrame> path = new ArrayList<>(frames.subList(0, i)); // independent copy
            List<RuleFrame> existing = candidates.rules.get(frame.ruleId);
            if (!pathEquals(existing, path))
                candidates.rules.put(frame.ruleId, path);
            matched = true;
        }
        return matched;
    }

    private static boolean pathEquals(List<RuleFrame> a, List<RuleFrame> b) {
        if (a == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (a.get(i).ruleId != b.get(i).ruleId || a.get(i).tokenIndex != b.get(i).tokenIndex)
                return false;
        return true;
    }

    // ── Follow sets ───────────────────────────────────────────────────────────

    /**
     * BUG FIX (xem javadoc lớp): bản gốc gọi
     * {@code collectFollowSets(parser, start, stop, out, seen, ruleStack, ignoredTokens)} với
     * "stop" CỐ ĐỊNH = trạng thái dừng của rule NGOÀI CÙNG trong suốt đệ quy, kể cả khi đang đi sâu
     * vào 1 rule con qua RuleTransition. Hệ quả: điều kiện dừng
     * {@code s.getStateType() == ATNState.RULE_STOP} kích hoạt cho BẤT KỲ rule nào (kể cả rule
     * con), không chỉ rule ngoài cùng - nên ngay khi rule con hoàn thành (đặc biệt nếu nó rỗng-được,
     * vd đuôi {@code (...)*}/{@code (...)?}), code DỪNG LUÔN tại đó, KHÔNG bao giờ quay lại
     * {@code rt.followState} để tiếp tục khám phá phần theo sau lời gọi rule đó trong rule NGOÀI.
     * Kết quả quan sát được: thiếu candidate đúng ra phải có (vd "CREATE FUNCTION f() RETURNS int |"
     * thiếu LANGUAGE/AS vì opt_array_bounds cuối typename hoàn thành rỗng rồi dừng luôn, không quay
     * lại phần createfunc_opt_list theo sau), và thừa candidate (table_alias vẫn được báo hợp lệ dù
     * alias đã gõ xong, do tablesample_clause? optional gây hiệu ứng tương tự).
     * <p>
     * FIX: mang theo 1 stack "returnStates" (điểm quay về cho mỗi lần dive vào rule con qua
     * RuleTransition, tương tự cơ chế gọi hàm/return address thật). Khi gặp RULE_STOP: nếu
     * returnStates còn phần tử, lấy ra 1 điểm quay về và TIẾP TỤC duyệt từ đó (rule con vừa xong,
     * quay lại đúng chỗ trong rule gọi nó) - CHỈ khi returnStates rỗng (đã quay về hết, đang ở đúng
     * rule ngoài cùng) mới thật sự dừng và ghi nhận "có thể kết thúc tại đây" (epsilon).
     */
    static List<FollowSetWithPath> computeFollowSets(
            Parser parser, ATNState start, ATNState stop, Map<Integer, Boolean> ignoredTokens) {
        List<FollowSetWithPath> result = new ArrayList<>();
        collectFollowSets(parser, start, stop, result, new IdentityHashMap<>(),
                new RuleCallStack(), ignoredTokens, new ArrayDeque<>());
        return result;
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
                // Rule con vừa hoàn thành - quay lại đúng vị trí trong rule gọi nó, KHÔNG dừng
                // hẳn ở đây. Dùng "seen" MỚI cho phần tiếp theo (mirror cách RuleTransition vẫn
                // dùng seen mới mỗi lần dive - resume cũng là bắt đầu khám phá 1 vùng ATN mới).
                Deque<ATNState> rest = new ArrayDeque<>(returnStates);
                ATNState resume = rest.pop();
                collectFollowSets(parser, resume, stop, out, new IdentityHashMap<>(), ruleStack,
                        ignoredTokens, rest);
                return;
            }
            // Không còn điểm quay về nào - đây mới thật sự là rule NGOÀI CÙNG hoàn thành xong.
            IntervalSet eps = new IntervalSet();
            eps.add(org.antlr.v4.runtime.Token.EPSILON);
            out.add(new FollowSetWithPath(eps, ruleStack.copy(), Collections.emptyList()));
            return;
        }

        for (Transition t : s.getTransitions()) {
            if (t instanceof RuleTransition rt) {
                if (ruleStack.contains(rt.target.ruleIndex)) continue;
                ruleStack.push(rt.target.ruleIndex, RuleFrame.NO_TOKEN);
                Deque<ATNState> nextReturnStates = new ArrayDeque<>(returnStates);
                nextReturnStates.push(rt.followState);
                collectFollowSets(parser, t.target, stop, out, new IdentityHashMap<>(),
                        ruleStack, ignoredTokens, nextReturnStates);
                ruleStack.pop();

            } else if (t instanceof PredicateTransition pt) {
                if (pt.getPredicate().eval(parser, ParserRuleContext.EMPTY))
                    collectFollowSets(parser, t.target, stop, out, seen, ruleStack, ignoredTokens, returnStates);

            } else if (t instanceof WildcardTransition) {
                out.add(new FollowSetWithPath(
                        IntervalSet.of(org.antlr.v4.runtime.Token.MIN_USER_TOKEN_TYPE,
                                parser.getATN().maxTokenType),
                        ruleStack.copy(), Collections.emptyList()));

            } else if (t.isEpsilon()) {
                collectFollowSets(parser, t.target, stop, out, seen, ruleStack, ignoredTokens, returnStates);

            } else {
                IntervalSet label = t.label();
                if (label == null || label.size() == 0) continue;
                if (t instanceof NotSetTransition)
                    label = label.complement(org.antlr.v4.runtime.Token.MIN_USER_TOKEN_TYPE,
                            parser.getATN().maxTokenType);
                out.add(new FollowSetWithPath(label, ruleStack.copy(),
                        getFollowingTokens(t, ignoredTokens)));
            }
        }
    }

    private static List<Integer> getFollowingTokens(Transition transition,
                                                    Map<Integer, Boolean> ignoredTokens) {
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

    // Overload used inside processTransition (already has ignoredTokens in scope)
    private List<Integer> getFollowingTokens(Transition t) {
        return getFollowingTokens(t, ignoredTokens);
    }

    private boolean checkPredicate(PredicateTransition t) {
        return t.getPredicate().eval(parser, ParserRuleContext.EMPTY);
    }

    // ── Data types ────────────────────────────────────────────────────────────

    public record InputToken(int type, int startPosition) {
    }

    record PipelineEntry(ATNState state, int tokenIndex) {
    }

    public record RuleFrame(int ruleId, int tokenIndex) {
        public static final int NO_TOKEN = -1;
    }

    public static class RuleCallStack {
        private final List<RuleFrame> frameList = new ArrayList<>();
        private final Map<Integer, Integer> refCount = new HashMap<>();

        public void push(int ruleId, int tokenIndex) {
            frameList.add(new RuleFrame(ruleId, tokenIndex));
            refCount.merge(ruleId, 1, Integer::sum);
        }

        public RuleFrame pop() {
            RuleFrame f = frameList.remove(frameList.size() - 1);
            int cnt = refCount.get(f.ruleId) - 1;
            if (cnt <= 0) refCount.remove(f.ruleId);
            else refCount.put(f.ruleId, cnt);
            return f;
        }

        public boolean contains(int ruleId) {
            return refCount.getOrDefault(ruleId, 0) > 0;
        }

        public int size() {
            return frameList.size();
        }

        public List<RuleFrame> frames() {
            return frameList;
        }

        public void appendPath(RuleCallStack other) {
            for (RuleFrame f : other.frameList) push(f.ruleId, f.tokenIndex);
        }

        public RuleCallStack copy() {
            RuleCallStack c = new RuleCallStack();
            for (RuleFrame f : frameList) c.push(f.ruleId, f.tokenIndex);
            return c;
        }
    }

    public static class CandidatesCollection {
        /**
         * token type → likely following tokens (empty = unknown)
         */
        public final Map<Integer, List<Integer>> tokens = new HashMap<>();
        /**
         * rule index → call stack path that led to it
         */
        public final Map<Integer, List<RuleFrame>> rules = new HashMap<>();
        /**
         * ATN states at caret — for context detectors
         */
        public final Set<ATNState> caretStates = new HashSet<>();
    }

    public record FollowSetWithPath(IntervalSet intervals, RuleCallStack path, List<Integer> following) {
    }

    public record FollowSetsHolder(List<FollowSetWithPath> sets, IntervalSet combined) {
    }

    /**
     * Thread-safe cache of follow sets, shared across engine instances.
     * Key = (stateNumber, ignoredTokens identity) — identity because different
     * ignoredTokens maps produce different follow sets.
     */
    public static class FollowSetsByState {
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
                List<FollowSetWithPath> sets = computeFollowSets(parser, start, stop, ignoredTokens);
                IntervalSet combined = new IntervalSet();
                sets.forEach(s -> combined.addAll(s.intervals()));
                inner.put(ignoredTokens, new FollowSetsHolder(sets, combined));
            } finally {
                lock.writeLock().unlock();
            }
        }
    }
}