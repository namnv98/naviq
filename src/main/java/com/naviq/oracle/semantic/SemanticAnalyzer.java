package com.naviq.oracle.semantic;

import com.naviq.antlr4.oracle.PlSqlParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tầng ngữ nghĩa Oracle - wrap toàn bộ việc gọi SemanticScope (patch token, walk, resolveAt)
 * thành 1 điểm vào duy nhất, giống hệt vai trò SemanticAnalyzer (Postgres).
 * <p>
 * BUG ĐÃ SỬA: bản trước gọi {@code CursorTokenPatcher.patch(...)} - class đó (nếu tồn tại cùng
 * package) là bản COPY NGUYÊN VẸN của CursorTokenPatcher (Postgres), bên trong vẫn tạo
 * {@code new PostgreSQLLexer(input)} và dùng token-type của PostgreSQLParser - lex SQL Oracle
 * bằng lexer Postgres rồi feed vào PlSqlParser (Oracle) khiến token-type hoàn toàn sai lệch,
 * ANTLR parse loạn (thường ném exception khi tra ATN theo token-type không hợp lệ), bị
 * {@code catch (Exception e)} nuốt im lặng -> trả về Result toàn null/rỗng cho MỌI input, kể cả
 * input hợp lệ đơn giản như "select * from users where |". Đã đổi sang gọi đúng
 * {@link OracleCursorTokenPatcher} (dùng PlSqlLexer thật).
 * <p>
 * ĐÃ BỔ SUNG: gọi {@link DanglingDotDetector} để phát hiện "gõ dở sau dấu chấm" (vd "u.") - bản
 * trước THIẾU bước này hoàn toàn (đã nói rõ ở lượt sửa SemanticScope trước: Oracle không patch
 * được grammar theo kiểu Postgres nên phải phát hiện thuần qua token, KHÔNG qua listener).
 */
public class SemanticAnalyzer {

    private static final Set<Integer> IDENTIFIER_TOKEN_TYPES =
            Set.of(PlSqlParser.REGULAR_ID, PlSqlParser.DELIMITED_ID);

    /**
     * @return kết quả resolve, hoặc Result toàn null/rỗng nếu parse lỗi nặng - KHÔNG BAO GIỜ throw
     * ra ngoài, completion không được sập vì lý do này.
     */
    public static Result analyze(String sql, int rawCursorOffset) {
        final int cursorOffset = Math.max(0, Math.min(rawCursorOffset, sql.length()));
        try {
            OracleCursorTokenPatcher.PatchResult patch = OracleCursorTokenPatcher.patch(sql, cursorOffset);
            CommonTokenStream tokens = patch.tokenStream();
            PlSqlParser parser = new PlSqlParser(tokens);
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
            ParseTree tree = parser.unit_statement();
            SemanticScope model = new SemanticScope();
            model.offendingTokenIndices.addAll(offendingTokens);
            ParseTreeWalker.DEFAULT.walk(model, tree);

            // Xem javadoc DanglingDotDetector - phải làm THUẦN qua token, KHÔNG qua listener (khác
            // Postgres) vì grammar Oracle chưa patch cho phép "u." tự nó hợp lệ cú pháp.
            String danglingQualifier = DanglingDotDetector.detect(
                    tokens, patch.caretTokenIndex(), PlSqlParser.PERIOD, IDENTIFIER_TOKEN_TYPES);
            model.recordDanglingDot(cursorOffset, danglingQualifier);

            var scope = model.scopeAt(patch.caretTokenIndex());
            var result = model.resolveAt(cursorOffset, scope);
            return new Result(
                    result.danglingQualifier(),
                    result.danglingQualifierResolvesTo(),
                    result.danglingQualifierScope(),
                    result.visibleAliases(),
                    result.visibleDerivedScopes()
            );
        } catch (Exception e) {
            e.printStackTrace();
            return Result.empty();
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