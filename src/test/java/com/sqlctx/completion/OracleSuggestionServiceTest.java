package com.sqlctx.completion;

import com.sqlctx.completion.support.CompletionExpectations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.sqlctx.completion.support.Completion.ora;
import static com.sqlctx.completion.support.CompletionFixtures.*;

/**
 * Test gợi ý SQL Oracle (SÂU: xếp hạng, alias, scope, CTE/subquery/UNION) đi qua ĐÚNG đường
 * production ({@code suggests(PrepareCompletionInput)}, có {@code SuggestFilter} rank/lọc). Cùng
 * cách kiểm tra với {@link PostgresSuggestionServiceTest}.
 * <p>
 * MỖI test khẳng định TOÀN BỘ danh sách gợi ý tại vị trí con trỏ - không chỉ "có chứa" (xem
 * {@link com.sqlctx.completion.support.CompletionExpectation}): mỗi loại khai báo phải khớp
 * CHÍNH XÁC, loại không khai báo phải rỗng, không được trùng, keyword khớp snapshot
 * ({@code src/test/resources/completion/keywords-*.txt}). Kiểm tra chạy tự động sau test bởi
 * {@link com.sqlctx.completion.support.CompletionExpectations}.
 */
@ExtendWith(CompletionExpectations.class)
@ExtendWith(CompletionExpectations.class)
class OracleSuggestionServiceTest {

    // =====================================================================
    // A. Prefix ranking thật - đối tượng schema thật phải thắng keyword chung
    // =====================================================================

    @Test
    @DisplayName("Gõ tên bảng có thật ('us') phải thắng keyword chung ('user') dù cả 2 cùng match prefix")
    void realTableBeatsGenericKeywordOnPrefix() {
        ora("select * from us|").tables("naviq.products", "naviq.users").first("naviq.users");
    }

    @Test
    @DisplayName("Gõ tên cột không alias ('i') phải thắng keyword ('interval'...) dù cả 2 cùng match prefix")
    void realColumnBeatsGenericKeywordOnPrefix() {
        ora("select * from users where i|").columns("users.email", "users.id").first("users.id");
    }

    @Test
    @DisplayName("Không phân biệt hoa/thường: 'USE' vẫn tìm ra bảng 'users'")
    void tableMatchIsCaseInsensitive() {
        ora("select * from USE|").tables("naviq.users").first("naviq.users");
    }

    // =====================================================================
    // B. Fuzzy/gõ thiếu chữ vẫn phải tìm ra đúng bảng
    // =====================================================================

    @Test
    @DisplayName("Gõ thiếu 1 ký tự giữa từ ('usrs' thiếu 'e') vẫn phải gợi ý ra 'users' (fuzzy subsequence)")
    void fuzzySubsequenceStillFindsRealTable() {
        ora("select * from usrs|").tables("naviq.users");
    }

    // =====================================================================
    // C. Alias resolution - JOIN nhiều bảng
    // =====================================================================

    @Test
    @DisplayName("JOIN 2 bảng, gõ 'u.' phải CHỈ ra đúng cột của users, không lẫn cột orders")
    void aliasQualifiedColumnsAfterJoin() {
        ora("select * from users u join orders o on o.user_id = u.|").columns("u.email", "u.id", "u.name");
    }

    @Test
    @DisplayName("JOIN...USING(|): chỉ gợi ý đúng cột TRÙNG TÊN giữa 2 bảng (users/orders chỉ chung 'id')")
    void joinUsingSuggestsOnlyCommonColumns() {
        ora("select * from users u join orders o using (|").columns("o.id", "u.id");
    }

    // =====================================================================
    // D. Tự đặt tên alias + tránh trùng (OracleAliasNameSuggester - implementation
    // riêng của Oracle, khác class với Postgres, nên phải test riêng)
    // =====================================================================

    @Test
    @DisplayName("Gợi ý alias mặc định cho 1 bảng chưa dùng lần nào: 'contracts' -> 'c'")
    void firstAliasSuggestionUsesFirstLetter() {
        ora("select * from contracts |").aliases("c");
    }

