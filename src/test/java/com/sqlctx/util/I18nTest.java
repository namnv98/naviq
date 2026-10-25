package com.sqlctx.util;

import com.sqlctx.kafka.KafkaTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class I18nTest {

    @AfterEach
    void trảVềTiếngViệt() {
        I18n.set(I18n.Lang.VI, false);
    }

    @Test
    void haiNgônNgữCóCùngBộKhoá() {
        Set<String> vi = I18n.bundle(I18n.Lang.VI).stringPropertyNames();
        Set<String> en = I18n.bundle(I18n.Lang.EN).stringPropertyNames();
        Set<String> onlyVi = new TreeSet<>(vi);
        onlyVi.removeAll(en);
        Set<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(vi);
        assertEquals(Set.of(), onlyVi, "thiếu bản tiếng Anh");
        assertEquals(Set.of(), onlyEn, "thiếu bản tiếng Việt");
    }

    private static Set<String> placeholders(String s) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("\\{\\d}").matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    @Test
    void thamSốKhớpGiữaHaiNgônNgữ() {
        Properties vi = I18n.bundle(I18n.Lang.VI);
        Properties en = I18n.bundle(I18n.Lang.EN);
        List<String> wrong = new ArrayList<>();
        for (String k : vi.stringPropertyNames()) {
            if (en.containsKey(k) && !placeholders(vi.getProperty(k)).equals(placeholders(en.getProperty(k)))) {
                wrong.add(k);
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void môTảToolKafkaĐềuCóBảnTiếngAnh() {
        I18n.set(I18n.Lang.EN, false);
        List<String> missing = new ArrayList<>();
        for (KafkaTools.Tool t : KafkaTools.TOOLS.values()) {
            check(t.help(), missing);
            t.modeHelp().values().forEach(h -> check(h, missing));
            t.options().forEach(o -> check(o.help(), missing));
        }
        KafkaTools.ENUMS_FOR_TEST.values().forEach(list -> list.forEach(e -> check(e[1], missing)));
        assertEquals(List.of(), missing);
    }

    private static final Pattern VIETNAMESE = Pattern.compile(
            "[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]", Pattern.CASE_INSENSITIVE);

    private static void check(String vi, List<String> missing) {
        if (vi != null && VIETNAMESE.matcher(I18n.tr(vi)).find()) {
            missing.add(vi);
        }
    }

    @Test
    void thayThamSốKhôngĐụngDấuNháy() {
        assertEquals("it's 3 'x'", I18n.format("it's {0} '{1}'", 3, "x"));
        assertEquals("{5}", I18n.format("{5}", 1));
    }

    @Test
    void lệnhLangĐổiNgônNgữ() {
        boolean[] ok = new boolean[1];
        assertEquals("Switched to English (saved for next time).", langNoSave("en", ok));
        assertEquals(I18n.Lang.EN, I18n.lang());
        langNoSave("xx", ok);
        assertEquals(false, ok[0]);
        assertEquals(I18n.Lang.EN, I18n.lang(), "tham số sai: giữ nguyên ngôn ngữ");
    }

    /** \lang nhưng không ghi settings.conf thật của người chạy test. */
    private static String langNoSave(String arg, boolean[] ok) {
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", System.getProperty("java.io.tmpdir") + "/sqlctx-i18n-test");
            return I18n.langCommand(arg, ok);
        } finally {
            System.setProperty("user.home", home);
        }
    }
}
