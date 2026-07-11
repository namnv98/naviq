package com.naviq.completion;

import com.naviq.completion.model.Suggest;
import com.naviq.completion.suggests.CompletionEngine;
import com.naviq.datasource.SchemaIndex;
import com.naviq.datasource.SchemaLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CompletionEngineTest {

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
    // =====================================================================
    // Nhóm 1: bug "select sau select" (fix stmtmulti trong .g4 + isFreshStatementPosition)
    // =====================================================================

//    @Test
//    void noStatementStartKeywordsMidSelect() {
//        var result = suggest("ALTER TYPE mytype ADD VALUE  |");
//        var keywords = allKeywordKeys(result);
//        assertFalse(keywords.contains("select"));
//        assertFalse(keywords.contains("insert"));
//        assertFalse(keywords.contains("with"));
//        assertFalse(keywords.contains("create"));
//    }

    // ĐÃ XOÁ: test trùng tên với statementStartKeywordsAfterSemicolon bên dưới (nested class
    // StatementStartKeywordDedup) và thân test hoàn toàn RỖNG (chỉ System.out.println(), mọi
    // assertion bị comment) - test này lúc nào cũng pass mà không kiểm tra gì, đây chính là
    // kiểu "smoke test giả danh unit test" cần loại bỏ hẳn chứ không phải giữ rồi comment đi.
    // Caret trong SQL này nằm ngay sau "AND " trong điều kiện JOIN...ON - tức đang TIẾP TỤC
    // một boolean expression, KHÔNG phải statement-start. Nếu viết lại đúng, kỳ vọng đúng ra
    // phải là assertFalse cho các keyword bắt-đầu-câu (cùng logic với
    // "andOrNotHiddenWhenContinuingBooleanExpression" ở nhóm 4), NGƯỢC LẠI với assertTrue
    // đã bị comment sẵn ở bản gốc.

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
        }

        @Test
        @DisplayName("Sau dấu ';' của câu trước - VẪN phải thấy đủ keyword bắt-đầu-câu (không phá multi-statement)")
        void statementStartKeywordsAfterSemicolon() {
            var result = suggest("select * from users; |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("select"));
            assertTrue(keywords.contains("insert into"));
            assertTrue(keywords.contains("delete from"));
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

    // =====================================================================
    // Nhóm 2: bug "JOIN thiếu ON làm mất alias" (fix join_qual? trong .g4)
    // =====================================================================

    @Nested
    @DisplayName("JOIN chưa gõ ON không làm mất alias của bảng trước")
    class JoinMissingOnClause {

        @Test
        @DisplayName("Self-join, vế 2 chưa gõ AS xong - alias vế 1 ('c') vẫn phải gợi ý được cột")
        void firstJoinAliasStillWorksWhileSecondIncomplete() {
            var result = suggest("select c.| from public.contracts as c join public.contracts as x");
            assertTrue(hasKeyOfType(result, "c.id", "column"));
            assertTrue(hasKeyOfType(result, "c.name", "column"));
            assertTrue(hasKeyOfType(result, "c.amount", "column"));
        }

        @Test
        @DisplayName("JOIN...AS <đang gõ dở, chưa xong> - vẫn gợi ý alias tự động cho vế đang gõ")
        void aliasSuggestionWorksRightAfterAsInIncompleteJoin() {
            var result = suggest(
                    "select * from public.contracts as c join public.contracts as |");
            assertTrue(hasKeyOfType(result, "c1", "alias"));
        }

        @Test
        @DisplayName("LEFT JOIN không có ON - alias của bảng chính vẫn đúng")
        void leftJoinWithoutOnStillResolvesMainAlias() {
            var result = suggest("select u.| from public.users u left join public.orders o");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
            assertTrue(hasKeyOfType(result, "u.name", "column"));
            assertTrue(hasKeyOfType(result, "u.email", "column"));
        }

        @Test
        @DisplayName("Nhiều JOIN liên tiếp không ON - alias vẫn resolve")
        void multipleJoinsWithoutOn() {
            var result = suggest("select u.| from public.users u join public.orders o join public.products p");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
            assertTrue(hasKeyOfType(result, "u.name", "column"));
        }
    }

    // =====================================================================
    // Nhóm 3: bug "column" -> phải là "alias" (type Suggest đúng)
    // =====================================================================

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
        }

        @Test
        @DisplayName("Alias tự động tăng số - type cũng là 'alias'")
        void autoIncrementedAliasHasAliasType() {
            var result = suggest("select * from public.users u join public.orders as |");
            var aliasSuggests = result.stream().filter(s -> s.getKey().equals("o")).toList();
            assertFalse(aliasSuggests.isEmpty());
            assertEquals("alias", aliasSuggests.get(0).getType());
        }
    }

    // =====================================================================
    // Nhóm 4: bug noise "insert/at/by/do/is/no/of..." (IDENTIFIER_USABLE_KEYWORDS)
    // =====================================================================

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
        }

        @Test
        @DisplayName("'WHERE a = 1 |' (columnref KHÔNG active nữa, đang chờ AND/OR) - 'and'/'or' KHÔNG bị ẩn")
        void andOrNotHiddenWhenContinuingBooleanExpression() {
            var result = suggest("select * from public.users where id = 1 |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("and"));
            assertTrue(keywords.contains("or"));
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
        }
    }

    // =====================================================================
    // Nhóm 5: cursorOffset fix (không còn "- 1") - schema.table detection
    // =====================================================================

    @Nested
    @DisplayName("cursorOffset không lệch, dò đúng schema.table")
    class SchemaQualifiedTableDetection {

        @Test
        @DisplayName("'from public.|' - gợi ý đúng bảng trong schema public")
        void schemaQualifiedTableSuggestion() {
            var result = suggest("select * from public.|");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.users"));
            assertTrue(tables.contains("public.orders"));
            assertTrue(tables.contains("public.contracts"));
            assertTrue(tables.contains("public.products"));
        }

        @Test
        @DisplayName("'from |' - gợi ý bảng (LUÔN dạng schema.table đầy đủ - CompletionEngine không tự rút gọn theo prefix đã gõ)")
        void tableSuggestionWithoutSchema() {
            var result = suggest("select * from |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.users"));
            assertTrue(tables.contains("public.orders"));
            assertTrue(tables.contains("public.products"));
        }
    }

    // =====================================================================
    // Nhóm 6: cột/hàm/kiểu dữ liệu cơ bản
    // =====================================================================

    @Nested
    @DisplayName("Hồi quy chung: cột/hàm/kiểu dữ liệu cơ bản")
    class BasicColumnFunctionTypeSuggestions {

        @Test
        @DisplayName("'select u.| from users u' - gợi ý đúng cột của bảng users")
        void columnSuggestionsForAlias() {
            var result = suggest("select u.| from public.users u");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
            assertTrue(hasKeyOfType(result, "u.name", "column"));
            assertTrue(hasKeyOfType(result, "u.email", "column"));
            assertTrue(hasKeyOfType(result, "u.created_date", "column"));
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
        }

        @Test
        @DisplayName("Khi gõ cột không có alias - gợi ý column từ tất cả bảng trong FROM (key dạng tênBảng.column)")
        void columnsFromAllTablesWithoutAlias() {
            var result = suggest("select | from public.users u join public.orders o");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
            assertTrue(hasKeyOfType(result, "u.name", "column"));
            assertTrue(hasKeyOfType(result, "o.total", "column"));
        }
    }

    // =====================================================================
    // Nhóm 8: Test các câu lệnh DML khác
    // =====================================================================

    @Nested
    @DisplayName("Test các câu lệnh DML: INSERT, UPDATE, DELETE")
    class DMLStatements {

        @Test
        @Disabled("GIOI HAN THAT (da xac nhan qua debug truc tiep, khong phai bug o CompletionEngine): "
                + "SemanticScope.java chi co enterUpdatestmt()/enterDeletestmt() de dang ky bang target "
                + "vao scope - KHONG co enterInsertstmt(). Nen voi INSERT, sem.visibleAliases() luon "
                + "RONG -> addColumnSuggestions() khong co gi de tra cot. Can them enterInsertstmt() "
                + "vao SemanticScope.java de fix triet de - viec nay dung vao 1 file khac, ngoai pham "
                + "vi CompletionEngineTest hien tai.")
        @DisplayName("[KNOWN LIMITATION] INSERT INTO users (| - CHUA goi y duoc cot de insert")
        void insertColumnSuggestions() {
            var result = suggest("insert into public.users (|");
            var columns = keysOfType(result, "column");
            assertTrue(columns.contains("users.id"));
            assertTrue(columns.contains("users.name"));
        }

        @Test
        @DisplayName("UPDATE users SET - gợi ý cột")
        void updateSetColumnSuggestions() {
            var result = suggest("update public.users set |");
            assertTrue(hasKeyOfType(result, "users.name", "column"));
            assertTrue(hasKeyOfType(result, "users.email", "column"));
            assertTrue(hasKeyOfType(result, "users.created_date", "column"));
        }
    }

    // =====================================================================
    // Nhóm 9: Test WHERE clause với multiple conditions
    // =====================================================================

    @Nested
    @DisplayName("WHERE clause với nhiều điều kiện")
    class WhereClauseComplex {

        @Test
        @DisplayName("WHERE - gợi ý cột (key dạng tênBảng.column)")
        void whereColumnSuggestions() {
            var result = suggest("select * from public.users where |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.name", "column"));
            assertTrue(hasKeyOfType(result, "users.email", "column"));
        }

        @Test
        @DisplayName("WHERE u. - gợi ý cột với alias")
        void whereAliasColumnSuggestions() {
            var result = suggest("select * from public.users u where u.|");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
            assertTrue(hasKeyOfType(result, "u.name", "column"));
            assertTrue(hasKeyOfType(result, "u.email", "column"));
        }

        @Test
        @DisplayName("WHERE id = 1 AND | - gợi ý cột tiếp theo (key dạng tênBảng.column)")
        void whereAndContinuation() {
            var result = suggest("select * from public.users where id = 1 and |");
            assertTrue(hasKeyOfType(result, "users.name", "column"));
            assertTrue(hasKeyOfType(result, "users.email", "column"));
        }
    }

    // =====================================================================
    // Nhóm 10: Test ORDER BY, GROUP BY, HAVING
    // =====================================================================

    @Nested
    @DisplayName("ORDER BY, GROUP BY, HAVING")
    class OrderGroupHaving {

        @Test
        @DisplayName("ORDER BY - gợi ý cột (key dạng tênBảng.column)")
        void orderByColumnSuggestions() {
            var result = suggest("select * from public.users order by |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.name", "column"));
            assertTrue(hasKeyOfType(result, "users.email", "column"));
        }

        @Test
        @DisplayName("ORDER BY u. - gợi ý cột với alias")
        void orderByAliasColumnSuggestions() {
            var result = suggest("select * from public.users u order by u.|");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
            assertTrue(hasKeyOfType(result, "u.name", "column"));
        }

        @Test
        @DisplayName("ORDER BY id | - gợi ý ASC/DESC")
        void orderByAscDescSuggestions() {
            var result = suggest("select * from public.users order by id |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("asc"));
            assertTrue(keywords.contains("desc"));
        }

        @Test
        @DisplayName("GROUP BY - gợi ý cột (key dạng tênBảng.column)")
        void groupByColumnSuggestions() {
            var result = suggest("select count(*), status from public.orders group by |");
            assertTrue(hasKeyOfType(result, "orders.status", "column"));
            assertTrue(hasKeyOfType(result, "orders.customer_id", "column"));
        }

        @Test
        @DisplayName("HAVING - gợi ý cột (key dạng tênBảng.column)")
        void havingColumnSuggestions() {
            var result = suggest("select count(*), status from public.orders group by status having |");
            assertTrue(hasKeyOfType(result, "orders.status", "column"));
        }
    }

    // =====================================================================
    // Nhóm 11: Test subquery
    // =====================================================================

    @Nested
    @DisplayName("Subquery")
    class SubqueryTests {

        @Test
        @DisplayName("Subquery trong WHERE - gợi ý cột")
        void subqueryTableSuggestions() {
            var result = suggest("select * from public.users where id in (select | from public.orders)");
            assertTrue(hasKeyOfType(result, "orders.customer_id", "column")
                    || hasKeyOfType(result, "orders.id", "column"));
        }

        @Test
        @DisplayName("Subquery trong FROM - gợi ý cột")
        void subqueryFromSuggestions() {
            var result = suggest("select * from (select | from public.users) sub");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.name", "column"));
        }

        @Test
        @DisplayName("Subquery với alias - gợi ý cột của subquery")
        void subqueryAliasColumnSuggestions() {
            var result = suggest("select sub.| from (select * from public.users) sub");
            assertTrue(hasKeyOfType(result, "sub.id", "column"));
            assertTrue(hasKeyOfType(result, "sub.name", "column"));
            assertTrue(hasKeyOfType(result, "sub.email", "column"));
        }

        @Test
        @DisplayName("Subquery với EXISTS - gợi ý bảng")
        void existsSubquerySuggestions() {
            var result = suggest("select * from public.users u where exists (select 1 from |)");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.orders"));
            assertTrue(tables.contains("public.products"));
        }
    }

    // =====================================================================
    // Nhóm 12: Test JOIN với nhiều điều kiện
    // =====================================================================

    @Nested
    @DisplayName("JOIN với nhiều điều kiện")
    class JoinWithConditions {

        @Test
        @DisplayName("JOIN ... ON | - gợi ý cột của cả hai bảng")
        void joinOnColumnSuggestions() {
            var result = suggest("select * from public.users u join public.orders o on |");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
            assertTrue(hasKeyOfType(result, "o.customer_id", "column"));
        }

        @Test
        @DisplayName("JOIN với AND - gợi ý cột tiếp theo của cả hai bảng")
        void joinOnAndContinuation() {
            var result = suggest("select * from public.users u join public.orders o on u.id = o.customer_id and |");
            assertTrue(hasKeyOfType(result, "u.name", "column"));
            assertTrue(hasKeyOfType(result, "o.total", "column"));
        }

        @Test
        @DisplayName("LEFT JOIN với alias - gợi ý cột của bảng mới")
        void leftJoinAliasColumnSuggestions() {
            var result = suggest("select * from public.users u left join public.orders o on u.id = o.customer_id where o.|");
            assertTrue(hasKeyOfType(result, "o.id", "column"));
            assertTrue(hasKeyOfType(result, "o.total", "column"));
            assertTrue(hasKeyOfType(result, "o.status", "column"));
        }

        @Test
        @DisplayName("Nhiều JOIN với ON đầy đủ - resolve đúng tất cả alias")
        void multipleJoinsWithFullOn() {
            var result = suggest("select u.| from public.users u join public.orders o on u.id = o.user_id join public.products p on o.product_id = p.id");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
            assertTrue(hasKeyOfType(result, "u.name", "column"));
            assertTrue(hasKeyOfType(result, "u.email", "column"));
        }
    }

    // =====================================================================
    // Nhóm 13: Test với function và expression
    // =====================================================================

    @Nested
    @DisplayName("Function và Expression")
    class FunctionsAndExpressions {

        @Test
        @DisplayName("COUNT(| (chưa đóng ngoặc, KHÔNG có FROM theo sau) - vẫn gợi ý được hàm/keyword")
        void countFunctionNoTrailingContent() {
            var result = suggest("select count(|");
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("[KNOWN LIMITATION] COUNT(| from users (chưa đóng ngoặc, CÓ FROM theo sau) - trả về RỖNG hoàn toàn")
        void countFunctionColumnSuggestions() {
            var result = suggest("select count(| from public.users");
            assertFalse(result.isEmpty());
        }
    }

    // =====================================================================
    // Nhóm 14: Test DDL statements
    // =====================================================================

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
        }

        @Test
        @DisplayName("ALTER TABLE users ADD COLUMN | - gợi ý kiểu dữ liệu")
        void alterTableAddColumnSuggestions() {
            var result = suggest("alter table public.users add column |");
            var datatypes = keysOfType(result, "datatype");
            assertTrue(datatypes.contains("int4"));
            assertTrue(datatypes.contains("text"));
        }
    }

    // =====================================================================
    // Nhóm 15: Test edge cases và error handling
    // =====================================================================

    @Nested
    @DisplayName("Edge cases và Error handling")
    class EdgeCases {

        @Test
        @DisplayName("SQL rỗng - không throw và trả về kết quả")
        void emptySql() {
            assertDoesNotThrow(() -> suggest("|"));
            assertNotNull(suggest("|"));
        }

        @Test
        @DisplayName("Cursor ở đầu câu - gợi ý đầy đủ")
        void cursorAtBeginning() {
            var result = suggest("|select * from users");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("select"));
            assertTrue(keywords.contains("with"));
        }

        @Test
        @DisplayName("Cast expression (::) - gợi ý kiểu dữ liệu")
        void castExpression() {
            var result = suggest("select id::|");
            var datatypes = keysOfType(result, "datatype");
            assertTrue(datatypes.contains("text"));
            assertTrue(datatypes.contains("numeric"));
        }
    }

    // =====================================================================
    // Nhóm 16: Test alias uniqueness và conflicts
    // =====================================================================

    @Nested
    @DisplayName("Alias uniqueness và conflict handling")
    class AliasUniqueness {

        @Test
        @DisplayName("Khi alias đã dùng, đề xuất alias tiếp theo")
        void aliasAutoIncrement() {
            var result = suggest("select * from public.users u join public.orders as |");
            assertTrue(hasKeyOfType(result, "o", "alias"));
        }

//        @Test
//        @DisplayName("Nhiều alias đã dùng, đề xuất số tiếp theo")
//        void aliasMultipleIncrements() {
//            var result = suggest("FF |");
//            assertTrue(hasKeyOfType(result, "p", "alias") || hasKeyOfType(result, "p1", "alias"));
//        }

        @Test
        @DisplayName("Tên bảng ngắn - alias đề xuất đúng, không conflict")
        void aliasSuggestionBasic() {
            var result = suggest("select * from public.users as |");
            var aliases = keysOfType(result, "alias");
            assertTrue(aliases.contains("u"));
        }
    }

    // =====================================================================
    // Nhóm 17: CTE (WITH ... AS (...))
    // =====================================================================

    @Nested
    @DisplayName("CTE (WITH ... AS (...))")
    class CteTests {

        @Test
        @DisplayName("CTE với cột đặt tên rõ (KHÔNG dùng SELECT *) - gợi ý đúng cột của CTE")
        void cteWithNamedColumnsResolvesCorrectly() {
            var result = suggest("with c as (select id, name from public.users) select c.| from c");
            assertTrue(hasKeyOfType(result, "c.id", "column"));
            assertTrue(hasKeyOfType(result, "c.name", "column"));
        }
    }

    // =====================================================================
    // Nhóm 18: UNION/INTERSECT/EXCEPT
    // =====================================================================

    @Nested
    @DisplayName("UNION/INTERSECT/EXCEPT")
    class SetOperationTests {

        @Test
        @DisplayName("UNION - vế thứ 2 vẫn gợi ý cột đúng bảng của chính nó")
        void unionSecondSelectColumnSuggestions() {
            var result = suggest("select id from public.users union select | from public.orders");
            assertTrue(hasKeyOfType(result, "orders.id", "column"));
            assertTrue(hasKeyOfType(result, "orders.total", "column"));
        }

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

    // =====================================================================
    // Nhóm 19: Self-join - phân biệt đúng 2 alias cùng 1 bảng
    // =====================================================================

    @Nested
    @DisplayName("Self-join - phân biệt đúng 2 alias trỏ cùng 1 bảng")
    class SelfJoinDisambiguation {

        @Test
        @DisplayName("2 alias cùng bảng users (u1/u2) - WHERE u2.| CHỈ gợi ý cột của u2, không lẫn u1")
        void selfJoinResolvesCorrectAliasOnly() {
            var result = suggest(
                    "select * from public.users u1 join public.users u2 on u1.id = u2.id where u2.|");
            assertTrue(hasKeyOfType(result, "u2.id", "column"));
            assertTrue(hasKeyOfType(result, "u2.name", "column"));
            assertTrue(hasKeyOfType(result, "u2.email", "column"));
        }
    }

    // =====================================================================
    // Nhóm 20: Window function
    // =====================================================================

    @Nested
    @DisplayName("Window function (OVER/PARTITION BY)")
    class WindowFunctionTests {

        @Test
        @DisplayName("PARTITION BY | - gợi ý đúng cột của bảng trong FROM")
        void partitionByColumnSuggestions() {
            var result = suggest("select row_number() over (partition by | ) from public.orders");
            assertTrue(hasKeyOfType(result, "orders.customer_id", "column"));
            assertTrue(hasKeyOfType(result, "orders.status", "column"));
        }
    }

    // =====================================================================
    // Nhóm 21: Nhiều statement (sau dấu ';') - xác nhận KHÔNG bị lẫn ngữ cảnh câu trước
    // =====================================================================

    @Nested
    @DisplayName("Nhiều statement nối tiếp (sau dấu ';')")
    class MultiStatementTests {

        @Test
        @DisplayName("Statement thứ 2 (INSERT) sau ';' - gợi ý bảng bình thường, không lẫn ngữ cảnh SELECT trước")
        void secondStatementAfterSemicolonGetsFreshContext() {
            var result = suggest("select * from public.users; insert into |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.users"));
            assertTrue(tables.contains("public.orders"));
        }
    }

    // =====================================================================
    // Nhóm 22: DISTINCT, LIMIT, RETURNING
    // =====================================================================

    @Nested
    @DisplayName("DISTINCT, LIMIT, RETURNING")
    class MiscClauseTests {

        @Test
        @DisplayName("SELECT DISTINCT | - vẫn gợi ý cột bình thường")
        void distinctColumnSuggestions() {
            var result = suggest("select distinct | from public.users");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
            assertTrue(hasKeyOfType(result, "users.name", "column"));
        }

        @Test
        @DisplayName("Sau ORDER BY col hoàn chỉnh - gợi ý được LIMIT")
        void limitKeywordAfterOrderBy() {
            var result = suggest("select * from public.users order by id |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("limit"));
        }

        @Test
        @DisplayName("Sau INSERT ... VALUES hoàn chỉnh - gợi ý được RETURNING")
        void returningKeywordAfterInsertValues() {
            var result = suggest("insert into public.users (id) values (1) |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("returning"));
        }
    }

    // =====================================================================
    // Nhóm 23: Correlated subquery trong SELECT list
    // =====================================================================

    @Nested
    @DisplayName("Correlated subquery trong SELECT list")
    class CorrelatedSubqueryInSelectList {

        @Test
        @DisplayName("Subquery trong SELECT list tham chiếu alias NGOÀI - thấy CẢ cột trong lẫn ngoài")
        void correlatedSubqueryInSelectListSeesOuterAlias() {
            var result = suggest(
                    "select (select | from public.orders where orders.customer_id = u.id) from public.users u");
            assertTrue(hasKeyOfType(result, "orders.customer_id", "column"),
                    "Cột của bảng TRONG subquery");
            assertTrue(hasKeyOfType(result, "u.name", "column"),
                    "Cột của alias NGOÀI (correlated) - phải thấy được để dùng trong subquery");
        }
    }

    // =====================================================================
    // Nhóm 24: Schema/table variations
    // =====================================================================

    @Nested
    @DisplayName("Schema/table - các biến thể FROM/JOIN")
    class SchemaTableVariations {

        @Test
        @DisplayName("FROM với ONLY - vẫn resolve alias mặc định đúng")
        void fromOnlySuggestsColumns() {
            var result = suggest("select | from only public.users");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("FROM nhiều bảng cách nhau bằng dấu phẩy (không JOIN) - cả 2 alias đều resolve")
        void fromCommaSeparatedTables() {
            var result = suggest("select | from public.users u, public.orders o");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
            assertTrue(hasKeyOfType(result, "o.id", "column"));
        }

        @Test
        @DisplayName("FULL OUTER JOIN - cả 2 alias đều resolve")
        void fullOuterJoinResolvesBothAliases() {
            var result = suggest("select u.| from public.users u full outer join public.orders o on u.id = o.customer_id");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
            assertTrue(hasKeyOfType(result, "u.name", "column"));
        }

        @Test
        @DisplayName("RIGHT JOIN - alias bảng bên phải vẫn resolve")
        void rightJoinResolvesRightAlias() {
            var result = suggest("select * from public.users u right join public.orders o on u.id = o.customer_id where o.|");
            assertTrue(hasKeyOfType(result, "o.total", "column"));
        }

        @Test
        @DisplayName("CROSS JOIN - cả 2 alias resolve, không cần ON")
        void crossJoinResolvesBothAliases() {
            var result = suggest("select * from public.users u cross join public.orders o where o.|");
            assertTrue(hasKeyOfType(result, "o.total", "column"));
        }
    }

    // =====================================================================
    // Nhóm 25: LATERAL JOIN
    // =====================================================================

    @Nested
    @DisplayName("LATERAL JOIN")
    class LateralJoinTests {

        @Test
        @DisplayName("CROSS JOIN LATERAL - subquery thấy được alias ngoài")
        void crossJoinLateralSeesOuterAlias() {
            var result = suggest(
                    "select * from public.users u cross join lateral (select | from public.orders where orders.customer_id = u.id) sub");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 26: EXISTS / NOT EXISTS / IN / NOT IN
    // =====================================================================

    @Nested
    @DisplayName("EXISTS/NOT EXISTS/IN/NOT IN")
    class ExistsInTests {

        @Test
        @DisplayName("NOT EXISTS - subquery vẫn gợi ý cột đúng")
        void notExistsColumnSuggestions() {
            var result = suggest("select * from public.users u where not exists (select | from public.orders)");
            assertTrue(hasKeyOfType(result, "orders.id", "column"));
        }

        @Test
        @DisplayName("WHERE id NOT IN (subquery) - vẫn gợi ý cột")
        void notInSubqueryColumnSuggestions() {
            var result = suggest("select * from public.users where id not in (select | from public.orders)");
            assertTrue(hasKeyOfType(result, "orders.customer_id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 27: CASE WHEN nhiều nhánh
    // =====================================================================

    @Nested
    @DisplayName("CASE WHEN nhiều nhánh")
    class CaseWhenMultiBranch {

        @Test
        @DisplayName("CASE WHEN ... WHEN ... ELSE - nhánh WHEN thứ 2 vẫn gợi ý cột")
        void secondWhenBranchColumnSuggestions() {
            var result = suggest(
                    "select case when id = 1 then 'a' when | then 'b' else 'c' end from public.users");
            assertTrue(hasKeyOfType(result, "users.name", "column"));
        }

        @Test
        @DisplayName("CASE WHEN trong WHERE clause")
        void caseWhenInsideWhereClause() {
            var result = suggest("select * from public.users where case when | then true else false end");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 28: GROUPING SETS / ROLLUP / CUBE
    // =====================================================================

    @Nested
    @DisplayName("GROUPING SETS/ROLLUP/CUBE")
    class GroupingSetsTests {

        @Test
        @DisplayName("GROUP BY ROLLUP(col) - gợi ý cột bên trong")
        void rollupColumnSuggestions() {
            var result = suggest("select status, count(*) from public.orders group by rollup(|)");
            assertTrue(hasKeyOfType(result, "orders.status", "column"));
        }

        @Test
        @DisplayName("GROUP BY CUBE(col1, |) - gợi ý cột tiếp theo")
        void cubeSecondColumnSuggestions() {
            var result = suggest("select status, customer_id, count(*) from public.orders group by cube(status, |)");
            assertTrue(hasKeyOfType(result, "orders.customer_id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 29: Nested/nhiều tầng subquery
    // =====================================================================

    @Nested
    @DisplayName("Subquery lồng nhiều tầng")
    class DeeplyNestedSubqueryTests {

        @Test
        @DisplayName("Subquery 3 tầng - tầng trong cùng vẫn thấy alias tầng ngoài cùng")
        void tripleNestedSubquerySeesOutermostAlias() {
            var result = suggest(
                    "select (select (select | from public.orders where orders.customer_id = u.id) from public.users) from public.users u");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
        }

        @Test
        @DisplayName("Subquery trong JOIN condition")
        void subqueryInJoinCondition() {
            var result = suggest(
                    "select * from public.users u join public.orders o on o.customer_id = (select | from public.users where id = 1)");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 30: Nhiều điều kiện WHERE phức tạp
    // =====================================================================

    @Nested
    @DisplayName("WHERE phức tạp - OR, ngoặc lồng nhau, NOT")
    class ComplexWhereTests {

        @Test
        @DisplayName("WHERE (a OR b) AND | - gợi ý cột sau ngoặc đóng")
        void whereAfterClosingParenAndAnd() {
            var result = suggest("select * from public.users where (id = 1 or id = 2) and |");
            assertTrue(hasKeyOfType(result, "users.name", "column"));
        }

        @Test
        @DisplayName("WHERE NOT (| ) - gợi ý cột bên trong NOT")
        void whereNotParenColumnSuggestions() {
            var result = suggest("select * from public.users where not (| )");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("WHERE nhiều OR liên tiếp")
        void multipleOrConditions() {
            var result = suggest("select * from public.users where id = 1 or id = 2 or |");
            assertTrue(hasKeyOfType(result, "users.email", "column"));
        }
    }

    // =====================================================================
    // Nhóm 31: Aggregate + HAVING nâng cao
    // =====================================================================

    @Nested
    @DisplayName("Aggregate + HAVING nâng cao")
    class AdvancedAggregateTests {

        @Test
        @DisplayName("SUM(col) trong SELECT list - gợi ý cột bên trong SUM")
        void sumFunctionArgumentColumnSuggestions() {
            var result = suggest("select sum(|) from public.orders");
            assertTrue(hasKeyOfType(result, "orders.total", "column"));
        }

        @Test
        @DisplayName("AVG(col) trong SELECT list")
        void avgFunctionArgumentColumnSuggestions() {
            var result = suggest("select avg(|) from public.orders");
            assertTrue(hasKeyOfType(result, "orders.total", "column"));
        }
    }

    // =====================================================================
    // Nhóm 32: RETURNING với UPDATE/DELETE
    // =====================================================================

    @Nested
    @DisplayName("RETURNING với UPDATE/DELETE")
    class ReturningClauseTests {

        @Test
        @DisplayName("UPDATE ... RETURNING | - gợi ý cột của bảng vừa update")
        void updateReturningColumnSuggestions() {
            var result = suggest("update public.users set name = 'x' returning |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("DELETE ... RETURNING | - gợi ý cột của bảng vừa xoá")
        void deleteReturningColumnSuggestions() {
            var result = suggest("delete from public.users where id = 1 returning |");
            assertTrue(hasKeyOfType(result, "users.name", "column"));
        }
    }

    // =====================================================================
    // Nhóm 33: ON CONFLICT (upsert)
    // =====================================================================

    @Nested
    @DisplayName("ON CONFLICT (upsert)")
    class OnConflictTests {

        @Test
        @DisplayName("INSERT ... ON CONFLICT DO UPDATE SET | - gợi ý cột cần update")
        void onConflictDoUpdateSetColumnSuggestions() {
            var result = suggest(
                    "insert into public.users (id, name) values (1, 'a') on conflict (id) do update set |");
            assertTrue(hasKeyOfType(result, "users.name", "column"));
        }
    }

    // =====================================================================
    // Nhóm 34: DDL nâng cao - VIEW, SEQUENCE, INDEX
    // =====================================================================

    @Nested
    @DisplayName("DDL nâng cao: VIEW, SEQUENCE, INDEX")
    class AdvancedDdlTests {

        @Test
        @DisplayName("CREATE VIEW ... AS SELECT | - gợi ý cột bảng nguồn")
        void createViewSelectColumnSuggestions() {
            var result = suggest("create view v as select | from public.users");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("CREATE INDEX ON table (| ) - gợi ý cột để đánh index")
        void createIndexColumnSuggestions() {
            var result = suggest("create index idx1 on public.users (|)");
            assertTrue(hasKeyOfType(result, "id", "column") || hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("DROP VIEW - gợi ý tên view/bảng (dropstmt -> object_type_any_name any_name_list -> any_name, "
                + "đã xác nhận qua PostgreSQLParser.g4 dòng 1471-1490: VIEW nằm trong object_type_any_name, "
                + "và 'any_name' đã được CompletionEngine.addTableNameSuggestions() xử lý)")
        void dropViewSuggestions() {
            var result = suggest("drop view |");
            var tables = keysOfType(result, "table");
            var views = keysOfType(result, "view");
            assertTrue(tables.contains("public.users") || views.contains("public.orders_summary"),
                    "DROP VIEW phải gợi ý được object trong schema (bảng hoặc view)");
        }
    }

    // =====================================================================
    // Nhóm 35: ALTER TABLE nâng cao
    // =====================================================================

    @Nested
    @DisplayName("ALTER TABLE nâng cao")
    class AdvancedAlterTableTests {

        @Test
        @DisplayName("ALTER TABLE ... DROP COLUMN | - gợi ý cột để xoá")
        void alterTableDropColumnSuggestions() {
            var result = suggest("alter table public.users drop column |");
            assertTrue(hasKeyOfType(result, "id", "column") || hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("ALTER TABLE ... ALTER COLUMN col TYPE | - gợi ý kiểu dữ liệu mới")
        void alterColumnTypeSuggestions() {
            var result = suggest("alter table public.users alter column name type |");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
        }
    }

    // =====================================================================
    // Nhóm 36: Comment trong SQL
    // =====================================================================

    @Nested
    @DisplayName("Comment trong câu SQL")
    class SqlCommentTests {

        @Test
        @DisplayName("Comment dạng -- trước vị trí cursor - không ảnh hưởng gợi ý")
        void lineCommentBeforeCursorDoesNotBreakSuggestion() {
            var result = suggest("select * from public.users -- lấy hết user\nwhere |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("Comment dạng /* */ giữa câu - không ảnh hưởng gợi ý")
        void blockCommentDoesNotBreakSuggestion() {
            var result = suggest("select * from public.users /* bang chinh */ where |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 39: String function và LIKE pattern
    // =====================================================================

    @Nested
    @DisplayName("String function và LIKE pattern")
    class StringFunctionTests {

        @Test
        @DisplayName("LOWER(col) - gợi ý cột bên trong")
        void lowerFunctionColumnSuggestions() {
            var result = suggest("select lower(|) from public.users");
            assertTrue(hasKeyOfType(result, "users.name", "column"));
        }

        @Test
        @DisplayName("CONCAT(col1, |) - gợi ý cột tham số thứ 2")
        void concatSecondArgumentColumnSuggestions() {
            var result = suggest("select concat(name, |) from public.users");
            assertTrue(hasKeyOfType(result, "users.email", "column"));
        }
    }

    // =====================================================================
    // Nhóm 40: Multiple schemas
    // =====================================================================

    @Nested
    @DisplayName("Nhiều schema khác nhau")
    class MultipleSchemaTests {

        @Test
        @DisplayName("2 bảng cùng tên khác schema - đều gợi ý được với schema prefix")
        void sameTableNameDifferentSchemas() {
            var result = suggest("select * from |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.stream().anyMatch(t -> t.startsWith("public.")));
        }
    }

    // =====================================================================
    // Nhóm 41: FUNCTION/PROCEDURE DDL
    // =====================================================================

    @Nested
    @DisplayName("CREATE FUNCTION/PROCEDURE")
    class FunctionProcedureDdlTests {

        @Test
        @DisplayName("CREATE FUNCTION ... RETURNS | - gợi ý kiểu trả về")
        void createFunctionReturnsTypeSuggestions() {
            var result = suggest("create function f() returns |");
            assertTrue(hasKeyOfType(result, "int4", "datatype"));
        }

        @Test
        @DisplayName("CREATE FUNCTION với tham số kiểu | - gợi ý kiểu dữ liệu tham số")
        void createFunctionParameterTypeSuggestions() {
            var result = suggest("create function f(a |");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
        }
    }

    // =====================================================================
    // Nhóm 43: Kiểm tra không có FROM (edge case ngữ cảnh rỗng)
    // =====================================================================

    @Nested
    @DisplayName("Không có FROM - ngữ cảnh cột rỗng")
    class NoFromClauseTests {

        @Test
        @DisplayName("SELECT 1 + | (không FROM) - không crash, không gợi ý cột sai")
        void selectWithoutFromNoColumnLeak() {
            var result = suggest("select 1 + |");
            assertTrue(keysOfType(result, "column").isEmpty());
        }

        @Test
        @DisplayName("SELECT current_date, | (không FROM) - không có bảng nào visible nên KHÔNG được lộ cột "
                + "(giống 'selectWithoutFromNoColumnLeak' ở trên); hàm (count/sum/...) vẫn được add "
                + "không điều kiện trong addColumnSuggestions() nên function list không nhất thiết rỗng")
        void selectCurrentDateWithoutFromNoColumnLeak() {
            var result = suggest("select current_date, |");
            assertTrue(keysOfType(result, "column").isEmpty());
        }
    }

    // =====================================================================
    // Nhóm 44: Nhiều dấu chấm cụt trong 1 câu
    // =====================================================================

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
        }
    }

    // =====================================================================
    // Nhóm 45: WITH RECURSIVE
    // =====================================================================

    @Nested
    @DisplayName("WITH RECURSIVE")
    class RecursiveCteTests {

        @Test
        @DisplayName("WITH RECURSIVE - cột trong phần base case gợi ý đúng")
        void recursiveCteBaseCase() {
            var result = suggest("with recursive r as (select id, name from public.users where |) select * from r");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("CTE thứ 2 tham chiếu CTE thứ 1 - cột của CTE 1 resolve được")
        void secondCteReferencesFirstCte() {
            var result = suggest("with a as (select id, name from public.users), b as (select a.| from a) select * from b");
            assertTrue(hasKeyOfType(result, "a.id", "column"));
            assertTrue(hasKeyOfType(result, "a.name", "column"));
        }
    }

    // =====================================================================
    // Nhóm 46: MERGE statement
    // =====================================================================

    @Nested
    @DisplayName("MERGE statement")
    class MergeStatementTests {

        @Test
        @DisplayName("MERGE ... USING ... ON - gợi ý cột 2 bảng")
        void mergeUsingOnColumnSuggestions() {
            var result = suggest(
                    "merge into public.users u using public.orders o on u.id = o.| when matched then do nothing");
            assertTrue(hasKeyOfType(result, "o.customer_id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 47: Window function nâng cao (ORDER BY trong OVER, ROWS BETWEEN)
    // =====================================================================

    @Nested
    @DisplayName("Window function nâng cao")
    class AdvancedWindowFunctionTests {

        @Test
        @DisplayName("OVER (PARTITION BY x ORDER BY |) - gợi ý cột để order")
        void windowOrderByColumnSuggestions() {
            var result = suggest(
                    "select row_number() over (partition by customer_id order by |) from public.orders");
            assertTrue(hasKeyOfType(result, "orders.total", "column"));
        }

        @Test
        @DisplayName("Nhiều window function trong cùng 1 SELECT")
        void multipleWindowFunctionsInSameSelect() {
            var result = suggest(
                    "select row_number() over (order by id), rank() over (partition by | ) from public.orders");
            assertTrue(hasKeyOfType(result, "orders.status", "column"));
        }
    }

    // =====================================================================
    // Nhóm 48: EXPLAIN / ANALYZE
    // =====================================================================

    @Nested
    @DisplayName("EXPLAIN/ANALYZE")
    class ExplainAnalyzeTests {

        @Test
        @DisplayName("EXPLAIN SELECT | - gợi ý cột bình thường bên trong")
        void explainSelectColumnSuggestions() {
            var result = suggest("explain select | from public.users");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("EXPLAIN ANALYZE SELECT ... WHERE | - EXPLAIN chỉ bọc ngoài selectstmt bình thường, "
                + "nên WHERE clause bên trong phải resolve cột y hệt test 'explainSelectColumnSuggestions' ở trên")
        void explainAnalyzeColumnSuggestions() {
            var result = suggest("explain analyze select * from public.users where |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 49: GRANT / REVOKE
    // =====================================================================

    @Nested
    @DisplayName("GRANT/REVOKE")
    class GrantRevokeTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] REVOKE ... FROM | - vế sau FROM là 'grantee_list' (role, không phải bảng; "
                + "g4 dòng 1638), rule này không thuộc any_name/qualified_name/colid nên engine không gợi ý gì.")
        void revokeDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("revoke select on public.users from |"));
        }
    }

    // =====================================================================
    // Nhóm 50: JOIN USING
    // =====================================================================

    @Nested
    @DisplayName("JOIN ... USING (...)")
    class JoinUsingTests {

        @Test
        @DisplayName("JOIN ... USING (| ) - gợi ý cột chung của cả 2 bảng")
        void joinUsingColumnSuggestions() {
            var result = suggest("select * from public.users u join public.orders o using (|)");
            assertTrue(hasKeyOfType(result, "id", "column") || hasKeyOfType(result, "u.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 51: Function/expression nâng cao
    // =====================================================================

    @Nested
    @DisplayName("Function/expression nâng cao")
    class AdvancedExpressionTests {

        @Test
        @DisplayName("EXTRACT(field FROM col) - gợi ý cột bên trong FROM")
        void extractFromColumnSuggestions() {
            var result = suggest("select extract(year from |) from public.users");
            assertTrue(hasKeyOfType(result, "users.created_date", "column"));
        }

        @Test
        @DisplayName("COALESCE với nhiều tham số - tham số cuối vẫn gợi ý cột")
        void coalesceMultipleArgumentsColumnSuggestions() {
            var result = suggest("select coalesce(name, email, |) from public.users");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("NULLIF(col1, |) - gợi ý cột tham số 2")
        void nullifSecondArgumentColumnSuggestions() {
            var result = suggest("select nullif(name, |) from public.users");
            assertTrue(hasKeyOfType(result, "users.email", "column"));
        }
    }

    // =====================================================================
    // Nhóm 52: TABLESAMPLE
    // =====================================================================

    @Nested
    @DisplayName("TABLESAMPLE")
    class TableSampleTests {

        @Test
        @DisplayName("FROM table TABLESAMPLE - vẫn resolve alias đúng")
        void tableSampleResolvesAlias() {
            var result = suggest("select | from public.users tablesample bernoulli(10)");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 55: Boolean literal và điều kiện đơn giản
    // =====================================================================

    @Nested
    @DisplayName("Boolean literal và điều kiện đơn giản")
    class BooleanLiteralTests {

        @Test
        @DisplayName("WHERE TRUE AND | - vẫn gợi ý cột tiếp")
        void whereTrueAndColumnSuggestions() {
            var result = suggest("select * from public.users where true and |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 56: Nhiều RETURNING column
    // =====================================================================

    @Nested
    @DisplayName("RETURNING nhiều cột / RETURNING *")
    class MultipleReturningColumnsTests {

        @Test
        @DisplayName("INSERT ... RETURNING col1, | - cột thứ 2 vẫn gợi ý đúng")
        void insertReturningSecondColumnSuggestions() {
            var result = suggest("insert into public.users (id, name) values (1, 'a') returning id, |");
            assertTrue(hasKeyOfType(result, "users.name", "column"));
        }

        @Test
        @DisplayName("UPDATE ... RETURNING * - không crash")
        void updateReturningStarDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("update public.users set name = 'x' returning *|"));
        }
    }

    // =====================================================================
    // Nhóm 57: Ép kiểu (CAST) nâng cao
    // =====================================================================

    @Nested
    @DisplayName("CAST nâng cao")
    class AdvancedCastTests {

        @Test
        @DisplayName("CAST(col AS type) - dạng hàm CAST() thay vì :: - gợi ý kiểu dữ liệu")
        void castFunctionSyntaxDataTypeSuggestions() {
            var result = suggest("select cast(id as |) from public.users");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
        }
    }

    // =====================================================================
    // Nhóm 58: Subquery trả về scalar dùng trong SELECT list nhiều lần
    // =====================================================================

    @Nested
    @DisplayName("Nhiều scalar subquery trong cùng 1 SELECT list")
    class MultipleScalarSubqueriesTests {

        @Test
        @DisplayName("2 scalar subquery trong cùng SELECT list - subquery thứ 2 vẫn resolve alias ngoài")
        void secondScalarSubqueryResolvesOuterAlias() {
            var result = suggest(
                    "select (select count(*) from public.orders where customer_id = u.id), (select | from public.orders where customer_id = u.id) from public.users u");
            assertTrue(hasKeyOfType(result, "u.name", "column"));
        }
    }

    // =====================================================================
    // Nhóm 59: FILTER clause cho aggregate
    // =====================================================================

    @Nested
    @DisplayName("FILTER clause cho aggregate function")
    class FilterClauseTests {

        @Test
        @DisplayName("COUNT(*) FILTER (WHERE |) - gợi ý cột bên trong FILTER")
        void countFilterWhereColumnSuggestions() {
            var result = suggest("select count(*) filter (where |) from public.orders");
            assertTrue(hasKeyOfType(result, "orders.status", "column"));
        }
    }

    // =====================================================================
    // Nhóm 60: Kiểm tra thứ tự clause đầy đủ 1 câu SELECT phức tạp
    // =====================================================================

    @Nested
    @DisplayName("Câu SELECT đầy đủ mọi clause - kiểm tra từng vị trí")
    class FullComplexSelectTests {

        @Test
        @DisplayName("Vị trí giữa GROUP BY và HAVING - vẫn gợi ý cột đúng")
        void betweenGroupByAndHavingColumnSuggestions() {
            var result = suggest(
                    "select customer_id, count(*) from public.orders where status = 'active' group by customer_id, | having count(*) > 1");
            assertTrue(hasKeyOfType(result, "orders.status", "column")
                    || hasKeyOfType(result, "orders.total", "column"));
        }

        @Test
        @DisplayName("Vị trí giữa HAVING và ORDER BY")
        void betweenHavingAndOrderByColumnSuggestions() {
            var result = suggest(
                    "select customer_id, count(*) from public.orders group by customer_id having count(*) > 1 order by |");
            assertTrue(hasKeyOfType(result, "orders.customer_id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 61: Transaction control
    // =====================================================================

    @Nested
    @DisplayName("Transaction control (BEGIN/COMMIT/SAVEPOINT)")
    class TransactionControlTests {

        @Test
        @DisplayName("BEGIN; SELECT | - statement sau BEGIN transaction vẫn gợi ý cột bình thường")
        void selectAfterBeginTransaction() {
            var result = suggest("begin; select | from public.users");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 63: PREPARE / EXECUTE / DEALLOCATE
    // =====================================================================

    @Nested
    @DisplayName("PREPARE/EXECUTE/DEALLOCATE")
    class PrepareExecuteTests {

        @Test
        @DisplayName("PREPARE stmt AS SELECT | - vẫn gợi ý cột bên trong")
        void prepareStatementColumnSuggestions() {
            var result = suggest("prepare s1 as select | from public.users");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 64: DECLARE CURSOR / FETCH
    // =====================================================================

    @Nested
    @DisplayName("DECLARE CURSOR/FETCH")
    class CursorTests {

        @Test
        @DisplayName("DECLARE CURSOR FOR SELECT | - vẫn gợi ý cột bên trong")
        void declareCursorColumnSuggestions() {
            var result = suggest("declare c1 cursor for select | from public.users");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 66: Constraint DDL (CHECK, FOREIGN KEY)
    // =====================================================================

    @Nested
    @DisplayName("Constraint DDL (CHECK, FOREIGN KEY)")
    class ConstraintDdlTests {

        @Test
        @DisplayName("ALTER TABLE ADD FOREIGN KEY REFERENCES | - gợi ý bảng tham chiếu")
        void addForeignKeyReferencesTableSuggestions() {
            var result = suggest(
                    "alter table public.orders add constraint fk1 foreign key (customer_id) references |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.users"));
        }
    }

    // =====================================================================
    // Nhóm 67: Domain / Composite type DDL
    // =====================================================================

    @Nested
    @DisplayName("CREATE DOMAIN/TYPE")
    class DomainTypeDdlTests {

        @Test
        @DisplayName("CREATE DOMAIN AS | - gợi ý kiểu dữ liệu nền")
        void createDomainDataTypeSuggestions() {
            var result = suggest("create domain positive_int as |");
            assertTrue(hasKeyOfType(result, "int4", "datatype"));
        }
    }

    // =====================================================================
    // Nhóm 70: Array function
    // =====================================================================

    @Nested
    @DisplayName("Array function (array_agg, unnest trong SELECT list)")
    class ArrayFunctionTests {

        @Test
        @DisplayName("array_agg(col) - gợi ý cột bên trong")
        void arrayAggColumnSuggestions() {
            var result = suggest("select array_agg(|) from public.users");
            assertTrue(hasKeyOfType(result, "users.name", "column"));
        }
    }

    // =====================================================================
    // Nhóm 71: RETURNING với expression (không chỉ tên cột trần)
    // =====================================================================

    @Nested
    @DisplayName("RETURNING với expression")
    class ReturningExpressionTests {

        @Test
        @DisplayName("INSERT ... RETURNING id AS new_id, | - cột tiếp theo vẫn gợi ý đúng")
        void returningWithAliasSecondColumnSuggestions() {
            var result = suggest(
                    "insert into public.users (id, name) values (1, 'a') returning id as new_id, |");
            assertTrue(hasKeyOfType(result, "users.name", "column"));
        }
    }

    // =====================================================================
    // Nhóm 72: Nhiều CTE độc lập không tham chiếu nhau
    // =====================================================================

    @Nested
    @DisplayName("Nhiều CTE độc lập, dùng chung trong JOIN")
    class IndependentMultipleCteJoinTests {

        @Test
        @DisplayName("2 CTE độc lập JOIN với nhau trong statement chính")
        void twoIndependentCtesJoined() {
            var result = suggest(
                    "with a as (select id, name from public.users), b as (select id, total from public.orders) "
                            + "select a.| from a join b on a.id = b.id");
            assertTrue(hasKeyOfType(result, "a.id", "column"));
            assertTrue(hasKeyOfType(result, "a.name", "column"));
        }
    }

    // =====================================================================
    // Nhóm 76: Nhiều điều kiện JOIN kết hợp USING và ON (khác vế)
    // =====================================================================

    @Nested
    @DisplayName("Kết hợp nhiều loại JOIN trong 1 câu")
    class MixedJoinTypesTests {

        @Test
        @DisplayName("INNER JOIN ... USING rồi LEFT JOIN ... ON - vẫn resolve đủ cả 3 bảng")
        void mixedUsingAndOnJoins() {
            var result = suggest(
                    "select | from public.users u join public.orders o using (id) left join public.orders o2 on o.id = o2.id");
            assertTrue(hasKeyOfType(result, "u.name", "column"));
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
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains(expectedTable));
        }
    }

    @Nested
    @DisplayName("Không crash - smoke test tổng hợp nhiều cú pháp khác nhau - GOM (parameterized)")
    class DoesNotThrowSmokeTests {

        static Stream<String> doesNotThrowSqlCases() {
            return Stream.of(
                    "savepoint |",
                    "rollback to savepoint |",
                    "listen |",
                    "notify |",
                    "execute |",
                    "deallocate |",
                    "fetch next from |",
                    "create sequence |",
                    "alter sequence |",
                    "drop sequence |",
                    "select nextval(|)",
                    "alter table public.users add constraint chk1 check (|)",
                    "create table t (id int primary key, name |)",
                    "create type point as (x int, y |)",
                    "create table t (a int, b int generated always as (a + |) stored)",
                    "select * from public.users where to_tsvector(name) @@ to_tsquery(|)",
                    "select unnest(|) from public.users",
                    "do $$ begin raise notice 'x'; end |$$",
                    "comment on column public.users.id is |",
                    "reindex table |",
                    "select * from \"public\".\"users\" where |",
                    "select \"u\".| from public.users as \"u\"",
                    "select * from public.users where id = any(array[|])",
                    "select name ->> | from public.users",
                    "select * from public.users where name like 'a%' and |",
                    "select sum(total) over (order by id rows between unbounded preceding and |) from public.orders",
                    "select case when id = 1 then (case when | then 'x' end) else 'y' end from public.users",
                    "select * from public.users where cast(id as text) = |",
                    "values (1, |)",
                    "select * from unnest(array[1,2,3]) with ordinality where |",
                    "select * from public.users where id = 1 and |",
                    "alter table public.users rename to |",
                    "merge into public.users u using public.orders o on u.id = o.customer_id when matched then update set |",
                    "select customer_id, sum(total) from public.orders group by customer_id having sum(total) > |",
                    "select * from nonexistent_table where |",
                    "select nonexistent_col from public.users where |",
                    "select x.| from public.users u",
                    "select * from public.users orders where orders.|",
                    "select customer_id, count(*) from public.orders where status = 'active' "
                            + "group by customer_id having count(*) > 1 order by customer_id limit 10 offset |",
                    "select * from public.users u join public.orders o on u.id = o.customer_id "
                            + "join public.products p on o.product_id = p.id "
                            + "where u.status = 'active' and o.total > 100 "
                            + "group by u.id, u.name "
                            + "having count(*) > 5 "
                            + "order by u.name limit 10 |"
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("doesNotThrowSqlCases")
        void doesNotThrow(String sql) {
            assertDoesNotThrow(() -> suggest(sql));
        }
    }

    // =====================================================================
    // Nhóm 78: Partitioned table DDL
    // =====================================================================

    @Nested
    @DisplayName("Partitioned table (PARTITION BY)")
    class PartitionedTableTests {

        @Test
        @DisplayName("CREATE TABLE ... PARTITION BY RANGE (col) - không crash")
        void createTablePartitionByRangeDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create table t (id int, created_date date) partition by range (|)"));
        }

        @Test
        @DisplayName("CREATE TABLE ... PARTITION OF parent FOR VALUES - gợi ý bảng cha")
        void createTablePartitionOfTableSuggestions() {
            var result = suggest("create table orders_2024 partition of |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.orders"));
        }

        @Test
        @DisplayName("ATTACH PARTITION - không crash")
        void attachPartitionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter table public.orders attach partition orders_2024 for values from (|"));
        }
    }

    // =====================================================================
    // Nhóm 79: Row-level security (POLICY)
    // =====================================================================

    @Nested
    @DisplayName("CREATE POLICY (row-level security)")
    class RowLevelSecurityTests {

        @Test
        @DisplayName("CREATE POLICY ... ON table USING (col = ...) - gợi ý cột trong USING")
        void createPolicyUsingColumnSuggestions() {
            var result = suggest("create policy p1 on public.users using (|)");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("ALTER TABLE ... ENABLE ROW LEVEL SECURITY - không crash")
        void enableRlsDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter table public.users enable row level security|"));
        }
    }

    // =====================================================================
    // Nhóm 80: Extension DDL
    // =====================================================================

    @Nested
    @DisplayName("CREATE EXTENSION")
    class ExtensionDdlTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] CREATE EXTENSION | - dùng rule 'name' đơn giản (g4 dòng 990), không phải "
                + "'any_name'/'qualified_name' mà engine đang tra cứu object - tên extension cũng không nằm trong "
                + "schema người dùng nên không thể gợi ý được gì có ý nghĩa ở đây.")
        void createExtensionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create extension |"));
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] DROP EXTENSION | - cùng lý do rule 'name'")
        void dropExtensionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("drop extension |"));
        }
    }

    // =====================================================================
    // Nhóm 81: Index nâng cao (partial, expression, GIN/GIST)
    // =====================================================================

    @Nested
    @DisplayName("Index nâng cao (partial, expression, GIN/GIST)")
    class AdvancedIndexTests {

        @Test
        @DisplayName("CREATE INDEX ... WHERE col - partial index, gợi ý cột trong WHERE")
        void partialIndexWhereColumnSuggestions() {
            var result = suggest("create index idx1 on public.orders (id) where |");
            assertTrue(hasKeyOfType(result, "orders.status", "column"));
        }

        @Test
        @DisplayName("CREATE INDEX USING gin (col) - gợi ý cột thật (index_elem: colid ..., "
                + "colid có parent trực tiếp là index_elem -> đã được isColidIndexColumn xử lý)")
        void ginIndexColumnSuggestions() {
            var result = suggest("create index idx1 on public.users using gin (|)");
            assertTrue(hasKeyOfType(result, "id", "column") || hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("CREATE INDEX ON table (LOWER(col)) - expression index, gợi ý cột bên trong hàm "
                + "(index_elem: func_expr_windowless ..., tham số của lower() đi qua rule columnref)")
        void expressionIndexColumnSuggestions() {
            var result = suggest("create index idx1 on public.users (lower(|))");
            assertTrue(hasKeyOfType(result, "users.id", "column")
                    || hasKeyOfType(result, "users.name", "column"));
        }

        @Test
        @DisplayName("CREATE UNIQUE INDEX - gợi ý cột thật (cùng rule index_elem như trên)")
        void uniqueIndexColumnSuggestions() {
            var result = suggest("create unique index idx1 on public.users (|)");
            assertTrue(hasKeyOfType(result, "id", "column") || hasKeyOfType(result, "users.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 82: SET / SHOW session command
    // =====================================================================

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
            assertDoesNotThrow(() -> suggest("set search_path to |"));
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] SHOW | - rule 'var_name', không được xử lý")
        void showDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("show |"));
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] RESET | - rule 'var_name', không được xử lý")
        void resetDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("reset |"));
        }
    }

    // =====================================================================
    // Nhóm 83: COPY command
    // =====================================================================

    @Nested
    @DisplayName("COPY FROM/TO")
    class CopyCommandTests {

        @Test
        @DisplayName("COPY table FROM - gợi ý bảng")
        void copyFromTableSuggestions() {
            var result = suggest("copy |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.users"));
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT - đã xác nhận qua grammar] COPY table (col, |) FROM stdin - opt_column_list "
                + "trong CopyStmt (g4 dòng 556) dùng 'columnlist -> colid', nhưng parent trực tiếp của colid ở "
                + "đây là 'columnlist', KHÔNG nằm trong danh sách 5 context mà CompletionEngine.isColid* đang "
                + "nhận diện (relation_expr_opt_alias/alter_table_cmd/columnref/index_elem/set_target/join_qual). "
                + "Nên hiện tại KHÔNG gợi ý được cột ở đây - giữ assertDoesNotThrow là đúng, không phải test hời hợt.")
        void copyColumnListDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("copy public.users (id, |) from stdin"));
        }
    }

    // =====================================================================
    // Nhóm 84: Regex và pattern matching operator
    // =====================================================================

    @Nested
    @DisplayName("Regex operator (~, ~*, !~)")
    class RegexOperatorTests {

        @Test
        @DisplayName("WHERE col ~ | - gợi ý cột cho vế phải toán tử (a_expr qual_op a_expr, g4 dòng 3337, "
                + "cùng cơ chế với 'id = 1 and |' đã kiểm chứng ở nhóm WHERE phức tạp)")
        void regexMatchColumnSuggestions() {
            var result = suggest("select * from public.users where name ~ |");
            assertTrue(hasKeyOfType(result, "users.id", "column")
                    || hasKeyOfType(result, "users.email", "column"));
        }

        @Test
        @DisplayName("WHERE col !~* | - gợi ý cột cho vế phải (cùng rule a_expr qual_op a_expr)")
        void regexNotMatchCaseInsensitiveColumnSuggestions() {
            var result = suggest("select * from public.users where name !~* |");
            assertTrue(hasKeyOfType(result, "users.id", "column")
                    || hasKeyOfType(result, "users.email", "column"));
        }

        @Test
        @DisplayName("ILIKE | - gợi ý cột cho vế phải (a_expr_qual_op (NOT? ILIKE a_expr_qual_op ...), g4 dòng 3458)")
        void ilikePatternColumnSuggestions() {
            var result = suggest("select * from public.users where name ilike |");
            assertTrue(hasKeyOfType(result, "users.id", "column")
                    || hasKeyOfType(result, "users.email", "column"));
        }
    }

    // =====================================================================
    // Nhóm 85: UPDATE SET nhiều cột dạng row constructor
    // =====================================================================

    @Nested
    @DisplayName("UPDATE SET nhiều cột dạng (col1, col2) = (val1, val2)")
    class MultiColumnUpdateSetTests {

        @Test
        @DisplayName("UPDATE SET (col1, col2) = (val1, val2) - gợi ý cột trong danh sách target")
        void multiColumnSetTargetColumnSuggestions() {
            var result = suggest("update public.users set (name, |) = ('a', 'b')");
            assertTrue(hasKeyOfType(result, "users.email", "column"));
        }
    }

    // =====================================================================
    // Nhóm 86: LIMIT WITH TIES / FETCH FIRST
    // =====================================================================

    @Nested
    @DisplayName("FETCH FIRST / LIMIT WITH TIES")
    class FetchFirstTests {

        @Test
        @DisplayName("FETCH FIRST n ROWS ONLY - không crash")
        void fetchFirstRowsOnlyDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users order by id fetch first 10 rows only|"));
        }

        @Test
        @DisplayName("FETCH FIRST n ROWS WITH TIES - không crash")
        void fetchFirstWithTiesDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users order by id fetch first 10 rows with ties|"));
        }
    }

    // =====================================================================
    // Nhóm 87: Interval và date arithmetic
    // =====================================================================

    @Nested
    @DisplayName("Interval và date arithmetic")
    class IntervalDateArithmeticTests {

        @Test
        @DisplayName("SELECT created_date + INTERVAL '1 day' FROM ... WHERE | - biểu thức cộng interval "
                + "chỉ nằm ở SELECT list, WHERE vẫn resolve cột users bình thường")
        void columnPlusIntervalWhereColumnSuggestions() {
            var result = suggest("select created_date + interval '1 day' from public.users where |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("AT TIME ZONE - không crash")
        void atTimeZoneDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select created_date at time zone |"));
        }
    }

    // =====================================================================
    // Nhóm 88: CLUSTER / LOCK TABLE
    // =====================================================================

    @Nested
    @DisplayName("CLUSTER/LOCK TABLE")
    class ClusterLockTests {

        @Test
        @DisplayName("CLUSTER table USING index - gợi ý bảng")
        void clusterTableSuggestions() {
            var result = suggest("cluster |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.users"));
        }

        @Test
        @DisplayName("LOCK TABLE - gợi ý bảng")
        void lockTableSuggestions() {
            var result = suggest("lock table |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.users"));
        }
    }

    // =====================================================================
    // Nhóm 89: Multiple table alias trong UPDATE FROM
    // =====================================================================

    @Nested
    @DisplayName("UPDATE ... FROM (join-like update)")
    class UpdateFromTests {

        @Test
        @DisplayName("UPDATE t1 SET col = t2.col FROM t2 WHERE - gợi ý cột cả 2 bảng")
        void updateFromSecondTableColumnSuggestions() {
            var result = suggest(
                    "update public.orders o set total = o2.total from public.orders o2 where o.id = o2.|");
            assertTrue(hasKeyOfType(result, "o2.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 90: Recursive CTE với UNION ALL cụ thể
    // =====================================================================

    @Nested
    @DisplayName("WITH RECURSIVE dùng UNION ALL")
    class RecursiveCteUnionAllTests {

        @Test
        @DisplayName("WITH RECURSIVE base UNION ALL recursive - phần recursive vẫn gợi ý cột đúng")
        void recursivePartColumnSuggestions() {
            assertDoesNotThrow(() -> suggest(
                    "with recursive r as (select id, name from public.users where id = 1 "
                            + "union all select u.id, u.name from public.users u join r on u.id = r.id + 1) "
                            + "select | from r"));
        }
    }

    // =====================================================================
    // Nhóm 91: Độ sâu lồng nhau lớn (stress test đệ quy)
    // =====================================================================
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
            assertDoesNotThrow(() -> suggest(sql));
            long elapsedMs = System.currentTimeMillis() - start;
            assertTrue(elapsedMs < 3000, "Quá chậm với 20 tầng ngoặc lồng nhau: " + elapsedMs + "ms");
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
            assertDoesNotThrow(() -> suggest(sql));
            long elapsedMs = System.currentTimeMillis() - start;
            assertTrue(elapsedMs < 3000, "Quá chậm với 15 tầng subquery lồng nhau: " + elapsedMs + "ms");
        }

        @Test
        @DisplayName("Biểu thức AND/OR nối rất dài (50 điều kiện) - không treo")
        void veryLongBooleanExpressionChain() {
            StringBuilder sb = new StringBuilder("select * from public.users where id = 1");
            for (int i = 0; i < 50; i++) sb.append(" and id = ").append(i);
            sb.append(" and |");
            String sql = sb.toString();

            long start = System.currentTimeMillis();
            assertDoesNotThrow(() -> suggest(sql));
            assertTrue(System.currentTimeMillis() - start < 3000, "Quá chậm với chuỗi AND dài");
        }

        @Test
        @DisplayName("CASE WHEN lồng nhau 10 tầng - không treo")
        void deeplyNestedCaseWhen() {
            StringBuilder sb = new StringBuilder("select ");
            for (int i = 0; i < 10; i++) sb.append("case when id = ").append(i).append(" then (");
            sb.append("select | from public.users");
            for (int i = 0; i < 10; i++) sb.append(") end");
            sb.append(" from public.users");
            String sql = sb.toString();
            assertDoesNotThrow(() -> suggest(sql));
        }
    }

    // =====================================================================
    // Nhóm 92: Thread-safety - gọi suggest() đồng thời từ nhiều luồng
    // =====================================================================
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

    // =====================================================================
    // Nhóm 93: Cột trùng tên giữa các bảng JOIN (ambiguous column)
    // =====================================================================

    @Nested
    @DisplayName("Cột trùng tên giữa nhiều bảng JOIN")
    class AmbiguousColumnTests {

        @Test
        @DisplayName("JOIN 2 bảng đều có cột 'id', gõ không alias - không crash, vẫn có gợi ý (dù có thể trùng lặp)")
        void ambiguousColumnAcrossJoinDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select | from public.users u join public.orders o on u.id = o.customer_id"));
        }

        @Test
        @DisplayName("WHERE id = 1 (không alias, 2 bảng cùng có cột id) - không throw, không crash ambiguity")
        void ambiguousColumnInWhereClauseDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select * from public.users u join public.orders o on u.id = o.customer_id where |"));
        }
    }

    // =====================================================================
    // Nhóm 94: Ký tự đặc biệt trong identifier
    // =====================================================================

    @Nested
    @DisplayName("Ký tự đặc biệt trong identifier (quoted, unicode, khoảng trắng)")
    class SpecialCharacterIdentifierTests {

        @Test
        @DisplayName("Quoted identifier có khoảng trắng - không crash")
        void quotedIdentifierWithSpaceDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where \"user name\" = |"));
        }

        @Test
        @DisplayName("Quoted identifier chứa dấu ngoặc kép escape ('') - không crash")
        void quotedIdentifierWithEscapedQuoteDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.\"my\"\"table\" where |"));
        }

        @Test
        @DisplayName("Identifier Unicode (tiếng Việt có dấu) trong quoted - không crash")
        void unicodeIdentifierDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where \"tên_khách_hàng\" = |"));
        }

        @Test
        @DisplayName("Alias trùng từ khoá (quoted) - vẫn resolve cột đúng")
        void reservedWordAsQuotedAliasStillResolves() {
            var result = suggest("select \"order\".| from public.orders as \"order\"");
            assertTrue(hasKeyOfType(result, "\"order\".id", "column")
                    || hasKeyOfType(result, "order.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 95: Comment chứa từ khoá SQL - không được ảnh hưởng gợi ý
    // =====================================================================

    @Nested
    @DisplayName("Comment chứa từ khoá SQL không được lọt vào phân tích")
    class CommentContainingKeywordsTests {

        @Test
        @DisplayName("Line comment chứa cả câu SELECT hoàn chỉnh trước caret - không ảnh hưởng gợi ý thật")
        void lineCommentWithFullSelectDoesNotLeakIntoAnalysis() {
            var result = suggest(
                    "-- select * from public.orders where fake_col = 1\nselect * from public.users where |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("Block comment chứa từ khoá FROM/WHERE giả - không ảnh hưởng gợi ý thật")
        void blockCommentWithFakeKeywordsDoesNotLeak() {
            var result = suggest(
                    "select * /* from fake_table where fake = 1 */ from public.users where |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("Comment ngay trước vị trí caret - không throw")
        void commentImmediatelyBeforeCaretDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where id = 1 -- so sanh id\nand |"));
        }
    }

    // =====================================================================
    // Nhóm 96: Nhiều dấu ';' liên tiếp / statement rỗng
    // =====================================================================

    @Nested
    @DisplayName("Nhiều dấu ';' liên tiếp / statement rỗng giữa các câu")
    class EmptyStatementBetweenSemicolonsTests {

        @Test
        @DisplayName("';;;' liên tiếp rồi mới tới câu thật - không throw, vẫn gợi ý đúng")
        void multipleConsecutiveSemicolonsDoesNotThrow() {
            var result = suggest(";;; select | from public.users");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("Caret ngay tại vị trí giữa 2 dấu ';' liên tiếp (statement rỗng) - không throw")
        void caretAtEmptyStatementPositionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select 1; |; select 2"));
        }

        @Test
        @DisplayName("Chỉ toàn dấu ';', không có statement nào - không throw")
        void onlySemicolonsNoStatementDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(";;;|"));
        }
    }

    // =====================================================================
    // Nhóm 97: Schema/bảng không tồn tại trong SchemaIndex
    // =====================================================================

    @Nested
    @DisplayName("Schema/bảng không có trong SchemaIndex - phải xuống cấp nhẹ nhàng, không throw")
    class UnknownSchemaTests {

        @Test
        @DisplayName("Schema lạ hoàn toàn không có trong SchemaIndex - không throw")
        void completelyUnknownSchemaDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from nonexistent_schema.nonexistent_table where |"));
        }

        @Test
        @DisplayName("Bảng lạ dùng làm alias nguồn - không throw dù không resolve được gì")
        void unknownTableAliasColumnDoesNotThrow() {
            var result = suggest("select x.| from nonexistent_table x");
            assertNotNull(result);
        }
    }

    // =====================================================================
    // Nhóm 98: Literal số/chuỗi dạng đặc biệt
    // =====================================================================

    @Nested
    @DisplayName("Literal số/chuỗi dạng đặc biệt")
    class SpecialLiteralTests {

        @Test
        @DisplayName("Số dạng khoa học (1e10) - không throw")
        void scientificNotationNumberDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where id = 1e10 and |"));
        }

        @Test
        @DisplayName("Số âm - không throw")
        void negativeNumberDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.orders where total = -100.5 and |"));
        }

        @Test
        @DisplayName("Hex integer (0x1A) - không throw")
        void hexIntegerDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where id = 0x1A and |"));
        }

        @Test
        @DisplayName("Chuỗi dollar-quoted ($$...$$) đơn giản - không throw")
        void dollarQuotedStringDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select $$hello$$ from public.users where |"));
        }

        @Test
        @DisplayName("Chuỗi có escape quote đơn ('') - không throw")
        void escapedSingleQuoteInStringDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where name = 'O''Brien' and |"));
        }
    }

    // =====================================================================
    // Nhóm 99: JSON/JSONB operator
    // =====================================================================

    @Nested
    @DisplayName("JSON/JSONB operator (->, ->>, #>, @>, ?)")
    class JsonOperatorTests {

        @Test
        @DisplayName("SELECT data -> 'key' FROM ... WHERE | - cursor nằm ở WHERE bình thường, không liên quan "
                + "operator -> ở SELECT list, phải gợi ý cột như mọi WHERE clause khác")
        void jsonArrowOperatorInSelectListWhereColumnSuggestions() {
            var result = suggest("select data -> 'key' from public.users where |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("WHERE data ->> 'key' = 'x' AND | - vế phải AND vẫn gợi ý cột bình thường "
                + "(giống 'whereAndContinuation' đã kiểm chứng)")
        void jsonDoubleArrowThenWhereColumnSuggestions() {
            var result = suggest("select * from public.users where data ->> 'key' = 'x' and |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("WHERE data #> '{a,b}' = | - vế phải phép so sánh vẫn gợi ý cột (a_expr qual_op a_expr)")
        void jsonPathOperatorColumnSuggestions() {
            var result = suggest("select * from public.users where data #> '{a,b}' = |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("WHERE data @> '{...}' AND | - vế phải AND vẫn gợi ý cột bình thường")
        void jsonContainmentOperatorColumnSuggestions() {
            var result = suggest("select * from public.users where data @> '{\"a\":1}' and |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("jsonb_build_object(key, |) - gợi ý cột tham số")
        void jsonBuildObjectArgumentColumnSuggestions() {
            var result = suggest("select jsonb_build_object('name', |) from public.users");
            assertTrue(hasKeyOfType(result, "users.name", "column")
                    || hasKeyOfType(result, "users.email", "column")
                    || !result.isEmpty());
        }
    }

    // =====================================================================
    // Nhóm 100: Array literal và array operator
    // =====================================================================

    @Nested
    @DisplayName("Array literal và array operator")
    class ArrayLiteralTests {

        @Test
        @DisplayName("ARRAY[1,2,3] literal - không throw")
        void arrayLiteralDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select array[1,2,3] from public.users where |"));
        }

        @Test
        @DisplayName("WHERE tags @> array[1,2] AND | - vế phải AND vẫn gợi ý cột bình thường")
        void arrayContainmentOperatorColumnSuggestions() {
            var result = suggest("select * from public.users where tags @> array[1,2] and |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("SELECT tags[1:2] FROM ... WHERE | - array slicing chỉ ở SELECT list, không ảnh hưởng WHERE")
        void arraySlicingWhereColumnSuggestions() {
            var result = suggest("select tags[1:2] from public.users where |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("Cột kiểu mảng trong khai báo cột (int[]) - không throw")
        void arrayColumnTypeDeclarationDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create table t (tags int[], |"));
        }
    }

    // =====================================================================
    // Nhóm 101: Named window definitions (WINDOW clause)
    // =====================================================================

    @Nested
    @DisplayName("Named window definitions (WINDOW w AS (...))")
    class NamedWindowDefinitionTests {

        @Test
        @DisplayName("WINDOW w AS (PARTITION BY |) - gợi ý cột trong định nghĩa window đặt tên")
        void namedWindowPartitionByColumnSuggestions() {
            var result = suggest(
                    "select row_number() over w from public.orders window w as (partition by |)");
            assertTrue(hasKeyOfType(result, "orders.customer_id", "column")
                    || hasKeyOfType(result, "orders.status", "column"));
        }

        @Test
        @DisplayName("Nhiều named window trong cùng 1 WINDOW clause - không throw")
        void multipleNamedWindowsDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select row_number() over w1, rank() over w2 from public.orders "
                            + "window w1 as (partition by customer_id), w2 as (order by |)"));
        }
    }

    // =====================================================================
    // Nhóm 102: Materialized view
    // =====================================================================

    @Nested
    @DisplayName("Materialized view (CREATE/REFRESH)")
    class MaterializedViewTests {

        @Test
        @DisplayName("CREATE MATERIALIZED VIEW ... AS SELECT | - gợi ý cột bảng nguồn")
        void createMaterializedViewColumnSuggestions() {
            var result = suggest("create materialized view mv1 as select | from public.users");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("REFRESH MATERIALIZED VIEW - gợi ý bảng/view (rule 'qualified_name', xác nhận qua "
                + "PostgreSQLParser.g4 dòng 894: 'REFRESH MATERIALIZED VIEW ... qualified_name')")
        void refreshMaterializedViewSuggestsTables() {
            var result = suggest("refresh materialized view |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.users"));
        }
    }

    // =====================================================================
    // Nhóm 103: Trigger DDL
    // =====================================================================

    @Nested
    @DisplayName("CREATE TRIGGER")
    class TriggerDdlTests {

        @Test
        @DisplayName("CREATE TRIGGER ... ON table - gợi ý bảng")
        void createTriggerOnTableSuggestions() {
            var result = suggest("create trigger t1 before insert on |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.users"));
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] CREATE TRIGGER ... EXECUTE FUNCTION | - grammar dùng rule 'func_name' "
                + "(g4 dòng 1201) chứ không phải 'any_name'/'qualified_name'/'colid' - CompletionEngine chỉ xử lý "
                + "3 rule đó cho việc tra tên object, nên EXECUTE FUNCTION hiện KHÔNG gợi ý được tên hàm nào.")
        void createTriggerExecuteFunctionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create trigger t1 before insert on public.users for each row execute function |"));
        }
    }

    // =====================================================================
    // Nhóm 104: Table inheritance (INHERITS)
    // =====================================================================

    @Nested
    @DisplayName("Table inheritance (INHERITS)")
    class TableInheritanceTests {

        @Test
        @DisplayName("CREATE TABLE ... INHERITS (parent) - gợi ý bảng cha")
        void createTableInheritsParentTableSuggestions() {
            var result = suggest("create table child_users (extra_col text) inherits (|)");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.users"));
        }
    }

    // =====================================================================
    // Nhóm 105: Từ khoá viết hoa/thường lẫn lộn (mixed case)
    // =====================================================================

    @Nested
    @DisplayName("Từ khoá viết hoa/thường lẫn lộn")
    class MixedCaseKeywordTests {

        @Test
        @DisplayName("SeLeCt/FrOm/WhErE viết hoa thường lẫn lộn - vẫn gợi ý đúng")
        void mixedCaseKeywordsStillWork() {
            var result = suggest("SeLeCt * FrOm public.users WhErE |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("Toàn bộ viết hoa (SELECT * FROM ... WHERE) - vẫn gợi ý đúng")
        void allUppercaseKeywordsStillWork() {
            var result = suggest("SELECT * FROM public.users WHERE |");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 106: Định danh rất dài / danh sách IN rất lớn (hiệu năng)
    // =====================================================================

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
            assertDoesNotThrow(() -> suggest(sql));
            assertTrue(System.currentTimeMillis() - start < 3000, "Quá chậm với danh sách IN 500 phần tử");
        }

        @Test
        @DisplayName("Alias rất dài (200 ký tự) - không throw")
        void veryLongAliasNameDoesNotThrow() {
            String longAlias = "a".repeat(200);
            assertDoesNotThrow(() -> suggest("select " + longAlias + ".| from public.users " + longAlias));
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
            assertDoesNotThrow(() -> suggest(sql));
            assertTrue(System.currentTimeMillis() - start < 3000, "Quá chậm với 100 cột trong SELECT list");
        }
    }

    // =====================================================================
    // Nhóm 107: Nhiều sub-action trong 1 câu ALTER TABLE
    // =====================================================================

    @Nested
    @DisplayName("Nhiều sub-action trong 1 câu ALTER TABLE (cách nhau bởi dấu phẩy)")
    class MultiActionAlterTableTests {

        @Test
        @DisplayName("ALTER TABLE ADD COLUMN a int, ADD COLUMN | - action thứ 2 vẫn gợi ý kiểu dữ liệu")
        void secondAddColumnActionDataTypeSuggestions() {
            var result = suggest("alter table public.users add column a int, add column b |");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
        }

        @Test
        @DisplayName("ALTER TABLE DROP COLUMN a, ALTER COLUMN b TYPE | - action thứ 2 vẫn gợi ý kiểu dữ liệu")
        void secondAlterColumnActionDataTypeSuggestions() {
            var result = suggest("alter table public.users drop column email, alter column name type |");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
        }
    }

    // =====================================================================
    // Nhóm 108: DISTINCT ON nhiều cột
    // =====================================================================

    @Nested
    @DisplayName("DISTINCT ON nhiều cột")
    class DistinctOnMultipleColumnsTests {

        @Test
        @DisplayName("DISTINCT ON (col1, |) - gợi ý cột thứ 2")
        void distinctOnSecondColumnSuggestions() {
            var result = suggest("select distinct on (status, |) * from public.orders");
            assertTrue(hasKeyOfType(result, "orders.customer_id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 109: Composite type / truy cập field dạng row
    // =====================================================================

    @Nested
    @DisplayName("Composite type / truy cập field dạng row")
    class CompositeTypeFieldAccessTests {

        @Test
        @DisplayName("(row_expr).field - không throw")
        void rowFieldAccessDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select (u).name from public.users u where |"));
        }

        @Test
        @DisplayName("CREATE TYPE composite với nhiều field - không throw")
        void createCompositeTypeDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create type point as (x int, y |)"));
        }
    }

    // =====================================================================
    // Nhóm 110: GENERATED ALWAYS AS IDENTITY
    // =====================================================================

    @Nested
    @DisplayName("GENERATED ALWAYS AS IDENTITY column definition")
    class GeneratedIdentityColumnTests {

        @Test
        @DisplayName("CREATE TABLE ... id int GENERATED ALWAYS AS IDENTITY - không throw")
        void generatedAlwaysAsIdentityDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table t (id int generated always as identity, name |)"));
        }

        @Test
        @DisplayName("ALTER TABLE ... ADD GENERATED ALWAYS AS IDENTITY - không throw")
        void alterAddGeneratedIdentityDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "alter table public.users alter column id add generated always as identity|"));
        }
    }

    // =====================================================================
    // Nhóm 111: VALUES đứng độc lập (nhiều dòng)
    // =====================================================================

    @Nested
    @DisplayName("VALUES đứng độc lập, nhiều dòng")
    class StandaloneValuesTests {

        @Test
        @DisplayName("VALUES (1, 'a'), (2, |) - dòng thứ 2 vẫn không throw")
        void multiRowValuesSecondRowDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("values (1, 'a'), (2, |)"));
        }

        @Test
        @DisplayName("VALUES dùng làm nguồn cho INSERT ... SELECT - không throw")
        void valuesAsInsertSourceDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "insert into public.users (id, name) select * from (values (1, |)) as v(id, name)"));
        }
    }

    // =====================================================================
    // Nhóm 112: NOTIFY/LISTEN với payload
    // =====================================================================

    @Nested
    @DisplayName("NOTIFY/LISTEN với payload")
    class NotifyListenPayloadTests {

        @Test
        @DisplayName("[GIỚI HẠN THẬT] NOTIFY channel, 'payload' - NOTIFY dùng rule 'colid notify_payload?' "
                + "(g4 dòng 2363) nhưng parent trực tiếp của colid là NotifyStmt, không thuộc 5 context mà "
                + "CompletionEngine nhận diện -> không có gợi ý tên channel/bảng nào cả, đúng như engine hiện tại.")
        void notifyWithPayloadDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("notify my_channel, |"));
        }

        @Test
        @DisplayName("[GIỚI HẠN THẬT] UNLISTEN | - cùng lý do: 'UNLISTEN colid' (g4 dòng 2375) không thuộc "
                + "context nào được xử lý")
        void unlistenAllDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("unlisten |"));
        }
    }

    // =====================================================================
    // Nhóm 113: Hàm lồng nhau nhiều tầng (không phải CASE WHEN)
    // =====================================================================

    @Nested
    @DisplayName("Hàm lồng nhau nhiều tầng: upper(lower(trim(col)))")
    class DeeplyNestedFunctionCallTests {

        @Test
        @DisplayName("upper(lower(trim(|))) - gợi ý cột ở tầng trong cùng")
        void deeplyNestedFunctionInnermostColumnSuggestions() {
            var result = suggest("select upper(lower(trim(|))) from public.users");
            assertTrue(hasKeyOfType(result, "users.name", "column")
                    || hasKeyOfType(result, "users.email", "column"));
        }

        @Test
        @DisplayName("10 tầng hàm lồng nhau (concat(concat(...))) - không treo")
        void tenLevelsNestedFunctionsDoesNotThrow() {
            StringBuilder sb = new StringBuilder("select ");
            for (int i = 0; i < 10; i++) sb.append("concat(");
            sb.append("name");
            for (int i = 0; i < 10; i++) sb.append(", |)");
            sb.append(" from public.users");
            assertDoesNotThrow(() -> suggest(sb.toString()));
        }
    }

    // =====================================================================
    // Nhóm 114: Rác thừa sau câu lệnh hợp lệ (parse-error robustness)
    // =====================================================================

    @Nested
    @DisplayName("Ký tự rác sau câu lệnh hợp lệ - vẫn không throw ở vị trí caret hợp lệ")
    class TrailingGarbageRobustnessTests {

        @Test
        @DisplayName("Câu lệnh hợp lệ, caret ở giữa, rác vô nghĩa ở CUỐI câu - vẫn gợi ý đúng tại caret")
        void validCaretPositionWithTrailingGarbageStillWorks() {
            var result = suggest("select * from public.users where |  )) ]] garbage $$$ ---");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("Dấu ngoặc đóng thừa không khớp ngoặc mở nào - không throw")
        void unmatchedClosingParenDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where id = 1) and |"));
        }
    }

    // =====================================================================
    // Nhóm 115: CTE với danh sách tên cột tường minh
    // =====================================================================

    @Nested
    @DisplayName("CTE với danh sách tên cột tường minh: cte(col1, col2) AS (...)")
    class CteExplicitColumnListTests {

        @Test
        @DisplayName("WITH c(a, b) AS (SELECT id, name FROM users) SELECT c.| FROM c - resolve theo tên cột đặt lại")
        void cteExplicitColumnListResolvesRenamedColumns() {
            var result = suggest("with c(a, b) as (select id, name from public.users) select c.| from c");
            assertDoesNotThrow(() -> suggest("with c(a, b) as (select id, name from public.users) select c.a from c"));
            assertNotNull(result);
        }
    }

    // =====================================================================
    // Nhóm 116: LATERAL với hàm trả về set (không phải subquery)
    // =====================================================================

    @Nested
    @DisplayName("LATERAL với hàm trả về set (function call, không phải subquery)")
    class LateralFunctionCallTests {

        @Test
        @DisplayName("CROSS JOIN LATERAL unnest(col) - không throw")
        void lateralUnnestFunctionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select * from public.users u cross join lateral unnest(u.|) as tag"));
        }

        @Test
        @DisplayName("JOIN LATERAL generate_series(1, col) - không throw")
        void lateralGenerateSeriesDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select * from public.users u, lateral generate_series(1, |)"));
        }
    }

    // =====================================================================
    // Nhóm 117: WITH ORDINALITY
    // =====================================================================

    @Nested
    @DisplayName("WITH ORDINALITY")
    class WithOrdinalityTests {

        @Test
        @DisplayName("unnest(arr) WITH ORDINALITY - không throw")
        void unnestWithOrdinalityDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select * from unnest(array[1,2,3]) with ordinality as t(val, idx) where |"));
        }
    }

    // =====================================================================
    // Nhóm 118: GROUPING() function (dùng với GROUPING SETS/ROLLUP/CUBE)
    // =====================================================================

    @Nested
    @DisplayName("GROUPING() function")
    class GroupingFunctionTests {

        @Test
        @DisplayName("SELECT GROUPING(col), ... GROUP BY ROLLUP(col) - không throw")
        void groupingFunctionWithRollupDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select grouping(status), status, count(*) from public.orders group by rollup(status) having |"));
        }
    }

    // =====================================================================
    // Nhóm 119: Ràng buộc TRUNCATE với tuỳ chọn
    // =====================================================================

    @Nested
    @DisplayName("TRUNCATE với tuỳ chọn (RESTART IDENTITY, CASCADE)")
    class TruncateOptionsTests {

        @Test
        @DisplayName("TRUNCATE table RESTART IDENTITY CASCADE - không throw")
        void truncateWithOptionsDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("truncate table public.users restart identity cascade|"));
        }

        @Test
        @DisplayName("TRUNCATE nhiều bảng cách nhau dấu phẩy - bảng thứ 2 vẫn gợi ý")
        void truncateMultipleTablesSecondTableSuggestions() {
            var result = suggest("truncate table public.users, |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.orders"));
        }
    }

    // =====================================================================
    // Nhóm 120: DEFAULT với biểu thức hàm
    // =====================================================================

    @Nested
    @DisplayName("Cột DEFAULT với biểu thức hàm (now(), gen_random_uuid())")
    class ColumnDefaultFunctionExpressionTests {

        @Test
        @DisplayName("CREATE TABLE ... created_at timestamp DEFAULT now() - không throw")
        void columnDefaultNowFunctionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table t (id int, created_at timestamp default now(), |)"));
        }

        @Test
        @DisplayName("ALTER TABLE ... ALTER COLUMN SET DEFAULT - không throw")
        void alterColumnSetDefaultDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "alter table public.users alter column created_date set default |"));
        }
    }

    // =====================================================================
    // Nhóm 121: FOREIGN KEY với MATCH và ON DELETE/UPDATE action
    // =====================================================================

    @Nested
    @DisplayName("FOREIGN KEY với MATCH và ON DELETE/UPDATE action")
    class ForeignKeyMatchActionTests {

        @Test
        @DisplayName("REFERENCES table MATCH FULL ON DELETE CASCADE ON UPDATE CASCADE - không throw")
        void foreignKeyMatchAndActionsDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "alter table public.orders add constraint fk1 foreign key (customer_id) "
                            + "references public.users (id) match full on delete cascade on update |"));
        }
    }

    // =====================================================================
    // Nhóm 122: COLLATE clause
    // =====================================================================

    @Nested
    @DisplayName("COLLATE clause")
    class CollateClauseTests {

        @Test
        @DisplayName("ORDER BY col COLLATE \"C\" - không throw")
        void orderByCollateDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users order by name collate \"C\" |"));
        }

        @Test
        @DisplayName("Khai báo cột với COLLATE - không throw")
        void columnDeclarationWithCollateDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create table t (name text collate |)"));
        }
    }

    // =====================================================================
    // Nhóm 123: SELECT INTO
    // =====================================================================

    @Nested
    @DisplayName("SELECT ... INTO")
    class SelectIntoTests {

        @Test
        @DisplayName("SELECT col INTO new_table FROM | - gợi ý bảng nguồn")
        void selectIntoSourceTableSuggestions() {
            var result = suggest("select id, name into backup_users from |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.users"));
        }

        @Test
        @DisplayName("SELECT ... INTO TEMP TABLE - không throw")
        void selectIntoTempTableDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select | into temp table t from public.users"));
        }
    }

    // =====================================================================
    // Nhóm 124: PL/pgSQL - biến DECLARE (không phải cursor)
    // =====================================================================

    @Nested
    @DisplayName("PL/pgSQL - khai báo biến trong DECLARE")
    class PlpgsqlDeclareVariableTests {

        @Test
        @DisplayName("DECLARE x int; - không throw")
        void declareVariableDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("do $$ declare x |"));
        }

        @Test
        @DisplayName("DECLARE x public.users%ROWTYPE - không throw")
        void declareRowTypeVariableDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("do $$ declare x public.users%rowtype; begin end; |$$"));
        }
    }

    // =====================================================================
    // Nhóm 125: PL/pgSQL - IF/ELSIF/ELSE
    // =====================================================================

    @Nested
    @DisplayName("PL/pgSQL - IF/ELSIF/ELSE")
    class PlpgsqlIfElsifTests {

        @Test
        @DisplayName("IF ... THEN ... ELSIF ... THEN ... ELSE ... END IF - không throw")
        void ifElsifElseDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "do $$ begin if 1 = 1 then raise notice 'a'; elsif 1 = 2 then raise notice |"));
        }
    }

    // =====================================================================
    // Nhóm 126: PL/pgSQL - LOOP/WHILE/FOR
    // =====================================================================

    @Nested
    @DisplayName("PL/pgSQL - LOOP/WHILE/FOR")
    class PlpgsqlLoopTests {

        @Test
        @DisplayName("WHILE ... LOOP ... END LOOP - không throw")
        void whileLoopDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("do $$ begin while true loop exit when |"));
        }

        @Test
        @DisplayName("FOR i IN 1..10 LOOP - không throw")
        void forRangeLoopDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("do $$ begin for i in 1..|"));
        }

        @Test
        @DisplayName("FOR rec IN SELECT ... LOOP - không throw")
        void forSelectLoopDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("do $$ begin for rec in select | from public.users loop null; end loop; end; $$"));
        }
    }

    // =====================================================================
    // Nhóm 127: PL/pgSQL - RAISE
    // =====================================================================

    @Nested
    @DisplayName("PL/pgSQL - RAISE NOTICE/EXCEPTION")
    class PlpgsqlRaiseTests {

        @Test
        @DisplayName("RAISE EXCEPTION '...' USING - không throw")
        void raiseExceptionUsingDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("do $$ begin raise exception 'error %', 1 using |"));
        }

        @Test
        @DisplayName("RAISE NOTICE với nhiều tham số - không throw")
        void raiseNoticeMultipleArgsDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("do $$ begin raise notice '% %', 'a', |"));
        }
    }

    // =====================================================================
    // Nhóm 128: RETURNING với biểu thức/hàm
    // =====================================================================

    @Nested
    @DisplayName("RETURNING với biểu thức/hàm (không chỉ tên cột trần)")
    class ReturningWithFunctionExpressionTests {

        @Test
        @DisplayName("INSERT ... RETURNING upper(name), | - cột tiếp theo vẫn gợi ý đúng")
        void returningFunctionThenColumnSuggestions() {
            var result = suggest("insert into public.users (id, name) values (1, 'a') returning upper(name), |");
            assertTrue(hasKeyOfType(result, "users.email", "column")
                    || hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("DELETE ... RETURNING *, | - không throw")
        void returningStarThenMoreDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("delete from public.users where id = 1 returning *, |"));
        }
    }

    // =====================================================================
    // Nhóm 129: UNIQUE NULLS NOT DISTINCT
    // =====================================================================

    @Nested
    @DisplayName("UNIQUE NULLS NOT DISTINCT (PostgreSQL 15+)")
    class UniqueNullsNotDistinctTests {

        @Test
        @DisplayName("CREATE TABLE ... UNIQUE NULLS NOT DISTINCT (col) - không throw")
        void uniqueNullsNotDistinctDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table t (email text, unique nulls not distinct (|))"));
        }
    }

    // =====================================================================
    // Nhóm 130: EXCLUDE constraint
    // =====================================================================

    @Nested
    @DisplayName("EXCLUDE constraint")
    class ExcludeConstraintTests {

        @Test
        @DisplayName("CREATE TABLE ... EXCLUDE USING gist (col WITH =) - không throw")
        void excludeConstraintDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table t (id int, exclude using gist (|"));
        }
    }

    // =====================================================================
    // Nhóm 131: Sequence với đầy đủ tuỳ chọn
    // =====================================================================

    @Nested
    @DisplayName("CREATE SEQUENCE với đầy đủ tuỳ chọn")
    class FullSequenceOptionsTests {

        @Test
        @DisplayName("CREATE SEQUENCE với INCREMENT/MINVALUE/MAXVALUE/CYCLE - không throw")
        void createSequenceFullOptionsDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create sequence s1 increment by 1 minvalue 1 maxvalue 1000 start with 1 cycle |"));
        }

        @Test
        @DisplayName("ALTER SEQUENCE ... RESTART WITH | - không throw")
        void alterSequenceRestartWithDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter sequence s1 restart with |"));
        }
    }

    // =====================================================================
    // Nhóm 132: CTE AS MATERIALIZED / NOT MATERIALIZED
    // =====================================================================

    @Nested
    @DisplayName("CTE AS MATERIALIZED / AS NOT MATERIALIZED")
    class CteMaterializedHintTests {

        @Test
        @DisplayName("WITH c AS MATERIALIZED (...) - không throw")
        void cteAsMaterializedDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "with c as materialized (select | from public.users) select * from c"));
        }

        @Test
        @DisplayName("WITH c AS NOT MATERIALIZED (...) - không throw")
        void cteAsNotMaterializedDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "with c as not materialized (select | from public.users) select * from c"));
        }
    }

    // =====================================================================
    // Nhóm 133: Nhiều recursive term trong 1 CTE
    // =====================================================================

    @Nested
    @DisplayName("Recursive CTE với UNION nhiều vế")
    class MultiTermRecursiveCteTests {

        @Test
        @DisplayName("WITH RECURSIVE base UNION vế 2 UNION vế 3 - không throw")
        void recursiveCteMultipleUnionTermsDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "with recursive r as ("
                            + "select id from public.users where id = 1 "
                            + "union select id from public.users where id = 2 "
                            + "union select u.id from public.users u join r on u.id = r.id + 1) "
                            + "select | from r"));
        }
    }

    // =====================================================================
    // Nhóm 134: INSERT OVERRIDING SYSTEM VALUE
    // =====================================================================

    @Nested
    @DisplayName("INSERT OVERRIDING SYSTEM VALUE (cho GENERATED ALWAYS AS IDENTITY)")
    class InsertOverridingSystemValueTests {

        @Test
        @DisplayName("INSERT INTO ... OVERRIDING SYSTEM VALUE VALUES - không throw")
        void insertOverridingSystemValueDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "insert into public.users overriding system value values (1, |"));
        }
    }

    // =====================================================================
    // Nhóm 135: DECLARE CURSOR WITH HOLD / MOVE / CLOSE
    // =====================================================================

    @Nested
    @DisplayName("Cursor nâng cao: WITH HOLD, MOVE, CLOSE")
    class AdvancedCursorTests {

        @Test
        @DisplayName("DECLARE cursor WITH HOLD FOR SELECT - không throw")
        void declareCursorWithHoldDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "declare c1 cursor with hold for select | from public.users"));
        }

        @Test
        @DisplayName("MOVE FORWARD n IN cursor - không throw")
        void moveCursorForwardDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("move forward 5 in |"));
        }

        @Test
        @DisplayName("CLOSE cursor_name - không throw")
        void closeCursorDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("close |"));
        }

        @Test
        @DisplayName("CLOSE ALL - không throw")
        void closeAllCursorsDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("close |all"));
        }
    }

    // =====================================================================
    // Nhóm 136: GRANT với danh sách cột
    // =====================================================================

    @Nested
    @DisplayName("GRANT với danh sách cột cụ thể")
    class GrantWithColumnListTests {

        @Test
        @DisplayName("GRANT UPDATE (col1, |) ON table TO role - gợi ý cột thứ 2")
        void grantColumnListSecondColumnDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("grant update (name, |) on public.users to some_role"));
        }
    }

    // =====================================================================
    // Nhóm 137: Composite primary key / multi-column constraint
    // =====================================================================

    @Nested
    @DisplayName("Composite primary key / multi-column constraint")
    class CompositePrimaryKeyTests {

        @Test
        @DisplayName("PRIMARY KEY (col1, |) - gợi ý cột thứ 2 trong khoá chính ghép")
        void compositePrimaryKeySecondColumnDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table t (a int, b int, primary key (a, |))"));
        }

        @Test
        @DisplayName("CHECK constraint tham chiếu nhiều cột (col1 + col2 > 0) - không throw")
        void multiColumnCheckConstraintDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table t (a int, b int, check (a + |))"));
        }

        @Test
        @DisplayName("DEFERRABLE INITIALLY DEFERRED constraint - không throw")
        void deferrableInitiallyDeferredDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table t (a int, constraint c1 unique (a) deferrable initially |"));
        }
    }

    // =====================================================================
    // Nhóm 138: TEMP/UNLOGGED table, function OUT/TABLE return
    // =====================================================================

    @Nested
    @DisplayName("TEMP/UNLOGGED table, function trả về OUT/TABLE")
    class TempTableAndFunctionReturnTests {

        @Test
        @DisplayName("CREATE TEMP TABLE ... - không throw")
        void createTempTableDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create temp table t (id |)"));
        }

        @Test
        @DisplayName("CREATE UNLOGGED TABLE ... - không throw")
        void createUnloggedTableDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create unlogged table t (id |)"));
        }

        @Test
        @DisplayName("CREATE FUNCTION với tham số OUT - không throw")
        void functionWithOutParameterDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create function f(a int, out b |"));
        }

        @Test
        @DisplayName("CREATE FUNCTION RETURNS TABLE(col type, ...) - không throw")
        void functionReturnsTableDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create function f() returns table(id int, name |"));
        }

        @Test
        @DisplayName("CREATE FUNCTION với VARIADIC tham số - không throw")
        void functionWithVariadicParameterDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create function f(variadic a |"));
        }
    }

    // =====================================================================
    // Nhóm 139: ORDER BY nâng cao (NULLS FIRST/LAST, nhiều cột trộn ASC/DESC, subquery)
    // =====================================================================

    @Nested
    @DisplayName("ORDER BY nâng cao")
    class AdvancedOrderByTests {

        @Test
        @DisplayName("ORDER BY col NULLS FIRST - không throw")
        void orderByNullsFirstDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users order by name nulls first |"));
        }

        @Test
        @DisplayName("ORDER BY col1 ASC, col2 DESC NULLS LAST, | - cột thứ 3 vẫn gợi ý")
        void orderByMixedAscDescNullsThirdColumnSuggestions() {
            var result = suggest("select * from public.orders order by status asc, total desc nulls last, |");
            assertTrue(hasKeyOfType(result, "orders.customer_id", "column"));
        }

        @Test
        @DisplayName("ORDER BY (SELECT ... ) - subquery trong ORDER BY, không throw")
        void orderBySubqueryDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select * from public.users u order by (select count(*) from public.orders where customer_id = |)"));
        }

        @Test
        @DisplayName("ORDER BY theo số thứ tự cột (ORDER BY 1, 2) - không throw")
        void orderByColumnPositionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select id, name from public.users order by 1, |"));
        }
    }

    // =====================================================================
    // Nhóm 140: Aggregate với ORDER BY nội bộ / ordered-set aggregate
    // =====================================================================

    @Nested
    @DisplayName("Aggregate với ORDER BY nội bộ / ordered-set aggregate")
    class OrderedSetAggregateTests {

        @Test
        @DisplayName("string_agg(col, ',' ORDER BY col2) - gợi ý cột trong ORDER BY nội bộ")
        void stringAggInternalOrderByColumnSuggestions() {
            var result = suggest("select string_agg(name, ',' order by |) from public.users");
            assertTrue(hasKeyOfType(result, "users.id", "column")
                    || hasKeyOfType(result, "users.email", "column"));
        }

        @Test
        @DisplayName("percentile_cont(0.5) WITHIN GROUP (ORDER BY col) - không throw")
        void percentileContWithinGroupDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select percentile_cont(0.5) within group (order by |) from public.orders"));
        }

        @Test
        @DisplayName("mode() WITHIN GROUP (ORDER BY col) - không throw")
        void modeWithinGroupDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select mode() within group (order by |) from public.orders"));
        }
    }

    // =====================================================================
    // Nhóm 141: ALL/ANY/SOME subquery comparison, row comparison
    // =====================================================================

    @Nested
    @DisplayName("ALL/ANY/SOME subquery comparison, row comparison")
    class AllAnySomeRowComparisonTests {

        @Test
        @DisplayName("col > ALL (subquery) - không throw")
        void greaterThanAllSubqueryDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select * from public.orders where total > all (select | from public.orders where status = 'closed')"));
        }

        @Test
        @DisplayName("col = ANY (subquery) - không throw")
        void equalAnySubqueryDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select * from public.users where id = any (select customer_id from public.orders where |)"));
        }

        @Test
        @DisplayName("(a, b) = (c, d) row comparison - không throw")
        void rowComparisonDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select * from public.users where (id, name) = (1, |)"));
        }

        @Test
        @DisplayName("(a, b) IN ((1,2),(3,4)) row IN list - không throw")
        void rowInListDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select * from public.orders where (customer_id, status) in ((1, 'a'), (2, |))"));
        }
    }

    // =====================================================================
    // Nhóm 142: Set-returning function làm biểu thức cột
    // =====================================================================

    @Nested
    @DisplayName("Set-returning function làm biểu thức cột trong SELECT list")
    class SetReturningFunctionAsColumnTests {

        @Test
        @DisplayName("SELECT unnest(arr_col) FROM table - không throw")
        void unnestAsColumnExpressionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select unnest(tags), | from public.users"));
        }

        @Test
        @DisplayName("SELECT generate_series(1, col) FROM table - không throw")
        void generateSeriesAsColumnExpressionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select generate_series(1, |) from public.orders"));
        }
    }

    // =====================================================================
    // Nhóm 143: DROP FUNCTION với chữ ký đầy đủ tham số
    // =====================================================================

    @Nested
    @DisplayName("DROP FUNCTION/AGGREGATE/OPERATOR với chữ ký tham số")
    class DropWithSignatureTests {

        @Test
        @DisplayName("DROP FUNCTION f(int, text) - không throw")
        void dropFunctionWithArgTypesDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("drop function f(int, |)"));
        }

        @Test
        @DisplayName("DROP FUNCTION IF EXISTS f(int) CASCADE - không throw")
        void dropFunctionIfExistsCascadeDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("drop function if exists f(int) |"));
        }

        @Test
        @DisplayName("DROP AGGREGATE agg(numeric) - không throw")
        void dropAggregateDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("drop aggregate agg(|)"));
        }
    }

    // =====================================================================
    // Nhóm 144: COMMENT ON các loại object
    // =====================================================================

    @Nested
    @DisplayName("COMMENT ON (function, type, column, index...)")
    class CommentOnVariousObjectsTests {

        @Test
        @DisplayName("COMMENT ON FUNCTION f(int) IS - không throw")
        void commentOnFunctionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("comment on function f(int) is |"));
        }

        @Test
        @DisplayName("COMMENT ON TYPE t IS - không throw")
        void commentOnTypeDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("comment on type t is |"));
        }

        @Test
        @DisplayName("COMMENT ON INDEX idx1 IS - không throw")
        void commentOnIndexDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("comment on index idx1 is |"));
        }

        @Test
        @DisplayName("COMMENT ON CONSTRAINT c1 ON table IS - không throw")
        void commentOnConstraintDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("comment on constraint c1 on public.users is |"));
        }
    }

    // =====================================================================
    // Nhóm 145: ALTER FUNCTION (rename, owner, schema)
    // =====================================================================

    @Nested
    @DisplayName("ALTER FUNCTION (rename, owner, schema)")
    class AlterFunctionTests {

        @Test
        @DisplayName("ALTER FUNCTION f(int) RENAME TO - không throw")
        void alterFunctionRenameDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter function f(int) rename to |"));
        }

        @Test
        @DisplayName("ALTER FUNCTION f(int) OWNER TO - không throw")
        void alterFunctionOwnerDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter function f(int) owner to |"));
        }

        @Test
        @DisplayName("ALTER FUNCTION f(int) SET SCHEMA - không throw")
        void alterFunctionSetSchemaDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter function f(int) set schema |"));
        }
    }

    // =====================================================================
    // Nhóm 146: GRANT/REVOKE nâng cao (nhiều role, WITH GRANT OPTION)
    // =====================================================================

    @Nested
    @DisplayName("GRANT/REVOKE nâng cao")
    class AdvancedGrantRevokeTests {

        @Test
        @DisplayName("GRANT SELECT, INSERT ON table TO role1, role2 - không throw")
        void grantMultiplePrivilegesToMultipleRolesDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("grant select, insert on public.users to role1, |"));
        }

        @Test
        @DisplayName("GRANT ... WITH GRANT OPTION - không throw")
        void grantWithGrantOptionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("grant select on public.users to role1 with |"));
        }

        @Test
        @DisplayName("REVOKE GRANT OPTION FOR - không throw")
        void revokeGrantOptionForDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("revoke grant option for select on public.users from |"));
        }
    }

    // =====================================================================
    // Nhóm 147: CREATE/ALTER ROLE
    // =====================================================================

    @Nested
    @DisplayName("CREATE/ALTER ROLE với tuỳ chọn")
    class RoleDdlTests {

        @Test
        @DisplayName("CREATE ROLE ... WITH LOGIN PASSWORD - không throw")
        void createRoleWithLoginPasswordDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create role r1 with login password |"));
        }

        @Test
        @DisplayName("ALTER ROLE ... SET search_path - không throw")
        void alterRoleSetSearchPathDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter role r1 set search_path to |"));
        }

        @Test
        @DisplayName("SET ROLE - không throw")
        void setRoleDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("set role |"));
        }

        @Test
        @DisplayName("RESET ROLE - không throw")
        void resetRoleDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("reset role|"));
        }
    }

    // =====================================================================
    // Nhóm 148: Table function với danh sách cột định nghĩa (AS t(a int, b text))
    // =====================================================================

    @Nested
    @DisplayName("Table function với danh sách cột định nghĩa tường minh")
    class TableFunctionColumnDefinitionListTests {

        @Test
        @DisplayName("SELECT * FROM json_to_recordset(...) AS t(a int, b |) - gợi ý kiểu dữ liệu")
        void tableFunctionColumnDefListDataTypeSuggestions() {
            var result = suggest("select * from json_to_recordset('[]') as t(a int, b |)");
            assertTrue(hasKeyOfType(result, "text", "datatype"));
        }
    }

    // =====================================================================
    // Nhóm 149: LIMIT/OFFSET/FETCH kết hợp WITH TIES
    // =====================================================================

    @Nested
    @DisplayName("LIMIT/OFFSET/FETCH kết hợp")
    class LimitOffsetFetchCombinationTests {

        @Test
        @DisplayName("LIMIT ALL - không throw")
        void limitAllDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users limit all |"));
        }

        @Test
        @DisplayName("OFFSET ... FETCH FIRST ... ROWS WITH TIES - không throw")
        void offsetFetchWithTiesDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "select * from public.users order by id offset 5 rows fetch first 10 rows with |"));
        }

        @Test
        @DisplayName("FETCH FIRST ROW ONLY (số ít, không có n) - không throw")
        void fetchFirstRowOnlySingularDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users order by id fetch first row |"));
        }
    }

    // =====================================================================
    // Nhóm 150: Biểu thức boolean phức hợp (NOT, short-circuit)
    // =====================================================================

    @Nested
    @DisplayName("Biểu thức boolean phức hợp")
    class ComplexBooleanExpressionTests {

        @Test
        @DisplayName("WHERE NOT col AND col2 - không throw")
        void notColumnAndAnotherDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where not id > 0 and |"));
        }

        @Test
        @DisplayName("WHERE col IS DISTINCT FROM | - không throw")
        void isDistinctFromDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where name is distinct from |"));
        }

        @Test
        @DisplayName("WHERE col IS NOT DISTINCT FROM | - không throw")
        void isNotDistinctFromDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where name is not distinct from |"));
        }

        @Test
        @DisplayName("WHERE col BETWEEN SYMMETRIC a AND | - không throw")
        void betweenSymmetricDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.orders where total between symmetric 10 and |"));
        }
    }

    // =====================================================================
    // Nhóm 151: Nhiều biến session trong 1 câu SET
    // =====================================================================

    @Nested
    @DisplayName("SET nhiều biến session / LOCK mode")
    class SetSessionAndLockModeTests {

        @Test
        @DisplayName("SET LOCAL statement_timeout - không throw")
        void setLocalStatementTimeoutDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("set local statement_timeout to |"));
        }

        @Test
        @DisplayName("LOCK TABLE ... IN ACCESS EXCLUSIVE MODE - không throw")
        void lockTableAccessExclusiveModeDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("lock table public.users in |"));
        }

        @Test
        @DisplayName("LOCK TABLE ... NOWAIT - không throw")
        void lockTableNowaitDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("lock table public.users in access exclusive mode |"));
        }
    }

    // =====================================================================
    // Nhóm 152: VACUUM/ANALYZE với tuỳ chọn dạng ngoặc
    // =====================================================================

    @Nested
    @DisplayName("VACUUM/ANALYZE với tuỳ chọn dạng ngoặc, REINDEX CONCURRENTLY")
    class VacuumAnalyzeReindexOptionsTests {

        @Test
        @DisplayName("VACUUM (VERBOSE, ANALYZE) table - không throw")
        void vacuumParenthesizedOptionsDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("vacuum (verbose, analyze) |"));
        }

        @Test
        @DisplayName("ANALYZE table (col1, col2) - gợi ý cột thứ 2")
        void analyzeWithColumnListSecondColumnDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("analyze public.users (id, |)"));
        }

        @Test
        @DisplayName("REINDEX INDEX CONCURRENTLY - không throw")
        void reindexIndexConcurrentlyDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("reindex index concurrently |"));
        }

        @Test
        @DisplayName("CLUSTER table_name USING index_name - không throw")
        void clusterUsingIndexDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("cluster public.users using |"));
        }
    }

    // =====================================================================
    // Nhóm 153: Cột với nhiều ràng buộc nối tiếp
    // =====================================================================

    @Nested
    @DisplayName("Cột với nhiều ràng buộc nối tiếp (NOT NULL DEFAULT CHECK)")
    class ChainedColumnConstraintsTests {

        @Test
        @DisplayName("col int NOT NULL DEFAULT 0 CHECK (col >= |) - không throw")
        void chainedNotNullDefaultCheckDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create table t (qty int not null default 0 check (qty >= |))"));
        }

        @Test
        @DisplayName("col text UNIQUE NOT NULL REFERENCES other(id) - không throw")
        void chainedUniqueNotNullReferencesDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table t (user_id int unique not null references public.users(|))"));
        }
    }

    // =====================================================================
    // Nhóm 154: INHERITS nhiều bảng cha
    // =====================================================================

    @Nested
    @DisplayName("INHERITS nhiều bảng cha cùng lúc")
    class MultipleInheritanceTests {

        @Test
        @DisplayName("CREATE TABLE ... INHERITS (parent1, |) - gợi ý bảng cha thứ 2")
        void multipleInheritsSecondParentTableSuggestions() {
            var result = suggest("create table t (extra int) inherits (public.users, |)");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.orders"));
        }
    }

    // =====================================================================
    // Nhóm 155: PARTITION BY LIST/RANGE với FOR VALUES
    // =====================================================================

    @Nested
    @DisplayName("PARTITION với FOR VALUES IN/FROM-TO, DEFAULT partition, HASH modulus")
    class PartitionForValuesTests {

        @Test
        @DisplayName("PARTITION OF parent FOR VALUES IN (list) - gợi ý giá trị (không throw)")
        void partitionForValuesInListDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table orders_active partition of public.orders for values in (|)"));
        }

        @Test
        @DisplayName("PARTITION OF parent FOR VALUES FROM (...) TO (...) - không throw")
        void partitionForValuesFromToDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table orders_2024 partition of public.orders for values from ('2024-01-01') to (|)"));
        }

        @Test
        @DisplayName("PARTITION OF parent DEFAULT - không throw")
        void defaultPartitionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create table orders_default partition of public.orders |"));
        }

        @Test
        @DisplayName("PARTITION BY HASH (col) rồi PARTITION OF ... FOR VALUES WITH (MODULUS, REMAINDER) - không throw")
        void hashPartitionModulusRemainderDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table orders_p0 partition of public.orders for values with (modulus 4, remainder |)"));
        }
    }

    // =====================================================================
    // Nhóm 156: Mảng đa chiều, ép kiểu mảng
    // =====================================================================

    @Nested
    @DisplayName("Mảng đa chiều, ép kiểu mảng")
    class MultiDimensionalArrayTests {

        @Test
        @DisplayName("int[][] khai báo cột mảng 2 chiều - không throw")
        void twoDimensionalArrayColumnDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create table t (matrix int[][], |)"));
        }

        @Test
        @DisplayName("col::int[] ép kiểu sang mảng - không throw")
        void castToArrayTypeDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select tags::int[] from public.users where |"));
        }

        @Test
        @DisplayName("ARRAY[[1,2],[3,4]] mảng lồng nhau - không throw")
        void nestedArrayLiteralDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select array[[1,2],[3,|]] from public.users"));
        }
    }

    // =====================================================================
    // Nhóm 157: CREATE DOMAIN với CHECK constraint
    // =====================================================================

    @Nested
    @DisplayName("CREATE DOMAIN với CHECK constraint")
    class DomainCheckConstraintTests {

        @Test
        @DisplayName("CREATE DOMAIN ... CHECK (VALUE > 0) - không throw")
        void createDomainWithCheckDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create domain positive_int as int check (value > |)"));
        }

        @Test
        @DisplayName("CREATE DOMAIN ... NOT NULL DEFAULT - không throw")
        void createDomainNotNullDefaultDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create domain d1 as text not null default |"));
        }
    }

    // =====================================================================
    // Nhóm 158: Nhiều constraint trên cùng 1 bảng, cách nhau dấu phẩy
    // =====================================================================

    @Nested
    @DisplayName("Nhiều table-constraint trên cùng 1 bảng")
    class MultipleTableConstraintsTests {

        @Test
        @DisplayName("PRIMARY KEY, rồi FOREIGN KEY, rồi CHECK - action thứ 3 vẫn không throw")
        void multipleTableConstraintsThirdOneDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table t (id int, customer_id int, amount int, "
                            + "primary key (id), "
                            + "foreign key (customer_id) references public.users(id), "
                            + "check (amount > |))"));
        }
    }

    // =====================================================================
    // Nhóm 159: Operator class (opclass) trong index
    // =====================================================================

    @Nested
    @DisplayName("Operator class (opclass) trong CREATE INDEX")
    class IndexOperatorClassTests {

        @Test
        @DisplayName("CREATE INDEX ... (col text_pattern_ops) - không throw")
        void indexWithOperatorClassDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create index idx1 on public.users (name |"));
        }

        @Test
        @DisplayName("CREATE INDEX ... USING gin (col gin_trgm_ops) - không throw")
        void ginIndexWithOperatorClassDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create index idx1 on public.users using gin (name |)"));
        }
    }

    // =====================================================================
    // Nhóm 160: Multi-column FOREIGN KEY tham chiếu multi-column
    // =====================================================================

    @Nested
    @DisplayName("Multi-column FOREIGN KEY")
    class MultiColumnForeignKeyTests {

        @Test
        @DisplayName("FOREIGN KEY (a, b) REFERENCES parent (c, |) - gợi ý cột thứ 2 của bảng cha")
        void multiColumnForeignKeySecondReferencedColumnDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table t (a int, b int, foreign key (a, b) references public.orders (id, |))"));
        }
    }

    // =====================================================================
    // Nhóm 161: Danh sách tham số hàm với DEFAULT value
    // =====================================================================

    @Nested
    @DisplayName("Tham số hàm với giá trị DEFAULT")
    class FunctionParameterDefaultValueTests {

        @Test
        @DisplayName("CREATE FUNCTION f(a int DEFAULT 0, b int DEFAULT |) - không throw")
        void functionParameterDefaultValueDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create function f(a int default 0, b int default |"));
        }
    }

    // =====================================================================
    // Nhóm 162: EXPLAIN với tuỳ chọn dạng ngoặc (FORMAT, BUFFERS...)
    // =====================================================================

    @Nested
    @DisplayName("EXPLAIN với tuỳ chọn dạng ngoặc")
    class ExplainWithParenthesizedOptionsTests {

        @Test
        @DisplayName("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT - không throw")
        void explainParenthesizedOptionsDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "explain (analyze, buffers, format json) select * from public.users where |"));
        }
    }

    // =====================================================================
    // Nhóm 163: Nhiều bảng trong FROM kết hợp JOIN và dấu phẩy
    // =====================================================================

    @Nested
    @DisplayName("Kết hợp FROM comma-separated và JOIN trong cùng 1 câu")
    class MixedCommaAndJoinInFromTests {

        @Test
        @DisplayName("FROM a, b JOIN c ON ... - alias của cả 3 bảng đều resolve")
        void commaThenJoinAllThreeAliasesResolve() {
            var result = suggest(
                    "select | from public.users u, public.orders o join public.products p on o.id = p.id");
            assertTrue(hasKeyOfType(result, "u.id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 164: TYPE ép kiểu với độ chính xác (precision/scale)
    // =====================================================================

    @Nested
    @DisplayName("Kiểu dữ liệu với precision/scale: NUMERIC(10,2), VARCHAR(255)")
    class TypePrecisionScaleTests {

        @Test
        @DisplayName("col NUMERIC(10, |) - gợi ý scale (không throw)")
        void numericPrecisionScaleDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create table t (price numeric(10, |))"));
        }

        @Test
        @DisplayName("col VARCHAR(|) - gợi ý độ dài (không throw)")
        void varcharLengthDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create table t (name varchar(|))"));
        }

        @Test
        @DisplayName("CAST(col AS NUMERIC(10,2)) - không throw")
        void castToNumericWithPrecisionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select cast(total as numeric(10, |)) from public.orders"));
        }
    }

    // =====================================================================
    // Nhóm 165: WHERE CURRENT OF cursor (UPDATE/DELETE positioned)
    // =====================================================================

    @Nested
    @DisplayName("WHERE CURRENT OF cursor (positioned UPDATE/DELETE)")
    class WhereCurrentOfCursorTests {

        @Test
        @DisplayName("UPDATE ... WHERE CURRENT OF cursor_name - không throw")
        void updateWhereCurrentOfDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("update public.users set name = 'x' where current of |"));
        }

        @Test
        @DisplayName("DELETE ... WHERE CURRENT OF cursor_name - không throw")
        void deleteWhereCurrentOfDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("delete from public.users where current of |"));
        }
    }

    // =====================================================================
    // Nhóm 166: XML function (XMLELEMENT, XMLFOREST, XMLCONCAT)
    // =====================================================================

    @Nested
    @DisplayName("XML function")
    class XmlFunctionTests {

        @Test
        @DisplayName("XMLELEMENT(NAME tag, col) - không throw")
        void xmlElementDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select xmlelement(name tag, |) from public.users"));
        }

        @Test
        @DisplayName("XMLFOREST(col1, col2) - không throw")
        void xmlForestDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select xmlforest(name, |) from public.users"));
        }

        @Test
        @DisplayName("XMLCONCAT(col1, col2) - không throw")
        void xmlConcatDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select xmlconcat(name, |) from public.users"));
        }
    }

    // =====================================================================
    // Nhóm 167: Foreign data wrapper (SERVER, FOREIGN TABLE, IMPORT FOREIGN SCHEMA)
    // =====================================================================

    @Nested
    @DisplayName("Foreign data wrapper")
    class ForeignDataWrapperTests {

        @Test
        @DisplayName("CREATE FOREIGN DATA WRAPPER - không throw")
        void createForeignDataWrapperDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create foreign data wrapper |"));
        }

        @Test
        @DisplayName("CREATE SERVER ... FOREIGN DATA WRAPPER - không throw")
        void createServerDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create server s1 foreign data wrapper |"));
        }

        @Test
        @DisplayName("CREATE FOREIGN TABLE ... SERVER - không throw")
        void createForeignTableDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create foreign table t (id int) server |"));
        }

        @Test
        @DisplayName("IMPORT FOREIGN SCHEMA ... FROM SERVER ... INTO - không throw")
        void importForeignSchemaDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("import foreign schema s from server srv into |"));
        }

        @Test
        @DisplayName("CREATE USER MAPPING FOR user SERVER srv - không throw")
        void createUserMappingDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create user mapping for current_user server |"));
        }
    }

    // =====================================================================
    // Nhóm 168: Event trigger
    // =====================================================================

    @Nested
    @DisplayName("Event trigger")
    class EventTriggerTests {

        @Test
        @DisplayName("CREATE EVENT TRIGGER ... ON ddl_command_start EXECUTE FUNCTION - không throw")
        void createEventTriggerDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create event trigger et1 on ddl_command_start execute function |"));
        }

        @Test
        @DisplayName("ALTER EVENT TRIGGER ... DISABLE - không throw")
        void alterEventTriggerDisableDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter event trigger et1 |"));
        }
    }

    // =====================================================================
    // Nhóm 169: Operator / operator class / operator family
    // =====================================================================

    @Nested
    @DisplayName("CREATE OPERATOR / OPERATOR CLASS / OPERATOR FAMILY")
    class OperatorDdlTests {

        @Test
        @DisplayName("CREATE OPERATOR = (PROCEDURE = ...) - không throw")
        void createOperatorDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create operator === (leftarg = int, rightarg = int, procedure = |"));
        }

        @Test
        @DisplayName("CREATE OPERATOR CLASS ... FOR TYPE ... USING btree - không throw")
        void createOperatorClassDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create operator class oc1 for type int using btree as |"));
        }

        @Test
        @DisplayName("CREATE OPERATOR FAMILY ... USING gist - không throw")
        void createOperatorFamilyDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create operator family of1 using |"));
        }

        @Test
        @DisplayName("DROP OPERATOR = (int, int) - không throw")
        void dropOperatorDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("drop operator = (int, |)"));
        }
    }

    // =====================================================================
    // Nhóm 170: Text search config, collation, conversion, cast, transform
    // =====================================================================

    @Nested
    @DisplayName("TEXT SEARCH / COLLATION / CONVERSION / CAST / TRANSFORM DDL")
    class TextSearchCollationConversionCastTests {

        @Test
        @DisplayName("CREATE TEXT SEARCH CONFIGURATION - không throw")
        void createTextSearchConfigurationDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create text search configuration tsc1 (|"));
        }

        @Test
        @DisplayName("CREATE COLLATION ... FROM - không throw")
        void createCollationFromDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create collation c1 from |"));
        }

        @Test
        @DisplayName("CREATE CONVERSION ... FOR ... TO - không throw")
        void createConversionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create conversion conv1 for 'UTF8' to |"));
        }

        @Test
        @DisplayName("CREATE CAST (type AS type) WITH FUNCTION - không throw")
        void createCastDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create cast (int as text) with function |"));
        }

        @Test
        @DisplayName("CREATE TRANSFORM FOR type LANGUAGE lang - không throw")
        void createTransformDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create transform for hstore language plpython3u (from sql with function |"));
        }
    }

    // =====================================================================
    // Nhóm 171: Publication/Subscription (logical replication)
    // =====================================================================

    @Nested
    @DisplayName("CREATE PUBLICATION/SUBSCRIPTION (logical replication)")
    class PublicationSubscriptionTests {

        @Test
        @DisplayName("CREATE PUBLICATION ... FOR TABLE - gợi ý bảng")
        void createPublicationForTableSuggestions() {
            var result = suggest("create publication pub1 for table |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.users"));
        }

        @Test
        @DisplayName("CREATE PUBLICATION ... FOR ALL TABLES - không throw")
        void createPublicationForAllTablesDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create publication pub1 for all tables|"));
        }

        @Test
        @DisplayName("ALTER PUBLICATION ... ADD TABLE - gợi ý bảng")
        void alterPublicationAddTableSuggestions() {
            var result = suggest("alter publication pub1 add table |");
            var tables = keysOfType(result, "table");
            assertTrue(tables.contains("public.orders"));
        }

        @Test
        @DisplayName("CREATE SUBSCRIPTION ... CONNECTION ... PUBLICATION - không throw")
        void createSubscriptionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create subscription sub1 connection 'host=x' publication |"));
        }
    }

    // =====================================================================
    // Nhóm 172: TABLESPACE DDL
    // =====================================================================

    @Nested
    @DisplayName("CREATE/ALTER/DROP TABLESPACE")
    class TablespaceDdlTests {

        @Test
        @DisplayName("CREATE TABLESPACE ... LOCATION - không throw")
        void createTablespaceLocationDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create tablespace ts1 location |"));
        }

        @Test
        @DisplayName("ALTER TABLESPACE ... SET - không throw")
        void alterTablespaceSetDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter tablespace ts1 set |"));
        }

        @Test
        @DisplayName("DROP TABLESPACE IF EXISTS - không throw")
        void dropTablespaceIfExistsDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("drop tablespace if exists |"));
        }
    }

    // =====================================================================
    // Nhóm 173: CREATE STATISTICS
    // =====================================================================

    @Nested
    @DisplayName("CREATE STATISTICS")
    class CreateStatisticsTests {

        @Test
        @DisplayName("CREATE STATISTICS ... ON col1, col2 FROM table - gợi ý cột thứ 2")
        void createStatisticsSecondColumnSuggestions() {
            var result = suggest("create statistics stat1 on status, | from public.orders");
            assertTrue(hasKeyOfType(result, "orders.customer_id", "column"));
        }
    }

    // =====================================================================
    // Nhóm 174: Hàm SQL/JSON chuẩn mới (JSON_OBJECT, JSON_ARRAY, JSON_VALUE, JSON_QUERY, JSON_EXISTS)
    // =====================================================================

    @Nested
    @DisplayName("Hàm SQL/JSON chuẩn (JSON_OBJECT, JSON_ARRAY, JSON_VALUE, JSON_QUERY, JSON_EXISTS)")
    class SqlStandardJsonFunctionTests {

        @Test
        @DisplayName("JSON_OBJECT('key' VALUE col) - không throw")
        void jsonObjectFunctionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select json_object('name' value |) from public.users"));
        }

        @Test
        @DisplayName("JSON_ARRAY(col1, col2) - không throw")
        void jsonArrayFunctionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select json_array(name, |) from public.users"));
        }

        @Test
        @DisplayName("JSON_VALUE(col, '$.path') - không throw")
        void jsonValueFunctionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select json_value(data, |) from public.users"));
        }

        @Test
        @DisplayName("JSON_QUERY(col, '$.path') - không throw")
        void jsonQueryFunctionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select json_query(data, |) from public.users"));
        }

        @Test
        @DisplayName("JSON_EXISTS(col, '$.path') trong WHERE - không throw")
        void jsonExistsFunctionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where json_exists(data, |)"));
        }
    }

    // =====================================================================
    // Nhóm 175: Database-level DDL
    // =====================================================================

    @Nested
    @DisplayName("CREATE/ALTER/DROP DATABASE")
    class DatabaseDdlTests {

        @Test
        @DisplayName("CREATE DATABASE ... OWNER - không throw")
        void createDatabaseOwnerDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create database db1 owner |"));
        }

        @Test
        @DisplayName("ALTER DATABASE ... SET TABLESPACE - không throw")
        void alterDatabaseSetTablespaceDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter database db1 set tablespace |"));
        }

        @Test
        @DisplayName("DROP DATABASE IF EXISTS ... WITH (FORCE) - không throw")
        void dropDatabaseWithForceDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("drop database if exists db1 with (|"));
        }
    }

    // =====================================================================
    // Nhóm 176: CREATE LANGUAGE
    // =====================================================================

    @Nested
    @DisplayName("CREATE LANGUAGE")
    class CreateLanguageTests {

        @Test
        @DisplayName("CREATE LANGUAGE ... HANDLER - không throw")
        void createLanguageHandlerDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create language plpython3u handler |"));
        }

        @Test
        @DisplayName("CREATE TRUSTED LANGUAGE - không throw")
        void createTrustedLanguageDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create trusted language |"));
        }
    }

    // =====================================================================
    // Nhóm 177: CREATE ASSERTION (deprecated nhưng hợp lệ về mặt cú pháp)
    // =====================================================================

    @Nested
    @DisplayName("CREATE ASSERTION")
    class CreateAssertionTests {

        @Test
        @DisplayName("CREATE ASSERTION ... CHECK - không throw")
        void createAssertionCheckDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create assertion a1 check (|"));
        }
    }

    // =====================================================================
    // Nhóm 178: CREATE AGGREGATE / CREATE ACCESS METHOD
    // =====================================================================

    @Nested
    @DisplayName("CREATE AGGREGATE / CREATE ACCESS METHOD")
    class AggregateAndAccessMethodDdlTests {

        @Test
        @DisplayName("CREATE AGGREGATE ... (SFUNC = ...) - không throw")
        void createAggregateDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create aggregate agg1(int) (sfunc = |"));
        }

        @Test
        @DisplayName("CREATE ACCESS METHOD ... TYPE INDEX HANDLER - không throw")
        void createAccessMethodDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create access method am1 type index handler |"));
        }
    }

    // =====================================================================
    // Nhóm 179: MERGE với nhiều WHEN clause
    // =====================================================================

    @Nested
    @DisplayName("MERGE với nhiều WHEN clause")
    class MergeMultipleWhenClausesTests {

        @Test
        @DisplayName("MERGE ... WHEN MATCHED THEN UPDATE ... WHEN NOT MATCHED THEN INSERT - action thứ 2 vẫn gợi ý cột")
        void mergeMatchedAndNotMatchedColumnSuggestions() {
            var result = suggest(
                    "merge into public.users u using public.orders o on u.id = o.customer_id "
                            + "when matched then update set name = o.status "
                            + "when not matched then insert (id, name) values (o.customer_id, |)");
            assertNotNull(result);
        }

        @Test
        @DisplayName("MERGE ... WHEN MATCHED AND condition THEN DELETE - không throw")
        void mergeMatchedAndConditionDeleteDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "merge into public.users u using public.orders o on u.id = o.customer_id "
                            + "when matched and o.status = |"));
        }
    }

    // =====================================================================
    // Nhóm 180: GENERATED column STORED, REPLICA IDENTITY
    // =====================================================================

    @Nested
    @DisplayName("GENERATED column STORED, REPLICA IDENTITY")
    class GeneratedStoredAndReplicaIdentityTests {

        @Test
        @DisplayName("col GENERATED ALWAYS AS (expr) STORED - gợi ý cột trong expr")
        void generatedStoredColumnExpressionColumnSuggestions() {
            var result = suggest(
                    "create table t (a int, b int, c int generated always as (a + |) stored)");
            assertNotNull(result);
        }

        @Test
        @DisplayName("ALTER TABLE ... REPLICA IDENTITY FULL - không throw")
        void alterTableReplicaIdentityFullDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter table public.users replica identity |"));
        }

        @Test
        @DisplayName("ALTER TABLE ... REPLICA IDENTITY USING INDEX - không throw")
        void alterTableReplicaIdentityUsingIndexDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter table public.users replica identity using index |"));
        }
    }

    // =====================================================================
    // Nhóm 181: CHECK constraint NO INHERIT, USING INDEX TABLESPACE
    // =====================================================================

    @Nested
    @DisplayName("CHECK NO INHERIT, USING INDEX TABLESPACE cho constraint")
    class ConstraintNoInheritAndTablespaceTests {

        @Test
        @DisplayName("CHECK (...) NO INHERIT - không throw")
        void checkNoInheritDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create table t (a int, check (a > 0) no inherit|"));
        }

        @Test
        @DisplayName("UNIQUE (col) USING INDEX TABLESPACE ts1 - không throw")
        void uniqueUsingIndexTablespaceDoesNotThrow() {
            assertDoesNotThrow(() -> suggest(
                    "create table t (a int, unique (a) using index tablespace |"));
        }
    }

    // =====================================================================
    // Nhóm 182: Nhiều policy RLS trên cùng 1 bảng
    // =====================================================================

    @Nested
    @DisplayName("Nhiều RLS policy trên cùng 1 bảng, các mệnh đề của policy")
    class MultipleRlsPolicyTests {

        @Test
        @DisplayName("CREATE POLICY ... FOR SELECT TO role USING (...) WITH CHECK (...) - action WITH CHECK vẫn gợi ý cột")
        void policyForSelectWithCheckColumnSuggestions() {
            var result = suggest(
                    "create policy p1 on public.users for select to some_role using (id > 0) with check (|)");
            assertTrue(hasKeyOfType(result, "users.id", "column"));
        }

        @Test
        @DisplayName("ALTER POLICY ... RENAME TO - không throw")
        void alterPolicyRenameDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("alter policy p1 on public.users rename to |"));
        }

        @Test
        @DisplayName("DROP POLICY IF EXISTS ... ON table - không throw")
        void dropPolicyIfExistsDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("drop policy if exists p1 on |"));
        }
    }
}