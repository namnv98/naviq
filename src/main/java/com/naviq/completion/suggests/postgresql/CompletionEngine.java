package com.naviq.completion.suggests.postgresql;

import com.naviq.antlr4.postgresql.PostgreSQLParser;
import com.naviq.completion.suggests.CompletionInputPreparer;
import com.naviq.completion.suggests.DerivedColumnExpander;
import com.naviq.completion.suggests.SuggestFilter;
import com.naviq.completion.syntactic.engine.feature.RuleCallStack;
import com.naviq.datasource.SchemaIndex;
import com.naviq.model.Suggest;
import com.naviq.completion.syntactic.PostgreSQLSyntacticAnalyzer;
import com.naviq.completion.semantic.postgresql.SemanticAnalyzer;
import org.antlr.v4.runtime.Token;
import com.naviq.util.LoggingConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

/**
 * Orchestrator - CHỈ điều phối, không tự chứa logic parse/resolve nào. Toàn bộ chi
 * tiết nằm ở các class chuyên trách:
 * <p>
 * SchemaIndex          - biết schema có gì (bảng, cột, hàm, kiểu dữ liệu)
 * SemanticAnalyzer      - tầng ngữ nghĩa (SemanticScope: alias/scope/CTE/subquery)
 * SyntacticAnalyzer     - tầng cú pháp (AntlrCompletionEngine: token/rule hợp lệ)
 * KeywordNoiseFilter    - lọc noise (định danh đã đóng bằng khoảng trắng)
 * <p>
 * 2 tầng (Semantic/Syntactic) ĐỘC LẬP, không tầng nào thay được tầng kia.
 */
public class CompletionEngine {
    private static final Logger LOG = LoggingConfig.of(CompletionEngine.class);

    public static List<Suggest> suggests(CompletionInputPreparer.PrepareCompletionInput input) {
        var suggests = suggests(input.sql(), input.cursor());
        return SuggestFilter.filter(suggests, input.prefix(), input.dotMode());
    }

