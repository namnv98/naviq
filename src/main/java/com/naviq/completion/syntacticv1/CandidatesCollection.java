package com.naviq.completion.syntacticv1;

import org.antlr.v4.runtime.atn.ATNState;

import java.util.*;

/**
 * Kết quả trả về của {@code AntlrCompletionEngine1.collectCandidates}.
 */
public class CandidatesCollection {

    /** Token gợi ý -> danh sách token chắc chắn theo sau nó (rỗng nếu không rõ hoặc bị ambiguous). */
    public final Map<Integer, List<Integer>> tokens = new HashMap<>();

    /** Rule ưu tiên đã tìm thấy -> đường đi các rule cha (outer -> inner) dẫn tới nó. */
    public final Map<Integer, List<RuleFrame>> rules = new HashMap<>();

    /** Rule ưu tiên -> tokenIndex nơi rule đó thực sự bắt đầu (dùng để chọn occurrence phù hợp nhất). */
    public final Map<Integer, Integer> ruleEntryTokenIndex = new HashMap<>();

    /** Rule ưu tiên -> [offset ký tự bắt đầu, offset ký tự kết thúc] trong văn bản gốc. */
    public final Map<Integer, List<Integer>> rulePositions = new HashMap<>();

    /** Tập các ATN state đã từng được thăm đúng tại vị trí caret (phục vụ debug/phân tích thêm). */
    public final Set<ATNState> caretStates = new HashSet<>();
}
