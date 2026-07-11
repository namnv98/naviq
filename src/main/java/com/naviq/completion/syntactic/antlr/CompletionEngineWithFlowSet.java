package com.naviq.completion.syntactic.antlr;

import com.naviq.completion.syntactic.antlr.feature.FollowSetsByState;
import com.naviq.completion.syntactic.antlr.feature.PreferredRuleResolver;
import com.naviq.completion.syntactic.antlr.feature.RuleCallStack;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;


public class CompletionEngineWithFlowSet extends CompletionEngineBase {

    // FEATURE — xem FollowSetsByState.java. Field này chỉ là 1 "tay cầm" gọi
    // ra feature đó; engine core không quan tâm nó tính follow-set thế nào.
    protected final FollowSetsByState followSetsByState = new FollowSetsByState();

    public CompletionEngineWithFlowSet(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        super(parser, ignoredTokens, preferredRules);
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 1 — Bước vào 1 mê cung, tại 1 vị trí lời nói cho trước
    // ════════════════════════════════════════════════════════════════

    /**
     * CHẾ ĐỘ BẬT FOLLOW-SET. Vẫn phải tách 2 case con bên trong theo atCaret
     * — vì cách cache hoạt động HOÀN TOÀN KHÁC NHAU giữa 2 case đó:
     * <p>
     * - CÒN LỜI: an toàn đọc/ghi ruleExitCache theo (ruleIndex, tokenIndex) —
     * không có tác dụng phụ nào phụ thuộc {@code stack} của người gọi ở case này.
     * <p>
     * - TẠI CARET: KHÔNG đọc, KHÔNG ghi cache gì cả — ở đây có tác dụng phụ
     * (ghi nhận preferred rule vào {@code result}, qua handleReachedCaretInsideRule)
     * PHỤ THUỘC {@code stack} riêng của từng người gọi. Nếu đọc cache, chỉ
     * nhánh gọi TRƯỚC mới thật sự chạy và ghi nhận đúng; nhánh gọi SAU nhận
     * nhầm kết quả cache, tác dụng phụ của chính nó KHÔNG BAO GIỜ chạy — đây
     * chính là bug thật đã gặp ({@code "select * from "} mất gợi ý
     * {@code qualified_name} vì {@code func_name} dùng chung {@code colid}
     * gọi trước, cache che mất lượt gọi sau).
     */
    protected Set<Integer> enterRule(ATNState start, int tokenIndex, RuleCallStack stack) {
        if (!isAtCaret(tokenIndex)) {
            Map<Integer, Set<Integer>> exitsByEntryToken = ruleExitCache.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());
            Set<Integer> cached = exitsByEntryToken.get(tokenIndex);
            if (cached != null) {
                return cached;
            }
            exitsByEntryToken.put(tokenIndex, Collections.emptySet()); // chặn đệ quy vô hạn trong lúc tính dở

            RuleCallStack entered = stack.copy();
            entered.push(start.ruleIndex, tokenIndex);

            followSetsByState.ensureComputed(parser, start, ignoredTokens);
            FollowSetsByState.FollowSetsHolder followSets = followSetsByState.get(start.stateNumber, ignoredTokens);
            boolean mayMatch = followSets.combined().contains(Token.EPSILON) || followSets.combined().contains(tokens.get(tokenIndex).type());
            Set<Integer> exits = mayMatch ? walkRuleBody(start, tokenIndex, entered) : Collections.emptySet();

            exitsByEntryToken.put(tokenIndex, exits);
            return exits;
        }

        // TẠI CARET — không đọc, không ghi cache. Không cần chặn đệ quy vô
        // hạn — ANTLR4 không cho phép 1 rule gọi lại chính nó qua đường không
        // tốn token (bị cấm/biên dịch lại thành dạng không lặp lúc build grammar).
        RuleCallStack entered = stack.copy();
        entered.push(start.ruleIndex, tokenIndex);

        followSetsByState.ensureComputed(parser, start, ignoredTokens);
        FollowSetsByState.FollowSetsHolder followSets = followSetsByState.get(start.stateNumber, ignoredTokens);
        handleReachedCaretInsideRule(start.ruleIndex, entered, followSets);
        return followSets.combined().contains(Token.EPSILON)
                ? Collections.singleton(tokenIndex)
                : Collections.emptySet();
    }

    /**
     * Caret rơi ĐÚNG NGAY khi vừa bước vào mê cung {@code ruleIndex} — dùng
     * thẳng follow-set đã tính sẵn để sinh gợi ý, KHÔNG cần dò cửa sống.
     * <p>
     * Nhánh không-đặc-biệt uỷ thác thẳng cho FollowSetsByState — core ở đây
     * không cần biết cấu trúc FollowSetWithPath/path/following là gì cả.
     */
    protected void handleReachedCaretInsideRule(int ruleIndex, RuleCallStack stack, FollowSetsByState.FollowSetsHolder followSets) {
        if (preferredRules.containsKey(ruleIndex)) {
            // FEATURE: gộp về đúng mê cung đặc biệt ngoài cùng (nếu lồng nhau).
            PreferredRuleResolver.resolve(stack, preferredRules, result);
            return;
        }
        FollowSetsByState.generateSuggestionsFromFollowSets(stack, followSets, ignoredTokens, preferredRules, result);
    }

}