    public static List<Suggest> suggests(String sql, Integer cursorCharPos) {
        var suggests = new ArrayList<Suggest>();
        if (isNull(cursorCharPos)) {
            cursorCharPos = sql.length();
        }
        int cursorOffset = cursorCharPos;
        SemanticAnalyzer.Result semanticResult = SemanticAnalyzer.analyze(sql, cursorOffset);

        char charBeforeCursor = (cursorOffset > 0 && cursorOffset <= sql.length()) ? sql.charAt(cursorOffset - 1) : ' ';
        boolean stillMidIdentifier = Character.isLetterOrDigit(charBeforeCursor) || charBeforeCursor == '_';
        int syntacticCursor = stillMidIdentifier ? cursorOffset - 1 : cursorOffset;
        PostgreSQLSyntacticAnalyzer.Result syntacticResults = PostgreSQLSyntacticAnalyzer.analyze(sql, syntacticCursor);

        for (var entry : syntacticResults.candidates().tokens.entrySet()) {
            int tokenType = entry.getKey();
            List<Integer> following = entry.getValue();   // <-- đây, chuỗi mật khẩu chắc chắn theo sau
            addKeywordSuggestions(suggests, tokenType, following);
        }

        Set<String> matchedRuleNames = KeywordNoiseFilter.computeMatchedRuleNames(syntacticResults, syntacticCursor);

        if (matchedRuleNames.contains("typename")) {
            addDataTypeSuggestions(suggests);
        }

        if (matchedRuleNames.contains("table_alias")) {
            addTableAliasSuggestions(suggests, syntacticResults, semanticResult);
        }

        if (matchedRuleNames.contains("any_name")) {
            addTableNameSuggestions(suggests, syntacticResults);
        }

        if (matchedRuleNames.contains("qualified_name")) {
            addTableNameSuggestions(suggests, syntacticResults);
        }

        if (matchedRuleNames.contains("columnref")) {
            addColumnSuggestions(suggests, semanticResult);
        }

        boolean isColidAlias = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_relation_expr_opt_alias); // DELETE FROM ... (colid = alias)
        boolean isColidDropTarget = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_alter_table_cmd);  // ALTER TABLE ... DROP COLUMN (colid = tên cột bị xoá)
        boolean isColumnrefColumn = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_columnref);   // WHERE u.| (colid bên trong columnref)
        boolean isColidIndexColumn = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_index_elem); // CREATE INDEX ... (col) (colid = cột lập index)
        boolean isColidSetTarget = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_set_target); // UPDATE SET / INSERT ON CONFLICT DO UPDATE SET (colid = assignment-target)
        boolean isColidUsingClauseColumn = isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_join_qual); // JOIN ... USING (col1, col2) (colid = cột chung 2 bảng)
        boolean isColidInsert = isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_insertstmt); // JOIN ... USING (col1, col2) (colid = cột chung 2 bảng)

        if (matchedRuleNames.contains("colid") && (isColidDropTarget || isColumnrefColumn || isColidIndexColumn || isColidSetTarget || isColidUsingClauseColumn || isColidInsert)) {
            addColumnSuggestions(suggests, semanticResult);
        }

        return suggests;
    }

    private static boolean isRuleInContext(PostgreSQLSyntacticAnalyzer.Result syn, int ruleId, int expectedParentRuleId) {
        List<RuleCallStack.RuleFrame> path = syn.candidates().rules.get(ruleId);
        if (path == null || path.isEmpty()) return false;
        // Frame CUỐI CÙNG trong path (ancestor gần nhất) chính là rule cha
        // trực tiếp đã dẫn tới rule này - đây là thứ quyết định ngữ nghĩa.
        int immediateParent = path.get(path.size() - 1).ruleId();
        return immediateParent == expectedParentRuleId;
    }

    private static boolean isRuleAncestorAnywhere(PostgreSQLSyntacticAnalyzer.Result syn, int ruleId, int ancestorRuleIdToFind) {
        List<RuleCallStack.RuleFrame> path = syn.candidates().rules.get(ruleId);
        if (path == null) return false;
        return path.stream().anyMatch(f -> f.ruleId() == ancestorRuleIdToFind);
    }

    private static void addKeywordSuggestions(List<Suggest> suggests, Integer key, List<Integer> following) {
        String text = PostgreSQLParser.VOCABULARY.getDisplayName(key).toLowerCase().replace("'", "");

        if (following != null && !following.isEmpty()) {
            text += " " + following.stream()
                    .map(f -> PostgreSQLParser.VOCABULARY.getDisplayName(f).toLowerCase().replace("'", ""))
                    .collect(Collectors.joining(" "));
            // ví dụ: key=NOT, following=[EXISTS] -> text = "not exists"
        }

        suggests.add(Suggest.of(text, "keyword"));
    }

    private static void addDataTypeSuggestions(List<Suggest> suggests) {
        SchemaIndex.DATA_TYPES.forEach(t -> suggests.add(Suggest.of(t, "datatype", t)));
    }

    private static void addTableAliasSuggestions(List<Suggest> suggests, PostgreSQLSyntacticAnalyzer.Result syn, SemanticAnalyzer.Result sem) {
        var tableName = AliasNameSuggester.extractTableBeforeAs(syn.tokenStream(), syn.caretTokenIndex());
        if (tableName != null) {
            String alias = AliasNameSuggester.suggestAlias(sem.visibleAliases(), tableName);
            suggests.add(Suggest.of(alias, "alias"));
        }
    }

    private static void addColumnSuggestions(List<Suggest> suggests, SemanticAnalyzer.Result sem) {
        SchemaIndex.FUNCTIONS.forEach(fn -> suggests.add(Suggest.of(fn, "function")));
        if (sem.qualifier() != null) {
            String qualifier = sem.qualifier();
            if (sem.qualifierDerivedScope() != null) {
                DerivedColumnExpander.addDerivedColumns(suggests, qualifier, sem.qualifierDerivedScope());
            } else if (sem.qualifierResolvesTo() != null) {
                SchemaIndex.getColumnsOfTable(sem.qualifierResolvesTo()).forEach(c -> suggests.add(Suggest.of(qualifier + "." + c.name(), "column", c.dataType())));
            } else {
                SchemaIndex.getColumnsOfTable(qualifier).forEach(c -> suggests.add(Suggest.of(qualifier + "." + c.name(), "column", c.dataType())));
            }
        } else if (!sem.visibleAliases().isEmpty()) {
            sem.visibleAliases().forEach((alias, table) -> {
                var derived = sem.visibleDerivedScopes().get(alias);
                if (derived != null) {
                    DerivedColumnExpander.addDerivedColumns(suggests, alias, derived);
                } else {
                    SchemaIndex.getColumnsOfTable(table).forEach(c -> suggests.add(Suggest.of(alias + "." + c.name(), "column", c.dataType())));
                }
            });
        }
    }

    private static void addTableNameSuggestions(List<Suggest> suggests, PostgreSQLSyntacticAnalyzer.Result syn) {
        int caretTokenIndex = syn.caretTokenIndex();
        var tokenStream = syn.tokenStream();
        if (caretTokenIndex >= 2) {
            Token tok = tokenStream.get(caretTokenIndex - 1);
            if (tok.getType() == PostgreSQLParser.DOT) {
                Token prev = tokenStream.get(caretTokenIndex - 2);
                if (prev.getType() == PostgreSQLParser.Identifier) {
                    String schema = prev.getText();
                    SchemaIndex.getTablesBySchema(schema).forEach(t -> suggests.add(Suggest.of(t.fullName(), t.kind())));
                    return;
                }
            }
        }
        SchemaIndex.SCHEMA_TABLE_INDEX.values().forEach(t -> suggests.add(Suggest.of(t.fullName(), t.kind())));
    }
}