package com.sqlctx.cli.view;

import com.sqlctx.util.I18n;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * Bottom status bar cố định ở dòng cuối terminal (pgcli style).
 * Không dùng scroll region — chỉ save/restore cursor.
 */
public class BottomStatusBar {
    private boolean multiLine = false;
    private final Terminal terminal;
    private String dbInfo = "not connected";
    private String queryStatus = "idle";
    /** Dòng phía trên thanh: chữ ký hàm / lỗi cú pháp của câu đang gõ (xem InputHints); null = để trống. */
    private String hint;

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
            {"F1", "bar.help"},
            {"Ctrl T", "bar.multiline"},
            {"Ctrl F", "bar.format"},
            {"Esc", "bar.closePopup"},
            {"F10", "bar.quit"},
    };

    // Cùng 1 nền (36,36,36) xuyên suốt thanh - phân biệt từng mục bằng MÀU CHỮ thay vì đổi khối nền (đỡ
    // rối mắt hơn kiểu "powerline" nhiều khối màu), giống trước đây MỌI style đều dùng chung 1 màu trắng
    // phẳng nên các mục không phân biệt được với nhau.
    private static final AttributedStyle BAR_BG = AttributedStyle.DEFAULT.background(36, 36, 36);
    // foregroundRgb() nhận 1 số hex 24-bit thật (0xRRGGBB) - khác foreground(int) là màu THEO PALETTE 256
    // màu (chỉ dùng byte thấp làm index), dùng nhầm hàm đó với hex 0xffd166 từng ra màu index 102 sai bét.
    private static final AttributedStyle KEY_STYLE = BAR_BG.foregroundRgb(0xffd166).bold();
    private static final AttributedStyle LABEL_STYLE = BAR_BG.foreground(150, 150, 150);
    private static final AttributedStyle SEP_STYLE = BAR_BG.foreground(90, 90, 90);
    private static final AttributedStyle INFO_STYLE = BAR_BG.foregroundRgb(0x7fd8ff);
    private static final AttributedStyle CURSOR_STYLE = BAR_BG.foreground(150, 150, 150);

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

    public void setHint(String hint) {
        this.hint = hint;
    }

    private static final AttributedStyle HINT_STYLE = AttributedStyle.DEFAULT.foreground(244).italic();
    private static final AttributedStyle HINT_ARG_STYLE = AttributedStyle.DEFAULT.foreground(222).bold();
    private static final AttributedStyle HINT_ERROR_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0xff6b6b);

    /** Tham số đang gõ trong chữ ký ("[timestamp]") tô nổi; lỗi cú pháp tô đỏ. Cắt theo độ rộng terminal. */
    private AttributedString buildHint(int width) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        if (hint == null) {
            return sb.toAttributedString();
        }
        String text = hint.length() > width - 2 ? hint.substring(0, Math.max(0, width - 3)) + "…" : hint;
        if (text.startsWith("syntax error") || text.startsWith("lỗi cú pháp")) { // tiếng Anh / tiếng Việt (sql.hint.syntaxError)
            return sb.style(HINT_ERROR_STYLE).append(" ⚠ ").append(text).toAttributedString();
        }
        sb.style(HINT_STYLE).append(" ");
        int open = text.indexOf('[');
        int close = open < 0 ? -1 : text.indexOf(']', open);
        if (open < 0 || close < 0) {
            return sb.append(text).toAttributedString();
        }
        sb.append(text.substring(0, open));
        sb.style(HINT_ARG_STYLE).append(text.substring(open + 1, close));
        sb.style(HINT_STYLE).append(text.substring(close + 1));
        return sb.toAttributedString();
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
        attributedStrings.add(buildHint(width));
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

    /** Thứ tự ưu tiên giữ lại khi thanh hẹp (index trong MENU_ITEMS) - mục ít cần nhất bị bỏ trước. */
    private static final List<String> MENU_PRIORITY = List.of("F1", "Ctrl F", "Esc", "Ctrl T", "F10");

    /**
     * Phần thông tin (DB, Ln/Col, trạng thái query) luôn hiện; gợi ý phím chỉ hiện khi còn chỗ, theo
     * {@link #MENU_PRIORITY} nhưng vẫn đúng thứ tự trên thanh. Thông tin DB quá dài thì cắt "…".
     */
    private AttributedString buildBar(int width) {
        AttributedStringBuilder info = new AttributedStringBuilder();
        String cursor = String.format("Ln %d, Col %d", cursorRow, cursorCol);
        int fixed = 5 + 2 + 5 + cursor.length() + 5 + queryStatus.length() + 1; // 3 dấu "  │  ", icon, ...
        String db = dbInfo;
        int maxDb = width - fixed;
        if (db.length() > maxDb) {
            db = maxDb > 1 ? db.substring(0, maxDb - 1) + "…" : "";
        }
        info.style(SEP_STYLE).append("  │  ");
        info.style(INFO_STYLE).append("\uD83D\uDDA5 ").append(db);
        info.style(SEP_STYLE).append("  │  ");
        info.style(CURSOR_STYLE).append(cursor);
        info.style(SEP_STYLE).append("  │  ");
        info.style(queryStatusStyle()).append(queryStatus);
        AttributedString infoPart = info.toAttributedString();

        int room = width - infoPart.columnLength();
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (String key : MENU_PRIORITY) {
            for (String[] item : MENU_ITEMS) {
                if (item[0].equals(key)) {
                    int w = menuItemWidth(item);
                    if (w <= room) {
                        shown.add(key);
                        room -= w;
                    }
                }
            }
        }

        AttributedStringBuilder sb = new AttributedStringBuilder();
        for (String[] item : MENU_ITEMS) {
            if (!shown.contains(item[0])) {
                continue;
            }
            sb.style(KEY_STYLE).append(" [").append(item[0]).append("] ");
            sb.style(LABEL_STYLE).append(menuLabel(item)).append(" ");
        }
        sb.append(infoPart);

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

    private String menuLabel(String[] item) {
        return item[0].equals("Ctrl T") ? I18n.t("bar.multiline") + " " + (multiLine ? "ON" : "OFF") : I18n.t(item[1]);
    }

    private int menuItemWidth(String[] item) {
        return 2 + item[0].length() + 2 + menuLabel(item).length() + 1;
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