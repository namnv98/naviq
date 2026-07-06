package com.naviq.completion.suggests;

import com.naviq.antlr4.*;
import com.naviq.datasource.SchemaIndex;
import com.naviq.completion.syntactic.AntlrCompletionEngine;
import com.naviq.completion.model.Suggest;
import com.naviq.completion.semantic.*;
import com.naviq.completion.syntactic.SyntacticAnalyzer;
import org.antlr.v4.runtime.Token;
import com.naviq.util.LoggingConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

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
        SyntacticAnalyzer.Result syntacticResults = SyntacticAnalyzer.analyze(sql, syntacticCursor);

        for (var entry : syntacticResults.candidates().tokens.entrySet()) {
            addKeywordSuggestions(suggests, entry.getKey());
        }

        boolean identifierClosedByGap = KeywordNoiseFilter.isIdentifierClosedByGap(sql, cursorOffset);

        // BUG FIX: gom TÊN rule (không phải rule index) vào 1 Set TRƯỚC khi xử lý, thay vì
        // switch trực tiếp trong vòng lặp entrySet() như trước. Nhiều rule index KHÁC
        // NHAU (vd "colid" và "columnref") có thể CÙNG match tại 1 vị trí caret (ambiguous
        // theo ATN - xem giải thích AntlrCompletionEngine: "where |" khớp cả 2 vì columnref
        // : colid indirection? nên colid là điểm bắt đầu của chính columnref). Nếu switch
        // chạy trực tiếp trong for-loop cũ, mỗi entry khớp case "columnref"/"colid" gọi lại
        // addColumnSuggestions() 1 lần riêng -> add TRÙNG LẶP toàn bộ danh sách cột khi CẢ
        // HAI cùng có mặt. Gom vào Set<String> trước rồi xử lý MỖI NHÓM HÀNH ĐỘNG đúng 1
        // lần, bất kể có bao nhiêu rule index khác nhau cùng khớp vào nhóm đó.
        Set<String> matchedRuleNames = new HashSet<>();
        for (Integer ruleIndex : syntacticResults.candidates().rules.keySet()) {
            matchedRuleNames.add(PostgreSQLParser.ruleNames[ruleIndex]);
        }

        boolean blocked = KeywordNoiseFilter.shouldBlockIdentifierContinuation(identifierClosedByGap, matchedRuleNames);

        if (!blocked && (matchedRuleNames.contains("columnref") || matchedRuleNames.contains("colid"))) {
            addColumnSuggestions(suggests, semanticResult);
        }
        if (matchedRuleNames.contains("typename")) {
            addDataTypeSuggestions(suggests);
        }
        if (matchedRuleNames.contains("table_alias")) {
            addTableAliasSuggestions(suggests, syntacticResults, semanticResult);
        }
        if (!blocked && (matchedRuleNames.contains("qualified_name") || matchedRuleNames.contains("any_name"))) {
            addTableNameSuggestions(suggests, syntacticResults);
        }

        return suggests;
    }

    private static void addKeywordSuggestions(List<Suggest> suggests, Integer key) {
        suggests.add(Suggest.of(PostgreSQLParser.VOCABULARY.getDisplayName(key).toLowerCase().replace("'", ""), "keyword"));
    }

    private static void addDataTypeSuggestions(List<Suggest> suggests) {
        SchemaIndex.DATA_TYPES.forEach(t -> suggests.add(Suggest.of(t, "datatype", t)));
    }

    private static void addTableAliasSuggestions(List<Suggest> suggests, SyntacticAnalyzer.Result syn, SemanticAnalyzer.Result sem) {
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

    private static void addTableNameSuggestions(List<Suggest> suggests, SyntacticAnalyzer.Result syn) {
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