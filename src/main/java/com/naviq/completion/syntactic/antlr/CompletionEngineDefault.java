package com.naviq.completion.syntactic.antlr;

import com.naviq.completion.syntactic.antlr.feature.RuleCallStack;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.atn.ATNState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;


public class CompletionEngineDefault extends CompletionEngineBase {

    public CompletionEngineDefault(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        super(parser, ignoredTokens, preferredRules);
    }

    /**
     * CHẾ ĐỘ TẮT FOLLOW-SET: luôn dò cửa sống ({@code walkRuleBody}), không
     * tra/tính follow-set gì cả. Vẫn tách 2 case con theo atCaret vì LÝ DO
     * CACHE Y HỆT chế độ trên (xem javadoc {@code enterRuleWithFollowSets}) —
     * còn lời thì cache an toàn, tại caret thì tuyệt đối không được cache.
     */
    protected Set<Integer> enterRule(ATNState start, int tokenIndex, RuleCallStack stack) {
        if (!isAtCaret(tokenIndex)) {
            Map<Integer, Set<Integer>> exitsByEntryToken = ruleExitCache.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());
            Set<Integer> cached = exitsByEntryToken.get(tokenIndex);
            if (cached != null) {
                return cached;
            }
            exitsByEntryToken.put(tokenIndex, Collections.emptySet());

            RuleCallStack entered = stack.copy();
            entered.push(start.ruleIndex, tokenIndex);

            Set<Integer> exits = walkRuleBody(start, tokenIndex, entered);
            exitsByEntryToken.put(tokenIndex, exits);
            return exits;
        }

        // TẠI CARET — không đọc, không ghi cache (xem lý do ở enterRuleWithFollowSets).
        RuleCallStack entered = stack.copy();
        entered.push(start.ruleIndex, tokenIndex);
        return walkRuleBody(start, tokenIndex, entered);
    }

}