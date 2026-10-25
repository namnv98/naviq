package com.sqlctx.redis;

import com.sqlctx.redis.RespClient.Bulk;
import com.sqlctx.redis.RespClient.RespError;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phần không cần server, và phần chạy với container của docker-compose.yml nếu đang bật:
 * {@code docker compose --profile redis-cluster --profile redis-sentinel up -d} (không có thì bỏ qua).
 */
class RedisConnectionTest {

    private static Bulk bulk(String s) {
        return new Bulk(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void địaChỉTrongLỗiChuyểnHướng() {
        assertEquals("127.0.0.1:7002", RedisConnection.redirect(new RespError("MOVED 3999 127.0.0.1:7002"), "MOVED"));
        assertEquals(":7003", RedisConnection.redirect(new RespError("ASK 12 :7003"), "ASK"));
        assertNull(RedisConnection.redirect(new RespError("MOVED 3999 127.0.0.1:7002"), "ASK"));
        assertNull(RedisConnection.redirect(new RespError("ERR wrong number of arguments"), "MOVED"));
        assertNull(RedisConnection.redirect(bulk("MOVED 1 x:1"), "MOVED"));
    }

    @Test
    void slotNhưRedis() {
        // giá trị lấy từ CLUSTER KEYSLOT của Redis 7.4
        assertEquals(12182, RedisConnection.slot("foo"));
        assertEquals(1649, RedisConnection.slot("user:1000"));
        assertEquals(RedisConnection.slot("bar"), RedisConnection.slot("{bar}x"));
        assertEquals(10595, RedisConnection.slot("{}x")); // {} rỗng: tính cả key
        assertEquals(15495, RedisConnection.slot("a"));
    }

    @Test
    void xuấtRaFileLấyGiáTrịThô() {
        var table = new RedisReplyRenderer.Table(List.of("field", "value"), List.of(List.of("a", "1")));
        assertEquals(table, RedisCli.exportTable(table, null));
        List<Object> reply = Arrays.asList(bulk("x"), 2L, null, List.of(bulk("n"), 3L));
        var t = RedisCli.exportTable(RedisReplyRenderer.render(List.of("LRANGE", "l", "0", "-1"), reply), reply);
        assertEquals(List.of("value"), t.headers());
        assertEquals(List.of(List.of("x"), List.of("2"), List.of("<null>"), List.of("[n, 3]")), t.rows());
        assertEquals(List.of(List.of("<null>")), RedisCli.exportTable(RedisReplyRenderer.render(List.of("GET", "k"), null), null).rows());
    }

    private static boolean reachable(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static RedisProfile profile(int port, String sentinelMaster) {
        return new RedisProfile("t", "127.0.0.1", port, null, "Redis123", 0, false, false, sentinelMaster, null);
    }

    @Test
    void clusterTheoMovedVàQuétMọiMaster() throws IOException {
        assumeTrue(reachable(7001), "không có Redis Cluster ở 7001");
        try (RedisConnection c = RedisConnection.open(profile(7001, null), 5000)) {
            assertTrue(c.cluster());
            assertEquals(3, c.masters().size(), c.masters().toString());
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 30; i++) { // 30 key rải khắp 3 node: phải có MOVED
                keys.add("it:conn:" + i);
                assertEquals(new RespClient.Simple("OK"), c.call("SET", "it:conn:" + i, String.valueOf(i)));
            }
            for (int i = 0; i < 30; i++) {
                assertEquals(bulk(String.valueOf(i)), c.call("GET", "it:conn:" + i));
            }
            var scan = c.scan("it:conn:*", null, 1000, 0);
            assertTrue(scan.complete());
            assertEquals(keys.stream().sorted().toList(), scan.keys());
            List<List<String>> dels = keys.stream().map(k -> List.of("DEL", k)).toList();
            assertTrue(c.pipeline(dels).stream().allMatch(r -> r.equals(1L)));
        }
    }

    @Test
    void sentinelChỉRaMaster() throws IOException {
        assumeTrue(reachable(26390), "không có Sentinel ở 26390");
        RedisProfile target = RedisConnection.resolveMaster(profile(26390, "mymaster"));
        assertTrue(target.port() == 6390 || target.port() == 6391, target.toString()); // sau failover là 6391
        assertNull(target.sentinelMaster());
        try (RedisConnection c = RedisConnection.open(profile(26390, "mymaster"), 5000)) {
            assertTrue(((Bulk) c.call("INFO", "replication")).utf8().contains("role:master"));
        }
        try {
            RedisConnection.resolveMaster(profile(26390, "không-có"));
            throw new AssertionError("phải lỗi");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("không-có"), expected.getMessage());
        }
    }
}
