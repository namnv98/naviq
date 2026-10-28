package com.sqlctx.redis;

import com.sqlctx.util.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisStatsTest {

    @BeforeEach
    void english() {
        I18n.set(I18n.Lang.EN, false);
    }

    @AfterEach
    void vietnamese() {
        I18n.set(I18n.Lang.VI, false);
    }

    private static Map<String, String> info(String text) {
        return RedisStats.parseInfo(text);
    }

    private static final String HEALTHY = """
            # Memory
            used_memory:1048576
            used_memory_human:1.00M
            maxmemory:104857600
            maxmemory_human:100.00M
            mem_fragmentation_ratio:1.10
            # Stats
            instantaneous_ops_per_sec:120
            keyspace_hits:90
            keyspace_misses:10
            evicted_keys:0
            rejected_connections:0
            # Persistence
            rdb_last_bgsave_status:ok
            # Keyspace
            db0:keys=7,expires=1,avg_ttl=0
            db3:keys=5,expires=0,avg_ttl=0
            """;

    @Test
    void đọcInfo() {
        Map<String, String> i = info(HEALTHY);
        assertEquals(12, RedisStats.keys(i));
        assertEquals("90.0", RedisStats.hitRate(i));
        assertEquals("-", RedisStats.hitRate(Map.of()));
        assertEquals(List.of("10.0.0.2:6380"), RedisStats.replicaAddresses(
                info("role:master\nconnected_slaves:1\nslave0:ip=10.0.0.2,port=6380,state=online,offset=1,lag=0\n")));
    }

    @Test
    void khôngCảnhBáoKhiỔn() {
        var s = new RedisStats.Sample(0, List.of(new RedisStats.Node("a:1", true, 1, info(HEALTHY), null)), null, List.of());
        assertEquals(List.of(), RedisStats.warnings(s));
        List<String> frame = RedisStats.frame("title", List.of(s), 200);
        assertTrue(frame.stream().anyMatch(l -> l.contains("1.00M / 100.00M") && l.contains("90.0")), frame.toString());
        assertTrue(frame.get(frame.size() - 1).contains("no warnings"), frame.toString());
    }

    @Test
    void cảnhBáo() {
        String full = HEALTHY.replace("used_memory:1048576", "used_memory:99000000").replace("evicted_keys:0", "evicted_keys:42")
                .replace("rdb_last_bgsave_status:ok", "rdb_last_bgsave_status:err");
        var s = new RedisStats.Sample(0, List.of(
                new RedisStats.Node("m:1", true, 0, info(full), null),
                new RedisStats.Node("r:2", false, -1, info("role:slave\nmaster_link_status:down\nmaster_host:m\nmaster_port:1\nmaxmemory:0\n"), null),
                new RedisStats.Node("x:3", true, 1, Map.of(), "Connection refused")), "fail", List.of("y:4"));
        String all = String.join("\n", RedisStats.warnings(s));
        for (String expected : List.of("cluster_state: fail", "failed nodes: y:4", "m:1: 94% of maxmemory",
                "m:1: last RDB save failed", "r:2: lost the link to master m:1", "x:3: cannot get INFO", "maxmemory not set",
                "without a replica (losing a node loses data): m:1")) {
            assertTrue(all.contains(expected), expected + "\n" + all);
        }
        assertTrue(all.indexOf("✖") < all.indexOf("⚠"), "lỗi đỏ đứng trước cảnh báo vàng");
        assertFalse(all.contains("evict"), "evicted_keys cộng dồn từ lúc khởi động: 1 mẫu chưa nói được là đang evict\n" + all);
    }

    private static RedisStats.Sample one(long at, String info) {
        return new RedisStats.Sample(at, List.of(new RedisStats.Node("a:1", true, 1, info(info), null)), null, List.of());
    }

    @Test
    void cảnhBáoTheoDiễnBiến() {
        String base = HEALTHY + "run_id:r1\n";
        // evicted_keys 42 từ trước, không tăng thêm: không báo
        String old = String.join("\n", RedisStats.warnings(List.of(one(0, base.replace("evicted_keys:0", "evicted_keys:42")),
                one(30_000, base.replace("evicted_keys:0", "evicted_keys:42")))));
        assertFalse(old.contains("evict"), old);
        // tăng 60 trong 30 giây: đang evict
        String now = String.join("\n", RedisStats.warnings(List.of(one(0, base.replace("evicted_keys:0", "evicted_keys:42")),
                one(30_000, base.replace("evicted_keys:0", "evicted_keys:102").replace("rejected_connections:0", "rejected_connections:3")))));
        assertTrue(now.contains("a:1: evicting - 60 keys in the last 30s (2/s)"), now);
        assertTrue(now.contains("a:1: rejected 3 connections in the last 30s"), now);
        // khởi động lại (run_id đổi): bộ đếm về 0, không tính là âm hay tăng
        String restarted = String.join("\n", RedisStats.warnings(List.of(one(0, base.replace("evicted_keys:0", "evicted_keys:42")),
                one(30_000, base.replace("run_id:r1", "run_id:r2").replace("evicted_keys:0", "evicted_keys:5")))));
        assertFalse(restarted.contains("evict"), restarted);
    }

    @Test
    void lệnhChậmTừSlowlog() {
        var slow1 = List.of(new RedisStats.Slow(7, 100, 12_000, "KEYS *"));
        var slow2 = List.of(new RedisStats.Slow(9, 160, 250_000, "HGETALL big"), new RedisStats.Slow(8, 150, 15_000, "SMEMBERS s"),
                new RedisStats.Slow(7, 100, 12_000, "KEYS *"));
        String i = HEALTHY + "run_id:r1\n";
        var h = List.of(new RedisStats.Sample(0, List.of(new RedisStats.Node("a:1", true, 1, info(i), null, slow1)), null, List.of()),
                new RedisStats.Sample(20_000, List.of(new RedisStats.Node("a:1", true, 1, info(i), null, slow2)), null, List.of()));
        String all = String.join("\n", RedisStats.warnings(h));
        assertTrue(all.contains("a:1: 2 slow commands in the last 20s, slowest 250.0ms: HGETALL big"), all);
        assertEquals(List.of(new RedisStats.Slow(3, 1700000000, 15000, "GET k")), RedisStats.parseSlowlog(List.of(
                List.of(3L, 1700000000L, 15000L, List.of(new RespClient.Bulk("GET".getBytes()), new RespClient.Bulk("k".getBytes())),
                        new RespClient.Bulk("127.0.0.1:5000".getBytes()), new RespClient.Bulk("".getBytes())))));
        assertEquals(List.of(), RedisStats.parseSlowlog(new RespClient.RespError("NOPERM")));
    }

    /** Mẫu lúc {@code realMs} (đồng hồ máy này), server báo giờ {@code serverMs}. */
    private static RedisStats.Sample clocked(long realMs, long serverMs) {
        return new RedisStats.Sample(realMs, List.of(new RedisStats.Node("a:1", true, 1,
                info(HEALTHY + "run_id:r1\nserver_time_usec:" + serverMs * 1000 + "\n"), null, List.of(),
                new RedisStats.Clock(realMs, realMs * 1_000_000))), null, List.of());
    }

    @Test
    void đồngHồLệchVàNhảy() {
        long t = 1_800_000_000_000L;
        // khớp giờ, chạy đều: không báo
        assertEquals(List.of(), RedisStats.warnings(List.of(clocked(t, t + 300), clocked(t + 2000, t + 2300))));
        // lệch đều 5 phút: chỉ báo lệch
        String skew = String.join("\n", RedisStats.warnings(List.of(clocked(t, t + 312_000), clocked(t + 2000, t + 314_000))));
        assertTrue(skew.contains("a:1: clock is +5m12s off from this machine"), skew);
        assertFalse(skew.contains("jumped"), skew);
        // 2 giây thật trôi qua mà đồng hồ server tăng 22 giây: nhảy +20s
        String jump = String.join("\n", RedisStats.warnings(List.of(clocked(t, t), clocked(t + 2000, t + 22_000), clocked(t + 4000, t + 24_000))));
        assertTrue(jump.contains("✖ a:1: clock jumped +20s in the last minute"), jump);
        // hơn 1 phút sau thì thôi báo nhảy, còn lại báo lệch
        String later = String.join("\n", RedisStats.warnings(List.of(clocked(t, t), clocked(t + 2000, t + 22_000), clocked(t + 70_000, t + 90_000))));
        assertFalse(later.contains("jumped"), later);
        assertTrue(later.contains("clock is +20s off"), later);
        assertEquals("-3.0s", RedisStats.signed(-3000));
    }

    private static final long GB = 1L << 30;

    private static RedisStats.Node mem(String address, long rss, long max) {
        return new RedisStats.Node(address, true, 1, info("run_id:" + address + "\nused_memory:" + rss + "\nused_memory_rss:" + rss
                + "\nmaxmemory:" + max + "\ntotal_system_memory:" + 32 * GB + "\n"), null);
    }

    @Test
    void bộNhớTheoRamMáy() {
        // 3 node cùng máy, mỗi node 6 GB (19% RAM) - cộng lại 18 GB = 56%
        var three = new RedisStats.Sample(0, List.of(mem("10.0.0.1:7001", 6 * GB, 0), mem("10.0.0.1:7002", 6 * GB, 0),
                mem("10.0.0.1:7003", 6 * GB, 0)), null, List.of());
        String all = String.join("\n", RedisStats.warnings(three));
        assertTrue(all.contains("✖ host 10.0.0.1: Redis uses 18.0 GB / host RAM 32.0 GB (56%) without maxmemory: 10.0.0.1:7001, 10.0.0.1:7002, 10.0.0.1:7003"), all);
        // cùng lượng đó nhưng ở 3 máy khác nhau: không máy nào quá 50%
        var spread = new RedisStats.Sample(0, List.of(mem("10.0.0.1:7001", 6 * GB, 0), mem("10.0.0.2:7001", 6 * GB, 0),
                mem("10.0.0.3:7001", 6 * GB, 0)), null, List.of());
        assertFalse(String.join("\n", RedisStats.warnings(spread)).contains("host RAM"));
        // 9 GB và tăng 40 MB/s: còn 23 GB, khoảng 9m49s nữa chạm RAM máy
        List<RedisStats.Sample> growing = new java.util.ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            long rss = 9 * GB - (10 - i) * 2 * 40L * 1024 * 1024;
            growing.add(new RedisStats.Sample(i * 2000L, List.of(mem("127.0.0.1:7001", rss, 0)), null, List.of()));
        }
        String runaway = String.join("\n", RedisStats.warnings(growing));
        assertTrue(runaway.contains("✖ host 127.0.0.1: Redis uses 9.0 GB / host RAM 32.0 GB (28%), growing 40.0 MB/s - hits host RAM in about 9m48s"), runaway);
        assertFalse(String.join("\n", RedisStats.warnings(growing.subList(0, 4))).contains("growing"), "chưa đủ 10 giây quan sát");
        // có maxmemory hết nhưng cộng lại vượt RAM máy
        var over = new RedisStats.Sample(0, List.of(mem("10.0.0.1:7001", GB, 20 * GB), mem("10.0.0.1:7002", GB, 20 * GB)), null, List.of());
        String o = String.join("\n", RedisStats.warnings(over));
        assertTrue(o.contains("⚠ host 10.0.0.1: total maxmemory 40.0 GB exceeds host RAM 32.0 GB"), o);
    }

    @Test
    void replicaChậmHơnMaster() {
        java.util.function.LongFunction<RedisStats.Sample> at = off -> new RedisStats.Sample(off * 1000, List.of(
                new RedisStats.Node("m:1", true, 1, info("role:master\nrun_id:m\nmaster_repl_offset:" + (10_000_000 + off * 1_000_000)
                        + "\nslave0:ip=r,port=2,state=online,offset=" + (10_000_000 + off * 500_000) + ",lag=" + (off == 6 ? 12 : 0) + "\n"), null),
                new RedisStats.Node("r:2", false, -1, info("role:slave\nrun_id:r\nmaster_host:m\nmaster_port:1\nmaster_link_status:up\n"), null)),
                null, List.of());
        assertEquals(new RedisStats.ReplLag(500_000, 0), RedisStats.replicaLag(at.apply(1), at.apply(1).nodes().get(1)));
        List<RedisStats.Sample> h = new java.util.ArrayList<>();
        for (long i = 0; i <= 5; i++) {
            h.add(at.apply(i));
        }
        String behind = String.join("\n", RedisStats.warnings(h));
        assertTrue(behind.contains("r:2: replica is falling behind - 2.4 MB behind the master, growing for 5s"), behind);
        h.add(at.apply(6));
        String ack = String.join("\n", RedisStats.warnings(h));
        assertTrue(ack.contains("r:2: replica has not acked the master for 12s"), ack);
    }
}
