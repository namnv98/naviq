package com.sqlctx.completion.syntactic.engine;

import com.sqlctx.completion.model.CandidatesResult;
import com.sqlctx.completion.model.InputToken;
import com.sqlctx.completion.syntactic.engine.support.AtnPredicates;
import com.sqlctx.completion.syntactic.engine.support.FollowingTokensFinder;
import com.sqlctx.completion.syntactic.engine.support.PreferredRuleResolver;
import com.sqlctx.completion.syntactic.engine.support.RuleCallStack;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;

/**
 * Tìm các token / rule có thể đứng tại vị trí con trỏ (caret) bằng cách đi trực tiếp trên ATN của parser.
 * <p>
 * Ẩn dụ dùng xuyên suốt file này (xem {@code flow.md} ở root repo để đọc đầy đủ, kèm sơ đồ minh hoạ):
 * grammar là 1 tấm bản đồ trò chơi, mỗi rule là 1 <b>mê cung</b> gồm nhiều <b>phòng</b> ({@link ATNState})
 * nối nhau bằng <b>cửa</b> ({@link Transition}) - cửa cần mật khẩu (khớp đúng 1 token, tốn 1 lượt nói),
 * cửa miễn phí (epsilon, không tốn gì), hoặc cửa dẫn vào 1 mê cung con (gọi rule khác).
 * <p>
 * Ý tưởng:
 * <ul>
 *   <li><b>Lời đã nói</b> là các token từ đầu câu tới trước caret. <b>Caret</b> (phần tử cuối của
 *       {@link #tokens}) là chỗ đang gõ dở - nó KHÔNG được "ăn", ta hỏi xem chỗ đó có thể là gì.</li>
 *   <li>{@link #enterRule} trả lời câu hỏi: "bước vào mê cung R tại lời thứ i thì ra được ở những lời
 *       nào?" (tập <i>exit</i>). Lần đi không chạm caret chỉ phụ thuộc (R, i) nên được nhớ trong
 *       {@link #ruleExitCache}.</li>
 *   <li>{@link #walkRuleBody} đi trong 1 mê cung: gặp cửa mật khẩu khớp lời đã nói thì bước qua (tốn 1
 *       lời), gặp cửa vào mê cung con thì hỏi {@link #enterRule}, gặp cửa miễn phí thì cứ đi.</li>
 *   <li>Hết lời để nói (tại caret): mọi cửa mật khẩu mở được ngay lúc đó chính là gợi ý (ghi vào
 *       {@code result.tokens}); nếu đang đứng trong 1 "phòng VIP" ({@code preferredRules}) thì chốt tên
 *       phòng đó thay vì liệt kê từng cửa bên trong (xem {@link PreferredRuleResolver}).</li>
 * </ul>
 * Không có phục hồi lỗi: lời đã nói không khớp cửa nào thì nhánh đó chết, cho 0 gợi ý.
 * <p>
 * Hai lớp con chỉ khác nhau ở 3 hook: cách tính tập exit (còn lời / tại caret) và "mê cung rỗng được không".
 * Instance giữ trạng thái của lần gọi hiện tại nên KHÔNG dùng chung giữa nhiều luồng.
 */
public abstract class CompletionEngineBase {

    protected final Parser parser;
    protected final ATN atn;

    protected final Map<Integer, Boolean> ignoredTokens;
    protected final Map<Integer, Boolean> preferredRules;

    /** Token từ đầu câu tới token caret (bao gồm token caret ở cuối). */
    protected List<InputToken> tokens;
    protected CandidatesResult result;

    /**
     * ruleIndex -> (lời thứ mấy lúc vào mê cung -> những lời có thể ra được). Chỉ lưu những lần đi
     * KHÔNG chạm caret: lần đi chạm caret sinh gợi ý phụ thuộc cuốn nhật ký của người gọi, nên phải đi
     * lại với từng người gọi khác nhau, không nhớ chung được.
     */
    protected final Map<Integer, Map<Integer, Set<Integer>>> ruleExitCache = new HashMap<>();

    /**
     * Số lần đã chạm caret. Giá trị tuyệt đối không có ý nghĩa: {@link #enterRule} chỉ so trước/sau khi
     * tính 1 mê cung, tăng lên nghĩa là lần tính đó (kể cả mê cung con bên trong) có chạm caret.
     */
    private long caretTouches;

    public CompletionEngineBase(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        this.parser = parser;
        this.atn = parser.getATN();
        this.ignoredTokens = ignoredTokens;
        this.preferredRules = preferredRules;
    }

