package com.sqlctx.cli.repl;

import com.sqlctx.cli.terminal.CursorProbe;
import com.sqlctx.cli.terminal.MenuCompleter;
import com.sqlctx.cli.view.BottomStatusBar;
import org.jline.keymap.KeyMap;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.Status;

import java.nio.file.Path;

import static com.sqlctx.cli.session.SqlctxSession.MULTI_LINE;
import static com.sqlctx.cli.session.SqlctxSession.lastOutput;
import static com.sqlctx.cli.session.SqlctxSession.quitRequested;

/**
 * Dựng LineReaderImpl cho REPL: các hook JLine (đổi cỡ cửa sổ, self-insert/backward-delete/accept-line
 * để vẽ lại gợi ý, Ln/Col ở status bar), parser bắt buộc ';' khi đang multi-line, history, và keymap
 * (Ctrl+T toggle multi-line, F1 help, F10 quit) - tách khỏi SqlctxCli để main() không phải "biết" chi
 * tiết dựng JLine, chỉ cần gọi create() rồi dùng.
 */
public final class ReplLineReaderFactory {

    private ReplLineReaderFactory() {
    }

    public static LineReaderImpl create(Terminal terminal, BottomStatusBar statusBar, Path historyFile,
                                         Highlighter highlighter) {
        LineReaderImpl impl = new LineReaderImpl(terminal, "SLQ", null) {
            /**
             * Đổi cỡ cửa sổ: terminal tự xếp lại (reflow) phần đã in nên bảng cũ bị vỡ, và Status của JLine làm Display
             * tưởng con trỏ ở đầu dòng (prompt bị vẽ đôi). Xoá màn hình, in lại kết quả gần nhất theo cỡ mới,
             * rồi để JLine vẽ lại dòng nhập. Phần cũ hơn vẫn nằm trong scrollback.
             */
            @Override
            protected synchronized void handleSignal(Terminal.Signal signal) {
                if (signal == Terminal.Signal.WINCH) {
                    var out = getTerminal().writer();
                    out.print("\u001b[H\u001b[2J");
                    MenuCompleter.forgetMenu();
                    Runnable replay = lastOutput;
                    if (replay != null) {
                        replay.run();
                    }
                    out.flush();
                }
                super.handleSignal(signal);
            }

            /**
             * Gõ / xoá lùi / Enter dùng đúng hook JLine gọi cho self-insert/backward-delete-char/accept-line
             * (thay vì rebind phím qua keymap sang tên riêng) - Ctrl+R (reverse-i-search) nhận diện các
             * phím này bằng TÊN binding trong keymap, nên rebind tên khác từng khiến nó hỏng (xem MenuCompleter).
             */
            @Override
            protected boolean selfInsert() {
                boolean result = super.selfInsert();
                MenuCompleter.refreshAutosuggestion(this);
                return result;
            }

            @Override
            protected boolean backwardDeleteChar() {
                boolean result = super.backwardDeleteChar();
                MenuCompleter.refreshAutosuggestion(this);
                return result;
            }

            @Override
            protected boolean acceptLine() {
                MenuCompleter.hide();
                int lengthBefore = getBuffer().length();
                boolean result = super.acceptLine();
                // Multi-line chưa gặp ';' -> JLine chèn '\n' và vẫn đang nhập (buffer dài ra): vẽ lại gợi ý cho dòng mới.
                // Đã chấp nhận câu lệnh thì KHÔNG vẽ gợi ý nữa, kẻo nó đè lên kết quả in ra sau đó.
                boolean stillEditing = getBuffer().length() != lengthBefore;
                if (MULTI_LINE && stillEditing) {
                    MenuCompleter.refreshAutosuggestion(this);
                }
                return result;
            }

            @Override
            public boolean redisplay() {
                var result = super.redisplay();
                // Ln/Col tính từ buffer, KHÔNG hỏi terminal: redisplay còn chạy trên thread xử lý SIGWINCH,
                // truy vấn ESC[6n ở đó tranh đọc stdin với thread đang chờ phím (rụng phím, vẽ trùng prompt).
                String text = getBuffer().toString();
                int cursor = getBuffer().cursor();
                int line = 1;
                int lineStart = 0;
                for (int i = 0; i < cursor; i++) {
                    if (text.charAt(i) == '\n') {
                        line++;
                        lineStart = i + 1;
                    }
                }
                statusBar.updateCursor(line, cursor - lineStart + 1);
                statusBar.render();
                return result;
            }
        };

        CursorProbe.bind(impl);
        impl.setHighlighter(highlighter);
        impl.setVariable(LineReader.HISTORY_FILE, historyFile);
        impl.setVariable(LineReader.HISTORY_SIZE, 1000);
        impl.setVariable(LineReader.HISTORY_FILE_SIZE, 2000);
        impl.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, " ");
        impl.unsetOpt(LineReader.Option.HISTORY_BEEP);
        impl.setOpt(LineReader.Option.HISTORY_IGNORE_DUPS);
        impl.setOpt(LineReader.Option.HISTORY_IGNORE_SPACE);
        // '!' history expansion (bash-style) không dùng trong SQL, và tác dụng phụ của nó là ăn luôn MỌI dấu '\'
        // trong dòng đã gõ trước khi trả về - im lặng làm sai lệch chuỗi SQL (path, regex, E'...') chứa backslash.
        impl.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        // escapeChars rỗng: SQL không dùng '\' làm ký tự escape kiểu shell như DefaultParser mặc định giả định -
        // để mặc định thì '\' trong chuỗi SQL (path Windows, regex, E'...') bị âm thầm nuốt mất trước khi tới DB.
        DefaultParser sqlLineParser = new DefaultParser();
        sqlLineParser.setEscapeChars(new char[0]);
        impl.setParser(new Parser() {
            @Override
            public ParsedLine parse(String line, int cursor, ParseContext context)
                throws SyntaxError {
                if (context == ParseContext.ACCEPT_LINE && MULTI_LINE && !CommandDispatcher.isMetaCommand(line)) {
                    if (!line.trim().endsWith(";")) {
                        throw new EOFError(-1, cursor, "missing semicolon");
                    }
                }
                return sqlLineParser.parse(line, cursor, context);
            }
        });
        impl.setHistory(new org.jline.reader.impl.history.DefaultHistory(impl));

