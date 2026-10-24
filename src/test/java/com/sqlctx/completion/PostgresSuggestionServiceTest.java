package com.sqlctx.completion;

import com.sqlctx.completion.support.CompletionExpectations;
import com.sqlctx.completion.ranking.CompletionHistory;
import com.sqlctx.schema.SchemaIndex;
import com.sqlctx.schema.SchemaObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static com.sqlctx.completion.support.Completion.pg;
import static com.sqlctx.completion.support.CompletionFixtures.*;

/**
 * Test gợi ý SQL Postgres (SÂU: xếp hạng, alias, scope, CTE/subquery/UNION) đi qua ĐÚNG đường
 * production ({@code suggests(PrepareCompletionInput)}, có {@code SuggestFilter} rank/lọc) - thứ
 * người dùng thực sự thấy trên màn hình khi gõ.
 * <p>
 * MỖI test khẳng định TOÀN BỘ danh sách gợi ý tại vị trí con trỏ - không chỉ "có chứa" (xem
 * {@link com.sqlctx.completion.support.CompletionExpectation}): mỗi loại khai báo phải khớp
 * CHÍNH XÁC, loại không khai báo phải rỗng, không được trùng, keyword khớp snapshot
 * ({@code src/test/resources/completion/keywords-*.txt}). Kiểm tra chạy tự động sau test bởi
 * {@link com.sqlctx.completion.support.CompletionExpectations}.
 * <p>
 * Nguồn của tập kỳ vọng cột/bảng: đối chiếu với Postgres 18 THẬT trên cùng schema fixture (thay
 * từng ứng viên vào vị trí con trỏ rồi chạy câu lệnh) - không phải suy đoán, cũng không chép
 * ngược output. Nhờ vậy đã phát hiện: gợi ý view ở DROP TABLE/TRUNCATE..., gợi ý "bảng.cột" ở
 * vị trí chỉ nhận tên cột trần (INSERT (…), SET, USING (…)), gợi ý trùng lặp.
 */
@ExtendWith(CompletionExpectations.class)
class PostgresSuggestionServiceTest {

    // =====================================================================
    // A. Prefix ranking thật - đối tượng schema thật phải thắng keyword chung
    // chung khi cả 2 cùng match prefix (đây chính là bug đã phát hiện+sửa
    // trong SuggestFilter.matchWord() khi viết bộ test này).
    // =====================================================================

    @Test
    @DisplayName("Gõ tên bảng có thật ('us') phải thắng keyword chung ('user') dù cả 2 cùng match prefix")
    void realTableBeatsGenericKeywordOnPrefix() {
        // "products" có chữ u, s nhưng không ở đầu từ - không phải thứ đang gõ
        pg("select * from us|").tables("public.users").views(PG_VIEWS).first("public.users");
    }

    @Test
    @DisplayName("Gõ tên cột không alias ('i') phải thắng keyword ('int'/'integer') dù cả 2 cùng match prefix")
    void realColumnBeatsGenericKeywordOnPrefix() {
        // fuzzy phải bắt đầu ở đầu từ: "email", "cmin", "numeric"... có chữ i giữa từ nên không khớp
        pg("select * from users where i|")
                .columns("users.id")
                .datatypes("int4")
                .first("users.id");
    }

    @Test
    @DisplayName("UPDATE ... SET <cột>: cột thật phải đứng đầu, không phải keyword")
    void updateSetPrefixRanksRealColumnFirst() {
        pg("update users set na|").columns("name").first("name");
    }

    @Test
    @DisplayName("Không phân biệt hoa/thường: 'USE' vẫn tìm ra bảng 'users' (SQL identifier không phân biệt hoa thường)")
    void tableMatchIsCaseInsensitive() {
        pg("select * from USE|").tables("public.users").views(PG_VIEWS).first("public.users");
    }

    // =====================================================================
    // B. Fuzzy/gõ thiếu chữ (không phải transposition - thuật toán là
    // subsequence, không phải edit-distance) vẫn phải tìm ra đúng bảng.
    // =====================================================================

    @Test
    @DisplayName("Gõ thiếu 1 ký tự giữa từ ('usrs' thiếu 'e') vẫn phải gợi ý ra 'users' (fuzzy subsequence)")
    void fuzzySubsequenceStillFindsRealTable() {
        pg("select * from usrs|").tables("public.users").views(PG_VIEWS);
    }

    @Test
    @DisplayName("Prefix không khớp bảng nào thật thì KHÔNG được tự bịa ra 1 bảng nào cả")
    void noFalsePositiveTableForUnrelatedPrefix() {
        pg("select * from zzqq|");
    }

    // =====================================================================
    // C. Alias resolution - JOIN nhiều bảng, gợi ý cột đúng theo alias
    // =====================================================================

    @Test
    @DisplayName("JOIN 2 bảng, gõ 'u.' phải CHỈ ra đúng cột của users, không lẫn cột orders")
    void aliasQualifiedColumnsAfterJoin() {
        pg("select * from users u join orders o on o.user_id = u.|").columns("u.email", "u.id", "u.name")
                .systemColumns("u");
    }

    @Test
    @DisplayName("JOIN...USING(|): chỉ gợi ý đúng cột TRÙNG TÊN giữa 2 bảng (users/orders chỉ chung 'id')")
    void joinUsingSuggestsOnlyCommonColumns() {
        pg("select * from users u join orders o using (|").columns("id");
    }

    // =====================================================================
    // D. Tự đặt tên alias + tránh trùng (thực tế: join lặp lại cùng 1 bảng,
    // hoặc nhiều bảng cùng chữ cái đầu)
    // =====================================================================

    @Test
    @DisplayName("Gợi ý alias mặc định cho 1 bảng chưa dùng lần nào: 'contracts' -> 'c'")
    void firstAliasSuggestionUsesFirstLetter() {
        pg("select * from contracts as |").aliases("c");
    }

    @Test
    @DisplayName("Alias 'c' đã dùng - lần 2 phải là 'c1', không lặp lại 'c'")
    void secondAliasCollisionAvoidedWithSuffix1() {
        pg("select * from contracts c join contracts as |").aliases("c1");
    }

    @Test
    @DisplayName("Alias 'c' và 'c1' đã dùng - lần 3 phải là 'c2' (vòng lặp tránh trùng phải tăng đúng)")
    void thirdAliasCollisionAvoidedWithSuffix2() {
        pg("select * from contracts c join contracts c1 join contracts as |").aliases("c2");
    }

