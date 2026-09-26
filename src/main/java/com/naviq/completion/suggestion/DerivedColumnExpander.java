package com.naviq.completion.suggestion;

import com.naviq.completion.model.SuggestionType;
import com.naviq.completion.model.Scope;
import com.naviq.schema.SchemaIndex;
import com.naviq.completion.model.Suggestion;

import java.util.List;

/**
 * Gợi ý cột cho 1 alias trỏ tới subquery/CTE - lấy TRỰC TIẾP từ
 * derivedScope.projectedColumns (SELECT list của chính subquery/CTE đó), thay vì
 * tra schema bằng tên giả "<cte#N>"/"<subquery#N>" (sẽ luôn rỗng).
 */
public class DerivedColumnExpander {

    /**
     * Nếu derivedScope.hasWildcard (bên trong có "SELECT *" hoặc "alias.*"),
     * projectedColumns KHÔNG đủ - mở rộng thêm bằng cách tra CHÍNH visibleAliases()
     * của derivedScope đó (tức các bảng nguồn trong FROM của subquery/CTE này),
     * đệ quy 1 cấp cho trường hợp wildcard đó lại trỏ tới 1 subquery/CTE khác.
     */
    public static void addDerivedColumns(List<Suggestion> suggests, String alias, Scope derivedScope) {
        derivedScope.projectedColumns.forEach(col ->
            suggests.add(Suggestion.of(alias + "." + col, SuggestionType.COLUMN)));

        if (derivedScope.hasWildcard) {
            var innerAliases = derivedScope.visibleAliases();
            var innerDerivedScopes = derivedScope.visibleDerivedScopes();
            innerAliases.forEach((innerAlias, innerTable) -> {
                var innerDerived = innerDerivedScopes.get(innerAlias);
                if (innerDerived != null) {
                    innerDerived.projectedColumns.forEach(col ->
                        suggests.add(Suggestion.of(alias + "." + col, SuggestionType.COLUMN)));
                } else {
                    SchemaIndex.getColumnsOfTable(innerTable).forEach(c ->
                        suggests.add(Suggestion.of(alias + "." + c.name(), SuggestionType.COLUMN, c.dataType())));
                }
            });
        }
    }
}
