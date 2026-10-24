package com.sqlctx.completion.semantic.postgresql;

import com.sqlctx.antlr4.postgresql.PostgreSQLParser;
import com.sqlctx.antlr4.postgresql.PostgreSQLParserBaseListener;
import com.sqlctx.completion.model.Scope;
import com.sqlctx.completion.semantic.ScopeTree;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.*;

import static com.sqlctx.completion.semantic.ScopeTree.lastPart;

/**
 * Dựng {@link ScopeTree} cho PostgreSQL. Grammar đã patch "indirection_el: DOT (attr_name | STAR)??" nên
 * "u." parse thành công - dấu chấm cụt được phát hiện ngay trên cây ({@link #checkDanglingDot}).
 */
public class PostgresScopeBuilder extends PostgreSQLParserBaseListener {

    private final ScopeTree scopes;
    /** Token caret trong stream đã patch: target chứa nó là cột đang gõ dở, không phải cột chiếu ra. */
    private final int caretTokenIndex;

    public PostgresScopeBuilder(ScopeTree scopes, int caretTokenIndex) {
        this.scopes = scopes;
        this.caretTokenIndex = caretTokenIndex;
    }

    @Override
    public void exitEveryRule(ParserRuleContext ctx) {
        scopes.closeIfOpenedBy(ctx);
    }

    @Override
    public void visitErrorNode(ErrorNode node) {
        scopes.recordErrorNode(node);
    }

    // ---- SELECT ----

    @Override
    public void enterSelect_no_parens(PostgreSQLParser.Select_no_parensContext ctx) {
        scopes.open(ctx).hidesParentFromItems = isNonLateralFromSubquery(ctx);
    }

    /**
     * ORDER BY thuộc select_no_parens, nằm ngoài scope nhánh SELECT (nơi đăng ký FROM), nên caret ở đó không thấy
     * bảng nào. Câu 1 nhánh (không UNION/INTERSECT/EXCEPT) thì ORDER BY tham chiếu được cột của FROM: kéo scope nhánh
     * phủ tới hết ORDER BY. Không tới LIMIT/OFFSET (Postgres cấm cột ở đó). Câu nhiều nhánh giữ nguyên: ORDER BY
     * chỉ nhận tên cột đầu ra.
     */
    @Override
    public void exitSelect_no_parens(PostgreSQLParser.Select_no_parensContext ctx) {
        PostgreSQLParser.Select_clauseContext clause = ctx.select_clause();
        if (clause == null || clause.simple_select_intersect().size() != 1
                || clause.simple_select_intersect(0).simple_select_pramary().size() != 1) {
            return;
        }
        Scope branch = firstBranchOf.get(scopes.current());
        PostgreSQLParser.Opt_sort_clauseContext sort = ctx.opt_sort_clause();
        if (branch == null || sort == null || sort.getStop() == null) {
            return;
        }
        // Token thiếu được ANTLR chèn ảo (tokenIndex -1): coi như mở tới hết input, giống ScopeTree.closeIfOpenedBy.
        int stop = sort.getStop().getTokenIndex();
        branch.stopTokenIndex = stop >= branch.startTokenIndex ? Math.max(branch.stopTokenIndex, stop) : Integer.MAX_VALUE;
    }

    /** "(select ...)" đứng trực tiếp làm table_ref trong FROM, không có LATERAL (bỏ qua các lớp ngoặc thừa). */
    private static boolean isNonLateralFromSubquery(PostgreSQLParser.Select_no_parensContext ctx) {
        ParserRuleContext p = ctx.getParent();
        while (p instanceof PostgreSQLParser.Select_with_parensContext) {
            p = p.getParent();
        }
        return p instanceof PostgreSQLParser.Table_refContext ref && ref.LATERAL_P() == null;
    }

