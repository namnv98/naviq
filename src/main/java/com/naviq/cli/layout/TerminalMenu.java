package com.naviq.cli.layout;

import com.naviq.cli.anchor.Anchor;
import com.naviq.cli.anchor.AnchorStrategy;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class TerminalMenu {
    private final LineReader reader;
    private int menuRow = -1;
    private int lastHeight = 0;
    private List<String> lastRendered = new ArrayList<>();
    private int lastDrawCol = 1;
    private int lastMenuRow = -1;
    private boolean visible = false;

    public TerminalMenu(LineReader reader) {
        this.reader = reader;
    }


    public void show(List<AttributedString> lines, AnchorStrategy strategy) {
        show(lines, strategy, 1, 1);
    }

    /**
     * Hiển thị menu (list các dòng) theo vị trí tương đối với cursor hiện tại.
     *
     * @param lines          Danh sách các dòng sẽ render (menu content).
     * @param strategy       Strategy đặt vị trí menu
     * @param gapBelowCursor Khoảng cách (tính theo số dòng) giữa cursor và dòng đầu tiên của menu.
     * @param bottomPadding  Số dòng được chừa ở đáy terminal (không cho menu đè lên). Dùng để tránh đè footer hoặc giữ khoảng trống UI.
     */
    public void show(List<AttributedString> lines, AnchorStrategy strategy, int gapBelowCursor, int bottomPadding) {
        Terminal term = reader.getTerminal();
        PrintWriter out = term.writer();
        int width = term.getWidth();
        int termHeight = term.getHeight();

        reader.callWidget(LineReader.REDISPLAY);

        Anchor anchor = strategy.resolve(term);
        int cursorAbsRow = anchor.row();
        int cursorAbsCol = anchor.col();

        int height = anchor.height();

        int lastVisibleRow = termHeight - bottomPadding;
        int menuStartRow = cursorAbsRow + gapBelowCursor;
        int menuEndRow = menuStartRow + height - bottomPadding;

        if (menuEndRow > lastVisibleRow) {
            int scrollLines = menuEndRow - lastVisibleRow;
            out.print("\u001b[" + scrollLines + "S");
            cursorAbsRow -= scrollLines;
            menuStartRow -= scrollLines;
            out.print("\u001b[" + cursorAbsRow + ";" + cursorAbsCol + "H");
            lastRendered.clear(); // vị trí đổi → vẽ lại hết
            reader.callWidget(LineReader.REDISPLAY);
            out.flush();
        }

        int menuWidth = lines.stream().mapToInt(AttributedString::columnLength).max().orElse(0);
        int drawCol = Math.min(cursorAbsCol, width - menuWidth);
        if (drawCol < 1) drawCol = 1;

//        Cursor savedCursor = term.getCursorPosition(null);
//        int savedRow = savedCursor.getY() + 1;
//        int savedCol = savedCursor.getX() + 1;
        out.print("\u001b[s");

        // Xóa dòng thừa ở VỊ TRÍ CŨ trước khi vẽ mới
        if (lastMenuRow >= 0 && (lastDrawCol != drawCol || lastMenuRow != menuStartRow)) {
            for (int i = 0; i < lastRendered.size(); i++) {
                out.print("\u001b[" + (lastMenuRow + i) + ";" + lastDrawCol + "H");
                out.print("\u001b[2K");
            }
            lastRendered.clear();
        }

        // Diff update
        for (int i = 0; i < height; i++) {
            String ansi = lines.get(i).toAnsi();
            String cached = i < lastRendered.size() ? lastRendered.get(i) : null;
            if (!ansi.equals(cached)) {
                out.print("\u001b[" + (menuStartRow + i) + ";" + drawCol + "H");
                out.print(ansi);
                out.print("\u001b[K");
            }
        }
        // Xóa dòng thừa nếu menu co lại (cùng vị trí)
        for (int i = height; i < lastRendered.size(); i++) {
            out.print("\u001b[" + (menuStartRow + i) + ";" + drawCol + "H");
            out.print("\u001b[2K");
        }

//        out.print("\u001b[" + savedRow + ";" + savedCol + "H");
        out.print("\u001b[u");

        // Cập nhật cache
        lastRendered = new ArrayList<>();
        for (int i = 0; i < height; i++) {
            lastRendered.add(lines.get(i).toAnsi());
        }
        lastDrawCol = drawCol;
        lastMenuRow = menuStartRow;

        menuRow = menuStartRow;
        lastHeight = height;

        reader.callWidget(LineReader.REDISPLAY);
        term.flush();
        visible = true;
    }

    public void hide() {
        if (!visible) {
            return;
        }
        clearMenu(reader.getTerminal().writer());
        lastRendered.clear();
        lastMenuRow = -1;
        visible = false;
        reader.callWidget(LineReader.REDISPLAY);
        reader.getTerminal().flush();
    }

    // ─── clearMenu ────────────────────────────────────────────────────────────
    public void clearMenu(PrintWriter out) {
        if (lastHeight <= 0 || menuRow < 0) return;
        out.print("\u001b[s");
        for (int i = 0; i < lastHeight; i++) {
            out.print("\u001b[" + (menuRow + i) + ";1H");
            out.print("\u001b[2K");
        }
        out.print("\u001b[u");
        lastHeight = 0;
        menuRow = -1;
    }
}