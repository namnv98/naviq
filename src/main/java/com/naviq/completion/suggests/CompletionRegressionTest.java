package com.naviq.completion.suggests;

import com.naviq.antlr4.PostgreSQLParser;
import com.naviq.completion.syntactic.v1.RuleCallStack;
import com.naviq.completion.syntactic.SyntacticAnalyzer;

import java.util.*;

/**
 * Bộ test hồi quy - chạy toàn bộ case đã tích luỹ trong quá trình debug KeywordNoiseFilter cùng
 * lúc, để đảm bảo 1 fix mới không âm thầm phá vỡ 1 case cũ đã từng đúng (đây chính xác là điều đã
 * xảy ra nhiều lần khi test rời rạc từng case một trong quá trình phát triển).
 * <p>
 * Mỗi case khai báo: sql, cursorOffset, rule cần PHẢI CÓ mặt trong kết quả cuối (expectPresent),
 * rule cần PHẢI VẮNG MẶT (expectAbsent). Không cần assertion framework - in PASS/FAIL rõ ràng, exit
 * code khác 0 nếu có case chính (CASES) fail, để dễ tích hợp CI sau này.
 * <p>
 * LƯU Ý: {@code CASES} chỉ bao phủ tầng syntactic (AntlrCompletionEngine + KeywordNoiseFilter). Còn
 * 2 nhóm bug KHÁC đã biết, nằm ở tầng SemanticAnalyzer, được theo dõi RIÊNG trong
 * {@code KNOWN_FAILING} (không ảnh hưởng exit code / verdict tổng, chỉ để cảnh báo khi nào chúng tự
 * nhiên pass trở lại - lúc đó nên "tốt nghiệp" chuyển case đó sang CASES chính thức): - Nhóm A:
 * UPDATE...SET|, INSERT...ON CONFLICT...DO UPDATE SET|, ALTER TABLE...DROP COLUMN| - không gợi ý
 * cột vì SemanticAnalyzer chỉ lấy alias/table từ from_list, không xử lý bảng đích của các câu lệnh
 * này. - Nhóm B: PARTITION BY|/ORDER BY| bên trong window function khi bảng FROM nằm SAU vị trí
 * caret (forward-reference) - SemanticAnalyzer có vẻ chỉ quét scope tới caret, không quét toàn
 * câu.
 */
public class CompletionRegressionTest {

    private record Case(String description, String sql, int cursorOffset, List<String> expectPresent, List<String> expectAbsent) {

        Case(String description, String sql, List<String> expectPresent, List<String> expectAbsent) {
            this(description, sql, sql.length(), expectPresent, expectAbsent);
        }
    }

    // ── Bộ test chính - PHẢI pass, ảnh hưởng exit code ──────────────────────
    private static final List<Case> CASES = List.of(
        new Case(
            "JOIN ... USING (|) - colid (tên cột chung 2 bảng) phải match, "
                + "ancestor join_qual phải xuất hiện đâu đó trong path (không phải immediate "
                + "parent vì name/name_list dùng chung ở rất nhiều ngữ cảnh khác)",
            "select * from public.users u join public.orders o using (",
            List.of("colid"),
            List.of("qualified_name")
            // context-check cần dùng isRuleAncestorAnywhere thay vì isRuleInContext -
            // Case/ContextCheck hiện tại chưa hỗ trợ kiểu check này, cần mở rộng thêm
            // nếu muốn assert tự động; tạm thời xác nhận bằng mắt qua log "colid ancestor path"
        )
    );

    private static boolean isRuleInContext(SyntacticAnalyzer.Result syn, int ruleId, int expectedParentRuleId) {
        List<RuleCallStack.RuleFrame> path = syn.candidates().rules.get(ruleId);
        if (path == null || path.isEmpty()) return false;
        // Frame CUỐI CÙNG trong path (ancestor gần nhất) chính là rule cha
        // trực tiếp đã dẫn tới rule này - đây là thứ quyết định ngữ nghĩa.
        int immediateParent = path.get(path.size() - 1).ruleId();
        return immediateParent == expectedParentRuleId;
    }

