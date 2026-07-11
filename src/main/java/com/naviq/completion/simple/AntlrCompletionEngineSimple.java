package com.naviq.completion.simple;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;

/**
 * BẢN RÚT GỌN của AntlrCompletionEngine2 — chỉ giữ lại lõi thuật toán để dễ hiểu.
 * <p>
 * CÁCH ĐỌC FILE NÀY: hãy tưởng tượng grammar là 1 tấm bản đồ trò chơi. Mỗi rule là
 * 1 mê cung, mỗi ATNState là 1 căn phòng. Giữa các phòng có 3 loại cửa:
 * <p>
 * 1) Cửa cần MẬT KHẨU (AtomTransition / SetTransition / NotSetTransition / Wildcard)
 * Phải "nói" đúng 1 token thật thì cửa mới mở, và việc nói đó tốn 1 token.
 * <p>
 * 2) Cửa MIỄN PHÍ (epsilon, kể cả PredicateTransition)
 * Cứ bước qua, không cần nói gì, không tốn token nào cả.
 * <p>
 * 3) Cửa VÀO MÊ CUNG CON (RuleTransition)
 * Phải đi hết 1 mê cung nhỏ khác (1 rule khác) trước, xong xuôi mới được
 * quay lại mê cung chính — đúng tại điểm ngay sau cửa (rt.followState), chứ
 * không phải quay lại điểm bắt đầu. Bản thân cửa này không tốn token gì cả;
 * token chỉ bị tốn bởi các cửa mật khẩu NẰM BÊN TRONG mê cung con đó.
 * <p>
 * Việc engine làm chỉ đơn giản là: giả vờ đi trong mê cung đúng theo các token đã
 * gõ. Hễ hết token (tới caret), nó dừng lại, đứng yên tại 1 phòng cụ thể, và nhìn
 * quanh: "phòng này có những cửa nào đang mở?" — tên mật khẩu trên các cửa đó
 * chính là danh sách gợi ý trả về cho người dùng.
 * <p>
 * ĐÃ BỎ so với bản đầy đủ (chỉ mất tối ưu/tiện ích, không ảnh hưởng kết quả đúng sai):
 * - FollowSetsByState: cache follow-set dùng chung nhiều luồng.
 * - resolveRuleTextRanges: đổi token index thành offset trong text gốc.
 * - getFollowingTokens: dự đoán trước cả 1 chuỗi token chắc chắn đi liền nhau.
 * - resolveToPreferredRule kiểu quét "outermost" qua 1 RuleCallStack tường minh —
 * bản này không giữ stack riêng, mà tận dụng luôn thứ tự gọi enterRule() tự
 * nhiên từ ngoài vào trong để đạt hiệu quả tương đương.
 */
public class AntlrCompletionEngineSimple {

    private final Parser parser;
    private final ATN atn;

    // Những token không bao giờ được đưa vào gợi ý, kể cả khi khớp đúng tại caret
    // (ví dụ Identifier, dấu ngoặc, số... — những thứ gợi ý ra cũng vô nghĩa).
    private final Map<Integer, Boolean> ignoredTokens;

    // Những rule được coi là "có ý nghĩa nghiệp vụ" (ví dụ columnref, qualified_name).
    // Hễ caret rơi vào đúng 1 rule như vậy, ta gợi ý luôn cả cái rule đó (kiểu "bạn
    // cần điền 1 tên cột ở đây"), thay vì đi sâu vào bên trong liệt kê từng token
    // trần trụi (ví dụ Identifier) — vốn thường đã nằm trong ignoredTokens rồi.
    private final Map<Integer, Boolean> preferredRules;

    // Toàn bộ token đã đọc trước, từ đầu câu tới caret — nói nôm na là "những lời
    // người dùng đã gõ ra cho tới bây giờ".
    private List<Token> tokens;

    // KẾT QUẢ 1: các token-type được gợi ý (đã loại trừ ignoredTokens). Đây chính
    // là tên mật khẩu trên những cánh cửa đang mở, tại đúng phòng ta dừng lại.
    private final Set<Integer> suggestedTokens = new HashSet<>();

