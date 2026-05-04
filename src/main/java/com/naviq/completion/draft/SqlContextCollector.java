package com.naviq.completion.draft;

import com.naviq.datasource.SchemaLoader;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import com.example.PostgreSQLParser;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * Thu thập bảng/alias/CTE visible tại vị trí cursor.
 * <p>
 * Không dùng parse tree vì SQL đang gõ dở thường bị lỗi cú pháp.
 * Thay vào đó đọc thẳng token stream và xây scope tree theo dấu ngoặc.
 * <p>
 * Mọi "named query" (CTE hay subquery) đều đi qua một path duy nhất:
 * OPEN_PAREN  → openScope()
 * CLOSE_PAREN → closeScope()  (prescan aliases → extract columns → đặt tên)
 * <p>
 * CTE chỉ khác subquery ở chỗ tên được biết TRƯỚC '(' thay vì SAU ')'.
 * registerCteNames() ghi tên vào pendingCteNames[openParenIdx] để
 * openScope() nhặt lên khi vòng for tới đúng vị trí đó.
 */
public class SqlContextCollector {

    // ── Token type aliases ────────────────────────────────────────────────────

    private static final int OPEN_PAREN = PostgreSQLParser.LPAREN;
    private static final int CLOSE_PAREN = PostgreSQLParser.RPAREN;
    private static final int DOT = PostgreSQLParser.DOT;
    private static final int COMMA = PostgreSQLParser.COMMA;
    private static final int STAR = PostgreSQLParser.STAR;
    private static final int AS = PostgreSQLParser.AS;
    private static final int FROM = PostgreSQLParser.FROM;
    private static final int JOIN = PostgreSQLParser.JOIN;
    private static final int SELECT = PostgreSQLParser.SELECT;
    private static final int WITH = PostgreSQLParser.WITH;
    private static final int ID = PostgreSQLParser.ID;

    private static final Set<Integer> CLAUSE_KEYWORDS = Set.of(
            FROM, JOIN, AS, WITH, SELECT,
            PostgreSQLParser.WHERE, PostgreSQLParser.ON,
            PostgreSQLParser.GROUP, PostgreSQLParser.ORDER,
            PostgreSQLParser.HAVING, PostgreSQLParser.LIMIT,
            PostgreSQLParser.UNIQUE, PostgreSQLParser.INNER,
            PostgreSQLParser.LEFT, PostgreSQLParser.RIGHT,
            PostgreSQLParser.FULL, PostgreSQLParser.CROSS,
            PostgreSQLParser.OUTER, PostgreSQLParser.NATURAL
    );

    // ── Public output ─────────────────────────────────────────────────────────

    public record SqlContext(
            Map<String, String> aliasMap,
            Map<String, List<ColumnInfo>> cteColumns,
            List<String> tables,
            List<ColumnInfo> columns
    ) {
    }

    public record ColumnInfo(
            String name,
            String fullName,
            String table,
            String type,
            String tableAlias,
            String alias
    ) {
        public ColumnInfo(String name) {
            this(name, "", "", "", "", "");
        }

        public ColumnInfo(String name, String fullName, String table, String type) {
            this(name, fullName, table, type, "", "");
        }

        public ColumnInfo withAlias(String alias) {
            return new ColumnInfo(name, fullName, table, type, tableAlias, alias);
        }

        public ColumnInfo withTableAlias(String tableAlias) {
            return new ColumnInfo(name, fullName, table, type, tableAlias, alias);
        }

        public String nameAlias() {
            if (StringUtils.isEmpty(alias)) {
                return name;
            }
            return alias;
        }
    }

    // ── Scope ─────────────────────────────────────────────────────────────────

    private static class Scope {
        int openIdx;
        int closeIdx;
        int lastConsumedIdx; // index token cuối đã đọc (dùng để sync vòng for)

        final Scope parent;
        final List<Scope> children = new ArrayList<>();

