package com.sqlctx.redis;

import com.sqlctx.redis.RespClient.Bulk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kết nối của 1 phiên Redis: kết nối chính chạy lệnh người dùng gõ, và kết nối phụ (timeout ngắn, mở khi cần)
 * chỉ để lấy dữ liệu gợi ý. Tách 2 kết nối vì kết nối chính có thể đang trong MULTI (SCAN gợi ý sẽ bị xếp
 * hàng vào transaction) hoặc đang chặn ở BLPOP/SUBSCRIBE.
 */
final class RedisSession implements RedisCompletion.Source {

    private static final long CACHE_MILLIS = 3000;
    private static final int MAX_SUGGESTIONS = 200;

    private RedisProfile profile;
    private RedisConnection main;
    private RedisConnection meta;
    volatile boolean running;
    volatile boolean cancelled;
    boolean inTransaction;

    private record Cached(long at, List<String> values) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private List<String> configParams;

    RedisSession(RedisProfile profile) throws IOException {
        this.profile = profile;
        this.main = RedisConnection.open(profile, 0);
    }

    /** Cây lệnh của server này (COMMAND DOCS, qua cache), trên 1 kết nối riêng để không chen vào kết nối chính. */
    RedisCommandTree loadCommands() {
        try (RespClient client = RespClient.connect(
                profile.sentinelMaster() != null ? RedisConnection.resolveMaster(profile) : profile, 5000)) {
            return RedisCommandTree.load(client, com.sqlctx.context.ConnectionProfileStore.configDir().resolve("redis-docs"));
        } catch (IOException e) {
            return RedisCommandTree.fallback();
        }
    }

    RedisProfile profile() {
        return profile;
    }

    RedisConnection main() {
        return main;
    }

    /** SELECT thành công trên kết nối chính: nhớ DB mới để kết nối lại/kết nối phụ dùng đúng DB. */
    void dbChanged(int db) {
        profile = profile.withDb(db);
        closeMeta();
        recentKeys.clear();
    }

    /** Sau Ctrl+C (socket bị đóng giữa lệnh) hoặc mất kết nối: nối lại cùng DB. Transaction đang dở coi như mất. */
    void reconnect() throws IOException {
        main.close();
        inTransaction = false;
        main = RedisConnection.open(profile, 0); // sentinel: hỏi lại master (có thể vừa failover)
    }

    /** Gọi từ thread xử lý Ctrl+C: đóng socket để lệnh đang chặn (BLPOP, SUBSCRIBE...) thoát ra ngay. */
    void cancelRunning() {
        cancelled = true;
        main.close();
    }

    void close() {
        main.close();
        closeMeta();
    }

    private synchronized void closeMeta() {
        if (meta != null) {
            meta.close();
            meta = null;
        }
        cache.clear();
        keyScans.clear();
    }

    /** Lỗi gì cũng trả null - gợi ý thiếu còn hơn làm vỡ ô nhập. */
    private synchronized Object metaCall(String... args) {
        try {
            if (meta == null || meta.isClosed()) {
                meta = RedisConnection.open(profile, 1500);
            }
            return meta.call(args);
        } catch (IOException e) {
            if (meta != null) {
                meta.close();
                meta = null;
            }
            return null;
        }
    }

    /** SCAN trên kết nối phụ (cluster: mọi master); lỗi thì null. */
    private synchronized RedisConnection.ScanResult metaScan(String match, String type, long deadline) {
        try {
            if (meta == null || meta.isClosed()) {
                meta = RedisConnection.open(profile, 1500);
            }
            return meta.scan(match, type, MAX_SUGGESTIONS, deadline);
        } catch (IOException e) {
            if (meta != null) {
                meta.close();
                meta = null;
            }
            return null;
        }
    }

    /** Kết quả SCAN theo "kiểu\0tiền tố"; {@code complete}: đã quét hết keyspace, không bị cắt ở MAX_SUGGESTIONS. */
    private record KeyScan(long at, List<String> keys, boolean complete) {
    }

    private final Map<String, KeyScan> keyScans = new ConcurrentHashMap<>();
    /** Key vừa dùng/vừa thấy ở DB hiện tại - RedisCli cho học sau mỗi lệnh, gợi ý key đưa chúng lên đầu. */
    final RecentKeys recentKeys = new RecentKeys();

    /**
     * Gợi ý gọi hàm này ở MỖI phím gõ (popup tự hiện): "u", "us", "use"... Đã quét trọn với 1 tiền tố ngắn hơn thì
     * chỉ lọc lại kết quả đó, không quét server nữa. Mỗi lần quét dừng sau 300ms dù chưa hết keyspace.
     */
    @Override
    public List<String> keys(String prefix, String type) {
        return recentKeys.merge(prefix, type, scanKeys(prefix, type));
    }

    private List<String> scanKeys(String prefix, String type) {
        long now = System.currentTimeMillis();
        String t = String.valueOf(type);
        for (int len = prefix.length(); len >= 0; len--) {
            KeyScan c = keyScans.get(t + "\0" + prefix.substring(0, len));
            if (c != null && now - c.at() < CACHE_MILLIS && (len == prefix.length() || c.complete())) {
                return len == prefix.length() ? c.keys() : c.keys().stream().filter(k -> k.startsWith(prefix)).toList();
            }
        }
        RedisConnection.ScanResult scan = metaScan(escapeGlob(prefix) + "*", type, now + 300);
        if (scan == null) {
            return List.of();
        }
        List<String> keys = scan.keys();
        boolean complete = scan.complete();
        keyScans.put(t + "\0" + prefix, new KeyScan(now, keys, complete));
        return keys;
    }

