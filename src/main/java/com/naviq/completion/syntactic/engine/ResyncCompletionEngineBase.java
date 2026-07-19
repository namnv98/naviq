package com.naviq.completion.syntactic.engine;

import com.naviq.completion.syntactic.engine.feature.RuleResyncSkipper;
import com.naviq.completion.syntactic.engine.model.CandidatesResult;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.RuleTransition;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CỘNG THÊM khả năng hồi phục lỗi (resync) vào {@link CompletionEngineBase},
 * CHỈ qua 3 hook có sẵn ({@link #recoverIfNeeded}, {@link #recoverRuleDeadEnd},
 * {@link #onReachedCaret}) — KHÔNG sửa/copy lại 1 dòng nào của thuật toán BFS
 * gốc. Muốn hiểu đúng THUẬT TOÁN, đọc {@link CompletionEngineBase}. File này
 * chỉ cần đọc khi quan tâm PHẦN HỒI PHỤC LỖI.
 * <p>
 * Toàn bộ lý do thiết kế (2 lượt, dead-end ghi sổ, LỚP 1/LỚP 2, cờ toàn cục)
 * xem chi tiết ở từng method dưới.
 */
public abstract class ResyncCompletionEngineBase extends CompletionEngineBase {

    protected final RuleResyncSkipper resyncSkipper;
    /** Giấy phép dùng resync (constructor, bất biến) — khác {@link #resyncActiveThisPass} (công tắc thật, đổi theo từng lượt). */
    protected final boolean resyncEnabled;
    private boolean resyncActiveThisPass = false;
    /** Toàn cục cho 1 LƯỢT chạy: hễ đâu đó đã chạm caret sống, mọi rule con khác biết ngay và không tự ý resync nữa. */
    protected final AtomicBoolean reachedCaretGlobal = new AtomicBoolean(false);
    /** Số vòng resync tối đa — chặn input lỗi quá nặng làm nổ vòng lặp vô hạn. */
    protected static final int MAX_RESYNC_ROUNDS = 10;

    public ResyncCompletionEngineBase(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        this(parser, ignoredTokens, preferredRules, true);
    }

    public ResyncCompletionEngineBase(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules, boolean resyncEnabled) {
        super(parser, ignoredTokens, preferredRules);
        this.resyncSkipper = new RuleResyncSkipper(atn, 20);
        this.resyncEnabled = resyncEnabled;
    }

    // ════════════════════════════════════════════════════════════════
    // 2 LƯỢT TUẦN TỰ — override collectCandidates, KHÔNG override walkRuleBody
    // ════════════════════════════════════════════════════════════════

    /**
     * 2 LƯỢT TUẦN TỰ — đúng ở cấp TOÀN CỤC, không phụ thuộc thứ tự xử lý đệ
     * quy (khác cờ chia sẻ giữa các rule con, KHÔNG đủ — 1 rule con vẫn có
     * thể tự resync TRƯỚC KHI nhánh đúng phía sau kịp chạm caret).
     * <p>
     * LƯỢT 1 — sạch tuyệt đối, resync tắt hẳn (gọi lại {@code runOnePass} kế
     * thừa từ base — KHÔNG có logic mới, chỉ tắt công tắc). Ra được gợi ý là
     * DỪNG NGAY, không bao giờ chạm lượt 2 (tới đích rồi thì không cần mò nữa).
     * <p>
     * LƯỢT 2 — CHỈ chạy khi lượt 1 rỗng hoàn toàn (không có đường sống nào ở
     * bất kỳ đâu trong TOÀN BỘ câu) VÀ có giấy phép ({@link #resyncEnabled}).
     */
    @Override
    public CandidatesResult collectCandidates(int caretTokenIndex, ParserRuleContext context) {
        prepareTokens(caretTokenIndex, context);
        int startRuleIndex = startRuleIndexOf(context);

        resyncActiveThisPass = false;
        reachedCaretGlobal.set(false);
        CandidatesResult pass1 = runOnePass(startRuleIndex);
        if (!pass1.tokens.isEmpty() || !pass1.rules.isEmpty()) {
            return pass1; // tới đích được rồi -> không mò nữa
        }
        if (!resyncEnabled) {
            return pass1; // rỗng thật, không có giấy phép -> chịu
        }

        resyncActiveThisPass = true;
        reachedCaretGlobal.set(false);
        return runOnePass(startRuleIndex);
    }

    // ════════════════════════════════════════════════════════════════
    // HOOK 1 — sau khi 1 rule chết hẳn, thử vá dựa vào deadEnds đã ghi
    // ════════════════════════════════════════════════════════════════

    /**
     * {@code CompletionEngineBase} chỉ GHI NHỚ điểm chết vào {@code deadEnds}
     * (không tự đọc). Hook này mới THẬT SỰ dùng tới chúng — nhưng chỉ khi
     * đang ở lượt CÓ resync, rule vẫn rỗng, VÀ chưa nhánh nào (ở BẤT KỲ rule
     * nào khác) chạm caret sống. Lặp lại thử vá tối đa
     * {@link #MAX_RESYNC_ROUNDS} vòng (1 lần vá có thể lộ ra 1 cái chết MỚI
     * xa hơn, cần vá tiếp).
     */
    @Override
    protected Set<Integer> recoverIfNeeded(Set<String> visited, Set<Integer> ruleExits, List<DeadEnd> deadEnds) {
        for (int round = 0; round < MAX_RESYNC_ROUNDS && canAttemptResync(ruleExits, deadEnds); round++) {
            List<DeadEnd> thisRound = new ArrayList<>(deadEnds);
            deadEnds.clear(); // dead-end MỚI phát sinh trong vòng này -> gom cho vòng SAU

            Deque<PipelineEntry> resyncQueue = new ArrayDeque<>();
            for (DeadEnd de : thisRound) {
                resolveDeadEnd(de, resyncQueue);
            }

            if (!resyncQueue.isEmpty()) {
                bfsSweep(resyncQueue, visited, ruleExits, deadEnds);
            }
        }
        return ruleExits;
    }

    /** Chỉ được thử vá khi: đang trong lượt CÓ resync, rule vẫn CHẾT HẲN, CHƯA từng chạm caret sống, VÀ còn điểm chết để vá. */
    private boolean canAttemptResync(Set<Integer> ruleExits, List<DeadEnd> deadEnds) {
        return resyncActiveThisPass && ruleExits.isEmpty() && !reachedCaretGlobal.get() && !deadEnds.isEmpty();
    }

    /**
     * Thử vá 1 điểm chết, theo đúng thứ tự ưu tiên an toàn giảm dần:
     * <p>
     * LỚP 1 — tìm ĐÚNG loại token cửa này cần ở phía sau, coi khoảng giữa là
     * rác gõ nhầm. Chắc chắn (không đoán), không giới hạn số lần dùng.
     * <p>
     * LỚP 2 — không có bản sao nào -> coi cửa "bị bỏ qua miễn phí" (0-skip
     * theo follow-set đích đến). Là ĐOÁN, có thể sai; nếu sai sẽ tự chết ở
     * bước sau, không cần tự tay chặn — sống tới caret thì góp vào gợi ý.
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

    // ════════════════════════════════════════════════════════════════
    // HOOK 2 — resync ở ranh giới rule (RuleTransition chết hẳn)
    // ════════════════════════════════════════════════════════════════

    /**
     * {@code rt.target} chết hẳn (input sai ngữ pháp thật, ví dụ gõ nhầm từ
     * khoá bắt buộc) — nhảy tới vị trí token gần nhất mà CALLER
     * ({@code rt.followState}) chấp nhận được, giống
     * {@code DefaultErrorStrategy} của ANTLR thật recover.
     */
    @Override
    protected Set<Integer> recoverRuleDeadEnd(RuleTransition rt, PipelineEntry cur, Set<Integer> exits) {
        if (!resyncActiveThisPass) {
            return exits;
        }
        int resyncIndex = resyncSkipper.findResyncPoint(rt.followState, tokens, cur.tokenIndex());
        return resyncIndex != -1 ? Collections.singleton(resyncIndex) : exits;
    }

    // ════════════════════════════════════════════════════════════════
    // HOOK 3 — theo dõi cờ toàn cục
    // ════════════════════════════════════════════════════════════════

    @Override
    protected void onReachedCaret() {
        reachedCaretGlobal.set(true);
    }
}