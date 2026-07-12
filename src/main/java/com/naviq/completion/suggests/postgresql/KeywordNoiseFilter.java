package com.naviq.completion.suggests.postgresql;

import com.naviq.antlr4.postgresql.PostgreSQLParser;
import com.naviq.completion.syntactic.feature.RuleCallStack;
import com.naviq.completion.syntactic.PostgreSQLSyntacticAnalyzer;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.*;

/**
 * Lọc bỏ các preferred-rule "sống sót giả" khỏi kết quả matched rules của AntlrCompletionEngine -
 * tức các rule chỉ còn match tại caret vì đang đứng bên trong 1 optional-tail (indirection?,
 * opt_alias_clause?, tablesample_clause?...) của chính rule đó hoặc của 1 rule anh em (sibling) đã
 * thực sự hoàn tất, KHÔNG phải vì đây là 1 vị trí thực sự cần gợi ý loại đó.
 * <p>
 * Thay thế hoàn toàn cách tiếp cận cũ (so token type của token cuối cùng trước caret với 1 danh
 * sách "COLID_TERMINAL_TOKENS") - cách đó về bản chất không thể đúng, vì nhiều keyword cấu trúc
 * (BY, SET, COLUMN...) có CÙNG token type với 1 identifier hợp lệ.
 * <p>
 * Áp dụng 2 cơ chế độc lập, cả 2 đều dựa trên CẤU TRÚC ATN đã đi qua (path + tokenIndex), không suy
 * đoán từ token type - trừ đúng 1 ngoại lệ an toàn: kiểm tra token cuối có phải DOT hay không (DOT
 * không đa nghĩa như AND/BY/SET nên kiểm tra type ở đây không lặp lại lỗi cũ).
 * <p>
 * 1. SIBLING-BRANCH SUPPRESSION: 2 preferred rule cùng match, chia sẻ chung 1 ancestor nhưng rẽ
 * nhánh ở 2 rule con khác nhau (sibling, không phải cha/con) - nhánh có tokenIndex NHỎ HƠN tại điểm
 * rẽ là tàn dư, bị suppress. Ví dụ: qualified_name (qua relation_expr) vs table_alias (qua
 * opt_alias_clause), cùng dưới table_ref.
 * <p>
 * 2. GENUINE-CONTINUATION SUPPRESSION: 1 rule đã thực sự ENTER (traversal thật, không phải
 * followSets-path tĩnh) ở tokenIndex <= lastReal - đã "nuốt" identifier vừa gõ, giờ chỉ sống nhờ
 * optional-tail phía sau. CHỈ áp dụng khi: có khoảng trắng thật giữa lastReal và caret, VÀ lastReal
 * không phải DOT (nếu là DOT, chắc chắn còn đang chờ gõ tiếp attr_name, không bao giờ được suppress
 * - ví dụ "o." đang chờ tên cột của alias o).
 */
public class KeywordNoiseFilter {

    public KeywordNoiseFilter() {
    }

