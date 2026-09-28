package com.sqlctx.completion;

import com.sqlctx.completion.support.CompletionFixtures;
import com.sqlctx.completion.suggestion.CompletionHistory;
import com.sqlctx.completion.suggestion.CompletionInputPreparer;
import com.sqlctx.completion.suggestion.postgresql.PostgresSuggestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * KHÔNG phải test - công cụ dump gợi ý của tool cho từng câu trong target/ij-compare/ij-cases.txt ra
 * target/ij-compare/tool-out.tsv, để so với completion của IntelliJ (xem
 * tools/intellij-compare/README.md). Chỉ chạy khi bật cờ:
 * {@code mvn test -Dtest=IntellijCompareDumpTest -DijCompare=true}.
 */
@EnabledIfSystemProperty(named = "ijCompare", matches = "true")
class IntellijCompareDumpTest {

    private static final Path DIR = Path.of("target/ij-compare");

    @Test
    void dumpToolSuggestions() throws Exception {
        var out = new StringBuilder();
        for (String line : Files.readAllLines(DIR.resolve("ij-cases.txt"))) {
            if (line.isBlank()) {
                continue;
            }
            String raw = unescape(line);
            CompletionHistory.resetForTests();
            CompletionFixtures.installPostgres();
            int cursor = raw.indexOf('|');
            String sql = raw.substring(0, cursor) + raw.substring(cursor + 1);
            int i = 0;
            for (var s : PostgresSuggestionService.suggests(CompletionInputPreparer.buildInput(sql, cursor))) {
                out.append(line).append('\t').append(i++).append('\t').append(s.getKey()).append('\t')
                        .append(s.getType().label()).append('\n');
            }
            out.append(line).append("\t-1\t\t\n");
        }
        Files.writeString(DIR.resolve("tool-out.tsv"), out);
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
