package com.sqlctx.completion.semantic.oracle;

import com.sqlctx.antlr4.oracle.PlSqlParser;
import com.sqlctx.antlr4.oracle.PlSqlParserBaseListener;
import com.sqlctx.completion.model.Scope;
import com.sqlctx.completion.semantic.ScopeTree;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;

import static com.sqlctx.completion.semantic.ScopeTree.lastPart;

/**
 * Dựng {@link ScopeTree} cho PL/SQL. Khác bản Postgres:
 * <ul>
 *   <li>Grammar không patch "u." nên dấu chấm cụt là lỗi cú pháp: OracleSemanticAnalyzer phát hiện qua
 *       token stream, không phải ở đây.</li>
 *   <li>WITH nằm trong query_block nên CTE và câu dùng nó chung 1 scope.</li>
 *   <li>Mỗi nhánh UNION/INTERSECT/MINUS là 1 query_block riêng (xem {@link #firstBranchScope}).</li>
 * </ul>
 */
public class OracleScopeBuilder extends PlSqlParserBaseListener {

    private final ScopeTree scopes;

    public OracleScopeBuilder(ScopeTree scopes) {
        this.scopes = scopes;
    }

    @Override
    public void exitEveryRule(ParserRuleContext ctx) {
        scopes.closeIfOpenedBy(ctx);
    }

    @Override
    public void visitErrorNode(ErrorNode node) {
        scopes.recordErrorNode(node);
    }

    @Override
    public void enterQuery_block(PlSqlParser.Query_blockContext ctx) {
        scopes.open(ctx);
    }

    @Override
    public void enterUpdate_statement(PlSqlParser.Update_statementContext ctx) {
        registerGeneralTableRef(scopes.open(ctx), ctx.general_table_ref());
    }

    @Override
    public void enterDelete_statement(PlSqlParser.Delete_statementContext ctx) {
        registerGeneralTableRef(scopes.open(ctx), ctx.general_table_ref());
    }

    // multi_table_insert (INSERT ALL/FIRST) bỏ qua có chủ đích
    @Override
    public void enterInsert_statement(PlSqlParser.Insert_statementContext ctx) {
        Scope scope = openTargetScope(ctx);
        var single = ctx.single_table_insert();
        if (single != null && single.insert_into_clause() != null) {
            registerGeneralTableRef(scope, single.insert_into_clause().general_table_ref());
        }
    }

    // vế USING là subquery thì bỏ qua có chủ đích (registerSelectedTableview chỉ nhận bảng)
    @Override
    public void enterMerge_statement(PlSqlParser.Merge_statementContext ctx) {
        Scope scope = openTargetScope(ctx);
        for (var tableview : ctx.selected_tableview()) {
            registerSelectedTableview(scope, tableview);
        }
    }

    @Override
    public void enterAlter_table(PlSqlParser.Alter_tableContext ctx) {
        Scope scope = openTargetScope(ctx);
        var tvCtx = ctx.tableview_name();
        String table = tableNameOf(tvCtx);
        if (table != null && !scopes.isUnreliable(tvCtx)) {
            registerTable(scope, lastPart(table), table);
        }
    }

    // bảng nằm sâu hơn (create_index -> table_index_clause) nên đăng ký ở exitTable_index_clause
    @Override
    public void enterCreate_index(PlSqlParser.Create_indexContext ctx) {
        openTargetScope(ctx);
    }

    @Override
    public void exitTable_index_clause(PlSqlParser.Table_index_clauseContext ctx) {
        String table = tableNameOf(ctx.tableview_name());
        if (table != null && !scopes.isUnreliable(ctx)) {
            registerTable(scopes.current(), aliasOrTableName(ctx.table_alias(), table), table);
        }
    }

    // "FOR rec IN (SELECT ...) LOOP ... END LOOP": rec chỉ nhìn thấy trong thân loop -> scope riêng cho loop
    @Override
    public void enterLoop_statement(PlSqlParser.Loop_statementContext ctx) {
        scopes.open(ctx);
    }

    @Override
    public void exitCursor_loop_param(PlSqlParser.Cursor_loop_paramContext ctx) {
        if (ctx.record_name() != null && !scopes.isUnreliable(ctx)) {
            registerSubquery(ctx.record_name().getText(), ctx.select_statement());
        }
    }

    @Override
    public void exitSelected_list(PlSqlParser.Selected_listContext ctx) {
        if (!scopes.isUnreliable(ctx) && ctx.getChildCount() > 0 && "*".equals(ctx.getChild(0).getText())) {
            scopes.current().hasWildcard = true;
        }
    }

    @Override
    public void exitSelect_list_elements(PlSqlParser.Select_list_elementsContext ctx) {
        if (scopes.isUnreliable(ctx)) {
            return;
        }
        Scope cur = scopes.current();
        if (ctx.table_wild() != null) {
            cur.hasWildcard = true;
            return;
        }
        if (ctx.expression() == null) {
            return;
        }
        String outName;
        var aliasCtx = ctx.column_alias();
        if (aliasCtx != null) {
            outName = aliasCtx.identifier() != null ? aliasCtx.identifier().getText() : null;
        } else {
            // không alias: chỉ suy ra tên từ định danh trần ("t.col" -> "col"), không đoán tên biểu thức
            String text = ctx.expression().getText();
            outName = text.matches("[a-zA-Z_][a-zA-Z0-9_$#.]*") ? lastPart(text) : null;
        }
        if (outName != null) {
            cur.projectedColumns.add(outName);
        }
    }

