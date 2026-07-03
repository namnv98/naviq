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
 * 1. Grammar patch "qualifiedName ... DOT??" (xem grammar.patch.md) - biến "u." thành hợp lệ về cú
 * pháp, KHÔNG sinh lỗi, KHÔNG kích hoạt ANTLR error-recovery (nguồn gốc bug "cắn nhầm alias" ban
 * đầu). 2. Mọi callback dưới đây defensive - null/thiếu ở đâu thì bỏ qua đúng chỗ đó, KHÔNG throw,
 * KHÔNG làm hỏng phần scope đã build được (mirror SQLQuerySemanticUtils.performPartialResolution
 * của DBeaver). 3. hasErrorChildren() (mirror STMTreeNode.hasErrorChildren()) - phát hiện subtree
 * bị ANTLR error-recovery vá vào (xem PostgreSQLParserBase + STMTreeTermErrorNode) để KHÔNG đăng ký
 * alias/tên dựa trên dữ liệu không đáng tin, thay vì mù quáng tin mọi thứ ANTLR trả về.
 */
public class SemanticScope extends PostgreSQLBaseListener {

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
     * - Lỗi "thiếu token" (vd "u." thiếu identifier sau dấu chấm) -> ANTLR chèn token ảo
     * (tokenIndex == -1) -> LUÔN tạo ErrorNode -> instanceof check đủ. - Lỗi "token thừa" (vd
     * "123abc" - số không hợp lệ ở vị trí identifier) -> ANTLR xóa token thừa bằng
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

    // ---- selectStmt = 1 scope ----

    @Override
    public void enterSelectStmt(PostgreSQLParser.SelectStmtContext ctx) {
        pushScope(ctx.getStart());
    }

    @Override
    public void exitSelectStmt(PostgreSQLParser.SelectStmtContext ctx) {
        popScope(ctx.getStop());
    }

    // ---- UPDATE / DELETE - grammar KHÔNG dùng tableSource/simpleTable cho 2 câu này
    //      (updateStmt: UPDATE tableName (AS? tableAlias)? SET ... ; deleteStmt: DELETE
    //      FROM tableName (AS? tableAlias)? ...) nên phải đăng ký alias trực tiếp, đồng
    //      thời PHẢI push scope riêng (giống selectStmt) để WHERE/subquery bên trong
    //      UPDATE/DELETE thấy được alias của chính statement đó. ----

    @Override
    public void enterUpdateStmt(PostgreSQLParser.UpdateStmtContext ctx) {
        Scope child = pushScope(ctx.getStart());
        registerDmlTableAlias(child, ctx.tableName(), ctx.tableAlias());
    }

    @Override
    public void exitUpdateStmt(PostgreSQLParser.UpdateStmtContext ctx) {
        popScope(ctx.getStop());
    }

    @Override
    public void enterDeleteStmt(PostgreSQLParser.DeleteStmtContext ctx) {
        Scope child = pushScope(ctx.getStart());
        registerDmlTableAlias(child, ctx.tableName(), ctx.tableAlias());
    }

    @Override
    public void exitDeleteStmt(PostgreSQLParser.DeleteStmtContext ctx) {
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
        PostgreSQLParser.TableNameContext tableNameCtx,
        PostgreSQLParser.TableAliasContext aliasCtx
    ) {
        if (target == null || tableNameCtx == null) {
            return;
        }
        // Check trên tableName + tableAlias riêng (không phải toàn bộ updateStmt/deleteStmt,
        // vì lỗi ở SET/WHERE không liên quan không nên chặn đăng ký alias hợp lệ ở đây -
        // khác với enterSimpleTable, "table (AS alias)?" luôn là phần TỪ ĐẦU statement,
        // ít rủi ro sync() xảy ra trước nó hơn hẳn so với vị trí giữa 1 FROM-list dài).
        if (isUnreliable(tableNameCtx)) {
            return;
        }
        if (aliasCtx != null && isUnreliable(aliasCtx)) {
            return;
        }
        String table = tableNameCtx.getText();
        String alias = aliasCtx != null ? aliasCtx.getText() : lastPart(table);
        // resolveAsExistingCte() trả về Scope (không phải String) - phải populate CẢ
        // aliases (dạng hiển thị) LẪN derivedScopeAliases (Scope thật, cho
        // projectedColumns hoạt động đúng với UPDATE/DELETE trên 1 CTE - hiếm nhưng
        // hợp lệ, vd "UPDATE (SELECT ...) ..." không, nhưng "table" trùng tên CTE thì có).
        Scope cteScope = resolveAsExistingCte(table);
        if (cteScope != null) {
            target.aliases.put(alias, "<cte#" + cteScope.id + ">");
            target.derivedScopeAliases.put(alias, cteScope);
        } else {
            target.aliases.put(alias, table);
        }
    }

