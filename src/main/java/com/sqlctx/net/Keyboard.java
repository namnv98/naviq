package com.sqlctx.net;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Đọc phím trong lúc bảng đang chạy (lọc, tạm dừng...). Terminal ở chế độ raw: phím tới ngay không cần Enter và không
 * hiện lên màn hình; Ctrl+C vẫn là tín hiệu dừng như thường. Trả lại chế độ cũ khi thoát.
 */
final class Keyboard {

    private final Terminal terminal;
    private final Attributes previous;
    private volatile boolean closed;

    private Keyboard(Terminal terminal, Attributes previous) {
        this.terminal = terminal;
        this.previous = previous;
    }

    /** null nếu không có terminal thật (chạy qua pipe, ghi ra file...). */
    static Keyboard open() {
        if (System.console() == null || !NetFormat.stdoutIsTerminal()) {
            return null;
        }
        try {
            // ffm(false): lý do như SqlctxCli - provider FFM cài handler tín hiệu native làm JVM chết khi Ctrl+C
            Terminal t = TerminalBuilder.builder().system(true).ffm(false).encoding(StandardCharsets.UTF_8).build();
            if (Terminal.TYPE_DUMB.equals(t.getType()) || Terminal.TYPE_DUMB_COLOR.equals(t.getType())) {
                t.close();
                return null;
            }
            return new Keyboard(t, t.enterRawMode());
        } catch (IOException e) {
            return null;
        }
    }

    void start(Consumer<String> onKey) {
        Thread reader = new Thread(() -> {
            NonBlockingReader in = terminal.reader();
            try {
                while (!closed) {
                    int c = in.read(200);
                    if (c < 0) {
                        if (c == NonBlockingReader.EOF) {
                            return;
                        }
                        continue;
                    }
                    StringBuilder key = new StringBuilder().appendCodePoint(c);
                    if (c == 27) { // Esc hoặc phím mũi tên / F (Esc [ A...): đọc nốt phần đuôi gửi liền sau
                        int next;
                        while ((next = in.read(15)) >= 0) {
                            key.appendCodePoint(next);
                        }
                    }
                    onKey.accept(key.toString());
                }
            } catch (IOException ignored) {
            }
        }, "net-keyboard");
        reader.setDaemon(true);
        reader.start();
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            terminal.setAttributes(previous);
            terminal.close();
        } catch (IOException | IllegalStateException ignored) {
            // JLine có shutdown hook riêng, có thể đã đóng (và tự trả chế độ cũ) trước - không sao
        }
    }
}
