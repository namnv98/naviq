package com.naviq.completion.syntactic;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * CompletionCore – autocomplete.
 * <p>
 * Cách hoạt động (tóm tắt):
 * 1. Nhận vào một câu SQL đang gõ dở và vị trí con trỏ (caretTokenIndex).
 * 2. Duyệt qua ATN (Augmented Transition Network) – máy trạng thái mà ANTLR dùng để mô tả grammar.
 * 3. Thu thập những token/rule nào có thể xuất hiện hợp lệ tại vị trí con trỏ.
 * 4. Trả về CandidatesCollection chứa danh sách gợi ý đó.
 */

public class AntlrCompletionEngine {

    private static final int MAX_RECURSION_DEPTH = 100;

    public Map<Integer, Boolean> ignoredTokens;
    public Map<Integer, Boolean> preferredRules;

    private final Parser parser;
    private final ATN atn;
    private CandidatesCollection candidates;
    private final FollowSetsByState followSetsByState;
    private int tokenStartIndex;
    private List<Token> tokens;
    private RuleList callStack;

    public AntlrCompletionEngine(Parser parser,
                                 Map<Integer, Boolean> ignoredTokens,
                                 Map<Integer, Boolean> preferredRules,
                                 FollowSetsByState followSets) {
        this.ignoredTokens = ignoredTokens;
        this.preferredRules = preferredRules;
        this.parser = parser;
        this.atn = parser.getATN();
        this.followSetsByState = followSets;
    }

    public CandidatesCollection collectCandidates(int caretTokenIndex) {
        candidates = new CandidatesCollection();
        tokenStartIndex = 0;

        tokens = new ArrayList<>();
        TokenStream tokenStream = parser.getTokenStream();
        int currentOffset = tokenStream.index();
        tokenStream.seek(tokenStartIndex);
        for (int offset = 1; ; offset++) {
            org.antlr.v4.runtime.Token token = tokenStream.LT(offset);
            tokens.add(new Token(token.getType(), token.getStartIndex()));
            if (token.getTokenIndex() >= caretTokenIndex
                    || token.getType() == org.antlr.v4.runtime.Token.EOF) break;
        }
        tokenStream.seek(currentOffset);

        callStack = new RuleList();
        fetchEndStatus(atn.ruleToStartState[0], 0);
        return candidates;
    }