    // Mỗi nhánh UNION/INTERSECT/EXCEPT có scope riêng: FROM của nhánh này không được nhìn thấy ở nhánh
    // kia (Postgres từ chối "select id from users union select users.name from orders").
    @Override
    public void enterSimple_select_pramary(PostgreSQLParser.Simple_select_pramaryContext ctx) {
        if (ctx.SELECT() != null) {
            Scope wrapper = scopes.current();
            firstBranchOf.putIfAbsent(wrapper, scopes.open(ctx));
        }
    }

    /**
     * select_no_parens scope -> scope nhánh SELECT ĐẦU TIÊN bên trong. Cột đầu ra, wildcard, FROM nằm ở
     * scope nhánh; cột đầu ra của cả câu UNION lấy theo nhánh đầu.
     */
    private final Map<Scope, Scope> firstBranchOf = new HashMap<>();

    private Scope columnScopeOf(Scope selectScope) {
        return firstBranchOf.getOrDefault(selectScope, selectScope);
    }

    @Override
    public void exitTarget_star(PostgreSQLParser.Target_starContext ctx) {
        scopes.current().hasWildcard = true;
    }

    @Override
    public void exitTarget_columnref(PostgreSQLParser.Target_columnrefContext ctx) {
        PostgreSQLParser.ColumnrefContext col = ctx.columnref();
        if (col == null || scopes.isUnreliable(ctx) || containsCaret(ctx)) {
            return;
        }
        if (endsWithStar(col.indirection())) {
            scopes.current().hasWildcard = true;
        } else {
            addProjectedColumn(lastPart(col.getText()));
        }
    }

    @Override
    public void exitTarget_label(PostgreSQLParser.Target_labelContext ctx) {
        if (ctx.a_expr() == null || scopes.isUnreliable(ctx) || containsCaret(ctx)) {
            return;
        }
        PostgreSQLParser.Target_aliasContext aliasCtx = ctx.target_alias();
        if (aliasCtx != null) {
            // lấy đúng phần label - getText() của cả target_alias dính luôn "AS"
            ParserRuleContext label = aliasCtx.collabel() != null ? aliasCtx.collabel() : aliasCtx.bare_col_label();
            addProjectedColumn(label != null ? label.getText() : null);
        } else {
            // không alias: chỉ suy ra tên từ định danh trần, không đoán tên biểu thức
            String text = ctx.a_expr().getText();
            addProjectedColumn(text.matches("[a-zA-Z_][a-zA-Z0-9_.]*") ? lastPart(text) : null);
        }
    }

    private boolean containsCaret(ParserRuleContext ctx) {
        return ctx.getStart() != null && ctx.getStop() != null
                && ctx.getStart().getTokenIndex() <= caretTokenIndex && caretTokenIndex <= ctx.getStop().getTokenIndex();
    }

    /** Bỏ trùng: nhiều vế UNION có thể ra cùng tên cột. */
    private void addProjectedColumn(String name) {
        List<String> columns = scopes.current().projectedColumns;
        if (name != null && !columns.contains(name)) {
            columns.add(name);
        }
    }

    private static boolean endsWithStar(PostgreSQLParser.IndirectionContext indirection) {
        if (indirection == null || indirection.indirection_el().isEmpty()) {
            return false;
        }
        List<PostgreSQLParser.Indirection_elContext> els = indirection.indirection_el();
        return els.get(els.size() - 1).STAR() != null;
    }

    // ---- FROM / JOIN ----

    // func_table/xmltable/"(" table_ref ")" chưa hỗ trợ, bỏ qua có chủ đích.
    // Phải ở exit: lúc enter, scope của subquery bên trong chưa được tạo.
    @Override
    public void exitTable_ref(PostgreSQLParser.Table_refContext ctx) {
        // check cả ctx, không chỉ relation_expr: token bị recovery xoá có thể nằm ngoài span của nó
        if (scopes.isUnreliable(ctx)) {
            return;
        }
        PostgreSQLParser.Table_aliasContext aliasCtx = tableAliasOf(ctx);
        if (ctx.relation_expr() != null) {
            registerTable(scopes.current(), ctx.relation_expr().qualified_name(), aliasCtx);
        } else if (ctx.select_with_parens() != null && aliasCtx != null) {
            // subquery chỉ tham chiếu được khi có alias
            Scope cur = scopes.current();
            if (!cur.children.isEmpty()) {
                Scope inner = columnScopeOf(cur.children.get(cur.children.size() - 1));
                cur.aliases.put(aliasCtx.getText(), "<subquery#" + inner.id + ">");
                cur.derivedScopeAliases.put(aliasCtx.getText(), inner);
            }
        }
    }

