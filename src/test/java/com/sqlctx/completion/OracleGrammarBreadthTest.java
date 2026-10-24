package com.sqlctx.completion;

import com.sqlctx.completion.support.CompletionExpectations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.sqlctx.completion.support.Completion.ora;
import static com.sqlctx.completion.support.CompletionFixtures.*;

/**
 * Phủ RỘNG: 1 test cho MỖI alternative của {@code unit_statement}/{@code sql_statement} trong
 * PlSqlParser.g4 (kể cả tính năng DBA hiếm gặp: diskgroup, PMEM filestore, lockdown profile,
 * edition...) - khác {@link OracleSuggestionServiceTest} (SÂU: đúng thứ tự xếp hạng cho các luồng
 * gõ phổ biến nhất).
 * <p>
 * MỖI test khẳng định TOÀN BỘ danh sách gợi ý tại vị trí con trỏ - không chỉ "có chứa" (xem
 * {@link com.sqlctx.completion.support.CompletionExpectation}): mỗi loại khai báo phải khớp
 * CHÍNH XÁC, loại không khai báo phải rỗng, không được trùng, keyword khớp snapshot
 * ({@code src/test/resources/completion/keywords-*.txt}). Kiểm tra chạy tự động sau test bởi
 * {@link com.sqlctx.completion.support.CompletionExpectations}.
 * <p>
 * {@code grammarShapes()} chỉ gồm vị trí KHÔNG có dữ liệu schema enumerable - test tham số hoá
 * khẳng định mọi loại gợi ý dữ liệu RỖNG (bản cũ chỉ assertDoesNotThrow, và thực tế 19/~150 entry
 * CÓ dữ liệu - đã tách ra test riêng ở nhóm N/O).
 */
@ExtendWith(CompletionExpectations.class)
class OracleGrammarBreadthTest {

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
                Arguments.of("create_inmemory_join_group", "CREATE INMEMORY JOIN GROUP jg1 (users(id), orders(user_id))|"),
                Arguments.of("create_java", "CREATE JAVA SOURCE NAMED src1 AS class Foo {}|"),
                Arguments.of("create_library", "CREATE LIBRARY lib1 AS '/tmp/lib.so'|"),
                Arguments.of("create_lockdown_profile", "CREATE LOCKDOWN PROFILE lp1|"),
                Arguments.of("create_materialized_zonemap", "CREATE MATERIALIZED ZONEMAP zm1 ON users (id)|"),
                Arguments.of("create_operator", "CREATE OPERATOR op1 BINDING (NUMBER) RETURN NUMBER USING f1|"),
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
                Arguments.of("create_tablespace", "CREATE TABLESPACE ts1 DATAFILE 'ts1.dbf' SIZE 10M|"),
                Arguments.of("create_tablespace_set", "CREATE TABLESPACE SET tss1|"),
                Arguments.of("create_trigger", "CREATE TRIGGER trg1 BEFORE INSERT ON users FOR EACH ROW BEGIN NULL; END;|"),
                Arguments.of("create_type", "CREATE TYPE ty1 AS OBJECT (a NUMBER)|"),
                Arguments.of("create_user", "CREATE USER u1 IDENTIFIED BY pass1|"),

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
                Arguments.of("drop_tablespace", "DROP TABLESPACE ts1|"),
                Arguments.of("drop_tablespace_set", "DROP TABLESPACE SET tss1|"),
                Arguments.of("drop_trigger", "DROP TRIGGER trg1|"),
                Arguments.of("drop_type", "DROP TYPE ty1|"),
                Arguments.of("drop_user", "DROP USER u1|"),
                Arguments.of("drop_view", "DROP VIEW |"),

                // ===== Misc (unit_statement) =====
                Arguments.of("administer_key_management", "ADMINISTER KEY MANAGEMENT CREATE KEYSTORE '/tmp/wallet' IDENTIFIED BY pass1|"),
                Arguments.of("analyze", "ANALYZE TABLE users COMPUTE STATISTICS|"),
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
                Arguments.of("unified_auditing", "AUDIT POLICY p1|"),

                // ===== DML (data_manipulation_language_statements) =====
                Arguments.of("lock_table_statement", "LOCK TABLE users IN |"),

                // ===== cursor_manipulation_statements =====
                Arguments.of("close_statement", "BEGIN CLOSE |END;"),
                Arguments.of("open_statement", "BEGIN OPEN |END;"),
                Arguments.of("fetch_statement", "BEGIN FETCH c1 INTO |END;"),

                // ===== transaction_control_statements =====
                Arguments.of("set_transaction_command", "SET TRANSACTION READ ONLY|"),
                Arguments.of("set_constraint_command", "SET CONSTRAINT ALL |"),
                Arguments.of("commit_statement", "COMMIT|"),
                Arguments.of("rollback_statement", "ROLLBACK|"),
                Arguments.of("savepoint_statement", "SAVEPOINT sp1|")