    // ════════════════════════════════════════════════════════════════
    // ĐIỂM VÀO — bắt đầu ván chơi
    // ════════════════════════════════════════════════════════════════

    public CandidatesResult collectCandidates(int caretTokenIndex) {
        result = new CandidatesResult();
        ruleExitCache.clear();
        caretTouches = 0;
        tokens = readTokens(parser.getTokenStream(), caretTokenIndex);

        int startRuleIndex = 0;
        // Bước chân vào mê cung chính (rule gốc), tại ô đầu tiên trên bàn cờ, cuốn nhật ký còn trắng tinh.
        enterRule(atn.ruleToStartState[startRuleIndex], 0, new RuleCallStack());
        return result;
    }

    protected boolean isAtCaret(int tokenIndex) {
        return tokenIndex >= tokens.size() - 1;
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 1 — bước vào 1 mê cung tại 1 lời: tính những lời có thể ra được
    // ════════════════════════════════════════════════════════════════

    /**
     * "Bước vào mê cung {@code start} tại lời thứ {@code tokenIndex}, ra được ở đâu?" Trước khi đi, tự
     * hỏi "đã từng đứng đúng chỗ này trong mê cung này chưa" ({@link #ruleExitCache}) - nếu rồi, lấy
     * ngay kết quả cũ, khỏi đi lại.
     */
    protected final Set<Integer> enterRule(ATNState start, int tokenIndex, RuleCallStack caller) {
        if (isAtCaret(tokenIndex)) {
            caretTouches++;
            return computeExitsAtCaret(start, tokenIndex, callStackInto(start, tokenIndex, caller));
        }

        Map<Integer, Set<Integer>> exitsByEntryToken = ruleExitCache.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());
        Set<Integer> cached = exitsByEntryToken.get(tokenIndex);
        if (cached != null) {
            return cached;
        }
        // Grammar ANTLR không có đệ quy trái nên không thể quay lại đúng (rule, lời) này trong lúc đang tính;
        // đặt tạm "coi như ngõ cụt" chỉ để chắc chắn không lặp vô hạn nếu đi vòng quay lại đúng chỗ này.
        exitsByEntryToken.put(tokenIndex, Collections.emptySet());

        long touchesBefore = caretTouches;
        Set<Integer> exits = computeExitsNotAtCaret(start, tokenIndex, callStackInto(start, tokenIndex, caller));
        boolean reachedCaret = caretTouches > touchesBefore;
        if (reachedCaret) {
            // Gợi ý vừa sinh phụ thuộc cuốn nhật ký của người gọi này -> không cất hồ sơ, người gọi khác
            // phải tự đi lại với đúng nhật ký của họ.
            exitsByEntryToken.remove(tokenIndex); // bỏ dấu "ngõ cụt" tạm ở trên
        } else {
            exitsByEntryToken.put(tokenIndex, exits);
        }
        return exits;
    }

    /** Copy cuốn nhật ký của người gọi, ghi thêm tên mê cung vừa bước vào - không sửa bản gốc (xem
     * "Cuốn nhật ký cần mang theo riêng cho từng nhánh" trong flow.md). */
    private static RuleCallStack callStackInto(ATNState ruleStart, int tokenIndex, RuleCallStack caller) {
        RuleCallStack entered = caller.copy();
        entered.push(ruleStart.ruleIndex, tokenIndex);
        return entered;
    }

    // ── 3 hook: chỗ 2 chế độ khác nhau ────────────────────────────────

    /** Còn lời để nói: những lời có thể ra khỏi mê cung. Mặc định đi thẳng vào trong ({@link #walkRuleBody}). */
    protected Set<Integer> computeExitsNotAtCaret(ATNState start, int tokenIndex, RuleCallStack entered) {
        return walkRuleBody(start, tokenIndex, entered);
    }

    /**
     * Bước vào mê cung ngay TẠI caret (hết lời): sinh gợi ý (ghi vào {@code result}) rồi trả về
     * {@code {tokenIndex}} nếu mê cung có thể rỗng, ngược lại tập rỗng. Mặc định đi thẳng vào trong.
     */
    protected Set<Integer> computeExitsAtCaret(ATNState start, int tokenIndex, RuleCallStack entered) {
        return walkRuleBody(start, tokenIndex, entered);
    }

    /** "Mê cung này có thể coi như xong ngay không, dù chưa nói thêm gì?" - đi thử cửa miễn phí/cửa vào
     * mê cung con khác (không tốn lời), chạm được RULE_STOP thì đúng - trả lời có. */
    protected abstract boolean canExitWithoutConsumingToken(ATNState state);

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 2 — dò đường trong 1 mê cung: duyệt rộng (BFS) qua các cửa
    // ════════════════════════════════════════════════════════════════

