package com.sqlctx.completion.semantic;

import com.sqlctx.completion.model.Scope;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.*;

/**
 * Cây scope mà *ScopeBuilder dựng trong lúc walk, cùng các truy vấn tại caret - phần không phụ thuộc
 * grammar. Builder chỉ quyết định rule nào mở scope và đăng ký tên gì.
 */
public final class ScopeTree {

    private int nextId = 0;
    private final Scope root = new Scope(nextId++, null);
    private final List<Scope> allScopes = new ArrayList<>(List.of(root));
    private final Deque<Scope> open = new ArrayDeque<>(List.of(root));
    /** Context đã mở từng scope trong {@link #open} (root không có) - để tự đóng ở {@link #closeIfOpenedBy}. */
    private final Deque<ParserRuleContext> openedBy = new ArrayDeque<>();
    private final Set<Integer> offendingTokenIndices;
    /** offset ngay sau dấu chấm cụt -> qualifier đứng trước nó, vd "u." -> {41: "u"} */
    private final Map<Integer, String> danglingDotQualifier = new HashMap<>();

    /**
     * @param offendingTokenIndices token bị ErrorListener báo lỗi - nguồn duy nhất bắt được MỌI kiểu lỗi:
     *                              single-token deletion ("extraneous input") không tạo ErrorNode trong cây
     */
    public ScopeTree(Set<Integer> offendingTokenIndices) {
        this.offendingTokenIndices = new HashSet<>(offendingTokenIndices);
    }

    public Scope root() {
        return root;
    }

    public Scope current() {
        return open.peek();
    }

    /** Mở scope con của scope hiện tại; tự đóng khi walker ra khỏi {@code ctx}. */
    public Scope open(ParserRuleContext ctx) {
        Scope child = newScope(current());
        child.startTokenIndex = ctx.getStart() != null ? ctx.getStart().getTokenIndex() : -1;
        current().children.add(child);
        allScopes.add(child);
        open.push(child);
        openedBy.push(ctx);
        return child;
    }

    /** Gọi từ exitEveryRule của builder. */
    public void closeIfOpenedBy(ParserRuleContext ctx) {
        if (openedBy.peek() != ctx) {
            return;
        }
        openedBy.pop();
        Scope s = open.pop();
        int stopIdx = ctx.getStop() != null ? ctx.getStop().getTokenIndex() : -1;
        // Token đóng thiếu (người dùng chưa gõ ")") được ANTLR chèn ảo với tokenIndex -1: coi scope vẫn
        // mở tới hết input, không đóng non (đóng non thì caret bên trong văng ra scope cha).
        s.stopTokenIndex = (stopIdx >= s.startTokenIndex) ? stopIdx : Integer.MAX_VALUE;
    }

    /** Scope không nằm trong cây (không tìm thấy qua scopeAt), vd danh sách cột tường minh của CTE. */
    public Scope newScope(Scope parent) {
        return new Scope(nextId++, parent);
    }

    public void recordErrorNode(ErrorNode node) {
        Token symbol = (Token) node.getSymbol();
        if (symbol != null) {
            offendingTokenIndices.add(symbol.getTokenIndex());
        }
    }

    public void recordDanglingDot(int offset, String qualifier) {
        if (qualifier != null) {
            danglingDotQualifier.put(offset, qualifier);
        }
    }