    private static PostgreSQLParser.Table_aliasContext tableAliasOf(PostgreSQLParser.Table_refContext ctx) {
        PostgreSQLParser.Opt_alias_clauseContext aliasClauseCtx = ctx.opt_alias_clause();
        if (aliasClauseCtx == null || aliasClauseCtx.table_alias_clause() == null) {
            return null;
        }
        return aliasClauseCtx.table_alias_clause().table_alias();
    }

    // ---- DML / DDL có bảng đích: scope riêng để cột trong statement tra được bảng đó ----

    @Override
    public void enterUpdatestmt(PostgreSQLParser.UpdatestmtContext ctx) {
        registerDmlTable(scopes.open(ctx), ctx.relation_expr_opt_alias());
    }

    @Override
    public void enterDeletestmt(PostgreSQLParser.DeletestmtContext ctx) {
        registerDmlTable(scopes.open(ctx), ctx.relation_expr_opt_alias());
    }

    @Override
    public void enterInsertstmt(PostgreSQLParser.InsertstmtContext ctx) {
        Scope scope = openTargetScope(ctx);
        var target = ctx.insert_target();
        if (target != null && !scopes.isUnreliable(target)) {
            registerTable(scope, target.qualified_name(), target.colid());
        }
    }

    // MERGE có 2 bảng (target + USING); USING là subquery thì không có qualified_name thứ 2
    @Override
    public void enterMergestmt(PostgreSQLParser.MergestmtContext ctx) {
        Scope scope = openTargetScope(ctx);
        var names = ctx.qualified_name();
        var aliases = ctx.alias_clause();
        for (int i = 0; i < names.size(); i++) {
            var name = names.get(i);
            var alias = i < aliases.size() ? aliases.get(i).colid() : null;
            if (!scopes.isUnreliable(name)) {
                registerTable(scope, name, alias);
            }
        }
    }

    // chỉ alternative "ALTER TABLE ... relation_expr" có relation_expr(); ALTER INDEX/SEQUENCE bỏ qua
    @Override
    public void enterAltertablestmt(PostgreSQLParser.AltertablestmtContext ctx) {
        openTargetScope(ctx, ctx.relation_expr() != null ? ctx.relation_expr().qualified_name() : null);
    }

    @Override
    public void enterIndexstmt(PostgreSQLParser.IndexstmtContext ctx) {
        openTargetScope(ctx, ctx.relation_expr() != null ? ctx.relation_expr().qualified_name() : null);
    }

    @Override
    public void enterCreatepolicystmt(PostgreSQLParser.CreatepolicystmtContext ctx) {
        openTargetScope(ctx, ctx.qualified_name());
    }

    @Override
    public void enterAlterpolicystmt(PostgreSQLParser.AlterpolicystmtContext ctx) {
        openTargetScope(ctx, ctx.qualified_name());
    }

    // Danh sách cột trong ngoặc sau tên bảng: COPY t (a, |), ANALYZE t (a, |), GRANT UPDATE (a, |) ON t,
    // REFERENCES t (a, |). GRANT/REVOKE: bảng đứng SAU danh sách cột nhưng scope phủ cả statement.

    @Override
    public void enterCopystmt(PostgreSQLParser.CopystmtContext ctx) {
        openTargetScope(ctx, ctx.qualified_name());
    }

    @Override
    public void enterVacuum_relation(PostgreSQLParser.Vacuum_relationContext ctx) {
        openTargetScope(ctx, ctx.qualified_name());
    }

