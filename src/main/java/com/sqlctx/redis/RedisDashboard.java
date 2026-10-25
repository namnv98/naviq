package com.sqlctx.redis;

import com.sqlctx.shell.Dashboard;
import com.sqlctx.shell.Screen;
import com.sqlctx.shell.Screen.Column;
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

    private final RedisConnection connection;
    private final RedisProfile profile;
    private final Deque<RedisStats.Sample> history = new ArrayDeque<>();
    private volatile Map<String, String> commandStats = Map.of();
    private volatile String commandStatsOf;

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
            Object reply = connection.cluster() ? connection.callOn(n.address(), "INFO", "commandstats")
                    : n.address().equals(connection.address()) ? connection.call("INFO", "commandstats") : direct(n.address());
            commandStats = reply instanceof RespClient.Bulk b ? RedisStats.parseInfo(b.utf8()) : Map.of();
            commandStatsOf = n.address();
        }
    }

    /** Replica của server đơn/sentinel: không nằm trong RedisConnection, nối riêng 1 lần. */
    private Object direct(String address) {
        int colon = address.lastIndexOf(':');
        try (RespClient c = RespClient.connect(connection.profile().at(address.substring(0, colon),
                Integer.parseInt(address.substring(colon + 1))), 2000)) {
            return c.call("INFO", "commandstats");
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
        return 1;
    }

    @Override
    public int rows(int panel) {
        synchronized (history) {
            return history.isEmpty() ? 0 : history.peekLast().nodes().size();
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
        String mode = cur == null ? "" : cur.clusterState() != null ? I18n.t("redis.stats.modeCluster", cur.nodes().size() + cur.failed().size())
                : profile.sentinelMaster() != null ? "sentinel " + profile.sentinelMaster() : "standalone";
        out.add(Dashboard.titleBar("Redis · " + profile.name() + (mode.isEmpty() ? "" : " · " + mode), right, width));
        if (cur == null) {
            out.add(Screen.DIM + " " + I18n.t("stats.loading") + Screen.RESET);
            return out;
        }

        List<String> summary = new ArrayList<>();
        long masters = cur.nodes().stream().filter(RedisStats.Node::master).count();
        long keys = cur.nodes().stream().filter(RedisStats.Node::master).mapToLong(n -> RedisStats.keys(n.info())).sum();
        long clients = cur.nodes().stream().mapToLong(n -> (long) num(n.info(), "connected_clients")).sum();
        List<String> parts = new ArrayList<>();
        parts.add(I18n.t("stats.redis.nodes", cur.nodes().size(), masters, cur.nodes().size() - masters)
                + (cur.failed().isEmpty() ? "" : "  " + Screen.RED + I18n.t("stats.redis.failedCount", cur.failed().size()) + Screen.RESET));
        if (cur.clusterState() != null) {
            parts.add("cluster_state " + ("ok".equals(cur.clusterState()) ? Screen.GREEN : Screen.RED) + cur.clusterState() + Screen.RESET);
        }
        parts.add(I18n.t("stats.redis.keys") + " " + keys);
        parts.add("clients " + clients);
        cur.nodes().stream().filter(n -> n.error() == null).findFirst()
                .ifPresent(n -> parts.add("Redis " + n.info().getOrDefault("redis_version", "?")));
        summary.addAll(Screen.flow(parts, "  · ", width - 4));
        List<Double> ops = opsSeries(h, null);
        int sparkWidth = Math.max(8, Math.min(60, width - 30));
        summary.add(String.format("%-11s", "ops/s") + " " + Screen.spark(ops, sparkWidth) + " " + Screen.NUMBER
                + Screen.compact(ops.isEmpty() ? 0 : ops.get(ops.size() - 1)) + Screen.RESET);
        int barWidth = Math.max(10, Math.min(30, width / 4));
        List<RedisStats.Node> byMemory = new ArrayList<>(cur.nodes().stream().filter(n -> n.error() == null).toList());
        byMemory.sort(Comparator.comparingDouble(n -> -num(n.info(), "used_memory")));
        for (RedisStats.Node n : byMemory.subList(0, Math.min(3, byMemory.size()))) {
            Map<String, String> i = n.info();
            double max = num(i, "maxmemory");
            double limit = max > 0 ? max : num(i, "total_system_memory");
            String of = max > 0 ? "maxmemory " + i.getOrDefault("maxmemory_human", "?")
                    : I18n.t("stats.redis.ofSystem", i.getOrDefault("total_system_memory_human", "?"));
            summary.addAll(Screen.flow(List.of(String.format("%-11s", I18n.t("stats.redis.memory")) + " "
                    + (limit > 0 ? Screen.bar(num(i, "used_memory") / limit, barWidth, max > 0 ? 0.8 : 0.6, max > 0 ? 0.9 : 0.8) + " " : "")
                    + i.getOrDefault("used_memory_human", "?") + " / " + of, Screen.DIM + n.address() + Screen.RESET), "   ", width - 4));
        }
        out.addAll(Screen.box(I18n.t("stats.overview"), summary, width, summary.size() + 2, false));

        List<String> warnings = RedisStats.warnings(cur);
        int warnLines = Math.min(4, Math.max(1, warnings.size()));
        int panelHeight = Math.max(4, height - out.size() - (warnLines + 2) - 1);
        if (state.detail) {
            out.addAll(detail(cur, state, width, panelHeight));
        } else {
            List<List<String>> rows = new ArrayList<>();
            for (RedisStats.Node n : cur.nodes()) {
                Map<String, String> i = n.info();
                if (n.error() != null) {
                    rows.add(List.of(n.address(), n.master() ? "master" : "replica", "", Screen.RED + "✖", "-", "-", "-", "-", "-", "-"));
                    continue;
                }
                double max = num(i, "maxmemory");
                rows.add(List.of(n.address(), n.master() ? "master" + (n.replicas() >= 0 ? " (" + n.replicas() + ")" : "") : "replica",
                        Screen.spark(opsSeries(h, n.address()), 10), Screen.compact(num(i, "instantaneous_ops_per_sec")),
                        i.getOrDefault("used_memory_human", "-") + (max > 0 ? " / " + i.getOrDefault("maxmemory_human", "") : ""),
                        i.getOrDefault("mem_fragmentation_ratio", "-"),
                        i.getOrDefault("connected_clients", "-"), RedisStats.hitRate(i), Screen.compact(RedisStats.keys(i)),
                        Screen.compact(num(i, "evicted_keys"))));
            }
            out.addAll(Screen.box("Node (" + rows.size() + ")", Screen.table(List.of(new Column("node", 'l', 10),
                    new Column(I18n.t("redis.stats.role"), 'l', 7), new Column(I18n.t("stats.trend"), 'l', 10),
                    new Column("ops/s", 'r', 5), new Column(I18n.t("stats.redis.memory"), 'l', 6), new Column("frag", 'r', 4),
                    new Column("clients", 'r', 7), new Column("hit %", 'r', 5), new Column("keys", 'r', 4),
                    new Column("evicted", 'r', 7)), rows, width - 4, panelHeight - 2, state.selected[0], true),
                    width, panelHeight, true));
        }
        List<String> warnBody = new ArrayList<>();
        if (warnings.isEmpty()) {
            warnBody.add(Screen.GREEN + "✓ " + I18n.t("redis.stats.ok") + Screen.RESET);
        } else {
            warnBody.addAll(warnings.subList(0, Math.min(warnLines, warnings.size())));
            if (warnings.size() > warnLines) {
                warnBody.set(warnLines - 1, Screen.DIM + I18n.t("stats.moreWarnings", warnings.size() - warnLines + 1) + Screen.RESET);
            }
        }
        out.addAll(Screen.box(I18n.t("stats.warnings") + " (" + warnings.size() + ")", warnBody, width, warnLines + 2, false));
        out.add(Dashboard.footer(I18n.t(state.detail ? "stats.keysDetail" : "stats.keysOnePanel"), width));
        return out.subList(0, Math.min(out.size(), height));
    }

    private List<String> detail(RedisStats.Sample cur, Dashboard.State state, int width, int height) {
        if (cur.nodes().isEmpty()) {
            return Screen.box("", List.of(), width, height, true);
        }
        RedisStats.Node n = cur.nodes().get(Math.min(state.selected[0], cur.nodes().size() - 1));
        Map<String, String> i = n.info();
        List<String> left = new ArrayList<>();
        if (n.error() != null) {
            left.add(Screen.RED + n.error() + Screen.RESET);
        } else {
            section(left, "Server", i, "redis_version", "redis_mode", "uptime_in_days", "tcp_port", "process_id");
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
        right.add(Screen.HEADER + I18n.t("stats.redis.topCommands") + Screen.RESET);
        if (n.address().equals(commandStatsOf) && !commandStats.isEmpty()) {
            record Cmd(String name, long calls, double usec, double perCall) {
            }
            List<Cmd> cmds = new ArrayList<>();
            commandStats.forEach((k, v) -> {
                if (k.startsWith("cmdstat_")) {
                    long calls = 0;
                    double usec = 0;
                    double per = 0;
                    for (String part : v.split(",")) {
                        String[] kv = part.split("=", 2);
                        if (kv.length == 2) {
                            switch (kv[0]) {
                                case "calls" -> calls = Long.parseLong(kv[1]);
                                case "usec" -> usec = Double.parseDouble(kv[1]);
                                case "usec_per_call" -> per = Double.parseDouble(kv[1]);
                                default -> {
                                }
                            }
                        }
                    }
                    cmds.add(new Cmd(k.substring(8).replace('|', ' '), calls, usec, per));
                }
            });
            cmds.sort(Comparator.comparingDouble((Cmd c) -> -c.usec()));
            List<List<String>> rows = new ArrayList<>();
            for (Cmd c : cmds) {
                rows.add(List.of(c.name(), Screen.compact(c.calls()), String.format("%.1f", c.perCall()),
                        Screen.compact(c.usec() / 1000)));
            }
            right.addAll(Screen.table(List.of(new Column(I18n.t("stats.redis.command"), 'l', 8), new Column("calls", 'r', 5),
                    new Column("µs/call", 'r', 7), new Column("ms", 'r', 4)), rows, Math.max(20, width / 2 - 4), height - 3, -1, false));
        } else {
            right.add(Screen.DIM + I18n.t("stats.loading") + Screen.RESET);
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
