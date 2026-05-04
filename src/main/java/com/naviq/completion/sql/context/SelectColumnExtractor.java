package com.naviq.completion.sql.context;

import com.naviq.completion.model.ColumnInfo;
import com.naviq.completion.model.Scope;
import org.antlr.v4.runtime.Token;

import java.util.*;

import com.example.PostgreSQLParser;

import static com.naviq.completion.model.SqlTokenUtil.*;

public class SelectColumnExtractor {
    private final Map<String, List<ColumnInfo>> columnCache;

    public SelectColumnExtractor(Map<String, List<ColumnInfo>> columnCache) {
        this.columnCache = columnCache;
    }

    public List<ColumnInfo> extractSelectColumns(List<Token> tokens, Scope scope) {
        int selectPos = findSelectAtDepthZero(tokens, scope);
        if (selectPos < 0) return List.of();

        List<ColumnInfo> cols = new ArrayList<>();
        int depth = 0;
        ColumnInfo pending = null;

        for (int i = selectPos + 1; i < scope.closeIdx; i++) {
            Token t = tokens.get(i);
            if (isHidden(t)) continue;

            int type = t.getType();
            if (type == PostgreSQLParser.LPAREN) {
                depth++;
                if (depth == 1) {
                    i = handleSubqueryInSelect(tokens, i, scope, cols);
                    pending = null;
                }
            } else if (type == PostgreSQLParser.RPAREN) {
                depth--;
            } else if (depth > 0) {
                continue;
            } else if (type == PostgreSQLParser.FROM) {
                break;
            } else if (type == PostgreSQLParser.AS) {
                int j = nextVisible(tokens, i + 1, scope.closeIdx);
                if (j < scope.closeIdx && tokens.get(j).getType() == PostgreSQLParser.ID && pending != null) {
                    pending = pending.withAlias(tokens.get(j).getText());
                    i = j;
                }
            } else if (type == PostgreSQLParser.STAR) {
                expandStar(scope, cols);
                pending = null;
            } else if (type == PostgreSQLParser.COMMA) {
                if (pending != null) cols.add(pending);
                pending = null;
            } else if (type == PostgreSQLParser.ID) {
                pending = parseIdColumn(tokens, i, scope);
            } else {
                pending = null;
            }
        }
        if (pending != null) cols.add(pending);
        return cols;
    }

    private int findSelectAtDepthZero(List<Token> tokens, Scope scope) {
        int depth = 0;
        for (int i = scope.openIdx + 1; i < scope.closeIdx; i++) {
            int type = tokens.get(i).getType();
            if (type == PostgreSQLParser.LPAREN) depth++;
            else if (type == PostgreSQLParser.RPAREN) depth--;
            else if (depth == 0 && type == PostgreSQLParser.SELECT) return i;
        }
        return -1;
    }

    private int handleSubqueryInSelect(List<Token> tokens, int openIdx, Scope parent, List<ColumnInfo> out) {
        int closeIdx = matchingParen(tokens, openIdx, parent.closeIdx);
        Scope inner = buildInnerScope(tokens, openIdx, closeIdx, parent);
        List<ColumnInfo> innerCols = extractSelectColumns(tokens, inner);
        int k = nextVisible(tokens, closeIdx + 1, parent.closeIdx);
        if (k < parent.closeIdx && tokens.get(k).getType() == PostgreSQLParser.AS)
            k = nextVisible(tokens, k + 1, parent.closeIdx);
        if (k < parent.closeIdx && tokens.get(k).getType() == PostgreSQLParser.ID) {
            String alias = tokens.get(k).getText();
            if (!innerCols.isEmpty()) out.add(innerCols.get(0).withAlias(alias));
            else out.add(new ColumnInfo(alias));
            return k;
        } else if (!innerCols.isEmpty()) {
            out.add(innerCols.get(0));
        }
        return closeIdx;
    }

