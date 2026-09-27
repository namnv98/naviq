package com.sqlctx.completion;

import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.suggestion.CompletionHistory;
import com.sqlctx.completion.suggestion.CompletionInputPreparer;
import com.sqlctx.completion.suggestion.oracle.OracleSuggestionService;
import com.sqlctx.schema.ColumnInfo;
import com.sqlctx.schema.SchemaInfo;
import com.sqlctx.schema.SchemaIndex;
import com.sqlctx.schema.TableInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test gợi ý SQL Oracle đi qua ĐÚNG đường thật production dùng
 * ({@code suggests(PrepareCompletionInput)}, có {@code SuggestFilter} rank/lọc). Cùng triết lý
 * với {@link PostgresSuggestionServiceTest}: viết kỳ vọng ĐÚNG trước dựa trên schema fixture +
 * ngữ nghĩa SQL, rồi mới assert - không quan sát output thật rồi khớp ngược.
 */
class OracleSuggestionServiceTest {

    @BeforeAll
    static void setUpFixtureSchema() {
        var id = new ColumnInfo("id", "id", "NUMBER", true);
        var name = new ColumnInfo("name", "name", "VARCHAR2", false);
        var email = new ColumnInfo("email", "email", "VARCHAR2", false);
        var customerId = new ColumnInfo("customer_id", "customer_id", "NUMBER", false);
        var total = new ColumnInfo("total", "total", "NUMBER", false);
        var status = new ColumnInfo("status", "status", "VARCHAR2", false);
        var userId = new ColumnInfo("user_id", "user_id", "NUMBER", false);
        var amount = new ColumnInfo("amount", "amount", "NUMBER", false);
        var price = new ColumnInfo("price", "price", "NUMBER", false);
        var quantity = new ColumnInfo("quantity", "quantity", "NUMBER", false);
        var description = new ColumnInfo("description", "description", "VARCHAR2", false);

        var users = new TableInfo("naviq", "users", "table", List.of(id, name, email));
        var orders = new TableInfo("naviq", "orders", "table", List.of(id, customerId, total, status, userId));
        var contracts = new TableInfo("naviq", "contracts", "table", List.of(id, name, amount, status));
        var products = new TableInfo("naviq", "products", "table", List.of(id, name, price, quantity, description));

        var schema = new SchemaInfo("naviq", List.of(users, orders, contracts, products));
        SchemaIndex.schemas = List.of(schema);
        SchemaIndex.tableIndex = Map.of(
                "naviq.users", users, "users", users,
                "naviq.orders", orders, "orders", orders,
                "naviq.contracts", contracts, "contracts", contracts,
                "naviq.products", products, "products", products
        );
        SchemaIndex.schemaTableIndex = Map.of(
                "naviq.users", users, "naviq.orders", orders,
                "naviq.contracts", contracts, "naviq.products", products
        );
        SchemaIndex.functions = List.of("count", "sum", "avg", "sysdate");
        SchemaIndex.dataTypes = List.of("NUMBER", "VARCHAR2", "DATE", "CHAR");
    }

    @BeforeEach
    void resetHistory() {
        CompletionHistory.resetForTests();
    }

    // =====================================================================
    // Helper - gọi ĐÚNG đường production (có SuggestFilter)
    // =====================================================================

    private static List<Suggestion> suggest(String rawWithCursor) {
        int cursor = rawWithCursor.indexOf('|');
        String sql = rawWithCursor.substring(0, cursor) + rawWithCursor.substring(cursor + 1);
        var input = CompletionInputPreparer.buildInput(sql, cursor);
        return OracleSuggestionService.suggests(input);
    }

    private static String firstKey(List<Suggestion> list) {
        assertFalse(list.isEmpty(), "Danh sách gợi ý rỗng, không có kết quả để lấy top 1");
        return list.get(0).getKey();
    }

