package com.naviq.completion;

import com.naviq.completion.model.Suggest;
import com.naviq.datasource.SchemaIndex;
import com.naviq.datasource.SchemaLoader;
import com.naviq.oracle.suggests.CompletionEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OracleCompletionEngineTest {
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

    // =====================================================================
    // Nhóm mới - dựa trên rule THẬT đã verify trong PlSqlParser.g4 (không đoán) - xem lại
    // com.naviq.oracle.suggests.CompletionEngine đã sửa ở các lượt trước để đối chiếu.
    //
    // LƯU Ý CHUNG (giống mọi lượt trước): môi trường này không có mvn/mạng để build+chạy thật -
    // các test dưới đây được suy luận cẩn thận từ chính grammar Oracle, KHÔNG phải kết quả chạy
    // thực tế. Trước khi merge nên chạy thử ở máy có mvn để xác nhận.
    // =====================================================================

    @Test
    @DisplayName("'select * from |' - gợi ý bảng qua rule tableview_name (Oracle gộp chung khái "
            + "niệm mà Postgres tách any_name/qualified_name)")
    void tableNameSuggestionsAfterFrom() {
        var result = suggest("select * from |");
        var tables = keysOfType(result, "table");
        assertTrue(tables.stream().anyMatch(t -> t.equalsIgnoreCase("public.users")));
    }

    @Test
    @DisplayName("'select * from users |' - table_ref_aux cho phép table_alias KHÔNG cần AS "
            + "(giống Postgres), phải gợi ý được alias nào đó")
    void tableAliasSuggestionAfterTableName() {
        var result = suggest("select * from users |");
        assertFalse(keysOfType(result, "alias").isEmpty());
    }

    @Test
    @DisplayName("WHERE | sau FROM users (không alias tường minh) - biểu thức cột đi qua "
            + "general_element (KHÔNG phải column_name - xem giải thích ở lớp CompletionEngine), "
            + "alias mặc định = tên bảng")
    void columnSuggestionsInWhereClauseNoAlias() {
        var result = suggest("select * from users where |");
       var a= keysOfType(result, "column");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.email", "column"));
    }

    @Test
    @DisplayName("WHERE u.id = 1 AND | - tiếp tục biểu thức boolean, vẫn general_element, gợi ý "
            + "cột thật của alias u")
    void columnSuggestionsInWhereAndContinuation() {
        var result = suggest("select * from users u where u.id = 1 and |");
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "u.email", "column"));
    }

    @Test
    @DisplayName("'update users set |' - column_based_update_set_clause dùng THẲNG column_name "
            + "(không qua expression như ORDER BY/GROUP BY) - gợi ý cột thật")
    void updateSetColumnSuggestions() {
        var result = suggest("update users set |");
        assertTrue(hasKeyOfType(result, "users.name", "column"));
        assertTrue(hasKeyOfType(result, "users.email", "column"));
    }

    @Test
    @DisplayName("'delete from users where |' - delete_statement push scope riêng qua "
            + "general_table_ref, WHERE vẫn resolve cột qua general_element")
    void deleteWhereColumnSuggestions() {
        var result = suggest("delete from users where |");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
    }

    @Test
    @DisplayName("'insert into users (|' - paren_column_list -> column_list -> column_name, "
            + "insert_statement đăng ký bảng target qua insert_into_clause.general_table_ref")
    void insertColumnListSuggestions() {
        var result = suggest("insert into users (|");
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("JOIN ... USING (|) - paren_column_list dùng chung y hệt INSERT column-list, cả "
            + "2 bảng JOIN đều visible")
    void joinUsingColumnSuggestions() {
        var result = suggest("select * from users u join orders o using (|)");
        assertTrue(hasKeyOfType(result, "u.id", "column") || hasKeyOfType(result, "o.id", "column"));
    }

    @Test
    @DisplayName("'alter table users drop column |' - drop_column_clause dùng column_name trực "
            + "tiếp, alter_table push scope target riêng (isDdlTargetScope)")
    void alterTableDropColumnSuggestions() {
        var result = suggest("alter table users drop column |");
        assertTrue(hasKeyOfType(result, "users.email", "column"));
    }

    @Test
    @DisplayName("'create index idx1 on users (|)' - index_expr: column_name | expression - "
            + "table_index_clause đăng ký bảng ở exitTable_index_clause lên scope create_index đã "
            + "push từ enterCreate_index")
    void createIndexColumnSuggestions() {
        var result = suggest("create index idx1 on users (|)");
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("CAST(id AS |) - type_spec sau AS trong biểu thức CAST, gợi ý kiểu dữ liệu thật "
            + "(không cần block PL/SQL hoàn chỉnh như DECLARE, an toàn hơn để test)")
    void castExpressionDataTypeSuggestions() {
        var result = suggest("select cast(id as |) from users");
        var datatypes = keysOfType(result, "datatype");
        assertTrue(datatypes.contains("text") || datatypes.contains("numeric") || datatypes.contains("int4"));
    }

    @Test
    @DisplayName("WITH c AS (...) SELECT | FROM c - factoring_element đăng ký CTE NGAY TRONG "
            + "CÙNG scope của query_block chứa nó (khác Postgres cần withHost/pendingCte riêng) - "
            + "gợi ý đúng cột projected của CTE")
    void cteColumnSuggestions() {
        var result = suggest("with c as (select id, name from users) select | from c");
        assertTrue(hasKeyOfType(result, "c.id", "column"));
        assertTrue(hasKeyOfType(result, "c.name", "column"));
    }

    @Test
    @DisplayName("Subquery trong FROM có alias - dml_table_expression_clause nhánh "
            + "'(select_statement)' + alias, đăng ký derivedScopeAliases trỏ tới scope con")
    void subqueryInFromColumnSuggestions() {
        var result = suggest("select | from (select id, name from users) sub");
        assertTrue(hasKeyOfType(result, "sub.id", "column"));
        assertTrue(hasKeyOfType(result, "sub.name", "column"));
    }

    @Test
    @DisplayName("2 bảng JOIN, vị trí cột KHÔNG có qualifier (SELECT list trước FROM) - cả 2 alias "
            + "đều visible cùng lúc")
    void multipleJoinTablesColumnSuggestionsNoQualifier() {
        var result = suggest("select | from users u join orders o on u.id = o.user_id");
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "o.total", "column"));
    }

    @Test
    @DisplayName("ORDER BY name | - order_by_elements dùng 'expression' (KHÔNG phải column_name - "
            + "khác với UPDATE SET/JOIN USING/INSERT column-list) nên vẫn đi qua general_element, "
            + "sau đó chờ ASC/DESC/NULLS - kiểm tra keyword ASC/DESC vẫn gợi ý được")
    void orderByAscDescKeywordSuggestions() {
        var result = suggest("select * from users order by name |");
        var keywords = allKeywordKeys(result);
        assertTrue(keywords.contains("asc") || keywords.contains("desc"));
    }

    @Test
    @DisplayName("[ĐỘ TIN CẬY THẤP HƠN - chưa chắc chắn 100% nếu không chạy thử] "
            + "'begin | := 1; end;' - general_element ở đây là assignable_element (biến PL/SQL "
            + "cục bộ, KHÔNG phải cột bảng) - phải bị loại trừ, cột phải RỖNG. Đây là test cho "
            + "đúng cơ chế loại trừ isGeneralElementAssignTarget mới thêm ở CompletionEngine")
    void assignmentTargetDoesNotSuggestColumns() {
        var result = suggest("begin | := 1; end;");
        assertTrue(keysOfType(result, "column").isEmpty());
    }
}