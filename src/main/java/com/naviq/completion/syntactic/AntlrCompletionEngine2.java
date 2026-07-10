package com.naviq.completion.syntactic;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Tính danh sách gợi ý code-completion (token + rule) tại vị trí con trỏ,
 * bằng cách "đi bộ" (duyệt) trên ATN của parser (thuật toán kiểu antlr4-c3).
 *
 * LUỒNG TỔNG QUÁT (đọc từ trên xuống):
 *
 *   collectCandidates()                 <- điểm vào public
 *     └─ enterRule()                    <- đệ quy, có nhớ (memoized) theo (rule, tokenIndex)
 *          ├─ đã tới caret?      -> collectSuggestionsAtCaret()
 *          ├─ token không khớp?  -> ngõ cụt, không có exit nào
 *          └─ ngược lại          -> walkRuleBody()   (BFS trên các transition của ATN)
 *                                   └─ dispatchTransition() định tuyến tới đúng
 *                                      handler cho từng loại transition:
 *                                        handleRuleCall()
 *                                        handlePredicate()
 *                                        handleWildcard()
 *                                        handleEpsilon()
 *                                        handleTokenMatch()
 *     └─ resolveRuleTextRanges()         <- hậu xử lý, đổi các chỉ số token
 *                                           thành offset trong text gốc
 *
 * Ý TƯỞNG CỐT LÕI cần nhớ khi đọc file này:
 *  - Gọi rule con (RuleTransition) KHÔNG tiêu tốn token nào — giống như gọi hàm,
 *    chỉ là "nhảy" sang một sub-graph khác rồi quay về, vị trí trong input vẫn nguyên.
 *  - Ở mỗi bước, engine chỉ tự hỏi đúng 2 câu: "đã hết token để so chưa?" (isAtCaret)
 *    và "token tiếp theo có khớp cái tôi mong đợi không?" (canConsumeCurrentToken).
 *    Mọi thứ còn lại (RuleCallStack, cache follow-set, preferredRules...) chỉ là
 *    tối ưu hoá / ghi nhớ ngữ cảnh phục vụ 2 câu hỏi đó, không phải logic cốt lõi.
 */
public class AntlrCompletionEngine2 {

    public final Map<Integer, Boolean> ignoredTokens;
    private Map<Integer, Boolean> preferredRules;

    private final Parser parser;
    private final ATN atn;
    private final FollowSetsByState followSetsByState;

    // Trạng thái riêng cho mỗi lần gọi (reset lại mỗi khi collectCandidates() chạy)
    private CandidatesCollection candidates;
    private List<InputToken> tokens;
    private int tokenStartIndex;

    /** ruleIndex -> (tokenIndex lúc vào rule -> tập tokenIndex có thể thoát ra), dùng để memoize. */
    private Map<Integer, Map<Integer, Set<Integer>>> ruleExitCache;

    public AntlrCompletionEngine2(Parser parser,
                                  Map<Integer, Boolean> ignoredTokens,
                                  Map<Integer, Boolean> preferredRules,
                                  FollowSetsByState followSetsByState) {
        this.parser = Objects.requireNonNull(parser);
        this.atn = parser.getATN();
        this.ignoredTokens = Objects.requireNonNull(ignoredTokens);
        this.preferredRules = Objects.requireNonNull(preferredRules);
        this.followSetsByState = Objects.requireNonNull(followSetsByState);
    }

    // ════════════════════════════════════════════════════════════════════════
    // BƯỚC 0 — Điểm vào public
    // ════════════════════════════════════════════════════════════════════════

    public CandidatesCollection collectCandidates(int caretTokenIndex) {
        return collectCandidates(caretTokenIndex, null);
    }

