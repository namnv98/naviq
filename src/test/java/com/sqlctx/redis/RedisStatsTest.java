package com.sqlctx.redis;

import com.sqlctx.util.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        var s = new RedisStats.Sample(List.of(new RedisStats.Node("a:1", true, 1, info(HEALTHY), null)), null, List.of());
        assertEquals(List.of(), RedisStats.warnings(s));
        List<String> frame = RedisStats.frame("title", s, 200);
        assertTrue(frame.stream().anyMatch(l -> l.contains("1.00M / 100.00M") && l.contains("90.0")), frame.toString());
        assertTrue(frame.get(frame.size() - 1).contains("no warnings"), frame.toString());
    }

    @Test
    void cảnhBáo() {
        String full = HEALTHY.replace("used_memory:1048576", "used_memory:99000000").replace("evicted_keys:0", "evicted_keys:42")
                .replace("rdb_last_bgsave_status:ok", "rdb_last_bgsave_status:err");
        var s = new RedisStats.Sample(List.of(
                new RedisStats.Node("m:1", true, 0, info(full), null),
                new RedisStats.Node("r:2", false, -1, info("role:slave\nmaster_link_status:down\nmaster_host:m\nmaster_port:1\nmaxmemory:0\n"), null),
                new RedisStats.Node("x:3", true, 1, Map.of(), "Connection refused")), "fail", List.of("y:4"));
        String all = String.join("\n", RedisStats.warnings(s));
        for (String expected : List.of("cluster_state: fail", "failed nodes: y:4", "m:1: 94% of maxmemory", "evicted 42",
                "m:1: last RDB save failed", "r:2: lost the link to master m:1", "x:3: cannot get INFO", "maxmemory not set",
                "without a replica (losing a node loses data): m:1")) {
            assertTrue(all.contains(expected), expected + "\n" + all);
        }
        assertTrue(all.indexOf("✖") < all.indexOf("⚠"), "lỗi đỏ đứng trước cảnh báo vàng");
    }
}
