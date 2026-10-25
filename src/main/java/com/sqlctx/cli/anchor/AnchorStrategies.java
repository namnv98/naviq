package com.sqlctx.cli.anchor;

import com.sqlctx.cli.terminal.CursorProbe;
import org.jline.terminal.Cursor;
import org.jline.utils.AttributedString;

import java.util.List;

public class AnchorStrategies {
    public static AnchorStrategy smart(List<AttributedString> lines, int maxVisible) {
        return term -> {
            term.writer().flush();
            Cursor c = CursorProbe.query(term);
            if (c == null) return null;

            int cursorRow = c.getY() + 1;
            int cursorCol = c.getX() + 1;
            int termHeight = term.getHeight();
            int termWidth = term.getWidth();
            int h = Math.min(lines.size(), maxVisible);
            int menuWidth = lines.stream().mapToInt(AttributedString::columnLength).max().orElse(0);

            // Không đủ chỗ bên phải thì chỉ dịch sang trái vừa đủ cho khỏi tràn mép, không lật hẳn sang bên trái con
            // trỏ: terminal hẹp (vd thu nửa màn hình) popup từng nhảy về cột 1, cách xa chữ đang gõ.
            int col = Math.max(1, Math.min(cursorCol, termWidth - menuWidth + 1));

            return new Anchor(cursorRow, col, h);
        };
    }

    public static AnchorStrategy at(Position position, List<AttributedString> lines, int maxVisible) {
        return term -> {
            term.writer().flush();
            Cursor c = CursorProbe.query(term);
            if (c == null) return null;

            // cursor: 0-based từ JLine → convert 1-based
            int cursorRow = c.getY() + 1;
            int cursorCol = c.getX() + 1;
            int h = Math.min(lines.size(), maxVisible);
            int menuWidth = lines.stream().mapToInt(AttributedString::columnLength).max().orElse(0);

            return switch (position) {
                case BELOW_LEFT -> new Anchor(cursorRow, cursorCol - menuWidth, h);
                case BELOW_RIGHT -> new Anchor(cursorRow, cursorCol, h);
                case BELOW_CENTER -> new Anchor(cursorRow, cursorCol - menuWidth / 2, h);
            };
        };
    }

    public static AnchorStrategy fixedPosition(int row, int col, int h) {
        return term -> new Anchor(row, col, h);
    }

    public static AnchorStrategy at(int row, int col, int h) {
        return fixedPosition(row, col, h);
    }
}