    @Test
    @DisplayName("Alias 'c' đã dùng - lần 2 phải là 'c1', không lặp lại 'c'")
    void secondAliasCollisionAvoidedWithSuffix1() {
        ora("select * from contracts c join contracts |").aliases("c1");
    }

    @Test
    @DisplayName("Alias 'c' và 'c1' đã dùng - lần 3 phải là 'c2'")
    void thirdAliasCollisionAvoidedWithSuffix2() {
        ora("select * from contracts c join contracts c1 join contracts |").aliases("c2");
    }

    // =====================================================================
    // E. Gõ có schema-qualify (dot-mode)
    // =====================================================================

    @Test
    @DisplayName("'naviq.us|' (đã gõ rõ schema) phải xếp 'naviq.users' lên đầu, đúng schema đã gõ")
    void schemaQualifiedPrefixRanksRealTableFirst() {
        ora("select * from naviq.us|").tables("naviq.products", "naviq.users").first("naviq.users");
    }

    // =====================================================================
    // F. INSERT/UPDATE - gợi ý cột thật
    // =====================================================================

    @Test
    @DisplayName("INSERT INTO orders (...): danh sách cột phải ĐÚNG các cột thật của orders")
    void insertColumnListSuggestsExactRealColumns() {
        ora("insert into orders (|")
                .columns("orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id");
    }

    // =====================================================================
    // G. ORDER BY - sau khi đã chọn xong 1 cột, KHÔNG còn gợi ý cột nữa
    // =====================================================================

    @Test
    @DisplayName("'order by name |' (đã gõ xong 1 cột) - KHÔNG còn gợi ý cột nào nữa, chỉ còn ASC/DESC")
    void orderByNoLongerSuggestsColumnsAfterCompleteElement() {
        ora("select * from users order by name |").keywordsInclude("asc", "desc");
    }

    @Test
    @DisplayName("'group by name |' (đã gõ xong 1 cột) - KHÔNG còn gợi ý cột nào nữa (y hệt ORDER BY)")
    void groupByNoLongerSuggestsColumnsAfterCompleteElement() {
        ora("select * from users group by name |");
    }

    // =====================================================================
    // G2. CTE (WITH ... AS (...)) - alias của CTE phải resolve được cột y hệt
    // 1 bảng thật.
    // =====================================================================

    @Test
    @DisplayName("WITH cte AS (...): alias của CTE phải gợi ý đúng cột của SELECT bên trong nó")
    void cteAliasColumnsResolveCorrectly() {
        ora("with recent as (select * from users) select * from recent r where r.|")
                .columns("r.email", "r.id", "r.name");
    }

    // =====================================================================
    // G3. JOIN 2 bảng CÙNG có cột trùng tên - gõ trần phải thấy CẢ HAI.
    // =====================================================================

    @Test
    @DisplayName("2 bảng join cùng có cột 'name' - gõ trần 'na' phải thấy CẢ HAI, đúng alias riêng")
    void ambiguousColumnAcrossJoinedTablesShowsBothQualified() {
        ora("select * from users u join contracts c on u.id = c.id where na|").columns("c.name", "u.name");
    }

    // =====================================================================
    // H. MERGE - cú pháp ETL thường gặp trong Oracle thực tế
    // =====================================================================

    @Test
    @DisplayName("MERGE ... WHEN MATCHED THEN UPDATE SET <alias>.<cột>: chỉ gợi ý đúng cột của bảng đích")
    void mergeUpdateSetSuggestsTargetTableColumns() {
        ora("merge into orders o using contracts c on (o.id = c.id) when matched then update set o.|")
                .columns("o.customer_id", "o.id", "o.status", "o.total", "o.user_id");
    }

    // =====================================================================
    // I. Không được crash khi người dùng đang gõ dở
    // =====================================================================

    @Test
    @DisplayName("Subquery chưa đóng ngoặc (đang gõ dở): 'u.|' vẫn phải ra đúng cột của alias bên trong - bug thật đã sửa (trước đây rỗng)")
    void unclosedSubqueryStillSuggestsColumns() {
        ora("select * from (select * from users u where u.|").columns("u.email", "u.id", "u.name");
    }

