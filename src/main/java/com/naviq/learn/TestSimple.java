package com.naviq.learn;


import com.naviq.antlr4.oracle.PlSqlLexer;
import com.naviq.antlr4.oracle.PlSqlParser;
import com.naviq.oracle.OracleSQLSyntacticAnalyzer;
import org.antlr.v4.runtime.*;
import com.vmware.antlr4c3.CodeCompletionCore;

import java.util.*;

public class TestSimple {
    public static void main(String[] args) {
        String sql = "select * from users where ";

        CharStream input = CharStreams.fromString(sql);
        PlSqlLexer lexer = new PlSqlLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(tokenStream);
        Vocabulary vocabulary = parser.getVocabulary();
        ParserRuleContext root = parser.unit_statement();
        tokenStream.fill();
        int caretTokenIndex = findCaretTokenIndex(tokenStream, sql.length());

        Map<Integer, Boolean> m = new HashMap<>();
        m.put(PlSqlParser.RULE_tableview_name, true);
        m.put(PlSqlParser.RULE_query_name, true);
        m.put(PlSqlParser.RULE_index_name, true);
        m.put(PlSqlParser.RULE_sequence_name, true);
        m.put(PlSqlParser.RULE_synonym_name, true);
        m.put(PlSqlParser.RULE_trigger_name, true);
        m.put(PlSqlParser.RULE_type_name, true);
        m.put(PlSqlParser.RULE_package_name, true);
        m.put(PlSqlParser.RULE_procedure_name, true);
        m.put(PlSqlParser.RULE_general_element, true);
        m.put(PlSqlParser.RULE_column_name, true);
        m.put(PlSqlParser.RULE_type_spec, true);
        m.put(PlSqlParser.RULE_datatype, true);
        m.put(PlSqlParser.RULE_function_name, true);
        m.put(PlSqlParser.RULE_table_alias, true);
        m.put(PlSqlParser.RULE_identifier, true);

        m.put(PlSqlParser.RULE_id_expression, true);
        m.put(PlSqlParser.RULE_regular_id, true);

        // C3
        CodeCompletionCore c3 = new CodeCompletionCore(parser, m.keySet(), Set.of());
        CodeCompletionCore.CandidatesCollection c3Result = c3.collectCandidates(caretTokenIndex, root);
        System.out.println("c3 tokens: " + c3Result.tokens.size());
        System.out.println("c3 rules:");
        c3Result.rules.keySet().stream()
                .sorted()
                .forEach(r ->
                        System.out.println("  " + parser.getRuleNames()[r]));


        // Simple
        AntlrCompletionEngineSimple_Test engine = new AntlrCompletionEngineSimple_Test(parser, Map.of(), m);
        Set<Integer> simpleTokens = engine.collectCandidates(caretTokenIndex);
        Set<Integer> simpleRules = engine.getSuggestedRules();

        System.out.println("simple tokens: " + simpleTokens.size());
        System.out.println("simple rules:");
        simpleRules.stream()
                .sorted()
                .forEach(r ->
                        System.out.println("  " + parser.getRuleNames()[r]));

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
