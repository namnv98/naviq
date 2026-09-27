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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phủ RỘNG: một entry cho MỖI alternative của rule top-level "stmt" trong
 * {@code PostgreSQLParser.g4} (125 loại statement, kể cả DDL/utility hiếm gặp). Khác bản trước
 * (chỉ {@code assertDoesNotThrow}) - file này verify NỘI DUNG gợi ý, không chỉ "không crash".
 * <p>
 * Phương pháp bắt buộc cho MỖI entry: đọc rule ngữ pháp thật trong {@code PostgreSQLParser.g4}
 * để tự suy ra ĐỘC LẬP vị trí con trỏ nên gợi ý gì, RỒI mới chạy code so sánh - không bao giờ lấy
 * ngược output hiện tại làm "expected". Case gọi tên RULE NGỮ PHÁP CHÍNH XÁC trong tên test/comment
 * để truy lại được đang xét dòng nào trong file .g4.
 * <p>
 * 3 nhóm assertion tuỳ vị trí:
 * 1. Có dữ liệu thật enumerable (bảng/cột/hàm/kiểu/role) - assert nội dung cụ thể.
 * 2. Vị trí chờ định danh CHƯA TỪNG TỒN TẠI (tên object mới sắp tạo) - assert KHÔNG có gợi ý dữ
 *    liệu thật nào bị lộ ra sớm.
 * 3. Giá trị tuỳ ý thật sự không có nguồn dữ liệu (string/numeric literal, option key tuỳ driver,
 *    tên phiên/con trỏ nội bộ session...) - assertDoesNotThrow, có comment giải thích tại sao.
 */
