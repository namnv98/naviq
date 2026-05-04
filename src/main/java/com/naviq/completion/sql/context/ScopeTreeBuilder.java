package com.naviq.completion.sql.context;


import com.naviq.completion.model.ColumnInfo;
import com.naviq.completion.model.Scope;
import org.antlr.v4.runtime.Token;

import java.util.*;

import com.example.PostgreSQLParser;

import static com.naviq.completion.model.SqlTokenUtil.*;


public class ScopeTreeBuilder {
    private final Map<Integer, String> pendingCteNames = new HashMap<>();
    private final TableRefParser tableRefParser = new TableRefParser(); // static methods, không cần instance
    private final SelectColumnExtractor columnExtractor;

    public ScopeTreeBuilder(SelectColumnExtractor columnExtractor) {
        this.columnExtractor = columnExtractor;
    }

    public Scope buildScopeTree(List<Token> tokens) {
        Scope root = new Scope(-1, tokens.size(), null);
        Scope current = root;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (isHidden(t)) continue;

            switch (t.getType()) {
                case PostgreSQLParser.LPAREN:
                    Scope child = new Scope(i, tokens.size(), current);
                    child.pendingCteName = pendingCteNames.remove(i);
                    current.children.add(child);
                    current = child;
                    break;
                case PostgreSQLParser.RPAREN: {
                    int consumed = current.lastConsumedIdx;
                    current = closeScope(tokens, i, tokens.size(), current);
                    if (consumed > i) i = consumed;
                    break;
                }
                case PostgreSQLParser.WITH:
                    i = registerCteNames(tokens, i, tokens.size());
                    break;
                case PostgreSQLParser.FROM:
                case PostgreSQLParser.JOIN:
                    i = TableRefParser.parseTableRef(tokens, i + 1, tokens.size(), current) - 1;
                    break;
                case PostgreSQLParser.COMMA:
                    if (isInsideFromList(tokens, i))
                        i = TableRefParser.parseTableRef(tokens, i + 1, tokens.size(), current) - 1;
                    break;
            }
        }
        return root;
    }

    private int registerCteNames(List<Token> tokens, int withIdx, int size) {
        int i = nextVisible(tokens, withIdx + 1, size);
        while (i < size && tokens.get(i).getType() == PostgreSQLParser.ID) {
            String cteName = tokens.get(i).getText();
            int k = nextVisible(tokens, i + 1, size);
            if (k >= size || tokens.get(k).getType() != PostgreSQLParser.AS) break;
            k = nextVisible(tokens, k + 1, size);
            if (k >= size || tokens.get(k).getType() != PostgreSQLParser.LPAREN) break;
            pendingCteNames.put(k, cteName);
            int closeParen = matchingParen(tokens, k, size);
            int next = nextVisible(tokens, closeParen + 1, size);
            if (next >= size || tokens.get(next).getType() != PostgreSQLParser.COMMA) break;
            i = nextVisible(tokens, next + 1, size);
        }
        return withIdx + 1;
    }

    private Scope closeScope(List<Token> tokens, int closeIdx, int size, Scope current) {
        current.closeIdx = closeIdx;
        Scope parent = current.parent != null ? current.parent : current;
        if (isUsefulSubquery(tokens, current)) {
            TableRefParser.prescanAliases(tokens, current);
            List<ColumnInfo> cols = columnExtractor.extractSelectColumns(tokens, current);
            String name = resolveName(tokens, closeIdx, size, current);
            if (name != null) {
                parent.realTables.putIfAbsent(name, name);
                parent.virtualTables.putIfAbsent(name, cols);
            } else {
                parent.virtualTables.putIfAbsent("__anon_" + current.openIdx, cols);
            }
        }
        parent.lastConsumedIdx = current.lastConsumedIdx;
        return parent;
    }

    private boolean isUsefulSubquery(List<Token> tokens, Scope scope) {
        if (scope.pendingCteName != null) return true;
        int i = scope.openIdx - 1;
        while (i >= 0 && isHidden(tokens.get(i))) i--;
        if (i < 0) return false;
        int type = tokens.get(i).getType();
        return type == PostgreSQLParser.FROM || type == PostgreSQLParser.JOIN || type == PostgreSQLParser.COMMA;
    }

    private String resolveName(List<Token> tokens, int closeIdx, int size, Scope scope) {
        if (scope.pendingCteName != null) return scope.pendingCteName;
        int k = nextVisible(tokens, closeIdx + 1, size);
        if (k < size && tokens.get(k).getType() == PostgreSQLParser.AS)
            k = nextVisible(tokens, k + 1, size);
        if (k < size && tokens.get(k).getType() == PostgreSQLParser.ID) {
            scope.lastConsumedIdx = k;
            return tokens.get(k).getText();
        }
        return null;
    }
}