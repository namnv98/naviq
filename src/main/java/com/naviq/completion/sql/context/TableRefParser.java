package com.naviq.completion.sql.context;


import com.naviq.completion.model.Scope;
import org.antlr.v4.runtime.Token;

import java.util.List;
import java.util.Set;

import com.example.PostgreSQLParser;

import static com.naviq.completion.model.SqlTokenUtil.*;

public class TableRefParser {
    private static final Set<Integer> CLAUSE_KEYWORDS = Set.of(
            PostgreSQLParser.FROM, PostgreSQLParser.JOIN, PostgreSQLParser.AS,
            PostgreSQLParser.WITH, PostgreSQLParser.SELECT, PostgreSQLParser.WHERE,
            PostgreSQLParser.ON, PostgreSQLParser.GROUP, PostgreSQLParser.ORDER,
            PostgreSQLParser.HAVING, PostgreSQLParser.LIMIT, PostgreSQLParser.UNIQUE,
            PostgreSQLParser.INNER, PostgreSQLParser.LEFT, PostgreSQLParser.RIGHT,
            PostgreSQLParser.FULL, PostgreSQLParser.CROSS, PostgreSQLParser.OUTER,
            PostgreSQLParser.NATURAL
    );

    // Parse [schema.]table [[AS] alias] và trả về index token tiếp theo
    public static int parseTableRef(List<Token> tokens, int start, int limit, Scope scope) {
        int i = nextVisible(tokens, start, limit);
        if (i >= limit || tokens.get(i).getType() != PostgreSQLParser.ID) return i;
        String tableName = tokens.get(i).getText();
        int k = nextVisible(tokens, i + 1, limit);
        if (k < limit && tokens.get(k).getType() == PostgreSQLParser.DOT) {
            int afterDot = nextVisible(tokens, k + 1, limit);
            if (afterDot < limit && tokens.get(afterDot).getType() == PostgreSQLParser.ID) {
                tableName = tableName + "." + tokens.get(afterDot).getText();
                i = afterDot;
            }
        }
        k = nextVisible(tokens, i + 1, limit);
        if (k < limit && tokens.get(k).getType() == PostgreSQLParser.AS)
            k = nextVisible(tokens, k + 1, limit);
        if (k < limit && tokens.get(k).getType() == PostgreSQLParser.ID && !CLAUSE_KEYWORDS.contains(tokens.get(k).getType())) {
            scope.realTables.putIfAbsent(tokens.get(k).getText(), tableName);
            return k + 1;
        }
        scope.realTables.putIfAbsent(tableName, tableName);
        return i + 1;
    }

    // Duyệt trước FROM/JOIN để ghi nhận alias
    public static void prescanAliases(List<Token> tokens, Scope scope) {
        int depth = 0;
        for (int i = scope.openIdx + 1; i < scope.closeIdx; i++) {
            Token t = tokens.get(i);
            if (isHidden(t)) continue;
            if (t.getType() == PostgreSQLParser.LPAREN) {
                depth++;
                continue;
            }
            if (t.getType() == PostgreSQLParser.RPAREN) {
                depth--;
                continue;
            }
            if (depth > 0) continue;
            if (t.getType() == PostgreSQLParser.FROM || t.getType() == PostgreSQLParser.JOIN) {
                i = parseTableRef(tokens, i + 1, scope.closeIdx, scope) - 1;
            } else if (t.getType() == PostgreSQLParser.COMMA && isInsideFromList(tokens, i)) {
                i = parseTableRef(tokens, i + 1, scope.closeIdx, scope) - 1;
            }
        }
    }
}