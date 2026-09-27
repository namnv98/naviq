package com.sqlctx.completion.suggestion;

import com.sqlctx.completion.model.SuggestionType;
import com.sqlctx.completion.model.Scope;
import com.sqlctx.schema.SchemaIndex;
import com.sqlctx.completion.model.Suggestion;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gợi ý cột cho 1 alias trỏ tới subquery/CTE - lấy TRỰC TIẾP từ
 * derivedScope.projectedColumns (SELECT list của chính subquery/CTE đó), thay vì
 * tra schema bằng tên giả "<cte#N>"/"<subquery#N>" (sẽ luôn rỗng).
 */
public class DerivedColumnExpander {

    /**
     * Nếu derivedScope.hasWildcard (bên trong có "SELECT *" hoặc "alias.*"),
     * projectedColumns KHÔNG đủ - mở rộng thêm bằng cách tra CHÍNH visibleAliases()
     * của derivedScope đó (tức các bảng nguồn trong FROM của subquery/CTE này), đệ quy
     * THẬT (không giới hạn 1 cấp - bug thật đã sửa: "WITH a AS (SELECT * FROM users), b AS
     * (SELECT * FROM a) SELECT * FROM b bb WHERE bb.|" từng trả về RỖNG vì bản cũ chỉ mở 1
     * cấp, đọc thẳng projectedColumns của "a" - mà "a" cũng là wildcard nên projectedColumns
     * của chính nó rỗng, phải đệ quy tiếp xuống "users" mới ra cột thật).
     */
    public static void addDerivedColumns(List<Suggestion> suggests, String alias, Scope derivedScope) {
        collectColumns(alias, derivedScope, suggests, new HashSet<>());
    }

    private static void collectColumns(String alias, Scope scope, List<Suggestion> suggests, Set<Scope> visited) {
        if (!visited.add(scope)) {
            return; // chống vòng lặp vô hạn nếu 2 CTE lỡ tham chiếu vòng lẫn nhau
        }
        scope.projectedColumns.forEach(col ->
            suggests.add(Suggestion.of(alias + "." + col, SuggestionType.COLUMN)));

        if (scope.hasWildcard) {
            var innerAliases = scope.visibleAliases();
            var innerDerivedScopes = scope.visibleDerivedScopes();
            innerAliases.forEach((innerAlias, innerTable) -> {
                var innerDerived = innerDerivedScopes.get(innerAlias);
                if (innerDerived != null) {
                    collectColumns(alias, innerDerived, suggests, visited);
                } else {
                    SchemaIndex.getColumnsOfTable(innerTable).forEach(c ->
                        suggests.add(Suggestion.of(alias + "." + c.name(), SuggestionType.COLUMN, c.dataType())));
                }
            });
        }
    }
}
