//package com.navi.cli.completion.sql;
//
//import com.navi.cli.datasource.postgres.SchemaLoader;
//import org.antlr.v4.runtime.CommonTokenStream;
//import org.antlr.v4.runtime.Token;
//import com.example.PostgreSQLFinalParser;
//import org.apache.commons.lang3.StringUtils;
//
//import java.util.*;
//
//public class SqlContextCollector1 {
//
//    // --- Token types ----------------------------------------------------------
//    private static final int OPEN_PAREN = PostgreSQLFinalParser.LPAREN;
//    private static final int CLOSE_PAREN = PostgreSQLFinalParser.RPAREN;
//    private static final int DOT = PostgreSQLFinalParser.DOT;
//    private static final int COMMA = PostgreSQLFinalParser.COMMA;
//    private static final int STAR = PostgreSQLFinalParser.STAR;
//    private static final int AS = PostgreSQLFinalParser.AS;
//    private static final int FROM = PostgreSQLFinalParser.FROM;
//    private static final int JOIN = PostgreSQLFinalParser.JOIN;
//    private static final int SELECT = PostgreSQLFinalParser.SELECT;
//    private static final int WITH = PostgreSQLFinalParser.WITH;
//    private static final int ID = PostgreSQLFinalParser.ID;
//
//    private static final Set<Integer> CLAUSE_KEYWORDS = Set.of(
//            FROM, JOIN, AS, WITH, SELECT,
//            PostgreSQLFinalParser.WHERE, PostgreSQLFinalParser.ON,
//            PostgreSQLFinalParser.GROUP, PostgreSQLFinalParser.ORDER,
//            PostgreSQLFinalParser.HAVING, PostgreSQLFinalParser.LIMIT,
//            PostgreSQLFinalParser.UNIQUE, PostgreSQLFinalParser.INNER,
//            PostgreSQLFinalParser.LEFT, PostgreSQLFinalParser.RIGHT,
//            PostgreSQLFinalParser.FULL, PostgreSQLFinalParser.CROSS,
//            PostgreSQLFinalParser.OUTER, PostgreSQLFinalParser.NATURAL
//    );
//
//    // --- Output records -------------------------------------------------------
//    public record ColumnInfo(String name, String fullName, String table, String type,
//                             String tableAlias, String alias) {
//        public ColumnInfo(String name) {
//            this(name, "", "", "", "", "");
//        }
//
//        public ColumnInfo(String name, String fullName, String table, String type) {
//            this(name, fullName, table, type, "", "");
//        }
//
//        public ColumnInfo withAlias(String alias) {
//            return new ColumnInfo(name, fullName, table, type, tableAlias, alias);
//        }
//
//        public ColumnInfo withTableAlias(String tableAlias) {
//            return new ColumnInfo(name, fullName, table, type, tableAlias, alias);
//        }
//
//        public String nameAlias() {
//            return StringUtils.isEmpty(alias) ? name : alias;
//        }
//    }
//
//    public record SqlContext(Map<String, String> aliasMap, Map<String, List<ColumnInfo>> cteColumns,
//                             List<String> tables, List<ColumnInfo> columns) {
//    }
//
//    // --- Scope ----------------------------------------------------------------
//    private static class Scope {
//        int openIdx, closeIdx;
//        int lastConsumedIdx;          // token cuối cùng đã đọc (dùng để nhảy chỉ số)
//        final Scope parent;
//        final List<Scope> children = new ArrayList<>();
//        final Map<String, String> realTables = new LinkedHashMap<>();     // alias -> schema.table
//        final Map<String, List<ColumnInfo>> virtualTables = new LinkedHashMap<>(); // alias -> columns
//        String pendingCteName;        // tên CTE nếu scope này là body CTE
//
//        Scope(int openIdx, int closeIdx, Scope parent) {
//            this.openIdx = openIdx;
//            this.closeIdx = closeIdx;
//            this.parent = parent;
//            this.lastConsumedIdx = openIdx;
//        }
//
//        boolean contains(int tokenIdx) {
//            return tokenIdx > openIdx && tokenIdx < closeIdx;
//        }
//    }
//
//    // --- Fields ---------------------------------------------------------------
//    private final Map<String, SchemaLoader.TableInfo> tableIndex;
//    private final Map<String, List<ColumnInfo>> columnCache = new HashMap<>();
//    private final Map<Integer, String> pendingCteNames = new HashMap<>(); // openParenIdx -> CTE name
//
//    public SqlContextCollector1(Map<String, SchemaLoader.TableInfo> tableIndex) {
//        this.tableIndex = tableIndex;
//    }
//
//    // --- Public API -----------------------------------------------------------
//    public SqlContext collect(CommonTokenStream tokenStream, int caretTokenIndex) {
//        List<Token> tokens = tokenStream.getTokens();
//        Scope root = buildScopeTree(tokens);
//        Scope innermost = findInnermostScope(root, caretTokenIndex);
//        return resolveContext(innermost);
//    }
//
//    // --- Phase 1: Build scope tree -------------------------------------------
//    private Scope buildScopeTree(List<Token> tokens) {
//        Scope root = new Scope(-1, tokens.size(), null);
//        Scope current = root;
//        for (int i = 0; i < tokens.size(); i++) {
//            Token t = tokens.get(i);
//            if (isHidden(t)) continue;
//
//            switch (t.getType()) {
//                case OPEN_PAREN:
//                    Scope child = new Scope(i, tokens.size(), current);
//                    child.pendingCteName = pendingCteNames.remove(i);
//                    current.children.add(child);
//                    current = child;
//                    break;
//                case CLOSE_PAREN: {
//                    int consumed = current.lastConsumedIdx;
//                    current = closeScope(tokens, i, tokens.size(), current);
//                    if (consumed > i) i = consumed;  // nhảy qua alias sau ')'
//                    break;
//                }
//                case WITH:
//                    i = registerCteNames(tokens, i, tokens.size());
//                    break;
//                case FROM:
//                case JOIN:
//                    i = parseTableRef(tokens, i + 1, tokens.size(), current) - 1;
//                    break;
//                case COMMA:
//                    if (isInsideFromList(tokens, i))
//                        i = parseTableRef(tokens, i + 1, tokens.size(), current) - 1;
//                    break;
//            }
//        }
//        return root;
//    }
//
//    // Đóng scope: xử lý subquery, trả về parent scope
//    private Scope closeScope(List<Token> tokens, int closeIdx, int size, Scope current) {
//        current.closeIdx = closeIdx;
//        Scope parent = current.parent != null ? current.parent : current;
//        if (isUsefulSubquery(tokens, current)) {
//            prescanAliases(tokens, current);                       // 1. gom alias trong FROM/JOIN
//            List<ColumnInfo> cols = extractSelectColumns(tokens, current); // 2. lấy danh sách cột
//            String name = resolveName(tokens, closeIdx, size, current);    // 3. đọc alias (nếu có)
//            if (name != null) {
//                parent.realTables.putIfAbsent(name, name);
//                parent.virtualTables.putIfAbsent(name, cols);
//            } else {
//                parent.virtualTables.putIfAbsent("__anon_" + current.openIdx, cols);
//            }
//        }
//        parent.lastConsumedIdx = current.lastConsumedIdx;          // đồng bộ chỉ số đã đọc
//        return parent;
//    }
//
//    // Đăng ký tên CTE trước khi gặp '('
//    private int registerCteNames(List<Token> tokens, int withIdx, int size) {
//        int i = nextVisible(tokens, withIdx + 1, size);
//        while (i < size && tokens.get(i).getType() == ID) {
//            String cteName = tokens.get(i).getText();
//            int k = nextVisible(tokens, i + 1, size);
//            if (k >= size || tokens.get(k).getType() != AS) break;
//            k = nextVisible(tokens, k + 1, size);
//            if (k >= size || tokens.get(k).getType() != OPEN_PAREN) break;
//            pendingCteNames.put(k, cteName);
//            int closeParen = matchingParen(tokens, k, size);
//            int next = nextVisible(tokens, closeParen + 1, size);
//            if (next >= size || tokens.get(next).getType() != COMMA) break;
//            i = nextVisible(tokens, next + 1, size);
//        }
//        return withIdx + 1;
//    }
//
//    // Kiểm tra '(' có phải mở đầu subquery trong FROM/JOIN không
//    private boolean isUsefulSubquery(List<Token> tokens, Scope scope) {
//        if (scope.pendingCteName != null) return true;
//        int i = scope.openIdx - 1;
//        while (i >= 0 && isHidden(tokens.get(i))) i--;
//        if (i < 0) return false;
//        int type = tokens.get(i).getType();
//        return type == FROM || type == JOIN || type == COMMA;
//    }
//
//    // Đọc alias [AS] <id> sau dấu ')', đồng thời cập nhật lastConsumedIdx
//    private String resolveName(List<Token> tokens, int closeIdx, int size, Scope scope) {
//        if (scope.pendingCteName != null) return scope.pendingCteName;
//        int k = nextVisible(tokens, closeIdx + 1, size);
//        if (k < size && tokens.get(k).getType() == AS)
//            k = nextVisible(tokens, k + 1, size);
//        if (k < size && tokens.get(k).getType() == ID) {
//            scope.lastConsumedIdx = k;
//            return tokens.get(k).getText();
//        }
//        return null;
//    }
//
//    // Duyệt trước FROM/JOIN trong scope để ghi nhận alias trước khi trích xuất SELECT list
//    private void prescanAliases(List<Token> tokens, Scope scope) {
//        int depth = 0;
//        for (int i = scope.openIdx + 1; i < scope.closeIdx; i++) {
//            Token t = tokens.get(i);
//            if (isHidden(t)) continue;
//            if (t.getType() == OPEN_PAREN) {
//                depth++;
//                continue;
//            }
//            if (t.getType() == CLOSE_PAREN) {
//                depth--;
//                continue;
//            }
//            if (depth > 0) continue;
//            if (t.getType() == FROM || t.getType() == JOIN) {
//                i = parseTableRef(tokens, i + 1, scope.closeIdx, scope) - 1;
//            } else if (t.getType() == COMMA && isInsideFromList(tokens, i)) {
//                i = parseTableRef(tokens, i + 1, scope.closeIdx, scope) - 1;
//            }
//        }
//    }
//
//    // Parse [schema.]table [[AS] alias] và ghi vào realTables. Trả về index tiếp theo.
//    private int parseTableRef(List<Token> tokens, int start, int limit, Scope scope) {
//        int i = nextVisible(tokens, start, limit);
//        if (i >= limit || tokens.get(i).getType() != ID) return i;
//        String tableName = tokens.get(i).getText();
//        int k = nextVisible(tokens, i + 1, limit);
//        if (k < limit && tokens.get(k).getType() == DOT) {
//            int afterDot = nextVisible(tokens, k + 1, limit);
//            if (afterDot < limit && tokens.get(afterDot).getType() == ID) {
//                tableName = tableName + "." + tokens.get(afterDot).getText();
//                i = afterDot;
//            }
//        }
//        k = nextVisible(tokens, i + 1, limit);
//        if (k < limit && tokens.get(k).getType() == AS)
//            k = nextVisible(tokens, k + 1, limit);
//        if (k < limit && tokens.get(k).getType() == ID && !CLAUSE_KEYWORDS.contains(tokens.get(k).getType())) {
//            scope.realTables.putIfAbsent(tokens.get(k).getText(), tableName);
//            return k + 1;
//        }
//        scope.realTables.putIfAbsent(tableName, tableName);
//        return i + 1;
//    }
//
//    // --- Phase 2: Tìm scope chứa caret ---------------------------------------
//    private Scope findInnermostScope(Scope node, int caretIdx) {
//        for (Scope child : node.children)
//            if (child.contains(caretIdx)) return findInnermostScope(child, caretIdx);
//        return node;
//    }
//
//    // --- Phase 3: Tổng hợp context -------------------------------------------
//    private SqlContext resolveContext(Scope innermost) {
//        Map<String, String> aliasMap = new LinkedHashMap<>();
//        Map<String, List<ColumnInfo>> cteColumns = new LinkedHashMap<>();
//        List<ColumnInfo> columns = new ArrayList<>();
//
//        for (Scope s = innermost; s != null; s = s.parent) {
//            aliasMap.putAll(s.realTables);
//            cteColumns.putAll(s.virtualTables);
//        }
//
//        cteColumns.forEach((alias, cols) -> {
//            if (alias.startsWith("__anon_"))
//                cols.forEach(c -> columns.add(c.withTableAlias(null)));
//            else
//                cols.forEach(c -> columns.add(c.withTableAlias(alias)));
//        });
//
//        aliasMap.forEach((alias, table) ->
//                getColumns(table).forEach(c -> columns.add(c.withTableAlias(alias)))
//        );
//
//        List<String> tables = new ArrayList<>(new LinkedHashSet<>(aliasMap.values()));
//        return new SqlContext(aliasMap, cteColumns, tables, columns);
//    }
//
//    // --- Column extraction ----------------------------------------------------
//    private List<ColumnInfo> extractSelectColumns(List<Token> tokens, Scope scope) {
//        int selectPos = findSelectAtDepthZero(tokens, scope);
//        if (selectPos < 0) return List.of();
//
//        List<ColumnInfo> cols = new ArrayList<>();
//        int depth = 0;
//        ColumnInfo pending = null;
//
//        for (int i = selectPos + 1; i < scope.closeIdx; i++) {
//            Token t = tokens.get(i);
//            if (isHidden(t)) continue;
//
//            int type = t.getType();
//            if (type == OPEN_PAREN) {
//                depth++;
//                if (depth == 1) {
//                    i = handleSubqueryInSelect(tokens, i, scope, cols);
//                    pending = null;
//                }
//            } else if (type == CLOSE_PAREN) {
//                depth--;
//            } else if (depth > 0) {
//                continue;
//            } else if (type == FROM) {
//                break;
//            } else if (type == AS) {
//                int j = nextVisible(tokens, i + 1, scope.closeIdx);
//                if (j < scope.closeIdx && tokens.get(j).getType() == ID && pending != null) {
//                    pending = pending.withAlias(tokens.get(j).getText());
//                    i = j;
//                }
//            } else if (type == STAR) {
//                expandStar(scope, cols);
//                pending = null;
//            } else if (type == COMMA) {
//                if (pending != null) cols.add(pending);
//                pending = null;
//            } else if (type == ID) {
//                pending = parseIdColumn(tokens, i, scope);
//            } else {
//                pending = null;
//            }
//        }
//        if (pending != null) cols.add(pending);
//        return cols;
//    }
//
//    private int handleSubqueryInSelect(List<Token> tokens, int openIdx, Scope parent, List<ColumnInfo> out) {
//        int closeIdx = matchingParen(tokens, openIdx, parent.closeIdx);
//        Scope inner = buildInnerScope(tokens, openIdx, closeIdx, parent);
//        List<ColumnInfo> innerCols = extractSelectColumns(tokens, inner);
//        int k = nextVisible(tokens, closeIdx + 1, parent.closeIdx);
//        if (k < parent.closeIdx && tokens.get(k).getType() == AS)
//            k = nextVisible(tokens, k + 1, parent.closeIdx);
//        if (k < parent.closeIdx && tokens.get(k).getType() == ID) {
//            String alias = tokens.get(k).getText();
//            if (!innerCols.isEmpty()) out.add(innerCols.get(0).withAlias(alias));
//            else out.add(new ColumnInfo(alias));
//            return k;
//        } else if (!innerCols.isEmpty()) {
//            out.add(innerCols.get(0));
//        }
//        return closeIdx;
//    }
//
//    private Scope buildInnerScope(List<Token> tokens, int openIdx, int closeIdx, Scope parent) {
//        Scope inner = new Scope(openIdx, closeIdx, parent);
//        prescanAliases(tokens, inner);
//        for (int i = openIdx + 1; i < closeIdx; i++) {
//            if (isHidden(tokens.get(i))) continue;
//            if (tokens.get(i).getType() == OPEN_PAREN && isUsefulSubquery(tokens, new Scope(i, closeIdx, inner))) {
//                int childClose = matchingParen(tokens, i, closeIdx);
//                Scope child = buildInnerScope(tokens, i, childClose, inner);
//                inner.children.add(child);
//                int aliasPos = nextVisible(tokens, childClose + 1, closeIdx);
//                if (aliasPos < closeIdx && tokens.get(aliasPos).getType() == AS)
//                    aliasPos = nextVisible(tokens, aliasPos + 1, closeIdx);
//                String name = null;
//                if (aliasPos < closeIdx && tokens.get(aliasPos).getType() == ID) {
//                    name = tokens.get(aliasPos).getText();
//                    i = aliasPos;
//                }
//                List<ColumnInfo> childCols = extractSelectColumns(tokens, child);
//                if (name != null) {
//                    inner.realTables.putIfAbsent(name, name);
//                    inner.virtualTables.putIfAbsent(name, childCols);
//                } else {
//                    inner.virtualTables.putIfAbsent("__anon_" + i, childCols);
//                }
//                i = childClose;
//            }
//        }
//        return inner;
//    }
//
//    private ColumnInfo parseIdColumn(List<Token> tokens, int pos, Scope scope) {
//        String col = tokens.get(pos).getText();
//        String tableAlias = null;
//        int k = nextVisible(tokens, pos + 1, scope.closeIdx);
//        if (k < scope.closeIdx && tokens.get(k).getType() == DOT) {
//            tableAlias = col;
//            int afterDot = nextVisible(tokens, k + 1, scope.closeIdx);
//            if (afterDot < scope.closeIdx && tokens.get(afterDot).getType() == ID) {
//                col = tokens.get(afterDot).getText();
//                return resolveColumn(scope, tableAlias, col);
//            } else if (afterDot < scope.closeIdx && tokens.get(afterDot).getType() == STAR) {
//                expandStarForAlias(scope, tableAlias, new ArrayList<>()); // side effect only
//                return null;
//            }
//        }
//        return resolveColumn(scope, tableAlias, col);
//    }
//
//    private void expandStar(Scope scope, List<ColumnInfo> out) {
//        scope.realTables.forEach((alias, table) ->
//                getColumns(table).forEach(c -> out.add(c.withTableAlias(alias)))
//        );
//        scope.virtualTables.forEach((alias, cols) -> {
//            if (!alias.startsWith("__anon_"))
//                cols.forEach(c -> out.add(c.withTableAlias(alias)));
//        });
//    }
//
//    private void expandStarForAlias(Scope scope, String alias, List<ColumnInfo> out) {
//        String table = scope.realTables.get(alias);
//        if (table != null) {
//            getColumns(table).forEach(c -> out.add(c.withTableAlias(alias)));
//            return;
//        }
//        List<ColumnInfo> vt = scope.virtualTables.get(alias);
//        if (vt != null) vt.forEach(c -> out.add(c.withTableAlias(alias)));
//    }
//
//    private ColumnInfo resolveColumn(Scope scope, String alias, String colName) {
//        if (alias != null) {
//            String table = scope.realTables.get(alias);
//            if (table != null) {
//                return getColumns(table).stream()
//                        .filter(c -> c.name().equalsIgnoreCase(colName))
//                        .findFirst()
//                        .orElse(new ColumnInfo(colName))
//                        .withTableAlias(alias);
//            }
//            List<ColumnInfo> vt = scope.virtualTables.get(alias);
//            if (vt != null) {
//                return vt.stream()
//                        .filter(c -> c.name().equalsIgnoreCase(colName))
//                        .findFirst()
//                        .orElse(new ColumnInfo(colName))
//                        .withTableAlias(alias);
//            }
//        }
//        for (String table : scope.realTables.values()) {
//            Optional<ColumnInfo> found = getColumns(table).stream()
//                    .filter(c -> c.name().equalsIgnoreCase(colName)).findFirst();
//            if (found.isPresent()) return found.get();
//        }
//        for (List<ColumnInfo> vt : scope.virtualTables.values()) {
//            Optional<ColumnInfo> found = vt.stream()
//                    .filter(c -> c.name().equalsIgnoreCase(colName)).findFirst();
//            if (found.isPresent()) return found.get();
//        }
//        return new ColumnInfo(colName);
//    }
//
//    private int findSelectAtDepthZero(List<Token> tokens, Scope scope) {
//        int depth = 0;
//        for (int i = scope.openIdx + 1; i < scope.closeIdx; i++) {
//            int type = tokens.get(i).getType();
//            if (type == OPEN_PAREN) depth++;
//            else if (type == CLOSE_PAREN) depth--;
//            else if (depth == 0 && type == SELECT) return i;
//        }
//        return -1;
//    }
//
//    // --- DB helper -----------------------------------------------------------
//    private List<ColumnInfo> getColumns(String tableName) {
//        return columnCache.computeIfAbsent(tableName, k -> {
//            var table = tableIndex.get(k);
//            if (table == null) return List.of();
//            return table.columns().stream()
//                    .map(c -> new ColumnInfo(c.name(), c.fullName(), tableName, c.dataType()))
//                    .toList();
//        });
//    }
//
//    // --- Utilities -----------------------------------------------------------
//    private int nextVisible(List<Token> tokens, int from, int limit) {
//        while (from < limit && isHidden(tokens.get(from))) from++;
//        return from;
//    }
//
//    private boolean isHidden(Token t) {
//        return t.getChannel() != Token.DEFAULT_CHANNEL;
//    }
//
//    private int matchingParen(List<Token> tokens, int openIdx, int limit) {
//        int depth = 1, i = openIdx + 1;
//        while (i < limit && depth > 0) {
//            int type = tokens.get(i++).getType();
//            if (type == OPEN_PAREN) depth++;
//            else if (type == CLOSE_PAREN) depth--;
//        }
//        return i - 1;
//    }
//
//    private boolean isInsideFromList(List<Token> tokens, int commaIdx) {
//        for (int i = commaIdx - 1; i >= Math.max(0, commaIdx - 50); i--) {
//            if (isHidden(tokens.get(i))) continue;
//            int type = tokens.get(i).getType();
//            if (type == FROM) return true;
//            if (type == SELECT || type == JOIN) return false;
//        }
//        return false;
//    }
//}