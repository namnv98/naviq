package com.naviq.completion.syntactic;

import com.naviq.antlr4.*;
import com.naviq.utils.TokenPositionUtil;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.HashMap;
import java.util.Map;

/**
 * Tầng cú pháp - wrap toàn bộ việc gọi AntlrCompletionEngineFix (setup
 * ignoredTokens/preferredRules, parse, collectCandidates) thành 1 điểm vào. Luôn
 * parse "sql" GỐC (không placeholder) - AntlrCompletionEngineFix dừng đúng tại
 * caretTokenIndex nên không cần patch gì cho vị trí trống, khác với SemanticAnalyzer.
 * <p>
 * GHI CHÚ SỬA THEO GRAMMAR MỚI: tên token/rule của grammar rút gọn cũ (ID, LPAREN,
 * RPAREN, EQ, NEQ, NUMBER, STRING, RULE_tableName, RULE_columnName, RULE_dataTypeName,
 * RULE_functionCall, RULE_tableAlias...) KHÔNG còn tồn tại trong grammar PostgreSQL đầy
 * đủ mới (rule/token đặt tên kiểu Postgres gram.y). Đã map lại 1-1 sang tên tương ứng -
 * xem chú thích cạnh từng dòng.
 */
public class SyntacticAnalyzer {

    /**
     * PHẢI dùng chung (static), KHÔNG tạo mới mỗi lần gọi analyze() - chính
     * javadoc của FollowSetsByState ghi rõ "Thread-safe cache of follow sets,
     * shared across engine instances", nhưng code trước đây tạo mới mỗi lần gọi,
     * làm mất hoàn toàn tác dụng cache (mỗi completion request tính lại follow set
     * từ đầu dù ATN state giống hệt lần trước).
     */
    private static final AntlrCompletionEngine.FollowSetsByState FOLLOW_SETS = new AntlrCompletionEngine.FollowSetsByState();

    /**
     * PHẢI dùng chung (static) CÙNG VỚI FOLLOW_SETS ở trên - FollowSetsByState cache
     * theo IDENTITY của map này (IdentityHashMap bên trong nó), không phải theo nội
     * dung. Nếu tạo map mới mỗi lần gọi (như trước đây), FOLLOW_SETS dù đã static
     * vẫn KHÔNG BAO GIỜ hit cache - vì mỗi lần là 1 object khác nhau về identity dù
     * nội dung giống hệt.
     */
    private static final Map<Integer, Boolean> IGNORED_TOKENS = buildIgnoredTokens();
    private static final Map<Integer, Boolean> PREFERRED_RULES = buildPreferredRules();

    private static Map<Integer, Boolean> buildIgnoredTokens() {
        Map<Integer, Boolean> m = new HashMap<>();
        m.put(Token.EOF, true);
        m.put(PostgreSQLParser.Identifier, true);       // ID cũ -> Identifier (colid/identifier trần)
        m.put(PostgreSQLParser.OPEN_PAREN, true);        // LPAREN cũ
        m.put(PostgreSQLParser.CLOSE_PAREN, true);       // RPAREN cũ
        m.put(PostgreSQLParser.PLUS, true);
        m.put(PostgreSQLParser.MINUS, true);
        m.put(PostgreSQLParser.SLASH, true);
        m.put(PostgreSQLParser.EQUAL, true);             // EQ cũ -> EQUAL
        m.put(PostgreSQLParser.NOT_EQUALS, true);        // NEQ cũ -> NOT_EQUALS (token "<>")
        m.put(PostgreSQLParser.LT, true);
        m.put(PostgreSQLParser.GT, true);
        m.put(PostgreSQLParser.LESS_EQUALS, true);       // LTE cũ
        m.put(PostgreSQLParser.GREATER_EQUALS, true);    // GTE cũ
        m.put(PostgreSQLParser.Numeric, true);           // NUMBER cũ -> Numeric (số thực)
        m.put(PostgreSQLParser.Integral, true);          // NUMBER cũ -> Integral (số nguyên) - grammar
        // mới tách riêng 2 token cho hằng số, cả 2 đều cần bỏ qua
        m.put(PostgreSQLParser.StringConstant, true);    // STRING cũ
        m.put(PostgreSQLParser.SEMI, true);
        return m;
    }

    private static Map<Integer, Boolean> buildPreferredRules() {
        Map<Integer, Boolean> m = new HashMap<>();
        m.put(PostgreSQLParser.RULE_qualified_name, true);  // tableName cũ - tên bảng/CTE (FROM,
        m.put(PostgreSQLParser.RULE_any_name, true);        // DROP TABLE/VIEW/INDEX/SEQUENCE/...
        m.put(PostgreSQLParser.RULE_columnref, true);       // columnName cũ - biểu thức cột (SELECT
        m.put(PostgreSQLParser.RULE_typename, true);        // dataTypeName cũ
        m.put(PostgreSQLParser.RULE_func_name, true);       // functionCall cũ - tên hàm (không phải
        m.put(PostgreSQLParser.RULE_table_alias, true);     // tableAlias cũ
        m.put(PostgreSQLParser.RULE_colid, true);
        return m;
    }

    public record Result(
            CommonTokenStream tokenStream,
            int caretTokenIndex,
            AntlrCompletionEngine.CandidatesCollection candidates
    ) {

    }

    public static Result analyze(String sql, int cursorOffset) {
        CharStream input = CharStreams.fromString(sql);
        PostgreSQLLexer lexer = new PostgreSQLLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        PostgreSQLParser parser = new PostgreSQLParser(tokenStream);
        parser.removeErrorListeners();
        tokenStream.fill();
        int caretTokenIndex = TokenPositionUtil.findCaretTokenIndex(tokenStream, cursorOffset);
        AntlrCompletionEngine engine = new AntlrCompletionEngine(parser, IGNORED_TOKENS, PREFERRED_RULES, FOLLOW_SETS);
        var candidates = engine.collectCandidates(caretTokenIndex);
        return new Result(tokenStream, caretTokenIndex, candidates);
    }
}