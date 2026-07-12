package com.naviq.oracle.suggests;

import com.naviq.antlr4.oracle.PlSqlParser;
import com.naviq.completion.model.Suggest;
import com.naviq.completion.syntactic.feature.RuleCallStack;
import com.naviq.datasource.SchemaIndex;
import com.naviq.oracle.OracleSQLSyntacticAnalyzer;
import com.naviq.oracle.semantic.SemanticAnalyzer;
import org.antlr.v4.runtime.Token;

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
 * SchemaIndex          - biết schema có gì (bảng, cột, hàm, kiểu dữ liệu) - DÙNG CHUNG mọi dialect
 * SemanticAnalyzer     - tầng ngữ nghĩa (com.naviq.oracle.semantic.SemanticScope: alias/scope/CTE/subquery)
 * OracleSQLSyntacticAnalyzer - tầng cú pháp (AntlrCompletionEngine generic + PlSqlParser cụ thể)
 * KeywordNoiseFilter, AliasNameSuggester - PHẢI là bản Oracle-specific riêng (không import được từ
 * package Postgres) - file này giả định chúng tồn tại đúng ở com.naviq.oracle.suggests (cùng
 * package), nhưng nội dung của chúng KHÔNG nằm trong phạm vi sửa lần này (chưa được cung cấp).
 * <p>
 * 2 tầng (Semantic/Syntactic) ĐỘC LẬP, không tầng nào thay được tầng kia.
 */
public class CompletionEngine {
    private static final Logger LOG = com.naviq.util.LoggingConfig.of(CompletionEngine.class);

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
        OracleSQLSyntacticAnalyzer.Result syntacticResults = OracleSQLSyntacticAnalyzer.analyze(sql, syntacticCursor);

        for (var entry : syntacticResults.candidates().tokens.entrySet()) {
            int tokenType = entry.getKey();
            List<Integer> following = entry.getValue();   // <-- chuỗi token chắc chắn theo sau
            addKeywordSuggestions(suggests, tokenType, following);
        }

        Set<String> matchedRuleNames = KeywordNoiseFilter.computeMatchedRuleNames(syntacticResults, syntacticCursor);

        // typename (Postgres) -> Oracle KHÔNG có rule tên "typename": type_spec là rule bao ngoài
        // (gồm cả REF/%ROWTYPE/%TYPE), datatype là kiểu dữ liệu "thuần" (NUMBER/VARCHAR2/...) -
        // check cả 2 vì tuỳ vị trí trong grammar sẽ khớp rule nào.
        if (matchedRuleNames.contains("type_spec") || matchedRuleNames.contains("datatype")) {
            addDataTypeSuggestions(suggests);
        }

        // table_alias - rule TÊN GIỐNG HỆT Postgres, đã verify tồn tại thật trong PlSqlParser.g4
        // ("table_alias : identifier | quoted_string ;"), không cần đổi.
        if (matchedRuleNames.contains("table_alias")) {
            addTableAliasSuggestions(suggests, syntacticResults, semanticResult);
        }

        // any_name / qualified_name (Postgres, 2 rule riêng nhưng CÙNG dùng để suggest bảng) ->
        // Oracle GỘP CHUNG thành 1 rule duy nhất "tableview_name" cho mọi vị trí tham chiếu bảng
        // (FROM, table_ref, ALTER TABLE, general_table_ref, CREATE INDEX...) - chỉ cần 1 check,
        // không cần 2 check trùng lặp như bản gốc.
        if (matchedRuleNames.contains("tableview_name")) {
            addTableNameSuggestions(suggests, syntacticResults);
        }

        // columnref (Postgres, 1 rule gộp chung mọi biểu thức cột) -> Oracle TÁCH thành 2 rule:
        // "general_element" (chain "t.col" trong biểu thức - SELECT list/WHERE/HAVING...) và
        // "column_name" (vị trí cột TRẦN - ORDER BY/GROUP BY/danh sách cột trong ngoặc).
        //
        // KHÁC BIỆT QUAN TRỌNG với Postgres: "column_name" của Oracle được TÁI DÙNG y hệt ở hầu
        // hết các vị trí mà Postgres cần tách riêng "colid" theo TỪNG parent-rule khác nhau (xem
        // các biến isColidXxx đã BỊ XOÁ bên dưới) - vd column_based_update_set_clause (SET),
        // paren_column_list (JOIN...USING, INSERT (col,...), ALTER TABLE DROP COLUMN),
        // index_expr (CREATE INDEX) ĐỀU dùng chung "column_name". Nên 1 check duy nhất
        // "column_name" đã phủ được tương đương ~5-6 check colid-theo-parent-rule của Postgres -
        // đây là ĐƠN GIẢN HOÁ THẬT SỰ nhờ grammar Oracle đồng nhất hơn ở điểm này, không phải bỏ
        // sót.
        //
        // "general_element" thì NGƯỢC LẠI vẫn bị overload giống "colid" (dùng cả cho cursor_name
        // và assignable_element - biến PL/SQL cục bộ, KHÔNG phải cột bảng) - vẫn cần loại trừ 2
        // trường hợp đó bằng parent-context giống cơ chế Postgres đã dùng.
        boolean isGeneralElementCursorName =
                isRuleAncestorAnywhere(syntacticResults, PlSqlParser.RULE_general_element, PlSqlParser.RULE_cursor_name);
        boolean isGeneralElementAssignTarget =
                isRuleAncestorAnywhere(syntacticResults, PlSqlParser.RULE_general_element, PlSqlParser.RULE_assignable_element);