    @Test
    @DisplayName("Buffer rỗng - không crash, gợi ý các từ khoá bắt đầu câu")
    void emptyBufferSuggestsStatementStartKeywords() {
        ora("|").keywordsInclude("select");
    }

    @Test
    @DisplayName("Gõ 'x.' với 'x' không phải alias/bảng nào có thật - không crash, không suy đoán bừa")
    void dotAfterUnknownQualifierDoesNotCrash() {
        ora("select * from x.|");
    }

    // =====================================================================
    // J. Datatype - CAST phải gợi ý đúng kiểu dữ liệu thật theo prefix.
    // =====================================================================

    @Test
    @DisplayName("CAST(x AS N|): chỉ gợi ý đúng datatype có thật khớp prefix 'N' (NUMBER)")
    void castSuggestsMatchingDatatypes() {
        ora("select cast(id as N|) from dual").datatypes("NUMBER");
    }

    // =====================================================================
    // K. PL/SQL block (BEGIN...END) - scope cột vẫn phải resolve đúng bên
    // trong block, không chỉ ở top-level SELECT thường.
    // =====================================================================

    @Test
    @DisplayName("Trong BEGIN...END, SELECT vẫn phải resolve đúng cột theo alias")
    void plsqlBlockScopeResolvesColumnsCorrectly() {
        ora("begin\n  select name from users u where u.|\nend;").columns("u.email", "u.id", "u.name");
    }

    // =====================================================================
    // L. CONNECT BY - truy vấn phân cấp (hierarchical query) đặc trưng Oracle.
    // =====================================================================

    @Test
    @DisplayName("CONNECT BY PRIOR id = |: vẫn phải gợi ý đúng cột của bảng đang truy vấn phân cấp")
    void connectByResolvesTableColumns() {
        ora("select * from contracts start with id = 1 connect by prior id = |")
                .columns("contracts.amount", "contracts.id", "contracts.name", "contracts.status")
                .functions(ORA_FUNCTIONS);
    }

    // =====================================================================
    // M. 3 bảng JOIN cùng lúc - scope phải đúng, không rò rỉ cột bảng khác.
    // =====================================================================

    @Test
    @DisplayName("JOIN 3 bảng, gõ alias bảng thứ 3 phải CHỈ ra đúng cột của nó, không lẫn 2 bảng kia")
    void tripleJoinResolvesCorrectTableAmongThree() {
        ora("select * from users u join orders o on o.user_id = u.id join contracts c on c.id = u.id where c.|")
                .columns("c.amount", "c.id", "c.name", "c.status");
    }

    // =====================================================================
    // N. TỪNG VỊ TRÍ trong câu (không chỉ 1 vị trí đại diện) - CTE/HAVING/
    // subquery, y hệt nhóm đã thêm bên Postgres.
    // =====================================================================

    @Test
    @DisplayName("HAVING |: phải thấy đúng cột thật của bảng đang GROUP BY")
    void havingClauseSuggestsRealColumns() {
        ora("select status, count(*) from orders group by status having |")
                .columns("orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id")
                .functions(ORA_FUNCTIONS);
    }

    @Test
    @DisplayName("Subquery không tương quan trong WHERE IN (...): cursor bên trong subquery phải thấy cột bảng subquery đang FROM")
    void whereInSubquerySuggestsSubqueryOwnColumns() {
        ora("select * from users where id in (select | from orders)")
                .columns("orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id", "users.email", "users.id", "users.name")
                .functions(ORA_FUNCTIONS);
    }

    @Test
    @DisplayName("Tên CTE phải được gợi ý như 1 bảng khi đang gõ dở trong FROM - bug thật đã sửa")
    void cteNameSuggestedAsFromTarget() {
        ora("with recent as (select * from users) select * from re|").tables("naviq.orders", "recent");
    }

    @Test
    @DisplayName("CTE tham chiếu CTE khác (2 tầng, cả 2 đều wildcard) phải resolve xuyên suốt - bug thật đã sửa (DerivedColumnExpander dùng chung với Postgres)")
    void cteChainResolvesTransitively() {
        ora("with a as (select * from users), b as (select * from a) select * from b bb where bb.|")
                .columns("bb.email", "bb.id", "bb.name");
    }

