package com.naviq.learn.draft;

import com.naviq.completion.syntactic.engine.feature.FollowingTokensFinder;
import com.naviq.completion.syntactic.engine.feature.PreferredRuleResolver;
import com.naviq.completion.syntactic.engine.feature.RuleCallStack;
import com.naviq.completion.syntactic.engine.feature.RuleTextRangeResolver;
import com.naviq.completion.syntactic.engine.model.CandidatesResult;
import com.naviq.completion.syntactic.engine.model.InputToken;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;

/**
 * CORE — chỉ chứa đúng thuật toán "phòng và cửa" (xem ATN_ROOM_DOOR_ANALOGY.md).
 * 4 tính năng thêm vào bản gốc đầy đủ giờ nằm ở FILE RIÊNG, engine này chỉ GỌI
 * RA chúng ở đúng vài điểm nối, không tự cài logic của chúng vào giữa:
 * <p>
 * - FollowSetsByState   : tính trước "từ phòng này, token nào có thể gặp",
 * cache thread-safe, dùng để cắt sớm trước khi dò cửa sống
 * — VÀ khi caret rơi đúng lúc vừa vào 1 mê cung, dùng thẳng luôn để sinh gợi ý (generateSuggestionsFromFollowSets),
 * core không cần biết cấu trúc dữ liệu follow-set là gì cả.
 * - PreferredRuleResolver: gộp gợi ý về mê cung đặc biệt NGOÀI CÙNG nếu lồng nhau.
 * - RuleTextRangeResolver: đổi vị trí mê cung đặc biệt thành offset ký tự.
 * - RuleCallStack       : ngăn xếp "đang lồng trong mê cung nào" — dữ liệu
 * dùng chung giữa 2 feature outermost/text-range.
 * <p>
 * Đọc file này là đủ để hiểu đúng LÕI thuật toán completion. 4 file kia chỉ
 * cần đọc khi bạn quan tâm tới đúng phần tối ưu/tiện ích tương ứng.
 * <p>
 * Có đúng 3 HOOK nhỏ (mặc định KHÔNG làm gì cả — giữ nguyên hành vi gốc) để
 * class con thêm hành vi mà KHÔNG cần sửa file này:
 * <p>
 * - {@link #recoverIfNeeded}    : sau khi 1 rule chết hẳn, có muốn cứu không?
 * - {@link #recoverRuleDeadEnd} : sau khi 1 rule con (qua RuleTransition) chết hẳn, có muốn cứu không?
 * - {@link #onReachedCaret}     : mỗi khi có 1 nhánh sống chạm caret, cần biết thì override.
 * <p>
 * Xem {@code ResyncCompletionEngineBase} — class con dùng cả 3 hook này để
 * cộng thêm khả năng hồi phục lỗi, không đụng vào 1 dòng nào ở đây.
 * Xem {@code ResyncCompletionEngineBase} — class con dùng cả 3 hook này để
 * cộng thêm khả năng hồi phục lỗi, không đụng vào 1 dòng nào ở đây.
 */
public abstract class CompletionEngineBase3 {

    protected final Parser parser;
    protected final ATN atn;

    protected final Map<Integer, Boolean> ignoredTokens;
    protected final Map<Integer, Boolean> preferredRules;

    protected List<InputToken> tokens;
    protected int tokenStartIndex;

    protected CandidatesResult result;
    protected final Map<Integer, Map<Integer, Set<Integer>>> ruleExitCache = new HashMap<>();

    public CompletionEngineBase3(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        this.parser = parser;
        this.atn = parser.getATN();
        this.ignoredTokens = ignoredTokens;
        this.preferredRules = preferredRules;
    }

    // ════════════════════════════════════════════════════════════════
    // ĐIỂM VÀO
    // ════════════════════════════════════════════════════════════════

    public CandidatesResult collectCandidates(int caretTokenIndex) {
        return collectCandidates(caretTokenIndex, null);
    }

    public CandidatesResult collectCandidates(int caretTokenIndex, ParserRuleContext context) {
        prepareTokens(caretTokenIndex, context);
        return runOnePass(startRuleIndexOf(context));
    }

    /**
     * Tách riêng để class con (2-lượt resync) tái sử dụng, không phải chuẩn bị token lại từ đầu cho mỗi lượt.
     */
    protected void prepareTokens(int caretTokenIndex, ParserRuleContext context) {
        tokenStartIndex = context != null ? context.start.getTokenIndex() : 0;
        tokens = readTokens(parser.getTokenStream(), tokenStartIndex, caretTokenIndex);
    }

