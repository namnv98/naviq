package com.sqlctx.completion;

import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.suggestion.CompletionHistory;
import com.sqlctx.completion.suggestion.CompletionInputPreparer;
import com.sqlctx.completion.suggestion.oracle.OracleSuggestionService;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phủ RỘNG: 1 test cho MỖI alternative của {@code unit_statement}/{@code sql_statement} trong
 * PlSqlParser.g4 (kể cả tính năng DBA hiếm gặp: diskgroup, PMEM filestore, lockdown profile,
 * edition...) - khác {@link OracleSuggestionServiceTest} (SÂU: đúng thứ tự xếp hạng cho các luồng
 * gõ phổ biến nhất).
 * <p>
 * Đi qua ĐÚNG đường thật production ({@code suggests(PrepareCompletionInput)}, có
 * {@code SuggestFilter}), giống các bộ test suggestion khác trong session này.
 * <p>
 * QUAN TRỌNG: {@code assertDoesNotThrow} ("không crash") KHÔNG đủ để bắt bug nội dung sai - đã
 * kiểm chứng thật ở phía Postgres cùng session này ("ALTER TABLE ADD COLUMN" gợi ý datatype SỚM
 * hơn lúc đáng lẽ, không hề crash, bộ test rộng cũ bỏ sót hoàn toàn). Vì vậy: MỌI vị trí có nguồn
 * dữ liệu thật (bảng/cột/hàm/kiểu dữ liệu trong fixture) đọc grammar rồi assert đúng NỘI DUNG bên
 * dưới (nhóm "N. Nội dung thật" các @Test riêng) - danh sách tham số hoá {@code grammarShapes()}
 * chỉ còn giữ lại các vị trí THẬT SỰ không có dữ liệu enumerable nào (option tuỳ ý theo
 * driver/extension, literal chuỗi/số, tính năng DBA không model hoá) - ở đó {@code assertDoesNotThrow}
 * là claim trung thực DUY NHẤT có thể đưa ra, không phải chỗ né việc.
 */
class OracleGrammarBreadthTest {

    @BeforeAll
    static void setUpFixtureSchema() {
        var id = new ColumnInfo("id", "id", "NUMBER", true);
        var name = new ColumnInfo("name", "name", "VARCHAR2", false);
        var email = new ColumnInfo("email", "email", "VARCHAR2", false);
        var customerId = new ColumnInfo("customer_id", "customer_id", "NUMBER", false);
        var total = new ColumnInfo("total", "total", "NUMBER", false);
        var status = new ColumnInfo("status", "status", "VARCHAR2", false);
        var userId = new ColumnInfo("user_id", "user_id", "NUMBER", false);
        var amount = new ColumnInfo("amount", "amount", "NUMBER", false);
        var price = new ColumnInfo("price", "price", "NUMBER", false);
        var quantity = new ColumnInfo("quantity", "quantity", "NUMBER", false);
        var description = new ColumnInfo("description", "description", "VARCHAR2", false);

        var users = new TableInfo("naviq", "users", "table", List.of(id, name, email));
        var orders = new TableInfo("naviq", "orders", "table", List.of(id, customerId, total, status, userId));
        var contracts = new TableInfo("naviq", "contracts", "table", List.of(id, name, amount, status));
        var products = new TableInfo("naviq", "products", "table", List.of(id, name, price, quantity, description));

        var schema = new SchemaInfo("naviq", List.of(users, orders, contracts, products));
        SchemaIndex.schemas = List.of(schema);
        SchemaIndex.tableIndex = Map.of(
                "naviq.users", users, "users", users,
                "naviq.orders", orders, "orders", orders,
                "naviq.contracts", contracts, "contracts", contracts,
                "naviq.products", products, "products", products
        );
        SchemaIndex.schemaTableIndex = Map.of(
                "naviq.users", users, "naviq.orders", orders,
                "naviq.contracts", contracts, "naviq.products", products
        );
        SchemaIndex.functions = List.of("count", "sum", "avg", "sysdate");
        SchemaIndex.dataTypes = List.of("NUMBER", "VARCHAR2", "DATE", "CHAR");
    }

    @BeforeEach
    void resetHistory() {
        CompletionHistory.resetForTests();
    }

