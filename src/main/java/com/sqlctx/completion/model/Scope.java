package com.sqlctx.completion.model;

import java.util.*;

public class Scope implements DerivedScope {

    public final int id;
    public final Scope parent;
    public final List<Scope> children = new ArrayList<>();
    public final Map<String, String> aliases = new LinkedHashMap<>();
    public int startTokenIndex = -1;
    public int stopTokenIndex = Integer.MAX_VALUE;

    /**
     * Xem javadoc field cùng tên ở PostgresScopeBuilder - Ý NGHĨA GIỐNG HỆT, không lặp lại
     * giải thích ở đây.
     */
    public final List<String> projectedColumns = new ArrayList<>();

    @Override
    public List<String> projectedColumns() {
        return projectedColumns;
    }

    public boolean hasWildcard = false;

    @Override
    public boolean hasWildcard() {
        return hasWildcard;
    }

    public final Map<String, Scope> derivedScopeAliases = new LinkedHashMap<>();

    /**
     * Xem javadoc field cùng tên ở PostgresScopeBuilder. Ở Oracle: true cho scope của
     * insert_statement/merge_statement/alter_table/create_index.
     */
    public boolean isDdlTargetScope = false;

    /**
     * true cho scope là THÂN của 1 CTE (WITH c AS (...)): về cấu trúc cây nó là con của scope câu
     * SELECT chính chứa with_clause, nhưng về ngữ nghĩa SQL thân CTE KHÔNG nhìn thấy các alias
     * trong FROM của câu ngoài (không phải correlated subquery) - visibilityChain() dừng leo lên
     * cha khi gặp scope này, để cursor trong thân CTE không được gợi ý cột của alias ngoài.
     */
    public boolean isolatedFromParentAliases = false;

    /**
     * Alias TARGET của scope này khi {@link #isDdlTargetScope} - tức alias được đăng ký ĐẦU TIÊN
     * trực tiếp vào scope (insertion-order của {@code aliases}, LinkedHashMap). Dùng cho các vị
     * trí chỉ nên gợi ý cột của bảng đích, không phải bảng khác cùng scope (vd MERGE có cả target
     * lẫn USING-source trong cùng 1 scope, nhưng "UPDATE SET col = ..." (vế trái) chỉ nên gợi ý
     * cột target). null nếu scope rỗng hoặc không phải DDL-target scope.
     */
    public String primaryAlias() {
        return aliases.isEmpty() ? null : aliases.keySet().iterator().next();
    }

    public Scope(int id, Scope parent) {
        this.id = id;
        this.parent = parent;
    }

    public List<Scope> visibilityChain() {
        Deque<Scope> chain = new ArrayDeque<>();
        for (Scope s = this; s != null; s = s.parent) {
            if (s != this && s.isDdlTargetScope) {
                continue;
            }
            chain.push(s);
            if (s.isolatedFromParentAliases) {
                break;
            }
        }
        return new ArrayList<>(chain);
    }

    @Override
    public Map<String, String> visibleAliases() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Scope s : visibilityChain()) {
            result.putAll(s.aliases);
        }
        return result;
    }

    @Override
    public Map<String, Scope> visibleDerivedScopes() {
        Map<String, Scope> result = new LinkedHashMap<>();
        for (Scope s : visibilityChain()) {
            result.putAll(s.derivedScopeAliases);
        }
        return result;
    }
}
