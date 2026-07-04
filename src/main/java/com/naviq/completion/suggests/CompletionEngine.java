package com.naviq.completion.suggests;

import com.naviq.antlr4.PostgreSQLParser;
import com.naviq.completion.model.Suggest;
import com.naviq.completion.semantic.SemanticAnalyzer;
import com.naviq.completion.syntactic.AntlrCompletionEngine;
import com.naviq.completion.syntactic.SyntacticAnalyzer;
import com.naviq.datasource.SchemaIndex;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

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
 * DmlTargetResolver     - fallback token-scan cho INSERT/UPDATE/ALTER (dùng nội bộ bởi SemanticAnalyzer khi parse lỗi nặng)
 * <p>
 * 2 tầng (Semantic/Syntactic) ĐỘC LẬP, không tầng nào thay được tầng kia - xem
 * javadoc gốc trong SemanticAnalyzer/SyntacticAnalyzer để biết lý do.
 * <p>
 * CẬP NHẬT (port sang grammar PostgreSQL đầy đủ): tên rule trong switch bên dưới đổi theo tên rule
 * THẬT của grammar mới (kiểu Postgres gram.y), khác hẳn tên rule rút gọn cũ:
 * - "columnName" cũ -> {@code columnref} (biểu thức cột trong SELECT list/WHERE/...)
 * - "dataTypeName" cũ -> {@code typename}
 * - "tableAlias" cũ -> {@code table_alias}
 * - "tableName" cũ -> {@code qualified_name} (FROM/UPDATE/DELETE/ALTER TABLE/TRUNCATE - đi qua
 * relation_expr) VÀ {@code any_name} (DROP TABLE/VIEW/INDEX/SEQUENCE/... - dùng any_name_list,
 * KHÔNG phải qualified_name, xem object_type_any_name trong grammar) - cả 2 đều nên gợi ý tên
 * bảng giống nhau nên cùng route vào addTableNameSuggestions().
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
        SyntacticAnalyzer.Result syntacticResults = SyntacticAnalyzer.analyze(sql, cursorOffset - 1);

        for (Map.Entry<Integer, List<Integer>> entry : syntacticResults.candidates().tokens.entrySet()) {
            addKeywordSuggestions(suggests, entry.getKey());
        }

        for (Map.Entry<Integer, List<AntlrCompletionEngine.RuleFrame>> entry : syntacticResults.candidates().rules.entrySet()) {
            String ruleName = PostgreSQLParser.ruleNames[entry.getKey()];
            switch (ruleName) {
                case "columnref" -> addColumnSuggestions(suggests, semanticResult);
                case "typename" -> addDataTypeSuggestions(suggests);
                case "table_alias" -> addTableAliasSuggestions(suggests, syntacticResults, semanticResult);
                case "qualified_name", "any_name" -> addTableNameSuggestions(suggests, syntacticResults);
            }
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
            suggests.add(Suggest.of(alias, "table"));
        }
    }

    private static void addColumnSuggestions(List<Suggest> suggests, SemanticAnalyzer.Result sem) {
        SchemaIndex.FUNCTIONS.forEach(fn -> suggests.add(Suggest.of(fn, "function")));
        if (sem.qualifier() != null) {
            String qualifier = sem.qualifier();
            if (sem.qualifierDerivedScope() != null) {
                // qualifier trỏ tới subquery/CTE - lấy cột TRỰC TIẾP từ SELECT list của nó, KHÔNG tra schema bằng tên giả "<cte#N>".
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
//            SchemaIndex.SCHEMA_TABLE_INDEX.keySet().forEach(t -> SchemaIndex.getColumnsOfTable(t).forEach(c -> suggests.add(Suggest.of(c.fullName(), "column", c.dataType()))));
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