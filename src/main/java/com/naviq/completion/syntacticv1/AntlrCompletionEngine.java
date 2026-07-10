package com.naviq.completion.syntacticv1;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;

/**
 * Duyệt ATN của một parser ANTLR4 để gợi ý completion (token tiếp theo có thể gõ,
 * và/hoặc rule "ưu tiên" nào đang được gõ dở) tại một vị trí caret cho trước.
 * <p>
 * Ý tưởng chính: từ rule bắt đầu, đi theo các transition trong ATN đồng thời so khớp
 * với các token thật đã có trong input, cho tới khi hết token (chạm caret). Tại đó,
 * mọi thứ còn "mở" (chưa buộc phải là 1 token cụ thể) chính là các candidate cần gợi ý.
 */
public class AntlrCompletionEngine {

    public final Map<Integer, Boolean> ignoredTokens;
    private Map<Integer, Boolean> preferredRules;

    private final Parser parser;
    private final ATN atn;
    private final FollowSetsByState followSetsByState;

    // ── Trạng thái riêng cho mỗi lần gọi collectCandidates (reset ở đầu mỗi lần gọi) ──
    private CandidatesCollection candidates;
    private List<InputToken> tokens;
    private int tokenStartIndex;

    /**
     * Cache kết quả traverseATN theo (ruleIndex -> tokenIndex -> tập các vị trí token
     * mà rule có thể kết thúc). Đây vừa là tối ưu hiệu năng (rule được gọi lặp lại nhiều
     * lần, ví dụ biểu thức đệ quy, chỉ cần tính 1 lần cho mỗi tokenIndex), vừa là điều
     * kiện chặn đệ quy vô hạn:
     * <p>
     * ANTLR4 đã loại bỏ đệ quy trái (left recursion) khi build ATN, nên một rule không
     * bao giờ tự gọi lại chính nó (trực tiếp hay gián tiếp) TẠI ĐÚNG vị trí token mà
     * chưa tiêu thụ token nào ở giữa. Nói cách khác: rule chỉ có thể được vào lại ở
     * những vị trí token tăng dần — mà số vị trí token là hữu hạn (bị chặn bởi độ dài
     * input) — nên riêng cache này đã đủ đảm bảo quá trình duyệt luôn kết thúc, không
     * cần thêm bộ đếm độ sâu nào khác.
     */
    private Map<Integer, Map<Integer, Set<Integer>>> shortcutMap;

    public AntlrCompletionEngine(Parser parser,
                                 Map<Integer, Boolean> ignoredTokens,
                                 Map<Integer, Boolean> preferredRules,
                                 FollowSetsByState followSetsByState) {
        this.parser = Objects.requireNonNull(parser);
        this.atn = parser.getATN();
        this.ignoredTokens = Objects.requireNonNull(ignoredTokens);
        this.preferredRules = Objects.requireNonNull(preferredRules);
        this.followSetsByState = Objects.requireNonNull(followSetsByState);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public CandidatesCollection collectCandidates(int caretTokenIndex) {
        return collectCandidates(caretTokenIndex, null);
    }

    /**
     * @param context nếu khác null, giới hạn việc duyệt bắt đầu từ rule chứa context này
     *                (thay vì luôn duyệt lại từ rule gốc của toàn bộ input) — hữu ích khi
     *                đã biết trước caret nằm trong 1 sub-rule cụ thể, giúp tránh phải
     *                duyệt lại phần input phía trước không liên quan.
     */
    public CandidatesCollection collectCandidates(int caretTokenIndex, ParserRuleContext context) {
        if (caretTokenIndex < 0) {
            throw new IllegalArgumentException("caretTokenIndex must be >= 0");
        }

        candidates = new CandidatesCollection();
        shortcutMap = new HashMap<>();
        tokenStartIndex = context != null ? context.start.getTokenIndex() : 0;
        int startRuleIndex = context != null ? context.getRuleIndex() : 0;
        tokens = readTokens(parser.getTokenStream(), tokenStartIndex, caretTokenIndex);

        traverseATN(atn.ruleToStartState[startRuleIndex], 0, new RuleCallStack());
        computeRulePositions();

        return candidates;
    }

    // ── Token stream ──────────────────────────────────────────────────────────

    /** Đọc các token từ {@code tokenStartIndex} cho tới caret (bao gồm cả token tại caret, nếu có). */
    private static List<InputToken> readTokens(TokenStream stream, int tokenStartIndex, int caretTokenIndex) {
        int saved = stream.index();
        stream.seek(tokenStartIndex);
        List<InputToken> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            var t = stream.LT(i);
            result.add(new InputToken(t.getType(), t.getStartIndex(), t.getStopIndex()));
            if (t.getTokenIndex() >= caretTokenIndex || t.getType() == Token.EOF) {
                break;
            }
        }
        stream.seek(saved);
        return result;
    }

