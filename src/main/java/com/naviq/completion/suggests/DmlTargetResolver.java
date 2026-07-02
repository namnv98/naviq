package com.naviq.completion.suggests;

import com.example.PostgreSQLParser;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.List;

/**
 * Xác định bảng đích của INSERT/UPDATE/ALTER khi vị trí cursor KHÔNG đứng sau dấu
 * chấm (nên không có "qualifier" theo nghĩa alias.column) - đây là bài toán khác
 * hẳn với SemanticScope: "câu này thuộc loại statement nào, và bảng đích của nó là
 * gì" thay vì "alias này trỏ tới bảng nào". Cũng dùng làm fallback token-scan khi
 * SemanticScope parse lỗi nặng (xem SemanticAnalyzer).
 */
public class DmlTargetResolver {

    public static String extractQualifier(CommonTokenStream tokenStream, int caretTokenIndex) {
        List<Token> tokens = tokenStream.getTokens();
        if (caretTokenIndex >= 2) {
            Token tok = tokens.get(caretTokenIndex - 1);
            if (tok.getType() == PostgreSQLParser.DOT) {
                Token prev = tokens.get(caretTokenIndex - 2);
                if (prev.getType() == PostgreSQLParser.ID) return prev.getText();
            }
        }
        String insertTable = extractInsertTable(tokens, caretTokenIndex);
        if (insertTable != null) return insertTable;
        String updateTable = extractUpdateTable(tokens, caretTokenIndex);
        if (updateTable != null) return updateTable;
        String alterTable = extractAlterTable(tokens, caretTokenIndex);
        if (alterTable != null) return alterTable;
        return null;
    }

    public static String extractAlterTable(List<Token> tokens, int caretTokenIndex) {
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            if (tokens.get(i).getChannel() != Token.DEFAULT_CHANNEL) continue;
            int type = tokens.get(i).getType();

            if (type == PostgreSQLParser.SELECT
                || type == PostgreSQLParser.INSERT
                || type == PostgreSQLParser.UPDATE
                || type == PostgreSQLParser.DELETE) return null;

            if (type == PostgreSQLParser.COLUMN) {
                int k = i - 1;
                while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
                if (k < 0) return null;

                int prevType = tokens.get(k).getType();
                if (prevType != PostgreSQLParser.ALTER
                    && prevType != PostgreSQLParser.DROP
                    && prevType != PostgreSQLParser.ADD) return null;

                return findAlterTableName(tokens, i);
            }

            if (type == PostgreSQLParser.ALTER) {
                int k = i + 1;
                while (k < caretTokenIndex
                    && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k++;
                if (k < caretTokenIndex
                    && tokens.get(k).getType() == PostgreSQLParser.TABLE) {
                    return null;
                }
                return findAlterTableName(tokens, i);
            }

            if (type == PostgreSQLParser.TABLE) {
                int k = i - 1;
                while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
                if (k >= 0 && tokens.get(k).getType() == PostgreSQLParser.ALTER) {
                    return null;
                }
            }
        }
        return null;
    }

    public static String findAlterTableName(List<Token> tokens, int fromIndex) {
        for (int i = fromIndex - 1; i >= 1; i--) {
            if (tokens.get(i).getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (tokens.get(i).getType() != PostgreSQLParser.TABLE) continue;

            int k = i - 1;
            while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
            if (k < 0 || tokens.get(k).getType() != PostgreSQLParser.ALTER) continue;

            int j = i + 1;
            while (j < fromIndex
                && tokens.get(j).getChannel() != Token.DEFAULT_CHANNEL) j++;
            if (j >= fromIndex || tokens.get(j).getType() != PostgreSQLParser.ID) return null;

            String part1 = tokens.get(j).getText();
            int j2 = j + 1;
            while (j2 < fromIndex
                && tokens.get(j2).getChannel() != Token.DEFAULT_CHANNEL) j2++;

            if (j2 < fromIndex && tokens.get(j2).getType() == PostgreSQLParser.DOT) {
                int j3 = j2 + 1;
                while (j3 < fromIndex
                    && tokens.get(j3).getChannel() != Token.DEFAULT_CHANNEL) j3++;
                if (j3 < fromIndex && tokens.get(j3).getType() == PostgreSQLParser.ID) {
                    return part1 + "." + tokens.get(j3).getText();
                }
            }
            return part1;
        }
        return null;
    }

    public static String extractUpdateTable(List<Token> tokens, int caretTokenIndex) {
        boolean foundSet = false;
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            int type = tokens.get(i).getType();
            if (tokens.get(i).getChannel() != Token.DEFAULT_CHANNEL) continue;

            if (type == PostgreSQLParser.SET) {
                foundSet = true;
                continue;
            }

            if (!foundSet && (type == PostgreSQLParser.SELECT
                || type == PostgreSQLParser.FROM
                || type == PostgreSQLParser.WHERE)) return null;

            if (type == PostgreSQLParser.UPDATE) {
                if (!foundSet) return null;

                int k = i + 1;
                while (k < caretTokenIndex
                    && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k++;
                if (k >= caretTokenIndex
                    || tokens.get(k).getType() != PostgreSQLParser.ID) return null;

                String part1 = tokens.get(k).getText();
                int k2 = k + 1;
                while (k2 < caretTokenIndex
                    && tokens.get(k2).getChannel() != Token.DEFAULT_CHANNEL) k2++;

                if (k2 < caretTokenIndex
                    && tokens.get(k2).getType() == PostgreSQLParser.DOT) {
                    int k3 = k2 + 1;
                    while (k3 < caretTokenIndex
                        && tokens.get(k3).getChannel() != Token.DEFAULT_CHANNEL) k3++;
                    if (k3 < caretTokenIndex
                        && tokens.get(k3).getType() == PostgreSQLParser.ID) {
                        return part1 + "." + tokens.get(k3).getText();
                    }
                }
                return part1;
            }
        }
        return null;
    }

    public static String extractInsertTable(List<Token> tokens, int caretTokenIndex) {
        int depth = 0;
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            int type = tokens.get(i).getType();
            if (type == Token.HIDDEN_CHANNEL) continue;

            if (type == PostgreSQLParser.RPAREN) {
                depth++;
                continue;
            }
            if (type == PostgreSQLParser.LPAREN) {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                int k = i - 1;
                while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
                if (k < 0) return null;

                if (tokens.get(k).getType() != PostgreSQLParser.ID) return null;
                String tablePart = tokens.get(k).getText();
                k--;
                while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;

                if (k >= 0 && tokens.get(k).getType() == PostgreSQLParser.DOT) {
                    k--;
                    while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
                    if (k >= 0 && tokens.get(k).getType() == PostgreSQLParser.ID) {
                        tablePart = tokens.get(k).getText() + "." + tablePart;
                        k--;
                        while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
                    }
                }

                if (k >= 0 && tokens.get(k).getType() == PostgreSQLParser.INTO) {
                    return tablePart;
                }
                return null;
            }

            if (type == PostgreSQLParser.SELECT || type == PostgreSQLParser.WHERE
                || type == PostgreSQLParser.FROM) return null;
        }
        return null;
    }
}
