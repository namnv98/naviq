package com.naviq.completion.syntactic;

import com.naviq.antlr4.oracle.PlSqlLexer;
import com.naviq.antlr4.oracle.PlSqlParser;
import com.naviq.completion.syntactic.feature.FollowSetsByState;
import com.naviq.completion.syntactic.model.CandidatesResult;
import com.naviq.utils.TokenPositionUtil;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.HashMap;
import java.util.Map;

/**
 * Tầng cú pháp - wrap toàn bộ việc gọi AntlrCompletionEngineFix (setup
 * ignoredTokens/preferredRules, parse, collectCandidates) thành 1 điểm vào.
 * <p>
 * SỬA LẦN NÀY (bug "gợi ý 2319 keyword ở vị trí WHERE |"): buildPreferredRules() trước đó chỉ có
 * RULE_id_expression, RULE_general_element, RULE_column_name... - toàn là rule TỔ TIÊN, không
 * phải rule CHỨA TRỰC TIẾP ~2283 alternative literal keyword (ABORT, ABS, ..., SYS_XQPOLYABS...).
 * Chuỗi thật: general_element_part -> id_expression -> regular_id -> (non_reserved_keywords_pre12c
 * | non_reserved_keywords_in_12c | REGULAR_ID | ~100 token trực tiếp). Đã xác nhận bằng thực
 * nghiệm với bản AntlrCompletionEngineSimple (chỉ check rule ĐANG CHẠY NGAY LÚC ĐÓ tại mỗi
 * password-door, không phải cả chuỗi tổ tiên) - đánh dấu id_expression hoàn toàn vô nghĩa vì lúc
 * đánh giá từng token literal, rule đang chạy là regular_id (hoặc 1 trong 2 rule con của nó), không
 * phải id_expression. Đã thêm đúng 3 rule con đó vào preferredRules.
 */
public class OracleSQLSyntacticAnalyzer {

    /**
     * PHẢI dùng chung (static), KHÔNG tạo mới mỗi lần gọi analyze() - chính
     * javadoc của FollowSetsByState ghi rõ "Thread-safe cache of follow sets,
     * shared across engine instances", nhưng code trước đây tạo mới mỗi lần gọi,
     * làm mất hoàn toàn tác dụng cache (mỗi completion request tính lại follow set
     * từ đầu dù ATN state giống hệt lần trước).
     */
    private static final FollowSetsByState FOLLOW_SETS = new FollowSetsByState();

    /**
     * PHẢI dùng chung (static) CÙNG VỚI FOLLOW_SETS ở trên - FollowSetsByState cache
     * theo IDENTITY của map này (IdentityHashMap bên trong nó), không phải theo nội
     * dung. Nếu tạo map mới mỗi lần gọi (như trước đây), FOLLOW_SETS dù đã static
     * vẫn KHÔNG BAO GIỜ hit cache - vì mỗi lần là 1 object khác nhau về identity dù
     * nội dung giống hệt.
     */
    private static final Map<Integer, Boolean> IGNORED_TOKENS = buildIgnoredTokens();
    private static final Map<Integer, Boolean> PREFERRED_RULES = buildPreferredRules();

    /**
     * Token nào KHÔNG mang thông tin ngữ nghĩa cho completion (toán tử, dấu câu, literal...) -
     * dùng để lọc nhiễu khi tính follow-set. Đối chiếu đúng PlSqlLexer.g4.
     */
    public static Map<Integer, Boolean> buildIgnoredTokens() {
        Map<Integer, Boolean> m = new HashMap<>();
        m.put(Token.EOF, true);
        m.put(PlSqlParser.REGULAR_ID, true);
        m.put(PlSqlParser.DELIMITED_ID, true);
        m.put(PlSqlParser.LEFT_PAREN, true);
        m.put(PlSqlParser.RIGHT_PAREN, true);
        m.put(PlSqlParser.PLUS_SIGN, true);
        m.put(PlSqlParser.MINUS_SIGN, true);
        m.put(PlSqlParser.SOLIDUS, true);
        m.put(PlSqlParser.EQUALS_OP, true);
        m.put(PlSqlParser.NOT_EQUAL_OP, true);
        m.put(PlSqlParser.LESS_THAN_OP, true);
        m.put(PlSqlParser.GREATER_THAN_OP, true);
        m.put(PlSqlParser.UNSIGNED_INTEGER, true);
        m.put(PlSqlParser.APPROXIMATE_NUM_LIT, true);
        m.put(PlSqlParser.CHAR_STRING, true);
        m.put(PlSqlParser.NATIONAL_CHAR_STRING_LIT, true);
        m.put(PlSqlParser.SEMICOLON, true);
        return m;
    }

    /**
     * Rule nào được ưu tiên khi resolve completion - đối chiếu đúng PlSqlParser.g4.
     */
    public static Map<Integer, Boolean> buildPreferredRules() {
        Map<Integer, Boolean> m = new HashMap<>();
        m.put(PlSqlParser.RULE_tableview_name, true);
        m.put(PlSqlParser.RULE_query_name, true);
        m.put(PlSqlParser.RULE_index_name, true);
        m.put(PlSqlParser.RULE_sequence_name, true);
        m.put(PlSqlParser.RULE_synonym_name, true);
        m.put(PlSqlParser.RULE_trigger_name, true);
        m.put(PlSqlParser.RULE_type_name, true);
        m.put(PlSqlParser.RULE_package_name, true);
        m.put(PlSqlParser.RULE_procedure_name, true);
        m.put(PlSqlParser.RULE_general_element, true);
        m.put(PlSqlParser.RULE_column_name, true);
        m.put(PlSqlParser.RULE_type_spec, true);
        m.put(PlSqlParser.RULE_datatype, true);
        m.put(PlSqlParser.RULE_function_name, true);
        m.put(PlSqlParser.RULE_table_alias, true);
        m.put(PlSqlParser.RULE_identifier, true);

        m.put(PlSqlParser.RULE_id_expression, true);
        m.put(PlSqlParser.RULE_regular_id, true);

        return m;
    }

    public record Result(
            CommonTokenStream tokenStream,
            int caretTokenIndex,
            CandidatesResult candidates) {

    }

    public static Result analyze(String sql, int cursorOffset) {
        CharStream input = CharStreams.fromString(sql);
        PlSqlLexer lexer = new PlSqlLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(tokenStream);
        parser.removeErrorListeners();
        tokenStream.fill();
        int caretTokenIndex = TokenPositionUtil.findCaretTokenIndex(tokenStream, cursorOffset);
        CompletionEngine engine = new CompletionEngine(parser, IGNORED_TOKENS, PREFERRED_RULES);
        var candidates = engine.collectCandidates(caretTokenIndex);
        return new Result(tokenStream, caretTokenIndex, candidates);
    }
}