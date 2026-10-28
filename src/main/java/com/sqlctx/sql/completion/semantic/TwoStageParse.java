package com.sqlctx.sql.completion.semantic;

import com.sqlctx.sql.completion.CompletionBudget;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Parse 2 bước kiểu ANTLR khuyên dùng: SLL trước (nhanh hơn nhiều - grammar PL/SQL câu ~150 dòng: ~600ms -> ~12ms),
 * chỉ khi SLL báo lỗi cú pháp mới parse lại bằng LL. Câu không lỗi thì SLL cho đúng cây như LL; câu có lỗi thì
 * phục hồi lỗi của SLL khác LL (đã thấy alias bị mất ở "UPDATE users SET |") nên phải đi lại bằng LL.
 * <p>
 * Cả 2 lần chung 1 {@link CompletionBudget}: quá giờ thì ném {@link CompletionBudget.Exceeded} (caller coi như
 * không có scope).
 */
public final class TwoStageParse {

    private TwoStageParse() {
    }

    /** @param offendingTokens token bị error listener báo lỗi (xem {@link ScopeTree#ScopeTree(Set)}) */
    public record Parsed<T extends ParseTree>(T tree, Set<Integer> offendingTokens) {
    }

    public static <P extends Parser, T extends ParseTree> Parsed<T> parse(CommonTokenStream tokens,
                                                                         Function<TokenStream, P> newParser,
                                                                         Function<P, T> startRule) {
        return parse(tokens, newParser, startRule, CompletionBudget.millis);
    }

    /**
     * Như {@link #parse(CommonTokenStream, Function, Function)}, nhưng trước khi rơi xuống LL thì thử SLL trên
     * {@code alternative} (thường là {@link #withPlaceholderAfter}): câu đang gõ dở kết thúc bằng từ khoá ("... and|")
     * lỗi ở SLL, LL trên grammar PL/SQL câu dài mất hàng trăm ms - thêm 1 định danh giả sau caret thì câu hợp lệ,
     * SLL vài ms. Định danh giả nằm SAU caret nên không đổi scope tại caret.
     */
    public static <P extends Parser, T extends ParseTree> Parsed<T> parse(CommonTokenStream tokens, CommonTokenStream alternative,
                                                                         Function<TokenStream, P> newParser,
                                                                         Function<P, T> startRule) {
        long deadline = CompletionBudget.deadlineFromNow();
        Parsed<T> sll = parseWith(tokens, newParser, startRule, PredictionMode.SLL, deadline);
        if (sll.offendingTokens().isEmpty()) {
            return sll;
        }
        if (alternative != null) {
            Parsed<T> alt = parseWith(alternative, newParser, startRule, PredictionMode.SLL, deadline);
            if (alt.offendingTokens().isEmpty()) {
                return alt;
            }
        }
        return parseWith(tokens, newParser, startRule, PredictionMode.LL, deadline);
    }

    /**
     * Bản sao của {@code tokens} có thêm 1 token giả loại {@code type} ngay sau token {@code caretIndex}. Sao chép từng
     * token: CommonTokenStream gán lại chỉ số cho chính object token khi nạp - dùng chung object thì chỉ số của luồng
     * gốc lệch theo.
     */
    public static CommonTokenStream withPlaceholderAfter(CommonTokenStream tokens, int caretIndex, int type) {
        List<Token> copy = new ArrayList<>();
        for (Token t : tokens.getTokens()) {
            copy.add(new CommonToken(t));
        }
        if (caretIndex < 0 || caretIndex >= copy.size() || copy.get(caretIndex).getType() == Token.EOF) {
            return null;
        }
        Token anchor = copy.get(caretIndex);
        CommonToken placeholder = new CommonToken(new org.antlr.v4.runtime.misc.Pair<>(anchor.getTokenSource(), anchor.getInputStream()),
                type, Token.DEFAULT_CHANNEL, anchor.getStopIndex() + 1, anchor.getStopIndex());
        placeholder.setText("");
        copy.add(caretIndex + 1, placeholder);
        CommonTokenStream stream = new CommonTokenStream(new ListTokenSource(copy));
        stream.fill();
        return stream;
    }

    /** Như {@link #parse(CommonTokenStream, Function, Function)} với budget riêng (ms). */
    public static <P extends Parser, T extends ParseTree> Parsed<T> parse(CommonTokenStream tokens,
                                                                         Function<TokenStream, P> newParser,
                                                                         Function<P, T> startRule, long budgetMs) {
        long deadline = System.nanoTime() + budgetMs * 1_000_000;
        Parsed<T> sll = parseWith(tokens, newParser, startRule, PredictionMode.SLL, deadline);
        return sll.offendingTokens().isEmpty() ? sll : parseWith(tokens, newParser, startRule, PredictionMode.LL, deadline);
    }

    private static <P extends Parser, T extends ParseTree> Parsed<T> parseWith(CommonTokenStream tokens,
                                                                              Function<TokenStream, P> newParser,
                                                                              Function<P, T> startRule,
                                                                              PredictionMode mode, long deadline) {
        tokens.seek(0);
        P parser = newParser.apply(tokens);
        parser.getInterpreter().setPredictionMode(mode);
        Set<Integer> offendingTokens = new HashSet<>();
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object offendingSymbol,
                                    int l, int c, String m, RecognitionException e) {
                if (offendingSymbol instanceof Token t) {
                    offendingTokens.add(t.getTokenIndex());
                }
            }
        });
        parser.addParseListener(new org.antlr.v4.runtime.tree.ParseTreeListener() {
            @Override
            public void enterEveryRule(ParserRuleContext ctx) {
                if (System.nanoTime() > deadline) {
                    throw new CompletionBudget.Exceeded();
                }
            }

            @Override
            public void exitEveryRule(ParserRuleContext ctx) {
            }

            @Override
            public void visitTerminal(org.antlr.v4.runtime.tree.TerminalNode node) {
            }

            @Override
            public void visitErrorNode(org.antlr.v4.runtime.tree.ErrorNode node) {
            }
        });
        return new Parsed<>(startRule.apply(parser), offendingTokens);
    }
}