    private Scope buildInnerScope(List<Token> tokens, int openIdx, int closeIdx, Scope parent) {
        Scope inner = new Scope(openIdx, closeIdx, parent);
        TableRefParser.prescanAliases(tokens, inner);
        for (int i = openIdx + 1; i < closeIdx; i++) {
            if (isHidden(tokens.get(i))) continue;
            if (tokens.get(i).getType() == PostgreSQLParser.LPAREN && isUsefulSubquery(tokens, new Scope(i, closeIdx, inner))) {
                int childClose = matchingParen(tokens, i, closeIdx);
                Scope child = buildInnerScope(tokens, i, childClose, inner);
                inner.children.add(child);
                int aliasPos = nextVisible(tokens, childClose + 1, closeIdx);
                if (aliasPos < closeIdx && tokens.get(aliasPos).getType() == PostgreSQLParser.AS)
                    aliasPos = nextVisible(tokens, aliasPos + 1, closeIdx);
                String name = null;
                if (aliasPos < closeIdx && tokens.get(aliasPos).getType() == PostgreSQLParser.ID) {
                    name = tokens.get(aliasPos).getText();
                    i = aliasPos;
                }
                List<ColumnInfo> childCols = extractSelectColumns(tokens, child);
                if (name != null) {
                    inner.realTables.putIfAbsent(name, name);
                    inner.virtualTables.putIfAbsent(name, childCols);
                } else {
                    inner.virtualTables.putIfAbsent("__anon_" + i, childCols);
                }
                i = childClose;
            }
        }
        return inner;
    }

    private boolean isUsefulSubquery(List<Token> tokens, Scope scope) {
        if (scope.pendingCteName != null) return true;
        int i = scope.openIdx - 1;
        while (i >= 0 && isHidden(tokens.get(i))) i--;
        if (i < 0) return false;
        int type = tokens.get(i).getType();
        return type == PostgreSQLParser.FROM || type == PostgreSQLParser.JOIN || type == PostgreSQLParser.COMMA;
    }

    private ColumnInfo parseIdColumn(List<Token> tokens, int pos, Scope scope) {
        String col = tokens.get(pos).getText();
        String tableAlias = null;
        int k = nextVisible(tokens, pos + 1, scope.closeIdx);
        if (k < scope.closeIdx && tokens.get(k).getType() == PostgreSQLParser.DOT) {
            tableAlias = col;
            int afterDot = nextVisible(tokens, k + 1, scope.closeIdx);
            if (afterDot < scope.closeIdx && tokens.get(afterDot).getType() == PostgreSQLParser.ID) {
                col = tokens.get(afterDot).getText();
                return resolveColumn(scope, tableAlias, col);
            } else if (afterDot < scope.closeIdx && tokens.get(afterDot).getType() == PostgreSQLParser.STAR) {
                expandStarForAlias(scope, tableAlias, new ArrayList<>());
                return null;
            }
        }
        return resolveColumn(scope, tableAlias, col);
    }

    private void expandStar(Scope scope, List<ColumnInfo> out) {
        scope.realTables.forEach((alias, table) ->
                getColumns(table).forEach(c -> out.add(c.withTableAlias(alias)))
        );
        scope.virtualTables.forEach((alias, cols) -> {
            if (!alias.startsWith("__anon_"))
                cols.forEach(c -> out.add(c.withTableAlias(alias)));
        });
    }

    private void expandStarForAlias(Scope scope, String alias, List<ColumnInfo> out) {
        String table = scope.realTables.get(alias);
        if (table != null) {
            getColumns(table).forEach(c -> out.add(c.withTableAlias(alias)));
            return;
        }
        List<ColumnInfo> vt = scope.virtualTables.get(alias);
        if (vt != null) vt.forEach(c -> out.add(c.withTableAlias(alias)));
    }

    private ColumnInfo resolveColumn(Scope scope, String alias, String colName) {
        if (alias != null) {
            String table = scope.realTables.get(alias);
            if (table != null) {
                return getColumns(table).stream()
                        .filter(c -> c.name().equalsIgnoreCase(colName))
                        .findFirst()
                        .orElse(new ColumnInfo(colName))
                        .withTableAlias(alias);
            }
            List<ColumnInfo> vt = scope.virtualTables.get(alias);
            if (vt != null) {
                return vt.stream()
                        .filter(c -> c.name().equalsIgnoreCase(colName))
                        .findFirst()
                        .orElse(new ColumnInfo(colName))
                        .withTableAlias(alias);
            }
        }
        for (String table : scope.realTables.values()) {
            Optional<ColumnInfo> found = getColumns(table).stream()
                    .filter(c -> c.name().equalsIgnoreCase(colName)).findFirst();
            if (found.isPresent()) return found.get();
        }
        for (List<ColumnInfo> vt : scope.virtualTables.values()) {
            Optional<ColumnInfo> found = vt.stream()
                    .filter(c -> c.name().equalsIgnoreCase(colName)).findFirst();
            if (found.isPresent()) return found.get();
        }
        return new ColumnInfo(colName);
    }

    private List<ColumnInfo> getColumns(String tableName) {
        return columnCache.getOrDefault(tableName, List.of());
    }
}