        MenuCompleter.register(impl);

        // Ngoài readLine (đang chạy query, đang chờ phím ở bảng bị cắt...) JLine không xử lý đổi cỡ cửa sổ,
        // status bar sẽ lệch và vỡ. readLine tự thay handler này khi chạy và trả lại khi xong.
        terminal.handle(Terminal.Signal.WINCH, signal -> {
            Status status = Status.getStatus(terminal, false);
            if (status != null) {
                status.resize();
            }
            statusBar.render();
        });

        // Ctrl+C ngoài readLine (đang chạy query) không được readLine bắt thành UserInterruptException -
        // terminal (thư viện JLine) tự xử lý SIGINT theo cách riêng, không đi qua vòng đời JVM bình thường
        // nên shutdown hook phía trên KHÔNG chạy, để lại terminal hỏng (đã kiểm chứng bằng thực nghiệm).
        // Đăng ký thẳng handler ở đây - readLine tự thay/trả lại handler này khi chạy, nên Ctrl+C lúc đang
        // gõ vẫn đi theo đường UserInterruptException như cũ, không bị handler này giành mất.
        // Có query đang chạy (currentStatement != null) thì HUỶ QUERY ĐÓ (Statement.cancel() - JDBC cho gọi
        // an toàn từ thread khác trong lúc execute() đang block) thay vì thoát cả process; execute() sẽ tự
        // ném lỗi "cancel" và rơi vào đúng đường báo lỗi bình thường (reportError). Không có gì đang chạy
        // (đang treo ở chỗ khác, vd chờ phím "press any key"/xác nhận huỷ) thì vẫn thoát như cũ - không có
        // gì để "huỷ" ở đó.
        //
        // BUG PHÁT HIỆN LÚC TEST: gọi thẳng System.exit() ở NHÁNH NÀY từng bị TREO CẢ PROCESS khi main
        // thread đang block trong chính 1 lần đọc phím raw khác (confirmProceed()'s terminal.reader().read()
        // ở prompt xác nhận huỷ, hoặc vòng chờ "press any key" của bảng bị cắt) - shutdown hook (đóng
        // Status/terminal) tranh lock với lần đọc raw đó, System.exit() không bao giờ quay lại. Chạy exit()
        // trên 1 thread riêng kèm timeout: dọn dẹp sạch (đúng ý ban đầu) nếu kịp, không thì halt() cưỡng
        // chế thoát ngay - thà terminal bẩn còn hơn treo process phải kill -9 từ bên ngoài.
        terminal.handle(Terminal.Signal.INT, signal -> {
            java.sql.Statement running = com.sqlctx.cli.session.SqlctxSession.currentStatement;
            if (running != null) {
                try {
                    running.cancel();
                } catch (Exception ignored) {
                }
            } else {
                Thread exitThread = new Thread(() -> System.exit(130));
                exitThread.setDaemon(true);
                exitThread.start();
                try {
                    exitThread.join(500);
                } catch (InterruptedException ignored) {
                }
                if (exitThread.isAlive()) {
                    Runtime.getRuntime().halt(130);
                }
            }
        });

        KeyMap<Binding> keyMap = impl.getKeyMaps().get(LineReader.MAIN);

        keyMap.bind(new Reference("toggle-multiline"), "\u0014");

        impl.getWidgets().put("toggle-multiline", () -> {
            MULTI_LINE = !MULTI_LINE;

            statusBar.toggleMultiLine();
            MenuCompleter.toggleMultiLine();
            statusBar.setQueryStatus("multi-line: " + (MULTI_LINE ? "ON" : "OFF"));
            statusBar.render();

            return true;
        });

        // Thanh trạng thái quảng cáo F1/F10 nên phải bấm được thật, không chỉ nằm trên hình.
        impl.getWidgets().put("show-help", () -> {
            CommandDispatcher.printHelp(impl);
            statusBar.render();
            return true;
        });
        impl.getWidgets().put("quit-cli", () -> {
            quitRequested = true;
            impl.getBuffer().clear();
            impl.callWidget(LineReader.ACCEPT_LINE);
            return true;
        });
        // "\u001b[57440u": phím F1 vật lý trên máy người dùng thực ra gửi mã CSI-u của phím "Mute Volume"
        // (kitty keyboard protocol, xem functional key table) chứ KHÔNG PHẢI mã F1 thật - do Fn-lock của
        // bàn phím/hệ điều hành đổi hàng F1-F12 thành phím media mặc định. Terminfo key_f1 không khớp được
        // chuỗi này nên nó rơi qua self-insert, hiện rác "[57440u" ra ngay dòng lệnh. Gán thêm đúng chuỗi
        // này cho help để khớp với những gì bàn phím NGƯỜI DÙNG THẬT SỰ gửi khi họ bấm phím họ gọi là "F1".
        keyMap.bind(new Reference("show-help"), KeyMap.key(terminal, Capability.key_f1), "\u001b[57440u");
        keyMap.bind(new Reference("quit-cli"), KeyMap.key(terminal, Capability.key_f10));

        return impl;
    }
}
