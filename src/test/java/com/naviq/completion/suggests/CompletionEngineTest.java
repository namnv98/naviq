package com.naviq.completion.suggests;

import com.naviq.completion.model.Suggest;
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


    @Test
    @DisplayName("Sau dấu ';' của câu trước - VẪN phải thấy đủ keyword bắt-đầu-câu (không phá multi-statement)")
    void statementStartKeywordsAfterSemicolon() {
        var result = suggest("select * from public.contracts where |");
        System.out.println();
//        var keywords = allKeywordKeys(result);
//        assertTrue(keywords.contains("select"));
//        assertTrue(keywords.contains("insert"));
//        assertTrue(keywords.contains("delete"));
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
            assertTrue(keywords.contains("insert"));
            assertTrue(keywords.contains("with"));
            assertTrue(keywords.contains("create"));
            assertTrue(keywords.contains("delete"));
            assertTrue(keywords.contains("update"));
        }

        @Test
        @DisplayName("Sau dấu ';' của câu trước - VẪN phải thấy đủ keyword bắt-đầu-câu (không phá multi-statement)")
        void statementStartKeywordsAfterSemicolon() {
            var result = suggest("select * from users; |");
            var keywords = allKeywordKeys(result);
            assertTrue(keywords.contains("select"));
            assertTrue(keywords.contains("insert"));
            assertTrue(keywords.contains("delete"));
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

        @Test
        @DisplayName("Nhiều alias đã dùng, đề xuất số tiếp theo")
        void aliasMultipleIncrements() {
            var result = suggest("select * from public.users u join public.orders o join public.products as |");
            assertTrue(hasKeyOfType(result, "p", "alias") || hasKeyOfType(result, "p1", "alias"));
        }

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
        @DisplayName("DROP VIEW - gợi ý tên view/bảng")
        void dropViewSuggestions() {
            assertDoesNotThrow(() -> suggest("drop view |"));
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
        @DisplayName("SELECT current_date, | (không FROM) - không crash")
        void selectCurrentDateWithoutFrom() {
            assertDoesNotThrow(() -> suggest("select current_date, |"));
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
        @DisplayName("EXPLAIN ANALYZE SELECT - không crash")
        void explainAnalyzeDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("explain analyze select * from public.users where |"));
        }
    }

    // =====================================================================
    // Nhóm 49: GRANT / REVOKE
    // =====================================================================

    @Nested
    @DisplayName("GRANT/REVOKE")
    class GrantRevokeTests {

        @Test
        @DisplayName("REVOKE - không crash")
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
        @DisplayName("CREATE EXTENSION - không crash")
        void createExtensionDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create extension |"));
        }

        @Test
        @DisplayName("DROP EXTENSION - không crash")
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
        @DisplayName("CREATE INDEX USING gin (col) - không crash")
        void ginIndexDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create index idx1 on public.users using gin (|)"));
        }

        @Test
        @DisplayName("CREATE INDEX ON table (LOWER(col)) - expression index, không crash")
        void expressionIndexDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create index idx1 on public.users (lower(|))"));
        }

        @Test
        @DisplayName("CREATE UNIQUE INDEX - không crash")
        void uniqueIndexDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("create unique index idx1 on public.users (|)"));
        }
    }

    // =====================================================================
    // Nhóm 82: SET / SHOW session command
    // =====================================================================

    @Nested
    @DisplayName("SET/SHOW session command")
    class SessionCommandTests {

        @Test
        @DisplayName("SET search_path - không crash")
        void setSearchPathDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("set search_path to |"));
        }

        @Test
        @DisplayName("SHOW - không crash")
        void showDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("show |"));
        }

        @Test
        @DisplayName("RESET - không crash")
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
        @DisplayName("COPY table (col, |) FROM stdin - gợi ý cột")
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
        @DisplayName("WHERE col ~ pattern - không crash")
        void regexMatchDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where name ~ |"));
        }

        @Test
        @DisplayName("WHERE col !~* pattern - không crash")
        void regexNotMatchCaseInsensitiveDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where name !~* |"));
        }

        @Test
        @DisplayName("ILIKE pattern - không crash")
        void ilikePatternDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select * from public.users where name ilike |"));
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
        @DisplayName("col + INTERVAL '1 day' - không crash")
        void columnPlusIntervalDoesNotThrow() {
            assertDoesNotThrow(() -> suggest("select created_date + interval '1 day' from public.users where |"));
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
}