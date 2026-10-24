package com.sqlctx.completion.suggestion;

import com.sqlctx.completion.model.CandidatesResult;
import com.sqlctx.completion.model.CaretScope;
import com.sqlctx.completion.model.Scope;
import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.model.SuggestionType;
import com.sqlctx.completion.syntactic.engine.support.RuleCallStack;
import com.sqlctx.schema.ColumnInfo;
import com.sqlctx.schema.ForeignKey;
import com.sqlctx.schema.Identifiers;
import com.sqlctx.schema.SchemaIndex;
import com.sqlctx.schema.TableInfo;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.Vocabulary;

import java.util.*;
import java.util.function.Function;

/** Phần không phụ thuộc dialect của các *SuggestionService. */
public final class SuggestionSupport {

    private SuggestionSupport() {
    }

    /** Thêm cột riêng của dialect cho 1 bảng thật, sau cột thường của nó - vd cột hệ thống của Postgres. */
    @FunctionalInterface
    public interface ExtraColumns {
        void add(List<Suggestion> columns, String alias, String table);
    }

    /**
     * Từ khoá (ngắn) của Postgres/Oracle không dùng được làm alias trần: "order_notes" -> "on" mà chèn vào thì câu
     * hỏng ("join order_notes on on ..."). Chỉ cần từ ngắn - alias ghép từ chữ cái đầu mỗi phần tên bảng.
     */
    private static final Set<String> RESERVED_ALIASES = Set.of(
            "add", "all", "and", "any", "as", "asc", "at", "by", "case", "cast", "check", "desc", "do", "else", "end",
            "file", "for", "from", "full", "grant", "group", "if", "in", "inner", "into", "is", "join", "left", "level",
            "like", "limit", "lock", "minus", "mode", "natural", "not", "null", "of", "on", "only", "or", "order",
            "outer", "right", "row", "rows", "select", "set", "size", "some", "start", "table", "then", "to", "true",
            "uid", "union", "user", "using", "view", "when", "where", "with");

