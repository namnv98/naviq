package com.sqlctx.net;

import com.sqlctx.util.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bảng sự kiện của sqlctx --net: khung luôn thẳng dù chữ dài, có màu, có tiếng Việt. */
class NetPrinterTest {

    @BeforeAll
    static void tiếngViệt() {
        I18n.set(I18n.Lang.VI, false);
    }

    @AfterEach
    void tắtMàu() {
        NetFormat.color = false;
    }

    private static List<String> lines(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    void mọiDòngCủaBảngCùngBềNgangKểCảKhiChữDàiXuốngDòng() {
        NetFormat.color = true;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        NetPrinter p = new NetPrinter(new PrintStream(buf, true, StandardCharsets.UTF_8), 120);
        String sql = NetFormat.blue("SQL") + " select c.oid, a.attnum, a.attname, c.relname, n.nspname from pg_class c join pg_attribute a "
                + "on a.attrelid = c.oid join pg_namespace n on n.oid = c.relnamespace where c.relname = 'users' → "
                + NetFormat.green("SELECT 13") + NetFormat.dim(" · 27 ms");
        p.emit(1, NetPrinter.Kind.REQUEST, "java", "devops@pawn 10.1.9.2:5432", sql, null);
        p.emit(2, NetPrinter.Kind.ERROR, "curl", "127.0.0.1:443", NetFormat.red("TỪ CHỐI kết nối"),
                "máy 127.0.0.1 có sống, nhưng không có gì chạy ở cổng 443 (hoặc firewall reject)");
        p.emit(3, NetPrinter.Kind.LAN, "LAN", "fe80::cf5:5851:f6eb:c0bd Google Chrome/155.0.0.0 Linux", "mDNS đang tìm", null);
        p.close();
        List<String> lines = lines(buf);
        int width = NetPrinter.width(lines.get(0));
        for (String l : lines) {
            assertEquals(width, NetPrinter.width(l), "lệch khung: " + l);
            assertTrue(width <= 120);
        }
        assertTrue(lines.get(0).contains("╭") && lines.get(lines.size() - 1).contains("╰"));
        assertTrue(lines.size() > 3 + 3 + 1, "câu SQL dài phải xuống dòng trong ô");
    }

    @Test
    void xuốngDòngGiữMàuVàNgắtỞKhoảngTrắng() {
        String red = "\u001b[1;31m";
        List<String> lines = NetPrinter.wrap(red + "một hai ba bốn năm sáu\u001b[0m bảy", 10);
        for (String l : lines) {
            assertTrue(NetPrinter.width(l) <= 10, l);
        }
        assertEquals("một hai ba", lines.get(0).replaceAll("\u001b\\[[0-9;]*m", ""));
        assertTrue(lines.get(1).startsWith(red), "dòng sau phải mở lại màu đỏ: " + lines.get(1));
        assertEquals(List.of("ngắn"), NetPrinter.wrap("ngắn", 10));
    }

    @Test
    void bảngTĩnhCănPhảiCộtSố() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        NetPrinter.table(new PrintStream(buf, true, StandardCharsets.UTF_8), "TÓM TẮT", List.of("", "", ""),
                List.of(List.of("✓", "Kết nối thành công", "12"), List.of("✗", "Bị từ chối", "3")), 2);
        List<String> lines = lines(buf);
        assertEquals("TÓM TẮT", lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.contains("│  3 │")), String.join("\n", lines));
    }

    private static void type(NetPrinter p, String keys) {
        keys.codePoints().forEach(cp -> p.key(new String(Character.toChars(cp))));
    }

    @Test
    void lọcLúcĐangChạyKhôngPhânBiệtDấuVàLoạiTrừĐược() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        NetPrinter p = new NetPrinter(new PrintStream(buf, true, StandardCharsets.UTF_8), 140);
        type(p, "/tu choi -6443\r");             // "tu choi" khớp "TỪ CHỐI", loại dòng có 6443
        p.emit(1, NetPrinter.Kind.ERROR, "curl", "127.0.0.1:443", "TỪ CHỐI kết nối", null);
        p.emit(2, NetPrinter.Kind.ERROR, "curl", "127.0.0.1:6443", "TỪ CHỐI kết nối", null);
        p.emit(3, NetPrinter.Kind.OPEN, "chrome", "github.com", "mở HTTPS", null);
        String text = buf.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("127.0.0.1:443 "));
        assertFalse(text.contains("127.0.0.1:6443"), text);   // (chữ 6443 vẫn có trong dòng trạng thái bộ lọc)
        assertFalse(text.contains("github.com"), text);
        assertEquals(1, text.lines().filter(l -> l.contains("THỜI GIAN")).count(), "tiêu đề cột chỉ in 1 lần:\n" + text);
    }

    @Test
    void tạmDừngGiữDòngLạiRồiInKhiChạyTiếp() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        NetPrinter p = new NetPrinter(new PrintStream(buf, true, StandardCharsets.UTF_8), 140);
        p.key("p");
        p.emit(1, NetPrinter.Kind.OPEN, "psql", "127.0.0.1:54329", "mở PostgreSQL", null);
        assertFalse(buf.toString(StandardCharsets.UTF_8).contains("54329"));
        p.key("e"); // đổi bộ lọc trong lúc dừng: dòng đã giữ cũng bị lọc lại theo
        p.key("e");
        p.key("p");
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("54329"));
        assertFalse(p.key("q"), "q là phím thoát");
    }

    @Test
    void phímMũiTênĐổiSangMànChiTiếtVàVẽLạiDòngCũ() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        NetPrinter p = new NetPrinter(new PrintStream(buf, true, StandardCharsets.UTF_8), 188, 30);
        p.emit(1, NetPrinter.Kind.CLOSE, "psql", "db.local:54329", "đóng bình thường", "ghi chú dài",
                new NetPrinter.Detail("TCP PGSQL", "127.0.0.1:41234", "127.0.0.1:54329", 2048, 1_500_000, 3.2, 4242));
        String before = buf.toString(StandardCharsets.UTF_8);
        assertFalse(before.contains("4242"), "màn tổng quan: chưa có PID");
        p.key(">");
        p.key("\u001b[C"); // mũi tên phải ở màn cuối: đứng yên
        String detail = buf.toString(StandardCharsets.UTF_8).substring(before.length());
        assertTrue(detail.contains("4242") && detail.contains("127.0.0.1:41234") && detail.contains("1.4 MB")
                && detail.contains("psql") && detail.contains("db.local"), detail);
        assertFalse(detail.contains("ghi chú"), "màn chi tiết: mỗi sự kiện 1 dòng, không ghi chú");
        List<String> table = detail.lines().filter(l -> l.startsWith("╭") || l.startsWith("│") || l.startsWith("├")).toList();
        int width = NetPrinter.width(table.get(0));
        table.forEach(l -> assertEquals(width, NetPrinter.width(l), "lệch khung: " + l));
        assertTrue(width <= 188);
        p.key("<");
        String back = buf.toString(StandardCharsets.UTF_8).substring(before.length() + detail.length());
        assertTrue(back.contains("CHUYỆN GÌ") && back.contains("đóng bình thường"), back);
    }
}