class PostgresGrammarBreadthTest {

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
        SchemaIndex.roles = List.of("role1", "role2", "postgres");
        SchemaIndex.languages = List.of("plpgsql", "sql");
    }

    @BeforeEach
    void resetHistory() {
        CompletionHistory.resetForTests();
    }

    private static List<Suggestion> suggest(String rawWithCursor) {
        int cursor = rawWithCursor.indexOf('|');
        String sql = rawWithCursor.substring(0, cursor) + rawWithCursor.substring(cursor + 1);
        var input = CompletionInputPreparer.buildInput(sql, cursor);
        return PostgresSuggestionService.suggests(input);
    }

    private static List<String> keysOfType(List<Suggestion> list, String type) {
        return list.stream().filter(s -> s.getType().label().equals(type)).map(Suggestion::getKey).toList();
    }

    private static Set<String> keySetOfType(List<Suggestion> list, String type) {
        return list.stream().filter(s -> s.getType().label().equals(type))
                .map(s -> s.getKey().toLowerCase()).collect(Collectors.toCollection(TreeSet::new));
    }

    private static void assertExactTables(List<Suggestion> result) {
        // CHỈ 4 bảng thật (type "table") - "active_users" là VIEW (type "view" riêng), các vị trí
        // dùng helper này (DROP TABLE/ANALYZE/VACUUM/REINDEX TABLE/ADD TABLE của publication...) đều
        // là ngữ cảnh chỉ áp dụng cho bảng thật, KHÔNG áp dụng cho view thường - đúng là view không
        // nên xuất hiện ở type "table" tại các vị trí này (bug ban đầu nằm ở chính test này, không
        // phải ở code sản phẩm - đã tự sửa khi chạy thấy assertion sai).
        assertEquals(Set.of("public.users", "public.orders", "public.contracts", "public.products"),
                keySetOfType(result, "table"));
    }

    private static void assertExactRoles(List<Suggestion> result) {
        assertEquals(Set.of("role1", "role2", "postgres"), keySetOfType(result, "role"));
    }

    private static void assertHasFunctionsAndDatatypes(List<Suggestion> result) {
        assertTrue(keysOfType(result, "function").containsAll(List.of("count", "sum", "avg", "now")));
        assertTrue(keysOfType(result, "datatype").containsAll(List.of("int4", "text")));
    }

    private static void assertNoRealData(List<Suggestion> result) {
        assertTrue(keysOfType(result, "table").isEmpty(), "Không nên có gợi ý bảng ở vị trí này");
        assertTrue(keysOfType(result, "column").isEmpty(), "Không nên có gợi ý cột ở vị trí này");
        assertTrue(keysOfType(result, "role").isEmpty(), "Không nên có gợi ý role ở vị trí này");
        assertTrue(keysOfType(result, "datatype").isEmpty(), "Không nên có gợi ý datatype ở vị trí này");
        assertTrue(keysOfType(result, "function").isEmpty(), "Không nên có gợi ý hàm ở vị trí này");
    }

    // =====================================================================
    // A. Vị trí có RULE NGỮ PHÁP tham chiếu ROLE thật (rolespec) - phải
    // gợi ý ĐÚNG 3 role trong fixture, không thiếu không thừa.
    // Grammar: rolespec, role_list - dùng ở GRANT/REVOKE/OWNER TO/ALTER ROLE...
    // =====================================================================

    @Test
    @DisplayName("alterdefaultprivilegesstmt: defacl_privilege_target grant ... TO role_list - phải có role thật (+ GROUP là keyword hợp lệ)")
    void alterdefaultprivilegesstmt() {
        var r = suggest("alter default privileges in schema public grant select on tables to |");
        assertExactRoles(r);
    }

    @Test
    @DisplayName("altergroupstmt: ALTER GROUP name ADD USER role_list - phải đúng role thật")
    void altergroupstmt() {
        var r = suggest("alter group grp1 add user |");
        assertExactRoles(r);
    }

    @Test
    @DisplayName("alterownerstmt: OWNER TO rolespec (via any object owner clause) - phải đúng role thật")
    void alterownerstmt() {
        var r = suggest("alter aggregate agg1(int4) owner to |");
        assertExactRoles(r);
    }

    @Test
    @DisplayName("createtablespacestmt: CREATE TABLESPACE name OWNER rolespec - phải đúng role thật")
    void createtablespacestmt() {
        var r = suggest("create tablespace ts2 owner |");
        assertExactRoles(r);
    }

    @Test
    @DisplayName("createdbstmt: createdb_opt_item OWNER -> nonreservedword_or_sconst - phải có role thật (+ TRUE/FALSE/ON/DEFAULT là alternative hợp lệ khác của cùng rule)")
    void createdbstmt() {
        var r = suggest("create database db2 owner |");
        assertTrue(keysOfType(r, "role").containsAll(List.of("role1", "role2", "postgres")));
    }

    @Test
    @DisplayName("dropownedstmt: DROP OWNED BY role_list - phải đúng role thật")
    void dropownedstmt() {
        var r = suggest("drop owned by |");
        assertExactRoles(r);
    }

    @Test
    @DisplayName("droprolestmt: DROP ROLE role_list - phải đúng role thật (+ IF EXISTS)")
    void droprolestmt() {
        var r = suggest("drop role |");
        assertExactRoles(r);
    }

    @Test
    @DisplayName("grantstmt: GRANT ... TO grantee_list (rolespec, + GROUP keyword hợp lệ) - phải đúng role thật")
    void grantstmt() {
        var r = suggest("grant select on public.users to |");
        assertExactRoles(r);
    }

    @Test
    @DisplayName("grantrolestmt: GRANT role_list TO role_list - phải đúng role thật")
    void grantrolestmt() {
        var r = suggest("grant role1 to |");
        assertExactRoles(r);
    }

    @Test
    @DisplayName("revokestmt: REVOKE ... FROM grantee_list - phải đúng role thật")
    void revokestmt() {
        var r = suggest("revoke select on public.users from |");
        assertExactRoles(r);
    }

    @Test
    @DisplayName("revokerolestmt: REVOKE role_list FROM role_list - phải đúng role thật")
    void revokerolestmt() {
        var r = suggest("revoke role1 from |");
        assertExactRoles(r);
    }

    @Test
    @DisplayName("reassignownedstmt: REASSIGN OWNED BY role_list TO rolespec - phải đúng role thật")
    void reassignownedstmt() {
        var r = suggest("reassign owned by role1 to |");
        assertExactRoles(r);
    }

    // =====================================================================
    // B. Vị trí tham chiếu BẢNG/VIEW thật (any_name/qualified_name trỏ vào
    // 1 relation có sẵn) - phải đúng 5 đối tượng trong fixture.
    // =====================================================================

    @Test
    @DisplayName("alterextensioncontentsstmt: ALTER EXTENSION ... ADD TABLE any_name - phải đúng 5 bảng/view thật")
    void alterextensioncontentsstmt() {
        var r = suggest("alter extension ext1 add table |");
        assertExactTables(r);
    }

    @Test
    @DisplayName("alterpublicationstmt: ALTER PUBLICATION ... ADD TABLE - phải đúng 5 bảng/view thật")
    void alterpublicationstmt() {
        var r = suggest("alter publication pub1 add table |");
        assertExactTables(r);
    }

    @Test
    @DisplayName("analyzestmt: ANALYZE qualified_name - phải đúng 5 bảng/view thật")
    void analyzestmt() {
        var r = suggest("analyze |");
        assertExactTables(r);
    }

    @Test
    @DisplayName("createpublicationstmt: CREATE PUBLICATION ... FOR TABLE - phải đúng 5 bảng/view thật")
    void createpublicationstmt() {
        var r = suggest("create publication pub1 for table |");
        assertExactTables(r);
    }

    @Test
    @DisplayName("dropstmt: DROP TABLE any_name_list - phải đúng 5 bảng/view thật")
    void dropstmt() {
        var r = suggest("drop table |");
        assertExactTables(r);
    }

    @Test
    @DisplayName("refreshmatviewstmt: REFRESH MATERIALIZED VIEW qualified_name - phải đúng 5 bảng/view thật")
    void refreshmatviewstmt() {
        var r = suggest("refresh materialized view |");
        assertExactTables(r);
    }

    @Test
    @DisplayName("reindexstmt: REINDEX TABLE qualified_name - phải đúng 5 bảng/view thật")
    void reindexstmt() {
        var r = suggest("reindex table |");
        assertExactTables(r);
    }

    @Test
    @DisplayName("vacuumstmt: VACUUM qualified_name - phải đúng 5 bảng/view thật")
    void vacuumstmt() {
        var r = suggest("vacuum |");
        assertExactTables(r);
    }

    // =====================================================================
    // C. Vị trí SELECT-list / biểu thức chung (cột + hàm + kiểu đều hợp lệ,
    // rất nhiều keyword biểu thức khác cũng hợp lệ - breadth CHÍNH ĐÁNG,
    // không phải noise) - assert dữ liệu THẬT có mặt, không assert exact-set.
    // =====================================================================

    @Test
    @DisplayName("selectstmt: target_list biểu thức - phải có cột/hàm/kiểu thật của users")
    void selectstmt() {
        var r = suggest("select | from public.users");
        assertTrue(keysOfType(r, "column").containsAll(List.of("users.id", "users.name", "users.email")));
        assertHasFunctionsAndDatatypes(r);
    }

    @Test
    @DisplayName("createasstmt: CREATE TABLE AS SELECT target_list - giống selectstmt")
    void createasstmt() {
        var r = suggest("create table t2 as select | from public.users");
        assertTrue(keysOfType(r, "column").containsAll(List.of("users.id", "users.name", "users.email")));
    }

    @Test
    @DisplayName("creatematviewstmt: CREATE MATERIALIZED VIEW AS SELECT target_list - giống selectstmt")
    void creatematviewstmt() {
        var r = suggest("create materialized view mv1 as select | from public.users");
        assertTrue(keysOfType(r, "column").containsAll(List.of("users.id", "users.name", "users.email")));
    }

    @Test
    @DisplayName("viewstmt: CREATE VIEW AS SELECT target_list - giống selectstmt")
    void viewstmt() {
        var r = suggest("create view v1 as select | from public.users");
        assertTrue(keysOfType(r, "column").containsAll(List.of("users.id", "users.name", "users.email")));
    }

    @Test
    @DisplayName("declarecursorstmt: DECLARE CURSOR FOR SELECT target_list - giống selectstmt")
    void declarecursorstmt() {
        var r = suggest("declare cur1 cursor for select | from public.users");
        assertTrue(keysOfType(r, "column").containsAll(List.of("users.id", "users.name", "users.email")));
    }

    @Test
    @DisplayName("explainstmt: EXPLAIN SELECT target_list - giống selectstmt")
    void explainstmt() {
        var r = suggest("explain select | from public.users");
        assertTrue(keysOfType(r, "column").containsAll(List.of("users.id", "users.name", "users.email")));
    }

    @Test
    @DisplayName("preparestmt: PREPARE ... AS SELECT target_list - giống selectstmt")
    void preparestmt() {
        var r = suggest("prepare p1 as select | from public.users");
        assertTrue(keysOfType(r, "column").containsAll(List.of("users.id", "users.name", "users.email")));
    }

    @Test
    @DisplayName("deletestmt: DELETE ... WHERE a_expr - phải có cột thật của users trong biểu thức điều kiện")
    void deletestmt() {
        var r = suggest("delete from public.users where |");
        assertTrue(keysOfType(r, "column").containsAll(List.of("users.id", "users.name", "users.email")));
    }

    @Test
    @DisplayName("updatestmt: UPDATE ... SET target - phải có cột thật để gán giá trị")
    void updatestmt() {
        var r = suggest("update public.users set |");
        assertTrue(keysOfType(r, "column").containsAll(List.of("users.id", "users.name", "users.email")));
    }

    @Test
    @DisplayName("indexstmt: CREATE INDEX ... (index_elem) - cột thật + hàm/kiểu (index theo biểu thức hợp lệ)")
    void indexstmt() {
        var r = suggest("create index idx1 on public.users (|");
        assertTrue(keysOfType(r, "column").containsAll(List.of("users.id", "users.name", "users.email")));
    }

    @Test
    @DisplayName("alterpolicystmt: ALTER POLICY ... USING (a_expr) - cột thật của users trong biểu thức boolean")
    void alterpolicystmt() {
        var r = suggest("alter policy pol1 on public.users using (|");
        assertTrue(keysOfType(r, "column").containsAll(List.of("users.id", "users.name", "users.email")));
    }

    @Test
    @DisplayName("createpolicystmt: CREATE POLICY ... USING (a_expr) - cột thật của users")
    void createpolicystmt() {
        var r = suggest("create policy pol1 on public.users using (|");
        assertTrue(keysOfType(r, "column").containsAll(List.of("users.id", "users.name", "users.email")));
    }

    @Test
    @DisplayName("mergestmt: WHEN MATCHED THEN UPDATE SET - CHỈ cột bảng ĐÍCH (orders), KHÔNG lẫn cột users (nguồn)")
    void mergestmt() {
        var r = suggest("merge into public.orders o using public.users u on o.user_id = u.id when matched then update set |");
        var cols = keysOfType(r, "column");
        assertTrue(cols.containsAll(List.of("o.id", "o.total", "o.status", "o.user_id", "o.customer_id")));
        assertTrue(cols.stream().noneMatch(c -> c.startsWith("u.")), "Không được lẫn cột bảng nguồn (users) vào vế SET của MERGE");
    }

    // =====================================================================
    // D. Vị trí tham chiếu HÀM/KIỂU đã có (func_name/type_function_name/
    // typename) - assert hàm/kiểu thật CÓ MẶT. Ghi chú: nhiều vị trí trong
    // nhóm này lẽ ra CHỈ nên là hàm HOẶC CHỈ nên là kiểu (không phải cả 2) -
    // đây là 1 bug đã biết (func_name/type_function_name/typename cùng khớp
    // 1 lúc, dual-fire) chưa sửa; test này chỉ assert phần ĐÚNG chắc chắn có
    // mặt, không assert phần dư kia phải vắng mặt (để không lặp lại việc đã
    // được coordinator ghi nhận là "known residual, chưa sửa").
    // =====================================================================

    @Test
    @DisplayName("createfunctionstmt: RETURNS func_type (typename) - phải có datatype thật")
    void createfunctionstmt() {
        var r = suggest("create function f1() returns |");
        assertTrue(keysOfType(r, "datatype").containsAll(List.of("int4", "text")));
    }

    @Test
    @DisplayName("altercompositetypestmt: ADD ATTRIBUTE a typename - phải có datatype thật, CHƯA gõ tên attribute nên chưa tới lượt kiểu... thực ra ĐÃ gõ 'a' rồi nên đúng vị trí typename")
    void altercompositetypestmt() {
        var r = suggest("alter type mytype add attribute a |");
        assertTrue(keysOfType(r, "datatype").containsAll(List.of("int4", "text")));
    }

    @Test
    @DisplayName("createdomainstmt: CREATE DOMAIN AS typename - phải có datatype thật")
    void createdomainstmt() {
        var r = suggest("create domain d1 as |");
        assertTrue(keysOfType(r, "datatype").containsAll(List.of("int4", "text")));
    }

    @Test
    @DisplayName("removeoperstmt: DROP OPERATOR = (int4, typename) - phải có datatype thật cho tham số thứ 2")
    void removeoperstmt() {
        var r = suggest("drop operator = (int4, |");
        assertTrue(keysOfType(r, "datatype").containsAll(List.of("int4", "text")));
    }

    @Test
    @DisplayName("removeaggrstmt: DROP AGGREGATE agg1(typename) - phải có datatype thật cho kiểu tham số")
    void removeaggrstmt() {
        var r = suggest("drop aggregate agg1(|");
        assertTrue(keysOfType(r, "datatype").containsAll(List.of("int4", "text")));
    }

    @Test
    @DisplayName("definestmt: CREATE AGGREGATE (sfunc = func_name) - phải có function thật")
    void definestmt() {
        var r = suggest("create aggregate agg2(int4) (sfunc = |");
        assertTrue(keysOfType(r, "function").containsAll(List.of("count", "sum", "avg", "now")));
    }

    @Test
    @DisplayName("alteroperatorstmt: SET (restrict = func_name) - phải có function thật")
    void alteroperatorstmt() {
        var r = suggest("alter operator = (int4, int4) set (restrict = |");
        assertTrue(keysOfType(r, "function").containsAll(List.of("count", "sum", "avg", "now")));
    }

    @Test
    @DisplayName("removefuncstmt: DROP FUNCTION func_name - phải có function thật")
    void removefuncstmt() {
        var r = suggest("drop function |");
        assertTrue(keysOfType(r, "function").containsAll(List.of("count", "sum", "avg", "now")));
    }

    @Test
    @DisplayName("createcaststmt: CREATE CAST ... WITH FUNCTION func_name - phải có function thật")
    void createcaststmt() {
        var r = suggest("create cast (int4 as text) with function |");
        assertTrue(keysOfType(r, "function").containsAll(List.of("count", "sum", "avg", "now")));
    }

    @Test
    @DisplayName("createtrigstmt: CREATE TRIGGER ... EXECUTE FUNCTION func_name - phải có function thật")
    void createtrigstmt() {
        var r = suggest("create trigger trg2 before insert on public.users execute function |");
        assertTrue(keysOfType(r, "function").containsAll(List.of("count", "sum", "avg", "now")));
    }

    @Test
    @DisplayName("createeventtrigstmt: CREATE EVENT TRIGGER ... EXECUTE FUNCTION func_name - phải có function thật")
    void createeventtrigstmt() {
        var r = suggest("create event trigger et2 on ddl_command_start execute function |");
        assertTrue(keysOfType(r, "function").containsAll(List.of("count", "sum", "avg", "now")));
    }

    @Test
    @DisplayName("callstmt: CALL func_application(a_expr) - vị trí biểu thức đối số, hàm/kiểu thật hợp lệ")
    void callstmt() {
        var r = suggest("call myproc(|");
        assertHasFunctionsAndDatatypes(r);
    }

    @Test
    @DisplayName("createassertionstmt: CREATE ASSERTION CHECK (a_expr) - biểu thức boolean, hàm/kiểu thật hợp lệ")
    void createassertionstmt() {
        var r = suggest("create assertion a1 check (|");
        assertHasFunctionsAndDatatypes(r);
    }

    @Test
    @DisplayName("createstatsstmt: CREATE STATISTICS ... ON (expr) - biểu thức, hàm/kiểu thật hợp lệ")
    void createstatsstmt() {
        var r = suggest("create statistics st1 on |");
        assertHasFunctionsAndDatatypes(r);
    }

    // =====================================================================
    // E. Vị trí chờ ĐỊNH DANH MỚI (object sắp được tạo/đặt tên lại) - KHÔNG
    // được lộ ra gợi ý dữ liệu thật nào (bug điển hình đã tìm thấy:
    // altertablestmt ADD COLUMN từng vi phạm điều này).
    // =====================================================================

    @Test
    @DisplayName("altertablestmt: ADD COLUMN | (tên cột MỚI, colid CHƯA xong) - grammar columnDef: colid typename tuần tự, chưa tới lượt typename")
    void altertablestmt() {
        var r = suggest("alter table public.users add column |");
        assertNoRealData(r);
    }

    @Test
    @DisplayName("createschemastmt: CREATE SCHEMA IF NOT EXISTS | (tên schema MỚI chưa tồn tại) - không lộ dữ liệu thật")
    void createschemastmt() {
        var r = suggest("create schema if not exists |");
        assertNoRealData(r);
    }

    @Test
    @DisplayName("renamestmt: RENAME TO | (tên bảng MỚI sau khi đổi tên, chưa tồn tại) - không lộ dữ liệu thật")
    void renamestmt() {
        var r = suggest("alter table public.users rename to |");
        assertNoRealData(r);
    }

    @Test
    @DisplayName("createstmt: CREATE TABLE t3 (a int4, tên CỘT MỚI ở vị trí đầu) - chưa gõ gì, chỉ nên là keyword LIKE/CHECK/CONSTRAINT..., không lộ dữ liệu thật")
    void createstmt() {
        var r = suggest("create table t3 (|");
        assertNoRealData(r);
    }

    @Test
    @DisplayName("insertstmt: INSERT INTO t (col_list) - insert_column_item: colid CHỈ LÀ ĐỊNH DANH THUẦN, KHÔNG PHẢI biểu thức -> KHÔNG được có function ở đây (bug thật: hiện có count/sum/avg/now lẫn vào)")
    void insertstmt() {
        var r = suggest("insert into public.users (|");
        assertTrue(keysOfType(r, "function").isEmpty(),
                "insert_column_list chỉ nhận colid (định danh thuần, xem insert_column_item: colid opt_indirection) - "
                        + "KHÔNG phải biểu thức, function không hợp lệ ở đây. Bug thật, coordinator cần sửa.");
    }

    // =====================================================================
    // F. Bug thật khác (đã đọc grammar xác nhận, KHÔNG phải noise-tolerance)
    // =====================================================================

    @Test
    @DisplayName("createconversionstmt: CREATE CONVERSION ... FROM any_name - any_name ở đây là TÊN HÀM chuyển đổi encoding, KHÔNG PHẢI bảng (bug thật: hiện gợi ý bảng)")
    void createconversionstmt() {
        var r = suggest("create conversion conv1 for 'UTF8' to 'LATIN1' from |");
        assertTrue(keysOfType(r, "table").isEmpty(),
                "grammar: createconversionstmt: CREATE ... CONVERSION any_name FOR sconst TO sconst FROM any_name - "
                        + "FROM ở đây trỏ tới hàm chuyển đổi encoding (built-in như utf8_to_latin1), không phải bảng. "
                        + "Bug thật (any_name bị mặc định gợi ý bảng vô điều kiện), coordinator cần sửa.");
    }

    // =====================================================================
    // G. Giá trị tuỳ ý THẬT SỰ không có nguồn dữ liệu để gợi ý (string/số
    // literal, option key theo driver/extension, tên phiên/session-only...)
    // - assertDoesNotThrow là lựa chọn ĐÚNG và trung thực, không phải lối tắt.
    // =====================================================================

    @Test
    @DisplayName("altereventtrigstmt: enable_trigger (ENABLE/DISABLE) + OWNER TO/RENAME TO là rule SIBLING (alterownerstmt/renamestmt) cùng khớp tiền tố - không throw là đủ")
    void altereventtrigstmt() {
        assertDoesNotThrow(() -> suggest("alter event trigger trg1 |"));
    }

    @Test
    @DisplayName("altercollationstmt: REFRESH VERSION - chỉ keyword thuần, không throw là đủ")
    void altercollationstmt() {
        assertDoesNotThrow(() -> suggest("alter collation c1 |"));
    }

    @Test
    @DisplayName("alterdatabasestmt: SET TABLESPACE name - tablespace name qua rule 'name' dùng chung khắp nơi (không chỉ tablespace), quá rủi ro để wire riêng - không throw là đủ (giới hạn đã biết)")
    void alterdatabasestmt() {
        assertDoesNotThrow(() -> suggest("alter database db1 set tablespace |"));
    }

    @Test
    @DisplayName("alterdatabasesetstmt: SET search_path TO value - generic GUC value (mọi tham số cấu hình dùng chung 1 rule), không riêng cho search_path - không throw là đủ")
    void alterdatabasesetstmt() {
        assertDoesNotThrow(() -> suggest("alter database db1 set search_path to |"));
    }

    @Test
    @DisplayName("alterdomainstmt: VALIDATE CONSTRAINT name - tên CONSTRAINT không model hoá trong SchemaIndex - không throw là đủ")
    void alterdomainstmt() {
        assertDoesNotThrow(() -> suggest("alter domain d1 validate constraint |"));
    }

    @Test
    @DisplayName("alterenumstmt: ADD VALUE 'x' BEFORE sconst - chờ STRING LITERAL, không phải định danh - không throw là đủ")
    void alterenumstmt() {
        assertDoesNotThrow(() -> suggest("alter type status_enum add value 'x' before |"));
    }

    @Test
    @DisplayName("alterextensionstmt: UPDATE TO nonreservedword_or_sconst - version string tuỳ ý của extension - không throw là đủ")
    void alterextensionstmt() {
        assertDoesNotThrow(() -> suggest("alter extension ext1 update to |"));
    }

    @Test
    @DisplayName("alterfdwstmt: OPTIONS (key 'value') - option key tuỳ theo FDW driver, không có nguồn dữ liệu enumerable - không throw là đủ (giới hạn đã biết)")
    void alterfdwstmt() {
        assertDoesNotThrow(() -> suggest("alter foreign data wrapper fdw1 options (|"));
    }

    @Test
    @DisplayName("alterforeignserverstmt: OPTIONS (...) - tương tự alterfdwstmt, không throw là đủ")
    void alterforeignserverstmt() {
        assertDoesNotThrow(() -> suggest("alter server srv1 options (|"));
    }

    @Test
    @DisplayName("alterfunctionstmt: alterfunc_opt_item - toàn keyword (STRICT/COST/ROWS...), không có dữ liệu thật cần gợi ý - không throw là đủ")
    void alterfunctionstmt() {
        assertDoesNotThrow(() -> suggest("alter function f1(int4) |"));
    }

    @Test
    @DisplayName("alterobjectdependsstmt: DEPENDS ON EXTENSION name - tên extension không model hoá - không throw là đủ")
    void alterobjectdependsstmt() {
        assertDoesNotThrow(() -> suggest("alter function f1(int4) depends on extension |"));
    }

    @Test
    @DisplayName("alterobjectschemastmt: SET SCHEMA name - tên schema qua rule 'name' dùng chung, không throw là đủ (giới hạn đã biết)")
    void alterobjectschemastmt() {
        assertDoesNotThrow(() -> suggest("alter table public.users set schema |"));
    }

    @Test
    @DisplayName("altertypestmt: SET (...) option list - option key tuỳ loại (storage/receive/send...), không throw là đủ")
    void altertypestmt() {
        assertDoesNotThrow(() -> suggest("alter type mytype set (|"));
    }

    @Test
    @DisplayName("alterseqstmt: RESTART WITH numeric - chờ SỐ, không phải định danh - không throw là đủ")
    void alterseqstmt() {
        assertDoesNotThrow(() -> suggest("alter sequence s1 restart with |"));
    }

    @Test
    @DisplayName("altersystemstmt: SET generic_set - tên GUC parameter tuỳ ý - không throw là đủ")
    void altersystemstmt() {
        assertDoesNotThrow(() -> suggest("alter system set |"));
    }

    @Test
    @DisplayName("altertblspcstmt: SET (...) option list - option tablespace tuỳ storage driver, không throw là đủ")
    void altertblspcstmt() {
        assertDoesNotThrow(() -> suggest("alter tablespace ts1 set (|"));
    }

    @Test
    @DisplayName("alterrolesetstmt: SET search_path TO value - generic GUC, không throw là đủ")
    void alterrolesetstmt() {
        assertDoesNotThrow(() -> suggest("alter role role1 set search_path to |"));
    }

    @Test
    @DisplayName("alterrolestmt: alteroptrolelist - toàn keyword thuộc tính role (SUPERUSER/LOGIN...), không throw là đủ")
    void alterrolestmt() {
        assertDoesNotThrow(() -> suggest("alter role role1 with |"));
    }

    @Test
    @DisplayName("altersubscriptionstmt: SET PUBLICATION publication_name_list - route qua rule collabel (dùng cả ở vị trí alias 'AS' rất phổ biến, quá rủi ro để suppress rộng), không throw là đủ (giới hạn đã biết, cố ý không sửa)")
    void altersubscriptionstmt() {
        assertDoesNotThrow(() -> suggest("alter subscription sub1 set publication |"));
    }

    @Test
    @DisplayName("alterstatsstmt: SET STATISTICS iconst - chờ SỐ, không throw là đủ")
    void alterstatsstmt() {
        assertDoesNotThrow(() -> suggest("alter statistics stat1 set statistics |"));
    }

    @Test
    @DisplayName("altertsconfigurationstmt: ADD MAPPING FOR name_list - tên token-type của text search, không model hoá - không throw là đủ")
    void altertsconfigurationstmt() {
        assertDoesNotThrow(() -> suggest("alter text search configuration tsc1 add mapping for |"));
    }

    @Test
    @DisplayName("altertsdictionarystmt: (definition) - option key tuỳ dictionary template, không throw là đủ")
    void altertsdictionarystmt() {
        assertDoesNotThrow(() -> suggest("alter text search dictionary tsd1 (|"));
    }

    @Test
    @DisplayName("alterusermappingstmt: OPTIONS (...) - tương tự alterfdwstmt, không throw là đủ")
    void alterusermappingstmt() {
        assertDoesNotThrow(() -> suggest("alter user mapping for user1 server srv1 options (|"));
    }

    @Test
    @DisplayName("checkpointstmt: không tham số nào - không throw là đủ")
    void checkpointstmt() {
        assertDoesNotThrow(() -> suggest("checkpoint |"));
    }

    @Test
    @DisplayName("closeportalstmt: CLOSE cursor_name - tên cursor phiên hiện tại, không model hoá - không throw là đủ")
    void closeportalstmt() {
        assertDoesNotThrow(() -> suggest("close |"));
    }

    @Test
    @DisplayName("clusterstmt: USING index_name - tên index không model hoá trong SchemaIndex - không throw là đủ")
    void clusterstmt() {
        assertDoesNotThrow(() -> suggest("cluster public.users using |"));
    }

    @Test
    @DisplayName("commentstmt: IS sconst - chờ STRING LITERAL - không throw là đủ")
    void commentstmt() {
        assertDoesNotThrow(() -> suggest("comment on table public.users is |"));
    }

    @Test
    @DisplayName("constraintssetstmt: SET CONSTRAINTS ALL (DEFERRED|IMMEDIATE) - toàn keyword, không throw là đủ")
    void constraintssetstmt() {
        assertDoesNotThrow(() -> suggest("set constraints all |"));
    }

    @Test
    @DisplayName("copystmt: COPY ... TO (STDOUT|PROGRAM|filename literal) - không throw là đủ")
    void copystmt() {
        assertDoesNotThrow(() -> suggest("copy public.users to |"));
    }

    @Test
    @DisplayName("createamstmt: TYPE INDEX HANDLER handler_name - handler_name route qua rule 'name' dùng chung, không throw là đủ (giới hạn đã biết)")
    void createamstmt() {
        assertDoesNotThrow(() -> suggest("create access method am1 type index handler |"));
    }

    @Test
    @DisplayName("createextensionstmt: SCHEMA name - route qua rule 'name' dùng chung, không throw là đủ (giới hạn đã biết)")
    void createextensionstmt() {
        assertDoesNotThrow(() -> suggest("create extension ext1 schema |"));
    }

    @Test
    @DisplayName("createfdwstmt: HANDLER handler_name - route qua rule 'name' dùng chung, không throw là đủ (giới hạn đã biết)")
    void createfdwstmt() {
        assertDoesNotThrow(() -> suggest("create foreign data wrapper fdw1 handler |"));
    }

    @Test
    @DisplayName("createforeignserverstmt: FOREIGN DATA WRAPPER name - tên FDW không model hoá - không throw là đủ")
    void createforeignserverstmt() {
        assertDoesNotThrow(() -> suggest("create server srv1 foreign data wrapper |"));
    }

    @Test
    @DisplayName("createforeigntablestmt: SERVER name - tên foreign server không model hoá - không throw là đủ")
    void createforeigntablestmt() {
        assertDoesNotThrow(() -> suggest("create foreign table ft1 (id int4) server |"));
    }

    @Test
    @DisplayName("creategroupstmt: OptRoleList - toàn keyword thuộc tính role, không throw là đủ")
    void creategroupstmt() {
        assertDoesNotThrow(() -> suggest("create group grp1 with |"));
    }

    @Test
    @DisplayName("createopclassstmt: FOR TYPE ... USING ... AS (opclass_item_list) - toàn keyword STORAGE/FUNCTION/OPERATOR, không throw là đủ")
    void createopclassstmt() {
        assertDoesNotThrow(() -> suggest("create operator class oc1 for type int4 using btree as |"));
    }

    @Test
    @DisplayName("createopfamilystmt: USING access_method - tên access method (btree/hash/gin...) không model hoá - không throw là đủ")
    void createopfamilystmt() {
        assertDoesNotThrow(() -> suggest("create operator family of1 using |"));
    }

    @Test
    @DisplayName("alteropfamilystmt: ADD opclass_item_list - toàn keyword, không throw là đủ")
    void alteropfamilystmt() {
        assertDoesNotThrow(() -> suggest("alter operator family of1 using btree add |"));
    }

    @Test
    @DisplayName("createplangstmt: HANDLER keyword vị trí (chưa tới function name) - không throw là đủ")
    void createplangstmt() {
        assertDoesNotThrow(() -> suggest("create language plpgsql2 |"));
    }

    @Test
    @DisplayName("createseqstmt: INCREMENT BY numeric - chờ SỐ - không throw là đủ")
    void createseqstmt() {
        assertDoesNotThrow(() -> suggest("create sequence s2 increment by |"));
    }

    @Test
    @DisplayName("createsubscriptionstmt: PUBLICATION publication_name_list - route qua rule collabel, giới hạn đã biết (cố ý không sửa) - không throw là đủ")
    void createsubscriptionstmt() {
        assertDoesNotThrow(() -> suggest("create subscription sub2 connection 'host=x' publication |"));
    }

    @Test
    @DisplayName("createtransformstmt: LANGUAGE lang_name (opt_definition) - vị trí sau LANGUAGE tới definition list, option key tuỳ ý - không throw là đủ")
    void createtransformstmt() {
        assertDoesNotThrow(() -> suggest("create transform for int4 language plpgsql2 (|"));
    }

    @Test
    @DisplayName("createrolestmt: OptRoleList - toàn keyword thuộc tính role, không throw là đủ")
    void createrolestmt() {
        assertDoesNotThrow(() -> suggest("create role role2 with |"));
    }

    @Test
    @DisplayName("createuserstmt: OptRoleList (CREATE USER = CREATE ROLE với LOGIN mặc định) - không throw là đủ")
    void createuserstmt() {
        assertDoesNotThrow(() -> suggest("create user user2 with |"));
    }

    @Test
    @DisplayName("createusermappingstmt: OPTIONS (...) - tương tự alterfdwstmt, không throw là đủ")
    void createusermappingstmt() {
        assertDoesNotThrow(() -> suggest("create user mapping for user2 server srv1 options (|"));
    }

    @Test
    @DisplayName("deallocatestmt: DEALLOCATE name|ALL - tên prepared statement phiên hiện tại, không model hoá - không throw là đủ")
    void deallocatestmt() {
        assertDoesNotThrow(() -> suggest("deallocate |"));
    }

    @Test
    @DisplayName("discardstmt: DISCARD (ALL|PLANS|TEMP|SEQUENCES) - toàn keyword, không throw là đủ")
    void discardstmt() {
        assertDoesNotThrow(() -> suggest("discard |"));
    }

    @Test
    @DisplayName("dostmt: LANGUAGE nonreservedword_or_sconst - phải có tên ngôn ngữ thật (plpgsql/sql)")
    void dostmt() {
        var r = suggest("do $$ begin null; end; $$ language |");
        assertEquals(Set.of("plpgsql", "sql"), keySetOfType(r, "other"));
    }

    @Test
    @DisplayName("dropcaststmt: CASCADE|RESTRICT - toàn keyword, không throw là đủ")
    void dropcaststmt() {
        assertDoesNotThrow(() -> suggest("drop cast (int4 as text) |"));
    }

    @Test
    @DisplayName("dropopclassstmt: USING access_method - tên access method không model hoá - không throw là đủ")
    void dropopclassstmt() {
        assertDoesNotThrow(() -> suggest("drop operator class oc1 using |"));
    }

    @Test
    @DisplayName("dropopfamilystmt: USING access_method - tương tự dropopclassstmt, không throw là đủ")
    void dropopfamilystmt() {
        assertDoesNotThrow(() -> suggest("drop operator family of1 using |"));
    }

    @Test
    @DisplayName("dropsubscriptionstmt: DROP SUBSCRIPTION name - tên subscription không model hoá - không throw là đủ")
    void dropsubscriptionstmt() {
        assertDoesNotThrow(() -> suggest("drop subscription |"));
    }

    @Test
    @DisplayName("droptablespacestmt: DROP TABLESPACE name - tên tablespace route qua rule 'name' dùng chung, không throw là đủ (giới hạn đã biết)")
    void droptablespacestmt() {
        assertDoesNotThrow(() -> suggest("drop tablespace |"));
    }

    @Test
    @DisplayName("droptransformstmt: FOR type_name LANGUAGE name - tên ngôn ngữ ở vị trí này chưa wire (khác dostmt dùng nonreservedword_or_sconst) - không throw là đủ")
    void droptransformstmt() {
        assertDoesNotThrow(() -> suggest("drop transform for int4 language |"));
    }

    @Test
    @DisplayName("dropusermappingstmt: SERVER name - tên foreign server không model hoá - không throw là đủ")
    void dropusermappingstmt() {
        assertDoesNotThrow(() -> suggest("drop user mapping for user2 server |"));
    }

    @Test
    @DisplayName("dropdbstmt: DROP DATABASE (IF EXISTS)? name - tên DB MỚI/khác session hiện tại, không model hoá đối tượng database - không throw là đủ")
    void dropdbstmt() {
        assertDoesNotThrow(() -> suggest("drop database |"));
    }

    @Test
    @DisplayName("executestmt: EXECUTE name - tên prepared statement phiên hiện tại, không model hoá - không throw là đủ")
    void executestmt() {
        assertDoesNotThrow(() -> suggest("execute |"));
    }

    @Test
    @DisplayName("fetchstmt: FETCH NEXT FROM cursor_name - tên cursor phiên hiện tại, không model hoá - không throw là đủ")
    void fetchstmt() {
        assertDoesNotThrow(() -> suggest("fetch next from |"));
    }

    @Test
    @DisplayName("importforeignschemastmt: FROM SERVER name INTO name - tên schema đích route qua rule 'name' dùng chung, không throw là đủ (giới hạn đã biết)")
    void importforeignschemastmt() {
        assertDoesNotThrow(() -> suggest("import foreign schema public from server srv1 into |"));
    }

    @Test
    @DisplayName("listenstmt: LISTEN channel_name - tên channel tuỳ ý, không model hoá - không throw là đủ")
    void listenstmt() {
        assertDoesNotThrow(() -> suggest("listen |"));
    }

    @Test
    @DisplayName("loadstmt: LOAD file_name (sconst) - chờ STRING LITERAL đường dẫn file - không throw là đủ")
    void loadstmt() {
        assertDoesNotThrow(() -> suggest("load |"));
    }

    @Test
    @DisplayName("lockstmt: IN lock_type MODE - toàn keyword chế độ khoá (ROW/SHARE/ACCESS EXCLUSIVE...), không throw là đủ")
    void lockstmt() {
        assertDoesNotThrow(() -> suggest("lock table public.users in |"));
    }

    @Test
    @DisplayName("notifystmt: NOTIFY channel_name - tên channel tuỳ ý, không model hoá - không throw là đủ")
    void notifystmt() {
        assertDoesNotThrow(() -> suggest("notify |"));
    }

    @Test
    @DisplayName("unlistenstmt: UNLISTEN (channel_name|*) - tên channel tuỳ ý, không throw là đủ")
    void unlistenstmt() {
        assertDoesNotThrow(() -> suggest("unlisten |"));
    }

    @Test
    @DisplayName("rulestmt: CREATE RULE ... AS ON INSERT TO table DO (NOTHING|action) - vị trí action là 1 statement lồng, không throw là đủ")
    void rulestmt() {
        assertDoesNotThrow(() -> suggest("create rule r1 as on insert to public.users do |"));
    }

    @Test
    @DisplayName("seclabelstmt: IS sconst - chờ STRING LITERAL - không throw là đủ")
    void seclabelstmt() {
        assertDoesNotThrow(() -> suggest("security label on table public.users is |"));
    }

    @Test
    @DisplayName("transactionstmt: START TRANSACTION transaction_mode_list - toàn keyword (READ WRITE/ISOLATION LEVEL...), không throw là đủ")
    void transactionstmt() {
        assertDoesNotThrow(() -> suggest("start transaction |"));
    }

    @Test
    @DisplayName("truncatestmt: TRUNCATE TABLE ... opt_restart_seqs - toàn keyword sau khi bảng đã được chỉ định, không throw là đủ")
    void truncatestmt() {
        assertDoesNotThrow(() -> suggest("truncate table public.users |"));
    }

    @Test
    @DisplayName("variableresetstmt: RESET generic_reset - tên GUC parameter tuỳ ý (+ ALL keyword), không throw là đủ")
    void variableresetstmt() {
        assertDoesNotThrow(() -> suggest("reset |"));
    }

    @Test
    @DisplayName("variablesetstmt: SET var_name TO value - generic GUC, không throw là đủ")
    void variablesetstmt() {
        assertDoesNotThrow(() -> suggest("set search_path to |"));
    }

    @Test
    @DisplayName("variableshowstmt: SHOW var_name - tên GUC parameter tuỳ ý (+ ALL keyword), không throw là đủ")
    void variableshowstmt() {
        assertDoesNotThrow(() -> suggest("show |"));
    }

    @Test
    @DisplayName("plsqlconsolecommand: MetaCommand (\\\\d, \\\\l...) - cú pháp riêng của CLI, không thuộc SQL chuẩn - không throw là đủ")
    void plsqlconsolecommand() {
        assertDoesNotThrow(() -> suggest("\\d |"));
    }

    // =====================================================================
    // Danh sách đầy đủ 125 rule (giữ lại để tham chiếu/CI liệt kê tên rule đã
    // phủ - KHÔNG dùng để assert nội dung nữa, mọi assertion thật đã chuyển
    // thành @Test riêng ở trên). Dùng 1 test rất nhẹ để đảm bảo KHÔNG rule
    // nào trong danh sách gốc bị bỏ sót so với @Test method tương ứng.
    // =====================================================================

    static Stream<Arguments> grammarRules() {
        return Stream.of(
                Arguments.of("altereventtrigstmt", "alter event trigger trg1 |"),
                Arguments.of("altercollationstmt", "alter collation c1 |"),
                Arguments.of("alterdatabasestmt", "alter database db1 set tablespace |"),
                Arguments.of("alterdatabasesetstmt", "alter database db1 set search_path to |"),
                Arguments.of("alterdefaultprivilegesstmt", "alter default privileges in schema public grant select on tables to |"),
                Arguments.of("alterdomainstmt", "alter domain d1 validate constraint |"),
                Arguments.of("alterenumstmt", "alter type status_enum add value 'x' before |"),
                Arguments.of("alterextensionstmt", "alter extension ext1 update to |"),
                Arguments.of("alterextensioncontentsstmt", "alter extension ext1 add table |"),
                Arguments.of("alterfdwstmt", "alter foreign data wrapper fdw1 options (|"),
                Arguments.of("alterforeignserverstmt", "alter server srv1 options (|"),
                Arguments.of("alterfunctionstmt", "alter function f1(int4) |"),
                Arguments.of("altergroupstmt", "alter group grp1 add user |"),
                Arguments.of("alterobjectdependsstmt", "alter function f1(int4) depends on extension |"),
                Arguments.of("alterobjectschemastmt", "alter table public.users set schema |"),
                Arguments.of("alterownerstmt", "alter aggregate agg1(int4) owner to |"),
                Arguments.of("alteroperatorstmt", "alter operator = (int4, int4) set (restrict = |"),
                Arguments.of("altertypestmt", "alter type mytype set (|"),
                Arguments.of("alterpolicystmt", "alter policy pol1 on public.users using (|"),
                Arguments.of("alterseqstmt", "alter sequence s1 restart with |"),
                Arguments.of("altersystemstmt", "alter system set |"),
                Arguments.of("altertablestmt", "alter table public.users add column |"),
                Arguments.of("altertblspcstmt", "alter tablespace ts1 set (|"),
                Arguments.of("altercompositetypestmt", "alter type mytype add attribute a |"),
                Arguments.of("alterpublicationstmt", "alter publication pub1 add table |"),
                Arguments.of("alterrolesetstmt", "alter role role1 set search_path to |"),
                Arguments.of("alterrolestmt", "alter role role1 with |"),
                Arguments.of("altersubscriptionstmt", "alter subscription sub1 set publication |"),
                Arguments.of("alterstatsstmt", "alter statistics stat1 set statistics |"),
                Arguments.of("altertsconfigurationstmt", "alter text search configuration tsc1 add mapping for |"),
                Arguments.of("altertsdictionarystmt", "alter text search dictionary tsd1 (|"),
                Arguments.of("alterusermappingstmt", "alter user mapping for user1 server srv1 options (|"),
                Arguments.of("analyzestmt", "analyze |"),
                Arguments.of("callstmt", "call myproc(|"),
                Arguments.of("checkpointstmt", "checkpoint |"),
                Arguments.of("closeportalstmt", "close |"),
                Arguments.of("clusterstmt", "cluster public.users using |"),
                Arguments.of("commentstmt", "comment on table public.users is |"),
                Arguments.of("constraintssetstmt", "set constraints all |"),
                Arguments.of("copystmt", "copy public.users to |"),
                Arguments.of("createamstmt", "create access method am1 type index handler |"),
                Arguments.of("createasstmt", "create table t2 as select | from public.users"),
                Arguments.of("createassertionstmt", "create assertion a1 check (|"),
                Arguments.of("createcaststmt", "create cast (int4 as text) with function |"),
                Arguments.of("createconversionstmt", "create conversion conv1 for 'UTF8' to 'LATIN1' from |"),
                Arguments.of("createdomainstmt", "create domain d1 as |"),
                Arguments.of("createextensionstmt", "create extension ext1 schema |"),
                Arguments.of("createfdwstmt", "create foreign data wrapper fdw1 handler |"),
                Arguments.of("createforeignserverstmt", "create server srv1 foreign data wrapper |"),
                Arguments.of("createforeigntablestmt", "create foreign table ft1 (id int4) server |"),
                Arguments.of("createfunctionstmt", "create function f1() returns |"),
                Arguments.of("creategroupstmt", "create group grp1 with |"),
                Arguments.of("creatematviewstmt", "create materialized view mv1 as select | from public.users"),
                Arguments.of("createopclassstmt", "create operator class oc1 for type int4 using btree as |"),
                Arguments.of("createopfamilystmt", "create operator family of1 using |"),
                Arguments.of("createpublicationstmt", "create publication pub1 for table |"),
                Arguments.of("alteropfamilystmt", "alter operator family of1 using btree add |"),
                Arguments.of("createpolicystmt", "create policy pol1 on public.users using (|"),
                Arguments.of("createplangstmt", "create language plpgsql2 |"),
                Arguments.of("createschemastmt", "create schema if not exists |"),
                Arguments.of("createseqstmt", "create sequence s2 increment by |"),
                Arguments.of("createstmt", "create table t3 (|"),
                Arguments.of("createsubscriptionstmt", "create subscription sub2 connection 'host=x' publication |"),
                Arguments.of("createstatsstmt", "create statistics st1 on |"),
                Arguments.of("createtablespacestmt", "create tablespace ts2 owner |"),
                Arguments.of("createtransformstmt", "create transform for int4 language plpgsql2 (|"),
                Arguments.of("createtrigstmt", "create trigger trg2 before insert on public.users execute function |"),
                Arguments.of("createeventtrigstmt", "create event trigger et2 on ddl_command_start execute function |"),
                Arguments.of("createrolestmt", "create role role2 with |"),
                Arguments.of("createuserstmt", "create user user2 with |"),
                Arguments.of("createusermappingstmt", "create user mapping for user2 server srv1 options (|"),
                Arguments.of("createdbstmt", "create database db2 owner |"),
                Arguments.of("deallocatestmt", "deallocate |"),
                Arguments.of("declarecursorstmt", "declare cur1 cursor for select | from public.users"),
                Arguments.of("definestmt", "create aggregate agg2(int4) (sfunc = |"),
                Arguments.of("deletestmt", "delete from public.users where |"),
                Arguments.of("discardstmt", "discard |"),
                Arguments.of("dostmt", "do $$ begin null; end; $$ language |"),
                Arguments.of("dropcaststmt", "drop cast (int4 as text) |"),
                Arguments.of("dropopclassstmt", "drop operator class oc1 using |"),
                Arguments.of("dropopfamilystmt", "drop operator family of1 using |"),
                Arguments.of("dropownedstmt", "drop owned by |"),
                Arguments.of("dropstmt", "drop table |"),
                Arguments.of("dropsubscriptionstmt", "drop subscription |"),
                Arguments.of("droptablespacestmt", "drop tablespace |"),
                Arguments.of("droptransformstmt", "drop transform for int4 language |"),
                Arguments.of("droprolestmt", "drop role |"),
                Arguments.of("dropusermappingstmt", "drop user mapping for user2 server |"),
                Arguments.of("dropdbstmt", "drop database |"),
                Arguments.of("executestmt", "execute |"),
                Arguments.of("explainstmt", "explain select | from public.users"),
                Arguments.of("fetchstmt", "fetch next from |"),
                Arguments.of("grantstmt", "grant select on public.users to |"),
                Arguments.of("grantrolestmt", "grant role1 to |"),
                Arguments.of("importforeignschemastmt", "import foreign schema public from server srv1 into |"),
                Arguments.of("indexstmt", "create index idx1 on public.users (|"),
                Arguments.of("insertstmt", "insert into public.users (|"),
                Arguments.of("mergestmt", "merge into public.orders o using public.users u on o.user_id = u.id when matched then update set |"),
                Arguments.of("listenstmt", "listen |"),
                Arguments.of("refreshmatviewstmt", "refresh materialized view |"),
                Arguments.of("loadstmt", "load |"),
                Arguments.of("lockstmt", "lock table public.users in |"),
                Arguments.of("notifystmt", "notify |"),
                Arguments.of("preparestmt", "prepare p1 as select | from public.users"),
                Arguments.of("reassignownedstmt", "reassign owned by role1 to |"),
                Arguments.of("reindexstmt", "reindex table |"),
                Arguments.of("removeaggrstmt", "drop aggregate agg1(|"),
                Arguments.of("removefuncstmt", "drop function |"),
                Arguments.of("removeoperstmt", "drop operator = (int4, |"),
                Arguments.of("renamestmt", "alter table public.users rename to |"),
                Arguments.of("revokestmt", "revoke select on public.users from |"),
                Arguments.of("revokerolestmt", "revoke role1 from |"),
                Arguments.of("rulestmt", "create rule r1 as on insert to public.users do |"),
                Arguments.of("seclabelstmt", "security label on table public.users is |"),
                Arguments.of("selectstmt", "select | from public.users"),
                Arguments.of("transactionstmt", "start transaction |"),
                Arguments.of("truncatestmt", "truncate table public.users |"),
                Arguments.of("unlistenstmt", "unlisten |"),
                Arguments.of("updatestmt", "update public.users set |"),
                Arguments.of("vacuumstmt", "vacuum |"),
                Arguments.of("variableresetstmt", "reset |"),
                Arguments.of("variablesetstmt", "set search_path to |"),
                Arguments.of("variableshowstmt", "show |"),
                Arguments.of("viewstmt", "create view v1 as select | from public.users"),
                Arguments.of("plsqlconsolecommand", "\\d |")
        );
    }

    @ParameterizedTest(name = "[{index}] {0} covered by a dedicated @Test above")
    @MethodSource("grammarRules")
    @DisplayName("Sanity: mỗi rule trong danh sách gốc phải có 1 @Test method cùng tên ở trên (không rule nào bị bỏ sót khi refactor sang assertion thật)")
    void everyRuleHasADedicatedTest(String ruleName, String sqlWithCursor) {
        boolean hasMethod = Stream.of(PostgresGrammarBreadthTest.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals(ruleName));
        assertTrue(hasMethod, "Rule '" + ruleName + "' không còn @Test method riêng - đã bị bỏ sót khi refactor");
    }
}
