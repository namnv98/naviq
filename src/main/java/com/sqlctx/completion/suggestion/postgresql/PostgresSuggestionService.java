package com.sqlctx.completion.suggestion.postgresql;

import com.sqlctx.antlr4.postgresql.PostgreSQLParser;
import com.sqlctx.completion.input.CompletionInputPreparer;
import com.sqlctx.completion.model.CandidatesResult;
import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.model.SuggestionType;
import com.sqlctx.completion.ranking.SuggestFilter;
import com.sqlctx.completion.semantic.postgresql.PostgresSemanticAnalyzer;
import com.sqlctx.completion.suggestion.SuggestionService;
import com.sqlctx.completion.syntactic.postgresql.PostgresSyntacticAnalyzer;
import com.sqlctx.schema.Identifiers;
import com.sqlctx.schema.SchemaIndex;
import com.sqlctx.schema.TableInfo;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import static com.sqlctx.completion.suggestion.SuggestionSupport.*;

/**
 * Ghép gợi ý cho Postgres: tầng cú pháp (rule/keyword hợp lệ tại caret) + tầng ngữ nghĩa (alias/scope) +
 * {@link SchemaIndex} (bảng, cột, hàm, kiểu, role...). Các rule như colid/any_name/name được grammar dùng chung ở
 * rất nhiều vị trí nên phần lớn logic dưới đây là phân biệt vị trí qua rule cha hoặc token trước caret.
 */
public class PostgresSuggestionService implements SuggestionService {

    public static List<Suggestion> suggests(CompletionInputPreparer.PrepareCompletionInput input) {
        return SuggestFilter.filter(suggests(input.sql(), input.cursor()), input.prefix(), input.dotMode());
    }

    @Override
    public List<Suggestion> suggest(String sql, Integer cursorCharPos) {
        return suggests(sql, cursorCharPos);
    }

