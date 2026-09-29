package com.sqlctx.completion;

import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.model.SuggestionType;
import com.sqlctx.completion.suggestion.CompletionHistory;
import com.sqlctx.completion.suggestion.CompletionInputPreparer;
import com.sqlctx.completion.suggestion.postgresql.PostgresSuggestionService;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Chống hồi quy so với IntelliJ Database Tools thật: mỗi câu trong
 * {@code src/test/resources/completion/intellij-golden.tsv} ghi lại NGUYÊN gợi ý của IntelliJ (relation/
 * cột/role/hàm-kiểu/keyword) cho 1 câu SQL (xem tools/intellij-compare/README.md). Test này chạy lại
 * đúng câu đó qua {@link PostgresSuggestionService} thật và fail nếu khác golden - kể cả ở những câu đã
 * biết IntelliJ sai theo Postgres thật (xem README mục "Đọc kết quả"): IntelliJ là mẫu gốc tuyệt đối ở
 * đây, KHÔNG lọc/không tự sửa theo tool - test đỏ là danh sách việc cần làm để tool khớp IntelliJ, không
 * phải bug báo động khẩn. Sinh lại bằng {@code tools/intellij-compare/generate_golden.py}.
 * <p>
 * Schema/role đọc từ ĐÚNG DB Postgres thật mà IntelliJ đã introspect ({@code sqlctx_fixture}, xem
 * {@code tools/intellij-compare/fixture.sql}) - không dùng {@link com.sqlctx.completion.support.CompletionFixtures}
 * (schema tĩnh viết tay, có thể trôi khỏi {@code fixture.sql} mà không ai biết), KHÔNG lọc bớt schema/
 * role gì (DB thật có gì so cái đó, giống hệt phạm vi {@code compare.py} dùng). Cần
 * {@code docker compose up -d postgres} + đã chạy {@code fixture.sql} (xem README bước 1) trước khi
 * chạy test này.
 */
class IntellijGoldenRegressionTest {

    // Khớp tools/intellij-compare/README.md bước 1-2 + docker-compose.yml (service postgres).
    private static final String DB_HOST = "localhost";
    private static final String DB_PORT = "54329";
    private static final String DB_NAME = "sqlctx_fixture";
    private static final String DB_USER = "tester";
    private static final String DB_PASSWORD = "x";

    private record GoldenCase(String sql, int cursor, Set<String> relations, Set<String> columns, Set<String> roles,
                               Set<String> funcs, Set<String> keywords) {
    }

    @BeforeAll
    static void connectRealFixtureDb() {
        System.setProperty("DB_HOST", DB_HOST);
        System.setProperty("DB_PORT", DB_PORT);
        System.setProperty("DB_DBNAME", DB_NAME);
        System.setProperty("DB_USER", DB_USER);
        System.setProperty("DB_PASSWORD", DB_PASSWORD);
        try {
            SchemaIndex.reload(Dialect.POSTGRES);
        } catch (Exception e) {
            fail("Không kết nối được DB fixture thật (" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME + ") - "
                    + "chạy `docker compose up -d postgres` rồi nạp tools/intellij-compare/fixture.sql "
                    + "(xem README bước 1). Lỗi: " + e, e);
        }
        // KHÔNG lọc schema/role gì cả - giữ nguyên TOÀN BỘ những gì DB thật trả về, đúng những gì
        // IntelliJ (introspect cùng DB) cũng thấy. IntelliJ là mẫu gốc để so; việc nó đúng/sai tính
        // sau, không tự ý cắt bớt trước.
    }

