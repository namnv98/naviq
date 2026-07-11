package com.naviq.completion.syntactic.v1;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;

/**
 * CORE — chỉ chứa đúng thuật toán "phòng và cửa" (xem ATN_ROOM_DOOR_ANALOGY.md).
 * 4 tính năng thêm vào bản gốc đầy đủ giờ nằm ở FILE RIÊNG, engine này chỉ GỌI
 * RA chúng ở đúng vài điểm nối, không tự cài logic của chúng vào giữa:
 * <p>
 * - FollowSetsByState   : tính trước "từ phòng này, token nào có thể gặp",
 * cache thread-safe, dùng để cắt sớm trước khi dò cửa sống.
 * - PreferredRuleResolver: gộp gợi ý về mê cung đặc biệt NGOÀI CÙNG nếu lồng nhau.
 * - RuleTextRangeResolver: đổi vị trí mê cung đặc biệt thành offset ký tự.
 * - RuleCallStack       : ngăn xếp "đang lồng trong mê cung nào" — dữ liệu
 * dùng chung giữa 2 feature outermost/text-range.
 * <p>
 * Đọc file này là đủ để hiểu đúng LÕI thuật toán completion. 4 file kia chỉ
 * cần đọc khi bạn quan tâm tới đúng phần tối ưu/tiện ích tương ứng.
 */
public class AntlrCompletionEngineSimpleV3 {

    private final Parser parser;
    private final ATN atn;

    private final Map<Integer, Boolean> ignoredTokens;
    private final Map<Integer, Boolean> preferredRules;

    private List<InputToken> tokens;
    private int tokenStartIndex;

    private CandidatesResult result;
    private final Map<Integer, Map<Integer, Set<Integer>>> ruleExitCache = new HashMap<>();

    // FEATURE — xem FollowSetsByState.java. Field này chỉ là 1 "tay cầm" gọi
    // ra feature đó; engine core không quan tâm nó tính follow-set thế nào.
    private final FollowSetsByState followSetsByState = new FollowSetsByState();

    public AntlrCompletionEngineSimpleV3(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        this.parser = parser;
        this.atn = parser.getATN();
        this.ignoredTokens = ignoredTokens;
        this.preferredRules = preferredRules;
    }

    // ════════════════════════════════════════════════════════════════
    // ĐIỂM VÀO
    // ════════════════════════════════════════════════════════════════

    public CandidatesResult collectCandidates(int caretTokenIndex) {
        return collectCandidates(caretTokenIndex, null);
    }

    public CandidatesResult collectCandidates(int caretTokenIndex, ParserRuleContext context) {
        result = new CandidatesResult();
        ruleExitCache.clear();

        tokenStartIndex = context != null ? context.start.getTokenIndex() : 0;
        int startRuleIndex = context != null ? context.getRuleIndex() : 0;
        tokens = readTokens(parser.getTokenStream(), tokenStartIndex, caretTokenIndex);

        enterRule(atn.ruleToStartState[startRuleIndex], 0, new RuleCallStack());

        // FEATURE: RuleTextRangeResolver.java — chạy sau khi mọi thứ đã xong.
        RuleTextRangeResolver.resolve(preferredRules, ruleExitCache, tokens, result);
        return result;
    }

