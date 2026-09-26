package com.naviq.completion.syntactic.engine.support;

import com.naviq.completion.model.CandidatesResult;
import com.naviq.completion.model.InputToken;
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
        // Chỉ tính vị trí cho những rule THẬT SỰ được gợi ý tại caret (đã có
        // trong result.rules/ruleEntryTokenIndex), không phải mọi rule nằm
        // trong ruleExitCache — cache này chứa cả những lần enterRule KHÔNG
        // tại caret (ví dụ bảng/alias đã gõ xong trước đó trong câu).
        for (Map.Entry<Integer, Integer> entry : result.ruleEntryTokenIndex.entrySet()) {
            int ruleId = entry.getKey();
            int startToken = entry.getValue();
            if (startToken == RuleCallStack.RuleFrame.NO_TOKEN) continue;

            // Rule được vào ngay tại caret (computeExitsAtCaret) thì không có
            // mặt trong ruleExitCache (cache chỉ ghi ở nhánh !atCaret) -> coi
            // như chưa "ăn" token nào, range rỗng ngay tại vị trí bắt đầu.
            Map<Integer, Set<Integer>> exitsByEntryToken = ruleExitCache.get(ruleId);
            Set<Integer> endSet = exitsByEntryToken == null ? null : exitsByEntryToken.get(startToken);
            int endToken = (endSet == null || endSet.isEmpty()) ? startToken : Collections.max(endSet);

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
