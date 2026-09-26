package com.naviq.completion.syntactic.engine;

import com.naviq.completion.syntactic.engine.support.FollowSetsByState;
import com.naviq.completion.syntactic.engine.support.FollowSetsByState.FollowSetWithPath;
import com.naviq.completion.syntactic.engine.support.FollowSetsByState.FollowSetsHolder;
import com.naviq.completion.syntactic.engine.support.PreferredRuleResolver;
import com.naviq.completion.syntactic.engine.support.RuleCallStack;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Chế độ dùng follow-set tính trước ({@link FollowSetsByState}): với mỗi state đầu rule, biết sẵn toàn bộ
 * token có thể đi ra từ đó. Nhờ vậy:
 * <ul>
 *   <li>trước caret: nếu token kế tiếp không nằm trong follow-set (và rule không rỗng được) thì bỏ luôn nhánh,
 *       khỏi đi trong thân rule;</li>
 *   <li>tại caret: sinh gợi ý thẳng từ follow-set, không phải đi trong thân rule;</li>
 *   <li>"rule rỗng được không" là tra O(1) (follow-set chứa EPSILON).</li>
 * </ul>
 */
public class CompletionEngineWithFlowSet extends CompletionEngineBase {

    private static final FollowSetsByState followSetsByState = new FollowSetsByState();

    public CompletionEngineWithFlowSet(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        super(parser, ignoredTokens, preferredRules);
    }

    @Override
    protected Set<Integer> computeExitsNotAtCaret(ATNState start, int tokenIndex, RuleCallStack entered) {
        FollowSetsHolder followSets = followSetsOf(start);
        boolean mayMatch = followSets.combined().contains(Token.EPSILON) || followSets.combined().contains(tokens.get(tokenIndex).type());
        return mayMatch ? walkRuleBody(start, tokenIndex, entered) : Collections.emptySet();
    }

    @Override
    protected Set<Integer> computeExitsAtCaret(ATNState start, int tokenIndex, RuleCallStack entered) {
        FollowSetsHolder followSets = followSetsOf(start);
        suggestFromFollowSets(start.ruleIndex, entered, followSets);
        return followSets.combined().contains(Token.EPSILON) ? Collections.singleton(tokenIndex) : Collections.emptySet();
    }

    @Override
    protected boolean isNullable(ATNState state) {
        return followSetsOf(state).combined().contains(Token.EPSILON);
    }

    private FollowSetsHolder followSetsOf(ATNState state) {
        return followSetsByState.getOrCompute(parser, state, ignoredTokens);
    }

    /**
     * Caret vừa chạm rule {@code ruleIndex}. Nếu rule đó là preferred thì ghi thành rule. Ngược lại xét từng
     * đường trong follow-set: đường nào đi qua 1 preferred rule thì ghi thành rule đó, còn lại mới thêm token.
     */
    private void suggestFromFollowSets(int ruleIndex, RuleCallStack stack, FollowSetsHolder followSets) {
        if (preferredRules.containsKey(ruleIndex)) {
            PreferredRuleResolver.resolve(stack, preferredRules, result);
            return;
        }
        for (FollowSetWithPath set : followSets.sets()) {
            RuleCallStack fullPath = stack.copy();
            fullPath.appendPath(set.path());
            if (PreferredRuleResolver.resolve(fullPath, preferredRules, result)) {
                continue;
            }
            for (int type : set.intervals().toList()) {
                if (!ignoredTokens.containsKey(type)) {
                    result.addToken(type, new ArrayList<>(set.following()));
                }
            }
        }
    }
}