    public CandidatesCollection collectCandidates(int caretTokenIndex, ParserRuleContext context) {
        if (caretTokenIndex < 0) {
            throw new IllegalArgumentException("caretTokenIndex must be >= 0");
        }

        initCallState(caretTokenIndex, context);

        // Nếu có context truyền vào thì bắt đầu duyệt từ đúng rule đó (đỡ phải duyệt lại từ gốc),
        // không thì mặc định bắt đầu từ rule đầu tiên của grammar (index 0).
        int startRuleIndex = context != null ? context.getRuleIndex() : 0;
        enterRule(atn.ruleToStartState[startRuleIndex], 0, new RuleCallStack());

        resolveRuleTextRanges();
        return candidates;
    }

    private void initCallState(int caretTokenIndex, ParserRuleContext context) {
        candidates = new CandidatesCollection();
        ruleExitCache = new HashMap<>();
        tokenStartIndex = context != null ? context.start.getTokenIndex() : 0;
        // Đọc trước toàn bộ token từ vị trí bắt đầu tới caret, dùng chung cho suốt quá trình duyệt.
        tokens = readTokens(parser.getTokenStream(), tokenStartIndex, caretTokenIndex);
    }

    // ════════════════════════════════════════════════════════════════════════
    // BƯỚC 1 — Vào một rule tại một vị trí token cho trước (đệ quy có nhớ)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Duyệt rule {@code start.ruleIndex} bắt đầu từ {@code tokenIndex}.
     * Trả về các chỉ số token mà lời gọi rule này có thể *thoát ra*,
     * để bên gọi (rule cha) biết cần "resume" tiếp từ đâu.
     */
    private Set<Integer> enterRule(ATNState start, int tokenIndex, RuleCallStack callerStack) {
        // Cache theo (ruleIndex, tokenIndex): nếu rule này đã từng được duyệt bắt đầu
        // từ đúng vị trí token này rồi thì khỏi làm lại, trả ngay kết quả cũ.
        Map<Integer, Set<Integer>> exitsByEntryToken =
                ruleExitCache.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());

        Set<Integer> cached = exitsByEntryToken.get(tokenIndex);
        if (cached != null) {
            return cached;
        }

        // Follow-set = "từ state này, những gì có thể xuất hiện tiếp theo" — được tính sẵn
        // (và cache lại) một lần, dùng để trả lời nhanh 2 câu hỏi bên dưới.
        followSetsByState.ensureComputed(parser, start, ignoredTokens);
        FollowSetsHolder followSets = followSetsByState.get(start.stateNumber, ignoredTokens);

        // Ghi lại "mình vừa vào rule này, tại token nào" — phục vụ cho preferredRules về sau.
        RuleCallStack stackWithThisRule = callerStack.copy();
        stackWithThisRule.push(start.ruleIndex, tokenIndex);

        Set<Integer> exits;
        if (isAtCaret(tokenIndex)) {
            // Câu hỏi 1: đã hết token để so chưa? -> chuyển sang chế độ "liệt kê gợi ý"
            exits = handleCaretReachedWhileEnteringRule(start.ruleIndex, tokenIndex, stackWithThisRule, followSets);
        } else if (!canConsumeCurrentToken(tokenIndex, followSets)) {
            // Câu hỏi 2: token tiếp theo không khớp gì cả -> ngõ cụt, cắt nhánh luôn
            exits = Collections.emptySet();
        } else {
            // Còn token để so và khớp được -> đi tiếp bằng BFS trong thân rule
            exits = walkRuleBody(start, tokenIndex, stackWithThisRule);
        }

