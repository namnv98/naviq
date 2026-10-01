package com.sqlctx.completion.syntactic.postgresql;

import com.sqlctx.antlr4.postgresql.*;
import com.sqlctx.completion.syntactic.engine.CompletionEngine;
import com.sqlctx.completion.syntactic.engine.support.FollowSetsByState;
import com.sqlctx.completion.model.CandidatesResult;
import com.sqlctx.util.TokenPositions;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
public class PostgresSyntacticAnalyzer {

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

    public static Map<Integer, Boolean> buildIgnoredTokens() {
        Map<Integer, Boolean> m = new HashMap<>();
        m.put(Token.EOF, true);
        m.put(PostgreSQLParser.Identifier, true);
        m.put(PostgreSQLParser.OPEN_PAREN, true);
        m.put(PostgreSQLParser.CLOSE_PAREN, true);
        m.put(PostgreSQLParser.PLUS, true);
        m.put(PostgreSQLParser.MINUS, true);
        m.put(PostgreSQLParser.SLASH, true);
        m.put(PostgreSQLParser.EQUAL, true);
        m.put(PostgreSQLParser.NOT_EQUALS, true);
        m.put(PostgreSQLParser.LT, true);
        m.put(PostgreSQLParser.GT, true);
        m.put(PostgreSQLParser.LESS_EQUALS, true);
        m.put(PostgreSQLParser.GREATER_EQUALS, true);
        m.put(PostgreSQLParser.Numeric, true);
        m.put(PostgreSQLParser.Integral, true);
        m.put(PostgreSQLParser.BinaryIntegral, true);
        m.put(PostgreSQLParser.OctalIntegral, true);
        m.put(PostgreSQLParser.HexadecimalIntegral, true);
        m.put(PostgreSQLParser.StringConstant, true);
        m.put(PostgreSQLParser.BeginDollarStringConstant, true);
        m.put(PostgreSQLParser.DollarText, true);
        m.put(PostgreSQLParser.EndDollarStringConstant, true);
        m.put(PostgreSQLParser.UnicodeEscapeStringConstant, true);
        m.put(PostgreSQLParser.EscapeStringConstant, true);
        m.put(PostgreSQLParser.BinaryStringConstant, true);
        m.put(PostgreSQLParser.HexadecimalStringConstant, true);
        m.put(PostgreSQLParser.SEMI, true);
        return m;
    }

