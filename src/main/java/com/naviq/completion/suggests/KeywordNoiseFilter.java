package com.naviq.completion.suggests;

import com.naviq.antlr4.PostgreSQLLexer;
import com.naviq.antlr4.PostgreSQLParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.Transition;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.*;

final class KeywordNoiseFilter {

    private KeywordNoiseFilter() {
    }

    /**
     * Toàn bộ token type CÓ THỂ là token CUỐI CÙNG của 1 "colid" (tên bảng/cột/alias
     * trần) - tính TỰ ĐỘNG bằng cách duyệt ATN của rule colid, thay vì liệt kê tay
     * "token cấu trúc" (dễ thiếu sót, phải nhớ vá mỗi khi gặp case mới). "colid" đã tự
     * định nghĩa đúng chính xác tập này trong .g4 (identifier | unreserved_keyword |
     * col_name_keyword | plsql_unreserved_keyword | LEFT | RIGHT) - lấy trực tiếp từ
     * nguồn sự thật duy nhất, tự động cập nhật nếu grammar đổi (thêm/bớt keyword), không
     * cần sửa file Java này lần nào nữa.
     * <p>
     * An toàn để BFS không giới hạn độ sâu: identifier/unreserved_keyword/
     * col_name_keyword/plsql_unreserved_keyword đều là danh sách PHẲNG "TOKEN1 | TOKEN2 |
     * ..." (không đệ quy gọi ngược lại colid, không có phần đuôi sau khi khớp 1 token) -
     * nên không cần cơ chế "return address" như computeFollowSets, chỉ cần lần theo mọi
     * RuleTransition + epsilon rồi gom nhãn token là đủ, không sợ vòng lặp vô hạn.
     */
    private static final Set<Integer> COLID_TERMINAL_TOKENS = computeColidTerminalTokens();

    private static Set<Integer> computeColidTerminalTokens() {
        Set<Integer> result = new HashSet<>();

        // CHỈ các token thực sự có thể là colid trong PostgreSQL
        // Đây là các token mà lexer trả về khi gặp identifier (không phải keyword)

        // 1. Identifier thông thường (không phải từ khóa)
        result.add(PostgreSQLParser.Identifier); // 562

        // 2. Identifier trong dấu ngoặc kép
        result.add(PostgreSQLParser.QuotedIdentifier); // 563

        // 3. Identifier Unicode trong dấu ngoặc kép
        result.add(PostgreSQLParser.UnicodeQuotedIdentifier); // 567

        // 4. PL/pgSQL identifiers
        result.add(PostgreSQLParser.PLSQLVARIABLENAME); // 590
        result.add(PostgreSQLParser.PLSQLIDENTIFIER); // 591

        // 5. Một số từ khóa không bị ràng buộc mà PostgreSQL cho phép làm colid
        // Ví dụ: "table" có thể là tên cột trong một số ngữ cảnh
        // Nhưng KHÔNG phải tất cả từ khóa!

        return result;
    }


//    private static Set<Integer> computeColidTerminalTokens() {
//        Set<Integer> result = new HashSet<>();
//        Set<ATNState> visited = new HashSet<>();
//        Deque<ATNState> queue = new ArrayDeque<>();
//        queue.push(PostgreSQLParser._ATN.ruleToStartState[PostgreSQLParser.RULE_colid]);
//
//        while (!queue.isEmpty()) {
//            ATNState s = queue.pop();
//            if (!visited.add(s)) continue;
//
//            for (Transition t : s.getTransitions()) {
//                if (t instanceof RuleTransition rt) {
//                    // Lặn vào rule con (identifier/unreserved_keyword/...) để gom nhãn của
//                    // NÓ - không cần push rt.followState vì các rule này không có phần
//                    // "tiếp diễn" nào sau khi khớp xong (xem javadoc field ở trên).
//                    queue.push(rt.target);
//                } else if (t.isEpsilon()) {
//                    queue.push(t.target);
//                } else {
//                    IntervalSet label = t.label();
//                    if (label != null) {
//                        result.addAll(label.toList());
//                    }
//                    // KHÔNG push t.target tiếp - các rule này match ĐÚNG 1 token rồi dừng.
//                }
//            }
//        }
//        return result;
//    }

    static boolean shouldBlockIdentifierContinuation(boolean identifierClosedByGap, Set<String> matchedRuleNames) {
        if (!identifierClosedByGap) {
            return false;
        }
        return matchedRuleNames.contains("qualified_name")
                || matchedRuleNames.contains("any_name")
                || matchedRuleNames.contains("columnref")
                || matchedRuleNames.contains("colid");
    }

    static boolean isIdentifierClosedByGap(String sql, int cursorOffset) {
        if (cursorOffset <= 0 || cursorOffset > sql.length()) return false;
        if (!Character.isWhitespace(sql.charAt(cursorOffset - 1))) return false;

        var lexer = new PostgreSQLLexer(CharStreams.fromString(sql));
        var ts = new CommonTokenStream(lexer);
        ts.fill();
        Token lastReal = null;
        for (Token t : ts.getTokens()) {
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (t.getType() == Token.EOF) break;
            if (t.getStopIndex() < cursorOffset) lastReal = t;
            else break;
        }
        if (lastReal == null) return false;
        // Chặn KHI VÀ CHỈ KHI token cuối CÓ THỂ là 1 colid (tức nó chính là tên vừa gõ
        // xong) - ngược lại (FROM/JOIN/WHERE/COMMA/DOT... toàn là reserved keyword hoặc
        // dấu câu, không nằm trong colid) nghĩa là đang đứng ở điểm BẮT ĐẦU 1 định danh
        // mới, không chặn.
        return COLID_TERMINAL_TOKENS.contains(lastReal.getType());
    }
}