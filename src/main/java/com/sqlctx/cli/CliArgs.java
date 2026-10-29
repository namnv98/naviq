package com.sqlctx.cli;

import com.sqlctx.util.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Đọc tham số dòng lệnh vào system property DB_&lt;TÊN&gt; (vd --context=dev -&gt; DB_CONTEXT). Nhận cả "--tên=giá trị"
 * lẫn "--tên giá trị". Trước đây chỉ có dạng "=": gõ "--context dev" thì cả 2 từ bị bỏ qua không báo gì và sqlctx
 * lặng lẽ mở context lần trước - giờ tuỳ chọn lạ, thiếu giá trị hay chữ thừa đều là lỗi.
 */
public final class CliArgs {

    private CliArgs() {
    }

    /** Tuỳ chọn có giá trị. */
    static final Set<String> VALUE_OPTIONS = Set.of("context", "host", "port", "dbname", "user", "password", "dialect",
            "command", "file", "lang", "sslmode", "sslrootcert", "sslcert", "sslkey", "sslpassword", "sslwallet",
            "sslserverdnmatch", "keyword-case", "indent", "bootstrap-server", "bootstrap", "schema-registry",
            "sentinel-master", "sentinel-password", "iface", "filter", "pcap");
    /** Đúng/sai: "--readonly" trống là true, "--readonly false" / "--readonly=false" thì như viết. */
    static final Set<String> BOOLEAN_OPTIONS = Set.of("readonly", "tls", "ssl");
    /** Cờ không có giá trị (đọc thẳng từ args ở nơi dùng). */
    static final Set<String> FLAGS = Set.of("yes", "lsp", "format", "net");

    /** Ghi các tuỳ chọn vào system property; trả danh sách lỗi (rỗng = ổn). */
    public static List<String> apply(String[] args) {
        // --lang trước tiên: thông báo lỗi của chính các tham số khác cũng theo ngôn ngữ đã chọn
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--lang=")) {
                set("lang", args[i].substring(7));
            } else if (args[i].equals("--lang") && i + 1 < args.length) {
                set("lang", args[i + 1]);
            }
        }
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || arg.length() == 2) {
                errors.add(I18n.t("cli.strayArg", arg));
                continue;
            }
            String name = arg.substring(2);
            String value = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (FLAGS.contains(key)) {
                if (value != null) {
                    errors.add(I18n.t("cli.flagNoValue", name));
                }
                continue;
            }
            if (BOOLEAN_OPTIONS.contains(key)) {
                if (value == null && i + 1 < args.length && isBoolean(args[i + 1])) {
                    value = args[++i];
                }
                set(key, value == null ? "true" : value);
                continue;
            }
            if (!VALUE_OPTIONS.contains(key)) {
                String near = nearest(key);
                errors.add(near == null ? I18n.t("cli.unknownOption", name) : I18n.t("cli.unknownOptionDidYouMean", name, near));
                continue;
            }
            if (value == null) {
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                } else {
                    errors.add(I18n.t("cli.missingValue", name));
                    continue;
                }
            }
            set(key, value);
        }
        return errors;
    }

    private static boolean isBoolean(String s) {
        return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false");
    }

    private static void set(String key, String value) {
        System.setProperty("DB_" + key.toUpperCase(Locale.ROOT), value);
    }

    /** Tên gần nhất (khoảng cách chỉnh sửa ≤ 2) để gợi ý khi gõ nhầm, vd --contxt -&gt; --context. */
    static String nearest(String key) {
        String best = null;
        int bestDist = 3;
        for (Set<String> group : List.of(VALUE_OPTIONS, BOOLEAN_OPTIONS, FLAGS)) {
            for (String candidate : group) {
                int d = distance(key, candidate);
                if (d < bestDist) {
                    bestDist = d;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
