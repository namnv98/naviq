package com.sqlctx.completion.suggestion.oracle;

import com.sqlctx.antlr4.oracle.PlSqlLexer;
import com.sqlctx.antlr4.oracle.PlSqlParser;
import com.sqlctx.completion.input.CompletionInputPreparer;
import com.sqlctx.completion.model.CandidatesResult;
import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.model.SuggestionType;
import com.sqlctx.completion.ranking.SuggestFilter;
import com.sqlctx.completion.semantic.oracle.OracleSemanticAnalyzer;
import com.sqlctx.completion.suggestion.SuggestionService;
import com.sqlctx.completion.syntactic.oracle.OracleSyntacticAnalyzer;
import com.sqlctx.schema.SchemaIndex;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.sqlctx.completion.suggestion.SuggestionSupport.*;

/**
 * Ghép gợi ý cho Oracle, cùng cách với PostgresSuggestionService. Khác grammar: bảng chỉ qua 1 rule
 * {@code tableview_name}; cột trần dùng chung {@code column_name} (SET, USING, INSERT (...), CREATE INDEX...) nên
 * không phải tách theo rule cha như colid của Postgres; còn {@code general_element}/{@code regular_id} bị dùng
 * chung cho cả biến PL/SQL, cursor... nên phải loại trừ theo ngữ cảnh.
 */
public class OracleSuggestionService implements SuggestionService {

    public static List<Suggestion> suggests(CompletionInputPreparer.PrepareCompletionInput input) {
        return SuggestFilter.filter(suggests(input.sql(), input.cursor()), input.prefix(), input.dotMode());
    }

    @Override
    public List<Suggestion> suggest(String sql, Integer cursorCharPos) {
        return suggests(sql, cursorCharPos);
    }

    public static List<Suggestion> suggests(String sql, Integer cursorCharPos) {
        int cursorOffset = cursorCharPos != null ? cursorCharPos : sql.length();
        int syntacticCursor = syntacticCursor(sql, cursorOffset);
        OracleSyntacticAnalyzer.Result syn = OracleSyntacticAnalyzer.analyze(sql, syntacticCursor);
        CandidatesResult candidates = syn.candidates();
        // tầng cú pháp chạy trước: tầng ngữ nghĩa chèn token giả đúng loại grammar cho phép tại caret
        OracleSemanticAnalyzer.Result sem = OracleSemanticAnalyzer.analyze(sql, cursorOffset,
                OracleSyntacticAnalyzer.caretTokenTypeToInsert(candidates));

        List<Suggestion> suggests = new ArrayList<>();
        addKeywords(suggests, PlSqlParser.VOCABULARY, candidates);

        Set<String> rules = OracleMatchedRuleResolver.computeMatchedRuleNamesV1(syn, syntacticCursor);
        boolean jsonReturnType = rules.contains("json_value_return_type") || rules.contains("json_query_return_type");
        // cursor_name của CLOSE/OPEN: general_element ở đây kéo theo type_spec/datatype ảo
        boolean cursorName = hasAncestor(candidates, PlSqlParser.RULE_general_element, PlSqlParser.RULE_cursor_name);

        if ((rules.contains("type_spec") || rules.contains("datatype") || jsonReturnType)
                && !cursorName && !isExecuteImmediatePhantomTypeSpec(candidates)) {
            addDataTypes(suggests);
        }

        if (rules.contains("table_alias")) {
            addTableAlias(suggests, syn, sem);
        }

        // tableview_name không phải tên bảng để chọn: trong table_wild ("t.*", tiền tố chỉ là alias đã có), và ngay
        // sau 1 định danh ("from contracts |" - định danh thứ 2 là alias)
        if (rules.contains("tableview_name")
                && !hasAncestor(candidates, PlSqlParser.RULE_tableview_name, PlSqlParser.RULE_table_wild)
                && !followsIdentifier(syn)) {
            addTableNames(suggests, syn, candidates, sem.visibleCteNames());
        }

        addColumnSuggestions(suggests, rules, candidates, syn, sem, cursorName, jsonReturnType);

        if (rules.contains("regular_id") && hasAncestor(candidates, PlSqlParser.RULE_regular_id, PlSqlParser.RULE_tablespace)) {
            SchemaIndex.tablespaces.forEach(t -> suggests.add(Suggestion.of(t, SuggestionType.TABLESPACE)));
        }
        return suggests;
    }