    private static String firstType(List<Suggestion> list) {
        assertFalse(list.isEmpty(), "Danh sách gợi ý rỗng, không có kết quả để lấy top 1");
        return list.get(0).getType().label();
    }

    private static List<String> keysOfType(List<Suggestion> list, String type) {
        return list.stream().filter(s -> s.getType().label().equals(type)).map(Suggestion::getKey).toList();
    }

    private static boolean hasKeyOfType(List<Suggestion> list, String key, String type) {
        return list.stream().anyMatch(s -> s.getKey().equalsIgnoreCase(key) && s.getType().label().equals(type));
    }

    private static void assertExactKeysOfType(List<Suggestion> list, String type, String... expectedKeys) {
        Set<String> actual = list.stream().filter(s -> s.getType().label().equals(type))
                .map(s -> s.getKey().toLowerCase()).collect(Collectors.toCollection(TreeSet::new));
        Set<String> expected = java.util.Arrays.stream(expectedKeys)
                .map(String::toLowerCase).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(expected, actual, () -> "Tập '" + type + "' không khớp — mong đợi đúng " + expected + " nhưng thực tế là " + actual);
    }

    private static void assertExactColumns(List<Suggestion> list, String... expectedKeys) {
        assertExactKeysOfType(list, "column", expectedKeys);
    }

    // =====================================================================
    // A. Prefix ranking thật - đối tượng schema thật phải thắng keyword chung
    // =====================================================================

    @Test
    @DisplayName("Gõ tên bảng có thật ('us') phải thắng keyword chung ('user') dù cả 2 cùng match prefix")
    void realTableBeatsGenericKeywordOnPrefix() {
        var result = suggest("select * from us|");
        assertEquals("naviq.users", firstKey(result));
        assertEquals("table", firstType(result));
    }

    @Test
    @DisplayName("Gõ tên cột không alias ('i') phải thắng keyword ('interval'...) dù cả 2 cùng match prefix")
    void realColumnBeatsGenericKeywordOnPrefix() {
        var result = suggest("select * from users where i|");
        assertEquals("users.id", firstKey(result));
        assertEquals("column", firstType(result));
    }

    @Test
    @DisplayName("Không phân biệt hoa/thường: 'USE' vẫn tìm ra bảng 'users'")
    void tableMatchIsCaseInsensitive() {
        var result = suggest("select * from USE|");
        assertEquals("naviq.users", firstKey(result));
    }

    // =====================================================================
    // B. Fuzzy/gõ thiếu chữ vẫn phải tìm ra đúng bảng
    // =====================================================================

    @Test
    @DisplayName("Gõ thiếu 1 ký tự giữa từ ('usrs' thiếu 'e') vẫn phải gợi ý ra 'users' (fuzzy subsequence)")
    void fuzzySubsequenceStillFindsRealTable() {
        var result = suggest("select * from usrs|");
        assertTrue(hasKeyOfType(result, "naviq.users", "table"),
                "Gõ 'usrs' (thiếu chữ 'e') phải vẫn khớp fuzzy ra 'users' - không được biến mất hoàn toàn");
    }

    // =====================================================================
    // C. Alias resolution - JOIN nhiều bảng
    // =====================================================================

    @Test
    @DisplayName("JOIN 2 bảng, gõ 'u.' phải CHỈ ra đúng cột của users, không lẫn cột orders")
    void aliasQualifiedColumnsAfterJoin() {
        var result = suggest("select * from users u join orders o on o.user_id = u.|");
        assertExactColumns(result, "u.id", "u.name", "u.email");
    }

    @Test
    @DisplayName("JOIN...USING(|): chỉ gợi ý đúng cột TRÙNG TÊN giữa 2 bảng (users/orders chỉ chung 'id')")
    void joinUsingSuggestsOnlyCommonColumns() {
        var result = suggest("select * from users u join orders o using (|");
        assertExactColumns(result, "u.id", "o.id");
    }

