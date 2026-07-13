package com.naviq.completion.syntactic.engine;

import com.naviq.completion.syntactic.engine.feature.FollowSetsByState;
import com.naviq.completion.syntactic.engine.feature.PreferredRuleResolver;
import com.naviq.completion.syntactic.engine.feature.RuleCallStack;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNState;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * CHẾ ĐỘ BẬT FOLLOW-SET. Sau khi gộp khung {@code enterRule} chung lên
 * {@code CompletionEngineBase}, file này giờ CHỈ còn đúng phần thật sự khác
 * biệt so với {@code CompletionEngineDefault}: cách tính "exits" dựa vào
 * follow-set đã tính trước, thay vì luôn dò cửa sống.
 */
public class CompletionEngineWithFlowSet extends CompletionEngineBase {

    // FEATURE — xem FollowSetsByState.java. Field này chỉ là 1 "tay cầm" gọi
    // ra feature đó; engine core không quan tâm nó tính follow-set thế nào.
    protected final FollowSetsByState followSetsByState = new FollowSetsByState();

    public CompletionEngineWithFlowSet(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        super(parser, ignoredTokens, preferredRules);
    }

    /**
     * Còn lời để nói: tra follow-set trước — nếu chắc chắn token kế tiếp
     * không khớp đâu cả (và cũng không nullable), khỏi cần gọi walkRuleBody
     * cho tốn công lặn qua bao nhiêu mê cung con.
     */
    @Override
    protected Set<Integer> computeExitsNotAtCaret(ATNState start, int tokenIndex, RuleCallStack entered) {
        followSetsByState.ensureComputed(parser, start, ignoredTokens);
        FollowSetsByState.FollowSetsHolder followSets = followSetsByState.get(start.stateNumber, ignoredTokens);

        boolean mayMatch = followSets.combined().contains(Token.EPSILON) || followSets.combined().contains(tokens.get(tokenIndex).type());
        return mayMatch ? walkRuleBody(start, tokenIndex, entered) : Collections.emptySet();
    }

    /**
     * Đúng tại caret: dùng thẳng follow-set đã tính sẵn để sinh gợi ý, KHÔNG
     * cần dò cửa sống.
     */
    @Override
    protected Set<Integer> computeExitsAtCaret(ATNState start, int tokenIndex, RuleCallStack entered) {
        followSetsByState.ensureComputed(parser, start, ignoredTokens);
        FollowSetsByState.FollowSetsHolder followSets = followSetsByState.get(start.stateNumber, ignoredTokens);

        handleReachedCaretInsideRule(start.ruleIndex, entered, followSets);
        return followSets.combined().contains(Token.EPSILON) ? Collections.singleton(tokenIndex) : Collections.emptySet();
    }

    /**
     * Override hook của Base: đọc thẳng {@code combined().contains(EPSILON)}
     * từ follow-set đã tính sẵn (O(1)) — thay vì dò sống như bản mặc định
     * ({@code NullableRuleChecker}) mà {@code Default} đang dùng. Đây chính
     * là lý do 2 chế độ tồn tại 2 cách trả lời khác nhau cho cùng 1 câu hỏi:
     * WithFlowSet đã buộc phải tính follow-set cho state đó rồi (để check
     * mayMatch), nên đọc luôn từ đó là miễn phí; Default không hề có gì để
     * đọc, phải dò sống.
     */
    @Override
    protected boolean isNullable(ATNState state) {
        followSetsByState.ensureComputed(parser, state, ignoredTokens);
        return followSetsByState.get(state.stateNumber, ignoredTokens).combined().contains(Token.EPSILON);
    }

    /**
     * Caret rơi ĐÚNG NGAY khi vừa bước vào mê cung {@code ruleIndex} — dùng
     * thẳng follow-set đã tính sẵn để sinh gợi ý, KHÔNG cần dò cửa sống.
     * <p>
     * Nhánh không-đặc-biệt uỷ thác thẳng cho FollowSetsByState — core ở đây
     * không cần biết cấu trúc FollowSetWithPath/path/following là gì cả.
     */
    private void handleReachedCaretInsideRule(int ruleIndex, RuleCallStack stack, FollowSetsByState.FollowSetsHolder followSets) {
        if (preferredRules.containsKey(ruleIndex)) {
            // FEATURE: gộp về đúng mê cung đặc biệt ngoài cùng (nếu lồng nhau).
            PreferredRuleResolver.resolve(stack, preferredRules, result);
            return;
        }
        FollowSetsByState.generateSuggestionsFromFollowSets(stack, followSets, ignoredTokens, preferredRules, result);
    }
}