    /** Cột đến từ 3 rule: column_name (cột trần), general_element (biểu thức), regular_id - mỗi rule có ngữ cảnh loại trừ riêng. */
    private static void addColumnSuggestions(List<Suggestion> suggests, Set<String> rules, CandidatesResult candidates,
                                             OracleSyntacticAnalyzer.Result syn, OracleSemanticAnalyzer.Result sem,
                                             boolean cursorName, boolean jsonReturnType) {
        // "ORDER BY name |": order_by_elements đóng được nên ATN đi tiếp lượt lặp như thể không cần dấu phẩy
        boolean orderByWithoutComma = isImmediatelyAfterOrderByElementsNoComma(syn);
        boolean awaitingDatatype = rules.contains("datatype");

        boolean viaGeneralElement = rules.contains("general_element")
                && !cursorName
                && !hasAncestor(candidates, PlSqlParser.RULE_general_element, PlSqlParser.RULE_assignable_element) // biến PL/SQL
                && !hasAncestor(candidates, PlSqlParser.RULE_general_element, PlSqlParser.RULE_values_clause)      // INSERT ... VALUES (|)
                && !orderByWithoutComma
                && !jsonReturnType; // "RETURNING VARCHAR2 |": general_element ảo của biểu thức ngoài cùng

        boolean viaRegularId = rules.contains("regular_id")
                && !hasAncestor(candidates, PlSqlParser.RULE_regular_id, PlSqlParser.RULE_values_clause)
                && !orderByWithoutComma
                // "ALTER TABLE t ADD c |" / "MODIFY c |": tên cột đã gõ xong, đang chờ kiểu (datatype cùng khớp)
                && !(awaitingDatatype && (hasAncestor(candidates, PlSqlParser.RULE_regular_id, PlSqlParser.RULE_add_column_clause)
                || hasAncestor(candidates, PlSqlParser.RULE_regular_id, PlSqlParser.RULE_modify_column_clauses)));

        boolean columnName = rules.contains("column_name");
        // MERGE: vế trái SET và INSERT (|) chỉ được là cột bảng đích, không phải bảng USING
        boolean mergeTarget = columnName && sem.ddlTargetAlias() != null
                && (hasAncestor(candidates, PlSqlParser.RULE_column_name, PlSqlParser.RULE_merge_element)
                || hasAncestor(candidates, PlSqlParser.RULE_column_name, PlSqlParser.RULE_merge_insert_clause));
        boolean joinUsing = columnName && hasAncestor(candidates, PlSqlParser.RULE_column_name, PlSqlParser.RULE_join_using_part);
        boolean viaColumnName = columnName && !mergeTarget && !joinUsing && !orderByWithoutComma
                && !(awaitingDatatype && (hasAncestor(candidates, PlSqlParser.RULE_column_name, PlSqlParser.RULE_add_column_clause)
                || hasAncestor(candidates, PlSqlParser.RULE_column_name, PlSqlParser.RULE_modify_column_clauses)));

        // "u." trước 1 lỗi cú pháp khác: grammar không cho dấu chấm cụt nên không rule nào khớp, chỉ tầng ngữ nghĩa
        // (dò trên token) biết qualifier
        boolean danglingDot = sem.qualifier() != null;

        if (mergeTarget) {
            addTargetColumns(suggests, sem);
        } else if (joinUsing) {
            addCommonColumns(suggests, sem);
        } else if (danglingDot || viaColumnName || viaGeneralElement || viaRegularId) {
            // hàm chỉ hợp lệ ở vị trí biểu thức; column_name/regular_id còn là định danh thuần (INSERT INTO t (|...)
            if (viaGeneralElement) {
                addFunctions(suggests);
            }
            addColumns(suggests, sem, null);
        }
    }

    /** "BEGIN EXECUTE IMMEDIATE |": nhánh ảo coi "EXECUTE IMMEDIATE" là khai báo biến, lộ type_spec ở vị trí biểu thức. */
    private static boolean isExecuteImmediatePhantomTypeSpec(CandidatesResult candidates) {
        return hasAncestor(candidates, PlSqlParser.RULE_general_element, PlSqlParser.RULE_execute_immediate)
                && hasAncestor(candidates, PlSqlParser.RULE_type_spec, PlSqlParser.RULE_variable_declaration);
    }

    private static boolean followsIdentifier(OracleSyntacticAnalyzer.Result syn) {
        int previous = previousTokenType(syn.tokenStream(), syn.caretTokenIndex());
        return previous == PlSqlParser.REGULAR_ID || previous == PlSqlParser.DELIMITED_ID;
    }

