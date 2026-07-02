package com.naviq.cli.layout;

import org.jline.reader.impl.LineReaderImpl;
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

    private static final AttributedStyle KEY_STYLE = AttributedStyle.DEFAULT.background(36, 36, 36).foreground(0xffffff);
    private static final AttributedStyle LABEL_STYLE = AttributedStyle.DEFAULT.background(36, 36, 36).foreground(0xffffff);
    private static final AttributedStyle SEP_STYLE = AttributedStyle.DEFAULT.background(36, 36, 36).foreground(0xffffff);
    private static final AttributedStyle INFO_STYLE = AttributedStyle.DEFAULT.background(36, 36, 36).foreground(0xffffff);
    private static final AttributedStyle STATUS_STYLE = AttributedStyle.DEFAULT.background(36, 36, 36).foreground(0xffffff);

    public BottomStatusBar(Terminal terminal) {
        this.terminal = terminal;
    }

    public void setDbInfo(String info) {
        this.dbInfo = info;
    }

    public void setQueryStatus(String s) {
        this.queryStatus = s;
    }

    private void updateAndRender(LineReaderImpl impl) {
        var c = impl.getTerminal().getCursorPosition(null);
        if (c != null) {
            updateCursor(c.getY() + 1, c.getX() + 1);
        }
        render();
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
        sb.style(STATUS_STYLE).append(String.format("Ln %d, Col %d", cursorRow, cursorCol));


        sb.style(SEP_STYLE).append("  │  ");
        sb.style(STATUS_STYLE).append(queryStatus);


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
}