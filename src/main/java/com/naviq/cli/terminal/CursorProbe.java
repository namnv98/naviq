package com.naviq.cli.terminal;

import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Cursor;
import org.jline.terminal.Terminal;

/**
 * Hỏi terminal vị trí con trỏ (ESC[6n) mà không làm mất phím người dùng gõ / dán trong lúc chờ trả lời.
 * <p>
 * {@code Terminal.getCursorPosition(null)} vứt mọi ký tự đọc được trước khi câu trả lời tới, nên gõ nhanh hoặc dán
 * nhiều ký tự sẽ bị rụng. Ở đây các ký tự đó được gom lại và trả về hàng đợi phím của LineReader.
 */
public final class CursorProbe {

    private static volatile LineReaderImpl reader;

    private CursorProbe() {
    }

    public static void bind(LineReaderImpl lineReader) {
        reader = lineReader;
    }

    public static Cursor query(Terminal terminal) {
        StringBuilder typedAhead = new StringBuilder();
        Cursor cursor = terminal.getCursorPosition(typedAhead::appendCodePoint);
        LineReaderImpl target = reader;
        if (typedAhead.length() > 0 && target != null) {
            target.runMacro(typedAhead.toString());
        }
        return cursor;
    }
}
