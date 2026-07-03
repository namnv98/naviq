package com.naviq.datasource;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import com.naviq.antlr4.*;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

public class CollectTableInfo {
    public CollectTableInfo(Map<String, SchemaLoader.TableInfo> tableIndex) {
        TABLE_INDEX = tableIndex;
    }

    static class SQLContextVisitor {
        public final Map<String, String> tableAliasMap = new HashMap<>();
        public final Map<String, List<String>> cteColumns = new HashMap<>();
        public final List<String> fromTables = new ArrayList<>();
    }


    public final Map<String, SchemaLoader.TableInfo> TABLE_INDEX;

    // ── Entry point thay thế enrichAliasMapFromTokens ────────────────────
    public void enrichAliasMapFromTokens(
            CommonTokenStream tokenStream,
            SQLContextVisitor visitor,
            int caretTokenIndex) {

        List<Token> tokens = tokenStream.getTokens();
        ScopeNode root = buildScopeTree(tokens);
        ScopeNode innermostScope = findInnermostScope(root, caretTokenIndex);
        resolveVisibleAliases(innermostScope, visitor);
    }

    // Trả List<String> để không đụng code cũ đang dùng
    public List<SchemaLoader.DBColumnInfo> getColumnsOfTable(String tableName) {
        SchemaLoader.TableInfo t = TABLE_INDEX.get(tableName);
        if (t == null) return List.of();
        return t.columns().stream()
                .toList();
    }


    // ── Scope node ───────────────────────────────────────────────────────
    public class ScopeNode {
        int open, close;                          // vị trí token LPAREN / RPAREN
        Map<String, String> aliases = new LinkedHashMap<>(); // alias → realTable
        Map<String, List<String>> cteColumns = new LinkedHashMap<>();
        List<ScopeNode> children = new ArrayList<>();
        ScopeNode parent;

        ScopeNode(int open, ScopeNode parent) {
            this.open = open;
            this.parent = parent;
        }

        boolean contains(int idx) {
            return idx > open && idx < close;
        }
    }

    // ── Giai đoạn 1: xây cây scope ───────────────────────────────────────
    public ScopeNode buildScopeTree(List<Token> tokens) {
        int size = tokens.size();
        ScopeNode root = new ScopeNode(-1, null);
        root.close = size;
        ScopeNode current = root;

        for (int i = 0; i < size - 1; i++) {
            int type = tokens.get(i).getType();

            // ── mở scope mới ──
            if (type == PostgreSQLParser.LPAREN) {
                ScopeNode child = new ScopeNode(i, current);
                current.children.add(child);
                child.close = size;
                current = child;
                continue;
            }

            // Detect CTE: WITH id AS (
            if (type == PostgreSQLParser.WITH) {
                int k = skipHidden(tokens, i + 1, size);
                while (k < size) {
                    // đọc CTE name
                    if (tokens.get(k).getType() != PostgreSQLParser.ID) break;
                    String cteName = tokens.get(k).getText();

                    int k2 = skipHidden(tokens, k + 1, size);
                    if (k2 >= size || tokens.get(k2).getType() != PostgreSQLParser.AS) break;

                    int k3 = skipHidden(tokens, k2 + 1, size);
                    if (k3 >= size || tokens.get(k3).getType() != PostgreSQLParser.LPAREN) break;

                    // Tìm RPAREN đóng của CTE body
                    int depth2 = 1;
                    int k4 = k3 + 1;
                    while (k4 < size && depth2 > 0) {
                        if (tokens.get(k4).getType() == PostgreSQLParser.LPAREN) depth2++;
                        if (tokens.get(k4).getType() == PostgreSQLParser.RPAREN) depth2--;
                        k4++;
                    }
                    int cteClose = k4 - 1; // vị trí RPAREN

                    // Tạo scope tạm để extract columns
                    ScopeNode cteScope = new ScopeNode(k3, current);
                    cteScope.close = cteClose;

                    List<String> cols = extractSubqueryColumns(cteScope, tokens, k3, cteClose);

                    // Đăng ký CTE vào scope HIỆN TẠI (root)
                    current.aliases.put(cteName, cteName);
                    current.cteColumns.put(cteName, cols);

                    // Tiếp tục xem có CTE tiếp theo không (WITH a AS (...), b AS (...))
                    int k5 = skipHidden(tokens, cteClose + 1, size);
                    if (k5 >= size || tokens.get(k5).getType() != PostgreSQLParser.COMMA) break;
                    k = skipHidden(tokens, k5 + 1, size);
                }
                continue;
            }

            // ── đóng scope ──
            if (type == PostgreSQLParser.RPAREN) {
                current.close = i;

                // subquery alias:  (SELECT …) [AS] alias
                int k = skipHidden(tokens, i + 1, size);
                if (k < size && tokens.get(k).getType() == PostgreSQLParser.AS)
                    k = skipHidden(tokens, k + 1, size);
                if (k < size && tokens.get(k).getType() == PostgreSQLParser.ID) {
                    String alias = tokens.get(k).getText();
                    List<String> cols = extractSubqueryColumns(current, tokens, current.open, current.close);
                    // alias đăng ký ở scope CHA (nơi subquery được dùng)
                    if (current.parent != null) {
                        current.parent.aliases.put(alias, alias);
                        current.parent.cteColumns.put(alias, cols);
                    }
                    i = k; // skip qua alias token
                }

                current = current.parent != null ? current.parent : root;
                continue;
            }

            // ── chỉ quan tâm FROM / JOIN / COMMA ──
            if (type != PostgreSQLParser.FROM && type != PostgreSQLParser.JOIN && type != PostgreSQLParser.COMMA) {
                continue;
            }

            // table name
            int j = skipHidden(tokens, i + 1, size);
            if (j >= size) {
                continue;
            }
            if (tokens.get(j).getType() == PostgreSQLParser.LPAREN) {
                continue; // subquery, bỏ qua
            }
            if (tokens.get(j).getType() != PostgreSQLParser.ID) {
                continue;
            }

            String[] qn = readQualifiedName(tokens, j, size);
            if (isNull(qn)) {
                continue;
            }
            String tableName = qn[0];
            int lastTokenTableName = Integer.parseInt(qn[1]);

            current.aliases.put(tableName, tableName);   // table tự alias chính nó

            // alias tuỳ chọn
            int k = skipHidden(tokens, lastTokenTableName + 1, size);
            if (k < size && tokens.get(k).getType() == PostgreSQLParser.AS) {
                k = skipHidden(tokens, k + 1, size);
            }
            if (k < size && tokens.get(k).getType() == PostgreSQLParser.ID) {
                current.aliases.put(tokens.get(k).getText(), tableName);
                i = k;
            } else {
                i = lastTokenTableName;
            }
        }
        return root;
    }