    /**
     * Caret ngay sau 1 order_by_elements đã hoàn chỉnh mà chưa có dấu phẩy. Chỉ nhìn 1-2 token thật trước caret
     * (path tổ tiên của rule không tin được: mỗi rule chỉ giữ 1 path), và bảo thủ - chỉ 2 dạng chắc chắn:
     * ASC/DESC/NULLS FIRST/NULLS LAST ngay trước caret, hoặc "BY|, định_danh |". Biểu thức phức tạp
     * ("ORDER BY UPPER(name) |") bỏ qua: thà không lọc còn hơn lọc nhầm.
     */
    private static boolean isImmediatelyAfterOrderByElementsNoComma(OracleSyntacticAnalyzer.Result syn) {
        var tokens = syn.tokenStream();
        int i = previousRealIndex(tokens, syn.caretTokenIndex());
        if (i < 0) {
            return false;
        }
        int type = tokens.get(i).getType();
        if (type == PlSqlParser.ASC || type == PlSqlParser.DESC) {
            return true;
        }
        int j = previousRealIndex(tokens, i);
        if (type == PlSqlParser.FIRST || type == PlSqlParser.LAST) {
            // FIRST/LAST còn ở "FETCH FIRST n ROWS" - chỉ tính khi đi sau NULLS
            return j >= 0 && tokens.get(j).getType() == PlSqlParser.NULLS;
        }
        // tên cột thường như "name" có token type riêng (non-reserved keyword), không phải REGULAR_ID
        if (!((PlSqlLexer) tokens.getTokenSource()).isIdentifier(type) || j < 0) {
            return false;
        }
        int beforeIdentifier = tokens.get(j).getType();
        return beforeIdentifier == PlSqlParser.BY || beforeIdentifier == PlSqlParser.COMMA;
    }

    private static int previousRealIndex(TokenStream tokens, int fromIndex) {
        int i = fromIndex - 1;
        while (i >= 0 && tokens.get(i).getChannel() != Token.DEFAULT_CHANNEL) {
            i--;
        }
        return i;
    }

    private static void addTableAlias(List<Suggestion> suggests, OracleSyntacticAnalyzer.Result syn, OracleSemanticAnalyzer.Result sem) {
        var tableName = OracleAliasNameSuggester.extractTableNameForImplicitAlias(syn.tokenStream(), syn.caretTokenIndex());
        if (tableName != null) {
            suggests.add(Suggestion.of(OracleAliasNameSuggester.suggestAlias(sem.visibleAliases(), tableName), SuggestionType.ALIAS));
        }
    }

    /** DROP VIEW chỉ gợi ý view, DROP TABLE chỉ bảng; CTE không DROP được. */
    private static void addTableNames(List<Suggestion> suggests, OracleSyntacticAnalyzer.Result syn,
                                      CandidatesResult candidates, Set<String> visibleCteNames) {
        boolean dropView = hasAncestor(candidates, PlSqlParser.RULE_tableview_name, PlSqlParser.RULE_drop_view);
        boolean dropTable = hasAncestor(candidates, PlSqlParser.RULE_tableview_name, PlSqlParser.RULE_drop_table);
        var tables = SchemaIndex.schemaTableIndex.values().stream();

        int caretTokenIndex = syn.caretTokenIndex();
        var tokenStream = syn.tokenStream();
        if (caretTokenIndex >= 2 && tokenStream.get(caretTokenIndex - 1).getType() == PlSqlParser.PERIOD) {
            Token prev = tokenStream.get(caretTokenIndex - 2);
            if (prev.getType() == PlSqlParser.REGULAR_ID || prev.getType() == PlSqlParser.DELIMITED_ID) {
                tables = SchemaIndex.getTablesBySchema(prev.getText()).stream();
                visibleCteNames = Set.of(); // "schema." đang gõ: chỉ bảng của schema đó
            }
        }
        tables.filter(t -> !dropView || "view".equalsIgnoreCase(t.kind()))
                .filter(t -> !dropTable || "table".equalsIgnoreCase(t.kind()))
                .forEach(t -> suggests.add(Suggestion.of(t.fullName(), SuggestionType.fromLabel(t.kind()))));
        if (!dropView && !dropTable) {
            visibleCteNames.forEach(name -> suggests.add(Suggestion.of(name, SuggestionType.TABLE)));
        }
    }
}
