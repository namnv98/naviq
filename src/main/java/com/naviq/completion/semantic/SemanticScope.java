package com.naviq.completion.semantic;

import com.naviq.antlr4.*;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.*;

/**
 * Build scope (alias -> table) + phát hiện "đang gõ dở sau dấu chấm", chịu được input lỗi/gõ dở nhờ
 * 3 nguyên tắc mirror DBeaver:
 * <p>
 * 1. Grammar patch "indirection_el: DOT (attr_name | STAR)??" (xem PATCH comment trong
 * PostgreSQL.g4) - biến "u." thành hợp lệ về cú pháp, KHÔNG sinh lỗi, KHÔNG kích hoạt ANTLR
 * error-recovery (nguồn gốc bug "cắn nhầm alias" ban đầu). Đây là bản port sang grammar PostgreSQL
 * đầy đủ (rule name kiểu Postgres gram.y: select_no_parens, updatestmt, deletestmt, table_ref,
 * qualified_name, columnref, target_el...) thay cho grammar rút gọn cũ (SelectStmt, TableName,
 * SimpleTable, QualifiedName...). 2. Mọi callback dưới đây defensive - null/thiếu ở đâu thì bỏ qua
 * đúng chỗ đó, KHÔNG throw, KHÔNG làm hỏng phần scope đã build được (mirror
 * SQLQuerySemanticUtils.performPartialResolution của DBeaver). 3. hasErrorChildren() (mirror
 * STMTreeNode.hasErrorChildren()) - phát hiện subtree bị ANTLR error-recovery vá vào (xem
 * PostgreSQLParserBase + STMTreeTermErrorNode) để KHÔNG đăng ký alias/tên dựa trên dữ liệu không
 * đáng tin, thay vì mù quáng tin mọi thứ ANTLR trả về.
 * <p>
 * GHI CHÚ KHÁC BIỆT SO VỚI GRAMMAR CŨ:
 * - "qualifiedName" cũ (dùng chung cho cả tên bảng lẫn tên cột dạng "a.b") tách thành 2 rule
 * riêng trong grammar mới: {@code qualified_name} (chỉ dùng cho tên đối tượng - bảng/CTE, trong
 * relation_expr/insert_target/...) và {@code columnref} (dùng cho biểu thức cột trong SELECT
 * list/WHERE/...). CẢ HAI đều có cấu trúc "colid indirection?" giống nhau nên dùng chung
 * {@link #checkDanglingDot}.
 * - "SimpleTable"/"SubqueryTable" cũ gộp thành 1 rule {@code table_ref} duy nhất (nhiều alternative
 * inline: relation_expr | func_table | xmltable | select_with_parens | LATERAL(...) | "(" table_ref
 * joined_table? ")" , theo sau bởi joined_table* cho JOIN) - phải tự phân biệt alternative nào khớp
 * bằng cách check accessor nào non-null (KHÔNG có label # riêng cho từng alternative).
 * - "selectStmt" cũ tương ứng {@code select_no_parens} mới (KHÔNG phải {@code selectstmt} - rule đó
 * chỉ là wrapper "select_no_parens | select_with_parens", select_with_parens lại bọc select_no_parens
 * nên push scope ở select_no_parens vẫn đảm bảo mỗi khối SELECT chỉ có đúng 1 scope, kể cả khi bọc
 * nhiều lớp dấu ngoặc). UNION/INTERSECT/EXCEPT nhiều "simple_select_pramary" bên trong 1
 * select_no_parens vẫn dùng CHUNG 1 scope (không tách riêng từng vế UNION) - đơn giản hoá có chủ
 * đích, giống cách selectStmt cũ xử lý.
 * - "selectElement"/"selectElements"/"selectAlias" cũ thành {@code target_el} (3 alternative có
 * label: target_columnref cho "col"/"t.col"/"t.*" trần không alias, target_label cho "expr [AS]
 * alias", target_star cho "*" trần) + {@code target_alias}. Không còn rule "selectElements" bọc
 * ngoài để check "SELECT *" - target_star tự xử lý luôn vì "*" trần cũng đi qua target_el như mọi
 * phần tử khác.
 */
public class SemanticScope extends PostgreSQLParserBaseListener {

    public static class Scope {

        public final int id;
        public final Scope parent;
        public final List<Scope> children = new ArrayList<>();
        public final Map<String, String> aliases = new LinkedHashMap<>();
        public int startTokenIndex = -1;
        public int stopTokenIndex = Integer.MAX_VALUE;

