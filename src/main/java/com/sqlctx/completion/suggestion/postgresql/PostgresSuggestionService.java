package com.sqlctx.completion.suggestion.postgresql;

import com.sqlctx.completion.suggestion.SuggestionService;
import com.sqlctx.completion.model.SuggestionType;
import com.sqlctx.antlr4.postgresql.PostgreSQLParser;
import com.sqlctx.completion.input.CompletionInputPreparer;
import com.sqlctx.completion.suggestion.DerivedColumnExpander;
import com.sqlctx.completion.suggestion.KeywordText;
import com.sqlctx.completion.ranking.SuggestFilter;
import com.sqlctx.completion.syntactic.engine.support.RuleCallStack;
import com.sqlctx.schema.SchemaIndex;
import com.sqlctx.schema.TableInfo;
import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.syntactic.postgresql.PostgresSyntacticAnalyzer;
import com.sqlctx.completion.semantic.postgresql.PostgresSemanticAnalyzer;
import org.antlr.v4.runtime.Token;
import com.sqlctx.util.LoggingConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

/**
 * Orchestrator - CHỈ điều phối, không tự chứa logic parse/resolve nào. Toàn bộ chi
 * tiết nằm ở các class chuyên trách:
 * <p>
 * SchemaIndex          - biết schema có gì (bảng, cột, hàm, kiểu dữ liệu)
 * PostgresSemanticAnalyzer      - tầng ngữ nghĩa (PostgresScopeBuilder: alias/scope/CTE/subquery)
 * PostgresSyntacticAnalyzer     - tầng cú pháp (AntlrCompletionEngine: token/rule hợp lệ)
 * PostgresMatchedRuleResolver    - lọc noise (định danh đã đóng bằng khoảng trắng)
 * <p>
 * 2 tầng (Semantic/Syntactic) ĐỘC LẬP, không tầng nào thay được tầng kia.
 */
public class PostgresSuggestionService implements SuggestionService {
    private static final Logger LOG = LoggingConfig.of(PostgresSuggestionService.class);

    public static List<Suggestion> suggests(CompletionInputPreparer.PrepareCompletionInput input) {
        var suggests = suggests(input.sql(), input.cursor());
        return SuggestFilter.filter(suggests, input.prefix(), input.dotMode());
    }

    @Override
    public List<Suggestion> suggest(String sql, Integer cursorCharPos) {
        return suggests(sql, cursorCharPos);
    }

