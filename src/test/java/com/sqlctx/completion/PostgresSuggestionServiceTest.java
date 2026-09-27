package com.sqlctx.completion;

import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.suggestion.CompletionHistory;
import com.sqlctx.completion.suggestion.CompletionInputPreparer;
import com.sqlctx.completion.suggestion.postgresql.PostgresSuggestionService;
import com.sqlctx.schema.ColumnInfo;
import com.sqlctx.schema.SchemaInfo;
import com.sqlctx.schema.SchemaIndex;
import com.sqlctx.schema.TableInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test gợi ý SQL đi qua ĐÚNG đường thật production dùng
 * ({@code suggests(PrepareCompletionInput)}, có {@code SuggestFilter} rank/lọc) - KHÔNG gọi
 * overload thô bỏ qua bước lọc, vì đó là thứ người dùng thực sự thấy trên màn hình khi gõ.
 * Mỗi test viết kỳ vọng ĐÚNG trước (dựa trên schema fixture + ngữ nghĩa SQL), rồi mới assert -
 * không quan sát output thật rồi viết assertion khớp ngược lại.
 */
class PostgresSuggestionServiceTest {

    @BeforeAll
    static void setUpFixtureSchema() {
        var id = new ColumnInfo("id", "id", "int4", true);
        var name = new ColumnInfo("name", "name", "text", false);
        var email = new ColumnInfo("email", "email", "text", false);
        var customerId = new ColumnInfo("customer_id", "customer_id", "int4", false);
        var total = new ColumnInfo("total", "total", "numeric", false);
        var status = new ColumnInfo("status", "status", "text", false);
        var userId = new ColumnInfo("user_id", "user_id", "int4", false);
        var amount = new ColumnInfo("amount", "amount", "numeric", false);
        var price = new ColumnInfo("price", "price", "numeric", false);
        var quantity = new ColumnInfo("quantity", "quantity", "int4", false);
        var description = new ColumnInfo("description", "description", "text", false);

        var users = new TableInfo("public", "users", "table", List.of(id, name, email));
        var orders = new TableInfo("public", "orders", "table", List.of(id, customerId, total, status, userId));
        var contracts = new TableInfo("public", "contracts", "table", List.of(id, name, amount, status));
        var products = new TableInfo("public", "products", "table", List.of(id, name, price, quantity, description));
        var activeUsers = new TableInfo("public", "active_users", "view", List.of(id, name));

        var publicSchema = new SchemaInfo("public", List.of(users, orders, contracts, products, activeUsers));
        SchemaIndex.schemas = List.of(publicSchema);
        SchemaIndex.tableIndex = new java.util.HashMap<>(Map.of(
                "public.users", users, "users", users,
                "public.orders", orders, "orders", orders,
                "public.contracts", contracts, "contracts", contracts,
                "public.products", products, "products", products
        ));
        SchemaIndex.tableIndex.put("public.active_users", activeUsers);
        SchemaIndex.tableIndex.put("active_users", activeUsers);
        SchemaIndex.schemaTableIndex = new java.util.HashMap<>(Map.of(
                "public.users", users, "public.orders", orders,
                "public.contracts", contracts, "public.products", products
        ));
        SchemaIndex.schemaTableIndex.put("public.active_users", activeUsers);
        SchemaIndex.functions = List.of("count", "sum", "avg", "now");
        SchemaIndex.dataTypes = List.of("int4", "text", "numeric", "bool", "timestamp");
        SchemaIndex.roles = List.of("app_reader", "app_writer", "postgres");
    }

    @BeforeEach
    void resetHistory() {
        // Không có bước này, kết quả xếp hạng phụ thuộc vào lịch sử dùng THẬT của người chạy
        // test trên máy đó (~/.sqlctx/completion_history.properties) - khác nhau giữa các máy.
        CompletionHistory.resetForTests();
    }

    // =====================================================================
    // Helper - gọi ĐÚNG đường production (có SuggestFilter)
    // =====================================================================

