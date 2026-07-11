//package com.naviq.completion.draft;
//
//import com.naviq.antlr4.PostgreSQLLexer;
//import com.naviq.antlr4.PostgreSQLParser;
//import org.antlr.v4.runtime.*;
//import org.antlr.v4.runtime.atn.*;
//import org.antlr.v4.runtime.misc.IntervalSet;
//
//import java.io.IOException;
//import java.io.Writer;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.*;
//
///**
// * Xuất ATN của một parser ANTLR4 ra định dạng Graphviz DOT để render thành ảnh trực quan.
// * <p>
// * Cách render sau khi có file .dot:
// * <ul>
// *   <li>Cài Graphviz rồi chạy: {@code dot -Tsvg columnref.dot -o columnref.svg}</li>
// *   <li>Hoặc dán nội dung file vào một trình xem online (tìm "graphviz online viewer"),
// *       không cần cài gì cả.</li>
// * </ul>
// * <p>
// * LƯU Ý: xuất TOÀN BỘ ATN của một grammar SQL lớn (như PostgreSQL) sẽ ra hàng nghìn
// * node — vẫn xuất được ({@link #exportFullAtn}) nhưng chỉ nên dùng để có cái nhìn tổng
// * quan (zoom/pan trong SVG), không đọc trực tiếp được như ảnh tĩnh. Trong đa số trường
// * hợp debug thực tế, {@link #exportRule} (chỉ 1 rule + các rule nó gọi trực tiếp) hữu
// * ích và dễ đọc hơn nhiều.
// */
//public final class AtnDotExporter {
//    public static void main(String[] args) {
//        String sql = "alter table public.users drop column ";
//        CharStream input = CharStreams.fromString(sql);
//        PostgreSQLLexer lexer = new PostgreSQLLexer(input);
//        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
//        PostgreSQLParser parser = new PostgreSQLParser(tokenStream);
//
//        try {
//            AtnDotExporter.exportRuleGraph(parser, "insertstmt", 5, Path.of("overview.dot"));
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private AtnDotExporter() {
//    }
//
//    /**
//     * Xuất riêng 1 rule cụ thể (theo tên) ra file .dot — lựa chọn được khuyến nghị khi debug.
//     */
//    public static void exportRule(Parser parser, String ruleName, Path outputFile) throws IOException {
//        int ruleIndex = indexOfRule(parser, ruleName);
//        ATN atn = parser.getATN();
//        RuleStartState start = atn.ruleToStartState[ruleIndex];
//        RuleStopState stop = atn.ruleToStopState[ruleIndex];
//
//        try (Writer w = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
//            w.write("digraph " + sanitize(ruleName) + " {\n");
//            w.write("  rankdir=LR;\n");
//            w.write("  node [shape=circle, fontsize=10];\n");
//
//            Set<ATNState> visited = new HashSet<>();
//            Deque<ATNState> queue = new ArrayDeque<>();
//            queue.push(start);
//
//            while (!queue.isEmpty()) {
//                ATNState state = queue.pop();
//                if (!visited.add(state)) {
//                    continue;
//                }
//                writeNode(w, parser, state, state == start, state == stop);
//
//                for (Transition t : state.getTransitions()) {
//                    writeEdge(w, parser, state, t);
//                    // Không "chui vào" bên trong rule con được gọi (RuleTransition) — chỉ vẽ
//                    // rule con đó như 1 node duy nhất (xem writeEdge) để giữ đồ thị gọn, dễ đọc.
//                    if (!(t instanceof RuleTransition) && state.ruleIndex == ruleIndex) {
//                        queue.push(t.target);
//                    }
//                }
//            }
//
//            w.write("}\n");
//        }
//    }
//
//    /**
//     * Xuất TOÀN BỘ ATN (mọi rule) ra 1 file .dot duy nhất.
//     * Cảnh báo: với grammar lớn, file sinh ra có thể rất nặng và khó render/xem trực tiếp.
//     * Cân nhắc dùng {@link #exportRule} cho từng rule quan tâm thay vì gọi hàm này trước.
//     */
//    public static void exportFullAtn(Parser parser, Path outputFile) throws IOException {
//        ATN atn = parser.getATN();
//        try (Writer w = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
//            w.write("digraph FullATN {\n");
//            w.write("  rankdir=LR;\n");
//            w.write("  node [shape=circle, fontsize=8];\n");
//
//            for (ATNState state : atn.states) {
//                if (state == null) continue;
//                writeNode(w, parser, state, state.getStateType() == ATNState.RULE_START,
//                        state.getStateType() == ATNState.RULE_STOP);
//                for (Transition t : state.getTransitions()) {
//                    writeEdge(w, parser, state, t);
//                }
//            }
//
//            w.write("}\n");
//        }
//    }
//
//    /**
//     * Xuất đồ thị quan hệ GIỮA CÁC RULE (1 node = 1 rule, cạnh = "rule A có gọi rule B
//     * hay không") — KHÔNG phải đồ thị mức state như {@link #exportFullAtn}. Nhỏ hơn rất
//     * nhiều (số node = số rule trong grammar, thường vài trăm, thay vì hàng chục nghìn
//     * state), phù hợp để có cái nhìn tổng quan "rule nào gọi rule nào" trước khi dùng
//     * {@link #exportRule} zoom vào rule cụ thể đang cần debug.
//     * <p>
//     * Nếu grammar vẫn quá lớn để xem 1 lần (PostgreSQL có thể hơn 500 rule), dùng thêm
//     * tham số {@code rootRule} để chỉ xuất rule đó và các rule nó gọi tới trong phạm vi
//     * {@code maxDepth} tầng — ví dụ {@code exportRuleGraph(parser, "select_stmt", 3, ...)}.
//     *
//     * @param rootRule tên rule bắt đầu, hoặc {@code null} để xuất TẤT CẢ rule (cẩn thận
//     *                 nếu grammar lớn — vẫn có thể vài trăm node).
//     * @param maxDepth độ sâu tối đa tính từ rootRule (bỏ qua nếu rootRule == null).
//     *                 Truyền số âm (ví dụ {@code -1}) để KHÔNG giới hạn độ sâu — vẫn kết
//     *                 thúc hữu hạn vì mỗi rule chỉ được thăm đúng 1 lần (an toàn với chu
//     *                 trình gọi rule vòng qua lại), nhưng với grammar lớn có thể ra rất
//     *                 nhiều node — cân nhắc kỹ trước khi dùng không giới hạn.
//     */
//    public static void exportRuleGraph(Parser parser, String rootRule, int maxDepth, Path outputFile) throws IOException {
//        ATN atn = parser.getATN();
//        String[] ruleNames = parser.getRuleNames();
//
//        // Tính sẵn: rule nào gọi trực tiếp tới rule nào (dựa trên toàn bộ RuleTransition
//        // xuất hiện bên trong ATN của mỗi rule).
//        Map<Integer, Set<Integer>> callsTo = new HashMap<>();
//        for (int ruleIndex = 0; ruleIndex < ruleNames.length; ruleIndex++) {
//            Set<Integer> callees = new HashSet<>();
//            Set<ATNState> visited = new HashSet<>();
//            Deque<ATNState> queue = new ArrayDeque<>();
//            queue.push(atn.ruleToStartState[ruleIndex]);
//            while (!queue.isEmpty()) {
//                ATNState s = queue.pop();
//                if (!visited.add(s) || s.ruleIndex != ruleIndex) {
//                    continue; // không lan sang state của rule khác trong lúc quét rule này
//                }
//                for (Transition t : s.getTransitions()) {
//                    if (t instanceof RuleTransition rt) {
//                        callees.add(rt.target.ruleIndex);
//                        queue.push(rt.followState); // tiếp tục sau điểm gọi, không "chui vào" rule con
//                    } else {
//                        queue.push(t.target);
//                    }
//                }
//            }
//            callsTo.put(ruleIndex, callees);
//        }
//
//        Set<Integer> included;
//        if (rootRule == null) {
//            included = new HashSet<>();
//            for (int i = 0; i < ruleNames.length; i++) included.add(i);
//        } else {
//            int rootIndex = indexOfRule(parser, rootRule);
//            included = new HashSet<>();
//            Deque<int[]> queue = new ArrayDeque<>(); // {ruleIndex, depth}
//            queue.push(new int[]{rootIndex, 0});
//            while (!queue.isEmpty()) {
//                int[] cur = queue.pop();
//                // maxDepth < 0 nghĩa là KHÔNG giới hạn độ sâu — đi tới khi hết rule liên quan
//                // (vẫn kết thúc hữu hạn vì included.add() chặn việc thăm lại rule đã thăm,
//                // kể cả khi có chu trình gọi rule vòng qua lại).
//                boolean depthExceeded = maxDepth >= 0 && cur[1] >= maxDepth;
//                if (!included.add(cur[0]) || depthExceeded) {
//                    continue;
//                }
//                for (int callee : callsTo.getOrDefault(cur[0], Collections.emptySet())) {
//                    queue.push(new int[]{callee, cur[1] + 1});
//                }
//            }
//        }
//
//        try (Writer w = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
//            w.write("digraph RuleGraph {\n");
//            w.write("  rankdir=LR;\n");
//            w.write("  node [shape=box, fontsize=11, style=filled, fillcolor=lightyellow];\n");
//            for (int ruleIndex : included) {
//                w.write("  " + sanitize(ruleNames[ruleIndex]) + ";\n");
//            }
//            for (int ruleIndex : included) {
//                for (int callee : callsTo.getOrDefault(ruleIndex, Collections.emptySet())) {
//                    if (included.contains(callee)) {
//                        w.write("  " + sanitize(ruleNames[ruleIndex]) + " -> " + sanitize(ruleNames[callee]) + ";\n");
//                    }
//                }
//            }
//            w.write("}\n");
//        }
//    }
//
//    // ── Helpers ───────────────────────────────────────────────────────────────
//
//    private static void writeNode(Writer w, Parser parser, ATNState state, boolean isStart, boolean isStop) throws IOException {
//        String label = state.stateNumber + "\\n" + parser.getRuleNames()[state.ruleIndex];
//        String shape = isStart ? "doublecircle" : isStop ? "doubleoctagon" : "circle";
//        String color = isStart ? "green" : isStop ? "red" : "black";
//        w.write("  s" + state.stateNumber
//                + " [label=\"" + label + "\", shape=" + shape + ", color=" + color + "];\n");
//    }
//
//    private static void writeEdge(Writer w, Parser parser, ATNState from, Transition t) throws IOException {
//        String edgeLabel;
//        if (t instanceof RuleTransition rt) {
//            // Vẽ rule con như 1 "hộp đen" duy nhất thay vì nối tới state bên trong nó,
//            // giúp đồ thị của rule đang xem không bị phình to bởi toàn bộ rule con.
//            String calledRule = parser.getRuleNames()[rt.target.ruleIndex];
//            w.write("  s" + from.stateNumber + " -> callee_" + sanitize(calledRule)
//                    + " [label=\"call " + calledRule + "\", style=dashed];\n");
//            w.write("  callee_" + sanitize(calledRule) + " [label=\"" + calledRule + "()\", shape=box, style=filled, fillcolor=lightyellow];\n");
//            return;
//        }
//
//        edgeLabel = describeLabel(parser, t);
//        w.write("  s" + from.stateNumber + " -> s" + t.target.stateNumber
//                + " [label=\"" + edgeLabel + "\"];\n");
//    }
//
//    private static String describeLabel(Parser parser, Transition t) {
//        if (t.isEpsilon()) {
//            return "ε";
//        }
//        if (t instanceof WildcardTransition) {
//            return "ANY";
//        }
//        IntervalSet label = t.label();
//        if (label == null || label.size() == 0) {
//            return "?";
//        }
//        Vocabulary vocab = parser.getVocabulary();
//        List<Integer> symbols = label.toList();
//        if (symbols.size() > 3) {
//            return vocab.getDisplayName(symbols.get(0)) + " .. " + vocab.getDisplayName(symbols.get(symbols.size() - 1));
//        }
//        StringBuilder sb = new StringBuilder();
//        for (int sym : symbols) {
//            if (sb.length() > 0) sb.append(", ");
//            sb.append(vocab.getDisplayName(sym));
//        }
//        return sb.toString();
//    }
//
//    private static int indexOfRule(Parser parser, String ruleName) {
//        String[] names = parser.getRuleNames();
//        for (int i = 0; i < names.length; i++) {
//            if (names[i].equals(ruleName)) return i;
//        }
//        throw new IllegalArgumentException("Rule not found: " + ruleName);
//    }
//
//    private static String sanitize(String s) {
//        return s.replaceAll("[^a-zA-Z0-9_]", "_");
//    }
//}