package com.sqlctx.cli.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GridViewTest {

    private static final int W = 60;
    private static final int H = 10; // 7 dòng dữ liệu

    /** 100 dòng x 12 cột: "id" rồi c1..c11, mỗi ô rộng 10. */
    private static GridView wide() {
        List<String> cols = new ArrayList<>(List.of("id"));
        for (int c = 1; c <= 11; c++) {
            cols.add("c" + c);
        }
        List<List<String>> rows = new ArrayList<>();
        for (int r = 1; r <= 100; r++) {
            List<String> row = new ArrayList<>(List.of(String.valueOf(r)));
            for (int c = 1; c <= 11; c++) {
                row.add(String.format("%-10s", "r" + r + "c" + c).replace(' ', 'x'));
            }
            rows.add(row);
        }
        return new GridView(cols, rows);
    }

    /** Dòng tên cột: ngay dưới khung trên. */
    private static String header(GridView v) {
        return plain(v).get(1);
    }

    private static List<String> plain(GridView v) {
        return v.render(W, H).stream().map(s -> s.replaceAll("\u001b\\[[0-9;]*[A-Za-z]", "")).toList();
    }

    @Test
    @DisplayName("Render đúng số dòng màn hình, header ở trên cùng, thanh trạng thái ở dưới cùng")
    void rendersExactlyScreenHeight() {
        List<String> lines = plain(wide());
        assertEquals(H, lines.size());
        assertTrue(lines.get(0).startsWith("╭") || lines.get(0).startsWith("+"), lines.get(0));
        assertTrue(lines.get(1).contains("id") && lines.get(1).contains("c1"), lines.get(1));
        assertTrue(lines.get(H - 1).contains("row 1 of 100"), lines.get(H - 1));
    }

    @Test
    @DisplayName("Không dòng nào rộng hơn màn hình")
    void noLineWiderThanScreen() {
        for (String line : plain(wide())) {
            assertTrue(DataViewTable.displayWidth(line) <= W, "rộng " + DataViewTable.displayWidth(line) + ": " + line);
        }
    }

    @Test
    @DisplayName("Cuộn xuống quá trang: header vẫn ở trên, dòng chọn luôn trong màn hình")
    void headerStaysWhileScrollingRows() {
        GridView v = wide();
        for (int i = 0; i < 20; i++) {
            v.handle("down", W, H);
        }
        List<String> lines = plain(v);
        assertTrue(lines.get(1).contains("id"), lines.get(1));
        assertTrue(lines.stream().anyMatch(l -> l.matches("^. +21 .*")), String.join("\n", lines));
        assertTrue(lines.get(H - 1).contains("row 21 of 100"));
    }

    @Test
    @DisplayName("g/G và PgDn không vượt quá dữ liệu")
    void cursorIsClamped() {
        GridView v = wide();
        v.handle("end", W, H);
        v.handle("pgdn", W, H);
        assertTrue(plain(v).get(H - 1).contains("row 100 of 100"));
        v.handle("home", W, H);
        v.handle("up", W, H);
        assertTrue(plain(v).get(H - 1).contains("row 1 of 100"));
    }

    @Test
    @DisplayName("Cuộn ngang: cột đầu đứng yên, cột bên trái bị đẩy ra, có dấu báo còn cột hai bên")
    void firstColumnFrozenWhileScrollingColumns() {
        GridView v = wide();
        assertTrue(header(v).endsWith("▶") || header(v).endsWith(">"), header(v));
        v.handle("right", W, H);
        v.handle("right", W, H);
        String header = header(v);
        assertTrue(header.contains("id"), header);
        assertFalse(header.contains(" c1 "), header);
        assertTrue(header.contains("c3"), header);
        assertTrue(header.contains("◀") || header.startsWith("<"), header);
    }

    @Test
    @DisplayName("$ tới cột cuối thì không cuộn phải thêm được nữa")
    void colEndShowsLastColumn() {
        GridView v = wide();
        v.handle("colEnd", W, H);
        String header = header(v);
        assertTrue(header.contains("c11"), header);
        List<int[]> before = v.visibleColumns(W);
        v.handle("right", W, H);
        assertEquals(before.get(1)[0], v.visibleColumns(W).get(1)[0]);
    }

    @Test
    @DisplayName("x: xem bản ghi đang chọn dạng 'cột │ giá trị', ←→ sang bản ghi khác, x lần nữa về lưới")
    void recordModeShowsCurrentRow() {
        GridView v = wide();
        v.handle("down", W, H);
        v.handle("record", W, H);
        List<String> lines = plain(v);
        assertTrue(lines.get(0).contains("Record 2 of 100"), lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.contains("c1 ") && l.contains("r2c1x")), String.join("\n", lines));
        v.handle("pgdn", W, H);
        lines = plain(v);
        assertTrue(lines.stream().anyMatch(l -> l.contains("c11") && l.contains("r2c11")), String.join("\n", lines));
        v.handle("right", W, H);
        assertTrue(plain(v).get(0).contains("Record 3 of 100"));
        v.handle("record", W, H);
        assertTrue(plain(v).get(H - 1).contains("row 3 of 100"));
    }

    @Test
    @DisplayName("Chế độ bản ghi: giá trị dài xuống dòng, không bị cắt")
    void recordModeWrapsLongValues() {
        String longValue = "a".repeat(200);
        GridView v = new GridView(List.of("id", "body"), List.of(List.of("1", longValue)));
        v.handle("record", W, 40);
        String joined = String.join("", v.render(W, 40).stream()
                .map(s -> s.replaceAll("\u001b\\[[0-9;]*[A-Za-z]", "")).toList());
        assertEquals(200, joined.chars().filter(ch -> ch == 'a').count());
    }

    @Test
    @DisplayName("q thoát")
    void quit() {
        assertFalse(wide().handle("quit", W, H));
    }

    @Test
    @DisplayName("Không có dòng nào: vẫn vẽ header và báo 0 rows")
    void emptyResult() {
        GridView v = new GridView(List.of("id", "name"), List.of());
        v.handle("down", W, H);
        List<String> lines = plain(v);
        assertTrue(lines.get(1).contains("name"));
        assertTrue(lines.get(3).startsWith("╰") || lines.get(3).startsWith("+"), lines.get(3));
        assertTrue(lines.get(H - 1).contains("0 rows"));
    }
}