    @Test
    void toolMatchesIntellijOnConfirmedCases() throws IOException {
        List<GoldenCase> cases = load(Path.of("src/test/resources/completion/intellij-golden.tsv"));
        assertTrue(cases.size() > 50, "golden file trông quá ít câu (" + cases.size() + "), có sinh thiếu không?");

        List<String> failures = new ArrayList<>();
        for (GoldenCase c : cases) {
            CompletionHistory.resetForTests();
            List<Suggestion> suggestions =
                    PostgresSuggestionService.suggests(CompletionInputPreparer.buildInput(c.sql, c.cursor));

            Set<String> relations = new TreeSet<>();
            Set<String> columns = new TreeSet<>();
            Set<String> roles = new TreeSet<>();
            Set<String> funcs = new TreeSet<>();
            Set<String> keywords = new TreeSet<>();
            for (Suggestion s : suggestions) {
                if (s.getType() == SuggestionType.TABLE || s.getType() == SuggestionType.VIEW
                        || s.getType() == SuggestionType.MATERIALIZED_VIEW) {
                    String key = s.getKey();
                    relations.add(key.substring(key.lastIndexOf('.') + 1));
                } else if (s.getType() == SuggestionType.COLUMN) {
                    columns.add(s.getKey());
                } else if (s.getType() == SuggestionType.ROLE) {
                    roles.add(s.getKey());
                } else if (s.getType() == SuggestionType.DATATYPE) {
                    // Kiểu composite (có dấu chấm, vd "pg_catalog.pg_matviews") = 1 relation thật -> đếm
                    // là relation; kiểu cơ bản (int4, text...) đếm chung nhóm hàm - khớp compare.py.
                    if (s.getKey().contains(".")) {
                        relations.add(s.getKey().substring(s.getKey().lastIndexOf('.') + 1));
                    } else {
                        funcs.add(s.getKey().toLowerCase());
                    }
                } else if (s.getType() == SuggestionType.FUNCTION) {
                    funcs.add(s.getKey().toLowerCase());
                } else if (s.getType() == SuggestionType.KEYWORD) {
                    keywords.add(s.getKey().toLowerCase());
                }
            }

            if (!relations.equals(c.relations) || !columns.equals(c.columns) || !roles.equals(c.roles)
                    || !funcs.equals(c.funcs) || !keywords.equals(c.keywords)) {
                failures.add(describeMismatch(c, relations, columns, roles, funcs, keywords));
            }
        }

        assertTrue(failures.isEmpty(), failures.size() + "/" + cases.size()
                + " câu trôi khỏi gợi ý đã xác nhận khớp IntelliJ:\n\n" + String.join("\n\n", failures));
    }

    private static String describeMismatch(GoldenCase c, Set<String> relations, Set<String> columns,
                                             Set<String> roles, Set<String> funcs, Set<String> keywords) {
        return c.sql.substring(0, c.cursor) + "|" + c.sql.substring(c.cursor)
                + "\n  relation: golden=" + c.relations + " tool=" + relations
                + "\n  cột:      golden=" + c.columns + " tool=" + columns
                + "\n  role:     golden=" + c.roles + " tool=" + roles
                + "\n  hàm/kiểu: chỉ golden=" + diffCount(c.funcs, funcs) + " chỉ tool=" + diffCount(funcs, c.funcs)
                + "\n  keyword:  chỉ golden=" + diffCount(c.keywords, keywords) + " chỉ tool=" + diffCount(keywords, c.keywords);
    }

    private static int diffCount(Set<String> a, Set<String> b) {
        Set<String> d = new TreeSet<>(a);
        d.removeAll(b);
        return d.size();
    }

    private static List<GoldenCase> load(Path path) throws IOException {
        Map<String, Set<String>> relations = new LinkedHashMap<>();
        Map<String, Set<String>> columns = new LinkedHashMap<>();
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        Map<String, Set<String>> funcs = new LinkedHashMap<>();
        Map<String, Set<String>> keywords = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            String key = unescape(parts[0]);
            switch (parts[1]) {
                case "CASE" -> {
                    relations.put(key, new TreeSet<>());
                    columns.put(key, new TreeSet<>());
                    roles.put(key, new TreeSet<>());
                    funcs.put(key, new TreeSet<>());
                    keywords.put(key, new TreeSet<>());
                }
                case "REL" -> relations.get(key).add(parts[2]);
                case "COL" -> columns.get(key).add(parts[2].isEmpty() ? parts[3] : parts[2] + "." + parts[3]);
                case "ROLE" -> roles.get(key).add(parts[2]);
                case "FUNC" -> funcs.get(key).add(parts[2]);
                case "KW" -> keywords.get(key).add(parts[2]);
                default -> throw new IllegalStateException("dòng golden không hiểu: " + line);
            }
        }
        List<GoldenCase> result = new ArrayList<>();
        for (String rawWithCursor : relations.keySet()) {
            int cursor = rawWithCursor.indexOf('|');
            String sql = rawWithCursor.substring(0, cursor) + rawWithCursor.substring(cursor + 1);
            result.add(new GoldenCase(sql, cursor, relations.get(rawWithCursor), columns.get(rawWithCursor),
                    roles.get(rawWithCursor), funcs.get(rawWithCursor), keywords.get(rawWithCursor)));
        }
        return result;
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
