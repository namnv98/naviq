//package com.naviq.completion.suggests;
//
//import com.naviq.completion.model.Suggest;
//import com.naviq.datasource.SchemaIndex;
//import com.naviq.datasource.SchemaLoader;
//import org.junit.jupiter.api.BeforeAll;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Nested;
//import org.junit.jupiter.api.Test;
//
//import java.util.List;
//import java.util.Map;
//import java.util.Set;
//import java.util.stream.Collectors;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * Test cho CompletionEngine - tầng orchestrator, tích hợp CẢ SemanticAnalyzer +
// * SyntacticAnalyzer + SchemaIndex thật (không mock riêng lẻ từng tầng), để test đúng
// * hành vi END-TO-END mà người dùng thực sự thấy trên menu completion.
// * <p>
// * QUAN TRỌNG: SchemaIndex có static initializer tự gọi reload() (kết nối DB thật qua
// * PostgresDataSource) - nếu môi trường test KHÔNG có DB, static init sẽ throw và
// * ExceptionInInitializerError "dính" cho cả JVM đang chạy test. Bản SchemaIndex đã sửa
// * (xem SchemaIndex.java) bắt exception đó, để field mặc định RỖNG thay vì crash - nhờ
// * vậy @BeforeAll bên dưới có thể an toàn override field bằng fixture, không cần DB.
// */
//class CompletionEngineTest {
//
//    @BeforeAll
//    static void setUpFixtureSchema() {
//        var idCol = new SchemaLoader.DBColumnInfo("id", "id", "int4", true);
//        var nameCol = new SchemaLoader.DBColumnInfo("name", "name", "text", false);
//        var emailCol = new SchemaLoader.DBColumnInfo("email", "email", "text", false);
//        var customerIdCol = new SchemaLoader.DBColumnInfo("customer_id", "customer_id", "int4", false);
//        var totalCol = new SchemaLoader.DBColumnInfo("total", "total", "numeric", false);
//        var amountCol = new SchemaLoader.DBColumnInfo("amount", "amount", "numeric", false);
//        var statusCol = new SchemaLoader.DBColumnInfo("status", "status", "text", false);
//        var createdDateCol = new SchemaLoader.DBColumnInfo("created_date", "created_date", "timestamp", false);
//        var userIdCol = new SchemaLoader.DBColumnInfo("user_id", "user_id", "int4", false);
//        var orderIdCol = new SchemaLoader.DBColumnInfo("order_id", "order_id", "int4", false);
//        var productIdCol = new SchemaLoader.DBColumnInfo("product_id", "product_id", "int4", false);
//        var priceCol = new SchemaLoader.DBColumnInfo("price", "price", "numeric", false);
//        var quantityCol = new SchemaLoader.DBColumnInfo("quantity", "quantity", "int4", false);
//        var descriptionCol = new SchemaLoader.DBColumnInfo("description", "description", "text", false);
//
//        var users = new SchemaLoader.TableInfo("public", "users", "table",
//                List.of(idCol, nameCol, emailCol, createdDateCol));
//        var orders = new SchemaLoader.TableInfo("public", "orders", "table",
//                List.of(idCol, customerIdCol, totalCol, statusCol, userIdCol));
//        var contracts = new SchemaLoader.TableInfo("public", "contracts", "table",
//                List.of(idCol, nameCol, amountCol, statusCol));
//        var ordersView = new SchemaLoader.TableInfo("public", "orders_summary", "view",
//                List.of(idCol, totalCol, orderIdCol, productIdCol, priceCol, quantityCol, descriptionCol));
//        var products = new SchemaLoader.TableInfo("public", "products", "table",
//                List.of(idCol, nameCol, priceCol, quantityCol, descriptionCol));
//
//        var publicSchema = new SchemaLoader.SchemaInfo("public",
//                List.of(users, orders, contracts, ordersView, products));
//
//        SchemaIndex.DB_SCHEMA = List.of(publicSchema);
//        SchemaIndex.TABLE_INDEX = Map.of(
//                "public.users", users, "users", users,
//                "public.orders", orders, "orders", orders,
//                "public.contracts", contracts, "contracts", contracts,
//                "public.orders_summary", ordersView, "orders_summary", ordersView,
//                "public.products", products, "products", products
//        );
//        SchemaIndex.SCHEMA_TABLE_INDEX = Map.of(
//                "public.users", users,
//                "public.orders", orders,
//                "public.contracts", contracts,
//                "public.orders_summary", ordersView,
//                "public.products", products
//        );
//        SchemaIndex.FUNCTIONS = List.of("count", "sum", "avg", "now", "min", "max", "concat", "lower", "upper", "trim");
//        SchemaIndex.DATA_TYPES = List.of("int4", "text", "numeric", "bool", "timestamp", "date", "time", "varchar");
//    }
//
//    // =====================================================================
//    // Helper
//    // =====================================================================
//
//    private static List<Suggest> suggest(String rawWithCursor) {
//        int cursor = rawWithCursor.indexOf('|');
//        String sql = rawWithCursor.substring(0, cursor) + rawWithCursor.substring(cursor + 1);
//        return CompletionEngine.suggests(sql, cursor);
//    }
//
//    private static boolean hasKey(List<Suggest> list, String key) {
//        return list.stream().anyMatch(s -> s.getKey().equalsIgnoreCase(key));
//    }
//
//    private static boolean hasKeyOfType(List<Suggest> list, String key, String type) {
//        return list.stream().anyMatch(s -> s.getKey().equalsIgnoreCase(key) && s.getType().equals(type));
//    }
//
//    private static List<String> keysOfType(List<Suggest> list, String type) {
//        return list.stream().filter(s -> s.getType().equals(type)).map(Suggest::getKey).toList();
//    }
//
//    private static Set<String> allKeywordKeys(List<Suggest> list) {
//        return list.stream().filter(s -> s.getType().equals("keyword"))
//                .map(s -> s.getKey().toLowerCase()).collect(Collectors.toSet());
//    }
//
//    // =====================================================================
//    // Nhóm 1: bug "select sau select" (fix stmtmulti trong .g4 + isFreshStatementPosition)
//    // =====================================================================
//
//    @Nested
//    @DisplayName("Bug đã fix: keyword bắt-đầu-câu không lặp lại giữa câu")
//    class StatementStartKeywordDedup {
//
//        @Test
//        @DisplayName("'select |' KHÔNG còn gợi ý lại 'select'/'insert'/'with'/'create' (đã gõ dở SELECT, chưa xong)")
//        void noStatementStartKeywordsMidSelect() {
//            var result = suggest("select |");
//            var keywords = allKeywordKeys(result);
//            assertFalse(keywords.contains("select"), "Không nên gợi ý lại 'select'");
//            assertFalse(keywords.contains("insert"), "Không nên gợi ý 'insert' giữa câu SELECT");
//            assertFalse(keywords.contains("with"), "Không nên gợi ý 'with' giữa câu SELECT");
//            assertFalse(keywords.contains("create"), "Không nên gợi ý 'create' giữa câu SELECT");
//        }
//
//        @Test
//        @DisplayName("Đầu file (chưa gõ gì) - VẪN phải thấy đủ keyword bắt-đầu-câu")
//        void statementStartKeywordsAtVeryBeginning() {
//            var result = suggest("|");
//            var keywords = allKeywordKeys(result);
//            assertTrue(keywords.contains("select"));
//            assertTrue(keywords.contains("insert"));
//            assertTrue(keywords.contains("with"));
//            assertTrue(keywords.contains("create"));
//            assertTrue(keywords.contains("delete"));
//            assertTrue(keywords.contains("update"));
//        }
//
//        @Test
//        @DisplayName("Sau dấu ';' của câu trước - VẪN phải thấy đủ keyword bắt-đầu-câu (không phá multi-statement)")
//        void statementStartKeywordsAfterSemicolon() {
//            var result = suggest("select * from users; |");
//            var keywords = allKeywordKeys(result);
//            assertTrue(keywords.contains("select"), "Sau ';' phải cho phép bắt đầu câu mới");
//            assertTrue(keywords.contains("insert"));
//            assertTrue(keywords.contains("delete"));
//        }
//
//        @Test
//        @DisplayName("Trong stored procedure, sau BEGIN - vẫn gợi ý SELECT, INSERT, UPDATE")
//        void statementStartKeywordsAfterBegin() {
//            var result = suggest("create procedure test() language plpgsql as $$ begin |");
//            var keywords = allKeywordKeys(result);
//            assertTrue(keywords.contains("select"));
//            assertTrue(keywords.contains("insert"));
//            assertTrue(keywords.contains("update"));
//            assertTrue(keywords.contains("delete"));
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 2: bug "JOIN thiếu ON làm mất alias" (fix join_qual? trong .g4)
//    // =====================================================================
//
//    @Nested
//    @DisplayName("Bug đã fix: JOIN chưa gõ ON không làm mất alias của bảng trước")
//    class JoinMissingOnClause {
//
//        @Test
//        @DisplayName("Self-join, vế 2 chưa gõ AS xong - alias vế 1 ('c') vẫn phải gợi ý được cột")
//        void firstJoinAliasStillWorksWhileSecondIncomplete() {
//            var result = suggest("select c.| from public.contracts as c join public.contracts as x");
//            assertTrue(hasKeyOfType(result, "c.id", "column"),
//                    "Alias 'c' của bảng ĐẦU phải resolve được cột dù vế JOIN sau chưa có ON");
//            assertTrue(hasKeyOfType(result, "c.name", "column"));
//            assertTrue(hasKeyOfType(result, "c.amount", "column"));
//        }
//
//        @Test
//        @DisplayName("JOIN...AS <đang gõ dở, chưa xong> - vẫn gợi ý alias tự động cho vế đang gõ")
//        void aliasSuggestionWorksRightAfterAsInIncompleteJoin() {
//            var result = suggest(
//                    "select * from public.contracts as c join public.contracts as |");
//            assertTrue(hasKeyOfType(result, "c1", "alias"),
//                    "Vì 'c' đã dùng, tự đề xuất phải là 'c1' (cộng số) - đồng thời xác nhận "
//                            + "KHÔNG bị mất do JOIN thiếu ON (bug đã fix)");
//        }
//
//        @Test
//        @DisplayName("LEFT JOIN không có ON - alias của bảng chính vẫn đúng")
//        void leftJoinWithoutOnStillResolvesMainAlias() {
//            var result = suggest("select u.| from public.users u left join public.orders o");
//            assertTrue(hasKeyOfType(result, "u.id", "column"));
//            assertTrue(hasKeyOfType(result, "u.name", "column"));
//            assertTrue(hasKeyOfType(result, "u.email", "column"));
//        }
//
//        @Test
//        @DisplayName("Nhiều JOIN liên tiếp không ON - alias vẫn resolve")
//        void multipleJoinsWithoutOn() {
//            var result = suggest("select u.| from public.users u join public.orders o join public.products p");
//            assertTrue(hasKeyOfType(result, "u.id", "column"));
//            assertTrue(hasKeyOfType(result, "u.name", "column"));
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 3: bug "column" -> phải là "alias" (type Suggest đúng)
//    // =====================================================================
//
//    @Nested
//    @DisplayName("Bug đã fix: gợi ý alias tự động phải có type \"alias\", không phải \"column\"")
//    class AliasSuggestionType {
//
//        @Test
//        @DisplayName("Gợi ý alias sau AS phải gắn type \"alias\"")
//        void suggestedAliasHasAliasType() {
//            var result = suggest("select * from public.users as |");
//            var aliasSuggests = result.stream().filter(s -> s.getKey().equals("u")).toList();
//            assertFalse(aliasSuggests.isEmpty(), "Phải có gợi ý alias 'u' cho bảng users");
//            assertEquals("alias", aliasSuggests.get(0).getType());
//        }
//
//        @Test
//        @DisplayName("Alias tự động tăng số - type cũng là 'alias'")
//        void autoIncrementedAliasHasAliasType() {
//            var result = suggest("select * from public.users u join public.orders as |");
//            var aliasSuggests = result.stream().filter(s -> s.getKey().equals("o")).toList();
//            assertFalse(aliasSuggests.isEmpty());
//            assertEquals("alias", aliasSuggests.get(0).getType());
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 4: bug noise "insert/at/by/do/is/no/of..." (IDENTIFIER_USABLE_KEYWORDS)
//    // =====================================================================
//
//    @Nested
//    @DisplayName("Bug đã fix: ẩn keyword-dùng-được-làm-identifier khi đã có cột/alias thật")
//    class IdentifierUsableKeywordNoise {
//
//        @Test
//        @DisplayName("'select |' (đã có cột thật từ users) - KHÔNG còn 'insert'/'at'/'by'/'do' trong keyword")
//        void noIdentifierNoiseWhenRealColumnsExist() {
//            var result = suggest("select * from public.users where |");
//            var keywords = allKeywordKeys(result);
//            assertFalse(keywords.contains("insert"));
//            assertFalse(keywords.contains("at"));
//            assertFalse(keywords.contains("by"));
//            assertFalse(keywords.contains("do"));
//            assertFalse(keywords.contains("truncate"));
//        }
//
//        @Test
//        @DisplayName("'... AS |' - cũng phải ẩn noise này (table_alias cùng bị nhiễu)")
//        void noIdentifierNoiseAtTableAliasPosition() {
//            var result = suggest("select * from public.contracts as c join public.contracts as |");
//            var keywords = allKeywordKeys(result);
//            assertFalse(keywords.contains("insert"));
//            assertFalse(keywords.contains("at"));
//            assertFalse(keywords.contains("do"));
//            assertFalse(keywords.contains("of"));
//        }
//
//        @Test
//        @DisplayName("'WHERE a = 1 |' (columnref KHÔNG active nữa, đang chờ AND/OR) - 'and'/'or' KHÔNG bị ẩn")
//        void andOrNotHiddenWhenContinuingBooleanExpression() {
//            var result = suggest("select * from public.users where id = 1 |");
//            var keywords = allKeywordKeys(result);
//            assertTrue(keywords.contains("and"), "AND vẫn phải gợi ý được để nối điều kiện tiếp");
//            assertTrue(keywords.contains("or"));
//        }
//
//        @Test
//        @DisplayName("Sau FROM mới, chưa có alias - vẫn ẩn noise keyword")
//        void noNoiseAfterFrom() {
//            var result = suggest("select * from |");
//            var keywords = allKeywordKeys(result);
//            assertFalse(keywords.contains("insert"));
//            assertFalse(keywords.contains("at"));
//            assertFalse(keywords.contains("by"));
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 5: cursorOffset fix (không còn "- 1") - schema.table detection
//    // =====================================================================
//
//    @Nested
//    @DisplayName("Bug đã fix: cursorOffset không lệch, dò đúng schema.table")
//    class SchemaQualifiedTableDetection {
//
//        @Test
//        @DisplayName("'from public.|' - gợi ý đúng bảng trong schema public")
//        void schemaQualifiedTableSuggestion() {
//            var result = suggest("select * from public.|");
//            var tables = keysOfType(result, "table");
//            assertTrue(tables.contains("public.users"));
//            assertTrue(tables.contains("public.orders"));
//            assertTrue(tables.contains("public.contracts"));
//            assertTrue(tables.contains("public.products"));
//        }
//
//        @Test
//        @DisplayName("'from public.u|' - gợi ý bảng bắt đầu bằng 'u'")
//        void tableSuggestionWithPrefix() {
//            var result = suggest("select * from public.use|");
//            var tables = keysOfType(result, "table");
//            assertTrue(tables.contains("public.users"));
//            assertFalse(tables.contains("public.orders"));
//        }
//
//        @Test
//        @DisplayName("'from |' - gợi ý bảng không cần schema prefix")
//        void tableSuggestionWithoutSchema() {
//            var result = suggest("select * from |");
//            var tables = keysOfType(result, "table");
//            assertTrue(tables.contains("public.users"));
//            assertTrue(tables.contains("orders"));
//            assertTrue(tables.contains("products"));
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 6: cột/hàm/kiểu dữ liệu cơ bản
//    // =====================================================================
//
//    @Nested
//    @DisplayName("Hồi quy chung: cột/hàm/kiểu dữ liệu cơ bản")
//    class BasicColumnFunctionTypeSuggestions {
//
//        @Test
//        @DisplayName("'select u.| from users u' - gợi ý đúng cột của bảng users")
//        void columnSuggestionsForAlias() {
//            var result = suggest("select u.| from public.users u");
//            assertTrue(hasKeyOfType(result, "u.id", "column"));
//            assertTrue(hasKeyOfType(result, "u.name", "column"));
//            assertTrue(hasKeyOfType(result, "u.email", "column"));
//            assertTrue(hasKeyOfType(result, "u.created_date", "column"));
//        }
//
//        @Test
//        @DisplayName("'select | from users' - gợi ý hàm (count/sum/avg) cùng với cột")
//        void functionSuggestionsAlwaysIncluded() {
//            var result = suggest("select | from public.users");
//            assertTrue(hasKeyOfType(result, "count", "function"));
//            assertTrue(hasKeyOfType(result, "sum", "function"));
//            assertTrue(hasKeyOfType(result, "avg", "function"));
//            assertTrue(hasKeyOfType(result, "min", "function"));
//            assertTrue(hasKeyOfType(result, "max", "function"));
//        }
//
//        @Test
//        @DisplayName("'create table t (id |' - gợi ý kiểu dữ liệu")
//        void dataTypeSuggestions() {
//            var result = suggest("create table t (id |");
//            assertTrue(hasKeyOfType(result, "text", "datatype"));
//            assertTrue(hasKeyOfType(result, "numeric", "datatype"));
//            assertTrue(hasKeyOfType(result, "int4", "datatype"));
//            assertTrue(hasKeyOfType(result, "bool", "datatype"));
//            assertTrue(hasKeyOfType(result, "timestamp", "datatype"));
//        }
//
//        @Test
//        @DisplayName("'create table t (id varchar(|' - gợi ý length cho varchar")
//        void varcharLengthSuggestion() {
//            var result = suggest("create table t (id varchar(|");
//            // Có thể gợi ý số length thường dùng
//            assertTrue(hasKey(result, "255") || hasKey(result, "50") || hasKey(result, "100"));
//        }
//
//        @Test
//        @DisplayName("alias lạ ('x.' - chưa từng khai báo) - vẫn thử tra cột thẳng theo tên, không throw")
//        void unknownAliasFallsBackGracefully() {
//            assertDoesNotThrow(() -> suggest("select x.| from public.users u"));
//        }
//
//        @Test
//        @DisplayName("Khi gõ cột không có alias - gợi ý column từ tất cả bảng trong FROM")
//        void columnsFromAllTablesWithoutAlias() {
//            var result = suggest("select | from public.users u join public.orders o");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("id") || columns.contains("u.id") || columns.contains("o.id"));
//            assertTrue(columns.contains("name") || columns.contains("u.name"));
//            assertTrue(columns.contains("total") || columns.contains("o.total"));
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 7: xếp hạng (order/type) - hồi quy cho phần orderOf trong Suggest
//    // =====================================================================
//
//    @Nested
//    @DisplayName("Hồi quy: field order đúng theo type")
//    class SuggestOrderRegression {
//
//        @Test
//        @DisplayName("Suggest type \"alias\" phải có order=1 (ưu tiên cao nhất)")
//        void aliasOrderIsHighestPriority() {
//            var result = suggest("select * from public.users as |");
//            var alias = result.stream().filter(s -> s.getType().equals("alias")).findFirst();
//            assertTrue(alias.isPresent());
//            assertEquals(1, alias.get().getOrder());
//        }
//
//        @Test
//        @DisplayName("Suggest type \"column\" phải có order=2")
//        void columnOrderIsTwo() {
//            var result = suggest("select u.| from public.users u");
//            var column = result.stream().filter(s -> s.getType().equals("column")).findFirst();
//            assertTrue(column.isPresent());
//            assertEquals(2, column.get().getOrder());
//        }
//
//        @Test
//        @DisplayName("Suggest type \"function\" phải có order=3")
//        void functionOrderIsThree() {
//            var result = suggest("select | from public.users");
//            var func = result.stream().filter(s -> s.getType().equals("function")).findFirst();
//            assertTrue(func.isPresent());
//            assertEquals(3, func.get().getOrder());
//        }
//
//        @Test
//        @DisplayName("Suggest type \"keyword\" phải có order=4")
//        void keywordOrderIsFour() {
//            var result = suggest("|");
//            var kw = result.stream().filter(s -> s.getType().equals("keyword")).findFirst();
//            assertTrue(kw.isPresent());
//            assertEquals(4, kw.get().getOrder());
//        }
//
//        @Test
//        @DisplayName("Suggest type \"datatype\" phải có order=5")
//        void dataTypeOrderIsFive() {
//            var result = suggest("create table t (id |");
//            var dt = result.stream().filter(s -> s.getType().equals("datatype")).findFirst();
//            assertTrue(dt.isPresent());
//            assertEquals(5, dt.get().getOrder());
//        }
//
//        @Test
//        @DisplayName("Suggest type \"table\" phải có order=6")
//        void tableOrderIsSix() {
//            var result = suggest("select * from |");
//            var table = result.stream().filter(s -> s.getType().equals("table")).findFirst();
//            assertTrue(table.isPresent());
//            assertEquals(6, table.get().getOrder());
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 8: Test các câu lệnh DML khác
//    // =====================================================================
//
//    @Nested
//    @DisplayName("Test các câu lệnh DML: INSERT, UPDATE, DELETE")
//    class DMLStatements {
//
//        @Test
//        @DisplayName("INSERT INTO - gợi ý bảng")
//        void insertIntoSuggestions() {
//            var result = suggest("insert into |");
//            var tables = keysOfType(result, "table");
//            assertTrue(tables.contains("public.users"));
//            assertTrue(tables.contains("public.orders"));
//        }
//
//        @Test
//        @DisplayName("INSERT INTO users - gợi ý cột để insert")
//        void insertColumnSuggestions() {
//            var result = suggest("insert into public.users (|");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("id"));
//            assertTrue(columns.contains("name"));
//            assertTrue(columns.contains("email"));
//        }
//
//        @Test
//        @DisplayName("UPDATE - gợi ý bảng")
//        void updateTableSuggestions() {
//            var result = suggest("update |");
//            var tables = keysOfType(result, "table");
//            assertTrue(tables.contains("public.users"));
//            assertTrue(tables.contains("public.products"));
//        }
//
//        @Test
//        @DisplayName("UPDATE users SET - gợi ý cột")
//        void updateSetColumnSuggestions() {
//            var result = suggest("update public.users set |");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("name"));
//            assertTrue(columns.contains("email"));
//            assertTrue(columns.contains("created_date"));
//        }
//
//        @Test
//        @DisplayName("DELETE FROM - gợi ý bảng")
//        void deleteFromSuggestions() {
//            var result = suggest("delete from |");
//            var tables = keysOfType(result, "table");
//            assertTrue(tables.contains("public.users"));
//            assertTrue(tables.contains("public.orders"));
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 9: Test WHERE clause với multiple conditions
//    // =====================================================================
//
//    @Nested
//    @DisplayName("WHERE clause với nhiều điều kiện")
//    class WhereClauseComplex {
//
//        @Test
//        @DisplayName("WHERE - gợi ý cột")
//        void whereColumnSuggestions() {
//            var result = suggest("select * from public.users where |");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("id"));
//            assertTrue(columns.contains("name"));
//            assertTrue(columns.contains("email"));
//        }
//
//        @Test
//        @DisplayName("WHERE u. - gợi ý cột với alias")
//        void whereAliasColumnSuggestions() {
//            var result = suggest("select * from public.users u where u.|");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("u.id"));
//            assertTrue(columns.contains("u.name"));
//            assertTrue(columns.contains("u.email"));
//        }
//
//        @Test
//        @DisplayName("WHERE id = 1 AND | - gợi ý cột tiếp theo")
//        void whereAndContinuation() {
//            var result = suggest("select * from public.users where id = 1 and |");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("name"));
//            assertTrue(columns.contains("email"));
//        }
//
//        @Test
//        @DisplayName("WHERE id = 1 AND name LIKE | - gợi ý giá trị string")
//        void whereLikeValueSuggestion() {
//            var result = suggest("select * from public.users where id = 1 and name like |");
//            // Có thể gợi ý '%' pattern
//            assertTrue(hasKey(result, "'%'") || hasKey(result, "'%'"));
//        }
//
//        @Test
//        @DisplayName("WHERE id IN (| - gợi ý subquery hoặc values")
//        void whereInSubquerySuggestion() {
//            var result = suggest("select * from public.users where id in (|");
//            // Gợi ý các hàm hoặc cột phù hợp
//            assertNotNull(result);
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 10: Test ORDER BY, GROUP BY, HAVING
//    // =====================================================================
//
//    @Nested
//    @DisplayName("ORDER BY, GROUP BY, HAVING")
//    class OrderGroupHaving {
//
//        @Test
//        @DisplayName("ORDER BY - gợi ý cột")
//        void orderByColumnSuggestions() {
//            var result = suggest("select * from public.users order by |");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("id"));
//            assertTrue(columns.contains("name"));
//            assertTrue(columns.contains("email"));
//        }
//
//        @Test
//        @DisplayName("ORDER BY u. - gợi ý cột với alias")
//        void orderByAliasColumnSuggestions() {
//            var result = suggest("select * from public.users u order by u.|");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("u.id"));
//            assertTrue(columns.contains("u.name"));
//        }
//
//        @Test
//        @DisplayName("ORDER BY id | - gợi ý ASC/DESC")
//        void orderByAscDescSuggestions() {
//            var result = suggest("select * from public.users order by id |");
//            var keywords = allKeywordKeys(result);
//            assertTrue(keywords.contains("asc"));
//            assertTrue(keywords.contains("desc"));
//        }
//
//        @Test
//        @DisplayName("GROUP BY - gợi ý cột")
//        void groupByColumnSuggestions() {
//            var result = suggest("select count(*), status from public.orders group by |");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("status"));
//            assertTrue(columns.contains("customer_id"));
//        }
//
//        @Test
//        @DisplayName("HAVING - gợi ý cột aggregate")
//        void havingColumnSuggestions() {
//            var result = suggest("select count(*), status from public.orders group by status having |");
//            var columns = keysOfType(result, "column");
//            // HAVING thường dùng với aggregate functions hoặc group by columns
//            assertTrue(columns.contains("status"));
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 11: Test subquery
//    // =====================================================================
//
//    @Nested
//    @DisplayName("Subquery")
//    class SubqueryTests {
//
//        @Test
//        @DisplayName("Subquery trong WHERE - gợi ý bảng")
//        void subqueryTableSuggestions() {
//            var result = suggest("select * from public.users where id in (select | from public.orders)");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("customer_id") || columns.contains("id"));
//        }
//
//        @Test
//        @DisplayName("Subquery trong FROM - gợi ý bảng")
//        void subqueryFromSuggestions() {
//            var result = suggest("select * from (select | from public.users) sub");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("id"));
//            assertTrue(columns.contains("name"));
//        }
//
//        @Test
//        @DisplayName("Subquery với alias - gợi ý cột của subquery")
//        void subqueryAliasColumnSuggestions() {
//            var result = suggest("select sub.| from (select * from public.users) sub");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("sub.id"));
//            assertTrue(columns.contains("sub.name"));
//            assertTrue(columns.contains("sub.email"));
//        }
//
//        @Test
//        @DisplayName("Subquery với EXISTS - gợi ý bảng")
//        void existsSubquerySuggestions() {
//            var result = suggest("select * from public.users u where exists (select 1 from |");
//            var tables = keysOfType(result, "table");
//            assertTrue(tables.contains("public.orders"));
//            assertTrue(tables.contains("public.products"));
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 12: Test JOIN với nhiều điều kiện
//    // =====================================================================
//
//    @Nested
//    @DisplayName("JOIN với nhiều điều kiện")
//    class JoinWithConditions {
//
//        @Test
//        @DisplayName("JOIN ... ON | - gợi ý cột của cả hai bảng")
//        void joinOnColumnSuggestions() {
//            var result = suggest("select * from public.users u join public.orders o on |");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("u.id") || columns.contains("id"));
//            assertTrue(columns.contains("o.customer_id") || columns.contains("customer_id"));
//        }
//
//        @Test
//        @DisplayName("JOIN với AND - gợi ý cột tiếp theo")
//        void joinOnAndContinuation() {
//            var result = suggest("select * from public.users u join public.orders o on u.id = o.customer_id and |");
//            var columns = keysOfType(result, "column");
//            // Có thể gợi ý thêm cột từ cả hai bảng
//            assertNotNull(columns);
//        }
//
//        @Test
//        @DisplayName("LEFT JOIN với alias - gợi ý cột của bảng mới")
//        void leftJoinAliasColumnSuggestions() {
//            var result = suggest("select * from public.users u left join public.orders o on u.id = o.customer_id where o.|");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("o.id"));
//            assertTrue(columns.contains("o.total"));
//            assertTrue(columns.contains("o.status"));
//        }
//
//        @Test
//        @DisplayName("Nhiều JOIN với ON đầy đủ - resolve đúng tất cả alias")
//        void multipleJoinsWithFullOn() {
//            var result = suggest("select u.| from public.users u join public.orders o on u.id = o.user_id join public.products p on o.product_id = p.id");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("u.id"));
//            assertTrue(columns.contains("u.name"));
//            assertTrue(columns.contains("u.email"));
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 13: Test với function và expression
//    // =====================================================================
//
//    @Nested
//    @DisplayName("Function và Expression")
//    class FunctionsAndExpressions {
//
//        @Test
//        @DisplayName("COUNT(| - gợi ý cột")
//        void countFunctionColumnSuggestions() {
//            var result = suggest("select count(| from public.users");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("id"));
//            assertTrue(columns.contains("name"));
//        }
//
//        @Test
//        @DisplayName("COUNT(DISTINCT | - gợi ý cột")
//        void countDistinctColumnSuggestions() {
//            var result = suggest("select count(distinct | from public.users");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("id"));
//            assertTrue(columns.contains("email"));
//        }
//
//        @Test
//        @DisplayName("CASE WHEN | - gợi ý cột")
//        void caseWhenColumnSuggestions() {
//            var result = suggest("select case when | then 1 else 0 end from public.users");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("id"));
//            assertTrue(columns.contains("status"));
//        }
//
//        @Test
//        @DisplayName("COALESCE(| - gợi ý cột")
//        void coalesceColumnSuggestions() {
//            var result = suggest("select coalesce(| from public.users");
//            var columns = keysOfType(result, "column");
//            assertTrue(columns.contains("name"));
//            assertTrue(columns.contains("email"));
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 14: Test DDL statements
//    // =====================================================================
//
//    @Nested
//    @DisplayName("DDL Statements: CREATE, ALTER, DROP")
//    class DDLStatements {
//
//        @Test
//        @DisplayName("CREATE TABLE - gợi ý tên bảng và kiểu dữ liệu")
//        void createTableSuggestions() {
//            var result = suggest("create table |");
//            var keywords = allKeywordKeys(result);
//            // Không có gợi ý đặc biệt cho create table, chỉ check không crash
//            assertNotNull(result);
//        }
//
//        @Test
//        @DisplayName("CREATE TABLE t (id | - gợi ý kiểu dữ liệu")
//        void createTableDataTypeSuggestions() {
//            var result = suggest("create table test (id |");
//            var datatypes = keysOfType(result, "datatype");
//            assertTrue(datatypes.contains("int4"));
//            assertTrue(datatypes.contains("text"));
//            assertTrue(datatypes.contains("numeric"));
//        }
//
//        @Test
//        @DisplayName("ALTER TABLE - gợi ý bảng")
//        void alterTableSuggestions() {
//            var result = suggest("alter table |");
//            var tables = keysOfType(result, "table");
//            assertTrue(tables.contains("public.users"));
//            assertTrue(tables.contains("public.orders"));
//        }
//
//        @Test
//        @DisplayName("ALTER TABLE users ADD COLUMN | - gợi ý kiểu dữ liệu")
//        void alterTableAddColumnSuggestions() {
//            var result = suggest("alter table public.users add column |");
//            var datatypes = keysOfType(result, "datatype");
//            assertTrue(datatypes.contains("int4"));
//            assertTrue(datatypes.contains("text"));
//        }
//
//        @Test
//        @DisplayName("DROP TABLE - gợi ý bảng")
//        void dropTableSuggestions() {
//            var result = suggest("drop table |");
//            var tables = keysOfType(result, "table");
//            assertTrue(tables.contains("public.users"));
//            assertTrue(tables.contains("public.orders"));
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 15: Test edge cases và error handling
//    // =====================================================================
//
//    @Nested
//    @DisplayName("Edge cases và Error handling")
//    class EdgeCases {
//
//        @Test
//        @DisplayName("SQL rỗng - không throw và trả về kết quả")
//        void emptySql() {
//            assertDoesNotThrow(() -> suggest("|"));
//            var result = suggest("|");
//            assertNotNull(result);
//        }
//
//        @Test
//        @DisplayName("SQL chỉ có khoảng trắng - không throw")
//        void whitespaceOnly() {
//            assertDoesNotThrow(() -> suggest("   |   "));
//            var result = suggest("   |   ");
//            assertNotNull(result);
//        }
//
//        @Test
//        @DisplayName("Cursor ở đầu câu - gợi ý đầy đủ")
//        void cursorAtBeginning() {
//            var result = suggest("|select * from users");
//            var keywords = allKeywordKeys(result);
//            assertTrue(keywords.contains("select"));
//            assertTrue(keywords.contains("with"));
//        }
//
//        @Test
//        @DisplayName("Cursor ở cuối câu - gợi ý tiếp theo")
//        void cursorAtEnd() {
//            var result = suggest("select * from users |");
//            var keywords = allKeywordKeys(result);
//            assertTrue(keywords.contains("where") || keywords.contains("order") || keywords.contains("limit"));
//        }
//
//        @Test
//        @DisplayName("SQL sai cú pháp - không crash và vẫn gợi ý cơ bản")
//        void invalidSyntax() {
//            assertDoesNotThrow(() -> suggest("select from |"));
//            var result = suggest("select from |");
//            // Vẫn có thể gợi ý bảng dù câu lệnh sai
//            assertNotNull(result);
//        }
//
//        @Test
//        @DisplayName("Bảng không tồn tại - không crash")
//        void nonExistentTable() {
//            assertDoesNotThrow(() -> suggest("select * from nonexistent_table where |"));
//            var result = suggest("select * from nonexistent_table where |");
//            assertNotNull(result);
//        }
//
//        @Test
//        @DisplayName("Tên cột không tồn tại - không crash")
//        void nonExistentColumn() {
//            assertDoesNotThrow(() -> suggest("select nonexistent_col from public.users where |"));
//            var result = suggest("select nonexistent_col from public.users where |");
//            assertNotNull(result);
//        }
//
//        @Test
//        @DisplayName("Chuỗi SQL dài - performance và không crash")
//        void longSql() {
//            var longSql = "select * from public.users u join public.orders o on u.id = o.customer_id " +
//                    "join public.products p on o.product_id = p.id " +
//                    "where u.status = 'active' and o.total > 100 " +
//                    "group by u.id, u.name " +
//                    "having count(*) > 5 " +
//                    "order by u.name limit 10 |";
//            assertDoesNotThrow(() -> suggest(longSql));
//            var result = suggest(longSql);
//            assertNotNull(result);
//        }
//
//        @Test
//        @DisplayName("Nested function call - gợi ý đúng")
//        void nestedFunctionCalls() {
//            var result = suggest("select upper(concat(| from public.users");
//            assertNotNull(result);
//            // Có thể gợi ý cột hoặc function
//        }
//
//        @Test
//        @DisplayName("Cast expression - gợi ý kiểu dữ liệu")
//        void castExpression() {
//            var result = suggest("select id::| from public.users");
//            var datatypes = keysOfType(result, "datatype");
//            assertTrue(datatypes.contains("text"));
//            assertTrue(datatypes.contains("numeric"));
//        }
//    }
//
//    // =====================================================================
//    // Nhóm 16: Test alias uniqueness và conflicts
//    // =====================================================================
//
//    @Nested
//    @DisplayName("Alias uniqueness và conflict handling")
//    class AliasUniqueness {
//
//        @Test
//        @DisplayName("Khi alias đã dùng, đề xuất alias tiếp theo (có số)")
//        void aliasAutoIncrement() {
//            var result = suggest("select * from public.users u join public.orders as |");
//            assertTrue(hasKeyOfType(result, "o", "alias"));
//        }
//
//        @Test
//        @DisplayName("Nhiều alias đã dùng, đề xuất số tiếp theo")
//        void aliasMultipleIncrements() {
//            var result = suggest("select * from public.users u join public.orders o join public.products as |");
//            assertTrue(hasKeyOfType(result, "p", "alias") || hasKeyOfType(result, "p1", "alias"));
//        }
//
//        @Test
//        @DisplayName("Tên alias trùng với keyword không bị conflict")
//        void aliasConflictWithKeyword() {
//            var result = suggest("select * from public.users as |");
//            // 'order' là keyword nhưng có thể làm alias
//            var aliases = keysOfType(result, "alias");
//            assertTrue(aliases.contains("u"));
//        }
//    }
//}