    private static List<Suggestion> suggest(String rawWithCursor) {
        int cursor = rawWithCursor.indexOf('|');
        String sql = rawWithCursor.substring(0, cursor) + rawWithCursor.substring(cursor + 1);
        var input = CompletionInputPreparer.buildInput(sql, cursor);
        return PostgresSuggestionService.suggests(input);
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

    /** Assert đúng TOÀN BỘ tập key của 1 type, không thừa không thiếu (bắt được noise lẫn vào). */
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

    private static void assertExactTables(List<Suggestion> list, String... expectedKeys) {
        assertExactKeysOfType(list, "table", expectedKeys);
    }

    private static void assertExactRoles(List<Suggestion> list, String... expectedKeys) {
        assertExactKeysOfType(list, "role", expectedKeys);
    }

    // =====================================================================
    // A. Prefix ranking thật - đối tượng schema thật phải thắng keyword chung
    // chung khi cả 2 cùng match prefix (đây chính là bug đã phát hiện+sửa
    // trong SuggestFilter.matchWord() khi viết bộ test này).
    // =====================================================================

    @Test
    @DisplayName("Gõ tên bảng có thật ('us') phải thắng keyword chung ('user') dù cả 2 cùng match prefix")
    void realTableBeatsGenericKeywordOnPrefix() {
        var result = suggest("select * from us|");
        assertEquals("public.users", firstKey(result));
        assertEquals("table", firstType(result));
    }

    @Test
    @DisplayName("Gõ tên cột không alias ('i') phải thắng keyword ('int'/'integer') dù cả 2 cùng match prefix")
    void realColumnBeatsGenericKeywordOnPrefix() {
        var result = suggest("select * from users where i|");
        assertEquals("users.id", firstKey(result));
        assertEquals("column", firstType(result));
    }

    @Test
    @DisplayName("UPDATE ... SET <cột>: cột thật phải đứng đầu, không phải keyword")
    void updateSetPrefixRanksRealColumnFirst() {
        var result = suggest("update users set na|");
        assertEquals("users.name", firstKey(result));
        assertEquals("column", firstType(result));
    }

    @Test
    @DisplayName("Không phân biệt hoa/thường: 'USE' vẫn tìm ra bảng 'users' (SQL identifier không phân biệt hoa thường)")
    void tableMatchIsCaseInsensitive() {
        var result = suggest("select * from USE|");
        assertEquals("public.users", firstKey(result));
    }

    // =====================================================================
    // B. Fuzzy/gõ thiếu chữ (không phải transposition - thuật toán là
    // subsequence, không phải edit-distance) vẫn phải tìm ra đúng bảng.
    // =====================================================================

    @Test
    @DisplayName("Gõ thiếu 1 ký tự giữa từ ('usrs' thiếu 'e') vẫn phải gợi ý ra 'users' (fuzzy subsequence)")
    void fuzzySubsequenceStillFindsRealTable() {
        var result = suggest("select * from usrs|");
        assertTrue(hasKeyOfType(result, "public.users", "table"),
                "Gõ 'usrs' (thiếu chữ 'e') phải vẫn khớp fuzzy ra 'users' - không được biến mất hoàn toàn");
    }

    @Test
    @DisplayName("Prefix không khớp bảng nào thật thì KHÔNG được tự bịa ra 1 bảng nào cả")
    void noFalsePositiveTableForUnrelatedPrefix() {
        var result = suggest("select * from zzqq|");
        assertTrue(keysOfType(result, "table").isEmpty(),
                "'zzqq' không phải subsequence của bảng nào trong fixture - không nên có gợi ý bảng nào");
    }

    // =====================================================================
    // C. Alias resolution - JOIN nhiều bảng, gợi ý cột đúng theo alias
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
    // D. Tự đặt tên alias + tránh trùng (thực tế: join lặp lại cùng 1 bảng,
    // hoặc nhiều bảng cùng chữ cái đầu)
    // =====================================================================

    @Test
    @DisplayName("Gợi ý alias mặc định cho 1 bảng chưa dùng lần nào: 'contracts' -> 'c'")
    void firstAliasSuggestionUsesFirstLetter() {
        var result = suggest("select * from contracts as |");
        assertTrue(hasKeyOfType(result, "c", "alias"));
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("Alias 'c' đã dùng - lần 2 phải là 'c1', không lặp lại 'c'")
    void secondAliasCollisionAvoidedWithSuffix1() {
        var result = suggest("select * from contracts c join contracts as |");
        assertTrue(hasKeyOfType(result, "c1", "alias"));
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("Alias 'c' và 'c1' đã dùng - lần 3 phải là 'c2' (vòng lặp tránh trùng phải tăng đúng)")
    void thirdAliasCollisionAvoidedWithSuffix2() {
        var result = suggest("select * from contracts c join contracts c1 join contracts as |");
        assertTrue(hasKeyOfType(result, "c2", "alias"));
        assertEquals(1, result.size());
    }

    // =====================================================================
    // E. Gõ có schema-qualify (dot-mode) - chỉ hiện đúng bảng khớp trong
    // đúng schema đó.
    // =====================================================================

    @Test
    @DisplayName("'public.us|' (đã gõ rõ schema) phải xếp 'public.users' lên đầu (prefix match thật, hơn fuzzy)")
    void schemaQualifiedPrefixRanksRealTableFirst() {
        var result = suggest("select * from public.us|");
        assertEquals("public.users", firstKey(result));
        assertEquals("table", firstType(result));
        // Mọi bảng match được đều phải thuộc đúng schema "public" đã gõ - không lẫn schema khác.
        keysOfType(result, "table").forEach(k -> assertTrue(k.startsWith("public."),
                () -> "'" + k + "' không thuộc schema 'public' đã gõ rõ"));
    }

    // =====================================================================
    // F. INSERT/UPDATE - gợi ý cột thật, không lẫn keyword
    // =====================================================================

    @Test
    @DisplayName("INSERT INTO orders (...): danh sách cột phải ĐÚNG các cột thật của orders, không thiếu không thừa")
    void insertColumnListSuggestsExactRealColumns() {
        var result = suggest("insert into orders (|");
        assertExactColumns(result, "orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id");
    }

    @Test
    @DisplayName("UPDATE ... SET a, |: vị trí cột thứ 2 trong SET vẫn phải gợi ý đúng cột thật của bảng")
    void updateSetSecondColumnStillSuggestsRealColumns() {
        var result = suggest("update users set name = 'x', |");
        assertExactColumns(result, "users.id", "users.name", "users.email");
    }

    // =====================================================================
    // G. ORDER BY - sau khi đã chọn xong 1 cột (có khoảng trắng), KHÔNG còn
    // gợi ý cột nữa (đang chờ ASC/DESC/dấu phẩy/kết thúc câu).
    // =====================================================================

    @Test
    @DisplayName("'order by name |' (đã gõ xong 1 cột) - KHÔNG còn gợi ý cột nào nữa, chỉ còn ASC/DESC")
    void orderByNoLongerSuggestsColumnsAfterCompleteElement() {
        var result = suggest("select * from users order by name |");
        assertTrue(keysOfType(result, "column").isEmpty(),
                "Đã gõ xong 1 cột trong ORDER BY (có khoảng trắng sau) - không nên còn gợi ý cột nào nữa");
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
                "Đã gõ xong 1 cột trong GROUP BY (có khoảng trắng sau) - không nên còn gợi ý cột nào nữa");
    }

    // =====================================================================
    // G2. CTE (WITH ... AS (...)) - alias của CTE phải resolve được cột y hệt
    // 1 bảng thật (thao tác rất thường gặp trong SQL thực tế).
    // =====================================================================

    @Test
    @DisplayName("WITH cte AS (...): alias của CTE phải gợi ý đúng cột của SELECT bên trong nó")
    void cteAliasColumnsResolveCorrectly() {
        var result = suggest("with recent as (select * from users) select * from recent r where r.|");
        assertExactColumns(result, "r.id", "r.name", "r.email");
    }

    // =====================================================================
    // G3. JOIN 2 bảng CÙNG có 1 cột trùng tên - gõ TRẦN (không alias) phải
    // thấy CẢ HAI dạng qualify (không tự ý chọn 1 bên, cũng không biến mất).
    // =====================================================================

    @Test
    @DisplayName("2 bảng join cùng có cột 'name' - gõ trần 'na' phải thấy CẢ HAI, đúng alias riêng")
    void ambiguousColumnAcrossJoinedTablesShowsBothQualified() {
        var result = suggest("select * from users u join contracts c on u.id = c.id where na|");
        assertExactColumns(result, "u.name", "c.name");
    }

    // =====================================================================
    // G4. Habit weight (CompletionHistory) - tầng duy nhất của SuggestFilter
    // không đi qua trong các test khác (các test khác đều reset về rỗng để
    // xếp hạng không phụ thuộc máy chạy) - test RIÊNG để đảm bảo cơ chế này
    // thật sự hoạt động khi CÓ lịch sử, không chỉ khi rỗng.
    // =====================================================================

    @Test
    @DisplayName("Cột được CHỌN nhiều lần gần đây phải lên hạng đầu, dù cùng tier/order với cột khác")
    void recentlyChosenColumnRanksFirstDueToHabitWeight() {
        for (int i = 0; i < 5; i++) {
            CompletionHistory.record("contracts.id");
        }
        var result = suggest("select * from contracts where |");
        assertEquals("contracts.id", firstKey(result));
    }

    // =====================================================================
    // H. Không được crash khi người dùng đang gõ dở (thực tế rất hay gặp)
    // =====================================================================

    @Test
    @DisplayName("Subquery chưa đóng ngoặc (đang gõ dở) vẫn phải gợi ý được cột bên trong nó")
    void unclosedSubqueryStillSuggestsColumns() {
        var result = suggest("select * from (select * from users u where u.|");
        assertExactColumns(result, "u.id", "u.name", "u.email");
    }

    @Test
    @DisplayName("Buffer rỗng - không crash, gợi ý các từ khoá bắt đầu câu")
    void emptyBufferSuggestsStatementStartKeywords() {
        var result = suggest("|");
        var keywords = result.stream().filter(s -> s.getType().label().equals("keyword"))
                .map(s -> s.getKey().toLowerCase()).collect(Collectors.toSet());
        assertTrue(keywords.contains("select"));
        assertTrue(keywords.contains("insert into"));
    }

    @Test
    @DisplayName("Gõ 'x.' với 'x' không phải alias/bảng nào có thật - không crash, không suy đoán bừa")
    void dotAfterUnknownQualifierDoesNotCrash() {
        var result = suggest("select * from x.|");
        assertTrue(result.isEmpty(), "'x' không phải alias/bảng nào có thật - không nên bịa ra gợi ý nào");
    }

    // =====================================================================
    // I. VIEW - phải gợi ý đúng type "view" (khác "table"), và vẫn thắng
    // keyword khi prefix match thật.
    // =====================================================================

    @Test
    @DisplayName("Gõ prefix của 1 view ('active') phải ra type \"view\", xếp hạng đầu")
    void viewSuggestionHasViewType() {
        var result = suggest("select * from active|");
        assertEquals("public.active_users", firstKey(result));
        assertEquals("view", firstType(result));
    }

    // =====================================================================
    // J. Datatype - CAST/CREATE TABLE phải gợi ý đúng kiểu dữ liệu thật theo
    // prefix, không lẫn kiểu không tồn tại trong schema.
    // =====================================================================

    @Test
    @DisplayName("CAST(x AS t|): chỉ gợi ý đúng datatype có thật khớp prefix 't' (text/timestamp)")
    void castSuggestsMatchingDatatypes() {
        var result = suggest("select cast(id as t|) from users");
        var datatypes = keysOfType(result, "datatype");
        assertTrue(datatypes.contains("text"));
        assertTrue(datatypes.contains("timestamp"));
    }

    @Test
    @DisplayName("CREATE TABLE x (a t|): vị trí kiểu dữ liệu cột cũng phải gợi ý đúng datatype thật")
    void createTableColumnSuggestsMatchingDatatypes() {
        var result = suggest("create table x (a t|)");
        var datatypes = keysOfType(result, "datatype");
        assertTrue(datatypes.contains("text"));
        assertTrue(datatypes.contains("timestamp"));
    }

    // =====================================================================
    // K. 3 bảng JOIN cùng lúc - scope phải đúng, không rò rỉ cột bảng khác.
    // =====================================================================

    @Test
    @DisplayName("JOIN 3 bảng, gõ alias bảng thứ 3 phải CHỈ ra đúng cột của nó, không lẫn 2 bảng kia")
    void tripleJoinResolvesCorrectTableAmongThree() {
        var result = suggest(
                "select * from users u join orders o on o.user_id = u.id join contracts c on c.id = u.id where c.|");
        assertExactColumns(result, "c.id", "c.name", "c.amount", "c.status");
    }

    // =====================================================================
    // L. UPDATE...FROM / DELETE...USING (cú pháp Postgres thật, không phải
    // chuẩn SQL - hay dùng để update/xoá theo điều kiện join với bảng khác).
    // =====================================================================

    @Test
    @DisplayName("UPDATE ... FROM u WHERE ... AND u.|: phải resolve đúng cột bảng trong FROM")
    void updateFromResolvesJoinedTableColumns() {
        var result = suggest("update orders o set status = 'x' from users u where u.id = o.user_id and u.|");
        assertExactColumns(result, "u.id", "u.name", "u.email");
    }

    @Test
    @DisplayName("DELETE ... USING u WHERE ... AND u.|: phải resolve đúng cột bảng trong USING")
    void deleteUsingResolvesJoinedTableColumns() {
        var result = suggest("delete from orders o using users u where u.id = o.user_id and u.|");
        assertExactColumns(result, "u.id", "u.name", "u.email");
    }

    // =====================================================================
    // M. ROLE/USER (GRANT/REVOKE/ALTER ROLE/OWNER TO/DROP ROLE...) - phải
    // gợi ý đúng role thật (SchemaIndex.roles), KHÔNG lẫn hàng trăm keyword
    // rác (bug thật: PREFERRED_RULES thiếu "rolespec", đã sửa trong
    // PostgresSyntacticAnalyzer - xem test này như bằng chứng không tái phát).
    // =====================================================================

    @Test
    @DisplayName("GRANT ... TO |: đúng 3 role thật, không còn 458 keyword rác")
    void grantToSuggestsExactRoles() {
        var result = suggest("grant select on public.users to |");
        assertExactRoles(result, "app_reader", "app_writer", "postgres");
    }

    @Test
    @DisplayName("REVOKE ... FROM |: đúng 3 role thật")
    void revokeFromSuggestsExactRoles() {
        var result = suggest("revoke select on public.users from |");
        assertExactRoles(result, "app_reader", "app_writer", "postgres");
    }

    @Test
    @DisplayName("ALTER GROUP g ADD USER |: đúng 3 role thật")
    void alterGroupAddUserSuggestsExactRoles() {
        var result = suggest("alter group grp1 add user |");
        assertExactRoles(result, "app_reader", "app_writer", "postgres");
    }

    @Test
    @DisplayName("ALTER AGGREGATE ... OWNER TO |: đúng 3 role thật")
    void alterOwnerToSuggestsExactRoles() {
        var result = suggest("alter aggregate agg1(int4) owner to |");
        assertExactRoles(result, "app_reader", "app_writer", "postgres");
    }

    @Test
    @DisplayName("DROP ROLE |: đúng 3 role thật")
    void dropRoleSuggestsExactRoles() {
        var result = suggest("drop role |");
        assertExactRoles(result, "app_reader", "app_writer", "postgres");
    }

    @Test
    @DisplayName("DROP OWNED BY |: đúng 3 role thật")
    void dropOwnedBySuggestsExactRoles() {
        var result = suggest("drop owned by |");
        assertExactRoles(result, "app_reader", "app_writer", "postgres");
    }

    @Test
    @DisplayName("GRANT role1 TO |: đúng 3 role thật (grant 1 role cho role khác)")
    void grantRoleToSuggestsExactRoles() {
        var result = suggest("grant app_reader to |");
        assertExactRoles(result, "app_reader", "app_writer", "postgres");
    }

    @Test
    @DisplayName("REASSIGN OWNED BY x TO |: đúng 3 role thật")
    void reassignOwnedToSuggestsExactRoles() {
        var result = suggest("reassign owned by app_reader to |");
        assertExactRoles(result, "app_reader", "app_writer", "postgres");
    }

    @Test
    @DisplayName("CREATE TABLESPACE ... OWNER |: đúng 3 role thật")
    void createTablespaceOwnerSuggestsExactRoles() {
        var result = suggest("create tablespace ts2 owner |");
        assertExactRoles(result, "app_reader", "app_writer", "postgres");
    }

    // =====================================================================
    // N. func_name/type_function_name (tham chiếu hàm/kiểu ĐÃ CÓ) - trước đây
    // matchedRuleNames báo đúng nhưng không ai nối để thêm gợi ý thật, rơi
    // xuống tràn keyword rác (CREATE FUNCTION RETURNS, DROP FUNCTION/AGGREGATE).
    // =====================================================================

    @Test
    @DisplayName("CREATE FUNCTION ... RETURNS |: phải thấy đúng datatype thật, không tràn 396 keyword rác")
    void createFunctionReturnsSuggestsRealDatatypes() {
        var result = suggest("create function f1() returns |");
        var datatypes = keysOfType(result, "datatype");
        assertTrue(datatypes.contains("int4"));
        assertTrue(datatypes.contains("text"));
        assertTrue(result.size() < 60, "Vẫn còn quá nhiều gợi ý (" + result.size() + ") - nhiễu chưa được dọn");
    }

    @Test
    @DisplayName("DROP FUNCTION |: phải thấy đúng hàm thật")
    void dropFunctionSuggestsRealFunctions() {
        var result = suggest("drop function |");
        assertTrue(keysOfType(result, "function").containsAll(List.of("count", "sum", "avg", "now")));
    }

    // =====================================================================
    // O. ALTER TABLE ADD COLUMN - grammar thật: columnDef: colid typename ...
    // (TUẦN TỰ - tên cột MỚI trước, kiểu dữ liệu SAU). Bug thật: trước đây
    // gợi ý datatype NGAY cả khi tên cột mới CHƯA được gõ (colid chưa xong).
    // =====================================================================

    @Test
    @DisplayName("ADD COLUMN |: tên cột MỚI chưa gõ - KHÔNG được gợi ý datatype (đứng trước colid)")
    void addColumnWithoutNameYetSuggestsNoDatatype() {
        var result = suggest("alter table users add column |");
        assertEquals(List.of(), keysOfType(result, "datatype"),
                "Chưa gõ tên cột mới (colid) thì chưa tới lượt gợi ý datatype (typename) - grammar là colid typename tuần tự");
    }

    @Test
    @DisplayName("ADD COLUMN newcol |: đã gõ xong tên cột mới - PHẢI gợi ý đúng datatype thật")
    void addColumnWithNameTypedSuggestsRealDatatype() {
        var result = suggest("alter table users add column newcol |");
        var datatypes = keysOfType(result, "datatype");
        assertTrue(datatypes.contains("int4"));
        assertTrue(datatypes.contains("text"));
    }
}