    public static List<Suggestion> suggests(String sql, Integer cursorCharPos) {
        var suggests = new ArrayList<Suggestion>();
        if (isNull(cursorCharPos)) {
            cursorCharPos = sql.length();
        }
        int cursorOffset = cursorCharPos;
        PostgresSemanticAnalyzer.Result semanticResult = PostgresSemanticAnalyzer.analyze(sql, cursorOffset);

        char charBeforeCursor = (cursorOffset > 0 && cursorOffset <= sql.length()) ? sql.charAt(cursorOffset - 1) : ' ';
        boolean stillMidIdentifier = Character.isLetterOrDigit(charBeforeCursor) || charBeforeCursor == '_';
        int syntacticCursor = stillMidIdentifier ? cursorOffset - 1 : cursorOffset;
        PostgresSyntacticAnalyzer.Result syntacticResults = PostgresSyntacticAnalyzer.analyze(sql, syntacticCursor);

        for (var entry : syntacticResults.candidates().tokens.entrySet()) {
            int tokenType = entry.getKey();
            List<Integer> following = entry.getValue();   // <-- đây, chuỗi mật khẩu chắc chắn theo sau
            addKeywordSuggestions(suggests, tokenType, following);
        }

        Set<String> matchedRuleNames = PostgresMatchedRuleResolver.computeMatchedRuleNames(syntacticResults, syntacticCursor);
        // Tính 1 LẦN DUY NHẤT cho cả lần gọi suggests() này - isDropRoleContext/isObjectGrantContext/
        // isTablespaceNamePosition/isRoleOptionListPosition/isCreateRoleVariant đều chỉ đọc danh sách
        // này (không tự quét lại token stream) - trước đây mỗi helper tự gọi riêng, có case quét tới
        // 4-6 lần cho cùng 1 caret (review phát hiện).
        List<Integer> beforeCaret = realTokenTypesBeforeTableName(syntacticResults);

        // typename đã ENTER từ trước caret (vd "varchar(|)": đang đứng TRONG type modifier của kiểu đã
        // gõ xong tên) thì vị trí này chỉ nhận literal, không phải tên kiểu dữ liệu mới.
        // "colid" CÙNG khớp lúc này là bug thật (phát hiện qua đọc grammar columnDef: colid typename
        // ... - 2 rule này TUẦN TỰ, không bao giờ cùng hợp lệ 1 lúc): "ALTER TABLE t ADD COLUMN |"
        // (tên cột MỚI - colid - chưa gõ) lại gợi ý datatype ngay, sai thứ tự. c3 completion-core báo
        // "typename" ở đây là ảo (cùng loại lỗi sibling-ambiguity đã gặp ở createfunctionstmt/definestmt).
        if (matchedRuleNames.contains("typename")
                && !matchedRuleNames.contains("colid")
                && !PostgresMatchedRuleResolver.isRuleEnteredBeforeCaret(syntacticResults, PostgreSQLParser.RULE_typename)) {
            addDataTypeSuggestions(suggests);
            addCompositeTypeSuggestions(suggests);
        }

        if (matchedRuleNames.contains("table_alias")) {
            addTableAliasSuggestions(suggests, syntacticResults, semanticResult);
        }

        // any_name/qualified_name được grammar dùng chung cho MỌI loại đối tượng (sequence, index,
        // collation...), nhưng engine chỉ có registry bảng/view - nên ở vị trí tên của đối tượng
        // KHÔNG phải bảng thì không được gợi ý tên bảng.
        boolean nameFollowsNonTableKeyword = followsNonTableObjectKeyword(syntacticResults);

        if (matchedRuleNames.contains("any_name")
                && !nameFollowsNonTableKeyword
                && !isNonTableAnyNameContext(syntacticResults)) {
            addTableNameSuggestions(suggests, syntacticResults, semanticResult.visibleCteNames());
        }

        if (matchedRuleNames.contains("qualified_name") && !nameFollowsNonTableKeyword) {
            addTableNameSuggestions(suggests, syntacticResults, semanticResult.visibleCteNames());
        }

        // GRANT/REVOKE .. TO/FROM, ALTER ROLE/GROUP, OWNER TO, DROP ROLE, DROP OWNED BY,
        // REASSIGN OWNED BY .. TO - role/user thật từ pg_roles (SchemaIndex.roles). rolespec là preferred
        // rule nên engine dừng lại ở đây, không tự đi tiếp vào các nhánh literal token của rule (bug thật
        // - so với IntelliJ: thiếu CURRENT_USER/SESSION_USER, 2 alternative literal của rolespec trong
        // grammar, current_user/session_user cũng chạy được thật trên Postgres ở các vị trí này) - TRỪ
        // DROP ROLE/USER/GROUP: rolespec cùng khớp ở đây (droprolestmt: DROP (ROLE|USER|GROUP_P) ...
        // role_list) nhưng Postgres thật từ chối "cannot use special role specifier in DROP ROLE".
        if (matchedRuleNames.contains("rolespec")) {
            SchemaIndex.roles.forEach(r -> suggests.add(Suggestion.of(r, SuggestionType.ROLE)));
            if (!isDropRoleContext(beforeCaret)) {
                suggests.add(Suggestion.of("current_user", SuggestionType.KEYWORD));
                suggests.add(Suggestion.of("session_user", SuggestionType.KEYWORD));
            }
            // PUBLIC (pseudo-role, không có trong pg_roles) - chỉ hợp lệ ở grantee của GRANT/REVOKE cấp
            // QUYỀN TRÊN OBJECT (grantstmt/revokestmt/defaclaction: "GRANT ... ON ... TO/FROM ..." - có
            // ON), KHÔNG hợp lệ ở grantrolestmt/revokerolestmt (GRANT role TO role - không ON, verify
            // thật: "role public does not exist") hay bất kỳ rolespec nào khác (OWNER TO, DROP OWNED...).
            if (isObjectGrantContext(beforeCaret)) {
                suggests.add(Suggestion.of("public", SuggestionType.KEYWORD));
            }
        }

        // CREATE/ALTER ... TABLESPACE | (tablespaceclause, ALTER DATABASE...SET TABLESPACE, ALTER
        // TABLE/INDEX/MATERIALIZED VIEW...SET TABLESPACE, CREATE INDEX...TABLESPACE, DROP TABLESPACE...)
        // - "TABLESPACE name" dùng chung rule "name" (-> colid) với rất nhiều vị trí khác (đã có if/else
        // riêng phía trên), token TABLESPACE ngay trước caret là tín hiệu rõ ràng, không đa nghĩa như
        // ROLE/USER/GROUP_P - tablespace thật từ pg_tablespace (SchemaIndex.tablespaces).
        if (matchedRuleNames.contains("colid") && isTablespaceNamePosition(beforeCaret)) {
            SchemaIndex.tablespaces.forEach(t -> suggests.add(Suggestion.of(t, SuggestionType.TABLESPACE)));
        }

        // CREATE ROLE/USER/GROUP ... WITH |, ALTER ROLE ... WITH | - option (SUPERUSER/CREATEDB/...)
        // route qua nhánh "identifier" dùng chung của alteroptroleelem/createoptroleelem (grammar thật
        // của Postgres: các từ này KHÔNG phải reserved keyword, xử lý ngữ nghĩa sau parse, không phải
        // literal token nên hoàn toàn không lộ ra qua candidates.tokens) - "identifier" chưa đăng ký
        // preferred rule (rule generic dùng ở hàng trăm chỗ, quá rủi ro đăng ký rộng), nên gợi ý cứng ở
        // đây, chỉ khi token WITH ngay trước caret VÀ câu lệnh bắt đầu bằng đúng 1 trong 4 dạng - verify
        // từng từ bằng Postgres thật (CREATE ROLE x WITH ...): SUPERUSER/CREATEDB/CREATEROLE/LOGIN/
        // REPLICATION/BYPASSRLS/INHERIT (+ dạng NO...) và "IN ROLE"/"IN GROUP" đều chạy được. Cố tình
        // BỎ createuser/nocreateuser dù IntelliJ có gợi ý - Postgres 18 thật từ chối "unrecognized role
        // option createuser" (option cũ, đã bỏ từ lâu, IntelliJ gợi sai).
        if (isRoleOptionListPosition(beforeCaret)) {
            for (String kw : ROLE_OPTION_KEYWORDS) {
                suggests.add(Suggestion.of(kw, SuggestionType.KEYWORD));
            }
            // "IN ROLE"/"IN GROUP" chỉ có ở createoptroleelem (CREATE ROLE/USER/GROUP), KHÔNG có ở
            // alteroptroleelem (ALTER ROLE) - verify thật: "ALTER ROLE x IN ROLE y" báo syntax error.
            if (isCreateRoleVariant(beforeCaret)) {
                suggests.add(Suggestion.of("in role", SuggestionType.KEYWORD));
                suggests.add(Suggestion.of("in group", SuggestionType.KEYWORD));
            }
        }

        // "func_name"/"type_function_name" - tham chiếu tên HÀM ĐÃ CÓ (CREATE OPERATOR
        // RESTRICT/JOIN=, CREATE AGGREGATE sfunc/finalfunc=, DROP FUNCTION...) hoặc tên KIỂU
        // (RETURNS SETOF|%TYPE của func_type) - gợi ý cả 2 nguồn thật đã có sẵn (SchemaIndex.functions/
        // dataTypes) vì rule dùng CHUNG, không tách được ngữ nghĩa chỉ từ tên rule. "func_name" đã là
        // preferred rule từ trước (chặn được tràn keyword) nhưng CHƯA TỪNG được nối để thêm gợi ý
        // thật - phát hiện qua audit noise, cùng đợt với type_function_name.
        //
        // Tách 2 nguồn theo đúng vị trí (bug thật: trước đây LUÔN thêm cả hàm lẫn kiểu):
        // - func_name khớp (DROP FUNCTION |, EXECUTE FUNCTION |, WITH FUNCTION |) -> CHỈ hàm; riêng
        //   vị trí BIỂU THỨC (columnref cùng khớp) thêm cả kiểu: typed literal "int4 '5'" đi qua
        //   AexprConst: func_name sconst (đã kiểm chứng trên Postgres thật).
        // - type_function_name dưới func_return (RETURNS |) / func_arg (DROP AGGREGATE a(|) -> vị trí
        //   KIỂU, "typename" cùng khớp đã tự thêm datatype - không thêm hàm.
        // - còn lại (def_arg: sfunc = | / restrict = | / stype = |) -> không phân biệt được hàm hay
        //   kiểu chỉ từ rule -> giữ cả 2.
        if (matchedRuleNames.contains("func_name")) {
            SchemaIndex.functions.forEach(fn -> suggests.add(Suggestion.of(fn, SuggestionType.FUNCTION)));
            if (matchedRuleNames.contains("columnref")) {
                SchemaIndex.dataTypes.forEach(t -> suggests.add(Suggestion.of(t, SuggestionType.DATATYPE, t)));
            }
        } else if (matchedRuleNames.contains("type_function_name")
                && !isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_type_function_name, PostgreSQLParser.RULE_func_return)
                && !isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_type_function_name, PostgreSQLParser.RULE_func_arg)) {
            SchemaIndex.functions.forEach(fn -> suggests.add(Suggestion.of(fn, SuggestionType.FUNCTION)));
            SchemaIndex.dataTypes.forEach(t -> suggests.add(Suggestion.of(t, SuggestionType.DATATYPE, t)));
        }

        // "nonreservedword_or_sconst" dùng chung cho NHIỀU vị trí khác nhau (CREATE DATABASE OWNER/
        // TEMPLATE/ENCODING, DO/CREATE FUNCTION ... LANGUAGE, ALTER EXTENSION VERSION/FROM...) -
        // phải tự phân biệt bằng keyword đứng NGAY TRƯỚC caret, vì rule KHÔNG cho biết ngữ nghĩa
        // thật (chỉ "1 định danh hoặc chuỗi"). OWNER -> role thật; LANGUAGE -> ngôn ngữ thật; còn lại
        // KHÔNG có dữ liệu thật để gợi ý (TEMPLATE/ENCODING/VERSION/FROM là tên DB mẫu/encoding/số
        // hiệu bản mở rộng - không model hoá trong SchemaIndex) - chỉ cần KHÔNG bịa, không thêm gì.
        if (matchedRuleNames.contains("nonreservedword_or_sconst")) {
            int precedingKeyword = lastRealTokenTypeBefore(syntacticResults);
            if (precedingKeyword == PostgreSQLParser.OWNER) {
                SchemaIndex.roles.forEach(r -> suggests.add(Suggestion.of(r, SuggestionType.ROLE)));
            } else if (precedingKeyword == PostgreSQLParser.LANGUAGE) {
                SchemaIndex.languages.forEach(l -> suggests.add(Suggestion.of(l, SuggestionType.OTHER)));
            }
        }

        // FOR VALUES FROM (|) / TO (|) của partition bound chỉ nhận biểu thức hằng, không có cột nào
        // trong scope để tham chiếu.
        if (matchedRuleNames.contains("columnref")) {
            if (isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_columnref, PostgreSQLParser.RULE_partitionboundspec)) {
                SchemaIndex.functions.forEach(fn -> suggests.add(Suggestion.of(fn, SuggestionType.FUNCTION)));
            } else {
                addColumnSuggestions(suggests, semanticResult, true, true);
            }
        }

        boolean isColidAlias = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_relation_expr_opt_alias); // DELETE FROM ... (colid = alias)
        boolean isColidDropTarget = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_alter_table_cmd);  // ALTER TABLE ... DROP COLUMN (colid = tên cột bị xoá)
        boolean isColumnrefColumn = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_columnref);   // WHERE u.| (colid bên trong columnref)
        boolean isColidIndexColumn = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_index_elem); // CREATE INDEX ... (col) (colid = cột lập index)
        boolean isColidSetTarget = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_set_target); // UPDATE SET / INSERT ON CONFLICT DO UPDATE SET (colid = assignment-target)
        boolean isColidUsingClauseColumn = isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_join_qual); // JOIN ... USING (col1, col2) (colid = cột chung 2 bảng)
        // INSERT INTO t (col, |) - chỉ danh sách cột (insert_column_item), KHÔNG phải mọi colid nằm
        // đâu đó trong câu INSERT (bản cũ dùng ancestor insertstmt nên "INSERT ... SELECT |" cũng bị
        // coi là danh sách cột thuần).
        boolean isColidInsert = isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_insert_column_item);

        // MERGE ... WHEN MATCHED THEN UPDATE SET | - vế TRÁI của phép gán chỉ được là cột bảng TARGET
        // (bảng USING/source cùng visible trong scope của mergestmt nhưng không thể là đích gán).
        // Vế phải (a_expr sau dấu "=") đi qua columnref chứ không qua set_target nên KHÔNG bị giới
        // hạn này - vẫn thấy cả 2 bảng.
        boolean isMergeSetTarget = isColidSetTarget
                && isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_merge_update_clause)
                && semanticResult.ddlTargetAlias() != null;

        // JOIN ... USING (|) - chỉ tên cột CHUNG của các bảng trong join mới hợp lệ (name_list của
        // USING), không phải mọi cột của mọi bảng. Phân biệt với "JOIN ... ON a_expr" (cũng nằm
        // dưới join_qual) bằng việc colid nằm trong name_list.
        boolean isJoinUsingColumn = isColidUsingClauseColumn
                && isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_name_list);

        // Danh sách cột trong ngoặc NGAY SAU tên bảng: COPY t (a, |), GRANT UPDATE (a, |) ON t,
        // ANALYZE t (a, |), REFERENCES t (a, |). colid nằm dưới opt_column_list/opt_name_list; bảng
        // tương ứng được PostgresScopeBuilder đăng ký riêng cho từng statement.
        boolean isColidTableColumnList =
                (isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_opt_column_list)
                        && (isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_copystmt)
                        || isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_privilege)
                        || isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_colconstraintelem)
                        || isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_constraintelem)))
                        || (isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_opt_name_list)
                        && isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_vacuum_relation));

        // colid ở đây LÀ định danh THUẦN (colid opt_indirection - xem insert_column_item,
        // set_target, opt_column_list...) - KHÔNG phải a_expr, nên KHÔNG được gợi ý hàm (bug thật,
        // phát hiện ở insertstmt: "INSERT INTO t (|" gợi ý cả count/sum/avg/now trong khi grammar
        // chỉ nhận đúng 1 tên cột, không nhận lời gọi hàm). isColumnrefColumn/isColidIndexColumn giữ
        // nguyên gợi ý hàm vì đó là vị trí BIỂU THỨC thật (WHERE u.|, index trên biểu thức
        // lower(col)) - chỉ 4 case dưới đây là định danh thuần.
        boolean isColidPureIdentifierList = isColidDropTarget || isColidSetTarget || isColidUsingClauseColumn || isColidInsert || isColidTableColumnList;

        // Các vị trí trên nhận TÊN CỘT TRẦN - dạng có tiền tố bảng/alias bị Postgres từ chối (đã
        // kiểm chứng trên Postgres 18 thật: "INSERT INTO users (users.id)", "UPDATE users SET
        // users.name = ..", "JOIN .. USING (u.id)", "CREATE INDEX ON users (users.id)", "MERGE ..
        // UPDATE SET o.status = ..", "DROP COLUMN users.email" đều lỗi). MenuCompleter chèn NGUYÊN key
        // khi không ở dot-mode, nên key phải là tên trần (bug thật: trước đây chèn "users.id").
        boolean bareColumnPosition = matchedRuleNames.contains("colid") && semanticResult.qualifier() == null
                && (isMergeSetTarget || isJoinUsingColumn || isColidPureIdentifierList || isColidIndexColumn);
        int columnsFrom = suggests.size();

        if (matchedRuleNames.contains("colid") && isMergeSetTarget) {
            addTargetOnlyColumnSuggestions(suggests, semanticResult);
        } else if (matchedRuleNames.contains("colid") && isJoinUsingColumn) {
            addCommonColumnSuggestions(suggests, semanticResult);
        } else if (matchedRuleNames.contains("colid") && isColidPureIdentifierList) {
            addColumnSuggestions(suggests, semanticResult, false, false);
        } else if (matchedRuleNames.contains("colid") && (isColumnrefColumn || isColidIndexColumn)) {
            // Cột hệ thống không dùng được trong CREATE INDEX (Postgres: "index creation on system
            // columns is not supported") - chỉ thêm ở vị trí biểu thức thật (columnref).
            addColumnSuggestions(suggests, semanticResult, true, isColumnrefColumn && !isColidIndexColumn);
        }

        if (bareColumnPosition) {
            for (int i = columnsFrom; i < suggests.size(); i++) {
                Suggestion col = suggests.get(i);
                if (col.getType() == SuggestionType.COLUMN && col.getKey().contains(".")) {
                    String bare = col.getKey().substring(col.getKey().lastIndexOf('.') + 1);
                    suggests.set(i, Suggestion.of(bare, SuggestionType.COLUMN, col.getColumnType()));
                }
            }
        }

        // Bug thật (phát hiện qua so IntelliJ): ở "CREATE ROLE/USER/GROUP...WITH |" và "...TABLESPACE |",
        // candidates.rules() rỗng hoàn toàn (không rule nào preferred khớp - optrolelist:
        // createoptroleelem* là Kleene-star, có thể match rỗng), engine completion-core rơi vào fallback
        // dump MỌI token còn "reach được về cấu trúc" - lẫn cả token chỉ có nghĩa bên trong block PL/pgSQL
        // ($$ ... $$, vd ELSIF/RAISE/DIAGNOSTICS/SQLSTATE) dù câu đang gõ không hề ở trong block nào.
        // Lọc bỏ đúng tập token rác đã xác nhận (không đụng đến keyword literal HỢP LỆ đã có ở 2 vị trí
        // này như admin/connection limit/inherit/password/role/sysid/unencrypted/user/valid until, hay
        // keyword ở BẤT KỲ vị trí nào khác - chỉ áp dụng đúng 2 context đã verify).
        boolean isTablespacePos = isTablespaceNamePosition(beforeCaret);
        if (isRoleOptionListPosition(beforeCaret) || isTablespacePos) {
            suggests.removeIf(s -> s.getType() == SuggestionType.KEYWORD
                    && (PLPGSQL_NOISE_KEYWORDS.contains(s.getKey())
                            || (isTablespacePos && TABLESPACE_SIBLING_LEAK_KEYWORDS.contains(s.getKey()))));
        }

        return suggests;
    }

    /**
     * any_name đứng ở vị trí chỉ nhận tên đối tượng KHÔNG phải bảng: COLLATE any_name (cột/index),
     * operator class trong index_elem, và mọi any_name dưới definestmt (CREATE COLLATION ... FROM
     * any_name, CREATE AGGREGATE/OPERATOR/TYPE...).
     */
    private static boolean isNonTableAnyNameContext(PostgresSyntacticAnalyzer.Result syn) {
        return isRuleInContext(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_colconstraint)
                || isRuleInContext(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_index_elem_options)
                || isRuleInContext(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_opt_class)
                || isRuleInContext(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_opt_collate)
                || isRuleInContext(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_opt_collate_clause)
                || isRuleAncestorAnywhere(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_definestmt)
                // CREATE CONVERSION conv_name FOR ... TO ... FROM any_name - CẢ 2 any_name ở đây
                // đều không phải bảng (any_name đầu = tên conversion MỚI đang đặt, any_name sau FROM
                // = tên hàm chuyển đổi encoding có sẵn, vd utf8_to_latin1) - bug thật phát hiện qua
                // GrammarBreadthTest (any_name mặc định gợi ý bảng vô điều kiện).
                || isRuleAncestorAnywhere(syn, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_createconversionstmt);
    }

    /**
     * Token thật gần nhất trước caret (bỏ qua IF [NOT] EXISTS / CONCURRENTLY / ONLY và cặp
     * "schema.") là SEQUENCE / INDEX / COLLATION / COLLATE -> đang đặt tên sequence/index/collation,
     * không phải tên bảng (vd "DROP SEQUENCE |", "ALTER SEQUENCE |", "REINDEX INDEX CONCURRENTLY |").
     */
    private static boolean followsNonTableObjectKeyword(PostgresSyntacticAnalyzer.Result syn) {
        var ts = syn.tokenStream();
        int i = syn.caretTokenIndex() - 1;
        while (i >= 0) {
            Token t = ts.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                i--;
                continue;
            }
            int type = t.getType();
            if (type == PostgreSQLParser.DOT) {
                i--;
                while (i >= 0 && ts.get(i).getChannel() != Token.DEFAULT_CHANNEL) i--;
                i--; // bỏ tên schema đứng trước dấu chấm
                continue;
            }
            if (type == PostgreSQLParser.IF_P || type == PostgreSQLParser.EXISTS || type == PostgreSQLParser.NOT
                    || type == PostgreSQLParser.CONCURRENTLY || type == PostgreSQLParser.ONLY) {
                i--;
                continue;
            }
            return type == PostgreSQLParser.SEQUENCE || type == PostgreSQLParser.INDEX
                    || type == PostgreSQLParser.COLLATION || type == PostgreSQLParser.COLLATE;
        }
        return false;
    }

    private static boolean isRuleInContext(PostgresSyntacticAnalyzer.Result syn, int ruleId, int expectedParentRuleId) {
        List<RuleCallStack.RuleFrame> path = syn.candidates().rules.get(ruleId);
        if (path == null || path.isEmpty()) return false;
        // Frame CUỐI CÙNG trong path (ancestor gần nhất) chính là rule cha
        // trực tiếp đã dẫn tới rule này - đây là thứ quyết định ngữ nghĩa.
        int immediateParent = path.get(path.size() - 1).ruleId();
        return immediateParent == expectedParentRuleId;
    }

    private static boolean isRuleAncestorAnywhere(PostgresSyntacticAnalyzer.Result syn, int ruleId, int ancestorRuleIdToFind) {
        List<RuleCallStack.RuleFrame> path = syn.candidates().rules.get(ruleId);
        if (path == null) return false;
        return path.stream().anyMatch(f -> f.ruleId() == ancestorRuleIdToFind);
    }

    /**
     * Token type THẬT (bỏ qua hidden channel) đứng NGAY TRƯỚC caret - dùng để phân biệt ngữ nghĩa
     * cho các rule dùng-chung (vd "nonreservedword_or_sconst") khi bản thân rule không đủ thông tin.
     * Trả -1 nếu chưa gõ gì trước caret.
     */
    private static int lastRealTokenTypeBefore(PostgresSyntacticAnalyzer.Result syn) {
        var ts = syn.tokenStream();
        for (int i = syn.caretTokenIndex() - 1; i >= 0; i--) {
            Token t = ts.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            return t.getType();
        }
        return -1;
    }

    private static void addKeywordSuggestions(List<Suggestion> suggests, Integer key, List<Integer> following) {
        String text = KeywordText.of(PostgreSQLParser.VOCABULARY, key, following); // null = không phải từ khoá thật
        if (text != null) {
            suggests.add(Suggestion.of(text, SuggestionType.KEYWORD));
        }
    }

    private static void addDataTypeSuggestions(List<Suggestion> suggests) {
        SchemaIndex.dataTypes.forEach(t -> suggests.add(Suggestion.of(t, SuggestionType.DATATYPE, t)));
    }

    /**
     * Mỗi bảng/view/materialized view trong Postgres đồng thời là 1 KIỂU composite cùng tên - hợp lệ ở
     * mọi vị trí khai báo kiểu (RETURNS users, CAST(x AS users), x::users, cột kiểu users, CREATE
     * DOMAIN ... AS users, ADD ATTRIBUTE a users...), đã kiểm chứng trên Postgres 18 thật. Thiếu hẳn
     * trước đây (phát hiện khi so với completion của IntelliJ). Chỉ áp cho vị trí "typename" thật, không
     * áp cho typed literal trong biểu thức ("users '(1,a,b)'" hợp lệ nhưng là nhiễu).
     */
    private static void addCompositeTypeSuggestions(List<Suggestion> suggests) {
        SchemaIndex.schemaTableIndex.values().forEach(t ->
                suggests.add(Suggestion.of(t.fullName(), SuggestionType.DATATYPE, "composite (" + t.kind() + ")")));
    }

    private static void addTableAliasSuggestions(List<Suggestion> suggests, PostgresSyntacticAnalyzer.Result syn, PostgresSemanticAnalyzer.Result sem) {
        var tableName = PostgresAliasNameSuggester.extractTableBeforeAs(syn.tokenStream(), syn.caretTokenIndex());
        if (tableName != null) {
            String alias = PostgresAliasNameSuggester.suggestAlias(sem.visibleAliases(), tableName);
            suggests.add(Suggestion.of(alias, SuggestionType.ALIAS));
        }
    }

    /** Cột của TỪNG alias visible (alias -> danh sách suggest cột), dùng chung cho các kiểu lọc theo alias. */
    private static java.util.Map<String, List<Suggestion>> columnsPerVisibleAlias(PostgresSemanticAnalyzer.Result sem) {
        var perAlias = new java.util.LinkedHashMap<String, List<Suggestion>>();
        sem.visibleAliases().forEach((alias, table) -> {
            var cols = new ArrayList<Suggestion>();
            var derived = sem.visibleDerivedScopes().get(alias);
            if (derived != null) {
                DerivedColumnExpander.addDerivedColumns(cols, alias, derived);
            } else {
                SchemaIndex.getColumnsOfTable(table).forEach(c -> cols.add(Suggestion.of(alias + "." + c.name(), SuggestionType.COLUMN, c.dataType())));
            }
            perAlias.put(alias, cols);
        });
        return perAlias;
    }

    /** Như addColumnSuggestions nhưng chỉ cột của alias {@link PostgresSemanticAnalyzer.Result#ddlTargetAlias()}. */
    private static void addTargetOnlyColumnSuggestions(List<Suggestion> suggests, PostgresSemanticAnalyzer.Result sem) {
        // Vế trái SET là định danh cột THUẦN (set_target), không phải biểu thức - không gợi ý hàm
        // (bug thật: MERGE ... UPDATE SET | từng hiện count/sum/avg/now, khác hẳn UPDATE ... SET |).
        var cols = columnsPerVisibleAlias(sem).get(sem.ddlTargetAlias());
        if (cols != null) {
            suggests.addAll(cols);
        }
    }

    /**
     * JOIN ... USING (|): chỉ gợi ý cột có TÊN xuất hiện ở ít nhất 2 alias visible (cột chung
     * giữa các bảng tham gia join), mỗi alias đóng góp 1 gợi ý dạng alias.cột.
     */
    private static void addCommonColumnSuggestions(List<Suggestion> suggests, PostgresSemanticAnalyzer.Result sem) {
        var perAlias = columnsPerVisibleAlias(sem);
        var aliasCountByColumn = new java.util.HashMap<String, Integer>();
        perAlias.values().forEach(cols -> cols.stream()
                .map(s -> s.getKey().substring(s.getKey().indexOf('.') + 1).toLowerCase())
                .distinct()
                .forEach(name -> aliasCountByColumn.merge(name, 1, Integer::sum)));
        perAlias.values().forEach(cols -> cols.stream()
                .filter(s -> aliasCountByColumn.get(s.getKey().substring(s.getKey().indexOf('.') + 1).toLowerCase()) >= 2)
                .forEach(suggests::add));
    }

    private static void addColumnSuggestions(List<Suggestion> suggests, PostgresSemanticAnalyzer.Result sem,
                                             boolean includeFunctions, boolean includeSystemColumns) {
        if (includeFunctions) {
            SchemaIndex.functions.forEach(fn -> suggests.add(Suggestion.of(fn, SuggestionType.FUNCTION)));
        }
        if (sem.qualifier() != null) {
            String qualifier = sem.qualifier();
            if (sem.qualifierDerivedScope() != null) {
                DerivedColumnExpander.addDerivedColumns(suggests, qualifier, sem.qualifierDerivedScope());
            } else {
                String table = sem.qualifierResolvesTo() != null ? sem.qualifierResolvesTo() : qualifier;
                SchemaIndex.getColumnsOfTable(table).forEach(c -> suggests.add(Suggestion.of(qualifier + "." + c.name(), SuggestionType.COLUMN, c.dataType())));
                if (includeSystemColumns) {
                    addSystemColumns(suggests, qualifier, table);
                }
            }
        } else if (!sem.visibleAliases().isEmpty()) {
            sem.visibleAliases().forEach((alias, table) -> {
                var derived = sem.visibleDerivedScopes().get(alias);
                if (derived != null) {
                    DerivedColumnExpander.addDerivedColumns(suggests, alias, derived);
                } else {
                    SchemaIndex.getColumnsOfTable(table).forEach(c -> suggests.add(Suggestion.of(alias + "." + c.name(), SuggestionType.COLUMN, c.dataType())));
                    if (includeSystemColumns) {
                        addSystemColumns(suggests, alias, table);
                    }
                }
            });
        }
    }

    /** Cột hệ thống Postgres (tên -> kiểu) - có trên MỌI bảng/materialized view, SELECT được như cột thường. */
    private static final Map<String, String> SYSTEM_COLUMNS = Map.of(
            "tableoid", "oid", "xmin", "xid", "cmin", "cid", "xmax", "xid", "cmax", "cid", "ctid", "tid");

    /**
     * Thêm cột hệ thống (ctid, xmin, xmax, cmin, cmax, tableoid) cho {@code table} nếu nó là bảng hoặc
     * materialized view - view/subquery/CTE KHÔNG có (Postgres 18: "column ctid does not exist"). Chỉ gọi
     * ở vị trí biểu thức: INSERT (...), SET, USING (...), CREATE INDEX (...) đều từ chối cột hệ thống.
     */
    private static void addSystemColumns(List<Suggestion> suggests, String qualifier, String table) {
        TableInfo info = SchemaIndex.tableIndex.get(table);
        if (info == null || !("table".equals(info.kind()) || "materialized view".equals(info.kind()))) {
            return;
        }
        SYSTEM_COLUMNS.forEach((name, type) ->
                suggests.add(Suggestion.of(qualifier + "." + name, SuggestionType.COLUMN, type)));
    }

    private static void addTableNameSuggestions(List<Suggestion> suggests, PostgresSyntacticAnalyzer.Result syn, java.util.Set<String> visibleCteNames) {
        int caretTokenIndex = syn.caretTokenIndex();
        var tokenStream = syn.tokenStream();
        Set<String> kinds = allowedRelationKinds(syn);
        if (caretTokenIndex >= 2) {
            Token tok = tokenStream.get(caretTokenIndex - 1);
            if (tok.getType() == PostgreSQLParser.DOT) {
                Token prev = tokenStream.get(caretTokenIndex - 2);
                if (prev.getType() == PostgreSQLParser.Identifier) {
                    String schema = prev.getText();
                    SchemaIndex.getTablesBySchema(schema).stream()
                            .filter(t -> kinds == null || kinds.contains(t.kind()))
                            .forEach(t -> suggests.add(Suggestion.of(t.fullName(), SuggestionType.fromLabel(t.kind()))));
                    return;
                }
            }
        }
        SchemaIndex.schemaTableIndex.values().stream()
                .filter(t -> kinds == null || kinds.contains(t.kind()))
                .forEach(t -> suggests.add(Suggestion.of(t.fullName(), SuggestionType.fromLabel(t.kind()))));
        // Tên schema làm tiền tố ("pg_catalog." rồi chọn tiếp bảng) - hợp lệ ở mọi vị trí tên bảng, kể cả lệnh
        // DDL (DROP TABLE public.x). Thiếu hẳn trước đây (phát hiện khi so với IntelliJ).
        SchemaIndex.schemas.forEach(schema -> suggests.add(Suggestion.of(schema.name(), SuggestionType.SCHEMA)));
        if (kinds != null) {
            return; // CTE chỉ tham chiếu được trong câu truy vấn, không phải đích của lệnh DDL/utility
        }
        // Tên CTE (WITH cte AS (...)) cũng là 1 "bảng" hợp lệ để gõ trong FROM - bug thật đã sửa:
        // trước đây HOÀN TOÀN không gợi ý được tên CTE lúc đang gõ dở (chỉ resolve được SAU khi gõ
        // xong nguyên tên nhờ resolveAsExistingCte ở tầng semantic, không phải lúc completion).
        visibleCteNames.forEach(name -> suggests.add(Suggestion.of(name, SuggestionType.TABLE)));
    }

    private static final Set<String> TABLE = Set.of("table");
    private static final Set<String> VIEW = Set.of("view");
    private static final Set<String> MATVIEW = Set.of("materialized view");
    private static final Set<String> TABLE_OR_MATVIEW = Set.of("table", "materialized view");
    private static final Set<String> TABLE_OR_VIEW = Set.of("table", "view");

    /**
     * Loại relation (TableInfo.kind) mà lệnh đang gõ chấp nhận ở vị trí tên bảng - null = mọi loại
     * (SELECT/JOIN, ALTER TABLE, GRANT...). Bảng dưới đây đối chiếu với Postgres 18 THẬT (chạy từng
     * lệnh trên table/view/materialized view, xem lỗi 42809 "... is not a table"...) - bug thật:
     * trước đây "DROP TABLE |", "TRUNCATE |", "REFRESH MATERIALIZED VIEW |"... đều gợi ý cả view.
     */
    private static Set<String> allowedRelationKinds(PostgresSyntacticAnalyzer.Result syn) {
        List<Integer> before = realTokenTypesBeforeTableName(syn);
        if (before.isEmpty()) {
            return null;
        }
        int prev = before.get(0);
        int prev2 = before.size() > 1 ? before.get(1) : -1;
        int prev3 = before.size() > 2 ? before.get(2) : -1;
        int first = before.get(before.size() - 1); // token đầu câu lệnh
        switch (prev) {
            case PostgreSQLParser.TABLE -> {
                return switch (prev2) {
                    case PostgreSQLParser.DROP, PostgreSQLParser.TRUNCATE -> TABLE;
                    case PostgreSQLParser.FOR, PostgreSQLParser.ADD_P, PostgreSQLParser.SET -> TABLE; // PUBLICATION / ALTER EXTENSION ADD
                    case PostgreSQLParser.ON -> prev3 == PostgreSQLParser.COMMENT ? TABLE : null;  // COMMENT ON TABLE (GRANT ON TABLE: mọi loại)
                    case PostgreSQLParser.REINDEX -> TABLE_OR_MATVIEW;
                    case PostgreSQLParser.LOCK_P -> TABLE_OR_VIEW;
                    default -> null;
                };
            }
            case PostgreSQLParser.VIEW -> {
                if (prev2 == PostgreSQLParser.MATERIALIZED) {
                    return MATVIEW; // REFRESH / DROP / ALTER MATERIALIZED VIEW
                }
                return prev2 == PostgreSQLParser.DROP || prev2 == PostgreSQLParser.ALTER ? VIEW : null;
            }
            case PostgreSQLParser.TRUNCATE -> {
                return TABLE;
            }
            case PostgreSQLParser.LOCK_P -> {
                return TABLE_OR_VIEW;
            }
            case PostgreSQLParser.CLUSTER -> {
                return TABLE_OR_MATVIEW;
            }
            case PostgreSQLParser.INTO -> {
                return first == PostgreSQLParser.INSERT || first == PostgreSQLParser.MERGE ? TABLE_OR_VIEW : null;
            }
            case PostgreSQLParser.UPDATE -> {
                return first == PostgreSQLParser.UPDATE ? TABLE_OR_VIEW : null;
            }
            case PostgreSQLParser.FROM -> {
                return first == PostgreSQLParser.DELETE_P && before.size() == 2 ? TABLE_OR_VIEW : null;
            }
            case PostgreSQLParser.ON -> {
                return first == PostgreSQLParser.CREATE && before.contains(PostgreSQLParser.INDEX) ? TABLE_OR_MATVIEW : null;
            }
            default -> {
                if (first == PostgreSQLParser.VACUUM || first == PostgreSQLParser.ANALYZE || first == PostgreSQLParser.ANALYSE) {
                    return TABLE_OR_MATVIEW;
                }
                return null;
            }
        }
    }

    /**
     * Token type thật trước vị trí tên bảng, GẦN NHẤT ĐỨNG ĐẦU, dừng ở đầu câu lệnh (sau ";"). Bỏ
     * qua phần không đổi ngữ nghĩa: IF [NOT] EXISTS, ONLY, CONCURRENTLY, tên đã gõ trong danh
     * sách ("DROP TABLE a, |" -> như "DROP TABLE |") và phần "schema." đang gõ dở.
     */
    private static List<Integer> realTokenTypesBeforeTableName(PostgresSyntacticAnalyzer.Result syn) {
        var ts = syn.tokenStream();
        List<Integer> out = new ArrayList<>();
        boolean skippingNames = true;
        for (int i = syn.caretTokenIndex() - 1; i >= 0; i--) {
            Token t = ts.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            int type = t.getType();
            if (type == PostgreSQLParser.SEMI) {
                break;
            }
            if (skippingNames) {
                if (type == PostgreSQLParser.DOT || type == PostgreSQLParser.COMMA || type == PostgreSQLParser.Identifier
                        || type == PostgreSQLParser.IF_P || type == PostgreSQLParser.EXISTS || type == PostgreSQLParser.NOT
                        || type == PostgreSQLParser.ONLY || type == PostgreSQLParser.CONCURRENTLY) {
                    continue;
                }
                skippingNames = false;
            }
            out.add(type);
        }
        return out;
    }

    /** {@code DROP ROLE|USER|GROUP [IF EXISTS] role_list} - {@code before} từ
     * {@link #realTokenTypesBeforeTableName} (đã skip Identifier/COMMA/IF_P/EXISTS đúng cấu trúc
     * role_list, dù tên gọi hướng theo bảng), tính 1 lần ở đầu {@code suggests()} rồi truyền vào - KHÔNG
     * tự quét lại token stream mỗi helper (trước đây làm vậy, review phát hiện quét thừa 4-6 lần/caret). */
    private static boolean isDropRoleContext(List<Integer> before) {
        if (before.size() < 2 || before.get(1) != PostgreSQLParser.DROP) {
            return false;
        }
        int prev = before.get(0);
        return prev == PostgreSQLParser.ROLE || prev == PostgreSQLParser.USER || prev == PostgreSQLParser.GROUP_P;
    }

    /** grantstmt/revokestmt/defaclaction: {@code GRANT ... ON ... TO|FROM ...} - phân biệt với
     * grantrolestmt/revokerolestmt ({@code GRANT role TO role}, không có ON) bằng chính token ON, không
     * cần biết hết cấu trúc privilege_target/privilege_list (nhiều dạng, không đáng công parse lại). */
    private static boolean isObjectGrantContext(List<Integer> before) {
        boolean hasGrantOrRevoke = false;
        boolean hasOn = false;
        for (int type : before) {
            if (type == PostgreSQLParser.GRANT || type == PostgreSQLParser.REVOKE) {
                hasGrantOrRevoke = true;
            } else if (type == PostgreSQLParser.ON) {
                hasOn = true;
            }
        }
        return hasGrantOrRevoke && hasOn;
    }

    /** {@code TABLESPACE name} - token TABLESPACE ngay trước caret (before.get(0), không skip vì
     * TABLESPACE không nằm trong danh sách skip của {@link #realTokenTypesBeforeTableName}). */
    private static boolean isTablespaceNamePosition(List<Integer> before) {
        return !before.isEmpty() && before.get(0) == PostgreSQLParser.TABLESPACE;
    }

    // Verify bằng Postgres 18 thật (xem chú thích nơi gọi) - KHÔNG có createuser/nocreateuser (option cũ
    // đã bỏ, Postgres thật từ chối dù IntelliJ có gợi ý).
    private static final List<String> ROLE_OPTION_KEYWORDS = List.of(
            "superuser", "nosuperuser", "createdb", "nocreatedb", "createrole", "nocreaterole",
            "inherit", "noinherit", "login", "nologin", "replication", "noreplication",
            "bypassrls", "nobypassrls");

    // Token chỉ có nghĩa THẬT bên trong block PL/pgSQL ($$ ... $$) - xác nhận qua so với IntelliJ (0 item
    // trong số này xuất hiện ở "CREATE ROLE...WITH |"/"...TABLESPACE |" thật) VÀ đọc grammar (đều là
    // literal token của statement/decl_stmt trong plsql.g4-style block, không thuộc alteroptroleelem/
    // createoptroleelem/tablespaceclause). KHÔNG đụng keyword hợp lệ khác của 2 vị trí này (admin/
    // connection limit/inherit/password/role/sysid/unencrypted/user/valid until/tablespace).
    private static final Set<String> PLPGSQL_NOISE_KEYWORDS = Set.of(
            "absolute", "alias", "array", "assert", "backward", "call", "chain", "close", "collate",
            "column", "commit", "constant", "constraint", "continue", "current", "cursor", "debug",
            "default", "diagnostics", "do", "dump", "elsif", "error", "exception", "exit", "fetch",
            "first", "forward", "get", "info", "insert", "is", "last", "log", "move", "next", "no",
            "notice", "open", "option", "outer", "perform", "print_strict_params", "prior", "query",
            "raise", "relative", "reset", "return", "reverse", "rollback", "rowtype", "schema", "scroll",
            "set", "slice", "sqlstate", "stacked", "table", "type", "use_column", "use_variable",
            "variable_conflict", "warning");

    /** {@code CREATE ROLE|USER|GROUP ... WITH |} / {@code ALTER ROLE ... WITH |} - token WITH ngay
     * trước caret, VÀ câu lệnh bắt đầu đúng bằng 1 trong 4 dạng trên (kiểm 2 token đầu câu, không phải
     * đoán qua "colid"/"identifier" vì rule đó không preferred - xem chú thích nơi gọi). */
    private static boolean isRoleOptionListPosition(List<Integer> before) {
        if (before.size() < 3 || before.get(0) != PostgreSQLParser.WITH) {
            return false;
        }
        int first = firstStatementToken(before);
        int second = before.get(before.size() - 2);
        if (first == PostgreSQLParser.CREATE) {
            return second == PostgreSQLParser.ROLE || second == PostgreSQLParser.USER
                    || second == PostgreSQLParser.GROUP_P;
        }
        if (first == PostgreSQLParser.ALTER) {
            return second == PostgreSQLParser.ROLE || second == PostgreSQLParser.USER;
        }
        return false;
    }

    /** Chỉ gọi khi {@link #isRoleOptionListPosition} đã true - câu bắt đầu bằng CREATE (không phải ALTER). */
    private static boolean isCreateRoleVariant(List<Integer> before) {
        return firstStatementToken(before) == PostgreSQLParser.CREATE;
    }

    /** Token đầu câu lệnh (xa nhất trong {@code before} - {@link #realTokenTypesBeforeTableName} quét
     * NGƯỢC từ caret về đầu câu, nên phần tử cuối danh sách là token gõ đầu tiên). */
    private static int firstStatementToken(List<Integer> before) {
        return before.get(before.size() - 1);
    }

    // "ALTER DATABASE name SET TABLESPACE |" cùng dòng với "ALTER DATABASE name (WITH createdb_opt_list?
    // | createdb_opt_list? | SET TABLESPACE name | ...)" - sau khi đã gõ hết "SET TABLESPACE", engine vẫn
    // rơi tàn dư token của nhánh SIBLING createdb_opt_list (encoding/owner/template/connection limit/
    // location/from current) - verify thật: "ALTER DATABASE db SET TABLESPACE encoding" bị nuốt làm TÊN
    // tablespace (không phải lỗi cú pháp ở "encoding"), xác nhận không có gì hợp lệ ở đây ngoài tên
    // tablespace thật. "to"/"tablespace" cũng rơi vào tàn dư tương tự (từ chính literal đã gõ).
    private static final Set<String> TABLESPACE_SIBLING_LEAK_KEYWORDS = Set.of(
            "connection limit", "encoding", "from current", "location", "owner", "tablespace", "template", "to");
}
