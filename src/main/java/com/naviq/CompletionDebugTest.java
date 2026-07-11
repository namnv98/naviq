//package com.naviq;
//
//import com.naviq.antlr4.PostgreSQLLexer;
//import com.naviq.antlr4.PostgreSQLParser;
//import com.naviq.completion.syntactic.AntlrCompletionEngine;
//import com.naviq.completion.syntactic.SyntacticAnalyzer;
//import org.antlr.v4.runtime.CharStreams;
//import org.antlr.v4.runtime.CommonTokenStream;
//import org.antlr.v4.runtime.Token;
//
//import java.util.*;
//
//public class CompletionDebugTest {
//
//    public static void main(String[] args) {
//        String sql = "select * from ";
//        int cursorOffset = sql.length();
//
//        var result = SyntacticAnalyzer.analyze(sql, cursorOffset);
//        var candidates = result.candidates();
//        Map<Integer, List<AntlrCompletionEngine.RuleFrame>> rulesMatched = candidates.rules;
//        Map<Integer, Integer> ruleEntryTokenIndex = candidates.ruleEntryTokenIndex;
//
//        // ── 1. tokenIndex of the last REAL (non-EOF, on-channel) token
//        //    strictly before cursorOffset — this is what isGenuineContinuation
//        //    compares each rule's entry point against.
//        int lastRealTokenIndex = computeLastRealTokenIndex(sql, cursorOffset);
//        System.out.println("lastRealTokenIndex = " + lastRealTokenIndex);
//
//        // ── 2. Raw dump: every matched rule, its ancestor path, and its own
//        //    entry tokenIndex.
//        System.out.println("\n-- Raw matched rules --");
//        for (var e : rulesMatched.entrySet()) {
//            int ruleId = e.getKey();
//            System.out.println(PostgreSQLParser.ruleNames[ruleId]
//                    + "  enteredAt=" + ruleEntryTokenIndex.get(ruleId)
//                    + "  ancestorPath=" + e.getValue().stream()
//                    .map(f -> PostgreSQLParser.ruleNames[f.ruleId()] + "@" + f.tokenIndex())
//                    .toList());
//        }
//
//        // ── 3. Sibling-branch suppression (qualified_name vs table_alias style)
//        Set<Integer> suppressedBySibling = computeSuppressedRules(rulesMatched);
//        System.out.println("\nSuppressed by sibling-branch: "
//                + suppressedBySibling.stream().map(id -> PostgreSQLParser.ruleNames[id]).toList());
//
//        // ── 4. Genuine-continuation suppression (columnref/qualified_name/func_name
//        //    that already consumed lastReal, vs one that starts fresh at caret)
//        Set<Integer> suppressedByContinuation = new HashSet<>();
//        for (int ruleId : rulesMatched.keySet()) {
//            if (isGenuineContinuation(ruleId, lastRealTokenIndex, ruleEntryTokenIndex)) {
//                suppressedByContinuation.add(ruleId);
//            }
//        }
//        System.out.println("Suppressed by genuine-continuation (entered before/at lastReal): "
//                + suppressedByContinuation.stream().map(id -> PostgreSQLParser.ruleNames[id]).toList());
//
//        // ── 5. Final matchedRuleNames after applying both filters
//        Set<Integer> allSuppressed = new HashSet<>();
//        allSuppressed.addAll(suppressedBySibling);
//        allSuppressed.addAll(suppressedByContinuation);
//
//        Set<String> matchedRuleNames = new HashSet<>();
//        for (Integer ruleIndex : rulesMatched.keySet()) {
//            if (allSuppressed.contains(ruleIndex)) continue;
//            matchedRuleNames.add(PostgreSQLParser.ruleNames[ruleIndex]);
//        }
//        System.out.println("\nFinal matchedRuleNames = " + matchedRuleNames);
//    }
//
//    // ── Helpers ──────────────────────────────────────────────────────────────
//
//    /**
//     * Finds the token index (in the raw lexer token stream) of the last
//     * on-channel, non-EOF token whose stop position is strictly before
//     * cursorOffset. This is the token that "genuine continuation" checks are
//     * anchored against — e.g. for "...where u.id ", this resolves to the
//     * token for "id".
//     */
//    private static int computeLastRealTokenIndex(String sql, int cursorOffset) {
//        var lexer = new PostgreSQLLexer(CharStreams.fromString(sql));
//        var ts = new CommonTokenStream(lexer);
//        ts.fill();
//        int lastIndex = -1;
//        for (Token t : ts.getTokens()) {
//            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
//            if (t.getType() == Token.EOF) break;
//            if (t.getStopIndex() < cursorOffset) {
//                lastIndex = t.getTokenIndex();
//            } else {
//                break;
//            }
//        }
//        return lastIndex;
//    }
//
//    /**
//     * True if `ruleId` was entered at or before lastRealTokenIndex — meaning
//     * it has actually consumed the identifier the user just typed (it's a
//     * "genuine continuation" still open only because of some optional tail),
//     * as opposed to a rule that starts fresh exactly at the caret (which
//     * should NOT be suppressed).
//     * <p>
//     * IMPORTANT: {@code AntlrCompletionEngine.RuleFrame.NO_TOKEN} (-1) is a
//     * SENTINEL used when the rule was matched via the static followSets-path
//     * expansion inside collectAtCaret (i.e. the caret is still sitting at an
//     * ENCLOSING rule like table_ref, and qualified_name/func_name are just
//     * reachable-from-here candidates, not something the walk has actually
//     * dived into and consumed real tokens for). -1 must NOT be compared as
//     * "entered before token 0" — that would make EVERY freshly-reachable
//     * candidate look like a "genuine continuation" and get suppressed
//     * unconditionally, which is exactly the bug seen with "select * from  ".
//     * Only a REAL (>= 0) entry tokenIndex counts as genuine continuation.
//     */
//    static boolean isGenuineContinuation(int ruleId, int lastRealTokenIndex,
//                                         Map<Integer, Integer> ruleEntryTokenIndex) {
//        Integer enteredAt = ruleEntryTokenIndex.get(ruleId);
//        if (enteredAt == null) return false;
//        if (enteredAt == AntlrCompletionEngine.RuleFrame.NO_TOKEN) return false; // sentinel, not a real entry
//        if (lastRealTokenIndex < 0) return false; // nothing typed yet, nothing to have consumed
//        return enteredAt <= lastRealTokenIndex;
//    }
//
//    /**
//     * Compares 2 ancestor paths by finding their longest common rule-id
//     * prefix (the branch point), then comparing tokenIndex of the frame
//     * right at that branch point on each side. The branch with the SMALLER
//     * tokenIndex there is considered "stale" (the parser has since moved
//     * further along the other sibling branch), so it gets suppressed.
//     */
//    private static Integer laterBranchWins(
//            List<AntlrCompletionEngine.RuleFrame> pathA,
//            List<AntlrCompletionEngine.RuleFrame> pathB) {
//        int lcp = 0;
//        int minLen = Math.min(pathA.size(), pathB.size());
//        while (lcp < minLen && pathA.get(lcp).ruleId() == pathB.get(lcp).ruleId()) {
//            lcp++;
//        }
//        if (lcp >= pathA.size() || lcp >= pathB.size()) return null;
//        int tokenA = pathA.get(lcp).tokenIndex();
//        int tokenB = pathB.get(lcp).tokenIndex();
//        if (tokenA == tokenB) return null;
//        return tokenA > tokenB ? -1 : 1;
//    }
//
//    private static Set<Integer> computeSuppressedRules(
//            Map<Integer, List<AntlrCompletionEngine.RuleFrame>> rules) {
//        Set<Integer> suppressed = new HashSet<>();
//        List<Map.Entry<Integer, List<AntlrCompletionEngine.RuleFrame>>> entries =
//                new ArrayList<>(rules.entrySet());
//        for (int i = 0; i < entries.size(); i++) {
//            for (int j = i + 1; j < entries.size(); j++) {
//                var a = entries.get(i);
//                var b = entries.get(j);
//                Integer res = laterBranchWins(a.getValue(), b.getValue());
//                if (res == null) continue;
//                if (res < 0) suppressed.add(b.getKey());
//                else suppressed.add(a.getKey());
//            }
//        }
//        return suppressed;
//    }
//}