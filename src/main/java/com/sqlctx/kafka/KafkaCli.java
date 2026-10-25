package com.sqlctx.kafka;

import com.sqlctx.util.I18n;

import com.sqlctx.cli.startup.StartupBanner;
import com.sqlctx.cli.view.DataViewTable;
import com.sqlctx.datasource.ConnectionProfileStore;
import com.sqlctx.shell.CompletionMenu;
import com.sqlctx.shell.Dashboard;
import com.sqlctx.shell.MenuItem;
import com.sqlctx.shell.ShellReader;
import com.sqlctx.shell.ShellStatusBar;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.Status;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Chế độ Kafka: REPL gõ lệnh kafka-*.sh (bỏ --bootstrap-server, kết nối lấy từ context), dùng chung popup gợi ý,
 * status bar, khung bảng với các chế độ khác. Vào chế độ này khi {@code --dialect=kafka}, hoặc --context= (hoặc
 * context lần trước) là 1 context trong {@code ~/.sqlctx/kafka.conf}.
 */
public final class KafkaCli {

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
    private KafkaSession session;
    private LineReaderImpl reader;
    private CompletionMenu menu;
    private String previousContext;
    private volatile boolean quit;
    /** Đang nhập message cho producer: tắt gợi ý và tô màu (dòng là dữ liệu). */
    private volatile boolean producing;

    private KafkaCli(Terminal terminal, KafkaSession session, boolean batch, boolean assumeYes) {
        this.terminal = terminal;
        this.session = session;
        this.batch = batch;
        this.assumeYes = assumeYes;
        this.statusBar = batch ? null : new ShellStatusBar(terminal, "kafka");
    }

    /** Sau khi SqlctxCli chép --x=y vào DB_X. Cùng tên với context SQL/Redis thì bên đó thắng, trừ khi --dialect=kafka. */
    public static boolean handles() {
        String dialect = System.getProperty("DB_DIALECT");
        if (dialect != null) {
            return dialect.equalsIgnoreCase("kafka");
        }
        String context = System.getProperty("DB_CONTEXT");
        if (context == null && System.getProperty("DB_HOST") == null) {
            context = ConnectionProfileStore.getLastContextName().orElse(null);
        }
        return context != null && KafkaProfiles.find(context).isPresent() && ConnectionProfileStore.find(context).isEmpty();
    }

    public static void run(String[] args) throws Exception {
        // log INFO của kafka-clients (mỗi lần tạo consumer vài chục dòng) chỉ làm đầy file log
        Logger.getLogger("org.apache.kafka").setLevel(Level.WARNING);
        boolean assumeYes = Arrays.asList(args).contains("--yes");
        KafkaProfiles.Profile profile;
        try {
            profile = resolveProfile();
        } catch (IllegalArgumentException e) {
            System.err.println(RED + "ERROR: " + e.getMessage() + RESET);
            System.exit(1);
            return;
        }
        KafkaSession session = new KafkaSession(profile);
        try {
            session.admin().describeCluster().clusterId().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println(RED + I18n.t("common.cannotConnect", "Kafka", profile.bootstrap(), KafkaCommands.message(e)) + RESET);
            session.close();
            System.exit(1);
            return;
        }
        if (!profile.name().contains(":")) {
            ConnectionProfileStore.setLastContextName(profile.name());
        }
        String command = System.getProperty("DB_COMMAND");
        String file = System.getProperty("DB_FILE");
        boolean batch = command != null || file != null;
        // cùng lý do với SqlctxCli: provider FFM làm JVM chết khi SIGINT rơi vào thread không phải Java
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
        KafkaCli cli = new KafkaCli(terminal, session, batch, assumeYes);
        if (batch) {
            List<String> commands = new ArrayList<>();
            if (command != null) {
                commands.addAll(lines(command));
            }
            if (file != null) {
                commands.addAll(lines(Files.readString(Paths.get(file))));
            }
            System.exit(cli.runBatch(commands));
        }
        cli.repl();
        System.exit(0);
    }

