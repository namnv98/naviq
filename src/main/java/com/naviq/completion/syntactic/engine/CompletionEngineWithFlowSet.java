package com.naviq.completion.syntactic.engine;

import com.naviq.completion.syntactic.engine.feature.FollowSetsByState;
import com.naviq.completion.syntactic.engine.feature.PreferredRuleResolver;
import com.naviq.completion.syntactic.engine.feature.RuleCallStack;
import com.naviq.completion.syntactic.engine.model.CandidatesResult;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;


public class CompletionEngineWithFlowSet extends CompletionEngineBase {

    // FEATURE — xem FollowSetsByState.java. Field này chỉ là 1 "tay cầm" gọi
    // ra feature đó; engine core không quan tâm nó tính follow-set thế nào.
    protected final FollowSetsByState followSetsByState = new FollowSetsByState();

    public CompletionEngineWithFlowSet(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        super(parser, ignoredTokens, preferredRules);
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 1 — Bước vào 1 mê cung, tại 1 vị trí lời nói cho trước
    // ════════════════════════════════════════════════════════════════

    /**
     * CHẾ ĐỘ BẬT FOLLOW-SET.
     * <p>
     * GỌN LẠI: 3 việc "tính `entered` / đảm bảo follow-set đã có / lấy follow-set
     * ra" giống hệt nhau ở cả 2 nhánh (còn lời / tại caret) — tách ra tính đúng
     * 1 lần, đưa lên TRƯỚC nhánh rẽ, thay vì lặp lại y hệt ở cả 2 nơi.
     * <p>
     * Phần BẮT BUỘC phải tách riêng theo nhánh vẫn giữ nguyên, vì lý do đã nói:
     * cách cache hoạt động HOÀN TOÀN KHÁC NHAU giữa 2 case:
     * <p>
     * - CÒN LỜI: an toàn đọc/ghi {@code ruleExitCache} theo (ruleIndex, tokenIndex) —
     * không có tác dụng phụ nào phụ thuộc {@code stack} của người gọi ở case này.
     * <p>
     * - TẠI CARET: KHÔNG đọc, KHÔNG ghi cache gì cả — ở đây có tác dụng phụ
     * (ghi nhận preferred rule vào {@code result}, qua handleReachedCaretInsideRule)
     * PHỤ THUỘC {@code stack} riêng của từng người gọi. Nếu đọc cache, chỉ
     * nhánh gọi TRƯỚC mới thật sự chạy và ghi nhận đúng; nhánh gọi SAU nhận
     * nhầm kết quả cache, tác dụng phụ của chính nó KHÔNG BAO GIỜ chạy — đây
     * chính là bug thật đã gặp ({@code "select * from "} mất gợi ý
     * {@code qualified_name} vì {@code func_name} dùng chung {@code colid}
     * gọi trước, cache che mất lượt gọi sau).
     */
    protected Set<Integer> enterRule(ATNState start, int tokenIndex, RuleCallStack stack) {
        boolean atCaret = isAtCaret(tokenIndex);

        // Chỉ nhánh "còn lời" mới được đọc/ghi ruleExitCache. Nếu cache hit, trả về ngay — khỏi cần tính entered/follow-set làm gì.
        Map<Integer, Set<Integer>> exitsByEntryToken = null;
        if (!atCaret) {
            exitsByEntryToken = ruleExitCache.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());
            Set<Integer> cached = exitsByEntryToken.get(tokenIndex);
            if (cached != null) {
                return cached;
            }
            exitsByEntryToken.put(tokenIndex, Collections.emptySet()); // chặn vòng lặp vô hạn
        }

        // Từ đây trở xuống, cả 2 nhánh đều cần đúng 3 thứ này như nhau.
        RuleCallStack entered = stack.copy();
        entered.push(start.ruleIndex, tokenIndex);
        followSetsByState.ensureComputed(parser, start, ignoredTokens);
        FollowSetsByState.FollowSetsHolder followSets = followSetsByState.get(start.stateNumber, ignoredTokens);

        if (atCaret) {
            // TẠI CARET — không đọc, không ghi cache (xem lý do ở javadoc trên).
            // Không cần chặn đệ quy vô hạn ở đây: ANTLR4 không cho phép 1 rule
            // gọi lại chính nó qua đường không tốn token (bị cấm lúc build grammar).
            handleReachedCaretInsideRule(start.ruleIndex, entered, followSets);
            return isNullable(followSets) ? Collections.singleton(tokenIndex) : Collections.emptySet();
        }

        boolean mayMatch = isNullable(followSets) || followSets.combined().contains(tokens.get(tokenIndex).type());

        Set<Integer> exits;
        // nếu trinh sát nói chắc chắn "không cửa nào ở đây khớp được với từ tiếp theo" (mayMatch == false),
        // thì khỏi cần gọi walkRuleBody luôn — bỏ qua hẳn việc dò cửa sống, trả emptySet() ngay.
        // Đây là tiết kiệm công
        if (mayMatch) {
            exits = walkRuleBody(start, tokenIndex, entered);
        } else {
            return Collections.emptySet();
        }
        exitsByEntryToken.put(tokenIndex, exits);
        return exits;
    }

    /**
     * Mê cung này có "rỗng" được không — ra khỏi được mà không cần nói thêm
     * gì? Tương đương {@code NullableRuleChecker.canExitWithoutConsumingToken}
     * nhưng đọc thẳng từ follow-set đã tính sẵn, khỏi dò sống lại.
     */
    private static boolean isNullable(FollowSetsByState.FollowSetsHolder followSets) {
        return followSets.combined().contains(Token.EPSILON);
    }

    /**
     * Caret rơi ĐÚNG NGAY khi vừa bước vào mê cung {@code ruleIndex} — dùng
     * thẳng follow-set đã tính sẵn để sinh gợi ý, KHÔNG cần dò cửa sống.
     * <p>
     * Nhánh không-đặc-biệt uỷ thác thẳng cho FollowSetsByState — core ở đây
     * không cần biết cấu trúc FollowSetWithPath/path/following là gì cả.
     */
    protected void handleReachedCaretInsideRule(int ruleIndex, RuleCallStack stack, FollowSetsByState.FollowSetsHolder followSets) {
        // Nếu rule hiện tại là preferred, ưu tiên rule này
        if (preferredRules.containsKey(ruleIndex)) {
            // FEATURE: gộp về đúng mê cung đặc biệt ngoài cùng (nếu lồng nhau).
            PreferredRuleResolver.resolve(stack, preferredRules, result);
            return;
        }
        // Nếu không, dùng follow-set để sinh gợi ý (bên trong nó sẽ kiểm tra preferred rule trên fullPath)
        FollowSetsByState.generateSuggestionsFromFollowSets(stack, followSets, ignoredTokens, preferredRules, result);
    }

    // Hook xử lý preferred rules trong BFS (tại caret)
    @Override
    protected boolean handlePreferredRules(RuleCallStack stack, CandidatesResult result) {
        return PreferredRuleResolver.resolve(stack, preferredRules, result);
    }
}