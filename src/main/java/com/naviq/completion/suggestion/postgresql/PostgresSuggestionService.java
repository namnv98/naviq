package com.naviq.completion.suggestion.postgresql;

import com.naviq.completion.suggestion.SuggestionService;
import com.naviq.completion.model.SuggestionType;
import com.naviq.antlr4.postgresql.PostgreSQLParser;
import com.naviq.completion.suggestion.CompletionInputPreparer;
import com.naviq.completion.suggestion.DerivedColumnExpander;
import com.naviq.completion.suggestion.SuggestFilter;
import com.naviq.completion.syntactic.engine.support.RuleCallStack;
import com.naviq.schema.SchemaIndex;
import com.naviq.completion.model.Suggestion;
import com.naviq.completion.syntactic.postgresql.PostgresSyntacticAnalyzer;
import com.naviq.completion.semantic.postgresql.PostgresSemanticAnalyzer;
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
 * PostgresSemanticAnalyzer      - tầng ngữ nghĩa (PostgresScopeBuilder: alias/scope/CTE/subquery)
 * PostgresSyntacticAnalyzer     - tầng cú pháp (AntlrCompletionEngine: token/rule hợp lệ)
 * PostgresMatchedRuleResolver    - lọc noise (định danh đã đóng bằng khoảng trắng)
 * <p>
 * 2 tầng (Semantic/Syntactic) ĐỘC LẬP, không tầng nào thay được tầng kia.
 */
public class PostgresSuggestionService implements SuggestionService {
    private static final Logger LOG = LoggingConfig.of(PostgresSuggestionService.class);

    public static List<Suggestion> suggests(CompletionInputPreparer.PrepareCompletionInput input) {
        var suggests = suggests(input.sql(), input.cursor());
        return SuggestFilter.filter(suggests, input.prefix(), input.dotMode());
    }

    @Override
    public List<Suggestion> suggest(String sql, Integer cursorCharPos) {
        return suggests(sql, cursorCharPos);
    }

    public static List<Suggestion> suggests(String sql, Integer cursorCharPos) {
        var suggests = new ArrayList<Suggestion>();
        if (isNull(cursorCharPos)) {
            cursorCharPos = sql.length();
        }
        int cursorOffset = cursorCharPos;
        PostgresSemanticAnalyzer.Result semanticResult = PostgresSemanticAnalyzer.analyze(sql, cursorOffset);

        char charBeforeCursor = (cursorOffset > 0 && cursorOffset <= sql.length()) ? sql.charAt(cursorOffset - 1) : ' ';
        boolean stillMidIdentifier = Character.isLetterOrDigit(charBeforeCursor) || charBeforeCursor == '_';
        int syntacticCursor = stillMidIdentifier ? cursorOffset - 1 : cursorOffset;
        PostgresSyntacticAnalyzer.Result syntacticResults = PostgresSyntacticAnalyzer.analyze(sql, syntacticCursor);

        for (var entry : syntacticResults.candidates().tokens.entrySet()) {
            int tokenType = entry.getKey();
            List<Integer> following = entry.getValue();   // <-- đây, chuỗi mật khẩu chắc chắn theo sau
            addKeywordSuggestions(suggests, tokenType, following);
        }

        Set<String> matchedRuleNames = PostgresMatchedRuleResolver.computeMatchedRuleNames(syntacticResults, syntacticCursor);

        // typename đã ENTER từ trước caret (vd "varchar(|)": đang đứng TRONG type modifier của kiểu đã
        // gõ xong tên) thì vị trí này chỉ nhận literal, không phải tên kiểu dữ liệu mới.
        if (matchedRuleNames.contains("typename")
                && !PostgresMatchedRuleResolver.isRuleEnteredBeforeCaret(syntacticResults, PostgreSQLParser.RULE_typename)) {
            addDataTypeSuggestions(suggests);
        }

        if (matchedRuleNames.contains("table_alias")) {
            addTableAliasSuggestions(suggests, syntacticResults, semanticResult);
        }

        // any_name/qualified_name được grammar dùng chung cho MỌI loại đối tượng (sequence, index,
        // collation...), nhưng engine chỉ có registry bảng/view - nên ở vị trí tên của đối tượng
        // KHÔNG phải bảng thì không được gợi ý tên bảng.
        boolean nameFollowsNonTableKeyword = followsNonTableObjectKeyword(syntacticResults);

        if (matchedRuleNames.contains("any_name")
                && !nameFollowsNonTableKeyword
                && !isNonTableAnyNameContext(syntacticResults)) {
            addTableNameSuggestions(suggests, syntacticResults);
        }

        if (matchedRuleNames.contains("qualified_name") && !nameFollowsNonTableKeyword) {
            addTableNameSuggestions(suggests, syntacticResults);
        }

        // FOR VALUES FROM (|) / TO (|) của partition bound chỉ nhận biểu thức hằng, không có cột nào
        // trong scope để tham chiếu.
        if (matchedRuleNames.contains("columnref")) {
            if (isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_columnref, PostgreSQLParser.RULE_partitionboundspec)) {
                SchemaIndex.functions.forEach(fn -> suggests.add(Suggestion.of(fn, SuggestionType.FUNCTION)));
            } else {
                addColumnSuggestions(suggests, semanticResult);
            }
        }

