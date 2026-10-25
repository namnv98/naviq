package com.sqlctx.cli;

import com.sqlctx.util.I18n;

import com.sqlctx.cli.command.ContextManager;
import com.sqlctx.cli.command.StatementExecutor;
import com.sqlctx.cli.repl.CommandDispatcher;
import com.sqlctx.cli.repl.ReplLineReaderFactory;
import com.sqlctx.cli.startup.StartupBanner;
import com.sqlctx.cli.terminal.SqlHighlighter;
import com.sqlctx.datasource.ConnectionProfileStore;
import com.sqlctx.schema.Dialect;
import com.sqlctx.dialect.DialectAdapters;
import com.sqlctx.schema.SchemaIndex;
import com.sqlctx.cli.view.BottomStatusBar;
import org.jline.reader.EndOfFileException;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.Status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static com.sqlctx.cli.session.SqlctxSession.*;

/**
 * Entry point: parse tham số dòng lệnh, kết nối DB, dựng terminal + shutdown hook, rồi hoặc chạy chế độ
 * batch (--command/--file, thoát ngay) hoặc vào vòng lặp REPL tương tác (đọc 1 dòng, chuyển cho
 * {@link CommandDispatcher} xử lý, lặp lại). Toàn bộ chi tiết dựng LineReader nằm ở
 * {@link ReplLineReaderFactory}, chi tiết XỬ LÝ từng lệnh nằm ở {@link CommandDispatcher} và các class
 * trong {@code cli.command}, banner khởi động nằm ở {@link StartupBanner}. State dùng chung nằm ở
 * {@code cli.session.SqlctxSession}.
 */
public class SqlctxCli {

    public static void main(String[] args) throws Exception {
        List<String> argErrors = CliArgs.apply(args);
        if (!argErrors.isEmpty()) {
            argErrors.forEach(e -> System.err.println(RED + "ERROR: " + e + RESET));
            System.exit(2);
        }
        System.setProperty("org.jline.terminal.type", "xterm-256color");
        // JLine đổi ký tự vẽ khung (│ ─ ·...) sang bộ ký tự ACS của terminal khi vẽ status bar - tmux và vài terminal
        // hiện nó thành chữ "x"/"q"/"~". Font hiện đại đều có sẵn các ký tự Unicode này, không cần ACS.
        System.setProperty("org.jline.utils.disableAlternateCharset", "true");
        com.sqlctx.util.LoggingConfig.init();

        if (java.util.Arrays.asList(args).contains("--lsp")) {
            runLanguageServer();
            return;
        }
        if (java.util.Arrays.asList(args).contains("--format")) {
            System.exit(runFormatter());
        }
        // Redis, Kafka chạy REPL riêng (com.sqlctx.redis, com.sqlctx.kafka), không đi qua phần SQL bên dưới.
        if (com.sqlctx.redis.RedisCli.handles()) {
            com.sqlctx.redis.RedisCli.run(args);
            return;
        }
        if (com.sqlctx.kafka.KafkaCli.handles()) {
            com.sqlctx.kafka.KafkaCli.run(args);
            return;
        }

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
            ContextManager.applyContext(contextArg);
            currentContextName = contextArg;
            ConnectionProfileStore.setLastContextName(contextArg);
        }

        // --dialect=oracle|postgres (mặc định postgres) - đã được set vào system property DB_DIALECT
        // bởi vòng lặp parse arg chung ở trên (hoặc bởi applyContext() ở trên), không cần parse riêng.
        Dialect dialect = "oracle".equalsIgnoreCase(System.getProperty("DB_DIALECT", "postgres"))
                ? Dialect.ORACLE : Dialect.POSTGRES;
        try {
            // chỉ kiểm tra kết nối (sai host/mật khẩu thì báo ngay); schema nạp ở nền sau khi có ô nhập
            DialectAdapters.of(dialect).connection();
            SchemaIndex.dialect = dialect;
        } catch (Exception e) {
            // Kết nối lỗi ngay lúc khởi động (sai host/port/user/password, DB chưa bật...) - trước đây
            // ném thẳng RuntimeException lồng nhau ra ngoài, JVM in cả stack trace ~20 dòng (kể cả
            // internal frame của pgjdbc/socket) ra màn hình. Chỉ hiện thông điệp gốc, gọn, giống các
            // lỗi khác trong app, rồi thoát sạch - không có gì để chạy tiếp nếu không kết nối được.
            System.err.println(RED + I18n.t("sql.cannotConnect", rootCauseMessage(e)) + RESET);
            System.exit(1);
        }

