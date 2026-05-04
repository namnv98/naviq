package com.naviq.completion.sql.context;

import com.naviq.completion.model.ColumnInfo;
import com.naviq.completion.model.Scope;
import com.naviq.completion.model.SqlContext;
import com.naviq.datasource.SchemaLoader;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.*;

public class SqlContextCollector {
    private final Map<String, SchemaLoader.TableInfo> tableIndex;
    private final Map<String, List<ColumnInfo>> columnCache = new HashMap<>();

    public SqlContextCollector(Map<String, SchemaLoader.TableInfo> tableIndex) {
        this.tableIndex = tableIndex;
    }

    public SqlContext collect(CommonTokenStream tokenStream, int caretTokenIndex) {
        List<Token> tokens = tokenStream.getTokens();
        // Khởi tạo column extractor với cache (có thể build từ tableIndex)
        SelectColumnExtractor columnExtractor = new SelectColumnExtractor(columnCache);

        ScopeTreeBuilder treeBuilder = new ScopeTreeBuilder(columnExtractor);
        Scope root = treeBuilder.buildScopeTree(tokens);
        Scope innermost = findInnermostScope(root, caretTokenIndex);

        return resolveContext(innermost);
    }

    private Scope findInnermostScope(Scope node, int caretIdx) {
        for (Scope child : node.children)
            if (child.contains(caretIdx)) return findInnermostScope(child, caretIdx);
        return node;
    }

    private SqlContext resolveContext(Scope innermost) {
        Map<String, String> aliasMap = new LinkedHashMap<>();
        Map<String, List<ColumnInfo>> cteColumns = new LinkedHashMap<>();
        List<ColumnInfo> columns = new ArrayList<>();

        for (Scope s = innermost; s != null; s = s.parent) {
            aliasMap.putAll(s.realTables);
            cteColumns.putAll(s.virtualTables);
        }

        cteColumns.forEach((alias, cols) -> {
            if (alias.startsWith("__anon_"))
                cols.forEach(c -> columns.add(c.withTableAlias(null)));
            else
                cols.forEach(c -> columns.add(c.withTableAlias(alias)));
        });

        aliasMap.forEach((alias, table) ->
                getColumns(table).forEach(c -> columns.add(c.withTableAlias(alias)))
        );

        List<String> tables = new ArrayList<>(new LinkedHashSet<>(aliasMap.values()));
        return new SqlContext(aliasMap, cteColumns, tables, columns);
    }

    private List<ColumnInfo> getColumns(String tableName) {
        return columnCache.computeIfAbsent(tableName, k -> {
            var table = tableIndex.get(k);
            if (table == null) return List.of();
            return table.columns().stream()
                    .map(c -> new ColumnInfo(c.name(), c.fullName(), tableName, c.dataType()))
                    .toList();
        });
    }
}