    @Override
    public void enterGrantstmt(PostgreSQLParser.GrantstmtContext ctx) {
        openTargetScope(ctx, firstQualifiedName(ctx.privilege_target()));
    }

    @Override
    public void enterRevokestmt(PostgreSQLParser.RevokestmtContext ctx) {
        openTargetScope(ctx, firstQualifiedName(ctx.privilege_target()));
    }

    // bảng ĐƯỢC THAM CHIẾU (không phải bảng đang tạo) là bảng của danh sách cột phía sau REFERENCES
    @Override
    public void enterColconstraintelem(PostgreSQLParser.ColconstraintelemContext ctx) {
        if (ctx.REFERENCES() != null) {
            openTargetScope(ctx, ctx.qualified_name());
        }
    }

    @Override
    public void enterConstraintelem(PostgreSQLParser.ConstraintelemContext ctx) {
        if (ctx.REFERENCES() != null) {
            openTargetScope(ctx, ctx.qualified_name());
        }
    }

    private Scope openTargetScope(ParserRuleContext ctx) {
        Scope scope = scopes.open(ctx);
        scope.isDdlTargetScope = true;
        return scope;
    }

    /** Scope đích DDL, bảng không có alias (alias = tên bảng). */
    private void openTargetScope(ParserRuleContext ctx, PostgreSQLParser.Qualified_nameContext table) {
        Scope scope = openTargetScope(ctx);
        if (table != null && !scopes.isUnreliable(table)) {
            registerTable(scope, table, null);
        }
    }

    private void registerDmlTable(Scope target, PostgreSQLParser.Relation_expr_opt_aliasContext relCtx) {
        if (relCtx == null || relCtx.relation_expr() == null) {
            return;
        }
        // check riêng bảng và alias, không cả statement: lỗi ở SET/WHERE không liên quan
        PostgreSQLParser.ColidContext aliasCtx = relCtx.colid();
        if (scopes.isUnreliable(relCtx.relation_expr()) || (aliasCtx != null && scopes.isUnreliable(aliasCtx))) {
            return;
        }
        registerTable(target, relCtx.relation_expr().qualified_name(), aliasCtx);
    }

    private static PostgreSQLParser.Qualified_nameContext firstQualifiedName(PostgreSQLParser.Privilege_targetContext ctx) {
        if (ctx == null || ctx.qualified_name_list() == null || ctx.qualified_name_list().qualified_name().isEmpty()) {
            return null;
        }
        return ctx.qualified_name_list().qualified_name().get(0);
    }

    /**
     * @param aliasCtx null hoặc rỗng (CaretToken, vd "from users |") thì alias là tên bảng, để bảng vẫn
     *                 nhìn thấy theo tên
     */
    private void registerTable(Scope target, PostgreSQLParser.Qualified_nameContext nameCtx, ParserRuleContext aliasCtx) {
        if (nameCtx == null) {
            return;
        }
        // bỏ ngoặc kép: SchemaIndex lưu tên không ngoặc kép, và alias mặc định phải sạch
        String table = nameCtx.getText().replace("\"", "");
        String alias = aliasCtx != null ? aliasCtx.getText() : "";
        ScopeTree.registerTable(target, alias.isEmpty() ? lastPart(table) : alias, table, findCte(table));
    }

    // ---- CTE ----

    private final Deque<Scope> withHost = new ArrayDeque<>();
    /** CTE đã đóng của WITH đang mở, chưa merge vào host (CTE sau thấy CTE trước). */
    private final Deque<Map<String, Scope>> pendingCte = new ArrayDeque<>();

    private Scope findCte(String name) {
        Scope pending = pendingCte.isEmpty() ? null : pendingCte.peek().get(name);
        return pending != null ? pending : scopes.findCte(name);
    }

    @Override
    public void enterWith_clause(PostgreSQLParser.With_clauseContext ctx) {
        withHost.push(scopes.current());
        pendingCte.push(new LinkedHashMap<>());
    }

