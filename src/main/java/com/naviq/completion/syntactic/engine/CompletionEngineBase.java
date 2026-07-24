package com.naviq.completion.syntactic.engine;

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

public abstract class CompletionEngineBase {

    protected final Parser parser;
    protected final ATN atn;

    protected final Map<Integer, Boolean> ignoredTokens;
    protected final Map<Integer, Boolean> preferredRules;

    protected List<InputToken> tokens;
    protected int tokenStartIndex;

    protected CandidatesResult result;
    protected final Map<Integer, Map<Integer, Set<Integer>>> ruleExitCache = new HashMap<>();


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
        return collectCandidates(caretTokenIndex, null);
    }

    public CandidatesResult collectCandidates(int caretTokenIndex, ParserRuleContext context) {
        result = new CandidatesResult();
        ruleExitCache.clear();

        tokenStartIndex = context != null ? context.start.getTokenIndex() : 0;
        int startRuleIndex = context != null ? context.getRuleIndex() : 0;
        tokens = readTokens(parser.getTokenStream(), tokenStartIndex, caretTokenIndex);

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
     * Quét {@code stack} tìm mê cung đặc biệt (ngoài cùng nhất nếu lồng nhau), ghi nhận vào {@code result} nếu tìm thấy.
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
        Deque<PipelineEntry> queue = new ArrayDeque<>();
        queue.push(new PipelineEntry(start, startTokenIndex, stack));
        while (!queue.isEmpty()) {
            PipelineEntry cur = queue.pop();
            if (!visited.add(cur.state().stateNumber + ":" + cur.tokenIndex())) {
                continue;
            }

            if (cur.state().getStateType() == ATNState.RULE_STOP) {
                boolean atCaretHere = isAtCaret(cur.tokenIndex());
                if (atCaretHere) {
                    handlePreferredRules(cur.stack(), result);
                }
                ruleExits.add(cur.tokenIndex());
                continue;
            }

            boolean atCaret = isAtCaret(cur.tokenIndex());
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
                    handlePasswordDoor(t, cur, atCaret, queue);
                }
            }
        }
        return ruleExits;
    }

    protected void handleRuleDoor(RuleTransition rt, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
        if (atCaret) {
            RuleCallStack withTarget = cur.stack().copy();
            withTarget.push(rt.target.ruleIndex, cur.tokenIndex());
            if (PreferredRuleResolver.resolve(withTarget, preferredRules, result)) {
                if (isNullable(rt.target)) {
                    queue.push(new PipelineEntry(rt.followState, cur.tokenIndex(), cur.stack()));
                }
                // Không nullable -> rule con này còn "nợ" ít nhất 1 token, không thể hoàn thành ngay tại caret -> không push gì thêm, dừng ở đây.
                return;
            }
            // resolve() không tìm thấy preferred-rule nào (kể cả rt.target không preferred) -> đi tiếp bình thường, đệ quy vào enterRule như dưới.
        }

        for (int exitTok : enterRule(rt.target, cur.tokenIndex(), cur.stack())) {
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

    protected void handlePasswordDoor(Transition t, PipelineEntry cur, boolean atCaret, Deque<PipelineEntry> queue) {
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
            // Sai mật khẩu -> nhánh này chết ở đây, không push gì cả (giữ nguyên hành vi thuật toán).
        }
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