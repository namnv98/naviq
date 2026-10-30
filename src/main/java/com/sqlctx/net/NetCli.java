package com.sqlctx.net;

import com.sqlctx.context.ConnectionProfile;
import com.sqlctx.context.ConnectionProfileStore;
import com.sqlctx.kafka.KafkaProfiles;
import com.sqlctx.redis.RedisProfile;
import com.sqlctx.redis.RedisProfiles;
import com.sqlctx.sql.schema.Dialect;
import com.sqlctx.util.I18n;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code sqlctx --net}: xem traffic mạng theo thời gian thực, mỗi sự kiện 1 dòng dễ hiểu - kèm tên chương trình (eBPF)
 * và nội dung các giao thức sqlctx hiểu (câu SQL Postgres, lệnh Redis, API Kafka). Bắt gói bằng {@code tcpdump -w -}
 * (đọc pcap nhị phân, xem {@link Pcap}).
 * <pre>
 * sqlctx --net                                  mọi traffic trên mọi interface
 * sqlctx --net --filter="port 443"              bộ lọc kiểu tcpdump
 * sqlctx --net --context=dev                    chỉ traffic tới DB của context (SQL, Redis hay Kafka)
 * sqlctx --net --iface=wlan0 --pcap=cap.pcap    --pcap: đọc file đã bắt thay vì bắt trực tiếp
 * sqlctx --net --verbose                        từng gói SYN/SYN-ACK/ACK/FIN/RST (như phím v lúc đang chạy)
 * </pre>
 */
public final class NetCli {

    private static final Object PACKET_END = new Object();

    private NetCli() {
    }

    public static boolean handles(String[] args) {
        return Arrays.asList(args).contains("--net");
    }

    public static void run(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        String pcapFile = System.getProperty("DB_PCAP");
        String iface = System.getProperty("DB_IFACE", "any");
        Map<Integer, NetAnalyzer.Proto> hints = new HashMap<>();
        String filter = contextFilter(hints);
        String userFilter = System.getProperty("DB_FILTER");
        if (userFilter != null && !userFilter.isBlank()) {
            filter = filter == null ? userFilter : "(" + filter + ") and (" + userFilter + ")";
        }
        if (!onPath("tcpdump")) {
            System.err.println(NetFormat.red(I18n.t("net.noTcpdump")));
            System.exit(1);
        }

        List<String> cmd = new ArrayList<>(List.of("tcpdump", "-U", "-w", "-", "-s", "0"));
        if (pcapFile != null) {
            cmd.addAll(List.of("-r", pcapFile));
        } else {
            cmd.addAll(List.of("-i", iface));
            if (!isRoot()) {
                ensureSudo();
                cmd.add(0, "sudo");
            }
        }
        if (filter != null) {
            cmd.add(filter);
        }
        out.println(NetFormat.dim("$ " + String.join(" ", cmd.stream().map(a -> a.contains(" ") ? "'" + a + "'" : a).toList())));

        Process tcpdump = new ProcessBuilder(cmd).start();
        Thread errors = new Thread(() -> showErrors(tcpdump), "net-tcpdump-stderr");
        errors.setDaemon(true);
        errors.start();
        // sai interface/bộ lọc thì tcpdump chết ngay: dừng luôn, khỏi gắn eBPF rồi in bảng rỗng
        if (tcpdump.waitFor(500, TimeUnit.MILLISECONDS) && tcpdump.exitValue() != 0) {
            errors.join(1000);
            System.exit(1);
        }

        boolean live = pcapFile == null;
        // bật chế độ đọc phím trước khi gắn eBPF (mất vài giây): phím gõ lúc đó không bị in ra màn hình và không mất
        Keyboard keyboard = live ? Keyboard.open() : null;
        if (live) {
            out.println(NetFormat.dim(I18n.t("net.attaching")));
        }
        ProcessMap processes = new ProcessMap(live);
        if (live) {
            out.println(NetFormat.dim(I18n.t(processes.usingBpf() ? "net.namesFromBpf" : "net.namesFromSs")));
        }
        out.println(NetFormat.dim(I18n.t("net.legend")));
        if (keyboard != null) {
            out.println(NetFormat.dim(I18n.t("net.key.intro")));
        }
        out.println();

        int[] size = terminalSize();
        NetPrinter printer = new NetPrinter(out, size[0], size[1]);
        printer.verbose(Arrays.asList(args).contains("--verbose"));
        NetAnalyzer analyzer = new NetAnalyzer(printer, processes, hints);
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(200_000);
        Thread reader = new Thread(() -> {
            try {
                Pcap pcap = new Pcap(tcpdump.getInputStream());
                Pcap.Packet p;
                while ((p = pcap.next()) != null) {
                    queue.put(p);
                }
            } catch (IOException | InterruptedException ignored) {
            }
            queue.offer(PACKET_END);
        }, "net-pcap");
        reader.setDaemon(true);
        reader.start();

        AtomicBoolean finished = new AtomicBoolean();
        Runnable finish = () -> {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (keyboard != null) {
                keyboard.close();
            }
            tcpdump.destroy();
            processes.stop();
            analyzer.tick(true);
            printSummary(out, printer, analyzer);
        };
        // Ctrl+C: JVM chạy shutdown hook - dừng tcpdump/bpftrace rồi in tóm tắt
        Runtime.getRuntime().addShutdownHook(new Thread(finish, "net-shutdown"));

        if (keyboard != null) {
            keyboard.start(key -> {
                if (!printer.key(key)) {
                    System.exit(0); // q: thoát như Ctrl+C (shutdown hook in tóm tắt)
                }
            });
        }
        long lastTick = System.nanoTime();
        while (!finished.get()) {
            Object item = queue.poll(250, TimeUnit.MILLISECONDS);
            if (item == PACKET_END) {
                break;
            }
            if (item instanceof Pcap.Packet p) {
                analyzer.packet(p);
            }
            if (item == null || System.nanoTime() - lastTick > 250_000_000L) { // traffic dồn dập vẫn kiểm tra timeout đều
                analyzer.tick(false);
                lastTick = System.nanoTime();
            }
        }
        finish.run();
        int code = tcpdump.waitFor();
        System.exit(live && code != 0 && analyzer.ok == 0 ? 1 : 0);
    }

