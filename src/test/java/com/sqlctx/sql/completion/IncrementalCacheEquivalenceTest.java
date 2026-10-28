package com.sqlctx.sql.completion;

import com.sqlctx.sql.completion.model.CandidatesResult;
import com.sqlctx.sql.completion.syntactic.oracle.OracleSyntacticAnalyzer;
import com.sqlctx.sql.completion.syntactic.postgresql.PostgresSyntacticAnalyzer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cache của engine (IncrementalExitCache giữ kết quả rule con qua các lần gõ, CandidatesCache giữ cả kết quả) không
 * được làm đổi gợi ý: giả lập gõ từng câu trong queries.txt, tại mỗi ranh giới token so kết quả đường có cache với kết
 * quả tính lại từ đầu.
 */
class IncrementalCacheEquivalenceTest {

    @Test
    void cachedResultsEqualFreshResultsWhileTyping() throws Exception {
        List<String> queries = Files.readAllLines(Path.of("tools/intellij-compare/queries.txt")).stream()
                .filter(l -> !l.isBlank() && !l.strip().startsWith("#"))
                .map(l -> l.replace("|", ""))
                .toList();
        long saved = CompletionBudget.millis;
        CompletionBudget.millis = 10_000; // không để hết giờ làm 2 bên khác nhau
        try {
            int compared = 0;
            for (String q : queries) {
                for (int cursor : typingPoints(q)) {
                    String typed = q.substring(0, cursor);
                    assertEquals(describe(PostgresSyntacticAnalyzer.analyzeWithoutCaches(typed, cursor)),
                            describe(PostgresSyntacticAnalyzer.analyze(typed, cursor).candidates()), "postgres: " + typed + "|");
                    assertEquals(describe(OracleSyntacticAnalyzer.analyzeWithoutCaches(typed, cursor)),
                            describe(OracleSyntacticAnalyzer.analyze(typed, cursor).candidates()), "oracle: " + typed + "|");
                    compared++;
                }
            }
            org.junit.jupiter.api.Assertions.assertTrue(compared > 1000, "chỉ so được " + compared + " vị trí");
        } finally {
            CompletionBudget.millis = saved;
        }
    }

    /** Sau mỗi dấu cách/dấu câu và giữa từ - như người gõ dần từ trái sang phải. */
    private static List<Integer> typingPoints(String q) {
        List<Integer> points = new ArrayList<>();
        for (int i = 1; i <= q.length(); i++) {
            char c = q.charAt(i - 1);
            if (!Character.isLetterOrDigit(c) || i % 3 == 0) {
                points.add(i);
            }
        }
        return points;
    }

    private static String describe(CandidatesResult r) {
        Map<Integer, List<Integer>> tokens = new TreeMap<>(r.tokens);
        return "tokens=" + tokens + " rules=" + new TreeMap<>(r.rules) + " caretTypes=" + new java.util.TreeSet<>(r.caretTokenTypes);
    }
}
