package com.naviq.cli.anchor;

import org.jline.terminal.Cursor;
import org.jline.utils.AttributedString;

import java.util.List;

public class AnchorStrategyUtil {
    public static AnchorStrategy smart(List<AttributedString> lines, int maxVisible) {
        return term -> {
            term.writer().flush();
            Cursor c = term.getCursorPosition(null);
            if (c == null) return null;

            int cursorRow = c.getY() + 1;
            int cursorCol = c.getX() + 1;
            int termHeight = term.getHeight();
            int termWidth = term.getWidth();
            int h = Math.min(lines.size(), maxVisible);
            int menuWidth = lines.stream().mapToInt(AttributedString::columnLength).max().orElse(0);

            // Chọn cột: ưu tiên bên phải, nếu tràn thì sang trái
            boolean canRight = (cursorCol + menuWidth) <= termWidth;

            int col = canRight ? cursorCol : (cursorCol - menuWidth);

            return new Anchor(cursorRow, col, h);
        };
    }

    public static AnchorStrategy at(Position position, List<AttributedString> lines, int maxVisible) {
        return term -> {
            term.writer().flush();
            Cursor c = term.getCursorPosition(null);
            if (c == null) return null;

            // cursor: 0-based từ JLine → convert 1-based
            int cursorRow = c.getY() + 1;
            int cursorCol = c.getX() + 1;
            int h = Math.min(lines.size(), maxVisible);
            int menuWidth = lines.stream().mapToInt(AttributedString::columnLength).max().orElse(0);

            return switch (position) {
                // ── Bên dưới ──────────────────────────────────
                case BELOW_LEFT -> new Anchor(cursorRow, cursorCol - menuWidth, h);
                case BELOW_RIGHT -> new Anchor(cursorRow, cursorCol, h);
                case BELOW_CENTER -> new Anchor(cursorRow, cursorCol - menuWidth / 2, h);
            };
        };
    }

    /**
     * Vẽ tại vị trí cố định.
     */
    public static AnchorStrategy fixedPosition(int row, int col, int h) {
        return term -> new Anchor(row, col, h);
    }

    /**
     * Vẽ tại vị trí bất kỳ do caller tính toán.
     */
    public static AnchorStrategy at(int row, int col, int h) {
        return fixedPosition(row, col, h);
    }
}