    // ── Rule start/end offset post-processing ──────────────────────────────────

    /**
     * Với mỗi preferred rule thực sự được chạm tới trong lúc duyệt, tìm lần xuất hiện
     * GẦN CARET NHẤT của nó trong {@link #shortcutMap} (tức bắt đầu ở tokenIndex lớn
     * nhất), rồi dịch cặp (token bắt đầu, token kết thúc) đó sang offset ký tự trong
     * văn bản gốc — để người gọi biết chính xác đoạn text nào tương ứng với rule đó.
     */
    private void computeRulePositions() {
        for (int ruleId : preferredRules.keySet()) {
            Map<Integer, Set<Integer>> positionMap = shortcutMap.get(ruleId);
            if (positionMap == null || positionMap.isEmpty()) {
                continue;
            }

            int startToken = Collections.max(positionMap.keySet());
            Set<Integer> endSet = positionMap.get(startToken);
            int endToken = endSet.isEmpty() ? tokens.size() - 1 : Collections.max(endSet);

            int startOffset = tokens.get(startToken).startPosition();
            int endOffset;
            if (tokens.get(endToken).type() == Token.EOF) {
                // Token cuối là EOF: lấy luôn cả khoảng trắng còn lại tới EOF.
                endOffset = tokens.get(endToken).startPosition();
            } else {
                // Dừng ngay sau token liền trước, không bao gồm khoảng trắng thừa ở cuối.
                endOffset = tokens.get(Math.max(endToken - 1, 0)).stopPosition() + 1;
            }

            candidates.rulePositions.put(ruleId, Arrays.asList(startOffset, endOffset));
        }
    }

    // ── ATN traversal ─────────────────────────────────────────────────────────

    /**
     * Duyệt vào 1 rule (bắt đầu tại ATN state {@code start}, tại vị trí token
     * {@code tokenIndex}) và trả về tập các vị trí token mà rule này CÓ THỂ kết thúc —
     * để rule cha (nơi đã gọi rule này) biết những vị trí nào cần tiếp tục duyệt tiếp.
     * <p>
     * 3 khả năng xảy ra, theo đúng thứ tự kiểm tra bên dưới:
     * <ol>
     *   <li>Đã tính rồi (cache hit) → trả lại luôn kết quả cũ.</li>
     *   <li>Đã hết token thật (đang đúng tại vị trí caret) → thu thập candidate tại đây,
     *       KHÔNG cần đi tiếp vào ATN nữa.</li>
     *   <li>Còn token thật để so khớp → chạy BFS thật sự trên ATN, vừa so khớp token
     *       vừa dò tiếp các rule con.</li>
     * </ol>
     */
    private Set<Integer> traverseATN(ATNState start, int tokenIndex, RuleCallStack stack) {
        Map<Integer, Set<Integer>> positionMap = shortcutMap.computeIfAbsent(start.ruleIndex, k -> new HashMap<>());
        Set<Integer> cached = positionMap.get(tokenIndex);
        if (cached != null) {
            return cached;
        }

        followSetsByState.ensureComputed(parser, start, ignoredTokens);
        FollowSetsHolder followSets = followSetsByState.get(start.stateNumber, ignoredTokens);

        RuleCallStack enteredStack = stack.copy();
        enteredStack.push(start.ruleIndex, tokenIndex);

        boolean atCaret = tokenIndex >= tokens.size() - 1;
        if (atCaret) {
            collectAtCaret(start.ruleIndex, enteredStack, followSets);
            // Rule "có thể kết thúc ngay tại đây" khi và chỉ khi EPSILON nằm trong follow-set
            // của nó — nghĩa là không còn gì bắt buộc phải gõ thêm để rule này hợp lệ.
            Set<Integer> result = followSets.combined().contains(Token.EPSILON)
                    ? Collections.singleton(tokenIndex)
                    : Collections.emptySet();
            positionMap.put(tokenIndex, result);
            return result;
        }

        InputToken currentToken = tokens.get(tokenIndex);
        boolean tokenIsCompatibleWithRule =
                followSets.combined().contains(Token.EPSILON) || followSets.combined().contains(currentToken.type());
        if (!tokenIsCompatibleWithRule) {
            // Token thật tiếp theo không khớp với bất cứ thứ gì rule này chấp nhận
            // -> nhánh này của grammar không áp dụng được, dừng ngay không cần duyệt tiếp.
            Set<Integer> result = Collections.emptySet();
            positionMap.put(tokenIndex, result);
            return result;
        }

        Set<Integer> result = runBFS(start, tokenIndex, enteredStack);
        positionMap.put(tokenIndex, result);
        return result;
    }