    public String[] readQualifiedName(List<Token> tokens, int start, int size) {
        int j = skipHidden(tokens, start, size);
        if (j >= size || tokens.get(j).getType() != PostgreSQLParser.ID) {
            return null;
        }

        String part1 = tokens.get(j).getText();
        int afterPart1 = skipHidden(tokens, j + 1, size);

        if (afterPart1 < size && tokens.get(afterPart1).getType() == PostgreSQLParser.DOT) {
            int afterDot = skipHidden(tokens, afterPart1 + 1, size);
            if (afterDot < size && tokens.get(afterDot).getType() == PostgreSQLParser.ID) {
                // schema.table
                String full = part1 + "." + tokens.get(afterDot).getText();
                return new String[]{full, String.valueOf(afterDot)};
            }
        }

        return new String[]{part1, String.valueOf(j)};
    }

    record ColumnName(List<String> names, int tokenIndex) {
    }


    public ColumnName readColumeName(ScopeNode current, List<Token> tokens, int start, int size) {
        int i = skipHidden(tokens, start, size);
        if (i >= size || tokens.get(i).getType() != PostgreSQLParser.ID) {
            return null;
        }

        String first = tokens.get(i).getText();
        int i1 = skipHidden(tokens, i + 1, size);

        // ── case 1: single column ──
        if (i1 >= size || tokens.get(i1).getType() != PostgreSQLParser.DOT) {
            return new ColumnName(List.of(first), i);
        }

        // ── case 2: a.b or a.b.c ──
        int i2 = skipHidden(tokens, i1 + 1, size);

        if (i2 >= size || tokens.get(i2).getType() == PostgreSQLParser.STAR) {
            var columnsOfTable = getColumnsOfTable(current.aliases.get(first)).stream().map(SchemaLoader.DBColumnInfo::name).collect(Collectors.toList());
            return new ColumnName(columnsOfTable, i);
        }

        if (i2 >= size || tokens.get(i2).getType() != PostgreSQLParser.ID) {
            return new ColumnName(List.of(first), i);
        }

        String second = tokens.get(i2).getText();

        int i3 = skipHidden(tokens, i2 + 1, size);

        // ── case 3: schema.table.column ──
        if (i3 < size && tokens.get(i3).getType() == PostgreSQLParser.DOT) {
            int i4 = skipHidden(tokens, i3 + 1, size);
            if (i4 < size && tokens.get(i4).getType() == PostgreSQLParser.ID) {
                String third = tokens.get(i4).getText();

                // schema.table.column
                String full = first + "." + second + "." + third;
                return new ColumnName(List.of(full), i4);
            }
        }

        // table.column
        String full = first + "." + second;
        return new ColumnName(List.of(full), i2);
    }

