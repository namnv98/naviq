package com.naviq;

import com.naviq.antlr4.postgresql.*;
import com.naviq.completion.syntactic.engine.CompletionEngineDefault;
import com.naviq.completion.syntactic.engine.model.CandidatesResult;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;

import java.util.HashMap;
import java.util.Map;

public class DemoBugRepro {

    public static void main(String[] args) throws Exception {
        runQualifiedNameAmbiguity();
    }

    // ── Test: "1 từ khớp nhiều cửa cùng lúc" (mục cuối ATN_ROOM_DOOR_ANALOGY.md) ──
    // qualified_name : IDENTIFIER (DOT IDENTIFIER)? ;
    // Gõ xong "public" (1 Identifier), engine đang sống song song ở CẢ 2 nhánh:
    //   - "public" là TÊN BẢNG, qualified_name coi như xong -> gợi ý tiếp = WHERE, EOF
    //   - "public" là TÊN SCHEMA, đang chờ ".tên_bảng"          -> gợi ý tiếp = DOT
    // Kỳ vọng đúng: cả 3 token (WHERE, EOF, DOT) cùng xuất hiện trong 1 lần gọi
    // collectCandidates() — không phải 1 nhánh "thắng" rồi loại nhánh kia.
    private static void runQualifiedNameAmbiguity() throws Exception {
        String sql = "SELECT name FROM public";
        // Token: SELECT(0) name(1) FROM(2) public(3) EOF(4) -> caret ngay sau "public"
        int caretTokenIndex = 4;

        var input = CharStreams.fromString(sql);
        var lexer = new DemoLexer(input);
        var tokens = new CommonTokenStream(lexer);
        var parser = new DemoParser(tokens);
        tokens.fill();

        Map<Integer, Boolean> ignored = new HashMap<>();
        Map<Integer, Boolean> preferred = new HashMap<>(); // không cần VIP cho test này

        var engine = new CompletionEngineDefault(parser, ignored, preferred);
        CandidatesResult result = engine.collectCandidates(caretTokenIndex);

        System.out.println("========== TEST: qualified_name — 1 từ khớp nhiều cửa cùng lúc ==========");
        System.out.println("Input: \"" + sql + "\", caret ngay sau \"public\"");
        printTokens(parser, result);
        System.out.println("KỲ VỌNG ĐÚNG: thấy CẢ 3 — DOT, WHERE, EOF.");
        System.out.println("  - DOT   : nhánh coi \"public\" là tên schema, đang chờ \".tên_bảng\"");
        System.out.println("  - WHERE : nhánh coi \"public\" là tên bảng, qualified_name đã xong, có thể thêm WHERE");
        System.out.println("  - EOF   : nhánh coi \"public\" là tên bảng, câu kết thúc luôn tại đây");
        System.out.println("NẾU SAI: chỉ thấy 1 hoặc 2 trong 3 token trên -> 1 nhánh bị chặn nhầm.");
    }

    private static void printTokens(Parser parser, CandidatesResult result) {
        if (result.tokens.isEmpty()) {
            System.out.println("(KHÔNG có token nào được gợi ý)");
            return;
        }
        for (var tokenType : result.tokens.keySet()) {
            System.out.println("  " + parser.getVocabulary().getDisplayName(tokenType));
        }
    }
}