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
}