    // =====================================================================
    // E. Gõ có schema-qualify (dot-mode) - chỉ hiện đúng bảng khớp trong
    // đúng schema đó.
    // =====================================================================

    @Test
    @DisplayName("'public.us|' (đã gõ rõ schema) phải xếp 'public.users' lên đầu (prefix match thật, hơn fuzzy)")
    void schemaQualifiedPrefixRanksRealTableFirst() {
        pg("select * from public.us|").tables("public.users").views(PG_VIEWS).first("public.users");
    }

    // =====================================================================
    // F. INSERT/UPDATE - gợi ý cột thật, không lẫn keyword
    // =====================================================================

    @Test
    @DisplayName("INSERT INTO orders (...): danh sách cột phải ĐÚNG các cột thật của orders, không thiếu không thừa")
    void insertColumnListSuggestsExactRealColumns() {
        pg("insert into orders (|").columns("customer_id", "id", "status", "total", "user_id");
    }

    @Test
    @DisplayName("UPDATE ... SET a, |: vị trí cột thứ 2 trong SET vẫn phải gợi ý đúng cột thật của bảng")
    void updateSetSecondColumnStillSuggestsRealColumns() {
        pg("update users set name = 'x', |").columns("email", "id", "name");
    }

    // =====================================================================
    // G. ORDER BY - sau khi đã chọn xong 1 cột (có khoảng trắng), KHÔNG còn
    // gợi ý cột nữa (đang chờ ASC/DESC/dấu phẩy/kết thúc câu).
    // =====================================================================

    @Test
    @DisplayName("'order by name |' (đã gõ xong 1 cột) - KHÔNG còn gợi ý cột nào nữa, chỉ còn ASC/DESC")
    void orderByNoLongerSuggestsColumnsAfterCompleteElement() {
        pg("select * from users order by name |").keywordsInclude("asc", "desc");
    }

    @Test
    @DisplayName("'group by name |' (đã gõ xong 1 cột) - KHÔNG còn gợi ý cột nào nữa (y hệt ORDER BY)")
    void groupByNoLongerSuggestsColumnsAfterCompleteElement() {
        pg("select * from users group by name |");
    }

    // =====================================================================
    // G2. CTE (WITH ... AS (...)) - alias của CTE phải resolve được cột y hệt
    // 1 bảng thật (thao tác rất thường gặp trong SQL thực tế).
    // =====================================================================

    @Test
    @DisplayName("WITH cte AS (...): alias của CTE phải gợi ý đúng cột của SELECT bên trong nó")
    void cteAliasColumnsResolveCorrectly() {
        pg("with recent as (select * from users) select * from recent r where r.|")
                .columns("r.email", "r.id", "r.name");
    }

    // =====================================================================
    // G3. JOIN 2 bảng CÙNG có 1 cột trùng tên - gõ TRẦN (không alias) phải
    // thấy CẢ HAI dạng qualify (không tự ý chọn 1 bên, cũng không biến mất).
    // =====================================================================

    @Test
    @DisplayName("2 bảng join cùng có cột 'name' - gõ trần 'na' phải thấy CẢ HAI, đúng alias riêng")
    void ambiguousColumnAcrossJoinedTablesShowsBothQualified() {
        pg("select * from users u join contracts c on u.id = c.id where na|").columns("c.name", "u.name");
    }

    // =====================================================================
    // G4. Habit weight (CompletionHistory) - tầng duy nhất của SuggestFilter
    // không đi qua trong các test khác (các test khác đều reset về rỗng để
    // xếp hạng không phụ thuộc máy chạy) - test RIÊNG để đảm bảo cơ chế này
    // thật sự hoạt động khi CÓ lịch sử, không chỉ khi rỗng.
    // =====================================================================

    @Test
    @DisplayName("Cột được CHỌN nhiều lần gần đây phải lên hạng đầu, dù cùng tier/order với cột khác")
    void recentlyChosenColumnRanksFirstDueToHabitWeight() {
        for (int i = 0; i < 5; i++) {
            CompletionHistory.record("contracts.id");
        }

        pg("select * from contracts where |")
                .columns("contracts.amount", "contracts.id", "contracts.name", "contracts.status")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .first("contracts.id")
                .systemColumns("contracts");
    }

    // =====================================================================
    // H. Không được crash khi người dùng đang gõ dở (thực tế rất hay gặp)
    // =====================================================================

    @Test
    @DisplayName("Subquery chưa đóng ngoặc (đang gõ dở) vẫn phải gợi ý được cột bên trong nó")
    void unclosedSubqueryStillSuggestsColumns() {
        pg("select * from (select * from users u where u.|").columns("u.email", "u.id", "u.name")
                .systemColumns("u");
    }

    @Test
    @DisplayName("Buffer rỗng - không crash, gợi ý các từ khoá bắt đầu câu")
    void emptyBufferSuggestsStatementStartKeywords() {
        pg("|").keywordsInclude("select", "insert into");
    }

    @Test
    @DisplayName("Gõ 'x.' với 'x' không phải alias/bảng nào có thật - không crash, không suy đoán bừa")
    void dotAfterUnknownQualifierDoesNotCrash() {
        pg("select * from x.|");
    }

    // =====================================================================
    // I. VIEW - phải gợi ý đúng type "view" (khác "table"), và vẫn thắng
    // keyword khi prefix match thật.
    // =====================================================================

    @Test
    @DisplayName("Gõ prefix của 1 view ('active') phải ra type \"view\", xếp hạng đầu")
    void viewSuggestionHasViewType() {
        pg("select * from active|").views(PG_VIEWS).first("public.active_users");
    }

    // =====================================================================
    // J. Datatype - CAST/CREATE TABLE phải gợi ý đúng kiểu dữ liệu thật theo
    // prefix, không lẫn kiểu không tồn tại trong schema.
    // =====================================================================

    @Test
    @DisplayName("CAST(x AS t|): chỉ gợi ý datatype có từ bắt đầu bằng 't' (text, timestamp, daily_totals)")
    void castSuggestsMatchingDatatypes() {
        pg("select cast(id as t|) from users")
                .datatypes("text", "timestamp", "public.daily_totals");
    }

