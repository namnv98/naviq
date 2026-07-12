package com.naviq.completion.syntactic.model;

import com.naviq.completion.syntactic.feature.RuleCallStack;

import java.util.*;

/**
 * Kết quả trả về: token/rule gợi ý, kèm vị trí trong text (rulePositions, do RuleTextRangeResolver điền vào).
 */
public class CandidatesResult {
    public final Map<Integer, List<Integer>> tokens = new HashMap<>();
    public final Map<Integer, List<RuleCallStack.RuleFrame>> rules = new HashMap<>();
    public final Map<Integer, Integer> ruleEntryTokenIndex = new HashMap<>();
    public final Map<Integer, List<Integer>> rulePositions = new HashMap<>();
}
