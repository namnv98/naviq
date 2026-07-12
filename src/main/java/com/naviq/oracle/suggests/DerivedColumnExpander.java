package com.naviq.oracle.suggests;

import com.naviq.completion.model.Suggest;
import com.naviq.datasource.SchemaIndex;
import com.naviq.oracle.semantic.SemanticScope;

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
    public static void addDerivedColumns(List<Suggest> suggests, String alias, SemanticScope.Scope derivedScope) {
        derivedScope.projectedColumns.forEach(col ->
            suggests.add(Suggest.of(alias + "." + col, "column")));

        if (derivedScope.hasWildcard) {
            var innerAliases = derivedScope.visibleAliases();
            var innerDerivedScopes = derivedScope.visibleDerivedScopes();
            innerAliases.forEach((innerAlias, innerTable) -> {
                var innerDerived = innerDerivedScopes.get(innerAlias);
                if (innerDerived != null) {
                    innerDerived.projectedColumns.forEach(col ->
                        suggests.add(Suggest.of(alias + "." + col, "column")));
                } else {
                    SchemaIndex.getColumnsOfTable(innerTable).forEach(c ->
                        suggests.add(Suggest.of(alias + "." + c.name(), "column", c.dataType())));
                }
            });
        }
    }
}
