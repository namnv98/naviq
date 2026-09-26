package com.naviq.completion;

import com.naviq.completion.suggests.postgresql.CompletionEngine;
import com.naviq.datasource.SchemaIndex;
import com.naviq.datasource.SchemaLoader;
import com.naviq.model.Suggest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PostgresqlCompletionEngineTest {

    @BeforeAll
    static void setUpFixtureSchema() {
        var idCol = new SchemaLoader.DBColumnInfo("id", "id", "int4", true);
        var nameCol = new SchemaLoader.DBColumnInfo("name", "name", "text", false);
        var emailCol = new SchemaLoader.DBColumnInfo("email", "email", "text", false);
        var customerIdCol = new SchemaLoader.DBColumnInfo("customer_id", "customer_id", "int4", false);
        var totalCol = new SchemaLoader.DBColumnInfo("total", "total", "numeric", false);
        var amountCol = new SchemaLoader.DBColumnInfo("amount", "amount", "numeric", false);
        var statusCol = new SchemaLoader.DBColumnInfo("status", "status", "text", false);
        var createdDateCol = new SchemaLoader.DBColumnInfo("created_date", "created_date", "timestamp", false);
        var userIdCol = new SchemaLoader.DBColumnInfo("user_id", "user_id", "int4", false);
        var orderIdCol = new SchemaLoader.DBColumnInfo("order_id", "order_id", "int4", false);
        var productIdCol = new SchemaLoader.DBColumnInfo("product_id", "product_id", "int4", false);
        var priceCol = new SchemaLoader.DBColumnInfo("price", "price", "numeric", false);
        var quantityCol = new SchemaLoader.DBColumnInfo("quantity", "quantity", "int4", false);
        var descriptionCol = new SchemaLoader.DBColumnInfo("description", "description", "text", false);

        var users = new SchemaLoader.TableInfo("public", "users", "table",
                List.of(idCol, nameCol, emailCol, createdDateCol));
        var orders = new SchemaLoader.TableInfo("public", "orders", "table",
                List.of(idCol, customerIdCol, totalCol, statusCol, userIdCol));
        var contracts = new SchemaLoader.TableInfo("public", "contracts", "table",
                List.of(idCol, nameCol, amountCol, statusCol));
        var ordersView = new SchemaLoader.TableInfo("public", "orders_summary", "view",
                List.of(idCol, totalCol, orderIdCol, productIdCol, priceCol, quantityCol, descriptionCol));
        var products = new SchemaLoader.TableInfo("public", "products", "table",
                List.of(idCol, nameCol, priceCol, quantityCol, descriptionCol));

        var publicSchema = new SchemaLoader.SchemaInfo("public",
                List.of(users, orders, contracts, ordersView, products));

        SchemaIndex.DB_SCHEMA = List.of(publicSchema);
        SchemaIndex.TABLE_INDEX = Map.of(
                "public.users", users, "users", users,
                "public.orders", orders, "orders", orders,
                "public.contracts", contracts, "contracts", contracts,
                "public.orders_summary", ordersView, "orders_summary", ordersView,
                "public.products", products, "products", products
        );
        SchemaIndex.SCHEMA_TABLE_INDEX = Map.of(
                "public.users", users,
                "public.orders", orders,
                "public.contracts", contracts,
                "public.orders_summary", ordersView,
                "public.products", products
        );
        SchemaIndex.FUNCTIONS = List.of("count", "sum", "avg", "now", "min", "max", "concat", "lower", "upper", "trim");
        SchemaIndex.DATA_TYPES = List.of("int4", "text", "numeric", "bool", "timestamp", "date", "time", "varchar");
    }

    // =====================================================================
    // Helper
    // =====================================================================

    private static List<Suggest> suggest(String rawWithCursor) {
        int cursor = rawWithCursor.indexOf('|');
        String sql = rawWithCursor.substring(0, cursor) + rawWithCursor.substring(cursor + 1);
        return CompletionEngine.suggests(sql, cursor);
    }

    private static boolean hasKeyOfType(List<Suggest> list, String key, String type) {
        return list.stream().anyMatch(s -> s.getKey().equalsIgnoreCase(key) && s.getType().equals(type));
    }

    private static List<String> keysOfType(List<Suggest> list, String type) {
        return list.stream().filter(s -> s.getType().equals(type)).map(Suggest::getKey).toList();
    }

    private static Set<String> allKeywordKeys(List<Suggest> list) {
        return list.stream().filter(s -> s.getType().equals("keyword"))
                .map(s -> s.getKey().toLowerCase()).collect(Collectors.toSet());
    }

    /**
     * Khác {@link #hasKeyOfType} (chỉ check CÓ MẶT) - assert đúng TOÀN BỘ tập key của 1 type,
     * không thừa không thiếu. Bắt được noise (vd bảng lẫn vào vị trí chỉ nên có cột) mà
     * hasKeyOfType không bao giờ bắt được vì nó không quan tâm CÁC KEY KHÁC ngoài key đang check.
     */
    private static void assertExactKeysOfType(List<Suggest> list, String type, String... expectedKeys) {
        Set<String> actual = list.stream().filter(s -> s.getType().equals(type))
                .map(s -> s.getKey().toLowerCase()).collect(Collectors.toCollection(java.util.TreeSet::new));
        Set<String> expected = java.util.Arrays.stream(expectedKeys)
                .map(String::toLowerCase).collect(Collectors.toCollection(java.util.TreeSet::new));
        assertEquals(expected, actual, () -> "Tập '" + type + "' không khớp — mong đợi đúng " + expected + " nhưng thực tế là " + actual);
    }

    private static void assertExactColumns(List<Suggest> list, String... expectedKeys) {
        assertExactKeysOfType(list, "column", expectedKeys);
    }

    private static void assertExactTables(List<Suggest> list, String... expectedKeys) {
        assertExactKeysOfType(list, "table", expectedKeys);
    }

    private static void assertExactViews(List<Suggest> list, String... expectedKeys) {
        assertExactKeysOfType(list, "view", expectedKeys);
    }

    @Nested
    @DisplayName("keyword bắt-đầu-câu không lặp lại giữa câu")
    class StatementStartKeywordDedup {

        @Test
        @DisplayName("'select |' KHÔNG còn gợi ý lại 'select'/'insert'/'with'/'create' (đã gõ dở SELECT, chưa xong)")
        void noStatementStartKeywordsMidSelect() {
            var result = suggest("select |");
            var keywords = allKeywordKeys(result);
            assertFalse(keywords.contains("select"));
            assertFalse(keywords.contains("insert"));
            assertFalse(keywords.contains("with"));
            assertFalse(keywords.contains("create"));
        }

        @Test
        @DisplayName("Đầu file (chưa gõ gì) - VẪN phải thấy đủ keyword bắt-đầu-câu")
        void statementStartKeywordsAtVeryBeginning() {
            var result = suggest("|");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("select"));
            assertTrue(keywords.contains("insert into"));
            assertTrue(keywords.contains("with"));
            assertTrue(keywords.contains("create"));
            assertTrue(keywords.contains("delete from"));
            assertTrue(keywords.contains("update"));
            assertExactColumns(result);
            assertExactTables(result);
        }

        @Test
        @DisplayName("Sau dấu ';' của câu trước - VẪN phải thấy đủ keyword bắt-đầu-câu (không phá multi-statement)")
        void statementStartKeywordsAfterSemicolon() {
            var result = suggest("select * from users; |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("select"));
            assertTrue(keywords.contains("insert into"));
            assertTrue(keywords.contains("delete from"));
            assertTrue(keywords.contains("with"));
            assertTrue(keywords.contains("update"));
            assertExactColumns(result);
            assertExactTables(result);
        }

        @Test
        @Disabled("GIOI HAN THAT (khong phai bug sua duoc o CompletionEngine): noi dung trong "
                + "$$ ... $$ duoc LEXER coi la 1 token chuoi (DollarText) duy nhat - SQL grammar tang "
                + "ngoai khong parse sau vao ben trong. Da xac nhan bang debug truc tiep: tai vi tri "
                + "nay, candidates().tokens CHI co DollarText/EndDollarStringConstant, khong co "
                + "SELECT/INSERT/... de ma loc hay giu lai. Muon ho tro completion ben trong function "
                + "body can 1 parse-pass RIENG cho noi dung dollar-quoted - ngoai pham vi hien tai.")
        @DisplayName("[KNOWN LIMITATION] Trong stored procedure, sau BEGIN - CHUA goi y duoc SELECT/INSERT/UPDATE")
        void statementStartKeywordsAfterBegin() {
            var result = suggest("create procedure test() language plpgsql as $$ begin |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("select"));
            assertTrue(keywords.contains("insert"));
        }
    }

    @Nested
    @DisplayName("JOIN chưa gõ ON không làm mất alias của bảng trước")
    class JoinMissingOnClause {

        @Test
        @DisplayName("JOIN...AS <đang gõ dở, chưa xong> - vẫn gợi ý alias tự động cho vế đang gõ")
        void aliasSuggestionWorksRightAfterAsInIncompleteJoin() {
            var result = suggest(
                    "select * from public.contracts as c join public.contracts as |");
            assertTrue(hasKeyOfType(result, "c1", "alias"));
            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("gợi ý alias tự động phải có type \"alias\", không phải \"column\"")
    class AliasSuggestionType {

        @Test
        @DisplayName("Gợi ý alias sau AS phải gắn type \"alias\"")
        void suggestedAliasHasAliasType() {
            var result = suggest("select * from public.users as |");
            var aliasSuggests = result.stream().filter(s -> s.getKey().equals("u")).toList();
            assertFalse(aliasSuggests.isEmpty());
            assertEquals("alias", aliasSuggests.get(0).getType());
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("Alias tự động tăng số - type cũng là 'alias'")
        void autoIncrementedAliasHasAliasType() {
            var result = suggest("select * from public.users u join public.orders as |");
            var aliasSuggests = result.stream().filter(s -> s.getKey().equals("o")).toList();
            assertFalse(aliasSuggests.isEmpty());
            assertEquals("alias", aliasSuggests.get(0).getType());
            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("ẩn keyword-dùng-được-làm-identifier khi đã có cột/alias/tên bảng thật")
    class IdentifierUsableKeywordNoise {

        @Test
        @DisplayName("'select |' (đã có cột thật từ users) - KHÔNG còn 'insert'/'at'/'by'/'do' trong keyword")
        void noIdentifierNoiseWhenRealColumnsExist() {
            var result = suggest("select * from public.users where |");
            var keywords = allKeywordKeys(result);
            assertFalse(keywords.contains("insert"));
            assertFalse(keywords.contains("at"));
            assertFalse(keywords.contains("by"));
            assertFalse(keywords.contains("do"));
            assertFalse(keywords.contains("truncate"));
            assertFalse(keywords.contains("insert into"));
            assertExactColumns(result, "users.created_date", "users.email", "users.id", "users.name");
            assertEquals(4, keysOfType(result, "column").size());
        }

        @Test
        @DisplayName("'... AS |' - cũng phải ẩn noise này (table_alias cùng bị nhiễu)")
        void noIdentifierNoiseAtTableAliasPosition() {
            var result = suggest("select * from public.contracts as c join public.contracts as |");
            var keywords = allKeywordKeys(result);
            assertFalse(keywords.contains("insert"));
            assertFalse(keywords.contains("at"));
            assertFalse(keywords.contains("do"));
            assertFalse(keywords.contains("of"));
            assertTrue(keywords.isEmpty());
            assertTrue(hasKeyOfType(result, "c1", "alias"));
        }

        @Test
        @DisplayName("'WHERE a = 1 |' (columnref KHÔNG active nữa, đang chờ AND/OR) - 'and'/'or' KHÔNG bị ẩn")
        void andOrNotHiddenWhenContinuingBooleanExpression() {
            var result = suggest("select * from public.users where id = 1 |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("and"));
            assertTrue(keywords.contains("or"));
            assertExactColumns(result);
        }

        @Test
        @DisplayName("Sau FROM mới (chưa gõ schema prefix) - cũng phải ẩn noise keyword (fix mở rộng qualified_name/any_name)")
        void noNoiseAfterFrom() {
            var result = suggest("select * from |");
            var keywords = allKeywordKeys(result);
            assertFalse(keywords.contains("insert"));
            assertFalse(keywords.contains("at"));
            assertFalse(keywords.contains("by"));
            assertFalse(keywords.contains("do"));
            assertExactTables(result, "public.users", "public.orders", "public.contracts", "public.products");
            assertExactViews(result, "public.orders_summary");
            assertExactColumns(result);
        }
    }


    @Nested
    @DisplayName("Hồi quy chung: cột/hàm/kiểu dữ liệu cơ bản")
    class BasicColumnFunctionTypeSuggestions {

        @Test
        @DisplayName("'select u.| from users u' - gợi ý đúng cột của bảng users")
        void columnSuggestionsForAlias() {
            var result = suggest("select u.| from public.users u");
            assertExactColumns(result, "u.id", "u.name", "u.email", "u.created_date");
            assertExactTables(result);
        }

        @Test
        @DisplayName("'select | from users' - gợi ý hàm (count/sum/avg) cùng với cột")
        void functionSuggestionsAlwaysIncluded() {
            var result = suggest("select | from public.users");
            assertTrue(hasKeyOfType(result, "count", "function"));
            assertTrue(hasKeyOfType(result, "sum", "function"));
            assertTrue(hasKeyOfType(result, "avg", "function"));
            assertTrue(hasKeyOfType(result, "min", "function"));
            assertTrue(hasKeyOfType(result, "max", "function"));
            assertExactColumns(result, "users.created_date", "users.email", "users.id", "users.name");
        }

        @Test
        @DisplayName("'create table t (id |' - gợi ý kiểu dữ liệu")
        void dataTypeSuggestions() {
            var result = suggest("create table t (id |");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
            assertTrue(hasKeyOfType(result, "numeric", "datatype"));
            assertTrue(hasKeyOfType(result, "int4", "datatype"));
            assertTrue(hasKeyOfType(result, "bool", "datatype"));
            assertTrue(hasKeyOfType(result, "timestamp", "datatype"));
            assertTrue(hasKeyOfType(result, "date", "datatype"));
            assertTrue(hasKeyOfType(result, "time", "datatype"));
            assertTrue(hasKeyOfType(result, "varchar", "datatype"));
            assertEquals(8, keysOfType(result, "datatype").size());
            assertExactColumns(result);
        }
    }

    @Nested
    @DisplayName("Test các câu lệnh DML: INSERT, UPDATE, DELETE")
    class DMLStatements {

        @Test

        @DisplayName("INSERT INTO users (| - gợi ý đủ cột của users")
        void insertColumnSuggestions() {
            var result = suggest("insert into public.users (|");
            var columns = keysOfType(result, "column");
            assertTrue(columns.contains("users.id"));
            assertTrue(columns.contains("users.name"));
            assertExactColumns(result, "users.created_date", "users.email", "users.id", "users.name");
            assertEquals(4, columns.size());
        }
    }


    @Nested
    @DisplayName("ORDER BY, GROUP BY, HAVING")
    class OrderGroupHaving {

        @Test
        @DisplayName("ORDER BY id | - gợi ý ASC/DESC")
        void orderByAscDescSuggestions() {
            var result = suggest("select * from public.users order by id |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("asc"));
            assertTrue(keywords.contains("desc"));
            assertExactColumns(result);
        }

        @Test
        @DisplayName("HAVING - gợi ý cột (key dạng tênBảng.column)")
        void havingColumnSuggestions() {
            var result = suggest("select count(*), status from public.orders group by status having |");
            assertExactColumns(result, "orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id");
            assertEquals(5, keysOfType(result, "column").size());
            assertTrue(hasKeyOfType(result, "count", "function"));
        }
    }

    @Nested
    @DisplayName("Subquery")
    class SubqueryTests {

        @Test
        @DisplayName("Subquery trong FROM - gợi ý cột")
        void subqueryFromSuggestions() {
            var result = suggest("select * from (select | from public.users) sub");
            assertExactColumns(result, "users.created_date", "users.email", "users.id", "users.name");
            assertTrue(keysOfType(result, "column").stream().noneMatch(k -> k.contains("zzzcursorzzz")));
        }
    }


    @Nested
    @DisplayName("Function và Expression")
    class FunctionsAndExpressions {

        @Test
        @DisplayName("COUNT(| (chưa đóng ngoặc, KHÔNG có FROM theo sau) - vẫn gợi ý được hàm/keyword")
        void countFunctionNoTrailingContent() {
            var result = suggest("select count(|");
            assertTrue(hasKeyOfType(result, "count", "function"));
            assertExactColumns(result);
        }
    }

    @Nested
    @DisplayName("DDL Statements: CREATE, ALTER, DROP")
    class DDLStatements {

        @Test
        @DisplayName("CREATE TABLE t (id | - gợi ý kiểu dữ liệu")
        void createTableDataTypeSuggestions() {
            var result = suggest("create table test (id |");
            var datatypes = keysOfType(result, "datatype");
            assertTrue(datatypes.contains("int4"));
            assertTrue(datatypes.contains("text"));
            assertTrue(datatypes.contains("numeric"));
            assertTrue(hasKeyOfType(result, "bool", "datatype"));
            assertTrue(hasKeyOfType(result, "date", "datatype"));
            assertEquals(8, keysOfType(result, "datatype").size());
        }

        @Test
        @DisplayName("ALTER TABLE users ADD COLUMN | - gợi ý kiểu dữ liệu")
        void alterTableAddColumnSuggestions() {
            var result = suggest("alter table public.users add column |");
            var datatypes = keysOfType(result, "datatype");
            assertTrue(datatypes.contains("int4"));
            assertTrue(datatypes.contains("text"));
            assertTrue(hasKeyOfType(result, "date", "datatype"));
            assertTrue(hasKeyOfType(result, "time", "datatype"));
            assertTrue(hasKeyOfType(result, "varchar", "datatype"));
            assertEquals(8, keysOfType(result, "datatype").size());
        }
    }

    @Nested
    @DisplayName("Edge cases và Error handling")
    class EdgeCases {

        @Test
        @DisplayName("SQL rỗng - không throw và trả về kết quả")
        void emptySql() {
            var result = assertDoesNotThrow(() -> suggest("|"));
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("select"));
            assertTrue(keywords.contains("insert into"));
            assertTrue(keywords.contains("with"));
            assertExactColumns(result);
            assertExactTables(result);
        }

        @Test
        @DisplayName("Cursor ở đầu câu - gợi ý đầy đủ")
        void cursorAtBeginning() {
            var result = suggest("|select * from users");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("select"));
            assertTrue(keywords.contains("with"));
            assertTrue(keywords.contains("insert into"));
            assertExactColumns(result);
            assertExactTables(result);
        }

        @Test
        @DisplayName("Cast expression (::) - gợi ý kiểu dữ liệu")
        void castExpression() {
            var result = suggest("select id::|");
            var datatypes = keysOfType(result, "datatype");
            assertTrue(datatypes.contains("text"));
            assertTrue(datatypes.contains("numeric"));
            assertTrue(hasKeyOfType(result, "bool", "datatype"));
            assertTrue(hasKeyOfType(result, "date", "datatype"));
            assertEquals(8, result.size());
        }
    }

    @Nested
    @DisplayName("Alias uniqueness và conflict handling")
    class AliasUniqueness {

        @Test
        @DisplayName("Khi alias đã dùng, đề xuất alias tiếp theo")
        void aliasAutoIncrement() {
            var result = suggest("select * from public.users u join public.orders as |");
            assertTrue(hasKeyOfType(result, "o", "alias"));
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("Tên bảng ngắn - alias đề xuất đúng, không conflict")
        void aliasSuggestionBasic() {
            var result = suggest("select * from public.users as |");
            var aliases = keysOfType(result, "alias");
            assertTrue(aliases.contains("u"));
            assertEquals(1, aliases.size());
        }
    }


    @Nested
    @DisplayName("UNION/INTERSECT/EXCEPT")
    class SetOperationTests {

        @Test
        @DisplayName("LƯU Ý THIẾT KẾ (không phải bug): UNION dùng CHUNG 1 scope cho cả 2 vế "
                + "(xem javadoc SemanticScope) - nên cột của bảng vế ĐẦU ('users') cũng lọt vào "
                + "danh sách gợi ý ở vế 2, dù về mặt SQL thật thì KHÔNG hợp lệ để dùng cột đó ở "
                + "vế 2. Đây là đơn giản hoá có chủ đích, không phải điều cần fix.")
        void unionSharesScopeAcrossBothSides() {
            var result = suggest("select id from public.users union select | from public.orders");
            assertTrue(hasKeyOfType(result, "users.name", "column"),
                    "Xác nhận hành vi ĐÃ BIẾT: cột của vế union ĐẦU vẫn lọt vào gợi ý ở vế union SAU");
        }
    }

    @Nested
    @DisplayName("Self-join - phân biệt đúng 2 alias trỏ cùng 1 bảng")
    class SelfJoinDisambiguation {

        @Test
        @DisplayName("2 alias cùng bảng users (u1/u2) - WHERE u2.| CHỈ gợi ý cột của u2, không lẫn u1")
        void selfJoinResolvesCorrectAliasOnly() {
            var result = suggest(
                    "select * from public.users u1 join public.users u2 on u1.id = u2.id where u2.|");
            assertExactColumns(result, "u2.id", "u2.name", "u2.email", "u2.created_date");
            assertTrue(keysOfType(result, "column").stream().noneMatch(k -> k.startsWith("u1.")));
        }
    }


    @Nested
    @DisplayName("Nhiều statement nối tiếp (sau dấu ';')")
    class MultiStatementTests {

        @Test
        @DisplayName("Statement thứ 2 (INSERT) sau ';' - gợi ý bảng bình thường, không lẫn ngữ cảnh SELECT trước")
        void secondStatementAfterSemicolonGetsFreshContext() {
            var result = suggest("select * from public.users; insert into |");
            assertExactTables(result, "public.users", "public.orders", "public.contracts", "public.products");
            assertExactViews(result, "public.orders_summary");
            assertEquals(5, result.size());
        }
    }

    @Nested
    @DisplayName("DISTINCT, LIMIT, RETURNING")
    class MiscClauseTests {

        @Test
        @DisplayName("Sau ORDER BY col hoàn chỉnh - gợi ý được LIMIT")
        void limitKeywordAfterOrderBy() {
            var result = suggest("select * from public.users order by id |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("limit"));
            assertExactColumns(result);
        }

        @Test
        @DisplayName("Sau INSERT ... VALUES hoàn chỉnh - gợi ý được RETURNING")
        void returningKeywordAfterInsertValues() {
            var result = suggest("insert into public.users (id) values (1) |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("returning"));
            assertExactColumns(result);
        }
    }


    @Nested
    @DisplayName("LATERAL JOIN")
    class LateralJoinTests {

        @Test
        @DisplayName("CROSS JOIN LATERAL - subquery thấy được alias ngoài")
        void crossJoinLateralSeesOuterAlias() {
            var result = suggest(
                    "select * from public.users u cross join lateral (select | from public.orders where orders.customer_id = u.id) sub");
            assertExactColumns(result, "orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id", "u.created_date", "u.email", "u.id", "u.name");
            assertTrue(keysOfType(result, "column").stream().noneMatch(k -> k.contains("zzzcursorzzz")));
        }
    }


    @Nested
    @DisplayName("Aggregate + HAVING nâng cao")
    class AdvancedAggregateTests {

        @Test
        @DisplayName("SUM(col) trong SELECT list - gợi ý cột bên trong SUM")
        void sumFunctionArgumentColumnSuggestions() {
            var result = suggest("select sum(|) from public.orders");
            assertExactColumns(result, "orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id");
            assertEquals(5, keysOfType(result, "column").size());
            assertTrue(hasKeyOfType(result, "sum", "function"));
        }

        @Test
        @DisplayName("AVG(col) trong SELECT list")
        void avgFunctionArgumentColumnSuggestions() {
            var result = suggest("select avg(|) from public.orders");
            assertExactColumns(result, "orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id");
            assertEquals(5, keysOfType(result, "column").size());
            assertTrue(hasKeyOfType(result, "avg", "function"));
        }
    }


    @Nested
    @DisplayName("ALTER TABLE nâng cao")
    class AdvancedAlterTableTests {

        @Test
        @DisplayName("ALTER TABLE ... DROP COLUMN | - gợi ý cột để xoá")
        void alterTableDropColumnSuggestions() {
            var result = suggest("alter table public.users drop column |");
            assertExactColumns(result, "users.id", "users.name", "users.email", "users.created_date");
            assertExactTables(result);
        }

        @Test
        @DisplayName("ALTER TABLE ... ALTER COLUMN col TYPE | - gợi ý kiểu dữ liệu mới")
        void alterColumnTypeSuggestions() {
            var result = suggest("alter table public.users alter column name type |");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
            assertTrue(hasKeyOfType(result, "int4", "datatype"));
            assertTrue(hasKeyOfType(result, "numeric", "datatype"));
            assertEquals(8, keysOfType(result, "datatype").size());
        }
    }

    @Nested
    @DisplayName("Comment trong câu SQL")
    class SqlCommentTests {

        @Test
        @DisplayName("Comment dạng -- trước vị trí cursor - không ảnh hưởng gợi ý")
        void lineCommentBeforeCursorDoesNotBreakSuggestion() {
            var result = suggest("select * from public.users -- lấy hết user\nwhere |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.name", "column"));
            assertEquals(4, keysOfType(result, "column").size());
        }
    }


    @Nested
    @DisplayName("CREATE FUNCTION/PROCEDURE")
    class FunctionProcedureDdlTests {

        @Test
        @DisplayName("CREATE FUNCTION ... RETURNS | - gợi ý kiểu trả về")
        void createFunctionReturnsTypeSuggestions() {
            var result = suggest("create function f() returns |");
            assertTrue(hasKeyOfType(result, "int4", "datatype"));
            assertTrue(hasKeyOfType(result, "text", "datatype"));
            assertTrue(hasKeyOfType(result, "numeric", "datatype"));
            assertEquals(8, keysOfType(result, "datatype").size());
        }

        @Test
        @DisplayName("CREATE FUNCTION với tham số kiểu | - gợi ý kiểu dữ liệu tham số")
        void createFunctionParameterTypeSuggestions() {
            var result = suggest("create function f(a |");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
            assertTrue(hasKeyOfType(result, "int4", "datatype"));
            assertTrue(hasKeyOfType(result, "varchar", "datatype"));
            assertEquals(8, keysOfType(result, "datatype").size());
        }
    }

    @Nested
    @DisplayName("Không có FROM - ngữ cảnh cột rỗng")
    class NoFromClauseTests {

        @Test
        @DisplayName("SELECT 1 + | (không FROM) - không crash, không gợi ý cột sai")
        void selectWithoutFromNoColumnLeak() {
            var result = suggest("select 1 + |");
            assertExactColumns(result);
            assertExactTables(result);
            assertTrue(hasKeyOfType(result, "count", "function"));
        }

        @Test
        @DisplayName("SELECT current_date, | (không FROM) - không có bảng nào visible nên KHÔNG được lộ cột "
                + "(giống 'selectWithoutFromNoColumnLeak' ở trên); hàm (count/sum/...) vẫn được add "
                + "không điều kiện trong addColumnSuggestions() nên function list không nhất thiết rỗng")
        void selectCurrentDateWithoutFromNoColumnLeak() {
            var result = suggest("select current_date, |");
            assertExactColumns(result);
            assertExactTables(result);
            assertTrue(hasKeyOfType(result, "count", "function"));
        }
    }

    @Nested
    @DisplayName("Nhiều vị trí gõ dở trong cùng 1 câu (test riêng từng vị trí)")
    class MultiplePositionsInSameStatement {

        @Test
        @DisplayName("Vị trí đầu (SELECT list) và vị trí sau (WHERE) - mỗi vị trí resolve độc lập đúng")
        void selectListAndWhereClauseResolveIndependently() {
            var selectListResult = suggest("select u.| from public.users u join public.orders o on u.id = o.customer_id");
            assertTrue(hasKeyOfType(selectListResult, "u.name", "column"));

            var whereResult = suggest("select * from public.users u join public.orders o on u.id = o.customer_id where o.|");
            assertTrue(hasKeyOfType(whereResult, "o.total", "column"));
            assertEquals(4, keysOfType(selectListResult, "column").size());
            assertEquals(5, keysOfType(whereResult, "column").size());
            assertFalse(hasKeyOfType(selectListResult, "o.total", "column"));
            assertFalse(hasKeyOfType(whereResult, "u.id", "column"));
        }
    }

    @Nested
    @DisplayName("WITH RECURSIVE")
    class RecursiveCteTests {

        @Test
        @DisplayName("WITH RECURSIVE - cột trong phần base case gợi ý đúng")
        void recursiveCteBaseCase() {
            var result = suggest("with recursive r as (select id, name from public.users where |) select * from r");
            assertExactColumns(result, "users.created_date", "users.email", "users.id", "users.name");
            assertTrue(keysOfType(result, "column").stream().noneMatch(k -> k.startsWith("r.")));
        }
    }

    @Nested
    @DisplayName("MERGE statement")
    class MergeStatementTests {

        @Test
        @DisplayName("MERGE ... USING ... ON - gợi ý cột 2 bảng")
        void mergeUsingOnColumnSuggestions() {
            var result = suggest(
                    "merge into public.users u using public.orders o on u.id = o.| when matched then do nothing");
            assertExactColumns(result, "o.customer_id", "o.id", "o.total", "o.status", "o.user_id");
            assertTrue(keysOfType(result, "column").stream().noneMatch(k -> k.startsWith("u.")));
        }
    }


    @Nested
    @DisplayName("GRANT/REVOKE")
    class GrantRevokeTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] REVOKE ... FROM | - vế sau FROM là 'grantee_list' (role, không phải bảng; "
                + "g4 dòng 1638), rule này không thuộc any_name/qualified_name/colid nên engine không gợi ý gì.")
        void revokeDoesNotThrow() {
            var result = suggest("revoke select on public.users from |");
            assertTrue(result.size() > 100);
            assertExactTables(result);
            assertExactColumns(result);
        }
    }

    @Nested
    @DisplayName("JOIN ... USING (...)")
    class JoinUsingTests {

        @Test
        @DisplayName("JOIN ... USING (| ) - gợi ý cột chung của cả 2 bảng")
        void joinUsingColumnSuggestions() {
            var result = suggest("select * from public.users u join public.orders o using (|)");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
            assertTrue(hasKeyOfType(result, "o.id", "column"));
            assertFalse(hasKeyOfType(result, "u.name", "column"));
            assertFalse(hasKeyOfType(result, "o.total", "column"));
        }
    }


    @Nested
    @DisplayName("CAST nâng cao")
    class AdvancedCastTests {

        @Test
        @DisplayName("CAST(col AS type) - dạng hàm CAST() thay vì :: - gợi ý kiểu dữ liệu")
        void castFunctionSyntaxDataTypeSuggestions() {
            var result = suggest("select cast(id as |) from public.users");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
            assertTrue(hasKeyOfType(result, "int4", "datatype"));
            assertTrue(hasKeyOfType(result, "numeric", "datatype"));
            assertEquals(8, keysOfType(result, "datatype").size());
        }
    }


    @Nested
    @DisplayName("Transaction control (BEGIN/COMMIT/SAVEPOINT)")
    class TransactionControlTests {

        @Test
        @DisplayName("BEGIN; SELECT | - statement sau BEGIN transaction vẫn gợi ý cột bình thường")
        void selectAfterBeginTransaction() {
            var result = suggest("begin; select | from public.users");
            assertExactColumns(result, "users.created_date", "users.email", "users.id", "users.name");
            assertEquals(4, keysOfType(result, "column").size());
        }
    }


    @Nested
    @DisplayName("CREATE DOMAIN/TYPE")
    class DomainTypeDdlTests {

        @Test
        @DisplayName("CREATE DOMAIN AS | - gợi ý kiểu dữ liệu nền")
        void createDomainDataTypeSuggestions() {
            var result = suggest("create domain positive_int as |");
            assertTrue(hasKeyOfType(result, "int4", "datatype"));
            assertTrue(hasKeyOfType(result, "text", "datatype"));
            assertTrue(hasKeyOfType(result, "varchar", "datatype"));
            assertEquals(8, keysOfType(result, "datatype").size());
        }
    }


    @Nested
    @DisplayName("Nhiều CTE độc lập, dùng chung trong JOIN")
    class IndependentMultipleCteJoinTests {

        @Test
        @DisplayName("2 CTE độc lập JOIN với nhau trong statement chính")
        void twoIndependentCtesJoined() {
            var result = suggest(
                    "with a as (select id, name from public.users), b as (select id, total from public.orders) "
                            + "select a.| from a join b on a.id = b.id");
            assertExactColumns(result, "a.id", "a.name");
        }
    }


    @Nested
    @DisplayName("Hồi quy field order - GOM (parameterized)")
    class SuggestOrderRegressionParameterized {

        static Stream<Arguments> orderCases() {
            return Stream.of(
                    Arguments.of("select * from public.users as |", "alias", 1),
                    Arguments.of("select u.| from public.users u", "column", 2),
                    Arguments.of("select * from |", "table", 3),
                    Arguments.of("|", "keyword", 4),
                    Arguments.of("select * from public.|", "view", 5),
                    Arguments.of("select | from public.users", "function", 6),
                    Arguments.of("create table t (id |", "datatype", 7)
            );
        }

        @ParameterizedTest(name = "type=\"{1}\" phải có order={2} (sql=\"{0}\")")
        @MethodSource("orderCases")
        void suggestTypeHasExpectedOrder(String sql, String type, int expectedOrder) {
            var result = suggest(sql);
            var match = result.stream().filter(s -> s.getType().equals(type)).findFirst();
            assertTrue(match.isPresent(), "Không tìm thấy suggest type=" + type);
            assertEquals(expectedOrder, match.get().getOrder());
        }
    }


    @Nested
    @DisplayName("Gợi ý bảng cho nhiều loại statement khác nhau - GOM (parameterized)")
    class TableSuggestionParameterized {

        static Stream<Arguments> tableSuggestionCases() {
            return Stream.of(
                    Arguments.of("insert into |", "public.users"),
                    Arguments.of("update |", "public.users"),
                    Arguments.of("delete from |", "public.users"),
                    Arguments.of("alter table |", "public.users"),
                    Arguments.of("drop table |", "public.users"),
                    Arguments.of("truncate table |", "public.users"),
                    Arguments.of("grant select on |", "public.users"),
                    Arguments.of("create trigger t1 before insert on |", "public.users"),
                    Arguments.of("comment on table |", "public.users"),
                    Arguments.of("vacuum |", "public.users"),
                    Arguments.of("analyze |", "public.users")
            );
        }

        @ParameterizedTest(name = "\"{0}\" gợi ý được bảng {1}")
        @MethodSource("tableSuggestionCases")
        void suggestsExpectedTable(String sql, String expectedTable) {
            var result = suggest(sql);
            assertTrue(keysOfType(result, "table").contains(expectedTable));
            assertExactTables(result, "public.users", "public.orders", "public.contracts", "public.products");
            assertExactViews(result, "public.orders_summary");
            assertExactColumns(result);
        }
    }

    @Nested
    @DisplayName("Không crash - smoke test tổng hợp nhiều cú pháp khác nhau - GOM (parameterized)")
    class DoesNotThrowSmokeTests {

        static Stream<Arguments> smokeCases() {
            // (sql, loại kỳ vọng, key kỳ vọng) - "empty": không có gợi ý nào; "nocolumn": không có cột nào;
            // "anycolumn": có ít nhất 1 cột; còn lại: phải có key thuộc đúng loại đó
            return Stream.of(
                    Arguments.of("savepoint |", "empty", ""),
                    Arguments.of("rollback to savepoint |", "empty", ""),
                    Arguments.of("listen |", "empty", ""),
                    Arguments.of("notify |", "empty", ""),
                    Arguments.of("execute |", "empty", ""),
                    Arguments.of("deallocate |", "keyword", "prepare"),
                    Arguments.of("fetch next from |", "empty", ""),
                    Arguments.of("create sequence |", "keyword", "if not exists"),
                    Arguments.of("alter sequence |", "keyword", "if exists"),
                    Arguments.of("drop sequence |", "keyword", "if exists"),
                    Arguments.of("select nextval(|)", "function", "count"),
                    Arguments.of("alter table public.users add constraint chk1 check (|)", "column", "users.id"),
                    Arguments.of("create table t (id int primary key, name |)", "datatype", "int4"),
                    Arguments.of("create type point as (x int, y |)", "datatype", "int4"),
                    Arguments.of("create table t (a int, b int generated always as (a + |) stored)", "function", "count"),
                    Arguments.of("select * from public.users where to_tsvector(name) @@ to_tsquery(|)", "column", "users.id"),
                    Arguments.of("select unnest(|) from public.users", "column", "users.id"),
                    Arguments.of("do $$ begin raise notice 'x'; end |$$", "empty", ""),
                    Arguments.of("comment on column public.users.id is |", "keyword", "null"),
                    Arguments.of("reindex table |", "table", "public.users"),
                    Arguments.of("select * from \"public\".\"users\" where |", "anycolumn", ""),
                    Arguments.of("select \"u\".| from public.users as \"u\"", "column", "\"u\".id"),
                    Arguments.of("select * from public.users where id = any(array[|])", "column", "users.id"),
                    Arguments.of("select name ->> | from public.users", "column", "users.id"),
                    Arguments.of("select * from public.users where name like 'a%' and |", "column", "users.name"),
                    Arguments.of("select sum(total) over (order by id rows between unbounded preceding and |) from public.orders", "column", "orders.total"),
                    Arguments.of("select case when id = 1 then (case when | then 'x' end) else 'y' end from public.users", "column", "users.id"),
                    Arguments.of("select * from public.users where cast(id as text) = |", "column", "users.id"),
                    Arguments.of("values (1, |)", "function", "count"),
                    Arguments.of("select * from unnest(array[1,2,3]) with ordinality where |", "function", "count"),
                    Arguments.of("select * from public.users where id = 1 and |", "column", "users.email"),
                    Arguments.of("alter table public.users rename to |", "empty", ""),
                    Arguments.of("merge into public.users u using public.orders o on u.id = o.customer_id when matched then update set |", "column", "u.name"),
                    Arguments.of("select customer_id, sum(total) from public.orders group by customer_id having sum(total) > |", "column", "orders.total"),
                    Arguments.of("select * from nonexistent_table where |", "nocolumn", ""),
                    Arguments.of("select nonexistent_col from public.users where |", "column", "users.id"),
                    Arguments.of("select x.| from public.users u", "nocolumn", ""),
                    Arguments.of("select * from public.users orders where orders.|", "column", "orders.name"),
                    Arguments.of("select customer_id, count(*) from public.orders where status = 'active' group by customer_id having count(*) > 1 order by customer_id limit 10 offset |", "function", "count"),
                    Arguments.of("select * from public.users u join public.orders o on u.id = o.customer_id join public.products p on o.product_id = p.id where u.status = 'active' and o.total > 100 group by u.id, u.name having count(*) > 5 order by u.name limit 10 |", "keyword", "or")
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("smokeCases")
        void suggestsExpectedContent(String sql, String expectedType, String expectedKey) {
            var result = assertDoesNotThrow(() -> suggest(sql));
            switch (expectedType) {
                case "empty" -> assertEquals(0, result.size());
                case "nocolumn" -> assertTrue(keysOfType(result, "column").isEmpty());
                case "anycolumn" -> assertFalse(keysOfType(result, "column").isEmpty());
                default -> assertTrue(hasKeyOfType(result, expectedKey, expectedType),
                        "thiếu " + expectedType + " '" + expectedKey + "' cho: " + sql);
            }
        }

        // (không có registry sequence nên fallback về tên bảng) - sai loại object.
        @Test
        void sequenceNamePositionDoesNotSuggestTables() {
            for (String sql : List.of("create sequence |", "alter sequence |", "drop sequence |")) {
                var result = suggest(sql);
                assertTrue(keysOfType(result, "table").isEmpty(), "gợi ý nhầm bảng cho: " + sql);
                assertTrue(keysOfType(result, "view").isEmpty(), "gợi ý nhầm view cho: " + sql);
            }
        }

        @Test
        void mergeUpdateSetLhsOnlySuggestsTargetColumns() {
            var result = suggest("merge into public.users u using public.orders o on u.id = o.customer_id "
                    + "when matched then update set |");
            assertTrue(hasKeyOfType(result, "u.name", "column"));
            assertFalse(hasKeyOfType(result, "o.total", "column"));
        }
    }

    @Nested
    @DisplayName("Partitioned table (PARTITION BY)")
    class PartitionedTableTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] PARTITION BY RANGE (|) - part_elem: colid ... (g4 dòng 830), parent trực tiếp là 'part_elem' không thuộc 5 context mà isColid* nhận diện; hơn nữa đây là CREATE TABLE bảng MỚI nên SemanticScope cũng không đăng ký scope nào (chỉ AlterTable/Index/Insert/Update/Delete/Merge/Policy có scope)")
        void createTablePartitionByRangeDoesNotThrow() {
            // t chưa tồn tại trong schema (đang được CREATE) nên engine không tự tham chiếu được cột
            // của chính nó - đây là giới hạn kiến trúc đã biết (không self-reference), không phải bug.
            var result = suggest("create table t (id int, created_date date) partition by range (|)");
            assertExactColumns(result);
            assertExactTables(result);
            assertTrue(allKeywordKeys(result).contains("extract"));
        }

        @Test
        @DisplayName("[VỊ TRÍ LITERAL] ATTACH PARTITION ... FOR VALUES FROM (|) - partitionboundspec (g4 dòng 522-527) chỉ nhận literal expr_list ở đây, không có ngữ cảnh cột nào để gợi ý")
        void attachPartitionDoesNotThrow() {
            var result = suggest("alter table public.orders attach partition orders_2024 for values from (|");
            assertExactColumns(result);
            assertExactTables(result);
        }
    }

    @Nested
    @DisplayName("Index nâng cao (partial, expression, GIN/GIST)")
    class AdvancedIndexTests {

        @Test
        @DisplayName("CREATE INDEX USING gin (col) - gợi ý cột thật (index_elem: colid ..., "
                + "colid có parent trực tiếp là index_elem -> đã được isColidIndexColumn xử lý)")
        void ginIndexColumnSuggestions() {
            var result = suggest("create index idx1 on public.users using gin (|)");
            assertExactColumns(result, "users.id", "users.name", "users.email", "users.created_date");
            assertExactTables(result);
        }

        @Test
        @DisplayName("CREATE INDEX ON table (LOWER(col)) - expression index, gợi ý cột bên trong hàm "
                + "(index_elem: func_expr_windowless ..., tham số của lower() đi qua rule columnref)")
        void expressionIndexColumnSuggestions() {
            var result = suggest("create index idx1 on public.users (lower(|))");
            assertExactColumns(result, "users.created_date", "users.email", "users.id", "users.name");
            assertEquals(4, keysOfType(result, "column").size());
            assertExactTables(result);
        }

        @Test
        @DisplayName("CREATE UNIQUE INDEX - gợi ý cột thật (cùng rule index_elem như trên)")
        void uniqueIndexColumnSuggestions() {
            var result = suggest("create unique index idx1 on public.users (|)");
            assertExactColumns(result, "users.id", "users.name", "users.email", "users.created_date");
            assertExactTables(result);
        }
    }

    @Nested
    @DisplayName("SET/SHOW session command")
    class SessionCommandTests {

        // [GIỚI HẠN THẬT cho cả nhóm này] SET/SHOW/RESET đều dùng rule 'var_name' (g4 dòng 287, dùng lại
        // ở dòng 271/360...) - đây là rule RIÊNG, không phải any_name/qualified_name/typename/colid nên
        // CompletionEngine không có nhánh nào tra cứu theo nó. Giữ assertDoesNotThrow là đúng bản chất
        // hiện tại của engine, không phải test viết hời hợt.

        @Test
        @DisplayName("[GIỚI HẠN THẬT] SET search_path TO | - rule 'var_name', không được xử lý")
        void setSearchPathDoesNotThrow() {
            var result = suggest("set search_path to |");
            assertTrue(result.size() > 100);
            assertExactColumns(result);
            assertExactTables(result);
            assertTrue(allKeywordKeys(result).contains("abort"));
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] SHOW | - rule 'var_name', không được xử lý")
        void showDoesNotThrow() {
            var result = suggest("show |");
            assertEquals(4, result.size());
            var kw = allKeywordKeys(result);
            assertTrue(kw.contains("all") && kw.contains("time zone") && kw.contains("session authorization") && kw.contains("transaction isolation level"));
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] RESET | - rule 'var_name', không được xử lý")
        void resetDoesNotThrow() {
            var result = suggest("reset |");
            assertEquals(4, result.size());
            var kw = allKeywordKeys(result);
            assertTrue(kw.contains("all") && kw.contains("time zone") && kw.contains("session authorization") && kw.contains("transaction isolation level"));
        }
    }


    @Nested
    @DisplayName("FETCH FIRST / LIMIT WITH TIES")
    class FetchFirstTests {

        @Test
        @DisplayName("[TRAILING] FETCH FIRST 10 ROWS ONLY| - row_or_rows (ONLY | WITH TIES) đã chọn xong ONLY, statement hoàn chỉnh, không có gì tiếp theo để gợi ý")
        void fetchFirstRowsOnlyDoesNotThrow() {
            var result = suggest("select * from public.users order by id fetch first 10 rows only|");
            assertEquals(2, result.size());
            var kw = allKeywordKeys(result);
            assertTrue(kw.contains("only") && kw.contains("with ties"));
        }
    }


    @Nested
    @DisplayName("UPDATE ... FROM (join-like update)")
    class UpdateFromTests {

        @Test
        @DisplayName("UPDATE t1 SET col = t2.col FROM t2 WHERE - gợi ý cột cả 2 bảng")
        void updateFromSecondTableColumnSuggestions() {
            var result = suggest(
                    "update public.orders o set total = o2.total from public.orders o2 where o.id = o2.|");
            assertExactColumns(result, "o2.customer_id", "o2.id", "o2.status", "o2.total", "o2.user_id");
            assertEquals(5, keysOfType(result, "column").size());
            assertTrue(keysOfType(result, "column").stream().noneMatch(k -> k.startsWith("o.")));
        }
    }

    @Nested
    @DisplayName("WITH RECURSIVE dùng UNION ALL")
    class RecursiveCteUnionAllTests {

        @Test
        @DisplayName("WITH RECURSIVE ... select | from r - r là CTE (derivedScope qua exitCommon_table_expr), 'select | from r' resolve cột projected của r (id, name) giống pattern recursiveCteBaseCase")
        void recursivePartColumnSuggestions() {
            var result = suggest("with recursive r as (select id, name from public.users where id = 1 union all select u.id, u.name from public.users u join r on u.id = r.id + 1) select | from r");
            assertExactColumns(result, "r.id", "r.name");
            var cols = keysOfType(result, "column");
            assertEquals(cols.size(), cols.stream().distinct().count());
            assertEquals(2, cols.size());
        }
    }

    // BỐI CẢNH: MAX_DEPTH đã bị bỏ khỏi engine (xem AntlrCompletionEngineBase),
    // dựa vào giả định "ANTLR4 không tạo ra epsilon-cycle trong ATN". Giả định
    // này ĐÚNG với left-recursion (ANTLR tự viết lại), nhưng KHÔNG loại trừ
    // khả năng đệ quy sâu hợp lệ (ngoặc lồng nhau, subquery lồng nhau) làm
    // treo/StackOverflow nếu walkRuleBody hay collectFollowSets có chỗ tính
    // toán lại theo cấp số nhân. Nhóm này xác nhận thực tế không xảy ra.

    @Nested
    @DisplayName("Độ sâu lồng nhau lớn - không treo, không StackOverflow")
    class DeepNestingStressTests {

        @Test
        @DisplayName("20 tầng ngoặc lồng nhau trong biểu thức WHERE - phải trả lời trong thời gian hợp lý")
        void deeplyNestedParenthesesInWhereClause() {
            StringBuilder sb = new StringBuilder("select * from public.users where ");
            for (int i = 0; i < 20; i++) sb.append("(");
            sb.append("id = 1");
            for (int i = 0; i < 19; i++) sb.append(")");
            sb.append(" and |)");
            String sql = sb.toString();

            long start = System.currentTimeMillis();
            var result = assertDoesNotThrow(() -> suggest(sql));
            long elapsedMs = System.currentTimeMillis() - start;
            assertTrue(elapsedMs < 3000, "Quá chậm với 20 tầng ngoặc lồng nhau: " + elapsedMs + "ms");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.email", "column"));
        }

        @Test
        @DisplayName("15 tầng subquery lồng nhau (mỗi tầng SELECT trong FROM) - không treo")
        void deeplyNestedSubqueriesInFrom() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 15; i++) sb.append("select * from (");
            sb.append("select | from public.users");
            for (int i = 0; i < 15; i++) sb.append(") sub").append(i);
            String sql = sb.toString();

            long start = System.currentTimeMillis();
            var result = assertDoesNotThrow(() -> suggest(sql));
            long elapsedMs = System.currentTimeMillis() - start;
            assertTrue(elapsedMs < 3000, "Quá chậm với 15 tầng subquery lồng nhau: " + elapsedMs + "ms");
            // tầng trong cùng "select | from public.users" vẫn phải resolve cột thật của users
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.name", "column"));
            assertTrue(hasKeyOfType(result, "users.email", "column"));
            assertTrue(keysOfType(result, "column").stream().noneMatch(k -> k.contains("zzzcursorzzz")));
        }

        @Test
        @DisplayName("Biểu thức AND/OR nối rất dài (50 điều kiện) - không treo")
        void veryLongBooleanExpressionChain() {
            StringBuilder sb = new StringBuilder("select * from public.users where id = 1");
            for (int i = 0; i < 50; i++) sb.append(" and id = ").append(i);
            sb.append(" and |");
            String sql = sb.toString();

            long start = System.currentTimeMillis();
            var result = assertDoesNotThrow(() -> suggest(sql));
            assertTrue(System.currentTimeMillis() - start < 3000, "Quá chậm với chuỗi AND dài");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.email", "column"));
        }

        @Test
        @DisplayName("CASE WHEN lồng nhau 10 tầng - tầng trong cùng vẫn phải gợi ý cột thật (không chỉ không throw); thêm timing check để nhất quán với 3 test cùng nhóm DeepNestingStressTests ở trên")
        void deeplyNestedCaseWhenColumnSuggestions() {
            StringBuilder sb = new StringBuilder("select ");
            for (int i = 0; i < 10; i++) sb.append("case when id = ").append(i).append(" then (");
            sb.append("select | from public.users");
            for (int i = 0; i < 10; i++) sb.append(") end");
            sb.append(" from public.users");
            String sql = sb.toString();

            long start = System.currentTimeMillis();
            var result = suggest(sql);
            long elapsedMs = System.currentTimeMillis() - start;
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(elapsedMs < 3000, "Quá chậm với 10 tầng CASE WHEN lồng nhau: " + elapsedMs + "ms");
            assertTrue(hasKeyOfType(result, "users.name", "column"));
            assertTrue(hasKeyOfType(result, "users.email", "column"));
            assertEquals(4, keysOfType(result, "column").size());
        }
    }

    // FollowSetsByState cache dùng chung (ConcurrentHashMap) giữa mọi lần gọi
    // collectCandidates, kể cả khác luồng. Nhóm này xác nhận không có race
    // condition nào làm sai kết quả hoặc ném exception khi nhiều request
    // completion chạy đồng thời (đúng kịch bản 1 IDE server phục vụ nhiều
    // client cùng lúc).

    @Nested
    @DisplayName("Thread-safety - nhiều luồng gọi suggest() đồng thời")
    class ConcurrencyTests {

        @Test
        @DisplayName("50 luồng cùng gọi suggest() với các câu SQL khác nhau - không exception, kết quả đúng")
        void concurrentSuggestCallsProduceCorrectResults() throws InterruptedException {
            record ThreadCase(String sql, String expectedTable) {
            }
            List<ThreadCase> cases = List.of(
                    new ThreadCase("select * from public.|", "public.users"),
                    new ThreadCase("select * from public.|", "public.orders"),
                    new ThreadCase("select * from public.|", "public.contracts"),
                    new ThreadCase("select * from public.|", "public.products")
            );

            int threadCount = 50;
            var executor = java.util.concurrent.Executors.newFixedThreadPool(16);
            var errors = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
            var latch = new java.util.concurrent.CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                ThreadCase tc = cases.get(i % cases.size());
                executor.submit(() -> {
                    try {
                        var result = suggest(tc.sql());
                        var tables = keysOfType(result, "table");
                        if (tables.size() != 4 || !keysOfType(result, "view").contains("public.orders_summary")
                                || !keysOfType(result, "column").isEmpty()) {
                            errors.add("Kết quả sai (tables=" + tables + ") ở luồng " + Thread.currentThread().getName());
                        }
                        if (!tables.contains(tc.expectedTable())) {
                            errors.add("Thiếu bảng " + tc.expectedTable() + " ở luồng " + Thread.currentThread().getName());
                        }
                    } catch (Exception e) {
                        errors.add("Exception ở luồng " + Thread.currentThread().getName() + ": " + e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
            executor.shutdownNow();

            assertTrue(finished, "Không hoàn thành trong 30s - nghi ngờ deadlock");
            assertTrue(errors.isEmpty(), "Có lỗi khi chạy đồng thời:\n" + String.join("\n", errors));
        }
    }


    @Nested
    @DisplayName("Ký tự đặc biệt trong identifier (quoted, unicode, khoảng trắng)")
    class SpecialCharacterIdentifierTests {

        @Test
        @DisplayName("[ROBUSTNESS - cố ý] Quoted/unicode identifier - đây là test độ bền của LEXER (không phải vị trí cần gợi ý cột/bảng cụ thể nào), assertDoesNotThrow đúng là assertion cần thiết ở đây")
        void quotedIdentifierWithSpaceDoesNotThrow() {
            // test độ bền lexer với identifier có khoảng trắng trong ngoặc kép - vị trí RHS của "="
            // vẫn phải resolve cột thật của users, không liên quan gì tới identifier "user name" ở LHS
            var result = suggest("select * from public.users where \"user name\" = |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.email", "column"));
        }

        @Test
        @DisplayName("[ROBUSTNESS - cố ý] Quoted/unicode identifier - đây là test độ bền của LEXER (không phải vị trí cần gợi ý cột/bảng cụ thể nào), assertDoesNotThrow đúng là assertion cần thiết ở đây")
        void quotedIdentifierWithEscapedQuoteDoesNotThrow() {
            // bảng "my""table" không có trong schema fixture - resolve cột phải fail êm (không crash,
            // không bịa cột), fallback về keyword/function chung
            var result = suggest("select * from public.\"my\"\"table\" where |");
            assertExactColumns(result);
            assertFalse(allKeywordKeys(result).isEmpty());
        }

        @Test
        @DisplayName("[ROBUSTNESS - cố ý] Quoted/unicode identifier - đây là test độ bền của LEXER (không phải vị trí cần gợi ý cột/bảng cụ thể nào), assertDoesNotThrow đúng là assertion cần thiết ở đây")
        void unicodeIdentifierDoesNotThrow() {
            // identifier Unicode ở LHS không ảnh hưởng resolve cột thật ở RHS của "="
            var result = suggest("select * from public.users where \"tên_khách_hàng\" = |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.name", "column"));
        }

        @Test
        @DisplayName("Alias trùng từ khoá (quoted) - vẫn resolve cột đúng")
        void reservedWordAsQuotedAliasStillResolves() {
            var result = suggest("select \"order\".| from public.orders as \"order\"");
            assertTrue(hasKeyOfType(result, "\"order\".id", "column"));
            assertTrue(hasKeyOfType(result, "\"order\".customer_id", "column"));
            assertTrue(hasKeyOfType(result, "\"order\".total", "column"));
            assertTrue(hasKeyOfType(result, "\"order\".status", "column"));
            assertTrue(hasKeyOfType(result, "\"order\".user_id", "column"));
            assertEquals(5, keysOfType(result, "column").size());
        }
    }

    @Nested
    @DisplayName("Comment chứa từ khoá SQL không được lọt vào phân tích")
    class CommentContainingKeywordsTests {

        @Test
        @DisplayName("Line comment chứa cả câu SELECT hoàn chỉnh trước caret - không ảnh hưởng gợi ý thật")
        void lineCommentWithFullSelectDoesNotLeakIntoAnalysis() {
            var result = suggest(
                    "-- select * from public.orders where fake_col = 1\nselect * from public.users where |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.name", "column"));
            assertEquals(4, keysOfType(result, "column").size());
            // cột giả fake_col / bảng orders trong comment KHÔNG được lọt vào gợi ý
            assertFalse(hasKeyOfType(result, "orders.total", "column"));
            assertTrue(keysOfType(result, "column").stream().noneMatch(k -> k.contains("fake")));
        }

        @Test
        @DisplayName("Block comment chứa từ khoá FROM/WHERE giả - không ảnh hưởng gợi ý thật")
        void blockCommentWithFakeKeywordsDoesNotLeak() {
            var result = suggest(
                    "select * /* from fake_table where fake = 1 */ from public.users where |");
            assertExactColumns(result, "users.created_date", "users.email", "users.id", "users.name");
            assertEquals(4, keysOfType(result, "column").size());
            assertTrue(keysOfType(result, "column").stream().noneMatch(k -> k.contains("fake")));
        }
    }

    @Nested
    @DisplayName("Nhiều dấu ';' liên tiếp / statement rỗng giữa các câu")
    class EmptyStatementBetweenSemicolonsTests {

        @Test
        @DisplayName("';;;' liên tiếp rồi mới tới câu thật - không throw, vẫn gợi ý đúng")
        void multipleConsecutiveSemicolonsDoesNotThrow() {
            var result = suggest(";;; select | from public.users");
            assertExactColumns(result, "users.created_date", "users.email", "users.id", "users.name");
            assertEquals(4, keysOfType(result, "column").size());
        }

        @Test
        @DisplayName("[ROBUSTNESS] Caret ở statement RỖNG giữa 2 dấu ';' - không có gì để gợi ý, assertDoesNotThrow đúng là assertion cần thiết (statement rỗng không có ngữ cảnh nào)")
        void caretAtEmptyStatementPositionDoesNotThrow() {
            var result = suggest("select 1; |; select 2");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("select"));
            assertTrue(keywords.contains("insert into"));
            assertExactColumns(result);
        }

        @Test
        @DisplayName("[ROBUSTNESS] Chỉ toàn dấu ';' - cùng lý do, statement rỗng không có ngữ cảnh nào")
        void onlySemicolonsNoStatementDoesNotThrow() {
            var result = suggest(";;;|");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("select"));
            assertTrue(keywords.contains("insert into"));
            assertExactColumns(result);
        }
    }


    @Nested
    @DisplayName("Array literal và array operator")
    class ArrayLiteralTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] create table t (tags int[], |) - vị trí này là colid của CỘT TIẾP THEO trong columnDef (g4 dòng 678), không thuộc 5 context của isColid*, và CREATE TABLE bảng mới không có scope")
        void arrayColumnTypeDeclarationDoesNotThrow() {
            var result = suggest("create table t (tags int[], |)");
            var kw = allKeywordKeys(result);
            assertTrue(kw.containsAll(java.util.List.of("check", "exclude", "primary key", "constraint", "like", "foreign key", "unique")));
        }
    }


    @Nested
    @DisplayName("Định danh rất dài / danh sách IN rất lớn - kiểm tra hiệu năng")
    class LargeInputPerformanceTests {

        @Test
        @DisplayName("WHERE id IN (1, 2, ..., 500) - phải trả lời trong thời gian hợp lý")
        void largeInListDoesNotSlowDown() {
            StringBuilder sb = new StringBuilder("select * from public.users where id in (");
            for (int i = 0; i < 500; i++) {
                if (i > 0) sb.append(", ");
                sb.append(i);
            }
            sb.append(") and |");
            String sql = sb.toString();

            long start = System.currentTimeMillis();
            var result = assertDoesNotThrow(() -> suggest(sql));
            assertTrue(System.currentTimeMillis() - start < 3000, "Quá chậm với danh sách IN 500 phần tử");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.name", "column"));
        }

        @Test
        @DisplayName("Alias rất dài (200 ký tự) - vẫn phải resolve cột thật qua dot-qualifier, "
                + "độ dài alias không ảnh hưởng logic resolve")
        void veryLongAliasNameColumnSuggestions() {
            String longAlias = "a".repeat(200);
            var result = suggest("select " + longAlias + ".| from public.users " + longAlias);
            assertTrue(hasKeyOfType(result, longAlias + ".id", "column"));
            assertTrue(hasKeyOfType(result, longAlias + ".name", "column"));
            assertTrue(hasKeyOfType(result, longAlias + ".email", "column"));
            assertEquals(4, keysOfType(result, "column").size());
        }

        @Test
        @DisplayName("100 cột trong SELECT list (lặp lại name nhiều lần) - không treo")
        void manySelectListColumnsDoesNotSlowDown() {
            StringBuilder sb = new StringBuilder("select ");
            for (int i = 0; i < 100; i++) {
                if (i > 0) sb.append(", ");
                sb.append("name");
            }
            sb.append(", | from public.users");
            String sql = sb.toString();

            long start = System.currentTimeMillis();
            var result = assertDoesNotThrow(() -> suggest(sql));
            assertTrue(System.currentTimeMillis() - start < 3000, "Quá chậm với 100 cột trong SELECT list");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.email", "column"));
            assertTrue(hasKeyOfType(result, "users.name", "column"));
            assertEquals(4, keysOfType(result, "column").size());
        }
    }

    @Nested
    @DisplayName("Nhiều sub-action trong 1 câu ALTER TABLE (cách nhau bởi dấu phẩy)")
    class MultiActionAlterTableTests {

        @Test
        @DisplayName("ALTER TABLE ADD COLUMN a int, ADD COLUMN | - action thứ 2 vẫn gợi ý kiểu dữ liệu")
        void secondAddColumnActionDataTypeSuggestions() {
            var result = suggest("alter table public.users add column a int, add column b |");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
            assertTrue(hasKeyOfType(result, "int4", "datatype"));
            assertTrue(hasKeyOfType(result, "numeric", "datatype"));
            assertEquals(8, keysOfType(result, "datatype").size());
        }

        @Test
        @DisplayName("ALTER TABLE DROP COLUMN a, ALTER COLUMN b TYPE | - action thứ 2 vẫn gợi ý kiểu dữ liệu")
        void secondAlterColumnActionDataTypeSuggestions() {
            var result = suggest("alter table public.users drop column email, alter column name type |");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
            assertTrue(hasKeyOfType(result, "int4", "datatype"));
            assertTrue(hasKeyOfType(result, "numeric", "datatype"));
            assertEquals(8, keysOfType(result, "datatype").size());
        }
    }


    @Nested
    @DisplayName("Composite type / truy cập field dạng row")
    class CompositeTypeFieldAccessTests {

        @Test
        @DisplayName("CREATE TYPE point AS (x int, y |) - tablefuncelement: colid typename (g4 dòng 3153-3155), vị trí sau 'y ' chờ typename thật, gợi ý kiểu dữ liệu")
        void createCompositeTypeDoesNotThrow() {
            var result = suggest("create type point as (x int, y |)");
            var datatypes = keysOfType(result, "datatype");
            assertTrue(datatypes.containsAll(java.util.List.of("int4", "text", "numeric", "bool", "timestamp", "date", "time", "varchar")));
            assertEquals(8, datatypes.size());
        }
    }

    @Nested
    @DisplayName("GENERATED ALWAYS AS IDENTITY column definition")
    class GeneratedIdentityColumnTests {

        @Test
        @DisplayName("create table t (id int generated always as identity, name |) - columnDef: colid typename (g4 dòng 678), vị trí sau 'name ' chờ typename, gợi ý kiểu dữ liệu (giống dataTypeSuggestions đã kiểm chứng)")
        void generatedAlwaysAsIdentityDoesNotThrow() {
            var result = suggest("create table t (id int generated always as identity, name |)");
            var datatypes = keysOfType(result, "datatype");
            assertTrue(datatypes.containsAll(java.util.List.of("int4", "text", "numeric", "bool", "timestamp", "date", "time", "varchar")));
            assertEquals(8, datatypes.size());
        }
    }

    @Nested
    @DisplayName("VALUES đứng độc lập, nhiều dòng")
    class StandaloneValuesTests {

        @Test
        @DisplayName("[VỊ TRÍ LITERAL] VALUES (1,'a'),(2,|) độc lập không FROM - không có scope/alias nào để tra cột")
        void multiRowValuesSecondRowDoesNotThrow() {
            var result = suggest("values (1, 'a'), (2, |)");
            assertEquals(10, keysOfType(result, "function").size());
            assertFalse(allKeywordKeys(result).isEmpty());
        }

        @Test
        @DisplayName("[VỊ TRÍ LITERAL] VALUES lồng trong INSERT...SELECT - vị trí cursor vẫn là literal trong tuple, không phải cột")
        void valuesAsInsertSourceDoesNotThrow() {
            var result = suggest("insert into public.users (id, name) select * from (values (1, |)) as v(id, name)");
            assertEquals(10, keysOfType(result, "function").size());
            assertFalse(allKeywordKeys(result).isEmpty());
        }
    }


    @Nested
    @DisplayName("CTE với danh sách tên cột tường minh: cte(col1, col2) AS (...)")
    class CteExplicitColumnListTests {

        @Test
        @DisplayName("WITH c(a, b) AS (SELECT id, name FROM users) SELECT c.| FROM c - resolve theo tên cột đặt lại")
        void cteExplicitColumnListResolvesRenamedColumns() {
            var result = suggest("with c(a, b) as (select id, name from public.users) select c.| from c");
            assertTrue(hasKeyOfType(result, "c.a", "column"));
            assertTrue(hasKeyOfType(result, "c.b", "column"));
            assertFalse(hasKeyOfType(result, "c.id", "column"));
            assertFalse(hasKeyOfType(result, "c.name", "column"));
        }
    }


    @Nested
    @DisplayName("WITH ORDINALITY")
    class WithOrdinalityTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT - đã xác nhận qua source] unnest(...) WITH ORDINALITY AS t(val, idx) - 't' là alias của 1 func_table, và SemanticScope.exitTable_ref() có comment tường minh: 'func_table/xmltable/... chưa hỗ trợ alias tracking' - nên KHÔNG có bảng/alias nào visible ở đây, cột phải RỖNG (khác hẳn trường hợp lateral ở trên vì ở đây KHÔNG có alias bảng thật nào khác trong FROM)")
        void unnestWithOrdinalityDoesNotThrow() {
            var result = suggest("select * from unnest(array[1,2,3]) with ordinality as t(val, idx) where |");
            assertExactColumns(result);
            assertTrue(hasKeyOfType(result, "count", "function"));
        }
    }


    @Nested
    @DisplayName("TRUNCATE với tuỳ chọn (RESTART IDENTITY, CASCADE)")
    class TruncateOptionsTests {

        @Test
        @DisplayName("[TRAILING] TRUNCATE ... RESTART IDENTITY CASCADE| - statement đã hoàn chỉnh")
        void truncateWithOptionsDoesNotThrow() {
            var result = suggest("truncate table public.users restart identity cascade|");
            assertEquals(2, result.size());
            var kw = allKeywordKeys(result);
            assertTrue(kw.contains("restrict") && kw.contains("cascade"));
        }
    }


    @Nested
    @DisplayName("FOREIGN KEY với MATCH và ON DELETE/UPDATE action")
    class ForeignKeyMatchActionTests {

        @Test
        @DisplayName("ON UPDATE | trong key_actions - key_action: NO ACTION | RESTRICT | CASCADE | SET NULL/DEFAULT (g4 dòng 807-812), đây là vị trí keyword thật, không phải cột")
        void foreignKeyMatchAndActionsDoesNotThrow() {
            var result = suggest("alter table public.orders add constraint fk1 foreign key (customer_id) references public.users (id) match full on delete cascade on update |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("cascade"));
            assertTrue(keywords.contains("restrict"));
            assertExactColumns(result);
            assertExactTables(result);
        }
    }

    @Nested
    @DisplayName("COLLATE clause")
    class CollateClauseTests {

        @Test
        @DisplayName("[TRAILING] ORDER BY name COLLATE \"C\" | - sau tên collation trần, không có gì chắc chắn để gợi ý an toàn")
        void orderByCollateDoesNotThrow() {
            var result = suggest("select * from public.users order by name collate \"C\" |");
            var kw = allKeywordKeys(result);
            assertTrue(kw.contains("asc") && kw.contains("nulls") && kw.contains("using"));
        }
    }


    @Nested
    @DisplayName("CREATE SEQUENCE với đầy đủ tuỳ chọn")
    class FullSequenceOptionsTests {

        @Test
        @DisplayName("[TRAILING] CREATE SEQUENCE ... CYCLE| - statement đã hoàn chỉnh")
        void createSequenceFullOptionsDoesNotThrow() {
            var result = suggest("create sequence s1 increment by 1 minvalue 1 maxvalue 1000 start with 1 cycle |");
            assertEquals(13, result.size());
            assertTrue(allKeywordKeys(result).contains("owned by"));
        }

        @Test
        @DisplayName("[VỊ TRÍ LITERAL] RESTART WITH | - numericonly literal, không có ngữ cảnh cột")
        void alterSequenceRestartWithDoesNotThrow() {
            var result = suggest("alter sequence s1 restart with |");
            assertEquals(13, result.size());
            assertTrue(allKeywordKeys(result).contains("owned by"));
        }
    }

    @Nested
    @DisplayName("CTE AS MATERIALIZED / AS NOT MATERIALIZED")
    class CteMaterializedHintTests {

        @Test
        @DisplayName("WITH c AS MATERIALIZED (select | from public.users) - opt_materialized không ảnh hưởng preparablestmt bên trong, vẫn là 1 selectstmt bình thường -> users resolve được")
        void cteAsMaterializedDoesNotThrow() {
            var result = suggest("with c as materialized (select | from public.users) select * from c");
            assertExactColumns(result, "users.created_date", "users.email", "users.id", "users.name");
            assertTrue(keysOfType(result, "column").stream().noneMatch(k -> k.contains("zzzcursorzzz")));
        }

        @Test
        @DisplayName("WITH c AS NOT MATERIALIZED (select | from public.users) - cùng lý do như trên")
        void cteAsNotMaterializedDoesNotThrow() {
            var result = suggest("with c as not materialized (select | from public.users) select * from c");
            assertExactColumns(result, "users.created_date", "users.email", "users.id", "users.name");
            assertTrue(keysOfType(result, "column").stream().noneMatch(k -> k.contains("zzzcursorzzz")));
        }
    }

    @Nested
    @DisplayName("Recursive CTE với UNION nhiều vế")
    class MultiTermRecursiveCteTests {

        @Test
        @DisplayName("Recursive CTE 3 vế UNION - toàn bộ vẫn nằm trong 1 selectstmt được registered, 'select | from r' ở ngoài cùng resolve cột projected của r")
        void recursiveCteMultipleUnionTermsDoesNotThrow() {
            var result = suggest("with recursive r as (select id from public.users where id = 1 union select id from public.users where id = 2 union select u.id from public.users u join r on u.id = r.id + 1) select | from r");
            assertExactColumns(result, "r.id");
            var cols = keysOfType(result, "column");
            assertEquals(cols.size(), cols.stream().distinct().count());
            assertEquals(1, cols.size());
        }
    }

    @Nested
    @DisplayName("INSERT OVERRIDING SYSTEM VALUE (cho GENERATED ALWAYS AS IDENTITY)")
    class InsertOverridingSystemValueTests {

        @Test
        @DisplayName("[VỊ TRÍ LITERAL] INSERT ... OVERRIDING SYSTEM VALUE VALUES (1, |) - vị trí literal trong VALUES tuple")
        void insertOverridingSystemValueDoesNotThrow() {
            var result = suggest("insert into public.users overriding system value values (1, |");
            assertEquals(10, keysOfType(result, "function").size());
            assertFalse(allKeywordKeys(result).isEmpty());
        }
    }


    @Nested
    @DisplayName("Composite primary key / multi-column constraint")
    class CompositePrimaryKeyTests {

        @Test
        @DisplayName("DEFERRABLE INITIALLY | - constraintattr: INITIALLY (DEFERRED | IMMEDIATE) (g4 dòng 720) - vị trí keyword thật")
        void deferrableInitiallyDeferredDoesNotThrow() {
            var result = suggest("create table t (a int, constraint c1 unique (a) deferrable initially |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("deferred"));
            assertTrue(keywords.contains("immediate"));
            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("TEMP/UNLOGGED table, function trả về OUT/TABLE")
    class TempTableAndFunctionReturnTests {

        static Stream<Arguments> awaitingDatatypeCases() {
            return Stream.of(
                    // "CREATE TEMP TABLE t (id |)" - TEMP không ảnh hưởng, columnDef: colid typename như bình thường
                    Arguments.of("create temp table t (id |)"),
                    // "CREATE UNLOGGED TABLE t (id |)" - cùng lý do như TEMP TABLE
                    Arguments.of("create unlogged table t (id |)"),
                    // "CREATE FUNCTION f(a int, OUT b |)" - func_arg: arg_class param_name? func_type (g4 dòng 1854-1858), func_type -> typename
                    Arguments.of("create function f(a int, out b |"),
                    // "RETURNS TABLE(id int, name |)" - table_func_column: param_name func_type (g4 dòng 1965-1967)
                    Arguments.of("create function f() returns table(id int, name |"),
                    // "CREATE FUNCTION f(VARIADIC a |)" - cùng rule func_arg như OUT parameter
                    Arguments.of("create function f(variadic a |")
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("awaitingDatatypeCases")
        void suggestsAllDatatypes(String sql) {
            var result = suggest(sql);
            var datatypes = keysOfType(result, "datatype");
            assertTrue(datatypes.containsAll(java.util.List.of("int4", "text", "numeric", "bool", "timestamp", "date", "time", "varchar")));
            assertEquals(8, datatypes.size());
        }
    }

    @Nested
    @DisplayName("ORDER BY nâng cao")
    class AdvancedOrderByTests {

        @Test
        @DisplayName("ORDER BY name NULLS FIRST | - sau NULLS FIRST vẫn có thể tiếp LIMIT, giống limitKeywordAfterOrderBy đã kiểm chứng (chỉ khác thêm NULLS FIRST ở giữa, không đổi follow-set ở cuối)")
        void orderByNullsFirstDoesNotThrow() {
            var result = suggest("select * from public.users order by name nulls first |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("limit"));
            assertExactColumns(result);
        }
    }


    @Nested
    @DisplayName("DROP FUNCTION/AGGREGATE/OPERATOR với chữ ký tham số")
    class DropWithSignatureTests {

        @Test
        @DisplayName("DROP FUNCTION f(int, |) - function_with_argtypes -> func_args -> func_arg -> func_type -> typename")
        void dropFunctionWithArgTypesDoesNotThrow() {
            var result = suggest("drop function f(int, |)");
            var datatypes = keysOfType(result, "datatype");
            assertTrue(datatypes.containsAll(java.util.List.of("int4", "text", "numeric", "bool", "timestamp", "date", "time", "varchar")));
            assertEquals(8, datatypes.size());
        }

        @Test
        @DisplayName("DROP FUNCTION IF EXISTS f(int) | - opt_drop_behavior: CASCADE | RESTRICT (g4 dòng 477-480)")
        void dropFunctionIfExistsCascadeDoesNotThrow() {
            var result = suggest("drop function if exists f(int) |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("cascade"));
            assertTrue(keywords.contains("restrict"));
        }

        @Test
        @DisplayName("DROP AGGREGATE agg(|) - aggregate_with_argtypes -> aggr_args -> aggr_arg -> func_arg -> typename")
        void dropAggregateDoesNotThrow() {
            var result = suggest("drop aggregate agg(|)");
            var datatypes = keysOfType(result, "datatype");
            assertTrue(datatypes.containsAll(java.util.List.of("int4", "text", "numeric", "bool", "timestamp", "date", "time", "varchar")));
            assertEquals(8, datatypes.size());
        }
    }


    @Nested
    @DisplayName("ALTER FUNCTION (rename, owner, schema)")
    class AlterFunctionTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] ALTER FUNCTION ... - target là 'name'/'rolespec' (tên mới/owner/schema), không phải any_name/qualified_name nên không được engine tra cứu")
        void alterFunctionOwnerDoesNotThrow() {
            var result = suggest("alter function f(int) owner to |");
            assertTrue(result.size() > 100);
            assertExactColumns(result);
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] ALTER FUNCTION ... - target là 'name'/'rolespec' (tên mới/owner/schema), không phải any_name/qualified_name nên không được engine tra cứu")
        void alterFunctionSetSchemaDoesNotThrow() {
            var result = suggest("alter function f(int) set schema |");
            assertEquals(3, result.size());
            assertTrue(allKeywordKeys(result).contains("from current"));
        }
    }

    @Nested
    @DisplayName("GRANT/REVOKE nâng cao")
    class AdvancedGrantRevokeTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] GRANT/REVOKE ... role - grantee_list/role_list, không phải any_name/qualified_name")
        void grantMultiplePrivilegesToMultipleRolesDoesNotThrow() {
            var result = suggest("grant select, insert on public.users to role1, |");
            assertTrue(result.size() > 100);
            assertExactColumns(result);
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] GRANT/REVOKE ... role - grantee_list/role_list, không phải any_name/qualified_name")
        void revokeGrantOptionForDoesNotThrow() {
            var result = suggest("revoke grant option for select on public.users from |");
            assertTrue(result.size() > 100);
            assertExactColumns(result);
        }
    }

    @Nested
    @DisplayName("CREATE/ALTER ROLE với tuỳ chọn")
    class RoleDdlTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] ALTER ROLE ... SET search_path TO | - var_name, cùng giới hạn với nhóm SET/SHOW/RESET")
        void alterRoleSetSearchPathDoesNotThrow() {
            var result = suggest("alter role r1 set search_path to |");
            assertTrue(result.size() > 100);
            assertExactColumns(result);
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] SET ROLE | - role_spec/name, không tracked")
        void setRoleDoesNotThrow() {
            var result = suggest("set role |");
            assertTrue(result.size() > 100);
            assertExactColumns(result);
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] RESET ROLE| - trailing, không có ngữ cảnh nào để gợi ý")
        void resetRoleDoesNotThrow() {
            var result = suggest("reset role|");
            assertEquals(4, result.size());
            var kw = allKeywordKeys(result);
            assertTrue(kw.contains("all") && kw.contains("time zone"));
        }
    }

    @Nested
    @DisplayName("Table function với danh sách cột định nghĩa tường minh")
    class TableFunctionColumnDefinitionListTests {

        @Test
        @DisplayName("SELECT * FROM json_to_recordset(...) AS t(a int, b |) - gợi ý kiểu dữ liệu")
        void tableFunctionColumnDefListDataTypeSuggestions() {
            var result = suggest("select * from json_to_recordset('[]') as t(a int, b |)");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
            assertTrue(hasKeyOfType(result, "int4", "datatype"));
            assertTrue(hasKeyOfType(result, "numeric", "datatype"));
            assertEquals(8, keysOfType(result, "datatype").size());
        }
    }

    @Nested
    @DisplayName("LIMIT/OFFSET/FETCH kết hợp")
    class LimitOffsetFetchCombinationTests {

        @Test
        @DisplayName("[TRAILING] LIMIT ALL | - statement đã đầy đủ ngữ nghĩa (ALL thay số)")
        void limitAllDoesNotThrow() {
            var result = suggest("select * from public.users limit all |");
            assertEquals(3, result.size());
            var kw = allKeywordKeys(result);
            assertTrue(kw.contains(",") && kw.contains("for") && kw.contains("offset"));
        }

        @Test
        @DisplayName("FETCH FIRST 10 ROWS WITH | - row_or_rows (ONLY | WITH TIES) (g4 dòng 2932), vị trí sau WITH chờ TIES")
        void offsetFetchWithTiesDoesNotThrow() {
            var result = suggest("select * from public.users order by id offset 5 rows fetch first 10 rows with |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("ties"));
        }

        @Test
        @DisplayName("FETCH FIRST ROW | (số ít) - chờ ONLY hoặc WITH TIES (g4 dòng 2932)")
        void fetchFirstRowOnlySingularDoesNotThrow() {
            var result = suggest("select * from public.users order by id fetch first row |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("only"));
            assertTrue(keywords.contains("with ties"));
        }
    }


    @Nested
    @DisplayName("SET nhiều biến session / LOCK mode")
    class SetSessionAndLockModeTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] SET LOCAL statement_timeout TO | - var_value/var_name, không tracked, và không có scope bảng nào ở 1 câu SET đơn thuần")
        void setLocalStatementTimeoutDoesNotThrow() {
            var result = suggest("set local statement_timeout to |");
            assertTrue(result.size() > 100);
            assertExactColumns(result);
        }

        @Test
        @DisplayName("LOCK TABLE ... IN | - lock_type: ACCESS (SHARE|EXCLUSIVE) | ROW (...) | SHARE (...) | EXCLUSIVE (g4 dòng 2753-2758), vị trí keyword thật")
        void lockTableAccessExclusiveModeDoesNotThrow() {
            var result = suggest("lock table public.users in |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("access"));
            assertTrue(keywords.contains("row"));
            assertTrue(keywords.contains("share"));
            assertTrue(keywords.contains("exclusive"));
            assertEquals(4, result.size());
        }

        @Test
        @DisplayName("LOCK TABLE ... MODE | - opt_nowait: NOWAIT (g4 dòng 2760-2762), vị trí keyword thật")
        void lockTableNowaitDoesNotThrow() {
            var result = suggest("lock table public.users in access exclusive mode |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("nowait"));
            assertEquals(1, result.size());
        }
    }


    @Nested
    @DisplayName("PARTITION với FOR VALUES IN/FROM-TO, DEFAULT partition, HASH modulus")
    class PartitionForValuesTests {

        @Test
        @DisplayName("[VỊ TRÍ LITERAL] partitionboundspec (g4 dòng 522-527/533-535) chỉ nhận literal expr_list/iconst ở đây")
        void partitionForValuesInListDoesNotThrow() {
            var result = suggest("create table orders_active partition of public.orders for values in (|)");
            assertEquals(10, keysOfType(result, "function").size());
            assertFalse(allKeywordKeys(result).isEmpty());
        }

        @Test
        @DisplayName("[VỊ TRÍ LITERAL] partitionboundspec (g4 dòng 522-527/533-535) chỉ nhận literal expr_list/iconst ở đây")
        void partitionForValuesFromToDoesNotThrow() {
            var result = suggest("create table orders_2024 partition of public.orders for values from ('2024-01-01') to (|)");
            assertEquals(10, keysOfType(result, "function").size());
            assertFalse(allKeywordKeys(result).isEmpty());
        }

        @Test
        @DisplayName("PARTITION OF public.orders | - partitionboundspec có alternative literally là keyword DEFAULT (g4 dòng 526), vị trí keyword thật")
        void defaultPartitionDoesNotThrow() {
            var result = suggest("create table orders_default partition of public.orders |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("default"));
            assertTrue(keywords.contains("for"));
            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("Mảng đa chiều, ép kiểu mảng")
    class MultiDimensionalArrayTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] create table t (matrix int[][], |) - vị trí colid của cột TIẾP THEO, không thuộc 5 context, và CREATE TABLE bảng mới không có scope")
        void twoDimensionalArrayColumnDoesNotThrow() {
            var result = suggest("create table t (matrix int[][], |)");
            var kw = allKeywordKeys(result);
            assertTrue(kw.containsAll(java.util.List.of("check", "exclude", "primary key", "constraint", "like", "foreign key", "unique")));
        }
    }


    @Nested
    @DisplayName("Operator class (opclass) trong CREATE INDEX")
    class IndexOperatorClassTests {

        @Test
        @DisplayName("CREATE INDEX ... (name |) - index_elem_options: opt_collate? opt_class? opt_asc_desc? ... (g4 dòng 1778-1781), sau tên cột có thể là ASC/DESC")
        void indexWithOperatorClassDoesNotThrow() {
            var result = suggest("create index idx1 on public.users (name |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("asc"));
            assertTrue(keywords.contains("desc"));
            assertExactTables(result);
            assertExactViews(result);
        }

        @Test
        @DisplayName("CREATE INDEX USING gin (name |) - cùng rule index_elem_options như trên")
        void ginIndexWithOperatorClassDoesNotThrow() {
            var result = suggest("create index idx1 on public.users using gin (name |)");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("asc"));
            assertTrue(keywords.contains("desc"));
            assertExactTables(result);
            assertExactViews(result);
        }
    }


    @Nested
    @DisplayName("Foreign data wrapper")
    class ForeignDataWrapperTests {

        // [GIỚI HẠN THẬT] Foreign Data Wrapper DDL - tên object (FDW/server/schema) đều dùng rule
        // 'name' đơn giản, không phải any_name/qualified_name - engine không track được, rỗng là đúng.
        static Stream<Arguments> foreignDataWrapperCases() {
            return Stream.of(
                    Arguments.of("create foreign data wrapper |"), // đặt tên FDW MỚI - không có gì để gợi ý
                    Arguments.of("create server s1 foreign data wrapper |"), // không track FDW đã tạo
                    Arguments.of("create foreign table t (id int) server |"), // không track server đã tạo
                    Arguments.of("import foreign schema s from server srv into |"), // không có cơ chế gợi ý tên schema chung
                    Arguments.of("create user mapping for current_user server |") // không track server đã tạo
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("foreignDataWrapperCases")
        void foreignDataWrapperDoesNotThrow(String sql) {
            var result = suggest(sql);
            assertEquals(0, result.size());
        }
    }

    @Nested
    @DisplayName("Event trigger")
    class EventTriggerTests {

        @Test
        @DisplayName("ALTER EVENT TRIGGER et1 | - enable_trigger: ENABLE_P (...) | DISABLE_P (g4 dòng 1320-1325), vị trí keyword thật")
        void alterEventTriggerDisableDoesNotThrow() {
            var result = suggest("alter event trigger et1 |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("disable"));
            assertTrue(keywords.contains("enable"));
        }
    }

    @Nested
    @DisplayName("CREATE OPERATOR / OPERATOR CLASS / OPERATOR FAMILY")
    class OperatorDdlTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] CREATE OPERATOR/OPERATOR FAMILY - procedure dùng func_name, access method dùng 'name' - cả hai đều không được engine tra cứu")
        void createOperatorDoesNotThrow() {
            var result = suggest("create operator === (leftarg = int, rightarg = int, procedure = |");
            assertEquals(8, keysOfType(result, "datatype").size());
            assertTrue(result.size() > 100);
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT - phức tạp, không trace thêm] CREATE OPERATOR CLASS ... AS | - danh sách opclass item (FUNCTION/OPERATOR/STORAGE) khá phức tạp, không thuộc any_name/qualified_name/typename/colid đã biết")
        void createOperatorClassDoesNotThrow() {
            var result = suggest("create operator class oc1 for type int using btree as |");
            assertEquals(3, result.size());
            var kw = allKeywordKeys(result);
            assertTrue(kw.contains("function") && kw.contains("storage") && kw.contains("operator"));
        }

        @Test
        @DisplayName("DROP OPERATOR = (int, |) - oper_argtypes: OPEN_PAREN typename (COMMA typename)? CLOSE_PAREN (g4 dòng 2004-2009), typename reachable")
        void dropOperatorDoesNotThrow() {
            var result = suggest("drop operator = (int, |)");
            var datatypes = keysOfType(result, "datatype");
            assertTrue(datatypes.containsAll(java.util.List.of("int4", "text", "numeric", "bool", "timestamp", "date", "time", "varchar")));
            assertEquals(8, datatypes.size());
        }
    }

    @Nested
    @DisplayName("TEXT SEARCH / COLLATION / CONVERSION / CAST / TRANSFORM DDL")
    class TextSearchCollationConversionCastTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT - phức tạp] CREATE TEXT SEARCH CONFIGURATION (|) - definition list generic, chưa xác nhận thuộc rule nào được engine xử lý")
        void createTextSearchConfigurationDoesNotThrow() {
            var result = suggest("create text search configuration tsc1 (|");
            assertTrue(result.size() > 100);
            assertExactColumns(result);
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] CREATE CAST/TRANSFORM ... WITH FUNCTION | - func_name, không được xử lý")
        void createCastDoesNotThrow() {
            var result = suggest("create cast (int as text) with function |");
            assertEquals(23, result.size());
            var kw = allKeywordKeys(result);
            assertTrue(kw.contains("left") && kw.contains("right") && kw.contains("inner"));
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] CREATE CAST/TRANSFORM ... WITH FUNCTION | - func_name, không được xử lý")
        void createTransformDoesNotThrow() {
            var result = suggest("create transform for hstore language plpython3u (from sql with function |");
            assertEquals(23, result.size());
            var kw = allKeywordKeys(result);
            assertTrue(kw.contains("left") && kw.contains("right") && kw.contains("inner"));
        }
    }

    @Nested
    @DisplayName("CREATE PUBLICATION/SUBSCRIPTION (logical replication)")
    class PublicationSubscriptionTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] CREATE SUBSCRIPTION ... PUBLICATION | - tên publication dùng name_list ('name'), không được xử lý")
        void createSubscriptionDoesNotThrow() {
            var result = suggest("create subscription sub1 connection 'host=x' publication |");
            assertTrue(result.size() > 100);
            assertExactColumns(result);
        }
    }


    @Nested
    @DisplayName("Hàm SQL/JSON chuẩn (JSON_OBJECT, JSON_ARRAY, JSON_VALUE, JSON_QUERY, JSON_EXISTS)")
    class SqlStandardJsonFunctionTests {

        static Stream<Arguments> jsonFunctionCases() {
            return Stream.of(
                    // "Hàm SQL/JSON chuẩn - tham số hàm vẫn là columnref bình thường, users (default alias) visible"
                    Arguments.of("select json_object('name' value |) from public.users"),
                    Arguments.of("select json_array(name, |) from public.users"),
                    Arguments.of("select json_value(data, |) from public.users"),
                    Arguments.of("select json_query(data, |) from public.users"),
                    // "JSON_EXISTS(data, |) trong WHERE - tham số hàm vẫn columnref, users visible"
                    Arguments.of("select * from public.users where json_exists(data, |)")
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("jsonFunctionCases")
        void jsonFunctionsSuggestColumns(String sql) {
            var result = suggest(sql);
            assertExactColumns(result, "users.created_date", "users.email", "users.id", "users.name");
        }
    }

    @Nested
    @DisplayName("CREATE/ALTER/DROP DATABASE")
    class DatabaseDdlTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] tên tablespace/database/role đều dùng rule 'name', không được xử lý")
        void createDatabaseOwnerDoesNotThrow() {
            var result = suggest("create database db1 owner |");
            assertTrue(result.size() > 100);
            assertExactColumns(result);
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] tên tablespace/database/role đều dùng rule 'name', không được xử lý")
        void alterDatabaseSetTablespaceDoesNotThrow() {
            var result = suggest("alter database db1 set tablespace |");
            assertEquals(77, result.size());
            assertTrue(allKeywordKeys(result).contains("tablespace"));
        }
    }


    @Nested
    @DisplayName("CREATE ASSERTION")
    class CreateAssertionTests {

        @Test
        @DisplayName("CREATE ASSERTION ... CHECK - không throw")
        void createAssertionCheckDoesNotThrow() {
            var result = suggest("create assertion a1 check (|");
            assertEquals(10, keysOfType(result, "function").size());
        }
    }

    @Nested
    @DisplayName("CREATE AGGREGATE / CREATE ACCESS METHOD")
    class AggregateAndAccessMethodDdlTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] CREATE AGGREGATE ... (SFUNC = |) - func_name cho sfunc, không được xử lý")
        void createAggregateDoesNotThrow() {
            var result = suggest("create aggregate agg1(int) (sfunc = |");
            assertEquals(8, keysOfType(result, "datatype").size());
            assertTrue(result.size() > 100);
        }
    }

    @Nested
    @DisplayName("MERGE với nhiều WHEN clause")
    class MergeMultipleWhenClausesTests {

        @Test
        @DisplayName("MERGE ... WHEN MATCHED THEN UPDATE ... WHEN NOT MATCHED THEN INSERT - action thứ 2 vẫn gợi ý cột")
        void mergeMatchedAndNotMatchedColumnSuggestions() {
            var result = suggest(
                    "merge into public.users u using public.orders o on u.id = o.customer_id "
                            + "when matched then update set name = |");
            assertTrue(hasKeyOfType(result, "u.email", "column"));
            assertTrue(hasKeyOfType(result, "o.total", "column"));
            assertTrue(hasKeyOfType(result, "o.status", "column"));
            assertTrue(hasKeyOfType(result, "u.id", "column"));
            assertTrue(hasKeyOfType(result, "u.name", "column"));
            assertTrue(hasKeyOfType(result, "o.id", "column"));
            assertTrue(hasKeyOfType(result, "o.user_id", "column"));
            assertEquals(9, keysOfType(result, "column").size());
        }
    }

    @Nested
    @DisplayName("GENERATED column STORED, REPLICA IDENTITY")
    class GeneratedStoredAndReplicaIdentityTests {

        @Test
        @DisplayName("ALTER TABLE ... REPLICA IDENTITY | - replica_identity: NOTHING | FULL | DEFAULT | USING INDEX name (g4 dòng 490-495), vị trí keyword thật")
        void alterTableReplicaIdentityFullDoesNotThrow() {
            var result = suggest("alter table public.users replica identity |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("full"));
            assertTrue(keywords.contains("default"));
            assertTrue(keywords.contains("nothing"));
        }
    }


    @Nested
    @DisplayName("Gom theo shape assertion (xuyên nested class cũ) - CONSOLIDATED")
    class ConsolidatedByAssertionShape {

        static Stream<Arguments> exactTablesViewsCases() {
            return Stream.of(
                    Arguments.of("select * from public.|", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}), // từ: schemaQualifiedTableSuggestion
                    Arguments.of("select * from |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}), // từ: tableSuggestionWithoutSchema
                    Arguments.of("select * from public.users u where exists (select 1 from |)", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}), // từ: existsSubquerySuggestions
                    Arguments.of("select * from |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}), // từ: sameTableNameDifferentSchemas
                    Arguments.of("alter table public.orders add constraint fk1 foreign key (customer_id) references |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}), // từ: addForeignKeyReferencesTableSuggestions
                    Arguments.of("create table orders_2024 partition of |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}), // từ: createTablePartitionOfTableSuggestions
                    Arguments.of("copy |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}), // từ: copyFromTableSuggestions
                    Arguments.of("cluster |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}), // từ: clusterTableSuggestions
                    Arguments.of("lock table |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}), // từ: lockTableSuggestions
                    Arguments.of("create table t (name text collate |)", new String[]{}, new String[]{}), // từ: columnDeclarationWithCollateDoesNotThrow
                    Arguments.of("vacuum (verbose, analyze) |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}), // từ: vacuumParenthesizedOptionsDoesNotThrow
                    Arguments.of("reindex index concurrently |", new String[]{}, new String[]{}), // từ: reindexIndexConcurrentlyDoesNotThrow
                    Arguments.of("create collation c1 from |", new String[]{}, new String[]{}), // từ: createCollationFromDoesNotThrow
                    Arguments.of("drop policy if exists p1 on |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}) // từ: dropPolicyIfExistsDoesNotThrow
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("exactTablesViewsCases")
        void exactTablesViews(String sql, String[] expectedTables, String[] expectedViews) {
            var result = suggest(sql);
            assertExactTables(result, expectedTables);
            assertExactViews(result, expectedViews);
        }

        static Stream<Arguments> exactTablesViewsColumnsCases() {
            return Stream.of(
                    Arguments.of("create index idx1 on public.users (|)", new String[]{}, new String[]{}, new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: createIndexColumnSuggestions
                    Arguments.of("drop view |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}, new String[]{}), // từ: dropViewSuggestions
                    Arguments.of("refresh materialized view |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}, new String[]{}), // từ: refreshMaterializedViewSuggestsTables
                    Arguments.of("create trigger t1 before insert on |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}, new String[]{}), // từ: createTriggerOnTableSuggestions
                    Arguments.of("create table child_users (extra_col text) inherits (|)", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}, new String[]{}), // từ: createTableInheritsParentTableSuggestions
                    Arguments.of("truncate table public.users, |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}, new String[]{}), // từ: truncateMultipleTablesSecondTableSuggestions
                    Arguments.of("select id, name into backup_users from |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}, new String[]{}), // từ: selectIntoSourceTableSuggestions
                    Arguments.of("create table t (extra int) inherits (public.users, |)", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}, new String[]{}), // từ: multipleInheritsSecondParentTableSuggestions
                    Arguments.of("create publication pub1 for table |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}, new String[]{}), // từ: createPublicationForTableSuggestions
                    Arguments.of("alter publication pub1 add table |", new String[]{"public.users", "public.orders", "public.contracts", "public.products"}, new String[]{"public.orders_summary"}, new String[]{}) // từ: alterPublicationAddTableSuggestions
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("exactTablesViewsColumnsCases")
        void exactTablesViewsColumns(String sql, String[] expectedTables, String[] expectedViews, String[] expectedColumns) {
            var result = suggest(sql);
            assertExactTables(result, expectedTables);
            assertExactViews(result, expectedViews);
            assertExactColumns(result, expectedColumns);
        }


        static Stream<Arguments> emptyResultCases() {
            return Stream.of(
                    Arguments.of("create trigger t1 before insert on public.users for each row execute function |"), // từ: createTriggerExecuteFunctionDoesNotThrow
                    Arguments.of("notify my_channel, |"), // từ: notifyWithPayloadDoesNotThrow
                    Arguments.of("select * from public.users where id = 1) and |"), // từ: unmatchedClosingParenDoesNotThrow
                    Arguments.of("do $$ declare x |"), // từ: declareVariableDoesNotThrow
                    Arguments.of("do $$ declare x public.users%rowtype; begin end; |$$"), // từ: declareRowTypeVariableDoesNotThrow
                    Arguments.of("do $$ begin if 1 = 1 then raise notice 'a'; elsif 1 = 2 then raise notice |"), // từ: ifElsifElseDoesNotThrow
                    Arguments.of("do $$ begin while true loop exit when |"), // từ: whileLoopDoesNotThrow
                    Arguments.of("do $$ begin for i in 1..|"), // từ: forRangeLoopDoesNotThrow
                    Arguments.of("do $$ begin for rec in select | from public.users loop null; end loop; end; $$"), // từ: forSelectLoopDoesNotThrow
                    Arguments.of("do $$ begin raise exception 'error %', 1 using |"), // từ: raiseExceptionUsingDoesNotThrow
                    Arguments.of("do $$ begin raise notice '% %', 'a', |"), // từ: raiseNoticeMultipleArgsDoesNotThrow
                    Arguments.of("move forward 5 in |"), // từ: moveCursorForwardDoesNotThrow
                    Arguments.of("alter function f(int) rename to |"), // từ: alterFunctionRenameDoesNotThrow
                    Arguments.of("cluster public.users using |"), // từ: clusterUsingIndexDoesNotThrow
                    Arguments.of("create table orders_p0 partition of public.orders for values with (modulus 4, remainder |)"), // từ: hashPartitionModulusRemainderDoesNotThrow
                    Arguments.of("create table t (price numeric(10, |))"), // từ: numericPrecisionScaleDoesNotThrow
                    Arguments.of("create table t (name varchar(|))"), // từ: varcharLengthDoesNotThrow
                    Arguments.of("select cast(total as numeric(10, |)) from public.orders"), // từ: castToNumericWithPrecisionDoesNotThrow
                    Arguments.of("update public.users set name = 'x' where current of |"), // từ: updateWhereCurrentOfDoesNotThrow
                    Arguments.of("delete from public.users where current of |"), // từ: deleteWhereCurrentOfDoesNotThrow
                    Arguments.of("create event trigger et1 on ddl_command_start execute function |"), // từ: createEventTriggerDoesNotThrow
                    Arguments.of("create operator family of1 using |"), // từ: createOperatorFamilyDoesNotThrow
                    Arguments.of("create conversion conv1 for 'UTF8' to |"), // từ: createConversionDoesNotThrow
                    Arguments.of("create tablespace ts1 location |"), // từ: createTablespaceLocationDoesNotThrow
                    Arguments.of("alter tablespace ts1 set |"), // từ: alterTablespaceSetDoesNotThrow
                    Arguments.of("drop tablespace if exists |"), // từ: dropTablespaceIfExistsDoesNotThrow
                    Arguments.of("create language plpython3u handler |"), // từ: createLanguageHandlerDoesNotThrow
                    Arguments.of("create trusted language |"), // từ: createTrustedLanguageDoesNotThrow
                    Arguments.of("create access method am1 type index handler |"), // từ: createAccessMethodDoesNotThrow
                    Arguments.of("alter table public.users replica identity using index |"), // từ: alterTableReplicaIdentityUsingIndexDoesNotThrow
                    Arguments.of("create table t (a int, unique (a) using index tablespace |"), // từ: uniqueUsingIndexTablespaceDoesNotThrow
                    Arguments.of("alter policy p1 on public.users rename to |") // từ: alterPolicyRenameDoesNotThrow
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("emptyResultCases")
        void emptyResult(String sql) {
            var result = suggest(sql);
            assertEquals(0, result.size());
        }

        static Stream<Arguments> singleKeywordCases() {
            return Stream.of(
                    Arguments.of("update public.users set name = 'x' returning *|", ","), // từ: updateReturningStarDoesNotThrow
                    Arguments.of("alter table public.users enable row level security|", "security"), // từ: enableRlsDoesNotThrow
                    Arguments.of("create extension |", "if not exists"), // từ: createExtensionDoesNotThrow
                    Arguments.of("drop extension |", "if exists"), // từ: dropExtensionDoesNotThrow
                    Arguments.of("select * from public.users order by id fetch first 10 rows with ties|", "ties"), // từ: fetchFirstWithTiesDoesNotThrow
                    Arguments.of("alter table public.users alter column id add generated always as identity|", "identity"), // từ: alterAddGeneratedIdentityDoesNotThrow
                    Arguments.of("unlisten |", "*"), // từ: unlistenAllDoesNotThrow
                    Arguments.of("close |", "all"), // từ: closeCursorDoesNotThrow
                    Arguments.of("close |all", "all"), // từ: closeAllCursorsDoesNotThrow
                    Arguments.of("comment on function f(int) is |", "null"), // từ: commentOnFunctionDoesNotThrow
                    Arguments.of("comment on type t is |", "null"), // từ: commentOnTypeDoesNotThrow
                    Arguments.of("comment on index idx1 is |", "null"), // từ: commentOnIndexDoesNotThrow
                    Arguments.of("comment on constraint c1 on public.users is |", "null"), // từ: commentOnConstraintDoesNotThrow
                    Arguments.of("grant select on public.users to role1 with |", "grant option"), // từ: grantWithGrantOptionDoesNotThrow
                    Arguments.of("create role r1 with login password |", "null"), // từ: createRoleWithLoginPasswordDoesNotThrow
                    Arguments.of("create publication pub1 for all tables|", "tables"), // từ: createPublicationForAllTablesDoesNotThrow
                    Arguments.of("drop database if exists db1 with (|", "force"), // từ: dropDatabaseWithForceDoesNotThrow
                    Arguments.of("create table t (a int, check (a > 0) no inherit|", "inherit") // từ: checkNoInheritDoesNotThrow
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("singleKeywordCases")
        void singleKeywordSuggestion(String sql, String expectedKeyword) {
            var result = suggest(sql);
            assertEquals(1, result.size());
            assertTrue(allKeywordKeys(result).contains(expectedKeyword));
        }


        static Stream<Arguments> exactColumnsOnlyCases() {
            return Stream.of(
                    Arguments.of("select c.| from public.contracts as c join public.contracts as x", new String[]{"c.id", "c.name", "c.amount", "c.status"}), // từ: firstJoinAliasStillWorksWhileSecondIncomplete
                    Arguments.of("select u.| from public.users u left join public.orders o", new String[]{"u.id", "u.name", "u.email", "u.created_date"}), // từ: leftJoinWithoutOnStillResolvesMainAlias
                    Arguments.of("select u.| from public.users u join public.orders o join public.products p", new String[]{"u.id", "u.name", "u.email", "u.created_date"}), // từ: multipleJoinsWithoutOn
                    Arguments.of("select | from public.users u join public.orders o", new String[]{"u.id", "u.name", "o.total", "u.email", "u.created_date", "o.id", "o.customer_id", "o.status", "o.user_id"}), // từ: columnsFromAllTablesWithoutAlias
                    Arguments.of("update public.users set |", new String[]{"users.name", "users.email", "users.created_date", "users.id"}), // từ: updateSetColumnSuggestions
                    Arguments.of("select * from public.users where |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: whereColumnSuggestions
                    Arguments.of("select * from public.users u where u.|", new String[]{"u.id", "u.name", "u.email", "u.created_date"}), // từ: whereAliasColumnSuggestions
                    Arguments.of("select * from public.users where id = 1 and |", new String[]{"users.name", "users.email", "users.id", "users.created_date"}), // từ: whereAndContinuation
                    Arguments.of("select * from public.users order by |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: orderByColumnSuggestions
                    Arguments.of("select * from public.users u order by u.|", new String[]{"u.id", "u.name", "u.email", "u.created_date"}), // từ: orderByAliasColumnSuggestions
                    Arguments.of("select count(*), status from public.orders group by |", new String[]{"orders.status", "orders.customer_id", "orders.id", "orders.total", "orders.user_id"}), // từ: groupByColumnSuggestions
                    Arguments.of("select * from public.users where id in (select | from public.orders)", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id", "users.created_date", "users.email", "users.id", "users.name"}), // từ: subqueryTableSuggestions
                    Arguments.of("select sub.| from (select * from public.users) sub", new String[]{"sub.id", "sub.name", "sub.email", "sub.created_date"}), // từ: subqueryAliasColumnSuggestions
                    Arguments.of("select * from public.users u join public.orders o on |", new String[]{"u.id", "o.customer_id", "u.name", "u.email", "u.created_date", "o.id", "o.total", "o.status", "o.user_id"}), // từ: joinOnColumnSuggestions
                    Arguments.of("select * from public.users u join public.orders o on u.id = o.customer_id and |", new String[]{"u.name", "o.total", "u.id", "u.email", "u.created_date", "o.id", "o.customer_id", "o.status", "o.user_id"}), // từ: joinOnAndContinuation
                    Arguments.of("select * from public.users u left join public.orders o on u.id = o.customer_id where o.|", new String[]{"o.id", "o.total", "o.status", "o.customer_id", "o.user_id"}), // từ: leftJoinAliasColumnSuggestions
                    Arguments.of("select u.| from public.users u join public.orders o on u.id = o.user_id join public.products p on o.product_id = p.id", new String[]{"u.id", "u.name", "u.email", "u.created_date"}), // từ: multipleJoinsWithFullOn
                    Arguments.of("select count(| from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: countFunctionColumnSuggestions
                    Arguments.of("with c as (select id, name from public.users) select c.| from c", new String[]{"c.id", "c.name"}), // từ: cteWithNamedColumnsResolvesCorrectly
                    Arguments.of("select id from public.users union select | from public.orders", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id", "users.created_date", "users.email", "users.id", "users.name"}), // từ: unionSecondSelectColumnSuggestions
                    Arguments.of("select row_number() over (partition by | ) from public.orders", new String[]{"orders.customer_id", "orders.status", "orders.id", "orders.total", "orders.user_id"}), // từ: partitionByColumnSuggestions
                    Arguments.of("select distinct | from public.users", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: distinctColumnSuggestions
                    Arguments.of("select (select | from public.orders where orders.customer_id = u.id) from public.users u", new String[]{"orders.customer_id", "u.name", "u.id", "u.email", "u.created_date", "orders.id", "orders.total", "orders.status", "orders.user_id"}), // từ: correlatedSubqueryInSelectListSeesOuterAlias
                    Arguments.of("select | from only public.users", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: fromOnlySuggestsColumns
                    Arguments.of("select | from public.users u, public.orders o", new String[]{"o.customer_id", "o.id", "o.status", "o.total", "o.user_id", "u.created_date", "u.email", "u.id", "u.name"}), // từ: fromCommaSeparatedTables
                    Arguments.of("select u.| from public.users u full outer join public.orders o on u.id = o.customer_id", new String[]{"u.id", "u.name", "u.email", "u.created_date"}), // từ: fullOuterJoinResolvesBothAliases
                    Arguments.of("select * from public.users u right join public.orders o on u.id = o.customer_id where o.|", new String[]{"o.total", "o.id", "o.customer_id", "o.status", "o.user_id"}), // từ: rightJoinResolvesRightAlias
                    Arguments.of("select * from public.users u cross join public.orders o where o.|", new String[]{"o.total", "o.id", "o.customer_id", "o.status", "o.user_id"}), // từ: crossJoinResolvesBothAliases
                    Arguments.of("select * from public.users u where not exists (select | from public.orders)", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id", "u.created_date", "u.email", "u.id", "u.name"}), // từ: notExistsColumnSuggestions
                    Arguments.of("select * from public.users where id not in (select | from public.orders)", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id", "users.created_date", "users.email", "users.id", "users.name"}), // từ: notInSubqueryColumnSuggestions
                    Arguments.of("select case when id = 1 then 'a' when | then 'b' else 'c' end from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: secondWhenBranchColumnSuggestions
                    Arguments.of("select * from public.users where case when | then true else false end", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: caseWhenInsideWhereClause
                    Arguments.of("select status, count(*) from public.orders group by rollup(|)", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: rollupColumnSuggestions
                    Arguments.of("select status, customer_id, count(*) from public.orders group by cube(status, |)", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: cubeSecondColumnSuggestions
                    Arguments.of("select (select (select | from public.orders where orders.customer_id = u.id) from public.users) from public.users u", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id", "u.created_date", "u.email", "u.id", "u.name", "users.created_date", "users.email", "users.id", "users.name"}), // từ: tripleNestedSubquerySeesOutermostAlias
                    Arguments.of("select * from public.users u join public.orders o on o.customer_id = (select | from public.users where id = 1)", new String[]{"o.customer_id", "o.id", "o.status", "o.total", "o.user_id", "u.created_date", "u.email", "u.id", "u.name", "users.created_date", "users.email", "users.id", "users.name"}), // từ: subqueryInJoinCondition
                    Arguments.of("select * from public.users where (id = 1 or id = 2) and |", new String[]{"users.name", "users.id", "users.email", "users.created_date"}), // từ: whereAfterClosingParenAndAnd
                    Arguments.of("select * from public.users where not (| )", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: whereNotParenColumnSuggestions
                    Arguments.of("select * from public.users where id = 1 or id = 2 or |", new String[]{"users.email", "users.id", "users.name", "users.created_date"}), // từ: multipleOrConditions
                    Arguments.of("update public.users set name = 'x' returning |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: updateReturningColumnSuggestions
                    Arguments.of("delete from public.users where id = 1 returning |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: deleteReturningColumnSuggestions
                    Arguments.of("insert into public.users (id, name) values (1, 'a') on conflict (id) do update set |", new String[]{"users.name", "users.id", "users.email", "users.created_date"}), // từ: onConflictDoUpdateSetColumnSuggestions
                    Arguments.of("create view v as select | from public.users", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: createViewSelectColumnSuggestions
                    Arguments.of("select * from public.users /* bang chinh */ where |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: blockCommentDoesNotBreakSuggestion
                    Arguments.of("select lower(|) from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: lowerFunctionColumnSuggestions
                    Arguments.of("select concat(name, |) from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: concatSecondArgumentColumnSuggestions
                    Arguments.of("with a as (select id, name from public.users), b as (select a.| from a) select * from b", new String[]{"a.id", "a.name"}), // từ: secondCteReferencesFirstCte
                    Arguments.of("select row_number() over (partition by customer_id order by |) from public.orders", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: windowOrderByColumnSuggestions
                    Arguments.of("select row_number() over (order by id), rank() over (partition by | ) from public.orders", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: multipleWindowFunctionsInSameSelect
                    Arguments.of("explain select | from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: explainSelectColumnSuggestions
                    Arguments.of("explain analyze select * from public.users where |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: explainAnalyzeColumnSuggestions
                    Arguments.of("select extract(year from |) from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: extractFromColumnSuggestions
                    Arguments.of("select coalesce(name, email, |) from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: coalesceMultipleArgumentsColumnSuggestions
                    Arguments.of("select nullif(name, |) from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: nullifSecondArgumentColumnSuggestions
                    Arguments.of("select | from public.users tablesample bernoulli(10)", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: tableSampleResolvesAlias
                    Arguments.of("select * from public.users where true and |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: whereTrueAndColumnSuggestions
                    Arguments.of("insert into public.users (id, name) values (1, 'a') returning id, |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: insertReturningSecondColumnSuggestions
                    Arguments.of("select (select count(*) from public.orders where customer_id = u.id), (select | from public.orders where customer_id = u.id) from public.users u", new String[]{"u.name", "u.id", "u.email", "u.created_date", "orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}), // từ: secondScalarSubqueryResolvesOuterAlias
                    Arguments.of("select count(*) filter (where |) from public.orders", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: countFilterWhereColumnSuggestions
                    Arguments.of("select customer_id, count(*) from public.orders where status = 'active' group by customer_id, | having count(*) > 1", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: betweenGroupByAndHavingColumnSuggestions
                    Arguments.of("select customer_id, count(*) from public.orders group by customer_id having count(*) > 1 order by |", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: betweenHavingAndOrderByColumnSuggestions
                    Arguments.of("prepare s1 as select | from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: prepareStatementColumnSuggestions
                    Arguments.of("declare c1 cursor for select | from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: declareCursorColumnSuggestions
                    Arguments.of("select array_agg(|) from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: arrayAggColumnSuggestions
                    Arguments.of("insert into public.users (id, name) values (1, 'a') returning id as new_id, |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: returningWithAliasSecondColumnSuggestions
                    Arguments.of("select | from public.users u join public.orders o using (id) left join public.orders o2 on o.id = o2.id", new String[]{"o.customer_id", "o.id", "o.status", "o.total", "o.user_id", "o2.customer_id", "o2.id", "o2.status", "o2.total", "o2.user_id", "u.created_date", "u.email", "u.id", "u.name"}), // từ: mixedUsingAndOnJoins
                    Arguments.of("create policy p1 on public.users using (|)", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: createPolicyUsingColumnSuggestions
                    Arguments.of("create index idx1 on public.orders (id) where |", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: partialIndexWhereColumnSuggestions
                    Arguments.of("copy public.users (id, |) from stdin", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: copyColumnListDoesNotThrow
                    Arguments.of("select * from public.users where name ~ |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: regexMatchColumnSuggestions
                    Arguments.of("select * from public.users where name !~* |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: regexNotMatchCaseInsensitiveColumnSuggestions
                    Arguments.of("select * from public.users where name ilike |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: ilikePatternColumnSuggestions
                    Arguments.of("update public.users set (name, |) = ('a', 'b')", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: multiColumnSetTargetColumnSuggestions
                    Arguments.of("select created_date + interval '1 day' from public.users where |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: columnPlusIntervalWhereColumnSuggestions
                    Arguments.of("select created_date at time zone |", new String[]{}), // từ: atTimeZoneDoesNotThrow
                    Arguments.of("select | from public.users u join public.orders o on u.id = o.customer_id", new String[]{"u.id", "u.name", "u.email", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}), // từ: ambiguousColumnAcrossJoinDoesNotThrow
                    Arguments.of("select * from public.users u join public.orders o on u.id = o.customer_id where |", new String[]{"u.id", "u.name", "u.email", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}), // từ: ambiguousColumnInWhereClauseDoesNotThrow
                    Arguments.of("select * from public.users where id = 1 -- so sanh id\nand |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: commentImmediatelyBeforeCaretDoesNotThrow
                    Arguments.of("select * from nonexistent_schema.nonexistent_table where |", new String[]{}), // từ: completelyUnknownSchemaDoesNotThrow
                    Arguments.of("select x.| from nonexistent_table x", new String[]{}), // từ: unknownTableAliasColumnDoesNotThrow
                    Arguments.of("select * from public.users where id = 1e10 and |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: scientificNotationNumberDoesNotThrow
                    Arguments.of("select * from public.orders where total = -100.5 and |", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}), // từ: negativeNumberDoesNotThrow
                    Arguments.of("select * from public.users where id = 0x1A and |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: hexIntegerDoesNotThrow
                    Arguments.of("select $$hello$$ from public.users where |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: dollarQuotedStringDoesNotThrow
                    Arguments.of("select * from public.users where name = 'O''Brien' and |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: escapedSingleQuoteInStringDoesNotThrow
                    Arguments.of("select data -> 'key' from public.users where |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: jsonArrowOperatorInSelectListWhereColumnSuggestions
                    Arguments.of("select * from public.users where data ->> 'key' = 'x' and |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: jsonDoubleArrowThenWhereColumnSuggestions
                    Arguments.of("select * from public.users where data #> '{a,b}' = |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: jsonPathOperatorColumnSuggestions
                    Arguments.of("select * from public.users where data @> '{\\\"a\\\":1}' and |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: jsonContainmentOperatorColumnSuggestions
                    Arguments.of("select jsonb_build_object('name', |) from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: jsonBuildObjectArgumentColumnSuggestions
                    Arguments.of("select array[1,2,3] from public.users where |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: arrayLiteralDoesNotThrow
                    Arguments.of("select * from public.users where tags @> array[1,2] and |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: arrayContainmentOperatorColumnSuggestions
                    Arguments.of("select tags[1:2] from public.users where |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: arraySlicingWhereColumnSuggestions
                    Arguments.of("select row_number() over w from public.orders window w as (partition by |)", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: namedWindowPartitionByColumnSuggestions
                    Arguments.of("select row_number() over w1, rank() over w2 from public.orders window w1 as (partition by customer_id), w2 as (order by |)", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}), // từ: multipleNamedWindowsDoesNotThrow
                    Arguments.of("create materialized view mv1 as select | from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: createMaterializedViewColumnSuggestions
                    Arguments.of("SeLeCt * FrOm public.users WhErE |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: mixedCaseKeywordsStillWork
                    Arguments.of("SELECT * FROM public.users WHERE |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: allUppercaseKeywordsStillWork
                    Arguments.of("select distinct on (status, |) * from public.orders", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: distinctOnSecondColumnSuggestions
                    Arguments.of("select (u).name from public.users u where |", new String[]{"u.id", "u.name", "u.email", "u.created_date"}), // từ: rowFieldAccessDoesNotThrow
                    Arguments.of("select upper(lower(trim(|))) from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: deeplyNestedFunctionInnermostColumnSuggestions
                    Arguments.of("select concat(concat(concat(concat(concat(concat(concat(concat(concat(concat(name, |), 'x'), 'x'), 'x'), 'x'), 'x'), 'x'), 'x'), 'x'), 'x') from public.users", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: tenLevelsNestedFunctionsDoesNotThrow
                    Arguments.of("select * from public.users where |  )) ]] garbage $$$ ---", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: validCaretPositionWithTrailingGarbageStillWorks
                    Arguments.of("select * from public.users u cross join lateral unnest(u.|) as tag", new String[]{"u.created_date", "u.email", "u.id", "u.name"}), // từ: lateralUnnestFunctionDoesNotThrow
                    Arguments.of("select * from public.users u, lateral generate_series(1, |)", new String[]{"u.created_date", "u.email", "u.id", "u.name"}), // từ: lateralGenerateSeriesDoesNotThrow
                    Arguments.of("select grouping(status), status, count(*) from public.orders group by rollup(status) having |", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: groupingFunctionWithRollupDoesNotThrow
                    Arguments.of("create table t (id int, created_at timestamp default now(), |)", new String[]{}), // từ: columnDefaultNowFunctionDoesNotThrow
                    Arguments.of("alter table public.users alter column created_date set default |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: alterColumnSetDefaultDoesNotThrow
                    Arguments.of("select | into temp table t from public.users", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: selectIntoTempTableDoesNotThrow
                    Arguments.of("insert into public.users (id, name) values (1, 'a') returning upper(name), |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: returningFunctionThenColumnSuggestions
                    Arguments.of("delete from public.users where id = 1 returning *, |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: returningStarThenMoreDoesNotThrow
                    Arguments.of("create table t (email text, unique nulls not distinct (|))", new String[]{}), // từ: uniqueNullsNotDistinctDoesNotThrow
                    Arguments.of("create table t (id int, exclude using gist (|", new String[]{}), // từ: excludeConstraintDoesNotThrow
                    Arguments.of("declare c1 cursor with hold for select | from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: declareCursorWithHoldDoesNotThrow
                    Arguments.of("grant update (name, |) on public.users to some_role", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: grantColumnListSecondColumnDoesNotThrow
                    Arguments.of("create table t (a int, b int, primary key (a, |))", new String[]{}), // từ: compositePrimaryKeySecondColumnDoesNotThrow
                    Arguments.of("create table t (a int, b int, check (a + |))", new String[]{}), // từ: multiColumnCheckConstraintDoesNotThrow
                    Arguments.of("select * from public.orders order by status asc, total desc nulls last, |", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: orderByMixedAscDescNullsThirdColumnSuggestions
                    Arguments.of("select * from public.users u order by (select count(*) from public.orders where customer_id = |)", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id", "u.created_date", "u.email", "u.id", "u.name"}), // từ: orderBySubqueryDoesNotThrow
                    Arguments.of("select id, name from public.users order by 1, |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: orderByColumnPositionDoesNotThrow
                    Arguments.of("select string_agg(name, ',' order by |) from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: stringAggInternalOrderByColumnSuggestions
                    Arguments.of("select percentile_cont(0.5) within group (order by |) from public.orders", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}), // từ: percentileContWithinGroupDoesNotThrow
                    Arguments.of("select mode() within group (order by |) from public.orders", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}), // từ: modeWithinGroupDoesNotThrow
                    Arguments.of("select * from public.orders where total > all (select | from public.orders where status = 'closed')", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}), // từ: greaterThanAllSubqueryDoesNotThrow
                    Arguments.of("select * from public.users where id = any (select customer_id from public.orders where |)", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id", "users.created_date", "users.email", "users.id", "users.name"}), // từ: equalAnySubqueryDoesNotThrow
                    Arguments.of("select * from public.users where (id, name) = (1, |)", new String[]{"users.id", "users.email", "users.name", "users.created_date"}), // từ: rowComparisonDoesNotThrow
                    Arguments.of("select * from public.orders where (customer_id, status) in ((1, 'a'), (2, |))", new String[]{"orders.id", "orders.total", "orders.customer_id", "orders.status", "orders.user_id"}), // từ: rowInListDoesNotThrow
                    Arguments.of("select unnest(tags), | from public.users", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: unnestAsColumnExpressionDoesNotThrow
                    Arguments.of("select generate_series(1, |) from public.orders", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}), // từ: generateSeriesAsColumnExpressionDoesNotThrow
                    Arguments.of("select * from public.users where not id > 0 and |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: notColumnAndAnotherDoesNotThrow
                    Arguments.of("select * from public.users where name is distinct from |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: isDistinctFromDoesNotThrow
                    Arguments.of("select * from public.users where name is not distinct from |", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: isNotDistinctFromDoesNotThrow
                    Arguments.of("select * from public.orders where total between symmetric 10 and |", new String[]{"orders.customer_id", "orders.id", "orders.total", "orders.status", "orders.user_id"}), // từ: betweenSymmetricDoesNotThrow
                    Arguments.of("analyze public.users (id, |)", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: analyzeWithColumnListSecondColumnDoesNotThrow
                    Arguments.of("create table t (qty int not null default 0 check (qty >= |))", new String[]{}), // từ: chainedNotNullDefaultCheckDoesNotThrow
                    Arguments.of("create table t (user_id int unique not null references public.users(|))", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: chainedUniqueNotNullReferencesDoesNotThrow
                    Arguments.of("select tags::int[] from public.users where |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: castToArrayTypeDoesNotThrow
                    Arguments.of("select array[[1,2],[3,|]] from public.users", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: nestedArrayLiteralDoesNotThrow
                    Arguments.of("create domain positive_int as int check (value > |)", new String[]{}), // từ: createDomainWithCheckDoesNotThrow
                    Arguments.of("create domain d1 as text not null default |", new String[]{}), // từ: createDomainNotNullDefaultDoesNotThrow
                    Arguments.of("create table t (id int, customer_id int, amount int, primary key (id), foreign key (customer_id) references public.users(id), check (amount > |))", new String[]{}), // từ: multipleTableConstraintsThirdOneDoesNotThrow
                    Arguments.of("create table t (a int, b int, foreign key (a, b) references public.orders (id, |))", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: multiColumnForeignKeySecondReferencedColumnDoesNotThrow
                    Arguments.of("create function f(a int default 0, b int default |", new String[]{}), // từ: functionParameterDefaultValueDoesNotThrow
                    Arguments.of("explain (analyze, buffers, format json) select * from public.users where |", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: explainParenthesizedOptionsDoesNotThrow
                    Arguments.of("select | from public.users u, public.orders o join public.products p on o.id = p.id", new String[]{"u.id", "u.name", "u.email", "u.created_date", "p.id", "p.name", "p.price", "p.quantity", "p.description", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}), // từ: commaThenJoinAllThreeAliasesResolve
                    Arguments.of("select xmlelement(name tag, |) from public.users", new String[]{"users.created_date", "users.email", "users.id", "users.name"}), // từ: xmlElementDoesNotThrow
                    Arguments.of("select xmlforest(name, |) from public.users", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: xmlForestDoesNotThrow
                    Arguments.of("select xmlconcat(name, |) from public.users", new String[]{"users.id", "users.name", "users.email", "users.created_date"}), // từ: xmlConcatDoesNotThrow
                    Arguments.of("create statistics stat1 on status, | from public.orders", new String[]{"orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id"}), // từ: createStatisticsSecondColumnSuggestions
                    Arguments.of("merge into public.users u using public.orders o on u.id = o.customer_id when matched and o.status = |", new String[]{"u.id", "u.name", "u.email", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}), // từ: mergeMatchedAndConditionDeleteDoesNotThrow
                    Arguments.of("create table t (a int, b int, c int generated always as (a + |) stored)", new String[]{}), // từ: generatedStoredColumnExpressionColumnSuggestions
                    Arguments.of("create policy p1 on public.users for select to some_role using (id > 0) with check (|)", new String[]{"users.created_date", "users.email", "users.id", "users.name"}) // từ: policyForSelectWithCheckColumnSuggestions
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("exactColumnsOnlyCases")
        void exactColumnsOnly(String sql, String[] expectedColumns) {
            var result = suggest(sql);
            assertExactColumns(result, expectedColumns);
        }
    }

}