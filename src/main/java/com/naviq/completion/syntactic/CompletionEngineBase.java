package com.naviq.completion.syntactic;

import com.naviq.completion.syntactic.feature.FollowingTokensFinder;
import com.naviq.completion.syntactic.feature.NullableRuleChecker;
import com.naviq.completion.syntactic.feature.PreferredRuleResolver;
import com.naviq.completion.syntactic.feature.RuleCallStack;
import com.naviq.completion.syntactic.feature.RuleTextRangeResolver;
import com.naviq.completion.syntactic.model.CandidatesResult;
import com.naviq.completion.syntactic.model.InputToken;
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
 * cache thread-safe, dùng để cắt sớm trước khi dò cửa sống
 * — VÀ khi caret rơi đúng lúc vừa vào 1 mê cung, dùng thẳng luôn để sinh gợi ý (generateSuggestionsFromFollowSets),
 * core không cần biết cấu trúc dữ liệu follow-set là gì cả.
 * - PreferredRuleResolver: gộp gợi ý về mê cung đặc biệt NGOÀI CÙNG nếu lồng nhau.
 * - RuleTextRangeResolver: đổi vị trí mê cung đặc biệt thành offset ký tự.
 * - RuleCallStack       : ngăn xếp "đang lồng trong mê cung nào" — dữ liệu
 * dùng chung giữa 2 feature outermost/text-range.
 * <p>
 * Đọc file này là đủ để hiểu đúng LÕI thuật toán completion. 4 file kia chỉ
 * cần đọc khi bạn quan tâm tới đúng phần tối ưu/tiện ích tương ứng.
 */
public abstract class CompletionEngineBase {

    protected final Parser parser;
    protected final ATN atn;

    protected final Map<Integer, Boolean> ignoredTokens;
    protected final Map<Integer, Boolean> preferredRules;

    protected List<InputToken> tokens;
    protected int tokenStartIndex;

    protected CandidatesResult result;
    protected final Map<Integer, Map<Integer, Set<Integer>>> ruleExitCache = new HashMap<>();