    /**
     * Alias mặc định cho 1 bảng: chữ cái đầu mỗi phần tên ("contract_items" -> "ci"); trùng alias đang dùng hoặc
     * trùng từ khoá thì thêm số ("c1", "on1").
     */
    public static String suggestAlias(Collection<String> usedAliases, String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return null;
        }
        int dot = tableName.lastIndexOf('.');
        if (dot != -1) {
            tableName = tableName.substring(dot + 1);
        }
        StringBuilder base = new StringBuilder();
        for (String p : tableName.split("_")) {
            if (!p.isEmpty()) {
                base.append(Character.toLowerCase(p.charAt(0)));
            }
        }
        if (base.length() == 0 && !tableName.isEmpty()) {
            base.append(Character.toLowerCase(tableName.charAt(0)));
        }
        String alias = base.toString();
        if (!usedAliases.contains(alias) && !RESERVED_ALIASES.contains(alias)) {
            return alias;
        }
        int i = 1;
        while (usedAliases.contains(alias + i)) {
            i++;
        }
        return alias + i;
    }

    /** Caret dính cuối định danh đang gõ thì tầng cú pháp hỏi "ở đây có thể là gì" tại vị trí lùi 1 ký tự. */
    public static int syntacticCursor(String sql, int cursor) {
        char before = cursor > 0 && cursor <= sql.length() ? sql.charAt(cursor - 1) : ' ';
        return Character.isLetterOrDigit(before) || before == '_' ? cursor - 1 : cursor;
    }

    public static void addKeywords(List<Suggestion> out, Vocabulary vocabulary, CandidatesResult candidates) {
        candidates.tokens.forEach((type, following) -> {
            String text = KeywordText.of(vocabulary, type, following);
            if (text != null) {
                out.add(Suggestion.of(text, SuggestionType.KEYWORD));
            }
        });
    }

    /** Chữ ký dài hơn thế này bị cắt - cột mô tả của menu rộng theo dòng dài nhất. */
    private static final int MAX_SIGNATURE_LENGTH = 40;

    public static void addFunctions(List<Suggestion> out) {
        Map<String, String> signatures = SchemaIndex.functionSignatures;
        SchemaIndex.functions.forEach(fn -> {
            String signature = signatures.get(fn.toLowerCase());
            if (signature != null && signature.length() > MAX_SIGNATURE_LENGTH) {
                signature = signature.substring(0, MAX_SIGNATURE_LENGTH - 1) + "…";
            }
            out.add(Suggestion.of(fn, SuggestionType.FUNCTION, signature));
        });
    }

    /**
     * Alias của bảng đứng ngay trước ON gần nhất phía trước caret: "JOIN orders o ON |" -> "o", "JOIN orders ON |" ->
     * "orders", "JOIN public.orders ON |" -> "public.orders". Đọc từ token vì thứ tự alias trong scope là thứ tự
     * builder đăng ký (theo cây parse), không phải thứ tự trong câu. null nếu không phải alias đang visible.
     */
    public static String aliasBeforeOn(TokenStream tokens, int caretTokenIndex, int onType, int dotType, CaretScope scope) {
        int on = caretTokenIndex - 1;
        while (on >= 0 && !(tokens.get(on).getChannel() == Token.DEFAULT_CHANNEL && tokens.get(on).getType() == onType)) {
            on--;
        }
        int name = previousRealIndex(tokens, on);
        if (name < 0) {
            return null;
        }
        String candidate = tokens.get(name).getText();
        int dot = previousRealIndex(tokens, name);
        if (dot >= 0 && tokens.get(dot).getType() == dotType && previousRealIndex(tokens, dot) >= 0) {
            String qualified = tokens.get(previousRealIndex(tokens, dot)).getText() + "." + candidate;
            String match = visibleAliasIgnoreCase(scope, qualified);
            if (match != null) {
                return match;
            }
        }
        return visibleAliasIgnoreCase(scope, candidate);
    }

    private static String visibleAliasIgnoreCase(CaretScope scope, String name) {
        return scope.visibleAliases().keySet().stream().filter(name::equalsIgnoreCase).findFirst().orElse(null);
    }

    private static int previousRealIndex(TokenStream tokens, int fromIndex) {
        int i = fromIndex - 1;
        while (i >= 0 && tokens.get(i).getChannel() != Token.DEFAULT_CHANNEL) {
            i--;
        }
        return i;
    }

    /**
     * Điều kiện nối giữa bảng vừa JOIN ({@code joined}, xem {@link #aliasBeforeOn}) và từng alias khác đang visible,
     * vd "o.user_id = u.id"; khoá ghép nối các cặp cột bằng "and". Theo khoá ngoại thật trước; không có khoá ngoại
     * cho cặp cột nào thì đoán theo tên ({@link #nameBasedPairs}), xếp sau và ghi nhãn "by name" - nhiều DB thật
     * không khai báo khoá ngoại. Alias trỏ CTE/subquery bỏ qua (không có cột thật để so).
     */
    public static void addJoinConditions(List<Suggestion> out, CaretScope scope, String joined) {
        if (joined == null || scope.visibleDerivedScopes().containsKey(joined)) {
            return;
        }
        TableInfo joinedTable = SchemaIndex.findTable(scope.visibleAliases().get(joined));
        if (joinedTable == null) {
            return;
        }
        Set<String> added = new HashSet<>();
        realTableAliases(scope).forEach((alias, other) -> {
            if (alias.equals(joined)) {
                return;
            }
            int distance = scope.aliasDistances().getOrDefault(alias, 0);
            for (JoinCondition c : joinConditions(joinedTable, joined, other, alias)) {
                addJoinCondition(out, added, joinSuggestion(c.text(), c.foreignKey(), distance));
            }
        });
    }

    /**
     * Ở "JOIN |": gợi ý nguyên cụm "bảng alias on điều kiện" cho mọi bảng có quan hệ (khoá ngoại hoặc đoán theo
     * tên, như {@link #addJoinConditions}) với 1 bảng đang có trong FROM, vd "orders o on o.user_id = u.id".
     *
     * @param tableName tên chèn vào câu cho 1 bảng (tên trần hoặc "schema.bảng")
     */
    public static void addJoinClauses(List<Suggestion> out, CaretScope scope, Function<TableInfo, String> tableName) {
        Map<String, TableInfo> inFrom = realTableAliases(scope);
        if (inFrom.isEmpty()) {
            return;
        }
        Set<String> added = new HashSet<>();
        for (TableInfo candidate : SchemaIndex.schemaTableIndex.values()) {
            String alias = suggestAlias(scope.visibleAliases().keySet(), candidate.name());
            inFrom.forEach((existingAlias, existing) -> {
                int distance = scope.aliasDistances().getOrDefault(existingAlias, 0);
                for (JoinCondition c : joinConditions(candidate, alias, existing, existingAlias)) {
                    String clause = tableName.apply(candidate) + " " + alias + " on " + c.text();
                    addJoinCondition(out, added, joinSuggestion(clause, c.foreignKey(), distance));
                }
            });
        }
    }

    /** Alias visible trỏ tới bảng thật có trong schema (bỏ CTE/subquery, bảng lạ). */
    private static Map<String, TableInfo> realTableAliases(CaretScope scope) {
        Map<String, TableInfo> out = new LinkedHashMap<>();
        scope.visibleAliases().forEach((alias, tableName) -> {
            TableInfo t = scope.visibleDerivedScopes().containsKey(alias) ? null : SchemaIndex.findTable(tableName);
            if (t != null) {
                out.put(alias, t);
            }
        });
        return out;
    }

    private record JoinCondition(String text, boolean foreignKey) {
    }

    /** Điều kiện nối {@code left} (vế trái) với {@code right}: khoá ngoại trước, rồi đoán theo tên. */
    private static List<JoinCondition> joinConditions(TableInfo left, String leftAlias, TableInfo right, String rightAlias) {
        List<JoinCondition> out = new ArrayList<>();
        // không dùng else: khoá ngoại tự tham chiếu (employees.manager_id -> employees.id) khớp cả 2 chiều
        for (ForeignKey fk : SchemaIndex.foreignKeys) {
            if (fk.fromTable().equals(left.fullName()) && fk.toTable().equals(right.fullName())) {
                out.add(new JoinCondition(conditionText(leftAlias, fk.fromColumns(), rightAlias, fk.toColumns()), true));
            }
            if (fk.fromTable().equals(right.fullName()) && fk.toTable().equals(left.fullName())) {
                out.add(new JoinCondition(conditionText(leftAlias, fk.toColumns(), rightAlias, fk.fromColumns()), true));
            }
        }
        for (String[] pair : nameBasedPairs(left, right)) {
            out.add(new JoinCondition(conditionText(leftAlias, List.of(pair[0]), rightAlias, List.of(pair[1])), false));
        }
        return out;
    }

    /** Bỏ điều kiện đã có - đoán theo tên thường trùng đúng khoá ngoại thật đã thêm trước. */
    private static void addJoinCondition(List<Suggestion> out, Set<String> added, Suggestion condition) {
        if (added.add(condition.getKey())) {
            out.add(condition);
        }
    }

    /** Hậu tố của cột mang tính định danh - chỉ những cột này mới được ghép theo tên trùng (không ghép "status", "name"...). */
    private static final List<String> IDENTIFIER_SUFFIXES = List.of("_id", "_code", "_key", "_no", "_uuid");

    /**
     * Cặp cột (của {@code left}, của {@code right}) đoán là cột nối, không cần khoá ngoại:
     * <ul>
     *   <li>quy ước {@code <tên bảng số ít>_id = id}, cả 2 chiều: orders.user_id = users.id; tên bảng có tiền tố
     *       thì thử cả từ cuối (faq_articles.folder_id = faq_folders.id);</li>
     *   <li>cột trùng tên có hậu tố định danh ({@link #IDENTIFIER_SUFFIXES}): a.contract_code = c.contract_code.
     *       "id" trùng tên không tính - id của 2 bảng khác nhau gần như không bao giờ là điều kiện nối.</li>
     * </ul>
     */
    static List<String[]> nameBasedPairs(TableInfo left, TableInfo right) {
        // so khớp không phân biệt hoa/thường, nhưng trả tên cột thật (Postgres có thể có cột "UserId")
        Map<String, String> leftColumns = columnNames(left);
        Map<String, String> rightColumns = columnNames(right);
        List<String[]> pairs = new ArrayList<>();
        if (rightColumns.containsKey("id")) {
            for (String stem : tableStems(right.name())) {
                if (leftColumns.containsKey(stem + "_id")) {
                    pairs.add(new String[]{leftColumns.get(stem + "_id"), rightColumns.get("id")});
                }
            }
        }
        if (leftColumns.containsKey("id")) {
            for (String stem : tableStems(left.name())) {
                if (rightColumns.containsKey(stem + "_id")) {
                    pairs.add(new String[]{leftColumns.get("id"), rightColumns.get(stem + "_id")});
                }
            }
        }
        // cùng 1 bảng (self-join) thì cột trùng tên chính là 1 cột - "s1.dept_id = s.dept_id" vô nghĩa
        boolean selfJoin = left.fullName().equals(right.fullName());
        leftColumns.forEach((lower, actual) -> {
            if (!selfJoin && rightColumns.containsKey(lower) && IDENTIFIER_SUFFIXES.stream().anyMatch(lower::endsWith)) {
                pairs.add(new String[]{actual, rightColumns.get(lower)});
            }
        });
        return pairs;
    }

    /** tên chữ thường -> tên thật */
    private static Map<String, String> columnNames(TableInfo table) {
        Map<String, String> names = new LinkedHashMap<>();
        table.columns().forEach(c -> names.putIfAbsent(c.name().toLowerCase(), c.name()));
        return names;
    }

    /** "users" -> [user], "faq_folders" -> [faq_folder, folder], "categories" -> [category]. */
    private static Set<String> tableStems(String tableName) {
        String name = tableName.toLowerCase();
        Set<String> stems = new LinkedHashSet<>();
        stems.add(singular(name));
        int underscore = name.lastIndexOf('_');
        if (underscore > 0 && underscore < name.length() - 1) {
            stems.add(singular(name.substring(underscore + 1)));
        }
        return stems;
    }

    private static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 3) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("sses") || word.endsWith("xes") || word.endsWith("ches") || word.endsWith("shes")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 1) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static String conditionText(String leftAlias, List<String> leftColumns, String rightAlias, List<String> rightColumns) {
        StringJoiner condition = new StringJoiner(" and ");
        for (int i = 0; i < leftColumns.size(); i++) {
            condition.add(leftAlias + "." + Identifiers.quote(leftColumns.get(i), SchemaIndex.dialect)
                    + " = " + rightAlias + "." + Identifiers.quote(rightColumns.get(i), SchemaIndex.dialect));
        }
        return condition.toString();
    }

    private static Suggestion joinSuggestion(String text, boolean foreignKey, int distance) {
        Suggestion s = Suggestion.of(text, SuggestionType.JOIN, foreignKey ? "foreign key" : "by name")
                .withScopeDistance(distance);
        if (!foreignKey) {
            s.setOrder(SuggestionType.JOIN.order() + 1); // sau khoá ngoại thật, vẫn trước cột/bảng (order >= 2)
        }
        return s;
    }

    /**
     * "cột = |" / "cột <> |" với cột kiểu enum: gợi ý từng giá trị enum dạng chuỗi ('active'), đúng thứ tự khai báo.
     * Cột tìm theo "alias.cột" nếu có, không thì cột cùng tên của bảng visible đầu tiên có nó.
     *
     * @param isComparison token đứng ngay trước caret có phải toán tử so sánh không
     */
    public static void addEnumValues(List<Suggestion> out, CaretScope scope, TokenStream tokens, int caretTokenIndex,
                                     java.util.function.IntPredicate isComparison, int dotType) {
        int op = previousRealIndex(tokens, caretTokenIndex);
        if (op < 0 || !isComparison.test(tokens.get(op).getType())) {
            return;
        }
        int columnIdx = previousRealIndex(tokens, op);
        if (columnIdx < 0) {
            return;
        }
        String column = tokens.get(columnIdx).getText();
        int dot = previousRealIndex(tokens, columnIdx);
        String qualifier = dot >= 0 && tokens.get(dot).getType() == dotType && previousRealIndex(tokens, dot) >= 0
                ? tokens.get(previousRealIndex(tokens, dot)).getText() : null;

        String dataType = null;
        for (var e : realTableAliases(scope).entrySet()) {
            if (qualifier != null && !qualifier.equalsIgnoreCase(e.getKey())) {
                continue;
            }
            for (ColumnInfo c : e.getValue().columns()) {
                if (c.name().equalsIgnoreCase(Identifiers.normalize(column, SchemaIndex.dialect))) {
                    dataType = c.dataType();
                    break;
                }
            }
            if (dataType != null) {
                break;
            }
        }
        List<String> values = dataType == null ? null : SchemaIndex.enumValues.get(dataType);
        if (values != null) {
            for (String v : values) {
                out.add(Suggestion.of("'" + v.replace("'", "''") + "'", SuggestionType.VALUE, dataType));
            }
        }
    }

    /**
     * Tham số chỉ nhận vài chuỗi cố định: hàm (chữ thường) -> vị trí tham số (từ 0) -> giá trị. Chỉ Postgres có
     * catalog hàm thật; danh sách lấy theo tài liệu Postgres (field của date_trunc/date_part).
     */
    private static final List<String> PG_DATE_FIELDS = List.of("microseconds", "milliseconds", "second", "minute",
            "hour", "day", "week", "month", "quarter", "year", "decade", "century", "millennium");
    private static final Map<String, Map<Integer, List<String>>> PG_ARGUMENT_VALUES = Map.of(
            "date_trunc", Map.of(0, PG_DATE_FIELDS),
            "date_part", Map.of(0, PG_DATE_FIELDS));

    /** "date_trunc(|": gợi ý 'day', 'month'... cho tham số chỉ nhận chuỗi cố định (xem {@link #PG_ARGUMENT_VALUES}). */
    public static void addPostgresArgumentValues(List<Suggestion> out, String sql, int cursor) {
        var call = com.sqlctx.completion.hint.InputHints.callAt(sql, cursor, com.sqlctx.schema.Dialect.POSTGRES);
        List<String> values = call == null ? null
                : PG_ARGUMENT_VALUES.getOrDefault(call.function(), Map.of()).get(call.argumentIndex());
        if (values != null) {
            values.forEach(v -> out.add(Suggestion.of("'" + v + "'", SuggestionType.VALUE, call.function())));
        }
    }

    /**
     * "select *|": 1 gợi ý thay "*" bằng mọi cột của các mục FROM ở scope hiện tại ("u.id, u.name, o.total"; chỉ 1
     * bảng thì tên cột trần).
     */
    public static void addStarExpansion(List<Suggestion> out, CaretScope scope, String sql, int cursor) {
        if (!com.sqlctx.completion.input.CompletionInputPreparer.isSelectListStar(sql, cursor)) {
            return;
        }
        String qualifier = com.sqlctx.completion.input.CompletionInputPreparer.starQualifier(sql, cursor);
        // đúng thứ tự trong FROM (như cột "*" thật trả về) - thứ tự alias trong scope là thứ tự builder đăng ký
        String afterStar = sql.substring(cursor).toLowerCase();
        List<String> aliases = scope.visibleAliases().keySet().stream()
                .filter(a -> qualifier == null ? scope.aliasDistances().getOrDefault(a, 0) == 0 : a.equalsIgnoreCase(qualifier))
                .sorted(Comparator.comparingInt(a -> wordPosition(afterStar, a.toLowerCase())))
                .toList();
        List<String> columns = new ArrayList<>();
        for (String alias : aliases) {
            columnsOf(alias, scope.visibleAliases().get(alias), scope.visibleDerivedScopes().get(alias), null)
                    .forEach(c -> columns.add(aliases.size() == 1 && qualifier == null
                            ? c.getKey().substring(c.getKey().indexOf('.') + 1) : c.getKey()));
        }
        if (columns.isEmpty()) {
            return;
        }
        String key = String.join(", ", columns);
        if (qualifier != null) {
            // chỉ thay "*": "u." đã có trong câu nên cột đầu viết trần ("u.*" -> "u." + "id, u.name, u.email")
            key = key.substring(key.indexOf('.') + 1);
        }
        out.add(Suggestion.of(key, SuggestionType.COLUMNS, columns.size() + " columns"));
    }

    /** Vị trí đầu tiên {@code word} đứng như 1 từ riêng trong {@code text}; không có thì MAX_VALUE. */
    private static int wordPosition(String text, String word) {
        var m = java.util.regex.Pattern.compile("(?<![\\w$])" + java.util.regex.Pattern.quote(word) + "(?![\\w$])").matcher(text);
        return m.find() ? m.start() : Integer.MAX_VALUE;
    }

    /** Hàm gộp phổ biến của Postgres/Oracle - biểu thức chứa chúng không đưa vào GROUP BY. */
    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max", "array_agg", "string_agg",
            "json_agg", "jsonb_agg", "json_object_agg", "bool_and", "bool_or", "every", "listagg", "median", "stddev",
            "variance", "collect", "xmlagg", "percentile_cont", "percentile_disc", "mode");

    /**
     * "group by |": 1 gợi ý gồm mọi biểu thức của SELECT (cùng câu truy vấn) không chứa hàm gộp, bỏ alias cột
     * ("u.name as n" -> "u.name") - đúng thứ GROUP BY cần để câu chạy được.
     */
    public static void addGroupByExpansion(List<Suggestion> out, TokenStream tokens, int caretTokenIndex) {
        int by = previousRealIndex(tokens, caretTokenIndex);
        int group = by < 0 ? -1 : previousRealIndex(tokens, by);
        if (group < 0 || !tokens.get(by).getText().equalsIgnoreCase("by") || !tokens.get(group).getText().equalsIgnoreCase("group")) {
            return;
        }
        // SELECT của cùng câu: ngược từ GROUP, cùng độ sâu ngoặc
        int select = -1;
        int depth = 0;
        for (int i = group - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            String text = t.getText();
            if (text.equals(")")) {
                depth++;
            } else if (text.equals("(")) {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (depth == 0 && text.equalsIgnoreCase("select")) {
                select = i;
                break;
            }
        }
        if (select < 0) {
            return;
        }
        List<String> items = new ArrayList<>();
        List<Token> item = new ArrayList<>();
        depth = 0;
        for (int i = select + 1; i < group; i++) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            String text = t.getText();
            if (depth == 0 && (text.equalsIgnoreCase("from") || text.equals(","))) {
                addGroupItem(items, item);
                item = new ArrayList<>();
                if (text.equalsIgnoreCase("from")) {
                    break;
                }
                continue;
            }
            if (text.equals("(")) {
                depth++;
            } else if (text.equals(")")) {
                depth--;
            }
            if (!(item.isEmpty() && (text.equalsIgnoreCase("distinct") || text.equalsIgnoreCase("all")))) {
                item.add(t);
            }
        }
        if (!items.isEmpty()) {
            out.add(Suggestion.of(String.join(", ", items), SuggestionType.COLUMNS, "non-aggregated"));
        }
    }

    /** Thêm 1 mục SELECT vào GROUP BY nếu không phải "*", không chứa hàm gộp; bỏ "AS alias" / alias trần cuối. */
    private static void addGroupItem(List<String> items, List<Token> item) {
        if (item.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < item.size(); i++) {
            if (AGGREGATES.contains(item.get(i).getText().toLowerCase()) && item.get(i + 1).getText().equals("(")) {
                return;
            }
        }
        int end = item.size();
        if (end >= 3 && item.get(end - 2).getText().equalsIgnoreCase("as")) {
            end -= 2;
        } else if (end >= 2 && isBareWord(item.get(end - 1)) && isBareWord(item.get(end - 2))) {
            end -= 1; // "u.name n" -> "u.name"
        }
        StringBuilder text = new StringBuilder();
        Token previous = null;
        for (int i = 0; i < end; i++) {
            Token t = item.get(i);
            // giữ khoảng trắng gốc giữa 2 token (không nối "a + b" thành "a+b", không tách "u.name")
            if (previous != null && t.getStartIndex() > previous.getStopIndex() + 1) {
                text.append(' ');
            }
            text.append(t.getText());
            previous = t;
        }
        String expr = text.toString();
        if (!expr.equals("*") && !expr.endsWith(".*") && !expr.matches("'.*'|\\d+(\\.\\d+)?")) {
            items.add(expr);
        }
    }

    private static boolean isBareWord(Token t) {
        String text = t.getText();
        return !text.isEmpty() && (Character.isLetter(text.charAt(0)) || text.charAt(0) == '_' || text.charAt(0) == '"');
    }

    public static void addDataTypes(List<Suggestion> out) {
        SchemaIndex.dataTypes.forEach(t -> out.add(Suggestion.of(t, SuggestionType.DATATYPE, t)));
    }

    /** Preferred rule {@code ruleId} khớp tại caret qua đường có {@code ancestor}. */
    public static boolean hasAncestor(CandidatesResult candidates, int ruleId, int ancestor) {
        List<RuleCallStack.RuleFrame> path = candidates.rules.get(ruleId);
        return path != null && path.stream().anyMatch(f -> f.ruleId() == ancestor);
    }

    /** Preferred rule {@code ruleId} khớp tại caret với rule cha trực tiếp là {@code parent}. */
    public static boolean hasParent(CandidatesResult candidates, int ruleId, int parent) {
        List<RuleCallStack.RuleFrame> path = candidates.rules.get(ruleId);
        return path != null && !path.isEmpty() && path.get(path.size() - 1).ruleId() == parent;
    }

    /** Loại token thật (bỏ hidden channel) đứng ngay trước caret; -1 nếu không có. */
    public static int previousTokenType(TokenStream tokens, int caretTokenIndex) {
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.getChannel() == Token.DEFAULT_CHANNEL) {
                return t.getType();
            }
        }
        return -1;
    }

    /**
     * Cột nhìn thấy tại caret, dạng "alias.cột": của {@link CaretScope#qualifier()} nếu caret đứng sau "alias.",
     * không thì của mọi alias visible.
     *
     * @param extra cột riêng của dialect cho mỗi bảng thật; null nếu không cần
     */
    public static void addColumns(List<Suggestion> out, CaretScope scope, ExtraColumns extra) {
        String qualifier = scope.qualifier();
        if (qualifier != null) {
            String table = scope.qualifierResolvesTo() != null ? scope.qualifierResolvesTo() : qualifier;
            out.addAll(columnsOf(qualifier, table, scope.qualifierDerivedScope(), extra));
        } else {
            scope.visibleAliases().forEach((alias, table) -> {
                int distance = scope.aliasDistances().getOrDefault(alias, 0);
                columnsOf(alias, table, scope.visibleDerivedScopes().get(alias), extra)
                        .forEach(c -> out.add(c.withScopeDistance(distance)));
            });
        }
    }

    /** Chỉ cột của bảng đích DML ({@link CaretScope#ddlTargetAlias()}) - vd vế trái SET của MERGE. */
    public static void addTargetColumns(List<Suggestion> out, CaretScope scope) {
        String alias = scope.ddlTargetAlias();
        String table = alias == null ? null : scope.visibleAliases().get(alias);
        if (table != null) {
            out.addAll(columnsOf(alias, table, scope.visibleDerivedScopes().get(alias), null));
        }
    }

    /** JOIN ... USING (|): chỉ cột có tên xuất hiện ở ít nhất 2 alias visible. */
    public static void addCommonColumns(List<Suggestion> out, CaretScope scope) {
        Map<String, List<Suggestion>> perAlias = new LinkedHashMap<>();
        scope.visibleAliases().forEach((alias, table) ->
                perAlias.put(alias, columnsOf(alias, table, scope.visibleDerivedScopes().get(alias), null)));
        Map<String, Integer> aliasCountByColumn = new HashMap<>();
        perAlias.values().forEach(cols -> cols.stream()
                .map(SuggestionSupport::bareColumnName)
                .distinct()
                .forEach(name -> aliasCountByColumn.merge(name, 1, Integer::sum)));
        perAlias.values().forEach(cols -> cols.stream()
                .filter(s -> aliasCountByColumn.get(bareColumnName(s)) >= 2)
                .forEach(out::add));
    }

    private static List<Suggestion> columnsOf(String alias, String table, Scope derived, ExtraColumns extra) {
        List<Suggestion> cols = new ArrayList<>();
        if (derived != null) {
            DerivedColumnExpander.addDerivedColumns(cols, alias, derived);
            return cols;
        }
        SchemaIndex.getColumnsOfTable(table)
                .forEach(c -> cols.add(Suggestion.of(alias + "." + SchemaIndex.columnName(c), SuggestionType.COLUMN, c.dataType())));
        if (extra != null) {
            extra.add(cols, alias, table);
        }
        return cols;
    }

    private static String bareColumnName(Suggestion column) {
        return column.getKey().substring(column.getKey().indexOf('.') + 1).toLowerCase();
    }
}