    // ---- SELECT list projection - dùng để biết subquery/CTE "trả ra cột gì" ----

    @Override
    public void exitSelectElements(PostgreSQLParser.SelectElementsContext ctx) {
        Scope cur = stack.peek();
        if (cur == null) {
            return;
        }
        if (ctx.STAR() != null && ctx.selectElement().isEmpty()) {
            cur.hasWildcard = true; // "SELECT * FROM ..." - bare star, không có element nào khác
        }
    }

    @Override
    public void exitSelectElement(PostgreSQLParser.SelectElementContext ctx) {
        Scope cur = stack.peek();
        if (cur == null || isUnreliable(ctx)) {
            return;
        }

        if (ctx.qualifiedName() != null) {
            cur.hasWildcard = true; // "alias.*"
            return;
        }
        if (ctx.expression() == null) {
            return;
        }

        String outName;
        if (ctx.selectAlias() != null) {
            outName = ctx.selectAlias().getText(); // "expr AS alias" -> luôn dùng alias
        } else {
            // không có alias -> chỉ suy ra được tên nếu expression là 1 columnName
            // đơn giản (vd "t.id" -> "id"); biểu thức phức tạp (hàm, phép toán...)
            // không alias thì bỏ qua, KHÔNG đoán bừa tên cột.
            String text = ctx.expression().getText();
            outName = text.matches("[a-zA-Z_][a-zA-Z0-9_.]*") ? lastPart(text) : null;
        }
        if (outName != null) {
            cur.projectedColumns.add(outName);
        }
    }

    // ---- FROM/JOIN alias ----

    @Override
    public void enterSimpleTable(PostgreSQLParser.SimpleTableContext ctx) {
        Scope cur = stack.peek();
        if (cur == null || ctx.tableName() == null) {
            return; // gõ dở -> bỏ qua, không throw
        }
        // Check trên TOÀN BỘ ctx (không chỉ ctx.tableName()): token bị recovery xóa
        // có thể nằm NGOÀI span riêng của tableName (vd bị xử lý ở sync() cấp cha
        // TRƯỚC KHI tableName() được gọi) - ctx bao trọn từ trước khi recovery xảy ra.
        if (isUnreliable(ctx)) {
            return;
        }
        String table = ctx.tableName().getText();
        String alias = ctx.tableAlias() != null ? ctx.tableAlias().getText() : lastPart(table);
        // "FROM table" có thể thực ra là THAM CHIẾU tới 1 CTE cùng tên đã định nghĩa
        // trước đó trong cùng WITH (CTE sau thấy CTE trước) hoặc CTE của 1 WITH bao
        // ngoài đã "chốt" xong - phải ưu tiên check trước khi coi đây là bảng mới.
        Scope cteScope = resolveAsExistingCte(table);
        if (cteScope != null) {
            cur.aliases.put(alias, "<cte#" + cteScope.id + ">");
            cur.derivedScopeAliases.put(alias,
                cteScope); // lưu thẳng Scope, không cần parse ngược string
        } else {
            cur.aliases.put(alias, table);
        }
    }

