package com.sqlctx.shell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenTest {

    @Test
    void độRộngBỏMãMàu() {
        assertEquals(5, Screen.width("\u001b[31mhello\u001b[0m"));
        assertEquals(7, Screen.width("Bộ nhớ "));
        assertEquals(4, Screen.width("日本"), "chữ CJK rộng 2 ô");
    }

    @Test
    void cắtVàĐệmGiữMàu() {
        assertEquals(6, Screen.width(Screen.fit("\u001b[32mab", 6)));
        String cut = Screen.fit("\u001b[32mabcdefgh", 5);
        assertEquals(5, Screen.width(cut));
        assertTrue(cut.startsWith("\u001b[32mabcd…"), cut);
        assertTrue(cut.contains(Screen.RESET), "luôn đóng màu");
        assertEquals("   42" + Screen.RESET, Screen.right("42", 5));
    }

    @Test
    void xuốngDòngThayVìCắt() {
        assertEquals(List.of("aaa · bbb", "ccc"), Screen.flow(List.of("aaa", "bbb", "ccc"), " · ", 10));
        assertEquals(List.of("aaa · bbb · ccc"), Screen.flow(List.of("aaa", "bbb", "ccc"), " · ", 40));
        assertEquals(List.of("quá-dài-vẫn-giữ"), Screen.flow(List.of("quá-dài-vẫn-giữ"), " · ", 5), "1 mục dài hơn dòng: để nguyên");
    }

    @Test
    void ôBảngThanhVàSparkline() {
        List<String> box = Screen.box("Tiêu đề", List.of("một", "hai"), 20, 4, true);
        assertEquals(4, box.size());
        box.forEach(l -> assertEquals(20, Screen.width(l), l));
        assertEquals(10, Screen.width(Screen.bar(0.5, 10, 0.8, 0.9)));
        assertEquals(10, Screen.width(Screen.bar(0.33, 10, 0.8, 0.9)), "khối 1/8 vẫn đúng 10 ô");
        assertTrue(Screen.bar(0.95, 10, 0.8, 0.9).contains(Screen.RED));
        assertEquals(5, Screen.width(Screen.spark(List.of(0.0, 5.0, 10.0), 5)), "chưa đủ mẫu thì đệm chấm mờ");
        assertEquals("⣀⠊", Screen.plain(Screen.spark(List.of(0.0, 5.0, 10.0), 2)), "0 sát đáy, 5 giữa, 10 trên cùng");
        assertEquals("⠉⠉", Screen.plain(Screen.spark(List.of(7.0, 7.0, 7.0, 7.0), 2)), "đều thì là đường thẳng trên cùng");
        assertEquals("19.9k", Screen.compact(19920));
        assertEquals("1.5M", Screen.compact(1_500_000));
        assertEquals("42", Screen.compact(42));
    }

    @Test
    void biểuĐồVùngVàÔSốLiệu() {
        List<String> chart = Screen.area(List.of(0.0, 4.0, 8.0), 20, 5, Screen.GREEN_RAMP);
        assertEquals(5, chart.size());
        chart.forEach(l -> assertEquals(20, Screen.width(l), l));
        assertTrue(Screen.plain(chart.get(0)).startsWith("8 ┤"), "trục ghi giá trị lớn nhất\n" + chart);
        assertTrue(Screen.plain(chart.get(2)).startsWith("4 ┤"), "và giá trị giữa\n" + chart);
        assertTrue(Screen.plain(chart.get(4)).startsWith("0 ┤") && Screen.plain(chart.get(4)).endsWith("▁██"),
                "cột cao nhất đầy tới đáy\n" + chart);
        assertTrue(Screen.plain(chart.get(0)).endsWith("█"), "và tới đỉnh\n" + chart);
        List<String> tiles = Screen.tiles(List.of(new Screen.Tile("A", "1", "x"), new Screen.Tile("B", "2", "y"),
                new Screen.Tile("C", "3", "z")), 61);
        assertEquals(4, tiles.size());
        tiles.forEach(l -> assertEquals(61, Screen.width(l), l));
    }

    @Test
    void ôCảnhBáoCuộnĐượcNhưBảng() {
        List<String> warnings = List.of("w1", "w2", "w3", "w4", "w5", "w6", "w7");
        List<String> idle = Dashboard.warningsBox(warnings, 4, "ok", 40, 0, false);
        assertEquals(6, idle.size(), "viền + 4 dòng");
        assertTrue(Screen.plain(idle.get(0)).contains("7 · 1–4/7"), idle.get(0));
        String body = Screen.plain(String.join("\n", idle));
        assertTrue(body.contains("w4") && !body.contains("w5"), "không bỏ dòng nào thành \"… và N khác\"\n" + body);
        List<String> scrolled = Dashboard.warningsBox(warnings, 4, "ok", 40, 6, true);
        assertTrue(Screen.plain(scrolled.get(0)).contains("4–7/7"), scrolled.get(0));
        assertTrue(scrolled.get(4).contains(Screen.SELECTED) && Screen.plain(scrolled.get(4)).contains("w7"), "dòng chọn được tô\n" + scrolled);
        scrolled.forEach(l -> assertEquals(40, Screen.width(l), l));
    }

    @Test
    void bảngCoCộtVàCuộnTheoDòngChọn() {
        List<Screen.Column> cols = List.of(new Screen.Column("tên", 'l', 3), new Screen.Column("số", 'r', 2));
        List<List<String>> rows = List.of(List.of("một-cái-tên-rất-dài", "1"), List.of("b", "2"), List.of("c", "3"), List.of("d", "4"));
        List<String> t = Screen.table(cols, rows, 12, 3, 3, true);
        assertEquals(3, t.size(), "tiêu đề + 2 dòng");
        t.forEach(l -> assertTrue(Screen.width(l) <= 12, l));
        assertTrue(t.get(2).contains("d") && t.get(2).contains(Screen.SELECTED), "cuộn tới dòng đang chọn\n" + t);
    }
}
