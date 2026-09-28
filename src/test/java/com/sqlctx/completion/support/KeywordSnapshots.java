package com.sqlctx.completion.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Snapshot tập keyword CHÍNH XÁC của từng câu SQL test, 1 file / dialect
 * ({@code src/test/resources/completion/keywords-<dialect>.txt}).
 * <p>
 * Keyword do grammar ANTLR sinh ra (30-100 keyword / vị trí) - không có nguồn độc lập nào để tự
 * suy ra tập "đúng" như cột/bảng, nên dùng snapshot: bất kỳ keyword thừa/thiếu nào so với lần
 * được review gần nhất đều làm test fail. Thay đổi có chủ đích -> ghi lại bằng
 * {@code mvn test -Dsnapshot.update=true} rồi REVIEW diff của file snapshot trong git.
 * <p>
 * Định dạng: dòng SQL (escape "\n" và "\\"), dòng kế tiếp thụt 2 space là danh sách keyword đã
 * sort, cách nhau ", " ("(none)" nếu rỗng).
 */
final class KeywordSnapshots {

    static final boolean UPDATE = Boolean.getBoolean("snapshot.update");
    private static final String NONE = "(none)";
    private static final Map<String, KeywordSnapshots> BY_DIALECT = new HashMap<>();

    private final Path file;
    private final TreeMap<String, List<String>> entries = new TreeMap<>();

    private KeywordSnapshots(Path file) {
        this.file = file;
        load();
    }

    static synchronized KeywordSnapshots of(String dialect) {
        return BY_DIALECT.computeIfAbsent(dialect,
                d -> new KeywordSnapshots(Path.of("src/test/resources/completion/keywords-" + d + ".txt")));
    }

    /** null nếu chưa có snapshot cho câu này. */
    synchronized List<String> get(String sqlWithCursor) {
        return entries.get(sqlWithCursor);
    }

    synchronized void put(String sqlWithCursor, List<String> sortedKeywords) {
        if (sortedKeywords.equals(entries.get(sqlWithCursor))) {
            return;
        }
        entries.put(sqlWithCursor, List.copyOf(sortedKeywords));
        save();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            String sql = null;
            for (String line : lines) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                if (line.startsWith("  ")) {
                    String body = line.substring(2);
                    entries.put(sql, body.equals(NONE) ? List.of() : List.of(body.split(", ", -1)));
                } else {
                    sql = unescape(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void save() {
        List<String> out = new ArrayList<>();
        out.add("# Snapshot keyword gợi ý cho từng câu SQL test (" + file.getFileName() + ").");
        out.add("# KHÔNG sửa tay - ghi lại bằng: mvn test -Dsnapshot.update=true, rồi review diff.");
        entries.forEach((sql, kws) -> {
            out.add(escape(sql));
            out.add("  " + (kws.isEmpty() ? NONE : String.join(", ", kws)));
        });
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n");
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
