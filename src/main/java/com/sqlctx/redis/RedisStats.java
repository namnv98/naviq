package com.sqlctx.redis;

import com.sqlctx.ui.DataViewTable;
import com.sqlctx.ui.Screen;
import com.sqlctx.util.I18n;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
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

    /**
     * {@code replicas}: số replica đang sống của master này (-1 nếu không biết); {@code error}: không lấy được INFO;
     * {@code slowlog}: vài mục SLOWLOG mới nhất (rỗng nếu server không cho gọi SLOWLOG).
     */
    record Node(String address, boolean master, int replicas, Map<String, String> info, String error, List<Slow> slowlog,
                Clock clock) {
        Node(String address, boolean master, int replicas, Map<String, String> info, String error) {
            this(address, master, replicas, info, error, List.of(), null);
        }

        Node(String address, boolean master, int replicas, Map<String, String> info, String error, List<Slow> slowlog) {
            this(address, master, replicas, info, error, slowlog, null);
        }
    }

    /**
     * Lúc nhận INFO của node theo máy này (giữa lúc gửi và lúc nhận): {@code wallMs} đồng hồ thường - so với
     * server_time_usec ra độ lệch giờ; {@code monoNanos} đồng hồ đơn điệu - so khoảng trôi giữa 2 mẫu để thấy đồng hồ
     * server nhảy, không bị nhầm khi chính máy này đổi giờ.
     */
    record Clock(long wallMs, long monoNanos) {
        static Clock around(long startNanos, long startWallMs) {
            long end = System.nanoTime();
            long half = (end - startNanos) / 2;
            return new Clock(startWallMs + half / 1_000_000, startNanos + half);
        }
    }

    /** 1 mục SLOWLOG: {@code id} tăng dần theo từng lệnh chậm, {@code time} giây (đồng hồ server), {@code micros} thời gian chạy. */
    record Slow(long id, long time, long micros, String command) {
    }

    /** {@code at}: lúc lấy mẫu (ms); {@code clusterState}: cluster_state của CLUSTER INFO, null nếu không phải cluster. */
    record Sample(long at, List<Node> nodes, String clusterState, List<String> failed) {
    }

    /** Số mục SLOWLOG lấy mỗi lần (đủ để thấy lệnh chậm nhất gần đây, id mới nhất cho biết có thêm bao nhiêu lệnh chậm). */
    static final int SLOWLOG = 10;

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
            return new Sample(System.currentTimeMillis(), nodes, null, List.of());
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
        return new Sample(System.currentTimeMillis(), nodes, state, failed);
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
            long nanos = System.nanoTime();
            long wall = System.currentTimeMillis();
            Object reply = r.call("INFO");
            Clock clock = Clock.around(nanos, wall);
            if (reply instanceof RespClient.RespError e) {
                return new Node(address, false, -1, Map.of(), e.message());
            }
            return new Node(address, false, -1, parseInfo(RedisSession.text(reply)), null,
                    slowlog(() -> r.call("SLOWLOG", "GET", String.valueOf(SLOWLOG))), clock);
        } catch (IOException | RuntimeException e) {
            return new Node(address, false, -1, Map.of(), e.getMessage());
        }
    }

    private static Node node(RedisConnection c, String address, Boolean master, int replicas) {
        try {
            long nanos = System.nanoTime();
            long wall = System.currentTimeMillis();
            Object reply = c.cluster() ? c.callOn(address, "INFO") : c.call("INFO");
            Clock clock = Clock.around(nanos, wall);
            if (reply instanceof RespClient.RespError e) {
                return new Node(address, master == null || master, replicas, Map.of(), e.message());
            }
            Map<String, String> info = parseInfo(RedisSession.text(reply));
            boolean isMaster = master != null ? master : !"slave".equals(info.get("role"));
            int r = replicas >= 0 ? replicas : (int) num(info, "connected_slaves");
            List<Slow> slow = slowlog(() -> c.cluster() ? c.callOn(address, "SLOWLOG", "GET", String.valueOf(SLOWLOG))
                    : c.call("SLOWLOG", "GET", String.valueOf(SLOWLOG)));
            return new Node(address, isMaster, isMaster ? r : -1, info, null, slow, clock);
        } catch (IOException e) {
            return new Node(address, master == null || master, replicas, Map.of(), e.getMessage());
        }
    }

    private interface Call {
        Object get() throws IOException;
    }

    /** SLOWLOG lỗi (bị ACL cấm, mất kết nối giữa chừng...) không làm node bị coi là chết như lỗi INFO. */
    private static List<Slow> slowlog(Call call) {
        try {
            return parseSlowlog(call.get());
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** Reply của SLOWLOG GET: mỗi mục [id, giờ, micro giây, [lệnh, tham số...], ...]; lỗi (vd ACL cấm) thì rỗng. */
    static List<Slow> parseSlowlog(Object reply) {
        List<Slow> out = new ArrayList<>();
        if (reply instanceof List<?> entries) {
            for (Object e : entries) {
                if (e instanceof List<?> f && f.size() >= 4 && f.get(0) instanceof Long id && f.get(1) instanceof Long time
                        && f.get(2) instanceof Long micros && f.get(3) instanceof List<?> args) {
                    String command = String.join(" ", args.stream().map(RedisSession::text).toList());
                    out.add(new Slow(id, time, micros, command.length() > 120 ? command.substring(0, 119) + "…" : command));
                }
            }
        }
        return out;
    }

    /** {@code bytes}: replica chậm hơn master bao nhiêu byte replication; {@code ackSeconds}: lần ack cuối cách đây (-1 nếu không biết). */
    record ReplLag(long bytes, long ackSeconds) {
    }

    /**
     * Replica chậm bao nhiêu so với master của nó: ưu tiên dòng "slaveN:...,offset=,lag=" trong INFO của master (cùng 1
     * lần INFO nên không lệch thời điểm), không khớp địa chỉ thì so master_repl_offset của master với slave_repl_offset
     * của replica. Null nếu không phải replica hoặc không thấy master trong mẫu.
     */
    static ReplLag replicaLag(Sample s, Node replica) {
        if (replica.master() || replica.error() != null) {
            return null;
        }
        for (Node m : s.nodes()) {
            if (!m.master() || m.error() != null) {
                continue;
            }
            for (var e : m.info().entrySet()) {
                if (!e.getKey().matches("slave\\d+")) {
                    continue;
                }
                Map<String, String> f = new HashMap<>();
                for (String part : e.getValue().split(",")) {
                    int eq = part.indexOf('=');
                    if (eq > 0) {
                        f.put(part.substring(0, eq), part.substring(eq + 1));
                    }
                }
                if (replica.address().equals(f.get("ip") + ":" + f.get("port"))) {
                    return new ReplLag(Math.max(0, (long) num(m.info(), "master_repl_offset") - (long) num(f, "offset")),
                            (long) num(f, "lag"));
                }
            }
        }
        String master = replica.info().get("master_host") + ":" + replica.info().get("master_port");
        for (Node m : s.nodes()) {
            if (m.master() && m.error() == null && m.address().equals(master)) {
                return new ReplLag(Math.max(0, (long) num(m.info(), "master_repl_offset") - (long) num(replica.info(), "slave_repl_offset")), -1);
            }
        }
        return null;
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

    /** Hit % trong khoảng nếu có, không thì từ lúc khởi động. */
    static String hitText(Double windowed, Map<String, String> info) {
        if (windowed == null) {
            return hitRate(info);
        }
        return windowed.isNaN() ? "-" : String.format("%.1f", windowed);
    }

    /** Evicted/giây trong khoảng nếu có, không thì tổng từ lúc khởi động. */
    static String evictedText(RedisTrend.Delta d, Map<String, String> info) {
        return d == null ? info.getOrDefault("evicted_keys", "-") : Screen.compact(Math.round(d.perSecond() * 10) / 10.0);
    }

    static String hitRate(Map<String, String> info) {
        double hits = num(info, "keyspace_hits");
        double misses = num(info, "keyspace_misses");
        return hits + misses == 0 ? "-" : String.format("%.1f", 100 * hits / (hits + misses));
    }

    /**
     * Các dòng của 1 khung: tiêu đề, bảng (vừa {@code width}), cảnh báo. {@code history}: mẫu cuối là hiện tại; có từ 2 mẫu
     * thì hit % và evicted tính trong khoảng giữa các mẫu, không thì từ lúc server khởi động.
     */
    static List<String> frame(String title, List<Sample> history, int width) {
        Sample s = history.get(history.size() - 1);
        RedisTrend trend = new RedisTrend(history);
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
                    hitText(trend.hitRate(n.address(), RedisTrend.WINDOW_MS), i), String.valueOf(keys(i)),
                    evictedText(trend.delta(n.address(), "evicted_keys", RedisTrend.WINDOW_MS), i)));
        }
        boolean windowed = history.size() > 1;
        out.addAll(Screen.lines(DataViewTable.renderFit(List.of("node", I18n.t("redis.stats.role"), "ops/s", "kB/s in / out",
                I18n.t("redis.stats.memory"), "frag", "clients (blocked)", "hit %", "keys", windowed ? "evicted/s" : "evicted"),
                rows, width)));
        List<String> warnings = warnings(history);
        out.add(warnings.isEmpty() ? GREEN + "✓ " + I18n.t("redis.stats.ok") + RESET : "");
        out.addAll(warnings);
        return out;
    }

    static List<String> warnings(Sample s) {
        return warnings(List.of(s));
    }

    /**
     * Đỏ (✖) = đang có sự cố; vàng (⚠) = cấu hình/tình trạng nên xem lại. {@code history}: các mẫu theo thời gian, mẫu
     * cuối là hiện tại - evict, từ chối kết nối, lệnh chậm chỉ báo khi xảy ra trong {@link RedisTrend#WINDOW_MS} gần đây
     * (bộ đếm của INFO cộng dồn từ lúc khởi động), nên 1 mẫu thì chưa báo được.
     */
    static List<String> warnings(List<Sample> history) {
        Sample s = history.get(history.size() - 1);
        RedisTrend trend = new RedisTrend(history);
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
            RedisTrend.Delta evicted = trend.delta(a, "evicted_keys", RedisTrend.WINDOW_MS);
            if (evicted != null && evicted.value() > 0) {
                yellow.add(I18n.t("redis.stats.evicting", a, (long) evicted.value(), Screen.duration(evicted.ms()),
                        Screen.compact(Math.round(evicted.perSecond() * 10) / 10.0)));
            }
            RedisTrend.Delta rejected = trend.delta(a, "rejected_connections", RedisTrend.WINDOW_MS);
            if (rejected != null && rejected.value() > 0) {
                yellow.add(I18n.t("redis.stats.rejected", a, (long) rejected.value(), Screen.duration(rejected.ms())));
            }
            Long jump = trend.clockJumpMs(a, RedisTrend.WINDOW_MS);
            Long skew = trend.skewMs(a);
            if (jump != null) {
                red.add(I18n.t("redis.stats.clockJump", a, signed(jump)));
            } else if (skew != null && Math.abs(skew) >= RedisTrend.SKEW_MS) {
                yellow.add(I18n.t("redis.stats.clockSkew", a, signed(skew)));
            }
            RedisTrend.SlowSummary slow = trend.slowlog(a, RedisTrend.WINDOW_MS);
            if (slow != null) {
                yellow.add(I18n.t("redis.stats.slowlog", a, slow.count(), Screen.duration(slow.ms()),
                        slow.slowest() == null ? "?" : millis(slow.slowest().micros()),
                        slow.slowest() == null ? "?" : slow.slowest().command()));
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
            } else if (!n.master()) {
                ReplLag lag = replicaLag(s, n);
                long[] behind = trend.replicaFallingBehind(a, REPLICA_BEHIND_SAMPLES);
                if (lag != null && lag.ackSeconds() >= REPLICA_ACK_S) {
                    yellow.add(I18n.t("redis.stats.replicaAck", a, lag.ackSeconds()));
                } else if (behind != null && behind[0] >= REPLICA_BEHIND_BYTES) {
                    yellow.add(I18n.t("redis.stats.replicaBehind", a, bytes(behind[0]), Screen.duration(behind[1])));
                }
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

    /** Master nhận ack của replica mỗi giây; quá ngần này giây không ack là replica có vấn đề. */
    static final long REPLICA_ACK_S = 10;
    /** Replica chậm hơn master quá ngần này byte và tăng liên tục qua {@link #REPLICA_BEHIND_SAMPLES} mẫu: đọc không kịp. */
    static final long REPLICA_BEHIND_BYTES = 1024 * 1024;
    static final int REPLICA_BEHIND_SAMPLES = 5;

    /** "+5m12s", "-3s", "+0.4s". */
    static String signed(long ms) {
        String sign = ms < 0 ? "-" : "+";
        long abs = Math.abs(ms);
        return sign + (abs < 10_000 ? String.format("%.1fs", abs / 1000.0) : Screen.duration(abs));
    }

    static String millis(long micros) {
        return micros >= 1_000_000 ? String.format("%.1fs", micros / 1e6) : String.format("%.1fms", micros / 1e3);
    }

    static String bytes(double b) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int u = 0;
        while (b >= 1024 && u < units.length - 1) {
            b /= 1024;
            u++;
        }
        return u == 0 ? String.format("%.0f B", b) : String.format("%.1f %s", b, units[u]);
    }

    static String title(RedisProfile p, Sample s, long intervalSeconds, boolean live) {
        String mode = s.clusterState() != null ? I18n.t("redis.stats.modeCluster", s.nodes().size() + s.failed().size())
                : p.sentinelMaster() != null ? "sentinel " + p.sentinelMaster() : "standalone";
        String clock = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        return "\u001b[1;36mRedis\u001b[0m · " + p.name() + " · " + mode + " · " + clock
                + (live ? DIM + " · " + I18n.t("common.statsEvery", intervalSeconds) + RESET : "");
    }
}
