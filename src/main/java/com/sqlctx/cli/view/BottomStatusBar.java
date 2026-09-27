package com.sqlctx.cli.view;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.util.ArrayList;

/**
 * Bottom status bar cố định ở dòng cuối terminal (pgcli style).
 * Không dùng scroll region — chỉ save/restore cursor.
 */
public class BottomStatusBar {
    private boolean multiLine = false;
    private final Terminal terminal;
    private String dbInfo = "not connected";
    private String queryStatus = "idle";

    public void toggleMultiLine() {
        this.multiLine = !this.multiLine;
    }

    private int cursorRow = 0;
    private int cursorCol = 0;

    public void updateCursor(int row, int col) {
        this.cursorRow = row;
        this.cursorCol = col;
    }

    private static final String[][] MENU_ITEMS = {
            {"F1", "Help"},
            {"Ctrl T", "Multiline"},
            {"F10", "Quit"},
    };

    // Cùng 1 nền (36,36,36) xuyên suốt thanh - phân biệt từng mục bằng MÀU CHỮ thay vì đổi khối nền (đỡ
    // rối mắt hơn kiểu "powerline" nhiều khối màu), giống trước đây MỌI style đều dùng chung 1 màu trắng
    // phẳng nên các mục không phân biệt được với nhau.
    private static final AttributedStyle BAR_BG = AttributedStyle.DEFAULT.background(36, 36, 36);
    // foregroundRgb() nhận 1 số hex 24-bit thật (0xRRGGBB) - khác foreground(int) là màu THEO PALETTE 256
    // màu (chỉ dùng byte thấp làm index), dùng nhầm hàm đó với hex 0xffd166 từng ra màu index 102 sai bét.
    private static final AttributedStyle KEY_STYLE = BAR_BG.foregroundRgb(0xffd166).bold(); // phím tắt - vàng nổi bật, kiểu "keycap"
    private static final AttributedStyle LABEL_STYLE = BAR_BG.foreground(150, 150, 150); // tên hành động - mờ hơn phím
    private static final AttributedStyle SEP_STYLE = BAR_BG.foreground(90, 90, 90); // dấu "│" ngăn cách - chỉ làm nền, không cần nổi bật
    private static final AttributedStyle INFO_STYLE = BAR_BG.foregroundRgb(0x7fd8ff); // db/host đang kết nối - xanh dương nhạt
    private static final AttributedStyle CURSOR_STYLE = BAR_BG.foreground(150, 150, 150); // Ln/Col - thông tin phụ, mờ

    // Query status đổi màu THEO TRẠNG THÁI - trước đây luôn trắng nên không phân biệt được RUNNING/ERROR/idle
    // qua màu, phải đọc chữ. Đây là chỗ có giá trị thực tế nhất trong cả thanh status.
    private static final AttributedStyle STATUS_ERROR = BAR_BG.foregroundRgb(0xff6b6b).bold();
    private static final AttributedStyle STATUS_BUSY = BAR_BG.foregroundRgb(0xffd166).bold();
    private static final AttributedStyle STATUS_IDLE = BAR_BG.foregroundRgb(0x6bcf7f);
    private static final AttributedStyle STATUS_INFO = BAR_BG.foregroundRgb(0x7fd8ff);
    private static final AttributedStyle STATUS_DEFAULT = BAR_BG.foreground(200, 200, 200);

    public BottomStatusBar(Terminal terminal) {
        this.terminal = terminal;
    }

    public void setDbInfo(String info) {
        this.dbInfo = info;
    }

    public void setQueryStatus(String s) {
        this.queryStatus = s;
    }

    /**
     * Vẽ thanh tại dòng cuối.
     * Dùng DECSC/DECRC (ESC 7 / ESC 8) để save/restore cursor —
     * không ảnh hưởng đến vị trí prompt của JLine.
     */
    public void render() {
        int width = Math.max(terminal.getWidth(), 80);
        AttributedString bar = buildBar(width);
        Status status = Status.getStatus(terminal);
        var attributedStrings = new ArrayList<AttributedString>();
        attributedStrings.add(new AttributedStringBuilder().toAttributedString());
        attributedStrings.add(bar);

        status.update(attributedStrings);
    }

    /** Vẽ lại status bar sau khi màn hình vừa bị xoá hoặc đổi cỡ (Status chỉ vẽ lại khi nội dung đổi). */
    public void redraw() {
        Status status = Status.getStatus(terminal, false);
        if (status != null) {
            status.resize();
            status.reset();
            status.redraw();
        }
        render();
    }

    private AttributedString buildBar(int width) {
        AttributedStringBuilder sb = new AttributedStringBuilder();

        for (String[] item : MENU_ITEMS) {

            String label = item[1];

            if (item[0].equals("Ctrl T")) {
                label = "Multiline " + (multiLine ? "ON" : "OFF");
            }

            sb.style(KEY_STYLE).append(" [").append(item[0]).append("] ");
            sb.style(LABEL_STYLE).append(label).append(" ");
        }

        sb.style(SEP_STYLE).append("  │  ");
        sb.style(INFO_STYLE).append("\uD83D\uDDA5 ").append(dbInfo);


        sb.style(SEP_STYLE).append("  │  ");
        sb.style(CURSOR_STYLE).append(String.format("Ln %d, Col %d", cursorRow, cursorCol));


        sb.style(SEP_STYLE).append("  │  ");
        sb.style(queryStatusStyle()).append(queryStatus);


        AttributedString line = sb.toAttributedString();
        int len = line.columnLength();
        if (len < width) {
            AttributedStringBuilder padded = new AttributedStringBuilder();
            padded.append(line);
            padded.style(SEP_STYLE);
            padded.append(" ".repeat(width - len));
            return padded.toAttributedString();
        }
        return line;
    }

    /** Màu theo NỘI DUNG trạng thái - trước đây luôn 1 màu trắng nên phải đọc chữ mới biết đang ERROR hay idle. */
    private AttributedStyle queryStatusStyle() {
        String s = queryStatus.toUpperCase();
        if (s.contains("ERROR")) {
            return STATUS_ERROR;
        }
        if (s.contains("RUNNING") || s.contains("CONNECTING")) {
            return STATUS_BUSY;
        }
        if (s.contains("IDLE")) {
            return STATUS_IDLE;
        }
        if (s.contains("MULTI-LINE")) {
            return STATUS_INFO;
        }
        return STATUS_DEFAULT;
    }
}