    @Override
    public void exitTable_ref_aux(PlSqlParser.Table_ref_auxContext ctx) {
        if (scopes.isUnreliable(ctx)) {
            return;
        }
        var internal = ctx.table_ref_aux_internal();
        PlSqlParser.Dml_table_expression_clauseContext dmlCtx = null;
        if (internal instanceof PlSqlParser.Table_ref_aux_internal_oneContext one) {
            dmlCtx = one.dml_table_expression_clause();
        } else if (internal instanceof PlSqlParser.Table_ref_aux_internal_threeContext three) {
            dmlCtx = three.dml_table_expression_clause();
        }
        if (dmlCtx == null) {
            return;
        }
        String table = tableNameOf(dmlCtx.tableview_name());
        if (table != null) {
            registerTable(scopes.current(), aliasOrTableName(ctx.table_alias(), table), table);
        } else if (ctx.table_alias() != null) {
            // subquery chỉ tham chiếu được khi có alias
            registerSubquery(ctx.table_alias().getText().trim(), dmlCtx.select_statement());
        }
    }

    @Override
    public void exitFactoring_element(PlSqlParser.Factoring_elementContext ctx) {
        if (ctx.query_name() == null || scopes.isUnreliable(ctx)) {
            return;
        }
        Scope cur = scopes.current();
        Scope cteScope = firstBranchScope(cur, ctx.subquery());
        if (cteScope == null) {
            return;
        }
        var columnList = ctx.paren_column_list();
        if (columnList != null) {
            // "WITH c (a, b) AS (...)": tên cột tường minh thay hẳn cột đầu ra của thân CTE
            cteScope = scopes.newScope(cur);
            if (columnList.column_list() != null) {
                for (var colCtx : columnList.column_list().column_name()) {
                    cteScope.projectedColumns.add(colCtx.getText());
                }
            }
        }
        String name = ctx.query_name().getText();
        cur.aliases.put(name, "<cte#" + cteScope.id + ">");
        cur.derivedScopeAliases.put(name, cteScope);
        cur.cteNames.add(name);
    }

    private Scope openTargetScope(ParserRuleContext ctx) {
        Scope scope = scopes.open(ctx);
        scope.isDdlTargetScope = true;
        return scope;
    }

    private void registerGeneralTableRef(Scope target, PlSqlParser.General_table_refContext refCtx) {
        if (refCtx == null || refCtx.dml_table_expression_clause() == null) {
            return;
        }
        String table = tableNameOf(refCtx.dml_table_expression_clause().tableview_name());
        if (table != null && !scopes.isUnreliable(refCtx)) {
            registerTable(target, aliasOrTableName(refCtx.table_alias(), table), table);
        }
    }

    private void registerSelectedTableview(Scope target, PlSqlParser.Selected_tableviewContext stCtx) {
        String table = tableNameOf(stCtx.tableview_name());
        if (table != null && !scopes.isUnreliable(stCtx)) {
            registerTable(target, aliasOrTableName(stCtx.table_alias(), table), table);
        }
    }

    private void registerTable(Scope target, String alias, String table) {
        ScopeTree.registerTable(target, alias, table, scopes.findCte(table));
    }

    private void registerSubquery(String alias, ParserRuleContext subqueryCtx) {
        Scope cur = scopes.current();
        Scope inner = firstBranchScope(cur, subqueryCtx);
        if (inner != null) {
            cur.aliases.put(alias, "<subquery#" + inner.id + ">");
            cur.derivedScopeAliases.put(alias, inner);
        }
    }

    /**
     * Scope mang cột đầu ra của subquery vừa exit: query_block ĐẦU TIÊN trong {@code subqueryCtx}. Mỗi
     * nhánh UNION là 1 scope con riêng, và cột đầu ra của cả câu lấy theo nhánh đầu.
     */
    private static Scope firstBranchScope(Scope cur, ParserRuleContext subqueryCtx) {
        if (subqueryCtx == null || subqueryCtx.getStart() == null) {
            return null;
        }
        int start = subqueryCtx.getStart().getTokenIndex();
        for (Scope child : cur.children) {
            if (child.startTokenIndex >= start) {
                return child;
            }
        }
        return null;
    }

    /** "identifier[.id_expression]", bỏ phần dblink/partition; null nếu không phải tên bảng. */
    private static String tableNameOf(PlSqlParser.Tableview_nameContext tvCtx) {
        if (tvCtx == null || tvCtx.identifier() == null) {
            return null;
        }
        String base = tvCtx.identifier().getText();
        return tvCtx.id_expression() != null ? base + "." + tvCtx.id_expression().getText() : base;
    }

    /** Alias rỗng (CaretToken, vd "from users |") coi như không có, để bảng vẫn nhìn thấy theo tên. */
    private static String aliasOrTableName(PlSqlParser.Table_aliasContext aliasCtx, String table) {
        String alias = aliasCtx != null ? aliasCtx.getText().trim() : "";
        return alias.isEmpty() ? lastPart(table) : alias;
    }
}