    public static void main(String[] args) {
        int failCount = 0;
        for (Case c : CASES) {
            failCount += runCase(c);
        }

        System.out.println("\n========================================");
        if (failCount == 0) {
            System.out.println("TẤT CẢ " + CASES.size() + " CASE PASS.");
        } else {
            System.out.println("CÓ " + failCount + " CASE FAIL / " + CASES.size() + " tổng số.");
        }

        if (failCount != 0) {
            System.exit(1);
        }
    }
    private static boolean isRuleAncestorAnywhere(SyntacticAnalyzer.Result syn, int ruleId, int ancestorRuleIdToFind) {
        List<RuleCallStack.RuleFrame> path = syn.candidates().rules.get(ruleId);
        if (path == null) return false;
        return path.stream().anyMatch(f -> f.ruleId() == ancestorRuleIdToFind);
    }
    private static int runCase(Case c) {
        System.out.println("\n---- " + c.description() + " ----");
        System.out.println("sql = \"" + c.sql() + "\"  cursorOffset=" + c.cursorOffset());

        var syntacticResults = SyntacticAnalyzer.analyze(c.sql(), c.cursorOffset());

        boolean isColidAlias = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_relation_expr_opt_alias);          // DELETE FROM ... (colid = alias)
        boolean isColidDropTarget = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_alter_table_cmd);                   // ALTER TABLE ... DROP COLUMN (colid = tên cột bị xoá)
        boolean isColumnrefColumn = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_columnref);                         // WHERE u.| (colid bên trong columnref)
        boolean isColidIndexColumn = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_index_elem); // CREATE INDEX ... (col) (colid = cột lập index)
        boolean isColidSetTarget = isRuleInContext(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_set_target); // UPDATE SET / INSERT ON CONFLICT DO UPDATE SET (colid = assignment-target)

        boolean isColidUsingClauseColumn = isRuleAncestorAnywhere(syntacticResults, PostgreSQLParser.RULE_colid, PostgreSQLParser.RULE_join_qual); // JOIN ... USING (col1, col2) (colid = cột chung 2 bảng)

        System.out.println("isColidAlias: "+isColidAlias);
        System.out.println("isColidDropTarget: "+isColidDropTarget);
        System.out.println("isColumnrefColumn: "+isColumnrefColumn);
        System.out.println("isColidIndexColumn: "+isColidIndexColumn);
        System.out.println("isColidSetTarget: "+isColidSetTarget);
        System.out.println("isColidUsingClauseColumn: "+isColidUsingClauseColumn);

        System.out.println("Raw rules matched: " + syntacticResults.candidates().rules.keySet().stream().map(id -> PostgreSQLParser.ruleNames[id]).toList());

        var path = syntacticResults.candidates().rules.get(PostgreSQLParser.RULE_colid);
        System.out.println("colid ancestor path: " + path.stream()
            .map(f -> PostgreSQLParser.ruleNames[f.ruleId()]).toList());

        Set<String> matchedRuleNames = KeywordNoiseFilter.computeMatchedRuleNames(syntacticResults, c.cursorOffset());
        System.out.println("Final matchedRuleNames = " + matchedRuleNames);

        boolean pass = true;
        for (String expected : c.expectPresent()) {
            if (!matchedRuleNames.contains(expected)) {
                System.out.println("  [FAIL] Thiếu rule bắt buộc phải có: " + expected);
                pass = false;
            }
        }
        for (String forbidden : c.expectAbsent()) {
            if (matchedRuleNames.contains(forbidden)) {
                System.out.println("  [FAIL] Có rule đáng lẽ phải bị suppress: " + forbidden);
                pass = false;
            }
        }

        System.out.println(pass ? "  [PASS]" : "  [FAIL]");
        return pass ? 0 : 1;
    }
}