    @Override
    public List<String> members(String key, String type, String prefix) {
        String scan = switch (type) {
            case "hash" -> "HSCAN";
            case "set" -> "SSCAN";
            case "zset" -> "ZSCAN";
            default -> null;
        };
        if (scan == null) {
            return List.of();
        }
        boolean pairs = !scan.equals("SSCAN"); // HSCAN/ZSCAN trả field, value, field, value...
        return cached("m\0" + key + "\0" + prefix, () -> {
            Object reply = metaCall(scan, key, "0", "MATCH", escapeGlob(prefix) + "*", "COUNT", "500");
            TreeSet<String> found = new TreeSet<>();
            if (reply instanceof List<?> page && page.size() == 2 && page.get(1) instanceof List<?> items) {
                for (int i = 0; i < items.size(); i += pairs ? 2 : 1) {
                    found.add(text(items.get(i)));
                }
            }
            return List.copyOf(found);
        });
    }

    @Override
    public synchronized List<String> configParams() {
        if (configParams == null) {
            Object reply = metaCall("CONFIG", "GET", "*");
            if (!(reply instanceof List<?> items)) {
                return List.of(); // không có quyền CONFIG (ACL, dịch vụ cloud): thử lại lần sau cũng vậy, nhưng rẻ
            }
            TreeSet<String> names = new TreeSet<>();
            for (int i = 0; i < items.size(); i += 2) {
                names.add(text(items.get(i)));
            }
            configParams = List.copyOf(names);
        }
        return configParams;
    }

    @Override
    public List<String> channels() {
        return cached("c", () -> {
            Object reply = metaCall("PUBSUB", "CHANNELS");
            List<String> names = new ArrayList<>();
            if (reply instanceof List<?> items) {
                for (Object o : items) {
                    names.add(text(o));
                }
            }
            names.sort(null);
            return names;
        });
    }

    @Override
    public List<String> groups(String key) {
        if (key == null) {
            // XREADGROUP GROUP |: chưa biết stream nào - gom group của các stream (tối đa 50 stream)
            return cached("g\0*", () -> {
                TreeSet<String> all = new TreeSet<>();
                List<String> streams = keys("", "stream");
                for (String k : streams.subList(0, Math.min(50, streams.size()))) {
                    all.addAll(groups(k));
                }
                return List.copyOf(all);
            });
        }
        return cached("g\0" + key, () -> infoNames(metaCall("XINFO", "GROUPS", key)));
    }

    @Override
    public List<String> consumers(String key, String group) {
        return cached("c\0" + key + "\0" + group, () -> infoNames(metaCall("XINFO", "CONSUMERS", key, group)));
    }

    @Override
    public List<String> pendingIds(String key, String group) {
        return cached("p\0" + key + "\0" + group, () -> {
            List<String> ids = new ArrayList<>();
            // dạng chi tiết: [[id, consumer, idle, số lần giao], ...]
            if (metaCall("XPENDING", key, group, "-", "+", "50") instanceof List<?> entries) {
                for (Object e : entries) {
                    if (e instanceof List<?> entry && !entry.isEmpty()) {
                        ids.add(text(entry.get(0)));
                    }
                }
            }
            java.util.Collections.reverse(ids);
            return ids;
        });
    }

    @Override
    public List<String> entryIds(String key) {
        return cached("e\0" + key, () -> {
            List<String> ids = new ArrayList<>();
            if (metaCall("XREVRANGE", key, "+", "-", "COUNT", "20") instanceof List<?> entries) {
                for (Object e : entries) {
                    if (e instanceof List<?> entry && !entry.isEmpty()) {
                        ids.add(text(entry.get(0)));
                    }
                }
            }
            return ids;
        });
    }

    /** XINFO GROUPS/CONSUMERS: [[name, x, ...], ...] (mỗi phần tử là map phẳng) -> các giá trị "name". */
    private static List<String> infoNames(Object reply) {
        List<String> names = new ArrayList<>();
        if (reply instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof List<?> map) {
                    for (int i = 0; i + 1 < map.size(); i += 2) {
                        if ("name".equals(text(map.get(i)))) {
                            names.add(text(map.get(i + 1)));
                        }
                    }
                }
            }
        }
        names.sort(null);
        return names;
    }

    @Override
    public List<String> contexts() {
        return RedisProfiles.loadAll().stream().map(RedisProfile::name).toList();
    }

    private List<String> cached(String key, java.util.function.Supplier<List<String>> load) {
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < CACHE_MILLIS) {
            return c.values();
        }
        List<String> values = load.get();
        cache.put(key, new Cached(now, values));
        return values;
    }

    /** Ký tự đặc biệt của mẫu glob trong phần đã gõ phải thoát, không thì "user[1" làm SCAN MATCH lỗi/khớp sai. */
    static String escapeGlob(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static String text(Object o) {
        if (o instanceof Bulk b) {
            return b.utf8();
        }
        if (o instanceof RespClient.Simple s) {
            return s.text();
        }
        return String.valueOf(o);
    }
}