    @Override
    public void exitCommon_table_expr(PostgreSQLParser.Common_table_exprContext ctx) {
        if (pendingCte.isEmpty() || ctx.name() == null || scopes.isUnreliable(ctx)) {
            return;
        }
        Scope host = withHost.peek();
        // CTE UPDATE/DELETE/INSERT/MERGE không mở scope riêng -> children.last() sẽ là scope khác
        if (host.children.isEmpty() || ctx.preparablestmt() == null || ctx.preparablestmt().selectstmt() == null) {
            return;
        }
        // Cô lập ở scope bọc ngoài để MỌI nhánh của thân CTE (UNION, RECURSIVE) không thấy alias câu
        // ngoài; cột thì lấy từ nhánh đầu.
        Scope wrapper = host.children.get(host.children.size() - 1);
        wrapper.isolatedFromParentAliases = true;
        Scope cteScope = columnScopeOf(wrapper);
        applyCteColumnRename(cteScope, ctx.opt_name_list());
        pendingCte.peek().put(ctx.name().getText(), cteScope);
    }

    /**
     * "WITH c(a, b) AS (SELECT id, name ...)": c.| phải ra a/b. Thiếu tên thì cột còn lại giữ tên gốc;
     * bỏ qua khi có wildcard (không biết cột nào ứng với tên nào).
     */
    private static void applyCteColumnRename(Scope cteScope, PostgreSQLParser.Opt_name_listContext nameListCtx) {
        if (nameListCtx == null || nameListCtx.name_list() == null || cteScope.hasWildcard) {
            return;
        }
        List<String> columns = cteScope.projectedColumns;
        List<PostgreSQLParser.NameContext> names = nameListCtx.name_list().name();
        for (int i = 0; i < names.size(); i++) {
            if (i < columns.size()) {
                columns.set(i, names.get(i).getText());
            } else {
                columns.add(names.get(i).getText());
            }
        }
    }

    @Override
    public void exitWith_clause(PostgreSQLParser.With_clauseContext ctx) {
        Scope host = withHost.pop();
        Map<String, Scope> pending = pendingCte.pop();
        // Chỉ vào derivedScopeAliases (để "FROM c" resolve được), KHÔNG vào aliases: tên CTE chưa xuất
        // hiện trong FROM thì không phải nguồn cột.
        pending.forEach(host.derivedScopeAliases::put);
        host.cteNames.addAll(pending.keySet());
    }

    // ---- "gõ dở sau dấu chấm" ----

    @Override
    public void exitQualified_name(PostgreSQLParser.Qualified_nameContext ctx) {
        checkDanglingDot(ctx.colid(), ctx.indirection());
    }

    @Override
    public void exitColumnref(PostgreSQLParser.ColumnrefContext ctx) {
        checkDanglingDot(ctx.colid(), ctx.indirection());
    }

    /**
     * indirection_el cuối là DOT không có tên theo sau. Nhờ grammar patch đây là parse THÀNH CÔNG, nên
     * không (và không được) gate bằng isUnreliable().
     */
    private void checkDanglingDot(PostgreSQLParser.ColidContext colidCtx, PostgreSQLParser.IndirectionContext indirection) {
        if (indirection == null || indirection.indirection_el().isEmpty()) {
            return;
        }
        List<PostgreSQLParser.Indirection_elContext> els = indirection.indirection_el();
        PostgreSQLParser.Indirection_elContext last = els.get(els.size() - 1);
        TerminalNode dot = last.DOT();
        if (dot == null || last.attr_name() != null || last.STAR() != null) {
            return;
        }
        // qualifier = đúng 1 segment ngay trước dấu chấm: "a.b." -> "b"
        String qualifier;
        if (els.size() >= 2) {
            PostgreSQLParser.Indirection_elContext prev = els.get(els.size() - 2);
            qualifier = prev.attr_name() != null ? prev.attr_name().getText()
                    : prev.STAR() != null ? "*" : null;
        } else {
            qualifier = colidCtx != null ? colidCtx.getText() : null;
        }
        scopes.recordDanglingDot(dot.getSymbol().getStopIndex() + 1, qualifier);
    }
}