    // KẾT QUẢ 2: các rule-index được gợi ý (những rule nằm trong preferredRules mà
    // caret đang đứng ngay bên trong).
    private final Set<Integer> suggestedRules = new HashSet<>();

    // Bộ nhớ thật theo (ruleIndex, tokenIndex) -> tập vị trí mà mê cung đó thoát ra
    // được. Trong lúc đang tính dở (để chặn vòng lặp vô hạn do left-recursion), ta
    // tạm ghi vào đây 1 kết quả rỗng; tính xong thì ghi đè bằng kết quả thật. Nhờ
    // vậy, nếu có 1 nhánh khác sau này cũng cần đi vào đúng (mê cung, vị trí) này,
    // nó sẽ nhận lại đúng kết quả đã tính, thay vì bị hiểu lầm là ngõ cụt.
    private final Map<Integer, Map<Integer, Set<Integer>>> ruleExitCache = new HashMap<>();

    public AntlrCompletionEngineSimple(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        this.parser = parser;
        this.atn = parser.getATN();
        this.ignoredTokens = ignoredTokens;
        this.preferredRules = preferredRules;
    }

    // ════════════════════════════════════════════════════════════════
    // ĐIỂM VÀO
    // ════════════════════════════════════════════════════════════════

    /**
     * Tính gợi ý tại vị trí {@code caretTokenIndex}.
     * <p>
     * Việc đầu tiên là đọc lại toàn bộ token từ đầu câu tới caret, rồi bước chân
     * vào mê cung chính (rule đầu tiên của grammar, index 0), bắt đầu từ token số 0.
     * Mọi thứ xảy ra sau đó đều là hệ quả của đúng 1 lời gọi enterRule() này.
     */
    public Set<Integer> collectCandidates(int caretTokenIndex) {
        suggestedTokens.clear();
        suggestedRules.clear();
        ruleExitCache.clear();
        tokens = readTokens(parser.getTokenStream(), caretTokenIndex);

        enterRule(atn.ruleToStartState[0], 0);
        return suggestedTokens;
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 1 — Bước vào 1 mê cung, tại 1 vị trí lời nói cho trước
    // ════════════════════════════════════════════════════════════════

    /**
     * Bước vào mê cung bắt đầu từ phòng {@code start}, tại đúng vị trí lời nói
     * {@code tokenIndex}. Trả về tập vị trí mà lần vào này có thể "thoát ra" được
     * — để mê cung cha (nơi đã gọi vào đây, xem handleRuleDoor) biết nên tiếp tục
     * từ đâu sau khi mê cung con này xong việc.
     * <p>
     * Có đúng 2 việc xảy ra ở đây: (1) tra cache trước khi làm gì cả, và (2) nếu
     * chưa có thì mới thật sự đi — hết lời thì nhìn quanh lấy gợi ý, còn lời thì
     * dò cửa để đi tiếp.
     */
    private Set<Integer> enterRule(ATNState start, int tokenIndex) {
        // Đã từng đi vào đúng (mê cung, vị trí) này chưa?
        // Nếu rồi thì khỏi tính lại -> trả thẳng kết quả đã lưu (kể cả khi kết quả đó chỉ là placeholder rỗng đang tính dở).
        Map<Integer, Set<Integer>> exitsByEntryToken = ruleExitCache.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());
        Set<Integer> cached = exitsByEntryToken.get(tokenIndex);
        if (cached != null) {
            return cached;
        }

        // Trước khi đi, đặt tạm kết quả RỖNG vào đây. Đây chính là "cái phanh" chặn vòng lặp vô hạn:
        // nếu trong lúc tính mà lại có 1 nhánh khác quay về đúng (mê cung, vị trí) này (kiểu grammar đệ quy về chính nó),
        // nhánh đó sẽ nhận ngay giá trị tạm này và dừng, thay vì gọi tới gọi lui mãi mãi.
        // Khi tính xong thật sự, ta ghi đè giá trị tạm này bằng kết quả đúng.
        exitsByEntryToken.put(tokenIndex, Collections.emptySet());

        // kiểm tra xem có đang đứng tại caret không, hết lời để nói chưa.
        var atCaret = isAtCaret(tokenIndex);

        Set<Integer> result;
        if (atCaret) {
            result = handleReachedCaretInsideRule(start, tokenIndex); // hết lời -> nhìn quanh lấy gợi ý
        } else {
            result = walkRuleBody(start, tokenIndex); // còn lời -> dò cửa để đi tiếp
        }

        exitsByEntryToken.put(tokenIndex, result); // ghi đè placeholder rỗng bằng kết quả thật
        return result;
    }