    /**
     * Điểm vào duy nhất - trả về tập tên rule cuối cùng nên dùng để quyết định gợi ý, đã lọc bỏ các
     * rule "sống sót giả".
     *
     * @param cursorOffset vị trí caret tính theo KÝ TỰ (character offset) trong sql gốc - bắt buộc
     *                     phải truyền vì whitespace bị {@code skip()} hoàn toàn trong lexer, không
     *                     để lại token nào (kể cả hidden channel) để phát hiện gap chỉ bằng
     *                     tokenIndex.
     */
    public static Set<String> computeMatchedRuleNames(PostgreSQLSyntacticAnalyzer.Result syn, int cursorOffset) {
        var candidates = syn.candidates();
        Map<Integer, List<RuleCallStack.RuleFrame>> rulesMatched = candidates.rules;
        Map<Integer, Integer> ruleEntryTokenIndex = candidates.ruleEntryTokenIndex;

        CommonTokenStream ts = syn.tokenStream();
        int caretTokenIndex = syn.caretTokenIndex();

        LastRealInfo lastRealInfo = findLastReal(ts, caretTokenIndex);
        int lastRealTokenIndex = lastRealInfo == null ? -1 : lastRealInfo.compactIndex();
        Token lastReal = lastRealInfo == null ? null : lastRealInfo.token();
        boolean hasGapBeforeCaret = computeHasGapBeforeCaret(lastReal, cursorOffset);
        boolean rightAfterDot = lastReal != null && lastReal.getType() == PostgreSQLParser.DOT;

        // TOP-LEVEL GATE: cả 2 cơ chế suppress (sibling-branch VÀ
        // genuine-continuation) CHỈ được phép hoạt động khi user THỰC SỰ đã
        // "đóng" xong token trước đó (có khoảng trắng thật + không phải đang
        // đứng ngay sau DOT chờ gõ tiếp).
        // <p>
        // Bắt buộc phải gộp chung 1 cổng thay vì để 2 cơ chế chạy độc lập:
        // table_ref: relation_expr opt_alias_clause? ... là cấu trúc TUẦN
        // TỰ, nên opt_alias_clause LUÔN được ATN khám phá ở tokenIndex >=
        // tokenIndex mà relation_expr được entered - MỘT CÁCH MÁY MÓC, bất
        // kể relation_expr đã thực sự "xong" theo ý user hay chưa. Ví dụ
        // "select * from public." (mới gõ xong dấu chấm, relation_expr entered
        // ở token3): opt_alias_clause vẫn được khám phá ở token4/5 chỉ vì ATN
        // thử MỌI khả năng hoàn thành sớm của indirection?, KHÔNG phải vì đây
        // là interpretation đúng. Nếu để sibling-branch suppression
        // (laterBranchWins) chạy không điều kiện, nó sẽ LUÔN thiên vị rule
        // đứng SAU trong ngữ pháp (opt_alias_clause/table_alias) một cách sai
        // lầm bất cứ khi nào 2 rule sibling có quan hệ tuần tự kiểu này -
        // suppress nhầm qualified_name dù đây chính là candidate cần giữ để
        // trigger gợi ý bảng theo schema.
        if (!hasGapBeforeCaret || rightAfterDot) {
            return allRuleNames(rulesMatched.keySet()); // chưa đủ tín hiệu "đã đóng" - không suppress gì
        }

        Set<Integer> suppressed = new HashSet<>();
        suppressed.addAll(computeSuppressedBySiblingBranch(rulesMatched));
        suppressed.addAll(computeSuppressedByGenuineContinuation(rulesMatched.keySet(), lastRealTokenIndex, ruleEntryTokenIndex));

        Set<String> result = new HashSet<>();
        for (Integer ruleIndex : rulesMatched.keySet()) {
            if (suppressed.contains(ruleIndex)) {
                continue;
            }
            result.add(PostgreSQLParser.ruleNames[ruleIndex]);
        }
        return result;
    }

    private static Set<String> allRuleNames(Set<Integer> ruleIds) {
        Set<String> result = new HashSet<>();
        for (Integer ruleIndex : ruleIds) {
            result.add(PostgreSQLParser.ruleNames[ruleIndex]);
        }
        return result;
    }


    /**
     * Trả về Token object của token thật cuối cùng trước caret (dùng để check type == DOT và
     * getStopIndex() cho gap-detection) - lấy bằng cách duyệt raw stream lùi từ caretRawTokenIndex,
     * KHÔNG cần quy đổi thang đo vì đây chỉ dùng token OBJECT, không dùng chỉ số của nó.
     */
    private record LastRealInfo(Token token, int compactIndex) {}

    private static LastRealInfo findLastReal(CommonTokenStream ts, int caretRawTokenIndex) {
        Token found = null;
        int compactIndex = -1;
        for (int i = 0; i < caretRawTokenIndex; i++) {
            Token t = ts.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (t.getType() == Token.EOF) continue;
            found = t;
            compactIndex++;
        }
        return found == null ? null : new LastRealInfo(found, compactIndex);
    }

    /**
     * True nếu có ít nhất 1 ký tự (thường là whitespace) nằm giữa cuối lastReal token và vị trí
     * caret - tính theo OFFSET KÝ TỰ, không phải tokenIndex, vì whitespace bị {@code skip()} hoàn
     * toàn trong lexer nên không để lại token nào (kể cả hidden channel) để so tokenIndex được. Nếu
     * {@code lastReal == null} (chưa gõ gì) → false.
     */
    private static boolean computeHasGapBeforeCaret(Token lastReal, int cursorOffset) {
        if (lastReal == null) {
            return false;
        }
        return lastReal.getStopIndex() + 1 < cursorOffset;
    }