    @Test
    @DisplayName("CREATE TABLE x (a t|): vị trí kiểu dữ liệu cột cũng phải gợi ý đúng datatype thật")
    void createTableColumnSuggestsMatchingDatatypes() {
        pg("create table x (a t|)")
                .datatypes("text", "timestamp", "public.daily_totals");
    }

    // =====================================================================
    // K. 3 bảng JOIN cùng lúc - scope phải đúng, không rò rỉ cột bảng khác.
    // =====================================================================

    @Test
    @DisplayName("JOIN 3 bảng, gõ alias bảng thứ 3 phải CHỈ ra đúng cột của nó, không lẫn 2 bảng kia")
    void tripleJoinResolvesCorrectTableAmongThree() {
        pg("select * from users u join orders o on o.user_id = u.id join contracts c on c.id = u.id where c.|")
                .columns("c.amount", "c.id", "c.name", "c.status")
                .systemColumns("c");
    }

    // =====================================================================
    // L. UPDATE...FROM / DELETE...USING (cú pháp Postgres thật, không phải
    // chuẩn SQL - hay dùng để update/xoá theo điều kiện join với bảng khác).
    // =====================================================================

    @Test
    @DisplayName("UPDATE ... FROM u WHERE ... AND u.|: phải resolve đúng cột bảng trong FROM")
    void updateFromResolvesJoinedTableColumns() {
        pg("update orders o set status = 'x' from users u where u.id = o.user_id and u.|")
                .columns("u.email", "u.id", "u.name")
                .systemColumns("u");
    }

    @Test
    @DisplayName("DELETE ... USING u WHERE ... AND u.|: phải resolve đúng cột bảng trong USING")
    void deleteUsingResolvesJoinedTableColumns() {
        pg("delete from orders o using users u where u.id = o.user_id and u.|").columns("u.email", "u.id", "u.name")
                .systemColumns("u");
    }

    // =====================================================================
    // M. ROLE/USER (GRANT/REVOKE/ALTER ROLE/OWNER TO/DROP ROLE...) - phải
    // gợi ý đúng role thật (SchemaIndex.roles), KHÔNG lẫn hàng trăm keyword
    // rác (bug thật: PREFERRED_RULES thiếu "rolespec", đã sửa trong
    // PostgresSyntacticAnalyzer - xem test này như bằng chứng không tái phát).
    // =====================================================================

