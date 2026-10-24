package com.sqlctx.lsp;

import org.eclipse.lsp4j.Position;

/** Đổi giữa (dòng, cột) của LSP và offset trong chuỗi. Cột LSP tính theo UTF-16 - đúng đơn vị index của String Java. */
final class Positions {

    private Positions() {
    }

    static int toOffset(String text, Position position) {
        int line = 0;
        int i = 0;
        while (line < position.getLine() && i < text.length()) {
            if (text.charAt(i++) == '\n') {
                line++;
            }
        }
        int lineEnd = text.indexOf('\n', i);
        int max = lineEnd < 0 ? text.length() : lineEnd;
        return Math.min(i + position.getCharacter(), max);
    }

    static Position toPosition(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, offset - lineStart);
    }
}
