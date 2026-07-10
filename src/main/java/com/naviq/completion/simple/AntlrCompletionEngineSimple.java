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
 * Đã BỎ so với bản đầy đủ (không ảnh hưởng tính đúng đắn, chỉ mất tối ưu/tiện ích):
 * - FollowSetsByState cache dùng chung nhiều luồng (thread-safe cache)
 * - resolveRuleTextRanges (đổi token index -> offset trong text gốc)
 * - getFollowingTokens (dự đoán chuỗi token chắc chắn theo sau)
 * - resolveToPreferredRule quét "outermost" qua RuleCallStack (bản này không giữ
 * stack, dựa vào thứ tự gọi enterRule tự nhiên từ ngoài vào trong)
 * <p>
 * ĐÃ SỬA (2 lỗi logic cốt lõi từng có ở bản rút gọn trước):
 * 1) Cache theo (ruleIndex, tokenIndex) trước đây chỉ là "cờ đã thấy" -> nếu 2 nhánh
 * khác nhau cùng gọi 1 rule con ở cùng vị trí, nhánh thứ 2 bị coi nhầm là ngõ cụt.
 * Giờ ruleExitCache lưu ĐÚNG kết quả thật (có đặt placeholder rỗng khi đang tính
 * dở để vẫn chặn được đệ quy vô hạn do left-recursion).
 * 2) Tại caret, trước đây LUÔN coi rule là "đã thoát được" dù rule đó còn bắt buộc
 * phải gõ thêm token bên trong -> rule cha bị sinh nhầm gợi ý cho phần đứng sau.
 * Giờ chỉ coi là thoát được nếu rule thật sự "nullable" tại đó (xem
 * canExitWithoutConsumingToken()).
 * <p>
 * Ý TƯỞNG CỐT LÕI (không đổi so với bản gốc):
 * - Grammar = ATN = mạng lưới state nối nhau bằng transition.
 * - Gọi rule con (RuleTransition) KHÔNG tốn token — giống gọi hàm, xong quay về.
 * - Engine chỉ tự hỏi đúng 2 câu tại mỗi bước:
 * 1) isAtCaret(tokenIndex)              -> đã hết token để so chưa?
 * 2) canConsumeCurrentToken(...)         -> token tiếp theo có khớp không?
 * - Hễ tới caret thì DỪNG NUỐT TOKEN, chuyển sang liệt kê nhãn các transition
 * còn lại tại đó làm gợi ý.
 */
public class AntlrCompletionEngineSimple {

    private final Parser parser;
    private final ATN atn;

    // Token bị bỏ qua, không bao giờ đưa vào gợi ý (kể cả khi khớp tại caret).
    private final Map<Integer, Boolean> ignoredTokens;

    // Rule được coi là "có ý nghĩa nghiệp vụ": hễ caret rơi vào đây thì gợi ý
    // luôn cả rule (vd "columnref") thay vì đi sâu hơn liệt kê token trần trụi bên trong.
    private final Map<Integer, Boolean> preferredRules;

    // Token đã đọc trước, từ vị trí bắt đầu tới caret.
    private List<Token> tokens;

    // Kết quả: tập token-type được gợi ý (loại trừ ignoredTokens).
    private final Set<Integer> suggestedTokens = new HashSet<>();

    // Kết quả: tập rule-index được gợi ý (những rule nằm trong preferredRules).
    private final Set<Integer> suggestedRules = new HashSet<>();

    // Cache THẬT theo (ruleIndex, tokenIndex) -> tập token-index mà rule này thoát ra.
    // Khi đang tính dở (chặn left-recursion), ta đặt tạm rỗng vào đây; sau khi tính
    // xong sẽ ghi đè bằng kết quả thật. Nhờ vậy lần gọi thứ 2 tới cùng (rule, tokenIndex)
    // từ 1 nhánh khác sẽ nhận lại đúng kết quả, thay vì bị coi nhầm là ngõ cụt.
    private final Map<Integer, Map<Integer, Set<Integer>>> ruleExitCache = new HashMap<>();

    public AntlrCompletionEngineSimple(Parser parser,
                                       Map<Integer, Boolean> ignoredTokens,
                                       Map<Integer, Boolean> preferredRules) {
        this.parser = parser;
        this.atn = parser.getATN();
        this.ignoredTokens = ignoredTokens;
        this.preferredRules = preferredRules;
    }

    // ────────────────────────────────────────────────────────────────
    // Điểm vào
    // ────────────────────────────────────────────────────────────────