    /** Đang "sống" ở phòng nào, với cuốn nhật ký nào, đã ăn tới lời thứ mấy - 1 nhánh BFS. */
    private record PipelineEntry(ATNState state, int tokenIndex, RuleCallStack stack) {
        long key() {
            return ((long) state.stateNumber << 32) | tokenIndex;
        }
    }

    /**
     * Dò hết mọi phòng trong mê cung bắt đầu ở {@code start}, trả về những lời mà tại đó chạm được
     * RULE_STOP (ra khỏi mê cung). {@code stack} là cuốn nhật ký tới mê cung này, dùng chung cho lần
     * đi này (mỗi nhánh BFS bên trong tự mang bản sao riêng khi cần rẽ vào mê cung con - xem
     * {@link #handleRuleDoor}).
     */
    protected Set<Integer> walkRuleBody(ATNState start, int startTokenIndex, RuleCallStack stack) {
        Set<Integer> exits = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        Deque<PipelineEntry> queue = new ArrayDeque<>();
        queue.push(new PipelineEntry(start, startTokenIndex, stack));

        while (!queue.isEmpty()) {
            PipelineEntry cur = queue.pop();
            if (!visited.add(cur.key())) {
                continue;
            }
            if (isAtCaret(cur.tokenIndex())) {
                caretTouches++;
            }

            if (cur.state().getStateType() == ATNState.RULE_STOP) {
                // Chạm đáy đúng lúc hết lời: "lưới an toàn" quét lại nhật ký tìm phòng VIP gần ngoài nhất
                // đã đi qua (xem PreferredRuleResolver.resolve() + mục "Lưới an toàn" trong flow.md).
                if (isAtCaret(cur.tokenIndex())) {
                    reportPreferredRule(cur.stack());
                }
                exits.add(cur.tokenIndex());
                continue;
            }

            for (Transition t : cur.state().getTransitions()) {
                followTransition(t, cur, queue);
            }
        }
        return exits;
    }

    /** Xét 1 cửa của phòng hiện tại, định tuyến sang đúng người xử lý cửa loại đó. */
    private void followTransition(Transition t, PipelineEntry cur, Deque<PipelineEntry> queue) {
        if (t instanceof RuleTransition rt) {
            handleRuleDoor(rt, cur, queue);
        } else if (t instanceof PredicateTransition pt) {
            handleFreeDoorWithCondition(pt, cur, queue);
        } else if (t instanceof WildcardTransition) {
            handleWildcardDoor(t, cur, queue);
        } else if (t.isEpsilon()) {
            handleFreeDoor(t, cur, queue);
        } else {
            handlePasswordDoor(t, cur, queue);
        }
    }

    /** Ghi {@code stack} vào {@code result} nếu nó đi qua 1 phòng VIP; true nếu đã ghi. Dùng ở cả 2 nơi
     * ("đường tắt" trong {@link #handleRuleDoor} lẫn "lưới an toàn" trong {@link #walkRuleBody}/
     * {@link #handlePasswordDoor}/{@link #handleWildcardDoor}) - CÙNG 1 hàm, chỉ khác thời điểm gọi và
     * nội dung nhật ký truyền vào (xem flow.md mục "Vì sao 2 đường này không bao giờ giẫm chân nhau"). */
    private boolean reportPreferredRule(RuleCallStack stack) {
        return PreferredRuleResolver.resolve(stack, preferredRules, result);
    }

    /**
     * Cửa dẫn vào 1 mê cung con: đúng tại caret VÀ mê cung con đó là "VIP" thì dùng đường tắt - chốt
     * tên phòng VIP luôn, KHÔNG bước chân vào bên trong (chỉ hỏi thêm "phòng VIP này rỗng được không" để
     * biết có nên đi tiếp qua {@code followState} hay dừng hẳn). Ngược lại, đi hết mê cung con đó bình
     * thường (đệ quy {@link #enterRule}), rồi luôn tiếp tục từ đúng điểm ngay sau cửa
     * ({@code rt.followState}) trong mê cung chính, ở mọi lời đã ra được.
     */
    private void handleRuleDoor(RuleTransition rt, PipelineEntry cur, Deque<PipelineEntry> queue) {
        if (isAtCaret(cur.tokenIndex())) {
            RuleCallStack withCallee = cur.stack().copy();
            withCallee.push(rt.target.ruleIndex, cur.tokenIndex());
            if (reportPreferredRule(withCallee)) {
                if (canExitWithoutConsumingToken(rt.target)) {
                    queue.push(new PipelineEntry(rt.followState, cur.tokenIndex(), cur.stack()));
                }
                return;
            }
        }
        for (int exit : enterRule(rt.target, cur.tokenIndex(), cur.stack())) {
            queue.push(new PipelineEntry(rt.followState, exit, cur.stack()));
        }
    }