    public static List<Suggestion> suggests(String sql, Integer cursorCharPos) {
        int cursorOffset = cursorCharPos != null ? cursorCharPos : sql.length();
        // nextval('|'): caret trong chuỗi chưa đóng - grammar không giúp được, chỉ tên sequence là hợp lý
        if (SEQUENCE_FUNCTION_ARGUMENT.matcher(sql.substring(0, Math.min(cursorOffset, sql.length()))).find()) {
            List<Suggestion> sequences = new ArrayList<>();
            // trong chuỗi regclass tên viết y như ngoài chuỗi (trần / schema.tên / "Hoa")
            SchemaIndex.sequences.forEach(q -> sequences.add(Suggestion.of(SchemaIndex.displayName(q), SuggestionType.SEQUENCE)));
            return sequences;
        }
        int syntacticCursor = syntacticCursor(sql, cursorOffset);
        PostgresSyntacticAnalyzer.Result syn = PostgresSyntacticAnalyzer.analyze(sql, syntacticCursor);
        CandidatesResult candidates = syn.candidates();
        // tầng cú pháp chạy trước: tầng ngữ nghĩa chèn token giả đúng loại grammar cho phép tại caret
        PostgresSemanticAnalyzer.Result sem = PostgresSemanticAnalyzer.analyze(sql, cursorOffset,
                PostgresSyntacticAnalyzer.caretTokenTypeToInsert(candidates), candidates.caretTokenTypes);

        List<Suggestion> suggests = new ArrayList<>();
        addKeywords(suggests, PostgreSQLParser.VOCABULARY, candidates);

        Set<String> rules = PostgresMatchedRuleResolver.computeMatchedRuleNames(syn, syntacticCursor);
        List<Integer> before = realTokenTypesBeforeTableName(syn);
        boolean roleOptionList = isRoleOptionListPosition(before);
        boolean tablespaceName = isTablespaceNamePosition(before);

        // typename đã vào từ trước caret ("varchar(|)") thì chỉ nhận literal. colid cùng khớp thì typename là
        // nhánh ảo: "columnDef: colid typename" là tuần tự, "ADD COLUMN |" đang chờ tên cột chứ chưa phải kiểu.
        if (rules.contains("typename") && !rules.contains("colid")
                && !PostgresMatchedRuleResolver.isRuleEnteredBeforeCaret(syn, PostgreSQLParser.RULE_typename)) {
            addDataTypes(suggests);
            addCompositeTypes(suggests);
        }

        if (rules.contains("table_alias")) {
            addTableAlias(suggests, syn, sem);
        }

        // any_name/qualified_name dùng chung cho mọi loại đối tượng (sequence, index, collation...)
        int objectKeyword = nonTableObjectKeyword(syn);
        boolean nameFollowsNonTableKeyword = objectKeyword != -1;
        // tên index/sequence đã có (DROP/ALTER/REINDEX...), không phải đặt tên mới (CREATE INDEX|SEQUENCE |)
        if ((rules.contains("any_name") || rules.contains("qualified_name"))
                && (before.isEmpty() || firstStatementToken(before) != PostgreSQLParser.CREATE)) {
            if (objectKeyword == PostgreSQLParser.INDEX) {
                SchemaIndex.indexes.forEach(i -> suggests.add(Suggestion.of(SchemaIndex.displayName(i), SuggestionType.INDEX, "on " + i.table())));
            } else if (objectKeyword == PostgreSQLParser.SEQUENCE) {
                SchemaIndex.sequences.forEach(q -> suggests.add(Suggestion.of(SchemaIndex.displayName(q), SuggestionType.SEQUENCE)));
            }
        }
        // DROP/ALTER/COMMENT ON TRIGGER |: tên trigger đã có (tên trần - trigger thuộc bảng, ghi ở "ON bảng")
        if (rules.contains("colid") && hasParent(candidates, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_name)
                && !before.isEmpty() && before.get(0) == PostgreSQLParser.TRIGGER
                && firstStatementToken(before) != PostgreSQLParser.CREATE) {
            SchemaIndex.triggers.forEach(t -> suggests.add(Suggestion.of(Identifiers.quote(t.name(), SchemaIndex.dialect),
                    SuggestionType.TRIGGER, "on " + t.table())));
        }
        if (rules.contains("any_name") && !nameFollowsNonTableKeyword && !isNonTableAnyNameContext(candidates)) {
            addTableNames(suggests, syn, sem.visibleCteNames());
        }
        if (rules.contains("qualified_name") && !nameFollowsNonTableKeyword) {
            addTableNames(suggests, syn, sem.visibleCteNames());
        }
        // "JOIN |": cả cụm "bảng alias on điều kiện" cho bảng có quan hệ với bảng đã có trong FROM
        if ((rules.contains("qualified_name") || rules.contains("any_name"))
                && previousTokenType(syn.tokenStream(), syn.caretTokenIndex()) == PostgreSQLParser.JOIN) {
            addJoinClauses(suggests, sem, SchemaIndex::displayName);
        }

        if (rules.contains("rolespec")) {
            addRoles(suggests);
            // current_user/session_user là 2 alternative literal của rolespec (preferred rule nên engine không
            // tự bung ra) - trừ DROP ROLE: "cannot use special role specifier in DROP ROLE"
            if (!isDropRoleContext(before)) {
                suggests.add(Suggestion.of("current_user", SuggestionType.KEYWORD));
                suggests.add(Suggestion.of("session_user", SuggestionType.KEYWORD));
            }
            // PUBLIC chỉ hợp lệ ở grantee của GRANT/REVOKE quyền trên object (có ON), không ở GRANT role TO role
            if (isObjectGrantContext(before)) {
                suggests.add(Suggestion.of("public", SuggestionType.KEYWORD));
            }
        }

        if (rules.contains("colid") && tablespaceName) {
            SchemaIndex.tablespaces.forEach(t -> suggests.add(Suggestion.of(t, SuggestionType.TABLESPACE)));
        }

        // option của CREATE/ALTER ROLE ... WITH đi qua nhánh "identifier" dùng chung (không phải literal token, không
        // lộ ra qua candidates) nên gợi ý cứng. Không có createuser/nocreateuser: Postgres 18 từ chối.
        if (roleOptionList) {
            ROLE_OPTION_KEYWORDS.forEach(kw -> suggests.add(Suggestion.of(kw, SuggestionType.KEYWORD)));
            // IN ROLE/IN GROUP chỉ có ở CREATE, ALTER ROLE báo syntax error
            if (firstStatementToken(before) == PostgreSQLParser.CREATE) {
                suggests.add(Suggestion.of("in role", SuggestionType.KEYWORD));
                suggests.add(Suggestion.of("in group", SuggestionType.KEYWORD));
            }
        }

        // func_name: chỉ hàm; ở vị trí biểu thức (columnref cùng khớp) thêm kiểu cho typed literal "int4 '5'".
        // type_function_name dưới func_return/func_arg là vị trí kiểu (typename đã thêm); còn lại (sfunc = |...)
        // không phân biệt được hàm hay kiểu nên giữ cả 2.
        if (rules.contains("func_name")) {
            addFunctions(suggests);
            if (rules.contains("columnref")) {
                addDataTypes(suggests);
            }
        } else if (rules.contains("type_function_name")
                && !hasAncestor(candidates, PostgreSQLParser.RULE_type_function_name, PostgreSQLParser.RULE_func_return)
                && !hasAncestor(candidates, PostgreSQLParser.RULE_type_function_name, PostgreSQLParser.RULE_func_arg)) {
            addFunctions(suggests);
            addDataTypes(suggests);
        }

        // "nonreservedword_or_sconst" dùng chung (OWNER/TEMPLATE/ENCODING/LANGUAGE/VERSION...): phân biệt bằng
        // keyword ngay trước caret; TEMPLATE/ENCODING/VERSION không có dữ liệu thật để gợi ý.
        if (rules.contains("nonreservedword_or_sconst")) {
            int previous = previousTokenType(syn.tokenStream(), syn.caretTokenIndex());
            if (previous == PostgreSQLParser.OWNER) {
                addRoles(suggests);
            } else if (previous == PostgreSQLParser.LANGUAGE) {
                SchemaIndex.languages.forEach(l -> suggests.add(Suggestion.of(l, SuggestionType.OTHER)));
            }
        }

        if (rules.contains("columnref")) {
            // FOR VALUES FROM (|) của partition bound chỉ nhận biểu thức hằng
            if (hasAncestor(candidates, PostgreSQLParser.RULE_columnref, PostgreSQLParser.RULE_partitionboundspec)) {
                addFunctions(suggests);
            } else {
                addFunctions(suggests);
                addColumns(suggests, sem, PostgresSuggestionService::addSystemColumns);
                addPostgresArgumentValues(suggests, sql, cursorOffset);
                addEnumValues(suggests, sem, syn.tokenStream(), syn.caretTokenIndex(),
                        type -> type == PostgreSQLParser.EQUAL || type == PostgreSQLParser.NOT_EQUALS, PostgreSQLParser.DOT);
                if (sem.qualifier() == null && isJoinConditionStart(syn, candidates)) {
                    addJoinConditions(suggests, sem, aliasBeforeOn(syn.tokenStream(), syn.caretTokenIndex(),
                            PostgreSQLParser.ON, PostgreSQLParser.DOT, sem));
                }
            }
        }

        if (rules.contains("colid")) {
            addColidColumns(suggests, candidates, sem);
        }

        addStarExpansion(suggests, sem, sql, cursorOffset);
        addGroupByExpansion(suggests, syn.tokenStream(), syn.caretTokenIndex());

        // 2 vị trí này không có preferred rule nào khớp (optrolelist có thể rỗng) nên engine dump mọi token còn
        // reach được, lẫn token chỉ có nghĩa trong block PL/pgSQL - lọc đúng tập rác đã xác nhận.
        if (roleOptionList || tablespaceName) {
            suggests.removeIf(s -> s.getType() == SuggestionType.KEYWORD
                    && (PLPGSQL_NOISE_KEYWORDS.contains(s.getKey())
                    || (tablespaceName && TABLESPACE_SIBLING_LEAK_KEYWORDS.contains(s.getKey()))));
        }
        return suggests;
    }

