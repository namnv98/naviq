package com.sqlctx.completion.syntactic.engine;

import com.sqlctx.completion.model.CandidatesResult;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test engine trên grammar nhỏ dựng lúc chạy. Mỗi case chạy với cả 2 thứ tự alternative vì
 * thứ tự duyệt ATN không được làm thay đổi kết quả.
 */
class CompletionEngineBaseTest {

    private static final LexerGrammar LEXER;

    static {
        try {
            LEXER = new LexerGrammar("""
                    lexer grammar L;
                    X : 'x' ;
                    Y : 'y' ;
                    WS : [ \\t]+ -> skip ;
                    """);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private record Engine(String name, BiFunction<Parser, Map<Integer, Boolean>, CompletionEngineBase> factory) {
    }

    private static Stream<Engine> engines() {
        return Stream.of(
                new Engine("default", (p, preferred) -> new CompletionEngineDefault(p, Map.of(), preferred)),
                new Engine("flowset", (p, preferred) -> new CompletionEngineWithFlowSet(p, Map.of(), preferred)));
    }

    private static Grammar grammar(String text) throws Exception {
        return new Grammar(text, LEXER);
    }

    private static CandidatesResult complete(Engine engine, Grammar g, String input, int caretTokenIndex, String... preferredRules) {
        var tokens = new CommonTokenStream(LEXER.createLexerInterpreter(CharStreams.fromString(input)));
        tokens.fill();
        Parser parser = g.createParserInterpreter(tokens);
        Map<Integer, Boolean> preferred = new HashMap<>();
        for (String r : preferredRules) {
            preferred.put(g.getRule(r).index, true);
        }
        return engine.factory().apply(parser, preferred).collectCandidates(caretTokenIndex);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("#1 rule r vào tại cùng token từ 2 đường (qua preferred p / qua q): cache không được nuốt gợi ý của đường thứ 2")
    void ruleExitCacheDoesNotDropSuggestionsOfSecondCaller(boolean swapAlternatives) throws Exception {
        String alts = swapAlternatives ? "q | p" : "p | q";
        Grammar g = grammar("""
                parser grammar T;
                start : (%s) EOF ;
                p : r ;
                q : r ;
                r : X Y ;
                """.formatted(alts));
        int p = g.getRule("p").index;
        int y = g.getTokenType("Y");

        engines().forEach(engine -> {
            // "x|" — r vào ở token 0 (trước caret) qua cả p lẫn q, caret ở token 1.
            CandidatesResult result = complete(engine, g, "x", 1, "p");
            assertTrue(result.rules.containsKey(p), engine.name() + ": thiếu preferred rule p, rules=" + result.rules);
            assertEquals(0, result.ruleEntryTokenIndex.get(p), engine.name());
            assertTrue(result.tokens.containsKey(y), engine.name() + ": thiếu token Y (đường qua q), tokens=" + result.tokens);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("#2 token vừa đến từ transition thường (có following) vừa từ wildcard: following phải bị bỏ")
    void wildcardClearsFollowingTokens(boolean swapAlternatives) throws Exception {
        String alts = swapAlternatives ? "b | a" : "a | b";
        Grammar g = grammar("""
                parser grammar T;
                start : (%s) EOF ;
                a : X Y ;
                b : . ;
                """.formatted(alts));
        int x = g.getTokenType("X");

        engines().forEach(engine -> {
            CandidatesResult result = complete(engine, g, "", 0);
            assertEquals(List.of(), result.tokens.get(x), engine.name() + ": tokens=" + result.tokens);
        });
    }

    @Test
    @DisplayName("#3 gọi cùng 1 rule con rỗng được 2 lần liền: follow-set không được dừng ở lần gọi thứ 2")
    void followSetContinuesPastRepeatedNullableRule() throws Exception {
        Grammar g = grammar("""
                parser grammar T;
                start : a EOF ;
                a : b b Y ;
                b : X? ;
                """);
        int x = g.getTokenType("X");
        int y = g.getTokenType("Y");

        engines().forEach(engine -> {
            CandidatesResult result = complete(engine, g, "", 0);
            assertEquals(Set.of(x, y), result.tokens.keySet(), engine.name() + ": tokens=" + result.tokens);
        });
    }

    @Test
    @DisplayName("#4 token sau 1 preferred rule rỗng được: path của token không được còn chứa rule con đã ra")
    void tokenAfterNullablePreferredRuleIsNotSwallowed() throws Exception {
        Grammar g = grammar("""
                parser grammar T;
                start : a EOF ;
                a : b Y ;
                b : X? ;
                """);
        int b = g.getRule("b").index;
        int y = g.getTokenType("Y");

        engines().forEach(engine -> {
            CandidatesResult result = complete(engine, g, "", 0, "b");
            assertTrue(result.rules.containsKey(b), engine.name() + ": thiếu preferred rule b, rules=" + result.rules);
            assertTrue(result.tokens.containsKey(y), engine.name() + ": thiếu token Y, tokens=" + result.tokens);
        });
    }
}
