package com.sqlctx.completion;

import com.sqlctx.completion.support.CompletionExpectations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.sqlctx.completion.support.Completion.pg;
import static com.sqlctx.completion.support.CompletionFixtures.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phủ RỘNG: một entry cho MỖI alternative của rule top-level "stmt" trong
 * {@code PostgreSQLParser.g4} (125 loại statement, kể cả DDL/utility hiếm gặp). Case gọi tên RULE
 * NGỮ PHÁP CHÍNH XÁC trong tên test/comment để truy lại được đang xét dòng nào trong file .g4.
 * <p>
 * MỖI test khẳng định TOÀN BỘ danh sách gợi ý tại vị trí con trỏ - không chỉ "có chứa" (xem
 * {@link com.sqlctx.completion.support.CompletionExpectation}): mỗi loại khai báo phải khớp
 * CHÍNH XÁC, loại không khai báo phải rỗng, không được trùng, keyword khớp snapshot
 * ({@code src/test/resources/completion/keywords-*.txt}). Kiểm tra chạy tự động sau test bởi
 * {@link com.sqlctx.completion.support.CompletionExpectations}.
 * <p>
 * Vị trí KHÔNG có nguồn dữ liệu enumerable (tên object mới sắp tạo, literal chuỗi/số, option key
 * tuỳ driver, tên phiên/con trỏ...) cũng được khẳng định chính xác: mọi loại gợi ý dữ liệu phải
 * RỖNG - bản cũ chỉ assertDoesNotThrow nên không bắt được noise lọt vào (vd ~525 keyword ở
 * OPTIONS (|).
 */
@ExtendWith(CompletionExpectations.class)
@ExtendWith(CompletionExpectations.class)
class PostgresGrammarBreadthTest {

    // =====================================================================
    // A. Vị trí có RULE NGỮ PHÁP tham chiếu ROLE thật (rolespec) - phải
    // gợi ý ĐÚNG 3 role trong fixture, không thiếu không thừa.
    // Grammar: rolespec, role_list - dùng ở GRANT/REVOKE/OWNER TO/ALTER ROLE...
    // =====================================================================

    @Test
    @DisplayName("alterdefaultprivilegesstmt: defacl_privilege_target grant ... TO role_list - phải có role thật (+ GROUP là keyword hợp lệ)")
    void alterdefaultprivilegesstmt() {
        pg("alter default privileges in schema public grant select on tables to |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("altergroupstmt: ALTER GROUP name ADD USER role_list - phải đúng role thật")
    void altergroupstmt() {
        pg("alter group grp1 add user |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("alterownerstmt: OWNER TO rolespec (via any object owner clause) - phải đúng role thật")
    void alterownerstmt() {
        pg("alter aggregate agg1(int4) owner to |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("createtablespacestmt: CREATE TABLESPACE name OWNER rolespec - phải đúng role thật")
    void createtablespacestmt() {
        pg("create tablespace ts2 owner |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("createdbstmt: createdb_opt_item OWNER -> nonreservedword_or_sconst - phải có role thật (+ TRUE/FALSE/ON/DEFAULT là alternative hợp lệ khác của cùng rule)")
    void createdbstmt() {
        pg("create database db2 owner |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("dropownedstmt: DROP OWNED BY role_list - phải đúng role thật")
    void dropownedstmt() {
        pg("drop owned by |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("droprolestmt: DROP ROLE role_list - phải đúng role thật (+ IF EXISTS)")
    void droprolestmt() {
        pg("drop role |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("grantstmt: GRANT ... TO grantee_list (rolespec, + GROUP keyword hợp lệ) - phải đúng role thật")
    void grantstmt() {
        pg("grant select on public.users to |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("grantrolestmt: GRANT role_list TO role_list - phải đúng role thật")
    void grantrolestmt() {
        pg("grant role1 to |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("revokestmt: REVOKE ... FROM grantee_list - phải đúng role thật")
    void revokestmt() {
        pg("revoke select on public.users from |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("revokerolestmt: REVOKE role_list FROM role_list - phải đúng role thật")
    void revokerolestmt() {
        pg("revoke role1 from |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("reassignownedstmt: REASSIGN OWNED BY role_list TO rolespec - phải đúng role thật")
    void reassignownedstmt() {
        pg("reassign owned by role1 to |").roles(PG_ROLES);
    }

    // =====================================================================
    // B. Vị trí tham chiếu BẢNG/VIEW thật (any_name/qualified_name trỏ vào
    // 1 relation có sẵn) - phải ĐÚNG loại relation mà lệnh chấp nhận. Bảng
    // loại đã đối chiếu Postgres 18 thật (xem PostgresSuggestionService
    // .allowedRelationKinds) - vd DROP TABLE một view -> lỗi 42809.
    // =====================================================================

    @Test
    @DisplayName("alterextensioncontentsstmt: ALTER EXTENSION ... ADD TABLE any_name - chỉ bảng thường")
    void alterextensioncontentsstmt() {
        pg("alter extension ext1 add table |").tables(PG_TABLES)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("alterpublicationstmt: ALTER PUBLICATION ... ADD TABLE - chỉ bảng thường (publication không nhận view/materialized view)")
    void alterpublicationstmt() {
        pg("alter publication pub1 add table |").tables(PG_TABLES)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("analyzestmt: ANALYZE qualified_name - bảng + materialized view (view bị Postgres bỏ qua)")
    void analyzestmt() {
        pg("analyze |").tables(PG_TABLES).materializedViews(PG_MATVIEWS)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("createpublicationstmt: CREATE PUBLICATION ... FOR TABLE - chỉ bảng thường (publication không nhận view/materialized view)")
    void createpublicationstmt() {
        pg("create publication pub1 for table |").tables(PG_TABLES)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("dropstmt: DROP TABLE any_name_list - chỉ bảng thường (view/materialized view -> lỗi 42809)")
    void dropstmt() {
        pg("drop table |").tables(PG_TABLES)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("refreshmatviewstmt: REFRESH MATERIALIZED VIEW qualified_name - CHỈ materialized view")
    void refreshmatviewstmt() {
        pg("refresh materialized view |").materializedViews(PG_MATVIEWS)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("reindexstmt: REINDEX TABLE qualified_name - bảng + materialized view (view -> lỗi 42809)")
    void reindexstmt() {
        pg("reindex table |").tables(PG_TABLES).materializedViews(PG_MATVIEWS)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("vacuumstmt: VACUUM qualified_name - bảng + materialized view (view bị Postgres bỏ qua)")
    void vacuumstmt() {
        pg("vacuum |").tables(PG_TABLES).materializedViews(PG_MATVIEWS)
                .schemas(PG_SCHEMAS);
    }

    // =====================================================================
    // C. Vị trí SELECT-list / biểu thức chung (cột + hàm + kiểu đều hợp lệ,
    // rất nhiều keyword biểu thức khác cũng hợp lệ - breadth CHÍNH ĐÁNG,
    // không phải noise) - assert dữ liệu THẬT có mặt, không assert exact-set.
    // =====================================================================

    @Test
    @DisplayName("selectstmt: target_list biểu thức - phải có cột/hàm/kiểu thật của users")
    void selectstmt() {
        pg("select | from public.users")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("createasstmt: CREATE TABLE AS SELECT target_list - giống selectstmt")
    void createasstmt() {
        pg("create table t2 as select | from public.users")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("creatematviewstmt: CREATE MATERIALIZED VIEW AS SELECT target_list - giống selectstmt")
    void creatematviewstmt() {
        pg("create materialized view mv1 as select | from public.users")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("viewstmt: CREATE VIEW AS SELECT target_list - giống selectstmt")
    void viewstmt() {
        pg("create view v1 as select | from public.users")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("declarecursorstmt: DECLARE CURSOR FOR SELECT target_list - giống selectstmt")
    void declarecursorstmt() {
        pg("declare cur1 cursor for select | from public.users")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("explainstmt: EXPLAIN SELECT target_list - giống selectstmt")
    void explainstmt() {
        pg("explain select | from public.users")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("preparestmt: PREPARE ... AS SELECT target_list - giống selectstmt")
    void preparestmt() {
        pg("prepare p1 as select | from public.users")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("deletestmt: DELETE ... WHERE a_expr - phải có cột thật của users trong biểu thức điều kiện")
    void deletestmt() {
        pg("delete from public.users where |")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("updatestmt: UPDATE ... SET target - phải có cột thật để gán giá trị")
    void updatestmt() {
        pg("update public.users set |").columns("email", "id", "name");
    }

    @Test
    @DisplayName("indexstmt: CREATE INDEX ... (index_elem) - cột thật + hàm/kiểu (index theo biểu thức hợp lệ)")
    void indexstmt() {
        pg("create index idx1 on public.users (|").columns("email", "id", "name").functions(PG_FUNCTIONS);
    }

    @Test
    @DisplayName("alterpolicystmt: ALTER POLICY ... USING (a_expr) - cột thật của users trong biểu thức boolean")
    void alterpolicystmt() {
        pg("alter policy pol1 on public.users using (|")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("createpolicystmt: CREATE POLICY ... USING (a_expr) - cột thật của users")
    void createpolicystmt() {
        pg("create policy pol1 on public.users using (|")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("mergestmt: WHEN MATCHED THEN UPDATE SET - CHỈ cột bảng ĐÍCH (orders), KHÔNG lẫn cột users (nguồn)")
    void mergestmt() {
        pg("merge into public.orders o using public.users u on o.user_id = u.id when matched then update set |")
                .columns("customer_id", "id", "status", "total", "user_id");
    }

    // =====================================================================
    // D. Vị trí tham chiếu HÀM/KIỂU đã có (func_name/type_function_name/
    // typename). Đã tách: func_name (DROP FUNCTION, EXECUTE FUNCTION...) -> CHỈ
    // hàm; RETURNS/đối số hàm -> CHỈ kiểu. Riêng def_arg (sfunc = |, restrict
    // = |) vẫn cả hai vì rule không phân biệt được tham số nhận hàm hay kiểu.
    // =====================================================================

    @Test
    @DisplayName("createfunctionstmt: RETURNS func_type (typename) - phải có datatype thật")
    void createfunctionstmt() {
        pg("create function f1() returns |").datatypes(PG_TYPE_NAMES);
    }

    @Test
    @DisplayName("altercompositetypestmt: ADD ATTRIBUTE a typename - phải có datatype thật, CHƯA gõ tên attribute nên chưa tới lượt kiểu... thực ra ĐÃ gõ 'a' rồi nên đúng vị trí typename")
    void altercompositetypestmt() {
        pg("alter type mytype add attribute a |").datatypes(PG_TYPE_NAMES);
    }

    @Test
    @DisplayName("createdomainstmt: CREATE DOMAIN AS typename - phải có datatype thật")
    void createdomainstmt() {
        pg("create domain d1 as |").datatypes(PG_TYPE_NAMES);
    }

    @Test
    @DisplayName("removeoperstmt: DROP OPERATOR = (int4, typename) - phải có datatype thật cho tham số thứ 2")
    void removeoperstmt() {
        pg("drop operator = (int4, |").datatypes(PG_TYPE_NAMES);
    }

    @Test
    @DisplayName("removeaggrstmt: DROP AGGREGATE agg1(typename) - phải có datatype thật cho kiểu tham số")
    void removeaggrstmt() {
        pg("drop aggregate agg1(|").datatypes(PG_TYPE_NAMES);
    }

    @Test
    @DisplayName("definestmt: CREATE AGGREGATE (sfunc = func_name) - phải có function thật")
    void definestmt() {
        pg("create aggregate agg2(int4) (sfunc = |").functions(PG_FUNCTIONS).datatypes(PG_TYPE_NAMES);
    }

    @Test
    @DisplayName("alteroperatorstmt: SET (restrict = func_name) - phải có function thật")
    void alteroperatorstmt() {
        pg("alter operator = (int4, int4) set (restrict = |").functions(PG_FUNCTIONS).datatypes(PG_TYPE_NAMES);
    }

    @Test
    @DisplayName("removefuncstmt: DROP FUNCTION func_name - phải có function thật")
    void removefuncstmt() {
        pg("drop function |").functions(PG_FUNCTIONS);
    }

    @Test
    @DisplayName("createcaststmt: CREATE CAST ... WITH FUNCTION func_name - phải có function thật")
    void createcaststmt() {
        pg("create cast (int4 as text) with function |").functions(PG_FUNCTIONS);
    }

    @Test
    @DisplayName("createtrigstmt: CREATE TRIGGER ... EXECUTE FUNCTION func_name - phải có function thật")
    void createtrigstmt() {
        pg("create trigger trg2 before insert on public.users execute function |").functions(PG_FUNCTIONS);
    }

    @Test
    @DisplayName("createeventtrigstmt: CREATE EVENT TRIGGER ... EXECUTE FUNCTION func_name - phải có function thật")
    void createeventtrigstmt() {
        pg("create event trigger et2 on ddl_command_start execute function |").functions(PG_FUNCTIONS);
    }

    @Test
    @DisplayName("callstmt: CALL func_application(a_expr) - vị trí biểu thức đối số, hàm/kiểu thật hợp lệ")
    void callstmt() {
        pg("call myproc(|").functions(PG_FUNCTIONS).datatypes(PG_DATATYPES);
    }

    @Test
    @DisplayName("createassertionstmt: CREATE ASSERTION CHECK (a_expr) - biểu thức boolean, hàm/kiểu thật hợp lệ")
    void createassertionstmt() {
        pg("create assertion a1 check (|").functions(PG_FUNCTIONS).datatypes(PG_DATATYPES);
    }

    @Test
    @DisplayName("createstatsstmt: CREATE STATISTICS ... ON (expr) - biểu thức, hàm/kiểu thật hợp lệ")
    void createstatsstmt() {
        pg("create statistics st1 on |").functions(PG_FUNCTIONS).datatypes(PG_DATATYPES);
    }

    // =====================================================================
    // E. Vị trí chờ ĐỊNH DANH MỚI (object sắp được tạo/đặt tên lại) - KHÔNG
    // được lộ ra gợi ý dữ liệu thật nào (bug điển hình đã tìm thấy:
    // altertablestmt ADD COLUMN từng vi phạm điều này).
    // =====================================================================

    @Test
    @DisplayName("altertablestmt: ADD COLUMN | (tên cột MỚI, colid CHƯA xong) - grammar columnDef: colid typename tuần tự, chưa tới lượt typename")
    void altertablestmt() {
        pg("alter table public.users add column |");
    }

    @Test
    @DisplayName("createschemastmt: CREATE SCHEMA IF NOT EXISTS | (tên schema MỚI chưa tồn tại) - không lộ dữ liệu thật")
    void createschemastmt() {
        pg("create schema if not exists |");
    }

    @Test
    @DisplayName("renamestmt: RENAME TO | (tên bảng MỚI sau khi đổi tên, chưa tồn tại) - không lộ dữ liệu thật")
    void renamestmt() {
        pg("alter table public.users rename to |");
    }

    @Test
    @DisplayName("createstmt: CREATE TABLE t3 (a int4, tên CỘT MỚI ở vị trí đầu) - chưa gõ gì, chỉ nên là keyword LIKE/CHECK/CONSTRAINT..., không lộ dữ liệu thật")
    void createstmt() {
        pg("create table t3 (|");
    }

    @Test
    @DisplayName("insertstmt: INSERT INTO t (col_list) - insert_column_item: colid CHỈ LÀ ĐỊNH DANH THUẦN, KHÔNG PHẢI biểu thức -> KHÔNG được có function ở đây (bug thật: hiện có count/sum/avg/now lẫn vào)")
    void insertstmt() {
        pg("insert into public.users (|").columns("email", "id", "name");
    }

    // =====================================================================
    // F. Bug thật khác (đã đọc grammar xác nhận, KHÔNG phải noise-tolerance)
    // =====================================================================

    @Test
    @DisplayName("createconversionstmt: CREATE CONVERSION ... FROM any_name - any_name ở đây là TÊN HÀM chuyển đổi encoding, KHÔNG PHẢI bảng (bug thật: hiện gợi ý bảng)")
    void createconversionstmt() {
        pg("create conversion conv1 for 'UTF8' to 'LATIN1' from |");
    }

    // =====================================================================
    // G. Giá trị tuỳ ý THẬT SỰ không có nguồn dữ liệu để gợi ý (string/số
    // literal, option key theo driver/extension, tên phiên/session-only...)
    // - assert KHÔNG có gợi ý dữ liệu schema nào (mọi loại rỗng), keyword khớp
    //   snapshot.
    // =====================================================================

    @Test
    @DisplayName("altereventtrigstmt: enable_trigger (ENABLE/DISABLE) + OWNER TO/RENAME TO là rule SIBLING (alterownerstmt/renamestmt) cùng khớp tiền tố - không gợi ý dữ liệu schema nào")
    void altereventtrigstmt() {
        pg("alter event trigger trg1 |");
    }

    @Test
    @DisplayName("altercollationstmt: REFRESH VERSION - chỉ keyword thuần, không gợi ý dữ liệu schema nào")
    void altercollationstmt() {
        pg("alter collation c1 |");
    }

    @Test
    @DisplayName("alterdatabasestmt: SET TABLESPACE name - tablespace thật từ pg_tablespace (token TABLESPACE ngay trước caret, không đa nghĩa như ROLE/USER/GROUP_P nên wire riêng được, không cần đụng tới rule 'name' dùng chung khắp nơi)")
    void alterdatabasestmt() {
        pg("alter database db1 set tablespace |").tablespaces(PG_TABLESPACES);
    }

    @Test
    @DisplayName("alterdatabasesetstmt: SET search_path TO value - generic GUC value (mọi tham số cấu hình dùng chung 1 rule), không riêng cho search_path - không gợi ý dữ liệu schema nào")
    void alterdatabasesetstmt() {
        pg("alter database db1 set search_path to |");
    }

    @Test
    @DisplayName("alterdomainstmt: VALIDATE CONSTRAINT name - tên CONSTRAINT không model hoá trong SchemaIndex - không gợi ý dữ liệu schema nào")
    void alterdomainstmt() {
        pg("alter domain d1 validate constraint |");
    }

    @Test
    @DisplayName("alterenumstmt: ADD VALUE 'x' BEFORE sconst - chờ STRING LITERAL, không phải định danh - không gợi ý dữ liệu schema nào")
    void alterenumstmt() {
        pg("alter type status_enum add value 'x' before |");
    }

    @Test
    @DisplayName("alterextensionstmt: UPDATE TO nonreservedword_or_sconst - version string tuỳ ý của extension - không gợi ý dữ liệu schema nào")
    void alterextensionstmt() {
        pg("alter extension ext1 update to |");
    }

    @Test
    @DisplayName("alterfdwstmt: OPTIONS (key 'value') - option key tuỳ theo FDW driver, không có nguồn dữ liệu enumerable - không gợi ý dữ liệu schema nào (giới hạn đã biết)")
    void alterfdwstmt() {
        pg("alter foreign data wrapper fdw1 options (|");
    }

    @Test
    @DisplayName("alterforeignserverstmt: OPTIONS (...) - tương tự alterfdwstmt, không gợi ý dữ liệu schema nào")
    void alterforeignserverstmt() {
        pg("alter server srv1 options (|");
    }

    @Test
    @DisplayName("alterfunctionstmt: alterfunc_opt_item - toàn keyword (STRICT/COST/ROWS...), không có dữ liệu thật cần gợi ý - không gợi ý dữ liệu schema nào")
    void alterfunctionstmt() {
        pg("alter function f1(int4) |");
    }

    @Test
    @DisplayName("alterobjectdependsstmt: DEPENDS ON EXTENSION name - tên extension không model hoá - không gợi ý dữ liệu schema nào")
    void alterobjectdependsstmt() {
        pg("alter function f1(int4) depends on extension |");
    }

    @Test
    @DisplayName("alterobjectschemastmt: SET SCHEMA name - tên schema qua rule 'name' dùng chung, không gợi ý dữ liệu schema nào (giới hạn đã biết)")
    void alterobjectschemastmt() {
        pg("alter table public.users set schema |");
    }

    @Test
    @DisplayName("altertypestmt: SET (...) option list - option key tuỳ loại (storage/receive/send...), không gợi ý dữ liệu schema nào")
    void altertypestmt() {
        pg("alter type mytype set (|");
    }

    @Test
    @DisplayName("alterseqstmt: RESTART WITH numeric - chờ SỐ, không phải định danh - không gợi ý dữ liệu schema nào")
    void alterseqstmt() {
        pg("alter sequence s1 restart with |");
    }

    @Test
    @DisplayName("altersystemstmt: SET generic_set - tên GUC parameter tuỳ ý - không gợi ý dữ liệu schema nào")
    void altersystemstmt() {
        pg("alter system set |");
    }

    @Test
    @DisplayName("altertblspcstmt: SET (...) option list - option tablespace tuỳ storage driver, không gợi ý dữ liệu schema nào")
    void altertblspcstmt() {
        pg("alter tablespace ts1 set (|");
    }

    @Test
    @DisplayName("alterrolesetstmt: SET search_path TO value - generic GUC, không gợi ý dữ liệu schema nào")
    void alterrolesetstmt() {
        pg("alter role role1 set search_path to |");
    }

    @Test
    @DisplayName("alterrolestmt: alteroptrolelist - toàn keyword thuộc tính role (SUPERUSER/LOGIN...), không gợi ý dữ liệu schema nào")
    void alterrolestmt() {
        pg("alter role role1 with |");
    }

    @Test
    @DisplayName("altersubscriptionstmt: SET PUBLICATION publication_name_list - route qua rule collabel (đã thêm vào PREFERRED_RULES - hết tràn ~525 keyword), không có nguồn tên publication nên không gợi ý gì")
    void altersubscriptionstmt() {
        pg("alter subscription sub1 set publication |");
    }

    @Test
    @DisplayName("alterstatsstmt: SET STATISTICS iconst - chờ SỐ, không gợi ý dữ liệu schema nào")
    void alterstatsstmt() {
        pg("alter statistics stat1 set statistics |");
    }

    @Test
    @DisplayName("altertsconfigurationstmt: ADD MAPPING FOR name_list - tên token-type của text search, không model hoá - không gợi ý dữ liệu schema nào")
    void altertsconfigurationstmt() {
        pg("alter text search configuration tsc1 add mapping for |");
    }

    @Test
    @DisplayName("altertsdictionarystmt: (definition) - option key tuỳ dictionary template, không gợi ý dữ liệu schema nào")
    void altertsdictionarystmt() {
        pg("alter text search dictionary tsd1 (|");
    }

    @Test
    @DisplayName("alterusermappingstmt: OPTIONS (...) - tương tự alterfdwstmt, không gợi ý dữ liệu schema nào")
    void alterusermappingstmt() {
        pg("alter user mapping for user1 server srv1 options (|");
    }

    @Test
    @DisplayName("checkpointstmt: không tham số nào - không gợi ý dữ liệu schema nào")
    void checkpointstmt() {
        pg("checkpoint |");
    }

    @Test
    @DisplayName("closeportalstmt: CLOSE cursor_name - tên cursor phiên hiện tại, không model hoá - không gợi ý dữ liệu schema nào")
    void closeportalstmt() {
        pg("close |");
    }

    @Test
    @DisplayName("clusterstmt: USING index_name - tên index không model hoá trong SchemaIndex - không gợi ý dữ liệu schema nào")
    void clusterstmt() {
        pg("cluster public.users using |");
    }

    @Test
    @DisplayName("commentstmt: IS sconst - chờ STRING LITERAL - không gợi ý dữ liệu schema nào")
    void commentstmt() {
        pg("comment on table public.users is |");
    }

    @Test
    @DisplayName("constraintssetstmt: SET CONSTRAINTS ALL (DEFERRED|IMMEDIATE) - toàn keyword, không gợi ý dữ liệu schema nào")
    void constraintssetstmt() {
        pg("set constraints all |");
    }

    @Test
    @DisplayName("copystmt: COPY ... TO (STDOUT|PROGRAM|filename literal) - không gợi ý dữ liệu schema nào")
    void copystmt() {
        pg("copy public.users to |");
    }

    @Test
    @DisplayName("createamstmt: TYPE INDEX HANDLER handler_name - handler_name route qua rule 'name' dùng chung, không gợi ý dữ liệu schema nào (giới hạn đã biết)")
    void createamstmt() {
        pg("create access method am1 type index handler |");
    }

    @Test
    @DisplayName("createextensionstmt: SCHEMA name - route qua rule 'name' dùng chung, không gợi ý dữ liệu schema nào (giới hạn đã biết)")
    void createextensionstmt() {
        pg("create extension ext1 schema |");
    }

    @Test
    @DisplayName("createfdwstmt: HANDLER handler_name - route qua rule 'name' dùng chung, không gợi ý dữ liệu schema nào (giới hạn đã biết)")
    void createfdwstmt() {
        pg("create foreign data wrapper fdw1 handler |");
    }

    @Test
    @DisplayName("createforeignserverstmt: FOREIGN DATA WRAPPER name - tên FDW không model hoá - không gợi ý dữ liệu schema nào")
    void createforeignserverstmt() {
        pg("create server srv1 foreign data wrapper |");
    }

    @Test
    @DisplayName("createforeigntablestmt: SERVER name - tên foreign server không model hoá - không gợi ý dữ liệu schema nào")
    void createforeigntablestmt() {
        pg("create foreign table ft1 (id int4) server |");
    }

    @Test
    @DisplayName("creategroupstmt: OptRoleList - toàn keyword thuộc tính role, không gợi ý dữ liệu schema nào")
    void creategroupstmt() {
        pg("create group grp1 with |");
    }

    @Test
    @DisplayName("createopclassstmt: FOR TYPE ... USING ... AS (opclass_item_list) - toàn keyword STORAGE/FUNCTION/OPERATOR, không gợi ý dữ liệu schema nào")
    void createopclassstmt() {
        pg("create operator class oc1 for type int4 using btree as |");
    }

    @Test
    @DisplayName("createopfamilystmt: USING access_method - tên access method (btree/hash/gin...) không model hoá - không gợi ý dữ liệu schema nào")
    void createopfamilystmt() {
        pg("create operator family of1 using |");
    }

    @Test
    @DisplayName("alteropfamilystmt: ADD opclass_item_list - toàn keyword, không gợi ý dữ liệu schema nào")
    void alteropfamilystmt() {
        pg("alter operator family of1 using btree add |");
    }

    @Test
    @DisplayName("createplangstmt: HANDLER keyword vị trí (chưa tới function name) - không gợi ý dữ liệu schema nào")
    void createplangstmt() {
        pg("create language plpgsql2 |");
    }

    @Test
    @DisplayName("createseqstmt: INCREMENT BY numeric - chờ SỐ - không gợi ý dữ liệu schema nào")
    void createseqstmt() {
        pg("create sequence s2 increment by |");
    }

    @Test
    @DisplayName("createsubscriptionstmt: PUBLICATION publication_name_list - route qua rule collabel (đã thêm vào PREFERRED_RULES - hết tràn ~525 keyword), không có nguồn tên publication nên không gợi ý gì")
    void createsubscriptionstmt() {
        pg("create subscription sub2 connection 'host=x' publication |");
    }

    @Test
    @DisplayName("createtransformstmt: LANGUAGE lang_name (opt_definition) - vị trí sau LANGUAGE tới definition list, option key tuỳ ý - không gợi ý dữ liệu schema nào")
    void createtransformstmt() {
        pg("create transform for int4 language plpgsql2 (|");
    }

    @Test
    @DisplayName("createrolestmt: OptRoleList - toàn keyword thuộc tính role, không gợi ý dữ liệu schema nào")
    void createrolestmt() {
        pg("create role role2 with |");
    }

    @Test
    @DisplayName("createuserstmt: OptRoleList (CREATE USER = CREATE ROLE với LOGIN mặc định) - không gợi ý dữ liệu schema nào")
    void createuserstmt() {
        pg("create user user2 with |");
    }

    @Test
    @DisplayName("createusermappingstmt: OPTIONS (...) - tương tự alterfdwstmt, không gợi ý dữ liệu schema nào")
    void createusermappingstmt() {
        pg("create user mapping for user2 server srv1 options (|");
    }

    @Test
    @DisplayName("deallocatestmt: DEALLOCATE name|ALL - tên prepared statement phiên hiện tại, không model hoá - không gợi ý dữ liệu schema nào")
    void deallocatestmt() {
        pg("deallocate |");
    }

    @Test
    @DisplayName("discardstmt: DISCARD (ALL|PLANS|TEMP|SEQUENCES) - toàn keyword, không gợi ý dữ liệu schema nào")
    void discardstmt() {
        pg("discard |");
    }

    @Test
    @DisplayName("dostmt: LANGUAGE nonreservedword_or_sconst - phải có tên ngôn ngữ thật (plpgsql/sql)")
    void dostmt() {
        pg("do $$ begin null; end; $$ language |").others(PG_LANGUAGES);
    }

    @Test
    @DisplayName("dropcaststmt: CASCADE|RESTRICT - toàn keyword, không gợi ý dữ liệu schema nào")
    void dropcaststmt() {
        pg("drop cast (int4 as text) |");
    }

    @Test
    @DisplayName("dropopclassstmt: USING access_method - tên access method không model hoá - không gợi ý dữ liệu schema nào")
    void dropopclassstmt() {
        pg("drop operator class oc1 using |");
    }

    @Test
    @DisplayName("dropopfamilystmt: USING access_method - tương tự dropopclassstmt, không gợi ý dữ liệu schema nào")
    void dropopfamilystmt() {
        pg("drop operator family of1 using |");
    }

    @Test
    @DisplayName("dropsubscriptionstmt: DROP SUBSCRIPTION name - tên subscription không model hoá - không gợi ý dữ liệu schema nào")
    void dropsubscriptionstmt() {
        pg("drop subscription |");
    }

    @Test
    @DisplayName("droptablespacestmt: DROP TABLESPACE name - tablespace thật từ pg_tablespace (cùng cơ chế alterdatabasestmt)")
    void droptablespacestmt() {
        pg("drop tablespace |").tablespaces(PG_TABLESPACES);
    }

    @Test
    @DisplayName("droptransformstmt: FOR type_name LANGUAGE name - tên ngôn ngữ ở vị trí này chưa wire (khác dostmt dùng nonreservedword_or_sconst) - không gợi ý dữ liệu schema nào")
    void droptransformstmt() {
        pg("drop transform for int4 language |");
    }

    @Test
    @DisplayName("dropusermappingstmt: SERVER name - tên foreign server không model hoá - không gợi ý dữ liệu schema nào")
    void dropusermappingstmt() {
        pg("drop user mapping for user2 server |");
    }

    @Test
    @DisplayName("dropdbstmt: DROP DATABASE (IF EXISTS)? name - tên DB MỚI/khác session hiện tại, không model hoá đối tượng database - không gợi ý dữ liệu schema nào")
    void dropdbstmt() {
        pg("drop database |");
    }

    @Test
    @DisplayName("executestmt: EXECUTE name - tên prepared statement phiên hiện tại, không model hoá - không gợi ý dữ liệu schema nào")
    void executestmt() {
        pg("execute |");
    }

    @Test
    @DisplayName("fetchstmt: FETCH NEXT FROM cursor_name - tên cursor phiên hiện tại, không model hoá - không gợi ý dữ liệu schema nào")
    void fetchstmt() {
        pg("fetch next from |");
    }

    @Test
    @DisplayName("importforeignschemastmt: FROM SERVER name INTO name - tên schema đích route qua rule 'name' dùng chung, không gợi ý dữ liệu schema nào (giới hạn đã biết)")
    void importforeignschemastmt() {
        pg("import foreign schema public from server srv1 into |");
    }

    @Test
    @DisplayName("listenstmt: LISTEN channel_name - tên channel tuỳ ý, không model hoá - không gợi ý dữ liệu schema nào")
    void listenstmt() {
        pg("listen |");
    }

    @Test
    @DisplayName("loadstmt: LOAD file_name (sconst) - chờ STRING LITERAL đường dẫn file - không gợi ý dữ liệu schema nào")
    void loadstmt() {
        pg("load |");
    }

    @Test
    @DisplayName("lockstmt: IN lock_type MODE - toàn keyword chế độ khoá (ROW/SHARE/ACCESS EXCLUSIVE...), không gợi ý dữ liệu schema nào")
    void lockstmt() {
        pg("lock table public.users in |");
    }

    @Test
    @DisplayName("notifystmt: NOTIFY channel_name - tên channel tuỳ ý, không model hoá - không gợi ý dữ liệu schema nào")
    void notifystmt() {
        pg("notify |");
    }

    @Test
    @DisplayName("unlistenstmt: UNLISTEN (channel_name|*) - tên channel tuỳ ý, không gợi ý dữ liệu schema nào")
    void unlistenstmt() {
        pg("unlisten |");
    }

    @Test
    @DisplayName("rulestmt: CREATE RULE ... AS ON INSERT TO table DO (NOTHING|action) - vị trí action là 1 statement lồng, không gợi ý dữ liệu schema nào")
    void rulestmt() {
        pg("create rule r1 as on insert to public.users do |");
    }

    @Test
    @DisplayName("seclabelstmt: IS sconst - chờ STRING LITERAL - không gợi ý dữ liệu schema nào")
    void seclabelstmt() {
        pg("security label on table public.users is |");
    }

    @Test
    @DisplayName("transactionstmt: START TRANSACTION transaction_mode_list - toàn keyword (READ WRITE/ISOLATION LEVEL...), không gợi ý dữ liệu schema nào")
    void transactionstmt() {
        pg("start transaction |");
    }

    @Test
    @DisplayName("truncatestmt: TRUNCATE TABLE ... opt_restart_seqs - toàn keyword sau khi bảng đã được chỉ định, không gợi ý dữ liệu schema nào")
    void truncatestmt() {
        pg("truncate table public.users |");
    }

    @Test
    @DisplayName("variableresetstmt: RESET generic_reset - tên GUC parameter tuỳ ý (+ ALL keyword), không gợi ý dữ liệu schema nào")
    void variableresetstmt() {
        pg("reset |");
    }

    @Test
    @DisplayName("variablesetstmt: SET var_name TO value - generic GUC, không gợi ý dữ liệu schema nào")
    void variablesetstmt() {
        pg("set search_path to |");
    }

    @Test
    @DisplayName("variableshowstmt: SHOW var_name - tên GUC parameter tuỳ ý (+ ALL keyword), không gợi ý dữ liệu schema nào")
    void variableshowstmt() {
        pg("show |");
    }

    @Test
    @DisplayName("plsqlconsolecommand: MetaCommand (\\\\d, \\\\l...) - cú pháp riêng của CLI, không thuộc SQL chuẩn - không gợi ý dữ liệu schema nào")
    void plsqlconsolecommand() {
        pg("\\d |");
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