    public CompletionEngineBase(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
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

    protected boolean isAtCaret(int tokenIndex) {
        return tokenIndex >= tokens.size() - 1;
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 1 — Bước vào 1 mê cung, tại 1 vị trí lời nói cho trước
    // ════════════════════════════════════════════════════════════════

    /**
     * Rẽ nhánh theo {@code useFollowSets} — 2 CHẾ ĐỘ HOÀN TOÀN TÁCH BIỆT, mỗi
     * chế độ tự lo cả trường hợp còn lời lẫn tại caret bên trong nó. Lặp lại
     * vài dòng (push stack, đọc/ghi cache) giữa 2 hàm, đổi lại mỗi hàm đọc
     * trọn vẹn từ trên xuống dưới cho đúng 1 chế độ, không phải nhảy qua lại.
     */
    protected abstract Set<Integer> enterRule(ATNState start, int tokenIndex, RuleCallStack stack);

    /**
     * Hook để xử lý preferred rules.
     * @return true nếu đã xử lý (thêm rule vào result) và không cần thêm token nữa.
     */
    protected boolean handlePreferredRules(RuleCallStack stack, CandidatesResult result) {
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 2 — Dò từng cửa trong 1 phòng: BFS trên các transition của ATN
    // ════════════════════════════════════════════════════════════════

    protected Set<Integer> walkRuleBody(ATNState start, int startTokenIndex, RuleCallStack stack) {
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
                if (isAtCaret(cur.tokenIndex())) {
                    handlePreferredRules(cur.stack(), result);
                }
                ruleExits.add(cur.tokenIndex());
                continue;
            }

            boolean atCaret = isAtCaret(cur.tokenIndex());
            for (Transition t : cur.state().getTransitions()) {
                if (t instanceof RuleTransition rt) {
                    handleRuleDoor(rt, cur, atCaret, queue);
                } else if (t instanceof PredicateTransition pt) {
                    handleFreeDoorWithCondition(pt, cur, queue);
                } else if (t instanceof WildcardTransition wt) {
                    handleWildcardDoor(wt, cur, atCaret, queue);
                } else if (t.isEpsilon()) {
                    handleFreeDoor(t, cur, queue);
                } else {
                    handlePasswordDoor(t, cur, atCaret, queue);
                }
            }
        }
        return ruleExits;
    }

    /**
     * SỬA: nối {@code PreferredRuleResolver.recordMatch} vào đúng chỗ comment
     * của nó mô tả — TRƯỚC KHI đệ quy vào {@code enterRule(rt.target, ...)}.
     * <p>
     * Ý tưởng: nếu đang ở ĐÚNG caret (không còn token nào để tiêu thụ nữa) VÀ
     * ta đã biết trước {@code rt.target.ruleIndex} là 1 preferred-rule, thì
     * việc đệ quy vào bên trong nó (walkRuleBody/enterRule đầy đủ) là THỪA:
     * theo đúng ngữ nghĩa "quy về preferred-rule NGOÀI CÙNG NHẤT", bất kể bên
     * trong nó có preferred-rule con nào khác hay không, kết quả cuối cùng
     * VẪN LÀ chính {@code rt.target.ruleIndex} này (vì nó đã là match ngoài
     * cùng nhất tại điểm này rồi). Ta chỉ cần:
     * <p>
     * 1) Ghi nhận match ngay lập tức via {@code recordMatch} — khỏi phải
     * dựng lại {@code fullPath} rồi quét lại từ đầu như {@code resolve} làm.
     * 2) Xác định rule đó có "rỗng" được không ({@code canExitWithoutConsumingToken})
     * để biết caller (walkRuleBody đang chờ ở {@code cur}) có nên tiếp tục đi
     * qua {@code rt.followState} hay dừng hẳn ở đây (chưa nói hết câu, không
     * đủ để hoàn thành rule con này).
     * <p>
     * Nếu KHÔNG ở tại caret, hoặc {@code rt.target.ruleIndex} không phải
     * preferred, quay lại đường đi bình thường: đệ quy {@code enterRule} như
     * trước — vì lúc này còn phải thật sự tiêu thụ token để biết đi tiếp được
     * hay ngõ cụt, "preferred hay không" không giúp bỏ qua bước đó được.
     */
    protected void handleRuleDoor(RuleTransition rt, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
        if (atCaret && preferredRules.containsKey(rt.target.ruleIndex)) {
            PreferredRuleResolver.recordMatch(rt.target.ruleIndex, cur.stack(), cur.tokenIndex(), result);
            if (NullableRuleChecker.canExitWithoutConsumingToken(parser, rt.target)) {
                queue.push(new PipelineEntry(rt.followState, cur.tokenIndex(), cur.stack()));
            }
            // Không nullable -> rule con này còn "nợ" ít nhất 1 token, không
            // thể hoàn thành ngay tại caret -> không push gì thêm, dừng ở đây.
            return;
        }

        for (int exitTok : enterRule(rt.target, cur.tokenIndex(), cur.stack())) {
            queue.push(new PipelineEntry(rt.followState, exitTok, cur.stack()));
        }
    }

    protected void handleFreeDoorWithCondition(PredicateTransition pt, PipelineEntry cur, Deque<PipelineEntry> queue) {
        if (pt.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
            queue.push(new PipelineEntry(pt.target, cur.tokenIndex(), cur.stack()));
        }
    }

    protected void handleFreeDoor(Transition t, PipelineEntry cur, Deque<PipelineEntry> queue) {
        queue.push(new PipelineEntry(t.target, cur.tokenIndex(), cur.stack()));
    }

    /**
     * Cửa "gõ gì cũng được" (dấu `.` trong grammar) — label() của nó luôn null,
     * nên cần xử lý riêng thay vì rơi vào handlePasswordDoor.
     */
    protected void handleWildcardDoor(WildcardTransition t, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
        if (!atCaret) {
            queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, cur.stack()));
            return;
        }
        if (handlePreferredRules(cur.stack(), result)) {
            return;
        }
        IntervalSet all = IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
        for (int sym : all.toList()) {
            if (!ignoredTokens.containsKey(sym)) {
                result.tokens.putIfAbsent(sym, Collections.emptyList());
            }
        }
    }

    protected void handlePasswordDoor(Transition t, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
        IntervalSet label = t.label();
        if (label == null || label.size() == 0) {
            return;
        }
        if (t instanceof NotSetTransition) {
            label = label.complement(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
        }

        if (atCaret) {
            if (handlePreferredRules(cur.stack(), result)) {
                return;
            }
            List<Integer> syms = label.toList();
            List<Integer> following = syms.size() == 1 ? FollowingTokensFinder.getFollowingTokens(t, ignoredTokens) : Collections.emptyList();
            for (int sym : syms) {
                if (ignoredTokens.containsKey(sym)) {
                    continue;
                }
                if (!result.tokens.containsKey(sym)) {
                    result.tokens.put(sym, following);
                } else if (!result.tokens.get(sym).equals(following)) {
                    result.tokens.put(sym, Collections.emptyList());
                }
            }
        } else if (label.contains(tokens.get(cur.tokenIndex()).type())) {
            queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, cur.stack()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Đọc trước "những lời đã nói" — token từ tokenStartIndex tới caret
    // ════════════════════════════════════════════════════════════════
    protected static List<InputToken> readTokens(TokenStream stream, int tokenStartIndex, int caretTokenIndex) {
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

    protected record PipelineEntry(ATNState state, int tokenIndex, RuleCallStack stack) {
    }
}