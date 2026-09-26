package com.naviq.completion;

import com.naviq.completion.suggests.oracle.CompletionEngine;
import com.naviq.datasource.SchemaIndex;
import com.naviq.datasource.SchemaLoader;
import com.naviq.model.Suggest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("'select * from |' - gợi ý bảng qua rule tableview_name (Oracle gộp chung khái niệm mà Postgres tách any_name/qualified_name)")
    void tableNameSuggestionsAfterFrom() {
        var result = suggest("select * from |");
        assertExactTables(result, "public.users", "public.orders", "public.contracts", "public.products");
        assertExactViews(result, "public.orders_summary");
        assertExactColumns(result);
    }

    @Test
    @DisplayName("'select * from users |' - table_ref_aux cho phép table_alias KHÔNG cần AS (giống Postgres), phải gợi ý được alias nào đó")
    void tableAliasSuggestionAfterTableName() {
        var result = suggest("select * from users |");
        assertTrue(hasKeyOfType(result, "u", "alias"));
    }

    // GOM (parameterized) - 44 test trước đây tách riêng, cùng 1 hình dạng assertion:
    // 1 câu SQL -> đúng 1 lệnh assertExactColumns. Tên method gốc giữ lại trong displayName
    // để tra ngược lại lý do/ngữ cảnh khi cần (xem lịch sử git nếu cần @DisplayName đầy đủ).
    static Stream<Arguments> columnOnlyCases() {
        return Stream.of(
                Arguments.of("select * from users where |", "columnSuggestionsInWhereClauseNoAlias", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("select * from users u where u.id = 1 and |", "columnSuggestionsInWhereAndContinuation", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("update users set |", "updateSetColumnSuggestions", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("delete from users where |", "deleteWhereColumnSuggestions", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("insert into users (|", "insertColumnListSuggestions", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("alter table users drop column |", "alterTableDropColumnSuggestions", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("select | from users u join orders o on u.id = o.user_id", "multipleJoinTablesColumnSuggestionsNoQualifier", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}),
                Arguments.of("select * from users u where u.|", "danglingDotAfterAliasInWhereSuggestsOnlyThatTableColumns", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("select u.| from users u join orders o on u.id = o.user_id", "danglingDotInSelectListWithMultipleJoinsScopesCorrectAlias", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("delete from users u where u.|", "danglingDotInDeleteWhereClause", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("select * from users u, orders o where |", "commaStyleFromListRegistersBothAliases", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}),
                Arguments.of("update users u set name = |", "updateSetRightHandSideSeesTableAlias", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("select * from users u where u.name = 'unterminated and u.|", "unterminatedStringLiteralDoesNotCrashAndKeepsPriorAlias", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("select * from users u left join orders o on u.id = o.user_id where |", "leftJoinRegistersBothAliases", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}),
                Arguments.of("select * from users group by |", "groupByClauseColumnSuggestions", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("select * from users order by |", "orderByClauseColumnSuggestions", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("select id as user_id from users order by |", "orderByWithAliasSuggestion", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("select * from users u join orders o on |", "onClauseColumnSuggestions", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}),
                Arguments.of("select * from users u join orders o on u.id = o.user_id and |", "onClauseAndContinuationColumnSuggestions", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}),
                Arguments.of("select distinct | from users", "selectDistinctColumnSuggestions", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("select id from users union select | from orders", "unionSecondBranchColumnSuggestions", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("SELECT ROWNUM, | FROM users", "selectListAfterRowNumSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT LEVEL, | FROM users CONNECT BY PRIOR id = manager_id", "selectLevelAndColumnSuggestions", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users START WITH |", "startWithConditionSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("CREATE TABLE new_users AS SELECT | FROM users", "createTableAsSelectListSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("CREATE VIEW v AS SELECT | FROM users", "createViewSelectListSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users ORDER BY id OFFSET |", "offsetClauseDoesNotCrash", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users FETCH FIRST | ROWS ONLY", "fetchFirstDoesNotCrash", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users WHERE ROWNUM < |", "rownumComparisonDoesNotCrash", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT id FROM users INTERSECT SELECT | FROM orders", "intersectSecondBranchSuggestsColumns", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("SELECT id FROM users MINUS SELECT | FROM orders", "minusSecondBranchSuggestsColumns", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("SELECT CASE WHEN status = 'A' THEN | END FROM orders", "caseThenClauseSuggestsSomething", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("SELECT CASE status WHEN 'A' THEN | END FROM orders", "simpleCaseThenClauseSuggestsSomething", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("SELECT * FROM users WHERE SYSDATE > |", "sysdateComparisonDoesNotCrash", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users FOR UPDATE OF |", "forUpdateOfSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("DECLARE v_id NUMBER; BEGIN SELECT id INTO v_id FROM users WHERE |; END;", "plsqlSelectIntoWhereSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users WHERE ROWNUM <= |", "rownumCompareDoesNotCrash", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users u JOIN orders o ON u.id = o.user_id AND |", "onClauseAndSuggestsBothTables", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}),
                Arguments.of("SELECT * FROM users u LEFT OUTER JOIN orders o ON u.id = o.user_id WHERE |", "leftOuterJoinWhereSuggestsBoth", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}),
                Arguments.of("SELECT * FROM users u RIGHT JOIN orders o ON u.id = o.user_id WHERE |", "rightJoinWhereSuggestsBoth", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}),
                Arguments.of("SELECT * FROM users u FULL OUTER JOIN orders o ON u.id = o.user_id WHERE |", "fullJoinWhereSuggestsBoth", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}),
                Arguments.of("SELECT * FROM users u NATURAL JOIN orders o WHERE |", "naturalJoinWhereSuggestsBoth", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}),
                Arguments.of("SELECT * FROM users u CROSS JOIN orders o WHERE |", "crossJoinWhereSuggestsBoth", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id"}),
                Arguments.of("SELECT * FROM users NATURAL JOIN orders WHERE |", "naturalJoinWithoutAliasWhereSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date", "orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                // === Đợt gom mở rộng (round 4, xuyên suốt không cần cùng vị trí gốc) ===
                Arguments.of("select * from users u join orders o using (|)", "joinUsingColumnSuggestions", new String[]{"u.id", "o.id"}),
                Arguments.of("with c as (select id, name from users) select | from c", "cteColumnSuggestions", new String[]{"c.id", "c.name"}),
                Arguments.of("select | from (select id, name from users) sub", "subqueryInFromColumnSuggestions", new String[]{"sub.id", "sub.name"}),
                Arguments.of("select sub.| from (select id, name from users) sub", "danglingDotForSubqueryAliasSuggestsProjectedColumnsOnly", new String[]{"sub.id", "sub.name"}),
                Arguments.of("with c as (select id, name from users) select c.| from c", "danglingDotForCteSuggestsProjectedColumnsOnly", new String[]{"c.id", "c.name"}),
                Arguments.of("select * from users u where exists (select 1 from orders o where o.user_id = u.|)", "danglingDotForOuterAliasInsideCorrelatedSubquery", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("select count(*), | from orders group by status", "selectListMixedAggregateAndPlainColumnStillSuggestsColumns", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("select status, count(*) from orders group by status having |", "havingClauseColumnSuggestions", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("merge into users u using orders o on (u.id = o.user_id) when matched then update set |", "mergeUpdateSetColumnSuggestions", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("select (select | from orders o where o.user_id = u.id) from users u", "subqueryInSelectListColumnSuggestions", new String[]{"o.id", "o.customer_id", "o.total", "o.status", "o.user_id", "u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("with c1 as (select id from users), c2 as (select id, status from orders) select | from c1 join c2 on c1.id = c2.id", "multipleCtesColumnSuggestions", new String[]{"c1.id", "c2.id", "c2.status"}),
                Arguments.of("with c (col1, col2) as (select id, name from users) select | from c", "cteWithColumnListSuggestions", new String[]{"c.col1", "c.col2"}),
                Arguments.of("select * from users where id in (select | from orders)", "subqueryInInClauseColumnSuggestions", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id", "users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT NVL(name, |) FROM users", "nvlFunctionSecondArgSuggestsSomething", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT DECODE(status, 'A', 'Active', |) FROM orders", "decodeFunctionArgSuggestsSomething", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("ALTER TABLE users ADD CONSTRAINT pk PRIMARY KEY (|)", "alterTableAddConstraintColumnListSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT SUM(total) OVER (PARTITION BY |) FROM orders", "windowPartitionBySuggestsColumns", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("SELECT RANK() OVER (ORDER BY |) FROM orders", "windowOrderBySuggestsColumns", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("SELECT COALESCE(total, |) FROM orders", "coalesceSecondArgSuggestsSomething", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("SELECT NULLIF(status, |) FROM orders", "nullifSecondArgSuggestsSomething", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("SELECT GREATEST(total, |) FROM orders", "greatestSecondArgSuggestsSomething", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("SELECT LEAST(total, |) FROM orders", "leastSecondArgSuggestsSomething", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"}),
                Arguments.of("MERGE INTO users u USING orders o ON (u.id = o.user_id) WHEN NOT MATCHED THEN INSERT (|) VALUES (1, 'new')", "mergeInsertColumnListSuggestsTargetColumns", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("MERGE INTO users u USING orders o ON (u.id = o.user_id) WHEN MATCHED THEN UPDATE SET name = |", "mergeUpdateSetRhsSuggestsSourceColumns", new String[]{"o.id", "o.customer_id", "o.total", "o.status", "o.user_id", "u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("WITH c (col1) AS (SELECT id FROM users) SELECT | FROM c", "cteWithColumnAliasListSuggestsAlias", new String[]{"c.col1"}),
                Arguments.of("WITH RECURSIVE cte AS (SELECT id FROM users UNION ALL SELECT id FROM orders) SELECT | FROM cte", "recursiveCteSuggestsColumns", new String[]{"cte.id"}),
                Arguments.of("SELECT * FROM users WHERE id IN (SELECT | FROM orders)", "inSubquerySuggestsColumns", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id", "users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE |)", "existsSubquerySuggestsColumns", new String[]{"o.id", "o.customer_id", "o.total", "o.status", "o.user_id", "u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("SELECT * FROM users WHERE id = ANY (SELECT | FROM orders)", "anySubquerySuggestsColumns", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id", "users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users WHERE id > ALL (SELECT | FROM orders)", "allSubquerySuggestsColumns", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id", "users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users WHERE TRUNC(SYSDATE) = |", "truncFunctionDoesNotCrash", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT TO_CHAR(created_date, 'YYYY') FROM users WHERE |", "toCharThenWhereSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users WHERE EXTRACT(YEAR FROM created_date) = |", "extractFunctionDoesNotCrash", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("BEGIN FOR rec IN (SELECT * FROM users) LOOP DBMS_OUTPUT.PUT_LINE(rec.|); END LOOP; END;", "plsqlCursorLoopRecDotSuggestsColumns", new String[]{"rec.id", "rec.name", "rec.email", "rec.manager_id", "rec.created_date"}),
                Arguments.of("SELECT * FROM users UNPIVOT (value FOR column IN (|))", "unpivotInClauseSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users WHERE JSON_EXISTS(json_col, '$' |)", "jsonExistsDoesNotCrash", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT XMLELEMENT(\"user\", |) FROM users", "xmlElementSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT XMLAGG(XMLELEMENT(\"name\", name)) FROM users WHERE |", "xmlAggThenWhereSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users WHERE SOUNDEX(name) = SOUNDEX(|)", "soundexArgSuggestsSomething", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users WHERE CONTAINS(name, 'keyword', 1) > |", "containsFunctionDoesNotCrash", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users WHERE id NOT IN (SELECT | FROM orders)", "notInSubquerySuggestsColumns", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id", "users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users u WHERE id = (SELECT o.id FROM orders o WHERE |)", "correlatedSubquerySuggestsBoth", new String[]{"o.id", "o.customer_id", "o.total", "o.status", "o.user_id", "u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("SELECT * FROM users WHERE id IN (SELECT id FROM orders UNION SELECT | FROM products)", "unionInsideSubquerySuggestsColumns", new String[]{"products.id", "products.name", "products.price", "products.quantity", "products.description", "users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users WHERE id IN (SELECT id FROM orders MINUS SELECT | FROM products)", "minusInsideSubquerySuggestsColumns", new String[]{"products.id", "products.name", "products.price", "products.quantity", "products.description", "users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM (SELECT * FROM users) sub WHERE sub.|", "danglingDotOnSubqueryAliasSuggestsColumns", new String[]{"sub.id", "sub.name", "sub.email", "sub.manager_id", "sub.created_date"}),
                Arguments.of("SELECT u.id, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) FROM users u WHERE u.|", "scalarSubqueryAndWhereDanglingDot", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("SELECT * FROM users u WHERE u.id IN (SELECT o.user_id FROM orders o WHERE o.user_id = |)", "correlatedInSubqueryDanglingDot", new String[]{"o.id", "o.customer_id", "o.total", "o.status", "o.user_id", "u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("SELECT * FROM users WHERE id = (SELECT MAX(total) FROM orders WHERE |)", "scalarSubqueryWhereSuggestsColumns", new String[]{"orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id", "users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND |)", "existsAndContinuationSuggestsColumns", new String[]{"o.id", "o.customer_id", "o.total", "o.status", "o.user_id", "u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("SELECT * FROM users INNER JOIN orders USING (user_id) WHERE |", "innerJoinUsingWhereSuggestsColumns", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date", "orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id"})
        );
    }

    // === 4 cụm mới gom round 4 (xuyên vị trí, không cần cùng chỗ gốc) ===

    static Stream<Arguments> columnEmptyOnlyCases() {
        return Stream.of(
                Arguments.of("begin | := 1; end;", "assignmentTargetDoesNotSuggestColumns"),
                Arguments.of("insert into users values (|)", "insertValuesClauseDoesNotSuggestColumns"),
                Arguments.of("INSERT ALL INTO users (id, name) VALUES (1, 'a') INTO orders (id, total) VALUES (2, |) SELECT * FROM DUAL", "insertAllValuesDoesNotSuggestColumns"),
                Arguments.of("INSERT FIRST WHEN total > 100 THEN INTO orders_high VALUES (|) ELSE INTO orders_low VALUES (|) SELECT * FROM orders", "insertFirstValuesDoesNotCrash"),
                Arguments.of("SELECT * FROM users u, TABLE(orders) o WHERE o.|", "tableCollectionExpressionDoesNotCrash"),
                Arguments.of("SELECT * FROM orders PIVOT (COUNT(*) FOR status IN (|))", "pivotInClauseDoesNotCrash"),
                Arguments.of("SELECT * FROM users ORDER BY name NULLS FIRST |", "orderByNullsFirstDoesNotCrash")
        );
    }

    @ParameterizedTest(name = "[{index}] {1}: {0}")
    @MethodSource("columnEmptyOnlyCases")
    void columnEmptyOnlyCases(String sql, String caseName) {
        var result = suggest(sql);
        assertTrue(keysOfType(result, "column").isEmpty());
    }

    static Stream<Arguments> tablesAndViewsOnlyCases() {
        return Stream.of(
                Arguments.of("drop table |", "dropTableSuggestions"),
                Arguments.of("truncate table |", "truncateTableSuggestions"),
                Arguments.of("SELECT seq_name.NEXTVAL FROM |", "nextvalFromSuggestsTables"),
                Arguments.of("SELECT FIRST_VALUE(name) OVER (PARTITION BY dept ORDER BY hire_date ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) FROM |", "complexWindowFunctionAfterFromSuggestsTables")
        );
    }

    @ParameterizedTest(name = "[{index}] {1}: {0}")
    @MethodSource("tablesAndViewsOnlyCases")
    void tablesAndViewsOnlyCases(String sql, String caseName) {
        var result = suggest(sql);
        assertExactTables(result, "public.users", "public.orders", "public.contracts", "public.products");
        assertExactViews(result, "public.orders_summary");
    }

    static Stream<Arguments> columnsWithCountFunctionCases() {
        return Stream.of(
                Arguments.of("select * from users u where u.status = |", "comparisonRightHandSideAcceptsColumnReference", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date"}),
                Arguments.of("select | from users", "selectKeywordSuggestions", new String[]{"users.id", "users.name", "users.email", "users.manager_id", "users.created_date"}),
                Arguments.of("select * from users u where u.id = |", "comparisonRightHandSideGeneralSuggestions", new String[]{"u.id", "u.name", "u.email", "u.manager_id", "u.created_date"})
        );
    }

    @ParameterizedTest(name = "[{index}] {1}: {0}")
    @MethodSource("columnsWithCountFunctionCases")
    void columnsWithCountFunctionCases(String sql, String caseName, String[] expectedColumns) {
        var result = suggest(sql);
        assertExactColumns(result, expectedColumns);
        assertTrue(hasKeyOfType(result, "count", "function"));
    }

    static Stream<Arguments> columnEmptyWithCountFunctionCases() {
        return Stream.of(
                Arguments.of("select u.|", "missingFromDoesNotCrash"),
                Arguments.of("select |", "bareSelectDoesNotCrash"),
                Arguments.of("BEGIN IF | THEN NULL; END IF; END;", "plsqlIfConditionDoesNotCrash"),
                Arguments.of("SELECT * FROM (SELECT * FROM users) WHERE |", "subqueryWithoutAliasWhereSuggestsColumns")
        );
    }

    @ParameterizedTest(name = "[{index}] {1}: {0}")
    @MethodSource("columnEmptyWithCountFunctionCases")
    void columnEmptyWithCountFunctionCases(String sql, String caseName) {
        var result = suggest(sql);
        assertTrue(keysOfType(result, "column").isEmpty());
        assertTrue(hasKeyOfType(result, "count", "function"));
    }

    @ParameterizedTest(name = "[{index}] {1}: {0}")
    @MethodSource("columnOnlyCases")
    void columnOnlyCases(String sql, String caseName, String[] expectedColumns) {
        var result = suggest(sql);
        assertExactColumns(result, expectedColumns);
    }


    @Test
    @DisplayName("'create index idx1 on users (|)' - index_expr: column_name | expression - "
            + "table_index_clause đăng ký bảng ở exitTable_index_clause lên scope create_index đã "
            + "push từ enterCreate_index")
    void createIndexColumnSuggestions() {
        var result = suggest("create index idx1 on users (|)");
        assertExactColumns(result, "users.id", "users.name", "users.email", "users.manager_id", "users.created_date");
        assertExactTables(result);
    }

    @Test
    @DisplayName("CAST(id AS |) - type_spec sau AS trong biểu thức CAST, gợi ý kiểu dữ liệu thật "
            + "(không cần block PL/SQL hoàn chỉnh như DECLARE, an toàn hơn để test)")
    void castExpressionDataTypeSuggestions() {
        var result = suggest("select cast(id as |) from users");
        assertTrue(keysOfType(result, "datatype").containsAll(SchemaIndex.DATA_TYPES));
        assertTrue(keysOfType(result, "column").isEmpty());
    }

    @Test
    @DisplayName("ORDER BY name | - order_by_elements dùng 'expression' (KHÔNG phải column_name - "
            + "khác với UPDATE SET/JOIN USING/INSERT column-list) nên vẫn đi qua general_element, "
            + "sau đó chờ ASC/DESC/NULLS - kiểm tra keyword ASC/DESC vẫn gợi ý được")
    void orderByAscDescKeywordSuggestions() {
        var result = suggest("select * from users order by name |");
        assertTrue(allKeywordKeys(result).contains("asc"));
        assertTrue(allKeywordKeys(result).contains("desc"));
    }

    @Test
    @DisplayName("PHỦ ĐỊNH: 'select * from users where id = 1' (KHÔNG có caret ở vùng liên quan) "
            + "- gợi ý tại vị trí ngay sau 'FROM' của 1 câu ĐÃ HOÀN CHỈNH đứng trước, đảm bảo scope "
            + "của statement trước không rò rỉ gợi ý cột sang statement sau nếu có nhiều statement")
    void secondStatementDoesNotSeeFirstStatementAliases() {
        var result = suggest("select * from users u where u.id = 1; select * from |");
        assertExactTables(result, "public.users", "public.orders", "public.contracts", "public.products");
        assertExactViews(result, "public.orders_summary");
        assertExactColumns(result);
    }

    @Test
    @DisplayName("'select | from users union select | from orders' (2 vị trí caret riêng biệt, "
            + "test bằng 2 lời gọi khác nhau) - mỗi vế UNION có scope riêng, vế sau KHÔNG được "
            + "thấy alias/cột của vế trước dù cùng 1 statement UNION")
    void unionBranchesHaveIndependentScopes() {
        var firstBranch = suggest("select | from users union select id from orders");
        assertExactColumns(firstBranch, "users.id", "users.name", "users.email", "users.manager_id", "users.created_date");

        var secondBranch = suggest("select id from users union select | from orders");
        assertExactColumns(secondBranch, "orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id");
    }


    @Test
    @DisplayName("ROBUSTNESS: gõ dở giữa chừng 1 subquery chưa đóng ngoặc "
            + "'select * from (select id from users |' - PHẢI không crash; vì thiếu CLOSE_PAREN, "
            + "scope subquery coi như MỞ tới hết input (đúng BUG FIX đã nói ở popScope) thay vì "
            + "đóng non và mất alias")
    void unclosedSubqueryParenDoesNotCrash() {
        var result = suggest("select * from (select id, name from users |");
        assertTrue(keysOfType(result, "alias").contains("u"));
    }


    // =====================================================================
    // Các test bổ sung
    // =====================================================================

    @Test
    @DisplayName("'select | from users' - gợi ý cả cột và hàm (count, sum, ...)")
    void selectListFunctionSuggestions() {
        var result = suggest("select | from users");
        assertExactColumns(result, "users.id", "users.name", "users.email", "users.manager_id", "users.created_date");
        // Có hàm (từ SchemaIndex.FUNCTIONS)
        assertTrue(hasKeyOfType(result, "count", "function"));
        assertTrue(hasKeyOfType(result, "sum", "function"));
        assertTrue(hasKeyOfType(result, "avg", "function"));
    }


    @Test
    @DisplayName("'alter table users add column new_col |' - sau định nghĩa cột, CHỈ gợi ý kiểu dữ "
            + "liệu, KHÔNG lẫn cột bảng (đã sửa bug: trước đây lẫn cả users.* - xem "
            + "isAlterColumnAwaitingDatatype trong suggests/oracle/CompletionEngine.java)")
    void alterTableAddColumnDataTypeSuggestions() {
        var result = suggest("alter table users add new_col |");
        var datatypes = keysOfType(result, "datatype");
        assertTrue(datatypes.contains("int4"));
        assertTrue(datatypes.contains("text"));
        assertTrue(datatypes.contains("numeric"));
        assertExactColumns(result);
    }

    @Test
    @DisplayName("'alter table users modify column email |' - MODIFY cột gợi ý kiểu dữ liệu (có thể "
            + "NULL/NOT NULL nhưng ta chỉ test datatype), KHÔNG lẫn cột bảng (đã sửa bug tương tự "
            + "alterTableAddColumnDataTypeSuggestions)")
    void alterTableModifyColumnDataTypeSuggestions() {
        var result = suggest("alter table users modify email |");
        assertTrue(keysOfType(result, "datatype").containsAll(SchemaIndex.DATA_TYPES));
        assertTrue(allKeywordKeys(result).contains("not"));
        assertTrue(allKeywordKeys(result).contains("null"));
        assertExactColumns(result);
    }

    @Test
    @DisplayName("'SELECT * FROM users CONNECT BY |' - after CONNECT BY, suggests keyword PRIOR and columns")
    void connectBySuggestsPriorKeywordAndColumns() {
        var result = suggest("SELECT * FROM users CONNECT BY |");
        assertTrue(allKeywordKeys(result).contains("prior"));
        assertExactColumns(result, "users.id", "users.name", "users.email", "users.manager_id", "users.created_date");
    }

    @Test
    @DisplayName("'SELECT * FROM users FOR |' - FOR keyword suggests UPDATE from keyword list")
    void forClauseSuggestsUpdateKeyword() {
        var result = suggest("SELECT * FROM users FOR |");
        assertTrue(allKeywordKeys(result).contains("update"));
        assertTrue(keysOfType(result, "column").isEmpty());
    }


    // =====================================================================
    // Additional test cases - Set operations, CASE, analytic functions,
    // DDL, DCL, PL/SQL, advanced features, etc.
    // =====================================================================


    @Test
    @DisplayName("'SELECT SUM(total) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND |) FROM orders' - windowing clause ROWS/RANGE suggests keywords CURRENT ROW, etc.")
    void windowFrameBoundSuggestsKeywordsAndColumns() {
        var result = suggest("SELECT SUM(total) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND |) FROM orders");
        assertTrue(allKeywordKeys(result).contains("current row"));
        assertExactColumns(result, "orders.id", "orders.customer_id", "orders.total", "orders.status", "orders.user_id");
    }

    // GOM (parameterized) - 5 test "DoesNotCrash" trước đây tách riêng, cùng hình dạng:
    // vị trí không nên gợi ý cột nào, nhưng vẫn có ít nhất 1 keyword khác (không rỗng hoàn toàn).
    static Stream<Arguments> noColumnButHasKeywordCases() {
        return Stream.of(
                Arguments.of("COMMENT ON TABLE users IS |", "commentOnTableDoesNotCrash"),
                Arguments.of("GRANT SELECT ON users TO |", "grantToDoesNotCrash"),
                Arguments.of("REVOKE SELECT ON users FROM |", "revokeFromDoesNotCrash"),
                Arguments.of("ANALYZE TABLE users |", "analyzeTableDoesNotCrash"),
                Arguments.of("SELECT * FROM users WHERE CURRENT OF |", "whereCurrentOfDoesNotCrash")
        );
    }

    @ParameterizedTest(name = "[{index}] {1}: {0}")
    @MethodSource("noColumnButHasKeywordCases")
    void noColumnButHasKeywordCases(String sql, String caseName) {
        var result = suggest(sql);
        assertTrue(keysOfType(result, "column").isEmpty());
        assertFalse(allKeywordKeys(result).isEmpty());
    }

    @Test
    @DisplayName("'TRUNCATE TABLE users DROP STORAGE' - with storage clause does not crash")
    void truncateTableWithStorageDoesNotCrash() {
        var result = suggest("TRUNCATE TABLE users DROP |");
        assertExactColumns(result);
        assertExactTables(result);
    }

    @Test
    @DisplayName("'ALTER INDEX idx_name REBUILD |' - ALTER INDEX suggests ONLINE/PARALLEL keywords")
    void alterIndexRebuildSuggestsKeywords() {
        var result = suggest("ALTER INDEX idx_name REBUILD |");
        assertTrue(allKeywordKeys(result).contains("online"));
        assertTrue(allKeywordKeys(result).contains("parallel"));
        assertTrue(keysOfType(result, "column").isEmpty());
    }

    @Test
    @DisplayName("'CREATE SEQUENCE seq_name START WITH |' - START WITH value, not crash")
    void createSequenceStartWithDoesNotCrash() {
        var result = suggest("CREATE SEQUENCE seq_name START WITH |");
        assertExactColumns(result);
        assertExactTables(result);
    }

    @Test
    @DisplayName("'DECLARE v_name users.name%TYPE; BEGIN SELECT name INTO v_name FROM users WHERE id=1; | END;' - variable assignment after SELECT suggests columns? (not crash)")
    void plsqlVariableAssignmentDoesNotCrash() {
        var result = suggest("DECLARE v_name users.name%TYPE; BEGIN SELECT name INTO v_name FROM users WHERE id=1; | END;");
        assertTrue(allKeywordKeys(result).contains("select"));
        assertTrue(allKeywordKeys(result).contains("if"));
        assertTrue(keysOfType(result, "column").isEmpty());
    }

    @Test
    @DisplayName("'SELECT * FROM users WHERE JSON_VALUE(json_col, '$.name' RETURNING VARCHAR2 |)' - "
            + "JSON_VALUE returning type suggests ONLY datatypes, KHÔNG lẫn cột (đã sửa bug: trước "
            + "đây lẫn cả users.* - xem isGeneralElementJsonReturnType trong "
            + "suggests/oracle/CompletionEngine.java)")
    void jsonValueReturningSuggestsDatatypes() {
        var result = suggest("SELECT * FROM users WHERE JSON_VALUE(json_col, '$.name' RETURNING VARCHAR2 |)");
        assertTrue(keysOfType(result, "datatype").containsAll(SchemaIndex.DATA_TYPES));
        assertExactColumns(result);
    }

    @Test
    @DisplayName("'SELECT * FROM XMLTABLE('/root/row' PASSING xml_col COLUMNS id INT PATH '@id', name VARCHAR2 |)' - XMLTABLE column type suggests datatypes")
    void xmlTableColumnTypeSuggestsDatatypes() {
        var result = suggest("SELECT * FROM XMLTABLE('/root/row' PASSING xml_col COLUMNS id INT, name |)");
        assertTrue(keysOfType(result, "datatype").containsAll(SchemaIndex.DATA_TYPES));
    }
}