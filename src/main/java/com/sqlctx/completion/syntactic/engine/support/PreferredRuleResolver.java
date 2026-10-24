package com.sqlctx.completion.syntactic.engine.support;

import com.sqlctx.completion.model.CandidatesResult;

import java.util.*;

/**
 * Khi caret rơi vào 1 rule "ưu tiên" (preferredRules, ví dụ tên bảng, tên cột), ghi rule đó thành gợi ý
 * thay vì liệt kê từng token bên trong nó.
 */
public class PreferredRuleResolver {

    /**
     * Quét {@code stack} từ rule NGOÀI CÙNG vào trong; gặp preferred rule đầu tiên thì ghi vào {@code result}
     * và dừng ngay (không xét các preferred rule lồng sâu hơn).
     *
     * @return true nếu đã tìm thấy và ghi nhận 1 preferred rule.
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
            return true;
        }
        return false;
    }

    private static void recordIfMoreRelevant(int ruleId, List<RuleCallStack.RuleFrame> pathToRule, int tokenIndex, CandidatesResult result) {
        Integer existingEntryIndex = result.ruleEntryTokenIndex.get(ruleId);
        if (isMoreRelevant(tokenIndex, existingEntryIndex)) {
            result.rules.put(ruleId, pathToRule);
            result.ruleEntryTokenIndex.put(ruleId, tokenIndex);
        }
    }

    /**
     * Preferred rule được chạm tới từ nhiều nhánh: chỉ ghi đè khi lần này "liên quan hơn" — vào rule ở token muộn hơn.
     * Quy ước cho {@link RuleCallStack.RuleFrame#NO_TOKEN} giữ nguyên như cũ (xem các MatchedRuleResolver dùng nó).
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
        return candidateTokenIndex > existingTokenIndex;
    }
}