    /**
     * Tại đúng vị trí caret: thu thập mọi token/rule khả dĩ tiếp theo, dựa trên follow-set
     * (đã tính sẵn, thuần cấu trúc grammar) của rule đang đứng.
     */
    private void collectAtCaret(int enteringRuleIndex, RuleCallStack stack, FollowSetsHolder followSets) {
        // Bản thân rule đang đứng đã là 1 preferred rule -> ghi nhận luôn, không cần
        // đào sâu thêm vào các nhánh follow-set bên trong nó nữa.
        if (preferredRules.containsKey(enteringRuleIndex)) {
            recordPreferredRuleIfPresent(stack);
            return;
        }

        for (FollowSetWithPath branch : followSets.sets()) {
            RuleCallStack fullPath = stack.copy();
            fullPath.appendPath(branch.path());

            // Nhánh follow-set này đi qua 1 preferred rule khác -> ưu tiên ghi nhận rule đó,
            // bỏ qua việc liệt kê từng token cụ thể của nhánh này (không cần thiết nữa).
            if (recordPreferredRuleIfPresent(fullPath)) {
                continue;
            }

            for (int symbol : branch.intervals().toList()) {
                if (ignoredTokens.containsKey(symbol)) {
                    continue;
                }
                recordTokenCandidate(symbol, branch.following());
            }
        }
    }

    /** Ghi nhận 1 token candidate, gộp "following" nếu cùng token đến từ nhiều nhánh khác nhau. */
    private void recordTokenCandidate(int symbol, List<Integer> following) {
        List<Integer> existing = candidates.tokens.get(symbol);
        if (existing == null) {
            candidates.tokens.put(symbol, new ArrayList<>(following));
        } else if (!existing.equals(following)) {
            // Cùng 1 token nhưng "following" khác nhau tùy nhánh -> không còn chắc chắn
            // token nào sẽ theo sau nữa, coi như ambiguous (không gợi ý following).
            candidates.tokens.put(symbol, Collections.emptyList());
        }
    }

    /**
     * Duyệt theo kiểu BFS trên các transition của ATN, vừa so khớp token thật vừa dò
     * vào rule con khi gặp {@link RuleTransition}. Trả về tập vị trí token nơi rule có
     * thể dừng (tức chạm {@code RULE_STOP}).
     */
    private Set<Integer> runBFS(ATNState start, int startTokenIndex, RuleCallStack stack) {
        Set<Integer> endIndices = new HashSet<>();
        Set<String> visitedStateAndToken = new HashSet<>();
        Deque<PipelineEntry> queue = new ArrayDeque<>();
        queue.push(new PipelineEntry(start, startTokenIndex, stack));

        while (!queue.isEmpty()) {
            PipelineEntry cur = queue.pop();
            if (!visitedStateAndToken.add(cur.state().stateNumber + ":" + cur.tokenIndex())) {
                continue; // đã xử lý đúng (state, tokenIndex) này rồi, tránh lặp vô ích
            }

            boolean atCaret = cur.tokenIndex() >= tokens.size() - 1;

            if (cur.state().getStateType() == ATNState.RULE_STOP) {
                endIndices.add(cur.tokenIndex());
                continue;
            }
            if (atCaret) {
                candidates.caretStates.add(cur.state());
            }
            for (Transition t : cur.state().getTransitions()) {
                processTransition(t, cur, atCaret, queue, endIndices);
            }
        }
        return endIndices;
    }