    public static Map<Integer, Boolean> buildPreferredRules() {
        Map<Integer, Boolean> m = new HashMap<>();
        m.put(PostgreSQLParser.RULE_qualified_name, true);  // tableName/CTE
        m.put(PostgreSQLParser.RULE_any_name, true);        // DROP TABLE/VIEW/INDEX/SEQUENCE/...
        m.put(PostgreSQLParser.RULE_columnref, true);       // columnName biểu thức cột (SELECT
        m.put(PostgreSQLParser.RULE_typename, true);        // dataTypeName
        m.put(PostgreSQLParser.RULE_func_name, true);       // tên hàm (không phải
        m.put(PostgreSQLParser.RULE_table_alias, true);     // tableAlias
        m.put(PostgreSQLParser.RULE_colid, true);
        // role/user (GRANT/REVOKE/ALTER ROLE/OWNER TO/ALTER GROUP/DROP ROLE/DROP OWNED BY...) - THIẾU
        // rule này trước đây khiến engine tụt xuống liệt kê MỌI token nguyên thuỷ (400-500+ keyword
        // dùng-được-làm-identifier) thay vì dừng lại ở đúng 1 rule để gợi ý role thật (phát hiện qua
        // audit noise sau khi viết bộ test phủ rộng toàn bộ grammar).
        m.put(PostgreSQLParser.RULE_rolespec, true);
        // Rule DÙNG CHUNG cho nhiều vị trí khác nhau (CREATE DATABASE OWNER/TEMPLATE/ENCODING, DO
        // LANGUAGE, ALTER EXTENSION VERSION/FROM...) - PostgresSuggestionService tự phân biệt bằng
        // keyword ĐỨNG NGAY TRƯỚC (OWNER -> role thật, LANGUAGE -> tên ngôn ngữ, còn lại -> không có
        // dữ liệu thật để gợi ý, chỉ cần ngừng tràn keyword rác). Cùng lý do với rolespec ở trên.
        m.put(PostgreSQLParser.RULE_nonreservedword_or_sconst, true);
        // "type_function_name" (func_name/typename đều dựa vào rule này ở lõi) - vị trí tham chiếu
        // TÊN HÀM/KIỂU DỮ LIỆU ĐÃ CÓ (vd RESTRICT/JOIN của CREATE OPERATOR, sfunc/finalfunc của
        // CREATE AGGREGATE) - cùng lý do rolespec/nonreservedword_or_sconst: thiếu rule này khiến
        // engine tụt xuống liệt kê hết token nguyên thuỷ thay vì dừng ở 1 rule để gợi ý hàm/kiểu thật.
        m.put(PostgreSQLParser.RULE_type_function_name, true);
        // "collabel" = IDENTIFIER | MỌI keyword (unreserved/col_name/type_func_name/reserved) - vị trí
        // tên option tuỳ ý (OPTIONS (|, SET (|, CREATE TEXT SEARCH DICTIONARY (|...) và alias cột mới
        // (AS |). Thiếu rule này engine liệt kê cả ~525 keyword như thể đều là gợi ý hợp lệ (bug thật,
        // phát hiện khi chuyển test sang so khớp CHÍNH XÁC). Keyword cú pháp THẬT tại cùng vị trí
        // (vd ADD/SET/DROP của alter_generic_option_elem) đi qua nhánh khác nên không bị ảnh hưởng.
        m.put(PostgreSQLParser.RULE_collabel, true);
        m.put(PostgreSQLParser.RULE_bare_col_label, true);

        return m;
    }

    public record Result(
            CommonTokenStream tokenStream,
            int caretTokenIndex,
            CandidatesResult candidates) {

    }

    /**
     * Loại token giả mà tầng ngữ nghĩa nên chèn tại caret để parse đi qua được: định danh nếu grammar
     * cho phép (hầu hết mọi chỗ), không thì literal số, rồi literal chuỗi (vd "varchar(|)" chỉ nhận số -
     * chèn định danh ở đó làm parser bỏ cả subquery bao ngoài). Không dự đoán được gì (tầng cú pháp
     * không phục hồi lỗi, token trước caret sai là rỗng) hoặc không loại nào hợp lệ thì vẫn là định danh.
     */
    public static int caretTokenTypeToInsert(CandidatesResult candidates) {
        Set<Integer> types = candidates.caretTokenTypes;
        if (types.isEmpty() || types.contains(PostgreSQLParser.Identifier)) {
            return PostgreSQLParser.Identifier;
        }
        for (int type : List.of(PostgreSQLParser.Integral, PostgreSQLParser.Numeric, PostgreSQLParser.StringConstant)) {
            if (types.contains(type)) {
                return type;
            }
        }
        return PostgreSQLParser.Identifier;
    }

    public static Result analyze(String sql, int cursorOffset) {
        CharStream input = CharStreams.fromString(sql);
        PostgreSQLLexer lexer = new PostgreSQLLexer(input);
        // Không có phục hồi lỗi (xem CompletionEngineBase) nên ký tự lạ (vd '\' của meta-command CLI) luôn
        // gây lỗi token ở lexer - không tắt thì ANTLR in thẳng "token recognition error..." ra System.err,
        // đè lên màn hình terminal, độc lập với việc parser.removeErrorListeners() bên dưới.
        lexer.removeErrorListeners();
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        PostgreSQLParser parser = new PostgreSQLParser(tokenStream);
        parser.removeErrorListeners();
        tokenStream.fill();
        int caretTokenIndex = TokenPositions.findCaretTokenIndex(tokenStream, cursorOffset);
        CompletionEngine engine = new CompletionEngine(parser, IGNORED_TOKENS, PREFERRED_RULES);
        var candidates = engine.collectCandidates(caretTokenIndex);
        return new Result(tokenStream, caretTokenIndex, candidates);
    }
}