    /**
     * Xử lý đúng khoảnh khắc caret rơi vào NGAY khi vừa bước chân vào 1 mê cung —
     * chưa kịp đi thêm bước nào bên trong nó cả. Có đúng 2 khả năng:
     * <p>
     * - Mê cung này "có ý nghĩa nghiệp vụ" (nằm trong preferredRules, ví dụ
     * columnref): ta không thèm liệt kê từng cửa mật khẩu trần trụi bên trong
     * nữa, mà chốt luôn "bạn đang cần điền 1 thứ thuộc loại này".
     * <p>
     * - Mê cung bình thường: vẫn phải dò cửa như mọi khi để biết cửa nào đang mở.
     */
    private Set<Integer> handleReachedCaretInsideRule(ATNState start, int tokenIndex) {
        if (preferredRules.containsKey(start.ruleIndex)) {
            suggestedRules.add(start.ruleIndex);

            // Mê cung cha chỉ được coi là "mê cung con này đã xong việc" nếu mê cung con CÓ THỂ kết thúc ngay tại đây mà không cần nói thêm gì nữa.
            // Nếu không, nó còn nợ ít nhất 1 lời bên trong — mê cung cha KHÔNG được phép sinh gợi ý cho phần đứng ngay sau lời gọi rule đó.
            var nullable = canExitWithoutConsumingToken(start);
            if (nullable) {
                return Collections.singleton(tokenIndex);
            } else {
                return Collections.emptySet();
            }
        }

        // walkRuleBody() ở đây tự biết dừng đúng lúc, vì isAtCaret() đã là true ngay từ đầu (xem nhánh atCaret trong handlePasswordDoor bên dưới).
        // Kết quả trả về LUÔN chỉ có thể là {tokenIndex} hoặc rỗng, vì tại caret không cửa nào làm tăng tokenIndex cả — không có lời nào bị nuốt thêm.
        Set<Integer> exits = walkRuleBody(start, tokenIndex);
        return exits.contains(tokenIndex) ? Collections.singleton(tokenIndex) : Collections.emptySet();
    }

