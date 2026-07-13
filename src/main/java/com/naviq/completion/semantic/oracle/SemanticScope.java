package com.naviq.completion.semantic.oracle;

import com.naviq.antlr4.oracle.PlSqlParser;
import com.naviq.antlr4.oracle.PlSqlParserBaseListener;
import com.naviq.completion.semantic.Scope;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.*;

/**
 * Bản Oracle PL/SQL của SemanticScope - CÙNG KIẾN TRÚC với bản Postgres
 * (com.naviq.completion.semantic.SemanticScope: cây Scope, alias/derivedScope, isUnreliable,
 * scopeAt, resolveAt...) nhưng mọi handler được ánh xạ lại theo ĐÚNG rule của PlSqlParser.g4 -
 * KHÔNG phải chép nguyên type PostgreSQLParser như bản trước đó (sẽ không compile được, vì
 * PlSqlParserBaseListener không có override target nào khớp kiểu PostgreSQLParser.XXXContext).
 * <p>
 * Khác biệt cấu trúc quan trọng nhất so với bản Postgres (đọc kỹ trước khi sửa thêm):
 * <p>
 * 1) KHÔNG CÓ patch grammar cho "dấu chấm cụt" - PlSqlParser.g4/PlSqlLexer.g4 ở đây là bản GỐC,
 * chưa patch (general_element_part đòi hỏi id_expression NGAY SAU PERIOD, không optional) - "u."
 * với cursor sau dấu chấm LUÔN là lỗi cú pháp thật (ANTLR phải error-recovery). Nên
 * checkDanglingDot() kiểu Postgres (dựa vào cây parse) KHÔNG dùng được ở đây - đã thay bằng
 * {@link DanglingDotDetector}, phát hiện thuần túy qua TOKEN STREAM,
 * độc lập với việc parse. Orchestrator (vd OracleSemanticAnalyzer, CHƯA có trong lượt sửa này -
 * xem ghi chú cuối file) phải tự gọi DanglingDotDetector.detect(...) rồi set kết quả vào
 * danglingDotQualifier qua {@link #recordDanglingDot}, KHÔNG phải qua 1 listener callback nào ở
 * đây.
 * <p>
 * 2) WITH clause (subquery_factoring_clause) nằm LỒNG BÊN TRONG query_block
 * ("subquery_factoring_clause? SELECT ..."), KHÔNG PHẢI 1 rule riêng bọc ngoài select như
 * with_clause của Postgres - nên KHÔNG cần cơ chế withHost/pendingCte tách biệt: CTE và câu SELECT
 * dùng nó nằm CHUNG 1 scope (chính scope của query_block đó), đơn giản hơn hẳn.
 * <p>
 * 3) table_ref_aux_internal dùng LABELED ALTERNATIVES (# table_ref_aux_internal_one/two/three) ->
 * ANTLR sinh 3 class Context RIÊNG (không phải 1 class chung như Postgres) - phải dùng
 * instanceof để phân biệt.
 * <p>
 * 4) dml_table_expression_clause GỘP CHUNG "bảng thật" VÀ "subquery trong ngoặc" vào 1 rule DUY
 * NHẤT (khác Postgres tách riêng relation_expr/select_with_parens ở table_ref) - phải tự phân biệt
 * bằng accessor nào non-null (tableview_name() vs select_statement()).
 */
public class SemanticScope extends PlSqlParserBaseListener {

    /**
     * offset ngay sau dấu chấm cụt -> alias đứng trước nó, vd "u." -> {41: "u"} - KHÔNG được điền
     * bởi 1 listener callback ở class này (khác Postgres) - phải gọi {@link #recordDanglingDot}
     * từ bên ngoài (xem javadoc đầu file, mục 1).
     */
    public final TreeMap<Integer, String> danglingDotQualifier = new TreeMap<>();

    /**
     * Orchestrator gọi hàm này SAU khi đã tự chạy
     * {@code com.naviq.completion.semantic.oracle.DanglingDotDetector.detect(...)} (không làm trong lớp
     * này vì cần token stream + caretTokenIndex, những thứ SemanticScope - 1 ParseTreeListener
     * thuần túy - không cầm trực tiếp).
     */
    public void recordDanglingDot(int cursorOffset, String qualifier) {
        if (qualifier != null) {
            danglingDotQualifier.put(cursorOffset, qualifier);
        }
    }

