package com.sqlctx.completion;

import com.sqlctx.completion.suggestion.CompletionHistory;
import com.sqlctx.completion.suggestion.CompletionInputPreparer;
import com.sqlctx.completion.suggestion.postgresql.PostgresSuggestionService;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * KHÔNG phải test - công cụ so completion của tool với IntelliJ (xem tools/intellij-compare/README.md).
 * <p>
 * Đọc danh sách câu SQL từ tools/intellij-compare/queries.txt (cùng file ij-completion-dump.groovy đọc
 * trong IntelliJ), lấy gợi ý của IntelliJ cho từng câu từ target/ij-compare/ij-out.tsv, chạy tool trên
 * từng câu, ghi target/ij-compare/compare.txt: với mỗi câu, in đủ 4 dòng - toàn bộ gợi ý thật của tool,
 * toàn bộ gợi ý thật của IntelliJ, rồi mới đến 2 dòng "tool THỪA"/"tool THIẾU" so với IntelliJ (phần
 * lệch nhau) - tránh đọc nhầm "tool THỪA (0)" thành "tool không gợi ý gì" (chỉ là không có gì thừa so
 * với IntelliJ, tool vẫn gợi ý bình thường - xem dòng "tool (...)" ở trên để biết chính xác). So theo
 * tên (phần sau dấu chấm cuối, không phân biệt hoa/thường) - tool viết "public.users"/"users.id",
 * IntelliJ viết "users"/"id".
 * <p>
 * Schema đọc từ đúng DB {@code sqlctx_fixture} mà IntelliJ introspect. Chỉ chạy khi bật cờ:
 * {@code mvn test -Dtest=IntellijCompareTest -DijCompare=true}.
 */
@EnabledIfSystemProperty(named = "ijCompare", matches = "true")
class IntellijCompareTest {

    // KHÔNG dùng target/ - Maven/IntelliJ rebuild hay xoá sạch target/, mất hết kết quả capture của
    // IntelliJ (~5 phút chạy tay) - xem cùng lý do trong ij-completion-dump.groovy.
    private static final Path DIR = Path.of("tools/intellij-compare/out");
    private static final Path QUERIES = Path.of("tools/intellij-compare/queries.txt");

    @Test
    void compareWithIntellij() throws Exception {
        System.setProperty("DB_HOST", "localhost");
        System.setProperty("DB_PORT", "54329");
        System.setProperty("DB_DBNAME", "sqlctx_fixture");
        System.setProperty("DB_USER", "tester");
        System.setProperty("DB_PASSWORD", "x");
        SchemaIndex.reload(Dialect.POSTGRES);

        List<String> queries = Files.readAllLines(QUERIES).stream()
                .filter(l -> !l.isBlank() && !l.strip().startsWith("#"))
                .toList();
        Map<String, Set<String>> intellij = loadIntellij(DIR.resolve("ij-out.tsv"));
        List<String> report = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int same = 0;
        for (String query : queries) {
            Set<String> ij = intellij.get(query);
            if (ij == null) {
                missing.add(query);
                continue;
            }
            int cursor = query.indexOf('|');
            String sql = query.substring(0, cursor) + query.substring(cursor + 1);
            CompletionHistory.resetForTests();
            Set<String> tool = new TreeSet<>();
            PostgresSuggestionService.suggests(CompletionInputPreparer.buildInput(sql, cursor))
                    .forEach(s -> tool.add(name(s.getKey())));

            Set<String> onlyTool = new TreeSet<>(tool);
            onlyTool.removeAll(ij);
            Set<String> onlyIntellij = new TreeSet<>(ij);
            onlyIntellij.removeAll(tool);
            if (onlyTool.isEmpty() && onlyIntellij.isEmpty()) {
                same++;
                continue;
            }
            report.add("\n=== " + query);
            report.add("tool (" + tool.size() + "): " + String.join(", ", tool));
            report.add("IntelliJ (" + ij.size() + "): " + String.join(", ", ij));
            report.add("tool THỪA so với IntelliJ (" + onlyTool.size() + "): " + String.join(", ", onlyTool));
            report.add("tool THIẾU so với IntelliJ (" + onlyIntellij.size() + "): " + String.join(", ", onlyIntellij));
        }
        int compared = queries.size() - missing.size();
        String header = compared + " câu: giống nhau " + same + ", khác " + (compared - same);
        if (!missing.isEmpty()) {
            header += "\n" + missing.size() + " câu trong queries.txt chưa có kết quả IntelliJ (chạy lại ij-completion-dump.groovy):";
            report.add("\n=== chưa có kết quả IntelliJ ===");
            missing.forEach(q -> report.add(q));
        }
        report.addFirst(header);
        Files.writeString(DIR.resolve("compare.txt"), String.join("\n", report) + "\n");
        System.out.println(header + "\n-> " + DIR.resolve("compare.txt"));
    }

    /** ij-out.tsv: câu (escape) \t thứ tự \t tên gợi ý \t ... ; dòng thứ tự -1 = hết 1 câu (có thể 0 gợi ý). */
    private static Map<String, Set<String>> loadIntellij(Path path) throws Exception {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Thiếu " + path + " - chạy ij-completion-dump.groovy trong IntelliJ trước "
                    + "(tools/intellij-compare/README.md)");
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            String[] parts = line.split("\t", -1);
            Set<String> names = result.computeIfAbsent(unescape(parts[0]), k -> new TreeSet<>());
            if (!parts[1].equals("-1")) {
                names.add(name(parts[2]));
            }
        }
        return result;
    }

    private static String name(String key) {
        return key.substring(key.lastIndexOf('.') + 1).trim().toLowerCase();
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