    /** Subtree có ErrorNode, hoặc span chứa token bị báo lỗi: không đăng ký tên từ đó. */
    public boolean isUnreliable(ParserRuleContext ctx) {
        // Walker duyệt post-order nên mọi ErrorNode trong subtree đã được recordErrorNode() ghi nhận:
        // không có lỗi nào thì chắc chắn không có ErrorNode, khỏi duyệt subtree (ca phổ biến nhất).
        if (offendingTokenIndices.isEmpty()) {
            return false;
        }
        if (hasErrorChildren(ctx)) {
            return true;
        }
        if (ctx.getStart() == null || ctx.getStop() == null) {
            return false;
        }
        int a = ctx.getStart().getTokenIndex();
        int b = ctx.getStop().getTokenIndex();
        for (int idx : offendingTokenIndices) {
            if (idx >= a && idx <= b) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasErrorChildren(ParseTree node) {
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

    /** CTE đã đăng ký tên {@code name}, tìm từ scope hiện tại lên tổ tiên. */
    public Scope findCte(String name) {
        for (Scope s = current(); s != null; s = s.parent) {
            Scope target = s.derivedScopeAliases.get(name);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    /** Gọi sau khi walk xong - xem {@link Scope#dropUnnamedEntries()}. */
    public void dropUnnamedEntries() {
        allScopes.forEach(Scope::dropUnnamedEntries);
    }

    /** Scope hẹp nhất có [start, stop] chứa tokenIndex; không có thì root. */
    public Scope scopeAt(int tokenIndex) {
        Scope best = null;
        for (Scope s : allScopes) {
            if (s.startTokenIndex < 0 || tokenIndex < s.startTokenIndex || tokenIndex > s.stopTokenIndex) {
                continue;
            }
            // Hoà span (scope lồng trùng khít, vd nhánh SELECT trong select_no_parens khi không có WITH):
            // chọn scope mở sau (id lớn hơn) - sâu hơn, là scope mang alias.
            if (best == null || spanOf(s) < spanOf(best) || (spanOf(s) == spanOf(best) && s.id > best.id)) {
                best = s;
            }
        }
        return best != null ? best : root;
    }

    /**
     * Như {@link #scopeAt}, nhưng khi không scope nào phủ tokenIndex (parser không ăn hết đuôi câu) thì
     * lấy scope bắt đầu gần nhất phía trước thay vì root.
     */
    public Scope scopeAtOrNearestBefore(int tokenIndex) {
        Scope covering = scopeAt(tokenIndex);
        if (covering != root) {
            return covering;
        }
        Scope best = null;
        for (Scope s : allScopes) {
            if (s.startTokenIndex >= 0 && s.startTokenIndex <= tokenIndex
                    && (best == null || s.startTokenIndex > best.startTokenIndex)) {
                best = s;
            }
        }
        return best != null ? best : root;
    }

    private static long spanOf(Scope s) {
        long stop = s.stopTokenIndex == Integer.MAX_VALUE ? Long.MAX_VALUE : s.stopTokenIndex;
        return stop - s.startTokenIndex;
    }

    /**
     * @param visibleAliases        alias -> tên bảng thật hoặc "&lt;cte#N&gt;"/"&lt;subquery#N&gt;"
     * @param visibleDerivedScopes  chỉ alias trỏ tới CTE/subquery (alias trỏ bảng thật thì tự tra schema)
     * @param danglingQualifier     khác null chỉ khi caret ngay sau dấu chấm cụt
     */
    public record Resolution(
            Map<String, String> visibleAliases,
            Map<String, Scope> visibleDerivedScopes,
            String danglingQualifier,
            String danglingQualifierResolvesTo,
            Scope danglingQualifierScope
    ) {
    }

    public Resolution resolveAt(int cursorOffset, Scope scopeAtCursor) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, Scope> derivedScopes = new LinkedHashMap<>();
        for (Scope s : scopeAtCursor.visibilityChain()) {
            aliases.putAll(s.aliases);
            derivedScopes.putAll(s.derivedScopeAliases);
        }
        String qualifier = danglingDotQualifier.get(cursorOffset);
        String resolvesTo = qualifier == null ? null : aliases.get(qualifier);
        Scope qualifierScope = qualifier == null ? null : derivedScopes.get(qualifier);
        return new Resolution(aliases, derivedScopes, qualifier, resolvesTo, qualifierScope);
    }

    /** Đăng ký bảng vào {@code target}; "FROM c" trùng tên CTE đã có thì là tham chiếu tới CTE đó. */
    public static void registerTable(Scope target, String alias, String table, Scope cteOrNull) {
        if (cteOrNull != null) {
            target.aliases.put(alias, "<cte#" + cteOrNull.id + ">");
            target.derivedScopeAliases.put(alias, cteOrNull);
        } else {
            target.aliases.put(alias, table);
        }
    }

    public static String lastPart(String qualifiedName) {
        int i = qualifiedName.lastIndexOf('.');
        return i < 0 ? qualifiedName : qualifiedName.substring(i + 1);
    }
}
