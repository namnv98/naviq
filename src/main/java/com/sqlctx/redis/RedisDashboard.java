package com.sqlctx.redis;

import com.sqlctx.ui.Dashboard;
import com.sqlctx.ui.Screen;
import com.sqlctx.ui.Screen.Column;
import com.sqlctx.util.I18n;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * \stats toàn màn hình của chế độ Redis: ô tổng quan (số node, cluster_state, tổng ops/s có sparkline, thanh bộ nhớ
 * của các node dùng nhiều nhất), bảng node, ô cảnh báo. Enter: chi tiết node - server, bộ nhớ, persistence,
 * replication, keyspace, các lệnh tốn thời gian nhất (INFO commandstats, chỉ lấy khi đang xem chi tiết).
 */
final class RedisDashboard implements Dashboard.View {

    static final int HISTORY = 120;
    private static final int NODES = 0;
    private static final int WARNINGS = 1;

    private final RedisConnection connection;
    private final RedisProfile profile;
    private final Deque<RedisStats.Sample> history = new ArrayDeque<>();
    private volatile Detail detail;

    /**
     * Số liệu chỉ lấy khi đang xem chi tiết 1 node: INFO commandstats lần này và lần trước (cùng node - để tính lệnh tốn
     * thời gian trong khoảng giữa 2 lần thay vì từ lúc khởi động), LATENCY LATEST.
     */
    record Detail(String address, long at, Map<String, String> commands, long beforeAt, Map<String, String> before, Object latency) {
    }

    RedisDashboard(RedisConnection connection, RedisProfile profile) {
        this.connection = connection;
        this.profile = profile;
    }

    @Override
    public void sample(Dashboard.State state) throws IOException {
        RedisStats.Sample s = RedisStats.sample(connection);
        synchronized (history) {
            history.addLast(s);
            while (history.size() > HISTORY) {
                history.removeFirst();
            }
        }
        if (state.detail && !s.nodes().isEmpty()) {
            RedisStats.Node n = s.nodes().get(Math.min(state.selected[0], s.nodes().size() - 1));
            Object reply = callOn(n.address(), "INFO", "commandstats");
            Map<String, String> commands = reply instanceof RespClient.Bulk b ? RedisStats.parseInfo(b.utf8()) : Map.of();
            Detail last = detail;
            boolean same = last != null && last.address().equals(n.address()) && !last.commands().isEmpty();
            detail = new Detail(n.address(), s.at(), commands, same ? last.at() : 0, same ? last.commands() : null,
                    callOn(n.address(), "LATENCY", "LATEST"));
        }
    }

    private Object callOn(String address, String... args) throws IOException {
        return connection.cluster() ? connection.callOn(address, args)
                : address.equals(connection.address()) ? connection.call(args) : direct(address, args);
    }

