package com.sqlctx.completion.semantic.postgresql;

import com.sqlctx.antlr4.postgresql.PostgreSQLParser;
import com.sqlctx.completion.model.CaretScope;
import com.sqlctx.completion.model.Scope;
import com.sqlctx.completion.semantic.ScopeTree;
import com.sqlctx.completion.semantic.TwoStageParse;
import com.sqlctx.util.LoggingConfig;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tầng ngữ nghĩa - wrap toàn bộ việc gọi PostgresScopeBuilder (parse, patch placeholder nếu cần, walk,
 * resolveAt) thành 1 điểm vào duy nhất. Tách khỏi PostgresCompletionEngine để orchestrator không
 * phải biết chi tiết "làm sao build được 1 PostgresScopeBuilder" - chỉ cần gọi analyze(sql, cursorOffset)
 * và nhận kết quả.
 */
public class PostgresSemanticAnalyzer {

    private static final Logger LOG = LoggingConfig.of(PostgresSemanticAnalyzer.class);

    /**
     * @return kết quả resolve, hoặc SemanticAnalysisResult với chỉ qualifier khác null (fallback
     * token-scan qua DmlTargetResolver) nếu parse lỗi nặng - KHÔNG BAO GIỜ throw ra ngoài,
     * completion không được sập vì lý do này.
     */
    public static Result analyze(String sql, int rawCursorOffset, int caretTokenType) {
        return analyze(sql, rawCursorOffset, caretTokenType, Set.of());
    }

    /** @param allowedAtCaret loại token grammar chấp nhận tại caret - xem {@link PostgresCursorTokenPatcher#patch(String, int, int, Set)} */
    public static Result analyze(String sql, int rawCursorOffset, int caretTokenType, Set<Integer> allowedAtCaret) {
        final int cursorOffset = Math.max(0, Math.min(rawCursorOffset, sql.length()));

        try {
            PostgresCursorTokenPatcher.PatchResult patch = PostgresCursorTokenPatcher.patch(sql, cursorOffset, caretTokenType, allowedAtCaret);
            CommonTokenStream tokens = patch.tokenStream();

            var parsed = TwoStageParse.parse(tokens,
                    TwoStageParse.withPlaceholderAfter(tokens, patch.caretTokenIndex(), PostgreSQLParser.Identifier),
                    PostgreSQLParser::new, PostgreSQLParser::root);
            ParseTree tree = parsed.tree();
            Set<Integer> offendingTokens = parsed.offendingTokens();

            ScopeTree scopes = new ScopeTree(offendingTokens);
            ParseTreeWalker.DEFAULT.walk(new PostgresScopeBuilder(scopes, patch.caretTokenIndex()), tree);
            scopes.dropUnnamedEntries();

            // caretTokenIndex đã được PostgresCursorTokenPatcher tính SẴN, đúng cho cả 2 case
            // (borrow token thật / chèn token giả) - không cần dò lại lần nữa ở đây.
            var scope = scopes.scopeAt(patch.caretTokenIndex());
            var result = scopes.resolveAt(cursorOffset, scope);
            String ddlTargetAlias = scope != null && scope.isDdlTargetScope ? scope.primaryAlias() : null;
            // Tên CTE (WITH cte AS (...)) đang HIỂN THỊ ở scope hiện tại - đăng ký ngay khi WITH
            // clause đóng xong (xem PostgresScopeBuilder.exitWith_clause), TRƯỚC KHI người dùng gõ
            // tới FROM. Cần để gợi ý TÊN CTE như 1 "bảng" khi đang gõ dở "FROM re|" (bug thật).
            // Đi qua visibleCteNames() (đúng chuỗi cha) - KHÔNG dùng key của
            // visibleDerivedScopes(): map đó còn chứa alias subquery ("(select ...) x") và alias
            // trỏ tới CTE ("from c cc"), không phải tên gõ được sau FROM (bug thật: từng gợi ý "x").
            Set<String> visibleCteNames = scope != null ? scope.visibleCteNames() : Set.of();
            return new Result(
                    result.danglingQualifier(),
                    result.danglingQualifierResolvesTo(),
                    result.danglingQualifierScope(),
                    result.visibleAliases(),
                    result.visibleDerivedScopes(),
                    ddlTargetAlias,
                    visibleCteNames,
                    result.aliasDistances()
            );
        } catch (Exception e) {
            // Không in ra stderr - đang gõ dở SQL nên lỗi kiểu này xảy ra liên tục, in thẳng ra sẽ phá
            // layout terminal (giống lỗi ANTLR ConsoleErrorListener đã sửa trước đây). Ghi vào file log
            // (~/sqlctx-debug.log) để vẫn còn dấu vết debug khi cần, không mất luôn thông tin lỗi.
            LOG.log(Level.FINE, "Lỗi khi phân tích ngữ nghĩa - fallback rỗng", e);
            return Result.empty();
        }
    }
    public record Result(
        String qualifier,
        String qualifierResolvesTo,
        Scope qualifierDerivedScope,
        Map<String, String> visibleAliases,
        Map<String, Scope> visibleDerivedScopes,
        String ddlTargetAlias,
        Set<String> visibleCteNames,
        Map<String, Integer> aliasDistances
    ) implements CaretScope {

        public static Result empty() {
            return new Result(null, null, null, Map.of(), Map.of(), null, Set.of(), Map.of());
        }
    }
}