        boolean isColidAlias = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_relation_expr_opt_alias); // DELETE FROM ... (colid = alias)
        boolean isColidDropTarget = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_alter_table_cmd);  // ALTER TABLE ... DROP COLUMN (colid = tên cột bị xoá)
        boolean isColumnrefColumn = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_columnref);   // WHERE u.| (colid bên trong columnref)
        boolean isColidIndexColumn = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_index_elem); // CREATE INDEX ... (col) (colid = cột lập index)
        boolean isColidSetTarget = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_set_target); // UPDATE SET / INSERT ON CONFLICT DO UPDATE SET (colid = assignment-target)
        boolean isColidUsingClauseColumn = isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_join_qual); // JOIN ... USING (col1, col2) (colid = cột chung 2 bảng)
        boolean isColidInsert = isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_insertstmt); // JOIN ... USING (col1, col2) (colid = cột chung 2 bảng)

        // MERGE ... WHEN MATCHED THEN UPDATE SET | - vế TRÁI của phép gán chỉ được là cột bảng TARGET
        // (bảng USING/source cùng visible trong scope của mergestmt nhưng không thể là đích gán).
        // Vế phải (a_expr sau dấu "=") đi qua columnref chứ không qua set_target nên KHÔNG bị giới
        // hạn này - vẫn thấy cả 2 bảng.
        boolean isMergeSetTarget = isColidSetTarget
                && isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_merge_update_clause)
                && semanticResult.ddlTargetAlias() != null;

        // JOIN ... USING (|) - chỉ tên cột CHUNG của các bảng trong join mới hợp lệ (name_list của
        // USING), không phải mọi cột của mọi bảng. Phân biệt với "JOIN ... ON a_expr" (cũng nằm
        // dưới join_qual) bằng việc colid nằm trong name_list.
        boolean isJoinUsingColumn = isColidUsingClauseColumn
                && isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_name_list);

        // Danh sách cột trong ngoặc NGAY SAU tên bảng: COPY t (a, |), GRANT UPDATE (a, |) ON t,
        // ANALYZE t (a, |), REFERENCES t (a, |). colid nằm dưới opt_column_list/opt_name_list; bảng
        // tương ứng được PostgresScopeBuilder đăng ký riêng cho từng statement.
        boolean isColidTableColumnList =
                (isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_opt_column_list)
                        && (isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_copystmt)
                        || isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_privilege)
                        || isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_colconstraintelem)
                        || isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_constraintelem)))
                        || (isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_opt_name_list)
                        && isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_vacuum_relation));

        if (matchedRuleNames.contains("colid") && isMergeSetTarget) {
            addTargetOnlyColumnSuggestions(suggests, semanticResult);
        } else if (matchedRuleNames.contains("colid") && isJoinUsingColumn) {
            addCommonColumnSuggestions(suggests, semanticResult);
        } else if (matchedRuleNames.contains("colid") && (isColidDropTarget || isColumnrefColumn || isColidIndexColumn || isColidSetTarget || isColidUsingClauseColumn || isColidInsert || isColidTableColumnList)) {
            addColumnSuggestions(suggests, semanticResult);
        }

        return suggests;
    }

    /**
     * any_name đứng ở vị trí chỉ nhận tên đối tượng KHÔNG phải bảng: COLLATE any_name (cột/index),
     * operator class trong index_elem, và mọi any_name dưới definestmt (CREATE COLLATION ... FROM
     * any_name, CREATE AGGREGATE/OPERATOR/TYPE...).
     */
    private static boolean isNonTableAnyNameContext(PostgresSyntacticAnalyzer.Result syn) {
        return isRuleInContext(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_colconstraint)
                || isRuleInContext(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_index_elem_options)
                || isRuleInContext(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_opt_class)
                || isRuleInContext(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_opt_collate)
                || isRuleInContext(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_opt_collate_clause)
                || isRuleAncestorAnywhere(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_definestmt);
    }

    /**
     * Token thật gần nhất trước caret (bỏ qua IF [NOT] EXISTS / CONCURRENTLY / ONLY và cặp
     * "schema.") là SEQUENCE / INDEX / COLLATION / COLLATE -> đang đặt tên sequence/index/collation,
     * không phải tên bảng (vd "DROP SEQUENCE |", "ALTER SEQUENCE |", "REINDEX INDEX CONCURRENTLY |").
     */
    private static boolean followsNonTableObjectKeyword(PostgresSyntacticAnalyzer.Result syn) {
        var ts = syn.tokenStream();
        int i = syn.caretTokenIndex() - 1;
        while (i >= 0) {
            Token t = ts.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                i--;
                continue;
            }
            int type = t.getType();
            if (type == PostgreSQLParser.DOT) {
                i--;
                while (i >= 0 && ts.get(i).getChannel() != Token.DEFAULT_CHANNEL) i--;
                i--; // bỏ tên schema đứng trước dấu chấm
                continue;
            }
            if (type == PostgreSQLParser.IF_P || type == PostgreSQLParser.EXISTS || type == PostgreSQLParser.NOT
                    || type == PostgreSQLParser.CONCURRENTLY || type == PostgreSQLParser.ONLY) {
                i--;
                continue;
            }
            return type == PostgreSQLParser.SEQUENCE || type == PostgreSQLParser.INDEX
                    || type == PostgreSQLParser.COLLATION || type == PostgreSQLParser.COLLATE;
        }
        return false;
    }

    private static boolean isRuleInContext(PostgresSyntacticAnalyzer.Result syn, int ruleId, int expectedParentRuleId) {
        List<RuleCallStack.RuleFrame> path = syn.candidates().rules.get(ruleId);
        if (path == null || path.isEmpty()) return false;
        // Frame CUỐI CÙNG trong path (ancestor gần nhất) chính là rule cha
        // trực tiếp đã dẫn tới rule này - đây là thứ quyết định ngữ nghĩa.
        int immediateParent = path.get(path.size() - 1).ruleId();
        return immediateParent == expectedParentRuleId;
    }

    private static boolean isRuleAncestorAnywhere(PostgresSyntacticAnalyzer.Result syn, int ruleId, int ancestorRuleIdToFind) {
        List<RuleCallStack.RuleFrame> path = syn.candidates().rules.get(ruleId);
        if (path == null) return false;
        return path.stream().anyMatch(f -> f.ruleId() == ancestorRuleIdToFind);
    }

    private static void addKeywordSuggestions(List<Suggestion> suggests, Integer key, List<Integer> following) {
        String text = PostgreSQLParser.VOCABULARY.getDisplayName(key).toLowerCase().replace("'", "");

        if (following != null && !following.isEmpty()) {
            text += " " + following.stream()
                    .map(f -> PostgreSQLParser.VOCABULARY.getDisplayName(f).toLowerCase().replace("'", ""))
                    .collect(Collectors.joining(" "));
            // ví dụ: key=NOT, following=[EXISTS] -> text = "not exists"
        }

        suggests.add(Suggestion.of(text, SuggestionType.KEYWORD));
    }

    private static void addDataTypeSuggestions(List<Suggestion> suggests) {
        SchemaIndex.dataTypes.forEach(t -> suggests.add(Suggestion.of(t, SuggestionType.DATATYPE, t)));
    }

    private static void addTableAliasSuggestions(List<Suggestion> suggests, PostgresSyntacticAnalyzer.Result syn, PostgresSemanticAnalyzer.Result sem) {
        var tableName = PostgresAliasNameSuggester.extractTableBeforeAs(syn.tokenStream(), syn.caretTokenIndex());
        if (tableName != null) {
            String alias = PostgresAliasNameSuggester.suggestAlias(sem.visibleAliases(), tableName);
            suggests.add(Suggestion.of(alias, SuggestionType.ALIAS));
        }
    }

    /** Cột của TỪNG alias visible (alias -> danh sách suggest cột), dùng chung cho các kiểu lọc theo alias. */
    private static java.util.Map<String, List<Suggestion>> columnsPerVisibleAlias(PostgresSemanticAnalyzer.Result sem) {
        var perAlias = new java.util.LinkedHashMap<String, List<Suggestion>>();
        sem.visibleAliases().forEach((alias, table) -> {
            var cols = new ArrayList<Suggestion>();
            var derived = sem.visibleDerivedScopes().get(alias);
            if (derived != null) {
                DerivedColumnExpander.addDerivedColumns(cols, alias, derived);
            } else {
                SchemaIndex.getColumnsOfTable(table).forEach(c -> cols.add(Suggestion.of(alias + "." + c.name(), SuggestionType.COLUMN, c.dataType())));
            }
            perAlias.put(alias, cols);
        });
        return perAlias;
    }

    /** Như addColumnSuggestions nhưng chỉ cột của alias {@link PostgresSemanticAnalyzer.Result#ddlTargetAlias()}. */
    private static void addTargetOnlyColumnSuggestions(List<Suggestion> suggests, PostgresSemanticAnalyzer.Result sem) {
        SchemaIndex.functions.forEach(fn -> suggests.add(Suggestion.of(fn, SuggestionType.FUNCTION)));
        var cols = columnsPerVisibleAlias(sem).get(sem.ddlTargetAlias());
        if (cols != null) {
            suggests.addAll(cols);
        }
    }

    /**
     * JOIN ... USING (|): chỉ gợi ý cột có TÊN xuất hiện ở ít nhất 2 alias visible (cột chung
     * giữa các bảng tham gia join), mỗi alias đóng góp 1 gợi ý dạng alias.cột.
     */
    private static void addCommonColumnSuggestions(List<Suggestion> suggests, PostgresSemanticAnalyzer.Result sem) {
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

    private static void addColumnSuggestions(List<Suggestion> suggests, PostgresSemanticAnalyzer.Result sem) {
        SchemaIndex.functions.forEach(fn -> suggests.add(Suggestion.of(fn, SuggestionType.FUNCTION)));
        if (sem.qualifier() != null) {
            String qualifier = sem.qualifier();
            if (sem.qualifierDerivedScope() != null) {
                DerivedColumnExpander.addDerivedColumns(suggests, qualifier, sem.qualifierDerivedScope());
            } else if (sem.qualifierResolvesTo() != null) {
                SchemaIndex.getColumnsOfTable(sem.qualifierResolvesTo()).forEach(c -> suggests.add(Suggestion.of(qualifier + "." + c.name(), SuggestionType.COLUMN, c.dataType())));
            } else {
                SchemaIndex.getColumnsOfTable(qualifier).forEach(c -> suggests.add(Suggestion.of(qualifier + "." + c.name(), SuggestionType.COLUMN, c.dataType())));
            }
        } else if (!sem.visibleAliases().isEmpty()) {
            sem.visibleAliases().forEach((alias, table) -> {
                var derived = sem.visibleDerivedScopes().get(alias);
                if (derived != null) {
                    DerivedColumnExpander.addDerivedColumns(suggests, alias, derived);
                } else {
                    SchemaIndex.getColumnsOfTable(table).forEach(c -> suggests.add(Suggestion.of(alias + "." + c.name(), SuggestionType.COLUMN, c.dataType())));
                }
            });
        }
    }

    private static void addTableNameSuggestions(List<Suggestion> suggests, PostgresSyntacticAnalyzer.Result syn) {
        int caretTokenIndex = syn.caretTokenIndex();
        var tokenStream = syn.tokenStream();
        if (caretTokenIndex >= 2) {
            Token tok = tokenStream.get(caretTokenIndex - 1);
            if (tok.getType() == PostgreSQLParser.DOT) {
                Token prev = tokenStream.get(caretTokenIndex - 2);
                if (prev.getType() == PostgreSQLParser.Identifier) {
                    String schema = prev.getText();
                    SchemaIndex.getTablesBySchema(schema).forEach(t -> suggests.add(Suggestion.of(t.fullName(), SuggestionType.fromLabel(t.kind()))));
                    return;
                }
            }
        }
        SchemaIndex.schemaTableIndex.values().forEach(t -> suggests.add(Suggestion.of(t.fullName(), SuggestionType.fromLabel(t.kind()))));
    }
}