    private boolean isAtCaret(int tokenIndex) {
        return tokenIndex >= tokens.size() - 1;
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 1 — Bước vào 1 mê cung, tại 1 vị trí lời nói cho trước
    // ════════════════════════════════════════════════════════════════

    private Set<Integer> enterRule(ATNState start, int tokenIndex, RuleCallStack stack) {
        Map<Integer, Set<Integer>> exitsByEntryToken = ruleExitCache.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());
        Set<Integer> cached = exitsByEntryToken.get(tokenIndex);
        if (cached != null) {
            return cached;
        }
        exitsByEntryToken.put(tokenIndex, Collections.emptySet());

        // FEATURE: tra (và tính nếu chưa có) follow-set của phòng này.
        followSetsByState.ensureComputed(parser, start, ignoredTokens);
        FollowSetsByState.FollowSetsHolder followSets = followSetsByState.get(start.stateNumber, ignoredTokens);

        RuleCallStack entered = stack.copy();
        entered.push(start.ruleIndex, tokenIndex);

        Set<Integer> exits;
        if (isAtCaret(tokenIndex)) {
            handleReachedCaretInsideRule(start.ruleIndex, entered, followSets);
            // Nullable đọc thẳng từ follow-set đã tính (chứa cờ EPSILON nếu có
            // đường ra khỏi mê cung mà không cần token nào) — không cần dò lại.
            exits = followSets.combined().contains(Token.EPSILON)
                    ? Collections.singleton(tokenIndex)
                    : Collections.emptySet();
        } else {
            boolean mayMatch = followSets.combined().contains(Token.EPSILON)
                    || followSets.combined().contains(tokens.get(tokenIndex).type());
            exits = mayMatch ? walkRuleBody(start, tokenIndex, entered) : Collections.emptySet();
        }

        exitsByEntryToken.put(tokenIndex, exits);
        return exits;
    }

    /**
     * Caret rơi ĐÚNG NGAY khi vừa bước vào mê cung {@code ruleIndex} — dùng
     * thẳng follow-set đã tính sẵn để sinh gợi ý, KHÔNG cần dò cửa sống.
     */
    private void handleReachedCaretInsideRule(int ruleIndex, RuleCallStack stack, FollowSetsByState.FollowSetsHolder followSets) {
        if (preferredRules.containsKey(ruleIndex)) {
            // FEATURE: gộp về đúng mê cung đặc biệt ngoài cùng (nếu lồng nhau).
            PreferredRuleResolver.resolve(stack, preferredRules, result);
            return;
        }
        for (FollowSetsByState.FollowSetWithPath set : followSets.sets()) {
            RuleCallStack fullPath = stack.copy();
            fullPath.appendPath(set.path());
            if (PreferredRuleResolver.resolve(fullPath, preferredRules, result)) {
                continue; // nhánh này quy về 1 mê cung đặc biệt rồi -> khỏi liệt kê token trần trụi
            }
            addTokenSuggestions(set);
        }
    }

