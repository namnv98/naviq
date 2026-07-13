package com.naviq.completion.syntactic.engine;

import com.naviq.completion.syntactic.engine.feature.NullableRuleChecker;
import com.naviq.completion.syntactic.engine.feature.RuleCallStack;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.atn.ATNState;

import java.util.Map;
import java.util.Set;

/**
 * CHẾ ĐỘ TẮT FOLLOW-SET: luôn dò cửa sống ({@code walkRuleBody}), không
 * tra/tính follow-set gì cả. Sau khi gộp khung {@code enterRule} (đọc/ghi
 * cache, dựng RuleCallStack) lên {@code CompletionEngineBase}, file này giờ
 * chỉ còn đúng phần khác biệt duy nhất: "cách tính exits" — với chế độ này,
 * luôn luôn là gọi thẳng {@code walkRuleBody}, dù còn lời hay tại caret.
 */
public class CompletionEngineDefault extends CompletionEngineBase {

    public CompletionEngineDefault(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        super(parser, ignoredTokens, preferredRules);
    }

    @Override
    protected Set<Integer> computeExitsNotAtCaret(ATNState start, int tokenIndex, RuleCallStack entered) {
        return walkRuleBody(start, tokenIndex, entered);
    }

    @Override
    protected Set<Integer> computeExitsAtCaret(ATNState start, int tokenIndex, RuleCallStack entered) {
        return walkRuleBody(start, tokenIndex, entered);
    }

    @Override
    protected boolean isNullable(ATNState state) {
        return NullableRuleChecker.canExitWithoutConsumingToken(parser, state);
    }
}