    private static List<Suggestion> suggest(String rawWithCursor) {
        int cursor = rawWithCursor.indexOf('|');
        String sql = rawWithCursor.substring(0, cursor) + rawWithCursor.substring(cursor + 1);
        var input = CompletionInputPreparer.buildInput(sql, cursor);
        return OracleSuggestionService.suggests(input);
    }

    private static List<String> keysOfType(List<Suggestion> list, String type) {
        return list.stream().filter(s -> s.getType().label().equals(type)).map(Suggestion::getKey).toList();
    }

    static Stream<Arguments> grammarShapes() {
        return Stream.of(
                // ===== ALTER (unit_statement) =====
                Arguments.of("alter_analytic_view", "ALTER ANALYTIC VIEW av1 COMPILE|"),
                Arguments.of("alter_attribute_dimension", "ALTER ATTRIBUTE DIMENSION ad1 COMPILE|"),
                Arguments.of("alter_audit_policy", "ALTER AUDIT POLICY p1 DROP CONDITION|"),
                Arguments.of("alter_cluster", "ALTER CLUSTER cl1 SIZE 10M|"),
                Arguments.of("alter_database", "ALTER DATABASE OPEN|"),
                Arguments.of("alter_database_link", "ALTER DATABASE LINK lnk1 CONNECT TO scott IDENTIFIED BY tiger|"),
                Arguments.of("alter_dimension", "ALTER DIMENSION dim1 COMPILE|"),
                Arguments.of("alter_diskgroup", "ALTER DISKGROUP dg1 CHECK|"),
                Arguments.of("alter_flashback_archive", "ALTER FLASHBACK ARCHIVE fa1 SET DEFAULT|"),
                Arguments.of("alter_function", "ALTER FUNCTION f1 COMPILE|"),
                Arguments.of("alter_hierarchy", "ALTER HIERARCHY h1 COMPILE|"),
                Arguments.of("alter_index", "ALTER INDEX idx1 REBUILD|"),
                Arguments.of("alter_inmemory_join_group", "ALTER INMEMORY JOIN GROUP jg1 ADD (t1(c1), t2(c2))|"),
                Arguments.of("alter_java", "ALTER JAVA SOURCE src1 COMPILE|"),
                Arguments.of("alter_library", "ALTER LIBRARY lib1 COMPILE|"),
                Arguments.of("alter_lockdown_profile", "ALTER LOCKDOWN PROFILE lp1 DISABLE STATEMENT ALTER_SYSTEM|"),
                Arguments.of("alter_materialized_view", "ALTER MATERIALIZED VIEW users COMPILE|"),
                Arguments.of("alter_materialized_view_log", "ALTER MATERIALIZED VIEW LOG ON users PARALLEL|"),
                Arguments.of("alter_materialized_zonemap", "ALTER MATERIALIZED ZONEMAP zm1 REBUILD|"),
                Arguments.of("alter_operator", "ALTER OPERATOR op1 COMPILE|"),
                Arguments.of("alter_outline", "ALTER OUTLINE ol1 REBUILD|"),
                Arguments.of("alter_package", "ALTER PACKAGE pkg1 COMPILE|"),
                Arguments.of("alter_pmem_filestore", "ALTER PMEM FILESTORE fs1 DISMOUNT|"),
                Arguments.of("alter_procedure", "ALTER PROCEDURE proc1 COMPILE|"),
                Arguments.of("alter_resource_cost", "ALTER RESOURCE COST CPU_PER_SESSION 1000|"),
                Arguments.of("alter_role", "ALTER ROLE role1 NOT IDENTIFIED|"),
                Arguments.of("alter_rollback_segment", "ALTER ROLLBACK SEGMENT rbs1 ONLINE|"),
                Arguments.of("alter_sequence", "ALTER SEQUENCE seq1 INCREMENT BY 1|"),
                Arguments.of("alter_session", "ALTER SESSION SET NLS_LANGUAGE = 'AMERICAN'|"),
                Arguments.of("alter_synonym", "ALTER SYNONYM syn1 COMPILE|"),
                Arguments.of("alter_table", "ALTER TABLE users ADD (new_col NUMBER)|"),
                Arguments.of("alter_tablespace", "ALTER TABLESPACE ts1 COALESCE|"),
                Arguments.of("alter_tablespace_set", "ALTER TABLESPACE SET tss1 RENAME TO tss2|"),
                Arguments.of("alter_trigger", "ALTER TRIGGER trg1 DISABLE|"),
                Arguments.of("alter_type", "ALTER TYPE ty1 COMPILE|"),
                Arguments.of("alter_user", "ALTER USER u1 IDENTIFIED BY newpass|"),
                Arguments.of("alter_view", "ALTER VIEW v1 COMPILE|"),

                // ===== CREATE (unit_statement) =====
                Arguments.of("create_analytic_view", "CREATE ANALYTIC VIEW av1 CACHE|"),
                Arguments.of("create_attribute_dimension", "CREATE ATTRIBUTE DIMENSION ad1 USING users|"),
                Arguments.of("create_audit_policy", "CREATE AUDIT POLICY p1 ACTIONS SELECT ON users|"),
                Arguments.of("create_cluster", "CREATE CLUSTER cl1 (c1 NUMBER)|"),
                Arguments.of("create_context", "CREATE CONTEXT ctx1 USING pkg1|"),
                Arguments.of("create_controlfile", "CREATE CONTROLFILE SET DATABASE db1 RESETLOGS|"),
                Arguments.of("create_database", "CREATE DATABASE db1|"),
                Arguments.of("create_database_link", "CREATE DATABASE LINK lnk1 CONNECT TO scott IDENTIFIED BY tiger|"),
                Arguments.of("create_dimension", "CREATE DIMENSION dim1 LEVEL lvl1 IS users.id HIERARCHY hh1 (lvl1 CHILD OF lvl1)|"),
                Arguments.of("create_directory", "CREATE DIRECTORY dir1 AS '/tmp'|"),
                Arguments.of("create_diskgroup", "CREATE DISKGROUP dg1 DISK '/dev/sda1'|"),
                Arguments.of("create_edition", "CREATE EDITION ed1|"),
                Arguments.of("create_flashback_archive", "CREATE FLASHBACK ARCHIVE fa1 TABLESPACE ts1|"),
                Arguments.of("create_function_body", "CREATE FUNCTION f1 RETURN NUMBER IS BEGIN RETURN 1; END;|"),
                Arguments.of("create_hierarchy", "CREATE HIERARCHY h1 USING users|"),
                Arguments.of("create_index", "CREATE INDEX idx1 ON users (|)"),
                Arguments.of("create_inmemory_join_group", "CREATE INMEMORY JOIN GROUP jg1 (users(id), orders(user_id))|"),
                Arguments.of("create_java", "CREATE JAVA SOURCE NAMED src1 AS class Foo {}|"),
                Arguments.of("create_library", "CREATE LIBRARY lib1 AS '/tmp/lib.so'|"),
                Arguments.of("create_lockdown_profile", "CREATE LOCKDOWN PROFILE lp1|"),
                Arguments.of("create_materialized_view", "CREATE MATERIALIZED VIEW mv1 AS SELECT * FROM users|"),
                Arguments.of("create_materialized_view_log", "CREATE MATERIALIZED VIEW LOG ON users|"),
                Arguments.of("create_materialized_zonemap", "CREATE MATERIALIZED ZONEMAP zm1 ON users (id)|"),
                Arguments.of("create_operator", "CREATE OPERATOR op1 BINDING (NUMBER) RETURN NUMBER USING f1|"),
                Arguments.of("create_outline", "CREATE OUTLINE ol1 ON SELECT * FROM users|"),
                Arguments.of("create_package", "CREATE PACKAGE pkg1 IS PROCEDURE p1; END;|"),
                Arguments.of("create_package_body", "CREATE PACKAGE BODY pkg1 IS END;|"),
                Arguments.of("create_pmem_filestore", "CREATE PMEM FILESTORE fs1 MOUNTPOINT '/mnt' SIZE 10G|"),
                Arguments.of("create_procedure_body", "CREATE PROCEDURE proc1 IS BEGIN NULL; END;|"),
                Arguments.of("create_profile", "CREATE PROFILE prof1 LIMIT SESSIONS_PER_USER 1|"),
                Arguments.of("create_restore_point", "CREATE RESTORE POINT rp1|"),
                Arguments.of("create_role", "CREATE ROLE role1|"),
                Arguments.of("create_rollback_segment", "CREATE ROLLBACK SEGMENT rbs1|"),
                Arguments.of("create_schema", "CREATE SCHEMA AUTHORIZATION naviq CREATE TABLE t1 (a NUMBER)|"),
                Arguments.of("create_sequence", "CREATE SEQUENCE seq1 START WITH |"),
                Arguments.of("create_spfile", "CREATE SPFILE FROM PFILE|"),
                Arguments.of("create_synonym", "CREATE SYNONYM syn1 FOR users|"),
                Arguments.of("create_table", "CREATE TABLE t1 (id |)"),
                Arguments.of("create_tablespace", "CREATE TABLESPACE ts1 DATAFILE 'ts1.dbf' SIZE 10M|"),
                Arguments.of("create_tablespace_set", "CREATE TABLESPACE SET tss1|"),
                Arguments.of("create_trigger", "CREATE TRIGGER trg1 BEFORE INSERT ON users FOR EACH ROW BEGIN NULL; END;|"),
                Arguments.of("create_type", "CREATE TYPE ty1 AS OBJECT (a NUMBER)|"),
                Arguments.of("create_user", "CREATE USER u1 IDENTIFIED BY pass1|"),
                Arguments.of("create_view", "CREATE VIEW v1 AS SELECT * FROM users|"),

                // ===== DROP (unit_statement) =====
                Arguments.of("drop_analytic_view", "DROP ANALYTIC VIEW av1|"),
                Arguments.of("drop_attribute_dimension", "DROP ATTRIBUTE DIMENSION ad1|"),
                Arguments.of("drop_audit_policy", "DROP AUDIT POLICY p1|"),
                Arguments.of("drop_cluster", "DROP CLUSTER cl1|"),
                Arguments.of("drop_context", "DROP CONTEXT ctx1|"),
                Arguments.of("drop_database", "DROP DATABASE|"),
                Arguments.of("drop_database_link", "DROP DATABASE LINK lnk1|"),
                Arguments.of("drop_directory", "DROP DIRECTORY dir1|"),
                Arguments.of("drop_diskgroup", "DROP DISKGROUP dg1|"),
                Arguments.of("drop_edition", "DROP EDITION ed1|"),
                Arguments.of("drop_flashback_archive", "DROP FLASHBACK ARCHIVE fa1|"),
                Arguments.of("drop_function", "DROP FUNCTION f1|"),
                Arguments.of("drop_hierarchy", "DROP HIERARCHY h1|"),
                Arguments.of("drop_index", "DROP INDEX idx1|"),
                Arguments.of("drop_indextype", "DROP INDEXTYPE it1|"),
                Arguments.of("drop_inmemory_join_group", "DROP INMEMORY JOIN GROUP jg1|"),
                Arguments.of("drop_java", "DROP JAVA SOURCE src1|"),
                Arguments.of("drop_library", "DROP LIBRARY lib1|"),
                Arguments.of("drop_lockdown_profile", "DROP LOCKDOWN PROFILE lp1|"),
                Arguments.of("drop_materialized_view", "DROP MATERIALIZED VIEW mv1|"),
                Arguments.of("drop_materialized_view_log", "DROP MATERIALIZED VIEW LOG ON users|"),
                Arguments.of("drop_materialized_zonemap", "DROP MATERIALIZED ZONEMAP zm1|"),
                Arguments.of("drop_operator", "DROP OPERATOR op1|"),
                Arguments.of("drop_outline", "DROP OUTLINE ol1|"),
                Arguments.of("drop_package", "DROP PACKAGE pkg1|"),
                Arguments.of("drop_pmem_filestore", "DROP PMEM FILESTORE fs1|"),
                Arguments.of("drop_procedure", "DROP PROCEDURE proc1|"),
                Arguments.of("drop_restore_point", "DROP RESTORE POINT rp1|"),
                Arguments.of("drop_role", "DROP ROLE role1|"),
                Arguments.of("drop_rollback_segment", "DROP ROLLBACK SEGMENT rbs1|"),
                Arguments.of("drop_sequence", "DROP SEQUENCE seq1|"),
                Arguments.of("drop_synonym", "DROP SYNONYM syn1|"),
                Arguments.of("drop_table", "DROP TABLE |"),
                Arguments.of("drop_tablespace", "DROP TABLESPACE ts1|"),
                Arguments.of("drop_tablespace_set", "DROP TABLESPACE SET tss1|"),
                Arguments.of("drop_trigger", "DROP TRIGGER trg1|"),
                Arguments.of("drop_type", "DROP TYPE ty1|"),
                Arguments.of("drop_user", "DROP USER u1|"),
                Arguments.of("drop_view", "DROP VIEW |"),

                // ===== Misc (unit_statement) =====
                Arguments.of("administer_key_management", "ADMINISTER KEY MANAGEMENT CREATE KEYSTORE '/tmp/wallet' IDENTIFIED BY pass1|"),
                Arguments.of("analyze", "ANALYZE TABLE users COMPUTE STATISTICS|"),
                Arguments.of("anonymous_block", "BEGIN NULL; |END;"),
                Arguments.of("associate_statistics", "ASSOCIATE STATISTICS WITH COLUMNS users.id USING f1|"),
                Arguments.of("audit_traditional", "AUDIT SELECT ON users|"),
                Arguments.of("comment_on_column", "COMMENT ON COLUMN users.name IS |'x'"),
                Arguments.of("comment_on_materialized", "COMMENT ON MATERIALIZED VIEW mv1 IS |'x'"),
                Arguments.of("comment_on_table", "COMMENT ON TABLE users IS |'x'"),
                Arguments.of("disassociate_statistics", "DISASSOCIATE STATISTICS FROM COLUMNS users.id|"),
                Arguments.of("flashback_table", "FLASHBACK TABLE users TO RESTORE POINT rp1|"),
                Arguments.of("grant_statement", "GRANT SELECT ON users TO |"),
                Arguments.of("noaudit_statement", "NOAUDIT SELECT ON users|"),
                Arguments.of("purge_statement", "PURGE TABLE |"),
                Arguments.of("rename_object", "RENAME users TO users_old|"),
                Arguments.of("revoke_statement", "REVOKE SELECT ON users FROM |"),
                Arguments.of("truncate_cluster", "TRUNCATE CLUSTER cl1|"),
                Arguments.of("truncate_table", "TRUNCATE TABLE |"),
                Arguments.of("unified_auditing", "AUDIT POLICY p1|"),
                Arguments.of("sql_call_statement", "CALL proc1(|)"),

                // ===== DML (data_manipulation_language_statements) =====
                Arguments.of("merge_statement", "MERGE INTO users u USING orders o ON (u.id = o.user_id) WHEN MATCHED THEN UPDATE SET u.|"),
                Arguments.of("lock_table_statement", "LOCK TABLE users IN |"),
                Arguments.of("select_statement", "SELECT * FROM |"),
                Arguments.of("update_statement", "UPDATE users SET |"),
                Arguments.of("delete_statement", "DELETE FROM users WHERE |"),
                Arguments.of("insert_statement", "INSERT INTO users (|"),
                Arguments.of("explain_statement", "EXPLAIN PLAN FOR SELECT * FROM |"),

                // ===== cursor_manipulation_statements =====
                Arguments.of("close_statement", "BEGIN CLOSE |END;"),
                Arguments.of("open_statement", "BEGIN OPEN |END;"),
                Arguments.of("fetch_statement", "BEGIN FETCH c1 INTO |END;"),
                Arguments.of("open_for_statement", "BEGIN OPEN v1 FOR SELECT * FROM |END;"),

                // ===== transaction_control_statements =====
                Arguments.of("set_transaction_command", "SET TRANSACTION READ ONLY|"),
                Arguments.of("set_constraint_command", "SET CONSTRAINT ALL |"),
                Arguments.of("commit_statement", "COMMIT|"),
                Arguments.of("rollback_statement", "ROLLBACK|"),
                Arguments.of("savepoint_statement", "SAVEPOINT sp1|"),

                // ===== execute_immediate (sql_statement) - only valid inside a PL/SQL block =====
                Arguments.of("execute_immediate", "BEGIN EXECUTE IMMEDIATE |END;")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("grammarShapes")
    void grammarShapeDoesNotCrash(String ruleName, String sqlWithCursor) {
        assertDoesNotThrow(() -> suggest(sqlWithCursor), () -> "Rule '" + ruleName + "' crashed on: " + sqlWithCursor);
    }

    // =========================================================================================
    // N. NỘI DUNG THẬT - các vị trí có nguồn dữ liệu enumerable thật (bảng/cột/hàm/kiểu dữ liệu
    // trong fixture), đọc grammar rồi assert đúng, KHÔNG chỉ "không crash". Đây là phần bù trực
    // tiếp cho lỗ hổng "assertDoesNotThrow không bắt được bug nội dung" đã phát hiện ở Postgres.
    // =========================================================================================

    @Test
    @DisplayName("create_index: 'CREATE INDEX idx1 ON users (|)' - index_expr: column_name|expression -> phải có đúng cột thật của users")
    void createIndexSuggestsRealColumns() {
        var result = suggest("CREATE INDEX idx1 ON users (|)");
        var columns = keysOfType(result, "column");
        assertTrue(columns.contains("users.id") || columns.contains("id"));
        assertTrue(columns.contains("users.name") || columns.contains("name"));
    }

    @Test
    @DisplayName("create_table: 'CREATE TABLE t1 (id |)' - column_definition: column_name (datatype|regular_id) -> tên cột ĐÃ gõ xong, phải có đúng datatype thật")
    void createTableColumnTypeSuggestsRealDatatypes() {
        var result = suggest("CREATE TABLE t1 (id |)");
        var datatypes = keysOfType(result, "datatype");
        assertTrue(datatypes.containsAll(List.of("NUMBER", "VARCHAR2", "DATE", "CHAR")));
    }

    @Test
    @DisplayName("alter_table MODIFY: cột trong ngoặc là cột ĐÃ TỒN TẠI (modify_col_properties: column_name...) -> phải gợi ý đúng cột thật của users")
    void alterTableModifySuggestsRealExistingColumns() {
        var result = suggest("ALTER TABLE users MODIFY (|)");
        var columns = keysOfType(result, "column");
        assertFalse(columns.isEmpty(), "MODIFY nhắm tới cột ĐÃ TỒN TẠI - phải thấy cột thật của users");
    }

    @Test
    @DisplayName("merge_statement dot-mode 'u.|' - phải CHỈ ra đúng cột của users (alias u), không lẫn orders")
    void mergeStatementResolvesAliasColumns() {
        var result = suggest("MERGE INTO users u USING orders o ON (u.id = o.user_id) WHEN MATCHED THEN UPDATE SET u.|");
        var columns = keysOfType(result, "column").stream().map(String::toLowerCase).collect(Collectors.toSet());
        assertTrue(columns.contains("u.id"));
        assertTrue(columns.contains("u.name"));
        assertTrue(columns.contains("u.email"));
        assertFalse(columns.stream().anyMatch(c -> c.contains("customer_id") || c.contains("user_id")),
                "Không được lẫn cột của orders (alias o) vào đây");
    }

    @Test
    @DisplayName("lock_table_statement: lock_mode chỉ có đúng 3 từ khoá bắt đầu hợp lệ (ROW/SHARE/EXCLUSIVE theo grammar)")
    void lockTableSuggestsRealLockModeKeywords() {
        var result = suggest("LOCK TABLE users IN |");
        var keywords = keysOfType(result, "keyword").stream().map(String::toLowerCase).collect(Collectors.toSet());
        assertTrue(keywords.contains("row"));
        assertTrue(keywords.contains("share"));
        assertTrue(keywords.contains("exclusive"));
    }

    @Test
    @DisplayName("select_statement 'FROM |' - phải gợi ý đúng bảng thật (users/orders), không rỗng")
    void selectFromSuggestsRealTables() {
        var result = suggest("SELECT * FROM |");
        var tables = keysOfType(result, "table");
        assertTrue(tables.stream().anyMatch(t -> t.toLowerCase().contains("users")));
        assertTrue(tables.stream().anyMatch(t -> t.toLowerCase().contains("orders")));
    }

    @Test
    @DisplayName("update_statement 'SET |' - phải gợi ý đúng cột thật của users")
    void updateSetSuggestsRealColumns() {
        var result = suggest("UPDATE users SET |");
        var columns = keysOfType(result, "column");
        assertFalse(columns.isEmpty());
    }

    @Test
    @DisplayName("insert_statement column-list - phải gợi ý đúng cột thật của users")
    void insertColumnListSuggestsRealColumns() {
        var result = suggest("INSERT INTO users (|");
        var columns = keysOfType(result, "column");
        assertFalse(columns.isEmpty());
    }

    @Test
    @DisplayName("explain_statement 'FOR SELECT * FROM |' - phải gợi ý đúng bảng thật")
    void explainStatementSuggestsRealTables() {
        var result = suggest("EXPLAIN PLAN FOR SELECT * FROM |");
        assertFalse(keysOfType(result, "table").isEmpty());
    }

    @Test
    @DisplayName("grant_statement 'TO |' - grantee_name|PUBLIC: PUBLIC phải có mặt, KHÔNG được lẫn bảng/cột nào (grantee không phải object trong schema)")
    void grantToSuggestsPublicOnlyNoTableOrColumnLeak() {
        var result = suggest("GRANT SELECT ON users TO |");
        var keywords = keysOfType(result, "keyword").stream().map(String::toLowerCase).collect(Collectors.toSet());
        assertTrue(keywords.contains("public"));
        assertTrue(keysOfType(result, "table").isEmpty());
        assertTrue(keysOfType(result, "column").isEmpty());
    }

    @Test
    @DisplayName("truncate_table: tableview_name - phải gợi ý đúng bảng thật")
    void truncateTableSuggestsRealTables() {
        var result = suggest("TRUNCATE TABLE |");
        assertFalse(keysOfType(result, "table").isEmpty());
    }

    @Test
    @DisplayName("drop_table: tableview_name - phải gợi ý đúng bảng thật")
    void dropTableSuggestsRealTables() {
        var result = suggest("DROP TABLE |");
        assertFalse(keysOfType(result, "table").isEmpty());
    }

    @Test
    @DisplayName("purge_statement 'PURGE TABLE |': id_expression là tên object TRONG RECYCLE BIN (đã bị drop) - schema fixture KHÔNG model khái niệm này, nên KHÔNG có dữ liệu thật nào để gợi ý (rỗng là đúng, không phải thiếu sót)")
    void purgeTableHasNoRealDataSource() {
        var result = suggest("PURGE TABLE |");
        assertTrue(result.isEmpty(),
                "Recycle bin không được model hoá trong SchemaIndex - không nên tự bịa ra bảng SỐNG nào ở đây");
    }

    // ---- Các bug THẬT phát hiện qua đọc grammar (KHÔNG sửa code, chỉ report qua test fail) ----

    @Test
    @DisplayName("BUG THẬT: 'BEGIN CLOSE |END;' (close_statement: CLOSE cursor_name) - cursor_name KHÔNG liên quan gì tới kiểu dữ liệu, nhưng datatype vẫn bị gợi ý lẫn vào (NUMBER/VARCHAR2/DATE/CHAR)")
    void closeStatementShouldNotSuggestDatatypes() {
        var result = suggest("BEGIN CLOSE |END;");
        assertTrue(keysOfType(result, "datatype").isEmpty(),
                "cursor_name không phải vị trí kiểu dữ liệu - đây là bug thật (typename/datatype rule bị khớp nhầm ở vị trí cursor_name), cần OracleSuggestionService xử lý ancestor-context riêng cho cursor_name giống cách đã làm cho MERGE target/JOIN USING");
    }

    @Test
    @DisplayName("BUG THẬT: 'BEGIN OPEN |END;' (open_statement: OPEN cursor_name (...)) - cùng lỗi datatype lẫn vào cursor_name như close_statement")
    void openStatementShouldNotSuggestDatatypes() {
        var result = suggest("BEGIN OPEN |END;");
        assertTrue(keysOfType(result, "datatype").isEmpty(),
                "cursor_name không phải vị trí kiểu dữ liệu - cùng bug với close_statement");
    }

    @Test
    @DisplayName("BUG THẬT (nhẹ): 'DROP VIEW |' (drop_view: DROP VIEW tableview_name) - tableview_name dùng CHUNG với bảng thật, nên TABLE (users/orders) bị gợi ý lẫn vào vị trí lẽ ra chỉ nên có VIEW - fixture không có view nào nên đáng lẽ phải RỖNG, không phải hiện bảng")
    void dropViewShouldNotSuggestTablesWhenNoViewsExist() {
        var result = suggest("DROP VIEW |");
        assertTrue(keysOfType(result, "table").isEmpty(),
                "DROP VIEW không nên gợi ý TABLE - tableview_name đang bị dùng chung không phân biệt kind, và fixture không có view nào nên kỳ vọng đúng là rỗng");
    }
}