    private void processTransition(Transition t, PipelineEntry cur, boolean atCaret,
                                   Deque<PipelineEntry> queue, Set<Integer> endIndices) {
        RuleCallStack stack = cur.stackSnapshot();

        if (t instanceof RuleTransition rt) {
            // Gọi vào rule con: mọi vị trí mà rule con có thể kết thúc chính là những vị trí
            // ta tiếp tục duyệt tiếp từ "followState" (điểm quay lại rule cha).
            for (int end : traverseATN(rt.target, cur.tokenIndex(), stack)) {
                queue.push(new PipelineEntry(rt.followState, end, stack));
            }
            return;
        }

        if (t instanceof PredicateTransition pt) {
            if (checkPredicate(pt)) {
                queue.push(new PipelineEntry(t.target, cur.tokenIndex(), stack));
            }
            return;
        }

        if (t instanceof WildcardTransition) {
            if (!atCaret) {
                queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, stack));
                return;
            }
            // Tại caret, wildcard nghĩa là "bất kỳ token nào cũng được" -> gợi ý toàn bộ
            // token hợp lệ (trừ những token bị ignore), trừ khi rule cha đã là preferred rule.
            if (!recordPreferredRuleIfPresent(stack)) {
                for (int symbol : IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType).toList()) {
                    if (!ignoredTokens.containsKey(symbol)) {
                        candidates.tokens.putIfAbsent(symbol, Collections.emptyList());
                    }
                }
            }
            return;
        }

        if (t.isEpsilon()) {
            if (atCaret) {
                recordPreferredRuleIfPresent(stack);
            }
            queue.push(new PipelineEntry(t.target, cur.tokenIndex(), stack));
            return;
        }

        // Còn lại: transition tiêu thụ 1 (khoảng) token cụ thể — AtomTransition,
        // SetTransition, NotSetTransition, RangeTransition.
        IntervalSet label = t.label();
        if (label == null || label.size() == 0) {
            return;
        }
        if (t instanceof NotSetTransition) {
            label = label.complement(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
        }

        if (!atCaret) {
            if (label.contains(tokens.get(cur.tokenIndex()).type())) {
                queue.push(new PipelineEntry(t.target, cur.tokenIndex() + 1, stack));
            }
            return;
        }

        if (recordPreferredRuleIfPresent(stack)) {
            return;
        }
        List<Integer> symbols = label.toList();
        // Chỉ xác định được "following" (chuỗi token chắc chắn theo sau) khi label chỉ ứng
        // với đúng 1 token cụ thể — nếu có nhiều lựa chọn thì following không còn ý nghĩa rõ ràng.
        List<Integer> following = symbols.size() == 1 ? getFollowingTokens(t) : Collections.emptyList();
        for (int symbol : symbols) {
            if (!ignoredTokens.containsKey(symbol)) {
                candidates.tokens.put(symbol, following);
            }
        }
    }

    /**
     * Quét toàn bộ call-stack (từ ngoài vào trong) tìm preferred rule ĐẦU TIÊN gặp được,
     * và ghi nhận nó vào kết quả nếu occurrence này "đáng tin" hơn occurrence đã ghi trước
     * đó (xem {@link #isMoreRelevant}).
     *
     * @return true nếu có ít nhất 1 preferred rule được tìm thấy trong stack (bất kể có ghi
     *         đè occurrence cũ hay không) — caller dùng giá trị này để quyết định có cần
     *         liệt kê thêm token cụ thể nữa hay không (không cần, nếu đã "trúng" 1 preferred rule).
     */
    private boolean recordPreferredRuleIfPresent(RuleCallStack stack) {
        if (preferredRules.isEmpty()) {
            return false;
        }
        List<RuleFrame> frames = stack.frames(); // outer -> inner
        for (int i = 0; i < frames.size(); i++) {
            RuleFrame frame = frames.get(i);
            if (!preferredRules.containsKey(frame.ruleId())) {
                continue;
            }
            List<RuleFrame> pathToThisRule = new ArrayList<>(frames.subList(0, i));
            Integer existingEntryIndex = candidates.ruleEntryTokenIndex.get(frame.ruleId());
            if (isMoreRelevant(frame.tokenIndex(), existingEntryIndex)) {
                candidates.rules.put(frame.ruleId(), pathToThisRule);
                candidates.ruleEntryTokenIndex.put(frame.ruleId(), frame.tokenIndex());
            }
            return true; // dừng lại ở rule NGOÀI CÙNG đầu tiên khớp — không cần đi sâu hơn
        }
        return false;
    }

    /**
     * Một preferred rule có thể được chạm tới qua nhiều đường đi ATN khác nhau tại cùng
     * 1 vị trí caret — ví dụ 1 từ khóa cũng hợp lệ như 1 identifier bình thường, nên rule
     * có thể được vào 1 lần qua traversal thật (đã "ăn" từ khóa đó như identifier), và
     * riêng biệt qua follow-set closure tĩnh cho thấy rule ĐÓ cũng tới được mà không cần
     * tiêu thụ thêm token nào (đánh dấu bằng {@link RuleFrame#NO_TOKEN}).
     * <p>
     * Occurrence dạng NO_TOKEN luôn "đáng tin" nhất cho mục đích completion — nó nghĩa là
     * "đây chính xác là thứ cần gõ tiếp theo" — nên luôn thắng occurrence thật (dù
     * occurrence thật có tokenIndex lớn thế nào). Giữa 2 occurrence thật, cái gần caret
     * hơn (tokenIndex lớn hơn) thắng, vì phản ánh đúng trạng thái parse mới nhất.
     */
    private static boolean isMoreRelevant(int candidateTokenIndex, Integer existingTokenIndex) {
        if (existingTokenIndex == null) {
            return true;
        }
        if (candidateTokenIndex == RuleFrame.NO_TOKEN) {
            return existingTokenIndex != RuleFrame.NO_TOKEN;
        }
        if (existingTokenIndex == RuleFrame.NO_TOKEN) {
            return false;
        }
        return candidateTokenIndex > existingTokenIndex;
    }

    private List<Integer> getFollowingTokens(Transition t) {
        return FollowSetComputer.getFollowingTokens(t, ignoredTokens);
    }

    private boolean checkPredicate(PredicateTransition t) {
        return t.getPredicate().eval(parser, ParserRuleContext.EMPTY);
    }
}