    // Core ATN traversal
    private Map<Integer, Boolean> fetchEndStatus(ATNState startState, int tokenIndex) {
        if (callStack.getRules().size() > MAX_RECURSION_DEPTH)
            return Collections.emptyMap();

        followSetsByState.collectFollowSets(parser, startState, ignoredTokens);
        FollowSetsHolder followSets = followSetsByState.get(startState.stateNumber);

        RuleContext ruleContext = new RuleContext(startState.ruleIndex, tokenIndex);
        callStack.push(ruleContext);

        try {
            if (tokenIndex >= tokens.size() - 1) {
                if (preferredRules.containsKey(startState.ruleIndex)) {
                    translateToRuleIndex(callStack);
                } else {
                    for (FollowSetWithPath set : followSets.sets) {
                        RuleList fullPath = callStack.copy();
                        fullPath.append(set.path);
                        if (!translateToRuleIndex(fullPath)) {
                            for (int symbol : set.intervals.toList()) {
                                if (ignoredTokens.containsKey(symbol)) continue;
                                if (!candidates.tokens.containsKey(symbol)) {
                                    candidates.tokens.put(symbol, set.following);
                                } else {
                                    List<Integer> existing = candidates.tokens.get(symbol);
                                    if (!existing.equals(set.following))
                                        candidates.tokens.put(symbol, Collections.emptyList());
                                }
                            }
                        }
                    }
                }
                return Collections.emptyMap();
            }

            Token currentSymbol = tokens.get(tokenIndex);
            if (!followSets.combined.contains(org.antlr.v4.runtime.Token.EPSILON)
                    && !followSets.combined.contains(currentSymbol.type)) {
                return Collections.emptyMap();
            }

            Map<Integer, Boolean> result = new HashMap<>();
            Deque<PipelineEntry> statePipeline = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            statePipeline.push(new PipelineEntry(startState, tokenIndex));

            while (!statePipeline.isEmpty()) {
                PipelineEntry currentEntry = statePipeline.pop();
                String visitKey = currentEntry.state.stateNumber + ":" + currentEntry.tokenIndex;
                if (!visited.add(visitKey)) continue;

                boolean atCaret = currentEntry.tokenIndex >= tokens.size() - 1;

                if (currentEntry.state.getStateType() == ATNState.RULE_STOP) {
                    result.put(currentEntry.tokenIndex, Boolean.TRUE);
                    continue;
                }

                if (atCaret) {
                    candidates.caretStates.add(currentEntry.state);
                }

                for (Transition t : currentEntry.state.getTransitions()) {
                    if (t instanceof RuleTransition) {

                        RuleTransition rt = (RuleTransition) t;

                        Map<Integer, Boolean> endStatus =
                                fetchEndStatus(rt.target, currentEntry.tokenIndex);
                        for (int status : endStatus.keySet())
                            statePipeline.push(new PipelineEntry(rt.followState, status));

                    } else if (t instanceof PredicateTransition) {
                        if (checkPredicate(parser, (PredicateTransition) t))
                            statePipeline.push(
                                    new PipelineEntry(t.target, currentEntry.tokenIndex));

                    } else if (t instanceof WildcardTransition) {
                        if (atCaret) {
                            if (!translateToRuleIndex(callStack)) {
                                IntervalSet interval = IntervalSet.of(
                                        org.antlr.v4.runtime.Token.MIN_USER_TOKEN_TYPE,
                                        atn.maxTokenType);
                                for (int symbol : interval.toList())
                                    if (!ignoredTokens.containsKey(symbol))
                                        candidates.tokens.putIfAbsent(
                                                symbol, Collections.emptyList());
                            }
                        } else {
                            statePipeline.push(new PipelineEntry(
                                    t.target, currentEntry.tokenIndex + 1));
                        }

                    } else {
                        if (t.isEpsilon()) {
                            if (atCaret) translateToRuleIndex(callStack);
                            statePipeline.push(new PipelineEntry(
                                    t.target, currentEntry.tokenIndex));
                        }

                        IntervalSet label = t.label();
                        if (label != null && label.size() > 0) {
                            if (t instanceof NotSetTransition)
                                label = label.complement(
                                        org.antlr.v4.runtime.Token.MIN_USER_TOKEN_TYPE,
                                        atn.maxTokenType);
                            if (atCaret) {
                                if (!translateToRuleIndex(callStack)) {
                                    List<Integer> list = label.toList();
                                    boolean addFollowing = list.size() == 1;
                                    for (int symbol : list)
                                        if (!ignoredTokens.containsKey(symbol))
                                            candidates.tokens.put(symbol,
                                                    addFollowing
                                                            ? getFollowingTokens(t, ignoredTokens)
                                                            : Collections.emptyList());
                                }
                            } else {
                                if (label.contains(tokens.get(currentEntry.tokenIndex).type))
                                    statePipeline.push(new PipelineEntry(
                                            t.target, currentEntry.tokenIndex + 1));
                            }
                        }
                    }
                }
            }
            return result;

        } finally {
            if (!callStack.getRules().isEmpty()) callStack.pop();
        }
    }

    private boolean translateToRuleIndex(RuleList ruleStack) {
        if (preferredRules.isEmpty()) return false;
        List<RuleContext> rules = ruleStack.getRules();
        boolean matched = false;

        for (int i = 0; i < rules.size(); i++) {
            if (!preferredRules.containsKey(rules.get(i).id)) continue;

            List<RuleContext> path = new ArrayList<>(rules.subList(0, i));
            List<RuleContext> existing = candidates.rules.get(rules.get(i).id);
            boolean same = existing != null && existing.size() == path.size();
            if (same) {
                for (int j = 0; j < existing.size(); j++) {
                    if (existing.get(j).id != path.get(j).id) {
                        same = false;
                        break;
                    }
                }
            }
            if (!same) candidates.rules.put(rules.get(i).id, path);

            matched = true;
            // ← KHÔNG return true ngay, tiếp tục duyệt hết stack
            // để collect tất cả preferred rules trong cùng 1 call stack
        }
        return matched;
    }