        /**
         * Tên cột mà CHÍNH scope này PROJECT ra (SELECT list của nó) - dùng khi scope này được dùng
         * làm nguồn cho 1 alias khác (subquery/CTE) để biết nó "trả ra cột gì". Chỉ chứa cột suy ra
         * được tên (có AS alias, hoặc là 1 columnName đơn giản không alias) - biểu thức phức tạp
         * không alias bị bỏ qua (không suy ra được tên cột thật, giống Postgres tự đặt tên kiểu
         * "?column?").
         */
        public final List<String> projectedColumns = new ArrayList<>();

        /**
         * true nếu SELECT list có ít nhất 1 "*" (bare "SELECT *" hoặc "alias.*") - nghĩa là
         * projectedColumns KHÔNG đủ để biết hết cột output, còn phụ thuộc cột thật của (các) bảng
         * nguồn trong FROM - caller cần tự mở rộng qua schema.
         */
        public boolean hasWildcard = false;

        /**
         * Giống aliases, nhưng CHỈ chứa các alias trỏ tới subquery/CTE (không phải bảng thật) - lưu
         * THẲNG tham chiếu Scope, không cần parse ngược chuỗi "<cte#N>"/"<subquery#N>" như trước.
         * Với alias trỏ tới bảng thật, entry này KHÔNG tồn tại (chỉ có trong aliases).
         */
        public final Map<String, Scope> derivedScopeAliases = new LinkedHashMap<>();

        Scope(int id, Scope parent) {
            this.id = id;
            this.parent = parent;
        }

        /**
         * Alias thấy được tại scope này, gồm cả kế thừa từ outer (correlated subquery).
         */
        public Map<String, String> visibleAliases() {
            Deque<Scope> chain = new ArrayDeque<>();
            for (Scope s = this; s != null; s = s.parent) {
                chain.push(s);
            }
            Map<String, String> result = new LinkedHashMap<>();
            while (!chain.isEmpty()) {
                result.putAll(chain.pop().aliases);
            }
            return result;
        }

        /**
         * Như visibleAliases() nhưng CHỈ phần trỏ tới subquery/CTE, giá trị là Scope thật.
         */
        public Map<String, Scope> visibleDerivedScopes() {
            Deque<Scope> chain = new ArrayDeque<>();
            for (Scope s = this; s != null; s = s.parent) {
                chain.push(s);
            }
            Map<String, Scope> result = new LinkedHashMap<>();
            while (!chain.isEmpty()) {
                result.putAll(chain.pop().derivedScopeAliases);
            }
            return result;
        }
    }

    /**
     * offset ngay sau dấu chấm cụt -> alias đứng trước nó, vd "u." -> {41: "u"}
     */
    public final TreeMap<Integer, String> danglingDotQualifier = new TreeMap<>();

    private int nextId = 0;
    private final Scope root = new Scope(nextId++, null);
    private final Deque<Scope> stack = new ArrayDeque<>(List.of(root));
    private final List<Scope> allScopes = new ArrayList<>(List.of(root));

    public Scope root() {
        return root;
    }

    /**
     * Token index bị báo lỗi bởi ErrorListener (offendingSymbol) - PHẢI truyền vào từ ngoài (xem
     * SemanticScopeTest) vì đây là nguồn đáng tin cậy DUY NHẤT cho MỌI kiểu lỗi, kể cả kiểu ANTLR
     * không tạo ErrorNode trong cây (single-token deletion cho "extraneous input" - xem ghi chú ở
     * isUnreliable()).
     */
    public final Set<Integer> offendingTokenIndices = new HashSet<>();

    /**
     * Mirror STMTreeNode.hasErrorChildren() của DBeaver, NHƯNG bổ sung thêm 1 nguồn phát hiện lỗi
     * thứ 2 vì thực nghiệm cho thấy hasErrorChildren-qua-ErrorNode KHÔNG đáng tin cho mọi trường
     * hợp:
     * <p>
     * - Lỗi "thiếu token" (vd "u." thiếu identifier sau dấu chấm, TRƯỚC KHI patch grammar) -> ANTLR
     * chèn token ảo (tokenIndex == -1) -> LUÔN tạo ErrorNode -> instanceof check đủ. - Lỗi "token
     * thừa" (vd "123abc" - số không hợp lệ ở vị trí identifier) -> ANTLR xóa token thừa bằng
     * single-token-deletion -> KHÔNG đảm bảo tạo ErrorNode (đã xác nhận bằng thực nghiệm) -> phải
     * cross-check bằng offendingTokenIndices (luôn có từ ErrorListener bất kể kiểu lỗi nào).
     */
    public boolean isUnreliable(org.antlr.v4.runtime.ParserRuleContext ctx) {
        boolean treeFlag = hasErrorChildren(ctx);
        boolean intervalFlag = false;
        Integer a = null, b = null;
        if (ctx.getStart() != null && ctx.getStop() != null) {
            a = ctx.getStart().getTokenIndex();
            b = ctx.getStop().getTokenIndex();
            for (int idx : offendingTokenIndices) {
                if (idx >= a && idx <= b) {
                    intervalFlag = true;
                    break;
                }
            }
        }
        if (debug) {
            System.out.println("  [debug isUnreliable] text=\"" + ctx.getText() + "\""
                    + " span=[" + a + "," + b + "]"
                    + " treeFlag=" + treeFlag
                    + " intervalFlag=" + intervalFlag
                    + " offendingTokenIndices=" + offendingTokenIndices);
        }
        return treeFlag || intervalFlag;
    }

