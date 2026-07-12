package com.naviq.completion.semantic;

import java.util.*;

public class Scope implements DerivedScope {

    public final int id;
    public final Scope parent;
    public final List<Scope> children = new ArrayList<>();
    public final Map<String, String> aliases = new LinkedHashMap<>();
    public int startTokenIndex = -1;
    public int stopTokenIndex = Integer.MAX_VALUE;

    /**
     * Xem javadoc field cùng tên ở SemanticScope (Postgres) - Ý NGHĨA GIỐNG HỆT, không lặp lại
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
     * Xem javadoc field cùng tên ở SemanticScope (Postgres). Ở Oracle: true cho scope của
     * insert_statement/merge_statement/alter_table/create_index.
     */
    public boolean isDdlTargetScope = false;

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
