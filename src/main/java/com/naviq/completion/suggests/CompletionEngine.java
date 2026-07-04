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
 * KeywordNoiseFilter    - lọc "rác" keyword (statement-start lặp lại + identifier-usable
 * keyword khi đã có cột/alias/bảng thật) - xem javadoc trong file đó để biết chi tiết 2
 * loại nhiễu khác nhau đang được xử lý
 * DmlTargetResolver     - fallback token-scan cho INSERT/UPDATE/ALTER (dùng nội bộ bởi SemanticAnalyzer khi parse lỗi nặng)
 * <p>
 * 2 tầng (Semantic/Syntactic) ĐỘC LẬP, không tầng nào thay được tầng kia - xem
 * javadoc gốc trong SemanticAnalyzer/SyntacticAnalyzer để biết lý do.
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
        boolean freshStatement = KeywordNoiseFilter.isFreshStatementPosition(sql, cursorOffset);

        SemanticAnalyzer.Result semanticResult = SemanticAnalyzer.analyze(sql, cursorOffset);
        // "-1" CẦN THIẾT cho completion kiểu prefix (vd "select * fr|" -> gợi ý "FROM") -
        // trỏ caretTokenIndex VÀO CHÍNH token đang gõ dở, để engine coi đây là "slot còn
        // mở, có thể là bất kỳ từ nào khớp prefix" thay vì "đã chốt xong, xét tiếp theo
        // là gì". NHƯNG chỉ đúng khi ký tự ngay trước cursor CÓ THỂ còn đang gõ dở (chữ/
        // số/_ - 1 phần của identifier/keyword) - SAI khi ký tự đó là dấu câu cố định
        // (vd "(", ",", ".") vì dấu câu LUÔN là 1 token ĐÃ HOÀN CHỈNH ngay khi gõ, không
        // thể "gõ dở" thêm được nữa. Đã xác nhận bằng debug trực tiếp: "insert into t (|"
        // với "-1" cho ra rule "qualified_name" (SAI - tưởng vẫn đang ở vị trí TRƯỚC dấu
        // "("), không có "-1" cho ra đúng "colid" (đang ở TRONG dấu ngoặc, chờ cột) - nên
        // "-1" phải áp CÓ ĐIỀU KIỆN, không phải lúc nào cũng trừ.
        char charBeforeCursor = (cursorOffset > 0 && cursorOffset <= sql.length())
                ? sql.charAt(cursorOffset - 1) : ' ';
        boolean stillMidIdentifier = Character.isLetterOrDigit(charBeforeCursor) || charBeforeCursor == '_';
        int syntacticCursor = stillMidIdentifier ? cursorOffset - 1 : cursorOffset;
        SyntacticAnalyzer.Result syntacticResults = SyntacticAnalyzer.analyze(sql, syntacticCursor);
        boolean hasRealColumns = KeywordNoiseFilter.hasColumnrefCandidate(syntacticResults);

        for (Map.Entry<Integer, List<Integer>> entry : syntacticResults.candidates().tokens.entrySet()) {
            if (!freshStatement && KeywordNoiseFilter.STATEMENT_START_TOKENS.contains(entry.getKey())) {
                continue;
            }
            if (hasRealColumns && KeywordNoiseFilter.IDENTIFIER_USABLE_KEYWORDS.contains(entry.getKey())) {
                continue;
            }
            addKeywordSuggestions(suggests, entry.getKey());
        }

        for (Map.Entry<Integer, List<AntlrCompletionEngine.RuleFrame>> entry : syntacticResults.candidates().rules.entrySet()) {
            String ruleName = PostgreSQLParser.ruleNames[entry.getKey()];
            switch (ruleName) {
                case "columnref", "colid" -> addColumnSuggestions(suggests, semanticResult);
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
            suggests.add(Suggest.of(alias, "alias"));
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