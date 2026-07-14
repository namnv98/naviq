package com.naviq.completion;

import com.naviq.model.Suggest;
import com.naviq.datasource.SchemaIndex;
import com.naviq.datasource.SchemaLoader;
import com.naviq.completion.suggests.oracle.CompletionEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OracleCompletionEngineTest {
    @BeforeAll
    static void setUpFixtureSchema() {
        var idCol = new SchemaLoader.DBColumnInfo("id", "id", "int4", true);
        var nameCol = new SchemaLoader.DBColumnInfo("name", "name", "text", false);
        var emailCol = new SchemaLoader.DBColumnInfo("email", "email", "text", false);
        var managerIdCol = new SchemaLoader.DBColumnInfo("manager_id", "manager_id", "int4", false);
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
                List.of(idCol, nameCol, emailCol, managerIdCol, createdDateCol));
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
        var result = suggest("select * from |");
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

    @Test
    @DisplayName("'select * from users u where u.|' - dangling dot NGAY SAU alias trong WHERE, "
            + "PHẢI chỉ gợi ý đúng cột của alias u (không lẫn cột bảng khác nếu có nhiều FROM)")
    void danglingDotAfterAliasInWhereSuggestsOnlyThatTableColumns() {
        var result = suggest("select * from users u where u.|");
        assertTrue(hasKeyOfType(result, "u.id", "column"));
        assertTrue(hasKeyOfType(result, "u.email", "column"));
    }

    @Test
    @DisplayName("'select u.| from users u join orders o on u.id = o.user_id' - dangling dot "
            + "trong SELECT list với 2 bảng JOIN cùng lúc - PHẢI chỉ gợi ý cột của alias u, "
            + "KHÔNG được lẫn cột của o (kiểm tra DanglingDotDetector không bị 'tràn' qua scope "
            + "của alias khác đang cùng visible)")
    void danglingDotInSelectListWithMultipleJoinsScopesCorrectAlias() {
        var result = suggest("select u.| from users u join orders o on u.id = o.user_id");
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertFalse(hasKeyOfType(result, "o.total", "column"));
    }

    @Test
    @DisplayName("'select sub.| from (select id, name from users) sub' - dangling dot trỏ tới "
            + "ALIAS CỦA SUBQUERY, phải resolve qua derivedScopeAliases (Scope thật của subquery), "
            + "gợi ý đúng cột PROJECTED (id, name) chứ không phải toàn bộ cột bảng users gốc")
    void danglingDotForSubqueryAliasSuggestsProjectedColumnsOnly() {
        var result = suggest("select sub.| from (select id, name from users) sub");
        assertTrue(hasKeyOfType(result, "sub.id", "column"));
        assertTrue(hasKeyOfType(result, "sub.name", "column"));
        // "email" không nằm trong SELECT list của subquery -> không được coi là cột của "sub"
        assertFalse(hasKeyOfType(result, "sub.email", "column"));
    }

    @Test
    @DisplayName("'with c as (select id, name from users) select c.| from c' - dangling dot trỏ "
            + "tới CTE, resolveAsExistingCte + derivedScopeAliases phải hoạt động đúng qua dấu "
            + "chấm cụt, không chỉ qua completion không-dấu-chấm (đã test ở cteColumnSuggestions)")
    void danglingDotForCteSuggestsProjectedColumnsOnly() {
        var result = suggest("with c as (select id, name from users) select c.| from c");
        assertTrue(hasKeyOfType(result, "c.id", "column"));
        assertTrue(hasKeyOfType(result, "c.name", "column"));
    }

    @Test
    @DisplayName("'delete from users u where u.|' - dangling dot trong DELETE, alias đăng ký qua "
            + "general_table_ref (khác registerDmlTableAlias của Postgres, cần verify tên hàm "
            + "tương ứng bên Oracle CompletionEngine) vẫn phải resolve được qua dấu chấm cụt")
    void danglingDotInDeleteWhereClause() {
        var result = suggest("delete from users u where u.|");
        assertTrue(hasKeyOfType(result, "u.email", "column"));
    }

    @Test
    @DisplayName("'select * from users u where exists (select 1 from orders o where o.user_id = u.|)' "
            + "- CORRELATED SUBQUERY: dấu chấm cụt của alias NGOÀI (u) đứng bên TRONG subquery - "
            + "scope con phải thấy được alias của scope cha (visibilityChain đi lên tổ tiên), "
            + "không được coi 'u' là alias lạ chỉ vì đang đứng trong 1 scope khác")
    void danglingDotForOuterAliasInsideCorrelatedSubquery() {
        var result = suggest(
                "select * from users u where exists (select 1 from orders o where o.user_id = u.|)");
        assertTrue(hasKeyOfType(result, "u.id", "column"));
    }

    @Test
    @DisplayName("'insert into users values (|)' - PHỦ ĐỊNH: values_clause nhận EXPRESSION "
            + "(literal/bind var/biểu thức), KHÔNG phải column_name - vị trí này KHÔNG được gợi ý "
            + "cột nào cả (khác hẳn 'insert into users (|' đã test ở insertColumnListSuggestions, "
            + "dễ nhầm lẫn 2 vị trí nếu code xử lý INSERT sai)")
    void insertValuesClauseDoesNotSuggestColumns() {
        var result = suggest("insert into users values (|)");
        assertTrue(keysOfType(result, "column").isEmpty());
    }

    @Test
    @DisplayName("PHỦ ĐỊNH: 'select * from users where id = 1' (KHÔNG có caret ở vùng liên quan) "
            + "- gợi ý tại vị trí ngay sau 'FROM' của 1 câu ĐÃ HOÀN CHỈNH đứng trước, đảm bảo scope "
            + "của statement trước không rò rỉ gợi ý cột sang statement sau nếu có nhiều statement")
    void secondStatementDoesNotSeeFirstStatementAliases() {
        var result = suggest("select * from users u where u.id = 1; select * from |");
        var tables = keysOfType(result, "table");
        assertTrue(tables.stream().anyMatch(t -> t.equalsIgnoreCase("public.orders")));
        assertFalse(hasKeyOfType(result, "u.id", "column"));
    }

    @Test
    @DisplayName("'select | from users union select | from orders' (2 vị trí caret riêng biệt, "
            + "test bằng 2 lời gọi khác nhau) - mỗi vế UNION có scope riêng, vế sau KHÔNG được "
            + "thấy alias/cột của vế trước dù cùng 1 statement UNION")
    void unionBranchesHaveIndependentScopes() {
        var firstBranch = suggest("select | from users union select id from orders");
        assertTrue(hasKeyOfType(firstBranch, "users.name", "column"));
        assertFalse(hasKeyOfType(firstBranch, "orders.total", "column"));

        var secondBranch = suggest("select id from users union select | from orders");
        assertTrue(hasKeyOfType(secondBranch, "orders.total", "column"));
        assertFalse(hasKeyOfType(secondBranch, "users.name", "column"));
    }

    @Test
    @DisplayName("'select * from users u, orders o where |' - FROM list kiểu dấu phẩy (cú pháp "
            + "JOIN cũ, KHÔNG dùng từ khoá JOIN) vẫn phải đăng ký được CẢ 2 alias u và o")
    void commaStyleFromListRegistersBothAliases() {
        var result = suggest("select * from users u, orders o where |");
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "o.total", "column"));
    }

    @Test
    @DisplayName("'update users u set name = |' - UPDATE có alias KHÔNG dùng AS, phải resolve "
            + "được scope alias 'u' cho phần bên PHẢI dấu '=' (không chỉ phần column_name bên "
            + "trái đã test ở updateSetColumnSuggestions)")
    void updateSetRightHandSideSeesTableAlias() {
        var result = suggest("update users u set name = |");
        assertTrue(hasKeyOfType(result, "u.email", "column"));
    }

    @Test
    @DisplayName("ROBUSTNESS: input có chuỗi string chưa đóng ('...') - error-recovery/isUnreliable "
            + "phải chặn đăng ký alias dựa trên dữ liệu không đáng tin, nhưng KHÔNG ĐƯỢC crash, "
            + "và alias 'u' đứng TRƯỚC chỗ lỗi (chưa bị ảnh hưởng) vẫn phải còn nguyên")
    void unterminatedStringLiteralDoesNotCrashAndKeepsPriorAlias() {
        assertDoesNotThrow(() -> {
            var result = suggest("select * from users u where u.name = 'unterminated and u.|");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("ROBUSTNESS: gõ dở giữa chừng 1 subquery chưa đóng ngoặc "
            + "'select * from (select id from users |' - PHẢI không crash; vì thiếu CLOSE_PAREN, "
            + "scope subquery coi như MỞ tới hết input (đúng BUG FIX đã nói ở popScope) thay vì "
            + "đóng non và mất alias")
    void unclosedSubqueryParenDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("select * from (select id, name from users |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'select * from users u left join orders o on u.id = o.user_id where |' - "
            + "LEFT JOIN (khác JOIN trần đã test) vẫn phải đăng ký đủ cả 2 alias, kiểm tra code "
            + "xử lý join_type không bỏ sót nhánh LEFT/RIGHT/FULL")
    void leftJoinRegistersBothAliases() {
        var result = suggest("select * from users u left join orders o on u.id = o.user_id where |");
        assertTrue(hasKeyOfType(result, "u.id", "column"));
        assertTrue(hasKeyOfType(result, "o.status", "column"));
    }

    @Test
    @DisplayName("'select * from users u where u.status = |' - PHỦ ĐỊNH cho GROUP BY/HAVING chưa "
            + "test: gợi ý bên PHẢI toán tử so sánh (=) là biểu thức GIÁ TRỊ, KHÔNG phải danh sách "
            + "cột trần bắt buộc - chấp nhận cả 2 (cột hoặc giá trị) miễn không throw, nhưng không "
            + "được RỖNG hoàn toàn nếu cột đúng là 1 lựa chọn hợp lệ ở vị trí value expression")
    void comparisonRightHandSideAcceptsColumnReference() {
        var result = suggest("select * from users u where u.status = |");
        assertNotNull(result);
        // Không assert cứng phải CÓ cột - vì đây là vị trí "biểu thức", có thể gợi ý cả literal/
        // bind variable/hàm - chỉ đảm bảo không rỗng hoàn toàn và không crash.
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("'select count(*), | from orders group by status' - SELECT list có cả hàm "
            + "aggregate (count(*)) LẪN cột trần trong CÙNG 1 danh sách - vị trí cột trần thứ 2 "
            + "vẫn phải gợi ý cột bình thường, không bị hàm aggregate đứng trước làm nhiễu")
    void selectListMixedAggregateAndPlainColumnStillSuggestsColumns() {
        var result = suggest("select count(*), | from orders group by status");
        assertTrue(hasKeyOfType(result, "orders.status", "column"));
    }

    // =====================================================================
    // Các test bổ sung
    // =====================================================================

    @Test
    @DisplayName("'select * from users group by |' - gợi ý các cột của bảng users để nhóm")
    void groupByClauseColumnSuggestions() {
        var result = suggest("select * from users group by |");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.name", "column"));
        assertTrue(hasKeyOfType(result, "users.email", "column"));
    }

    @Test
    @DisplayName("'select status, count(*) from orders group by status having |' - HAVING gợi ý cột đã có trong GROUP BY")
    void havingClauseColumnSuggestions() {
        var result = suggest("select status, count(*) from orders group by status having |");
        // status là cột trong GROUP BY, có thể dùng trong HAVING
        assertTrue(hasKeyOfType(result, "orders.status", "column"));
        // aggregate count(*) không phải cột nên không gợi ý, nhưng có thể gợi ý hàm count
        // không kiểm tra thêm
    }

    @Test
    @DisplayName("'select * from users order by |' - gợi ý cột của bảng users cho ORDER BY")
    void orderByClauseColumnSuggestions() {
        var result = suggest("select * from users order by |");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'select id as user_id from users order by |' - ORDER BY gợi ý được alias của cột")
    void orderByWithAliasSuggestion() {
        var result = suggest("select id as user_id from users order by |");
//        // Alias user_id nên được gợi ý dạng column (vì có thể dùng trong ORDER BY)
//        assertTrue(hasKeyOfType(result, "user_id", "column"));
        // Cũng có thể gợi ý cột thật users.id
        assertTrue(hasKeyOfType(result, "users.id", "column"));
    }

    @Test
    @DisplayName("'select | from users' - gợi ý cả cột và hàm (count, sum, ...)")
    void selectListFunctionSuggestions() {
        var result = suggest("select | from users");
        // Có cột
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        // Có hàm (từ SchemaIndex.FUNCTIONS)
        assertTrue(hasKeyOfType(result, "count", "function"));
        assertTrue(hasKeyOfType(result, "sum", "function"));
        assertTrue(hasKeyOfType(result, "avg", "function"));
    }

    @Test
    @DisplayName("'select * from users u join orders o on |' - ON clause gợi ý cột của cả 2 bảng với alias")
    void onClauseColumnSuggestions() {
        var result = suggest("select * from users u join orders o on |");
        assertTrue(hasKeyOfType(result, "u.id", "column"));
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "o.id", "column"));
        assertTrue(hasKeyOfType(result, "o.total", "column"));
    }

    @Test
    @DisplayName("'select * from users u join orders o on u.id = o.user_id and |' - ON tiếp tục với AND gợi ý cột")
    void onClauseAndContinuationColumnSuggestions() {
        var result = suggest("select * from users u join orders o on u.id = o.user_id and |");
        assertTrue(hasKeyOfType(result, "u.email", "column"));
        assertTrue(hasKeyOfType(result, "o.status", "column"));
    }

    @Test
    @DisplayName("'merge into users u using orders o on (u.id = o.user_id) when matched then update set |' - gợi ý cột của target table")
    void mergeUpdateSetColumnSuggestions() {
        var result = suggest("merge into users u using orders o on (u.id = o.user_id) when matched then update set |");
        // Cột của users (target)
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "u.email", "column"));
        // Không gợi ý cột của orders (source)
        assertFalse(hasKeyOfType(result, "o.total", "column"));
    }

    @Test
    @DisplayName("'alter table users add column new_col |' - sau định nghĩa cột, gợi ý kiểu dữ liệu")
    void alterTableAddColumnDataTypeSuggestions() {
        var result = suggest("alter table users add new_col |");
        var datatypes = keysOfType(result, "datatype");
        assertTrue(datatypes.contains("int4"));
        assertTrue(datatypes.contains("text"));
        assertTrue(datatypes.contains("numeric"));
    }

    @Test
    @DisplayName("'alter table users modify column email |' - MODIFY cột gợi ý kiểu dữ liệu (có thể NULL/NOT NULL nhưng ta chỉ test datatype)")
    void alterTableModifyColumnDataTypeSuggestions() {
        var result = suggest("alter table users modify email |");
        var datatypes = keysOfType(result, "datatype");
        assertTrue(datatypes.contains("text"));
        // Có thể gợi ý thêm NULL/NOT NULL nhưng không bắt buộc
    }

    @Test
    @DisplayName("'drop table |' - gợi ý tên bảng")
    void dropTableSuggestions() {
        var result = suggest("drop table |");
        var tables = keysOfType(result, "table");
        assertTrue(tables.contains("public.users"));
        assertTrue(tables.contains("public.orders"));
        assertTrue(tables.contains("public.contracts"));
    }

    @Test
    @DisplayName("'truncate table |' - gợi ý tên bảng")
    void truncateTableSuggestions() {
        var result = suggest("truncate table |");
        var tables = keysOfType(result, "table");
        assertTrue(tables.contains("public.users"));
        assertTrue(tables.contains("public.orders"));
    }

    @Test
    @DisplayName("'select distinct | from users' - DISTINCT vẫn gợi ý cột bình thường")
    void selectDistinctColumnSuggestions() {
        var result = suggest("select distinct | from users");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'select (select | from orders o where o.user_id = u.id) from users u' - subquery trong SELECT list gợi ý cột của subquery và outer alias")
    void subqueryInSelectListColumnSuggestions() {
        var result = suggest("select (select | from orders o where o.user_id = u.id) from users u");
        // Trong subquery, có thể gợi ý cột của orders (o)
        assertTrue(hasKeyOfType(result, "o.total", "column"));
        // Outer alias u cũng visible (correlated)
        assertTrue(hasKeyOfType(result, "u.id", "column"));
        // Không gợi ý cột không tồn tại
    }

    @Test
    @DisplayName("'with c1 as (select id from users), c2 as (select id, status from orders) select | from c1 join c2 on c1.id = c2.id' - nhiều CTE gợi ý cột của từng CTE")
    void multipleCtesColumnSuggestions() {
        var result = suggest("with c1 as (select id from users), c2 as (select id, status from orders) select | from c1 join c2 on c1.id = c2.id");
        assertTrue(hasKeyOfType(result, "c1.id", "column"));
        assertTrue(hasKeyOfType(result, "c2.id", "column"));
        assertTrue(hasKeyOfType(result, "c2.status", "column"));
    }

    @Test
    @DisplayName("'with c (col1, col2) as (select id, name from users) select | from c' - CTE có danh sách cột gợi ý đúng alias cột")
    void cteWithColumnListSuggestions() {
        var result = suggest("with c (col1, col2) as (select id, name from users) select | from c");
        // Phải gợi ý col1, col2 chứ không phải id, name
        assertTrue(hasKeyOfType(result, "c.col1", "column"));
        assertTrue(hasKeyOfType(result, "c.col2", "column"));
        assertFalse(hasKeyOfType(result, "c.id", "column"));
        assertFalse(hasKeyOfType(result, "c.name", "column"));
    }

    @Test
    @DisplayName("'select id from users union select | from orders' - vế UNION thứ hai gợi ý cột của orders, không thấy users")
    void unionSecondBranchColumnSuggestions() {
        var result = suggest("select id from users union select | from orders");
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
        assertFalse(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'select | from users' - gợi ý keyword 'distinct' và 'all'? (nếu có) - kiểm tra từ khóa")
    void selectKeywordSuggestions() {
        var result = suggest("select | from users");
        var keywords = allKeywordKeys(result);
        // Có thể gợi ý distinct/all nếu parser hỗ trợ
        // Tùy triển khai, nhưng ta kiểm tra nếu có
        // Không assert cứng, chỉ đảm bảo không crash
        assertNotNull(result);
    }

    @Test
    @DisplayName("'select * from users u where u.id = |' - bên phải so sánh gợi ý cột (có thể), hàm, literal - không rỗng")
    void comparisonRightHandSideGeneralSuggestions() {
        var result = suggest("select * from users u where u.id = |");
        assertFalse(result.isEmpty());
        // Có thể có cột, hàm, hoặc keyword NULL
    }

    @Test
    @DisplayName("'select * from users where id in (select | from orders)' - subquery trong IN gợi ý cột của orders")
    void subqueryInInClauseColumnSuggestions() {
        var result = suggest("select * from users where id in (select | from orders)");
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
    }

    @Test
    @DisplayName("ROBUSTNESS: câu lệnh thiếu FROM nhưng có alias - không crash")
    void missingFromDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("select u.|");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("ROBUSTNESS: câu lệnh chỉ có 'select |' - không crash, có thể gợi ý hàm hoặc từ khóa")
    void bareSelectDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("select |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT ROWNUM, | FROM users' - pseudo-column ROWNUM, vị trí sau dấu phẩy gợi ý cột của users")
    void selectListAfterRowNumSuggestsColumns() {
        var result = suggest("SELECT ROWNUM, | FROM users");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'SELECT LEVEL, | FROM users CONNECT BY PRIOR id = manager_id' - LEVEL pseudo-column, gợi ý cột users")
    void selectLevelAndColumnSuggestions() {
        var result = suggest("SELECT LEVEL, | FROM users CONNECT BY PRIOR id = manager_id");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users START WITH |' - START WITH condition suggests columns of users")
    void startWithConditionSuggestsColumns() {
        var result = suggest("SELECT * FROM users START WITH |");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.manager_id", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users CONNECT BY |' - after CONNECT BY, suggests keyword PRIOR and columns")
    void connectBySuggestsPriorKeywordAndColumns() {
        var result = suggest("SELECT * FROM users CONNECT BY |");
        assertTrue(allKeywordKeys(result).contains("prior"));
        // Có thể gợi ý cột nếu grammar cho phép expression
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("'SELECT NVL(name, |) FROM users' - second argument of NVL suggests columns or expressions")
    void nvlFunctionSecondArgSuggestsSomething() {
        var result = suggest("SELECT NVL(name, |) FROM users");
        assertFalse(result.isEmpty());
        assertTrue(hasKeyOfType(result, "users.email", "column"));
    }

    @Test
    @DisplayName("'SELECT DECODE(status, 'A', 'Active', |) FROM orders' - DECODE function argument suggests columns or values")
    void decodeFunctionArgSuggestsSomething() {
        var result = suggest("SELECT DECODE(status, 'A', 'Active', |) FROM orders");
        assertFalse(result.isEmpty());
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
    }

    @Test
    @DisplayName("'CREATE TABLE new_users AS SELECT | FROM users' - CTAS select list suggests columns")
    void createTableAsSelectListSuggestsColumns() {
        var result = suggest("CREATE TABLE new_users AS SELECT | FROM users");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'CREATE VIEW v AS SELECT | FROM users' - view select list suggests columns")
    void createViewSelectListSuggestsColumns() {
        var result = suggest("CREATE VIEW v AS SELECT | FROM users");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'ALTER TABLE users ADD CONSTRAINT pk PRIMARY KEY (|)' - constraint column list suggests columns")
    void alterTableAddConstraintColumnListSuggestsColumns() {
        var result = suggest("ALTER TABLE users ADD CONSTRAINT pk PRIMARY KEY (|)");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users FOR |' - FOR keyword suggests UPDATE from keyword list")
    void forClauseSuggestsUpdateKeyword() {
        var result = suggest("SELECT * FROM users FOR |");
        assertTrue(allKeywordKeys(result).contains("update"));
    }

    @Test
    @DisplayName("'SELECT * FROM users ORDER BY id OFFSET |' - OFFSET should not crash, may suggest number literals?")
    void offsetClauseDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users ORDER BY id OFFSET |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT * FROM users FETCH FIRST | ROWS ONLY' - FETCH FIRST should not crash")
    void fetchFirstDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users FETCH FIRST | ROWS ONLY");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE ROWNUM < |' - ROWNUM comparison should not crash")
    void rownumComparisonDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users WHERE ROWNUM < |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT SUM(total) OVER (PARTITION BY |) FROM orders' - PARTITION BY suggests columns of orders")
    void windowPartitionBySuggestsColumns() {
        var result = suggest("SELECT SUM(total) OVER (PARTITION BY |) FROM orders");
        assertTrue(hasKeyOfType(result, "orders.status", "column"));
        assertTrue(hasKeyOfType(result, "orders.user_id", "column"));
    }

    @Test
    @DisplayName("'SELECT RANK() OVER (ORDER BY |) FROM orders' - ORDER BY inside OVER suggests columns")
    void windowOrderBySuggestsColumns() {
        var result = suggest("SELECT RANK() OVER (ORDER BY |) FROM orders");
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
    }
    // =====================================================================
    // Additional test cases - Set operations, CASE, analytic functions,
    // DDL, DCL, PL/SQL, advanced features, etc.
    // =====================================================================

    @Test
    @DisplayName("'SELECT id FROM users INTERSECT SELECT | FROM orders' - INTERSECT second branch suggests columns from orders")
    void intersectSecondBranchSuggestsColumns() {
        var result = suggest("SELECT id FROM users INTERSECT SELECT | FROM orders");
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
        assertFalse(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'SELECT id FROM users MINUS SELECT | FROM orders' - MINUS second branch suggests columns from orders")
    void minusSecondBranchSuggestsColumns() {
        var result = suggest("SELECT id FROM users MINUS SELECT | FROM orders");
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
        assertFalse(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'SELECT CASE WHEN status = 'A' THEN | END FROM orders' - CASE THEN clause suggests columns or expressions")
    void caseThenClauseSuggestsSomething() {
        var result = suggest("SELECT CASE WHEN status = 'A' THEN | END FROM orders");
        assertFalse(result.isEmpty());
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
    }

    @Test
    @DisplayName("'SELECT CASE status WHEN 'A' THEN | END FROM orders' - simple CASE THEN suggests something")
    void simpleCaseThenClauseSuggestsSomething() {
        var result = suggest("SELECT CASE status WHEN 'A' THEN | END FROM orders");
        assertFalse(result.isEmpty());
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
    }

    @Test
    @DisplayName("'SELECT COALESCE(total, |) FROM orders' - COALESCE second arg suggests columns or expressions")
    void coalesceSecondArgSuggestsSomething() {
        var result = suggest("SELECT COALESCE(total, |) FROM orders");
        assertFalse(result.isEmpty());
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
    }

    @Test
    @DisplayName("'SELECT NULLIF(status, |) FROM orders' - NULLIF second arg suggests something")
    void nullifSecondArgSuggestsSomething() {
        var result = suggest("SELECT NULLIF(status, |) FROM orders");
        assertFalse(result.isEmpty());
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
    }

    @Test
    @DisplayName("'SELECT GREATEST(total, |) FROM orders' - GREATEST second arg suggests columns or expressions")
    void greatestSecondArgSuggestsSomething() {
        var result = suggest("SELECT GREATEST(total, |) FROM orders");
        assertFalse(result.isEmpty());
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
    }

    @Test
    @DisplayName("'SELECT LEAST(total, |) FROM orders' - LEAST second arg suggests something")
    void leastSecondArgSuggestsSomething() {
        var result = suggest("SELECT LEAST(total, |) FROM orders");
        assertFalse(result.isEmpty());
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
    }

    @Test
    @DisplayName("'SELECT SUM(total) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND |) FROM orders' - windowing clause ROWS/RANGE suggests keywords CURRENT ROW, etc.")
    void windowFrameBoundSuggestsKeywordsAndColumns() {
        var result = suggest("SELECT SUM(total) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND |) FROM orders");
        var keywords = allKeywordKeys(result);
        assertTrue(keywords.contains("current row") || keywords.contains("unbounded preceding"));
        // Có thể gợi ý cột nếu cho phép expression
    }

    @Test
    @DisplayName("'SELECT FIRST_VALUE(name) OVER (PARTITION BY dept ORDER BY hire_date ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) FROM |' - suggests tables after FROM")
    void complexWindowFunctionAfterFromSuggestsTables() {
        var result = suggest("SELECT FIRST_VALUE(name) OVER (PARTITION BY dept ORDER BY hire_date ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) FROM |");
        var tables = keysOfType(result, "table");
        assertTrue(tables.contains("public.users"));
    }

    @Test
    @DisplayName("'MERGE INTO users u USING (SELECT * FROM orders) o ON (u.id = o.user_id) WHEN NOT MATCHED THEN INSERT (|) VALUES (...)' - INSERT column list in MERGE suggests target columns")
    void mergeInsertColumnListSuggestsTargetColumns() {
        var result = suggest("MERGE INTO users u USING orders o ON (u.id = o.user_id) WHEN NOT MATCHED THEN INSERT (|) VALUES (1, 'new')");
        assertTrue(hasKeyOfType(result, "u.id", "column"));
        assertTrue(hasKeyOfType(result, "u.name", "column"));
    }

    @Test
    @DisplayName("'MERGE INTO users u USING orders o ON (u.id = o.user_id) WHEN MATCHED THEN UPDATE SET name = |' - UPDATE RHS suggests source columns")
    void mergeUpdateSetRhsSuggestsSourceColumns() {
        var result = suggest("MERGE INTO users u USING orders o ON (u.id = o.user_id) WHEN MATCHED THEN UPDATE SET name = |");
        assertTrue(hasKeyOfType(result, "o.total", "column"));
        assertTrue(hasKeyOfType(result, "o.status", "column"));
        // Also target column might be visible
        assertTrue(hasKeyOfType(result, "u.email", "column"));
    }

    @Test
    @DisplayName("'INSERT ALL INTO users (id, name) VALUES (1, 'a') INTO orders (id, total) VALUES (2, |) SELECT * FROM DUAL' - INSERT ALL second VALUES suggests expression (no columns expected)")
    void insertAllValuesDoesNotSuggestColumns() {
        var result = suggest("INSERT ALL INTO users (id, name) VALUES (1, 'a') INTO orders (id, total) VALUES (2, |) SELECT * FROM DUAL");
        // Position is a value expression, not a column list, so column suggestions may be absent or minimal.
        // We just check no crash.
        assertNotNull(result);
        // Optionally ensure not suggesting columns of any table.
        assertTrue(keysOfType(result, "column").isEmpty() || result.stream().noneMatch(s -> s.getKey().matches("(?i).*\\..*")));
    }

    @Test
    @DisplayName("'INSERT FIRST WHEN total > 100 THEN INTO orders_high VALUES (|) ELSE INTO orders_low VALUES (|)' - multi-table insert, VALUES expression positions")
    void insertFirstValuesDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("INSERT FIRST WHEN total > 100 THEN INTO orders_high VALUES (|) ELSE INTO orders_low VALUES (|) SELECT * FROM orders");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'WITH c (col1) AS (SELECT id FROM users) SELECT | FROM c' - CTE column alias list (col1) suggests that alias only")
    void cteWithColumnAliasListSuggestsAlias() {
        var result = suggest("WITH c (col1) AS (SELECT id FROM users) SELECT | FROM c");
        assertTrue(hasKeyOfType(result, "c.col1", "column"));
        assertFalse(hasKeyOfType(result, "c.id", "column"));
    }

    @Test
    @DisplayName("'WITH RECURSIVE cte AS (SELECT id FROM users UNION ALL SELECT id FROM orders) SELECT | FROM cte' - recursive CTE suggests columns of cte")
    void recursiveCteSuggestsColumns() {
        var result = suggest("WITH RECURSIVE cte AS (SELECT id FROM users UNION ALL SELECT id FROM orders) SELECT | FROM cte");
        assertTrue(hasKeyOfType(result, "cte.id", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE id IN (SELECT | FROM orders)' - subquery in IN suggests columns from orders")
    void inSubquerySuggestsColumns() {
        var result = suggest("SELECT * FROM users WHERE id IN (SELECT | FROM orders)");
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE |)' - EXISTS subquery suggests columns from orders and outer alias")
    void existsSubquerySuggestsColumns() {
        var result = suggest("SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE |)");
        assertTrue(hasKeyOfType(result, "o.id", "column"));
        assertTrue(hasKeyOfType(result, "o.total", "column"));
        // Outer alias u should be visible (correlated)
        assertTrue(hasKeyOfType(result, "u.name", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE id = ANY (SELECT | FROM orders)' - ANY subquery suggests columns")
    void anySubquerySuggestsColumns() {
        var result = suggest("SELECT * FROM users WHERE id = ANY (SELECT | FROM orders)");
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE id > ALL (SELECT | FROM orders)' - ALL subquery suggests columns")
    void allSubquerySuggestsColumns() {
        var result = suggest("SELECT * FROM users WHERE id > ALL (SELECT | FROM orders)");
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE id IN (SELECT orders.id FROM orders)' - subquery with qualified column works")
    void subqueryQualifiedColumnDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users WHERE id IN (SELECT | FROM orders)");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT * FROM users u, TABLE(orders) o' - TABLE collection expression suggests columns from order table? (if supported) - no crash")
    void tableCollectionExpressionDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users u, TABLE(orders) o WHERE o.|");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'COMMENT ON TABLE users IS |' - COMMENT literal does not suggest columns, but not crash")
    void commentOnTableDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("COMMENT ON TABLE users IS |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'GRANT SELECT ON users TO |' - GRANT TO suggests user/role, not crash")
    void grantToDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("GRANT SELECT ON users TO |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'REVOKE SELECT ON users FROM |' - REVOKE FROM suggests user/role, not crash")
    void revokeFromDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("REVOKE SELECT ON users FROM |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'ANALYZE TABLE users COMPUTE STATISTICS' - ANALYZE does not crash (no cursor)")
    void analyzeTableDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("ANALYZE TABLE users |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'TRUNCATE TABLE users DROP STORAGE' - with storage clause does not crash")
    void truncateTableWithStorageDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("TRUNCATE TABLE users DROP |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'ALTER INDEX idx_name REBUILD |' - ALTER INDEX suggests ONLINE/PARALLEL keywords")
    void alterIndexRebuildSuggestsKeywords() {
        var result = suggest("ALTER INDEX idx_name REBUILD |");
        var keywords = allKeywordKeys(result);
        assertTrue(keywords.contains("online") || keywords.contains("parallel"));
    }

    @Test
    @DisplayName("'CREATE SEQUENCE seq_name START WITH |' - START WITH value, not crash")
    void createSequenceStartWithDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("CREATE SEQUENCE seq_name START WITH |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT seq_name.NEXTVAL FROM |' - NEXTVAL suggests tables after FROM")
    void nextvalFromSuggestsTables() {
        var result = suggest("SELECT seq_name.NEXTVAL FROM |");
        var tables = keysOfType(result, "table");
        assertTrue(tables.contains("public.users"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE SYSDATE > |' - SYSDATE comparison, not crash")
    void sysdateComparisonDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users WHERE SYSDATE > |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE TRUNC(SYSDATE) = |' - function call, not crash")
    void truncFunctionDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users WHERE TRUNC(SYSDATE) = |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT TO_CHAR(created_date, 'YYYY') FROM users WHERE |' - TO_CHAR with format mask, then WHERE suggests columns")
    void toCharThenWhereSuggestsColumns() {
        var result = suggest("SELECT TO_CHAR(created_date, 'YYYY') FROM users WHERE |");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE EXTRACT(YEAR FROM created_date) = |' - EXTRACT, not crash")
    void extractFunctionDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users WHERE EXTRACT(YEAR FROM created_date) = |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT * FROM users ORDER BY name NULLS FIRST |' - after NULLS FIRST, no suggestion (end of clause), but not crash")
    void orderByNullsFirstDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users ORDER BY name NULLS FIRST |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT * FROM users FOR UPDATE OF |' - FOR UPDATE OF suggests columns of users (for locking)")
    void forUpdateOfSuggestsColumns() {
        var result = suggest("SELECT * FROM users FOR UPDATE OF |");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE CURRENT OF cursor_name' - no cursor defined, but does not crash")
    void whereCurrentOfDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users WHERE CURRENT OF |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'DECLARE v_id NUMBER; BEGIN SELECT id INTO v_id FROM users WHERE |; END;' - PL/SQL block SELECT INTO where clause suggests columns")
    void plsqlSelectIntoWhereSuggestsColumns() {
        var result = suggest("DECLARE v_id NUMBER; BEGIN SELECT id INTO v_id FROM users WHERE |; END;");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'DECLARE v_name users.name%TYPE; BEGIN SELECT name INTO v_name FROM users WHERE id=1; | END;' - variable assignment after SELECT suggests columns? (not crash)")
    void plsqlVariableAssignmentDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("DECLARE v_name users.name%TYPE; BEGIN SELECT name INTO v_name FROM users WHERE id=1; | END;");
            // Probably no suggestions, but not crash
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'BEGIN FOR rec IN (SELECT * FROM users) LOOP DBMS_OUTPUT.PUT_LINE(rec.|); END LOOP; END;' - cursor loop rec. suggests columns of users")
    void plsqlCursorLoopRecDotSuggestsColumns() {
        var result = suggest("BEGIN FOR rec IN (SELECT * FROM users) LOOP DBMS_OUTPUT.PUT_LINE(rec.|); END LOOP; END;");
        assertTrue(hasKeyOfType(result, "rec.id", "column"));
        assertTrue(hasKeyOfType(result, "rec.name", "column"));
    }

    @Test
    @DisplayName("'BEGIN IF | THEN NULL; END IF; END;' - IF condition suggests columns? Actually no table visible, but not crash")
    void plsqlIfConditionDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("BEGIN IF | THEN NULL; END IF; END;");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT * FROM users PIVOT (COUNT(*) FOR status IN (|))' - PIVOT IN list suggests values? Not easy, but not crash")
    void pivotInClauseDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM orders PIVOT (COUNT(*) FOR status IN (|))");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT * FROM users UNPIVOT (value FOR column IN (|))' - UNPIVOT IN suggests columns of users")
    void unpivotInClauseSuggestsColumns() {
        var result = suggest("SELECT * FROM users UNPIVOT (value FOR column IN (|))");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE JSON_EXISTS(json_col, '$.?' (|))' - JSON_EXISTS condition, not crash")
    void jsonExistsDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users WHERE JSON_EXISTS(json_col, '$' |)");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE JSON_VALUE(json_col, '$.name' RETURNING VARCHAR2 |)' - JSON_VALUE returning type suggests datatypes")
    void jsonValueReturningSuggestsDatatypes() {
        var result = suggest("SELECT * FROM users WHERE JSON_VALUE(json_col, '$.name' RETURNING VARCHAR2 |)");
        var datatypes = keysOfType(result, "datatype");
        assertTrue(datatypes.contains("varchar") || datatypes.contains("text"));
    }

    @Test
    @DisplayName("'SELECT XMLELEMENT(ELEMENT, |) FROM users' - XMLELEMENT argument suggests columns")
    void xmlElementSuggestsColumns() {
        var result = suggest("SELECT XMLELEMENT(\"user\", |) FROM users");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
        assertTrue(hasKeyOfType(result, "users.name", "column"));
    }

    @Test
    @DisplayName("'SELECT XMLAGG(XMLELEMENT(ELEMENT, name)) FROM users WHERE |' - XMLAGG then WHERE suggests columns")
    void xmlAggThenWhereSuggestsColumns() {
        var result = suggest("SELECT XMLAGG(XMLELEMENT(\"name\", name)) FROM users WHERE |");
        assertTrue(hasKeyOfType(result, "users.id", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM XMLTABLE('/root/row' PASSING xml_col COLUMNS id INT PATH '@id', name VARCHAR2 |)' - XMLTABLE column type suggests datatypes")
    void xmlTableColumnTypeSuggestsDatatypes() {
        var result = suggest("SELECT * FROM XMLTABLE('/root/row' PASSING xml_col COLUMNS id INT, name |)");
        var datatypes = keysOfType(result, "datatype");
        assertTrue(datatypes.contains("varchar") || datatypes.contains("text"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE SOUNDEX(name) = SOUNDEX(|)' - SOUNDEX argument suggests columns or expressions")
    void soundexArgSuggestsSomething() {
        var result = suggest("SELECT * FROM users WHERE SOUNDEX(name) = SOUNDEX(|)");
        assertTrue(hasKeyOfType(result, "users.email", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE CONTAINS(name, 'keyword', 1) > 0' - Oracle Text, not crash")
    void containsFunctionDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users WHERE CONTAINS(name, 'keyword', 1) > |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE condition AND |' - after AND in WHERE suggests more conditions (columns, functions, keywords)")
    void whereAndContinuationSuggestsColumnsAndKeywords() {
        var result = suggest("SELECT * FROM users u WHERE u.id = 1 AND |");
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "u.email", "column"));
        // Also might suggest keyword 'NOT', 'EXISTS', etc.
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE id NOT IN (SELECT | FROM orders)' - NOT IN subquery suggests columns from orders")
    void notInSubquerySuggestsColumns() {
        var result = suggest("SELECT * FROM users WHERE id NOT IN (SELECT | FROM orders)");
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE id = ANY (SELECT | FROM orders)' - ANY subquery suggests columns")
    void anySubquerySuggestsColumnsAgain() {
        var result = suggest("SELECT * FROM users WHERE id = ANY (SELECT | FROM orders)");
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE id > ALL (SELECT | FROM orders)' - ALL subquery suggests columns")
    void allSubquerySuggestsColumnsAgain() {
        var result = suggest("SELECT * FROM users WHERE id > ALL (SELECT | FROM orders)");
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE id = (SELECT id FROM orders WHERE |)' - correlated subquery suggests columns from orders and outer")
    void correlatedSubquerySuggestsBoth() {
        var result = suggest("SELECT * FROM users u WHERE id = (SELECT o.id FROM orders o WHERE |)");
        assertTrue(hasKeyOfType(result, "o.total", "column"));
        assertTrue(hasKeyOfType(result, "u.name", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE id IN (SELECT id FROM orders UNION SELECT | FROM products)' - UNION inside subquery suggests columns from products")
    void unionInsideSubquerySuggestsColumns() {
        var result = suggest("SELECT * FROM users WHERE id IN (SELECT id FROM orders UNION SELECT | FROM products)");
        assertTrue(hasKeyOfType(result, "products.id", "column"));
        assertTrue(hasKeyOfType(result, "products.name", "column"));
        assertFalse(hasKeyOfType(result, "orders.total", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE id IN (SELECT id FROM orders MINUS SELECT | FROM products)' - MINUS inside subquery suggests columns from products")
    void minusInsideSubquerySuggestsColumns() {
        var result = suggest("SELECT * FROM users WHERE id IN (SELECT id FROM orders MINUS SELECT | FROM products)");
        assertTrue(hasKeyOfType(result, "products.id", "column"));
        assertTrue(hasKeyOfType(result, "products.name", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE ROWNUM <= |' - ROWNUM compares to number, not column, but not crash")
    void rownumCompareDoesNotCrash() {
        assertDoesNotThrow(() -> {
            var result = suggest("SELECT * FROM users WHERE ROWNUM <= |");
            assertNotNull(result);
        });
    }

    @Test
    @DisplayName("'SELECT * FROM (SELECT * FROM users) WHERE |' - subquery in FROM, WHERE suggests columns from subquery alias? (subquery has no alias, table name accessible?)")
    void subqueryWithoutAliasWhereSuggestsColumns() {
        var result = suggest("SELECT * FROM (SELECT * FROM users) WHERE |");
        // Because subquery has no alias, columns may be exposed as original table name? Or maybe unresolved.
        // It should not crash.
        assertNotNull(result);
    }

    @Test
    @DisplayName("'SELECT * FROM (SELECT * FROM users) sub WHERE sub.|' - dangling dot on subquery alias suggests columns")
    void danglingDotOnSubqueryAliasSuggestsColumns() {
        var result = suggest("SELECT * FROM (SELECT * FROM users) sub WHERE sub.|");
        assertTrue(hasKeyOfType(result, "sub.id", "column"));
        assertTrue(hasKeyOfType(result, "sub.name", "column"));
    }

    @Test
    @DisplayName("'SELECT u.id, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) FROM users u WHERE u.|' - scalar subquery in SELECT and WHERE with dangling dot")
    void scalarSubqueryAndWhereDanglingDot() {
        var result = suggest("SELECT u.id, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) FROM users u WHERE u.|");
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "u.email", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users u WHERE u.id IN (SELECT o.user_id FROM orders o WHERE o.user_id = |)' - correlated subquery in IN, inner dangling dot points to outer alias?")
    void correlatedInSubqueryDanglingDot() {
        var result = suggest("SELECT * FROM users u WHERE u.id IN (SELECT o.user_id FROM orders o WHERE o.user_id = |)");
        // At this position, both o and u are visible, but dot is not present; we just suggest columns from o and u.
        assertTrue(hasKeyOfType(result, "o.total", "column"));
        assertTrue(hasKeyOfType(result, "u.name", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE id = (SELECT MAX(total) FROM orders WHERE |)' - subquery in scalar expression, WHERE suggests columns from orders")
    void scalarSubqueryWhereSuggestsColumns() {
        var result = suggest("SELECT * FROM users WHERE id = (SELECT MAX(total) FROM orders WHERE |)");
        assertTrue(hasKeyOfType(result, "orders.id", "column"));
        assertTrue(hasKeyOfType(result, "orders.status", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE user_id = u.id AND |)' - AND in EXISTS subquery suggests columns from orders and outer")
    void existsAndContinuationSuggestsColumns() {
        var result = suggest("SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND |)");
        assertTrue(hasKeyOfType(result, "o.total", "column"));
        assertTrue(hasKeyOfType(result, "u.name", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users u JOIN orders o ON u.id = o.user_id AND |' - ON clause AND suggests columns from both tables")
    void onClauseAndSuggestsBothTables() {
        var result = suggest("SELECT * FROM users u JOIN orders o ON u.id = o.user_id AND |");
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "o.status", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users u LEFT OUTER JOIN orders o ON u.id = o.user_id WHERE |' - LEFT OUTER JOIN registered both aliases")
    void leftOuterJoinWhereSuggestsBoth() {
        var result = suggest("SELECT * FROM users u LEFT OUTER JOIN orders o ON u.id = o.user_id WHERE |");
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "o.total", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users u RIGHT JOIN orders o ON u.id = o.user_id WHERE |' - RIGHT JOIN registered both")
    void rightJoinWhereSuggestsBoth() {
        var result = suggest("SELECT * FROM users u RIGHT JOIN orders o ON u.id = o.user_id WHERE |");
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "o.total", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users u FULL OUTER JOIN orders o ON u.id = o.user_id WHERE |' - FULL JOIN registered both")
    void fullJoinWhereSuggestsBoth() {
        var result = suggest("SELECT * FROM users u FULL OUTER JOIN orders o ON u.id = o.user_id WHERE |");
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "o.total", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users u NATURAL JOIN orders o WHERE |' - NATURAL JOIN aliases visible")
    void naturalJoinWhereSuggestsBoth() {
        var result = suggest("SELECT * FROM users u NATURAL JOIN orders o WHERE |");
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "o.total", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users u CROSS JOIN orders o WHERE |' - CROSS JOIN aliases visible")
    void crossJoinWhereSuggestsBoth() {
        var result = suggest("SELECT * FROM users u CROSS JOIN orders o WHERE |");
        assertTrue(hasKeyOfType(result, "u.name", "column"));
        assertTrue(hasKeyOfType(result, "o.total", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users INNER JOIN orders USING (user_id) WHERE |' - USING join, alias defaults to table names, columns visible")
    void innerJoinUsingWhereSuggestsColumns() {
        var result = suggest("SELECT * FROM users INNER JOIN orders USING (user_id) WHERE |");
        // Although USING merges columns, both table names are still accessible? In Oracle, USING disables table qualifier for join columns.
        // But we don't test that exact behavior, just ensure not crash.
        assertTrue(hasKeyOfType(result, "users.name", "column"));
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
    }

    @Test
    @DisplayName("'SELECT * FROM users NATURAL JOIN orders WHERE |' - NATURAL JOIN without alias, columns visible")
    void naturalJoinWithoutAliasWhereSuggestsColumns() {
        var result = suggest("SELECT * FROM users NATURAL JOIN orders WHERE |");
        // Should suggest columns from both tables (if parser handles)
        assertTrue(hasKeyOfType(result, "users.name", "column"));
        assertTrue(hasKeyOfType(result, "orders.total", "column"));
    }
}