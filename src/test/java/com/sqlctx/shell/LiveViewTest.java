package com.sqlctx.shell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveViewTest {

    @Test
    void khungCaoHơnMànHìnhThìCắt() {
        List<String> lines = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        assertEquals(lines, LiveView.fit(lines, 0), "không biết chiều cao (batch): giữ nguyên");
        assertEquals(lines, LiveView.fit(lines, 12));
        List<String> cut = LiveView.fit(lines, 8);
        assertEquals(6, cut.size(), "chừa 2 dòng cho dấu nhắc và thanh trạng thái");
        assertTrue(cut.get(5).contains("(5)"), cut.toString());
    }

    @Test
    void táchDòng() {
        assertEquals(List.of("a", "b"), LiveView.lines("a\r\nb\n\n"));
    }
}
