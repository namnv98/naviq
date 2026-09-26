package com.naviq.completion.suggests.oracle;

import com.naviq.antlr4.oracle.PlSqlParser;
import com.naviq.completion.suggests.CompletionInputPreparer;
import com.naviq.completion.suggests.DerivedColumnExpander;
import com.naviq.completion.suggests.SuggestFilter;
import com.naviq.model.Suggest;
import com.naviq.completion.syntactic.engine.feature.RuleCallStack;
import com.naviq.datasource.SchemaIndex;
import com.naviq.completion.syntactic.OracleSQLSyntacticAnalyzer;
import com.naviq.completion.semantic.oracle.SemanticAnalyzer;
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

        Set<String> matchedRuleNames = KeywordNoiseFilter.computeMatchedRuleNamesV1(syntacticResults, syntacticCursor);

        // typename (Postgres) -> Oracle KHÔNG có rule tên "typename": type_spec là rule bao ngoài
        // (gồm cả REF/%ROWTYPE/%TYPE), datatype là kiểu dữ liệu "thuần" (NUMBER/VARCHAR2/...) -
        // check cả 2 vì tuỳ vị trí trong grammar sẽ khớp rule nào.
        if (matchedRuleNames.contains("type_spec") || matchedRuleNames.contains("datatype")
                || matchedRuleNames.contains("json_value_return_type") || matchedRuleNames.contains("json_query_return_type")) {
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
        // INSERT INTO t VALUES (|) - "expression" ở values_clause route qua general_element,
        // nhưng ở vị trí này KHÔNG có bảng nào trong scope để gợi ý cột (giống INSERT ... VALUES
        // của Postgres không gợi ý cột).
        boolean isGeneralElementInsertValues =
                isRuleAncestorAnywhere(syntacticResults, PlSqlParser.RULE_general_element, PlSqlParser.RULE_values_clause);

        // "ORDER BY name |" (đã gõ XONG 1 order_by_elements hoàn chỉnh, kể cả có NULLS FIRST/LAST
        // hay không) - order_by_elements: expression (ASC|DESC)? (NULLS (FIRST|LAST))? toàn bộ
        // phần đuôi đều optional nên walk ATN coi rule này "đóng được", rồi tại order_by_clause:
        // order_by_elements (COMMA order_by_elements)* vẫn tiếp tục khám phá LƯỢT LẶP TIẾP THEO
        // như thể KHÔNG CẦN dấu phẩy - lộ ra general_element/regular_id (cột mới) dù thực tế
        // Oracle bắt buộc phải có "," trước order_by_elements kế tiếp.
        //
        // CHÚ Ý: KHÔNG dùng ancestor-path của general_element/regular_id để phát hiện case này
        // (đã thử, gây regression ở offsetClauseDoesNotCrash) - candidates().rules chỉ lưu ĐÚNG 1
        // path "relevant nhất" cho mỗi rule id (PreferredRuleResolver.recordIfMoreRelevant), nên
        // khi general_element khớp ở NHIỀU nhánh derivation khác nhau tại CÙNG caret (vd order by
        // bị lặp giả VÀ offset_clause's expression hợp lệ cùng lúc match general_element), path
        // ghi lại có thể là nhánh SAI, khiến ancestor-check suy luận nhầm cho cả nhánh đúng. Thay
        // bằng quét THUẦN TOKEN THẬT lùi từ caret: chỉ suppress khi xác nhận được cụ thể đang đứng
        // ngay sau 1 order_by_elements đã đóng KHÔNG có dấu phẩy, KHÔNG đi qua bất kỳ từ khóa nào
        // đánh dấu đã rời sang mệnh đề khác (OFFSET/FETCH/FOR) hay dấu phẩy/ORDER BY (hợp lệ).
        boolean isOrderByElementsWithoutComma = isImmediatelyAfterOrderByElementsNoComma(syntacticResults);
        boolean isRegularIdOrderByWithoutComma = isOrderByElementsWithoutComma;

        // "JSON_VALUE(col, path RETURNING VARCHAR2 |)" - general_element khớp ở 1 derivation HOÀN
        // TOÀN KHÁC (ancestor tận atom của expression NGOÀI CÙNG, không hề đi qua
        // json_value_return_clause/json_value_return_type) cùng lúc với json_value_return_type -
        // đây là vị trí chỉ nên gợi ý datatype, cột lọt vào là noise. Suppress general_element khi
        // json_value_return_type/json_query_return_type CŨNG được match cùng lúc.
        boolean isGeneralElementJsonReturnType = matchedRuleNames.contains("json_value_return_type")
                || matchedRuleNames.contains("json_query_return_type");

        boolean shouldSuggestColumnsViaGeneralElement = matchedRuleNames.contains("general_element")
                && !isGeneralElementCursorName && !isGeneralElementAssignTarget && !isGeneralElementInsertValues
                && !isOrderByElementsWithoutComma && !isGeneralElementJsonReturnType;

        // "regular_id" cũng bị overload giống "general_element" - trong VALUES (|), ATN còn
        // khớp cả nhánh other_function (hàm không có tham số, vd COUNT) đi qua regular_id, nên
        // cần loại trừ y hệt cho vị trí này.
        boolean isRegularIdInsertValues =
                isRuleAncestorAnywhere(syntacticResults, PlSqlParser.RULE_regular_id, PlSqlParser.RULE_values_clause);
        boolean shouldSuggestColumnsViaRegularId = matchedRuleNames.contains("regular_id")
                && !isRegularIdInsertValues && !isRegularIdOrderByWithoutComma;

        // "MERGE ... WHEN MATCHED THEN UPDATE SET |" - vế TRÁI của merge_element (column_name)
        // chỉ nên gợi ý cột bảng TARGET, không phải bảng USING (source) - dù cả 2 cùng visible
        // trong scope của merge_statement (xem SemanticScope.enterMerge_statement). Vế PHẢI
        // (expression sau EQUALS_OP) vẫn cần thấy cả 2 nên KHÔNG áp giới hạn này - chỉ áp khi
        // column_name có ancestor merge_element (chính là vị trí LHS).
        boolean isColumnNameMergeUpdateTarget = matchedRuleNames.contains("column_name")
                && semanticResult.ddlTargetAlias() != null
                && isRuleAncestorAnywhere(syntacticResults, PlSqlParser.RULE_column_name, PlSqlParser.RULE_merge_element);

        // "MERGE ... WHEN NOT MATCHED THEN INSERT (|)" - paren_column_list ở đây (khác VALUES(...))
        // chỉ nên gợi ý cột bảng TARGET (bảng đang INSERT vào), không phải bảng USING/source - y hệt
        // lý do isColumnNameMergeUpdateTarget, chỉ khác ancestor rule (merge_insert_clause thay vì
        // merge_element).
        boolean isColumnNameMergeInsertTarget = matchedRuleNames.contains("column_name")
                && semanticResult.ddlTargetAlias() != null
                && isRuleAncestorAnywhere(syntacticResults, PlSqlParser.RULE_column_name, PlSqlParser.RULE_merge_insert_clause);

        // "JOIN ... USING (|)" - paren_column_list dùng chung y hệt INSERT column-list, nhưng theo
        // đúng ngữ nghĩa Oracle USING chỉ CỘT CHUNG TÊN giữa các bảng tham gia join mới hợp lệ,
        // không phải mọi cột của mọi bảng.
        boolean isColumnNameJoinUsing = matchedRuleNames.contains("column_name")
                && isRuleAncestorAnywhere(syntacticResults, PlSqlParser.RULE_column_name, PlSqlParser.RULE_join_using_part);

        // "ALTER TABLE ... ADD new_col |" / "... MODIFY existing_col |" - column_name/regular_id
        // của chính add_column_clause/modify_column_clauses lại xuất hiện làm candidate NGAY TẠI vị
        // trí đang chờ datatype (sau khi tên cột đã gõ xong) - đây là artifact của việc ATN dò được
        // NHIỀU alternative cùng bắt đầu bằng column_name (column_definition/virtual_column_definition
        // cho ADD; modify_col_properties/modify_col_visibility/modify_col_substitutable cho MODIFY),
        // ghi đè lẫn nhau qua PreferredRuleResolver.recordIfMoreRelevant (chỉ giữ 1 path "gần nhất"
        // mỗi rule id). KHÔNG thể loại trừ bằng ancestor đơn thuần vì CÙNG ancestor đó (vd
        // modify_col_visibility) cũng là path hợp lệ DUY NHẤT được ghi nhận cho vị trí ĐẦU (chưa gõ
        // tên cột - lúc đó ĐÚNG là cần gợi ý cột có sẵn để chọn sửa/xoá). Phân biệt bằng tín hiệu
        // đáng tin cậy hơn: "datatype" CŨNG được match cùng lúc CHỈ xảy ra khi tên cột đã gõ xong
        // (đang chờ kiểu dữ liệu) - ở vị trí đầu, "datatype" không thể là candidate hợp lệ.
        boolean isAlterColumnAwaitingDatatype = matchedRuleNames.contains("datatype")
                && (isRuleAncestorAnywhere(syntacticResults, PlSqlParser.RULE_column_name, PlSqlParser.RULE_add_column_clause)
                        || isRuleAncestorAnywhere(syntacticResults, PlSqlParser.RULE_column_name, PlSqlParser.RULE_modify_column_clauses));
        boolean isAlterRegularIdAwaitingDatatype = matchedRuleNames.contains("datatype")
                && (isRuleAncestorAnywhere(syntacticResults, PlSqlParser.RULE_regular_id, PlSqlParser.RULE_add_column_clause)
                        || isRuleAncestorAnywhere(syntacticResults, PlSqlParser.RULE_regular_id, PlSqlParser.RULE_modify_column_clauses));

        // "u." đứng ngay trước 1 lỗi cú pháp khác (vd chuỗi chưa đóng phía trước) - PlSqlParser.g4
        // KHÔNG patch general_element_part cho phép PERIOD cụt (id_expression bắt buộc ngay sau
        // PERIOD, xem javadoc đầu SemanticScope.java) nên walk ATN cú pháp không tìm được rule nào
        // khớp tại vị trí này (matchedRuleNames rỗng hoàn toàn) - CHỈ tầng semantic (dangling-dot
        // detector trong SemanticAnalyzer.detect(), thuần dựa token stream, độc lập với việc parse
        // có thành công hay không) mới resolve được qualifier "u". Phải tự kích hoạt gợi ý cột theo
        // qualifier đó, không thể chờ matchedRuleNames vì nó không bao giờ khớp trong case này.
        boolean qualifierResolvedByDanglingDot = semanticResult.qualifier() != null;

        boolean shouldSuggestColumnsViaColumnName = matchedRuleNames.contains("column_name")
                && !isColumnNameMergeInsertTarget && !isColumnNameJoinUsing && !isAlterColumnAwaitingDatatype;

        if (isColumnNameMergeUpdateTarget || isColumnNameMergeInsertTarget) {
            addTargetOnlyColumnSuggestions(suggests, semanticResult);
        } else if (isColumnNameJoinUsing) {
            addCommonColumnSuggestions(suggests, semanticResult);
        } else if (qualifierResolvedByDanglingDot || shouldSuggestColumnsViaColumnName || shouldSuggestColumnsViaGeneralElement
                || (shouldSuggestColumnsViaRegularId && !isAlterRegularIdAwaitingDatatype)) {
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

    /**
     * "ORDER BY name NULLS FIRST |" / "ORDER BY name |" - true khi caret đứng ngay sau 1
     * order_by_elements ĐÃ HOÀN CHỈNH mà CHƯA có dấu phẩy nào theo sau (tức vị trí "lặp giả" mà
     * ATN nhầm là có thể gõ cột mới - xem chú thích ở nơi gọi).
     * <p>
     * Thuần dựa vào 1-2 TOKEN THẬT ngay trước caret (không dùng ancestor-path của rule - không
     * đáng tin, xem chú thích nơi gọi; KHÔNG quét lùi tìm ORDER kiểu boundary-keyword - đã thử,
     * vỡ ở windowFrameBoundSuggestsKeywordsAndColumns vì "ROWS BETWEEN ... AND |" cũng đi qua
     * order_by_clause trong cùng OVER(...) mà ROWS/BETWEEN/AND không nằm trong danh sách boundary
     * liệt kê được hết). Cố tình BẢO THỦ - chỉ suppress ở đúng 2 hình dạng đuôi order_by_elements
     * chắc chắn không lẫn với construct nào khác: (1) token ngay trước caret là ASC/DESC/FIRST/
     * LAST (từ khóa CHỈ xuất hiện trong đuôi order_by_elements, không dùng ở đâu khác quanh đây);
     * (2) token ngay trước caret là 1 định danh trần (REGULAR_ID/DELIMITED_ID) MÀ token trước đó
     * nữa là BY hoặc COMMA (tức "ORDER BY name |" - tên cột đơn giản, không phải biểu thức phức
     * tạp). Bỏ qua case biểu thức phức tạp hơn ("ORDER BY UPPER(name) |") - false negative chấp
     * nhận được (không suppress) còn hơn false positive (suppress nhầm chỗ khác).
     */
    private static boolean isImmediatelyAfterOrderByElementsNoComma(OracleSQLSyntacticAnalyzer.Result syn) {
        var tokenStream = syn.tokenStream();
        int i = syn.caretTokenIndex() - 1;

        while (i >= 0 && tokenStream.get(i).getChannel() != Token.DEFAULT_CHANNEL) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        int immediateType = tokenStream.get(i).getType();
        if (immediateType == PlSqlParser.ASC || immediateType == PlSqlParser.DESC) {
            return true;
        }
        if (immediateType == PlSqlParser.FIRST || immediateType == PlSqlParser.LAST) {
            // FIRST/LAST cũng dùng ở "FETCH FIRST n ROWS ONLY" (row-limiting clause, không phải
            // order_by_elements) - CHỈ tính là đuôi order_by_elements khi token liền trước nó là
            // NULLS ("NULLS FIRST"/"NULLS LAST"), không đứng riêng lẻ.
            int j = i - 1;
            while (j >= 0 && tokenStream.get(j).getChannel() != Token.DEFAULT_CHANNEL) {
                j--;
            }
            return j >= 0 && tokenStream.get(j).getType() == PlSqlParser.NULLS;
        }
        if (immediateType != PlSqlParser.REGULAR_ID && immediateType != PlSqlParser.DELIMITED_ID) {
            return false;
        }

        int j = i - 1;
        while (j >= 0 && tokenStream.get(j).getChannel() != Token.DEFAULT_CHANNEL) {
            j--;
        }
        if (j < 0) {
            return false;
        }
        int beforeIdentifierType = tokenStream.get(j).getType();
        return beforeIdentifierType == PlSqlParser.BY || beforeIdentifierType == PlSqlParser.COMMA;
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
        SchemaIndex.DATA_TYPES.forEach(t -> suggests.add(Suggest.of(t, "datatype", t)));
    }

    private static void addTableAliasSuggestions(List<Suggest> suggests, OracleSQLSyntacticAnalyzer.Result syn, SemanticAnalyzer.Result sem) {
        var tableName = AliasNameSuggester.extractTableNameForImplicitAlias(syn.tokenStream(), syn.caretTokenIndex());
        if (tableName != null) {
            String alias = AliasNameSuggester.suggestAlias(sem.visibleAliases(), tableName);
            suggests.add(Suggest.of(alias, "alias"));
        }
    }

    private static java.util.Map<String, List<Suggest>> columnsPerVisibleAlias(SemanticAnalyzer.Result sem) {
        var perAlias = new java.util.LinkedHashMap<String, List<Suggest>>();
        sem.visibleAliases().forEach((alias, table) -> {
            var cols = new ArrayList<Suggest>();
            var derived = sem.visibleDerivedScopes().get(alias);
            if (derived != null) {
                DerivedColumnExpander.addDerivedColumns(cols, alias, derived);
            } else {
                SchemaIndex.getColumnsOfTable(table).forEach(c -> cols.add(Suggest.of(alias + "." + c.name(), "column", c.dataType())));
            }
            perAlias.put(alias, cols);
        });
        return perAlias;
    }

    /**
     * JOIN ... USING (|): chỉ gợi ý cột có TÊN xuất hiện ở ít nhất 2 alias visible (cột chung
     * giữa các bảng tham gia join), mỗi alias đóng góp 1 gợi ý dạng alias.cột. Mirror y hệt
     * Postgres CompletionEngine.addCommonColumnSuggestions.
     */
    private static void addCommonColumnSuggestions(List<Suggest> suggests, SemanticAnalyzer.Result sem) {
        var perAlias = columnsPerVisibleAlias(sem);
        var aliasCountByColumn = new java.util.HashMap<String, Integer>();
        perAlias.values().forEach(cols -> cols.stream()
                .map(s -> s.getKey().substring(s.getKey().indexOf('.') + 1).toLowerCase())
                .distinct()
                .forEach(name -> aliasCountByColumn.merge(name, 1, Integer::sum)));
        perAlias.values().forEach(cols -> cols.stream()
                .filter(s -> aliasCountByColumn.get(s.getKey().substring(s.getKey().indexOf('.') + 1).toLowerCase()) >= 2)
                .forEach(suggests::add));
    }

    /** Như addColumnSuggestions, nhưng chỉ gợi ý cột của đúng 1 alias - {@link SemanticAnalyzer.Result#ddlTargetAlias()}. */
    private static void addTargetOnlyColumnSuggestions(List<Suggest> suggests, SemanticAnalyzer.Result sem) {
        String alias = sem.ddlTargetAlias();
        String table = sem.visibleAliases().get(alias);
        if (table == null) {
            return;
        }
        var derived = sem.visibleDerivedScopes().get(alias);
        if (derived != null) {
            DerivedColumnExpander.addDerivedColumns(suggests, alias, derived);
        } else {
            SchemaIndex.getColumnsOfTable(table).forEach(c -> suggests.add(Suggest.of(alias + "." + c.name(), "column", c.dataType())));
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