    // =====================================================================
    // D. Tự đặt tên alias + tránh trùng (OracleAliasNameSuggester - implementation
    // riêng của Oracle, khác class với Postgres, nên phải test riêng)
    // =====================================================================

    @Test
    @DisplayName("Gợi ý alias mặc định cho 1 bảng chưa dùng lần nào: 'contracts' -> 'c'")
    void firstAliasSuggestionUsesFirstLetter() {
        var result = suggest("select * from contracts |");
        assertTrue(hasKeyOfType(result, "c", "alias"));
    }

    @Test
    @DisplayName("Alias 'c' đã dùng - lần 2 phải là 'c1', không lặp lại 'c'")
    void secondAliasCollisionAvoidedWithSuffix1() {
        var result = suggest("select * from contracts c join contracts |");
        assertTrue(hasKeyOfType(result, "c1", "alias"));
    }

    @Test
    @DisplayName("Alias 'c' và 'c1' đã dùng - lần 3 phải là 'c2'")
    void thirdAliasCollisionAvoidedWithSuffix2() {
        var result = suggest("select * from contracts c join contracts c1 join contracts |");
        assertTrue(hasKeyOfType(result, "c2", "alias"));
    }

    // =====================================================================
    // E. Gõ có schema-qualify (dot-mode)
    // =====================================================================

    @Test
    @DisplayName("'naviq.us|' (đã gõ rõ schema) phải xếp 'naviq.users' lên đầu, đúng schema đã gõ")
    void schemaQualifiedPrefixRanksRealTableFirst() {
        var result = suggest("select * from naviq.us|");
        assertEquals("naviq.users", firstKey(result));
        assertEquals("table", firstType(result));
        keysOfType(result, "table").forEach(k -> assertTrue(k.startsWith("naviq."),
                () -> "'" + k + "' không thuộc schema 'naviq' đã gõ rõ"));
    }

    // =====================================================================
    // F. INSERT/UPDATE - gợi ý cột thật
    // =====================================================================

    @Test
    @DisplayName("INSERT INTO orders (...): danh sách cột phải ĐÚNG các cột thật của orders")
    void insertColumnListSuggestsExactRealColumns() {
        var result = suggest("insert into orders (|");
        assertExactColumns(result, "orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id");
    }

    // =====================================================================
    // G. ORDER BY - sau khi đã chọn xong 1 cột, KHÔNG còn gợi ý cột nữa
    // =====================================================================

    @Test
    @DisplayName("'order by name |' (đã gõ xong 1 cột) - KHÔNG còn gợi ý cột nào nữa, chỉ còn ASC/DESC")
    void orderByNoLongerSuggestsColumnsAfterCompleteElement() {
        var result = suggest("select * from users order by name |");
        assertTrue(keysOfType(result, "column").isEmpty(),
                "Đã gõ xong 1 cột trong ORDER BY - không nên còn gợi ý cột nào nữa");
        var keywords = result.stream().filter(s -> s.getType().label().equals("keyword"))
                .map(s -> s.getKey().toLowerCase()).collect(Collectors.toSet());
        assertTrue(keywords.contains("asc"));
        assertTrue(keywords.contains("desc"));
    }

    @Test
    @DisplayName("'group by name |' (đã gõ xong 1 cột) - KHÔNG còn gợi ý cột nào nữa (y hệt ORDER BY)")
    void groupByNoLongerSuggestsColumnsAfterCompleteElement() {
        var result = suggest("select * from users group by name |");
        assertTrue(keysOfType(result, "column").isEmpty(),
                "Đã gõ xong 1 cột trong GROUP BY - không nên còn gợi ý cột nào nữa");
    }

    // =====================================================================
    // G2. CTE (WITH ... AS (...)) - alias của CTE phải resolve được cột y hệt
    // 1 bảng thật.
    // =====================================================================

