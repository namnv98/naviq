package com.sqlctx.redis;

import com.sqlctx.redis.RespClient.RespError;
import com.sqlctx.util.I18n;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Redis như người dùng thấy: 1 server, master do Sentinel chỉ ra, hoặc cả Redis Cluster.
 * <p>
 * Sentinel ({@code sentinel-master} trong context): host/port là của sentinel; hỏi {@code SENTINEL
 * get-master-addr-by-name} rồi nối tới master. Kết nối lại (sau Ctrl+C, mất kết nối) hỏi lại - failover xong là
 * sang master mới.
 * <p>
 * Cluster (tự nhận ra qua {@code INFO cluster}): chạy lệnh ở node hiện tại; MOVED thì nối sang node được chỉ và ở
 * lại đó (như {@code redis-cli -c}); ASK thì gửi ASKING + lệnh ở node đó 1 lần. Lệnh không có key (KEYS, DBSIZE,
 * FLUSHALL...) chạy ở node hiện tại; {@link #scan} quét mọi master.
 */
final class RedisConnection implements Closeable {

    private static final int MAX_REDIRECTS = 5;

    private final RedisProfile profile;
    private final int timeoutMs;
    private final boolean cluster;
    private final Map<String, RespClient> nodes = new LinkedHashMap<>();
    private RespClient current;
    private String currentAddress;

    private RedisConnection(RedisProfile profile, int timeoutMs, RespClient first, String address, boolean cluster) {
        this.profile = profile;
        this.timeoutMs = timeoutMs;
        this.cluster = cluster;
        this.current = first;
        this.currentAddress = address;
        nodes.put(address, first);
    }

    /** {@code readTimeoutMs} như {@link RespClient#connect}. */
    static RedisConnection open(RedisProfile profile, int readTimeoutMs) throws IOException {
        RedisProfile target = profile.sentinelMaster() != null ? resolveMaster(profile) : profile;
        RespClient c = RespClient.connect(target, readTimeoutMs);
        boolean cluster = false;
        if (c.call("INFO", "cluster") instanceof RespClient.Bulk info) {
            cluster = info.utf8().contains("cluster_enabled:1");
        }
        return new RedisConnection(target, readTimeoutMs, c, target.host() + ":" + target.port(), cluster);
    }

    /** Hỏi sentinel địa chỉ master hiện tại. Sentinel có mật khẩu riêng thì đặt sentinel-password (mặc định thử không mật khẩu). */
    static RedisProfile resolveMaster(RedisProfile profile) throws IOException {
        RedisProfile sentinel = new RedisProfile(profile.name(), profile.host(), profile.port(), null,
                profile.sentinelPassword(), 0, profile.tls(), profile.readonly(), null, null);
        try (RespClient s = RespClient.connect(sentinel, 5000)) {
            Object reply = s.call("SENTINEL", "get-master-addr-by-name", profile.sentinelMaster());
            if (reply instanceof RespError e) {
                throw new IOException("sentinel " + profile.host() + ":" + profile.port() + ": " + e.message());
            }
            if (!(reply instanceof List<?> addr) || addr.size() != 2) {
                throw new IOException(I18n.t("redis.sentinel.unknownMaster", profile.sentinelMaster(),
                        profile.host() + ":" + profile.port()));
            }
            return profile.at(RedisSession.text(addr.get(0)), Integer.parseInt(RedisSession.text(addr.get(1))));
        }
    }

    boolean cluster() {
        return cluster;
    }

    /** host:port của node đang chạy lệnh. */
    String address() {
        return currentAddress;
    }

    /** "MOVED 3999 :6381" (host rỗng): cùng host với node đang nối. */
    private String normalize(String address) {
        return address.startsWith(":") ? currentAddress.substring(0, currentAddress.lastIndexOf(':')) + address : address;
    }

    private RespClient node(String address) throws IOException {
        RespClient c = nodes.get(address);
        if (c == null || c.isClosed()) {
            int colon = address.lastIndexOf(':');
            c = RespClient.connect(profile.at(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1))), timeoutMs);
            nodes.put(address, c);
        }
        return c;
    }

    /** Địa chỉ trong "MOVED 3999 127.0.0.1:6381" / "ASK ..."; null nếu không phải lỗi chuyển hướng. */
    static String redirect(Object reply, String kind) {
        if (reply instanceof RespError e && e.message().startsWith(kind + " ")) {
            String[] parts = e.message().split(" ");
            return parts.length >= 3 ? parts[2] : null;
        }
        return null;
    }

    Object call(String... args) throws IOException {
        List<byte[]> raw = new ArrayList<>(args.length);
        for (String a : args) {
            raw.add(a.getBytes(StandardCharsets.UTF_8));
        }
        return call(raw);
    }

    Object call(List<byte[]> args) throws IOException {
        Object reply = current.call(args);
        if (!cluster) {
            return reply;
        }
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            String moved = redirect(reply, "MOVED");
            String ask = moved == null ? redirect(reply, "ASK") : null;
            if (moved != null) {
                slots = null; // slot đã chuyển node: bản đồ cũ không còn đúng
                String address = normalize(moved);
                current = node(address);
                currentAddress = address;
                reply = current.call(args);
            } else if (ask != null) {
                RespClient n = node(normalize(ask));
                n.call("ASKING");
                reply = n.call(args);
            } else {
                break;
            }
        }
        return reply;
    }

    /**
     * Cluster: gom lệnh theo node giữ slot của key (tham số thứ 2 của mỗi lệnh - đúng với các lệnh 1 key mà \\dt, \\d
     * dùng), mỗi node 1 lượt pipeline. Bản đồ slot lấy từ CLUSTER SLOTS; reply nào vẫn là MOVED/ASK (slot vừa chuyển)
     * thì chạy lại riêng lệnh đó qua {@link #call} rồi nạp lại bản đồ.
     */
    List<Object> pipeline(List<List<String>> commands) throws IOException {
        if (!cluster) {
            return current.pipeline(commands);
        }
        String[] owners = slotOwners();
        Map<String, List<Integer>> byNode = new LinkedHashMap<>();
        for (int i = 0; i < commands.size(); i++) {
            List<String> c = commands.get(i);
            String node = c.size() > 1 && owners != null ? owners[slot(c.get(1))] : null;
            byNode.computeIfAbsent(node == null ? currentAddress : node, k -> new ArrayList<>()).add(i);
        }
        Object[] replies = new Object[commands.size()];
        boolean stale = false;
        for (var e : byNode.entrySet()) {
            List<List<String>> batch = e.getValue().stream().map(commands::get).toList();
            List<Object> r = node(e.getKey()).pipeline(batch);
            for (int j = 0; j < r.size(); j++) {
                int i = e.getValue().get(j);
                Object reply = r.get(j);
                if (redirect(reply, "MOVED") != null || redirect(reply, "ASK") != null) {
                    stale = true;
                    reply = call(commands.get(i).toArray(String[]::new));
                }
                replies[i] = reply;
            }
        }
        if (stale) {
            slots = null;
        }
        return java.util.Arrays.asList(replies);
    }

    /** Node giữ từng slot (16384 phần tử), null nếu không lấy được. */
    private String[] slots;

    private String[] slotOwners() throws IOException {
        if (slots != null) {
            return slots;
        }
        if (!(call("CLUSTER", "SLOTS") instanceof List<?> ranges)) {
            return null;
        }
        String[] owners = new String[16384];
        for (Object range : ranges) {
            if (!(range instanceof List<?> r) || r.size() < 3 || !(r.get(2) instanceof List<?> master) || master.size() < 2) {
                continue;
            }
            String address = normalize(RedisSession.text(master.get(0)) + ":" + RedisSession.text(master.get(1)));
            for (long s = (Long) r.get(0); s <= (Long) r.get(1); s++) {
                owners[(int) s] = address;
            }
        }
        slots = owners;
        return owners;
    }

    /** Slot của key như Redis: CRC16 (XMODEM) mod 16384, chỉ tính phần trong {...} đầu tiên nếu khác rỗng. */
    static int slot(String key) {
        byte[] b = key.getBytes(StandardCharsets.UTF_8);
        int from = 0;
        int to = b.length;
        int open = indexOf(b, (byte) '{', 0);
        if (open >= 0) {
            int close = indexOf(b, (byte) '}', open + 1);
            if (close > open + 1) {
                from = open + 1;
                to = close;
            }
        }
        int crc = 0;
        for (int i = from; i < to; i++) {
            crc ^= (b[i] & 0xff) << 8;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
        }
        return (crc & 0xffff) % 16384;
    }

    private static int indexOf(byte[] b, byte x, int from) {
        for (int i = from; i < b.length; i++) {
            if (b[i] == x) {
                return i;
            }
        }
        return -1;
    }

    void send(List<byte[]> args) throws IOException {
        current.send(args);
    }

    Object read() throws IOException {
        return current.read();
    }

    @Override
    public void close() {
        nodes.values().forEach(RespClient::close);
    }

    boolean isClosed() {
        return current.isClosed();
    }

    /** Địa chỉ các master đang giữ slot, theo CLUSTER NODES ("id ip:port@bus[,hostname] flags master - ... slots"). */
    List<String> masters() throws IOException {
        List<String> out = new ArrayList<>();
        if (!(call("CLUSTER", "NODES") instanceof RespClient.Bulk b)) {
            return List.of(currentAddress);
        }
        for (String line : b.utf8().split("\n")) {
            String[] f = line.strip().split(" ");
            if (f.length < 9 || !f[2].contains("master") || f[2].contains("fail")) {
                continue;
            }
            out.add(normalize(f[1].split("@")[0].split(",")[0]));
        }
        return out.isEmpty() ? List.of(currentAddress) : out;
    }

    record ScanResult(List<String> keys, boolean complete, String error) {
    }

    /**
     * SCAN MATCH tới khi đủ {@code limit} key, hết keyspace hoặc quá {@code deadline} (System.currentTimeMillis();
     * 0 = không giới hạn). Cluster: lần lượt từng master. {@code type} null = mọi kiểu.
     */
    ScanResult scan(String match, String type, int limit, long deadline) throws IOException {
        TreeSet<String> found = new TreeSet<>();
        List<RespClient> targets = new ArrayList<>();
        if (cluster) {
            for (String m : masters()) {
                targets.add(node(m));
            }
        } else {
            targets.add(current);
        }
        for (RespClient target : targets) {
            String cursor = "0";
            do {
                if (deadline > 0 && System.currentTimeMillis() > deadline) {
                    return new ScanResult(List.copyOf(found), false, null);
                }
                List<String> args = new ArrayList<>(List.of("SCAN", cursor, "MATCH", match, "COUNT", "1000"));
                if (type != null) {
                    args.add("TYPE");
                    args.add(type);
                }
                Object reply = target.call(args.stream().map(s -> s.getBytes(StandardCharsets.UTF_8)).toList());
                if (reply instanceof RespError e) {
                    return new ScanResult(List.copyOf(found), false, e.message());
                }
                if (!(reply instanceof List<?> page) || page.size() != 2) {
                    return new ScanResult(List.copyOf(found), false, null);
                }
                cursor = RedisSession.text(page.get(0));
                if (page.get(1) instanceof List<?> keys) {
                    for (Object k : keys) {
                        if (found.size() >= limit) {
                            return new ScanResult(List.copyOf(found), false, null);
                        }
                        found.add(RedisSession.text(k));
                    }
                }
            } while (!"0".equals(cursor));
        }
        return new ScanResult(List.copyOf(found), true, null);
    }
}
