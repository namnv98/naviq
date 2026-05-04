package com.naviq.completion.draft;

import com.example.PostgreSQLLexer;
import com.example.PostgreSQLParser;
import com.naviq.completion.engine.AntlrCompletionEngine;
import com.naviq.completion.model.Suggest;
import com.naviq.datasource.PostgresDataSource;
import com.naviq.datasource.SchemaLoader;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import com.naviq.completion.draft.CollectTableInfo.SQLContextVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.isNull;

public class PostgresCompletionEngine {
    static CollectTableInfo collectTableInfo;

    // Đổi kiểu
    public static List<SchemaLoader.SchemaInfo> DB_SCHEMA;
    public static Map<String, SchemaLoader.TableInfo> TABLE_INDEX;
    public static Map<String, SchemaLoader.TableInfo> SCHEMA_TABLE_INDEX;
    public static List<String> FUNCTIONS;
    public static List<String> DATA_TYPES;

    static {
        try {
            DB_SCHEMA = SchemaLoader.loadSchema(PostgresDataSource.get());
            TABLE_INDEX = buildIndex(DB_SCHEMA);
            DATA_TYPES = SchemaLoader.loadDataTypes(PostgresDataSource.get());
            SCHEMA_TABLE_INDEX = buildSchemaTableIndex(DB_SCHEMA);
            FUNCTIONS = SchemaLoader.loadFunctions(PostgresDataSource.get());
            collectTableInfo = new CollectTableInfo(TABLE_INDEX);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void reloadDB() {
        try {
            DB_SCHEMA = SchemaLoader.loadSchema(PostgresDataSource.get());
            TABLE_INDEX = buildIndex(DB_SCHEMA);
            DATA_TYPES = SchemaLoader.loadDataTypes(PostgresDataSource.get());
            SCHEMA_TABLE_INDEX = buildSchemaTableIndex(DB_SCHEMA);
            FUNCTIONS = SchemaLoader.loadFunctions(PostgresDataSource.get());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, SchemaLoader.TableInfo> buildIndex(List<SchemaLoader.SchemaInfo> schemas) {
        Map<String, SchemaLoader.TableInfo> index = new HashMap<>();
        for (SchemaLoader.SchemaInfo s : schemas) {
            for (SchemaLoader.TableInfo t : s.tables()) {
                index.put(t.fullName(), t);  // "public.user"
                index.put(t.name(), t);      // "user" — fallback không có schema
            }
        }
        return index;
    }

    private static Map<String, SchemaLoader.TableInfo> buildSchemaTableIndex(List<SchemaLoader.SchemaInfo> schemas) {
        Map<String, SchemaLoader.TableInfo> index = new HashMap<>();
        for (SchemaLoader.SchemaInfo s : schemas) {
            for (SchemaLoader.TableInfo t : s.tables()) {
                index.put(t.fullName(), t);  // "public.user"
            }
        }
        return index;
    }

    // Trả List<String> để không đụng code cũ đang dùng
    static List<SchemaLoader.DBColumnInfo> getColumnsOfTable(String tableName) {
        SchemaLoader.TableInfo t = TABLE_INDEX.get(tableName);
        if (t == null) return List.of();
        return t.columns().stream()
                .toList();
    }

    static List<SchemaLoader.DBColumnInfo> getFullColumnsOfTable(String tableName) {
        SchemaLoader.TableInfo t = TABLE_INDEX.get(tableName);
        if (t == null) return List.of();
        return t.columns().stream().toList();
    }

    static List<SchemaLoader.TableInfo> getTablesBySchema(String schemaName) {
        return DB_SCHEMA.stream()
                .filter(s -> s.name().equals(schemaName))
                .findFirst()
                .map(s -> s.tables().stream().toList())
                .orElse(List.of());
    }


    record ColumnName(List<String> names, int tokenIndex) {
    }


    // ----------------------------------------------------------------
    static int skipHidden(List<Token> tokens, int from, int size) {
        while (from < size && tokens.get(from).getChannel() != Token.DEFAULT_CHANNEL)
            from++;
        return from;
    }


    static String extractQualifier(CommonTokenStream tokenStream, int caretTokenIndex) {
        List<Token> tokens = tokenStream.getTokens();

        // Case 1: ID DOT [cursor]
        if (caretTokenIndex >= 2) {
            Token tok = tokens.get(caretTokenIndex - 1);
            if (tok.getType() == PostgreSQLParser.DOT) {
                Token prev = tokens.get(caretTokenIndex - 2);
                if (prev.getType() == PostgreSQLParser.ID) return prev.getText();
            }
        }

        // Case 2: INSERT INTO tableName ( col, [cursor]
        String insertTable = extractInsertTable(tokens, caretTokenIndex);
        if (insertTable != null) return insertTable;

        // Case 3: UPDATE tableName SET col = [cursor]
        String updateTable = extractUpdateTable(tokens, caretTokenIndex);
        if (updateTable != null) return updateTable;

        // Case 4: ALTER TABLE tableName ... COLUMN [cursor]
        String alterTable = extractAlterTable(tokens, caretTokenIndex);
        if (alterTable != null) return alterTable;

        return null;
    }

    static String extractAlterTable(List<Token> tokens, int caretTokenIndex) {
        // Scan ngược tìm ALTER TABLE
        // Pattern: ALTER TABLE tableName ... COLUMN [cursor]
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            if (tokens.get(i).getChannel() != Token.DEFAULT_CHANNEL) continue;
            int type = tokens.get(i).getType();

            // Dừng nếu gặp keyword không liên quan
            if (type == PostgreSQLParser.SELECT
                    || type == PostgreSQLParser.INSERT
                    || type == PostgreSQLParser.UPDATE
                    || type == PostgreSQLParser.DELETE) return null;

            if (type == PostgreSQLParser.COLUMN) {
                // Xác nhận trước COLUMN là ALTER hoặc DROP hoặc ADD
                int k = i - 1;
                while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
                if (k < 0) return null;

                int prevType = tokens.get(k).getType();
                if (prevType != PostgreSQLParser.ALTER
                        && prevType != PostgreSQLParser.DROP
                        && prevType != PostgreSQLParser.ADD) return null;

                // Leo lên tìm ALTER TABLE tableName
                return findAlterTableName(tokens, i);
            }

            if (type == PostgreSQLParser.ALTER) {
                // ALTER TABLE tableName ALTER [cursor] — không có COLUMN keyword
                int k = i + 1;
                while (k < caretTokenIndex
                        && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k++;
                if (k < caretTokenIndex
                        && tokens.get(k).getType() == PostgreSQLParser.TABLE) {
                    // ALTER TABLE → cursor ngay sau tableName, không phải column context
                    return null;
                }
                // ALTER ở giữa câu → đang nhập alterAction, tìm tableName
                return findAlterTableName(tokens, i);
            }

            if (type == PostgreSQLParser.TABLE) {
                // Chỉ TABLE không có ALTER trước → không phải context này
                int k = i - 1;
                while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
                if (k >= 0 && tokens.get(k).getType() == PostgreSQLParser.ALTER) {
                    // Cursor ngay sau TABLE → đang nhập tableName, không gợi ý column
                    return null;
                }
            }
        }
        return null;
    }

    // Tìm tableName trong: ALTER TABLE tableName ...
    static String findAlterTableName(List<Token> tokens, int fromIndex) {
        for (int i = fromIndex - 1; i >= 1; i--) {
            if (tokens.get(i).getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (tokens.get(i).getType() != PostgreSQLParser.TABLE) continue;

            // Kiểm tra trước TABLE là ALTER
            int k = i - 1;
            while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
            if (k < 0 || tokens.get(k).getType() != PostgreSQLParser.ALTER) continue;

            // Đọc tableName sau TABLE
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

    static String extractUpdateTable(List<Token> tokens, int caretTokenIndex) {
        // Scan ngược tìm keyword UPDATE
        // Dọc đường phải thấy SET → xác nhận đang trong SET clause
        boolean foundSet = false;
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            int type = tokens.get(i).getType();
            if (tokens.get(i).getChannel() != Token.DEFAULT_CHANNEL) continue;

            if (type == PostgreSQLParser.SET) {
                foundSet = true;
                continue;
            }

            // Gặp SELECT/FROM/WHERE mà chưa thấy SET → không phải UPDATE context
            if (!foundSet && (type == PostgreSQLParser.SELECT
                    || type == PostgreSQLParser.FROM
                    || type == PostgreSQLParser.WHERE)) return null;

            if (type == PostgreSQLParser.UPDATE) {
                if (!foundSet) return null; // UPDATE nhưng chưa qua SET → chưa vào value

                // Đọc tableName ngay sau UPDATE
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
                        return part1 + "." + tokens.get(k3).getText(); // schema.table
                    }
                }

                return part1; // table đơn
            }
        }
        return null;
    }

    static String extractInsertTable(List<Token> tokens, int caretTokenIndex) {
        // Scan ngược tìm LPAREN không có cặp
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

                // Tìm thấy LPAREN mở — kiểm tra trước nó có phải tableName không
                // Pattern: tableName LPAREN  →  INSERT INTO tableName (
                int k = i - 1;
                while (k >= 0 && tokens.get(k).getChannel() != Token.DEFAULT_CHANNEL) k--;
                if (k < 0) return null;

                // Đọc qualified name ngược: có thể là  ID  hoặc  ID DOT ID
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

                // Xác nhận đây là INSERT context: trước tableName phải là INTO
                if (k >= 0 && tokens.get(k).getType() == PostgreSQLParser.INTO) {
                    return tablePart; // e.g. "public.demo"
                }

                return null; // LPAREN của subquery hay expression, không phải INSERT
            }

            // Gặp keyword kết thúc vòng scan
            if (type == PostgreSQLParser.SELECT || type == PostgreSQLParser.WHERE
                    || type == PostgreSQLParser.FROM) return null;
        }
        return null;
    }

    static int findCaretTokenIndex(CommonTokenStream tokenStream, int cursorCharPos) {
        List<Token> tokens = tokenStream.getTokens();
        for (int i = 0; i < tokens.size() - 1; i++) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (t.getStartIndex() >= cursorCharPos) return i;
        }
        return tokens.size() - 1;
    }

    // ----------------------------------------------------------------
    public static void main(String[] args) {
        var suggests = suggests("select * from public.users",
                "select ".length());
        System.out.println();
    }

    public static List<Suggest> suggests(String sql, Integer cursorCharPos) {
        var suggests = new ArrayList<Suggest>();
        AntlrCompletionEngine.FollowSetsByState followSets = new AntlrCompletionEngine.FollowSetsByState();

        if (isNull(cursorCharPos)) {
            cursorCharPos = sql.length();
        }
        CharStream input = CharStreams.fromString(sql);
        PostgreSQLLexer lexer = new PostgreSQLLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        PostgreSQLParser parser = new PostgreSQLParser(tokenStream);
        parser.removeErrorListeners();
        tokenStream.fill();

        int caretTokenIndex = findCaretTokenIndex(tokenStream, cursorCharPos);

        SQLContextVisitor visitor = new SQLContextVisitor();
        collectTableInfo.enrichAliasMapFromTokens(tokenStream, visitor, caretTokenIndex - 1);


//        var sqlContextBuilder = new SqlContextCollector(TABLE_INDEX);
//        SqlContextCollector.SqlContext sqlContext = sqlContextBuilder.collect(tokenStream, caretTokenIndex);


        parser.reset();
        tokenStream.seek(0);

        Map<Integer, Boolean> ignoredTokens = new HashMap<>();
        ignoredTokens.put(Token.EOF, true);
        ignoredTokens.put(PostgreSQLParser.LPAREN, true);
        ignoredTokens.put(PostgreSQLParser.RPAREN, true);
        ignoredTokens.put(PostgreSQLParser.PLUS, true);
        ignoredTokens.put(PostgreSQLParser.MINUS, true);
        ignoredTokens.put(PostgreSQLParser.SLASH, true);
        ignoredTokens.put(PostgreSQLParser.EQ, true);
        ignoredTokens.put(PostgreSQLParser.NEQ, true);
        ignoredTokens.put(PostgreSQLParser.LT, true);
        ignoredTokens.put(PostgreSQLParser.GT, true);
        ignoredTokens.put(PostgreSQLParser.LTE, true);
        ignoredTokens.put(PostgreSQLParser.GTE, true);
        ignoredTokens.put(PostgreSQLParser.NUMBER, true);
        ignoredTokens.put(PostgreSQLParser.STRING, true);
        ignoredTokens.put(PostgreSQLParser.SEMI, true);

        Map<Integer, Boolean> preferredRules = new HashMap<>();
        preferredRules.put(PostgreSQLParser.RULE_tableName, true);
        preferredRules.put(PostgreSQLParser.RULE_columnName, true);
        preferredRules.put(PostgreSQLParser.RULE_dataTypeName, true);
        preferredRules.put(PostgreSQLParser.RULE_functionCall, true);
        preferredRules.put(PostgreSQLParser.RULE_tableAlias, true);

        AntlrCompletionEngine completionEngine = new AntlrCompletionEngine(parser, ignoredTokens, preferredRules, followSets);

        AntlrCompletionEngine.CandidatesCollection candidates = completionEngine.collectCandidates(caretTokenIndex+1);

        String qualifier = extractQualifier(tokenStream, caretTokenIndex);

        for (Map.Entry<Integer, List<Integer>> entry : candidates.tokens.entrySet()) {
            suggests.add(Suggest.of(PostgreSQLParser.VOCABULARY.getDisplayName(entry.getKey()).toLowerCase().replaceAll("\'", ""), "keyword"));
        }

        for (Map.Entry<Integer, List<AntlrCompletionEngine.RuleContext>> entry : candidates.rules.entrySet()) {
            String ruleName = PostgreSQLParser.ruleNames[entry.getKey()];

            if (ruleName.equals("columnName")) {
                if (qualifier != null) {
                    String realTable = visitor.tableAliasMap.getOrDefault(qualifier, qualifier);
                    if (visitor.cteColumns.containsKey(realTable)) {
                        visitor.cteColumns.get(realTable).forEach(c -> {
                            suggests.add(Suggest.of(qualifier + "." + c, "column"));
                        });
                    } else {
                        getColumnsOfTable(realTable).forEach(c -> {
                            suggests.add(Suggest.of(qualifier + "." + c.name(), "column", c.dataType()));
                        });
                    }
                } else {

                    if (visitor.tableAliasMap.isEmpty()) {
                        SCHEMA_TABLE_INDEX.keySet().forEach(t -> getColumnsOfTable(t).forEach(c -> {
                            suggests.add(Suggest.of(c.fullName(), "column", c.dataType()));
                        }));
                    } else {
                        visitor.tableAliasMap.forEach((alias, realTable) -> {
                            if (visitor.cteColumns.containsKey(realTable)) {
                                visitor.cteColumns.get(realTable).forEach(c -> {
                                    suggests.add(Suggest.of(alias + "." + c, "column"));
                                });
                            } else {
                                getFullColumnsOfTable(realTable).forEach(c -> {
                                    suggests.add(Suggest.of(c.fullName(), "column", c.dataType()));
                                });
                            }
                        });
                    }
                }
//                FUNCTIONS.forEach(fn -> suggests.add(Suggest.of(fn, "function")));
            }
            if (ruleName.equals("dataTypeName")) {
                DATA_TYPES.forEach(t -> suggests.add(Suggest.of(t, "datatype", t)));
            }

            if (ruleName.equals("tableAlias")) {
                var tableName = extractTableBeforeAs(tokenStream, caretTokenIndex);
                if (tableName != null) {
                    String alias = suggestAlias(visitor, tableName);
                    suggests.add(Suggest.of(alias, "column"));
                }
            }

            if (ruleName.equals("tableName")) {

                String schema;
                if (caretTokenIndex >= 2) {
                    Token tok = tokenStream.get(caretTokenIndex - 1);
                    if (tok.getType() == PostgreSQLParser.DOT) {
                        Token prev = tokenStream.get(caretTokenIndex - 2);
                        if (prev.getType() == PostgreSQLParser.ID) {
                            schema = prev.getText();
                            var tables = getTablesBySchema(schema);
                            tables.forEach(t -> {
                                suggests.add(Suggest.of(t.fullName(), t.kind()));
                            });
                            continue;
                        }
                    }
                }

                SCHEMA_TABLE_INDEX.values().forEach(t -> {
                    suggests.add(Suggest.of(t.fullName(), t.kind()));
                });
            }
        }
        return suggests;
    }

    static String suggestAlias(SQLContextVisitor visitor, String tableName) {
        if (tableName == null || tableName.isEmpty()) return null;

        var keySet = visitor.tableAliasMap.keySet();

        // bỏ schema nếu có
        int dot = tableName.lastIndexOf('.');
        if (dot != -1) {
            tableName = tableName.substring(dot + 1);
        }

        // snake_case → lấy chữ đầu
        String[] parts = tableName.split("_");

        StringBuilder base = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                base.append(Character.toLowerCase(p.charAt(0)));
            }
        }

        // fallback
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

    static String extractTableBeforeAs(CommonTokenStream tokenStream, int caretTokenIndex) {
        List<Token> tokens = tokenStream.getTokens();

        int fromIdx = -1;
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;

            if (t.getType() == PostgreSQLParser.FROM) {
                fromIdx = i;
                break;
            }
        }

        if (fromIdx < 0) return null;

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

            if (t.getType() == PostgreSQLParser.ID) {
                if (sb.length() > 0) sb.insert(0, ".");
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