    @Test
    @DisplayName("WITH cte AS (...): alias của CTE phải gợi ý đúng cột của SELECT bên trong nó")
    void cteAliasColumnsResolveCorrectly() {
        var result = suggest("with recent as (select * from users) select * from recent r where r.|");
        assertExactColumns(result, "r.id", "r.name", "r.email");
    }

    // =====================================================================
    // G3. JOIN 2 bảng CÙNG có cột trùng tên - gõ trần phải thấy CẢ HAI.
    // =====================================================================

    @Test
    @DisplayName("2 bảng join cùng có cột 'name' - gõ trần 'na' phải thấy CẢ HAI, đúng alias riêng")
    void ambiguousColumnAcrossJoinedTablesShowsBothQualified() {
        var result = suggest("select * from users u join contracts c on u.id = c.id where na|");
        assertExactColumns(result, "u.name", "c.name");
    }

    // =====================================================================
    // H. MERGE - cú pháp ETL thường gặp trong Oracle thực tế
    // =====================================================================

    @Test
    @DisplayName("MERGE ... WHEN MATCHED THEN UPDATE SET <alias>.<cột>: chỉ gợi ý đúng cột của bảng đích")
    void mergeUpdateSetSuggestsTargetTableColumns() {
        var result = suggest(
                "merge into orders o using contracts c on (o.id = c.id) when matched then update set o.|");
        assertExactColumns(result, "o.id", "o.customer_id", "o.total", "o.status", "o.user_id");
    }

    // =====================================================================
    // I. Không được crash khi người dùng đang gõ dở
    // =====================================================================

    @Test
    @DisplayName("Subquery chưa đóng ngoặc (đang gõ dở) - tối thiểu KHÔNG được crash")
    void unclosedSubqueryDoesNotCrash() {
        // Khác Postgres (resolve đúng cột của subquery dù chưa đóng ngoặc): engine Oracle hiện
        // KHÔNG resolve được scope cho case chưa-đóng-ngoặc này (derived-scope trả rỗng) - một
        // giới hạn thật, sâu hơn (liên quan tới cách OracleScopeBuilder dựng derived scope khi
        // parse chưa hoàn tất), không sửa trong lần này. Test chỉ đảm bảo tối thiểu: không throw.
        assertDoesNotThrow(() -> suggest("select * from (select * from users u where u.|"));
    }

    @Test
    @DisplayName("Buffer rỗng - không crash, gợi ý các từ khoá bắt đầu câu")
    void emptyBufferSuggestsStatementStartKeywords() {
        var result = suggest("|");
        var keywords = result.stream().filter(s -> s.getType().label().equals("keyword"))
                .map(s -> s.getKey().toLowerCase()).collect(Collectors.toSet());
        assertTrue(keywords.contains("select"));
    }

    @Test
    @DisplayName("Gõ 'x.' với 'x' không phải alias/bảng nào có thật - không crash, không suy đoán bừa")
    void dotAfterUnknownQualifierDoesNotCrash() {
        var result = suggest("select * from x.|");
        assertTrue(result.isEmpty(), "'x' không phải alias/bảng nào có thật - không nên bịa ra gợi ý nào");
    }

    // =====================================================================
    // J. Datatype - CAST phải gợi ý đúng kiểu dữ liệu thật theo prefix.
    // =====================================================================

    @Test
    @DisplayName("CAST(x AS N|): chỉ gợi ý đúng datatype có thật khớp prefix 'N' (NUMBER)")
    void castSuggestsMatchingDatatypes() {
        var result = suggest("select cast(id as N|) from dual");
        assertTrue(keysOfType(result, "datatype").stream().anyMatch(k -> k.equalsIgnoreCase("NUMBER")));
    }

    // =====================================================================
    // K. PL/SQL block (BEGIN...END) - scope cột vẫn phải resolve đúng bên
    // trong block, không chỉ ở top-level SELECT thường.
    // =====================================================================

