package com.naviq.completion.model;

import com.naviq.completion.syntactic.engine.support.RuleCallStack;

import java.util.*;

/**
 * Kết quả gợi ý tại caret.
 * <ul>
 *   <li>{@link #tokens}: loại token -> chuỗi token chắc chắn đi liền sau nó (rỗng nếu không chắc).</li>
 *   <li>{@link #rules}: preferred rule -> đường gọi (các rule bao ngoài) tới nó.</li>
 *   <li>{@link #ruleEntryTokenIndex}: preferred rule -> token index lúc vào rule đó.</li>
 * </ul>
 */
public class CandidatesResult {
    public final Map<Integer, List<Integer>> tokens = new HashMap<>();
    public final Map<Integer, List<RuleCallStack.RuleFrame>> rules = new HashMap<>();
    public final Map<Integer, Integer> ruleEntryTokenIndex = new HashMap<>();

    /** Thêm 1 token gợi ý; nếu token đã có với chuỗi {@code following} khác thì bỏ chuỗi đó (không chắc nữa). */
    public void addToken(int tokenType, List<Integer> following) {
        List<Integer> existing = tokens.get(tokenType);
        if (existing == null) {
            tokens.put(tokenType, following);
        } else if (!existing.equals(following)) {
            tokens.put(tokenType, Collections.emptyList());
        }
    }
}
