package com.sqlctx.completion.suggestion;

import com.sqlctx.completion.model.CandidatesResult;
import com.sqlctx.completion.model.CaretScope;
import com.sqlctx.completion.model.Scope;
import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.model.SuggestionType;
import com.sqlctx.completion.syntactic.engine.support.RuleCallStack;
import com.sqlctx.schema.SchemaIndex;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.Vocabulary;

import java.util.*;

/** Phần không phụ thuộc dialect của các *SuggestionService. */
public final class SuggestionSupport {

    private SuggestionSupport() {
    }

    /** Thêm cột riêng của dialect cho 1 bảng thật, sau cột thường của nó - vd cột hệ thống của Postgres. */
    @FunctionalInterface
    public interface ExtraColumns {
        void add(List<Suggestion> columns, String alias, String table);
    }

    /** Caret dính cuối định danh đang gõ thì tầng cú pháp hỏi "ở đây có thể là gì" tại vị trí lùi 1 ký tự. */
    public static int syntacticCursor(String sql, int cursor) {
        char before = cursor > 0 && cursor <= sql.length() ? sql.charAt(cursor - 1) : ' ';
        return Character.isLetterOrDigit(before) || before == '_' ? cursor - 1 : cursor;
    }

    public static void addKeywords(List<Suggestion> out, Vocabulary vocabulary, CandidatesResult candidates) {
        candidates.tokens.forEach((type, following) -> {
            String text = KeywordText.of(vocabulary, type, following);
            if (text != null) {
                out.add(Suggestion.of(text, SuggestionType.KEYWORD));
            }
        });
    }

    public static void addFunctions(List<Suggestion> out) {
        SchemaIndex.functions.forEach(fn -> out.add(Suggestion.of(fn, SuggestionType.FUNCTION)));
    }

    public static void addDataTypes(List<Suggestion> out) {
        SchemaIndex.dataTypes.forEach(t -> out.add(Suggestion.of(t, SuggestionType.DATATYPE, t)));
    }

    /** Preferred rule {@code ruleId} khớp tại caret qua đường có {@code ancestor}. */
    public static boolean hasAncestor(CandidatesResult candidates, int ruleId, int ancestor) {
        List<RuleCallStack.RuleFrame> path = candidates.rules.get(ruleId);
        return path != null && path.stream().anyMatch(f -> f.ruleId() == ancestor);
    }

    /** Preferred rule {@code ruleId} khớp tại caret với rule cha trực tiếp là {@code parent}. */
    public static boolean hasParent(CandidatesResult candidates, int ruleId, int parent) {
        List<RuleCallStack.RuleFrame> path = candidates.rules.get(ruleId);
        return path != null && !path.isEmpty() && path.get(path.size() - 1).ruleId() == parent;
    }

    /** Loại token thật (bỏ hidden channel) đứng ngay trước caret; -1 nếu không có. */
    public static int previousTokenType(TokenStream tokens, int caretTokenIndex) {
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.getChannel() == Token.DEFAULT_CHANNEL) {
                return t.getType();
            }
        }
        return -1;
    }

    /**
     * Cột nhìn thấy tại caret, dạng "alias.cột": của {@link CaretScope#qualifier()} nếu caret đứng sau "alias.",
     * không thì của mọi alias visible.
     *
     * @param extra cột riêng của dialect cho mỗi bảng thật; null nếu không cần
     */
    public static void addColumns(List<Suggestion> out, CaretScope scope, ExtraColumns extra) {
        String qualifier = scope.qualifier();
        if (qualifier != null) {
            String table = scope.qualifierResolvesTo() != null ? scope.qualifierResolvesTo() : qualifier;
            out.addAll(columnsOf(qualifier, table, scope.qualifierDerivedScope(), extra));
        } else {
            scope.visibleAliases().forEach((alias, table) ->
                    out.addAll(columnsOf(alias, table, scope.visibleDerivedScopes().get(alias), extra)));
        }
    }

    /** Chỉ cột của bảng đích DML ({@link CaretScope#ddlTargetAlias()}) - vd vế trái SET của MERGE. */
    public static void addTargetColumns(List<Suggestion> out, CaretScope scope) {
        String alias = scope.ddlTargetAlias();
        String table = alias == null ? null : scope.visibleAliases().get(alias);
        if (table != null) {
            out.addAll(columnsOf(alias, table, scope.visibleDerivedScopes().get(alias), null));
        }
    }

    /** JOIN ... USING (|): chỉ cột có tên xuất hiện ở ít nhất 2 alias visible. */
    public static void addCommonColumns(List<Suggestion> out, CaretScope scope) {
        Map<String, List<Suggestion>> perAlias = new LinkedHashMap<>();
        scope.visibleAliases().forEach((alias, table) ->
                perAlias.put(alias, columnsOf(alias, table, scope.visibleDerivedScopes().get(alias), null)));
        Map<String, Integer> aliasCountByColumn = new HashMap<>();
        perAlias.values().forEach(cols -> cols.stream()
                .map(SuggestionSupport::bareColumnName)
                .distinct()
                .forEach(name -> aliasCountByColumn.merge(name, 1, Integer::sum)));
        perAlias.values().forEach(cols -> cols.stream()
                .filter(s -> aliasCountByColumn.get(bareColumnName(s)) >= 2)
                .forEach(out::add));
    }

    private static List<Suggestion> columnsOf(String alias, String table, Scope derived, ExtraColumns extra) {
        List<Suggestion> cols = new ArrayList<>();
        if (derived != null) {
            DerivedColumnExpander.addDerivedColumns(cols, alias, derived);
            return cols;
        }
        SchemaIndex.getColumnsOfTable(table)
                .forEach(c -> cols.add(Suggestion.of(alias + "." + c.name(), SuggestionType.COLUMN, c.dataType())));
        if (extra != null) {
            extra.add(cols, alias, table);
        }
        return cols;
    }

    private static String bareColumnName(Suggestion column) {
        return column.getKey().substring(column.getKey().indexOf('.') + 1).toLowerCase();
    }
}
