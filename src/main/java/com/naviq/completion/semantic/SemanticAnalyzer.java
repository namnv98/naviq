package com.naviq.completion.semantic;

import com.naviq.antlr4.*;
import com.naviq.utils.TokenPositionUtil;
import com.naviq.completion.suggests.DmlTargetResolver;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tầng ngữ nghĩa - wrap toàn bộ việc gọi SemanticScope (parse, patch placeholder nếu cần, walk,
 * resolveAt) thành 1 điểm vào duy nhất. Tách khỏi PostgresCompletionEngine để orchestrator không
 * phải biết chi tiết "làm sao build được 1 SemanticScope" - chỉ cần gọi analyze(sql, cursorOffset)
 * và nhận kết quả.
 */
public class SemanticAnalyzer {

    /**
     * @return kết quả resolve, hoặc SemanticAnalysisResult với chỉ qualifier khác null (fallback
     * token-scan qua DmlTargetResolver) nếu parse lỗi nặng - KHÔNG BAO GIỜ throw ra ngoài,
     * completion không được sập vì lý do này.
     */
    public static Result analyze(String sql, int rawCursorOffset) {
        final int cursorOffset = Math.max(0, Math.min(rawCursorOffset, sql.length()));

        boolean rightAfterDot = cursorOffset > 0 && sql.charAt(cursorOffset - 1) == '.';
        String parseSql =
            rightAfterDot ? sql : SemanticScope.withCursorPlaceholder(sql, cursorOffset);
        boolean patched = !parseSql.equals(sql);

        try {
            CharStream input = CharStreams.fromString(parseSql);
            PostgreSQLLexer lexer = new PostgreSQLLexer(input);
            CommonTokenStream tokens = new CommonTokenStream(lexer);
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
            ParseTree tree = parser.query();

            SemanticScope model = new SemanticScope();
            model.offendingTokenIndices.addAll(offendingTokens);
            ParseTreeWalker.DEFAULT.walk(model, tree);

            tokens.fill();
            int tokenIdx = 0;
            if (patched) {
                for (Token t : tokens.getTokens()) {
                    if (SemanticScope.CURSOR_PLACEHOLDER.equals(t.getText())) {
                        tokenIdx = t.getTokenIndex();
                        break;
                    }
                }
            } else {
                for (Token t : tokens.getTokens()) {
                    if (t.getType() == Token.EOF || t.getStartIndex() > cursorOffset) {
                        break;
                    }
                    tokenIdx = t.getTokenIndex();
                }
            }

            var scope = model.scopeAt(tokenIdx);
            var result = model.resolveAt(cursorOffset, scope);
            return new Result(
                result.danglingQualifier(),
                result.danglingQualifierResolvesTo(),
                result.danglingQualifierScope(),
                result.visibleAliases(),
                result.visibleDerivedScopes()
            );
        } catch (Exception e) {
            // defensive: SemanticScope KHÔNG ĐƯỢC làm sập toàn bộ completion - rơi về
            // fallback token-scan thuần (DmlTargetResolver) trên chính "sql" gốc.
//            CommonTokenStream fallbackTokens = new CommonTokenStream(new PostgreSQLLexer(CharStreams.fromString(sql)));
//            fallbackTokens.fill();
//            int fallbackCaret = TokenPositionUtil.findCaretTokenIndex(fallbackTokens, cursorOffset);
//            String qualifier = DmlTargetResolver.extractQualifier(fallbackTokens, fallbackCaret);
//            return new Result(qualifier, null, null, java.util.Map.of(), java.util.Map.of());
            return new Result(null, null, null, java.util.Map.of(), java.util.Map.of());
        }
    }

    public record Result(
        String qualifier,
        String qualifierResolvesTo,
        SemanticScope.Scope qualifierDerivedScope,
        Map<String, String> visibleAliases,
        Map<String, SemanticScope.Scope> visibleDerivedScopes
    ) {

        public static Result empty() {
            return new Result(null, null, null, Map.of(), Map.of());
        }
    }
}