    /**
     * Câu hỏi: đứng ngay tại cửa vào mê cung {@code start}, có thể coi như đã xong,
     * ra khỏi mê cung này luôn, mà KHÔNG CẦN nói thêm bất kỳ lời nào không?
     * <p>
     * Cần hàm riêng cho việc này vì có 1 trường hợp code KHÔNG dò cửa đầy đủ bên
     * trong mê cung (mê cung "đặc biệt" trong preferredRules, xem
     * handleReachedCaretInsideRule) — nhưng vẫn phải biết mê cung đó có thể "rỗng"
     * hay không, để quyết định mê cung cha có được sinh gợi ý cho phần đứng sau
     * lời gọi rule đó hay không.
     * <p>
     * Cách trả lời: đi thử mọi cửa KHÔNG tốn lời (cửa miễn phí, cửa vào mê cung
     * con khác) — nếu chạm được "hết mê cung" (RULE_STOP) thì có, ngược lại thì
     * không. Cửa mật khẩu bị bỏ qua vì đi qua nó bắt buộc phải tốn 1 lời.
     */
    private boolean canExitWithoutConsumingToken(ATNState start) {
        Set<Integer> visited = new HashSet<>();
        Deque<ATNState> queue = new ArrayDeque<>();
        queue.push(start);
        while (!queue.isEmpty()) {
            ATNState s = queue.pop();
            if (!visited.add(s.stateNumber)) {
                continue;
            }
            if (s.getStateType() == ATNState.RULE_STOP) {
                return true; // đã ra khỏi mê cung, không cần nói gì thêm
            }

            for (Transition transition : s.getTransitions()) {
                if (transition instanceof RuleTransition ruleTransition) {
                    // Đệ quy hỏi lại đúng câu hỏi này cho mê cung con — nếu nó cũng "rỗng" được, coi như đi xuyên qua nó miễn phí.
                    if (canExitWithoutConsumingToken(ruleTransition.target)) {
                        queue.push(ruleTransition.followState);
                    }
                } else if (transition instanceof PredicateTransition predicateTransition) {
                    if (predicateTransition.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
                        queue.push(transition.target);
                    }
                } else if (transition.isEpsilon()) {
                    queue.push(transition.target);
                }
                // cửa mật khẩu (không epsilon) -> lờ đi, nhánh này bắt buộc phải nói thêm
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 2 — Dò từng cửa trong 1 phòng: BFS trên các transition của ATN
    // ════════════════════════════════════════════════════════════════

    /**
     * Đi (BFS) qua toàn bộ các phòng có thể tới được bên trong mê cung {@code start},
     * bắt đầu từ vị trí lời nói {@code startTokenIndex}. Với mỗi phòng gặp phải,
     * xét hết các cửa của nó, mỗi loại cửa giao cho đúng 1 người xử lý riêng bên
     * dưới (handleRuleDoor / handleFreeDoorWithCondition / handleFreeDoor /
     * handlePasswordDoor) — hàm này chỉ lo việc điều phối, không tự làm gì cả.
     * <p>
     * LƯU Ý: queue có thể chứa nhiều PipelineEntry CÙNG tokenIndex nhưng KHÁC
     * state (ví dụ khi 1 phòng có nhiều cửa miễn phí dẫn tới nhiều phòng khác
     * nhau cùng lúc — kiểu grammar có dấu "?" hoặc "|"). Mỗi entry được xử lý
     * hoàn toàn độc lập, không biết gì về entry khác. Nếu 2 phòng khác nhau đó
     * tình cờ cùng cần đúng 1 mật khẩu ở đúng tokenIndex đó, CẢ HAI đều qua cửa
     * được — không có cơ chế nào ở đây chọn ra "1 nhánh thắng" cả. Đây chính là
     * lý do đôi khi 1 từ có thể khớp được nhiều cửa cùng lúc, tại nhiều phòng
     * khác nhau (visited chỉ chặn trùng lặp CÙNG 1 phòng, không gộp theo tokenIndex).
     */
    private Set<Integer> walkRuleBody(ATNState start, int startTokenIndex) {
        Set<Integer> ruleExits = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<PipelineEntry> queue = new ArrayDeque<>();
        queue.push(new PipelineEntry(start, startTokenIndex));

        while (!queue.isEmpty()) {
            PipelineEntry cur = queue.pop();
            if (!visited.add(cur.state.stateNumber + ":" + cur.tokenIndex)) {
                continue; // (phòng, vị trí lời nói) này dò rồi, khỏi lặp lại
                // (chỉ chặn trùng CÙNG 1 phòng — 2 phòng khác nhau cùng tokenIndex
                // vẫn được coi là 2 entry riêng, không bị gộp lại ở đây)
            }

            if (cur.state.getStateType() == ATNState.RULE_STOP) {
                ruleExits.add(cur.tokenIndex); // "hết mê cung" -> ghi nhận đây là 1 điểm thoát ra được
                continue;
            }

            boolean atCaret = isAtCaret(cur.tokenIndex);
            for (Transition t : cur.state.getTransitions()) {
                if (t instanceof RuleTransition rt) {
                    handleRuleDoor(rt, cur, queue); // Cửa vào mê cung con
                } else if (t instanceof PredicateTransition pt) {
                    handleFreeDoorWithCondition(pt, cur, queue); // Cửa miễn phí có kèm điều kiện
                } else if (t.isEpsilon()) {
                    handleFreeDoor(t, cur, queue); //  Cửa miễn phí bình thường
                } else {
                    //Cửa cần mật khẩu mọi loại còn lại (ATOM, SET, NOT_SET, RANGE, WILDCARD, PRECEDENCE...) đều là cửa cần mật khẩu
                    handlePasswordDoor(t, cur, atCaret, queue);
                }
            }
        }
        return ruleExits;
    }

    /**
     * Cửa vào mê cung con: bản thân cửa này KHÔNG tốn lời nào. Ta đi hết mê cung
     * con đó trước (gọi lại enterRule), rồi bất kể nó thoát ra ở những vị trí nào,
     * luôn tiếp tục hàng đợi BFS từ đúng followState — điểm ngay sau cửa, trong
     * mê cung chính (không phải quay lại điểm bắt đầu của mê cung con).
     */
    private void handleRuleDoor(RuleTransition rt, PipelineEntry cur, Deque<PipelineEntry> queue) {
        for (int exitTok : enterRule(rt.target, cur.tokenIndex)) {
            queue.push(new PipelineEntry(rt.followState, exitTok));
        }
    }

    /**
     * Cửa miễn phí có kèm điều kiện (semantic predicate): điều kiện đúng thì mở, không tốn lời nào.
     */
    private void handleFreeDoorWithCondition(PredicateTransition pt, PipelineEntry cur, Deque<PipelineEntry> queue) {
        // Dùng "hoàn cảnh" RỖNG thay vì parser.getContext() thật, vì ta chưa hề
        // parse thật sự — parser.getContext() lúc này sẽ là null. Predicate nào
        // phụ thuộc hoàn cảnh thực có thể trả lời sai, nhưng ít nhất không NPE.
        if (pt.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
            queue.push(new PipelineEntry(pt.target, cur.tokenIndex));
        }
    }

    /**
     * Cửa miễn phí bình thường (epsilon): mở sẵn, cứ bước qua, không tốn lời nào.
     */
    private void handleFreeDoor(Transition t, PipelineEntry cur, Deque<PipelineEntry> queue) {
        queue.push(new PipelineEntry(t.target, cur.tokenIndex));
    }

    /**
     * Cửa cần mật khẩu (Atom / Set / NotSet / Wildcard) — nơi thật sự sinh ra
     * gợi ý. Nếu đã hết lời để nói (tại caret), tên mật khẩu trên cửa này CHÍNH
     * LÀ gợi ý (trừ khi nó nằm trong ignoredTokens). Nếu còn lời, ta so xem lời
     * tiếp theo có đúng mật khẩu không: đúng thì bước qua và tốn 1 lời, sai thì
     * im lặng — coi như ngõ cụt, không đi tiếp được từ đây.
     */
    private void handlePasswordDoor(Transition t, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
        IntervalSet label = t.label();
        if (label == null || label.size() == 0) return;
        if (t instanceof NotSetTransition) {
            label = label.complement(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
        }

        if (atCaret) {
            for (int sym : label.toList()) {
                if (!ignoredTokens.containsKey(sym)) {
                    suggestedTokens.add(sym);
                }
            }
        } else if (label.contains(tokens.get(cur.tokenIndex).getType())) {
            queue.push(new PipelineEntry(t.target, cur.tokenIndex + 1));
        }
        // Sai mật khẩu -> không push gì cả -> nhánh này chết ở đây, không đi tiếp được.
    }

    /**
     * Gọi sau collectCandidates(): những rule "có ý nghĩa" mà caret rơi vào (xem preferredRules).
     */
    public Set<Integer> getSuggestedRules() {
        return suggestedRules;
    }

    /**
     * true nếu đã dùng hết token đã gõ — nói cách khác, đang đứng đúng tại caret, hết lời để nói.
     */
    private boolean isAtCaret(int tokenIndex) {
        return tokenIndex >= tokens.size() - 1;
    }

    // ════════════════════════════════════════════════════════════════
    // Đọc trước "những lời đã nói" — token từ đầu câu tới caret
    // ════════════════════════════════════════════════════════════════

    private static List<Token> readTokens(TokenStream stream, int caretTokenIndex) {
        int saved = stream.index();
        stream.seek(0);
        List<Token> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            Token t = stream.LT(i);
            result.add(t);
            if (t.getTokenIndex() >= caretTokenIndex || t.getType() == Token.EOF) break;
        }
        stream.seek(saved);
        return result;
    }

    private record PipelineEntry(ATNState state, int tokenIndex) {
    }

}