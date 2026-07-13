package com.naviq.completion.syntactic.engine.feature;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.Transition;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * FEATURE: trả lời đúng 1 câu hỏi — xuất phát từ 1 {@code ATNState} cho trước,
 * có thể "thoát ra" (chạm RULE_STOP) mà KHÔNG CẦN khớp thêm bất kỳ token nào
 * không (tức là rule đó có "nullable" hay không)?
 * <p>
 * KHÔNG thuộc lõi thuật toán — giống {@code FollowSetsByState}, class này
 * hoàn toàn ĐỘC LẬP với engine: chỉ cần (Parser, ATNState), không đụng tới
 * tokens đã gõ, ruleExitCache, RuleCallStack hay preferredRules/ignoredTokens
 * của engine. Được dùng bởi {@code CompletionEngineBase.handleRuleDoor} ở
 * nhánh shortcut preferred-rule: khi đã biết chắc 1 RuleTransition dẫn tới 1
 * preferred-rule ngay tại caret, ta khỏi cần dò cửa đầy đủ bên trong rule đó
 * (walkRuleBody/enterRule) — chỉ cần biết nó có rỗng được không, để quyết
 * định caller có nên tiếp tục BFS qua {@code followState} hay dừng hẳn.
 * <p>
 * LƯU Ý: kết quả này thật ra TRÙNG với thông tin đã có sẵn trong follow-set
 * của {@link FollowSetsByState} — 1 rule nullable khi và chỉ khi
 * {@code combined.contains(Token.EPSILON)} là true (xem
 * {@code FollowSetsByState.collectFollowSets}, nhánh chạm RULE_STOP luôn
 * thêm 1 entry mang {@code Token.EPSILON}). Engine nào ĐÃ dùng
 * {@code FollowSetsByState} (như {@code CompletionEngineWithFlowSet}) nên ưu
 * tiên tra thẳng {@code combined.contains(Token.EPSILON)} thay vì gọi hàm ở
 * đây, để khỏi tính lại 2 lần cùng 1 thông tin bằng 2 cách khác nhau. Class
 * này chỉ thật sự cần thiết cho engine KHÔNG có follow-set cache (như
 * {@code CompletionEngineDefault}), nơi không có gì để tra sẵn cả.
 */
public class NullableRuleChecker {

    private NullableRuleChecker() {
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