    /** Caret ngay trong chuỗi tham số đầu của nextval/currval/setval (đã gõ dở tên hoặc chưa). */
    private static final java.util.regex.Pattern SEQUENCE_FUNCTION_ARGUMENT =
            java.util.regex.Pattern.compile("(?i)\\b(nextval|currval|setval)\\s*\\(\\s*'[\\w.$]*$");

    /** colid (định danh trần) dùng chung ở rất nhiều vị trí - chọn nguồn cột theo rule cha/tổ tiên của nó. */
    private static void addColidColumns(List<Suggestion> suggests, CandidatesResult candidates, PostgresSemanticAnalyzer.Result sem) {
        IntPredicate under = ancestor -> hasAncestor(candidates, PostgreSQLParser.RULE_colid, ancestor);
        IntPredicate parent = rule -> hasParent(candidates, PostgreSQLParser.RULE_colid, rule);

        boolean dropTarget = parent.test(PostgreSQLParser.RULE_alter_table_cmd);   // ALTER TABLE ... DROP COLUMN |
        boolean columnref = parent.test(PostgreSQLParser.RULE_columnref);          // WHERE u.|
        boolean indexColumn = parent.test(PostgreSQLParser.RULE_index_elem);       // CREATE INDEX ... (|)
        boolean setTarget = parent.test(PostgreSQLParser.RULE_set_target);         // UPDATE / ON CONFLICT ... SET |
        boolean usingClause = under.test(PostgreSQLParser.RULE_join_qual);         // JOIN ... USING (|) hoặc ON
        boolean insertColumn = under.test(PostgreSQLParser.RULE_insert_column_item); // INSERT INTO t (|
        // vế trái SET của MERGE chỉ được là cột bảng đích (bảng USING cùng visible nhưng không gán được)
        boolean mergeSetTarget = setTarget && under.test(PostgreSQLParser.RULE_merge_update_clause)
                && sem.ddlTargetAlias() != null;
        boolean joinUsing = usingClause && under.test(PostgreSQLParser.RULE_name_list);
        // danh sách cột ngay sau tên bảng: COPY t (|), GRANT UPDATE (|) ON t, REFERENCES t (|), ANALYZE t (|)
        boolean tableColumnList = (under.test(PostgreSQLParser.RULE_opt_column_list)
                && (under.test(PostgreSQLParser.RULE_copystmt) || under.test(PostgreSQLParser.RULE_privilege)
                || under.test(PostgreSQLParser.RULE_colconstraintelem) || under.test(PostgreSQLParser.RULE_constraintelem)))
                || (under.test(PostgreSQLParser.RULE_opt_name_list) && under.test(PostgreSQLParser.RULE_vacuum_relation));
        // định danh thuần, không phải biểu thức: không gợi ý hàm
        boolean pureIdentifier = dropTarget || setTarget || usingClause || insertColumn || tableColumnList;

        int columnsFrom = suggests.size();
        if (mergeSetTarget) {
            addTargetColumns(suggests, sem);
        } else if (joinUsing) {
            addCommonColumns(suggests, sem);
        } else if (pureIdentifier) {
            addColumns(suggests, sem, null);
        } else if (columnref || indexColumn) {
            addFunctions(suggests);
            // CREATE INDEX không nhận cột hệ thống ("index creation on system columns is not supported")
            addColumns(suggests, sem, columnref && !indexColumn ? PostgresSuggestionService::addSystemColumns : null);
        }

        // Các vị trí trên chỉ nhận tên cột trần: Postgres từ chối "INSERT INTO users (users.id)", "SET u.name =",
        // "USING (u.id)", "DROP COLUMN users.email"...; MenuCompleter chèn nguyên key nên key phải là tên trần.
        boolean bareColumnPosition = sem.qualifier() == null
                && (mergeSetTarget || joinUsing || pureIdentifier || indexColumn);
        if (bareColumnPosition) {
            for (int i = columnsFrom; i < suggests.size(); i++) {
                Suggestion col = suggests.get(i);
                if (col.getType() == SuggestionType.COLUMN && col.getKey().contains(".")) {
                    String bare = col.getKey().substring(col.getKey().lastIndexOf('.') + 1);
                    suggests.set(i, Suggestion.of(bare, SuggestionType.COLUMN, col.getColumnType())
                            .withScopeDistance(col.getScopeDistance()));
                }
            }
        }
    }