    /** --context=: lọc đúng host:port của context (SQL, Redis hoặc Kafka) và báo trước giao thức cho cổng đó. */
    private static String contextFilter(Map<Integer, NetAnalyzer.Proto> hints) {
        String name = System.getProperty("DB_CONTEXT");
        if (name == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        Optional<ConnectionProfile> sql = ConnectionProfileStore.find(name);
        Optional<RedisProfile> redis = RedisProfiles.find(name);
        Optional<KafkaProfiles.Profile> kafka = KafkaProfiles.find(name);
        if (sql.isPresent()) {
            ConnectionProfile p = sql.get();
            int port = Integer.parseInt(p.port());
            hints.put(port, p.dialect() == Dialect.ORACLE ? NetAnalyzer.Proto.ORACLE : NetAnalyzer.Proto.POSTGRES);
            parts.add(hostPort(p.host(), port));
        } else if (redis.isPresent()) {
            hints.put(redis.get().port(), NetAnalyzer.Proto.REDIS);
            parts.add(hostPort(redis.get().host(), redis.get().port()));
        } else if (kafka.isPresent()) {
            for (String server : kafka.get().bootstrap().split(",")) {
                String s = server.strip();
                int colon = s.lastIndexOf(':');
                int port = Integer.parseInt(s.substring(colon + 1));
                hints.put(port, NetAnalyzer.Proto.KAFKA);
                parts.add(hostPort(s.substring(0, colon), port));
            }
        } else {
            System.err.println(NetFormat.red(I18n.t("net.contextNotFound", name)));
            System.exit(2);
        }
        return String.join(" or ", parts);
    }

    private static String hostPort(String host, int port) {
        return "(tcp and host " + host.replace("[", "").replace("]", "") + " and port " + port + ")";
    }

    private static void printSummary(PrintStream out, NetPrinter printer, NetAnalyzer a) {
        printer.close();
        List<List<String>> open = a.openConnections();
        if (!open.isEmpty()) {
            out.println();
            NetPrinter.table(out, I18n.t("net.summary.open"), List.of(I18n.t("net.col.program"), I18n.t("net.col.target"), "↑", "↓"), open, 2, 3);
        }
        List<List<String>> rows = new ArrayList<>();
        rows.add(row(NetFormat.green("✓"), "net.summary.ok", a.ok, null));
        rows.add(row(NetFormat.red("✗"), "net.summary.refused", a.refused, "net.summary.refusedHint"));
        rows.add(row(NetFormat.red("✗"), "net.summary.noReply", a.noReply, "net.summary.noReplyHint"));
        rows.add(row(NetFormat.red("✗"), "net.summary.reset", a.resets, null));
        rows.add(row(NetFormat.yellow("⚠"), "net.summary.retrans", a.retransmits, "net.summary.retransHint"));
        rows.add(row(NetFormat.yellow("⚠"), "net.summary.zeroWindow", a.zeroWindows, null));
        rows.add(row(NetFormat.yellow("⚠"), "net.summary.nxdomain", a.nxdomain, null));
        if (a.appRequests > 0) {
            rows.add(List.of(NetFormat.blue("◆"), I18n.t("net.summary.requestsLabel"), String.valueOf(a.appRequests),
                    a.appErrors > 0 ? NetFormat.red(I18n.t("net.summary.requestErrors", a.appErrors)) : ""));
        }
        out.println();
        NetPrinter.table(out, I18n.t("net.summary.title"), List.of("", "", "", ""), rows, 2);
        out.flush();
    }

    private static List<String> row(String icon, String key, int n, String hintKey) {
        String count = n > 0 ? NetFormat.bold(String.valueOf(n)) : NetFormat.dim("0");
        String hint = n > 0 && hintKey != null ? NetFormat.red("← " + I18n.t(hintKey)) : "";
        return List.of(icon, I18n.t(key), count, hint);
    }

    /** Chỉ in dòng lỗi thật đầu tiên của tcpdump (sai bộ lọc, sai interface...), bỏ phần hướng dẫn dài phía sau. */
    private static void showErrors(Process tcpdump) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(tcpdump.getErrorStream(), StandardCharsets.UTF_8))) {
            boolean shown = false;
            String line;
            while ((line = r.readLine()) != null) {
                String l = line.strip();
                if (shown || l.isEmpty() || l.contains("WARNING") || l.toLowerCase().contains("promiscuous")
                        || l.contains("packets") || l.startsWith("tcpdump: listening") || l.startsWith("reading from")
                        || l.startsWith("tcpdump: verbose") || l.startsWith("tcpdump: data link")) {
                    continue;
                }
                shown = true;
                System.err.println(NetFormat.red(I18n.t("net.tcpdumpError", l.replaceFirst("^tcpdump: ", ""))));
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    /** Hỏi mật khẩu sudo 1 lần (dùng chung cho tcpdump, bpftrace, ss) nếu chưa có sẵn. */
    private static void ensureSudo() throws IOException, InterruptedException {
        if (new ProcessBuilder("sudo", "-n", "true").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0) {
            return;
        }
        if (System.console() != null) {
            new ProcessBuilder("sudo", "-v").inheritIO().start().waitFor();
        }
    }

    /** Lệnh cần root: thêm "sudo -n" (không hỏi mật khẩu - đã hỏi 1 lần ở đầu) nếu chưa phải root. */
    static List<String> sudo(String... command) {
        List<String> cmd = new ArrayList<>();
        if (!isRoot()) {
            cmd.add("sudo");
            cmd.add("-n");
        }
        cmd.addAll(Arrays.asList(command));
        return cmd;
    }

    static boolean onPath(String program) {
        String path = System.getenv("PATH");
        for (String dir : (path == null ? "/usr/bin:/usr/sbin:/bin:/sbin" : path + ":/usr/sbin:/sbin").split(":")) {
            if (new File(dir, program).canExecute()) {
                return true;
            }
        }
        return false;
    }

    /** Số cột, số dòng của terminal. */
    static int[] terminalSize() {
        int[] size = {120, 40};
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty size < /dev/tty").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.waitFor() == 0 && out.matches("\\d+ \\d+")) {
                size[0] = Integer.parseInt(out.split(" ")[1]);
                size[1] = Integer.parseInt(out.split(" ")[0]);
            }
        } catch (IOException | InterruptedException ignored) {
        }
        String columns = System.getenv("COLUMNS");
        if (columns != null && columns.matches("\\d+")) {
            size[0] = Integer.parseInt(columns);
        }
        return size;
    }
}