    /**
     * Trả về tập token-type gợi ý tại vị trí caretTokenIndex, bắt đầu từ rule đầu tiên (index 0).
     */
    public Set<Integer> collectCandidates(int caretTokenIndex) {
        suggestedTokens.clear();
        suggestedRules.clear();
        ruleExitCache.clear();
        tokens = readTokens(parser.getTokenStream(), caretTokenIndex);

        enterRule(atn.ruleToStartState[0], 0);
        return suggestedTokens;
    }

    /**
     * Gọi sau collectCandidates(): các rule "có ý nghĩa" (preferredRules) mà caret rơi vào.
     */
    public Set<Integer> getSuggestedRules() {
        return suggestedRules;
    }

    // ────────────────────────────────────────────────────────────────
    // BƯỚC 1 — Vào một rule tại một vị trí token cho trước (đệ quy)
    // ────────────────────────────────────────────────────────────────

    /**
     * Duyệt rule bắt đầu từ {@code start}, tại vị trí token {@code tokenIndex}.
     * Trả về tập vị trí token mà lời gọi rule này có thể "thoát ra"
     * (để rule cha biết resume tiếp từ đâu).
     */
    private Set<Integer> enterRule(ATNState start, int tokenIndex) {
        // Cache theo (ruleIndex, tokenIndex): nếu rule này đã được duyệt từ đúng vị trí
        // này rồi (kể cả đang duyệt dở) thì trả lại giá trị đã lưu — không tính lại.
        Map<Integer, Set<Integer>> exitsByEntryToken = ruleExitCache.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());
        Set<Integer> cached = exitsByEntryToken.get(tokenIndex);
        if (cached != null) {
            return cached;
        }
        // Đặt tạm RỖNG trước khi tính — đây là chỗ chặn đệ quy vô hạn (left-recursion):
        // nếu trong lúc tính mà quay lại đúng (rule, tokenIndex) này, sẽ nhận ngay giá
        // trị tạm này thay vì lặp mãi. Sau khi tính xong ta ghi đè bằng kết quả thật.
        exitsByEntryToken.put(tokenIndex, Collections.emptySet());

        Set<Integer> result;

        // ── Câu hỏi 1: đã hết token để so chưa? ──
        if (isAtCaret(tokenIndex)) {
            if (preferredRules.containsKey(start.ruleIndex)) {
                // Caret đang nằm trong 1 rule "có ý nghĩa" (vd columnref, qualified_name)
                // -> chốt luôn ở đây, KHÔNG đi sâu hơn liệt kê token trần trụi bên trong nó.
                suggestedRules.add(start.ruleIndex);
                // Rule cha chỉ được coi là "rule con đã xong" nếu rule con CÓ THỂ kết thúc
                // rỗng ngay tại đây — nếu không, còn nợ ít nhất 1 token bên trong rule con,
                // rule cha KHÔNG được phép sinh gợi ý cho phần đứng sau nó.
                result = canExitWithoutConsumingToken(start)
                        ? Collections.singleton(tokenIndex)
                        : Collections.emptySet();
            } else {
                // Không nuốt token nữa -> liệt kê mọi thứ có thể xuất hiện tiếp theo làm gợi ý.
                // walkRuleBody trả về đúng tập vị trí "thoát ra" (ở đây luôn là tokenIndex
                // nếu có, vì tại caret không có transition nào làm tăng tokenIndex).
                Set<Integer> exits = walkRuleBody(start, tokenIndex, /*atCaret=*/true);
                result = exits.contains(tokenIndex) ? Collections.singleton(tokenIndex) : Collections.emptySet();
            }
        } else {
            // ── Câu hỏi 2: token tiếp theo có khớp không? ──
            // (Ở bản gọn này ta không tính follow-set trước; cứ để walkRuleBody tự thử khớp
            //  từng transition — nếu không có transition nào khớp thì tự nhiên trả về rỗng.)
            result = walkRuleBody(start, tokenIndex, /*atCaret=*/false);
        }