    /**
     * Đầu 1 điều kiện nối: ngay sau ON (JOIN ... ON, MERGE ... USING ... ON), hoặc sau AND bên trong điều kiện JOIN.
     * AND ngoài join_qual (WHERE a AND |) không tính.
     */
    private static boolean isJoinConditionStart(PostgresSyntacticAnalyzer.Result syn, CandidatesResult candidates) {
        int previous = previousTokenType(syn.tokenStream(), syn.caretTokenIndex());
        return previous == PostgreSQLParser.ON
                || (previous == PostgreSQLParser.AND && hasAncestor(candidates, PostgreSQLParser.RULE_columnref, PostgreSQLParser.RULE_join_qual));
    }

    /** any_name ở vị trí chỉ nhận tên đối tượng không phải bảng (COLLATE, operator class, CREATE AGGREGATE/TYPE/CONVERSION...). */
    private static boolean isNonTableAnyNameContext(CandidatesResult candidates) {
        return hasParent(candidates, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_colconstraint)
                || hasParent(candidates, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_index_elem_options)
                || hasParent(candidates, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_opt_class)
                || hasParent(candidates, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_opt_collate)
                || hasParent(candidates, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_opt_collate_clause)
                || hasAncestor(candidates, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_definestmt)
                || hasAncestor(candidates, PostgreSQLParser.RULE_any_name, PostgreSQLParser.RULE_createconversionstmt);
    }