    /**
     * Tìm "table" trong danh sách CTE đã ĐÓNG (exitCteDefinition đã chạy) tính tới thời điểm này -
     * gồm CTE trước trong CÙNG WITH (pendingCte, chưa merge vào scope) và CTE của WITH bao ngoài đã
     * merge xong vào scope cha. Trả về CHÍNH Scope của CTE đó nếu khớp, null nếu "table" thực sự là
     * 1 bảng độc lập.
     */
    private Scope resolveAsExistingCte(String table) {
        if (!pendingCte.isEmpty()) {
            Scope s = pendingCte.peek().get(table);
            if (s != null) {
                return s;
            }
        }
        Scope cur = stack.peek();
        for (Scope s = cur == null ? null : cur.parent; s != null; s = s.parent) {
            Scope target = s.derivedScopeAliases.get(table);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    @Override
    public void exitSubqueryTable(PostgreSQLParser.SubqueryTableContext ctx) {
        // Dùng "exit" thay vì "enter": enter<Rule> luôn chạy TRƯỚC khi walker đi vào
        // các con của rule đó (kể cả selectStmt con bên trong dấu ngoặc) - nên tại
        // thời điểm enterSubqueryTable, scope con CHƯA được tạo (enterSelectStmt của
        // nó chưa chạy). Phải đợi tới exitSubqueryTable (chạy SAU khi toàn bộ con,
        // gồm cả exitSelectStmt của subquery, đã chạy xong) để outer.children mới
        // thật sự chứa scope con.
        if (ctx.tableAlias() == null) {
            return;
        }
        if (isUnreliable(ctx)) {
            return; // check toàn bộ ctx, không chỉ tableAlias() riêng lẻ
        }
        Scope outer = stack.peek();
        if (outer == null || outer.children.isEmpty()) {
            return;
        }
        Scope inner = outer.children.get(outer.children.size() - 1);
        String alias = ctx.tableAlias().getText();
        outer.aliases.put(alias, "<subquery#" + inner.id + ">");
        outer.derivedScopeAliases.put(alias, inner); // lưu thẳng Scope
    }

    // ---- CTE (WITH ... AS (...)) ----

    private final Deque<Scope> withHost = new ArrayDeque<>();
    private final Deque<LinkedHashMap<String, Scope>> pendingCte = new ArrayDeque<>();

    @Override
    public void enterWithClause(PostgreSQLParser.WithClauseContext ctx) {
        withHost.push(stack.peek());
        pendingCte.push(new LinkedHashMap<>());
    }

    @Override
    public void exitCteDefinition(PostgreSQLParser.CteDefinitionContext ctx) {
        if (pendingCte.isEmpty() || ctx.cteName() == null) {
            return;
        }
        if (isUnreliable(ctx)) {
            return; // check toàn bộ ctx, không chỉ cteName() riêng lẻ
        }
        Scope host = withHost.peek();
        if (host == null || host.children.isEmpty()) {
            return;
        }
        pendingCte.peek().put(ctx.cteName().getText(), host.children.get(host.children.size() - 1));
    }

    @Override
    public void exitWithClause(PostgreSQLParser.WithClauseContext ctx) {
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

    // ---- qualifiedName kết thúc bằng dấu chấm (nhờ grammar DOT??) ----

    @Override
    public void exitQualifiedName(PostgreSQLParser.QualifiedNameContext ctx) {
        if (ctx.children == null || ctx.children.isEmpty()) {
            return;
        }

        String lastName = null;
        Token trailingDot = null;
        boolean expectName = true;

        for (var child : ctx.children) {
            boolean isDot = child instanceof TerminalNode t
                && t.getSymbol().getType() == PostgreSQLLexer.DOT;
            if (isDot) {
                trailingDot = ((TerminalNode) child).getSymbol();
                expectName = true;
            } else {
                lastName = child.getText();
                trailingDot = null;
                expectName = false;
            }
        }

        if (expectName && trailingDot != null && lastName != null) {
            danglingDotQualifier.put(trailingDot.getStopIndex() + 1, lastName);
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
     * cursor là dấu chấm - trường hợp đó để nguyên, vì DOT?? trong grammar đã xử lý đúng rồi (chèn
     * thêm vào sẽ PHÁ cơ chế phát hiện "chấm cụt").
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