package com.sqlctx.completion.syntactic.engine.support;

import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.Transition;

import java.util.*;

/**
 * Dự đoán chuỗi mật khẩu (token) chắc chắn đi liền sau 1
 * cửa cụ thể, để IDE có thể tự gõ giúp cả cụm 1 lần thay vì bắt người dùng
 * gõ từng từ (ví dụ chọn gợi ý "NOT" thì tự gõ luôn "NOT EXISTS").
 * <p>
 * Chỉ đi theo chuỗi AtomTransition liền nhau (ANTLR nối thẳng các token liên tiếp
 * trong 1 sequence), gặp epsilon/rule/nhánh là dừng — nên luôn kết thúc, không cần visited.
 */
public class FollowingTokensFinder {

    /** Dò chuỗi mật khẩu chắc chắn đi liền sau 1 cửa (không rẽ nhánh nào cả). */
    public static List<Integer> getFollowingTokens(Transition transition, Map<Integer, Boolean> ignoredTokens) {
        List<Integer> result = new ArrayList<>();
        Deque<ATNState> queue = new ArrayDeque<>();
        queue.push(transition.target);
        while (!queue.isEmpty()) {
            for (Transition t : queue.pop().getTransitions()) {
                if (!(t instanceof AtomTransition)) continue;
                List<Integer> syms = t.label().toList();
                if (syms.size() == 1 && !ignoredTokens.containsKey(syms.get(0))) {
                    result.add(syms.get(0));
                    queue.push(t.target);
                }
            }
        }
        return result;
    }
}