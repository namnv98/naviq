package com.naviq.completion.semantic.oracle;

import com.naviq.antlr4.oracle.PlSqlParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import com.naviq.completion.model.Scope;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tầng ngữ nghĩa Oracle - wrap toàn bộ việc gọi OracleScopeBuilder (patch token, walk, resolveAt)
 * thành 1 điểm vào duy nhất, giống hệt vai trò OracleSemanticAnalyzer (Postgres).
 * <p>
 * BUG ĐÃ SỬA: bản trước gọi {@code OracleCursorTokenPatcher.patch(...)} - class đó (nếu tồn tại cùng
 * package) là bản COPY NGUYÊN VẸN của OracleCursorTokenPatcher (Postgres), bên trong vẫn tạo
 * {@code new PostgreSQLLexer(input)} và dùng token-type của PostgreSQLParser - lex SQL Oracle
 * bằng lexer Postgres rồi feed vào PlSqlParser (Oracle) khiến token-type hoàn toàn sai lệch,
 * ANTLR parse loạn (thường ném exception khi tra ATN theo token-type không hợp lệ), bị
 * {@code catch (Exception e)} nuốt im lặng -> trả về Result toàn null/rỗng cho MỌI input, kể cả
 * input hợp lệ đơn giản như "select * from users where |". Đã đổi sang gọi đúng
 * {@link OracleCursorTokenPatcher} (dùng PlSqlLexer thật).
 * <p>
 * ĐÃ BỔ SUNG: gọi {@link DanglingDotDetector} để phát hiện "gõ dở sau dấu chấm" (vd "u.") - bản
 * trước THIẾU bước này hoàn toàn (đã nói rõ ở lượt sửa OracleScopeBuilder trước: Oracle không patch
 * được grammar theo kiểu Postgres nên phải phát hiện thuần qua token, KHÔNG qua listener).
 */
public class OracleSemanticAnalyzer {

    private static final Set<Integer> IDENTIFIER_TOKEN_TYPES = Set.of(PlSqlParser.REGULAR_ID, PlSqlParser.DELIMITED_ID);

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
            OracleScopeBuilder model = new OracleScopeBuilder();
            model.offendingTokenIndices.addAll(offendingTokens);
            ParseTreeWalker.DEFAULT.walk(model, tree);

            String danglingQualifier = detect(tokens, patch.caretTokenIndex(), PlSqlParser.PERIOD, IDENTIFIER_TOKEN_TYPES);
            model.recordDanglingDot(cursorOffset, danglingQualifier);

            var scope = model.scopeAt(patch.caretTokenIndex());
            if (scope != model.root() && crossesRealSemicolon(tokens, scope, patch.caretTokenIndex())) {
                // scopeAt() có 1 fallback (xem javadoc ở đó) chọn "scope gần caret nhất theo điểm
                // bắt đầu" khi không scope nào phủ trọn caret - đúng ý đồ cho câu bị gõ dở/lỗi cú
                // pháp (chưa có ";"). NHƯNG nếu giữa điểm ĐÓNG THẬT của scope đó và caret có 1 dấu
                // ";" THẬT (đã lex đúng, không phải lỗi) - đây là ranh giới statement rõ ràng, câu
                // trước đã hoàn toàn kết thúc và caret đang ở 1 statement MỚI không liên quan, vd
                // "SELECT ... FROM users WHERE id=1; |" trong PL/SQL block - scope SELECT không
                // được phép rò alias/cột sang đây. Bỏ qua fallback, coi như không tìm được scope.
                scope = model.root();
            }
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
            e.printStackTrace();
            return Result.empty();
        }
    }

    private static boolean crossesRealSemicolon(CommonTokenStream tokens, Scope scope, int caretTokenIndex) {
        if (scope.stopTokenIndex == Integer.MAX_VALUE) {
            return false; // scope thật sự chưa đóng (lỗi cú pháp/gõ dở) - không có gì để "vượt qua"
        }
        for (int i = scope.stopTokenIndex + 1; i < caretTokenIndex; i++) {
            Token t = tokens.get(i);
            if (t.getChannel() == Token.DEFAULT_CHANNEL && t.getType() == PlSqlParser.SEMICOLON) {
                return true;
            }
        }
        return false;
    }

    public static String detect(CommonTokenStream tokens, int caretTokenIndex, int dotTokenType, Set<Integer> identifierTypes) {
        List<Token> all = tokens.getTokens();
        if (caretTokenIndex < 0 || caretTokenIndex >= all.size()) {
            return null;
        }
        Token caret = all.get(caretTokenIndex);
        if (caret.getType() != dotTokenType) {
            return null; // cursor không nằm ngay tại 1 dấu chấm -> không phải trường hợp này
        }
        // tìm token THẬT gần nhất TRƯỚC dấu chấm (bỏ qua hidden channel như whitespace/comment)
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            Token t = all.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            return identifierTypes.contains(t.getType()) ? t.getText() : null;
        }
        return null;
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