    // Static helpers
    private static List<FollowSetWithPath> determineFollowSets(
            Parser parser, ATNState start, ATNState stop,
            Map<Integer, Boolean> ignoredTokens) {
        List<FollowSetWithPath> result = new ArrayList<>();
        collectFollowSets(parser, start, stop, result,
                new IdentityHashMap<>(), new RuleList(), ignoredTokens);
        return result;
    }

    // FIX: tạo seen mới cho mỗi RuleTransition để các alternative
    // không bị block lẫn nhau khi cùng dẫn đến cùng một rule (vd: qualifiedName)
    private static void collectFollowSets(
            Parser parser, ATNState s, ATNState stopState,
            List<FollowSetWithPath> followSets, Map<ATNState, Boolean> seen,
            RuleList ruleStack, Map<Integer, Boolean> ignoredTokens) {
        if (seen.containsKey(s)) return;
        seen.put(s, Boolean.TRUE);

        if (s == stopState || s.getStateType() == ATNState.RULE_STOP) {
            IntervalSet eps = new IntervalSet();
            eps.add(org.antlr.v4.runtime.Token.EPSILON);
            followSets.add(new FollowSetWithPath(
                    eps, ruleStack.copy(), Collections.emptyList()));
            return;
        }

        for (Transition t : s.getTransitions()) {
            if (t instanceof RuleTransition) {
                RuleTransition rt = (RuleTransition) t;
                if (ruleStack.contains(rt.target.ruleIndex)) continue;
                ruleStack.push(new RuleContext(rt.target.ruleIndex));
                // FIX: new IdentityHashMap<>() thay vì dùng chung seen
                // → mỗi alternative explore độc lập, không bị block bởi alternative khác
                collectFollowSets(parser, t.target, stopState,
                        followSets, new IdentityHashMap<>(), ruleStack, ignoredTokens);
                ruleStack.pop();
            } else if (t instanceof PredicateTransition) {
                if (checkPredicate(parser, (PredicateTransition) t))
                    collectFollowSets(parser, t.target, stopState,
                            followSets, seen, ruleStack, ignoredTokens);
            } else if (t.isEpsilon()) {
                collectFollowSets(parser, t.target, stopState,
                        followSets, seen, ruleStack, ignoredTokens);
            } else if (t instanceof WildcardTransition) {
                followSets.add(new FollowSetWithPath(
                        IntervalSet.of(org.antlr.v4.runtime.Token.MIN_USER_TOKEN_TYPE,
                                parser.getATN().maxTokenType),
                        ruleStack.copy(), Collections.emptyList()));
            } else {
                IntervalSet set = t.label();
                if (set == null || set.size() == 0) continue;
                if (t instanceof NotSetTransition)
                    set = set.complement(org.antlr.v4.runtime.Token.MIN_USER_TOKEN_TYPE,
                            parser.getATN().maxTokenType);
                followSets.add(new FollowSetWithPath(
                        set, ruleStack.copy(), getFollowingTokens(t, ignoredTokens)));
            }
        }
    }