    /** Mỗi dòng 1 lệnh; '\' cuối dòng nối dòng sau; '#' đầu dòng là comment. Không tách theo ';' (sasl.jaas.config có ';'). */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\r?\n", -1)) {
            String l = cur.isEmpty() ? line.stripTrailing() : line.strip(); // dòng nối: bỏ phần thụt đầu dòng
            if (cur.isEmpty() && (l.isBlank() || l.stripLeading().startsWith("#"))) {
                continue;
            }
            if (l.endsWith("\\")) {
                cur.append(l.substring(0, l.length() - 1).stripTrailing()).append(' ');
                continue;
            }
            cur.append(l);
            out.add(cur.toString().strip());
            cur.setLength(0);
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString().strip());
        }
        return out;
    }

    /** --context= (đã lưu) và/hoặc --bootstrap-server= (hoặc --host= --port=), --schema-registry=, --readonly=true. */
    static KafkaProfiles.Profile resolveProfile() throws IOException {
        String context = System.getProperty("DB_CONTEXT");
        String bootstrap = Optional.ofNullable(System.getProperty("DB_BOOTSTRAP-SERVER"))
                .or(() -> Optional.ofNullable(System.getProperty("DB_BOOTSTRAP")))
                .orElse(System.getProperty("DB_HOST") == null ? null
                        : System.getProperty("DB_HOST") + ":" + System.getProperty("DB_PORT", "9092"));
        if (context == null && bootstrap == null) {
            context = ConnectionProfileStore.getLastContextName().orElse(null);
        }
        Optional<KafkaProfiles.Profile> saved = context == null ? Optional.empty() : KafkaProfiles.find(context);
        if (context != null && saved.isEmpty() && bootstrap == null) {
            throw new IllegalArgumentException(I18n.t("kafka.err.noContext", context, KafkaProfiles.file()));
        }
        Map<String, String> client = new LinkedHashMap<>(saved.map(KafkaProfiles.Profile::client).orElse(Map.of()));
        if (bootstrap != null) {
            client.put("bootstrap.servers", bootstrap);
        }
        if (!client.containsKey("bootstrap.servers")) {
            client.put("bootstrap.servers", "localhost:9092");
        }
        String sr = Optional.ofNullable(System.getProperty("DB_SCHEMA-REGISTRY"))
                .orElse(saved.map(KafkaProfiles.Profile::schemaRegistryUrl).orElse(null));
        boolean readonly = System.getProperty("DB_READONLY") != null ? Boolean.parseBoolean(System.getProperty("DB_READONLY"))
                : saved.map(KafkaProfiles.Profile::readonly).orElse(false);
        String name = context != null ? context : client.get("bootstrap.servers");
        KafkaProfiles.Profile profile = new KafkaProfiles.Profile(name, client, sr,
                saved.map(KafkaProfiles.Profile::schemaRegistryAuth).orElse(null), readonly);
        boolean overrides = bootstrap != null || System.getProperty("DB_SCHEMA-REGISTRY") != null
                || System.getProperty("DB_READONLY") != null;
        if (context != null && (saved.isEmpty() || overrides)) {
            KafkaProfiles.save(profile);
        }
        return profile;
    }

    // ===================================================================== REPL

    private void repl() throws IOException {
        Path history = ConnectionProfileStore.configDir().resolve("kafka_history.txt");
        Files.createDirectories(history.getParent());
        ShellReader.Shell shell = ShellReader.create(terminal, new ShellReader.Config("sqlctx-kafka", history,
                new KafkaHighlighter(() -> producing), true, this::updateHint, this::suggest, KafkaCli::helpText,
                () -> session.running, () -> session.cancel(), () -> quit = true, statusBar));
        reader = shell.reader();
        menu = shell.menu();
        menu.literalTab(() -> producing); // key<Tab>value như tool gốc (key.separator mặc định là tab)
        statusBar.setInfo(info(), session.profile.readonly());
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
            execute(String.join(" ", lines(line)));
            statusBar.setInfo(info(), session.profile.readonly());
            statusBar.render();
        }
    }

    private List<MenuItem> suggest(String line, int cursor) {
        return producing ? List.of() : KafkaCompletion.suggest(line, cursor, session);
    }

    private String prompt() {
        return CYAN + session.profile.name() + RESET + "> ";
    }

    private String info() {
        return session.profile.name() + " · " + session.profile.bootstrap();
    }

    private void updateHint(String buffer, int cursor) {
        if (producing) {
            statusBar.setHint(I18n.t("kafka.hint.producing"), false);
        } else if (buffer.isBlank()) {
            statusBar.setHint(null, false);
        } else if (buffer.stripLeading().startsWith("\\")) {
            String cmd = buffer.strip().split("\\s+")[0];
            statusBar.setHint(KafkaCompletion.META.stream().filter(m -> m[0].equals(cmd)).map(m -> m[0] + " - " + I18n.t(m[1]))
                    .findFirst().orElse("\\ctx \\brokers \\h \\i \\lang \\q"), false);
        } else {
            KafkaArgs.Parsed p = KafkaArgs.parse(buffer);
            boolean typingLast = !p.tokens().isEmpty() && p.tokens().get(p.tokens().size() - 1).end() == buffer.length();
            KafkaArgs.Problem problem = p.problems().stream()
                    .filter(x -> !(typingLast && x.token() == p.tokens().size() - 1)).findFirst().orElse(null);
            if (problem != null) {
                statusBar.setHint(problem.message(), true);
            } else if (p.tool() != null) {
                List<List<String>> missing = p.missing();
                String usage = KafkaTools.usage(p.tool(), p.mode());
                statusBar.setHint(missing.isEmpty() ? usage
                        : I18n.t("kafka.missing", String.join(I18n.t("kafka.or"), missing.get(0))) + "  ·  " + usage, false);
            } else {
                statusBar.setHint(null, false);
            }
        }
        statusBar.render();
    }

    private void printBanner() {
        StringBuilder sb = new StringBuilder();
        sb.append(TITLE).append(I18n.t("kafka.banner.title")).append(RESET).append("\n\n");
        try {
            DescribeClusterResult c = session.admin().describeCluster();
            Node controller = c.controller().get(5, TimeUnit.SECONDS);
            int brokers = c.nodes().get(5, TimeUnit.SECONDS).size();
            sb.append(StartupBanner.label("banner.connected")).append(GREEN).append(session.profile.bootstrap()).append(RESET)
                    .append(session.profile.readonly() ? YELLOW + I18n.t("common.readonlyBanner") + RESET : "").append('\n');
            sb.append(StartupBanner.label("banner.cluster")).append(I18n.t("banner.clusterValue", c.clusterId().get(5, TimeUnit.SECONDS),
                    brokers, controller == null ? "?" : controller.id())).append('\n');
            sb.append(StartupBanner.label("banner.topics")).append(I18n.t("banner.topicsValue",
                    session.topics().stream().filter(t -> !t.startsWith("__")).count())).append('\n');
        } catch (Exception e) {
            sb.append(RED).append(KafkaCommands.message(e)).append(RESET).append('\n');
        }
        SchemaRegistry sr = session.schemaRegistry(null);
        sb.append(StartupBanner.label("banner.schemaRegistry")).append(sr == null ? I18n.t("banner.noSchemaRegistry") : sr.url()).append('\n');
        sb.append(StartupBanner.label("banner.config")).append(KafkaProfiles.file()).append("\n\n");
        sb.append(DIM).append(I18n.t("kafka.banner.tip")).append(RESET).append('\n');
        reader.printAbove(sb.toString());
    }

    private static String helpText() {
        StringBuilder sb = new StringBuilder(TITLE + I18n.t("kafka.help.tools") + RESET + "\n");
        KafkaTools.TOOLS.values().forEach(t -> sb.append(String.format("  %-34s %s%n", t.name(), I18n.tr(t.help()))));
        sb.append('\n').append(TITLE).append(I18n.t("kafka.help.meta")).append(RESET).append('\n');
        KafkaCompletion.META.forEach(m -> sb.append(String.format("  %-34s %s%n", m[0], I18n.t(m[1]))));
        return sb.toString();
    }

    // ===================================================================== chạy lệnh

    private int runBatch(List<String> commands) {
        if (!assumeYes) {
            List<String> dangerous = commands.stream().filter(c -> {
                KafkaArgs.Parsed p = KafkaArgs.parse(c);
                return p.tool() != null && dangerous(p);
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

    /** Ghi/xoá - chặn ở context readonly. reset-offsets chỉ ghi khi có --execute (mặc định là xem trước). */
    private static boolean writes(KafkaArgs.Parsed p) {
        if (p.tool().name().equals("kafka-consumer-groups") && "--reset-offsets".equals(p.mode())) {
            return p.has("--execute");
        }
        if (p.tool().name().equals("kafka-streams-application-reset") && p.has("--dry-run")) {
            return false;
        }
        return p.tool().writes().contains("*") || p.mode() != null && p.tool().writes().contains(p.mode());
    }

    private static boolean dangerous(KafkaArgs.Parsed p) {
        if (p.tool().name().equals("kafka-consumer-groups") && "--reset-offsets".equals(p.mode())) {
            return p.has("--execute");
        }
        if (p.tool().name().equals("kafka-streams-application-reset") && p.has("--dry-run")) {
            return false;
        }
        if (p.tool().name().equals("kafka-acls") && p.has("--force")) {
            return false;
        }
        if (p.tool().name().equals("kafka-leader-election")) {
            return "unclean".equalsIgnoreCase(p.get("--election-type")); // preferred chỉ đưa leader về đúng chỗ
        }
        return p.tool().dangerous().contains("*") || p.mode() != null && p.tool().dangerous().contains(p.mode());
    }

    /** false nếu lỗi - batch dừng ở lỗi đầu tiên. */
    private boolean execute(String line) {
        if (line.startsWith("\\")) {
            return meta(line);
        }
        if (line.equals("exit") || line.equals("quit")) {
            quit = true;
            return true;
        }
        KafkaArgs.Parsed p = KafkaArgs.parse(line);
        if (!p.problems().isEmpty()) {
            println(RED + "(error) " + p.problems().get(0).message() + RESET);
            if (p.tool() != null) {
                println(DIM + KafkaTools.usage(p.tool(), p.mode()) + RESET);
            }
            return false;
        }
        if (p.pending() != null && p.pending().arity() == KafkaTools.Arity.VALUE) {
            println(RED + "(error) " + I18n.t("kafka.args.missingValue", p.pending().name()) + RESET);
            return false;
        }
        List<List<String>> missing = p.missing();
        if (!missing.isEmpty()) {
            println(RED + "(error) " + I18n.t("kafka.missing", String.join(I18n.t("kafka.or"), missing.get(0))) + RESET);
            println(DIM + KafkaTools.usage(p.tool(), p.mode()) + RESET);
            return false;
        }
        if (session.profile.readonly() && writes(p)) {
            println(RED + I18n.t("common.readonlyBlocked", session.profile.name(), p.tool().name()
                    + (p.mode() != null ? " " + p.mode() : "")) + RESET);
            return false;
        }
        if (dangerous(p) && !batch && !confirm(line)) {
            println(I18n.t("common.cancelled"));
            return true;
        }
        long start = System.nanoTime();
        session.cancelled = false;
        session.running = true;
        Thread main = Thread.currentThread();
        session.onCancel = main::interrupt; // lệnh Admin đang chờ future.get(): Ctrl+C làm nó thoát ra
        setStatus("RUNNING " + p.tool().name() + " · " + I18n.t("common.ctrlcToStop"));
        boolean ok = true;
        try {
            String tool = p.tool().name();
            KafkaConsole console = new KafkaConsole(session, out(), this::println);
            if (tool.endsWith("-console-consumer")) {
                console.consume(p, session.profile.readonly());
            } else if (tool.endsWith("-console-producer")) {
                console.produce(p, messageLines());
            } else {
                new KafkaCommands(session, out()).run(p);
            }
        } catch (InterruptedException e) {
            println(YELLOW + I18n.t("kafka.stoppedPlain") + RESET);
        } catch (Exception e) {
            if (session.cancelled) {
                println(YELLOW + I18n.t("kafka.stoppedPlain") + RESET);
            } else {
                println(RED + "(error) " + KafkaCommands.message(e) + RESET);
                ok = false;
            }
        } finally {
            session.running = false;
            session.onCancel = null;
            Thread.interrupted(); // Ctrl+C tới đúng lúc lệnh vừa xong: không để cờ interrupt rơi sang lệnh sau
            producing = false;
        }
        if (!batch) {
            println(DIM + String.format("(%.3fs)", (System.nanoTime() - start) / 1e9) + RESET);
        }
        setStatus(ok ? "idle" : "ERROR " + p.tool().name());
        return ok;
    }

    /**
     * Nguồn dòng message cho producer: REPL thì đọc từng dòng với prompt riêng (Ctrl+D / Ctrl+C để dừng), batch thì
     * đọc stdin như tool gốc (vd {@code sqlctx --command='kafka-console-producer --topic t' < messages.txt}).
     */
    private Supplier<String> messageLines() {
        if (batch) {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return () -> {
                try {
                    return in.readLine();
                } catch (IOException e) {
                    return null;
                }
            };
        }
        producing = true;
        println(DIM + I18n.t("kafka.producing") + RESET);
        return () -> {
            session.running = false; // đang chờ gõ: Ctrl+C là UserInterruptException của readLine
            reader.setVariable(LineReader.DISABLE_HISTORY, true); // message không lẫn vào lịch sử lệnh (↑)
            try {
                return reader.readLine(DIM + "> " + RESET);
            } catch (UserInterruptException | EndOfFileException e) {
                return null;
            } finally {
                reader.setVariable(LineReader.DISABLE_HISTORY, false);
                session.running = true;
            }
        };
    }

    private KafkaCommands.Out out() {
        return new KafkaCommands.Out() {
            @Override
            public void table(List<String> headers, List<List<String>> rows) {
                try {
                    if (rows.isEmpty()) {
                        println(DIM + I18n.t("common.empty") + RESET);
                    } else if (batch) {
                        DataViewTable.printStatic(terminal, headers, rows);
                    } else {
                        DataViewTable.print(terminal, headers, rows, statusBar::redraw);
                    }
                } catch (Exception e) {
                    println(RED + "ERROR: " + e.getMessage() + RESET);
                }
            }

            @Override
            public void lines(List<String> lines) {
                try {
                    if (lines.isEmpty()) {
                        println(DIM + I18n.t("common.empty") + RESET);
                    } else if (batch) {
                        DataViewTable.printPlainStatic(terminal, lines);
                    } else {
                        DataViewTable.printPlain(terminal, lines);
                    }
                } catch (Exception e) {
                    println(RED + "ERROR: " + e.getMessage() + RESET);
                }
            }

            @Override
            public void info(String line) {
                println(line.startsWith("(") ? DIM + line + RESET : line);
            }
        };
    }

    private boolean confirm(String line) {
        if (assumeYes) {
            return true;
        }
        try {
            terminal.writer().print(YELLOW + I18n.t("kafka.confirm", session.profile.bootstrap()) + RESET
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

    /**
     * Dashboard toàn màn hình (chu kỳ {@code arg} giây, mặc định 5) tới khi q/Esc/Ctrl+C. Batch hoặc terminal không
     * vẽ được toàn màn hình: lấy mẫu 2 lần cách 1 giây để có tốc độ rồi in 1 khung.
     */
    private boolean stats(String arg) {
        long seconds;
        try {
            seconds = arg == null ? 5 : Math.max(1, Long.parseLong(arg));
        } catch (NumberFormatException e) {
            println(RED + I18n.t("common.usage", "\\stats [SECONDS]") + RESET);
            return false;
        }
        String name = session.profile.name();
        try {
            if (batch || !Dashboard.supported(terminal)) {
                KafkaStats.Sample first = KafkaStats.sample(session.admin());
                Thread.sleep(1000);
                KafkaStats.Sample second = KafkaStats.sample(session.admin());
                DataViewTable.printPlainStatic(terminal, KafkaStats.frame(KafkaStats.title(name, second, seconds, false),
                        first, second, terminal.getWidth()));
                return true;
            }
            if (Dashboard.show(terminal, new KafkaDashboard(session::admin, name), seconds * 1000)) {
                terminal.writer().print("\u001b[H\u001b[2J");
                statusBar.redraw();
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Exception e) {
            println(RED + "(error) " + KafkaCommands.message(e) + RESET);
            return false;
        }
    }

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
                if (arg == null) {
                    out().lines(helpText().lines().toList());
                    return true;
                }
                return help(arg);
            }
            case "\\ctx", "\\context" -> {
                return context(arg, parts.length > 2 ? parts[2] : null);
            }
            case "\\stats" -> {
                return stats(arg);
            }
            case "\\brokers" -> {
                try {
                    DescribeClusterResult c = session.admin().describeCluster();
                    Node controller = c.controller().get();
                    List<List<String>> rows = new ArrayList<>();
                    for (Node n : c.nodes().get()) {
                        rows.add(List.of(String.valueOf(n.id()), n.host(), String.valueOf(n.port()), n.rack() == null ? "" : n.rack(),
                                controller != null && controller.id() == n.id() ? "controller" : ""));
                    }
                    out().table(List.of("id", "host", "port", "rack", ""), rows);
                    println(DIM + "Cluster ID: " + c.clusterId().get() + RESET);
                } catch (Exception e) {
                    println(RED + "(error) " + KafkaCommands.message(e) + RESET);
                    return false;
                }
            }
            case "\\i" -> {
                if (arg == null) {
                    println(RED + I18n.t("common.usage", "\\i FILE") + RESET);
                    return false;
                }
                try {
                    for (String c : lines(Files.readString(Paths.get(arg)))) {
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

    /** \h TOOL: chế độ và tuỳ chọn (chế độ nào dùng được, mô tả). */
    private boolean help(String name) {
        KafkaTools.Tool tool = KafkaTools.tool(name);
        if (tool == null) {
            println(RED + I18n.t("kafka.noTool", name) + RESET);
            return false;
        }
        println(TITLE + tool.name() + RESET + " - " + I18n.tr(tool.help()));
        List<List<String>> rows = new ArrayList<>();
        for (String m : tool.modes()) {
            rows.add(List.of(m, "", I18n.t("kafka.help.mode"), I18n.tr(tool.modeHelp().get(m))));
        }
        for (KafkaTools.Opt o : tool.options()) {
            rows.add(List.of(o.name(), o.arity() == KafkaTools.Arity.FLAG ? "" : "<" + o.meta() + ">",
                    o.modes().isEmpty() ? "" : String.join(" ", o.modes()), I18n.tr(o.help())));
        }
        out().table(List.of(I18n.t("kafka.help.col.option"), I18n.t("kafka.help.col.value"), I18n.t("kafka.help.col.only"),
                I18n.t("kafka.help.col.desc")), rows);
        return true;
    }

    private boolean context(String arg, String extra) {
        List<KafkaProfiles.Profile> all = KafkaProfiles.loadAll();
        if (arg == null) {
            if (all.isEmpty()) {
                println(DIM + I18n.t("common.noContexts", KafkaProfiles.file()) + RESET);
                return true;
            }
            List<List<String>> rows = new ArrayList<>();
            for (KafkaProfiles.Profile x : all) {
                rows.add(List.of(x.name().equalsIgnoreCase(session.profile.name()) ? "*" : "", x.name(), x.bootstrap(),
                        x.schemaRegistryUrl() == null ? "" : x.schemaRegistryUrl(), x.readonly() ? "readonly" : ""));
            }
            out().table(List.of("", "context", "bootstrap", "schema registry", ""), rows);
            return true;
        }
        if (arg.equals("save")) {
            if (extra == null) {
                println(RED + I18n.t("common.usage", "\\ctx save NAME") + RESET);
                return false;
            }
            KafkaProfiles.Profile x = session.profile;
            try {
                KafkaProfiles.save(new KafkaProfiles.Profile(extra, x.client(), x.schemaRegistryUrl(), x.schemaRegistryAuth(), x.readonly()));
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
        Optional<KafkaProfiles.Profile> profile = KafkaProfiles.find(target);
        if (profile.isEmpty()) {
            println(RED + I18n.t("kafka.ctx.notFound", target) + RESET);
            return false;
        }
        KafkaSession next = new KafkaSession(profile.get());
        try {
            next.admin().describeCluster().clusterId().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            next.close();
            println(RED + I18n.t("common.cannotConnect", "Kafka", profile.get().bootstrap(), KafkaCommands.message(e)) + RESET);
            return false;
        }
        previousContext = session.profile.name();
        session.close();
        session = next;
        ConnectionProfileStore.setLastContextName(profile.get().name());
        println(GREEN + I18n.t("common.switchedTo", profile.get().name(), profile.get().bootstrap()) + RESET);
        return true;
    }

    // ===================================================================== tiện ích

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
