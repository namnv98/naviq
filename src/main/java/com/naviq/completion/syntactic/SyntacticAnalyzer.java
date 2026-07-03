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
        m.put(PostgreSQLParser.ID, true);
        m.put(PostgreSQLParser.LPAREN, true);
        m.put(PostgreSQLParser.RPAREN, true);
        m.put(PostgreSQLParser.PLUS, true);
        m.put(PostgreSQLParser.MINUS, true);
        m.put(PostgreSQLParser.SLASH, true);
        m.put(PostgreSQLParser.EQ, true);
        m.put(PostgreSQLParser.NEQ, true);
        m.put(PostgreSQLParser.LT, true);
        m.put(PostgreSQLParser.GT, true);
        m.put(PostgreSQLParser.LTE, true);
        m.put(PostgreSQLParser.GTE, true);
        m.put(PostgreSQLParser.NUMBER, true);
        m.put(PostgreSQLParser.STRING, true);
        m.put(PostgreSQLParser.SEMI, true);
        return m;
    }

    private static Map<Integer, Boolean> buildPreferredRules() {
        Map<Integer, Boolean> m = new HashMap<>();
        m.put(PostgreSQLParser.RULE_tableName, true);
        m.put(PostgreSQLParser.RULE_columnName, true);
        m.put(PostgreSQLParser.RULE_dataTypeName, true);
        m.put(PostgreSQLParser.RULE_functionCall, true);
        m.put(PostgreSQLParser.RULE_tableAlias, true);
        return m;
    }

    public record Result(
        CommonTokenStream tokenStream,
        int caretTokenIndex,
        AntlrCompletionEngine.CandidatesCollection candidates
    ) {}

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