    /**
     * Bật để in chẩn đoán chi tiết mỗi lần isUnreliable() được gọi.
     */
    public boolean debug = false;

    /**
     * Mirror STMTreeNode.hasErrorChildren() của DBeaver: duyệt đệ quy toàn bộ subtree, true nếu có
     * BẤT KỲ node nào là STMTreeTermErrorNode (tức ANTLR đã phải error-recovery để vá vào chỗ này -
     * không phải input thật).
     */
    public static boolean hasErrorChildren(ParseTree node) {
        if (node instanceof ErrorNode) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasErrorChildren(node.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scope chứa tokenIndex - PHẢI dùng interval containment [start, stop], không chỉ so
     * startTokenIndex (bug đã xác nhận: scope con đã ĐÓNG từ lâu, nếu chỉ so startTokenIndex lớn
     * nhất <= tokenIndex, sẽ bị chọn nhầm thay vì scope cha đang bao trọn tokenIndex thật sự - vd
     * cursor ở WHERE của outer query, sau khi 1 subquery lồng sâu trong FROM đã đóng). Trong các
     * scope có interval chứa tokenIndex, ưu tiên interval HẸP NHẤT (tức sâu/cụ thể nhất).
     */
    public Scope scopeAt(int tokenIndex) {
        Scope best = null;
        for (Scope s : allScopes) {
            if (s.startTokenIndex < 0) {
                continue; // root hoặc chưa set
            }
            if (s.startTokenIndex <= tokenIndex && tokenIndex <= s.stopTokenIndex) {
                if (best == null || spanOf(s) < spanOf(best)) {
                    best = s;
                }
            }
        }
        return best != null ? best : root;
    }

    private static long spanOf(Scope s) {
        long stop = s.stopTokenIndex == Integer.MAX_VALUE ? Long.MAX_VALUE : s.stopTokenIndex;
        return stop - s.startTokenIndex;
    }

    // ---- select_no_parens = 1 scope (tương ứng "selectStmt" ở grammar cũ) ----
    // Lưu ý: selectstmt = select_no_parens | select_with_parens, và select_with_parens lại bọc
    // select_no_parens - nên dù "(((SELECT ...)))" bọc bao nhiêu lớp ngoặc, chỉ có ĐÚNG 1
    // select_no_parens thật sự bên trong -> không lo bị double-push. UNION/INTERSECT/EXCEPT nhiều
    // simple_select_pramary trong CÙNG 1 select_no_parens dùng CHUNG 1 scope (không tách riêng
    // từng vế) - đơn giản hoá có chủ đích, giống cách selectStmt cũ xử lý.

    @Override
    public void enterSelect_no_parens(PostgreSQLParser.Select_no_parensContext ctx) {
        pushScope(ctx.getStart());
    }

    @Override
    public void exitSelect_no_parens(PostgreSQLParser.Select_no_parensContext ctx) {
        popScope(ctx.getStop());
    }

    // ---- UPDATE / DELETE - grammar mới KHÔNG dùng table_ref cho 2 câu này mà dùng
    //      relation_expr_opt_alias riêng (updatestmt: ... UPDATE relation_expr_opt_alias SET ... ;
    //      deletestmt: ... DELETE_P FROM relation_expr_opt_alias ...) nên phải đăng ký alias trực
    //      tiếp, đồng thời PHẢI push scope riêng (giống select_no_parens) để WHERE/subquery/WITH
    //      bên trong UPDATE/DELETE thấy được alias của chính statement đó. ----

    @Override
    public void enterUpdatestmt(PostgreSQLParser.UpdatestmtContext ctx) {
        Scope child = pushScope(ctx.getStart());
        registerDmlTableAlias(child, ctx.relation_expr_opt_alias());
    }

    @Override
    public void exitUpdatestmt(PostgreSQLParser.UpdatestmtContext ctx) {
        popScope(ctx.getStop());
    }

    @Override
    public void enterDeletestmt(PostgreSQLParser.DeletestmtContext ctx) {
        Scope child = pushScope(ctx.getStart());
        registerDmlTableAlias(child, ctx.relation_expr_opt_alias());
    }

    @Override
    public void exitDeletestmt(PostgreSQLParser.DeletestmtContext ctx) {
        popScope(ctx.getStop());
    }

    private Scope pushScope(Token startToken) {
        Scope parent = stack.peek();
        Scope child = new Scope(nextId++, parent);
        child.startTokenIndex = startToken != null ? startToken.getTokenIndex() : -1;
        parent.children.add(child);
        allScopes.add(child);
        stack.push(child);
        return child;
    }

    private void popScope(Token stopToken) {
        if (stack.size() > 1) {
            Scope s = stack.pop(); // defensive: không pop root nếu lệch cặp
            s.stopTokenIndex = stopToken != null ? stopToken.getTokenIndex() : Integer.MAX_VALUE;
        }
    }

    private void registerDmlTableAlias(
            Scope target,
            PostgreSQLParser.Relation_expr_opt_aliasContext relCtx
    ) {
        // relation_expr_opt_alias : relation_expr (AS? colid)? ;
        if (target == null || relCtx == null || relCtx.relation_expr() == null) {
            return;
        }
        PostgreSQLParser.Relation_exprContext tableCtx = relCtx.relation_expr();
        PostgreSQLParser.Qualified_nameContext qualifiedNameCtx = qualifiedNameOf(tableCtx);
        if (qualifiedNameCtx == null) {
            return;
        }
        // Check trên relation_expr + colid (alias) riêng (không phải toàn bộ updatestmt/
        // deletestmt, vì lỗi ở SET/WHERE không liên quan không nên chặn đăng ký alias hợp lệ ở
        // đây - khác với enterTable_ref, "table (AS alias)?" luôn là phần TỪ ĐẦU statement, ít
        // rủi ro sync() xảy ra trước nó hơn hẳn so với vị trí giữa 1 FROM-list dài).
        if (isUnreliable(tableCtx)) {
            return;
        }
        PostgreSQLParser.ColidContext aliasCtx = relCtx.colid();
        if (aliasCtx != null && isUnreliable(aliasCtx)) {
            return;
        }
        String table = qualifiedNameCtx.getText();
        String alias = aliasCtx != null ? aliasCtx.getText() : lastPart(table);
        // resolveAsExistingCte() trả về Scope (không phải String) - phải populate CẢ
        // aliases (dạng hiển thị) LẪN derivedScopeAliases (Scope thật, cho
        // projectedColumns hoạt động đúng với UPDATE/DELETE trên 1 CTE - hiếm nhưng
        // hợp lệ, vd "table" trùng tên CTE thì có).
        Scope cteScope = resolveAsExistingCte(table);
        if (cteScope != null) {
            target.aliases.put(alias, "<cte#" + cteScope.id + ">");
            target.derivedScopeAliases.put(alias, cteScope);
        } else {
            target.aliases.put(alias, table);
        }
    }

    /**
     * relation_expr : qualified_name STAR? | ONLY (qualified_name | OPEN_PAREN qualified_name
     * CLOSE_PAREN) ; - trong CẢ 3 alternative, chỉ có ĐÚNG 1 qualified_name (accessor
     * qualified_name() luôn dùng được trực tiếp bất kể alternative nào khớp).
     */
    private static PostgreSQLParser.Qualified_nameContext qualifiedNameOf(
            PostgreSQLParser.Relation_exprContext relationExprCtx) {
        return relationExprCtx == null ? null : relationExprCtx.qualified_name();
    }

    // ---- SELECT list projection - dùng để biết subquery/CTE "trả ra cột gì" ----
    // target_el : columnref #target_columnref | a_expr target_alias? #target_label | STAR
    // #target_star ; - "SELECT *" trần cũng là 1 target_el (target_star) như mọi phần tử khác,
    // không còn cần rule "selectElements" bọc ngoài để bắt riêng.

    @Override
    public void exitTarget_star(PostgreSQLParser.Target_starContext ctx) {
        Scope cur = stack.peek();
        if (cur != null) {
            cur.hasWildcard = true; // "*" trần, vd "SELECT *" hoặc "SELECT id, *"
        }
    }

    @Override
    public void exitTarget_columnref(PostgreSQLParser.Target_columnrefContext ctx) {
        // columnref trần KHÔNG có alias đi kèm ở alternative này (vd "col", "t.col", "t.*") -
        // "expr AS alias" luôn rơi vào target_label thay vì đây.
        Scope cur = stack.peek();
        if (cur == null || isUnreliable(ctx)) {
            return;
        }
        PostgreSQLParser.ColumnrefContext col = ctx.columnref();
        if (col == null) {
            return;
        }
        if (endsWithStar(col.indirection())) {
            cur.hasWildcard = true; // "alias.*"
            return;
        }
        // không có alias -> chỉ suy ra tên nếu là 1 columnName đơn giản (vd "t.id" -> "id").
        cur.projectedColumns.add(lastPart(col.getText()));
    }

    @Override
    public void exitTarget_label(PostgreSQLParser.Target_labelContext ctx) {
        Scope cur = stack.peek();
        if (cur == null || isUnreliable(ctx)) {
            return;
        }
        if (ctx.a_expr() == null) {
            return;
        }

        String outName;
        PostgreSQLParser.Target_aliasContext aliasCtx = ctx.target_alias();
        if (aliasCtx != null) {
            // target_alias : AS collabel | bare_col_label ; - lấy đúng phần label, KHÔNG dùng
            // getText() của cả target_alias (sẽ dính luôn từ khoá "AS" dính liền, không có
            // khoảng trắng).
            if (aliasCtx.collabel() != null) {
                outName = aliasCtx.collabel().getText();
            } else if (aliasCtx.bare_col_label() != null) {
                outName = aliasCtx.bare_col_label().getText();
            } else {
                outName = null;
            }
        } else {
            // không có alias -> chỉ suy ra được tên nếu expression là 1 identifier đơn giản;
            // biểu thức phức tạp (hàm, phép toán...) không alias thì bỏ qua, KHÔNG đoán bừa
            // tên cột.
            String text = ctx.a_expr().getText();
            outName = text.matches("[a-zA-Z_][a-zA-Z0-9_.]*") ? lastPart(text) : null;
        }
        if (outName != null) {
            cur.projectedColumns.add(outName);
        }
    }

    private static boolean endsWithStar(PostgreSQLParser.IndirectionContext indirection) {
        if (indirection == null || indirection.indirection_el().isEmpty()) {
            return false;
        }
        List<PostgreSQLParser.Indirection_elContext> els = indirection.indirection_el();
        return els.get(els.size() - 1).STAR() != null;
    }

    // ---- FROM/JOIN alias ----
    // table_ref gộp CẢ bảng thật lẫn subquery trong 1 rule (không còn SimpleTable/SubqueryTable
    // riêng như trước) - phải tự phân biệt bằng accessor nào non-null. joined_table* ở cuối +
    // đệ quy qua joined_table.table_ref() khiến listener này tự fire cho MỌI vế JOIN, không cần
    // xử lý join riêng.

    @Override
    public void exitTable_ref(PostgreSQLParser.Table_refContext ctx) {
        if (ctx.relation_expr() != null) {
            handlePlainTableRef(ctx);
        } else if (ctx.select_with_parens() != null) {
            handleSubqueryTableRef(ctx);
        }
        // func_table/xmltable/nhóm "(" table_ref ")" - chưa hỗ trợ alias tracking (hiếm gặp
        // trong ngữ cảnh completion), bỏ qua có chủ đích.
    }

    private void handlePlainTableRef(PostgreSQLParser.Table_refContext ctx) {
        Scope cur = stack.peek();
        if (cur == null) {
            return; // gõ dở -> bỏ qua, không throw
        }
        PostgreSQLParser.Qualified_nameContext qualifiedNameCtx = qualifiedNameOf(ctx.relation_expr());
        if (qualifiedNameCtx == null) {
            return;
        }
        // Check trên TOÀN BỘ ctx (không chỉ ctx.relation_expr()): token bị recovery xóa có thể
        // nằm NGOÀI span riêng của relation_expr (vd bị xử lý ở sync() cấp cha TRƯỚC KHI
        // relation_expr() được gọi) - ctx bao trọn từ trước khi recovery xảy ra.
        if (isUnreliable(ctx)) {
            return;
        }
        String table = qualifiedNameCtx.getText();
        String alias = tableAliasText(ctx);
        if (alias == null) {
            alias = lastPart(table);
        }
        // "FROM table" có thể thực ra là THAM CHIẾU tới 1 CTE cùng tên đã định nghĩa trước đó
        // trong cùng WITH (CTE sau thấy CTE trước) hoặc CTE của 1 WITH bao ngoài đã "chốt" xong -
        // phải ưu tiên check trước khi coi đây là bảng mới.
        Scope cteScope = resolveAsExistingCte(table);
        if (cteScope != null) {
            cur.aliases.put(alias, "<cte#" + cteScope.id + ">");
            cur.derivedScopeAliases.put(alias, cteScope); // lưu thẳng Scope, không cần parse ngược string
        } else {
            cur.aliases.put(alias, table);
        }
    }

    private void handleSubqueryTableRef(PostgreSQLParser.Table_refContext ctx) {
        // select_with_parens opt_alias_clause? (kể cả nhánh LATERAL_P select_with_parens
        // opt_alias_clause?) - dùng "exit" (không phải "enter"): enterTable_ref luôn chạy TRƯỚC
        // khi walker đi vào select_no_parens con bên trong dấu ngoặc, nên tại thời điểm
        // enterTable_ref scope con CHƯA tồn tại. Phải đợi tới exitTable_ref (chạy SAU khi toàn
        // bộ con, gồm cả exitSelect_no_parens của subquery, đã chạy xong) để cur.children mới
        // thật sự chứa scope con.
        PostgreSQLParser.Opt_alias_clauseContext aliasClauseCtx = ctx.opt_alias_clause();
        if (aliasClauseCtx == null) {
            return; // không alias -> không cách nào tham chiếu subquery này, bỏ qua
        }
        if (isUnreliable(ctx)) {
            return; // check toàn bộ ctx, không chỉ opt_alias_clause() riêng lẻ
        }
        Scope cur = stack.peek();
        if (cur == null || cur.children.isEmpty()) {
            return;
        }
        String alias = tableAliasText(ctx);
        if (alias == null) {
            return;
        }
        Scope inner = cur.children.get(cur.children.size() - 1);
        cur.aliases.put(alias, "<subquery#" + inner.id + ">");
        cur.derivedScopeAliases.put(alias, inner); // lưu thẳng Scope
    }

    /**
     * opt_alias_clause : table_alias_clause ; table_alias_clause : AS? table_alias (OPEN_PAREN
     * name_list CLOSE_PAREN)? ; - null nếu table_ref không có alias (vd "FROM users" trần, không
     * "AS u").
     */
    private static String tableAliasText(PostgreSQLParser.Table_refContext ctx) {
        PostgreSQLParser.Opt_alias_clauseContext aliasClauseCtx = ctx.opt_alias_clause();
        if (aliasClauseCtx == null || aliasClauseCtx.table_alias_clause() == null) {
            return null;
        }
        PostgreSQLParser.Table_aliasContext tableAliasCtx =
                aliasClauseCtx.table_alias_clause().table_alias();
        return tableAliasCtx != null ? tableAliasCtx.getText() : null;
    }

    /**
     * Tìm "table" trong danh sách CTE đã ĐÓNG (exitCommon_table_expr đã chạy) tính tới thời điểm
     * này - gồm CTE trước trong CÙNG WITH (pendingCte, chưa merge vào scope) và CTE của WITH bao
     * ngoài đã merge xong vào scope cha. Trả về CHÍNH Scope của CTE đó nếu khớp, null nếu "table"
     * thực sự là 1 bảng độc lập.
     */
    private Scope resolveAsExistingCte(String table) {
        if (!pendingCte.isEmpty()) {
            Scope s = pendingCte.peek().get(table);
            if (s != null) {
                return s;
            }
        }
        // BUG FIX: phải bắt đầu từ CHÍNH scope hiện tại (stack.peek()), KHÔNG phải
        // cur.parent - vì exitWith_clause merge CTE THẲNG vào scope đang xử lý FROM sau
        // đó (host == scope hiện tại), nếu bỏ qua "cur" và chỉ xét tổ tiên, mọi tham
        // chiếu CTE nằm CÙNG scope với WITH clause của nó sẽ luôn resolve fail, rơi về
        // đăng ký như bảng thường (vd "with c as (...) select * from c" - "c" bị coi là
        // bảng tên "c" thay vì <cte#1>).
        for (Scope s = stack.peek(); s != null; s = s.parent) {
            Scope target = s.derivedScopeAliases.get(table);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    // ---- CTE (WITH ... AS (...)) ----

    private final Deque<Scope> withHost = new ArrayDeque<>();
    private final Deque<LinkedHashMap<String, Scope>> pendingCte = new ArrayDeque<>();

    @Override
    public void enterWith_clause(PostgreSQLParser.With_clauseContext ctx) {
        withHost.push(stack.peek());
        pendingCte.push(new LinkedHashMap<>());
    }

    @Override
    public void exitCommon_table_expr(PostgreSQLParser.Common_table_exprContext ctx) {
        // common_table_expr : name opt_name_list? AS opt_materialized? OPEN_PAREN preparablestmt
        // CLOSE_PAREN ;
        if (pendingCte.isEmpty() || ctx.name() == null) {
            return;
        }
        if (isUnreliable(ctx)) {
            return; // check toàn bộ ctx, không chỉ name() riêng lẻ
        }
        Scope host = withHost.peek();
        if (host == null || host.children.isEmpty()) {
            return;
        }
        // Chỉ đáng tin nếu preparablestmt bên trong thực sự là 1 selectstmt (push scope qua
        // select_no_parens) - CTE dạng UPDATE/DELETE/INSERT/MERGE (data-modifying CTE) hiện
        // KHÔNG push scope riêng nên host.children.last() có thể là scope của 1 CTE/subquery
        // KHÁC đứng trước đó -> bỏ qua để tránh gán nhầm, thà thiếu còn hơn sai.
        if (ctx.preparablestmt() == null || ctx.preparablestmt().selectstmt() == null) {
            return;
        }
        pendingCte.peek().put(ctx.name().getText(), host.children.get(host.children.size() - 1));
    }

    @Override
    public void exitWith_clause(PostgreSQLParser.With_clauseContext ctx) {
        Scope host = withHost.isEmpty() ? null : withHost.pop();
        Map<String, Scope> pending = pendingCte.isEmpty() ? null : pendingCte.pop();
        if (host != null && pending != null) {
            pending.forEach((name, s) -> {
                host.aliases.put(name, "<cte#" + s.id + ">");
                host.derivedScopeAliases.put(name, s); // BẮT BUỘC - resolveAsExistingCte's
                // nhánh 2 (CTE của WITH bao ngoài) chỉ tin derivedScopeAliases, không
                // còn fallback qua aliases chung nữa.
            });
        }
    }

    // ---- qualified_name / columnref kết thúc bằng dấu chấm (nhờ grammar patch DOT??) ----
    // Grammar mới TÁCH tên bảng (qualified_name) và biểu thức cột (columnref) thành 2 rule khác
    // nhau (không gộp chung 1 "qualifiedName" như trước), nhưng cấu trúc cả 2 giống hệt nhau
    // ("colid indirection?") nên checkDanglingDot() dùng chung được cho cả 2.

    @Override
    public void exitQualified_name(PostgreSQLParser.Qualified_nameContext ctx) {
        checkDanglingDot(ctx.colid(), ctx.indirection());
    }

    @Override
    public void exitColumnref(PostgreSQLParser.ColumnrefContext ctx) {
        checkDanglingDot(ctx.colid(), ctx.indirection());
    }

    /**
     * indirection_el : DOT (attr_name | STAR)? | "[" ... "]" ; (đã patch dấu "?" - xem
     * PostgreSQL.g4). Nếu indirection_el CUỐI CÙNG là 1 dấu chấm KHÔNG có attr_name/STAR theo
     * sau -> đây chính là vị trí "gõ dở sau dấu chấm" cần ghi nhận. Nhờ patch grammar, trường hợp
     * này giờ parse THÀNH CÔNG (không có ErrorNode, isUnreliable() vẫn false) nên không cần (và
     * không được) gate bằng isUnreliable() ở đây - làm vậy sẽ tự triệt tiêu chính tính năng đang
     * cần phát hiện.
     */
    private void checkDanglingDot(
            PostgreSQLParser.ColidContext colidCtx,
            PostgreSQLParser.IndirectionContext indirection
    ) {
        if (indirection == null || indirection.indirection_el().isEmpty()) {
            return;
        }
        List<PostgreSQLParser.Indirection_elContext> els = indirection.indirection_el();
        PostgreSQLParser.Indirection_elContext last = els.get(els.size() - 1);
        TerminalNode dot = last.DOT();
        if (dot == null || last.attr_name() != null || last.STAR() != null) {
            return; // không phải "[...]" cuối, hoặc dấu chấm cuối ĐÃ có tên theo sau -> không cụt
        }
        // qualifier = đoạn TRỰC TIẾP đứng trước dấu chấm cụt (chỉ 1 segment, không nối chuỗi cả
        // đường dẫn) - vd "a.b." -> "b", "u." -> "u".
        String qualifier;
        if (els.size() >= 2) {
            PostgreSQLParser.Indirection_elContext prev = els.get(els.size() - 2);
            qualifier = prev.attr_name() != null ? prev.attr_name().getText()
                    : prev.STAR() != null ? "*" : null;
        } else {
            qualifier = colidCtx != null ? colidCtx.getText() : null;
        }
        if (qualifier != null) {
            danglingDotQualifier.put(dot.getSymbol().getStopIndex() + 1, qualifier);
        }
    }

    // ---- API resolve tại cursor - dùng được ở MỌI vị trí, không riêng sau dấu chấm ----

    /**
     * Kết quả resolve tại 1 vị trí cursor bất kỳ.
     * <p>
     * visibleAliases: LUÔN có giá trị hữu ích ở MỌI vị trí (kể cả cursor đứng ở chỗ hoàn toàn
     * trống) - alias -> tên bảng thật HOẶC "<cte#N>"/ "<subquery#N>" (dạng hiển thị, dùng để
     * debug/log). visibleDerivedScopes: alias -> Scope thật, CHỈ chứa các alias trỏ tới subquery/
     * CTE (không có entry cho alias trỏ tới bảng thật). Dùng khi KHÔNG có danglingQualifier (cursor
     * không đứng sau dấu chấm) - với MỖI alias trong visibleAliases, nếu nó CÓ mặt ở đây thì lấy
     * cột qua .projectedColumns/.hasWildcard; nếu KHÔNG có thì đó là bảng thật, tự tra schema
     * (SemanticScope không biết schema). danglingQualifier: chỉ khác null nếu cursor đứng NGAY SAU
     * 1 dấu chấm cụt (vd "u.") - là phần đứng trước dấu chấm đó (vd "u").
     * danglingQualifierResolvesTo: dạng hiển thị (tên bảng thật hoặc "<cte#N>"/ "<subquery#N>") của
     * alias đó - null nếu alias lạ/gõ sai. danglingQualifierScope: Scope THẬT nếu qualifier trỏ tới
     * subquery/CTE (dùng .projectedColumns/.hasWildcard trực tiếp) - null nếu qualifier trỏ tới
     * bảng thật (tự tra schema) hoặc alias lạ.
     */
    public record CompletionResult(
            Map<String, String> visibleAliases,
            Map<String, Scope> visibleDerivedScopes,
            String danglingQualifier,
            String danglingQualifierResolvesTo,
            Scope danglingQualifierScope
    ) {

    }

    public CompletionResult resolveAt(int cursorOffset, Scope scopeAtCursor) {
        Map<String, String> aliases = scopeAtCursor.visibleAliases();
        Map<String, Scope> derivedScopes = scopeAtCursor.visibleDerivedScopes();
        String qualifier = danglingDotQualifier.get(cursorOffset);
        String resolvesTo = qualifier == null ? null : aliases.get(qualifier);
        Scope qualifierScope = qualifier == null ? null : derivedScopes.get(qualifier);
        return new CompletionResult(aliases, derivedScopes, qualifier, resolvesTo, qualifierScope);
    }

    private static String lastPart(String q) {
        int i = q.lastIndexOf('.');
        return i < 0 ? q : q.substring(i + 1);
    }

    // ---- Hỗ trợ vị trí cursor "trống hoàn toàn" (không đứng sau dấu chấm) ----

    /**
     * Text placeholder chèn vào SQL - chọn 1 chuỗi gần như không thể trùng input thật.
     */
    public static final String CURSOR_PLACEHOLDER = "zzzcursorzzz";

    /**
     * Chèn 1 identifier giả (CURSOR_PLACEHOLDER) NGAY TẠI cursorOffset, TRỪ KHI ký tự ngay trước
     * cursor là dấu chấm - trường hợp đó để nguyên, vì patch DOT?? trong indirection_el đã xử lý
     * đúng rồi (chèn thêm vào sẽ PHÁ cơ chế phát hiện "chấm cụt").
     * <p>
     * Lý do cần: những vị trí HOÀN TOÀN TRỐNG (vd "SELECT |FROM t", "WHERE |") không có gì để
     * grammar "bám" vào - ANTLR error-recovery (single-token deletion) có thể đoán sai, xóa nhầm 1
     * từ khóa CẤU TRÚC thật (vd FROM) thay vì hiểu đây là "người dùng đang gõ dở". Chèn 1
     * identifier giả biến vị trí đó thành hợp lệ về cú pháp, loại bỏ hẳn tình huống gây đoán sai.
     */
    public static String withCursorPlaceholder(String sql, int cursorOffset) {
        boolean rightAfterDot = cursorOffset > 0 && sql.charAt(cursorOffset - 1) == '.';
        if (rightAfterDot) {
            return sql; // DOT?? tự lo, không đụng vào
        }
        return sql.substring(0, cursorOffset) + CURSOR_PLACEHOLDER + sql.substring(cursorOffset);
    }
}