package com.naviq.completion.syntactic.antlr.feature;

import com.naviq.completion.syntactic.antlr.model.CandidatesResult;

import java.util.*;

/**
 * FEATURE: khi caret rơi vào 1 mê cung "đặc biệt" (preferredRules) mà nó lại
 * đang lồng bên trong 1 mê cung đặc biệt KHÁC, gộp gợi ý về đúng cái NGOÀI
 * CÙNG — thay vì báo cả 2, hoặc báo nhầm cái trong cùng.
 * <p>
 * KHÔNG thuộc lõi thuật toán — nếu bỏ hẳn, engine vẫn gợi ý đúng token/rule,
 * chỉ là khi có 2 rule đặc biệt lồng nhau, có thể báo cả 2 (hoặc báo rule
 * trong cùng) thay vì chỉ báo đúng 1 rule ngoài cùng như mong muốn.
 */
public class PreferredRuleResolver {

    /**
     * Quét {@code stack} từ mê cung NGOÀI CÙNG vào trong; nếu tìm thấy 1 mê
     * cung đặc biệt trên đường đi, ghi nhận nó vào {@code result} rồi dừng
     * NGAY — không xét các mê cung đặc biệt nằm sâu hơn bên trong nó.
     *
     * @return true nếu đã tìm thấy và ghi nhận 1 mê cung đặc biệt trên đường đi.
     */
    public static boolean resolve(RuleCallStack stack,
                                  Map<Integer, Boolean> preferredRules,
                                  CandidatesResult result) {
        if (preferredRules.isEmpty()) return false;

        List<RuleCallStack.RuleFrame> frames = stack.frames();
        for (int i = 0; i < frames.size(); i++) {
            RuleCallStack.RuleFrame frame = frames.get(i);
            if (!preferredRules.containsKey(frame.ruleId())) continue;

            List<RuleCallStack.RuleFrame> pathToRule = new ArrayList<>(frames.subList(0, i));
            Integer existingEntryIndex = result.ruleEntryTokenIndex.get(frame.ruleId());
            if (isMoreRelevant(frame.tokenIndex(), existingEntryIndex)) {
                result.rules.put(frame.ruleId(), pathToRule);
                result.ruleEntryTokenIndex.put(frame.ruleId(), frame.tokenIndex());
            }
            return true; // dừng ngay tại match ngoài cùng nhất
        }
        return false;
    }

    /**
     * Khi 1 mê cung đặc biệt được chạm tới từ nhiều nhánh khác nhau, chỉ ghi
     * đè nếu lần này "liên quan hơn" — ví dụ vào rule ở vị trí token muộn hơn.
     */
    private static boolean isMoreRelevant(int candidateTokenIndex, Integer existingTokenIndex) {
        if (existingTokenIndex == null) return true;
        if (candidateTokenIndex == RuleCallStack.RuleFrame.NO_TOKEN) return existingTokenIndex != RuleCallStack.RuleFrame.NO_TOKEN;
        if (existingTokenIndex == RuleCallStack.RuleFrame.NO_TOKEN) return false;
        return candidateTokenIndex > existingTokenIndex;
    }
}
