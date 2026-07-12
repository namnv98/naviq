package com.naviq;


import com.naviq.antlr4.oracle.PlSqlLexer;
import com.naviq.antlr4.oracle.PlSqlParser;
import com.naviq.completion.syntactic.feature.RuleCallStack;
import com.naviq.oracle.OracleSQLSyntacticAnalyzer;
import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.*;

public class MainC3 {

    public static void main(String[] args) {

        String sql = "select * from users where  ";

        // ===================== My Engine =====================

        OracleSQLSyntacticAnalyzer.Result my = OracleSQLSyntacticAnalyzer.analyze(sql, sql.length());

        // ===================== ANTLR4-C3 =====================

        CharStream input = CharStreams.fromString(sql);

        PlSqlLexer lexer = new PlSqlLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);

        PlSqlParser parser = new PlSqlParser(tokenStream);
        parser.removeErrorListeners();

        ParserRuleContext root = parser.unit_statement();


        tokenStream.fill();

        int caretTokenIndex = tokenStream.size() - 1;



        CodeCompletionCore core = new CodeCompletionCore(
                parser,
                OracleSQLSyntacticAnalyzer.buildPreferredRules().keySet(),
                OracleSQLSyntacticAnalyzer.buildIgnoredTokens().keySet()
        );

        var c3 = core.collectCandidates(caretTokenIndex, root);

        Vocabulary vocabulary = parser.getVocabulary();

        // ============================================================
        // RULES
        // ============================================================

        Set<Integer> myRules = new TreeSet<>(my.candidates().rules.keySet());
        Set<Integer> c3Rules = new TreeSet<>(c3.rules.keySet());

        printRules("My Rules", my.candidates().rules, parser);
        printC3Rules("ANTLR4-C3 Rules", c3.rules, parser);

        compareRules(myRules, c3Rules, parser);

        // ============================================================
        // TOKENS
        // ============================================================

        Set<Integer> myTokens = new TreeSet<>(my.candidates().tokens.keySet());
        Set<Integer> c3Tokens = new TreeSet<>(c3.tokens.keySet());

        printTokens("My Tokens", my.candidates().tokens, vocabulary);
        printC3Tokens("ANTLR4-C3 Tokens", c3.tokens, vocabulary);

        compareTokens(myTokens, c3Tokens, vocabulary);
    }

    // ============================================================

    private static void printRules(
            String title,
            java.util.Map<Integer, List<RuleCallStack.RuleFrame>> rules,
            PlSqlParser parser) {

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
            PlSqlParser parser) {

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
            PlSqlParser parser) {

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