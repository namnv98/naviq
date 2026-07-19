package com.naviq.completion.syntactic.engine;

import com.naviq.completion.syntactic.engine.feature.*;
import com.naviq.completion.syntactic.engine.model.CandidatesResult;
import com.naviq.completion.syntactic.engine.model.InputToken;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CORE — chỉ chứa đúng thuật toán "phòng và cửa" (xem ATN_ROOM_DOOR_ANALOGY.md).
 * Các tính năng thêm vào bản gốc đầy đủ nằm ở FILE RIÊNG, engine này chỉ GỌI
 * RA chúng ở đúng vài điểm nối, không tự cài logic của chúng vào giữa:
 * <p>
 * - PreferredRuleResolver: gộp gợi ý về mê cung đặc biệt NGOÀI CÙNG nếu lồng nhau.
 * - RuleTextRangeResolver: đổi vị trí mê cung đặc biệt thành offset ký tự.
 * - RuleCallStack       : ngăn xếp "đang lồng trong mê cung nào" — dữ liệu
 * dùng chung giữa 2 feature outermost/text-range.
 * - RuleResyncSkipper   : khi 1 cửa/1 mê cung con CHẾT HẲN (input lỗi, ví dụ
 * gõ nhầm/thiếu từ khoá), tìm vị trí hồi phục hợp lý thay vì để cái chết lan
 * ra ngoài — xem chi tiết ở {@link #resolveDeadEnd} và {@link #handleRuleDoor}.
 */
public abstract class CompletionEngineBase2 {

    protected final Parser parser;
    protected final ATN atn;

    protected final Map<Integer, Boolean> ignoredTokens;
    protected final Map<Integer, Boolean> preferredRules;

    protected List<InputToken> tokens;
    protected int tokenStartIndex;

    protected CandidatesResult result;
    protected final Map<Integer, Map<Integer, Set<Integer>>> ruleExitCache = new HashMap<>();

    protected final RuleResyncSkipper resyncSkipper;
    /** Constructor cho phép resync hay không (bất biến — chỉ là "giấy phép", chưa chắc lượt nào cũng dùng). */
    protected final boolean resyncEnabled;
    /**
     * Bật/tắt resync CHO ĐÚNG LƯỢT CHẠY HIỆN TẠI (khác {@code resyncEnabled}
     * — đó chỉ là giấy phép cố định, cái này mới là công tắc thật, đổi giữa 2
     * lượt trong {@code collectCandidates()}). Xem chi tiết 2 lượt ở đó.
     */
    private boolean resyncActiveThisPass = false;
    /**
     * TOÀN CỤC cho cả 1 LƯỢT chạy (không phải riêng từng {@code walkRuleBody()}):
     * 1 rule con KHÔNG LIÊN QUAN tới ngữ cảnh caret thật vẫn có thể "tưởng
     * mình chết" và tự ý resync, dù đâu đó đã có 1 nhánh khác chạm đích thành
     * công rồi. Dùng cờ toàn cục: hễ BẤT KỲ đâu đã chạm caret sống, mọi rule
     * con khác đều biết và KHÔNG cố cứu gì nữa.
     */
    protected final AtomicBoolean reachedCaretGlobal = new AtomicBoolean(false);
    /** Số vòng resync tối đa trong 1 lần walkRuleBody — chặn input lỗi quá nặng làm nổ vòng lặp vô hạn. */
    protected static final int MAX_RESYNC_ROUNDS = 10;

    public CompletionEngineBase2(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        this(parser, ignoredTokens, preferredRules, true);
    }

    public CompletionEngineBase2(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules, boolean resyncEnabled) {
        this.parser = parser;
        this.atn = parser.getATN();
        this.ignoredTokens = ignoredTokens;
        this.preferredRules = preferredRules;
        this.resyncSkipper = new RuleResyncSkipper(atn, 20);
        this.resyncEnabled = resyncEnabled;
    }

    // ════════════════════════════════════════════════════════════════
    // ĐIỂM VÀO
    // ════════════════════════════════════════════════════════════════

    public CandidatesResult collectCandidates(int caretTokenIndex) {
        return collectCandidates(caretTokenIndex, null);
    }

    /**
     * 2 LƯỢT TUẦN TỰ — thay cho việc cố "biết trước" ở cấp toàn cục bằng 1
     * cờ chia sẻ giữa các rule con đệ quy (đã thử, KHÔNG đủ — 1 rule con vẫn
     * có thể bị xử lý và tự ý resync TRƯỚC KHI nhánh đúng phía sau kịp chạm
     * caret, xem lịch sử). Cách này đảm bảo đúng ở CẤP TOÀN CỤC một cách
     * TUYỆT ĐỐI, không phụ thuộc thứ tự xử lý đệ quy:
     * <p>
     * LƯỢT 1 — chạy hoàn toàn SẠCH, resync tắt tuyệt đối (y hệt thuật toán
     * gốc, không có bất kỳ rủi ro nào). Nếu ra được gợi ý (dù chỉ 1 rule/1
     * token), TỚI ĐÍCH ĐƯỢC RỒI THÌ KHÔNG CẦN MÒ NỮA — dừng ngay, trả kết quả
     * này, không bao giờ chạm lượt 2.
     * <p>
     * LƯỢT 2 — CHỈ chạy khi lượt 1 HOÀN TOÀN RỖNG (chắc chắn 100%, xét trên
     * TOÀN BỘ câu, không phải từng rule con: không có BẤT KỲ đường sống nào ở
     * BẤT KỲ đâu) VÀ constructor cho phép ({@code resyncEnabled}) — lúc này
     * mới bật resync, chấp nhận rủi ro đã biết (có thể đoán sai/nhiễu) vì
     * đằng nào cũng không còn lựa chọn nào khác tốt hơn.
     */
    public CandidatesResult collectCandidates(int caretTokenIndex, ParserRuleContext context) {
        tokenStartIndex = context != null ? context.start.getTokenIndex() : 0;
        int startRuleIndex = context != null ? context.getRuleIndex() : 0;
        tokens = readTokens(parser.getTokenStream(), tokenStartIndex, caretTokenIndex);

        CandidatesResult pass1 = runOnePass(startRuleIndex, false);
        if (!pass1.tokens.isEmpty() || !pass1.rules.isEmpty()) {
            return pass1; // tới đích được rồi -> không mò nữa
        }
        if (!resyncEnabled) {
            return pass1; // rỗng thật, và không có giấy phép resync -> chịu, trả rỗng
        }
        return runOnePass(startRuleIndex, true);
    }

    private CandidatesResult runOnePass(int startRuleIndex, boolean resyncActive) {
        result = new CandidatesResult();
        ruleExitCache.clear();
        reachedCaretGlobal.set(false);
        resyncActiveThisPass = resyncActive;

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
     * - CÒN LỜI: an toàn đọc/ghi {@code ruleExitCache} theo (ruleIndex, tokenIndex).
     * - TẠI CARET: KHÔNG đọc, KHÔNG ghi cache — có tác dụng phụ (ghi nhận
     * preferred rule vào {@code result}) PHỤ THUỘC {@code stack} riêng của
     * từng người gọi; đọc cache sẽ khiến nhánh gọi SAU mất tác dụng phụ của
     * chính nó (bug thật đã gặp: {@code "select * from "} mất gợi ý
     * {@code qualified_name} vì {@code func_name} dùng chung {@code colid}
     * gọi trước, cache che mất lượt gọi sau).
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
            exitsByEntryToken.put(tokenIndex, Collections.emptySet()); // chặn đệ quy vô hạn trong lúc tính dở

            Set<Integer> exits = computeExitsNotAtCaret(start, tokenIndex, entered);
            exitsByEntryToken.put(tokenIndex, exits);
            return exits;
        }

        return computeExitsAtCaret(start, tokenIndex, entered);
    }

    protected abstract Set<Integer> computeExitsNotAtCaret(ATNState start, int tokenIndex, RuleCallStack entered);

    protected abstract Set<Integer> computeExitsAtCaret(ATNState start, int tokenIndex, RuleCallStack entered);

    protected abstract boolean isNullable(ATNState state);

    protected boolean handlePreferredRules(RuleCallStack stack, CandidatesResult result) {
        return PreferredRuleResolver.resolve(stack, preferredRules, result);
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 2 — Dò từng cửa trong 1 phòng: BFS trên các transition của ATN
    // ════════════════════════════════════════════════════════════════

    /**
     * {@code handlePasswordDoor} CHỈ GHI NHỚ điểm chết vào {@code deadEnds},
     * KHÔNG resync ngay (resync ngay tại từng cửa đơn lẻ đã CHỨNG MINH SAI —
     * 1 cửa không khớp thường chỉ là 1 nhánh rẽ bình thường, không phải lỗi
     * thật). Chỉ sau khi 1 lượt BFS chạy xong TOÀN BỘ mà {@code ruleExits}
     * vẫn rỗng VÀ chưa từng có nhánh nào sống chạm caret ({@code reachedCaretGlobal}
     * — xem lý do dưới), mới thử resync tại từng điểm đã ghi nhớ, lặp lại tối
     * đa {@link #MAX_RESYNC_ROUNDS} vòng (1 lần resync có thể dẫn tới 1 cái
     * chết mới xa hơn, cần vá tiếp).
     */
    protected Set<Integer> walkRuleBody(ATNState start, int startTokenIndex, RuleCallStack stack) {
        Set<Integer> ruleExits = new HashSet<>();
        Set<String> visited = new HashSet<>();
        List<DeadEnd> deadEnds = new ArrayList<>();

        Deque<PipelineEntry> queue = new ArrayDeque<>();
        queue.push(new PipelineEntry(start, startTokenIndex, stack));
        runBfsPass(queue, visited, ruleExits, deadEnds);

        int round = 0;
        while (resyncActiveThisPass && ruleExits.isEmpty() && !reachedCaretGlobal.get() && !deadEnds.isEmpty() && round < MAX_RESYNC_ROUNDS) {
            round++;
            List<DeadEnd> thisRound = deadEnds;
            deadEnds = new ArrayList<>(); // dead-end MỚI phát sinh trong vòng resync này, cho vòng SAU (nếu cần)

            Deque<PipelineEntry> resyncQueue = new ArrayDeque<>();
            for (DeadEnd de : thisRound) {
                resolveDeadEnd(de, resyncQueue);
            }

            if (!resyncQueue.isEmpty()) {
                runBfsPass(resyncQueue, visited, ruleExits, deadEnds);
            }
        }

        return ruleExits;
    }

    /**
     * Thử vá 1 điểm chết, theo đúng thứ tự ưu tiên an toàn giảm dần:
     * <p>
     * LỚP 1 — tìm ĐÚNG loại token cửa này cần ở đâu đó phía sau, coi phần ở
     * giữa là rác gõ nhầm. Chắc chắn 100% (không đoán vai trò), nên không
     * giới hạn số lần dùng trong 1 chuỗi.
     * <p>
     * LỚP 2 — không tìm được bản sao nào của literal đó -> thử coi cửa này
     * "bị bỏ qua miễn phí" (0-skip theo follow-set của đích đến). Đây là ĐOÁN,
     * có thể sai — nhưng nếu sai, nhánh này sẽ tự chết ở bước tiếp theo khi
     * gặp token thật không khớp (không cần tự tay chặn), nên cứ để nó chạy;
     * nếu sống sót tới caret thì đó là 1 trong nhiều cách diễn giải hợp lý,
     * xứng đáng góp mặt vào gợi ý khi input thật sự mơ hồ (không có "điểm
     * neo" nào để loại trừ được diễn giải sai).
     */
    private void resolveDeadEnd(DeadEnd de, Deque<PipelineEntry> resyncQueue) {
        int idx = resyncSkipper.findResyncPointForLabel(de.label(), tokens, de.fromTokenIndex());
        if (idx != -1) {
            resyncQueue.push(new PipelineEntry(de.target(), idx + 1, de.stack()));
            return;
        }
        idx = resyncSkipper.findResyncPoint(de.target(), tokens, de.fromTokenIndex());
        if (idx != -1) {
            resyncQueue.push(new PipelineEntry(de.target(), idx, de.stack()));
        }
    }

    /** Chạy 1 lượt BFS từ {@code queue} cho tới khi rỗng, gom kết quả vào {@code ruleExits}/{@code deadEnds} đã có sẵn (dùng chung giữa các vòng resync), cập nhật {@code reachedCaretGlobal} TOÀN CỤC. */
    private void runBfsPass(Deque<PipelineEntry> queue, Set<String> visited, Set<Integer> ruleExits, List<DeadEnd> deadEnds) {
        while (!queue.isEmpty()) {
            PipelineEntry cur = queue.pop();
            if (!visited.add(cur.state().stateNumber + ":" + cur.tokenIndex())) {
                continue;
            }

            if (cur.state().getStateType() == ATNState.RULE_STOP) {
                if (isAtCaret(cur.tokenIndex())) {
                    reachedCaretGlobal.set(true);
                    handlePreferredRules(cur.stack(), result);
                }
                ruleExits.add(cur.tokenIndex());
                continue;
            }

            boolean atCaret = isAtCaret(cur.tokenIndex());
            if (atCaret) {
                reachedCaretGlobal.set(true);
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
     * Preferred-rule quy về NGOÀI CÙNG nhất nếu lồng nhau: {@code resolve()}
     * quét từ ngoài vào trong trên TOÀN BỘ (stack hiện tại + rt.target), dừng
     * ở match đầu tiên gặp được — không đệ quy vào thân {@code rt.target} nữa
     * nếu đã có match, chỉ cần biết nó "rỗng" được không để đi tiếp qua
     * {@code followState}.
     * <p>
     * RESYNC RANH GIỚI RULE: nếu {@code rt.target} CHẾT HẲN (input sai ngữ
     * pháp thật, ví dụ gõ nhầm từ khoá bắt buộc), đừng để cái chết lan ngược
     * lên caller — nhảy tới vị trí token gần nhất mà CALLER
     * ({@code rt.followState}) chấp nhận được, giống cách
     * {@code DefaultErrorStrategy} của ANTLR parser thật recover. CHỈ áp dụng
     * khi KHÔNG ở caret (tại caret cần chết đúng chỗ để gợi ý chính xác).
     */
    protected void handleRuleDoor(RuleTransition rt, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
        if (atCaret) {
            RuleCallStack withTarget = cur.stack().copy();
            withTarget.push(rt.target.ruleIndex, cur.tokenIndex());
            if (PreferredRuleResolver.resolve(withTarget, preferredRules, result)) {
                if (isNullable(rt.target)) {
                    queue.push(new PipelineEntry(rt.followState, cur.tokenIndex(), cur.stack()));
                }
                return;
            }
        }

        Set<Integer> exits = enterRule(rt.target, cur.tokenIndex(), cur.stack());

        if (resyncActiveThisPass && exits.isEmpty() && !atCaret) {
            int resyncIndex = resyncSkipper.findResyncPoint(rt.followState, tokens, cur.tokenIndex());
            if (resyncIndex != -1) {
                exits = Collections.singleton(resyncIndex);
            }
        }

        for (int exitTok : exits) {
            queue.push(new PipelineEntry(rt.followState, exitTok, cur.stack()));
        }
    }

    protected void handleFreeDoorWithCondition(PredicateTransition pt, PipelineEntry cur, Deque<PipelineEntry> queue) {
        if (pt.getPredicate().eval(parser, ParserRuleContext.EMPTY)) {
            queue.push(new PipelineEntry(pt.target, cur.tokenIndex(), cur.stack()));
        }
    }

    protected void handleFreeDoor(Transition t, PipelineEntry cur, Deque<PipelineEntry> queue) {
        queue.push(new PipelineEntry(t.target, cur.tokenIndex(), cur.stack()));
    }

    /** Cửa "gõ gì cũng được" (dấu `.` trong grammar) — label() của nó luôn null, nên cần xử lý riêng thay vì rơi vào handlePasswordDoor. */
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
            // Sai mật khẩu -> nhánh này chết ở đây. Chỉ GHI NHỚ, không resync
            // ngay (xem lý do ở javadoc walkRuleBody).
            deadEnds.add(new DeadEnd(cur.state(), cur.tokenIndex(), label, t.target, cur.stack()));
        }
    }

    /** 1 điểm chết tại 1 cửa mật khẩu — ghi nhớ đủ thông tin để thử resync SAU, không resync ngay lúc phát hiện. */
    protected record DeadEnd(ATNState from, int fromTokenIndex, IntervalSet label, ATNState target, RuleCallStack stack) {
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