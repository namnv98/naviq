package com.naviq.completion.syntactic;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class AntlrCompletionEngine1 {

    public final Map<Integer, Boolean> ignoredTokens;
    private Map<Integer, Boolean> preferredRules;

    private final Parser parser;
    private final ATN atn;
    private final FollowSetsByState followSetsByState;

    // Per-call state
    private CandidatesCollection candidates;
    private List<InputToken> tokens;
    private int tokenStartIndex;
    // Rule index -> token index -> set of end token indices already computed for that
    // (rule, tokenIndex) combination. This is what bounds the walk: ANTLR4 eliminates
    // left recursion when it builds the ATN, so a rule can never call back into itself
    // (directly or through other rules) at the same token position without consuming
    // at least one token first. That means a rule can only be re-entered at strictly
    // increasing token positions, which are finite (bounded by tokens.size()) — so this
    // shortcut cache alone is enough to guarantee termination, no depth counter needed.
    private Map<Integer, Map<Integer, Set<Integer>>> shortcutMap;

    public AntlrCompletionEngine1(Parser parser,
                                 Map<Integer, Boolean> ignoredTokens,
                                 Map<Integer, Boolean> preferredRules,
                                 FollowSetsByState followSetsByState) {
        this.parser = Objects.requireNonNull(parser);
        this.atn = parser.getATN();
        this.ignoredTokens = Objects.requireNonNull(ignoredTokens);
        this.preferredRules = Objects.requireNonNull(preferredRules);
        this.followSetsByState = Objects.requireNonNull(followSetsByState);
    }

    public Map<Integer, Boolean> getPreferredRules() {
        return Collections.unmodifiableMap(preferredRules);
    }

    public void setPreferredRules(Map<Integer, Boolean> preferredRules) {
        this.preferredRules = new HashMap<>(Objects.requireNonNull(preferredRules));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public CandidatesCollection collectCandidates(int caretTokenIndex) {
        return collectCandidates(caretTokenIndex, null);
    }

    public CandidatesCollection collectCandidates(int caretTokenIndex, ParserRuleContext context) {
        if (caretTokenIndex < 0)
            throw new IllegalArgumentException("caretTokenIndex must be >= 0");
        candidates = new CandidatesCollection();
        shortcutMap = new HashMap<>();
        tokenStartIndex = context != null ? context.start.getTokenIndex() : 0;
        int startRuleIndex = context != null ? context.getRuleIndex() : 0;
        tokens = readTokens(parser.getTokenStream(), tokenStartIndex, caretTokenIndex);
        traverseATN(atn.ruleToStartState[startRuleIndex], 0, new RuleCallStack());
        computeRulePositions();
        return candidates;
    }

    // ── Token stream ──────────────────────────────────────────────────────────

    private static List<InputToken> readTokens(TokenStream stream, int tokenStartIndex, int caretTokenIndex) {
        int saved = stream.index();
        stream.seek(tokenStartIndex);
        List<InputToken> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            var t = stream.LT(i);
            result.add(new InputToken(t.getType(), t.getStartIndex(), t.getStopIndex()));
            if (t.getTokenIndex() >= caretTokenIndex || t.getType() == Token.EOF) {
                break;
            }
        }
        stream.seek(saved);
        return result;
    }

    // ── Rule start/end offset post-processing ──────────────────────────────────

    /**
     * For every preferred rule that was actually hit during the walk, find its
     * right-most occurrence in shortcutMap and translate the (start token, end
     * token) pair into character offsets into the original input.
     */
    private void computeRulePositions() {
        for (int ruleId : preferredRules.keySet()) {
            Map<Integer, Set<Integer>> positionMap = shortcutMap.get(ruleId);
            if (positionMap == null || positionMap.isEmpty()) {
                continue;
            }

            // Right-most (i.e. latest in the input) entry point into this rule.
            int startToken = Collections.max(positionMap.keySet());
            Set<Integer> endSet = positionMap.get(startToken);
            int endToken = endSet.isEmpty() ? tokens.size() - 1 : Collections.max(endSet);

            int startOffset = tokens.get(startToken).startPosition();
            int endOffset;
            if (tokens.get(endToken).type() == Token.EOF) {
                // If the last token is EOF, include trailing whitespace up to it.
                endOffset = tokens.get(endToken).startPosition();
            } else {
                // Otherwise stop right after the previous token, excluding trailing whitespace.
                endOffset = tokens.get(Math.max(endToken - 1, 0)).stopPosition() + 1;
            }

            candidates.rulePositions.put(ruleId, Arrays.asList(startOffset, endOffset));
        }
    }

    // ── ATN traversal ─────────────────────────────────────────────────────────

    /**
     * Returns token indices where this rule can end (so caller can follow).
     */
    private Set<Integer> traverseATN(ATNState start, int tokenIndex, RuleCallStack stack) {
        Map<Integer, Set<Integer>> positionMap = shortcutMap.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());
        if (positionMap.containsKey(tokenIndex)) {
            return positionMap.get(tokenIndex);
        }

        followSetsByState.ensureComputed(parser, start, ignoredTokens);
        FollowSetsHolder followSets = followSetsByState.get(start.stateNumber, ignoredTokens);

        RuleCallStack entered = stack.copy();
        entered.push(start.ruleIndex, tokenIndex);

        Set<Integer> result;
        if (tokenIndex >= tokens.size() - 1) {   // at caret
            collectAtCaret(start.ruleIndex, entered, followSets);
            result = followSets.combined.contains(Token.EPSILON)
                    ? Collections.singleton(tokenIndex)
                    : Collections.emptySet();
            positionMap.put(tokenIndex, result);
            return result;
        }

        InputToken cur = tokens.get(tokenIndex);
        boolean canProceed = followSets.combined.contains(Token.EPSILON) || followSets.combined.contains(cur.type());
        if (!canProceed) {
            result = Collections.emptySet();
            positionMap.put(tokenIndex, result);
            return result;
        }

        result = runBFS(start, tokenIndex, entered);
        positionMap.put(tokenIndex, result);
        return result;
    }

    /**
     * Collect suggestions when we've reached the caret position.
     */
    private void collectAtCaret(int enteringRuleIndex, RuleCallStack stack, FollowSetsHolder followSets) {
        if (preferredRules.containsKey(enteringRuleIndex)) {
            translateToRuleIndex(stack);
            return;
        }
        for (FollowSetWithPath set : followSets.sets) {
            RuleCallStack fullPath = stack.copy();
            fullPath.appendPath(set.path);
            if (translateToRuleIndex(fullPath)) {
                continue;
            }
            for (int sym : set.intervals.toList()) {
                if (ignoredTokens.containsKey(sym)) {
                    continue;
                }
                if (!candidates.tokens.containsKey(sym)) {
                    candidates.tokens.put(sym, new ArrayList<>(set.following));
                } else if (!candidates.tokens.get(sym).equals(set.following)) {
                    // Same token reachable via multiple paths -> following is ambiguous
                    candidates.tokens.put(sym, Collections.emptyList());
                }
            }
        }
    }

    /**
     * BFS over ATN transitions.
     */
    private Set<Integer> runBFS(ATNState start, int startTokenIndex, RuleCallStack stack) {
        Set<Integer> endIndices = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<PipelineEntry> queue = new ArrayDeque<>();
        queue.push(new PipelineEntry(start, startTokenIndex, stack));

        while (!queue.isEmpty()) {
            PipelineEntry cur = queue.pop();
            if (!visited.add(cur.state.stateNumber + ":" + cur.tokenIndex())) {
                continue;
            }

            boolean atCaret = cur.tokenIndex() >= tokens.size() - 1;

            if (cur.state().getStateType() == ATNState.RULE_STOP) {
                endIndices.add(cur.tokenIndex());
                continue;
            }
            if (atCaret) {
                candidates.caretStates.add(cur.state());
            }
            for (Transition t : cur.state().getTransitions()) {
                processTransition(t, cur, atCaret, queue, endIndices);
            }
        }
        return endIndices;
    }

    private void processTransition(Transition t, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue, Set<Integer> endIndices) {
        RuleCallStack stack = cur.stackSnapshot();
        if (t instanceof RuleTransition rt) {
            for (int end : traverseATN(rt.target, cur.tokenIndex(), stack)) {
                queue.push(new PipelineEntry(rt.followState, end, stack));
            }
        } else if (t instanceof PredicateTransition pt) {
            if (checkPredicate(pt)) {
                queue.push(new PipelineEntry(t.target, cur.tokenIndex(), stack));
            }
        } else if (t instanceof WildcardTransition) {
            if (atCaret) {
                if (!translateToRuleIndex(stack)) {
                    IntervalSet all = IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
                    for (int sym : all.toList()) {
                        if (!ignoredTokens.containsKey(sym)) {
                            candidates.tokens.putIfAbsent(sym, Collections.emptyList());
                        }
                    }
                }
            } else {
                queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, stack));
            }

        } else if (t.isEpsilon()) {
            if (atCaret) {
                translateToRuleIndex(stack);
            }
            queue.push(new PipelineEntry(t.target, cur.tokenIndex(), stack));
        } else {
            // Labeled: AtomTransition, SetTransition, NotSetTransition, RangeTransition
            IntervalSet label = t.label();
            if (label == null || label.size() == 0) {
                return;
            }
            if (t instanceof NotSetTransition) {
                label = label.complement(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
            }
            if (atCaret) {
                if (!translateToRuleIndex(stack)) {
                    List<Integer> syms = label.toList();
                    List<Integer> following = syms.size() == 1 ? getFollowingTokens(t) : Collections.emptyList();
                    for (int sym : syms) {
                        if (!ignoredTokens.containsKey(sym)) {
                            candidates.tokens.put(sym, following);
                        }
                    }
                }
            } else {
                if (label.contains(tokens.get(cur.tokenIndex()).type())) {
                    queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, stack));
                }
            }
        }
    }

    /**
     * Scans the full call stack and records every preferred rule found.
     */
    private boolean translateToRuleIndex(RuleCallStack stack) {
        if (preferredRules.isEmpty()) return false;
        List<RuleFrame> frames = stack.frames();
        for (int i = 0; i < frames.size(); i++) {
            RuleFrame frame = frames.get(i);
            if (!preferredRules.containsKey(frame.ruleId)) {
                continue;
            }
            List<RuleFrame> path = new ArrayList<>(frames.subList(0, i)); // independent copy
            Integer existingEntryIndex = candidates.ruleEntryTokenIndex.get(frame.ruleId);
            if (isMoreRelevant(frame.tokenIndex(), existingEntryIndex)) {
                candidates.rules.put(frame.ruleId, path);
                candidates.ruleEntryTokenIndex.put(frame.ruleId, frame.tokenIndex()); // ← lưu tokenIndex CHÍNH rule này
            }
            return true;   // stop at the first (outermost) match
        }
        return false;
    }

    /**
     * Decides whether a newly-found occurrence of a preferred rule should replace whatever
     * occurrence is already recorded for it.
     * <p>
     * A rule can genuinely be reached through several different ATN paths at the same
     * caret: e.g. a keyword that also happens to be a valid unreserved identifier (so the
     * SAME rule gets entered once via real traversal, having "eaten" that keyword as if it
     * were the identifier), AND separately via the static follow-set closure showing the
     * rule is ALSO reachable with zero further consumption from here (sentinel
     * {@link RuleFrame#NO_TOKEN}). The sentinel occurrence is always the most relevant one
     * for completion purposes - it means "this rule is exactly what should be typed right
     * now" - so it must always win over any real, already-consumed occurrence, no matter
     * how large that occurrence's own tokenIndex is. Among two REAL occurrences, the one
     * closer to the caret (larger tokenIndex) wins, since it reflects the most current
     * parse state.
     */
    private static boolean isMoreRelevant(int candidateTokenIndex, Integer existingTokenIndex) {
        if (existingTokenIndex == null) {
            return true;
        }
        if (candidateTokenIndex == RuleFrame.NO_TOKEN) {
            return existingTokenIndex != RuleFrame.NO_TOKEN;
        }
        if (existingTokenIndex == RuleFrame.NO_TOKEN) {
            return false;
        }
        return candidateTokenIndex > existingTokenIndex;
    }

    // ── Follow sets ───────────────────────────────────────────────────────────

    static List<FollowSetWithPath> computeFollowSets(Parser parser, ATNState start, ATNState stop, Map<Integer, Boolean> ignoredTokens) {
        List<FollowSetWithPath> result = new ArrayList<>();
        collectFollowSets(parser, start, stop, result, new IdentityHashMap<>(), new RuleCallStack(), ignoredTokens, new ArrayDeque<>());
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
                out.add(new FollowSetWithPath(IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, parser.getATN().maxTokenType), ruleStack.copy(), Collections.emptyList()));
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
                out.add(new FollowSetWithPath(label, ruleStack.copy(),
                        getFollowingTokens(t, ignoredTokens)));
            }
        }
    }

    private static List<Integer> getFollowingTokens(Transition transition, Map<Integer, Boolean> ignoredTokens) {
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

    // Overload used inside processTransition (already has ignoredTokens in scope)
    private List<Integer> getFollowingTokens(Transition t) {
        return getFollowingTokens(t, ignoredTokens);
    }

    private boolean checkPredicate(PredicateTransition t) {
        return t.getPredicate().eval(parser, ParserRuleContext.EMPTY);
    }

    // ── Data types ────────────────────────────────────────────────────────────

    public record InputToken(int type, int startPosition, int stopPosition) {
    }

    record PipelineEntry(ATNState state, int tokenIndex, RuleCallStack stackSnapshot) {
    }

    public record RuleFrame(int ruleId, int tokenIndex) {
        public static final int NO_TOKEN = -1;
    }

    public static final class RuleCallStack {
        private static final class Node {
            final RuleFrame frame;
            final Node parent;
            Node(RuleFrame frame, Node parent) {
                this.frame = frame;
                this.parent = parent;
            }
        }

        private Node head;
        private int size;

        public RuleCallStack() {
            this.head = null;
            this.size = 0;
        }

        private RuleCallStack(Node head, int size) {
            this.head = head;
            this.size = size;
        }

        public void push(int ruleId, int tokenIndex) {
            head = new Node(new RuleFrame(ruleId, tokenIndex), head);
            size++;
        }

        public RuleFrame pop() {
            if (head == null) {
                throw new NoSuchElementException("RuleCallStack is empty");
            }
            RuleFrame f = head.frame;
            head = head.parent;
            size--;
            return f;
        }

        public boolean contains(int ruleId) {
            for (Node n = head; n != null; n = n.parent) {
                if (n.frame.ruleId() == ruleId) return true;
            }
            return false;
        }

        public int size() {
            return size;
        }

        public List<RuleFrame> frames() {
            RuleFrame[] arr = new RuleFrame[size];
            int i = size - 1;
            for (Node n = head; n != null; n = n.parent) {
                arr[i--] = n.frame;
            }
            return Arrays.asList(arr);
        }

        public void appendPath(RuleCallStack other) {
            for (RuleFrame f : other.frames()) {
                push(f.ruleId(), f.tokenIndex());
            }
        }

        public RuleCallStack copy() {
            return new RuleCallStack(head, size);
        }
    }

    public static class CandidatesCollection {
        public final Map<Integer, List<Integer>> tokens = new HashMap<>();
        public final Map<Integer, List<RuleFrame>> rules = new HashMap<>();
        public final Map<Integer, Integer> ruleEntryTokenIndex = new HashMap<>(); // MỚI
        public final Map<Integer, List<Integer>> rulePositions = new HashMap<>();
        public final Set<ATNState> caretStates = new HashSet<>();
    }

    public record FollowSetWithPath(IntervalSet intervals, RuleCallStack path, List<Integer> following) {
    }

    public record FollowSetsHolder(List<FollowSetWithPath> sets, IntervalSet combined) {
    }

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