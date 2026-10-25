package com.sqlctx.redis;

import com.sqlctx.cli.view.DataViewTable;
import com.sqlctx.shell.Screen;
import com.sqlctx.util.I18n;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * \stats: mỗi node 1 dòng chỉ số lấy từ INFO (cluster: mọi master và replica), rồi các cảnh báo đáng để ý - không đặt
 * maxmemory, sắp đầy bộ nhớ, đang evict, master không có replica, lần lưu RDB/AOF lỗi, replica mất kết nối...
 * Lấy mẫu ({@link #sample}) tách khỏi dựng khung ({@link #frame}) để dựng khung test được không cần server.
 */
final class RedisStats {

    private RedisStats() {
    }

    private static final String DIM = "\u001b[38;5;245m";
    private static final String YELLOW = "\u001b[33m";
    private static final String RED = "\u001b[31m";
    private static final String GREEN = "\u001b[32m";
    private static final String RESET = "\u001b[0m";

    /** {@code replicas}: số replica đang sống của master này (-1 nếu không biết); {@code error}: không lấy được INFO. */
    record Node(String address, boolean master, int replicas, Map<String, String> info, String error) {
    }

    /** {@code clusterState}: cluster_state của CLUSTER INFO, null nếu không phải cluster. */
    record Sample(List<Node> nodes, String clusterState, List<String> failed) {
    }

    static Map<String, String> parseInfo(String text) {
        Map<String, String> values = new TreeMap<>();
        for (String l : text.split("\r?\n")) {
            int colon = l.indexOf(':');
            if (colon > 0 && !l.startsWith("#")) {
                values.put(l.substring(0, colon), l.substring(colon + 1).strip());
            }
        }
        return values;
    }

    static Sample sample(RedisConnection c) throws IOException {
        List<Node> nodes = new ArrayList<>();
        if (!c.cluster()) {
            Node master = node(c, c.address(), null, -1);
            nodes.add(master);
            for (String replica : replicaAddresses(master.info())) {
                nodes.add(replica(c.profile(), replica));
            }
            return new Sample(nodes, null, List.of());
        }
        String state = null;
        if (c.call("CLUSTER", "INFO") instanceof RespClient.Bulk b) {
            state = parseInfo(b.utf8()).get("cluster_state");
        }
        List<RedisConnection.ClusterNode> all = c.clusterNodes();
        List<String> failed = new ArrayList<>();
        for (RedisConnection.ClusterNode n : all) {
            if (n.failed()) {
                failed.add(n.address());
                continue;
            }
            int replicas = n.master()
                    ? (int) all.stream().filter(r -> !r.master() && !r.failed() && r.masterId().equals(n.id())).count() : -1;
            nodes.add(node(c, n.address(), n.master(), replicas));
        }
        return new Sample(nodes, state, failed);
    }

    /** "slave0:ip=127.0.0.1,port=6391,state=online,..." của INFO replication. */
    static List<String> replicaAddresses(Map<String, String> info) {
        List<String> out = new ArrayList<>();
        for (var e : info.entrySet()) {
            if (e.getKey().matches("slave\\d+")) {
                String ip = null;
                String port = null;
                for (String part : e.getValue().split(",")) {
                    if (part.startsWith("ip=")) {
                        ip = part.substring(3);
                    } else if (part.startsWith("port=")) {
                        port = part.substring(5);
                    }
                }
                if (ip != null && port != null) {
                    out.add(ip + ":" + port);
                }
            }
        }
        return out;
    }

    /** Replica của server đơn/sentinel: nối thẳng bằng thông tin đăng nhập của master, timeout ngắn. */
    private static Node replica(RedisProfile profile, String address) {
        int colon = address.lastIndexOf(':');
        try (RespClient r = RespClient.connect(profile.at(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1))), 2000)) {
            Object reply = r.call("INFO");
            if (reply instanceof RespClient.RespError e) {
                return new Node(address, false, -1, Map.of(), e.message());
            }
            return new Node(address, false, -1, parseInfo(RedisSession.text(reply)), null);
        } catch (IOException | RuntimeException e) {
            return new Node(address, false, -1, Map.of(), e.getMessage());
        }
    }

    private static Node node(RedisConnection c, String address, Boolean master, int replicas) {
        try {
            Object reply = c.cluster() ? c.callOn(address, "INFO") : c.call("INFO");
            if (reply instanceof RespClient.RespError e) {
                return new Node(address, master == null || master, replicas, Map.of(), e.message());
            }
            Map<String, String> info = parseInfo(RedisSession.text(reply));
            boolean isMaster = master != null ? master : !"slave".equals(info.get("role"));
            int r = replicas >= 0 ? replicas : (int) num(info, "connected_slaves");
            return new Node(address, isMaster, isMaster ? r : -1, info, null);
        } catch (IOException e) {
            return new Node(address, master == null || master, replicas, Map.of(), e.getMessage());
        }
    }

    private static double num(Map<String, String> info, String key) {
        try {
            return Double.parseDouble(info.getOrDefault(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Tổng key của mọi DB ("db0:keys=3,expires=0,avg_ttl=0"). */
    static long keys(Map<String, String> info) {
        long total = 0;
        for (var e : info.entrySet()) {
            if (e.getKey().matches("db\\d+")) {
                for (String part : e.getValue().split(",")) {
                    if (part.startsWith("keys=")) {
                        total += Long.parseLong(part.substring(5));
                    }
                }
            }
        }
        return total;
    }

    static String hitRate(Map<String, String> info) {
        double hits = num(info, "keyspace_hits");
        double misses = num(info, "keyspace_misses");
        return hits + misses == 0 ? "-" : String.format("%.1f", 100 * hits / (hits + misses));
    }

    /** Các dòng của 1 khung: tiêu đề, bảng (vừa {@code width}), cảnh báo. */
    static List<String> frame(String title, Sample s, int width) {
        List<String> out = new ArrayList<>();
        out.add(title);
        List<List<String>> rows = new ArrayList<>();
        for (Node n : s.nodes()) {
            Map<String, String> i = n.info();
            if (n.error() != null) {
                rows.add(List.of(n.address(), n.master() ? "master" : "replica", "-", "-", "-", "-", "-", "-", "-", "-"));
                continue;
            }
            String max = num(i, "maxmemory") == 0 ? "∞" : i.getOrDefault("maxmemory_human", "?");
            rows.add(List.of(n.address(), n.master() ? "master" + (n.replicas() >= 0 ? " (" + n.replicas() + ")" : "") : "replica",
                    i.getOrDefault("instantaneous_ops_per_sec", "-"),
                    i.getOrDefault("instantaneous_input_kbps", "-") + " / " + i.getOrDefault("instantaneous_output_kbps", "-"),
                    i.getOrDefault("used_memory_human", "-") + " / " + max,
                    i.getOrDefault("mem_fragmentation_ratio", "-"),
                    i.getOrDefault("connected_clients", "-") + " (" + i.getOrDefault("blocked_clients", "0") + ")",
                    hitRate(i), String.valueOf(keys(i)), i.getOrDefault("evicted_keys", "-")));
        }
        out.addAll(Screen.lines(DataViewTable.renderFit(List.of("node", I18n.t("redis.stats.role"), "ops/s", "kB/s in / out",
                I18n.t("redis.stats.memory"), "frag", "clients (blocked)", "hit %", "keys", "evicted"), rows, width)));
        List<String> warnings = warnings(s);
        out.add(warnings.isEmpty() ? GREEN + "✓ " + I18n.t("redis.stats.ok") + RESET : "");
        out.addAll(warnings);
        return out;
    }

    /** Đỏ (✖) = đang có sự cố; vàng (⚠) = cấu hình/tình trạng nên xem lại. */
    static List<String> warnings(Sample s) {
        List<String> red = new ArrayList<>();
        List<String> yellow = new ArrayList<>();
        if (s.clusterState() != null && !"ok".equals(s.clusterState())) {
            red.add(I18n.t("redis.stats.clusterState", s.clusterState()));
        }
        if (!s.failed().isEmpty()) {
            red.add(I18n.t("redis.stats.failed", String.join(", ", s.failed())));
        }
        List<String> noMax = new ArrayList<>();
        List<String> noReplica = new ArrayList<>();
        for (Node n : s.nodes()) {
            Map<String, String> i = n.info();
            String a = n.address();
            if (n.error() != null) {
                red.add(I18n.t("redis.stats.unreachable", a, n.error()));
                continue;
            }
            double max = num(i, "maxmemory");
            double used = num(i, "used_memory");
            if (max == 0) {
                noMax.add(a);
            } else if (used / max >= 0.9) {
                red.add(I18n.t("redis.stats.memoryFull", a, String.format("%.0f", 100 * used / max)));
            }
            if (num(i, "evicted_keys") > 0) {
                yellow.add(I18n.t("redis.stats.evicting", a, i.get("evicted_keys")));
            }
            if (num(i, "rejected_connections") > 0) {
                yellow.add(I18n.t("redis.stats.rejected", a, i.get("rejected_connections")));
            }
            double maxClients = num(i, "maxclients");
            if (maxClients > 0 && num(i, "connected_clients") >= 0.9 * maxClients) {
                yellow.add(I18n.t("redis.stats.clients", a, i.get("connected_clients"), i.get("maxclients")));
            }
            if (i.containsKey("rdb_last_bgsave_status") && !"ok".equals(i.get("rdb_last_bgsave_status"))) {
                red.add(I18n.t("redis.stats.rdb", a));
            }
            if ("1".equals(i.get("aof_enabled")) && !"ok".equals(i.getOrDefault("aof_last_write_status", "ok"))) {
                red.add(I18n.t("redis.stats.aof", a));
            }
            if (!n.master() && "down".equals(i.get("master_link_status"))) {
                red.add(I18n.t("redis.stats.linkDown", a, i.getOrDefault("master_host", "?") + ":" + i.getOrDefault("master_port", "?")));
            }
            if (n.master() && n.replicas() == 0) {
                noReplica.add(a);
            }
            if (num(i, "mem_fragmentation_ratio") > 1.5 && used > 100L * 1024 * 1024) {
                yellow.add(I18n.t("redis.stats.fragmentation", a, i.get("mem_fragmentation_ratio")));
            }
        }
        if (!noMax.isEmpty()) {
            yellow.add(I18n.t("redis.stats.noMaxmemory", String.join(", ", noMax)));
        }
        if (!noReplica.isEmpty()) {
            yellow.add(I18n.t("redis.stats.noReplica", String.join(", ", noReplica)));
        }
        List<String> out = new ArrayList<>();
        red.forEach(w -> out.add(RED + "✖ " + w + RESET));
        yellow.forEach(w -> out.add(YELLOW + "⚠ " + w + RESET));
        return out;
    }

    static String title(RedisProfile p, Sample s, long intervalSeconds, boolean live) {
        String mode = s.clusterState() != null ? I18n.t("redis.stats.modeCluster", s.nodes().size() + s.failed().size())
                : p.sentinelMaster() != null ? "sentinel " + p.sentinelMaster() : "standalone";
        String clock = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        return "\u001b[1;36mRedis\u001b[0m · " + p.name() + " · " + mode + " · " + clock
                + (live ? DIM + " · " + I18n.t("common.statsEvery", intervalSeconds) + RESET : "");
    }
}
