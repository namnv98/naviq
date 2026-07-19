package com.naviq;

import com.naviq.antlr4.postgresql.*;
import com.naviq.completion.syntactic.PostgreSQLSyntacticAnalyzer;
import com.naviq.completion.syntactic.engine.CompletionEngineDefault;
import com.naviq.completion.syntactic.engine.model.CandidatesResult;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Chạy: java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp antlr.jar:out com.naviq.LogMazeZoomGraph
 * <p>
 * Bộ test case cho RuleResyncSkipper (3 lớp: rule-boundary follow-set,
 * literal-đúng-label, literal-follow-set-của-target). Mỗi case ghi rõ kỳ
 * vọng — SAI so với kỳ vọng nghĩa là fallback đang gây hại (như case
 * "thiếu WHERE + token thừa" đã phát hiện: gợi ý bị nhiễu chéo, lẫn cả
 * 'FROM' dù đã khớp đúng trước đó).
 */
public class LogMazeZoomGraph {

    public static void main(String[] args) throws Exception {
//        // ── Nhóm 1: sạch, không lỗi — luôn phải đúng, dùng làm baseline ──
//        run("SELECT * FROM orders WHERE ",
//                "sạch, không lỗi",
//                "'*' , IDENTIFIER (đang chờ columnref bên trái bool_expr)");
//
//        run("SELECT name FROM orders ",
//                "sạch, không lỗi, không có WHERE",
//                "EOF, WHERE, và các cửa optional khác hợp lệ ngay sau qualified_name");

//        run("SELECT name, ",
//                "sạch, đang gõ dở list cột (dấu phẩy)",
//                "'*' , IDENTIFIER (đang chờ columnref tiếp theo sau dấu phẩy)");

        // ── Nhóm 2: thiếu ĐÚNG 1 từ khoá, KHÔNG có token thừa gây nhiễu ──
        run("select * from1  ",
                "thiếu FROM, đúng 1 chỗ lỗi",
                "'*' , IDENTIFIER — nếu đúng, fallback cứu được sạch sẽ");
//
//        run("name FROM orders WHERE ",
//                "thiếu SELECT ở đầu câu, đúng 1 chỗ lỗi",
//                "'*' , IDENTIFIER — coi 'name' là columnref, SELECT bị bỏ qua");
//
//        run("SELECT * from1 WHERE ",
//                "lỗi kép liền nhau: thiếu cả FROM lẫn tên bảng",
//                "'*' , IDENTIFIER — 2 lỗi liền nhau vẫn nên cứu được nếu logic đúng");
//
//        // ── Nhóm 3: NGHI VẤN — thiếu từ khoá NHƯNG có token THỪA/LẠC phía sau ──
//        // Đây là nhóm đã phát hiện bug: token thừa "id" khớp nhầm vai trò ở
//        // NHIỀU vị trí khác nhau trong ATN cùng lúc, gây nhiễu chéo.
//        run("SELECT * FROM orders id ",
//                "thiếu WHERE, dư 1 identifier lạc phía sau (ĐÃ PHÁT HIỆN BUG)",
//                "KHÔNG rõ — nhưng chắc chắn KHÔNG được gợi ý lại 'FROM' (đã khớp trước đó rồi)");
//
//        run("SELECT * FROM orders extra1 extra2 ",
//                "thiếu WHERE, dư 2 token lạc liên tiếp",
//                "tương tự trên — kiểm tra xem nhiễu có nhân lên theo số token thừa không");
//
//        run("SELECT name garbage FROM orders WHERE ",
//                "token lạc nằm GIỮA (không phải cuối câu)",
//                "kiểm tra nhiễu chéo có xảy ra cả khi lỗi không nằm ở cuối");
//
//        // ── Nhóm 4: caret rơi NGAY SAU chỗ lỗi (áp lực caret khác vị trí cuối) ──
//        run("SELECT * from1 orders",
//                "thiếu FROM, caret ngay sau 'orders' (chưa gõ WHERE)",
//                "EOF, WHERE và các token hợp lệ khác sau qualified_name");
//
//        // ── Nhóm 5: input quá lỗi, không resync nổi — PHẢI chết đúng cách (rỗng), không được đoán bừa ──
//        run("SELECT SELECT SELECT ",
//                "lỗi nặng, lặp từ khoá vô nghĩa — không nên resync ra được gì hợp lý",
//                "rỗng, hoặc rất hạn chế — KHÔNG được bịa ra gợi ý không liên quan");
//
//        // ── Nhóm 6: input HỢP LỆ phức tạp hơn — kiểm tra kỹ hơn case 2 (đã lộ bug trước đây) ──
//        run("SELECT a, b, c FROM orders WHERE a = ",
//                "sạch, nhiều cột + đang gõ dở giá trị so sánh",
//                "IDENTIFIER, NUMBER, STRING (đang chờ 'value') — KHÔNG được có 'FROM' hay ',' hay bất kỳ token đã qua rồi");
//
//        run("SELECT schema1.orders FROM schema2.customers WHERE ",
//                "sạch, dùng qualified_name có DOT ở CẢ 2 chỗ (columnref không có DOT, nhưng qualified_name có)",
//                "'*' , IDENTIFIER — kiểm tra DOT không làm rối caching giữa 2 lần vào qualified_name-like state");
//
//        run("SELECT a FROM b WHERE a ",
//                "sạch, đang gõ dở NGAY TRƯỚC toán tử so sánh",
//                "'=', NEQ, '<=', '>=', '<', '>' — các toán tử comparison_op, KHÔNG được có gì khác");
//
//        // ── Nhóm 7: case THẬT SỰ đi qua handleRuleDoor (rule con gọi qua
//        // RuleTransition) — chưa từng test được nhánh này! Mọi case "thiếu
//        // FROM" ở trên đều đi qua handlePasswordDoor (đã gỡ), KHÔNG chạm tới
//        // handleRuleDoor. comparison_op là 1 rule con gọi qua RuleTransition
//        // thật (bool_expr: columnref comparison_op value) — gõ sai/thiếu hẳn
//        // toán tử so sánh sẽ làm comparison_op chết toàn bộ, đúng kịch bản
//        // handleRuleDoor cần xử lý.
//        run("SELECT a FROM b WHERE a isnotequalto 5",
//                "SAI hẳn toán tử so sánh (gõ nhầm thành identifier, không phải =,!=,<,>) — case này mới thật sự test handleRuleDoor",
//                "rỗng (an toàn) HOẶC gợi ý đúng nếu resync rule-boundary chạy — xem kỹ Tokens bên dưới");
//
//        run("SELECT a FROM b WHERE a 5",
//                "THIẾU HẲN toán tử so sánh (không gõ gì, nhảy thẳng qua giá trị) — case sạch hơn để test handleRuleDoor",
//                "rỗng nếu không cứu được, hoặc NUMBER/IDENTIFIER/STRING nếu resync rule-boundary cứu đúng — TUYỆT ĐỐI không được gợi ý lại '=' hay các toán tử (vì vị trí đó đã 'qua' rồi nếu resync đúng)");
    }