    /** Cửa miễn phí (epsilon) - không tốn lời, luôn đi. */
    private void handleFreeDoor(Transition t, PipelineEntry cur, Deque<PipelineEntry> queue) {
        queue.push(new PipelineEntry(t.target, cur.tokenIndex(), cur.stack()));
    }

    /** Cửa miễn phí có điều kiện (semantic predicate) - không tốn lời, chỉ đi khi điều kiện đúng. */
    private void handleFreeDoorWithCondition(PredicateTransition pt, PipelineEntry cur, Deque<PipelineEntry> queue) {
        if (AtnPredicates.holds(parser, pt)) {
            queue.push(new PipelineEntry(pt.target, cur.tokenIndex(), cur.stack()));
        }
    }

    /** Dấu {@code .} trong grammar (khớp lời bất kỳ) — {@code label()} của nó là null nên không đi qua
     * {@link #handlePasswordDoor}. */
    private void handleWildcardDoor(Transition t, PipelineEntry cur, Deque<PipelineEntry> queue) {
        if (!isAtCaret(cur.tokenIndex())) {
            queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, cur.stack()));
            return;
        }
        if (reportPreferredRule(cur.stack())) {
            return;
        }
        // addToken (không phải tokens.putIfAbsent) - bug thật (lộ ra qua test #2 trong
        // CompletionEngineBaseTest): 1 token vừa đến từ cửa mật khẩu thường (có "following" chắc chắn,
        // vd X luôn theo sau bởi Y) VỪA đến được từ cửa wildcard này (không có following chắc chắn nào -
        // wildcard khớp X trong ngữ cảnh khác, không nhất thiết theo sau bởi Y) - putIfAbsent bỏ qua nếu
        // token đã có sẵn trong map (do cửa mật khẩu kia chạy trước), giữ nguyên following SAI. addToken
        // xử lý đúng: phát hiện xung đột (existing != []) thì xoá về rỗng, đúng ngữ nghĩa "không chắc".
        for (int type : IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType).toList()) {
            if (!ignoredTokens.containsKey(type)) {
                result.addToken(type, Collections.emptyList());
            }
        }
    }

    /**
     * Cửa cần mật khẩu (Atom/Set/NotSet transition) - nơi mọi gợi ý dạng token thật sự được sinh ra.
     * Còn lời: đúng mật khẩu thì bước qua (tốn 1 lời), sai thì im lặng (nhánh chết, không push gì cả).
     * Hết lời (tại caret): đang đứng trong phòng VIP thì chốt tên phòng đó, không liệt kê token trần
     * trụi; không thì tên mật khẩu trên cửa chính là gợi ý (trừ khi bị bỏ qua - {@code ignoredTokens}).
     */
    private void handlePasswordDoor(Transition t, PipelineEntry cur, Deque<PipelineEntry> queue) {
        IntervalSet label = t.label();
        if (label == null || label.size() == 0) {
            return;
        }
        if (t instanceof NotSetTransition) {
            label = label.complement(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
        }

        if (!isAtCaret(cur.tokenIndex())) {
            if (label.contains(tokens.get(cur.tokenIndex()).type())) {
                queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, cur.stack()));
            }
            return;
        }

        if (reportPreferredRule(cur.stack())) {
            return;
        }
        List<Integer> types = label.toList();
        // Chỉ khi cửa có đúng 1 mật khẩu thì mới biết chắc chuỗi lời đi liền sau nó.
        List<Integer> following = types.size() == 1 ? FollowingTokensFinder.getFollowingTokens(t, ignoredTokens) : Collections.emptyList();
        for (int type : types) {
            if (!ignoredTokens.containsKey(type)) {
                result.addToken(type, following);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Đọc các token từ đầu câu tới token caret
    // ════════════════════════════════════════════════════════════════

    protected static List<InputToken> readTokens(TokenStream stream, int caretTokenIndex) {
        int saved = stream.index();
        stream.seek(0);
        List<InputToken> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            var t = stream.LT(i);
            result.add(new InputToken(t.getType(), t.getStartIndex(), t.getStopIndex()));
            if (t.getTokenIndex() >= caretTokenIndex || t.getType() == Token.EOF) break;
        }
        stream.seek(saved);
        return result;
    }
}
