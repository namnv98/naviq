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
 * Ý tưởng:
 * <ul>
 *   <li><b>Token đã gõ</b> là các token từ đầu câu tới trước caret. <b>Token caret</b> (phần tử cuối của
 *       {@link #tokens}) là token đang được gõ dở — nó KHÔNG được "ăn", ta hỏi xem chỗ đó có thể là gì.</li>
 *   <li>{@link #enterRule} trả lời câu hỏi: "vào rule R tại token i thì ra khỏi R được ở những token nào?"
 *       (tập <i>exit</i>). Lần đi không chạm caret chỉ phụ thuộc (R, i) nên được nhớ trong {@link #ruleExitCache}.</li>
 *   <li>{@link #walkRuleBody} đi trong thân 1 rule: gặp transition khớp token đã gõ thì tiến 1 token,
 *       gặp lời gọi rule con thì hỏi {@link #enterRule}, gặp epsilon thì đi tiếp không tốn token.</li>
 *   <li>Khi tới caret không còn token nào để khớp: mọi token mà transition chấp nhận chính là gợi ý
 *       (ghi vào {@code result.tokens}); rule nằm trong {@code preferredRules} được ghi thành rule thay vì
 *       bung ra từng token (xem {@link PreferredRuleResolver}).</li>
 * </ul>
 * Không có phục hồi lỗi: token đã gõ không khớp ATN thì nhánh đó chết, cho 0 gợi ý.
 * <p>
 * Hai lớp con chỉ khác nhau ở 3 hook: cách tính tập exit (còn token / tại caret) và "rule rỗng được không".
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
     * ruleIndex -> (token index lúc vào rule -> các token index có thể ra khỏi rule). Chỉ lưu những lần đi
     * KHÔNG chạm caret: lần đi chạm caret sinh gợi ý phụ thuộc call stack, nên phải đi lại với từng caller.
     */
    protected final Map<Integer, Map<Integer, Set<Integer>>> ruleExitCache = new HashMap<>();

    /**
     * Số lần đã đi tới caret. Giá trị tuyệt đối không có ý nghĩa: {@link #enterRule} chỉ so trước/sau khi tính
     * 1 rule, tăng lên nghĩa là lần tính đó (kể cả các rule con bên trong) có tới caret.
     */
    private long caretTouches;

    public CompletionEngineBase(Parser parser, Map<Integer, Boolean> ignoredTokens, Map<Integer, Boolean> preferredRules) {
        this.parser = parser;
        this.atn = parser.getATN();
        this.ignoredTokens = ignoredTokens;
        this.preferredRules = preferredRules;
    }

    // ════════════════════════════════════════════════════════════════
    // ĐIỂM VÀO
    // ════════════════════════════════════════════════════════════════

    public CandidatesResult collectCandidates(int caretTokenIndex) {
        result = new CandidatesResult();
        ruleExitCache.clear();
        caretTouches = 0;
        tokens = readTokens(parser.getTokenStream(), caretTokenIndex);

        int startRuleIndex = 0;
        enterRule(atn.ruleToStartState[startRuleIndex], 0, new RuleCallStack());
        return result;
    }

    protected boolean isAtCaret(int tokenIndex) {
        return tokenIndex >= tokens.size() - 1;
    }

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 1 — vào 1 rule tại 1 token: tính các token index có thể thoát ra
    // ════════════════════════════════════════════════════════════════

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
        // Grammar ANTLR không có đệ quy trái nên không thể quay lại đúng (rule, token) này trong lúc đang tính;
        // đặt tạm tập rỗng chỉ để chắc chắn không lặp vô hạn nếu điều đó xảy ra.
        exitsByEntryToken.put(tokenIndex, Collections.emptySet());

        long touchesBefore = caretTouches;
        Set<Integer> exits = computeExitsNotAtCaret(start, tokenIndex, callStackInto(start, tokenIndex, caller));
        boolean reachedCaret = caretTouches > touchesBefore;
        if (reachedCaret) {
            // Gợi ý vừa sinh phụ thuộc call stack của caller này -> không cache, caller khác phải tự đi lại.
            exitsByEntryToken.remove(tokenIndex); // bỏ tập rỗng đặt tạm ở trên
        } else {
            exitsByEntryToken.put(tokenIndex, exits);
        }
        return exits;
    }

    private static RuleCallStack callStackInto(ATNState ruleStart, int tokenIndex, RuleCallStack caller) {
        RuleCallStack entered = caller.copy();
        entered.push(ruleStart.ruleIndex, tokenIndex);
        return entered;
    }

    // ── 3 hook: chỗ 2 chế độ khác nhau ────────────────────────────────

    /** Còn token để khớp: các token index có thể thoát khỏi rule. Mặc định đi thẳng trong thân rule. */
    protected Set<Integer> computeExitsNotAtCaret(ATNState start, int tokenIndex, RuleCallStack entered) {
        return walkRuleBody(start, tokenIndex, entered);
    }

    /**
     * Rule được vào ngay tại caret: sinh gợi ý (ghi vào {@code result}) rồi trả về {@code {tokenIndex}}
     * nếu rule có thể rỗng, ngược lại tập rỗng. Mặc định đi thẳng trong thân rule.
     */
    protected Set<Integer> computeExitsAtCaret(ATNState start, int tokenIndex, RuleCallStack entered) {
        return walkRuleBody(start, tokenIndex, entered);
    }

    /** Từ {@code state} có tới được cuối rule mà không cần khớp token nào không. */
    protected abstract boolean isNullable(ATNState state);

    // ════════════════════════════════════════════════════════════════
    // BƯỚC 2 — đi trong thân 1 rule: duyệt (DFS, dùng Deque như stack) các transition của ATN
    // ════════════════════════════════════════════════════════════════

    /** Vị trí trong lúc đi: đang ở state nào của ATN, đã "ăn" tới token nào. */
    private record Position(ATNState state, int tokenIndex) {
        long key() {
            return ((long) state.stateNumber << 32) | tokenIndex;
        }
    }

    /**
     * Đi trong thân rule bắt đầu ở {@code start}; trả về các token index mà tại đó tới được cuối rule.
     * {@code stack} là đường gọi tới rule này, cố định trong suốt lần đi.
     */
    protected Set<Integer> walkRuleBody(ATNState start, int startTokenIndex, RuleCallStack stack) {
        Set<Integer> exits = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        Deque<Position> queue = new ArrayDeque<>();
        queue.push(new Position(start, startTokenIndex));

        while (!queue.isEmpty()) {
            Position cur = queue.pop();
            if (!visited.add(cur.key())) {
                continue;
            }
            if (isAtCaret(cur.tokenIndex())) {
                caretTouches++;
            }

            if (cur.state().getStateType() == ATNState.RULE_STOP) {
                if (isAtCaret(cur.tokenIndex())) {
                    reportPreferredRule(stack);
                }
                exits.add(cur.tokenIndex());
                continue;
            }

            for (Transition t : cur.state().getTransitions()) {
                followTransition(t, cur, stack, queue);
            }
        }
        return exits;
    }

    private void followTransition(Transition t, Position cur, RuleCallStack stack, Deque<Position> queue) {
        if (t instanceof RuleTransition rt) {
            followRuleCall(rt, cur, stack, queue);
        } else if (t instanceof PredicateTransition pt) {
            if (AtnPredicates.holds(parser, pt)) {
                queue.push(new Position(pt.target, cur.tokenIndex()));
            }
        } else if (t instanceof WildcardTransition) {
            followWildcard(t, cur, stack, queue);
        } else if (t.isEpsilon()) {
            queue.push(new Position(t.target, cur.tokenIndex()));
        } else {
            followTokenMatch(t, cur, stack, queue);
        }
    }

    /** Ghi {@code stack} vào {@code result} nếu nó đi qua 1 preferred rule; true nếu đã ghi. */
    private boolean reportPreferredRule(RuleCallStack stack) {
        return PreferredRuleResolver.resolve(stack, preferredRules, result);
    }

    /** Transition gọi rule con: đi qua rule con rồi tiếp tục từ {@code followState} ở mọi token index thoát ra được. */
    private void followRuleCall(RuleTransition rt, Position cur, RuleCallStack stack, Deque<Position> queue) {
        if (isAtCaret(cur.tokenIndex())) {
            RuleCallStack withCallee = stack.copy();
            withCallee.push(rt.target.ruleIndex, cur.tokenIndex());
            if (reportPreferredRule(withCallee)) {
                // Preferred rule được báo thành rule, không bung thành token. Chỉ đi tiếp qua nó khi nó có thể rỗng.
                if (isNullable(rt.target)) {
                    queue.push(new Position(rt.followState, cur.tokenIndex()));
                }
                return;
            }
        }
        for (int exit : enterRule(rt.target, cur.tokenIndex(), stack)) {
            queue.push(new Position(rt.followState, exit));
        }
    }

    /** Dấu {@code .} trong grammar (khớp token bất kỳ) — {@code label()} của nó là null nên không đi qua {@link #followTokenMatch}. */
    private void followWildcard(Transition t, Position cur, RuleCallStack stack, Deque<Position> queue) {
        if (!isAtCaret(cur.tokenIndex())) {
            queue.push(new Position(t.target, cur.tokenIndex() + 1));
            return;
        }
        if (reportPreferredRule(stack)) {
            return;
        }
        for (int type : IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType).toList()) {
            if (!ignoredTokens.containsKey(type)) {
                result.addToken(type, Collections.emptyList());
            }
        }
    }

    /** Transition khớp token (Atom / Set / NotSet): trước caret thì phải khớp token đã gõ, tại caret thì là gợi ý. */
    private void followTokenMatch(Transition t, Position cur, RuleCallStack stack, Deque<Position> queue) {
        IntervalSet label = t.label();
        if (label == null || label.size() == 0) {
            return;
        }
        if (t instanceof NotSetTransition) {
            label = label.complement(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType);
        }

        if (!isAtCaret(cur.tokenIndex())) {
            if (label.contains(tokens.get(cur.tokenIndex()).type())) {
                queue.push(new Position(t.target, cur.tokenIndex() + 1));
            }
            return;
        }

        if (reportPreferredRule(stack)) {
            return;
        }
        List<Integer> types = label.toList();
        // Chỉ khi transition có đúng 1 token thì mới biết chắc chuỗi token đi liền sau nó.
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
