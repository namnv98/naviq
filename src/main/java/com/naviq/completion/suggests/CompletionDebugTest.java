package com.naviq.completion.suggests;

import com.naviq.antlr4.PostgreSQLParser;
import com.naviq.completion.syntactic.AntlrCompletionEngine;
import com.naviq.completion.syntactic.SyntacticAnalyzer;

import java.util.*;

public class CompletionDebugTest {

    public static void main(String[] args) {
        String sql = "select * from public.y where a.s and ";
        int cursorOffset = sql.length();

        var syntacticResults = SyntacticAnalyzer.analyze(sql, cursorOffset);

        System.out.println("Rules matched: " + syntacticResults.candidates().rules.keySet().stream().map(id -> PostgreSQLParser.ruleNames[id]).toList());
        for (var e : syntacticResults.candidates().rules.entrySet()) {
            System.out.println(PostgreSQLParser.ruleNames[e.getKey()] + " path: " + e.getValue().stream().map(f -> PostgreSQLParser.ruleNames[f.ruleId()]).toList());
        }


        var candidates = syntacticResults.candidates();
        Map<Integer, List<AntlrCompletionEngine.RuleFrame>> rulesMatched = candidates.rules;
        Map<Integer, Integer> ruleEntryTokenIndex = candidates.ruleEntryTokenIndex;

        Set<String> matchedRuleNames = KeywordNoiseFilter.computeMatchedRuleNames(syntacticResults, cursorOffset);

        System.out.println("\nFinal matchedRuleNames = " + matchedRuleNames);
    }


}