    // ── Giai đoạn 2: tìm node chứa cursor, leo lên gom alias ────────────
    public ScopeNode findInnermostScope(ScopeNode node, int caretIdx) {
        for (ScopeNode child : node.children) {
            if (child.contains(caretIdx)) {
                return findInnermostScope(child, caretIdx);
            }
        }
        return node; // không có con nào chứa → chính node này
    }

    public void resolveVisibleAliases(ScopeNode innermostScope, SQLContextVisitor visitor) {
        // Đi từ innermost lên root, outer scope không ghi đè inner
        ScopeNode cur = innermostScope;
        while (cur != null) {
            cur.aliases.forEach(visitor.tableAliasMap::putIfAbsent);
            cur.cteColumns.forEach(visitor.cteColumns::putIfAbsent);
            cur = cur.parent;
        }
        new HashSet<>(visitor.tableAliasMap.values())
                .forEach(t -> {
                    if (!visitor.fromTables.contains(t)) visitor.fromTables.add(t);
                });
    }

    // ----------------------------------------------------------------
    public int skipHidden(List<Token> tokens, int from, int size) {
        while (from < size && tokens.get(from).getChannel() != Token.DEFAULT_CHANNEL)
            from++;
        return from;
    }

    public List<String> extractSubqueryColumns(ScopeNode current, List<Token> tokens, int openParen, int closeParen) {
        List<String> cols = new ArrayList<>();

        // Tìm SELECT đầu tiên, bỏ qua nested SELECT bên trong paren
        int i = openParen + 1;
        int depth = 0;
        while (i < closeParen) {
            int type = tokens.get(i).getType();
            if (type == PostgreSQLParser.LPAREN) {
                depth++;
                i++;
                continue;
            }
            if (type == PostgreSQLParser.RPAREN) {
                depth--;
                i++;
                continue;
            }
            if (depth == 0 && type == PostgreSQLParser.SELECT) break;
            i++;
        }
        if (i >= closeParen) return cols; // không tìm thấy SELECT

        i = skipHidden(tokens, i + 1, closeParen);

        // Scan select-list: chỉ ở depth 0, dừng khi gặp FROM
        String lastId = null;
        while (i < closeParen) {
            int type = tokens.get(i).getType();

            if (type == PostgreSQLParser.LPAREN) {
                depth++;
                lastId = null;
                i++;
                continue;
            }
            if (type == PostgreSQLParser.RPAREN) {
                depth--;
                i++;
                continue;
            }
            if (depth > 0 || tokens.get(i).getChannel() != Token.DEFAULT_CHANNEL) {
                i++;
                continue;
            }
            if (type == PostgreSQLParser.FROM) break;

            if (type == PostgreSQLParser.AS) {
                // AS luôn đặt tên column → flush ngay, bỏ qua lastId trước đó
                int j = skipHidden(tokens, i + 1, closeParen);
                if (j < closeParen && tokens.get(j).getType() == PostgreSQLParser.ID) {
                    cols.add(tokens.get(j).getText());
                    cols.remove(lastId);
                    lastId = null;
                    i = j + 1;
                    continue;
                }
            }

            if (type == PostgreSQLParser.STAR) {
                current.aliases.forEach((s, s2) -> {
                    var columnsOfTable = getColumnsOfTable(current.aliases.get(s));
                    columnsOfTable.forEach(columnInfo -> {
                        cols.add(columnInfo.name());
                    });
                });
                i++;
                continue;
            }

            if (type == PostgreSQLParser.COMMA) {
                if (lastId != null) {
                    cols.add(lastId); // flush col không có alias
                }
                lastId = null;
            } else if (type == PostgreSQLParser.ID) {
                var columnName = readColumeName(current, tokens, i, tokens.size());
                if (columnName.names.size() == 1) {
                    //trường hợp chỉ có 1 name (không phải *) thì set lastId để xử lý type == PostgreSQLParser.AS ghi đè alias
                    lastId = columnName.names.get(0);
                }

                columnName.names.forEach(s -> {
                    cols.add(s);
                });
                i = columnName.tokenIndex + 1;
                continue;
            } else if (type != PostgreSQLParser.DOT) {
                lastId = null; // expression như COUNT(), +, - → reset
            }
            i++;
        }

        if (lastId != null) {
            cols.add(lastId); // col cuối không có alias
        }
        return cols;
    }
}
