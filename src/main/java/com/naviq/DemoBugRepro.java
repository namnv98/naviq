package com.naviq;

import com.naviq.antlr4.postgresql.Demo4Lexer;
import com.naviq.antlr4.postgresql.Demo4Parser;
import com.naviq.antlr4.postgresql.Demo5Lexer;
import com.naviq.antlr4.postgresql.Demo5Parser;
import com.naviq.completion.syntactic.CompletionEngineDefault;
import com.naviq.completion.syntactic.model.CandidatesResult;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;

import java.util.HashMap;
import java.util.Map;

public class DemoBugRepro {
    public static void main(String[] args) throws Exception {
        runDemo4();
        System.out.println();
        runDemo5();
    }

    // ── Test 1: lồng 3 tầng — ge -> ide -> rid, cả 3 preferred ─────────────
    private static void runDemo4() throws Exception {
        String sql = "XY"; // PREFIX='X', TOK1='Y'
        int caretTokenIndex = 2; // caret ngay sau 'Y'

        var input = CharStreams.fromString(sql);
        var lexer = new Demo4Lexer(input);
        var tokens = new CommonTokenStream(lexer);
        var parser = new Demo4Parser(tokens);
        tokens.fill();

        Map<Integer, Boolean> ignored = new HashMap<>();
        Map<Integer, Boolean> preferred = new HashMap<>();
        preferred.put(Demo4Parser.RULE_ge, true);
        preferred.put(Demo4Parser.RULE_ide, true);
        preferred.put(Demo4Parser.RULE_rid, true);

        var engine = new CompletionEngineDefault(parser, ignored, preferred);
        CandidatesResult result = engine.collectCandidates(caretTokenIndex);

        System.out.println("========== DEMO4: lồng 3 tầng (ge -> ide -> rid) ==========");
        printRules(parser, result);
        System.out.println("KỲ VỌNG ĐÚNG: CHỈ có 'ge' (outermost nhất trong 3 tầng).");
        System.out.println("NẾU SAI: thấy 'ide' và/hoặc 'rid' xuất hiện thêm.");
    }

    // ── Test 2: 2 preferred-rule độc lập, cùng chạm 1 vị trí qua 2 nhánh ──
    private static void runDemo5() throws Exception {
        String sql = "XY"; // PREFIX='X', TOK1='Y' — TOK1 khớp CẢ 2 nhánh cùng lúc
        int caretTokenIndex = 2;

        var input = CharStreams.fromString(sql);
        var lexer = new Demo5Lexer(input);
        var tokens = new CommonTokenStream(lexer);
        var parser = new Demo5Parser(tokens);
        tokens.fill();

        Map<Integer, Boolean> ignored = new HashMap<>();
        Map<Integer, Boolean> preferred = new HashMap<>();
        preferred.put(Demo5Parser.RULE_preferredA, true);
        preferred.put(Demo5Parser.RULE_preferredB, true);

        var engine = new CompletionEngineDefault(parser, ignored, preferred);
        CandidatesResult result = engine.collectCandidates(caretTokenIndex);

        System.out.println("========== DEMO5: 2 preferred-rule độc lập, cùng vị trí ==========");
        printRules(parser, result);
        System.out.println("KỲ VỌNG ĐÚNG: CẢ 'preferredA' LẪN 'preferredB' đều xuất hiện.");
        System.out.println("NẾU SAI: chỉ 1 trong 2 xuất hiện (bị lẫn/nuốt nhầm nhau).");
    }

    private static void printRules(Parser parser, CandidatesResult result) {
        if (result.rules.isEmpty()) {
            System.out.println("(KHÔNG có preferred-rule nào được gợi ý)");
            return;
        }
        for (var ruleId : result.rules.keySet()) {
            System.out.println("  " + parser.getRuleNames()[ruleId]);
        }
    }
}