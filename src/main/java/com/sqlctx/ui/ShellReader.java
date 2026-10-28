package com.sqlctx.ui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.Status;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Dựng ô nhập cho các chế độ không phải SQL (Redis, Kafka): popup gợi ý theo từng phím gõ, dòng gợi ý cú pháp,
 * lịch sử, Ctrl+C dừng lệnh đang chạy (thay vì thoát), F1 help, F10 thoát, đổi cỡ cửa sổ.
 */
public final class ShellReader {

    private ShellReader() {
    }

    /**
     * @param escapeBackslash true: '\' cuối dòng là nối dòng như shell (Kafka: lệnh kafka-*.sh dài, hay dán nhiều
     *                        dòng); false: '\' giữ nguyên trong giá trị (Redis: "\x00", "a\"b")
     * @param onRedisplay     (buffer, con trỏ) sau mỗi lần vẽ lại - cập nhật dòng gợi ý cú pháp
     * @param running         đang chạy lệnh (Ctrl+C gọi {@code cancel} thay vì thoát)
     * @param onQuit          F10
     */
    public record Config(String appName, Path history, Highlighter highlighter, boolean escapeBackslash,
                         BiConsumer<String, Integer> onRedisplay, BiFunction<String, Integer, List<MenuItem>> suggester,
                         Supplier<String> helpText, BooleanSupplier running, Runnable cancel, Runnable onQuit,
                         ShellStatusBar statusBar) {
    }

    public record Shell(LineReaderImpl reader, CompletionMenu menu) {
    }

    public static Shell create(Terminal terminal, Config config) {
        CompletionMenu[] menu = new CompletionMenu[1];
        LineReaderImpl impl = new LineReaderImpl(terminal, config.appName(), null) {
            @Override
            public boolean redisplay() {
                boolean result = super.redisplay();
                config.onRedisplay().accept(getBuffer().toString(), getBuffer().cursor());
                return result;
            }

            // popup gợi ý theo từng phím gõ / xoá - cùng hook với ReplLineReaderFactory bên SQL (không rebind phím,
            // để Ctrl+R vẫn nhận ra các phím này)
            @Override
            protected boolean selfInsert() {
                boolean result = super.selfInsert();
                menu[0].refresh();
                return result;
            }

            @Override
            protected boolean backwardDeleteChar() {
                boolean result = super.backwardDeleteChar();
                menu[0].refresh();
                return result;
            }

            @Override
            protected boolean acceptLine() {
                menu[0].hide(); // không để popup đè lên kết quả in ra sau đó
                return super.acceptLine();
            }

            @Override
            protected synchronized void handleSignal(Terminal.Signal signal) {
                if (signal == Terminal.Signal.WINCH) {
                    menu[0].forget(); // terminal xếp lại màn hình, popup cũ không còn đúng chỗ
                }
                super.handleSignal(signal);
            }
        };
        impl.setHighlighter(config.highlighter());
        impl.setVariable(LineReader.HISTORY_FILE, config.history());
        impl.setVariable(LineReader.HISTORY_SIZE, 1000);
        impl.setVariable(LineReader.HISTORY_FILE_SIZE, 2000);
        impl.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "> ");
        impl.setOpt(LineReader.Option.HISTORY_IGNORE_DUPS);
        impl.setOpt(LineReader.Option.CASE_INSENSITIVE);
        impl.unsetOpt(LineReader.Option.HISTORY_BEEP);
        // cùng lý do với phía SQL: '!' kiểu shell nuốt mất ký tự trong giá trị gõ vào
        impl.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        DefaultParser parser = new DefaultParser();
        if (!config.escapeBackslash()) {
            parser.setEscapeChars(new char[0]);
        }
        impl.setParser(parser);
        impl.setHistory(new org.jline.reader.impl.history.DefaultHistory(impl));
        menu[0] = new CompletionMenu(impl, config.suggester());
        menu[0].register();
        com.sqlctx.ui.CursorProbe.bind(impl); // popup hỏi vị trí con trỏ mà không làm rụng phím gõ nhanh

        terminal.handle(Terminal.Signal.INT, signal -> {
            if (config.running().getAsBoolean()) {
                config.cancel().run();
                return;
            }
            Thread exit = new Thread(() -> System.exit(130));
            exit.setDaemon(true);
            exit.start();
            try {
                exit.join(500);
            } catch (InterruptedException ignored) {
            }
            if (exit.isAlive()) {
                Runtime.getRuntime().halt(130);
            }
        });
        terminal.handle(Terminal.Signal.WINCH, signal -> {
            Status status = Status.getStatus(terminal, false);
            if (status != null) {
                status.resize();
            }
            config.statusBar().render();
        });

        impl.getWidgets().put("shell-help", () -> {
            impl.printAbove(config.helpText().get());
            return true;
        });
        impl.getWidgets().put("shell-quit", () -> {
            config.onQuit().run();
            impl.getBuffer().clear();
            impl.callWidget(LineReader.ACCEPT_LINE);
            return true;
        });
        KeyMap<Binding> keyMap = impl.getKeyMaps().get(LineReader.MAIN);
        // "\u001b[57440u": phím F1 trên bàn phím có Fn-lock gửi mã của phím Mute (xem ReplLineReaderFactory)
        keyMap.bind(new Reference("shell-help"), KeyMap.key(terminal, Capability.key_f1), "\u001b[57440u");
        keyMap.bind(new Reference("shell-quit"), KeyMap.key(terminal, Capability.key_f10));
        return new Shell(impl, menu[0]);
    }
}