        boolean shouldSuggestColumnsViaGeneralElement = matchedRuleNames.contains("general_element")
                && !isGeneralElementCursorName && !isGeneralElementAssignTarget;

        if (matchedRuleNames.contains("column_name") || matchedRuleNames.contains("general_element")) {
            addColumnSuggestions(suggests, semanticResult);
        }

        // Toàn bộ khối "colid + parent-context" của Postgres (isColidAlias/isColidDropTarget/
        // isColumnrefColumn/isColidIndexColumn/isColidSetTarget/isColidUsingClauseColumn/
        // isColidInsert) ĐÃ XOÁ - Oracle không có rule "colid", và như giải thích ở trên,
        // "column_name" của Oracle đã tự phủ được các ngữ cảnh tương đương mà không cần tách
        // theo từng parent-rule riêng.

        return suggests;
    }

    private static boolean isRuleAncestorAnywhere(OracleSQLSyntacticAnalyzer.Result syn, int ruleId, int ancestorRuleIdToFind) {
        List<RuleCallStack.RuleFrame> path = syn.candidates().rules.get(ruleId);
        if (path == null) return false;
        return path.stream().anyMatch(f -> f.ruleId() == ancestorRuleIdToFind);
    }

    private static void addKeywordSuggestions(List<Suggest> suggests, Integer key, List<Integer> following) {
        String text = PlSqlParser.VOCABULARY.getDisplayName(key).toLowerCase().replace("'", "");

        if (following != null && !following.isEmpty()) {
            text += " " + following.stream()
                    .map(f -> PlSqlParser.VOCABULARY.getDisplayName(f).toLowerCase().replace("'", ""))
                    .collect(Collectors.joining(" "));
        }

        suggests.add(Suggest.of(text, "keyword"));
    }

    private static void addDataTypeSuggestions(List<Suggest> suggests) {
//        SchemaIndex.DATA_TYPES.forEach(t -> suggests.add(Suggest.of(t, "datatype", t)));
    }

    private static void addTableAliasSuggestions(List<Suggest> suggests, OracleSQLSyntacticAnalyzer.Result syn, SemanticAnalyzer.Result sem) {
        var tableName = AliasNameSuggester.extractTableBeforeAs(syn.tokenStream(), syn.caretTokenIndex());
        if (tableName != null) {
            String alias = AliasNameSuggester.suggestAlias(sem.visibleAliases(), tableName);
            suggests.add(Suggest.of(alias, "alias"));
        }
    }

    private static void addColumnSuggestions(List<Suggest> suggests, SemanticAnalyzer.Result sem) {
//        SchemaIndex.FUNCTIONS.forEach(fn -> suggests.add(Suggest.of(fn, "function")));
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

    private static void addTableNameSuggestions(List<Suggest> suggests, OracleSQLSyntacticAnalyzer.Result syn) {
        int caretTokenIndex = syn.caretTokenIndex();
        var tokenStream = syn.tokenStream();
        if (caretTokenIndex >= 2) {
            Token tok = tokenStream.get(caretTokenIndex - 1);
            // DOT (Postgres) -> Oracle: dấu chấm là token PERIOD (xem PlSqlLexer.g4: "PERIOD: '.';").
            if (tok.getType() == PlSqlParser.PERIOD) {
                Token prev = tokenStream.get(caretTokenIndex - 2);
                // Identifier (Postgres, 1 token) -> Oracle có 2 loại identifier: REGULAR_ID (không
                // quote) và DELIMITED_ID (có quote "..."), tên schema có thể là 1 trong 2.
                if (prev.getType() == PlSqlParser.REGULAR_ID || prev.getType() == PlSqlParser.DELIMITED_ID) {
                    String schema = prev.getText();
                    SchemaIndex.getTablesBySchema(schema).forEach(t -> suggests.add(Suggest.of(t.fullName(), t.kind())));
                    return;
                }
            }
        }
        SchemaIndex.SCHEMA_TABLE_INDEX.values().forEach(t -> suggests.add(Suggest.of(t.fullName(), t.kind())));
    }
}