                // ===== execute_immediate (sql_statement) - only valid inside a PL/SQL block =====
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("grammarShapes")
    @DisplayName("Vị trí KHÔNG có dữ liệu schema enumerable: không được gợi ý bảng/cột/hàm/kiểu nào, keyword khớp snapshot")
    void grammarShapeSuggestsNoSchemaData(String ruleName, String sqlWithCursor) {
        ora(sqlWithCursor);
    }

    // =========================================================================================
    // N. NỘI DUNG THẬT - các vị trí có nguồn dữ liệu enumerable thật (bảng/cột/hàm/kiểu dữ liệu
    // trong fixture), đọc grammar rồi assert đúng, KHÔNG chỉ "không crash". Đây là phần bù trực
    // tiếp cho lỗ hổng "assertDoesNotThrow không bắt được bug nội dung" đã phát hiện ở Postgres.
    // =========================================================================================

    @Test
    @DisplayName("create_index: 'CREATE INDEX idx1 ON users (|)' - index_expr: column_name|expression -> phải có đúng cột thật của users")
    void createIndexSuggestsRealColumns() {
        ora("CREATE INDEX idx1 ON users (|)")
                .columns("users.email", "users.id", "users.name")
                .functions(ORA_FUNCTIONS);
    }

    @Test
    @DisplayName("create_table: 'CREATE TABLE t1 (id |)' - column_definition: column_name (datatype|regular_id) -> tên cột ĐÃ gõ xong, phải có đúng datatype thật")
    void createTableColumnTypeSuggestsRealDatatypes() {
        ora("CREATE TABLE t1 (id |)").datatypes(ORA_DATATYPES);
    }

    @Test
    @DisplayName("alter_table MODIFY: cột trong ngoặc là cột ĐÃ TỒN TẠI (modify_col_properties: column_name...) -> phải gợi ý đúng cột thật của users")
    void alterTableModifySuggestsRealExistingColumns() {
        ora("ALTER TABLE users MODIFY (|)").columns("users.email", "users.id", "users.name");
    }

    @Test
    @DisplayName("merge_statement dot-mode 'u.|' - phải CHỈ ra đúng cột của users (alias u), không lẫn orders")
    void mergeStatementResolvesAliasColumns() {
        ora("MERGE INTO users u USING orders o ON (u.id = o.user_id) WHEN MATCHED THEN UPDATE SET u.|")
                .columns("u.email", "u.id", "u.name");
    }

    @Test
    @DisplayName("lock_table_statement: lock_mode chỉ có đúng 3 từ khoá bắt đầu hợp lệ (ROW/SHARE/EXCLUSIVE theo grammar)")
    void lockTableSuggestsRealLockModeKeywords() {
        ora("LOCK TABLE users IN |").keywordsInclude("row", "share", "exclusive");
    }

    @Test
    @DisplayName("select_statement 'FROM |' - phải gợi ý đúng bảng thật (users/orders), không rỗng")
    void selectFromSuggestsRealTables() {
        ora("SELECT * FROM |").tables(ORA_TABLES);
    }

    @Test
    @DisplayName("update_statement 'SET |' - phải gợi ý đúng cột thật của users")
    void updateSetSuggestsRealColumns() {
        ora("UPDATE users SET |").columns("users.email", "users.id", "users.name");
    }

    @Test
    @DisplayName("insert_statement column-list - phải gợi ý đúng cột thật của users")
    void insertColumnListSuggestsRealColumns() {
        ora("INSERT INTO users (|").columns("users.email", "users.id", "users.name");
    }

    @Test
    @DisplayName("explain_statement 'FOR SELECT * FROM |' - phải gợi ý đúng bảng thật")
    void explainStatementSuggestsRealTables() {
        ora("EXPLAIN PLAN FOR SELECT * FROM |").tables(ORA_TABLES);
    }

    @Test
    @DisplayName("grant_statement 'TO |' - grantee_name|PUBLIC: PUBLIC phải có mặt, KHÔNG được lẫn bảng/cột nào (grantee không phải object trong schema)")
    void grantToSuggestsPublicOnlyNoTableOrColumnLeak() {
        ora("GRANT SELECT ON users TO |").keywordsInclude("public");
    }

    @Test
    @DisplayName("truncate_table: tableview_name - phải gợi ý đúng bảng thật")
    void truncateTableSuggestsRealTables() {
        ora("TRUNCATE TABLE |").tables(ORA_TABLES);
    }

    @Test
    @DisplayName("drop_table: tableview_name - phải gợi ý đúng bảng thật")
    void dropTableSuggestsRealTables() {
        ora("DROP TABLE |").tables(ORA_TABLES);
    }

