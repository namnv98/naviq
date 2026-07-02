package com.naviq.completion.suggests;

import com.example.PostgreSQLParser;
import com.naviq.datasource.SchemaIndex;
import com.naviq.completion.syntactic.AntlrCompletionEngineFix;
import com.naviq.completion.model.Suggest;
import com.naviq.completion.semantic.*;
import com.naviq.completion.syntactic.SyntacticAnalyzer;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Objects.isNull;

/**
 * Orchestrator - CHỈ điều phối, không tự chứa logic parse/resolve nào. Toàn bộ chi
 * tiết nằm ở các class chuyên trách:
 * <p>
 * SchemaIndex          - biết schema có gì (bảng, cột, hàm, kiểu dữ liệu)
 * SemanticAnalyzer      - tầng ngữ nghĩa (SemanticScope: alias/scope/CTE/subquery)
 * SyntacticAnalyzer     - tầng cú pháp (AntlrCompletionEngineFix: token/rule hợp lệ)
 * DerivedColumnExpander - mở rộng cột của subquery/CTE (bao gồm case wildcard)
 * AliasNameSuggester    - đặt tên alias tự động + tìm tableName trước "AS"
 * DmlTargetResolver     - fallback token-scan cho INSERT/UPDATE/ALTER (dùng nội
 * bộ bởi SemanticAnalyzer khi parse lỗi nặng)
 * <p>
 * 2 tầng (Semantic/Syntactic) ĐỘC LẬP, không tầng nào thay được tầng kia - xem
 * javadoc gốc trong SemanticAnalyzer/SyntacticAnalyzer để biết lý do.
 */
public class PostgresCompletionEngine {

    public static void main(String[] args) {
        var suggests = suggests("s",
                "s".length());
        System.out.println();
    }

    public static List<Suggest> suggests(String sql, Integer cursorCharPos) {
        var suggests = new ArrayList<Suggest>();
        if (isNull(cursorCharPos)) {
            cursorCharPos = sql.length();
        }
        final int cursorOffset = cursorCharPos;

        SemanticAnalyzer.SemanticAnalysisResult sem = SemanticAnalyzer.analyze(sql, cursorOffset);
        SyntacticAnalyzer.Result syn = SyntacticAnalyzer.analyze(sql, cursorOffset - 1);

        for (Map.Entry<Integer, List<Integer>> entry : syn.candidates().tokens.entrySet()) {
            suggests.add(Suggest.of(
                    PostgreSQLParser.VOCABULARY.getDisplayName(entry.getKey()).toLowerCase().replaceAll("'", ""), "keyword"
            ));
        }

        for (Map.Entry<Integer, List<AntlrCompletionEngineFix.RuleFrame>> entry : syn.candidates().rules.entrySet()) {
            String ruleName = PostgreSQLParser.ruleNames[entry.getKey()];

            switch (ruleName) {
                case "columnName" -> {
                    addColumnSuggestions(suggests, sem);
                }
                case "dataTypeName" -> {
                    SchemaIndex.DATA_TYPES.forEach(t -> suggests.add(Suggest.of(t, "datatype", t)));
                }
                case "tableAlias" -> {
                    var tableName = AliasNameSuggester.extractTableBeforeAs(syn.tokenStream(), syn.caretTokenIndex());
                    if (tableName != null) {
                        String alias = AliasNameSuggester.suggestAlias(sem.visibleAliases(), tableName);
                        suggests.add(Suggest.of(alias, "column"));
                    }
                }
                case "tableName" -> {
                    addTableNameSuggestions(suggests, syn);
                }
                default -> { /* rule khác không cần xử lý riêng */ }
            }
        }
        return suggests;
    }

    private static void addColumnSuggestions(List<Suggest> suggests, SemanticAnalyzer.SemanticAnalysisResult sem) {
        SchemaIndex.FUNCTIONS.forEach(fn -> suggests.add(Suggest.of(fn, "function")));
        if (sem.qualifier() != null) {
            String qualifier = sem.qualifier();
            if (sem.qualifierDerivedScope() != null) {
                // qualifier trỏ tới subquery/CTE - lấy cột TRỰC TIẾP từ SELECT list
                // của nó, KHÔNG tra schema bằng tên giả "<cte#N>" (luôn rỗng).
                DerivedColumnExpander.addDerivedColumns(suggests, qualifier, sem.qualifierDerivedScope());
            } else if (sem.qualifierResolvesTo() != null) {
                SchemaIndex.getColumnsOfTable(sem.qualifierResolvesTo()).forEach(c -> suggests.add(Suggest.of(qualifier + "." + c.name(), "column", c.dataType())));
            } else {
                // alias lạ/gõ sai - thử tra thẳng bằng chính chuỗi qualifier (đoán còn hơn không gợi ý gì, người dùng có thể đang gõ tên bảng trực tiếp).
                SchemaIndex.getColumnsOfTable(qualifier).forEach(c -> suggests.add(Suggest.of(qualifier + "." + c.name(), "column", c.dataType())));
            }
        } else if (!sem.visibleAliases().isEmpty()) {
            // CHỈ liệt cột của bảng THẬT SỰ có trong câu - khác fallback toàn schema.
            sem.visibleAliases().forEach((alias, table) -> {
                var derived = sem.visibleDerivedScopes().get(alias);
                if (derived != null) {
                    DerivedColumnExpander.addDerivedColumns(suggests, alias, derived);
                } else {
                    SchemaIndex.getColumnsOfTable(table).forEach(c -> suggests.add(Suggest.of(alias + "." + c.name(), "column", c.dataType())));
                }
            });
        } else {
            // Không có FROM nào cả (hoặc SemanticAnalyzer rơi vào fallback) - fallback toàn schema, phương án cuối.
//            SchemaIndex.SCHEMA_TABLE_INDEX.keySet().forEach(t -> SchemaIndex.getColumnsOfTable(t).forEach(c ->
//                    suggests.add(Suggest.of(c.fullName(), "column", c.dataType()))));
        }
    }

    private static void addTableNameSuggestions(List<Suggest> suggests, SyntacticAnalyzer.Result syn) {
        int caretTokenIndex = syn.caretTokenIndex();
        var tokenStream = syn.tokenStream();
        if (caretTokenIndex >= 2) {
            Token tok = tokenStream.get(caretTokenIndex - 1);
            if (tok.getType() == PostgreSQLParser.DOT) {
                Token prev = tokenStream.get(caretTokenIndex - 2);
                if (prev.getType() == PostgreSQLParser.ID) {
                    String schema = prev.getText();
                    SchemaIndex.getTablesBySchema(schema).forEach(t -> suggests.add(Suggest.of(t.fullName(), t.kind())));
                    return;
                }
            }
        }
        SchemaIndex.SCHEMA_TABLE_INDEX.values().forEach(t -> suggests.add(Suggest.of(t.fullName(), t.kind())));
    }
}