        // Real tables: alias → "schema.table"
        final Map<String, String> realTables = new LinkedHashMap<>();
        // Virtual tables (CTE / subquery): alias → columns
        final Map<String, List<ColumnInfo>> virtualTables = new LinkedHashMap<>();

        // Tên CTE được gán trước khi scope này mở (null nếu là subquery thường)
        String pendingName;

        Scope(int openIdx, int closeIdx, Scope parent) {
            this.openIdx = openIdx;
            this.closeIdx = closeIdx;
            this.parent = parent;
            this.lastConsumedIdx = openIdx;
        }

        boolean contains(int tokenIdx) {
            return tokenIdx > openIdx && tokenIdx < closeIdx;
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final Map<String, SchemaLoader.TableInfo> tableIndex;
    private final Map<String, List<SchemaLoader.DBColumnInfo>> columnCache = new HashMap<>();

    /**
     * openParenIdx → tên CTE sẽ được gán cho scope mở tại vị trí đó
     */
    private final Map<Integer, String> pendingCteNames = new HashMap<>();

    public SqlContextCollector(Map<String, SchemaLoader.TableInfo> tableIndex) {
        this.tableIndex = tableIndex;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public SqlContext collect(CommonTokenStream tokenStream, int caretTokenIndex) {
        List<Token> tokens = tokenStream.getTokens();
        Scope root = buildScopeTree(tokens);
        Scope innermost = findInnermostScope(root, caretTokenIndex);
        return resolveContext(innermost);
    }

    // ── Phase 1: Build scope tree ─────────────────────────────────────────────

    private Scope buildScopeTree(List<Token> tokens) {
        int size = tokens.size();
        Scope root = new Scope(-1, size, null);
        Scope cur = root;

        for (int i = 0; i < size; i++) {
            Token token = tokens.get(i);
            if (isHidden(token)) continue;

            switch (token.getType()) {

                case OPEN_PAREN -> cur = openScope(tokens, i, cur);

                case CLOSE_PAREN -> {
                    int consumed = cur.lastConsumedIdx;
                    cur = closeScope(tokens, i, size, cur);
                    if (consumed > i) i = consumed; // bước qua [AS] alias sau ')' nếu có
                }

                case WITH -> i = registerCteNames(tokens, i, size, cur);

                case FROM,
                     JOIN -> i = parseTableRef(tokens, i + 1, size, cur) - 1;

                case COMMA -> {
                    if (isInsideFromList(tokens, i))
                        i = parseTableRef(tokens, i + 1, size, cur) - 1;
                }
            }
        }
        return root;
    }

    /**
     * Tạo scope con khi gặp '('.
     * Nếu openIdx khớp với một entry trong pendingCteNames → đây là body CTE.
     */
    private Scope openScope(List<Token> tokens, int openIdx, Scope parent) {
        Scope child = new Scope(openIdx, tokens.size(), parent);
        child.pendingName = pendingCteNames.remove(openIdx);
        parent.children.add(child);
        return child;
    }

    /**
     * Đóng scope khi gặp ')'.
     * Flow giống nhau cho cả CTE lẫn subquery:
     * 1. prescanAliases  – đảm bảo aliases đã đầy đủ trước khi extract columns
     * 2. extractColumns  – lấy SELECT list
     * 3. resolveName     – CTE dùng pendingName, subquery đọc [AS] alias sau ')'
     * 4. đăng ký vào parent scope
     * <p>
     * Trả về parent scope (đã cập nhật lastConsumedIdx).
     */
    private Scope closeScope(List<Token> tokens, int closeIdx, int size, Scope current) {
        current.closeIdx = closeIdx;

        Scope parent = current.parent != null ? current.parent : current;

        // Chỉ xử lý subquery nằm trong FROM/JOIN, bỏ qua subquery trong SELECT/WHERE/...
        if (isFromSubquery(tokens, current)) {
            prescanAliases(tokens, current);
            List<ColumnInfo> cols = extractSelectColumns(tokens, current);
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

    /**
     * Kiểm tra '(' này có phải mở đầu subquery trong FROM/JOIN không.
     * CTE luôn là FROM subquery (có pendingName).
     * Subquery thường: token ngay trước '(' phải là FROM, JOIN, hoặc COMMA.
     */
    private boolean isFromSubquery(List<Token> tokens, Scope scope) {
        if (scope.pendingName != null) return true;

        int i = scope.openIdx - 1;
        while (i >= 0 && isHidden(tokens.get(i))) i--;
        if (i < 0) return false;

        int type = tokens.get(i).getType();
        return type == FROM || type == JOIN || type == COMMA;
    }

    /**
     * Lấy tên cho scope vừa đóng:
     * - CTE: đã có sẵn trong pendingName
     * - Subquery: đọc [AS] <alias> ngay sau ')'
     */
    private String resolveName(List<Token> tokens, int closeIdx, int size, Scope scope) {
        if (scope.pendingName != null) return scope.pendingName;

        int k = nextVisible(tokens, closeIdx + 1, size);
        if (k < size && tokens.get(k).getType() == AS)
            k = nextVisible(tokens, k + 1, size);

        if (k < size && tokens.get(k).getType() == ID) {
            scope.lastConsumedIdx = k;
            return tokens.get(k).getText();
        }
        return null;
    }

    /**
     * Chỉ ghi tên CTE → pendingCteNames[openParenIdx].
     * Nội dung '(...)' để vòng for chính tự xử lý qua openScope/closeScope.
     */
    private int registerCteNames(List<Token> tokens, int withIdx, int size, Scope scope) {
        int i = nextVisible(tokens, withIdx + 1, size);

        while (i < size && tokens.get(i).getType() == ID) {
            String cteName = tokens.get(i).getText();

            int k = nextVisible(tokens, i + 1, size);
            if (k >= size || tokens.get(k).getType() != AS) break;

            k = nextVisible(tokens, k + 1, size);
            if (k >= size || tokens.get(k).getType() != OPEN_PAREN) break;

            pendingCteNames.put(k, cteName);

            // Tìm dấu ',' tiếp theo ngoài ngoặc để đọc CTE kế
            int closeIdx = matchingParen(tokens, k, size);
            int next = nextVisible(tokens, closeIdx + 1, size);
            if (next >= size || tokens.get(next).getType() != COMMA) break;
            i = nextVisible(tokens, next + 1, size);
        }

        return withIdx + 1; // để vòng for tự đi tiếp, tự gặp OPEN_PAREN
    }

    /**
     * Đọc trước các FROM/JOIN trong scope để aliases đầy đủ trước khi
     * extractSelectColumns chạy (SELECT đứng trước FROM trong token stream).
     */
    private void prescanAliases(List<Token> tokens, Scope scope) {
        int depth = 0;
        for (int i = scope.openIdx + 1; i < scope.closeIdx; i++) {
            Token token = tokens.get(i);
            if (isHidden(token)) continue;
            int type = token.getType();

            if (type == OPEN_PAREN) {
                depth++;
                continue;
            }
            if (type == CLOSE_PAREN) {
                depth--;
                continue;
            }
            if (depth > 0) continue;

            if (type == FROM || type == JOIN) {
                i = parseTableRef(tokens, i + 1, scope.closeIdx, scope) - 1;
            } else if (type == COMMA && isInsideFromList(tokens, i)) {
                i = parseTableRef(tokens, i + 1, scope.closeIdx, scope) - 1;
            }
        }
    }

    /**
     * Parse [schema.]table [[AS] alias] sau FROM/JOIN/COMMA.
     * Trả về index token tiếp theo (caller dùng result - 1 vì for loop sẽ i++).
     */
    private int parseTableRef(List<Token> tokens, int start, int limit, Scope scope) {
        int i = nextVisible(tokens, start, limit);
        if (i >= limit || tokens.get(i).getType() != ID) return i;

        // Đọc [schema.]table
        String tableName = tokens.get(i).getText();
        int k = nextVisible(tokens, i + 1, limit);
        if (k < limit && tokens.get(k).getType() == DOT) {
            int afterDot = nextVisible(tokens, k + 1, limit);
            if (afterDot < limit && tokens.get(afterDot).getType() == ID) {
                tableName = tableName + "." + tokens.get(afterDot).getText();
                i = afterDot;
            }
        }

        // Đọc [AS] alias
        k = nextVisible(tokens, i + 1, limit);
        if (k < limit && tokens.get(k).getType() == AS)
            k = nextVisible(tokens, k + 1, limit);

        if (k < limit && tokens.get(k).getType() == ID
                && !CLAUSE_KEYWORDS.contains(tokens.get(k).getType())) {
            scope.realTables.putIfAbsent(tokens.get(k).getText(), tableName);
            return k + 1;
        }

        scope.realTables.putIfAbsent(tableName, tableName);
        return i + 1;
    }

    // ── Phase 2: Find innermost scope ─────────────────────────────────────────

    private Scope findInnermostScope(Scope node, int caretIdx) {
        for (Scope child : node.children) {
            if (child.contains(caretIdx))
                return findInnermostScope(child, caretIdx);
        }
        return node;
    }

    // ── Phase 3: Resolve context ──────────────────────────────────────────────

    private SqlContext resolveContext(Scope innermost) {
        Map<String, String> aliasMap = new LinkedHashMap<>();
        Map<String, List<ColumnInfo>> cteColumns = new LinkedHashMap<>();
        List<ColumnInfo> columns = new ArrayList<>();

        // Đi từ innermost lên root — inner scope không bị outer ghi đè
        for (Scope scope = innermost; scope != null; scope = scope.parent) {
            scope.realTables.forEach(aliasMap::putIfAbsent);
            scope.virtualTables.forEach(cteColumns::putIfAbsent);
        }

        // Virtual tables (CTE + subquery)
        cteColumns.forEach((alias, cols) -> {
            if (alias.startsWith("__anon_")) {
                cols.forEach(c -> columns.add(c.withTableAlias(null)));
            } else {
                cols.forEach(c -> columns.add(c.withTableAlias(alias)));
            }
        });

        // Real tables
        aliasMap.forEach((alias, tableName) ->
                getColumns(tableName).forEach(c -> columns.add(c.withTableAlias(alias)))
        );

        List<String> tables = new ArrayList<>(new LinkedHashSet<>(aliasMap.values()));
        return new SqlContext(aliasMap, cteColumns, tables, columns);
    }

    // ── Column extraction ─────────────────────────────────────────────────────

    /**
     * Tìm SELECT list ở depth 0 bên trong scope.
     * Xử lý: col, alias.col, col AS name, *, alias.*
     */
    private List<ColumnInfo> extractSelectColumns(List<Token> tokens, Scope scope) {
        List<ColumnInfo> cols = new ArrayList<>();

        int selectIdx = findSelectAtDepthZero(tokens, scope);
        if (selectIdx < 0) return cols;

        int depth = 0;
        ColumnInfo pending = null;

        for (int i = selectIdx + 1; i < scope.closeIdx; i++) {
            Token token = tokens.get(i);
            if (isHidden(token)) continue;
            int type = token.getType();

            if (type == OPEN_PAREN) {
                depth++;
                // Subquery trong SELECT list: (select ... ) — extract columns đệ quy
                if (depth == 1) {
                    int closeIdx = matchingParen(tokens, i, scope.closeIdx);
                    Scope inner = buildInnerScope(tokens, i, closeIdx, scope);
                    List<ColumnInfo> innerCols = extractSelectColumns(tokens, inner);
                    // Đọc [AS] alias sau ')'
                    int k = nextVisible(tokens, closeIdx + 1, scope.closeIdx);
                    if (k < scope.closeIdx && tokens.get(k).getType() == AS)
                        k = nextVisible(tokens, k + 1, scope.closeIdx);
                    if (k < scope.closeIdx && tokens.get(k).getType() == ID) {
                        String alias = tokens.get(k).getText();
                        // Scalar subquery: đặt alias là tên cột, pending để AS tiếp theo có thể override
                        pending = innerCols.isEmpty()
                                ? new ColumnInfo(alias)
                                : innerCols.get(0).withAlias(alias);
                        i = k;
                    } else {
                        // Không có alias — dùng tên cột gốc từ bên trong
                        pending = innerCols.isEmpty() ? null : innerCols.get(0);
                        i = closeIdx;
                    }
                }
                continue;
            } else if (type == CLOSE_PAREN) {
                depth--;
                continue;
            }
            if (depth > 0) continue;
            if (type == FROM) break;

            if (type == AS) {
                int j = nextVisible(tokens, i + 1, scope.closeIdx);
                if (j < scope.closeIdx && tokens.get(j).getType() == ID) {
                    if (pending != null) pending = pending.withAlias(tokens.get(j).getText());
                    i = j;
                }

            } else if (type == STAR) {
                pending = null;
                expandStar(scope, cols);

            } else if (type == COMMA) {
                if (pending != null) {
                    cols.add(pending);
                    pending = null;
                }

            } else if (type == ID) {
                String colName = token.getText();
                String tableAlias = null;

                int k = nextVisible(tokens, i + 1, scope.closeIdx);
                if (k < scope.closeIdx && tokens.get(k).getType() == DOT) {
                    tableAlias = colName;
                    int afterDot = nextVisible(tokens, k + 1, scope.closeIdx);
                    if (afterDot < scope.closeIdx) {
                        int afterType = tokens.get(afterDot).getType();
                        if (afterType == ID) {
                            colName = tokens.get(afterDot).getText();
                            i = afterDot;
                        } else if (afterType == STAR) {
                            expandStarForAlias(scope, tableAlias, cols);
                            pending = null;
                            i = afterDot;
                            continue;
                        }
                    }
                }

                pending = resolveColumn(scope, tableAlias, colName);

            } else {
                pending = null;
            }
        }

        if (pending != null) cols.add(pending);
        return cols;
    }

    /**
     * Tạo Scope cho subquery trong SELECT list, đệ quy vào các subquery lồng bên trong.
     * Mỗi '(' bên trong cũng được build thành child scope đầy đủ.
     */
    private Scope buildInnerScope(List<Token> tokens, int openIdx, int closeIdx, Scope parent) {
        Scope inner = new Scope(openIdx, closeIdx, parent);
        prescanAliases(tokens, inner);

        // Build child scopes cho các subquery lồng bên trong (FROM subquery)
        for (int i = openIdx + 1; i < closeIdx; i++) {
            Token token = tokens.get(i);
            if (isHidden(token)) continue;
            if (token.getType() == OPEN_PAREN && isFromSubquery(tokens, new Scope(i, closeIdx, inner))) {
                int childClose = matchingParen(tokens, i, closeIdx);
                Scope child = buildInnerScope(tokens, i, childClose, inner);
                inner.children.add(child);
                // Đọc alias sau ')' nếu có
                int k = nextVisible(tokens, childClose + 1, closeIdx);
                if (k < closeIdx && tokens.get(k).getType() == AS)
                    k = nextVisible(tokens, k + 1, closeIdx);
                String name = null;
                if (k < closeIdx && tokens.get(k).getType() == ID) {
                    name = tokens.get(k).getText();
                    i = k;
                }
                List<ColumnInfo> cols = extractSelectColumns(tokens, child);
                if (name != null) {
                    inner.realTables.putIfAbsent(name, name);
                    inner.virtualTables.putIfAbsent(name, cols);
                } else {
                    inner.virtualTables.putIfAbsent("__anon_" + i, cols);
                }
                i = childClose;
            }
        }

        return inner;
    }

    /**
     * SELECT * → expand tất cả bảng trong scope
     */
    private void expandStar(Scope scope, List<ColumnInfo> out) {
        scope.realTables.forEach((alias, tableName) ->
                getColumns(tableName).forEach(c -> out.add(c.withTableAlias(alias)))
        );
        scope.virtualTables.forEach((alias, cols) -> {
            if (!alias.startsWith("__anon_"))
                cols.forEach(c -> out.add(c.withTableAlias(alias)));
        });
    }

    /**
     * alias.* → expand đúng bảng/virtual table
     */
    private void expandStarForAlias(Scope scope, String alias, List<ColumnInfo> out) {
        String tableName = scope.realTables.get(alias);
        if (tableName != null) {
            getColumns(tableName).forEach(c -> out.add(c.withTableAlias(alias)));
            return;
        }
        List<ColumnInfo> vtCols = scope.virtualTables.get(alias);
        if (vtCols != null)
            vtCols.forEach(c -> out.add(c.withTableAlias(alias)));
    }

    private ColumnInfo resolveColumn(Scope scope, String alias, String colName) {
        if (alias != null) {
            // Thử real table
            String tableName = scope.realTables.get(alias);
            if (tableName != null) {
                return getColumns(tableName).stream()
                        .filter(c -> c.name().equalsIgnoreCase(colName))
                        .findFirst()
                        .map(c -> c.withTableAlias(alias))
                        .orElse(new ColumnInfo(colName));
            }
            // Thử virtual table
            List<ColumnInfo> vtCols = scope.virtualTables.get(alias);
            if (vtCols != null) {
                return vtCols.stream()
                        .filter(c -> c.name().equalsIgnoreCase(colName))
                        .findFirst()
                        .map(c -> c.withTableAlias(alias))
                        .orElse(new ColumnInfo(colName));
            }
        }

        // Không có alias → scan tất cả
        for (String tableName : scope.realTables.values()) {
            for (ColumnInfo c : getColumns(tableName)) {
                if (c.name().equalsIgnoreCase(colName)) return c;
            }
        }
        for (List<ColumnInfo> vtCols : scope.virtualTables.values()) {
            for (ColumnInfo c : vtCols) {
                if (c.name().equalsIgnoreCase(colName)) return c;
            }
        }

        return new ColumnInfo(colName);
    }

    private int findSelectAtDepthZero(List<Token> tokens, Scope scope) {
        int depth = 0;
        for (int i = scope.openIdx + 1; i < scope.closeIdx; i++) {
            int type = tokens.get(i).getType();
            if (type == OPEN_PAREN) depth++;
            else if (type == CLOSE_PAREN) depth--;
            else if (depth == 0 && type == SELECT) return i;
        }
        return -1;
    }

    // ── DB column lookup ──────────────────────────────────────────────────────

    public List<ColumnInfo> getColumns(String tableName) {
        List<SchemaLoader.DBColumnInfo> dbCols =
                columnCache.computeIfAbsent(tableName, k -> {
                    var table = tableIndex.get(k);
                    return table == null ? List.of() : table.columns();
                });
        return dbCols.stream()
                .map(dbCol -> new ColumnInfo(dbCol.name(), dbCol.fullName(), tableName, dbCol.dataType()))
                .toList();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private int nextVisible(List<Token> tokens, int from, int limit) {
        while (from < limit && isHidden(tokens.get(from))) from++;
        return from;
    }

    private boolean isHidden(Token token) {
        return token.getChannel() != Token.DEFAULT_CHANNEL;
    }

    private int matchingParen(List<Token> tokens, int openIdx, int size) {
        int depth = 1, i = openIdx + 1;
        while (i < size && depth > 0) {
            int type = tokens.get(i++).getType();
            if (type == OPEN_PAREN) depth++;
            else if (type == CLOSE_PAREN) depth--;
        }
        return i - 1;
    }

    /**
     * COMMA này có nằm trong FROM list không? Nhìn lại tối đa 50 token
     */
    private boolean isInsideFromList(List<Token> tokens, int commaIdx) {
        for (int i = commaIdx - 1; i >= Math.max(0, commaIdx - 50); i--) {
            if (isHidden(tokens.get(i))) continue;
            int type = tokens.get(i).getType();
            if (type == FROM) return true;
            if (type == SELECT || type == JOIN) return false;
        }
        return false;
    }
}