package com.sqlctx.redis;

import com.sqlctx.util.I18n;

import com.sqlctx.ui.ResultExporter;
import com.sqlctx.cli.StartupBanner;
import com.sqlctx.ui.DataViewTable;
import com.sqlctx.context.ConnectionProfileStore;
import com.sqlctx.redis.RespClient.Bulk;
import com.sqlctx.redis.RespClient.RespError;
import com.sqlctx.ui.CompletionMenu;
import com.sqlctx.ui.Dashboard;
import com.sqlctx.ui.MenuItem;
import com.sqlctx.ui.ShellReader;
import com.sqlctx.ui.ShellStatusBar;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Chế độ Redis của sqlctx: REPL riêng, chỉ dùng lại các mảnh không phụ thuộc SQL (khung bảng/GridView,
 * engine gợi ý ATN, thư mục cấu hình). Vào chế độ này khi {@code --dialect=redis}, hoặc khi --context= (hoặc
 * context lần trước) là 1 context trong {@code ~/.sqlctx/redis.conf} - xem {@link #handles()}.
 */
public final class RedisCli {

    private static final String RED = "\u001b[31m";
    private static final String YELLOW = "\u001b[33m";
    private static final String GREEN = "\u001b[1;32m";
    private static final String CYAN = "\u001b[36m";
    private static final String DIM = "\u001b[38;5;245m";
    private static final String TITLE = "\u001b[1;36m";
    private static final String RESET = "\u001b[0m";

    private final Terminal terminal;
    private final ShellStatusBar statusBar;
    private final boolean batch;
    private final boolean assumeYes;
    private RedisSession session;
    private LineReaderImpl reader;
    private CompletionMenu menu;
    private String previousContext;
    private volatile boolean quit;

    private RedisCli(Terminal terminal, RedisSession session, boolean batch, boolean assumeYes) {
        this.terminal = terminal;
        this.session = session;
        this.batch = batch;
        this.assumeYes = assumeYes;
        this.statusBar = batch ? null : new ShellStatusBar(terminal, "redis");
    }

    /**
     * Gọi sau khi SqlctxCli đã chép các tham số --x=y vào system property DB_X. Cùng tên có cả context SQL lẫn
     * Redis thì phía SQL thắng, trừ khi ghi rõ --dialect=redis.
     */
    public static boolean handles() {
        String dialect = System.getProperty("DB_DIALECT");
        if (dialect != null) {
            return dialect.equalsIgnoreCase("redis");
        }
        String context = System.getProperty("DB_CONTEXT");
        if (context == null && System.getProperty("DB_HOST") == null) {
            context = ConnectionProfileStore.getLastContextName().orElse(null);
        }
        return context != null
                && RedisProfiles.find(context).isPresent()
                && ConnectionProfileStore.find(context).isEmpty();
    }

    public static void run(String[] args) throws Exception {
        boolean assumeYes = Arrays.asList(args).contains("--yes");
        RedisProfile profile;
        try {
            profile = resolveProfile();
        } catch (IllegalArgumentException e) {
            System.err.println(RED + "ERROR: " + e.getMessage() + RESET);
            System.exit(1);
            return;
        }
        RedisSession session;
        try {
            session = new RedisSession(profile);
        } catch (IOException e) {
            System.err.println(RED + I18n.t("common.cannotConnect", "Redis", profile.label(), e.getMessage()) + RESET);
            System.exit(1);
            return;
        }
        if (!profile.name().contains(":")) {
            ConnectionProfileStore.setLastContextName(profile.name());
        }
        RedisCommandTree.use(session.loadCommands());

        String command = System.getProperty("DB_COMMAND");
        String file = System.getProperty("DB_FILE");
        boolean batch = command != null || file != null;

        // Cùng lý do với SqlctxCli: provider FFM làm JVM chết khi SIGINT rơi vào thread không phải Java.
        Terminal terminal = TerminalBuilder.builder().system(true).ffm(false).encoding(StandardCharsets.UTF_8).build();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Status.getExistingStatus(terminal).ifPresent(Status::close);
            } catch (Exception ignored) {
            }
            try {
                terminal.close();
            } catch (Exception ignored) {
            }
            session.close();
        }));

        RedisCli cli = new RedisCli(terminal, session, batch, assumeYes);
        if (batch) {
            List<String> commands = new ArrayList<>();
            if (command != null) {
                commands.addAll(RedisArgs.splitCommands(command));
            }
            if (file != null) {
                commands.addAll(RedisArgs.fileCommands(Files.readString(Paths.get(file))));
            }
            System.exit(cli.runBatch(commands));
        }
        cli.repl();
        System.exit(0);
    }

    /**
     * --context= (đã lưu) và/hoặc --host= --port= --user= --password= --dbname= (số DB) --tls=true --readonly=true
     * --sentinel-master= (host/port là của sentinel) --sentinel-password=.
     */
    static RedisProfile resolveProfile() throws IOException {
        String context = System.getProperty("DB_CONTEXT");
        if (context == null && System.getProperty("DB_HOST") == null) {
            context = ConnectionProfileStore.getLastContextName().orElse(null);
        }
        Optional<RedisProfile> saved = context == null ? Optional.empty() : RedisProfiles.find(context);
        boolean overrides = Arrays.asList("DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD", "DB_DBNAME", "DB_TLS",
                "DB_READONLY", "DB_SENTINEL-MASTER", "DB_SENTINEL-PASSWORD").stream().anyMatch(k -> System.getProperty(k) != null);
        if (context != null && saved.isEmpty() && System.getProperty("DB_HOST") == null) {
            throw new IllegalArgumentException(I18n.t("redis.err.noContext", context, RedisProfiles.file()));
        }
        RedisProfile base = saved.orElse(null);
        String host = System.getProperty("DB_HOST", base != null ? base.host() : "127.0.0.1");
        int port = RedisProfiles.parseInt(System.getProperty("DB_PORT"), base != null ? base.port() : 6379);
        String user = System.getProperty("DB_USER", base != null ? base.user() : null);
        String password = System.getProperty("DB_PASSWORD", base != null ? base.password() : null);
        int db = RedisProfiles.parseInt(System.getProperty("DB_DBNAME"), base != null ? base.db() : 0);
        boolean tls = System.getProperty("DB_TLS") != null ? Boolean.parseBoolean(System.getProperty("DB_TLS"))
                : base != null && base.tls();
        boolean readonly = System.getProperty("DB_READONLY") != null
                ? Boolean.parseBoolean(System.getProperty("DB_READONLY")) : base != null && base.readonly();
        String sentinelMaster = System.getProperty("DB_SENTINEL-MASTER", base != null ? base.sentinelMaster() : null);
        String sentinelPassword = System.getProperty("DB_SENTINEL-PASSWORD", base != null ? base.sentinelPassword() : null);
        String name = context != null ? context : host + ":" + port;
        RedisProfile profile = new RedisProfile(name, host, port, user, password, db, tls, readonly,
                sentinelMaster == null || sentinelMaster.isEmpty() ? null : sentinelMaster, sentinelPassword);
        if (context != null && (saved.isEmpty() || overrides)) {
            RedisProfiles.save(profile);
        }
        return profile;
    }

    // ===================================================================== REPL

    private void repl() throws IOException {
        Path history = ConnectionProfileStore.configDir().resolve("redis_history.txt");
        Files.createDirectories(history.getParent());
        reader = createReader(history);
        statusBar.setInfo(info(), session.profile().readonly());
        statusBar.render();
        printBanner();

        while (!quit) {
            String line;
            try {
                line = reader.readLine(prompt());
            } catch (UserInterruptException e) {
                menu.discard();
                continue;
            } catch (EndOfFileException e) {
                break;
            }
            if (quit) {
                break;
            }
            if (line == null || line.isBlank()) {
                continue;
            }
            execute(line.strip());
            statusBar.setInfo(info(), session.profile().readonly());
            statusBar.render();
        }
    }

    private LineReaderImpl createReader(Path history) {
        ShellReader.Shell shell = ShellReader.create(terminal, new ShellReader.Config("sqlctx-redis", history,
                new RedisHighlighter(), false, this::updateHint, this::suggest, RedisCli::helpText,
                () -> session.running, () -> session.cancelRunning(), () -> quit = true, statusBar));
        menu = shell.menu();
        return shell.reader();
    }

    /** Gợi ý của popup: RedisCompletion trên session hiện tại (đổi khi \ctx). */
    private List<MenuItem> suggest(String line, int cursor) {
        return RedisCompletion.suggest(line, cursor, session).stream()
                .map(i -> new MenuItem(i.value(), i.description(), switch (i.kind()) {
                    case COMMAND -> "command";
                    case KEYWORD -> "keyword";
                    case KEY -> "key";
                    case MEMBER -> "field";
                    case VALUE -> "value";
                    case META -> "sqlctx";
                }))
                .toList();
    }

    private void updateHint(String buffer, int cursor) {
        String before = buffer.substring(0, Math.min(cursor, buffer.length()));
        if (before.isBlank()) {
            statusBar.setHint(session.inTransaction ? I18n.t("redis.hint.multi") : null, false);
        } else if (before.stripLeading().startsWith("\\")) {
            String cmd = before.strip().split("\\s+")[0];
            String desc = RedisCompletion.META_COMMANDS.stream().filter(m -> m[0].equals(cmd)).map(m -> m[0] + " - " + I18n.t(m[1]))
                    .findFirst().orElse("\\ctx \\c \\l \\dt \\d \\tail \\h \\i \\lang \\q");
            statusBar.setHint(desc, false);
        } else {
            RedisSyntax.Inspection inspection = RedisSyntax.inspect(buffer);
            RedisCommandSpec spec = RedisCommandSpec.find(RedisSyntax.words(before));
            if (inspection.error() != null) {
                statusBar.setHint(inspection.error() + (spec != null ? "  ·  " + spec.syntax() : ""), true);
            } else if (spec != null) {
                String complexity = spec.complexityShort();
                statusBar.setHint(spec.syntax() + "  —  " + spec.summary() + (complexity != null ? "  ·  " + complexity : ""), false);
            } else {
                statusBar.setHint(null, false);
            }
        }
        statusBar.render();
    }

    private String prompt() {
        RedisProfile p = session.profile();
        String tx = session.inTransaction ? YELLOW + "(TX)" + RESET : "";
        String where = session.main().cluster() ? session.main().address() : String.valueOf(p.db()); // cluster chỉ có db 0
        return CYAN + p.name() + RESET + DIM + "[" + where + "]" + RESET + tx + "> ";
    }

    private String info() {
        RedisProfile p = session.profile();
        return p.name() + " · " + p.label();
    }

    private void printBanner() {
        Map<String, String> server = infoSection("server");
        Object dbsize = callQuietly("DBSIZE");
        RedisProfile p = session.profile();
        String text = TITLE + I18n.t("redis.banner.title") + RESET + "\n\n"
                + StartupBanner.label("banner.connected") + GREEN + p.label() + RESET
                + (p.sentinelMaster() != null || session.main().cluster() ? " → " + session.main().address() : "")
                + (p.readonly() ? YELLOW + I18n.t("common.readonlyBanner") + RESET : "") + "\n"
                + StartupBanner.label("banner.config") + RedisProfiles.file() + "\n"
                + StartupBanner.label("banner.server") + server.getOrDefault("redis_version", "?")
                + " (" + server.getOrDefault("redis_mode", "?") + ", " + server.getOrDefault("os", "?") + ")\n"
                + StartupBanner.label("banner.keys") + I18n.t("banner.keysValue", dbsize instanceof Long n ? n : "?", p.db()) + "\n"
                + StartupBanner.label("banner.commands") + I18n.t("banner.commandsValue", RedisCommandTree.current().commands().size(),
                RedisCommandTree.current().source()) + "\n\n"
                + (session.main().cluster() ? DIM + I18n.t("redis.banner.cluster") + RESET + "\n" : "")
                + DIM + I18n.t("redis.banner.tip") + RESET + "\n";
        reader.printAbove(text);
    }

    private static String helpText() {
        StringBuilder sb = new StringBuilder();
        sb.append(TITLE).append(I18n.t("redis.help.title")).append(RESET).append('\n');
        for (String[] m : RedisCompletion.META_COMMANDS) {
            sb.append(String.format("  %-12s %s%n", m[0] + (m[0].equals("\\ctx") ? " [NAME|-|save NAME]"
                    : m[0].equals("\\c") ? " N" : m[0].equals("\\dt") ? " [PATTERN]" : m[0].equals("\\d") ? " KEY"
                    : m[0].equals("\\h") ? " [" + I18n.t("redis.help.cmdArg") + "]" : m[0].equals("\\i") ? " FILE"
                    : m[0].equals("\\tail") ? " KEY [GROUP g c] [FROM id]" : m[0].equals("\\lang") ? " [vi|en]" : ""), I18n.t(m[1])));
        }
        sb.append('\n').append(I18n.t("redis.help.passthrough")).append('\n');
        sb.append(I18n.t("redis.help.safety")).append('\n');
        return sb.toString();
    }

    // ===================================================================== chạy lệnh

    private int runBatch(List<String> commands) {
        if (!assumeYes) {
            List<String> dangerous = commands.stream().filter(c -> {
                RedisCommandSpec spec = specOf(c);
                return spec != null && spec.isDangerous();
            }).toList();
            if (!dangerous.isEmpty()) {
                println(RED + I18n.t("common.destructiveRefused", dangerous.size()) + RESET);
                dangerous.stream().limit(5).forEach(c -> println("  " + c));
                return 2;
            }
        }
        for (String c : commands) {
            if (!execute(c)) {
                return 1;
            }
        }
        return 0;
    }

    private static RedisCommandSpec specOf(String line) {
        try {
            return RedisCommandSpec.find(RedisArgs.split(line).stream().map(RedisArgs::text).toList());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Chạy 1 dòng (lệnh \ hoặc lệnh Redis). false nếu lỗi - batch dừng ở lỗi đầu tiên. */
    private boolean execute(String line) {
        if (line.startsWith("\\")) {
            return meta(line);
        }
        if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
            quit = true;
            return true;
        }
        List<byte[]> args;
        try {
            args = RedisArgs.split(line);
        } catch (IllegalArgumentException e) {
            println(RED + I18n.t("redis.err.badArgs", e.getMessage()) + RESET);
            return false;
        }
        if (args.isEmpty()) {
            return true;
        }
        List<String> words = args.stream().map(RedisArgs::text).toList();
        String cmd = words.get(0).toUpperCase(Locale.ROOT);
        if (cmd.equals("HELP") && words.size() > 1) {
            return help(words.get(1));
        }
        if (cmd.equals("SYNC") || cmd.equals("PSYNC")) {
            // Lệnh nội bộ của replication: server đẩy RDB rồi luồng lệnh replication lên chính kết nối này, reply kế
            // tiếp sqlctx đọc được sẽ là rác (đã gặp khi chạy thử mọi lệnh: SYNC treo, PSYNC làm hỏng lệnh sau đó).
            println(RED + I18n.t("redis.err.replication", cmd) + RESET);
            return false;
        }
        RedisCommandSpec spec = RedisCommandSpec.find(words);

        if (session.profile().readonly() && (spec == null || spec.isWrite())) {
            println(RED + I18n.t("common.readonlyBlocked", session.profile().name(),
                    spec == null ? I18n.t("redis.readonly.unknown", cmd) : I18n.t("redis.readonly.write", spec.name())) + RESET);
            return false;
        }
        if (spec != null && spec.isDangerous() && !batch && !confirm(line, spec)) {
            println(I18n.t("common.cancelled"));
            return true;
        }
        if (spec != null && spec.isSubscribe() && !session.inTransaction) {
            return stream(args, words);
        }
        return call(args, words, spec);
    }

    private boolean call(List<byte[]> args, List<String> words, RedisCommandSpec spec) {
        String cmd = words.get(0).toUpperCase(Locale.ROOT);
        setStatus("RUNNING " + cmd + (spec != null && spec.isBlocking() ? " (" + I18n.t("common.ctrlcToStop") + ")" : ""));
        long start = System.nanoTime();
        Object reply;
        session.cancelled = false;
        session.running = true;
        try {
            reply = session.main().call(args);
        } catch (IOException e) {
            boolean cancelled = session.cancelled;
            reconnectAfter(cancelled ? I18n.t("common.stopped", cmd) : I18n.t("common.connectionLost", e.getMessage()));
            return cancelled;
        } finally {
            session.running = false;
        }
        double seconds = (System.nanoTime() - start) / 1e9;

        boolean ok = !(reply instanceof RespError);
        if (ok) {
            track(cmd, words, reply);
            List<RedisSyntax.Role> roles = RedisSyntax.inspect(String.join(" ",
                    words.stream().map(RedisCompletion::quoteIfNeeded).toList())).roles();
            session.recentKeys.learn(words, roles, spec != null ? spec.keyType() : null, reply);
        }
        show(RedisReplyRenderer.render(words, reply), reply);
        if (!batch) {
            println(DIM + String.format("(%.3fs)", seconds) + RESET);
        }
        setStatus(ok ? "idle" : "ERROR " + cmd);
        return ok;
    }

    /** Trạng thái phía client đi theo lệnh vừa chạy thành công: DB hiện tại, đang trong MULTI hay không. */
    private void track(String cmd, List<String> words, Object reply) {
        switch (cmd) {
            case "SELECT" -> {
                if (!session.inTransaction) {
                    session.dbChanged(RedisProfiles.parseInt(words.get(1), session.profile().db()));
                }
            }
            case "MULTI" -> session.inTransaction = true;
            case "EXEC", "DISCARD", "RESET" -> session.inTransaction = false;
            default -> {
            }
        }
        if (session.inTransaction && reply instanceof RespError) {
            session.inTransaction = false;
        }
    }

    /** SUBSCRIBE/PSUBSCRIBE/MONITOR: in tin tới liên tục cho tới Ctrl+C, rồi nối lại (kết nối đã ở chế độ đẩy tin). */
    private boolean stream(List<byte[]> args, List<String> words) {
        String cmd = words.get(0).toUpperCase(Locale.ROOT);
        setStatus(cmd + " · " + I18n.t("common.ctrlcToStop"));
        session.cancelled = false;
        session.running = true;
        try {
            session.main().send(args);
            while (true) {
                Object msg = session.main().read();
                println(formatPush(msg));
                terminal.flush();
            }
        } catch (IOException e) {
            reconnectAfter(session.cancelled ? I18n.t("common.stopped", cmd) : I18n.t("common.connectionLost", e.getMessage()));
            return session.cancelled;
        } finally {
            session.running = false;
        }
    }

    static String formatPush(Object msg) {
        if (msg instanceof RespClient.Simple s) {
            return s.text(); // MONITOR
        }
        if (msg instanceof RespError e) {
            return RED + "(error) " + e.message() + RESET;
        }
        if (msg instanceof List<?> parts && !parts.isEmpty()) {
            String kind = RedisSession.text(parts.get(0)).toLowerCase(Locale.ROOT);
            if (kind.equals("message") && parts.size() == 3) {
                return CYAN + RedisSession.text(parts.get(1)) + RESET + ": " + RedisReplyRenderer.scalar(parts.get(2));
            }
            if (kind.equals("pmessage") && parts.size() == 4) {
                return CYAN + RedisSession.text(parts.get(2)) + RESET + DIM + " (" + RedisSession.text(parts.get(1)) + ")"
                        + RESET + ": " + RedisReplyRenderer.scalar(parts.get(3));
            }
            if ((kind.equals("subscribe") || kind.equals("psubscribe")) && parts.size() == 3) {
                return DIM + I18n.t("redis.subscribed", RedisSession.text(parts.get(1)), RedisSession.text(parts.get(2))) + RESET;
            }
        }
        List<String> lines = new ArrayList<>();
        RedisReplyRenderer.format(msg, "", lines);
        return String.join("\n", lines);
    }

    private void reconnectAfter(String message) {
        println(YELLOW + message + RESET);
        try {
            session.reconnect();
            setStatus(I18n.t("common.reconnected"));
        } catch (IOException e) {
            println(RED + I18n.t("common.reconnectFailed", e.getMessage()) + RESET);
            setStatus(I18n.t("common.connectionLostStatus"));
        }
    }

    private boolean confirm(String line, RedisCommandSpec spec) {
        if (assumeYes) {
            return true;
        }
        try {
            terminal.writer().print(YELLOW + I18n.t("redis.confirm", spec.summary(), session.profile().label()) + RESET
                    + line + "\n" + YELLOW + I18n.t("common.runIt") + RESET);
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
        } catch (IOException e) {
            return false;
        }
    }

    // ===================================================================== lệnh \

    private boolean meta(String line) {
        String[] parts = line.split("\\s+", 3);
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1] : null;
        switch (cmd) {
            case "\\q" -> quit = true;
            case "\\lang" -> {
                boolean[] ok = new boolean[1];
                String msg = I18n.langCommand(arg, ok);
                println(ok[0] ? msg : RED + msg + RESET);
                return ok[0];
            }
            case "\\?", "\\h" -> {
                return arg == null ? printLines(helpText().lines().toList()) : help(arg);
            }
            case "\\ctx", "\\context" -> {
                return context(arg, parts.length > 2 ? parts[2] : null);
            }
            case "\\c", "\\connect" -> {
                if (arg == null) {
                    println(RED + I18n.t("common.usage", I18n.t("redis.usage.c")) + RESET);
                    return false;
                }
                return execute("SELECT " + arg);
            }
            case "\\l" -> {
                return listDatabases();
            }
            case "\\dt" -> {
                return listKeys(arg == null ? "*" : RedisSyntax.unquote(arg));
            }
            case "\\d" -> {
                if (arg == null) {
                    return listKeys("*");
                }
                return describeKey(RedisSyntax.unquote(line.substring(cmd.length()).strip()));
            }
            case "\\tail" -> {
                return tail(line.substring(cmd.length()).strip());
            }
            case "\\o" -> {
                return output(arg);
            }
            case "\\stats" -> {
                return stats(arg);
            }
            case "\\i" -> {
                if (arg == null) {
                    println(RED + I18n.t("common.usage", "\\i FILE") + RESET);
                    return false;
                }
                try {
                    for (String c : RedisArgs.fileCommands(Files.readString(Paths.get(arg)))) {
                        println(DIM + "> " + c + RESET);
                        if (!execute(c)) {
                            return false;
                        }
                    }
                } catch (IOException e) {
                    println(RED + I18n.t("common.err.readFile", arg, e.getMessage()) + RESET);
                    return false;
                }
            }
            default -> {
                println(RED + I18n.t("common.unknownMeta", cmd) + RESET);
                return false;
            }
        }
        return true;
    }

    /**
     * \tail KEY [GROUP g c] [FROM id]: chờ entry mới (XREAD/XREADGROUP BLOCK 0) và in ngay khi tới, như tail -f, tới
     * khi Ctrl+C. Mặc định từ "$" (chỉ entry mới); có GROUP thì từ ">" (entry chưa giao cho ai) - entry đọc theo group
     * vào PEL của consumer đó và KHÔNG tự XACK.
     */
    private boolean tail(String rest) {
        List<String> a = RedisSyntax.words(rest);
        if (a.isEmpty()) {
            println(RED + I18n.t("common.usage", "\\tail KEY [GROUP group consumer] [FROM id]") + RESET);
            return false;
        }
        String key = a.get(0);
        String group = null;
        String consumer = null;
        String from = null;
        for (int i = 1; i < a.size(); i++) {
            String opt = a.get(i).toUpperCase(Locale.ROOT);
            if (opt.equals("GROUP") && i + 2 < a.size()) {
                group = a.get(++i);
                consumer = a.get(++i);
            } else if (opt.equals("FROM") && i + 1 < a.size()) {
                from = a.get(++i);
            } else {
                println(RED + I18n.t("redis.tail.badOption", a.get(i)) + RESET);
                return false;
            }
        }
        if (group != null && session.profile().readonly()) {
            println(RED + I18n.t("redis.tail.readonly") + RESET);
            return false;
        }
        String last = from != null ? from : group != null ? ">" : "$";
        try {
            String type = RedisSession.text(session.main().call("TYPE", key));
            if (!type.equals("stream") && !type.equals("none")) {
                println(RED + I18n.t("redis.tail.notStream", key, type) + RESET);
                return false;
            }
        } catch (IOException e) {
            reconnectAfter(I18n.t("common.connectionLost", e.getMessage()));
            return false;
        }
        println(DIM + I18n.t("redis.tail.start", key, group != null ? I18n.t("redis.tail.group", group, consumer) : "", last) + RESET);
        setStatus("TAIL " + key + " · " + I18n.t("common.ctrlcToStop"));
        java.time.format.DateTimeFormatter clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
        int count = 0;
        session.cancelled = false;
        session.running = true;
        try {
            while (true) {
                List<String> command = group == null
                        ? List.of("XREAD", "BLOCK", "0", "COUNT", "100", "STREAMS", key, last)
                        : List.of("XREADGROUP", "GROUP", group, consumer, "BLOCK", "0", "COUNT", "100", "STREAMS", key, last);
                Object reply = session.main().call(command.toArray(String[]::new));
                if (reply instanceof RespError e) {
                    println(RED + "(error) " + e.message() + RESET);
                    return false;
                }
                List<List<?>> entries = RedisReplyRenderer.readEntries(reply);
                for (List<?> entry : entries) {
                    String id = RedisReplyRenderer.cell(entry.get(0));
                    println(DIM + java.time.LocalTime.now().format(clock) + RESET + "  " + CYAN + id + RESET + "  "
                            + RedisReplyRenderer.entryFields(entry.get(1)));
                    count++;
                    if (group == null || !last.equals(">")) {
                        last = id;
                    }
                }
                if (group != null && !last.equals(">") && entries.isEmpty()) {
                    last = ">"; // đã đọc hết lịch sử PEL của consumer (FROM 0), giờ chờ entry mới
                }
            }
        } catch (IOException e) {
            reconnectAfter(session.cancelled ? I18n.t("redis.tail.stopped", count) : I18n.t("common.connectionLost", e.getMessage()));
            return session.cancelled;
        } finally {
            session.running = false;
        }
    }

    private boolean help(String command) {
        RedisCommandSpec spec = RedisCommandSpec.find(command);
        if (spec == null) {
            println(YELLOW + I18n.t("redis.help.noDocs", command) + RESET);
            return true;
        }
        List<String> lines = new ArrayList<>();
        lines.add(TITLE + spec.syntax() + RESET);
        lines.add("  " + spec.summary());
        lines.add(DIM + I18n.t("redis.help.group", spec.group()) + (spec.flags().isEmpty() ? "" : " · " + String.join(", ", spec.flags())) + RESET);
        if (spec.complexity() != null && !spec.complexity().isBlank()) {
            lines.add(DIM + I18n.t("redis.help.complexity", spec.complexity()) + RESET);
        }
        RedisCommandSpec.all().stream().filter(s -> s.name().startsWith(spec.name() + " "))
                .forEach(s -> lines.add("  " + s.syntax() + DIM + " - " + s.summary() + RESET));
        return printLines(lines);
    }

    private boolean context(String arg, String extra) {
        List<RedisProfile> all = RedisProfiles.loadAll();
        if (arg == null) {
            if (all.isEmpty()) {
                println(DIM + I18n.t("common.noContexts", RedisProfiles.file()) + RESET);
                return true;
            }
            List<List<String>> rows = new ArrayList<>();
            for (RedisProfile p : all) {
                boolean current = p.name().equalsIgnoreCase(session.profile().name());
                rows.add(List.of(current ? "*" : "", p.name(), p.label(), p.readonly() ? "readonly" : ""));
            }
            print(new RedisReplyRenderer.Table(List.of("", "context", I18n.t("common.connection"), ""), rows));
            return true;
        }
        if (arg.equals("save")) {
            if (extra == null) {
                println(RED + I18n.t("common.usage", "\\ctx save NAME") + RESET);
                return false;
            }
            RedisProfile p = session.profile();
            try {
                RedisProfiles.save(new RedisProfile(extra, p.host(), p.port(), p.user(), p.password(), p.db(), p.tls(),
                        p.readonly(), p.sentinelMaster(), p.sentinelPassword()));
            } catch (IOException e) {
                println(RED + "ERROR: " + e.getMessage() + RESET);
                return false;
            }
            println(I18n.t("common.ctxSaved", extra));
            return true;
        }
        String target = arg.equals("-") ? previousContext : arg;
        if (target == null) {
            println(RED + I18n.t("common.noPreviousCtx") + RESET);
            return false;
        }
        Optional<RedisProfile> profile = RedisProfiles.find(target);
        if (profile.isEmpty()) {
            println(RED + I18n.t("redis.ctx.notFound", target)
                    + (ConnectionProfileStore.find(target).isPresent() ? I18n.t("redis.ctx.isSql", target) : "") + RESET);
            return false;
        }
        try {
            RedisSession next = new RedisSession(profile.get());
            previousContext = session.profile().name();
            session.close();
            session = next;
            RedisCommandTree.use(next.loadCommands()); // server khác có thể khác phiên bản/module
            ConnectionProfileStore.setLastContextName(profile.get().name());
            println(GREEN + I18n.t("common.switchedTo", profile.get().name(), profile.get().label()) + RESET);
            return true;
        } catch (IOException e) {
            println(RED + I18n.t("common.cannotConnect", "Redis", profile.get().label(), e.getMessage()) + RESET);
            return false;
        }
    }

    private boolean listDatabases() {
        Map<String, String> keyspace = infoSection("keyspace");
        if (keyspace.isEmpty()) {
            println(DIM + I18n.t("redis.noKeysInAnyDb") + RESET);
            return true;
        }
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(keyspace).entrySet()) {
            Map<String, String> stats = new TreeMap<>();
            for (String kv : e.getValue().split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    stats.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
            }
            String db = e.getKey().replace("db", "");
            rows.add(List.of(db.equals(String.valueOf(session.profile().db())) ? "*" : "", db,
                    stats.getOrDefault("keys", ""), stats.getOrDefault("expires", ""), stats.getOrDefault("avg_ttl", "")));
        }
        show(new RedisReplyRenderer.Table(List.of("", "db", "keys", "expires", "avg_ttl"), rows), null);
        return true;
    }

    private static final int LIST_LIMIT = 1000;

    /** \dt: SCAN toàn bộ theo mẫu (cluster: mọi master; tối đa {@link #LIST_LIMIT} key), rồi lấy TYPE + PTTL bằng pipeline. */
    private boolean listKeys(String pattern) {
        try {
            RedisConnection.ScanResult scan = session.main().scan(pattern, null, LIST_LIMIT, 0);
            if (scan.error() != null) {
                println(RED + "(error) " + scan.error() + RESET);
                return false;
            }
            List<String> keys = new ArrayList<>(scan.keys());
            boolean truncated = !scan.complete();
            if (keys.isEmpty()) {
                println(DIM + I18n.t("redis.noKeysMatch", pattern) + RESET);
                return true;
            }
            keys.sort(null);
            List<List<String>> commands = new ArrayList<>();
            for (String k : keys) {
                commands.add(List.of("TYPE", k));
                commands.add(List.of("PTTL", k));
            }
            List<Object> replies = session.main().pipeline(commands);
            List<List<String>> rows = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                rows.add(List.of(keys.get(i), RedisSession.text(replies.get(2 * i)), ttl(replies.get(2 * i + 1))));
            }
            show(new RedisReplyRenderer.Table(List.of("key", "type", "ttl"), rows), null);
            println(DIM + I18n.t("redis.keysCount", keys.size()) + (truncated ? I18n.t("redis.keysTruncated", LIST_LIMIT) : "") + RESET);
            return true;
        } catch (IOException e) {
            reconnectAfter(I18n.t("common.connectionLost", e.getMessage()));
            return false;
        }
    }

    private static String ttl(Object pttl) {
        if (!(pttl instanceof Long ms)) {
            return "";
        }
        if (ms == -1) {
            return "∞";
        }
        if (ms < 0) {
            return "";
        }
        long s = ms / 1000;
        return s >= 86400 ? s / 86400 + "d" + (s % 86400) / 3600 + "h"
                : s >= 3600 ? s / 3600 + "h" + (s % 3600) / 60 + "m"
                : s >= 60 ? s / 60 + "m" + s % 60 + "s"
                : String.format("%.1fs", ms / 1000.0);
    }

    /** \d KEY: kiểu, TTL, encoding, bộ nhớ, kích thước, rồi xem trước tối đa 20 phần tử. */
    private boolean describeKey(String key) {
        try {
            String type = RedisSession.text(session.main().call("TYPE", key));
            if (type.equals("none")) {
                println(YELLOW + I18n.t("redis.noSuchKey", key) + RESET);
                return false;
            }
            String sizeCommand = switch (type) {
                case "string" -> "STRLEN";
                case "list" -> "LLEN";
                case "hash" -> "HLEN";
                case "set" -> "SCARD";
                case "zset" -> "ZCARD";
                case "stream" -> "XLEN";
                default -> null;
            };
            List<List<String>> commands = new ArrayList<>(List.of(
                    List.of("PTTL", key), List.of("OBJECT", "ENCODING", key), List.of("MEMORY", "USAGE", key)));
            if (sizeCommand != null) {
                commands.add(List.of(sizeCommand, key));
            }
            List<Object> r = session.main().pipeline(commands);
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("type", type));
            rows.add(List.of("ttl", ttl(r.get(0))));
            rows.add(List.of("encoding", RedisReplyRenderer.cell(r.get(1))));
            rows.add(List.of("memory", r.get(2) instanceof Long bytes ? bytes + " bytes" : RedisReplyRenderer.cell(r.get(2))));
            if (sizeCommand != null) {
                rows.add(List.of(type.equals("string") ? "length" : "size", RedisReplyRenderer.cell(r.get(3))));
            }
            Object groups = null;
            if (type.equals("stream")) {
                List<Object> s = session.main().pipeline(
                        List.of(List.of("XINFO", "STREAM", key), List.of("XINFO", "GROUPS", key)));
                Map<String, Object> info = flatMap(s.get(0));
                if (info.get("first-entry") instanceof List<?> first && !first.isEmpty()) {
                    rows.add(List.of("first id", RedisReplyRenderer.cell(first.get(0))));
                }
                rows.add(List.of("last id", RedisReplyRenderer.cell(info.get("last-generated-id"))));
                rows.add(List.of("groups", RedisReplyRenderer.cell(info.get("groups"))));
                groups = s.get(1);
            }
            print(new RedisReplyRenderer.Table(List.of(key, ""), rows));
            if (groups instanceof List<?> g && !g.isEmpty()) {
                println(DIM + I18n.t("redis.consumerGroups") + RESET);
                print(RedisReplyRenderer.render(List.of("XINFO", "GROUPS"), groups));
            }

            List<String> preview = switch (type) {
                case "string" -> List.of("GETRANGE", key, "0", "199");
                case "list" -> List.of("LRANGE", key, "0", "19");
                case "hash" -> List.of("HSCAN", key, "0", "COUNT", "20");
                case "set" -> List.of("SSCAN", key, "0", "COUNT", "20");
                case "zset" -> List.of("ZRANGE", key, "0", "19", "WITHSCORES");
                case "stream" -> List.of("XRANGE", key, "-", "+", "COUNT", "10");
                default -> null;
            };
            if (preview != null) {
                Object reply = session.main().call(preview.toArray(String[]::new));
                if (reply instanceof List<?> page && page.size() == 2 && preview.get(0).endsWith("SCAN")) {
                    reply = page.get(1);
                    preview = type.equals("hash") ? List.of("HGETALL") : List.of("SMEMBERS");
                }
                println(DIM + I18n.t("redis.preview") + RESET);
                print(RedisReplyRenderer.render(preview, reply));
            }
            return true;
        } catch (IOException e) {
            reconnectAfter(I18n.t("common.connectionLost", e.getMessage()));
            return false;
        }
    }

    // ===================================================================== tiện ích

    /** Reply dạng map phẳng (khoá, giá trị, khoá, giá trị...) -> Map; không phải map thì rỗng. */
    private static Map<String, Object> flatMap(Object reply) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (reply instanceof List<?> l) {
            for (int i = 0; i + 1 < l.size(); i += 2) {
                out.put(RedisSession.text(l.get(i)), l.get(i + 1));
            }
        }
        return out;
    }

    private Map<String, String> infoSection(String section) {
        Map<String, String> values = new TreeMap<>();
        Object reply = callQuietly("INFO", section);
        if (reply instanceof Bulk b) {
            for (String l : b.utf8().split("\r?\n")) {
                int colon = l.indexOf(':');
                if (colon > 0 && !l.startsWith("#")) {
                    values.put(l.substring(0, colon), l.substring(colon + 1));
                }
            }
        }
        return values;
    }

    private Object callQuietly(String... args) {
        try {
            return session.main().call(args);
        } catch (IOException e) {
            return null;
        }
    }

    // ===================================================================== \\stats

    /**
     * Dashboard toàn màn hình (chu kỳ {@code arg} giây, mặc định 2) tới khi q/Esc/Ctrl+C; batch hoặc terminal không vẽ
     * được toàn màn hình thì in 1 khung. Dùng kết nối riêng để không chen vào kết nối chính (có thể đang trong MULTI).
     */
    private boolean stats(String arg) {
        long seconds;
        try {
            seconds = arg == null ? 2 : Math.max(1, Long.parseLong(arg));
        } catch (NumberFormatException e) {
            println(RED + I18n.t("common.usage", "\\stats [SECONDS]") + RESET);
            return false;
        }
        try (RedisConnection c = RedisConnection.open(session.profile(), 5000)) {
            if (batch || !Dashboard.supported(terminal)) {
                // 2 mẫu cách nhau 1 chu kỳ: hit %, evict, lệnh chậm tính trong khoảng đó thay vì từ lúc server khởi động
                RedisStats.Sample first = RedisStats.sample(c);
                Thread.sleep(seconds * 1000);
                RedisStats.Sample s = RedisStats.sample(c);
                printLines(RedisStats.frame(RedisStats.title(session.profile(), s, seconds, false), List.of(first, s), terminal.getWidth()));
                return true;
            }
            if (Dashboard.show(terminal, new RedisDashboard(c, session.profile()), seconds * 1000)) {
                terminal.writer().print("\u001b[H\u001b[2J");
                statusBar.redraw();
            }
            return true;
        } catch (IOException e) {
            println(RED + "(error) " + e.getMessage() + RESET);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ===================================================================== \\o: xuất kết quả ra file

    /** Như \\o phía SQL: .csv/.json là kết quả của lệnh kế tiếp rồi tự tắt; tên khác là text, ghi nối tới khi \\o. */
    private Path outputFile;
    private String outputFormat;

    private boolean output(String arg) {
        if (arg == null) {
            println(outputFile != null ? I18n.t("redis.exportOff", outputFile) : I18n.t("redis.exportNone"));
            outputFile = null;
            outputFormat = null;
            return true;
        }
        outputFile = Paths.get(arg);
        outputFormat = ResultExporter.formatFor(arg);
        println("text".equals(outputFormat) ? I18n.t("redis.exportOnText", arg) : I18n.t("redis.exportOnNext", arg, outputFormat));
        return true;
    }

    /** In ra màn hình, hoặc ra file nếu \\o đang bật (lỗi thì vẫn in ra màn hình). {@code reply}: reply gốc nếu có. */
    private void show(RedisReplyRenderer.Output output, Object reply) {
        if (outputFile == null || output instanceof RedisReplyRenderer.Lines l && l.error()) {
            print(output);
            return;
        }
        RedisReplyRenderer.Table t = exportTable(output, reply);
        try {
            ResultExporter.export(outputFile, outputFormat, t.headers(), t.rows());
            println(I18n.t("redis.exported", t.rows().size(), t.headers().size(), outputFile));
        } catch (IOException e) {
            println(RED + I18n.t("common.err.writeFile", outputFile, e.getMessage()) + RESET);
        }
        if (!"text".equals(outputFormat)) {
            outputFile = null;
            outputFormat = null;
        }
    }

    /** Bảng giữ nguyên; reply dạng mảng thành 1 cột "value" (giá trị thô, không có 1) "..." như khi in); còn lại 1 ô. */
    static RedisReplyRenderer.Table exportTable(RedisReplyRenderer.Output output, Object reply) {
        if (output instanceof RedisReplyRenderer.Table t) {
            return t;
        }
        if (output instanceof RedisReplyRenderer.Many m) {
            for (RedisReplyRenderer.Output part : m.parts()) {
                if (part instanceof RedisReplyRenderer.Table t) {
                    return t;
                }
            }
        }
        List<List<String>> rows = new ArrayList<>();
        if (reply instanceof List<?> items) {
            for (Object item : items) {
                rows.add(List.of(cell(item)));
            }
        } else {
            rows.add(List.of(cell(reply))); // nil -> null trong json, ô trống trong csv
        }
        return new RedisReplyRenderer.Table(List.of("value"), rows);
    }

    private static String cell(Object o) {
        if (o == null) {
            return "<null>"; // ResultExporter: ô trống trong csv, null trong json
        }
        if (o instanceof List<?> nested) {
            return nested.stream().map(RedisCli::cell).collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }
        return RedisSession.text(o);
    }

    private void print(RedisReplyRenderer.Output output) {
        try {
            if (output instanceof RedisReplyRenderer.Table t) {
                if (t.rows().isEmpty()) {
                    println("(empty array)");
                } else if (batch) {
                    DataViewTable.printStatic(terminal, t.headers(), t.rows());
                } else {
                    DataViewTable.print(terminal, t.headers(), t.rows(), statusBar::redraw);
                }
            } else if (output instanceof RedisReplyRenderer.Many m) {
                m.parts().forEach(this::print);
            } else if (output instanceof RedisReplyRenderer.Lines l) {
                List<String> lines = l.error() ? l.lines().stream().map(s -> RED + s + RESET).toList() : l.lines();
                printLines(lines);
            }
        } catch (Exception e) {
            println(RED + "ERROR: " + e.getMessage() + RESET);
        }
    }

    private boolean printLines(List<String> lines) {
        try {
            if (batch) {
                DataViewTable.printPlainStatic(terminal, lines);
            } else {
                DataViewTable.printPlain(terminal, lines);
            }
        } catch (Exception e) {
            println(RED + "ERROR: " + e.getMessage() + RESET);
        }
        return true;
    }

    private void println(String s) {
        terminal.writer().println(s);
        terminal.writer().flush();
    }

    private void setStatus(String s) {
        if (statusBar != null) {
            statusBar.setStatus(s);
            statusBar.render();
        }
    }
}