    private int nextId = 0;
    private final Scope root = new Scope(nextId++, null);
    private final Deque<Scope> stack = new ArrayDeque<>(List.of(root));
    private final List<Scope> allScopes = new ArrayList<>(List.of(root));

    public Scope root() {
        return root;
    }

    /**
     * Xem javadoc field cùng tên ở SemanticScope (Postgres) - GIỐNG HỆT, dialect-agnostic hoàn
     * toàn (chỉ thao tác trên ParserRuleContext/ParseTree generic của ANTLR, không đụng gì tới
     * rule cụ thể của Postgres hay Oracle).
     */
    public final Set<Integer> offendingTokenIndices = new HashSet<>();

    public boolean debug = false;

    public boolean isUnreliable(org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (offendingTokenIndices.isEmpty()) {
            if (debug) {
                System.out.println("  [debug isUnreliable] text=\"" + ctx.getText() + "\""
                        + " (short-circuit: offendingTokenIndices rỗng -> chắc chắn reliable)");
            }
            return false;
        }
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

    public Scope scopeAt(int tokenIndex) {
        Scope best = null;
        for (Scope s : allScopes) {
            if (s.startTokenIndex < 0) {
                continue;
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

    // ---- SELECT - query_block là tương đương của select_no_parens bên Postgres: 1 khối
    //      SELECT...FROM...WHERE... hoàn chỉnh, kể cả subquery_factoring_clause (WITH) LỒNG BÊN
    //      TRONG nó (khác Postgres tách with_clause ra riêng) ----

    @Override
    public void enterQuery_block(PlSqlParser.Query_blockContext ctx) {
        pushScope(ctx.getStart());
    }

    @Override
    public void exitQuery_block(PlSqlParser.Query_blockContext ctx) {
        popScope(ctx.getStop());
    }

    // ---- UPDATE / DELETE - "UPDATE general_table_ref ..." / "DELETE FROM? general_table_ref ..."
    //      - PHẢI push scope riêng (giống Postgres) để WHERE/subquery bên trong thấy được alias
    //      của chính statement đó. ----

    @Override
    public void enterUpdate_statement(PlSqlParser.Update_statementContext ctx) {
        Scope child = pushScope(ctx.getStart());
        registerGeneralTableRef(child, ctx.general_table_ref());
    }

    @Override
    public void exitUpdate_statement(PlSqlParser.Update_statementContext ctx) {
        popScope(ctx.getStop());
    }

    @Override
    public void enterDelete_statement(PlSqlParser.Delete_statementContext ctx) {
        Scope child = pushScope(ctx.getStart());
        registerGeneralTableRef(child, ctx.general_table_ref());
    }

    @Override
    public void exitDelete_statement(PlSqlParser.Delete_statementContext ctx) {
        popScope(ctx.getStop());
    }

    /**
     * general_table_ref : (dml_table_expression_clause | ONLY LEFT_PAREN dml_table_expression_clause
     * RIGHT_PAREN) table_alias? ; - KHÔNG dùng labeled alternative nên chỉ 1 class Context, accessor
     * dml_table_expression_clause() dùng được bất kể alternative nào khớp.
     */
    private void registerGeneralTableRef(Scope target, PlSqlParser.General_table_refContext refCtx) {
        if (target == null || refCtx == null) {
            return;
        }
        var dmlCtx = refCtx.dml_table_expression_clause();
        if (dmlCtx == null || dmlCtx.tableview_name() == null) {
            return; // subquery/table_collection_expression/json_table_clause - bỏ qua có chủ đích
        }
        if (isUnreliable(refCtx)) {
            return;
        }
        String table = tableNameOf(dmlCtx.tableview_name());
        if (table == null) {
            return;
        }
        var aliasCtx = refCtx.table_alias();
        String alias = aliasCtx != null && !aliasCtx.getText().trim().isEmpty()
                ? aliasCtx.getText().trim()
                : lastPart(table);
        registerTableOrCte(target, alias, table);
    }

    // ---- INSERT - "INSERT (single_table_insert | multi_table_insert)" - chỉ xử lý
    //      single_table_insert (insert_into_clause -> general_table_ref); multi_table_insert
    //      (INSERT ALL/FIRST WHEN...) có NHIỀU insert_into_clause khác nhau, phức tạp hơn hẳn -
    //      bỏ qua có chủ đích (hiếm cần completion cột ở dạng multi-table insert). ----

    @Override
    public void enterInsert_statement(PlSqlParser.Insert_statementContext ctx) {
        Scope child = pushScope(ctx.getStart());
        child.isDdlTargetScope = true;
        var single = ctx.single_table_insert();
        if (single != null && single.insert_into_clause() != null) {
            registerGeneralTableRef(child, single.insert_into_clause().general_table_ref());
        }
    }

    @Override
    public void exitInsert_statement(PlSqlParser.Insert_statementContext ctx) {
        popScope(ctx.getStop());
    }

    // ---- MERGE - "MERGE INTO selected_tableview USING selected_tableview ON (...) ..." - CÓ 2
    //      bảng (target + USING), mỗi bảng optional alias riêng - selected_tableview() trả về LIST
    //      2 phần tử (rule xuất hiện 2 lần trong cùng 1 alternative, giống Postgres's mergestmt). ----

    @Override
    public void enterMerge_statement(PlSqlParser.Merge_statementContext ctx) {
        Scope child = pushScope(ctx.getStart());
        child.isDdlTargetScope = true;
        var tableviews = ctx.selected_tableview();
        if (!tableviews.isEmpty()) {
            registerSelectedTableview(child, tableviews.get(0));
        }
        // vế USING có thể là subquery ("(select ...) alias") thay vì tableview_name - chỉ xử lý
        // case bảng thường, bỏ qua có chủ đích nếu là subquery (giống Postgres mergestmt).
        if (tableviews.size() > 1) {
            registerSelectedTableview(child, tableviews.get(1));
        }
    }

    @Override
    public void exitMerge_statement(PlSqlParser.Merge_statementContext ctx) {
        popScope(ctx.getStop());
    }

    private void registerSelectedTableview(Scope target, PlSqlParser.Selected_tableviewContext stCtx) {
        // selected_tableview : (tableview_name | LEFT_PAREN select_statement RIGHT_PAREN) table_alias? ;
        if (stCtx == null || stCtx.tableview_name() == null || isUnreliable(stCtx)) {
            return;
        }
        String table = tableNameOf(stCtx.tableview_name());
        if (table == null) {
            return;
        }
        var aliasCtx = stCtx.table_alias();
        String alias = aliasCtx != null && !aliasCtx.getText().trim().isEmpty()
                ? aliasCtx.getText().trim()
                : lastPart(table);
        registerTableOrCte(target, alias, table);
    }

    // ---- ALTER TABLE - "ALTER TABLE tableview_name ..." - CÙNG lý do với INSERT: cần bảng target
    //      trong scope để column_clauses (ADD/MODIFY/DROP COLUMN...) tra được cột. ----

    @Override
    public void enterAlter_table(PlSqlParser.Alter_tableContext ctx) {
        Scope child = pushScope(ctx.getStart());
        child.isDdlTargetScope = true;
        registerTableviewNameTarget(child, ctx.tableview_name());
    }

    @Override
    public void exitAlter_table(PlSqlParser.Alter_tableContext ctx) {
        popScope(ctx.getStop());
    }

    // ---- CREATE INDEX - bảng nằm 1 CẤP SÂU HƠN (create_index -> table_index_clause ->
    //      tableview_name), KHÁC ALTER TABLE (nơi tableview_name là con TRỰC TIẾP) - phải push
    //      scope ở enterCreate_index rồi đăng ký ở exitTable_index_clause (đợi tableview_name +
    //      table_alias - 2 con của table_index_clause - parse xong). Bỏ qua có chủ đích
    //      cluster_index_clause (không có tableview_name, dùng cluster_name) và
    //      bitmap_join_index_clause (nhiều tableview_name, hiếm cần completion cột ở đó). ----

    @Override
    public void enterCreate_index(PlSqlParser.Create_indexContext ctx) {
        Scope child = pushScope(ctx.getStart());
        child.isDdlTargetScope = true;
    }

    @Override
    public void exitCreate_index(PlSqlParser.Create_indexContext ctx) {
        popScope(ctx.getStop());
    }

    @Override
    public void exitTable_index_clause(PlSqlParser.Table_index_clauseContext ctx) {
        // table_index_clause : tableview_name table_alias? LEFT_PAREN index_expr_option (...)*
        //                      RIGHT_PAREN index_properties? ;
        Scope cur = stack.peek();
        if (cur == null || ctx.tableview_name() == null || isUnreliable(ctx)) {
            return;
        }
        String table = tableNameOf(ctx.tableview_name());
        if (table == null) {
            return;
        }
        var aliasCtx = ctx.table_alias();
        String alias = aliasCtx != null && !aliasCtx.getText().trim().isEmpty()
                ? aliasCtx.getText().trim()
                : lastPart(table);
        registerTableOrCte(cur, alias, table);
    }

    private void registerTableviewNameTarget(Scope target, PlSqlParser.Tableview_nameContext tvCtx) {
        if (target == null || tvCtx == null || isUnreliable(tvCtx)) {
            return;
        }
        String table = tableNameOf(tvCtx);
        if (table == null) {
            return;
        }
        registerTableOrCte(target, lastPart(table), table);
    }

    // ---- SELECT list projection - dùng để biết subquery/CTE "trả ra cột gì". Grammar Oracle
    //      KHÁC hẳn Postgres ở đây: "*" trần là 1 ALTERNATIVE LITERAL của selected_list (không
    //      phải 1 target_el riêng như Postgres's target_star), còn "alias.*" là table_wild (1
    //      alternative của select_list_elements). ----
    // selected_list : '*' | select_list_elements (COMMA select_list_elements)* ;
    // select_list_elements : table_wild | expression column_alias? ;
    // table_wild : tableview_name PERIOD ASTERISK ;

    @Override
    public void exitSelected_list(PlSqlParser.Selected_listContext ctx) {
        Scope cur = stack.peek();
        if (cur == null || isUnreliable(ctx)) {
            return;
        }
        if (ctx.getChildCount() > 0 && "*".equals(ctx.getChild(0).getText())) {
            cur.hasWildcard = true; // "SELECT *" trần
        }
    }

    @Override
    public void exitSelect_list_elements(PlSqlParser.Select_list_elementsContext ctx) {
        Scope cur = stack.peek();
        if (cur == null || isUnreliable(ctx)) {
            return;
        }
        if (ctx.table_wild() != null) {
            cur.hasWildcard = true; // "alias.*"
            return;
        }
        if (ctx.expression() == null) {
            return;
        }
        String outName;
        var aliasCtx = ctx.column_alias();
        if (aliasCtx != null) {
            // column_alias : AS? (identifier | quoted_string) | AS ;
            outName = aliasCtx.identifier() != null ? aliasCtx.identifier().getText() : null;
            // nhánh quoted_string hoặc "AS" trần không có gì theo sau -> không suy ra được tên
            // hiển thị đáng tin cậy, bỏ qua (không đoán bừa).
        } else {
            // không có alias -> chỉ suy ra tên nếu expression là 1 identifier/qualified-name đơn
            // giản (vd "t.col" -> "col"); biểu thức phức tạp (hàm, phép toán...) thì bỏ qua.
            String text = ctx.expression().getText();
            outName = text.matches("[a-zA-Z_][a-zA-Z0-9_$#.]*") ? lastPart(text) : null;
        }
        if (outName != null) {
            cur.projectedColumns.add(outName);
        }
    }

    // ---- FROM/JOIN alias - table_ref_aux_internal dùng LABELED ALTERNATIVES nên ANTLR sinh 3
    //      class Context riêng (_one/_two/_three), không phải 1 class chung như Postgres's
    //      table_ref. dml_table_expression_clause GỘP CHUNG "bảng thật" và "subquery trong ngoặc"
    //      vào 1 rule (khác Postgres tách relation_expr/select_with_parens riêng ở table_ref). ----
    // table_ref_aux : table_ref_aux_internal flashback_query_clause* ({p.isTableAlias()}? table_alias)? ;
    // table_ref_aux_internal
    //     : dml_table_expression_clause (pivot_clause|unpivot_clause)?          # table_ref_aux_internal_one
    //     | LEFT_PAREN table_ref subquery_operation_part* RIGHT_PAREN (...)?   # table_ref_aux_internal_two
    //     | ONLY LEFT_PAREN dml_table_expression_clause RIGHT_PAREN            # table_ref_aux_internal_three
    //     ;

    @Override
    public void exitTable_ref_aux(PlSqlParser.Table_ref_auxContext ctx) {
        Scope cur = stack.peek();
        if (cur == null || isUnreliable(ctx)) {
            return;
        }
        var internal = ctx.table_ref_aux_internal();
        PlSqlParser.Dml_table_expression_clauseContext dmlCtx = null;
        if (internal instanceof PlSqlParser.Table_ref_aux_internal_oneContext one) {
            dmlCtx = one.dml_table_expression_clause();
        } else if (internal instanceof PlSqlParser.Table_ref_aux_internal_threeContext three) {
            dmlCtx = three.dml_table_expression_clause();
        }
        // table_ref_aux_internal_two ("(" table_ref ... ")") - table_ref lồng trong ngoặc, hiếm
        // gặp trong ngữ cảnh completion (thường chỉ xuất hiện khi viết join phức tạp có ngoặc bao
        // ngoài) - bỏ qua có chủ đích, giống cách Postgres bỏ qua "(" table_ref ")".
        if (dmlCtx == null) {
            return;
        }
        registerDmlTableExpression(cur, dmlCtx, ctx.table_alias());
    }

    private void registerDmlTableExpression(
            Scope cur,
            PlSqlParser.Dml_table_expression_clauseContext dmlCtx,
            PlSqlParser.Table_aliasContext aliasCtx
    ) {
        if (dmlCtx.tableview_name() != null) {
            // bảng thật
            String table = tableNameOf(dmlCtx.tableview_name());
            if (table == null) {
                return;
            }
            String alias = aliasCtx != null && !aliasCtx.getText().trim().isEmpty()
                    ? aliasCtx.getText().trim()
                    : lastPart(table);
            registerTableOrCte(cur, alias, table);
        } else if (dmlCtx.select_statement() != null) {
            // "(select ...) alias" - subquery trong ngoặc, chỉ tham chiếu được nếu CÓ alias
            if (aliasCtx == null || cur.children.isEmpty()) {
                return;
            }
            Scope inner = cur.children.get(cur.children.size() - 1);
            String alias = aliasCtx.getText();
            cur.aliases.put(alias, "<subquery#" + inner.id + ">");
            cur.derivedScopeAliases.put(alias, inner);
        }
        // table_collection_expression / json_table_clause - bỏ qua có chủ đích (hiếm gặp).
    }

    /**
     * tableview_name : identifier (PERIOD id_expression)? (AT_SIGN link_name (...)* |
     * partition_extension_clause)? | xmltable outer_join_sign? ; - chỉ lấy "identifier[.id_expression]",
     * CỐ TÌNH bỏ qua phần dblink/partition_extension_clause (không liên quan tới việc resolve tên
     * bảng cho completion cột), và bỏ qua hẳn nhánh xmltable (không có identifier() - trả null).
     */
    private static String tableNameOf(PlSqlParser.Tableview_nameContext tvCtx) {
        if (tvCtx == null || tvCtx.identifier() == null) {
            return null;
        }
        String base = tvCtx.identifier().getText();
        return tvCtx.id_expression() != null ? base + "." + tvCtx.id_expression().getText() : base;
    }

    private void registerTableOrCte(Scope target, String alias, String table) {
        Scope cteScope = resolveAsExistingCte(table);
        if (cteScope != null) {
            target.aliases.put(alias, "<cte#" + cteScope.id + ">");
            target.derivedScopeAliases.put(alias, cteScope);
        } else {
            target.aliases.put(alias, table);
        }
    }

    // ---- CTE (subquery_factoring_clause LỒNG TRONG query_block) ----
    // factoring_element : query_name paren_column_list? AS LEFT_PAREN subquery order_by_clause?
    //                     RIGHT_PAREN search_clause? cycle_clause? ;
    // KHÔNG cần withHost/pendingCte như Postgres (xem javadoc mục 2 đầu file) - CTE và câu SELECT
    // dùng nó nằm CHUNG 1 scope (chính query_block chứa subquery_factoring_clause đó), nên chỉ
    // cần đăng ký thẳng lên stack.peek().

    @Override
    public void exitFactoring_element(PlSqlParser.Factoring_elementContext ctx) {
        if (ctx.query_name() == null || isUnreliable(ctx)) {
            return;
        }
        Scope cur = stack.peek();
        if (cur == null || cur.children.isEmpty()) {
            return;
        }
        Scope inner = cur.children.get(cur.children.size() - 1);
        String name = ctx.query_name().getText();
        cur.aliases.put(name, "<cte#" + inner.id + ">");
        cur.derivedScopeAliases.put(name, inner);
    }

    /**
     * Tìm "table" trong danh sách CTE đã đăng ký (derivedScopeAliases) tính từ scope hiện tại leo
     * lên tổ tiên - vì WITH nằm CHUNG scope với chỗ dùng nó (khác Postgres cần merge từ pendingCte
     * riêng), chỉ cần 1 vòng leo cây duy nhất, không cần bước kiểm tra pendingCte nào khác.
     */
    private Scope resolveAsExistingCte(String table) {
        for (Scope s = stack.peek(); s != null; s = s.parent) {
            Scope target = s.derivedScopeAliases.get(table);
            if (target != null) {
                return target;
            }
        }
        return null;
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
            Scope s = stack.pop();
            int stopIdx = stopToken != null ? stopToken.getTokenIndex() : -1;
            // Xem javadoc chi tiết ở popScope() của SemanticScope (Postgres) - CÙNG BUG FIX,
            // CÙNG LÝ DO: token đóng bị ANTLR chèn ảo (tokenIndex == -1) khi người dùng gõ dở
            // (chưa gõ hết statement) không được dùng thẳng làm stopTokenIndex.
            s.stopTokenIndex = (stopIdx >= s.startTokenIndex) ? stopIdx : Integer.MAX_VALUE;
        }
    }

    private static String lastPart(String q) {
        int i = q.lastIndexOf('.');
        return i < 0 ? q : q.substring(i + 1);
    }

    @Override
    public void visitErrorNode(ErrorNode node) {
        Token symbol = (Token) node.getSymbol();
        if (symbol != null) {
            offendingTokenIndices.add(symbol.getTokenIndex());
        }
        // ParseTreeWalker mặc định sẽ tiếp tục, không throw.
    }

    // ---- API resolve tại cursor - GIỐNG HỆT shape của SemanticScope (Postgres), dùng chung được
    //      cho CompletionEngine (nếu tách interface như đã bàn ở lượt "SqlDialectAdapter" trước) ----

    public record CompletionResult(
            Map<String, String> visibleAliases,
            Map<String, Scope> visibleDerivedScopes,
            String danglingQualifier,
            String danglingQualifierResolvesTo,
            Scope danglingQualifierScope
    ) {
    }

    public CompletionResult resolveAt(int cursorOffset, Scope scopeAtCursor) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, Scope> derivedScopes = new LinkedHashMap<>();
        for (Scope s : scopeAtCursor.visibilityChain()) {
            aliases.putAll(s.aliases);
            derivedScopes.putAll(s.derivedScopeAliases);
        }
        String qualifier = danglingDotQualifier.get(cursorOffset);
        String resolvesTo = qualifier == null ? null : aliases.get(qualifier);
        Scope qualifierScope = qualifier == null ? null : derivedScopes.get(qualifier);
        return new CompletionResult(aliases, derivedScopes, qualifier, resolvesTo, qualifierScope);
    }
}

/*
 * CÒN THIẾU để chạy được end-to-end (chưa làm trong lượt sửa này, phạm vi lượt này chỉ là sửa
 * đúng SemanticScope):
 *
 * 1) OracleCursorTokenPatcher - lớp mỏng gọi TokenStreamCursorPatcher.patch(sql, cursorOffset,
 *    PlSqlLexer::new, new TokenStreamCursorPatcher.DialectTokenTypes(PlSqlLexer.REGULAR_ID,
 *    PlSqlLexer.LEFT_PAREN, PlSqlLexer.RIGHT_PAREN, PlSqlLexer.PERIOD)) - giống hệt mẫu
 *    CursorTokenPatcher (Postgres) đã viết trước đó, chỉ đổi 4 hằng số.
 *
 * 2) OracleSemanticAnalyzer - tương đương SemanticAnalyzer (Postgres): patch token, tạo
 *    PlSqlParser, gắn ErrorListener, walk cây bằng SemanticScope này, rồi PHẢI tự gọi thêm
 *    (Postgres KHÔNG cần bước này vì dựa vào patch grammar):
 *
 *        String qualifier = DanglingDotDetector.detect(
 *                patch.tokenStream(), patch.caretTokenIndex(),
 *                PlSqlLexer.PERIOD, Set.of(PlSqlLexer.REGULAR_ID, PlSqlLexer.DELIMITED_ID));
 *        model.recordDanglingDot(cursorOffset, qualifier);
 *
 *    trước khi gọi model.resolveAt(...).
 */