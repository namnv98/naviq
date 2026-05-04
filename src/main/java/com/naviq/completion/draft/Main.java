//package com.navi.cli.completion.sql;
//
//import com.example.PostgreSQLLexer;
//import com.example.PostgreSQLParser;
//import com.navi.cli.completion.engine.AntlrCompletionEngine;
//import com.navi.cli.completion.model.Suggest;
//import org.antlr.v4.runtime.CharStream;
//import org.antlr.v4.runtime.CharStreams;
//import org.antlr.v4.runtime.CommonTokenStream;
//import org.antlr.v4.runtime.Token;
//
//import java.util.*;
//
//import static java.util.Objects.isNull;
//
//public class Main {
//
//    static class SQLContextVisitor {
//        public final Map<String, String> tableAliasMap = new HashMap<>();
//        public final Map<String, List<String>> cteColumns = new HashMap<>();
//        public final List<String> fromTables = new ArrayList<>();
//    }
//
//    static final Map<String, List<String>> DB_SCHEMA = new HashMap<>();
//
//    static {
//        DB_SCHEMA.put("public.users", List.of("public.users.id", "public.users.name"));
//        DB_SCHEMA.put("public.demo", List.of("public.demo.id", "public.demo.name"));
//        DB_SCHEMA.put("public.idc_function_configuration", List.of("public.idc_function_configuration.id", "public.idc_function_configuration.idc_function_id"));
//    }
//
//    static List<String> getColumnsOfTable(String tableName) {
//        return DB_SCHEMA.getOrDefault(tableName, List.of());
//    }
//
//    // ── Scope node ───────────────────────────────────────────────────────
//    static class ScopeNode {
//        int open, close;                          // vị trí token LPAREN / RPAREN
//        Map<String, String> aliases = new LinkedHashMap<>(); // alias → realTable
//        Map<String, List<String>> cteColumns = new LinkedHashMap<>();
//        List<ScopeNode> children = new ArrayList<>();
//        ScopeNode parent;
//
//        ScopeNode(int open, ScopeNode parent) {
//            this.open = open;
//            this.parent = parent;
//        }
//
//        boolean contains(int idx) {
//            return idx > open && idx < close;
//        }
//    }
//
//    // ── Giai đoạn 1: xây cây scope ───────────────────────────────────────
//    static ScopeNode buildScopeTree(List<Token> tokens) {
//        int size = tokens.size();
//        ScopeNode root = new ScopeNode(-1, null);
//        root.close = size;
//        ScopeNode current = root;
//
//        for (int i = 0; i < size - 1; i++) {
//            int type = tokens.get(i).getType();
//
//            // ── mở scope mới ──
//            if (type == PostgreSQLParser.LPAREN) {
//                ScopeNode child = new ScopeNode(i, current);
//                current.children.add(child);
//                current = child;
//                child.close = size;
//                continue;
//            }
//
//            // ── đóng scope ──
//            if (type == PostgreSQLParser.RPAREN) {
//                current.close = i;
//
//                // subquery alias:  (SELECT …) [AS] alias
//                int k = skipHidden(tokens, i + 1, size);
//                if (k < size && tokens.get(k).getType() == PostgreSQLParser.AS)
//                    k = skipHidden(tokens, k + 1, size);
//                if (k < size && tokens.get(k).getType() == PostgreSQLParser.ID) {
//                    String alias = tokens.get(k).getText();
//                    List<String> cols = extractSubqueryColumns(current, tokens, current.open, current.close);
//                    // alias đăng ký ở scope CHA (nơi subquery được dùng)
//                    if (current.parent != null) {
//                        current.parent.aliases.put(alias, alias);
//                        current.parent.cteColumns.put(alias, cols);
//                    }
//                    i = k; // skip qua alias token
//                }
//
//                current = current.parent != null ? current.parent : root;
//                continue;
//            }
//
//            // ── chỉ quan tâm FROM / JOIN / COMMA ──
//            if (type != PostgreSQLParser.FROM && type != PostgreSQLParser.JOIN && type != PostgreSQLParser.COMMA) {
//                continue;
//            }
//
//            // table name
//            int j = skipHidden(tokens, i + 1, size);
//            if (j >= size) {
//                continue;
//            }
//            if (tokens.get(j).getType() == PostgreSQLParser.LPAREN) {
//                continue; // subquery, bỏ qua
//            }
//            if (tokens.get(j).getType() != PostgreSQLParser.ID) {
//                continue;
//            }
//
//            String[] qn = readQualifiedName(tokens, j, size);
//            String tableName = qn[0];
//            int lastTokenTableName = Integer.parseInt(qn[1]);
//
//            current.aliases.put(tableName, tableName);   // table tự alias chính nó
//
//            // alias tuỳ chọn
//            int k = skipHidden(tokens, lastTokenTableName + 1, size);
//            if (k < size && tokens.get(k).getType() == PostgreSQLParser.AS) {
//                k = skipHidden(tokens, k + 1, size);
//            }
//            if (k < size && tokens.get(k).getType() == PostgreSQLParser.ID) {
//                current.aliases.put(tokens.get(k).getText(), tableName);
//                i = k;
//            } else {
//                i = lastTokenTableName;
//            }
//        }
//        return root;
//    }
//
//    private static String[] readQualifiedName(List<Token> tokens, int start, int size) {
//        int j = skipHidden(tokens, start, size);
//        if (j >= size || tokens.get(j).getType() != PostgreSQLParser.ID) {
//            return null;
//        }
//
//        String part1 = tokens.get(j).getText();
//        int afterPart1 = skipHidden(tokens, j + 1, size);
//
//        if (afterPart1 < size && tokens.get(afterPart1).getType() == PostgreSQLParser.DOT) {
//            int afterDot = skipHidden(tokens, afterPart1 + 1, size);
//            if (afterDot < size && tokens.get(afterDot).getType() == PostgreSQLParser.ID) {
//                // schema.table
//                String full = part1 + "." + tokens.get(afterDot).getText();
//                return new String[]{full, String.valueOf(afterDot)};
//            }
//        }
//
//        return new String[]{part1, String.valueOf(j)};
//    }
//
//    record ColumnName(List<String> names, int tokenIndex) {
//    }
//
//
//    private static ColumnName readColumeName(ScopeNode current, List<Token> tokens, int start, int size) {
//        int i = skipHidden(tokens, start, size);
//        if (i >= size || tokens.get(i).getType() != PostgreSQLParser.ID) {
//            return null;
//        }
//
//        String first = tokens.get(i).getText();
//        int i1 = skipHidden(tokens, i + 1, size);
//
//        // ── case 1: single column ──
//        if (i1 >= size || tokens.get(i1).getType() != PostgreSQLParser.DOT) {
//            return new ColumnName(List.of(first), i);
//        }
//
//        // ── case 2: a.b or a.b.c ──
//        int i2 = skipHidden(tokens, i1 + 1, size);
//
//        if (i2 >= size || tokens.get(i2).getType() == PostgreSQLParser.STAR) {
//            var columnsOfTable = getColumnsOfTable(current.aliases.get(first));
//            return new ColumnName(columnsOfTable, i);
//        }
//
//        if (i2 >= size || tokens.get(i2).getType() != PostgreSQLParser.ID) {
//            return new ColumnName(List.of(first), i);
//        }
//
//        String second = tokens.get(i2).getText();
//
//        int i3 = skipHidden(tokens, i2 + 1, size);
//
//        // ── case 3: schema.table.column ──
//        if (i3 < size && tokens.get(i3).getType() == PostgreSQLParser.DOT) {
//            int i4 = skipHidden(tokens, i3 + 1, size);
//            if (i4 < size && tokens.get(i4).getType() == PostgreSQLParser.ID) {
//                String third = tokens.get(i4).getText();
//
//                // schema.table.column
//                String full = first + "." + second + "." + third;
//                return new ColumnName(List.of(full), i4);
//            }
//        }
//
//        // table.column
//        String full = first + "." + second;
//        return new ColumnName(List.of(full), i2);
//    }
//
//    // ── Giai đoạn 2: tìm node chứa cursor, leo lên gom alias ────────────
//    static ScopeNode findInnermostScope(ScopeNode node, int caretIdx) {
//        for (ScopeNode child : node.children) {
//            if (child.contains(caretIdx)) {
//                return findInnermostScope(child, caretIdx);
//            }
//        }
//        return node; // không có con nào chứa → chính node này
//    }
//
//    static void resolveVisibleAliases(ScopeNode innermostScope, SQLContextVisitor visitor) {
//        // Đi từ innermost lên root, outer scope không ghi đè inner
//        ScopeNode cur = innermostScope;
//        while (cur != null) {
//            cur.aliases.forEach(visitor.tableAliasMap::putIfAbsent);
//            cur.cteColumns.forEach(visitor.cteColumns::putIfAbsent);
//            cur = cur.parent;
//        }
//        new HashSet<>(visitor.tableAliasMap.values())
//                .forEach(t -> {
//                    if (!visitor.fromTables.contains(t)) visitor.fromTables.add(t);
//                });
//    }
//
//    // ── Entry point thay thế enrichAliasMapFromTokens ────────────────────
//    static void enrichAliasMapFromTokens(
//            CommonTokenStream tokenStream,
//            SQLContextVisitor visitor,
//            int caretTokenIndex) {
//
//        List<Token> tokens = tokenStream.getTokens();
//        ScopeNode root = buildScopeTree(tokens);
//        ScopeNode innermostScope = findInnermostScope(root, caretTokenIndex);
//        resolveVisibleAliases(innermostScope, visitor);
//    }
//
//    // ----------------------------------------------------------------
//    static int skipHidden(List<Token> tokens, int from, int size) {
//        while (from < size && tokens.get(from).getChannel() != Token.DEFAULT_CHANNEL)
//            from++;
//        return from;
//    }
//
//    static List<String> extractSubqueryColumns(ScopeNode current, List<Token> tokens, int openParen, int closeParen) {
//        List<String> cols = new ArrayList<>();
//
//        // Tìm SELECT đầu tiên, bỏ qua nested SELECT bên trong paren
//        int i = openParen + 1;
//        int depth = 0;
//        while (i < closeParen) {
//            int type = tokens.get(i).getType();
//            if (type == PostgreSQLParser.LPAREN) {
//                depth++;
//                i++;
//                continue;
//            }
//            if (type == PostgreSQLParser.RPAREN) {
//                depth--;
//                i++;
//                continue;
//            }
//            if (depth == 0 && type == PostgreSQLParser.SELECT) break;
//            i++;
//        }
//        if (i >= closeParen) return cols; // không tìm thấy SELECT
//
//        i = skipHidden(tokens, i + 1, closeParen);
//
//        // Scan select-list: chỉ ở depth 0, dừng khi gặp FROM
//        String lastId = null;
//        while (i < closeParen) {
//            int type = tokens.get(i).getType();
//
//            if (type == PostgreSQLParser.LPAREN) {
//                depth++;
//                lastId = null;
//                i++;
//                continue;
//            }
//            if (type == PostgreSQLParser.RPAREN) {
//                depth--;
//                i++;
//                continue;
//            }
//            if (depth > 0 || tokens.get(i).getChannel() != Token.DEFAULT_CHANNEL) {
//                i++;
//                continue;
//            }
//            if (type == PostgreSQLParser.FROM) break;
//
//            if (type == PostgreSQLParser.AS) {
//                // AS luôn đặt tên column → flush ngay, bỏ qua lastId trước đó
//                int j = skipHidden(tokens, i + 1, closeParen);
//                if (j < closeParen && tokens.get(j).getType() == PostgreSQLParser.ID) {
//                    cols.add(tokens.get(j).getText());
//                    cols.remove(lastId);
//                    lastId = null;
//                    i = j + 1;
//                    continue;
//                }
//            }
//
//            if (type == PostgreSQLParser.STAR) {
//                current.aliases.forEach((s, s2) -> {
//                    var columnsOfTable = getColumnsOfTable(current.aliases.get(s));
//                    cols.addAll(columnsOfTable);
//                });
//                i++;
//                continue;
//            }
//
//            if (type == PostgreSQLParser.COMMA) {
//                if (lastId != null) {
//                    cols.add(lastId); // flush col không có alias
//                }
//                lastId = null;
//            } else if (type == PostgreSQLParser.ID) {
//                var columnName = readColumeName(current, tokens, i, tokens.size());
//                if (columnName.names.size() == 1) {
//                    //trường hợp chỉ có 1 name (không phải *) thì set lastId để xử lý type == PostgreSQLParser.AS ghi đè alias
//                    lastId = columnName.names.get(0);
//                }
//
//                columnName.names.forEach(s -> {
//                    cols.add(s);
//                });
//                i = columnName.tokenIndex + 1;
//                continue;
//            } else if (type != PostgreSQLParser.DOT) {
//                lastId = null; // expression như COUNT(), +, - → reset
//            }
//            i++;
//        }
//
//        if (lastId != null) {
//            cols.add(lastId); // col cuối không có alias
//        }
//        return cols;
//    }
//
//    // ----------------------------------------------------------------
//    static String extractQualifier(CommonTokenStream tokenStream, int caretTokenIndex) {
//        List<Token> tokens = tokenStream.getTokens();
//
//        // Case 1: qualifier trực tiếp — ID DOT [cursor]  (SELECT t.| hoặc INSERT col có qualifier)
//        if (caretTokenIndex >= 2) {
//            Token tok = tokens.get(caretTokenIndex - 1);
//            if (tok.getType() == PostgreSQLParser.DOT) {
//                Token prev = tokens.get(caretTokenIndex - 2);
//                if (prev.getType() == PostgreSQLParser.ID) return prev.getText();
//            }
//        }
//
//        // Case 2: trong column list của INSERT — scan ngược tìm LPAREN trước cursor
//        // Pattern: INSERT INTO tableName ( col1, col2, [cursor]
//        String insertTable = extractInsertTable(tokens, caretTokenIndex);
//        if (insertTable != null) return insertTable;
//
//        return null;
//    }
//
//    static String extractInsertTable(List<Token> tokens, int caretTokenIndex) {
//        // Scan ngược tìm LPAREN không có cặp
//        int depth = 0;
//        for (int i = caretTokenIndex - 1; i >= 0; i--) {
//            int type = tokens.get(i).getType();
//            if (type == Token.HIDDEN_CHANNEL) continue;
//
//            if (type == PostgreSQLParser.RPAREN) {
//                depth++;
//                continue;
//            }
//            if (type == PostgreSQLParser.LPAREN) {
//                if (depth > 0) {
//                    depth--;
//                    continue;
//                }
//
//                // Tìm thấy LPAREN mở — kiểm tra trước nó có phải tableName không
//                // Pattern: tableName LPAREN  →  INSERT INTO tableName (
//                int k = i - 1;
//                while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
//                if (k < 0) return null;
//
//                // Đọc qualified name ngược: có thể là  ID  hoặc  ID DOT ID
//                if (tokens.get(k).getType() != PostgreSQLParser.ID) return null;
//                String tablePart = tokens.get(k).getText();
//                k--;
//                while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
//
//                if (k >= 0 && tokens.get(k).getType() == PostgreSQLParser.DOT) {
//                    k--;
//                    while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
//                    if (k >= 0 && tokens.get(k).getType() == PostgreSQLParser.ID) {
//                        tablePart = tokens.get(k).getText() + "." + tablePart;
//                        k--;
//                        while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
//                    }
//                }
//
//                // Xác nhận đây là INSERT context: trước tableName phải là INTO
//                if (k >= 0 && tokens.get(k).getType() == PostgreSQLParser.INTO) {
//                    return tablePart; // e.g. "public.demo"
//                }
//
//                return null; // LPAREN của subquery hay expression, không phải INSERT
//            }
//
//            // Gặp keyword kết thúc vòng scan
//            if (type == PostgreSQLParser.SELECT || type == PostgreSQLParser.WHERE
//                    || type == PostgreSQLParser.FROM) return null;
//        }
//        return null;
//    }
//
//    static int findCaretTokenIndex(CommonTokenStream tokenStream, int cursorCharPos) {
//        List<Token> tokens = tokenStream.getTokens();
//        for (int i = 0; i < tokens.size() - 1; i++) {
//            Token t = tokens.get(i);
//            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
//            if (t.getStartIndex() >= cursorCharPos) return i;
//        }
//        return tokens.size() - 1;
//    }
//
//    // ----------------------------------------------------------------
//    public static void main(String[] args) {
//        var suggests = suggests("WITH t AS ( SELECT id FROM users ) SELECT t. FROM t",
//                "WITH t AS ( SELECT id FROM users ) SELECT t.".length());
//        System.out.println();
//    }
//
//    public static List<Suggest> suggests(String sql, Integer cursorCharPos) {
//        var suggests = new ArrayList<Suggest>();
//        AntlrCompletionEngine.FollowSetsByState followSets = new AntlrCompletionEngine.FollowSetsByState();
//
//        // cursorCharPos = editor truyền vào; dùng length() để giả lập cursor ở cuối
//        if (isNull(cursorCharPos)) {
//            cursorCharPos = sql.length();
//        }
//        CharStream input = CharStreams.fromString(sql);
//        PostgreSQLLexer lexer = new PostgreSQLLexer(input);
//        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
//        PostgreSQLParser parser = new PostgreSQLParser(tokenStream);
//        parser.removeErrorListeners();
//        tokenStream.fill();
//
//        int caretTokenIndex = findCaretTokenIndex(tokenStream, cursorCharPos);
//
//        System.out.println("\n=== TOKEN STREAM ===");
//        for (int i = 0; i < tokenStream.size(); i++) {
//            Token t = tokenStream.get(i);
//            System.out.println(i + ": type=" + t.getType() + " text='" + t.getText() + "'");
//        }
//        System.out.println("caretTokenIndex = " + caretTokenIndex);
//
//        // ── Enrich ────────────────────────────────────────────────
//        SQLContextVisitor visitor = new SQLContextVisitor();
//        enrichAliasMapFromTokens(tokenStream, visitor, caretTokenIndex - 1);
//
//        System.out.println("\n=== CONTEXT ===");
//        System.out.println("tableAliasMap : " + visitor.tableAliasMap);
//        System.out.println("cteColumns    : " + visitor.cteColumns);
//        System.out.println("fromTables    : " + visitor.fromTables);
//
//        // ── C3 — reset parser, tái dùng tokenStream ───────────────
//        parser.reset();
//        tokenStream.seek(0);
//
//        Map<Integer, Boolean> ignoredTokens = new HashMap<>();
//        ignoredTokens.put(Token.EOF, true);
//        ignoredTokens.put(PostgreSQLParser.PLUS, true);
//        ignoredTokens.put(PostgreSQLParser.MINUS, true);
//        ignoredTokens.put(PostgreSQLParser.SLASH, true);
//        ignoredTokens.put(PostgreSQLParser.LT, true);
//        ignoredTokens.put(PostgreSQLParser.GT, true);
//        ignoredTokens.put(PostgreSQLParser.LTE, true);
//        ignoredTokens.put(PostgreSQLParser.GTE, true);
//        ignoredTokens.put(PostgreSQLParser.NUMBER, true);
//        ignoredTokens.put(PostgreSQLParser.STRING, true);
//        ignoredTokens.put(PostgreSQLParser.SEMI, true);
//
//        Map<Integer, Boolean> preferredRules = new HashMap<>();
//        preferredRules.put(PostgreSQLParser.RULE_tableName, true);
//        preferredRules.put(PostgreSQLParser.RULE_columnName, true);
//        preferredRules.put(PostgreSQLParser.RULE_tableAlias, true);
//
//        AntlrCompletionEngine core = new AntlrCompletionEngine(parser, ignoredTokens, preferredRules, followSets);
//
//        AntlrCompletionEngine.CandidatesCollection candidates = core.collectCandidates(caretTokenIndex);
//
//        // ── Suggestions ───────────────────────────────────────────
//        System.out.println("\n=== SUGGESTIONS ===");
//
//        String qualifier = extractQualifier(tokenStream, caretTokenIndex);
//        System.out.println("qualifier: " + qualifier);
//
//        for (Map.Entry<Integer, List<Integer>> entry : candidates.tokens.entrySet()) {
//            System.out.println("Token: " + PostgreSQLParser.VOCABULARY.getDisplayName(entry.getKey()));
//            suggests.add(Suggest.of(PostgreSQLParser.VOCABULARY.getDisplayName(entry.getKey()), "keyword"));
//
//        }
//
//        for (Map.Entry<Integer, List<AntlrCompletionEngine.RuleContext>> entry : candidates.rules.entrySet()) {
//            List<AntlrCompletionEngine.RuleContext> path = entry.getValue();
//            String ruleName = PostgreSQLParser.ruleNames[entry.getKey()];
//
//            if (ruleName.equals("tableAlias")) {
//                var tableName = extractTableBeforeAs(tokenStream, caretTokenIndex);
//                if (tableName != null) {
//                    String alias = suggestAlias(visitor, tableName);
//                    suggests.add(Suggest.of(alias, "column"));
//                }
//            }
//
//            if (ruleName.equals("columnName")) {
//                if (qualifier != null) {
//                    String realTable = visitor.tableAliasMap.getOrDefault(qualifier, qualifier);
//                    System.out.println("columnName → qualifier='" + qualifier + "' → realTable='" + realTable + "'");
//                    if (visitor.cteColumns.containsKey(realTable)) {
//                        System.out.println("  (subquery/CTE columns)");
//                        visitor.cteColumns.get(realTable).forEach(c -> {
//                            suggests.add(Suggest.of(realTable + "." + c, "column"));
//                            System.out.println("  - " + c);
//                        });
//                    } else {
//                        System.out.println("  (DB columns)");
//                        getColumnsOfTable(realTable).forEach(c -> {
//                            suggests.add(Suggest.of(realTable + "." + c, "column"));
//                            System.out.println("  - " + c);
//                        });
//                    }
//                } else {
//                    boolean inSelect = path.stream().anyMatch(r -> PostgreSQLParser.ruleNames[r.id].equals("selectStmt"));
//                    boolean inOrderBy = path.stream().anyMatch(r -> PostgreSQLParser.ruleNames[r.id].equals("orderByClause"));
//                    boolean inHaving = path.stream().anyMatch(r -> PostgreSQLParser.ruleNames[r.id].equals("havingClause"));
//
//                    if (visitor.tableAliasMap.isEmpty()) {
//                        System.out.println("columnName → all tables");
//                        DB_SCHEMA.keySet().forEach(t -> getColumnsOfTable(t).forEach(c -> {
//                            suggests.add(Suggest.of(t + "." + c, "column"));
//                            System.out.println("  - " + t + "." + c);
//                        }));
//                    } else {
//                        visitor.tableAliasMap.forEach((alias, realTable) -> {
//                            System.out.println("columnName → alias='" + alias + "' → realTable='" + realTable + "'");
//                            if (visitor.cteColumns.containsKey(realTable)) {
//                                System.out.println("  (subquery/CTE columns)");
//                                visitor.cteColumns.get(realTable).forEach(c -> {
//                                    suggests.add(Suggest.of(alias + "." + c, "column"));
//                                    System.out.println("  - " + c);
//                                });
//                            } else {
//                                System.out.println("  (DB columns)");
//                                getColumnsOfTable(realTable).forEach(c -> {
//                                    suggests.add(Suggest.of(alias + "." + c, "column"));
//                                    System.out.println("  - " + c);
//                                });
//                            }
//                        });
//                    }
//                }
//            }
//
//            if (ruleName.equals("tableName")) {
//                System.out.println("tableName → gợi ý:");
//                Set<String> tables = new HashSet<>(DB_SCHEMA.keySet());
//                visitor.tableAliasMap.values().forEach(tables::add);
//                tables.forEach(t -> {
//                    suggests.add(Suggest.of(t, "table"));
//                    System.out.println("  - " + t);
//                });
//            }
//        }
//        return suggests;
//    }
//
//    static String suggestAlias(SQLContextVisitor visitor, String tableName) {
//        if (tableName == null || tableName.isEmpty()) return null;
//
//        var keySet = visitor.tableAliasMap.keySet();
//
//        // bỏ schema nếu có
//        int dot = tableName.lastIndexOf('.');
//        if (dot != -1) {
//            tableName = tableName.substring(dot + 1);
//        }
//
//        // snake_case → lấy chữ đầu
//        String[] parts = tableName.split("_");
//
//        StringBuilder base = new StringBuilder();
//        for (String p : parts) {
//            if (!p.isEmpty()) {
//                base.append(Character.toLowerCase(p.charAt(0)));
//            }
//        }
//
//        // fallback
//        if (base.length() == 0 && !tableName.isEmpty()) {
//            base.append(Character.toLowerCase(tableName.charAt(0)));
//        }
//
//        String alias = base.toString();
//
//        if (!keySet.contains(alias)) {
//            return alias;
//        }
//
//        int i = 1;
//        while (keySet.contains(alias + i)) {
//            i++;
//        }
//
//        return alias + i;
//    }
//
//
//    static String extractTableBeforeAs(CommonTokenStream tokenStream, int caretTokenIndex) {
//        List<Token> tokens = tokenStream.getTokens();
//
//        int fromIdx = -1;
//        for (int i = caretTokenIndex - 1; i >= 0; i--) {
//            Token t = tokens.get(i);
//            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
//
//            if (t.getType() == PostgreSQLParser.FROM) {
//                fromIdx = i;
//                break;
//            }
//        }
//
//        if (fromIdx < 0) return null;
//
//        int i = fromIdx + 1;
//        while (i < caretTokenIndex) {
//            Token t = tokens.get(i);
//
//            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
//                i++;
//                continue;
//            }
//
//            if (t.getType() == PostgreSQLParser.AS) {
//                int next = skipHidden(tokens, i + 1, caretTokenIndex);
//                if (next >= caretTokenIndex) {
//                    return readTableNameBackward(tokens, i - 1);
//                }
//            }
//
//            i++;
//        }
//
//        return null;
//    }
//
//    static String readTableNameBackward(List<Token> tokens, int idx) {
//        StringBuilder sb = new StringBuilder();
//        int i = idx;
//
//        while (i >= 0) {
//            Token t = tokens.get(i);
//
//            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
//                i--;
//                continue;
//            }
//
//            if (t.getType() == PostgreSQLParser.ID) {
//                if (sb.length() > 0) sb.insert(0, ".");
//                sb.insert(0, t.getText());
//            } else if (t.getType() == PostgreSQLParser.DOT) {
//                // skip
//            } else {
//                break;
//            }
//
//            i--;
//        }
//
//        return sb.length() > 0 ? sb.toString() : null;
//    }
//}