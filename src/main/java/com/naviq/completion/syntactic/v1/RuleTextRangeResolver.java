package com.naviq.completion.syntactic.v1;

import org.antlr.v4.runtime.Token;

import java.util.*;

/**
 * FEATURE: đổi vị trí token của mỗi mê cung đặc biệt được gợi ý thành offset
 * ký tự thật trong văn bản gốc — hữu ích khi IDE cần biết chính xác nên
 * highlight/thay thế đúng vùng ký tự nào trong editor.
 * <p>
 * KHÔNG thuộc lõi thuật toán — nếu bỏ hẳn, engine vẫn gợi ý đúng rule nào,
 * chỉ là không biết rule đó chiếm từ ký tự nào tới ký tự nào trong câu gốc.
 * Chạy SAU KHI toàn bộ collectCandidates() đã xong (đọc lại ruleExitCache).
 */
public class RuleTextRangeResolver {

    public static void resolve(Map<Integer, Boolean> preferredRules,
                                Map<Integer, Map<Integer, Set<Integer>>> ruleExitCache,
                                List<InputToken> tokens,
                                CandidatesResult result) {
        for (int ruleId : preferredRules.keySet()) {
            Map<Integer, Set<Integer>> exitsByEntryToken = ruleExitCache.get(ruleId);
            if (exitsByEntryToken == null || exitsByEntryToken.isEmpty()) continue;

            // Điểm vào mê cung ở vị trí muộn nhất (gần caret nhất) trong input.
            int startToken = Collections.max(exitsByEntryToken.keySet());
            Set<Integer> endSet = exitsByEntryToken.get(startToken);
            int endToken = endSet.isEmpty() ? tokens.size() - 1 : Collections.max(endSet);

            result.rulePositions.put(ruleId, Arrays.asList(
                    tokens.get(startToken).startPosition(),
                    computeRuleEndOffset(tokens, endToken)));
        }
    }

    private static int computeRuleEndOffset(List<InputToken> tokens, int endToken) {
        if (tokens.get(endToken).type() == Token.EOF) {
            // Token cuối là EOF -> tính luôn cả khoảng trắng thừa cho tới đó.
            return tokens.get(endToken).startPosition();
        }
        // Ngược lại dừng ngay sau token trước đó, không tính khoảng trắng thừa.
        return tokens.get(Math.max(endToken - 1, 0)).stopPosition() + 1;
    }
}
