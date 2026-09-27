package com.sqlctx.cli;

import com.sqlctx.cli.view.BottomStatusBar;
import com.sqlctx.cli.view.DataViewTable;
import com.sqlctx.cli.terminal.CursorProbe;
import com.sqlctx.cli.terminal.SqlHighlighter;
import com.sqlctx.cli.terminal.MenuCompleter;
import com.sqlctx.datasource.ConnectionProfile;
import com.sqlctx.datasource.ConnectionProfileStore;
import com.sqlctx.datasource.OracleDataSource;
import com.sqlctx.datasource.PostgresDataSource;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import org.jline.keymap.KeyMap;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SqlctxCli {

    private static boolean MULTI_LINE = false;
    private static volatile boolean quitRequested = false;
    /** true khi chạy --file/--command (không có TTY thật, không cần vẽ/xoá màn hình gì cả). */
    private static volatile boolean BATCH_MODE = false;

    /** Cách in lại kết quả gần nhất (dùng khi đổi cỡ cửa sổ). */
    private static volatile Runnable lastOutput;

    /** Câu lệnh gần nhất đã chạy (kể cả chạy lỗi) - dùng cho \e (mở $EDITOR sửa lại). */
    private static volatile String lastStatement = "";

    /** \o <file> đang bật - null nghĩa là không xuất file, chỉ in ra màn hình như bình thường. */
    private static volatile Path outputFile;
    /** "csv" | "json" | "text" - suy ra từ đuôi file lúc \o <file>. csv/json là xuất 1 lần (tự tắt sau khi
     *  ghi xong câu lệnh kế tiếp); text là ghi nối tiếp (log liên tục) cho tới khi người dùng gõ \o tắt. */
    private static volatile String outputFormat;

    /** Tên context (kiểu kubectx) đang dùng - null nếu phiên này khởi động bằng --host=/... tay, không qua --context=. */
    private static volatile String currentContextName = null;
    /**
     * Connection ĐANG SỐNG ngay TRƯỚC lần "\ctx" gần nhất - chụp lại TOÀN BỘ thông số (không chỉ tên),
     * để "\ctx -" quay lại được đúng chỗ ngay cả khi chỗ đó chưa từng có tên (vd connection lúc mới mở CLI).
     */
    private static volatile ConnectionProfile previousConnection = null;

    private static void say(LineReader reader, String text) {
        reader.printAbove(text);
        lastOutput = () -> {
            var out = reader.getTerminal().writer();
            out.print(text);
            if (!text.endsWith("\n")) {
                out.print("\n");
            }
        };
    }

    private static final String RED = "\u001b[31m";
    private static final String YELLOW = "\u001b[33m";
    private static final String RESET = "\u001b[0m";
    private static final String DIM = "\u001b[38;5;245m"; // nhãn (label) trong banner khởi động - mờ để giá trị thật nổi bật hơn
    private static final String TITLE_COLOR = "\u001b[1;36m"; // tiêu đề banner - đậm cyan, đồng bộ màu header bảng kết quả
    private static final String GREEN = "\u001b[1;32m"; // dòng "Connected:" - thông tin quan trọng nhất, màu "thành công"

    /**
     * "--context=tên" cho 2 việc trong 1 lần gõ:
     * - Tên ĐÃ CÓ trong ~/.sqlctx/connections.conf: nạp lại, chỉ điền phần chưa được truyền tường minh trên
     *   dòng lệnh (--host=/... gõ kèm vẫn thắng nếu trùng).
     * - Tên CHƯA CÓ nhưng dòng lệnh gõ ĐỦ --host=/--port=/--dbname=/--user=/--password=: coi đây là lần
     *   đầu kết nối tới nơi này, TỰ LƯU LUÔN thành context mới - lần sau chỉ cần gõ --context=tên là đủ,
     *   khỏi phải nhớ/gõ lại toàn bộ --host=.../--password=... như lần đầu.
     */
    private static void applyContext(String name) {
        var existing = ConnectionProfileStore.find(name);
        if (existing.isPresent()) {
            ConnectionProfile p = existing.get();
            setIfAbsent("DB_DIALECT", p.dialect().name().toLowerCase());
            setIfAbsent("DB_HOST", p.host());
            setIfAbsent("DB_PORT", p.port());
            setIfAbsent("DB_DBNAME", p.dbname());
            setIfAbsent("DB_USER", p.user());
            setIfAbsent("DB_PASSWORD", p.password());
            return;
        }

        String host = System.getProperty("DB_HOST");
        String port = System.getProperty("DB_PORT");
        String dbname = System.getProperty("DB_DBNAME");
        String user = System.getProperty("DB_USER");
        String password = System.getProperty("DB_PASSWORD");
        if (host == null || port == null || dbname == null || user == null || password == null) {
            throw new RuntimeException("Không có context tên \"" + name + "\", và dòng lệnh cũng chưa gõ đủ "
                    + "--host=/--port=/--dbname=/--user=/--password= để tự lưu thành context mới - "
                    + "xem \\ctx (sau khi đã nối được lần nào đó) để biết tên đã lưu.");
        }
        Dialect dialect = "oracle".equalsIgnoreCase(System.getProperty("DB_DIALECT", "postgres"))
                ? Dialect.ORACLE : Dialect.POSTGRES;
        try {
            ConnectionProfileStore.save(new ConnectionProfile(name, dialect, host, port, dbname, user, password));
            System.err.println("Đã lưu context \"" + name + "\" - lần sau chỉ cần: --context=" + name);
        } catch (Exception e) {
            System.err.println("WARNING: không lưu được context \"" + name + "\" - " + e.getMessage());
        }
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null && value != null) {
            System.setProperty(key, value);
        }
    }

    private static java.sql.Connection connection(Dialect dialect) {
        return dialect == Dialect.ORACLE ? OracleDataSource.get() : PostgresDataSource.get();
    }

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                String[] parts = arg.substring(2).split("=", 2);
                System.setProperty("DB_" + parts[0].toUpperCase(), parts[1]);
            }
        }
        System.setProperty("org.jline.terminal.type", "xterm-256color");
        com.sqlctx.util.LoggingConfig.init();

        // --context=<tên> (kiểu kubectx): nạp connection đã LƯU SẴN (~/.sqlctx/connections.conf) - chỉ điền
        // vào những gì CHƯA được truyền tường minh qua --host=/--port=/... khác trên cùng dòng lệnh, để
        // tham số gõ tay luôn thắng, tránh bất ngờ khi vừa dùng --context vừa muốn override 1-2 giá trị.
        String contextArg = System.getProperty("DB_CONTEXT");
        // Không gõ GÌ CẢ (không --context=, cũng không tự tay --host=/...) - dùng lại context lần trước đã
        // dùng THÀNH CÔNG, giống kubeconfig tự nhớ "current-context" giữa các lần gọi kubectl khác nhau.
        if (contextArg == null && System.getProperty("DB_HOST") == null) {
            contextArg = ConnectionProfileStore.getLastContextName().orElse(null);
        }
        if (contextArg != null) {
            applyContext(contextArg);
            currentContextName = contextArg;
            ConnectionProfileStore.setLastContextName(contextArg);
        }

        // --dialect=oracle|postgres (mặc định postgres) - đã được set vào system property DB_DIALECT
        // bởi vòng lặp parse arg chung ở trên (hoặc bởi applyContext() ở trên), không cần parse riêng.
        Dialect dialect = "oracle".equalsIgnoreCase(System.getProperty("DB_DIALECT", "postgres"))
                ? Dialect.ORACLE : Dialect.POSTGRES;
        try {
            SchemaIndex.reload(dialect);
        } catch (Exception e) {
            // Kết nối lỗi ngay lúc khởi động (sai host/port/user/password, DB chưa bật...) - trước đây
            // ném thẳng RuntimeException lồng nhau ra ngoài, JVM in cả stack trace ~20 dòng (kể cả
            // internal frame của pgjdbc/socket) ra màn hình. Chỉ hiện thông điệp gốc, gọn, giống các
            // lỗi khác trong app, rồi thoát sạch - không có gì để chạy tiếp nếu không kết nối được.
            System.err.println(RED + "ERROR: không kết nối được tới database - " + rootCauseMessage(e) + RESET);
            System.exit(1);
        }

        Terminal terminal = TerminalBuilder.builder()
            .system(true)
            .encoding(StandardCharsets.UTF_8)
            .build();

        // Ctrl+C trong lúc query đang chạy (ngoài readLine) hay bị kill (SIGTERM) không chạy qua vòng lặp
        // chính bên dưới nên bỏ qua bước dọn dẹp thường lệ - terminal bị bỏ lại giữa chừng: status bar
        // còn giữ vùng cuộn (scroll region) nó chiếm riêng, gõ/echo hỏng cho tới khi shell cha tự stty sane.
        // Shutdown hook chạy được cho MỌI cách JVM kết thúc (SIGINT/SIGTERM mặc định, hay return bình thường).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Status chiếm 1 vùng cuộn (scroll region) riêng bằng escape code - đó là trạng thái CỦA TERMINAL,
            // terminal.close() chỉ khôi phục lại termios (echo, raw...) chứ không tự huỷ vùng cuộn này. Và dù
            // reset được vùng cuộn, dòng status bar đã in ra vẫn còn nằm nguyên trên màn hình - phải xoá hẳn.
            try {
                Status.getExistingStatus(terminal).ifPresent(Status::close);
            } catch (Exception ignored) {
            }
            try {
                // Chế độ batch (--file/--command) không có màn hình thật để "xoá" - stdout thường bị redirect
                // ra file/log, in thẳng byte ESC[2J ra đó chỉ để lại rác nhị phân vô nghĩa trong log.
                if (!BATCH_MODE) {
                    terminal.writer().print("\u001b[2J\u001b[H");
                    terminal.writer().flush();
                }
            } catch (Exception ignored) {
            }
            try {
                terminal.close();
            } catch (Exception ignored) {
            }
        }));

        Highlighter highlighter = new SqlHighlighter(dialect);

        BottomStatusBar statusBar = new BottomStatusBar(terminal);
        // Trước đây hardcode "/home/namnv/Downloads/demo-antlr/sql_history.txt" (đường dẫn dev cũ, không
        // portable, không liên quan gì tới nơi lưu config) - chuyển vào CÙNG thư mục config (~/.sqlctx) cho
        // nhất quán với connections.conf/current_context.
        Path historyFile = ConnectionProfileStore.configDir().resolve("history.txt");
        Files.createDirectories(historyFile.getParent());

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
                if (context == ParseContext.ACCEPT_LINE && MULTI_LINE && !isMetaCommand(line)) {
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
        // Đăng ký thẳng handler ở đây để tự dọn dẹp rồi thoát - readLine tự thay/trả lại handler này khi chạy,
        // nên Ctrl+C lúc đang gõ vẫn đi theo đường UserInterruptException như cũ, không bị handler này giành mất.
        terminal.handle(Terminal.Signal.INT, signal -> System.exit(130));

        // =========================
        // KEYMAP
        // =========================
        KeyMap<Binding> keyMap = impl.getKeyMaps().get(LineReader.MAIN);

        keyMap.bind(new Reference("toggle-multiline"), "\u0014"); // Ctrl+T

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
            printHelp(impl);
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

        statusBar.setDbInfo(dbLabel(dialect));
        statusBar.setQueryStatus("idle");
        statusBar.render();

        // ── Chế độ batch: --command="SQL" và/hoặc --file=path.sql - chạy xong thoát ngay, KHÔNG vào REPL.
        // Không hỏi xác nhận cho lệnh phá hoại (không có gì đảm bảo có TTY thật để trả lời) - dùng cho CI/script.
        String commandArg = System.getProperty("DB_COMMAND");
        String fileArg = System.getProperty("DB_FILE");
        if (commandArg != null || fileArg != null) {
            BATCH_MODE = true;
            int exitCode = 0;
            try {
                if (commandArg != null) {
                    exitCode = runStatements(splitStatements(commandArg), dialect, impl, terminal, statusBar, false);
                }
                if (exitCode == 0 && fileArg != null) {
                    exitCode = runStatements(splitStatements(Files.readString(Paths.get(fileArg))), dialect, impl, terminal, statusBar, false);
                }
            } catch (Exception e) {
                terminal.writer().println(RED + "ERROR: " + e.getMessage() + RESET);
                exitCode = 1;
            }
            terminal.flush();
            System.exit(exitCode);
        }

        printStartupInfo(impl, dialect);

        String pendingBuffer = null;
        while (true) {
            String line;

            try {
                String coloredPrompt = buildPrompt();
                line = pendingBuffer != null ? impl.readLine(coloredPrompt, null, pendingBuffer) : impl.readLine(coloredPrompt);
            } catch (UserInterruptException e) {
                MenuCompleter.discardMenu(terminal);
                pendingBuffer = null;
                continue;
            } catch (EndOfFileException e) {
                break;
            }
            pendingBuffer = null;
            if (quitRequested) {
                break;
            }
            if (line == null || line.isEmpty()) {
                continue;
            }
            line = line.trim();

            if (line.equalsIgnoreCase("exit;") || line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("\\q")) {
                break;
            }

            if (line.equalsIgnoreCase("help") || line.equalsIgnoreCase("\\?")) {
                printHelp(impl);
                statusBar.render();
                continue;
            }

            if (line.equalsIgnoreCase("\\l") || line.equalsIgnoreCase("\\list")) {
                listDatabases(impl, dialect);
                statusBar.render();
                continue;
            }

            if (line.toLowerCase().startsWith("\\c ") || line.toLowerCase().startsWith("\\connect ")) {
                String target = line.substring(line.indexOf(' ') + 1).trim();
                if (target.isEmpty()) {
                    say(impl, RED + "ERROR: thiếu tên database - dùng \\c <database>" + RESET);
                } else {
                    try {
                        statusBar.setQueryStatus("CONNECTING...");
                        statusBar.render();
                        switchDatabase(target, dialect);
                        statusBar.setDbInfo(dbLabel(dialect));
                        say(impl, "Đã chuyển sang database \"" + target + "\".\n");
                        statusBar.setQueryStatus("IDLE");
                    } catch (Exception e) {
                        say(impl, RED + "ERROR: không chuyển được sang \"" + target + "\" - " + e.getMessage() + RESET);
                        statusBar.setQueryStatus("ERROR");
                    }
                }
                statusBar.render();
                continue;
            }

            if (line.equalsIgnoreCase("\\ctx") || line.equalsIgnoreCase("\\context")) {
                listContexts(impl);
                continue;
            }

            if (line.toLowerCase().startsWith("\\ctx save ") || line.toLowerCase().startsWith("\\context save ")) {
                String name = line.substring(line.toLowerCase().indexOf("save ") + 5).trim();
                if (name.isEmpty()) {
                    say(impl, RED + "ERROR: thiếu tên - dùng \\ctx save <tên>" + RESET);
                } else {
                    try {
                        ConnectionProfileStore.save(new ConnectionProfile(name, dialect,
                                System.getProperty("DB_HOST"), System.getProperty("DB_PORT"),
                                System.getProperty("DB_DBNAME"), System.getProperty("DB_USER"),
                                System.getProperty("DB_PASSWORD")));
                        say(impl, "Đã lưu context \"" + name + "\" (kết nối đang dùng hiện tại).\n");
                    } catch (Exception e) {
                        say(impl, RED + "ERROR: không lưu được - " + e.getMessage() + RESET);
                    }
                }
                continue;
            }

            if (line.toLowerCase().startsWith("\\ctx ") || line.toLowerCase().startsWith("\\context ")) {
                String target = line.substring(line.indexOf(' ') + 1).trim();
                try {
                    ConnectionProfile targetProfile;
                    if (target.equals("-")) {
                        if (previousConnection == null) {
                            throw new IllegalStateException("chưa có connection nào trước đó để quay lại bằng \\ctx -");
                        }
                        targetProfile = previousConnection;
                    } else if (target.isEmpty()) {
                        throw new IllegalStateException("thiếu tên context - dùng \\ctx <tên>, xem \\ctx để liệt kê");
                    } else {
                        targetProfile = ConnectionProfileStore.find(target)
                                .orElseThrow(() -> new IllegalStateException("không có context tên \"" + target + "\" - xem \\ctx để liệt kê"));
                    }
                    // Tên hiện ra trong lời xác nhận LUÔN lấy từ chính targetProfile (nơi SẮP chuyển TỚI) -
                    // "(trước đó)" chỉ khi connection sắp quay lại (qua \ctx -) chưa từng được đặt tên.
                    String label = targetProfile.name() != null ? targetProfile.name() : "(trước đó)";

                    statusBar.setQueryStatus("CONNECTING...");
                    statusBar.render();
                    // Chụp lại connection ĐANG SỐNG (dù chưa từng có tên, vd lúc mới mở CLI bằng --host= tay)
                    // TRƯỚC khi đổi - để "\ctx -" quay lại được đúng chỗ, không chỉ quay lại được context ĐÃ ĐẶT TÊN.
                    previousConnection = new ConnectionProfile(currentContextName, dialect,
                            System.getProperty("DB_HOST"), System.getProperty("DB_PORT"),
                            System.getProperty("DB_DBNAME"), System.getProperty("DB_USER"),
                            System.getProperty("DB_PASSWORD"));
                    dialect = switchContext(targetProfile, impl);
                    currentContextName = targetProfile.name();
                    // Chỉ nhớ lại khi context có TÊN THẬT - "\ctx -" quay về 1 connection tay chưa từng đặt
                    // tên thì không có gì đáng nhớ để tự nối lại đúng chỗ đó ở lần chạy CLI kế tiếp.
                    if (currentContextName != null) {
                        ConnectionProfileStore.setLastContextName(currentContextName);
                    }
                    statusBar.setDbInfo(dbLabel(dialect));
                    say(impl, "Đã chuyển sang context \"" + label + "\" (" + dialect + " "
                            + targetProfile.host() + ":" + targetProfile.port() + "/" + targetProfile.dbname() + ").\n");
                    statusBar.setQueryStatus("IDLE");
                } catch (Exception e) {
                    say(impl, RED + "ERROR: không chuyển context được - " + e.getMessage() + RESET);
                    statusBar.setQueryStatus("ERROR");
                }
                statusBar.render();
                continue;
            }

            if (line.equalsIgnoreCase("\\dt")) {
                describeTables(impl, terminal, statusBar);
                continue;
            }
            if (line.equalsIgnoreCase("\\dn")) {
                describeSchemas(impl, terminal, statusBar);
                continue;
            }
            if (line.equalsIgnoreCase("\\df")) {
                describeFunctions(impl, terminal, statusBar);
                continue;
            }
            if (line.equalsIgnoreCase("\\du")) {
                describeUsers(dialect, impl, terminal, statusBar);
                continue;
            }
            if (line.toLowerCase().startsWith("\\d ")) {
                describeTable(line.substring(3).trim(), dialect, impl, terminal, statusBar);
                continue;
            }

            if (line.toLowerCase().startsWith("\\i ")) {
                String path = line.substring(3).trim();
                try {
                    int rc = runStatements(splitStatements(Files.readString(Paths.get(path))), dialect, impl, terminal, statusBar, true);
                    if (rc != 0) {
                        say(impl, RED + "Script dừng do lỗi.\n" + RESET);
                    }
                } catch (Exception e) {
                    say(impl, RED + "ERROR: không đọc được file \"" + path + "\" - " + e.getMessage() + RESET);
                }
                continue;
            }

            if (line.toLowerCase().startsWith("\\o ")) {
                String path = line.substring(3).trim();
                if (path.isEmpty()) {
                    say(impl, RED + "ERROR: thiếu tên file - dùng \\o <file>" + RESET);
                } else {
                    outputFile = Paths.get(path);
                    outputFormat = outputFormat(path);
                    if ("text".equals(outputFormat)) {
                        say(impl, "Đã bật xuất kết quả ra \"" + path + "\" (text, ghi nối tiếp cho tới khi \\o tắt).\n");
                    } else {
                        say(impl, "Đã bật xuất kết quả câu lệnh KẾ TIẾP ra \"" + path + "\" (" + outputFormat + ").\n");
                    }
                }
                continue;
            }
            if (line.equalsIgnoreCase("\\o")) {
                if (outputFile != null) {
                    say(impl, "Đã tắt xuất file (" + outputFile + ").\n");
                    outputFile = null;
                    outputFormat = null;
                } else {
                    say(impl, "(không có xuất file nào đang bật)\n");
                }
                continue;
            }

            if (line.equalsIgnoreCase("\\e")) {
                try {
                    pendingBuffer = openInEditor(terminal, lastStatement);
                } catch (Exception e) {
                    say(impl, RED + "ERROR: không mở được editor - " + e.getMessage() + RESET);
                }
                continue;
            }

            if (line.isEmpty()) {
                statusBar.render();
                continue;
            }

            // Người dùng có thể gõ/dán nhiều statement cách nhau bằng ';' trong CÙNG 1 lần Enter (paste
            // nhiều dòng, hoặc gõ ở chế độ multi-line) - tách ra chạy TỪNG câu một, giống hệt \i/--file/
            // --command đã làm từ trước. Thiếu bước này khiến Oracle (chỉ chấp nhận 1 statement/execute)
            // báo lỗi "ORA-03405: End of query reached" khi 2 statement dính liền nhau trong 1 chuỗi gửi
            // thẳng cho ojdbc.
            runStatements(splitStatements(line), dialect, impl, terminal, statusBar, true);
        }
        // Dọn dẹp (đóng Status, xoá màn hình, đóng terminal) đã chuyển hết vào shutdown hook ở trên,
        // vì nó chạy được cho MỌI cách thoát (return bình thường, "\q", F10, hay bị kill/Ctrl+C giữa chừng).
    }

    // Các lệnh CLI (không phải SQL) không cần ';' để chấp nhận dòng, kể cả khi đang multi-line.
    private static boolean isMetaCommand(String line) {
        String t = line.trim();
        String lower = t.toLowerCase();
        return t.equalsIgnoreCase("help") || t.equalsIgnoreCase("\\?")
                || t.equalsIgnoreCase("exit") || t.equalsIgnoreCase("\\q")
                || t.equalsIgnoreCase("\\l") || t.equalsIgnoreCase("\\list")
                || t.equalsIgnoreCase("\\dt") || t.equalsIgnoreCase("\\dn") || t.equalsIgnoreCase("\\df")
                || t.equalsIgnoreCase("\\du")
                || t.equalsIgnoreCase("\\o") || lower.startsWith("\\o ")
                || t.equalsIgnoreCase("\\e")
                || t.equalsIgnoreCase("\\ctx") || t.equalsIgnoreCase("\\context")
                || lower.startsWith("\\c ") || lower.startsWith("\\connect ")
                || lower.startsWith("\\d ") || lower.startsWith("\\i ")
                || lower.startsWith("\\ctx ") || lower.startsWith("\\context ")
                || t.isEmpty();
    }

    private static void printHelp(LineReader reader) {
        String text =
            "\n╔══ Keys ════════════════════════════════════════╗\n" +
                "F1          help\n" +
                "Ctrl+T      Toggle multi-line\n" +
                "TAB         autocomplete\n" +
                "F10         quit\n" +
                "\\q          exit\n" +
                "\\l          list databases\n" +
                "\\c <db>     switch database (cùng server)\n" +
                "\\ctx        list saved contexts (kubectx-style)\n" +
                "\\ctx <tên>  switch context (đổi cả host/user/dialect)\n" +
                "\\ctx -      switch to previous context\n" +
                "\\ctx save <tên>  save connection hiện tại\n" +
                "\\dt         list tables\n" +
                "\\dn         list schemas\n" +
                "\\df         list functions\n" +
                "\\du         list users/roles\n" +
                "\\d <tbl>    describe table\n" +
                "\\o <file>   xuất kết quả ra file (.csv/.json/khác)\n" +
                "\\o          tắt xuất file\n" +
                "\\i <file>   run SQL script\n" +
                "\\e          edit last statement in $EDITOR\n" +
                "╚══════════════════════════════════════════════════╝\n";

        reader.printAbove(text);
    }

    /** "[ctx] SQL > host/db (DIALECT)" hiện ở thanh trạng thái - lấy TỪ CHÍNH tham số kết nối đang dùng,
     *  không viết cứng. Phần "[ctx] " chỉ hiện khi phiên đang gắn với 1 context đã đặt tên (đồng bộ với
     *  prompt "[ctx] dbname>" - cùng 1 thông tin, 2 chỗ nhìn thấy). */
    private static String dbLabel(Dialect dialect) {
        String ctx = currentContextName != null ? "[" + currentContextName + "] " : "";
        return ctx + "SQL > " + System.getProperty("DB_HOST") + "/" + System.getProperty("DB_DBNAME")
                + " (" + dialect + ")";
    }

    private static void switchDatabase(String newDb, Dialect dialect) throws Exception {
        if (dialect == Dialect.ORACLE) {
            OracleDataSource.reconnect(newDb);
        } else {
            PostgresDataSource.reconnect(newDb);
        }
        SchemaIndex.reload(dialect);
    }

    /**
     * Khác {@link #switchDatabase} ở chỗ đổi được CẢ host/port/user/password/dialect - dùng cho "\ctx",
     * nhảy sang 1 connection LƯU SẴN có thể khác hẳn server/dialect, không chỉ đổi database cùng server.
     */
    private static Dialect switchContext(ConnectionProfile p, LineReaderImpl impl) throws Exception {
        // Đóng bất kỳ connection cũ nào đang mở (không biết trước đó là Postgres hay Oracle) trước khi đổi.
        try {
            PostgresDataSource.close();
        } catch (Exception ignored) {
        }
        try {
            OracleDataSource.close();
        } catch (Exception ignored) {
        }

        System.setProperty("DB_HOST", p.host());
        System.setProperty("DB_PORT", p.port());
        System.setProperty("DB_DBNAME", p.dbname());
        System.setProperty("DB_USER", p.user());
        System.setProperty("DB_PASSWORD", p.password());
        System.setProperty("DB_DIALECT", p.dialect().name().toLowerCase());

        if (p.dialect() == Dialect.ORACLE) {
            OracleDataSource.reconnect(p.dbname());
        } else {
            PostgresDataSource.reconnect(p.dbname());
        }
        SchemaIndex.reload(p.dialect());
        impl.setHighlighter(new SqlHighlighter(p.dialect()));
        return p.dialect();
    }

    private static void listContexts(LineReader reader) {
        List<ConnectionProfile> profiles = ConnectionProfileStore.loadAll();
        if (profiles.isEmpty()) {
            say(reader, "(chưa lưu context nào - dùng \\ctx save <tên> để lưu connection đang dùng)\n");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ConnectionProfile p : profiles) {
            boolean current = p.name().equalsIgnoreCase(currentContextName);
            sb.append(current ? "* " : "  ").append(p.name())
                    .append("  (").append(p.dialect()).append(" ").append(p.host()).append(":").append(p.port())
                    .append("/").append(p.dbname()).append(" as ").append(p.user()).append(")\n");
        }
        say(reader, sb.toString());
    }

    private static void listDatabases(LineReader reader, Dialect dialect) {
        String sql = dialect == Dialect.ORACLE
                ? "SELECT name FROM v$pdbs ORDER BY name"
                : "SELECT datname FROM pg_database WHERE NOT datistemplate ORDER BY datname";
        try (Statement stmt = connection(dialect).createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            say(reader, String.join("\n", names) + "\n");
        } catch (Exception e) {
            if (dialect == Dialect.ORACLE) {
                // Không phải connect tới CDB root (không thấy v$pdbs) - user thường chỉ thấy PDB của chính mình,
                // liệt kê schema/user khác trong cùng PDB thay thế, đó là khái niệm gần nhất với "database" ở đây.
                listOracleSchemasFallback(reader);
            } else {
                say(reader, RED + "ERROR: " + e.getMessage() + RESET);
            }
        }
    }

    private static void listOracleSchemasFallback(LineReader reader) {
        try (Statement stmt = OracleDataSource.get().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT username FROM all_users ORDER BY username")) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            say(reader, "(không thấy PDB nào - đây là danh sách schema/user trong PDB hiện tại)\n"
                    + String.join("\n", names) + "\n");
        } catch (Exception e) {
            say(reader, RED + "ERROR: " + e.getMessage() + RESET);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // \dt / \dn / \df / \d <table> — xem cấu trúc, đọc từ SchemaIndex đã nạp sẵn (không hỏi lại DB)
    // trừ \d <table> cần hỏi thêm khoá chính (SchemaIndex không lưu thông tin đó).
    // ════════════════════════════════════════════════════════════════

    private static void describeTables(LineReader reader, Terminal terminal, BottomStatusBar statusBar) {
        List<String> headers = List.of("Schema", "Name", "Type");
        List<List<String>> rows = new ArrayList<>();
        for (var s : SchemaIndex.schemas) {
            for (var t : s.tables()) {
                rows.add(List.of(s.name(), t.name(), t.kind()));
            }
        }
        printSimpleTable(reader, terminal, statusBar, rows.isEmpty() ? "(không có bảng/view nào)\n" : null, headers, rows);
    }

    private static void describeSchemas(LineReader reader, Terminal terminal, BottomStatusBar statusBar) {
        List<String> headers = List.of("Schema", "Tables/views");
        List<List<String>> rows = new ArrayList<>();
        for (var s : SchemaIndex.schemas) {
            rows.add(List.of(s.name(), String.valueOf(s.tables().size())));
        }
        printSimpleTable(reader, terminal, statusBar, rows.isEmpty() ? "(không có schema nào)\n" : null, headers, rows);
    }

    private static void describeFunctions(LineReader reader, Terminal terminal, BottomStatusBar statusBar) {
        List<String> headers = List.of("Function");
        List<List<String>> rows = SchemaIndex.functions.stream().map(List::of).toList();
        printSimpleTable(reader, terminal, statusBar, rows.isEmpty() ? "(không có hàm nào)\n" : null, headers, rows);
    }

    /** \du - khác \dt/\dn/\df ở chỗ SchemaIndex không cache sẵn user/role, phải hỏi DB trực tiếp mỗi lần gọi. */
    private static void describeUsers(Dialect dialect, LineReader reader, Terminal terminal, BottomStatusBar statusBar) {
        // ALL_USERS không có cột account_status (đó là DBA_USERS, cần quyền DBA mà user thường không có) -
        // chỉ dùng username + created, cả hai đều có sẵn cho MỌI user không cần quyền đặc biệt gì thêm.
        String sql = dialect == Dialect.ORACLE
                ? "SELECT username, created FROM all_users ORDER BY username"
                : "SELECT rolname, rolsuper, rolcanlogin FROM pg_roles ORDER BY rolname";
        List<String> headers = dialect == Dialect.ORACLE
                ? List.of("User", "Created")
                : List.of("Role", "Superuser", "Can login");
        List<List<String>> rows = new ArrayList<>();
        try (Statement stmt = connection(dialect).createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                if (dialect == Dialect.ORACLE) {
                    rows.add(List.of(rs.getString(1), rs.getString(2)));
                } else {
                    rows.add(List.of(rs.getString(1), rs.getBoolean(2) ? "yes" : "", rs.getBoolean(3) ? "yes" : ""));
                }
            }
        } catch (Exception e) {
            say(reader, RED + "ERROR: " + e.getMessage() + RESET);
            return;
        }
        printSimpleTable(reader, terminal, statusBar, rows.isEmpty() ? "(không có user/role nào)\n" : null, headers, rows);
    }

    private static void describeTable(String tableName, Dialect dialect, LineReader reader, Terminal terminal, BottomStatusBar statusBar) {
        var table = SchemaIndex.tableIndex.get(tableName.toLowerCase());
        if (table == null) {
            say(reader, RED + "ERROR: không thấy bảng/view \"" + tableName + "\"" + RESET);
            return;
        }
        Set<String> pk = fetchPrimaryKeyColumns(dialect, table.name());
        List<String> headers = List.of("Column", "Type", "Nullable", "Key");
        List<List<String>> rows = new ArrayList<>();
        for (var c : table.columns()) {
            rows.add(List.of(c.name(), c.dataType(), c.notNull() ? "not null" : "", pk.contains(c.name().toLowerCase()) ? "PK" : ""));
        }
        say(reader, "Table \"" + table.fullName() + "\" (" + table.kind() + ")\n");
        printSimpleTable(reader, terminal, statusBar, null, headers, rows);
    }

    private static Set<String> fetchPrimaryKeyColumns(Dialect dialect, String tableName) {
        String sql = dialect == Dialect.ORACLE
                ? "SELECT ucc.column_name FROM user_constraints uc " +
                        "JOIN user_cons_columns ucc ON uc.constraint_name = ucc.constraint_name " +
                        "WHERE uc.constraint_type = 'P' AND uc.table_name = UPPER(?)"
                : "SELECT a.attname FROM pg_index i " +
                        "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) " +
                        "WHERE i.indrelid = ?::regclass AND i.indisprimary";
        try (PreparedStatement ps = connection(dialect).prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> pk = new HashSet<>();
                while (rs.next()) {
                    pk.add(rs.getString(1).toLowerCase());
                }
                return pk;
            }
        } catch (Exception e) {
            return Set.of(); // không lấy được PK (thiếu quyền, tên bảng lạ...) - vẫn hiện được cột, chỉ thiếu cột "Key"
        }
    }

    // ════════════════════════════════════════════════════════════════
    // EXPLAIN/EXPLAIN ANALYZE (Postgres) / DBMS_XPLAN (Oracle) — tô màu thay vì bó khung bảng.
    // ════════════════════════════════════════════════════════════════

    // "(cost=0.00..1.10 rows=10 width=40)" hoặc "(actual time=0.010..0.015 rows=10 loops=1)".
    private static final Pattern EXPLAIN_METRICS = Pattern.compile("\\((?:cost=|actual time=)[^)]*\\)");
    // Dòng chi tiết dạng "Nhãn: giá trị" (Filter:, Index Cond:, Buffers:, Planning Time:, ...).
    private static final Pattern EXPLAIN_LABEL_LINE = Pattern.compile("^(\\s*)([A-Za-z][A-Za-z0-9 ]*):(\\s.*)?$");
    private static final Pattern EXPLAIN_NEVER_EXECUTED = Pattern.compile("\\(never executed\\)");

    // ════════════════════════════════════════════════════════════════
    // Tên bảng bị ảnh hưởng bởi INSERT/UPDATE/DELETE - hiện kèm "OK" cho rõ THAY VÌ chỉ nói "OK - N
    // row(s)" không biết là bảng nào (đặc biệt hữu ích trong \i chạy nhiều statement liên tiếp).
    // Chỉ tách bằng regex đơn giản (không phải parser SQL đầy đủ) - đủ dùng cho hình dạng INSERT
    // INTO/UPDATE/DELETE FROM thông thường, không cố xử lý CTE hay statement lồng nhau phức tạp.
    // ════════════════════════════════════════════════════════════════
    private static final Pattern INSERT_TABLE = Pattern.compile("(?i)insert\\s+into\\s+([\\w.\"]+)");
    private static final Pattern UPDATE_TABLE = Pattern.compile("(?i)update\\s+([\\w.\"]+)");
    private static final Pattern DELETE_TABLE = Pattern.compile("(?i)delete\\s+from\\s+([\\w.\"]+)");

    private static String extractDmlTableName(String sql) {
        for (Pattern p : List.of(INSERT_TABLE, UPDATE_TABLE, DELETE_TABLE)) {
            Matcher m = p.matcher(sql);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static String colorizeExplainLine(String line) {
        Matcher label = EXPLAIN_LABEL_LINE.matcher(line);
        if (label.matches()) {
            String indent = label.group(1);
            String key = label.group(2);
            String value = label.group(3) == null ? "" : label.group(3);
            // "Planning Time"/"Execution Time" là 2 dòng tổng kết quan trọng nhất - nổi bật hơn hẳn các
            // dòng chi tiết còn lại (Filter/Index Cond/Buffers/...).
            boolean summary = key.equalsIgnoreCase("Planning Time") || key.equalsIgnoreCase("Execution Time");
            String keyColor = summary ? "\u001b[1;35m" : YELLOW;
            return indent + keyColor + key + ":" + RESET + value;
        }
        // Oracle DBMS_XPLAN.DISPLAY tự vẽ sẵn khung ASCII ("| Id | Operation | ... |", dòng viền toàn
        // "-"/"+") - khác hẳn kiểu cây "->" của Postgres. Khung này đã đủ rõ ràng, tô 1 màu đồng loạt lên
        // cả bảng (kể cả viền) chỉ làm rối chứ không giúp đọc dễ hơn - để nguyên không màu.
        String stripped = line.strip();
        if (!stripped.isEmpty() && (stripped.contains("|") || stripped.chars().allMatch(c -> c == '-' || c == '+'))) {
            return line;
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = EXPLAIN_METRICS.matcher(line);
        int last = 0;
        while (m.find()) {
            sb.append(colorizeExplainNodeText(line.substring(last, m.start())));
            sb.append("\u001b[90m").append(m.group()).append(RESET); // cost/actual time - dim gray, phụ trợ
            last = m.end();
        }
        sb.append(colorizeExplainNodeText(line.substring(last)));
        return EXPLAIN_NEVER_EXECUTED.matcher(sb.toString())
                .replaceAll("\u001b[1;31m(never executed)" + RESET);
    }

    /** Tô đậm/cyan tên node (Seq Scan, Hash Join, ...); mũi tên cây "->" giữ mờ để tên node nổi bật hơn. */
    private static String colorizeExplainNodeText(String text) {
        int i = 0;
        while (i < text.length() && text.charAt(i) == ' ') {
            i++;
        }
        String indent = text.substring(0, i);
        String rest = text.substring(i);
        if (rest.isEmpty()) {
            return text;
        }
        if (rest.startsWith("->")) {
            return indent + "\u001b[2m->" + RESET + " \u001b[1;36m" + rest.substring(2).stripLeading() + RESET;
        }
        return indent + "\u001b[1;36m" + rest + RESET;
    }

    // ════════════════════════════════════════════════════════════════
    // \o — xuất kết quả câu lệnh ra file (csv/json xuất 1 lần, còn lại ghi log nối tiếp).
    // ════════════════════════════════════════════════════════════════

    private static String outputFormat(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".csv")) {
            return "csv";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        return "text";
    }

    private static void exportResult(Path file, String format, List<String> headers, List<List<String>> rows)
            throws IOException {
        switch (format) {
            case "csv" -> Files.writeString(file, toCsv(headers, rows));
            case "json" -> Files.writeString(file, toJson(headers, rows));
            default -> Files.writeString(file, toPlainLog(headers, rows),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static String toCsv(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(headers.stream().map(SqlctxCli::csvField).collect(Collectors.joining(","))).append("\n");
        for (List<String> row : rows) {
            sb.append(row.stream().map(SqlctxCli::csvField).collect(Collectors.joining(","))).append("\n");
        }
        return sb.toString();
    }

    private static String csvField(String raw) {
        // "<null>" là sentinel hiển thị NULL trên bảng terminal, không nên xuất y nguyên chuỗi đó ra file dữ
        // liệu - quy ước CSV phổ biến cho NULL là để trống ô.
        String v = "<null>".equals(raw) ? "" : raw;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String toJson(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            sb.append("  {");
            for (int c = 0; c < headers.size(); c++) {
                String val = c < row.size() ? row.get(c) : null;
                sb.append("\"").append(jsonEscape(headers.get(c))).append("\":");
                sb.append(val == null || "<null>".equals(val) ? "null" : "\"" + jsonEscape(val) + "\"");
                if (c < headers.size() - 1) {
                    sb.append(",");
                }
            }
            sb.append("}").append(r < rows.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String toPlainLog(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(" | ", headers)).append("\n");
        for (List<String> row : rows) {
            sb.append(String.join(" | ", row)).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static void printSimpleTable(LineReader reader, Terminal terminal, BottomStatusBar statusBar,
                                         String emptyMessage, List<String> headers, List<List<String>> rows) {
        if (rows.isEmpty() && emptyMessage != null) {
            say(reader, emptyMessage);
            return;
        }
        try {
            DataViewTable.print(terminal, headers, rows, statusBar::redraw);
        } catch (Exception e) {
            say(reader, RED + "ERROR: " + e.getMessage() + RESET);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // \e — mở $EDITOR sửa lại câu lệnh gần nhất, nạp lại vào dòng nhập cho người dùng xem/sửa tiếp/Enter.
    // ════════════════════════════════════════════════════════════════

    /**
     * Thứ tự tìm editor - giống quy ước hầu hết CLI (git, less, crontab...) đã dùng, không tự đặt "vi" làm
     * mặc định cứng:
     * 1. $VISUAL, 2. $EDITOR - người dùng đã tự đặt thì tôn trọng ngay, không cần tìm gì thêm.
     * 3. /etc/alternatives/editor - trên Debian/Ubuntu đây CHÍNH LÀ "editor mặc định của hệ điều hành" mà
     *    người dùng/admin đã chọn qua "update-alternatives --config editor" (Arch/macOS không có file này).
     * 4. sensible-editor - script có sẵn trên Debian/Ubuntu làm đúng việc dò editor mặc định hệ thống.
     * 5. Không có gì cấu hình sẵn (vd Arch, hoặc container tối giản) - thử vài editor hay có sẵn theo distro,
     *    ưu tiên cái dễ dùng hơn (nano có chỉ dẫn phím ngay trên màn hình) trước vi/vim.
     */
    private static String resolveEditor() {
        String visual = System.getenv("VISUAL");
        if (visual != null && !visual.isBlank()) {
            return visual;
        }
        String editor = System.getenv("EDITOR");
        if (editor != null && !editor.isBlank()) {
            return editor;
        }
        Path systemDefault = Path.of("/etc/alternatives/editor");
        if (Files.isExecutable(systemDefault)) {
            return systemDefault.toString();
        }
        if (isOnPath("sensible-editor")) {
            return "sensible-editor";
        }
        for (String candidate : List.of("nano", "vim", "vi")) {
            if (isOnPath(candidate)) {
                return candidate;
            }
        }
        return "vi"; // POSIX bắt buộc hệ thống Unix nào cũng phải có - chốt chặn cuối cùng
    }

    private static boolean isOnPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, command))) {
                return true;
            }
        }
        return false;
    }

    private static String openInEditor(Terminal terminal, String initialSql) throws Exception {
        Path tmp = Files.createTempFile("sqlctx_edit_", ".sql");
        try {
            Files.writeString(tmp, (initialSql == null ? "" : initialSql) + "\n");
            String editor = resolveEditor();
            terminal.writer().flush();
            // inheritIO(): tiến trình con dùng THẲNG stdin/stdout/stderr thật của terminal, không qua JLine -
            // editor (vim/nano...) tự lo raw mode của riêng nó, JLine tự thiết lập lại raw mode cần thiết
            // ở lần readLine() kế tiếp nên không cần khôi phục thủ công gì thêm ở đây.
            Process p = new ProcessBuilder(editor, tmp.toString()).inheritIO().start();
            p.waitFor();
            return Files.readString(tmp).stripTrailing();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Tách nhiều statement theo dấu ';' - tránh dấu ';' nằm TRONG chuỗi '...' (không xử lý '' escape bên
    // trong chuỗi) và TRONG dollar-quoting $$...$$/$tag$...$tag$ của Postgres (thân hàm CREATE FUNCTION
    // hay chứa ';' và '$$' bọc ngoài - không tránh thì tách vỡ ngay giữa thân hàm).
    // ════════════════════════════════════════════════════════════════

    private static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        String dollarTag = null; // null = không ở trong dollar-quote; khác null = tag đang mở (vd "$$", "$body$")
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (dollarTag != null) {
                cur.append(c);
                if (c == '$' && sql.startsWith(dollarTag, i - dollarTag.length() + 1)) {
                    dollarTag = null; // vừa gặp lại ĐÚNG tag đã mở -> đóng dollar-quote
                }
                continue;
            }
            if (!inString && c == '$') {
                String tag = matchDollarTag(sql, i);
                if (tag != null) {
                    dollarTag = tag;
                    cur.append(tag);
                    i += tag.length() - 1;
                    continue;
                }
            }
            if (c == '\'' && dollarTag == null) {
                inString = !inString;
                cur.append(c);
            } else if (c == ';' && !inString) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * "$$" hoặc "$tag$" đứng tại vị trí i - trả về CHUỖI TAG đầy đủ (kèm 2 dấu $) nếu khớp, null nếu không.
     * Theo đúng quy tắc Postgres: tag rỗng ("$$") luôn hợp lệ; tag có tên phải bắt đầu bằng chữ/gạch dưới
     * (không phải chữ số) - để không nhầm với tham số vị trí kiểu "$1".
     */
    private static String matchDollarTag(String sql, int i) {
        int j = i + 1;
        if (j < sql.length() && sql.charAt(j) == '$') {
            return sql.substring(i, j + 1);
        }
        if (j < sql.length() && (Character.isLetter(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
            while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                j++;
            }
            if (j < sql.length() && sql.charAt(j) == '$') {
                return sql.substring(i, j + 1);
            }
        }
        return null;
    }

    /** @return 0 nếu tất cả statement chạy thành công, 1 nếu có statement lỗi (dừng ngay tại đó). */
    private static int runStatements(List<String> statements, Dialect dialect, LineReader impl, Terminal terminal,
                                     BottomStatusBar statusBar, boolean interactive) {
        for (String stmt : statements) {
            String trimmedStmt = stmt.strip();
            if (trimmedStmt.isEmpty()) {
                continue;
            }
            if (!executeStatement(trimmedStmt, dialect, impl, terminal, statusBar, interactive)) {
                return 1;
            }
        }
        return 0;
    }

    // ════════════════════════════════════════════════════════════════
    // Chạy 1 statement (dùng chung cho REPL, \i, --file, --command). Trả về false nếu chạy lỗi.
    // ════════════════════════════════════════════════════════════════

    private static boolean isDestructive(String trimmedUpper) {
        return trimmedUpper.startsWith("DROP") || trimmedUpper.startsWith("TRUNCATE")
                || trimmedUpper.startsWith("DELETE") || trimmedUpper.startsWith("UPDATE")
                || trimmedUpper.startsWith("ALTER");
    }

    private static boolean isSchemaChanging(String trimmedUpper) {
        return trimmedUpper.startsWith("CREATE") || trimmedUpper.startsWith("DROP")
                || trimmedUpper.startsWith("ALTER") || trimmedUpper.startsWith("TRUNCATE");
    }

    /** Hỏi lại trước khi chạy lệnh có thể phá dữ liệu - đọc 1 phím thật từ terminal (y/Y mới chạy tiếp). */
    private static boolean confirmProceed(Terminal terminal, String sql) {
        try {
            terminal.writer().print(YELLOW + "⚠ Câu lệnh sau có thể THAY ĐỔI/XOÁ dữ liệu:\n" + RESET
                    + sql + "\n" + YELLOW + "Chạy không? [y/N] " + RESET);
            terminal.writer().flush();
            var saved = terminal.enterRawMode();
            try {
                int ch = terminal.reader().read();
                terminal.writer().print("\r\n");
                terminal.writer().flush();
                return ch == 'y' || ch == 'Y';
            } finally {
                terminal.setAttributes(saved);
            }
        } catch (Exception e) {
            return false; // đọc phím lỗi -> an toàn là huỷ, không chạy nhầm
        }
    }

    private static boolean executeStatement(String sql, Dialect dialect, LineReader impl, Terminal terminal,
                                            BottomStatusBar statusBar, boolean interactive) {
        lastStatement = sql;
        String trimmed = sql.stripLeading().toUpperCase();

        if (interactive && isDestructive(trimmed) && !confirmProceed(terminal, sql)) {
            say(impl, "Đã huỷ.\n");
            return true; // người dùng chủ động không chạy - không tính là lỗi
        }

        statusBar.setQueryStatus("RUNNING...");
        statusBar.render();

        try {
            // Oracle (ojdbc) không chấp nhận dấu ';' cuối câu - đó là quy ước của SQL*Plus/CLI, không phải cú
            // pháp SQL thật. Postgres (pgjdbc) thì chấp nhận nên không cần cắt.
            String execLine = dialect == Dialect.ORACLE && sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;

            long startTime = System.currentTimeMillis();

            // Statement.execute() dùng chung cho MỌI loại câu lệnh (SELECT/DML/DDL/GRANT/MERGE...) - tự biết
            // có ResultSet hay không, tránh phải đoán bằng cách so khớp tiền tố (từng bỏ sót ALTER/CREATE
            // INDEX/GRANT... khiến các lệnh đó rơi vào nhánh executeQuery() và luôn báo lỗi "not a result set").
            try (Statement stmt = connection(dialect).createStatement()) {
                boolean hasResultSet = stmt.execute(execLine);
                // "Query" = tới lúc DB trả về xong (round-trip execute), TÁCH RIÊNG khỏi "Fetch" = thời gian
                // đọc hết ResultSet phía client - phân biệt được chậm do DB hay do fetch/network nhiều dòng.
                long queryElapsed = System.currentTimeMillis() - startTime;
                String timeSuffix = "\nTime: " + String.format("%.3f", queryElapsed / 1000.0) + "s\n";

                if (hasResultSet) {
                    long fetchStart = System.currentTimeMillis();
                    List<String> headers = new ArrayList<>();
                    List<List<String>> rows = new ArrayList<>();
                    try (ResultSet rs = stmt.getResultSet()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int columnCount = meta.getColumnCount();
                        for (int i = 1; i <= columnCount; i++) {
                            headers.add(meta.getColumnLabel(i));
                        }
                        while (rs.next()) {
                            List<String> row = new ArrayList<>();
                            for (int i = 1; i <= columnCount; i++) {
                                Object val = rs.getObject(i);
                                row.add(val == null ? "<null>" : String.valueOf(val));
                            }
                            rows.add(row);
                        }
                    }
                    long fetchElapsed = System.currentTimeMillis() - fetchStart;
                    String timingPart = "Query: " + String.format("%.3f", queryElapsed / 1000.0) + "s  ·  "
                            + "Fetch: " + String.format("%.3f", fetchElapsed / 1000.0) + "s  ·  "
                            + "Total: " + String.format("%.3f", (queryElapsed + fetchElapsed) / 1000.0) + "s";
                    String rowsColsPart = rows.size() + " row(s), " + headers.size() + " col(s)";
                    String timeLine = rowsColsPart + "\n" + timingPart + "\n";
                    // EXPLAIN/EXPLAIN ANALYZE (Postgres) và SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY) (Oracle)
                    // đều trả về đúng 1 cột text đã tự có cấu trúc cây thụt lề - bó khung ô vuông từng dòng
                    // chỉ làm rối và cắt cụt dòng dài (cost=/actual time=... thường rất dài).
                    boolean isExplainPlan = headers.size() == 1
                            && ("QUERY PLAN".equalsIgnoreCase(headers.get(0))
                                    || "PLAN_TABLE_OUTPUT".equalsIgnoreCase(headers.get(0)));
                    // EXPLAIN (FORMAT JSON/XML/YAML) vẫn đặt tên cột y hệt "QUERY PLAN" nhưng trả về ĐÚNG 1
                    // hàng chứa cả khối JSON/XML/YAML gộp thành 1 chuỗi (kèm '\n' nhúng bên trong, không
                    // phải nhiều hàng như FORMAT TEXT) - không phải cây text nên bộ tô màu theo cost=/->
                    // không khớp gì cả, chỉ nên in thô (không box) chứ không tô nhầm màu.
                    boolean looksLikeStructuredFormat = isExplainPlan && rows.size() == 1
                            && !rows.get(0).isEmpty() && rows.get(0).get(0) != null
                            && rows.get(0).get(0).stripLeading().matches("(?s)^[\\[{<].*");
                    List<String> explainLines = isExplainPlan
                            ? rows.stream()
                                    .map(r -> r.isEmpty() ? "" : r.get(0))
                                    .map(line -> looksLikeStructuredFormat ? line : colorizeExplainLine(line))
                                    .collect(Collectors.toList())
                            : null;
                    if (outputFile != null) {
                        try {
                            exportResult(outputFile, outputFormat, headers, rows);
                            say(impl, "Đã xuất " + rows.size() + " dòng, " + headers.size() + " cột ra \""
                                    + outputFile + "\".\n" + timingPart + "\n");
                        } catch (IOException e) {
                            say(impl, RED + "ERROR: không ghi được file \"" + outputFile + "\" - " + e.getMessage() + RESET);
                        }
                        if (!"text".equals(outputFormat)) {
                            // csv/json là xuất 1 lần cho câu lệnh này rồi tự tắt - không thể ghi nối tiếp
                            // nhiều kết quả vào 1 file mà vẫn giữ đúng cú pháp csv/json hợp lệ.
                            outputFile = null;
                            outputFormat = null;
                        }
                    } else if (isExplainPlan) {
                        // Batch (--file/--command) không có TTY thật để trả lời "press key to continue" -
                        // printPlainStatic() không bao giờ dừng lại chờ phím, chỉ printPlain() ở phiên có
                        // người ngồi gõ mới an toàn (giống cặp print()/printStatic() ở bảng thường).
                        if (interactive) {
                            DataViewTable.printPlain(terminal, explainLines);
                        } else {
                            DataViewTable.printPlainStatic(terminal, explainLines);
                        }
                        // "N row(s)" không hợp ngữ nghĩa cho 1 execution plan (đây là DÒNG PLAN, không phải
                        // dòng dữ liệu) - chỉ hiện phần thời gian, bỏ rowsColsPart.
                        say(impl, timingPart + "\n");
                    } else {
                        // Batch (--file/--command) không có TTY thật để trả lời "press key to continue" -
                        // printStatic() không bao giờ dừng lại chờ phím, chỉ print() ở phiên có người ngồi gõ mới an toàn.
                        if (interactive) {
                            DataViewTable.print(terminal, headers, rows, statusBar::redraw);
                        } else {
                            DataViewTable.printStatic(terminal, headers, rows);
                        }
                        say(impl, timeLine);
                    }
                    lastOutput = () -> {
                        if (isExplainPlan) {
                            DataViewTable.printPlainStatic(terminal, explainLines);
                            terminal.writer().print(timingPart + "\n");
                        } else {
                            DataViewTable.printStatic(terminal, headers, rows);
                            terminal.writer().print(timeLine);
                        }
                    };
                } else {
                    int affected = stmt.getUpdateCount();
                    String table = extractDmlTableName(sql);
                    String tableTag = table != null ? table + " " : "";
                    String msg;
                    if (trimmed.startsWith("INSERT")) {
                        msg = "INSERT " + tableTag + "OK — " + affected + " row(s) inserted.";
                    } else if (trimmed.startsWith("UPDATE")) {
                        msg = "UPDATE " + tableTag + "OK — " + affected + " row(s) affected.";
                    } else if (trimmed.startsWith("DELETE")) {
                        msg = "DELETE " + tableTag + "OK — " + affected + " row(s) affected.";
                    } else {
                        msg = "OK.";
                    }
                    say(impl, msg + timeSuffix);
                }
            }
            // Reload SAU KHI đã chạy xong (không phải trước) - schema mới phải phản ánh đúng những gì
            // câu lệnh VỪA làm, không phải trạng thái cũ trước khi chạy.
            if (isSchemaChanging(trimmed)) {
                SchemaIndex.reload(dialect);
            }
            statusBar.setQueryStatus("IDLE");
            return true;
        } catch (Exception ex) {
            reportError(impl, sql, ex);
            statusBar.setQueryStatus("ERROR");
            return false;
        }
    }

    /**
     * SchemaIndex.reload() bọc lỗi JDBC thật trong 1-2 lớp RuntimeException (xem SchemaIndex.reload()) -
     * lấy message của nguyên nhân SÂU NHẤT (vd message gốc của PSQLException/SQLException) thay vì
     * message rỗng/chung chung của lớp RuntimeException bọc ngoài.
     */
    private static String rootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    private static void reportError(LineReader impl, String sql, Exception ex) {
        if (ex instanceof org.postgresql.util.PSQLException psql) {
            org.postgresql.util.ServerErrorMessage msg = psql.getServerErrorMessage();
            if (msg != null) {
                StringBuilder err = new StringBuilder();
                err.append(RED).append("ERROR: ").append(msg.getMessage()).append("\n");

                if (msg.getDetail() != null) {
                    err.append("DETAIL: ").append(msg.getDetail()).append("\n");
                }

                if (msg.getPosition() > 0) {
                    int pos = msg.getPosition() - 1; // 0-based
                    String[] lines = sql.split("\n");

                    int lineNum = 1;
                    int col = pos;
                    for (String l : lines) {
                        if (col <= l.length()) {
                            break;
                        }
                        col -= (l.length() + 1); // +1 cho \n
                        lineNum++;
                    }

                    String errorLine = lines[lineNum - 1];
                    err.append("LINE ").append(lineNum).append(": ").append(errorLine).append("\n");
                    err.append(" ".repeat("LINE ".length() + String.valueOf(lineNum).length() + ": ".length() + col)).append("^\n").append(RESET);
                }

                say(impl, err.toString());
                return;
            }
        }
        say(impl, RED + "ERROR: " + ex.getMessage() + RESET);
    }

    /**
     * "SLQ>" tĩnh trước đây không cho biết đang ở db nào - đổi thành "dbname>", giống quy ước
     * psql/mycli/pgcli luôn hiện database hiện tại ngay trong prompt thay vì 1 chuỗi cố định chung
     * chung không mang thông tin gì. Tên context đã hiện riêng ở status bar (dbLabel()) rồi, không lặp
     * lại ở đây nữa. Đọc lại DB_DBNAME MỖI LẦN gọi (không cache) để tự cập nhật ngay sau \c/\ctx.
     */
    private static String buildPrompt() {
        String dbName = System.getProperty("DB_DBNAME", "?");
        return "\u001B[32m" + dbName + "> " + RESET;
    }

    private static void printStartupInfo(LineReader reader, Dialect dialect) {
        String clientZone = java.time.ZoneId.systemDefault().toString();
        String serverZone = fetchServerTimezone(dialect);
        String serverVersion = fetchServerVersion(dialect);
        String serverTime = fetchServerTime(dialect);
        String encoding = fetchEncoding(dialect);
        String driver = fetchDriverInfo(dialect);

        String host = System.getProperty("DB_HOST");
        String port = System.getProperty("DB_PORT");
        String db = System.getProperty("DB_DBNAME");
        String user = System.getProperty("DB_USER");

        int schemaCount = SchemaIndex.schemas.size();
        int tableCount = SchemaIndex.schemaTableIndex.size();
        int columnCount = SchemaIndex.schemaTableIndex.values().stream().mapToInt(t -> t.columns().size()).sum();

        String info =
            TITLE_COLOR + "sqlctx CLI 1.0.0 — SQL completion & data browser" + RESET + "\n" +
                "\n" +
                DIM + "Connected:  " + RESET + GREEN + user + "@" + host + ":" + port + "/" + db + " (" + dialect + ")" + RESET + "\n" +
                DIM + "Config:     " + RESET + ConnectionProfileStore.configDir() + "\n" +
                DIM + "Server:     " + RESET + serverVersion + "\n" +
                DIM + "Driver:     " + RESET + driver + "\n" +
                DIM + "Encoding:   " + RESET + encoding + "\n" +
                DIM + "Time:       " + RESET + "server " + serverTime + " (" + serverZone + ")" + DIM + "  ·  " + RESET + "client " + clientZone + "\n" +
                "\n" +
                DIM + "Schema:     " + RESET + schemaCount + " schema(s), " + tableCount + " table/view(s), " + columnCount + " column(s) indexed\n" +
                DIM + "Suggest:    " + RESET + SchemaIndex.functions.size() + " function(s), " + SchemaIndex.dataTypes.size() + " data type(s)\n" +
                "\n" +
                DIM + "Home:       http://your-cli.dev" + RESET + "\n";

        reader.printAbove(info);
    }

    private static String fetchServerTime(Dialect dialect) {
        String sql = dialect == Dialect.ORACLE ? "SELECT TO_CHAR(SYSTIMESTAMP, 'YYYY-MM-DD HH24:MI:SS') FROM DUAL" : "SELECT NOW()";
        try (Statement stmt = connection(dialect).createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "(unknown)";
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    private static String fetchEncoding(Dialect dialect) {
        String sql = dialect == Dialect.ORACLE
                ? "SELECT value FROM nls_database_parameters WHERE parameter = 'NLS_CHARACTERSET'"
                : "SHOW SERVER_ENCODING";
        try (Statement stmt = connection(dialect).createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "(unknown)";
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    private static String fetchDriverInfo(Dialect dialect) {
        try {
            var meta = connection(dialect).getMetaData();
            return meta.getDriverName() + " " + meta.getDriverVersion();
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    private static String fetchServerVersion(Dialect dialect) {
        String sql = dialect == Dialect.ORACLE ? "SELECT banner FROM v$version WHERE ROWNUM = 1" : "SELECT version()";
        try (Statement stmt = connection(dialect).createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "(unknown)";
        } catch (Exception e) {
            return (dialect == Dialect.ORACLE ? "Oracle" : "PostgreSQL") + " (không đọc được version: " + e.getMessage() + ")";
        }
    }

    private static String fetchServerTimezone(Dialect dialect) {
        String sql = dialect == Dialect.ORACLE ? "SELECT SESSIONTIMEZONE FROM DUAL" : "SHOW TIMEZONE";
        try (Statement stmt = connection(dialect).createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "(unknown)";
        } catch (Exception e) {
            return "(unknown)";
        }
    }
}