    @Test
    @DisplayName("UNION nhánh 2 KHÔNG được thấy bảng của nhánh 1 (Oracle: mỗi query_block đã tự có scope riêng, không dính bug như Postgres từng có, test để canh không tái phát)")
    void unionSecondBranchDoesNotLeakFirstBranchTable() {
        ora("select id from users union select | from orders")
                .columns("orders.customer_id", "orders.id", "orders.status", "orders.total", "orders.user_id")
                .functions(ORA_FUNCTIONS);
    }

    @Test
    @DisplayName("Alias subquery KHÔNG được gợi ý như 1 bảng sau JOIN (chỉ tên CTE mới được)")
    void subqueryAliasNotSuggestedAsTable() {
        ora("select * from (select * from users) x join |").tables(ORA_TABLES);
    }

    @Test
    @DisplayName("Alias trỏ tới CTE ('from r rr') không được gợi ý như bảng - chỉ chính tên CTE 'r'")
    void cteAliasNotSuggestedAsTable() {
        ora("with r as (select * from users) select * from r rr join |")
                .tables("naviq.contracts", "naviq.orders", "naviq.products", "naviq.users", "r");
    }

    @Test
    @DisplayName("Tên CTE vẫn được gợi ý trong subquery lồng bên dưới (đi qua chuỗi scope cha)")
    void cteNameVisibleInsideNestedSubquery() {
        ora("with r as (select * from users) select * from orders where user_id in (select id from r|)")
                .tables("naviq.contracts", "naviq.orders", "naviq.products", "naviq.users", "r");
    }

    @Test
    @DisplayName("Chuỗi CTE 3 tầng toàn wildcard - đệ quy không giới hạn cấp")
    void cteChainThreeLevelsResolves() {
        ora("with a as (select * from users), b as (select * from a), c as (select * from b) select * from c cc where cc.|")
                .columns("cc.email", "cc.id", "cc.name");
    }

    @Test
    @DisplayName("UNION nhánh 2 không resolve được alias định nghĩa ở nhánh 1")
    void unionSecondBranchCannotUseFirstBranchAlias() {
        ora("select id from users u union select u.| from orders");
    }

    // =====================================================================
    // P. "x.|" gõ dở BÊN TRONG subquery - lỗi cú pháp ở dấu chấm cụt từng khiến
    // ANTLR đóng/bỏ query_block của subquery, alias bên trong biến mất.
    // =====================================================================

    @Test
    @DisplayName("Subquery trong FROM đã đóng ngoặc: 'u.|' phải ra cột của alias bên trong - bug thật đã sửa (trước đây rỗng)")
    void closedFromSubqueryDotSuggestsInnerAliasColumns() {
        ora("select * from (select * from users u where u.|)").columns("u.email", "u.id", "u.name");
    }

    @Test
    @DisplayName("Subquery trong WHERE ... IN (...): 'o.|' phải ra cột của alias bên trong - bug thật đã sửa (trước đây rỗng)")
    void inSubqueryDotSuggestsInnerAliasColumns() {
        ora("select * from users where id in (select 1 from orders o where o.|)")
                .columns("o.customer_id", "o.id", "o.status", "o.total", "o.user_id");
    }

    @Test
    @DisplayName("Subquery trong EXISTS (...): 'o.|' phải ra cột của alias bên trong")
    void existsSubqueryDotSuggestsInnerAliasColumns() {
        ora("select * from users where exists (select 1 from orders o where o.|)")
                .columns("o.customer_id", "o.id", "o.status", "o.total", "o.user_id");
    }

    @Test
    @DisplayName("SELECT list: KHÔNG gợi ý mọi bảng của schema (nhánh table_wild 't.*') - bug thật đã sửa")
    void selectListDoesNotSuggestAllTables() {
        ora("select | from users")
                .columns("users.email", "users.id", "users.name")
                .functions(ORA_FUNCTIONS);
    }
}