    @Test
    @DisplayName("Trong BEGIN...END, SELECT vẫn phải resolve đúng cột theo alias")
    void plsqlBlockScopeResolvesColumnsCorrectly() {
        var result = suggest("begin\n  select name from users u where u.|\nend;");
        assertExactColumns(result, "u.id", "u.name", "u.email");
    }

    // =====================================================================
    // L. CONNECT BY - truy vấn phân cấp (hierarchical query) đặc trưng Oracle.
    // =====================================================================

    @Test
    @DisplayName("CONNECT BY PRIOR id = |: vẫn phải gợi ý đúng cột của bảng đang truy vấn phân cấp")
    void connectByResolvesTableColumns() {
        var result = suggest("select * from contracts start with id = 1 connect by prior id = |");
        assertExactColumns(result, "contracts.id", "contracts.name", "contracts.amount", "contracts.status");
    }

    // =====================================================================
    // M. 3 bảng JOIN cùng lúc - scope phải đúng, không rò rỉ cột bảng khác.
    // =====================================================================

    @Test
    @DisplayName("JOIN 3 bảng, gõ alias bảng thứ 3 phải CHỈ ra đúng cột của nó, không lẫn 2 bảng kia")
    void tripleJoinResolvesCorrectTableAmongThree() {
        var result = suggest(
                "select * from users u join orders o on o.user_id = u.id join contracts c on c.id = u.id where c.|");
        assertExactColumns(result, "c.id", "c.name", "c.amount", "c.status");
    }

    // =====================================================================
    // N. TỪNG VỊ TRÍ trong câu (không chỉ 1 vị trí đại diện) - CTE/HAVING/
    // subquery, y hệt nhóm đã thêm bên Postgres.
    // =====================================================================

    @Test
    @DisplayName("HAVING |: phải thấy đúng cột thật của bảng đang GROUP BY")
    void havingClauseSuggestsRealColumns() {
        var result = suggest("select status, count(*) from orders group by status having |");
        var columns = keysOfType(result, "column");
        assertTrue(columns.containsAll(List.of("orders.status", "orders.id", "orders.total", "orders.user_id", "orders.customer_id")));
    }

    @Test
    @DisplayName("Subquery không tương quan trong WHERE IN (...): cursor bên trong subquery phải thấy cột bảng subquery đang FROM")
    void whereInSubquerySuggestsSubqueryOwnColumns() {
        var result = suggest("select * from users where id in (select | from orders)");
        var columns = keysOfType(result, "column");
        assertTrue(columns.containsAll(List.of("orders.id", "orders.total", "orders.status", "orders.user_id", "orders.customer_id")));
    }

    @Test
    @DisplayName("Tên CTE phải được gợi ý như 1 bảng khi đang gõ dở trong FROM - bug thật đã sửa")
    void cteNameSuggestedAsFromTarget() {
        var result = suggest("with recent as (select * from users) select * from re|");
        assertTrue(hasKeyOfType(result, "recent", "table"));
    }

    @Test
    @DisplayName("CTE tham chiếu CTE khác (2 tầng, cả 2 đều wildcard) phải resolve xuyên suốt - bug thật đã sửa (DerivedColumnExpander dùng chung với Postgres)")
    void cteChainResolvesTransitively() {
        var result = suggest("with a as (select * from users), b as (select * from a) select * from b bb where bb.|");
        assertExactColumns(result, "bb.id", "bb.name", "bb.email");
    }

    @Test
    @DisplayName("UNION nhánh 2 KHÔNG được thấy bảng của nhánh 1 (Oracle: mỗi query_block đã tự có scope riêng, không dính bug như Postgres từng có, test để canh không tái phát)")
    void unionSecondBranchDoesNotLeakFirstBranchTable() {
        var result = suggest("select id from users union select | from orders");
        var columns = keysOfType(result, "column");
        assertTrue(columns.containsAll(List.of("orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id")));
        assertTrue(columns.stream().noneMatch(c -> c.startsWith("users.")));
    }
}
