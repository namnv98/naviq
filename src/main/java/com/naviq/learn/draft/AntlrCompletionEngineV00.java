//package com.naviq.completion.draft;
//
//import com.naviq.completion.syntactic.antlr.feature.*;
//import com.naviq.completion.syntactic.antlr.model.CandidatesResult;
//import com.naviq.completion.syntactic.antlr.model.InputToken;
//import org.antlr.v4.runtime.Parser;
//import org.antlr.v4.runtime.ParserRuleContext;
//import org.antlr.v4.runtime.Token;
//import org.antlr.v4.runtime.TokenStream;
//import org.antlr.v4.runtime.atn.*;
//import org.antlr.v4.runtime.misc.IntervalSet;
//
//import java.util.*;
//
///**
// * CORE — chỉ chứa đúng thuật toán "phòng và cửa" (xem ATN_ROOM_DOOR_ANALOGY.md).
// * 4 tính năng thêm vào bản gốc đầy đủ giờ nằm ở FILE RIÊNG, engine này chỉ GỌI
// * RA chúng ở đúng vài điểm nối, không tự cài logic của chúng vào giữa:
// * <p>
// * - FollowSetsByState   : tính trước "từ phòng này, token nào có thể gặp",
// *                         cache thread-safe, dùng để cắt sớm trước khi dò cửa sống
// *                         — VÀ khi caret rơi đúng lúc vừa vào 1 mê cung, dùng
// *                         thẳng luôn để sinh gợi ý (generateSuggestionsFromFollowSets),
// *                         core không cần biết cấu trúc dữ liệu follow-set là gì cả.
// * - PreferredRuleResolver: gộp gợi ý về mê cung đặc biệt NGOÀI CÙNG nếu lồng nhau.
// * - RuleTextRangeResolver: đổi vị trí mê cung đặc biệt thành offset ký tự.
// * - RuleCallStack       : ngăn xếp "đang lồng trong mê cung nào" — dữ liệu
// *                         dùng chung giữa 2 feature outermost/text-range.
// * <p>
// * Đọc file này là đủ để hiểu đúng LÕI thuật toán completion. 4 file kia chỉ
// * cần đọc khi bạn quan tâm tới đúng phần tối ưu/tiện ích tương ứng.
// */
//public class AntlrCompletionEngineV00 {
//
//    private final Parser parser;
//    private final ATN atn;
//
//    private final Map<Integer, Boolean> ignoredTokens;
//    private final Map<Integer, Boolean> preferredRules;
//
//    private List<InputToken> tokens;
//    private int tokenStartIndex;
//
//    private CandidatesResult result;
//    private final Map<Integer, Map<Integer, Set<Integer>>> ruleExitCache = new HashMap<>();
//
//    // FEATURE — xem FollowSetsByState.java. Field này chỉ là 1 "tay cầm" gọi
//    // ra feature đó; engine core không quan tâm nó tính follow-set thế nào.
//    private final FollowSetsByState followSetsByState = new FollowSetsByState();
//
//    private boolean useFollowSets = true;
//
//    public AntlrCompletionEngineV00(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
//        this.parser = parser;
//        this.atn = parser.getATN();
//        this.ignoredTokens = ignoredTokens;
//        this.preferredRules = preferredRules;
//    }
//
//    // ════════════════════════════════════════════════════════════════
//    // ĐIỂM VÀO
//    // ════════════════════════════════════════════════════════════════
//
//    public CandidatesResult collectCandidates(int caretTokenIndex) {
//        return collectCandidates(caretTokenIndex, null);
//    }
//
//    public CandidatesResult collectCandidates(int caretTokenIndex, ParserRuleContext context) {
//        result = new CandidatesResult();
//        ruleExitCache.clear();
//
//        tokenStartIndex = context != null ? context.start.getTokenIndex() : 0;
//        int startRuleIndex = context != null ? context.getRuleIndex() : 0;
//        tokens = readTokens(parser.getTokenStream(), tokenStartIndex, caretTokenIndex);
//
//        enterRule(atn.ruleToStartState[startRuleIndex], 0, new RuleCallStack());
//
//        // FEATURE: RuleTextRangeResolver.java — chạy sau khi mọi thứ đã xong.
//        RuleTextRangeResolver.resolve(preferredRules, ruleExitCache, tokens, result);
//        return result;
//    }
//
//    private boolean isAtCaret(int tokenIndex) {
//        return tokenIndex >= tokens.size() - 1;
//    }
//
//    // ════════════════════════════════════════════════════════════════
//    // BƯỚC 1 — Bước vào 1 mê cung, tại 1 vị trí lời nói cho trước
//    // ════════════════════════════════════════════════════════════════
//
//    /**
//     * Điểm vào duy nhất — chỉ rẽ nhánh, không tự làm gì cả. Tách hẳn 2 case
//     * thành 2 hàm riêng bên dưới vì chúng có cách cache HOÀN TOÀN KHÁC NHAU
//     * (xem javadoc từng hàm), thà lặp vài dòng còn hơn nhồi chung 1 hàm dài.
//     */
//    /**
//     * Rẽ nhánh theo {@code useFollowSets} — 2 CHẾ ĐỘ HOÀN TOÀN TÁCH BIỆT, mỗi
//     * chế độ tự lo cả trường hợp còn lời lẫn tại caret bên trong nó. Lặp lại
//     * vài dòng (push stack, đọc/ghi cache) giữa 2 hàm, đổi lại mỗi hàm đọc
//     * trọn vẹn từ trên xuống dưới cho đúng 1 chế độ, không phải nhảy qua lại.
//     */
//    private Set<Integer> enterRule(ATNState start, int tokenIndex, RuleCallStack stack) {
//        return useFollowSets
//                ? enterRuleWithFollowSets(start, tokenIndex, stack)
//                : enterRuleLiveOnly(start, tokenIndex, stack);
//    }
//
//    /**
//     * CHẾ ĐỘ BẬT FOLLOW-SET. Vẫn phải tách 2 case con bên trong theo atCaret
//     * — vì cách cache hoạt động HOÀN TOÀN KHÁC NHAU giữa 2 case đó:
//     * <p>
//     * - CÒN LỜI: an toàn đọc/ghi ruleExitCache theo (ruleIndex, tokenIndex) —
//     * không có tác dụng phụ nào phụ thuộc {@code stack} của người gọi ở case này.
//     * <p>
//     * - TẠI CARET: KHÔNG đọc, KHÔNG ghi cache gì cả — ở đây có tác dụng phụ
//     * (ghi nhận preferred rule vào {@code result}, qua handleReachedCaretInsideRule)
//     * PHỤ THUỘC {@code stack} riêng của từng người gọi. Nếu đọc cache, chỉ
//     * nhánh gọi TRƯỚC mới thật sự chạy và ghi nhận đúng; nhánh gọi SAU nhận
//     * nhầm kết quả cache, tác dụng phụ của chính nó KHÔNG BAO GIỜ chạy — đây
//     * chính là bug thật đã gặp ({@code "select * from "} mất gợi ý
//     * {@code qualified_name} vì {@code func_name} dùng chung {@code colid}
//     * gọi trước, cache che mất lượt gọi sau).
//     */
//    private Set<Integer> enterRuleWithFollowSets(ATNState start, int tokenIndex, RuleCallStack stack) {
//        if (!isAtCaret(tokenIndex)) {
//            Map<Integer, Set<Integer>> exitsByEntryToken = ruleExitCache.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());
//            Set<Integer> cached = exitsByEntryToken.get(tokenIndex);
//            if (cached != null) {
//                return cached;
//            }
//            exitsByEntryToken.put(tokenIndex, Collections.emptySet()); // chặn đệ quy vô hạn trong lúc tính dở
//
//            RuleCallStack entered = stack.copy();
//            entered.push(start.ruleIndex, tokenIndex);
//
//            followSetsByState.ensureComputed(parser, start, ignoredTokens);
//            FollowSetsByState.FollowSetsHolder followSets = followSetsByState.get(start.stateNumber, ignoredTokens);
//            boolean mayMatch = followSets.combined().contains(Token.EPSILON)
//                    || followSets.combined().contains(tokens.get(tokenIndex).type());
//            Set<Integer> exits = mayMatch ? walkRuleBody(start, tokenIndex, entered) : Collections.emptySet();
//
//            exitsByEntryToken.put(tokenIndex, exits);
//            return exits;
//        }
//
//        // TẠI CARET — không đọc, không ghi cache. Không cần chặn đệ quy vô
//        // hạn — ANTLR4 không cho phép 1 rule gọi lại chính nó qua đường không
//        // tốn token (bị cấm/biên dịch lại thành dạng không lặp lúc build grammar).
//        RuleCallStack entered = stack.copy();
//        entered.push(start.ruleIndex, tokenIndex);
//
//        followSetsByState.ensureComputed(parser, start, ignoredTokens);
//        FollowSetsByState.FollowSetsHolder followSets = followSetsByState.get(start.stateNumber, ignoredTokens);
//        handleReachedCaretInsideRule(start.ruleIndex, entered, followSets);
//        return followSets.combined().contains(Token.EPSILON)
//                ? Collections.singleton(tokenIndex)
//                : Collections.emptySet();
//    }
//
//    /**
//     * CHẾ ĐỘ TẮT FOLLOW-SET: luôn dò cửa sống ({@code walkRuleBody}), không
//     * tra/tính follow-set gì cả. Vẫn tách 2 case con theo atCaret vì LÝ DO
//     * CACHE Y HỆT chế độ trên (xem javadoc {@code enterRuleWithFollowSets}) —
//     * còn lời thì cache an toàn, tại caret thì tuyệt đối không được cache.
//     */
//    private Set<Integer> enterRuleLiveOnly(ATNState start, int tokenIndex, RuleCallStack stack) {
//        if (!isAtCaret(tokenIndex)) {
//            Map<Integer, Set<Integer>> exitsByEntryToken = ruleExitCache.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());
//            Set<Integer> cached = exitsByEntryToken.get(tokenIndex);
//            if (cached != null) {
//                return cached;
//            }
//            exitsByEntryToken.put(tokenIndex, Collections.emptySet());
//
//            RuleCallStack entered = stack.copy();
//            entered.push(start.ruleIndex, tokenIndex);
//
//            Set<Integer> exits = walkRuleBody(start, tokenIndex, entered);
//            exitsByEntryToken.put(tokenIndex, exits);
//            return exits;
//        }
//
//        // TẠI CARET — không đọc, không ghi cache (xem lý do ở enterRuleWithFollowSets).
//        RuleCallStack entered = stack.copy();
//        entered.push(start.ruleIndex, tokenIndex);
//        return walkRuleBody(start, tokenIndex, entered);
//    }
//
//    /**
//     * Caret rơi ĐÚNG NGAY khi vừa bước vào mê cung {@code ruleIndex} — dùng
//     * thẳng follow-set đã tính sẵn để sinh gợi ý, KHÔNG cần dò cửa sống.
//     * <p>
//     * Nhánh không-đặc-biệt uỷ thác thẳng cho FollowSetsByState — core ở đây
//     * không cần biết cấu trúc FollowSetWithPath/path/following là gì cả.
//     */
//    private void handleReachedCaretInsideRule(int ruleIndex, RuleCallStack stack, FollowSetsByState.FollowSetsHolder followSets) {
//        if (preferredRules.containsKey(ruleIndex)) {
//            // FEATURE: gộp về đúng mê cung đặc biệt ngoài cùng (nếu lồng nhau).
//            PreferredRuleResolver.resolve(stack, preferredRules, result);
//            return;
//        }
//        FollowSetsByState.generateSuggestionsFromFollowSets(stack, followSets, ignoredTokens, preferredRules, result);
//    }
//
//    // ════════════════════════════════════════════════════════════════
//    // BƯỚC 2 — Dò từng cửa trong 1 phòng: BFS trên các transition của ATN
//    // ════════════════════════════════════════════════════════════════
//
//    private Set<Integer> walkRuleBody(ATNState start, int startTokenIndex, RuleCallStack stack) {
//        Set<Integer> ruleExits = new HashSet<>();
//        Set<String> visited = new HashSet<>();
//        Deque<PipelineEntry> queue = new ArrayDeque<>();
//        queue.push(new PipelineEntry(start, startTokenIndex, stack));
//
//        while (!queue.isEmpty()) {
//            PipelineEntry cur = queue.pop();
//            if (!visited.add(cur.state().stateNumber + ":" + cur.tokenIndex())) {
//                continue;
//            }
//
//            if (cur.state().getStateType() == ATNState.RULE_STOP) {
//                if (isAtCaret(cur.tokenIndex())) {
//                    PreferredRuleResolver.resolve(cur.stack(), preferredRules, result);
//                }
//                ruleExits.add(cur.tokenIndex());
//                continue;
//            }
//
//            boolean atCaret = isAtCaret(cur.tokenIndex());
//            for (Transition t : cur.state().getTransitions()) {
//                if (t instanceof RuleTransition rt) {
//                    handleRuleDoor(rt, cur, queue);
//                } else if (t instanceof PredicateTransition pt) {
//                    handleFreeDoorWithCondition(pt, cur, queue);
//                } else if (t instanceof WildcardTransition wt) {
//                    handleWildcardDoor(wt, cur, atCaret, queue);
//                } else if (t.isEpsilon()) {
//                    handleFreeDoor(t, cur, queue);
//                } else {
//                    handlePasswordDoor(t, cur, atCaret, queue);
//                }
//            }
//        }
//        return ruleExits;
//    }
//
//    private void handleRuleDoor(RuleTransition rt, PipelineEntry cur, Deque<PipelineEntry> queue) {
//        for (int exitTok : enterRule(rt.target, cur.tokenIndex(), cur.stack())) {
//            queue.push(new PipelineEntry(rt.followState, exitTok, cur.stack()));
//        }
//    }
//
//    private void handleFreeDoorWithCondition(PredicateTransition pt, PipelineEntry cur, Deque<PipelineEntry> queue) {
//        if (pt.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
//            queue.push(new PipelineEntry(pt.target, cur.tokenIndex(), cur.stack()));
//        }
//    }
//
//    private void handleFreeDoor(Transition t, PipelineEntry cur, Deque<PipelineEntry> queue) {
//        queue.push(new PipelineEntry(t.target, cur.tokenIndex(), cur.stack()));
//    }
//
//    /** Cửa "gõ gì cũng được" (dấu `.` trong grammar) — label() của nó luôn null,
//     *  nên cần xử lý riêng thay vì rơi vào handlePasswordDoor. */
//    private void handleWildcardDoor(WildcardTransition t, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
//        if (!atCaret) {
//            queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, cur.stack()));
//            return;
//        }
//        if (PreferredRuleResolver.resolve(cur.stack(), preferredRules, result)) return;
//        IntervalSet all = IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
//        for (int sym : all.toList()) {
//            if (!ignoredTokens.containsKey(sym)) {
//                result.tokens.putIfAbsent(sym, Collections.emptyList());
//            }
//        }
//    }
//
//    private void handlePasswordDoor(Transition t, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
//        IntervalSet label = t.label();
//        if (label == null || label.size() == 0) {
//            return;
//        }
//        if (t instanceof NotSetTransition) {
//            label = label.complement(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
//        }
//
//        if (atCaret) {
//            if (PreferredRuleResolver.resolve(cur.stack(), preferredRules, result)) {
//                return;
//            }
//            List<Integer> syms = label.toList();
//            List<Integer> following = syms.size() == 1 ? FollowingTokensFinder.getFollowingTokens(t, ignoredTokens) : Collections.emptyList();
//            for (int sym : syms) {
//                if (ignoredTokens.containsKey(sym)) {
//                    continue;
//                }
//                if (!result.tokens.containsKey(sym)) {
//                    result.tokens.put(sym, following);
//                } else if (!result.tokens.get(sym).equals(following)) {
//                    result.tokens.put(sym, Collections.emptyList());
//                }
//            }
//        } else if (label.contains(tokens.get(cur.tokenIndex()).type())) {
//            queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, cur.stack()));
//        }
//    }
//
//    // ════════════════════════════════════════════════════════════════
//    // Đọc trước "những lời đã nói" — token từ tokenStartIndex tới caret
//    // ════════════════════════════════════════════════════════════════
//
//    private static List<InputToken> readTokens(TokenStream stream, int tokenStartIndex, int caretTokenIndex) {
//        int saved = stream.index();
//        stream.seek(tokenStartIndex);
//        List<InputToken> result = new ArrayList<>();
//        for (int i = 1; ; i++) {
//            var t = stream.LT(i);
//            result.add(new InputToken(t.getType(), t.getStartIndex(), t.getStopIndex()));
//            if (t.getTokenIndex() >= caretTokenIndex || t.getType() == Token.EOF) break;
//        }
//        stream.seek(saved);
//        return result;
//    }
//
//    private record PipelineEntry(ATNState state, int tokenIndex, RuleCallStack stack) {
//    }
//}