    private static List<Integer> getFollowingTokens(
            Transition transition, Map<Integer, Boolean> ignoredTokens) {
        List<Integer> result = new ArrayList<>();
        Deque<ATNState> pipeline = new ArrayDeque<>();
        pipeline.push(transition.target);
        while (!pipeline.isEmpty()) {
            for (Transition t : pipeline.pop().getTransitions()) {
                if (t instanceof AtomTransition) {
                    if (t.isEpsilon()) {
                        pipeline.push(t.target);
                    } else {
                        List<Integer> list = t.label().toList();
                        if (list.size() == 1 && !ignoredTokens.containsKey(list.get(0))) {
                            result.add(list.get(0));
                            pipeline.push(t.target);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static boolean checkPredicate(Parser parser, PredicateTransition t) {
        return t.getPredicate().eval(parser, ParserRuleContext.EMPTY);
    }

    // Inner types
    public static class Token {
        public final int type;
        public final int startPosition;

        public Token(int type, int startPosition) {
            this.type = type;
            this.startPosition = startPosition;
        }
    }

    public static class PipelineEntry {
        public final ATNState state;
        public final int tokenIndex;

        public PipelineEntry(ATNState state, int tokenIndex) {
            this.state = state;
            this.tokenIndex = tokenIndex;
        }
    }

    public static class RuleContext {
        public final int id;
        public final int tokenIndex;
        public static final int NO_TOKEN = -1;

        public RuleContext(int id) {
            this.id = id;
            this.tokenIndex = NO_TOKEN;
        }

        public RuleContext(int id, int tokenIndex) {
            this.id = id;
            this.tokenIndex = tokenIndex;
        }
    }

    // FIX: thay BitSet bằng refCount Map
    // BitSet chỉ track có/không → alternative 2 bị block khi alternative 1
    // đã push cùng rule vào stack. refCount track số lần push thực tế,
    // chỉ block khi rule đang thực sự trên call stack (count > 0)
    public static class RuleList {
        private final List<RuleContext> rules = new ArrayList<>();
        private final Map<Integer, Integer> refCount = new HashMap<>(); // FIX: thay BitSet

        public RuleList copy() {
            RuleList copy = new RuleList();
            for (RuleContext rule : rules) {
                RuleContext ctx = new RuleContext(rule.id, rule.tokenIndex);
                copy.rules.add(ctx);
                copy.refCount.merge(ctx.id, 1, Integer::sum);
            }
            return copy;
        }

        public void append(RuleList other) {
            for (RuleContext r : other.rules) push(r);
        }

        public boolean contains(int ruleId) {
            return refCount.getOrDefault(ruleId, 0) > 0;
        }

        public void push(RuleContext rule) {
            rules.add(rule);
            refCount.merge(rule.id, 1, Integer::sum);
        }

        public RuleContext pop() {
            RuleContext r = rules.remove(rules.size() - 1);
            int count = refCount.get(r.id) - 1;
            if (count <= 0) refCount.remove(r.id);
            else refCount.put(r.id, count);
            return r;
        }

        public List<RuleContext> getRules() {
            return rules;
        }
    }
    public static class CandidatesCollection {
        public final Map<Integer, List<Integer>> tokens = new HashMap<>();
        public final Map<Integer, List<RuleContext>> rules = new HashMap<>();
        /* ATNState mà engine đang đứng tại caretTokenIndex — dùng cho CursorContextDetector*/
        public final Set<ATNState> caretStates = new HashSet<>();
    }

    public static class FollowSetWithPath {
        public final IntervalSet intervals;
        public final RuleList path;
        public final List<Integer> following;

        public FollowSetWithPath(IntervalSet intervals, RuleList path, List<Integer> following) {
            this.intervals = intervals;
            this.path = path;
            this.following = following;
        }
    }

    public static class FollowSetsHolder {
        public final List<FollowSetWithPath> sets;
        public final IntervalSet combined;

        public FollowSetsHolder(List<FollowSetWithPath> sets, IntervalSet combined) {
            this.sets = sets;
            this.combined = combined;
        }
    }

    public static class FollowSetsByState {
        private final ReadWriteLock rw = new ReentrantReadWriteLock();
        private final Map<Integer, FollowSetsHolder> map = new HashMap<>();

        public FollowSetsHolder get(int state) {
            rw.readLock().lock();
            try {
                return map.get(state);
            } finally {
                rw.readLock().unlock();
            }
        }

        public void collectFollowSets(Parser parser, ATNState startState,
                                      Map<Integer, Boolean> ignoredTokens) {
            rw.writeLock().lock();
            try {
                if (map.containsKey(startState.stateNumber)) return;
                ATNState stop = parser.getATN().ruleToStopState[startState.ruleIndex];
                List<FollowSetWithPath> followSets =
                        determineFollowSets(parser, startState, stop, ignoredTokens);
                IntervalSet combined = new IntervalSet();
                for (FollowSetWithPath s : followSets) combined.addAll(s.intervals);
                map.put(startState.stateNumber, new FollowSetsHolder(followSets, combined));
            } finally {
                rw.writeLock().unlock();
            }
        }
    }
}