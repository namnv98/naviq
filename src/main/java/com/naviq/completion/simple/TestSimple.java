package com.naviq.completion.simple;

import com.naviq.antlr4.PostgreSQLParser;
import com.naviq.antlr4.PostgreSQLLexer;
import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.*;

import java.util.*;

public class TestSimple {
    public static void main(String[] args) {
        String sql = "CREATE SCHEMA IF ";

        CharStream input = CharStreams.fromString(sql);
        PostgreSQLLexer lexer = new PostgreSQLLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        PostgreSQLParser parser = new PostgreSQLParser(tokenStream);
        Vocabulary vocabulary = parser.getVocabulary();

        PostgreSQLParser.RootContext root;
        try {
            root = parser.root();
        } catch (Exception e) {
            root = parser.getContext() instanceof PostgreSQLParser.RootContext
                    ? (PostgreSQLParser.RootContext) parser.getContext()
                    : null;
        }

        tokenStream.fill();

        int caretTokenIndex = findCaretTokenIndex(tokenStream, sql.length());

        // =====================================================
        // C3
        // =====================================================

        CodeCompletionCore c3 = new CodeCompletionCore(
                parser,
                buildPreferredRules().keySet(),
                buildIgnoredTokens().keySet()
        );

        CodeCompletionCore.CandidatesCollection c3Result =
                c3.collectCandidates(caretTokenIndex, root);

        // =====================================================
        // Simple
        // =====================================================

        AntlrCompletionEngineSimple engine =
                new AntlrCompletionEngineSimple(
                        parser,
                        buildIgnoredTokens(),
                        buildPreferredRules());

        Set<Integer> simpleTokens = engine.collectCandidates(caretTokenIndex);

        Set<Integer> simpleRules = engine.getSuggestedRules();

        // =====================================================
        // Print C3
        // =====================================================

        System.out.println("\n========== C3 ==========");

        System.out.println("Rules:");
        c3Result.rules.keySet().stream()
                .sorted()
                .forEach(r ->
                        System.out.println("  " + parser.getRuleNames()[r]));

        System.out.println("Tokens:");
        c3Result.tokens.keySet().stream()
                .filter(Objects::nonNull)
                .forEach(t ->
                        System.out.println("  " + vocabulary.getSymbolicName(t)));

        // =====================================================
        // Print Simple
        // =====================================================

        System.out.println("\n========== Simple ==========");

        System.out.println("Rules:");
        simpleRules.stream()
                .sorted()
                .forEach(r ->
                        System.out.println("  " + parser.getRuleNames()[r]));

        System.out.println("Tokens:");
        simpleTokens.stream()
                .sorted(Comparator.comparing(vocabulary::getSymbolicName))
                .forEach(t ->
                        System.out.println("  " + vocabulary.getSymbolicName(t)));

        // =====================================================
        // Compare Rules
        // =====================================================

        System.out.println("\n========== Compare Rules ==========");

        Set<Integer> onlyC3Rules = new java.util.TreeSet<>(c3Result.rules.keySet());
        onlyC3Rules.removeAll(simpleRules);

        Set<Integer> onlySimpleRules = new java.util.TreeSet<>(simpleRules);
        onlySimpleRules.removeAll(c3Result.rules.keySet());

        Set<Integer> bothRules = new java.util.TreeSet<>(c3Result.rules.keySet());
        bothRules.retainAll(simpleRules);

        System.out.println("Both:");
        bothRules.forEach(r ->
                System.out.println("  " + parser.getRuleNames()[r]));

        System.out.println("Only C3:");
        onlyC3Rules.forEach(r ->
                System.out.println("  " + parser.getRuleNames()[r]));

        System.out.println("Only Simple:");
        onlySimpleRules.forEach(r ->
                System.out.println("  " + parser.getRuleNames()[r]));

        // =====================================================
        // Compare Tokens
        // =====================================================

        System.out.println("\n========== Compare Tokens ==========");

        Set<Integer> onlyC3Tokens = new java.util.TreeSet<>(c3Result.tokens.keySet());
        onlyC3Tokens.removeAll(simpleTokens);

        Set<Integer> onlySimpleTokens = new java.util.TreeSet<>(simpleTokens);
        onlySimpleTokens.removeAll(c3Result.tokens.keySet());

        Set<Integer> bothTokens = new java.util.TreeSet<>(c3Result.tokens.keySet());
        bothTokens.retainAll(simpleTokens);

        System.out.println("Both:");
        bothTokens.stream()
                .sorted(Comparator.comparing(vocabulary::getSymbolicName))
                .forEach(t ->
                        System.out.println("  " + vocabulary.getSymbolicName(t)));

        System.out.println("Only C3:");
        onlyC3Tokens.stream()
                .sorted(Comparator.comparing(vocabulary::getSymbolicName))
                .forEach(t ->
                        System.out.println("  " + vocabulary.getSymbolicName(t)));

        System.out.println("Only Simple:");
        onlySimpleTokens.stream()
                .sorted(Comparator.comparing(vocabulary::getSymbolicName))
                .forEach(t ->
                        System.out.println("  " + vocabulary.getSymbolicName(t)));
    }


    private static Map<Integer, Boolean> buildIgnoredTokens() {
        Map<Integer, Boolean> m = new HashMap<>();
        m.put(Token.EOF, true);
        return m;
    }

    private static Map<Integer, Boolean> buildPreferredRules() {
        Map<Integer, Boolean> m = new HashMap<>();
        m.put(PostgreSQLParser.RULE_qualified_name, true);
        m.put(PostgreSQLParser.RULE_any_name, true);
        m.put(PostgreSQLParser.RULE_columnref, true);
        m.put(PostgreSQLParser.RULE_typename, true);
        m.put(PostgreSQLParser.RULE_func_name, true);
        m.put(PostgreSQLParser.RULE_table_alias, true);
        m.put(PostgreSQLParser.RULE_colid, true);
        return m;
    }


    public static int findCaretTokenIndex(CommonTokenStream tokenStream, int cursorCharPos) {
        tokenStream.fill();
        var tokens = tokenStream.getTokens();
        for (int i = 0; i < tokens.size() - 1; i++) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (t.getStartIndex() <= cursorCharPos && cursorCharPos <= t.getStopIndex()) return i;
            if (t.getStartIndex() > cursorCharPos) return i;
        }
        return tokens.size() - 1;
    }
}
