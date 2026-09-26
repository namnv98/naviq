package com.naviq.completion.syntactic.engine;

import com.naviq.completion.syntactic.engine.support.AtnPredicates;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.Transition;

import java.util.*;

/**
 * Chế độ đơn giản: luôn đi thẳng trong thân rule ({@code walkRuleBody}, là cài đặt mặc định của Base),
 * và tự dò ATN để biết 1 rule có rỗng được không.
 */
public class CompletionEngineDefault extends CompletionEngineBase {

    public CompletionEngineDefault(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        super(parser, ignoredTokens, preferredRules);
    }

    @Override
    protected boolean isNullable(ATNState state) {
        return canReachRuleEndWithoutToken(state);
    }

    /**
     * Từ {@code start} có tới được RULE_STOP chỉ qua epsilon, predicate đúng, hoặc rule con cũng rỗng được không.
     * Transition khớp token (Atom/Set/NotSet/Wildcard) bị bỏ qua vì đi qua nó bắt buộc tốn 1 token.
     */
    private boolean canReachRuleEndWithoutToken(ATNState start) {
        Set<Integer> visited = new HashSet<>();
        Deque<ATNState> queue = new ArrayDeque<>();
        queue.push(start);
        while (!queue.isEmpty()) {
            ATNState s = queue.pop();
            if (!visited.add(s.stateNumber)) {
                continue;
            }
            if (s.getStateType() == ATNState.RULE_STOP) {
                return true;
            }
            for (Transition t : s.getTransitions()) {
                if (t instanceof RuleTransition rt) {
                    if (canReachRuleEndWithoutToken(rt.target)) {
                        queue.push(rt.followState);
                    }
                } else if (t instanceof PredicateTransition pt) {
                    if (AtnPredicates.holds(parser, pt)) {
                        queue.push(pt.target);
                    }
                } else if (t.isEpsilon()) {
                    queue.push(t.target);
                }
            }
        }
        return false;
    }
}