        exitsByEntryToken.put(tokenIndex, exits);
        return exits;
    }

    /** true nếu đã dùng hết token đã gõ (đang đứng đúng tại vị trí con trỏ). */
    private boolean isAtCaret(int tokenIndex) {
        return tokenIndex >= tokens.size() - 1;
    }

    /** true nếu token hiện tại còn khớp được với những gì rule đang mong đợi tại đây. */
    private boolean canConsumeCurrentToken(int tokenIndex, FollowSetsHolder followSets) {
        int currentTokenType = tokens.get(tokenIndex).type();
        return followSets.combined.contains(Token.EPSILON) || followSets.combined.contains(currentTokenType);
    }

    private Set<Integer> handleCaretReachedWhileEnteringRule(int ruleIndex, int tokenIndex,
                                                             RuleCallStack stack, FollowSetsHolder followSets) {
        collectSuggestionsAtCaret(ruleIndex, stack, followSets);
        // Nếu rule này có thể rỗng (epsilon nằm trong follow-set), coi như nó cũng "thoát"
        // được ngay tại đúng vị trí caret, để rule cha (nếu có) cũng được xét gợi ý tiếp.
        return followSets.combined.contains(Token.EPSILON)
                ? Collections.singleton(tokenIndex)
                : Collections.emptySet();
    }

    // ════════════════════════════════════════════════════════════════════════
    // BƯỚC 2 — Tại vị trí caret: thu thập gợi ý token / rule
    // ════════════════════════════════════════════════════════════════════════

    private void collectSuggestionsAtCaret(int enteringRuleIndex, RuleCallStack stack, FollowSetsHolder followSets) {
        // Nếu rule hiện tại được đánh dấu là "preferred" (người dùng chỉ muốn biết
        // "đang ở trong rule nào" thay vì liệt kê từng token con của nó) -> chốt luôn ở đây.
        if (preferredRules.containsKey(enteringRuleIndex)) {
            resolveToPreferredRule(stack);
            return;
        }

        // followSets.sets đã được tính "full" từ trước (đệ quy xuyên qua mọi rule con
        // cho tới khi chạm token thật — xem collectFollowSets bên dưới), nên ở đây
        // chỉ cần duyệt qua từng nhánh kết quả có sẵn, không cần tự đi sâu thêm nữa.
        for (FollowSetWithPath set : followSets.sets) {
            // Nối "đường đi" riêng của nhánh này (path) vào stack hiện tại, để biết
            // đầy đủ nhánh đó phải xuyên qua những rule con nào mới tới được token.
            RuleCallStack fullPath = stack.copy();
            fullPath.appendPath(set.path);

            if (resolveToPreferredRule(fullPath)) {
                continue; // nhánh này quy về 1 preferred rule rồi -> khỏi liệt kê token trần trụi
            }
            addTokenSuggestions(set);
        }
    }

    private void addTokenSuggestions(FollowSetWithPath set) {
        for (int sym : set.intervals.toList()) {
            if (ignoredTokens.containsKey(sym)) {
                continue;
            }
            if (!candidates.tokens.containsKey(sym)) {
                candidates.tokens.put(sym, new ArrayList<>(set.following));
            } else if (!candidates.tokens.get(sym).equals(set.following)) {
                // Cùng 1 token nhưng đạt được qua nhiều nhánh khác nhau với "following"
                // khác nhau -> không còn chắc chắn phần đuôi theo sau nữa -> xoá về rỗng.
                candidates.tokens.put(sym, Collections.emptyList());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // BƯỚC 3 — Duyệt thân một rule: BFS trên các transition của ATN
    // ════════════════════════════════════════════════════════════════════════

    private Set<Integer> walkRuleBody(ATNState start, int startTokenIndex, RuleCallStack stack) {
        Set<Integer> ruleExitTokenIndices = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<PipelineEntry> queue = new ArrayDeque<>();
        queue.push(new PipelineEntry(start, startTokenIndex, stack));

        while (!queue.isEmpty()) {
            PipelineEntry current = queue.pop();
            if (!visited.add(current.state.stateNumber + ":" + current.tokenIndex())) {
                continue; // cặp (state, tokenIndex) này đã duyệt rồi, khỏi lặp lại
            }

            if (current.state().getStateType() == ATNState.RULE_STOP) {
                // Đây là điểm rule "kết thúc" — ghi nhận vị trí token này là 1 chỗ có thể thoát ra.
                ruleExitTokenIndices.add(current.tokenIndex());
                continue;
            }

            boolean atCaret = isAtCaret(current.tokenIndex());
            if (atCaret) {
                candidates.caretStates.add(current.state());
            }

            // Với mỗi mũi tên (transition) ra khỏi state hiện tại: nếu chưa tới caret thì
            // "token tiếp theo khớp label thì đi tiếp, không khớp thì bỏ qua"; nếu tới caret
            // rồi thì chuyển sang liệt kê label của mũi tên đó làm gợi ý.
            for (Transition t : current.state().getTransitions()) {
                dispatchTransition(t, current, atCaret, queue);
            }
        }
        return ruleExitTokenIndices;
    }

    /** Định tuyến một transition của ATN tới đúng handler theo loại của nó. */
    private void dispatchTransition(Transition t, PipelineEntry current, boolean atCaret, Deque<PipelineEntry> queue) {
        RuleCallStack stack = current.stackSnapshot();

        if (t instanceof RuleTransition rt) {
            handleRuleCall(rt, current, stack, queue);
        } else if (t instanceof PredicateTransition pt) {
            handlePredicate(pt, current, stack, queue);
        } else if (t instanceof WildcardTransition) {
            handleWildcard(t, current, atCaret, stack, queue);
        } else if (t.isEpsilon()) {
            handleEpsilon(t, current, atCaret, stack, queue);
        } else {
            handleTokenMatch(t, current, atCaret, stack, queue);
        }
    }

    // ── Các handler cho từng loại transition ────────────────────────────────

    /**
     * RuleTransition = "gọi rule con". KHÔNG tiêu tốn token nào — chỉ nhảy sang
     * ATN của rule con (đệ quy enterRule), rồi từ mỗi điểm rule con đó thoát ra,
     * tiếp tục hàng đợi tại followState (điểm ngay sau lời gọi rule, trong rule cha).
     */
    private void handleRuleCall(RuleTransition rt, PipelineEntry current, RuleCallStack stack, Deque<PipelineEntry> queue) {
        for (int exitTokenIndex : enterRule(rt.target, current.tokenIndex(), stack)) {
            queue.push(new PipelineEntry(rt.followState, exitTokenIndex, stack));
        }
    }

    private void handlePredicate(PredicateTransition pt, PipelineEntry current, RuleCallStack stack, Deque<PipelineEntry> queue) {
        if (checkPredicate(pt)) {
            queue.push(new PipelineEntry(pt.target, current.tokenIndex(), stack));
        }
    }

    private void handleWildcard(Transition t, PipelineEntry current, boolean atCaret, RuleCallStack stack, Deque<PipelineEntry> queue) {
        if (!atCaret) {
            // Wildcard khớp với bất kỳ token nào -> cứ ăn token hiện tại rồi đi tiếp.
            queue.push(new PipelineEntry(t.target, current.tokenIndex() + 1, stack));
            return;
        }
        if (resolveToPreferredRule(stack)) {
            return;
        }
        // Tại caret: wildcard nghĩa là "gõ token gì cũng được" -> gợi ý luôn toàn bộ token type.
        IntervalSet all = IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
        for (int sym : all.toList()) {
            if (!ignoredTokens.containsKey(sym)) {
                candidates.tokens.putIfAbsent(sym, Collections.emptyList());
            }
        }
    }

    private void handleEpsilon(Transition t, PipelineEntry current, boolean atCaret, RuleCallStack stack, Deque<PipelineEntry> queue) {
        // Epsilon = mũi tên "miễn phí", không ăn token, không đại diện cho lựa chọn gõ gì.
        // Nếu đang ở caret, tranh thủ thử quy về preferred rule (không bắt buộc thành công).
        if (atCaret) {
            resolveToPreferredRule(stack);
        }
        queue.push(new PipelineEntry(t.target, current.tokenIndex(), stack));
    }

    private void handleTokenMatch(Transition t, PipelineEntry current, boolean atCaret, RuleCallStack stack, Deque<PipelineEntry> queue) {
        IntervalSet label = t.label();
        if (label == null || label.size() == 0) {
            return;
        }
        if (t instanceof NotSetTransition) {
            label = label.complement(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
        }

        if (!atCaret) {
            // Còn token thật để so: khớp thì ăn token, tăng tokenIndex, đi tiếp.
            if (label.contains(tokens.get(current.tokenIndex()).type())) {
                queue.push(new PipelineEntry(t.target, current.tokenIndex() + 1, stack));
            }
            return;
        }

        // Tại caret: không so token thật nữa, mà liệt kê nhãn của mũi tên này làm gợi ý.
        if (resolveToPreferredRule(stack)) {
            return;
        }
        List<Integer> syms = label.toList();
        // Nếu nhãn chỉ có đúng 1 token khả dĩ, dò thêm chuỗi token chắc chắn theo sau nó
        // (getFollowingTokens) để IDE có thể tự gõ luôn cả cụm.
        List<Integer> following = syms.size() == 1 ? getFollowingTokens(t) : Collections.emptyList();
        for (int sym : syms) {
            if (!ignoredTokens.containsKey(sym)) {
                candidates.tokens.put(sym, following);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // BƯỚC 4 — Quy một call stack về gợi ý "preferred rule", nếu có
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Quét call stack từ ngoài vào trong (rule cha trước, rule con sau); nếu đường đi
     * này có xuyên qua một preferred rule, ghi nhận rule đó làm candidate (thay vì
     * liệt kê token trần trụi). Trả về true ngay khi tìm thấy preferred rule NGOÀI
     * CÙNG (outermost) đầu tiên — không cần đi sâu hơn vào các rule con bên trong nó.
     */
    private boolean resolveToPreferredRule(RuleCallStack stack) {
        if (preferredRules.isEmpty()) {
            return false;
        }
        List<RuleFrame> frames = stack.frames();
        for (int i = 0; i < frames.size(); i++) {
            RuleFrame frame = frames.get(i);
            if (!preferredRules.containsKey(frame.ruleId)) {
                continue;
            }
            recordPreferredRuleMatch(frame, new ArrayList<>(frames.subList(0, i)));
            return true; // dừng ngay tại match ngoài cùng nhất
        }
        return false;
    }

    private void recordPreferredRuleMatch(RuleFrame frame, List<RuleFrame> pathToRule) {
        Integer existingEntryIndex = candidates.ruleEntryTokenIndex.get(frame.ruleId);
        // Nếu rule này đã được ghi nhận từ trước (qua nhánh khác), chỉ ghi đè khi lần
        // này "liên quan hơn" — ví dụ vào rule ở vị trí token muộn hơn trong input.
        if (isMoreRelevant(frame.tokenIndex(), existingEntryIndex)) {
            candidates.rules.put(frame.ruleId, pathToRule);
            candidates.ruleEntryTokenIndex.put(frame.ruleId, frame.tokenIndex());
        }
    }

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

    // ════════════════════════════════════════════════════════════════════════
    // BƯỚC 5 — Hậu xử lý: đổi khoảng token của preferred rule thành offset trong text
    // ════════════════════════════════════════════════════════════════════════

    private void resolveRuleTextRanges() {
        for (int ruleId : preferredRules.keySet()) {
            Map<Integer, Set<Integer>> exitsByEntryToken = ruleExitCache.get(ruleId);
            if (exitsByEntryToken == null || exitsByEntryToken.isEmpty()) {
                continue;
            }

            // Điểm vào rule ở vị trí muộn nhất (gần caret nhất) trong input.
            int startToken = Collections.max(exitsByEntryToken.keySet());
            Set<Integer> endSet = exitsByEntryToken.get(startToken);
            int endToken = endSet.isEmpty() ? tokens.size() - 1 : Collections.max(endSet);

            candidates.rulePositions.put(ruleId, Arrays.asList(
                    tokens.get(startToken).startPosition(),
                    computeRuleEndOffset(endToken)));
        }
    }

    private int computeRuleEndOffset(int endToken) {
        if (tokens.get(endToken).type() == Token.EOF) {
            // Nếu token cuối là EOF, tính luôn cả khoảng trắng thừa cho tới đó.
            return tokens.get(endToken).startPosition();
        }
        // Ngược lại dừng ngay sau token trước đó, không tính khoảng trắng thừa.
        return tokens.get(Math.max(endToken - 1, 0)).stopPosition() + 1;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Hàm hỗ trợ đọc token stream
    // ════════════════════════════════════════════════════════════════════════

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

    // ════════════════════════════════════════════════════════════════════════
    // Follow set: thông tin "từ state này thì tiếp theo có thể gặp gì", tính sẵn
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Tính follow-set cho một state bắt đầu. Đây là bước duyệt "đầy đủ" (full),
     * không dừng lại ở +1 cấp: hễ gặp RuleTransition (gọi rule con) là LẶN THẲNG
     * vào bên trong rule con đó (vì gọi rule không tốn token nào, xem giải thích ở
     * đầu file), và cứ tiếp tục như vậy xuyên qua bao nhiêu tầng rule cũng được,
     * cho tới khi chạm một token thật (AtomTransition/SetTransition/Wildcard) hoặc
     * rule kết thúc mà không còn chỗ nào để quay lại (follow-set là epsilon).
     * `path` đính kèm mỗi kết quả ghi lại đúng chuỗi rule con đã phải xuyên qua để
     * tới được token đó — dùng sau này để có thể "gộp" gợi ý về một preferred rule
     * dọc theo đường đi, thay vì luôn hiển thị token trần trụi.
     */
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
                // Rule (con) này đã kết thúc nhưng còn "địa chỉ trả về" (đang lồng trong
                // rule cha khác) -> tiếp tục dò từ đúng điểm resume ở rule cha, không dừng.
                Deque<ATNState> rest = new ArrayDeque<>(returnStates);
                ATNState resume = rest.pop();
                collectFollowSets(parser, resume, stop, out, new IdentityHashMap<>(), ruleStack, ignoredTokens, rest);
                return;
            }
            // Không còn nơi nào để quay lại -> rule có thể kết thúc ở đây mà không cần
            // gõ thêm gì (epsilon).
            IntervalSet eps = new IntervalSet();
            eps.add(Token.EPSILON);
            out.add(new FollowSetWithPath(eps, ruleStack.copy(), Collections.emptyList()));
            return;
        }

        for (Transition t : s.getTransitions()) {
            if (t instanceof RuleTransition rt) {
                // Chặn tự-đệ-quy: nếu rule con này đã có mặt trong chính đường đi hiện tại
                // rồi (left-recursion kiểu expr: expr '+' term) thì dừng, tránh đệ quy vô hạn.
                // Đây là một xấp xỉ có chủ đích, đánh đổi để thuật toán dừng được.
                if (ruleStack.contains(rt.target.ruleIndex)) {
                    continue;
                }
                ruleStack.push(rt.target.ruleIndex, RuleFrame.NO_TOKEN);
                Deque<ATNState> nextReturnStates = new ArrayDeque<>(returnStates);
                nextReturnStates.push(rt.followState);
                // Lặn thẳng vào bên trong rule con — đây chính là chỗ "đi full" chứ
                // không dừng lại ở việc chỉ ghi nhận "có gọi 1 rule con ở đây".
                collectFollowSets(parser, t.target, stop, out, new IdentityHashMap<>(), ruleStack, ignoredTokens, nextReturnStates);
                ruleStack.pop();
            } else if (t instanceof PredicateTransition pt) {
                // Lưu ý: eval với ParserRuleContext.EMPTY (ngữ cảnh giả) vì tại đây ta
                // đang "mô phỏng" đi ngang qua ATN chứ chưa thực sự parse tới đó — nếu
                // predicate phụ thuộc ngữ cảnh thực, kết quả có thể không chính xác.
                if (pt.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
                    collectFollowSets(parser, t.target, stop, out, seen, ruleStack, ignoredTokens, returnStates);
                }
            } else if (t instanceof WildcardTransition) {
                out.add(new FollowSetWithPath(IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, parser.getATN().maxTokenType), ruleStack.copy(), Collections.emptyList()));
            } else if (t.isEpsilon()) {
                collectFollowSets(parser, t.target, stop, out, seen, ruleStack, ignoredTokens, returnStates);
            } else {
                // Chạm một token thật -> đây là điểm dừng của nhánh này, ghi nhận kết quả
                // kèm theo path (đường đi qua các rule con) và following (chuỗi token
                // chắc chắn theo sau nếu label chỉ có 1 token duy nhất).
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

    /** Dò chuỗi token chắc chắn đi liền sau 1 token (không rẽ nhánh), để gợi ý gõ luôn cả cụm. */
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

    // Overload dùng trong các transition handler (đã có sẵn ignoredTokens trong scope)
    private List<Integer> getFollowingTokens(Transition t) {
        return getFollowingTokens(t, ignoredTokens);
    }

    private boolean checkPredicate(PredicateTransition t) {
        return t.getPredicate().eval(parser, ParserRuleContext.EMPTY);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Kiểu dữ liệu
    // ════════════════════════════════════════════════════════════════════════

    public record InputToken(int type, int startPosition, int stopPosition) {
    }

    record PipelineEntry(ATNState state, int tokenIndex, RuleCallStack stackSnapshot) {
    }

    public record RuleFrame(int ruleId, int tokenIndex) {
        public static final int NO_TOKEN = -1;
    }

    /**
     * Ngăn xếp (bất biến) ghi lại "đang lồng trong những rule nào, gọi tại token nào".
     * Dùng cấu trúc linked-list chia sẻ (structural sharing) qua copy(): mỗi lần gọi
     * đệ quy giữ một "snapshot" riêng của stack tại thời điểm đó mà không cần clone
     * toàn bộ dữ liệu — push() ở nhánh này không ảnh hưởng tới nhánh khác.
     */
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

        /** Trả về danh sách frame theo thứ tự từ ngoài vào trong (rule cha trước). */
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

        /** "Nhân bản" nhẹ: chỉ chia sẻ lại con trỏ head hiện tại, O(1), không copy sâu. */
        public RuleCallStack copy() {
            return new RuleCallStack(head, size);
        }
    }

    /** Kết quả trả về cho người gọi API: token/rule gợi ý, vị trí trong text, v.v. */
    public static class CandidatesCollection {
        public final Map<Integer, List<Integer>> tokens = new HashMap<>();
        public final Map<Integer, List<RuleFrame>> rules = new HashMap<>();
        public final Map<Integer, Integer> ruleEntryTokenIndex = new HashMap<>();
        public final Map<Integer, List<Integer>> rulePositions = new HashMap<>();
        public final Set<ATNState> caretStates = new HashSet<>();
    }

    public record FollowSetWithPath(IntervalSet intervals, RuleCallStack path, List<Integer> following) {
    }

    public record FollowSetsHolder(List<FollowSetWithPath> sets, IntervalSet combined) {
    }

    /** Cache follow-set theo state, dùng chung giữa nhiều lần gọi collectCandidates() (thread-safe). */
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
            // Đường nhanh: đã có sẵn trong cache rồi thì đọc và thoát ngay.
            lock.readLock().lock();
            try {
                var inner = cache.get(start.stateNumber);
                if (inner != null && inner.containsKey(ignoredTokens)) return;
            } finally {
                lock.readLock().unlock();
            }

            // Đường chậm: chưa có, cần tính — khoá ghi để tránh 2 luồng tính trùng nhau.
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
    }
}