    @Test
    @DisplayName("purge_statement 'PURGE TABLE |': id_expression là tên object TRONG RECYCLE BIN (đã bị drop) - schema fixture KHÔNG model khái niệm này, nên KHÔNG có dữ liệu thật nào để gợi ý (rỗng là đúng, không phải thiếu sót)")
    void purgeTableHasNoRealDataSource() {
        ora("PURGE TABLE |");
    }

    // ---- Các bug THẬT phát hiện qua đọc grammar (KHÔNG sửa code, chỉ report qua test fail) ----

    @Test
    @DisplayName("BUG THẬT: 'BEGIN CLOSE |END;' (close_statement: CLOSE cursor_name) - cursor_name KHÔNG liên quan gì tới kiểu dữ liệu, nhưng datatype vẫn bị gợi ý lẫn vào (NUMBER/VARCHAR2/DATE/CHAR)")
    void closeStatementShouldNotSuggestDatatypes() {
        ora("BEGIN CLOSE |END;");
    }

    @Test
    @DisplayName("BUG THẬT: 'BEGIN OPEN |END;' (open_statement: OPEN cursor_name (...)) - cùng lỗi datatype lẫn vào cursor_name như close_statement")
    void openStatementShouldNotSuggestDatatypes() {
        ora("BEGIN OPEN |END;");
    }

    @Test
    @DisplayName("BUG THẬT (nhẹ): 'DROP VIEW |' (drop_view: DROP VIEW tableview_name) - tableview_name dùng CHUNG với bảng thật, nên TABLE (users/orders) bị gợi ý lẫn vào vị trí lẽ ra chỉ nên có VIEW - fixture không có view nào nên đáng lẽ phải RỖNG, không phải hiện bảng")
    void dropViewShouldNotSuggestTablesWhenNoViewsExist() {
        ora("DROP VIEW |");
    }

    // =========================================================================================
    // O. Các vị trí từng nằm trong grammarShapes() (chỉ assertDoesNotThrow) nhưng thật ra CÓ dữ liệu
    // schema - tách ra để assert chính xác.
    // =========================================================================================

    @Test
    @DisplayName("create_materialized_view: CREATE MATERIALIZED VIEW mv1 AS SELECT * FROM users|")
    void createMaterializedViewShape() {
        ora("CREATE MATERIALIZED VIEW mv1 AS SELECT * FROM users|").tables("naviq.users");
    }

    @Test
    @DisplayName("create_materialized_view_log: CREATE MATERIALIZED VIEW LOG ON users|")
    void createMaterializedViewLogShape() {
        ora("CREATE MATERIALIZED VIEW LOG ON users|").tables("naviq.users");
    }

    @Test
    @DisplayName("create_outline: CREATE OUTLINE ol1 ON SELECT * FROM users|")
    void createOutlineShape() {
        ora("CREATE OUTLINE ol1 ON SELECT * FROM users|").tables("naviq.users");
    }

    @Test
    @DisplayName("create_view: CREATE VIEW v1 AS SELECT * FROM users|")
    void createViewShape() {
        ora("CREATE VIEW v1 AS SELECT * FROM users|").tables("naviq.users");
    }

    @Test
    @DisplayName("drop_materialized_view_log: DROP MATERIALIZED VIEW LOG ON users|")
    void dropMaterializedViewLogShape() {
        ora("DROP MATERIALIZED VIEW LOG ON users|").tables("naviq.users");
    }

    @Test
    @DisplayName("anonymous_block: BEGIN NULL; |END;")
    void anonymousBlockShape() {
        ora("BEGIN NULL; |END;").functions(ORA_FUNCTIONS);
    }

    @Test
    @DisplayName("sql_call_statement: CALL proc1(|)")
    void sqlCallStatementShape() {
        ora("CALL proc1(|)").functions(ORA_FUNCTIONS);
    }

    @Test
    @DisplayName("delete_statement: DELETE FROM users WHERE |")
    void deleteStatementShape() {
        ora("DELETE FROM users WHERE |").columns("users.email", "users.id", "users.name").functions(ORA_FUNCTIONS);
    }

    @Test
    @DisplayName("open_for_statement: BEGIN OPEN v1 FOR SELECT * FROM |END;")
    void openForStatementShape() {
        ora("BEGIN OPEN v1 FOR SELECT * FROM |END;").tables(ORA_TABLES);
    }

    @Test
    @DisplayName("execute_immediate: BEGIN EXECUTE IMMEDIATE |END;")
    void executeImmediateShape() {
        ora("BEGIN EXECUTE IMMEDIATE |END;").functions(ORA_FUNCTIONS);
    }
}