    /**
     * Token thật gần nhất trước caret (bỏ qua IF [NOT] EXISTS / CONCURRENTLY / ONLY và "schema.") là SEQUENCE /
     * INDEX / COLLATION / COLLATE: đang đặt tên đối tượng đó, không phải bảng ("DROP SEQUENCE |"). Trả loại token
     * đó, -1 nếu không phải.
     */
    private static int nonTableObjectKeyword(PostgresSyntacticAnalyzer.Result syn) {
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
                    || type == PostgreSQLParser.COLLATION || type == PostgreSQLParser.COLLATE ? type : -1;
        }
        return -1;
    }

    private static void addRoles(List<Suggestion> suggests) {
        SchemaIndex.roles.forEach(r -> suggests.add(Suggestion.of(r, SuggestionType.ROLE)));
    }

    /** Mỗi bảng/view/matview đồng thời là 1 kiểu composite cùng tên (RETURNS users, x::users, cột kiểu users...). */
    private static void addCompositeTypes(List<Suggestion> suggests) {
        SchemaIndex.schemaTableIndex.values().forEach(t ->
                suggests.add(Suggestion.of(SchemaIndex.displayName(t), SuggestionType.DATATYPE, "composite (" + t.kind() + ")")));
    }

    private static void addTableAlias(List<Suggestion> suggests, PostgresSyntacticAnalyzer.Result syn, PostgresSemanticAnalyzer.Result sem) {
        var tableName = PostgresAliasNameSuggester.extractTableBeforeAs(syn.tokenStream(), syn.caretTokenIndex());
        if (tableName != null) {
            suggests.add(Suggestion.of(PostgresAliasNameSuggester.suggestAlias(sem.visibleAliases(), tableName), SuggestionType.ALIAS));
        }
    }

    /** Cột hệ thống: có trên mọi bảng/matview, view/subquery/CTE thì không ("column ctid does not exist"). */
    private static final Map<String, String> SYSTEM_COLUMNS = Map.of(
            "tableoid", "oid", "xmin", "xid", "cmin", "cid", "xmax", "xid", "cmax", "cid", "ctid", "tid");

    private static void addSystemColumns(List<Suggestion> columns, String qualifier, String table) {
        TableInfo info = SchemaIndex.findTable(table);
        if (info == null || !("table".equals(info.kind()) || "materialized view".equals(info.kind()))) {
            return;
        }
        SYSTEM_COLUMNS.forEach((name, type) -> columns.add(Suggestion.of(qualifier + "." + name, SuggestionType.COLUMN, type)));
    }

    private static void addTableNames(List<Suggestion> suggests, PostgresSyntacticAnalyzer.Result syn, Set<String> visibleCteNames) {
        int caretTokenIndex = syn.caretTokenIndex();
        var tokenStream = syn.tokenStream();
        Set<String> kinds = allowedRelationKinds(syn);
        if (caretTokenIndex >= 2 && tokenStream.get(caretTokenIndex - 1).getType() == PostgreSQLParser.DOT) {
            Token prev = tokenStream.get(caretTokenIndex - 2);
            if (prev.getType() == PostgreSQLParser.Identifier) {
                SchemaIndex.getTablesBySchema(prev.getText()).stream()
                        .filter(t -> kinds == null || kinds.contains(t.kind()))
                        .forEach(t -> suggests.add(tableSuggestion(SchemaIndex.qualifiedName(t), t)));
                return;
            }
        }
        SchemaIndex.schemaTableIndex.values().stream()
                .filter(t -> kinds == null || kinds.contains(t.kind()))
                .forEach(t -> suggests.add(tableSuggestion(SchemaIndex.displayName(t), t)));
        SchemaIndex.schemas.forEach(schema -> suggests.add(Suggestion.of(schema.name(), SuggestionType.SCHEMA)));
        if (kinds == null) { // CTE chỉ tham chiếu được trong câu truy vấn, không phải đích lệnh DDL/utility
            visibleCteNames.forEach(name -> suggests.add(Suggestion.of(name, SuggestionType.TABLE)));
        }
    }

    /** Bảng/view; của schema hệ thống (pg_catalog...) thì xếp sau bảng của người dùng. */
    private static Suggestion tableSuggestion(String name, TableInfo t) {
        Suggestion s = Suggestion.of(name, SuggestionType.fromLabel(t.kind()));
        return SchemaIndex.isSystemSchema(t.schema()) ? s.demoted() : s;
    }

    private static final Set<String> TABLE = Set.of("table");
    private static final Set<String> VIEW = Set.of("view");
    private static final Set<String> MATVIEW = Set.of("materialized view");
    private static final Set<String> TABLE_OR_MATVIEW = Set.of("table", "materialized view");
    private static final Set<String> TABLE_OR_VIEW = Set.of("table", "view");

    /**
     * Loại relation lệnh đang gõ chấp nhận ở vị trí tên bảng; null = mọi loại. Đối chiếu Postgres 18 thật (lỗi
     * 42809 "... is not a table"...): DROP TABLE/TRUNCATE chỉ bảng, REFRESH MATERIALIZED VIEW chỉ matview...
     */
    private static Set<String> allowedRelationKinds(PostgresSyntacticAnalyzer.Result syn) {
        List<Integer> before = realTokenTypesBeforeTableName(syn);
        if (before.isEmpty()) {
            return null;
        }
        int prev = before.get(0);
        int prev2 = before.size() > 1 ? before.get(1) : -1;
        int prev3 = before.size() > 2 ? before.get(2) : -1;
        int first = firstStatementToken(before);
        return switch (prev) {
            case PostgreSQLParser.TABLE -> switch (prev2) {
                case PostgreSQLParser.DROP, PostgreSQLParser.TRUNCATE -> TABLE;
                case PostgreSQLParser.FOR, PostgreSQLParser.ADD_P, PostgreSQLParser.SET -> TABLE; // PUBLICATION / ALTER EXTENSION ADD
                case PostgreSQLParser.ON -> prev3 == PostgreSQLParser.COMMENT ? TABLE : null;  // GRANT ON TABLE: mọi loại
                case PostgreSQLParser.REINDEX -> TABLE_OR_MATVIEW;
                case PostgreSQLParser.LOCK_P -> TABLE_OR_VIEW;
                default -> null;
            };
            case PostgreSQLParser.VIEW -> prev2 == PostgreSQLParser.MATERIALIZED ? MATVIEW
                    : prev2 == PostgreSQLParser.DROP || prev2 == PostgreSQLParser.ALTER ? VIEW : null;
            case PostgreSQLParser.TRUNCATE -> TABLE;
            case PostgreSQLParser.LOCK_P -> TABLE_OR_VIEW;
            case PostgreSQLParser.CLUSTER -> TABLE_OR_MATVIEW;
            case PostgreSQLParser.INTO -> first == PostgreSQLParser.INSERT || first == PostgreSQLParser.MERGE ? TABLE_OR_VIEW : null;
            case PostgreSQLParser.UPDATE -> first == PostgreSQLParser.UPDATE ? TABLE_OR_VIEW : null;
            case PostgreSQLParser.FROM -> first == PostgreSQLParser.DELETE_P && before.size() == 2 ? TABLE_OR_VIEW : null;
            case PostgreSQLParser.ON -> first == PostgreSQLParser.CREATE && before.contains(PostgreSQLParser.INDEX) ? TABLE_OR_MATVIEW : null;
            default -> first == PostgreSQLParser.VACUUM || first == PostgreSQLParser.ANALYZE || first == PostgreSQLParser.ANALYSE
                    ? TABLE_OR_MATVIEW : null;
        };
    }

    /**
     * Token thật trước vị trí tên, gần caret nhất đứng đầu, dừng ở đầu câu lệnh (sau ";"). Bỏ qua phần không đổi
     * ngữ nghĩa: IF [NOT] EXISTS, ONLY, CONCURRENTLY, tên đã gõ trong danh sách ("DROP TABLE a, |") và "schema.".
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

    /** {@code DROP ROLE|USER|GROUP [IF EXISTS] role_list}. */
    private static boolean isDropRoleContext(List<Integer> before) {
        if (before.size() < 2 || before.get(1) != PostgreSQLParser.DROP) {
            return false;
        }
        int prev = before.get(0);
        return prev == PostgreSQLParser.ROLE || prev == PostgreSQLParser.USER || prev == PostgreSQLParser.GROUP_P;
    }

    /** {@code GRANT ... ON ... TO|FROM} (quyền trên object) - phân biệt với {@code GRANT role TO role} bằng token ON. */
    private static boolean isObjectGrantContext(List<Integer> before) {
        return (before.contains(PostgreSQLParser.GRANT) || before.contains(PostgreSQLParser.REVOKE))
                && before.contains(PostgreSQLParser.ON);
    }

    private static boolean isTablespaceNamePosition(List<Integer> before) {
        return !before.isEmpty() && before.get(0) == PostgreSQLParser.TABLESPACE;
    }

    /** {@code CREATE ROLE|USER|GROUP ... WITH |} / {@code ALTER ROLE|USER ... WITH |}. */
    private static boolean isRoleOptionListPosition(List<Integer> before) {
        if (before.size() < 3 || before.get(0) != PostgreSQLParser.WITH) {
            return false;
        }
        int first = firstStatementToken(before);
        int second = before.get(before.size() - 2);
        if (first == PostgreSQLParser.CREATE) {
            return second == PostgreSQLParser.ROLE || second == PostgreSQLParser.USER || second == PostgreSQLParser.GROUP_P;
        }
        return first == PostgreSQLParser.ALTER && (second == PostgreSQLParser.ROLE || second == PostgreSQLParser.USER);
    }

    /** {@code before} quét ngược từ caret nên phần tử cuối là token đầu câu lệnh. */
    private static int firstStatementToken(List<Integer> before) {
        return before.get(before.size() - 1);
    }

    private static final List<String> ROLE_OPTION_KEYWORDS = List.of(
            "superuser", "nosuperuser", "createdb", "nocreatedb", "createrole", "nocreaterole",
            "inherit", "noinherit", "login", "nologin", "replication", "noreplication",
            "bypassrls", "nobypassrls");

    /** Token chỉ có nghĩa trong block PL/pgSQL ($$ ... $$), lọt ra ở "CREATE ROLE ... WITH |" và "... TABLESPACE |". */
    private static final Set<String> PLPGSQL_NOISE_KEYWORDS = Set.of(
            "absolute", "alias", "array", "assert", "backward", "call", "chain", "close", "collate",
            "column", "commit", "constant", "constraint", "continue", "current", "cursor", "debug",
            "default", "diagnostics", "do", "dump", "elsif", "error", "exception", "exit", "fetch",
            "first", "forward", "get", "info", "insert", "is", "last", "log", "move", "next", "no",
            "notice", "open", "option", "outer", "perform", "print_strict_params", "prior", "query",
            "raise", "relative", "reset", "return", "reverse", "rollback", "rowtype", "schema", "scroll",
            "set", "slice", "sqlstate", "stacked", "table", "type", "use_column", "use_variable",
            "variable_conflict", "warning");

    /**
     * "ALTER DATABASE db SET TABLESPACE |": tàn dư token của nhánh anh em createdb_opt_list - ở đây chỉ có tên
     * tablespace là hợp lệ ("... SET TABLESPACE encoding" bị nuốt làm tên tablespace).
     */
    private static final Set<String> TABLESPACE_SIBLING_LEAK_KEYWORDS = Set.of(
            "connection limit", "encoding", "from current", "location", "owner", "tablespace", "template", "to");
}