        // Không dùng provider FFM: nó cài handler SIGINT/WINCH bằng sigaction native trỏ thẳng vào upcall Java
        // (FfmSignalHandler). Tín hiệu rơi vào thread không phải Java (GC thread...) là JVM chết ngay:
        // "guarantee(thread->thread_state() == _thread_in_native) failed: wrong thread state for upcall"
        // (8 file hs_err_pid*.log, đều SIGINT lúc khởi động). Provider JNI đăng ký qua sun.misc.Signal - JVM tự
        // chuyển tín hiệu sang thread Java riêng, an toàn.
        Terminal terminal = TerminalBuilder.builder()
            .system(true)
            .ffm(false)
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

        LineReaderImpl impl = ReplLineReaderFactory.create(terminal, statusBar, historyFile, highlighter);

        statusBar.setDbInfo(ContextManager.dbLabel(dialect));
        statusBar.setQueryStatus("loading schema…");
        statusBar.render();
        com.sqlctx.completion.GrammarWarmup.warmInBackground(dialect);
        SchemaIndex.reloadInBackground(dialect, error -> {
            statusBar.setQueryStatus(error == null
                    ? "idle · " + SchemaIndex.schemaTableIndex.size() + " tables"
                    : "ERROR loading schema: " + rootCauseMessage(error));
            // vẽ lại ngay nếu đang chờ phím (không thì lần vẽ kế tiếp tự lấy trạng thái mới)
            if (impl.isReading()) {
                impl.callWidget(LineReader.REDISPLAY);
            }
        });

        // ── Chế độ batch: --command="SQL" và/hoặc --file=path.sql - chạy xong thoát ngay, KHÔNG vào REPL.
        // Không hỏi xác nhận cho lệnh phá hoại (không có gì đảm bảo có TTY thật để trả lời) - dùng cho CI/script.
        String commandArg = System.getProperty("DB_COMMAND");
        String fileArg = System.getProperty("DB_FILE");
        if (commandArg != null || fileArg != null) {
            BATCH_MODE = true;
            int exitCode = 0;
            try {
                List<String> commandStatements = commandArg != null ? StatementExecutor.splitStatements(commandArg) : List.of();
                List<String> fileStatements = fileArg != null
                        ? StatementExecutor.splitStatements(Files.readString(Paths.get(fileArg))) : List.of();
                // Chế độ batch không có ai trả lời [y/N] (External Tool của IDE, script...): có câu xoá/sửa dữ liệu
                // mà không ghi rõ --yes thì từ chối CẢ LÔ trước khi chạy câu nào - không bao giờ chạy dở nửa file.
                List<String> destructive = new ArrayList<>(StatementExecutor.destructiveStatements(commandStatements));
                destructive.addAll(StatementExecutor.destructiveStatements(fileStatements));
                if (!destructive.isEmpty() && !java.util.Arrays.asList(args).contains("--yes")) {
                    terminal.writer().println(RED + I18n.t("batch.destructiveRefused", destructive.size()) + RESET);
                    destructive.stream().limit(5).forEach(st -> terminal.writer().println("  " + firstLine(st)));
                    if (destructive.size() > 5) {
                        terminal.writer().println(I18n.t("batch.andMore", destructive.size() - 5));
                    }
                    terminal.flush();
                    System.exit(2);
                }
                if (!commandStatements.isEmpty()) {
                    exitCode = StatementExecutor.runStatements(commandStatements, dialect, impl, terminal, statusBar, false);
                }
                if (exitCode == 0 && !fileStatements.isEmpty()) {
                    exitCode = StatementExecutor.runStatements(fileStatements, dialect, impl, terminal, statusBar, false);
                }
            } catch (Exception e) {
                terminal.writer().println(RED + "ERROR: " + e.getMessage() + RESET);
                exitCode = 1;
            }
            terminal.flush();
            System.exit(exitCode);
        }

        StartupBanner.print(impl, dialect);

