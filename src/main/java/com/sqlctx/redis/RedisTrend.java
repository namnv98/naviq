package com.sqlctx.redis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bộ đếm của INFO (keyspace_hits, evicted_keys, rejected_connections, used_cpu_*...) cộng dồn từ lúc server khởi động:
 * evict 1 key từ 3 tháng trước vẫn &gt; 0, hit % pha loãng bởi mọi lần hit từ trước. Ở đây tính chênh lệch giữa các mẫu
 * của \stats như redis-stat/rrtop: {@link #delta} trong 1 khoảng gần đây, {@link #series} từng khoảng giữa 2 mẫu liền
 * nhau. Server khởi động lại (run_id đổi hoặc bộ đếm tụt) thì chỉ tính từ sau lúc đó.
 */
final class RedisTrend {

    /** Khoảng xét cho cảnh báo và hit % (đủ dài để bớt nhiễu, đủ ngắn để là "đang xảy ra"). */
    static final long WINDOW_MS = 60_000;

    /** {@code value} trong {@code ms} mili giây. */
    record Delta(double value, long ms) {
        double perSecond() {
            return ms <= 0 ? 0 : value * 1000 / ms;
        }
    }

    private final List<RedisStats.Sample> h;
    private final List<Map<String, RedisStats.Node>> nodes = new ArrayList<>();

    RedisTrend(List<RedisStats.Sample> history) {
        this.h = history;
        for (RedisStats.Sample s : history) {
            Map<String, RedisStats.Node> m = new HashMap<>();
            s.nodes().stream().filter(n -> n.error() == null).forEach(n -> m.put(n.address(), n));
            nodes.add(m);
        }
    }

    private RedisStats.Node node(int i, String address) {
        return nodes.get(i).get(address);
    }

    static double num(Map<String, String> info, String key) {
        try {
            return Double.parseDouble(info.getOrDefault(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Cùng 1 lần chạy của server: run_id không đổi và bộ đếm không tụt. */
    private static boolean sameRun(RedisStats.Node before, RedisStats.Node after, String key) {
        return before != null && after != null && before.info().getOrDefault("run_id", "").equals(after.info().getOrDefault("run_id", ""))
                && num(before.info(), key) <= num(after.info(), key);
    }

    /**
     * Bộ đếm {@code key} của node tăng bao nhiêu từ mẫu đầu tiên cách hiện tại ít nhất {@code windowMs} (hoặc mẫu cũ nhất
     * còn cùng lần chạy) tới mẫu hiện tại; {@code windowMs} 0 = chỉ so với mẫu liền trước. Null nếu chưa có 2 mẫu.
     */
    Delta delta(String address, String key, long windowMs) {
        int last = h.size() - 1;
        RedisStats.Node cur = last < 0 ? null : node(last, address);
        if (cur == null) {
            return null;
        }
        long now = h.get(last).at();
        int from = last;
        for (int i = last - 1; i >= 0 && sameRun(node(i, address), node(i + 1, address), key); i--) {
            from = i;
            if (h.get(i).at() <= now - windowMs) {
                break;
            }
        }
        if (from == last) {
            return null;
        }
        return new Delta(num(cur.info(), key) - num(node(from, address).info(), key), now - h.get(from).at());
    }

    /** Tổng {@link #delta} của mọi node sống ở mẫu hiện tại; null nếu chưa node nào có 2 mẫu. */
    Delta total(String key, long windowMs) {
        if (h.isEmpty()) {
            return null;
        }
        double value = 0;
        long ms = 0;
        boolean any = false;
        for (String address : nodes.get(h.size() - 1).keySet()) {
            Delta d = delta(address, key, windowMs);
            if (d != null) {
                value += d.value();
                ms = Math.max(ms, d.ms());
                any = true;
            }
        }
        return any ? new Delta(value, ms) : null;
    }

    /**
     * Tốc độ thay đổi (đơn vị/giây) của 1 giá trị tức thời như used_memory_rss: từ mẫu đầu tiên cách hiện tại ít nhất
     * {@code windowMs} (hoặc mẫu cũ nhất cùng lần chạy) tới hiện tại - lên xuống ở giữa không cắt khoảng như bộ đếm.
     * Null nếu khoảng quan sát ngắn hơn {@code minMs}.
     */
    Double gaugeRate(String address, String key, long windowMs, long minMs) {
        int last = h.size() - 1;
        RedisStats.Node cur = last < 0 ? null : node(last, address);
        if (cur == null) {
            return null;
        }
        long now = h.get(last).at();
        int from = last;
        for (int i = last - 1; i >= 0; i--) {
            RedisStats.Node n = node(i, address);
            if (n == null || !n.info().getOrDefault("run_id", "").equals(cur.info().getOrDefault("run_id", ""))) {
                break;
            }
            from = i;
            if (h.get(i).at() <= now - windowMs) {
                break;
            }
        }
        long ms = now - h.get(from).at();
        if (ms < minMs) {
            return null;
        }
        return (num(cur.info(), key) - num(node(from, address).info(), key)) * 1000 / ms;
    }

    /** Hit % trong khoảng (null: chưa đủ mẫu; NaN: không có lần đọc key nào); {@code address} null = cả cụm. */
    Double hitRate(String address, long windowMs) {
        Delta hits = address == null ? total("keyspace_hits", windowMs) : delta(address, "keyspace_hits", windowMs);
        Delta misses = address == null ? total("keyspace_misses", windowMs) : delta(address, "keyspace_misses", windowMs);
        if (hits == null || misses == null) {
            return null;
        }
        double all = hits.value() + misses.value();
        return all == 0 ? Double.NaN : 100 * hits.value() / all;
    }

    /** % CPU (user + sys, 100% = 1 lõi) giữa 2 mẫu gần nhất; null nếu chưa đủ mẫu. */
    Double cpu(String address) {
        Delta sys = delta(address, "used_cpu_sys", 0);
        Delta user = delta(address, "used_cpu_user", 0);
        return sys == null || user == null || sys.ms() == 0 ? null : 100 * (sys.value() + user.value()) * 1000 / sys.ms();
    }

    /** Mỗi khoảng giữa 2 mẫu liền nhau: tốc độ tăng/giây của bộ đếm, cộng mọi node (hoặc chỉ {@code address}). */
    List<Double> series(String address, String key) {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < h.size(); i++) {
            double sum = 0;
            for (RedisStats.Node n : nodes.get(i).values()) {
                RedisStats.Node before = node(i - 1, n.address());
                if ((address == null || n.address().equals(address)) && sameRun(before, n, key)) {
                    sum += num(n.info(), key) - num(before.info(), key);
                }
            }
            out.add(sum * 1000 / Math.max(1, h.get(i).at() - h.get(i - 1).at()));
        }
        return out;
    }

    /** Đồng hồ node lệch so với máy này quá ngần này thì báo (TTL tuyệt đối, giờ trong slowlog sai). */
    static final long SKEW_MS = 2000;
    /** Giữa 2 mẫu, đồng hồ node chạy khác máy này quá ngần này là vừa bị nhảy (NTP chỉnh bước, ai đó đổi giờ...). */
    static final long JUMP_MS = 1000;

    private RedisStats.Clock clock(int i, RedisStats.Node n) {
        return n.clock() != null ? n.clock() : new RedisStats.Clock(h.get(i).at(), h.get(i).at() * 1_000_000);
    }

    /** Giờ server trừ giờ máy này lúc nhận INFO (ms); null nếu server không báo server_time_usec (Redis &lt; 7). */
    Long skewMs(String address) {
        int last = h.size() - 1;
        RedisStats.Node n = last < 0 ? null : node(last, address);
        if (n == null || !n.info().containsKey("server_time_usec")) {
            return null;
        }
        return (long) num(n.info(), "server_time_usec") / 1000 - clock(last, n).wallMs();
    }

    /**
     * Lần nhảy đồng hồ lớn nhất của node trong khoảng gần đây (ms, dương = nhảy tới): giữa 2 mẫu liền nhau cùng lần chạy,
     * server_time_usec tăng khác khoảng thời gian thật trôi qua (đồng hồ đơn điệu của máy này) quá {@link #JUMP_MS}.
     * Key hết hạn theo đồng hồ của node, nên nhảy tới làm lock/TTL hết hạn sớm. Null nếu không thấy.
     */
    Long clockJumpMs(String address, long windowMs) {
        int last = h.size() - 1;
        if (last < 1) {
            return null;
        }
        long now = h.get(last).at();
        Long worst = null;
        for (int i = last; i >= 1 && h.get(i).at() >= now - windowMs; i--) {
            RedisStats.Node a = node(i - 1, address);
            RedisStats.Node b = node(i, address);
            if (a == null || b == null || !a.info().containsKey("server_time_usec") || !b.info().containsKey("server_time_usec")
                    || !a.info().getOrDefault("run_id", "").equals(b.info().getOrDefault("run_id", ""))) {
                continue;
            }
            long server = (long) (num(b.info(), "server_time_usec") - num(a.info(), "server_time_usec")) / 1000;
            long real = (clock(i, b).monoNanos() - clock(i - 1, a).monoNanos()) / 1_000_000;
            long jump = server - real;
            if (Math.abs(jump) >= JUMP_MS && (worst == null || Math.abs(jump) > Math.abs(worst))) {
                worst = jump;
            }
        }
        return worst;
    }

    /** Có bao nhiêu lệnh chậm mới trong khoảng (theo id SLOWLOG) và lệnh chậm nhất trong số đó; null nếu không có. */
    record SlowSummary(long count, RedisStats.Slow slowest, long ms) {
    }

    SlowSummary slowlog(String address, long windowMs) {
        int last = h.size() - 1;
        RedisStats.Node cur = last < 0 ? null : node(last, address);
        if (cur == null || cur.slowlog().isEmpty()) {
            return null;
        }
        long now = h.get(last).at();
        int from = -1;
        for (int i = last - 1; i >= 0; i--) {
            RedisStats.Node n = node(i, address);
            if (n == null || !n.info().getOrDefault("run_id", "").equals(cur.info().getOrDefault("run_id", ""))) {
                break;
            }
            from = i;
            if (h.get(i).at() <= now - windowMs) {
                break;
            }
        }
        if (from < 0) {
            return null;
        }
        long before = maxId(node(from, address).slowlog());
        long count = maxId(cur.slowlog()) - before;
        if (count <= 0) {
            return null;
        }
        RedisStats.Slow slowest = cur.slowlog().stream().filter(s -> s.id() > before)
                .max(java.util.Comparator.comparingLong(RedisStats.Slow::micros)).orElse(null);
        return new SlowSummary(count, slowest, now - h.get(from).at());
    }

    private static long maxId(List<RedisStats.Slow> slow) {
        return slow.stream().mapToLong(RedisStats.Slow::id).max().orElse(-1);
    }

    /**
     * Replica chậm hơn master (byte) tăng liên tục qua ít nhất {@code minSamples} mẫu gần nhất: trả về {bytes hiện tại,
     * thời gian tăng (ms)}; không thì null. Dao động lên xuống quanh vài byte là bình thường (master ghi liên tục).
     */
    long[] replicaFallingBehind(String address, int minSamples) {
        List<Long> lag = new ArrayList<>();
        List<Long> at = new ArrayList<>();
        for (int i = h.size() - 1; i >= 0; i--) {
            RedisStats.Node n = node(i, address);
            RedisStats.ReplLag l = n == null ? null : RedisStats.replicaLag(h.get(i), n);
            if (l == null || !lag.isEmpty() && l.bytes() >= lag.get(lag.size() - 1)) {
                break;
            }
            lag.add(l.bytes());
            at.add(h.get(i).at());
        }
        if (lag.size() < minSamples) {
            return null;
        }
        return new long[]{lag.get(0), at.get(0) - at.get(at.size() - 1)};
    }
}