        exitsByEntryToken.put(tokenIndex, result); // ghi đè placeholder bằng kết quả thật
        return result;
    }

    /**
     * Epsilon-closure thuần (không ăn token thật nào): từ {@code start} có đường nào
     * tới RULE_STOP mà không cần match token thật hay không? Dùng để biết 1 rule có
     * "nullable" ngay tại vị trí đang đứng hay không, khi ta chủ động KHÔNG walk
     * body đầy đủ (trường hợp preferred rule, để tránh sinh gợi ý token trần trụi).
     * Có transition qua RuleTransition/PredicateTransition/epsilon đều được đi tiếp
     * vì các loại này không tốn token; gặp token thật thì dừng nhánh đó.
     */
    private boolean canExitWithoutConsumingToken(ATNState start) {
        Set<Integer> visited = new HashSet<>();
        Deque<ATNState> queue = new ArrayDeque<>();
        queue.push(start);
        while (!queue.isEmpty()) {
            ATNState s = queue.pop();
            if (!visited.add(s.stateNumber)) continue;
            if (s.getStateType() == ATNState.RULE_STOP) return true;

            for (Transition t : s.getTransitions()) {
                if (t instanceof RuleTransition rt) {
                    if (canExitWithoutConsumingToken(rt.target)) {
                        queue.push(rt.followState);
                    }
                } else if (t instanceof PredicateTransition pt) {
                    if (pt.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
                        queue.push(t.target);
                    }
                } else if (t.isEpsilon()) {
                    queue.push(t.target);
                }
                // token thật -> không epsilon -> bỏ qua, nhánh này cần gõ thêm
            }
        }
        return false;
    }

    /**
     * true nếu đã dùng hết token đã gõ (đang đứng đúng tại caret).
     */
    private boolean isAtCaret(int tokenIndex) {
        return tokenIndex >= tokens.size() - 1;
    }

    // ────────────────────────────────────────────────────────────────
    // BƯỚC 2 — Duyệt thân 1 rule: BFS trên các transition của ATN
    // ────────────────────────────────────────────────────────────────

    private Set<Integer> walkRuleBody(ATNState start, int startTokenIndex, boolean atCaretHint) {
        Set<Integer> ruleExits = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<PipelineEntry> queue = new ArrayDeque<>();
        queue.push(new PipelineEntry(start, startTokenIndex));

        while (!queue.isEmpty()) {
            PipelineEntry cur = queue.pop();
            if (!visited.add(cur.state.stateNumber + ":" + cur.tokenIndex)) {
                continue; // (state, tokenIndex) này duyệt rồi
            }

            if (cur.state.getStateType() == ATNState.RULE_STOP) {
                ruleExits.add(cur.tokenIndex); // rule kết thúc ở đây -> ghi nhận điểm thoát
                continue;
            }

            boolean atCaret = isAtCaret(cur.tokenIndex);

            for (Transition t : cur.state.getTransitions()) {

                if (t instanceof RuleTransition rt) {
                    // Gọi rule con: KHÔNG tốn token -> đệ quy enterRule(), rồi resume
                    // ở followState (điểm ngay sau lời gọi, trong rule cha).
                    for (int exitTok : enterRule(rt.target, cur.tokenIndex)) {
                        queue.push(new PipelineEntry(rt.followState, exitTok));
                    }

                } else if (t instanceof PredicateTransition pt) {
                    // Dùng context RỖNG (không phải parser.getContext(), sẽ là null vì ta
                    // chưa thật sự parse) — predicate phụ thuộc context thực có thể sai,
                    // nhưng ít nhất không NPE.
                    if (pt.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
                        queue.push(new PipelineEntry(pt.target, cur.tokenIndex));
                    }

                } else if (t.isEpsilon()) {
                    // Miễn phí, không ăn token -> cứ đi tiếp.
                    queue.push(new PipelineEntry(t.target, cur.tokenIndex));

                } else {
                    // Transition ăn 1 token thật (Atom / Set / Wildcard / NotSet).
                    IntervalSet label = t.label();
                    if (label == null || label.size() == 0) continue;
                    if (t instanceof NotSetTransition) {
                        label = label.complement(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
                    }

                    if (atCaret) {
                        // Tại caret: không so token thật nữa -> nhãn của mũi tên này CHÍNH LÀ gợi ý, trừ những token nằm trong ignoredTokens (identifier, dấu câu, số, ...).
                        for (int sym : label.toList()) {
                            if (!ignoredTokens.containsKey(sym)) {
                                suggestedTokens.add(sym);
                            }
                        }
                    } else if (label.contains(tokens.get(cur.tokenIndex).getType())) {
                        // Còn token thật: khớp thì ăn token, đi tiếp.
                        queue.push(new PipelineEntry(t.target, cur.tokenIndex + 1));
                    }
                }
            }
        }
        return ruleExits;
    }

    // ────────────────────────────────────────────────────────────────
    // Đọc trước token từ đầu tới caret
    // ────────────────────────────────────────────────────────────────

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