        String pendingBuffer = null;
        while (true) {
            String line;

            try {
                String coloredPrompt = CommandDispatcher.buildPrompt();
                line = pendingBuffer != null ? impl.readLine(coloredPrompt, null, pendingBuffer) : impl.readLine(coloredPrompt);
            } catch (UserInterruptException e) {
                com.sqlctx.cli.terminal.MenuCompleter.discardMenu(terminal);
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

            dialect = CommandDispatcher.dispatch(line, dialect, impl, terminal, statusBar);
            if (CommandDispatcher.pendingEditorBuffer != null) {
                pendingBuffer = CommandDispatcher.pendingEditorBuffer;
                CommandDispatcher.pendingEditorBuffer = null;
            }
        }
        // Dọn dẹp (đóng Status, xoá màn hình, đóng terminal) đã chuyển hết vào shutdown hook ở trên,
        // vì nó chạy được cho MỌI cách thoát (return bình thường, "\q", F10, hay bị kill/Ctrl+C giữa chừng).
    }

    /**
     * SchemaIndex.reload() bọc lỗi JDBC thật trong 1-2 lớp RuntimeException (xem SchemaIndex.reload()) -
     * lấy message của nguyên nhân SÂU NHẤT (vd message gốc của PSQLException/SQLException) thay vì
     * message rỗng/chung chung của lớp RuntimeException bọc ngoài.
     */
    /**
     * --lsp: Language Server qua stdio cho editor (IntelliJ/LSP4IJ, Neovim, Helix...) - không dựng terminal/REPL.
     * Khác REPL ở 3 chỗ: lỗi cấu hình/kết nối không làm tiến trình chết (editor chỉ báo "server stopped", không ai
     * thấy stderr) mà được báo qua thông báo của editor; không tự dùng context lần trước (editor phải nói rõ nối
     * vào đâu); không ghi đè context mặc định của REPL.
     */
    private static void runLanguageServer() throws Exception {
        Dialect dialect = Dialect.POSTGRES;
        String startupError = null;
        try {
            String contextArg = System.getProperty("DB_CONTEXT");
            if (contextArg == null && System.getProperty("DB_HOST") == null) {
                throw new IllegalStateException(I18n.t("lsp.missingContext"));
            }
            if (contextArg != null) {
                ContextManager.applyContext(contextArg);
                currentContextName = contextArg;
            }
            dialect = "oracle".equalsIgnoreCase(System.getProperty("DB_DIALECT", "postgres")) ? Dialect.ORACLE : Dialect.POSTGRES;
            DialectAdapters.of(dialect).connection();
            SchemaIndex.dialect = dialect;
        } catch (Exception e) {
            startupError = rootCauseMessage(e);
        }
        com.sqlctx.completion.GrammarWarmup.warmInBackground(dialect);
        com.sqlctx.lsp.SqlLanguageServerLauncher.run(dialect, startupError);
    }

    /**
     * --format: in bản đã định dạng của --file (hoặc stdin) ra stdout - không kết nối DB. Dialect: --dialect=, không
     * thì của --context= đã lưu (chỉ đọc cấu hình), mặc định postgres.
     */
    private static int runFormatter() {
        try {
            String dialectArg = System.getProperty("DB_DIALECT");
            String contextArg = System.getProperty("DB_CONTEXT");
            Dialect dialect = dialectArg != null
                    ? ("oracle".equalsIgnoreCase(dialectArg) ? Dialect.ORACLE : Dialect.POSTGRES)
                    : contextArg != null
                    ? ConnectionProfileStore.find(contextArg).map(p -> p.dialect()).orElse(Dialect.POSTGRES)
                    : Dialect.POSTGRES;
            String file = System.getProperty("DB_FILE");
            String sql = file != null ? Files.readString(Paths.get(file)) : new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            System.out.print(com.sqlctx.format.SqlFormatter.format(sql, dialect, com.sqlctx.format.SqlFormatter.Options.fromSystemProperties()));
            if (!sql.endsWith("\n")) {
                System.out.println();
            }
            System.out.flush();
            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            return 1;
        }
    }

    /** Dòng đầu không phải comment (cắt 100 ký tự) để liệt kê câu lệnh trong thông báo lỗi. */
    private static String firstLine(String sql) {
        String line = sql.strip().lines().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("--")).findFirst().orElse(sql.strip());
        return line.length() > 100 ? line.substring(0, 99) + "…" : line;
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