    private static void run(String sql, String note, String expected) {
        System.out.println("=== SQL: [" + sql + "]");
        System.out.println("    Case: " + note);
        System.out.println("    Kỳ vọng: " + expected);

        var input = CharStreams.fromString(sql);
        var lexer = new PostgreSQLLexer(input);
        var tokens = new CommonTokenStream(lexer);
        tokens.fill();

        System.out.println("    Tokens:");
        for (var t : tokens.getTokens()) {
            if (t.getType() == -1) continue;
            System.out.println("      " + t.getTokenIndex() + ": '" + t.getText() + "' -> "
                    + lexer.getVocabulary().getSymbolicName(t.getType()));
        }

        var parser = new PostgreSQLParser(tokens);
        int caretTokenIndex = tokens.getTokens().size() - 1; // caret ở cuối, ngay trước/tại EOF

        Map<Integer, Boolean> ignored = new HashMap<>();
        Map<Integer, Boolean> preferred = new HashMap<>();
        var engine = new CompletionEngineDefault(parser, PostgreSQLSyntacticAnalyzer.buildIgnoredTokens(), PostgreSQLSyntacticAnalyzer.buildPreferredRules());
        CandidatesResult result = engine.collectCandidates(caretTokenIndex);
        printSuggestions(parser, result);
        System.out.println();
    }

    private static Set<String> allRuleNames(Set<Integer> ruleIds) {
        Set<String> result = new HashSet<>();
        for (Integer ruleIndex : ruleIds) {
            result.add(PostgreSQLParser.ruleNames[ruleIndex]);
        }
        return result;
    }

    private static void printSuggestions(Parser parser, CandidatesResult result) {
        var a = allRuleNames(result.rules.keySet());
        if (result.tokens.isEmpty()) {
            System.out.println("    Gợi ý: (RỖNG — không gợi ý gì)");
            return;
        }
        StringBuilder sb = new StringBuilder("    Gợi ý: ");
        boolean first = true;
        for (var tokenType : result.tokens.keySet()) {
            if (!first) sb.append(", ");
            sb.append(parser.getVocabulary().getDisplayName(tokenType));
            first = false;
        }
        System.out.println(sb);
    }
}