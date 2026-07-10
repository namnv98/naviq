package com.naviq;

import com.naviq.antlr4.PostgreSQLLexer;
import com.naviq.antlr4.PostgreSQLParser;
import com.naviq.antlr4.PostgreSQLTestParser;
import com.naviq.completion.syntactic.AntlrCompletionEngine;
import com.naviq.completion.syntactic.AtnDotExporter;
import com.naviq.completion.syntactic.SyntacticAnalyzer;
import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class Main {

    public static void main(String[] args) {
        String sql = "select * from ";
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

        int caretTokenIndex = tokenStream.size() - 1;

        Set<Integer> preferredRules = Set.of(
                PostgreSQLParser.RULE_qualified_name,
                PostgreSQLParser.RULE_any_name,
                PostgreSQLParser.RULE_columnref,
                PostgreSQLParser.RULE_typename,
                PostgreSQLParser.RULE_func_name,
                PostgreSQLParser.RULE_table_alias,
                PostgreSQLParser.RULE_colid,
                PostgreSQLParser.RULE_reserved_keyword,
                PostgreSQLParser.RULE_unreserved_keyword,
                PostgreSQLParser.RULE_col_name_keyword,
                PostgreSQLParser.RULE_type_func_name_keyword,
                PostgreSQLParser.RULE_bare_label_keyword,
                PostgreSQLParser.RULE_plsql_unreserved_keyword
        );

        Map<Integer, Boolean> m = new HashMap<>();


        CodeCompletionCore core = new CodeCompletionCore(parser, preferredRules, m.keySet());

        var c3 = core.collectCandidates(caretTokenIndex, root);

        printC3Rules("ANTLR4-C3 Rules", c3.rules, parser);
        printC3Tokens("ANTLR4-C3 Tokens", c3.tokens, vocabulary);

    }

    // ============================================================

    private static void printRules(
            String title,
            java.util.Map<Integer, List<AntlrCompletionEngine.RuleFrame>> rules,
            PostgreSQLParser parser) {

        System.out.println();
        System.out.println("========== " + title + " ==========");

        if (rules.isEmpty()) {
            System.out.println("(none)");
            return;
        }

        for (var e : rules.entrySet()) {

            System.out.print(parser.getRuleNames()[e.getKey()]);

            if (!e.getValue().isEmpty()) {
                System.out.print(" <- ");

                for (int i = 0; i < e.getValue().size(); i++) {

                    var frame = e.getValue().get(i);

                    System.out.print(parser.getRuleNames()[frame.ruleId()]);

                    if (i + 1 < e.getValue().size()) {
                        System.out.print(" -> ");
                    }
                }
            }

            System.out.println();
        }
    }

    private static void printC3Rules(
            String title,
            java.util.Map<Integer, ?> rules,
            PostgreSQLParser parser) {

        System.out.println();
        System.out.println("========== " + title + " ==========");

        if (rules.isEmpty()) {
            System.out.println("(none)");
            return;
        }

        for (Integer rule : rules.keySet()) {
            System.out.println(parser.getRuleNames()[rule]);
        }
    }

    private static void printTokens(
            String title,
            java.util.Map<Integer, List<Integer>> tokens,
            Vocabulary vocabulary) {

        System.out.println();
        System.out.println("========== " + title + " ==========");

        if (tokens.isEmpty()) {
            System.out.println("(none)");
            return;
        }

        for (var e : tokens.entrySet()) {

            System.out.print(vocabulary.getDisplayName(e.getKey()));

            if (!e.getValue().isEmpty()) {

                System.out.print(" -> ");

                for (Integer t : e.getValue()) {
                    System.out.print(vocabulary.getDisplayName(t) + " ");
                }
            }

            System.out.println();
        }
    }

    private static void printC3Tokens(
            String title,
            java.util.Map<Integer, ?> tokens,
            Vocabulary vocabulary) {

        System.out.println();
        System.out.println("========== " + title + " ==========");

        if (tokens.isEmpty()) {
            System.out.println("(none)");
            return;
        }

        for (Integer token : tokens.keySet()) {
            System.out.println(vocabulary.getDisplayName(token));
        }
    }

    // ============================================================

    private static void compareRules(
            Set<Integer> mine,
            Set<Integer> c3,
            PostgreSQLParser parser) {

        Set<Integer> common = new TreeSet<>(mine);
        common.retainAll(c3);

        Set<Integer> onlyMine = new TreeSet<>(mine);
        onlyMine.removeAll(c3);

        Set<Integer> onlyC3 = new TreeSet<>(c3);
        onlyC3.removeAll(mine);

        System.out.println();
        System.out.println("========== Rule Compare ==========");

        System.out.println("Common:");
        common.forEach(r -> System.out.println("  " + parser.getRuleNames()[r]));

        System.out.println();

        System.out.println("Only Mine:");
        onlyMine.forEach(r -> System.out.println("  " + parser.getRuleNames()[r]));

        System.out.println();

        System.out.println("Only C3:");
        onlyC3.forEach(r -> System.out.println("  " + parser.getRuleNames()[r]));
    }

    private static void compareTokens(
            Set<Integer> mine,
            Set<Integer> c3,
            Vocabulary vocabulary) {

        Set<Integer> common = new TreeSet<>(mine);
        common.retainAll(c3);

        Set<Integer> onlyMine = new TreeSet<>(mine);
        onlyMine.removeAll(c3);

        Set<Integer> onlyC3 = new TreeSet<>(c3);
        onlyC3.removeAll(mine);

        System.out.println();
        System.out.println("========== Token Compare ==========");

        System.out.println("Common:");
        common.forEach(t -> System.out.println("  " + vocabulary.getDisplayName(t)));

        System.out.println();

        System.out.println("Only Mine:");
        onlyMine.forEach(t -> System.out.println("  " + vocabulary.getDisplayName(t)));

        System.out.println();

        System.out.println("Only C3:");
        onlyC3.forEach(t -> System.out.println("  " + vocabulary.getDisplayName(t)));
    }
}