    @Test
    @DisplayName("GRANT ... TO |: đúng 3 role thật, không còn 458 keyword rác")
    void grantToSuggestsExactRoles() {
        pg("grant select on public.users to |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("REVOKE ... FROM |: đúng 3 role thật")
    void revokeFromSuggestsExactRoles() {
        pg("revoke select on public.users from |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("ALTER GROUP g ADD USER |: đúng 3 role thật")
    void alterGroupAddUserSuggestsExactRoles() {
        pg("alter group grp1 add user |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("ALTER AGGREGATE ... OWNER TO |: đúng 3 role thật")
    void alterOwnerToSuggestsExactRoles() {
        pg("alter aggregate agg1(int4) owner to |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("DROP ROLE |: đúng 3 role thật")
    void dropRoleSuggestsExactRoles() {
        pg("drop role |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("DROP OWNED BY |: đúng 3 role thật")
    void dropOwnedBySuggestsExactRoles() {
        pg("drop owned by |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("GRANT role1 TO |: đúng 3 role thật (grant 1 role cho role khác)")
    void grantRoleToSuggestsExactRoles() {
        pg("grant app_reader to |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("REASSIGN OWNED BY x TO |: đúng 3 role thật")
    void reassignOwnedToSuggestsExactRoles() {
        pg("reassign owned by app_reader to |").roles(PG_ROLES);
    }

    @Test
    @DisplayName("CREATE TABLESPACE ... OWNER |: đúng 3 role thật")
    void createTablespaceOwnerSuggestsExactRoles() {
        pg("create tablespace ts2 owner |").roles(PG_ROLES);
    }

    // =====================================================================
    // N. func_name/type_function_name (tham chiếu hàm/kiểu ĐÃ CÓ) - trước đây
    // matchedRuleNames báo đúng nhưng không ai nối để thêm gợi ý thật, rơi
    // xuống tràn keyword rác (CREATE FUNCTION RETURNS, DROP FUNCTION/AGGREGATE).
    // =====================================================================

    @Test
    @DisplayName("CREATE FUNCTION ... RETURNS |: phải thấy đúng datatype thật, không tràn 396 keyword rác")
    void createFunctionReturnsSuggestsRealDatatypes() {
        pg("create function f1() returns |").datatypes(PG_TYPE_NAMES);
    }

    @Test
    @DisplayName("DROP FUNCTION |: phải thấy đúng hàm thật")
    void dropFunctionSuggestsRealFunctions() {
        pg("drop function |").functions(PG_FUNCTIONS);
    }

    // =====================================================================
    // O. ALTER TABLE ADD COLUMN - grammar thật: columnDef: colid typename ...
    // (TUẦN TỰ - tên cột MỚI trước, kiểu dữ liệu SAU). Bug thật: trước đây
    // gợi ý datatype NGAY cả khi tên cột mới CHƯA được gõ (colid chưa xong).
    // =====================================================================

    @Test
    @DisplayName("ADD COLUMN |: tên cột MỚI chưa gõ - KHÔNG được gợi ý datatype (đứng trước colid)")
    void addColumnWithoutNameYetSuggestsNoDatatype() {
        pg("alter table users add column |");
    }

    @Test
    @DisplayName("ADD COLUMN newcol |: đã gõ xong tên cột mới - PHẢI gợi ý đúng datatype thật")
    void addColumnWithNameTypedSuggestsRealDatatype() {
        pg("alter table users add column newcol |").datatypes(PG_TYPE_NAMES);
    }

    // =====================================================================
    // P. TỪNG VỊ TRÍ trong 1 câu SELECT/UPDATE/DELETE hoàn chỉnh - không chỉ 1
    // vị trí đại diện cho cả câu lệnh (đây là tool AUTOCOMPLETE, người dùng gõ
    // và dừng lại ở RẤT NHIỀU điểm khác nhau trong CÙNG 1 câu).
    // =====================================================================

    @Test
    @DisplayName("'select |' (đầu câu, chưa gõ gì) - phải thấy đúng cột thật của bảng trong FROM")
    void selectBareStartSuggestsRealColumns() {
        pg("select | from users")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("'select id, |' (cột thứ 2 trở đi) - vẫn phải thấy đúng cột thật")
    void selectSecondColumnStillSuggestsColumns() {
        pg("select id, | from users")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("'WHERE id = 1 AND |' (điều kiện thứ 2 trở đi) - vẫn phải thấy đúng cột thật")
    void whereAndContinuationStillSuggestsColumns() {
        pg("select * from users where id = 1 and |")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("HAVING |: phải thấy đúng cột thật của bảng đang GROUP BY")
    void havingClauseSuggestsRealColumns() {
        pg("select status, count(*) from orders group by status having |")
                .columns("orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("orders");
    }

    @Test
    @DisplayName("Subquery KHÔNG tương quan trong WHERE IN (...): cursor bên trong subquery phải thấy cột của bảng subquery đang FROM")
    void whereInSubquerySuggestsSubqueryOwnColumns() {
        pg("select * from users where id in (select | from orders)")
                .columns("orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id", "users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("orders", "users");
    }

    @Test
    @DisplayName("Subquery TƯƠNG QUAN (correlated EXISTS): alias bảng NGOÀI vẫn phải resolve được bên trong subquery")
    void whereExistsCorrelatedResolvesOuterAlias() {
        pg("select * from users u where exists (select 1 from orders o where o.user_id = u.|)")
                .columns("u.email", "u.id", "u.name")
                .systemColumns("u");
    }

    @Test
    @DisplayName("Tên CTE phải được gợi ý như 1 bảng khi đang gõ dở trong FROM - bug thật đã sửa (trước đây hoàn toàn không gợi ý được)")
    void cteNameSuggestedAsFromTarget() {
        pg("with recent as (select * from users) select * from re|").tables("recent");
    }

    @Test
    @DisplayName("CTE tham chiếu CTE khác (2 tầng, cả 2 đều wildcard) phải resolve xuyên suốt - bug thật đã sửa (DerivedColumnExpander trước đây chỉ đệ quy đúng 1 cấp, tầng 2 luôn rỗng)")
    void cteChainResolvesTransitively() {
        pg("with a as (select * from users), b as (select * from a) select * from b bb where bb.|")
                .columns("bb.email", "bb.id", "bb.name");
    }

    @Test
    @DisplayName("INSERT ... VALUES (|): KHÔNG có bảng nào trong scope để gợi ý cột (quy ước đã có từ trước)")
    void insertValuesDoesNotSuggestColumns() {
        pg("insert into users (id, name) values (1, |)").functions(PG_FUNCTIONS).datatypes(PG_DATATYPES);
    }

    @Test
    @DisplayName("DELETE FROM users WHERE | (không JOIN/USING) - vẫn phải thấy đúng cột thật")
    void deletePlainWhereSuggestsColumns() {
        pg("delete from users where |")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    // =====================================================================
    // Q. Window function (OVER (...)), LATERAL, UNION - verify bằng Postgres
    // THẬT (không chỉ suy từ grammar - bài học từ session này: grammar port
    // có thể sai/thiếu, phải đối chiếu hành vi DB thật).
    // =====================================================================

    @Test
    @DisplayName("Window function PARTITION BY |: phải thấy đúng cột thật của bảng đang FROM")
    void windowPartitionBySuggestsRealColumns() {
        pg("select id, sum(total) over (partition by | order by id) from orders")
                .columns("orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("orders");
    }

    @Test
    @DisplayName("Window function ORDER BY (trong OVER) |: phải thấy đúng cột thật")
    void windowOrderBySuggestsRealColumns() {
        pg("select id, sum(total) over (partition by status order by |) from orders")
                .columns("orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("orders");
    }

    @Test
    @DisplayName("LATERAL subquery: được phép tham chiếu alias của FROM-item ĐỨNG TRƯỚC nó (đúng ngữ nghĩa LATERAL thật của Postgres, verify bằng docs/hành vi chuẩn)")
    void lateralSubqueryResolvesPrecedingAlias() {
        pg("select * from users u, lateral (select * from orders o where o.user_id = u.|) sub")
                .columns("u.email", "u.id", "u.name")
                .systemColumns("u");
    }

    @Test
    @DisplayName("ORDER BY |: phải gợi ý cột của FROM (bug thật: ORDER BY nằm ngoài scope nhánh SELECT nên ra rỗng)")
    void orderBySuggestsFromColumns() {
        pg("select * from users order by |")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("ORDER BY alias.|: phải ra cột của bảng theo alias")
    void orderByAliasDotSuggestsColumns() {
        pg("select * from users u order by u.|").columns("u.email", "u.id", "u.name").systemColumns("u");
    }

    @Test
    @DisplayName("LIMIT |: KHÔNG gợi ý cột - Postgres từ chối \"argument of LIMIT must not contain variables\"")
    void limitDoesNotSuggestColumns() {
        pg("select * from users order by id limit |").functions(PG_FUNCTIONS).datatypes(PG_DATATYPES);
    }

    @Test
    @DisplayName("Subquery trong FROM (không LATERAL) KHÔNG được thấy mục FROM cùng cấp - Postgres từ chối \"select * from users a, (select a.id from orders) o\"")
    void fromSubqueryDoesNotSeeSiblingFromItems() {
        pg("select * from users a, (select a.| from orders) o");
    }

    @Test
    @DisplayName("Subquery trong FROM vẫn thấy alias của câu NGOÀI hơn 1 cấp (correlated qua scalar subquery)")
    void fromSubquerySeesOuterQueryLevel() {
        pg("select (select 1 from (select a.| from orders) o) from users a")
                .columns("a.email", "a.id", "a.name")
                .systemColumns("a");
    }

    @Test
    @DisplayName("Subquery trong FROM vẫn thấy CTE của câu ngoài")
    void fromSubquerySeesOuterCte() {
        pg("with c as (select id from users) select * from (select c.| from c) x").columns("c.id");
    }

    @Test
    @DisplayName("Chữ đang gõ dở trong SELECT list của subquery KHÔNG thành cột của alias subquery (bug thật: gợi ý \"u1.na\")")
    void typedWordInSubquerySelectIsNotProjected() {
        pg("select u1.id from (select na| from users as u) as u1").columns("u.name");
    }

    @Test
    @DisplayName("UNION nhánh 2 KHÔNG được thấy bảng của nhánh 1 - bug thật đã sửa, verify bằng Postgres THẬT: \"select id from users union select users.name from orders\" bị Postgres từ chối \"missing FROM-clause entry for table users\" - chứng minh 2 nhánh KHÔNG share scope")
    void unionSecondBranchDoesNotLeakFirstBranchTable() {
        pg("select id from users union select | from orders")
                .columns("orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("orders");
    }

    @Test
    @DisplayName("UNION nhánh 2 cũng không được thấy CTE chỉ dùng ở nhánh 1")
    void unionSecondBranchDoesNotLeakCteFromFirstBranch() {
        pg("with a as (select * from users) select * from a union select | from orders")
                .columns("orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("orders");
    }

    // =====================================================================
    // R. Regression của đợt tách scope riêng cho từng nhánh UNION - trước
    // khi sửa, scope select_no_parens (thứ được đăng ký làm subquery/CTE)
    // không còn chứa cột nên cả 3 test đầu đều ra RỖNG; và tên CTE được lấy
    // từ visibleDerivedScopes() nên lẫn cả alias subquery.
    // =====================================================================

    @Test
    @DisplayName("Alias subquery trong FROM: x.| phải ra đúng cột SELECT list của subquery")
    void subqueryAliasSuggestsProjectedColumns() {
        pg("select * from (select id, name from users) x where x.|").columns("x.id", "x.name");
    }

    @Test
    @DisplayName("Alias subquery 'SELECT *': x.| phải mở rộng ra cột thật của bảng bên trong")
    void subqueryAliasWildcardExpandsToRealColumns() {
        pg("select * from (select * from users) x where x.|").columns("x.email", "x.id", "x.name");
    }

    @Test
    @DisplayName("Alias subquery thân UNION: cột đầu ra lấy theo nhánh ĐẦU (ngữ nghĩa Postgres)")
    void unionSubqueryAliasUsesFirstBranchColumns() {
        pg("select * from (select id from users union select id from orders) x where x.|").columns("x.id");
    }

    @Test
    @DisplayName("CTE thân UNION: t.| phải ra cột của nhánh đầu, không rỗng")
    void unionCteSuggestsFirstBranchColumns() {
        pg("with a as (select id from users union select id from orders) select * from a t where t.|").columns("t.id");
    }

    @Test
    @DisplayName("WITH RECURSIVE (thân UNION ALL tự tham chiếu): t.| phải ra cột của phần anchor")
    void recursiveCteSuggestsAnchorColumns() {
        pg("with recursive r as (select * from users union all select * from r) select * from r t where t.|")
                .columns("t.email", "t.id", "t.name");
    }

    @Test
    @DisplayName("CTE đổi tên cột 'WITH a(x, y)': t.| phải ra tên mới, không phải tên gốc")
    void cteColumnRenameApplies() {
        pg("with a(x, y) as (select id, name from users) select * from a t where t.|").columns("t.x", "t.y");
    }

    @Test
    @DisplayName("Chuỗi CTE 3 tầng toàn wildcard - đệ quy không giới hạn cấp")
    void cteChainThreeLevelsResolves() {
        pg("with a as (select * from users), b as (select * from a), c as (select * from b) select * from c cc where cc.|")
                .columns("cc.email", "cc.id", "cc.name");
    }

    @Test
    @DisplayName("Alias subquery KHÔNG được gợi ý như 1 bảng sau JOIN (chỉ tên CTE mới được)")
    void subqueryAliasNotSuggestedAsTable() {
        pg("select * from (select * from users) x join |")
                .tables(PG_TABLES)
                .views(PG_VIEWS)
                .materializedViews(PG_MATVIEWS)
                .functions(PG_FUNCTIONS)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("Alias trỏ tới CTE ('from r rr') không được gợi ý như bảng - chỉ chính tên CTE 'r'")
    void cteAliasNotSuggestedAsTable() {
        pg("with r as (select * from users) select * from r rr join |")
                .tables("public.contracts", "public.orders", "public.products", "public.users", "r")
                .views(PG_VIEWS)
                .materializedViews(PG_MATVIEWS)
                .functions(PG_FUNCTIONS)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("Tên CTE vẫn được gợi ý trong subquery lồng bên dưới (đi qua chuỗi scope cha)")
    void cteNameVisibleInsideNestedSubquery() {
        pg("with r as (select * from users) select * from orders where user_id in (select id from r|)")
                .tables("r");
    }

    @Test
    @DisplayName("UNION nhánh 1 không được thấy bảng của nhánh 2")
    void unionFirstBranchDoesNotLeakSecondBranchTable() {
        pg("select | from users union select id from orders")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    @Test
    @DisplayName("UNION nhánh 2 không resolve được alias định nghĩa ở nhánh 1")
    void unionSecondBranchCannotUseFirstBranchAlias() {
        pg("select id from users u union select u.| from orders");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select id from users intersect select | from orders",
            "select id from users except select | from orders",
            "select id from users union select id from contracts union select | from orders",
    })
    @DisplayName("INTERSECT / EXCEPT / UNION 3 nhánh: nhánh cuối chỉ thấy bảng của chính nó")
    void setOperationLastBranchSeesOnlyOwnTable(String sql) {
        pg(sql)
                .columns("orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("orders");
    }

    // =====================================================================
    // S. Lọc đúng LOẠI relation theo lệnh - đối chiếu Postgres 18 thật (chạy
    // từng lệnh trên table/view/materialized view). Bug thật: trước đây mọi
    // vị trí tên bảng đều gợi ý cả view.
    // =====================================================================

    @Test
    @DisplayName("DROP VIEW |: chỉ view")
    void dropViewSuggestsOnlyViews() {
        pg("drop view |").views(PG_VIEWS)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("DROP MATERIALIZED VIEW |: chỉ materialized view")
    void dropMaterializedViewSuggestsOnlyMaterializedViews() {
        pg("drop materialized view |").materializedViews(PG_MATVIEWS)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("TRUNCATE |: chỉ bảng thường (view/materialized view -> lỗi 42809)")
    void truncateSuggestsOnlyTables() {
        pg("truncate |").tables(PG_TABLES)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("LOCK TABLE |: bảng + view (materialized view -> lỗi 42809)")
    void lockTableSuggestsTablesAndViews() {
        pg("lock table |").tables(PG_TABLES).views(PG_VIEWS)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("COMMENT ON TABLE |: chỉ bảng thường")
    void commentOnTableSuggestsOnlyTables() {
        pg("comment on table |").tables(PG_TABLES)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("CREATE INDEX ... ON |: bảng + materialized view (view -> lỗi 42809)")
    void createIndexOnSuggestsTablesAndMaterializedViews() {
        pg("create index idx1 on |").tables(PG_TABLES).materializedViews(PG_MATVIEWS)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("INSERT INTO |: bảng + view cập nhật được (materialized view -> lỗi 42809)")
    void insertIntoSuggestsTablesAndViews() {
        pg("insert into |").tables(PG_TABLES).views(PG_VIEWS)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("DELETE FROM |: bảng + view (materialized view -> lỗi 42809)")
    void deleteFromSuggestsTablesAndViews() {
        pg("delete from |").tables(PG_TABLES).views(PG_VIEWS)
                .schemas(PG_SCHEMAS);
    }

    @Test
    @DisplayName("SELECT ... FROM |: mọi loại relation")
    void selectFromSuggestsEveryRelationKind() {
        pg("select * from |")
                .tables(PG_TABLES)
                .views(PG_VIEWS)
                .materializedViews(PG_MATVIEWS)
                .functions(PG_FUNCTIONS)
                .schemas(PG_SCHEMAS);
    }

    // =====================================================================
    // T. Vị trí chỉ nhận TÊN CỘT TRẦN - dạng "bảng.cột" bị Postgres thật từ
    // chối (INSERT (users.id), SET users.name =, USING (u.id), DROP COLUMN
    // users.email...), mà MenuCompleter chèn NGUYÊN key -> key phải trần.
    // =====================================================================

    @Test
    @DisplayName("ALTER TABLE ... DROP COLUMN |: tên cột trần")
    void dropColumnSuggestsBareColumnNames() {
        pg("alter table users drop column |").columns("email", "id", "name");
    }

    @Test
    @DisplayName("INSERT ... SELECT |: là BIỂU THỨC (không phải danh sách cột INSERT) - cột có tiền tố + hàm")
    void insertSelectListIsExpressionPosition() {
        pg("insert into orders select | from users")
                .columns("users.email", "users.id", "users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .systemColumns("users");
    }

    // =====================================================================
    // U. Cột hệ thống (ctid, xmin, xmax, cmin, cmax, tableoid) + tên schema -
    // thiếu so với IntelliJ, đã kiểm chứng trên Postgres 18 thật. Cột hệ thống
    // chỉ có trên bảng/materialized view, và chỉ dùng được ở vị trí biểu thức.
    // =====================================================================

    @Test
    @DisplayName("View KHÔNG có cột hệ thống (Postgres: column \"ctid\" does not exist)")
    void viewHasNoSystemColumns() {
        pg("select * from active_users where |")
                .columns("active_users.id", "active_users.name")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES);
    }

    @Test
    @DisplayName("Materialized view CÓ cột hệ thống như bảng thường")
    void materializedViewHasSystemColumns() {
        pg("select * from daily_totals where |")
                .columns("daily_totals.id", "daily_totals.total")
                .systemColumns("daily_totals")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES);
    }

    @Test
    @DisplayName("Tên schema gợi ý được sau FROM (gõ 'pu' -> 'public', sau đó 'public.' ra bảng)")
    void schemaNameSuggestedAtRelationPosition() {
        // "products": p rồi nhảy 3 ký tự mới tới u - không phải thứ đang gõ
        pg("select * from pu|")
                .schemas("public")
                .first("public");
    }

    // =====================================================================
    // Gợi ý điều kiện JOIN theo khoá ngoại (fixture: orders.user_id -> users.id)
    // =====================================================================

    @Test
    @DisplayName("JOIN ... ON |: gợi ý điều kiện theo khoá ngoại, đứng đầu danh sách")
    void joinOnSuggestsForeignKeyCondition() {
        pg("select * from users u join orders o on |")
                .joins("o.user_id = u.id")
                .columns("u.id", "u.name", "u.email", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id")
                .systemColumns("u")
                .systemColumns("o")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .first("o.user_id = u.id");
    }

    @Test
    @DisplayName("JOIN theo chiều ngược (bảng được tham chiếu JOIN sau): bảng vừa JOIN vẫn đứng vế trái")
    void joinOnSuggestsForeignKeyConditionReverseDirection() {
        pg("select * from orders o join users u on |")
                .joins("u.id = o.user_id")
                .columns("u.id", "u.name", "u.email", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id")
                .systemColumns("u")
                .systemColumns("o")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .first("u.id = o.user_id");
    }

    @Test
    @DisplayName("JOIN ... ON o|: gõ alias đầu điều kiện vẫn khớp cả cụm điều kiện")
    void joinOnConditionMatchesTypedAlias() {
        pg("select * from users u join orders o on o|")
                .joins("o.user_id = u.id")
                .first("o.user_id = u.id");
    }

    @Test
    @DisplayName("Không có khoá ngoại giữa 2 bảng thì không bịa điều kiện JOIN")
    void joinOnWithoutForeignKeySuggestsNoCondition() {
        pg("select * from users u join contracts c on |")
                .columns("u.id", "u.name", "u.email", "c.id", "c.name", "c.amount", "c.status")
                .systemColumns("u")
                .systemColumns("c")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES);
    }

    // =====================================================================
    // Fuzzy chỉ khớp khi ký tự đầu ở đầu 1 từ
    // =====================================================================

    @Test
    @DisplayName("Gõ 'id' ra cả 'customer_id'/'user_id' (đầu từ sau '_'), 'id' khớp chính xác đứng đầu")
    void fuzzyMatchesWordAfterUnderscore() {
        pg("select * from orders where id|")
                .columns("orders.id", "orders.customer_id", "orders.user_id")
                .first("orders.id");
    }

    // =====================================================================
    // Cột của scope gần caret xếp trước cột của scope ngoài
    // =====================================================================

    @Test
    @DisplayName("Trong subquery tương quan: cột của bảng trong subquery đứng trước cột của bảng câu ngoài")
    void innerScopeColumnsRankBeforeOuterScopeColumns() {
        var e = pg("select * from users u where exists (select 1 from orders o where |)")
                .columns("u.id", "u.name", "u.email", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id")
                .systemColumns("u")
                .systemColumns("o")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES);
        var columns = e.actual().stream()
                .filter(s -> s.getType() == com.sqlctx.completion.model.SuggestionType.COLUMN)
                .map(s -> s.getKey())
                .toList();
        int lastInner = 0;
        int firstOuter = columns.size();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).startsWith("o.")) {
                lastInner = i;
            } else if (firstOuter == columns.size()) {
                firstOuter = i;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(lastInner < firstOuter, "cột o.* phải đứng trước u.*: " + columns);
    }

    // =====================================================================
    // search_path: bảng tìm được bằng tên trần thì gợi ý tên trần
    // =====================================================================

    @Test
    @DisplayName("Schema nằm trong search_path: gợi ý 'users' thay vì 'public.users'")
    void searchPathTablesSuggestedWithoutSchema() {
        pg("select * from us|", () -> SchemaIndex.searchPath = List.of("public"))
                .tables("users")
                .views("active_users")
                .first("users");
    }

    @Test
    @DisplayName("Đã gõ 'public.': vẫn gợi ý tên đầy đủ dù schema nằm trong search_path")
    void searchPathDoesNotAffectSchemaQualifiedTyping() {
        pg("select * from public.us|", () -> SchemaIndex.searchPath = List.of("public"))
                .tables("public.users")
                .views(PG_VIEWS)
                .first("public.users");
    }

    // =====================================================================
    // Chữ ký hàm hiển thị cạnh tên hàm
    // =====================================================================

    @Test
    @DisplayName("Hàm có chữ ký trong catalog: gợi ý kèm chữ ký (cột mô tả của menu)")
    void functionSuggestionCarriesSignature() {
        var e = pg("select co| from users", () -> SchemaIndex.functionSignatures = Map.of("count", "(\"any\") → bigint"))
                .functions("count");
        var count = e.actual().stream().filter(s -> s.getKey().equals("count")).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("(\"any\") → bigint", count.getColumnType());
    }

    // =====================================================================
    // Điều kiện JOIN đoán theo tên khi không có khoá ngoại
    // =====================================================================

    @Test
    @DisplayName("Không khai báo khoá ngoại: vẫn đoán o.user_id = u.id theo quy ước <bảng số ít>_id, nhãn 'by name'")
    void joinOnGuessesConditionByNamingConvention() {
        var e = pg("select * from users u join orders o on |", () -> SchemaIndex.foreignKeys = List.of())
                .joins("o.user_id = u.id")
                .columns("u.id", "u.name", "u.email", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id")
                .systemColumns("u")
                .systemColumns("o")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .first("o.user_id = u.id");
        org.junit.jupiter.api.Assertions.assertEquals("by name", e.actual().get(0).getColumnType());
    }

    @Test
    @DisplayName("Khoá ngoại thật trùng đúng điều kiện đoán theo tên: chỉ 1 gợi ý, nhãn 'foreign key'")
    void joinOnForeignKeyWinsOverSameNameGuess() {
        var e = pg("select * from users u join orders o on o|")
                .joins("o.user_id = u.id");
        org.junit.jupiter.api.Assertions.assertEquals("foreign key", e.actual().get(0).getColumnType());
    }

    @Test
    @DisplayName("Tên bảng có tiền tố: faq_articles.folder_id = faq_folders.id (thử cả từ cuối 'folders')")
    void joinOnGuessesConditionForPrefixedTableName() {
        pg("select * from faq_folders f join faq_articles a on a|", () -> {
            addTable("public", "faq_folders", "id", "title");
            addTable("public", "faq_articles", "id", "folder_id", "body");
        })
                .joins("a.folder_id = f.id")
                .functions("avg")
                .first("a.folder_id = f.id");
    }

    @Test
    @DisplayName("Cột trùng tên có hậu tố định danh (_code) ghép được; 'id', 'status' trùng tên thì không")
    void joinOnGuessesConditionBySharedIdentifierColumn() {
        pg("select * from invoices i join receipts r on r|", () -> {
            addTable("public", "invoices", "id", "contract_code", "status");
            addTable("public", "receipts", "id", "contract_code", "status");
        })
                .joins("r.contract_code = i.contract_code")
                .first("r.contract_code = i.contract_code");
    }

    // =====================================================================
    // "JOIN |": gợi ý nguyên cụm bảng + alias + điều kiện
    // =====================================================================

    @Test
    @DisplayName("JOIN |: bảng có khoá ngoại tới bảng trong FROM được gợi ý kèm alias và điều kiện, đứng đầu")
    void joinSuggestsWholeClauseForRelatedTable() {
        pg("select * from users u join |")
                .joins("public.orders o on o.user_id = u.id")
                .tables(PG_TABLES)
                .views(PG_VIEWS)
                .materializedViews(PG_MATVIEWS)
                .schemas(PG_SCHEMAS)
                .functions(PG_FUNCTIONS) // hàm trả về tập hợp dùng được trong FROM
                .first("public.orders o on o.user_id = u.id");
    }

    @Test
    @DisplayName("JOIN or|: gõ tên bảng vẫn khớp cụm JOIN; search_path cho tên trần")
    void joinClauseMatchesTypedTableName() {
        pg("select * from users u join or|", () -> SchemaIndex.searchPath = List.of("public"))
                .joins("orders o on o.user_id = u.id")
                .tables("orders")
                .first("orders o on o.user_id = u.id");
    }

    @Test
    @DisplayName("Alias gợi ý không được là từ khoá: bảng order_notes -> 'on1', không phải 'on'")
    void suggestedAliasAvoidsReservedWord() {
        pg("select * from order_notes as |", () -> addTable("public", "order_notes", "id", "body"))
                .aliases("on1");
    }

    // =====================================================================
    // Tên cần ngoặc kép / SQL viết chữ hoa
    // =====================================================================

    @Test
    @DisplayName("SQL viết chữ hoa: FROM USERS u vẫn ra cột của bảng users (Postgres gấp tên không quote về chữ thường)")
    void uppercaseTableNameResolvesColumns() {
        pg("SELECT * FROM USERS u WHERE u.|")
                .columns("u.email", "u.id", "u.name")
                .systemColumns("u");
    }

    @Test
    @DisplayName("Bảng tên hoa thường lẫn lộn: gợi ý trong ngoặc kép, gõ không cần dấu \" vẫn khớp")
    void mixedCaseTableSuggestedQuoted() {
        pg("select * from Ord|", () -> addTable("public", "OrderItems", "id", "UserId"))
                .tables("public.orders", "public.\"OrderItems\"")
                .first("public.orders");
    }

    @Test
    @DisplayName("Cột của bảng quote: \"OrderItems\" oi -> oi.\"UserId\" (gõ trần sẽ thành userid, không tồn tại)")
    void columnsOfQuotedTableAreQuotedWhenNeeded() {
        pg("select * from \"OrderItems\" oi where oi.|", () -> addTable("public", "OrderItems", "id", "UserId"))
                .columns("oi.id", "oi.\"UserId\"")
                .systemColumns("oi");
    }

    @Test
    @DisplayName("Cột trùng từ khoá reserved (order, user) phải chèn trong ngoặc kép")
    void reservedWordColumnsAreQuoted() {
        pg("select e.| from events e", () -> addTable("public", "events", "id", "order", "user"))
                .columns("e.id", "e.\"order\"", "e.\"user\"")
                .systemColumns("e");
    }

    // =====================================================================
    // Index, sequence, trigger, giá trị enum
    // =====================================================================

    @Test
    @DisplayName("DROP INDEX |: gợi ý index thật (kèm bảng của nó), không gợi ý bảng")
    void dropIndexSuggestsIndexes() {
        pg("drop index |", () -> SchemaIndex.indexes = List.of(new SchemaObject("public", "orders_status_idx", "orders")))
                .indexes("public.orders_status_idx");
    }

    @Test
    @DisplayName("CREATE INDEX |: đang đặt tên mới - không gợi ý index đã có")
    void createIndexDoesNotSuggestExistingIndexes() {
        pg("create index |", () -> SchemaIndex.indexes = List.of(new SchemaObject("public", "orders_status_idx", "orders")));
    }

    @Test
    @DisplayName("ALTER SEQUENCE |: gợi ý sequence thật, tên trần khi schema nằm trong search_path")
    void alterSequenceSuggestsSequences() {
        pg("alter sequence |", () -> {
            SchemaIndex.searchPath = List.of("public");
            SchemaIndex.sequences = List.of(new SchemaObject("public", "orders_id_seq", null));
        }).sequences("orders_id_seq");
    }

    @Test
    @DisplayName("DROP TRIGGER |: gợi ý trigger thật")
    void dropTriggerSuggestsTriggers() {
        pg("drop trigger |", () -> SchemaIndex.triggers = List.of(new SchemaObject("public", "orders_audit", "orders")))
                .triggers("orders_audit");
    }

    @Test
    @DisplayName("cột_enum = |: gợi ý giá trị enum dạng chuỗi, đứng trước cột")
    void enumColumnComparisonSuggestsEnumValues() {
        pg("select * from tickets t where t.state = |", () -> {
            addTable("public", "tickets", "id", "state:ticket_state");
            SchemaIndex.enumValues = Map.of("ticket_state", List.of("open", "closed"));
        })
                .values("'open'", "'closed'")
                .columns("t.id", "t.state")
                .systemColumns("t")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .first("'open'");
    }

    @Test
    @DisplayName("date_trunc(|: gợi ý field hợp lệ dạng chuỗi ('day', 'month'...) trước cột/hàm")
    void dateTruncSuggestsFieldValues() {
        pg("select date_trunc(| from users")
                .values("'microseconds'", "'milliseconds'", "'second'", "'minute'", "'hour'", "'day'", "'week'",
                        "'month'", "'quarter'", "'year'", "'decade'", "'century'", "'millennium'")
                .columns("users.id", "users.name", "users.email")
                .systemColumns("users")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .first("'microseconds'");
    }

    // =====================================================================
    // Mở rộng "*" và GROUP BY
    // =====================================================================

    @Test
    @DisplayName("select *| from 1 bảng: thay * bằng danh sách cột (tên trần)")
    void starExpandsToColumnsOfSingleTable() {
        pg("select *| from users u")
                .columnLists("id, name, email")
                .first("id, name, email");
    }

    @Test
    @DisplayName("select *| từ nhiều bảng: cột kèm alias để không mơ hồ")
    void starExpandsToQualifiedColumnsOfJoin() {
        pg("select *| from users u join orders o on o.user_id = u.id")
                .columnLists("u.id, u.name, u.email, o.id, o.customer_id, o.total, o.status, o.user_id");
    }

    @Test
    @DisplayName("2 *| là phép nhân, không mở rộng (vẫn gợi ý vế phải như cũ)")
    void multiplicationStarIsNotExpanded() {
        pg("select 2 *| from users u")
                .columns("u.id", "u.name", "u.email")
                .systemColumns("u")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES);
    }

    @Test
    @DisplayName("group by |: gợi ý các biểu thức không gộp của SELECT (bỏ count(*), bỏ alias cột), đứng đầu")
    void groupBySuggestsNonAggregatedSelectItems() {
        pg("select u.name, count(*) as n, o.status s from users u join orders o on o.user_id = u.id group by |")
                .columnLists("u.name, o.status")
                .columns("u.id", "u.name", "u.email", "o.id", "o.customer_id", "o.total", "o.status", "o.user_id")
                .systemColumns("u")
                .systemColumns("o")
                .functions(PG_FUNCTIONS)
                .datatypes(PG_DATATYPES)
                .first("u.name, o.status");
    }

    @Test
    @DisplayName("select o.*| trong JOIN: chỉ cột của o, cột đầu trần vì \"o.\" đã có trong câu")
    void qualifiedStarExpandsOnlyThatAlias() {
        pg("select o.*| from users u join orders o on o.user_id = u.id")
                .columnLists("id, o.customer_id, o.total, o.status, o.user_id")
                .first("id, o.customer_id, o.total, o.status, o.user_id");
    }

    @Test
    @DisplayName("nextval('|') và nextval('ord|'): chỉ gợi ý tên sequence")
    void nextvalSuggestsSequences() {
        Runnable seqs = () -> {
            SchemaIndex.searchPath = List.of("public");
            SchemaIndex.sequences = List.of(new SchemaObject("public", "orders_id_seq", null),
                    new SchemaObject("public", "users_id_seq", null));
        };
        pg("select nextval('|", seqs).sequences("orders_id_seq", "users_id_seq");
        pg("insert into orders (id) values (nextval('ord|", seqs).sequences("orders_id_seq").first("orders_id_seq");
    }
}
