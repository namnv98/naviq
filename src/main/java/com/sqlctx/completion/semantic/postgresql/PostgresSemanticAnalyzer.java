package com.sqlctx.completion.semantic.postgresql;

import com.sqlctx.antlr4.postgresql.PostgreSQLParser;
import com.sqlctx.completion.model.Scope;
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
    public static Result analyze(String sql, int rawCursorOffset) {
        final int cursorOffset = Math.max(0, Math.min(rawCursorOffset, sql.length()));

        try {
            PostgresCursorTokenPatcher.PatchResult patch = PostgresCursorTokenPatcher.patch(sql, cursorOffset);
            CommonTokenStream tokens = patch.tokenStream();

            PostgreSQLParser parser = new PostgreSQLParser(tokens);
            Set<Integer> offendingTokens = new HashSet<>();
            parser.removeErrorListeners();
            parser.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> r, Object offendingSymbol,
                                        int l, int c, String m, RecognitionException e) {
                    if (offendingSymbol instanceof Token t) {
                        offendingTokens.add(t.getTokenIndex());
                    }
                }
            });
            ParseTree tree = parser.root();

            PostgresScopeBuilder model = new PostgresScopeBuilder();
            model.offendingTokenIndices.addAll(offendingTokens);
            ParseTreeWalker.DEFAULT.walk(model, tree);

            // caretTokenIndex đã được PostgresCursorTokenPatcher tính SẴN, đúng cho cả 2 case
            // (borrow token thật / chèn token giả) - không cần dò lại lần nữa ở đây.
            var scope = model.scopeAt(patch.caretTokenIndex());
            var result = model.resolveAt(cursorOffset, scope);
            String ddlTargetAlias = scope != null && scope.isDdlTargetScope ? scope.primaryAlias() : null;
            return new Result(
                    result.danglingQualifier(),
                    result.danglingQualifierResolvesTo(),
                    result.danglingQualifierScope(),
                    result.visibleAliases(),
                    result.visibleDerivedScopes(),
                    ddlTargetAlias
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
        String ddlTargetAlias
    ) {

        public static Result empty() {
            return new Result(null, null, null, Map.of(), Map.of(), null);
        }
    }
}