    private void addTokenSuggestions(FollowSetsByState.FollowSetWithPath set) {
        for (int sym : set.intervals().toList()) {
            if (ignoredTokens.containsKey(sym)) continue;
            if (!result.tokens.containsKey(sym)) {
                result.tokens.put(sym, new ArrayList<>(set.following()));
            } else if (!result.tokens.get(sym).equals(set.following())) {
                result.tokens.put(sym, Collections.emptyList());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 2 — Dò từng cửa trong 1 phòng: BFS trên các transition của ATN
    // ════════════════════════════════════════════════════════════════

    private Set<Integer> walkRuleBody(ATNState start, int startTokenIndex, RuleCallStack stack) {
        Set<Integer> ruleExits = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<PipelineEntry> queue = new ArrayDeque<>();
        queue.push(new PipelineEntry(start, startTokenIndex, stack));

        while (!queue.isEmpty()) {
            PipelineEntry cur = queue.pop();
            if (!visited.add(cur.state().stateNumber + ":" + cur.tokenIndex())) {
                continue;
            }

            if (cur.state().getStateType() == ATNState.RULE_STOP) {
                // MỚI: caret có thể chạm ĐÚNG lúc "hết mê cung" này xảy ra GIỮA
                // CHỪNG 1 lượt walkRuleBody đang chạy dở (ví dụ vừa thoát 1 mê
                // cung con optional, resume ngay vào rule cha rồi rule cha đó
                // cũng hết luôn tại đúng caret) — trường hợp này KHÔNG đi qua
                // enterRule() lần nào nữa, nên handleReachedCaretInsideRule()
                // không có cơ hội chạy. Phải tự check ngay tại đây.
                //
                // Dùng thẳng cur.stack() (đã có sẵn đầy đủ đường đi outer->inner
                // tại đúng thời điểm này) thay vì chỉ check start.ruleIndex —
                // nhờ vậy nếu có nhiều mê cung đặc biệt lồng nhau, vẫn tự động
                // chọn đúng cái NGOÀI CÙNG, giống hệt logic ở handlePasswordDoor.
                if (isAtCaret(cur.tokenIndex())) {
                    PreferredRuleResolver.resolve(cur.stack(), preferredRules, result);
                }
                ruleExits.add(cur.tokenIndex());
                continue;
            }

            boolean atCaret = isAtCaret(cur.tokenIndex());
            for (Transition t : cur.state().getTransitions()) {
                if (t instanceof RuleTransition rt) {
                    handleRuleDoor(rt, cur, queue);
                } else if (t instanceof PredicateTransition pt) {
                    handleFreeDoorWithCondition(pt, cur, queue);
                } else if (t instanceof WildcardTransition) {
                    handleWildcardDoor(cur, atCaret, queue);
                } else if (t.isEpsilon()) {
                    handleFreeDoor(t, cur, queue);
                } else {
                    handlePasswordDoor(t, cur, atCaret, queue);
                }
            }
        }
        return ruleExits;
    }

    private void handleRuleDoor(RuleTransition rt, PipelineEntry cur, Deque<PipelineEntry> queue) {
        for (int exitTok : enterRule(rt.target, cur.tokenIndex(), cur.stack())) {
            queue.push(new PipelineEntry(rt.followState, exitTok, cur.stack()));
        }
    }

    private void handleFreeDoorWithCondition(PredicateTransition pt, PipelineEntry cur, Deque<PipelineEntry> queue) {
        if (pt.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
            queue.push(new PipelineEntry(pt.target, cur.tokenIndex(), cur.stack()));
        }
    }

    private void handleFreeDoor(Transition t, PipelineEntry cur, Deque<PipelineEntry> queue) {
        queue.push(new PipelineEntry(t.target, cur.tokenIndex(), cur.stack()));
    }

    /**
     * Cửa "gõ gì cũng được" (dấu `.` trong grammar) — label() của nó luôn null,
     * nên cần xử lý riêng thay vì rơi vào handlePasswordDoor.
     */
    private void handleWildcardDoor(PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
        if (!atCaret) {
            queue.push(new PipelineEntry(cur.state().getTransitions()[0].target, cur.tokenIndex() + 1, cur.stack()));
            return;
        }
        if (PreferredRuleResolver.resolve(cur.stack(), preferredRules, result)) return;
        IntervalSet all = IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
        for (int sym : all.toList()) {
            if (!ignoredTokens.containsKey(sym)) {
                result.tokens.putIfAbsent(sym, Collections.emptyList());
            }
        }
    }

    private void handlePasswordDoor(Transition t, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
        IntervalSet label = t.label();
        if (label == null || label.size() == 0) return;
        if (t instanceof NotSetTransition) {
            label = label.complement(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
        }

        if (atCaret) {
            if (PreferredRuleResolver.resolve(cur.stack(), preferredRules, result)) return;
            List<Integer> syms = label.toList();
            // FEATURE: chỉ đúng 1 lựa chọn -> dò chuỗi mật khẩu chắc chắn theo sau.
            List<Integer> following = syms.size() == 1
                    ? FollowSetsByState.getFollowingTokens(t, ignoredTokens)
                    : Collections.emptyList();
            for (int sym : syms) {
                if (!ignoredTokens.containsKey(sym)) {
                    result.tokens.put(sym, following);
                }
            }
        } else if (label.contains(tokens.get(cur.tokenIndex()).type())) {
            queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, cur.stack()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Đọc trước "những lời đã nói" — token từ tokenStartIndex tới caret
    // ════════════════════════════════════════════════════════════════

    private static List<InputToken> readTokens(TokenStream stream, int tokenStartIndex, int caretTokenIndex) {
        int saved = stream.index();
        stream.seek(tokenStartIndex);
        List<InputToken> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            var t = stream.LT(i);
            result.add(new InputToken(t.getType(), t.getStartIndex(), t.getStopIndex()));
            if (t.getTokenIndex() >= caretTokenIndex || t.getType() == Token.EOF) break;
        }
        stream.seek(saved);
        return result;
    }

    private record PipelineEntry(ATNState state, int tokenIndex, RuleCallStack stack) {
    }
}