package com.naviq.oracle.suggests;

import com.naviq.antlr4.postgresql.PostgreSQLParser;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.List;
import java.util.Map;

/**
 * Đặt tên alias tự động (kiểu "orders" -> "o") + tìm tên bảng đứng trước "AS" tại vị trí cursor.
 */
public class AliasNameSuggester {

    public static String suggestAlias(Map<String, String> aliasMap, String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return null;
        }

        var keySet = aliasMap.keySet();

        int dot = tableName.lastIndexOf('.');
        if (dot != -1) {
            tableName = tableName.substring(dot + 1);
        }

        String[] parts = tableName.split("_");

        StringBuilder base = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                base.append(Character.toLowerCase(p.charAt(0)));
            }
        }

        if (base.length() == 0 && !tableName.isEmpty()) {
            base.append(Character.toLowerCase(tableName.charAt(0)));
        }

        String alias = base.toString();

        if (!keySet.contains(alias)) {
            return alias;
        }

        int i = 1;
        while (keySet.contains(alias + i)) {
            i++;
        }

        return alias + i;
    }

    static int skipHidden(List<Token> tokens, int from, int size) {
        while (from < size && tokens.get(from).getChannel() != Token.DEFAULT_CHANNEL) {
            from++;
        }
        return from;
    }

    public static String extractTableBeforeAs(CommonTokenStream tokenStream, int caretTokenIndex) {
        List<Token> tokens = tokenStream.getTokens();

        int fromIdx = -1;
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }

            if (t.getType() == PostgreSQLParser.FROM) {
                fromIdx = i;
                break;
            }
        }

        if (fromIdx < 0) {
            return null;
        }

        int i = fromIdx + 1;
        while (i < caretTokenIndex) {
            Token t = tokens.get(i);

            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                i++;
                continue;
            }

            if (t.getType() == PostgreSQLParser.AS) {
                int next = skipHidden(tokens, i + 1, caretTokenIndex);
                if (next >= caretTokenIndex) {
                    return readTableNameBackward(tokens, i - 1);
                }
            }

            i++;
        }

        return null;
    }

    static String readTableNameBackward(List<Token> tokens, int idx) {
        StringBuilder sb = new StringBuilder();
        int i = idx;

        while (i >= 0) {
            Token t = tokens.get(i);

            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                i--;
                continue;
            }

            if (t.getType() == PostgreSQLParser.Identifier) {
                if (sb.length() > 0) {
                    sb.insert(0, ".");
                }
                sb.insert(0, t.getText());
            } else if (t.getType() == PostgreSQLParser.DOT) {
                // skip
            } else {
                break;
            }

            i--;
        }

        return sb.length() > 0 ? sb.toString() : null;
    }
}
