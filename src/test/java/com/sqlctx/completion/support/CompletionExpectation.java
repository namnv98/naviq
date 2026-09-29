package com.sqlctx.completion.support;

import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.model.SuggestionType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Kỳ vọng CHÍNH XÁC cho toàn bộ danh sách gợi ý tại 1 vị trí con trỏ - không chỉ "có chứa":
 * <ul>
 *   <li>Mỗi loại (cột/bảng/view/hàm/kiểu/role/alias...) được khai báo -> tập thực tế phải BẰNG
 *       ĐÚNG tập khai báo (thừa hay thiếu đều fail).</li>
 *   <li>Loại KHÔNG khai báo -> phải RỖNG (quên khai báo = coi như khẳng định "không có").</li>
 *   <li>Không được có gợi ý trùng (cùng loại + cùng key).</li>
 *   <li>Keyword -> so với snapshot ({@link KeywordSnapshots}).</li>
 * </ul>
 * Kiểm tra chạy TỰ ĐỘNG sau mỗi test bởi {@link CompletionExpectations} (JUnit extension) - không
 * có bước "verify()" nào để quên gọi. Tạo qua {@link Completion#pg}/{@link Completion#ora}.
 */
public final class CompletionExpectation {

    private final String dialect;
    private final String sqlWithCursor;
    private final List<Suggestion> actual;
    private final Map<SuggestionType, List<String>> declared = new EnumMap<>(SuggestionType.class);
    private final List<String> keywordsIncluded = new ArrayList<>();
    private final List<String> extraColumns = new ArrayList<>();
    private String expectedFirst;
    private boolean verified;

    CompletionExpectation(String dialect, String sqlWithCursor, List<Suggestion> actual) {
        this.dialect = dialect;
        this.sqlWithCursor = sqlWithCursor;
        this.actual = List.copyOf(actual);
    }

    public CompletionExpectation columns(String... keys) {
        return declare(SuggestionType.COLUMN, keys);
    }

    public CompletionExpectation tables(String... keys) {
        return declare(SuggestionType.TABLE, keys);
    }

    public CompletionExpectation tables(Collection<String> keys) {
        return tables(keys.toArray(String[]::new));
    }

    public CompletionExpectation views(String... keys) {
        return declare(SuggestionType.VIEW, keys);
    }

    public CompletionExpectation views(Collection<String> keys) {
        return views(keys.toArray(String[]::new));
    }

    public CompletionExpectation materializedViews(String... keys) {
        return declare(SuggestionType.MATERIALIZED_VIEW, keys);
    }

    public CompletionExpectation materializedViews(Collection<String> keys) {
        return materializedViews(keys.toArray(String[]::new));
    }

    public CompletionExpectation functions(String... keys) {
        return declare(SuggestionType.FUNCTION, keys);
    }

    public CompletionExpectation functions(Collection<String> keys) {
        return functions(keys.toArray(String[]::new));
    }

    public CompletionExpectation datatypes(String... keys) {
        return declare(SuggestionType.DATATYPE, keys);
    }

    public CompletionExpectation datatypes(Collection<String> keys) {
        return datatypes(keys.toArray(String[]::new));
    }

    public CompletionExpectation roles(String... keys) {
        return declare(SuggestionType.ROLE, keys);
    }

    public CompletionExpectation roles(Collection<String> keys) {
        return roles(keys.toArray(String[]::new));
    }

    public CompletionExpectation schemas(String... keys) {
        return declare(SuggestionType.SCHEMA, keys);
    }

    public CompletionExpectation schemas(Collection<String> keys) {
        return schemas(keys.toArray(String[]::new));
    }

    /**
     * Cột hệ thống Postgres (ctid, xmin, xmax, cmin, cmax, tableoid) của từng tiền tố - GỘP vào tập
     * 'column' (cùng với {@link #columns}), để không phải liệt kê 6 cột mỗi lần.
     */
    public CompletionExpectation systemColumns(String... qualifiers) {
        for (String q : qualifiers) {
            for (String c : CompletionFixtures.PG_SYSTEM_COLUMNS) {
                extraColumns.add(q + "." + c);
            }
        }
        return this;
    }

    public CompletionExpectation aliases(String... keys) {
        return declare(SuggestionType.ALIAS, keys);
    }

    public CompletionExpectation others(String... keys) {
        return declare(SuggestionType.OTHER, keys);
    }

    public CompletionExpectation others(Collection<String> keys) {
        return others(keys.toArray(String[]::new));
    }

    /** Gợi ý xếp hạng đầu tiên phải là key này (ngoài việc tập vẫn phải khớp chính xác). */
    public CompletionExpectation first(String key) {
        this.expectedFirst = key;
        return this;
    }

    /**
     * Nhấn mạnh keyword quan trọng của test (vd ASC/DESC sau ORDER BY) - đã được snapshot phủ, nhưng
     * khai báo tường minh để test tự giải thích được và fail rõ ràng hơn nếu snapshot bị ghi đè sai.
     */
    public CompletionExpectation keywordsInclude(String... keywords) {
        keywordsIncluded.addAll(Arrays.asList(keywords));
        return this;
    }

    /** Danh sách gợi ý thô (đã qua SuggestFilter) - cho các assert đặc thù như thứ tự xếp hạng. */
    public List<Suggestion> actual() {
        return actual;
    }

    private CompletionExpectation declare(SuggestionType type, String... keys) {
        if (declared.containsKey(type)) {
            throw new IllegalStateException("Khai báo '" + type.label() + "' 2 lần cho: " + sqlWithCursor);
        }
        declared.put(type, Arrays.stream(keys).sorted().toList());
        return this;
    }

    void verify() {
        if (verified) {
            return;
        }
        verified = true;
        List<String> problems = new ArrayList<>();

        for (SuggestionType type : SuggestionType.values()) {
            if (type == SuggestionType.KEYWORD) {
                continue;
            }
            List<String> got = keysOf(type);
            List<String> want = declared.getOrDefault(type, List.of());
            if (type == SuggestionType.COLUMN && !extraColumns.isEmpty()) {
                want = java.util.stream.Stream.concat(want.stream(), extraColumns.stream()).sorted().toList();
            }
            if (!got.equals(want)) {
                List<String> extra = minus(got, want);
                List<String> missing = minus(want, got);
                problems.add("'" + type.label() + "': thừa " + extra + ", thiếu " + missing
                        + (declared.containsKey(type) || (type == SuggestionType.COLUMN && !extraColumns.isEmpty())
                        ? "" : " (loại này không được khai báo -> phải rỗng)"));
            }
        }

        List<String> keywords = keysOf(SuggestionType.KEYWORD);
        List<String> distinctKeywords = keywords.stream().distinct().toList();
        if (distinctKeywords.size() != keywords.size()) {
            problems.add("keyword bị trùng: " + minus(keywords, distinctKeywords));
        }
        List<String> missingIncluded = keywordsIncluded.stream().filter(k -> !keywords.contains(k)).toList();
        if (!missingIncluded.isEmpty()) {
            problems.add("thiếu keyword bắt buộc " + missingIncluded);
        }
        KeywordSnapshots snapshots = KeywordSnapshots.of(dialect);
        if (KeywordSnapshots.UPDATE) {
            snapshots.put(sqlWithCursor, keywords);
        } else {
            List<String> snap = snapshots.get(sqlWithCursor);
            if (snap == null) {
                problems.add("chưa có snapshot keyword - chạy: mvn test -Dsnapshot.update=true rồi review diff");
            } else if (!snap.equals(keywords)) {
                problems.add("keyword khác snapshot: thừa " + minus(keywords, snap) + ", thiếu " + minus(snap, keywords));
            }
        }

        if (expectedFirst != null) {
            String firstKey = actual.isEmpty() ? null : actual.get(0).getKey();
            if (!expectedFirst.equals(firstKey)) {
                problems.add("xếp hạng đầu phải là '" + expectedFirst + "' nhưng là '" + firstKey + "'");
            }
        }

        if (!problems.isEmpty()) {
            fail("[" + dialect + "] " + sqlWithCursor + "\n  - " + String.join("\n  - ", problems)
                    + "\n  Thực tế: " + describe());
        }
    }

    private List<String> keysOf(SuggestionType type) {
        return actual.stream().filter(s -> s.getType() == type).map(Suggestion::getKey).sorted().toList();
    }

    /** Hiệu đa tập (giữ phần tử lặp) - để báo đúng cả trường hợp trùng lặp. */
    private static List<String> minus(List<String> a, List<String> b) {
        List<String> rest = new ArrayList<>(b);
        List<String> out = new ArrayList<>();
        for (String x : a) {
            if (!rest.remove(x)) {
                out.add(x);
            }
        }
        return out;
    }

    private String describe() {
        return actual.stream()
                .collect(Collectors.groupingBy(s -> s.getType().label(), java.util.TreeMap::new,
                        Collectors.mapping(Suggestion::getKey, Collectors.toList())))
                .entrySet().stream()
                .map(e -> e.getKey().equals("keyword") ? "keyword=" + e.getValue().size() + " mục" : e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", ", "{", "}"));
    }
}