    // ── 1. Sibling-branch suppression ───────────────────────────────────────

    private static Set<Integer> computeSuppressedBySiblingBranch(
        Map<Integer, List<RuleCallStack.RuleFrame>> rules) {
        Set<Integer> suppressed = new HashSet<>();
        List<Map.Entry<Integer, List<RuleCallStack.RuleFrame>>> entries = new ArrayList<>(rules.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                var a = entries.get(i);
                var b = entries.get(j);
                Integer res = laterBranchWins(a.getValue(), b.getValue());
                if (res == null) {
                    continue;
                }
                if (res < 0) {
                    suppressed.add(b.getKey());
                } else {
                    suppressed.add(a.getKey());
                }
            }
        }
        return suppressed;
    }

    /**
     * So sánh 2 ancestor path bằng cách tìm điểm rẽ nhánh (longest common ruleId prefix), rồi so
     * sánh tokenIndex tại điểm rẽ đó. Nhánh có tokenIndex NHỎ HƠN bị coi là tàn dư. Trả về null nếu
     * 2 path không thực sự rẽ nhánh, hoặc tokenIndex bằng nhau / là sentinel NO_TOKEN (không đủ rõ
     * ràng để quyết định).
     */
    private static Integer laterBranchWins(List<RuleCallStack.RuleFrame> pathA, List<RuleCallStack.RuleFrame> pathB) {
        int lcp = 0;
        int minLen = Math.min(pathA.size(), pathB.size());
        while (lcp < minLen && pathA.get(lcp).ruleId() == pathB.get(lcp).ruleId()) {
            lcp++;
        }
        if (lcp >= pathA.size() || lcp >= pathB.size()) {
            return null;
        }

        int tokenA = pathA.get(lcp).tokenIndex();
        int tokenB = pathB.get(lcp).tokenIndex();
        if (tokenA == RuleCallStack.RuleFrame.NO_TOKEN || tokenB == RuleCallStack.RuleFrame.NO_TOKEN) {
            return null;
        }
        if (tokenA == tokenB) {
            return null;
        }
        return tokenA > tokenB ? -1 : 1;
    }

    // ── 2. Genuine-continuation suppression ─────────────────────────────────
    // NOTE: gap/DOT gating is done ONCE at the top level in
    // computeMatchedRuleNames before this is ever called - by the time we
    // get here, we already know the caret genuinely sits after a closed
    // token (real gap, not mid-dot).

    private static Set<Integer> computeSuppressedByGenuineContinuation(
        Set<Integer> matchedRuleIds, int lastRealTokenIndex,
        Map<Integer, Integer> ruleEntryTokenIndex) {
        Set<Integer> suppressed = new HashSet<>();
        if (lastRealTokenIndex < 0) {
            return suppressed; // chưa gõ gì
        }
        for (int ruleId : matchedRuleIds) {
            if (isGenuineContinuation(ruleId, lastRealTokenIndex, ruleEntryTokenIndex)) {
                suppressed.add(ruleId);
            }
        }
        return suppressed;
    }

    /**
     * True nếu {@code ruleId} đã thực sự được ENTER (traversal thật, không phải followSets-path
     * tĩnh - entry tokenIndex là sentinel {@link RuleFrame#NO_TOKEN} khi đến từ followSets-path)
     * tại 1 tokenIndex <= lastRealTokenIndex - tức nó đã tiêu thụ chính token user vừa gõ, và giờ
     * chỉ còn "sống" nhờ 1 optional-tail phía sau. Gap/DOT đã được gate ở tầng gọi
     * (computeMatchedRuleNames), không cần check lại ở đây.
     */
    private static boolean isGenuineContinuation(int ruleId, int lastRealTokenIndex,
        Map<Integer, Integer> ruleEntryTokenIndex) {
        Integer enteredAt = ruleEntryTokenIndex.get(ruleId);
        if (enteredAt == null) {
            return false;
        }
        if (enteredAt == RuleCallStack.RuleFrame.NO_TOKEN) {
            return false; // sentinel, không phải entry thật
        }
        return enteredAt <= lastRealTokenIndex;
    }
}