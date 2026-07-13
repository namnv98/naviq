package com.naviq.completion.syntactic.engine.feature;

import com.naviq.completion.syntactic.engine.model.CandidatesResult;

import java.util.*;

/**
 * FEATURE: khi caret rơi vào 1 mê cung "đặc biệt" (preferredRules), ghi nhận nó làm gợi ý thay vì
 * liệt kê từng token trần trụi bên trong.
 * <p>
 * SỬA LẦN NÀY: thêm {@link #recordMatch}, dùng bởi {@code handleRuleDoor} - nơi ĐÃ TỰ BIẾT
 * {@code rt.target.ruleIndex} khớp preferredRules TRƯỚC KHI đệ quy vào rule đó (xem
 * CompletionEngineBase), nên không cần quét lại {@code frames} tìm match như {@link #resolve}.
 * <p>
 * {@link #resolve} vẫn giữ nguyên - dùng làm lưới an toàn dự phòng cho rule GỐC (index 0, được
 * gọi trực tiếp từ collectCandidates(), KHÔNG đi qua bất kỳ RuleTransition/handleRuleDoor nào cả,
 * nên không có cơ hội chặn sớm) và cho các đường BFS cũ (RULE_STOP, password-door, wildcard-door)
 * vẫn còn gọi tới nó như trước.
 */
public class PreferredRuleResolver {

    /**
     * Quét {@code stack} từ mê cung NGOÀI CÙNG vào trong; nếu tìm thấy 1 mê
     * cung đặc biệt trên đường đi, ghi nhận nó vào {@code result} rồi dừng
     * NGAY — không xét các mê cung đặc biệt nằm sâu hơn bên trong nó.
     *
     * @return true nếu đã tìm thấy và ghi nhận 1 mê cung đặc biệt trên đường đi.
     */
    public static boolean resolve(RuleCallStack stack, Map<Integer, Boolean> preferredRules, CandidatesResult result) {
        if (preferredRules.isEmpty()) {
            return false;
        }
        List<RuleCallStack.RuleFrame> frames = stack.frames();
        for (int i = 0; i < frames.size(); i++) {
            RuleCallStack.RuleFrame frame = frames.get(i);
            if (!preferredRules.containsKey(frame.ruleId())) {
                continue;
            }
            List<RuleCallStack.RuleFrame> pathToRule = new ArrayList<>(frames.subList(0, i));
            recordIfMoreRelevant(frame.ruleId(), pathToRule, frame.tokenIndex(), result);
            return true; // dừng ngay tại match ngoài cùng nhất
        }
        return false;
    }

    /**
     * Ghi nhận trực tiếp {@code ruleId} làm 1 mê cung đặc biệt đã khớp - dùng khi caller (
     * {@code handleRuleDoor}) ĐÃ TỰ XÁC ĐỊNH rule này khớp preferredRules, không cần quét lại.
     * {@code stack} truyền vào là chuỗi tổ tiên TRƯỚC KHI push chính rule này (đúng ý nghĩa
     * "đường đi dẫn TỚI rule đặc biệt", khớp với {@code pathToRule} trong {@link #resolve}).
     */
    public static void recordMatch(int ruleId, RuleCallStack stack, int tokenIndex, CandidatesResult result) {
        List<RuleCallStack.RuleFrame> pathToRule = new ArrayList<>(stack.frames());
        recordIfMoreRelevant(ruleId, pathToRule, tokenIndex, result);
    }

    private static void recordIfMoreRelevant(int ruleId, List<RuleCallStack.RuleFrame> pathToRule, int tokenIndex, CandidatesResult result) {
        Integer existingEntryIndex = result.ruleEntryTokenIndex.get(ruleId);
        if (isMoreRelevant(tokenIndex, existingEntryIndex)) {
            result.rules.put(ruleId, pathToRule);
            result.ruleEntryTokenIndex.put(ruleId, tokenIndex);
        }
    }

    /**
     * Khi 1 mê cung đặc biệt được chạm tới từ nhiều nhánh khác nhau, chỉ ghi
     * đè nếu lần này "liên quan hơn" — ví dụ vào rule ở vị trí token muộn hơn.
     */
    private static boolean isMoreRelevant(int candidateTokenIndex, Integer existingTokenIndex) {
        if (existingTokenIndex == null) {
            return true;
        }
        if (candidateTokenIndex == RuleCallStack.RuleFrame.NO_TOKEN) {
            return existingTokenIndex != RuleCallStack.RuleFrame.NO_TOKEN;
        }
        if (existingTokenIndex == RuleCallStack.RuleFrame.NO_TOKEN) {
            return false;
        }
        return candidateTokenIndex > existingTokenIndex; // ưu tiên tokenIndex MUỘN HƠN
    }
}