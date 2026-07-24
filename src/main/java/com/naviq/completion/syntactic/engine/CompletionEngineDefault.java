package com.naviq.completion.syntactic.engine;

import com.naviq.completion.syntactic.engine.feature.RuleCallStack;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.Transition;

import java.util.*;

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
        return canExitWithoutConsumingToken(parser, state);
    }

    /**
     * true nếu, xuất phát từ {@code start}, có thể đi tới RULE_STOP mà không
     * cần khớp bất kỳ token nào — chỉ qua epsilon, predicate (đánh giá true),
     * hoặc RuleTransition (đệ quy hỏi lại đúng câu hỏi này cho rule con).
     * Cửa mật khẩu (Atom/Set/NotSet/Wildcard) bị bỏ qua vì đi qua nó bắt buộc
     * phải tốn 1 token.
     */
    public static boolean canExitWithoutConsumingToken(Parser parser, ATNState start) {
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
            for (Transition transition : s.getTransitions()) {
                if (transition instanceof RuleTransition ruleTransition) {
                    if (canExitWithoutConsumingToken(parser, ruleTransition.target)) {
                        queue.push(ruleTransition.followState);
                    }
                } else if (transition instanceof PredicateTransition predicateTransition) {
                    if (predicateTransition.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
                        queue.push(transition.target);
                    }
                } else if (transition.isEpsilon()) {
                    queue.push(transition.target);
                }
                // cửa mật khẩu -> bỏ qua, nhánh này bắt buộc phải nói thêm
            }
        }
        return false;
    }

}