    /** Replica của server đơn/sentinel: không nằm trong RedisConnection, nối riêng 1 lần. */
    private Object direct(String address, String... args) {
        int colon = address.lastIndexOf(':');
        try (RespClient c = RespClient.connect(connection.profile().at(address.substring(0, colon),
                Integer.parseInt(address.substring(colon + 1))), 2000)) {
            return c.call(args);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    void add(RedisStats.Sample s) {
        synchronized (history) {
            history.addLast(s);
        }
    }

    @Override
    public int panels() {
        return 2;
    }

    @Override
    public boolean hasDetail(int panel) {
        return panel == NODES;
    }

    @Override
    public int rows(int panel) {
        synchronized (history) {
            if (history.isEmpty()) {
                return 0;
            }
            return panel == NODES ? history.peekLast().nodes().size() : RedisStats.warnings(new ArrayList<>(history)).size();
        }
    }

    private List<RedisStats.Sample> snapshot() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    private static double num(Map<String, String> info, String key) {
        try {
            return Double.parseDouble(info.getOrDefault(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** ops/s của 1 node qua các mẫu (theo instantaneous_ops_per_sec của server). */
    static List<Double> opsSeries(List<RedisStats.Sample> h, String address) {
        List<Double> out = new ArrayList<>();
        for (RedisStats.Sample s : h) {
            double v = 0;
            for (RedisStats.Node n : s.nodes()) {
                if (address == null || n.address().equals(address)) {
                    v += num(n.info(), "instantaneous_ops_per_sec");
                }
            }
            out.add(v);
        }
        return out;
    }

    @Override
    public List<String> render(int width, int height, Dashboard.State state) {
        List<RedisStats.Sample> h = snapshot();
        List<String> out = new ArrayList<>();
        String right = Dashboard.status(state, I18n.t("stats.every", state.intervalMs / 1000), I18n.t("stats.paused"));
        RedisStats.Sample cur = h.isEmpty() ? null : h.get(h.size() - 1);
        String left = profile.name();
        if (cur != null) {
            String mode = cur.clusterState() != null ? I18n.t("redis.stats.modeCluster", cur.nodes().size() + cur.failed().size())
                    : profile.sentinelMaster() != null ? "sentinel " + profile.sentinelMaster() : "standalone";
            left += Screen.DIM + " · " + Screen.RESET + mode
                    + (cur.clusterState() == null ? "" : " " + ("ok".equals(cur.clusterState()) ? Screen.GREEN : Screen.RED) + "● "
                    + cur.clusterState() + Screen.RESET)
                    + cur.nodes().stream().filter(n -> n.error() == null).findFirst()
                    .map(n -> Screen.DIM + " · Redis " + n.info().getOrDefault("redis_version", "?") + Screen.RESET).orElse("");
        }
        out.add(Dashboard.titleBar("REDIS", "203", left, right, width));
        if (cur == null) {
            out.add(Screen.DIM + " " + I18n.t("stats.loading") + Screen.RESET);
            return out;
        }

        List<RedisStats.Node> live = cur.nodes().stream().filter(n -> n.error() == null).toList();
        List<Double> ops = opsSeries(h, null);
        double used = live.stream().mapToDouble(n -> num(n.info(), "used_memory")).sum();
        double max = live.stream().mapToDouble(n -> num(n.info(), "maxmemory")).sum();
        boolean allLimited = !live.isEmpty() && live.stream().allMatch(n -> num(n.info(), "maxmemory") > 0);
        RedisTrend trend = new RedisTrend(h);
        Double hit = trend.hitRate(null, RedisTrend.WINDOW_MS);
        RedisTrend.Delta hits = trend.total("keyspace_hits", RedisTrend.WINDOW_MS);
        RedisTrend.Delta misses = trend.total("keyspace_misses", RedisTrend.WINDOW_MS);
        RedisTrend.Delta evicted = trend.total("evicted_keys", RedisTrend.WINDOW_MS);
        RedisTrend.Delta expired = trend.total("expired_keys", RedisTrend.WINDOW_MS);
        long keys = cur.nodes().stream().filter(RedisStats.Node::master).mapToLong(n -> RedisStats.keys(n.info())).sum();
        long clients = live.stream().mapToLong(n -> (long) num(n.info(), "connected_clients")).sum();
        long blocked = live.stream().mapToLong(n -> (long) num(n.info(), "blocked_clients")).sum();
        long masters = cur.nodes().stream().filter(RedisStats.Node::master).count();
        List<Screen.Tile> tiles = new ArrayList<>(List.of(
                new Screen.Tile("ops/s", Screen.GREEN + Screen.compact(ops.isEmpty() ? 0 : ops.get(ops.size() - 1)),
                        I18n.t("stats.peak", Screen.compact(ops.stream().mapToDouble(Double::doubleValue).max().orElse(0))),
                        ops, Screen.GREEN),
                new Screen.Tile(I18n.t("stats.redis.memory"), (allLimited ? Screen.level(used / max, 0.8, 0.9) : "\u001b[38;5;252m")
                        + bytes(used), allLimited ? Math.round(100 * used / max) + "% maxmemory" : Screen.YELLOW + I18n.t("stats.redis.noLimit"),
                        memorySeries(h), "\u001b[38;5;75m"),
                new Screen.Tile("Clients", "\u001b[38;5;252m" + clients, (blocked > 0 ? Screen.YELLOW : "") + "blocked " + blocked),
                new Screen.Tile("Hit", hit == null ? Screen.DIM + "…" : hit.isNaN() ? Screen.DIM + "-"
                        : Screen.level(100 - hit, 20, 50) + String.format("%.1f%%", hit),
                        hits == null || misses == null ? "-" : perSecond(hits) + " hit/s · " + perSecond(misses) + " miss/s"),
                new Screen.Tile(I18n.t("stats.redis.keys"), "\u001b[38;5;252m" + Screen.compact(keys),
                        evicted == null ? "-" : (evicted.value() > 0 ? Screen.YELLOW : "") + "evicted " + perSecond(evicted) + "/s"
                                + Screen.DIM + " · expired " + (expired == null ? "-" : perSecond(expired)) + "/s")));
        if (width < 90) {
            tiles.remove(4);
        }
        out.addAll(Screen.tiles(tiles, width));

        List<String> warnings = RedisStats.warnings(h);
        int warnLines = Dashboard.warningLines(warnings.size(), height);
        int avail = height - out.size() - (warnLines + 2) - 1;
        int chart = Dashboard.chartHeight(avail, state.detail ? avail : cur.nodes().size() + 3);
        if (chart > 0) {
            int top = chart >= Dashboard.CHART_ROWS_AT ? chart / 2 : chart;
            double avg = ops.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            out.addAll(Screen.box("ops/s  " + Screen.GREEN + "\u001b[1m" + Screen.compact(ops.isEmpty() ? 0 : ops.get(ops.size() - 1))
                    + Screen.RESET + Screen.DIM + " · " + I18n.t("stats.avg") + " " + Screen.compact(Math.round(avg))
                    + " · " + I18n.t("stats.redis.nodes", cur.nodes().size(), masters, cur.nodes().size() - masters) + Screen.RESET,
                    Screen.area(ops, width - 4, top - 2, Screen.GREEN_RAMP), width, top, false));
            if (top < chart) {
                int half = width / 2;
                List<Double> clientHistory = h.stream().map(s -> s.nodes().stream().filter(n -> n.error() == null)
                        .mapToDouble(n -> num(n.info(), "connected_clients")).sum()).toList();
                List<String> m = Screen.box(I18n.t("stats.redis.memory") + "  \u001b[38;5;75m\u001b[1m" + bytes(used) + Screen.RESET,
                        Screen.area(memorySeries(h), half - 4, chart - top - 2, Screen.BLUE_RAMP, RedisDashboard::bytes), half, chart - top, false);
                List<String> c = Screen.box("Clients  \u001b[38;5;141m\u001b[1m" + clients + Screen.RESET,
                        Screen.area(clientHistory, width - half - 4, chart - top - 2, Screen.PURPLE_RAMP), width - half, chart - top, false);
                out.addAll(Screen.beside(m, half, c));
            }
        }
        int panelHeight = Math.max(4, avail - chart);
        if (state.detail) {
            out.addAll(detail(cur, trend, state, width, panelHeight));
        } else {
            List<List<String>> rows = new ArrayList<>();
            for (RedisStats.Node n : cur.nodes()) {
                Map<String, String> i = n.info();
                String role = n.master() ? Screen.FOCUS + "master" + Screen.RESET + (n.replicas() >= 0 ? Screen.DIM + " +" + n.replicas() : "")
                        : Screen.DIM + "replica";
                if (n.error() != null) {
                    rows.add(List.of(n.address(), role, "", Screen.RED + "✖ down", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-"));
                    continue;
                }
                double nodeMax = num(i, "maxmemory");
                // thanh chỉ có nghĩa khi có maxmemory; không đặt thì so với RAM máy gần như luôn rỗng
                String mem = (nodeMax > 0 ? Screen.bar(num(i, "used_memory") / nodeMax, 6, 0.8, 0.9) + " " : "")
                        + i.getOrDefault("used_memory_human", "-");
                Double cpu = trend.cpu(n.address());
                RedisTrend.Delta nodeEvicted = trend.delta(n.address(), "evicted_keys", RedisTrend.WINDOW_MS);
                rows.add(List.of(n.address(), role, Screen.spark(opsSeries(h, n.address()), 10),
                        Screen.compact(num(i, "instantaneous_ops_per_sec")),
                        cpu == null ? "…" : (cpu >= 80 ? Screen.YELLOW : "") + Math.round(cpu) + "%", mem,
                        (num(i, "mem_fragmentation_ratio") > 1.5 && num(i, "used_memory") > 100L * 1024 * 1024 ? Screen.YELLOW : "")
                                + i.getOrDefault("mem_fragmentation_ratio", "-"),
                        i.getOrDefault("connected_clients", "-"), RedisStats.hitText(trend.hitRate(n.address(), RedisTrend.WINDOW_MS), i),
                        Screen.compact(RedisStats.keys(i)),
                        nodeEvicted == null ? "…" : (nodeEvicted.value() > 0 ? Screen.YELLOW : "") + perSecond(nodeEvicted),
                        kbps(num(i, "instantaneous_input_kbps")) + "/" + kbps(num(i, "instantaneous_output_kbps")),
                        replLag(cur, n), clockText(trend, n.address())));
            }
            List<Column> cols = new ArrayList<>(List.of(new Column("node", 'l', 10),
                    new Column(I18n.t("redis.stats.role"), 'l', 7), new Column(I18n.t("stats.trend"), 'l', 10),
                    new Column("ops/s", 'r', 5), new Column("cpu", 'r', 4), new Column(I18n.t("stats.redis.memory"), 'l', 12),
                    new Column("frag", 'r', 4), new Column("clients", 'r', 7), new Column("hit %", 'r', 5), new Column("keys", 'r', 4),
                    new Column("evicted/s", 'r', 9), new Column("net kB/s", 'r', 8), new Column("repl lag", 'r', 8),
                    new Column(I18n.t("stats.redis.clock"), 'r', 7)));
            // màn hẹp: bỏ cột ít cần để địa chỉ node không bị co mất chữ
            List<String> drop = width < 110 ? List.of(I18n.t("stats.trend"), "cpu", "frag", "evicted/s", "net kB/s", I18n.t("stats.redis.clock"))
                    : width < 150 ? List.of("net kB/s", I18n.t("stats.redis.clock")) : List.of();
            for (int c = cols.size() - 1; c >= 0; c--) {
                if (drop.contains(cols.get(c).header())) {
                    cols.remove(c);
                    for (int r = 0; r < rows.size(); r++) {
                        List<String> row = new ArrayList<>(rows.get(r));
                        row.remove(c);
                        rows.set(r, row);
                    }
                }
            }
            out.addAll(Screen.box("Node " + Screen.DIM + rows.size(), Screen.table(cols, rows, width - 4, panelHeight - 2,
                    state.selected[NODES], state.panel == NODES), width, panelHeight, state.panel == NODES));
        }
        out.addAll(Dashboard.warningsBox(warnings, warnLines, I18n.t("redis.stats.ok"), width, state.selected[WARNINGS],
                state.panel == WARNINGS));
        out.add(Dashboard.footer(I18n.t(state.detail ? "stats.keysDetail" : "stats.keys"), width));
        return out.subList(0, Math.min(out.size(), height));
    }

    private static String perSecond(RedisTrend.Delta d) {
        return Screen.compact(Math.round(d.perSecond() * 10) / 10.0);
    }

    private static String kbps(double v) {
        return Screen.compact(Math.round(v * 10) / 10.0);
    }

    /** Đồng hồ node lệch bao nhiêu so với máy này: mờ khi khớp, vàng quá 2s, đỏ khi vừa nhảy; "-" nếu Redis &lt; 7. */
    private static String clockText(RedisTrend trend, String address) {
        Long jump = trend.clockJumpMs(address, RedisTrend.WINDOW_MS);
        Long skew = trend.skewMs(address);
        if (jump != null) {
            return Screen.RED + "↯ " + RedisStats.signed(jump);
        }
        if (skew == null) {
            return Screen.DIM + "-";
        }
        return (Math.abs(skew) >= RedisTrend.SKEW_MS ? Screen.YELLOW : Screen.DIM) + RedisStats.signed(skew);
    }

    /** Replica chậm hơn master bao nhiêu byte (vàng từ 1 MB), kèm số giây chưa ack nếu đáng kể; master: "-". */
    private static String replLag(RedisStats.Sample cur, RedisStats.Node n) {
        if (n.master()) {
            return Screen.DIM + "-";
        }
        if ("down".equals(n.info().get("master_link_status"))) {
            return Screen.RED + "✖ link";
        }
        RedisStats.ReplLag lag = RedisStats.replicaLag(cur, n);
        if (lag == null) {
            return Screen.DIM + "?";
        }
        String text = (lag.bytes() >= RedisStats.REPLICA_BEHIND_BYTES ? Screen.YELLOW : "") + RedisStats.bytes(lag.bytes());
        return lag.ackSeconds() >= 2 ? text + (lag.ackSeconds() >= RedisStats.REPLICA_ACK_S ? Screen.YELLOW : Screen.DIM)
                + " " + lag.ackSeconds() + "s" : text;
    }

    /** Tổng used_memory của các node sống qua các mẫu. */
    private static List<Double> memorySeries(List<RedisStats.Sample> h) {
        return h.stream().map(s -> s.nodes().stream().filter(n -> n.error() == null)
                .mapToDouble(n -> num(n.info(), "used_memory")).sum()).toList();
    }

    private static String bytes(double b) {
        return RedisStats.bytes(b);
    }

    private List<String> detail(RedisStats.Sample cur, RedisTrend trend, Dashboard.State state, int width, int height) {
        if (cur.nodes().isEmpty()) {
            return Screen.box("", List.of(), width, height, true);
        }
        RedisStats.Node n = cur.nodes().get(Math.min(state.selected[0], cur.nodes().size() - 1));
        Map<String, String> i = n.info();
        List<String> left = new ArrayList<>();
        if (n.error() != null) {
            left.add(Screen.RED + n.error() + Screen.RESET);
        } else {
            section(left, "Server", i, "redis_version", "redis_mode", "tcp_port", "process_id");
            left.add("  " + Screen.DIM + String.format("%-28s", "uptime") + Screen.RESET
                    + Screen.duration((long) num(i, "uptime_in_seconds") * 1000));
            if (i.containsKey("server_time_usec")) {
                Long skew = trend.skewMs(n.address());
                String time = java.time.Instant.ofEpochMilli((long) num(i, "server_time_usec") / 1000)
                        .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                left.add("  " + Screen.DIM + String.format("%-28s", I18n.t("stats.redis.serverTime")) + Screen.RESET + time
                        + (skew == null ? "" : Screen.DIM + " (" + I18n.t("stats.redis.skew") + " " + Screen.RESET
                        + (Math.abs(skew) >= RedisTrend.SKEW_MS ? Screen.YELLOW : "") + RedisStats.signed(skew) + Screen.RESET
                        + Screen.DIM + " " + I18n.t("stats.redis.vsLocal") + ")" + Screen.RESET));
                left.add("  " + Screen.DIM + " ".repeat(28) + I18n.t("stats.redis.clockNote") + Screen.RESET);
                left.add("  " + Screen.DIM + " ".repeat(28) + I18n.t("stats.redis.clockNote2") + Screen.RESET);
            }
            section(left, I18n.t("stats.redis.memory"), i, "used_memory_human", "used_memory_peak_human", "used_memory_rss_human",
                    "mem_fragmentation_ratio", "maxmemory_human", "maxmemory_policy");
            section(left, "Clients", i, "connected_clients", "blocked_clients", "maxclients", "rejected_connections");
            section(left, "Persistence", i, "rdb_last_bgsave_status", "rdb_changes_since_last_save", "rdb_last_save_time",
                    "aof_enabled", "aof_last_write_status");
            section(left, "Replication", i, "role", "connected_slaves", "master_host", "master_port", "master_link_status",
                    "master_repl_offset");
            List<String> dbs = i.keySet().stream().filter(k -> k.matches("db\\d+")).sorted().toList();
            if (!dbs.isEmpty()) {
                left.add(Screen.HEADER + "Keyspace" + Screen.RESET);
                dbs.forEach(db -> left.add("  " + db + "  " + i.get(db)));
            }
        }
        List<String> right = new ArrayList<>();
        int rightWidth = width >= 100 ? width - width / 2 - 4 : width - 4;
        Detail d = detail;
        if (d != null && n.address().equals(d.address()) && !d.commands().isEmpty()) {
            right.addAll(commands(d, rightWidth, Math.max(3, (height - 8) / 2)));
        } else {
            right.add(Screen.HEADER + I18n.t("stats.redis.topCommands") + Screen.RESET);
            right.add(Screen.DIM + I18n.t("stats.loading") + Screen.RESET);
        }
        right.add("");
        right.add(Screen.HEADER + I18n.t("stats.redis.slowlog") + Screen.RESET);
        if (n.slowlog().isEmpty()) {
            right.add(Screen.DIM + "  " + I18n.t("stats.redis.slowlogEmpty") + Screen.RESET);
        }
        java.time.format.DateTimeFormatter clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
        for (RedisStats.Slow slow : n.slowlog()) {
            String time = java.time.Instant.ofEpochSecond(slow.time()).atZone(java.time.ZoneId.systemDefault()).format(clock);
            right.add("  " + Screen.DIM + time + Screen.RESET + "  " + (slow.micros() >= 100_000 ? Screen.YELLOW : "")
                    + String.format("%8s", RedisStats.millis(slow.micros())) + Screen.RESET + "  " + slow.command());
        }
        if (d != null && n.address().equals(d.address())) {
            right.add("");
            right.addAll(latency(d.latency()));
        }
        String title = n.address() + " · " + (n.master() ? "master" : "replica");
        if (width >= 100) {
            int half = width / 2;
            return Screen.box(title, Screen.beside(fitAll(left, half - 4), half - 4, fitAll(right, width - half - 4)),
                    width, height, true);
        }
        List<String> all = new ArrayList<>(left);
        all.add("");
        all.addAll(right);
        return Screen.box(title, all, width, height, true);
    }

    /** calls và usec của mỗi lệnh trong INFO commandstats ("cmdstat_get:calls=10,usec=52,usec_per_call=5.20,..."). */
    static Map<String, double[]> commandTotals(Map<String, String> stats) {
        Map<String, double[]> out = new java.util.TreeMap<>();
        stats.forEach((k, v) -> {
            if (k.startsWith("cmdstat_")) {
                double[] cu = new double[2];
                for (String part : v.split(",")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2 && (kv[0].equals("calls") || kv[0].equals("usec"))) {
                        cu[kv[0].equals("calls") ? 0 : 1] = Double.parseDouble(kv[1]);
                    }
                }
                out.put(k.substring(8).replace('|', ' '), cu);
            }
        });
        return out;
    }

    /**
     * Lệnh tốn thời gian nhất: có lần trước thì tính trong khoảng giữa 2 lần (calls/s, µs mỗi lệnh, ms server bận mỗi
     * giây) - cho biết lệnh nào đang làm server bận lúc này; chưa có thì từ lúc server khởi động.
     */
    private static List<String> commands(Detail d, int width, int rows) {
        record Cmd(String name, double calls, double usec) {
        }
        Map<String, double[]> now = commandTotals(d.commands());
        Map<String, double[]> before = d.before() == null ? null : commandTotals(d.before());
        double seconds = before == null ? 0 : Math.max(0.001, (d.at() - d.beforeAt()) / 1000.0);
        List<Cmd> cmds = new ArrayList<>();
        now.forEach((name, cu) -> {
            double[] b = before == null ? null : before.getOrDefault(name, new double[2]);
            double calls = b == null ? cu[0] : cu[0] - b[0];
            double usec = b == null ? cu[1] : cu[1] - b[1];
            if (calls > 0) {
                cmds.add(new Cmd(name, calls, usec));
            }
        });
        cmds.sort(Comparator.comparingDouble((Cmd c) -> -c.usec()));
        List<String> out = new ArrayList<>();
        out.add(Screen.HEADER + (before == null ? I18n.t("stats.redis.topCommands")
                : I18n.t("stats.redis.topCommandsRecent", Screen.duration(d.at() - d.beforeAt()))) + Screen.RESET);
        if (cmds.isEmpty()) {
            out.add(Screen.DIM + "  " + I18n.t("stats.redis.noCommands", Screen.duration(d.at() - d.beforeAt())) + Screen.RESET);
            return out;
        }
        List<List<String>> table = new ArrayList<>();
        for (Cmd c : cmds) {
            table.add(before == null
                    ? List.of(c.name(), Screen.compact(c.calls()), String.format("%.1f", c.usec() / c.calls()), Screen.compact(Math.round(c.usec() / 1000)))
                    : List.of(c.name(), Screen.compact(Math.round(c.calls() / seconds * 10) / 10.0), String.format("%.1f", c.usec() / c.calls()),
                    String.format("%.1f", c.usec() / 1000 / seconds)));
        }
        out.addAll(Screen.table(List.of(new Column(I18n.t("stats.redis.command"), 'l', 8),
                new Column(before == null ? "calls" : "calls/s", 'r', 5), new Column("µs/call", 'r', 7),
                new Column(before == null ? "ms" : "ms/s", 'r', 4)), table, Math.max(20, width), rows + 1, -1, false));
        return out;
    }

    /** LATENCY LATEST: [[sự kiện, giờ, ms lần gần nhất, ms lớn nhất], ...]; trống khi chưa bật latency-monitor-threshold. */
    private static List<String> latency(Object reply) {
        List<String> out = new ArrayList<>();
        out.add(Screen.HEADER + "LATENCY LATEST" + Screen.RESET);
        if (!(reply instanceof List<?> events) || events.isEmpty()) {
            out.add(Screen.DIM + "  " + I18n.t("stats.redis.latencyOff") + Screen.RESET);
            return out;
        }
        for (Object e : events) {
            if (e instanceof List<?> f && f.size() >= 4) {
                out.add("  " + String.format("%-22s", RedisSession.text(f.get(0))) + Screen.DIM + " latest " + Screen.RESET
                        + f.get(2) + "ms" + Screen.DIM + " · max " + Screen.RESET + f.get(3) + "ms");
            }
        }
        return out;
    }

    private static List<String> fitAll(List<String> lines, int w) {
        return lines.stream().map(l -> Screen.fit(l, w)).toList();
    }

    private static void section(List<String> out, String title, Map<String, String> info, String... keys) {
        out.add(Screen.HEADER + title + Screen.RESET);
        for (String k : keys) {
            if (info.containsKey(k)) {
                out.add("  " + Screen.DIM + String.format("%-28s", k) + Screen.RESET + info.get(k));
            }
        }
    }
}
