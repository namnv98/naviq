package com.sqlctx.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chữ hiện cho người dùng, tiếng Việt hoặc tiếng Anh: {@code i18n/messages_vi.properties} / {@code messages_en.properties}.
 * Chọn ngôn ngữ theo thứ tự: {@code --lang=} (system property DB_LANG), {@code -Dsqlctx.lang=}, biến môi trường
 * SQLCTX_LANG, {@code lang=} trong ~/.sqlctx/settings.conf, rồi ngôn ngữ hệ thống (vi_* thì tiếng Việt, còn lại tiếng
 * Anh). Đổi lúc đang chạy bằng \lang (lưu vào settings.conf).
 * <p>
 * {0}, {1}... trong chuỗi là tham số - thay thẳng, KHÔNG dùng MessageFormat (dấu ' trong chuỗi sẽ bị nó nuốt mất).
 */
public final class I18n {

    public enum Lang {
        VI, EN;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Lang parse(String s) {
            if (s == null) {
                return null;
            }
            String v = s.strip().toLowerCase(Locale.ROOT);
            if (v.startsWith("vi")) {
                return VI;
            }
            if (v.startsWith("en")) {
                return EN;
            }
            return null;
        }
    }

    private static final Map<Lang, Properties> BUNDLES = new ConcurrentHashMap<>();
    private static volatile Lang current;

    private I18n() {
    }

    public static Lang lang() {
        Lang l = current;
        if (l == null) {
            l = resolve();
            current = l;
        }
        return l;
    }

    public static boolean vi() {
        return lang() == Lang.VI;
    }

    /** Đổi ngôn ngữ ngay; {@code persist}: ghi vào settings.conf cho lần chạy sau. */
    public static void set(Lang lang, boolean persist) {
        current = lang;
        if (persist) {
            Settings.put("lang", lang.code());
        }
    }

    /**
     * Lệnh \lang của mọi chế độ: không tham số thì cho biết ngôn ngữ hiện tại, có thì đổi và lưu. Trả chuỗi cần in;
     * {@code ok[0]} = false nếu tham số sai.
     */
    public static String langCommand(String arg, boolean[] ok) {
        ok[0] = true;
        if (arg == null || arg.isBlank()) {
            return t("lang.current");
        }
        Lang l = Lang.parse(arg);
        if (l == null) {
            ok[0] = false;
            return t("lang.invalid", arg.strip());
        }
        set(l, true);
        return t("lang.switched");
    }

    private static Lang resolve() {
        for (String candidate : new String[]{System.getProperty("DB_LANG"), System.getProperty("sqlctx.lang"),
                System.getenv("SQLCTX_LANG"), Settings.get("lang")}) {
            Lang l = Lang.parse(candidate);
            if (l != null) {
                return l;
            }
        }
        String env = System.getenv("LANG");
        if ("vi".equals(Locale.getDefault().getLanguage()) || env != null && env.startsWith("vi")) {
            return Lang.VI;
        }
        return Lang.EN;
    }

    /** Chuỗi theo ngôn ngữ hiện tại; thiếu ở tiếng Anh thì lấy tiếng Việt, thiếu cả hai thì trả nguyên khoá. */
    public static String t(String key, Object... args) {
        String s = bundle(lang()).getProperty(key);
        if (s == null) {
            s = bundle(Lang.VI).getProperty(key, key);
        }
        return format(s, args);
    }

    /**
     * Chữ để thẳng trong code dạng tiếng Việt (bảng mô tả tool Kafka...): tiếng Việt thì trả nguyên, tiếng Anh thì tra
     * {@code i18n/texts_en.tsv} (tiếng Việt TAB English). Dòng có {0} là mẫu cho câu ghép ("Đọc message {0} qua ...");
     * không có bản dịch thì giữ tiếng Việt.
     */
    public static String tr(String vietnamese) {
        if (vietnamese == null || lang() == Lang.VI) {
            return vietnamese;
        }
        Texts t = Texts.INSTANCE;
        String exact = t.exact.get(vietnamese);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<java.util.regex.Pattern, String> e : t.templates.entrySet()) {
            java.util.regex.Matcher m = e.getKey().matcher(vietnamese);
            if (m.matches()) {
                return e.getValue().replace("{0}", m.group(1));
            }
        }
        return vietnamese;
    }

    private static final class Texts {
        static final Texts INSTANCE = new Texts();
        final Map<String, String> exact = new java.util.HashMap<>();
        final Map<java.util.regex.Pattern, String> templates = new java.util.LinkedHashMap<>();

        private Texts() {
            try (InputStream in = I18n.class.getResourceAsStream("/i18n/texts_en.tsv")) {
                if (in == null) {
                    return;
                }
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    int tab = line.indexOf('\t');
                    if (line.startsWith("#") || tab <= 0) {
                        continue;
                    }
                    String vi = line.substring(0, tab);
                    String en = line.substring(tab + 1);
                    if (vi.contains("{0}")) {
                        String[] parts = vi.split("\\{0\\}", 2);
                        templates.put(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(parts[0]) + "(.+?)"
                                + java.util.regex.Pattern.quote(parts[1])), en);
                    } else {
                        exact.put(vi, en);
                    }
                }
            } catch (IOException ignored) {
                // thiếu file: giữ tiếng Việt
            }
        }
    }

    /** Có khoá này không (cho chữ tuỳ chọn, vd mô tả lệnh chỉ có ở 1 ngôn ngữ). */
    public static boolean has(String key) {
        return bundle(lang()).containsKey(key);
    }

    static String format(String s, Object... args) {
        if (args.length == 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int close = c == '{' ? s.indexOf('}', i) : -1;
            if (close > i + 1 && close - i <= 3) {
                String idx = s.substring(i + 1, close);
                if (idx.chars().allMatch(Character::isDigit)) {
                    int n = Integer.parseInt(idx);
                    sb.append(n < args.length ? String.valueOf(args[n]) : "{" + idx + "}");
                    i = close;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static Properties bundle(Lang lang) {
        return BUNDLES.computeIfAbsent(lang, l -> {
            Properties p = new Properties();
            try (InputStream in = I18n.class.getResourceAsStream("/i18n/messages_" + l.code() + ".properties")) {
                if (in != null) {
                    p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // thiếu file thì mọi chuỗi rơi về khoá - không chặn chạy
            }
            return p;
        });
    }

    /** ~/.sqlctx/settings.conf - cài đặt chung (key=value), hiện chỉ có lang. */
    public static final class Settings {

        private Settings() {
        }

        static Path file() {
            return Path.of(System.getProperty("user.home"), ".sqlctx", "settings.conf");
        }

        public static String get(String key) {
            Path f = file();
            if (!Files.exists(f)) {
                return null;
            }
            Properties p = new Properties();
            try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                return null;
            }
            return p.getProperty(key);
        }

        public static void put(String key, String value) {
            Path f = file();
            Properties p = new Properties();
            try {
                if (Files.exists(f)) {
                    try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                        p.load(r);
                    }
                }
                p.setProperty(key, value);
                Files.createDirectories(f.getParent());
                StringBuilder sb = new StringBuilder("# sqlctx settings\n");
                p.stringPropertyNames().stream().sorted().forEach(k -> sb.append(k).append('=').append(p.getProperty(k)).append('\n'));
                Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // không ghi được thì lần sau chỉ là không nhớ ngôn ngữ đã chọn
            }
        }
    }
}