    protected int startRuleIndexOf(ParserRuleContext context) {
        return context != null ? context.getRuleIndex() : 0;
    }

    /**
     * Chạy đúng 1 lượt phân tích trọn vẹn — class con gọi lại hàm này (không override) để chạy nhiều lượt nếu cần.
     */
    protected CandidatesResult runOnePass(int startRuleIndex) {
        result = new CandidatesResult();
        ruleExitCache.clear();

        enterRule(atn.ruleToStartState[startRuleIndex], 0, new RuleCallStack());

        // FEATURE: RuleTextRangeResolver.java — chạy sau khi mọi thứ đã xong.
        RuleTextRangeResolver.resolve(preferredRules, ruleExitCache, tokens, result);
        return result;
    }

    protected boolean isAtCaret(int tokenIndex) {
        return tokenIndex >= tokens.size() - 1;
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 1 — Bước vào 1 mê cung, tại 1 vị trí lời nói cho trước
    // ════════════════════════════════════════════════════════════════

    /**
     * KHUNG CHUNG cho cả 2 chế độ (bật/tắt follow-set) — phần cache HOÀN TOÀN
     * giống nhau giữa 2 chế độ, chỉ khác đúng "cách tính exits", nên viết 1
     * lần ở đây, 2 subclass chỉ cần implement {@link #computeExitsNotAtCaret}
     * và {@link #computeExitsAtCaret}.
     * <p>
     * - CÒN LỜI: an toàn đọc/ghi {@code ruleExitCache} theo (ruleIndex, tokenIndex) —
     * không có tác dụng phụ nào phụ thuộc {@code stack} của người gọi ở case này.
     * <p>
     * - TẠI CARET: KHÔNG đọc, KHÔNG ghi cache gì cả — ở đây có tác dụng phụ
     * (ghi nhận preferred rule vào {@code result}) PHỤ THUỘC {@code stack} riêng
     * của từng người gọi. Nếu đọc cache, chỉ nhánh gọi TRƯỚC mới thật sự chạy
     * và ghi nhận đúng; nhánh gọi SAU nhận nhầm kết quả cache, tác dụng phụ của
     * chính nó KHÔNG BAO GIỜ chạy — đây chính là bug thật đã gặp
     * ({@code "select * from "} mất gợi ý {@code qualified_name} vì
     * {@code func_name} dùng chung {@code colid} gọi trước, cache che mất
     * lượt gọi sau).
     */
    protected final Set<Integer> enterRule(ATNState start, int tokenIndex, RuleCallStack stack) {
        boolean atCaret = isAtCaret(tokenIndex);

        RuleCallStack entered = stack.copy();
        entered.push(start.ruleIndex, tokenIndex);

        if (!atCaret) {
            Map<Integer, Set<Integer>> exitsByEntryToken = ruleExitCache.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());
            Set<Integer> cached = exitsByEntryToken.get(tokenIndex);
            if (cached != null) {
                return cached;
            }
            exitsByEntryToken.put(tokenIndex, Collections.emptySet());

            Set<Integer> exits = computeExitsNotAtCaret(start, tokenIndex, entered);
            exitsByEntryToken.put(tokenIndex, exits);
            return exits;
        }

        return computeExitsAtCaret(start, tokenIndex, entered);
    }

    /**
     * Còn lời để nói: tính xem mê cung này thoát ra ở những vị trí nào (chế độ tự quyết định cách tính).
     */
    protected abstract Set<Integer> computeExitsNotAtCaret(ATNState start, int tokenIndex, RuleCallStack entered);

    /**
     * Đúng tại caret: sinh gợi ý (tác dụng phụ ghi vào {@code result}), rồi trả về xem mê cung có "rỗng" được không.
     */
    protected abstract Set<Integer> computeExitsAtCaret(ATNState start, int tokenIndex, RuleCallStack entered);

    /**
     * Mê cung {@code state} có "rỗng" được không — ra khỏi được mà không cần nói thêm gì?
     */
    protected abstract boolean isNullable(ATNState state);

