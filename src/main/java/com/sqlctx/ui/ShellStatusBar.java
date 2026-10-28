package com.sqlctx.ui;

import com.sqlctx.util.I18n;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.util.List;

/**
 * 2 dòng cuối màn hình của các chế độ không phải SQL: dòng gợi ý cú pháp lệnh đang gõ, và thanh trạng thái (chế
 * độ, kết nối, trạng thái lệnh). Cùng kiểu dáng với BottomStatusBar của phía SQL nhưng không có các phím chỉ phía
 * SQL mới có (multi-line, format).
 */
public final class ShellStatusBar {

    private static final AttributedStyle BAR_BG = AttributedStyle.DEFAULT.background(36, 36, 36);
    private static final AttributedStyle KEY_STYLE = BAR_BG.foregroundRgb(0xffd166).bold();
    private static final AttributedStyle LABEL_STYLE = BAR_BG.foreground(150, 150, 150);
    private static final AttributedStyle SEP_STYLE = BAR_BG.foreground(90, 90, 90);
    private static final AttributedStyle INFO_STYLE = BAR_BG.foregroundRgb(0x7fd8ff);
    private static final AttributedStyle READONLY_STYLE = BAR_BG.foregroundRgb(0xffd166).bold();
    private static final AttributedStyle STATUS_ERROR = BAR_BG.foregroundRgb(0xff6b6b).bold();
    private static final AttributedStyle STATUS_BUSY = BAR_BG.foregroundRgb(0xffd166).bold();
    private static final AttributedStyle STATUS_IDLE = BAR_BG.foregroundRgb(0x6bcf7f);
    private static final AttributedStyle HINT_STYLE = AttributedStyle.DEFAULT.foreground(244).italic();
    private static final AttributedStyle HINT_ERROR_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0xff6b6b);

    private static final String[][] MENU_ITEMS = {
            {"Tab", "bar.suggest"},
            {"F1", "bar.help"},
            {"F10", "bar.quit"},
    };

    private final Terminal terminal;
    private final String mode;
    private volatile String info = "";
    private volatile boolean readonly;
    private volatile String status = "idle";
    private volatile String hint;
    private volatile boolean hintIsError;

    /** {@code mode}: tên chế độ đứng đầu phần thông tin kết nối ("redis", "kafka"). */
    public ShellStatusBar(Terminal terminal, String mode) {
        this.terminal = terminal;
        this.mode = mode;
    }

    public void setInfo(String info, boolean readonly) {
        this.info = info;
        this.readonly = readonly;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setHint(String hint, boolean error) {
        this.hint = hint;
        this.hintIsError = error;
    }

    public void render() {
        int width = Math.max(terminal.getWidth(), 40);
        Status.getStatus(terminal).update(List.of(buildHint(width), buildBar(width)));
    }

    /** Sau khi màn hình bị xoá (GridView, đổi cỡ): Status chỉ vẽ khi nội dung đổi nên phải ép vẽ lại. */
    public void redraw() {
        Status s = Status.getStatus(terminal, false);
        if (s != null) {
            s.resize();
            s.reset();
            s.redraw();
        }
        render();
    }

    private AttributedString buildHint(int width) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        String h = hint;
        if (h == null || h.isEmpty()) {
            return sb.toAttributedString();
        }
        String text = h.length() > width - 4 ? h.substring(0, Math.max(0, width - 5)) + "…" : h;
        if (hintIsError) {
            return sb.style(HINT_ERROR_STYLE).append(" ⚠ ").append(text).toAttributedString();
        }
        return sb.style(HINT_STYLE).append(" ").append(text).toAttributedString();
    }

    private AttributedString buildBar(int width) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        for (String[] item : MENU_ITEMS) {
            sb.style(KEY_STYLE).append(" [").append(item[0]).append("] ");
            sb.style(LABEL_STYLE).append(I18n.t(item[1])).append(" ");
        }
        sb.style(SEP_STYLE).append("  │  ");
        sb.style(INFO_STYLE).append(mode + " ").append(info);
        if (readonly) {
            sb.style(SEP_STYLE).append("  │  ");
            sb.style(READONLY_STYLE).append("READONLY");
        }
        sb.style(SEP_STYLE).append("  │  ");
        String s = status;
        String upper = s.toUpperCase();
        AttributedStyle st = upper.contains("ERROR") ? STATUS_ERROR
                : upper.contains("RUNNING") || upper.contains("TX") || upper.contains("SUBSCRIBE") ? STATUS_BUSY
                : STATUS_IDLE;
        sb.style(st).append(s);
        AttributedString line = sb.toAttributedString();
        if (line.columnLength() > width) {
            return line.columnSubSequence(0, width);
        }
        AttributedStringBuilder padded = new AttributedStringBuilder().append(line);
        padded.style(SEP_STYLE).append(" ".repeat(width - line.columnLength()));
        return padded.toAttributedString();
    }
}