    /**
     * Quét {@code stack} tìm mê cung đặc biệt (ngoài cùng nhất nếu lồng nhau),
     * ghi nhận vào {@code result} nếu tìm thấy. Trước đây bị 2 subclass override
     * y hệt nhau — gộp thành implementation thật ở đây, không còn là hook rỗng
     * "return false" chờ override nữa.
     */
    protected boolean handlePreferredRules(RuleCallStack stack, CandidatesResult result) {
        return PreferredRuleResolver.resolve(stack, preferredRules, result);
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 2 — Dò từng cửa trong 1 phòng: BFS trên các transition của ATN
    // ════════════════════════════════════════════════════════════════

    protected Set<Integer> walkRuleBody(ATNState start, int startTokenIndex, RuleCallStack stack) {
        Set<Integer> ruleExits = new HashSet<>();
        Set<String> visited = new HashSet<>();
        List<DeadEnd> deadEnds = new ArrayList<>();
        Deque<PipelineEntry> queue = new ArrayDeque<>();
        queue.push(new PipelineEntry(start, startTokenIndex, stack));
        bfsSweep(queue, visited, ruleExits, deadEnds);
        // HOOK — mặc định trả nguyên ruleExits, không cố cứu gì (đúng thuật
        // toán gốc). ResyncCompletionEngineBase override để thêm khả năng vá.
        return recoverIfNeeded(visited, ruleExits, deadEnds);
    }

    /**
     * HOOK — gọi SAU KHI 1 rule đã chạy hết BFS bình thường. Mặc định KHÔNG
     * làm gì (rule chết là chết thật, giữ nguyên {@code ruleExits}).
     * Override để thử "vá" dựa vào {@code deadEnds} đã ghi lại.
     */
    protected Set<Integer> recoverIfNeeded(Set<String> visited, Set<Integer> ruleExits, List<DeadEnd> deadEnds) {
        return ruleExits;
    }

    /**
     * Quét cạn {@code queue} bằng BFS (không phải "LƯỢT" của collectCandidates — 1 lượt collectCandidates có thể gọi hàm này NHIỀU LẦN, mỗi lần vá xong 1 vòng resync). Protected để class con (resync) gọi lại, không phải chép logic dispatch.
     */
    protected void bfsSweep(Deque<PipelineEntry> queue, Set<String> visited, Set<Integer> ruleExits, List<DeadEnd> deadEnds) {
        while (!queue.isEmpty()) {
            PipelineEntry cur = queue.pop();
            if (!visited.add(cur.state().stateNumber + ":" + cur.tokenIndex())) {
                continue;
            }

            if (cur.state().getStateType() == ATNState.RULE_STOP) {
                if (isAtCaret(cur.tokenIndex())) {
                    onReachedCaret();
                    handlePreferredRules(cur.stack(), result);
                }
                ruleExits.add(cur.tokenIndex());
                continue;
            }

            boolean atCaret = isAtCaret(cur.tokenIndex());
            if (atCaret) {
                onReachedCaret();
            }
            for (Transition t : cur.state().getTransitions()) {
                if (t instanceof RuleTransition rt) {
                    handleRuleDoor(rt, cur, atCaret, queue);
                } else if (t instanceof PredicateTransition pt) {
                    handleFreeDoorWithCondition(pt, cur, queue);
                } else if (t instanceof WildcardTransition wt) {
                    handleWildcardDoor(wt, cur, atCaret, queue);
                } else if (t.isEpsilon()) {
                    handleFreeDoor(t, cur, queue);
                } else {
                    handlePasswordDoor(t, cur, atCaret, queue, deadEnds);
                }
            }
        }
    }

    /**
     * HOOK — gọi mỗi khi có 1 nhánh SỐNG (không qua resync) chạm caret. Mặc định không làm gì.
     */
    protected void onReachedCaret() {
    }

    /**
     * Preferred-rule quy về NGOÀI CÙNG nhất nếu lồng nhau: {@code resolve()}
     * quét từ ngoài vào trong trên (stack + rt.target), dừng ở match đầu
     * tiên — không đệ quy vào {@code rt.target} nữa nếu đã match.
     */
    protected void handleRuleDoor(RuleTransition rt, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
        if (atCaret) {
            RuleCallStack withTarget = cur.stack().copy();
            withTarget.push(rt.target.ruleIndex, cur.tokenIndex());
            if (PreferredRuleResolver.resolve(withTarget, preferredRules, result)) {
                if (isNullable(rt.target)) {
                    queue.push(new PipelineEntry(rt.followState, cur.tokenIndex(), cur.stack()));
                }
                // Không nullable -> rule con này còn "nợ" ít nhất 1 token, không
                // thể hoàn thành ngay tại caret -> không push gì thêm, dừng ở đây.
                return;
            }
            // resolve() không tìm thấy preferred-rule nào (kể cả rt.target không preferred)
            // -> đi tiếp bình thường, đệ quy vào enterRule như dưới.
        }

        Set<Integer> exits = enterRule(rt.target, cur.tokenIndex(), cur.stack());

        if (exits.isEmpty() && !atCaret) {
            // HOOK — mặc định trả nguyên (vẫn rỗng, chết thật). Override để cứu.
            exits = recoverRuleDeadEnd(rt, cur, exits);
        }

        for (int exitTok : exits) {
            queue.push(new PipelineEntry(rt.followState, exitTok, cur.stack()));
        }
    }

    /**
     * HOOK — {@code rt.target} (1 rule con gọi qua RuleTransition) vừa CHẾT
     * HẲN (không thoát ra được đâu cả). Mặc định KHÔNG cứu, trả nguyên
     * {@code exits} (vẫn rỗng). Override để nhảy tới vị trí caller chấp nhận
     * được, giống {@code DefaultErrorStrategy} của ANTLR thật.
     */
    protected Set<Integer> recoverRuleDeadEnd(RuleTransition rt, PipelineEntry cur, Set<Integer> exits) {
        return exits;
    }

    protected void handleFreeDoorWithCondition(PredicateTransition pt, PipelineEntry cur, Deque<PipelineEntry> queue) {
        if (pt.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
            queue.push(new PipelineEntry(pt.target, cur.tokenIndex(), cur.stack()));
        }
    }

    protected void handleFreeDoor(Transition t, PipelineEntry cur, Deque<PipelineEntry> queue) {
        queue.push(new PipelineEntry(t.target, cur.tokenIndex(), cur.stack()));
    }

    /**
     * Cửa "gõ gì cũng được" (dấu `.` trong grammar) — label() của nó luôn null,
     * nên cần xử lý riêng thay vì rơi vào handlePasswordDoor.
     */
    protected void handleWildcardDoor(WildcardTransition t, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
        if (!atCaret) {
            queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, cur.stack()));
            return;
        }
        if (handlePreferredRules(cur.stack(), result)) {
            return;
        }
        IntervalSet all = IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
        for (int sym : all.toList()) {
            if (!ignoredTokens.containsKey(sym)) {
                result.tokens.putIfAbsent(sym, Collections.emptyList());
            }
        }
    }

    protected void handlePasswordDoor(Transition t, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue, List<DeadEnd> deadEnds) {
        IntervalSet label = t.label();
        if (label == null || label.size() == 0) {
            return;
        }
        if (t instanceof NotSetTransition) {
            label = label.complement(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
        }

        if (atCaret) {
            if (handlePreferredRules(cur.stack(), result)) {
                return;
            }
            List<Integer> syms = label.toList();
            List<Integer> following = syms.size() == 1 ? FollowingTokensFinder.getFollowingTokens(t, ignoredTokens) : Collections.emptyList();
            for (int sym : syms) {
                if (ignoredTokens.containsKey(sym)) {
                    continue;
                }
                if (!result.tokens.containsKey(sym)) {
                    result.tokens.put(sym, following);
                } else if (!result.tokens.get(sym).equals(following)) {
                    result.tokens.put(sym, Collections.emptyList());
                }
            }
        } else if (label.contains(tokens.get(cur.tokenIndex()).type())) {
            queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, cur.stack()));
        } else {
            // Sai mật khẩu -> chết ở đây. Vẫn GHI vào deadEnds (rẻ, vô hại) để
            // hook recoverIfNeeded có dữ liệu dùng NẾU class con muốn — bản
            // gốc ở đây không tự đọc deadEnds, nên hành vi không đổi.
            deadEnds.add(new DeadEnd(cur.state(), cur.tokenIndex(), label, t.target, cur.stack()));
        }
    }

    /**
     * 1 điểm chết tại 1 cửa mật khẩu — ghi nhớ đủ thông tin để hook {@link #recoverIfNeeded} dùng nếu cần.
     */
    protected record DeadEnd(ATNState from, int fromTokenIndex, IntervalSet label, ATNState target,
                             RuleCallStack stack) {
    }

    // ════════════════════════════════════════════════════════════════
    // Đọc trước "những lời đã nói" — token từ tokenStartIndex tới caret
    // ════════════════════════════════════════════════════════════════
    protected static List<InputToken> readTokens(TokenStream stream, int tokenStartIndex, int caretTokenIndex) {
        int saved = stream.index();
        stream.seek(tokenStartIndex);
        List<InputToken> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            var t = stream.LT(i);
            result.add(new InputToken(t.getType(), t.getStartIndex(), t.getStopIndex()));
            if (t.getTokenIndex() >= caretTokenIndex || t.getType() == Token.EOF) break;
        }
        stream.seek(saved);
        return result;
    }

    protected record PipelineEntry(ATNState state, int tokenIndex, RuleCallStack stack) {
    }
}