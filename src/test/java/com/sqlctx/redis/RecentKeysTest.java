package com.sqlctx.redis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecentKeysTest {

    private static final RedisSyntax.Role C = RedisSyntax.Role.COMMAND;
    private static final RedisSyntax.Role K = RedisSyntax.Role.KEY;
    private static final RedisSyntax.Role V = RedisSyntax.Role.VALUE;

    private static RespClient.Bulk bulk(String s) {
        return new RespClient.Bulk(s.getBytes());
    }

    @Test
    void keyGầnĐâyLênĐầuMớiNhấtTrước() {
        RecentKeys r = new RecentKeys();
        r.learn(List.of("GET", "user:2"), List.of(C, K), "string", bulk("x"));
        r.learn(List.of("HGETALL", "user:9"), List.of(C, K), "hash", List.of());
        assertEquals(List.of("user:9", "user:2", "user:1", "user:3"), r.merge("user:", null, List.of("user:1", "user:2", "user:3")));
        // dùng lại user:2 thì nó thành mới nhất
        r.learn(List.of("GET", "user:2"), List.of(C, K), "string", bulk("x"));
        assertEquals(List.of("user:2", "user:9"), r.merge("user:", null, List.of()));
        assertEquals(List.of("user:9"), r.merge("user:9", null, List.of()));
    }

    @Test
    void lọcTheoKiểu() {
        RecentKeys r = new RecentKeys();
        r.learn(List.of("GET", "s"), List.of(C, K), "string", bulk("x"));
        r.learn(List.of("HSET", "h", "f", "v"), List.of(C, K, V, V), "hash", 1L);
        r.learn(List.of("EXPIRE", "u", "10"), List.of(C, K, V), null, 1L); // chưa biết kiểu
        // gợi ý cho HGET: chỉ key hash; key chưa rõ kiểu chỉ khi SCAN TYPE hash cũng thấy
        assertEquals(List.of("h"), r.merge("", "hash", List.of()));
        assertEquals(List.of("u", "h", "z"), r.merge("", "hash", List.of("u", "z")));
        // TYPE cho biết kiểu
        r.learn(List.of("TYPE", "u"), List.of(C, K), null, new RespClient.Simple("hash"));
        assertEquals(List.of("u", "h"), r.merge("", "hash", List.of()));
    }

    @Test
    void họcTừKếtQuảVàQuênKhiXoá() {
        RecentKeys r = new RecentKeys();
        r.learn(List.of("SCAN", "0", "MATCH", "o*", "TYPE", "zset"), List.of(C, V, V, V, V, V), null,
                List.of(bulk("0"), List.of(bulk("orders"), bulk("offers"))));
        assertEquals(List.of("offers", "orders"), r.merge("o", "zset", List.of()));
        r.learn(List.of("KEYS", "p*"), List.of(C, V), null, List.of(bulk("p1")));
        r.learn(List.of("DEL", "orders"), List.of(C, K), null, 1L);
        assertEquals(List.of("p1", "offers"), r.merge("", null, List.of()));
        r.learn(List.of("RENAME", "offers", "deals"), List.of(C, K, K), null, new RespClient.Simple("OK"));
        assertEquals(List.of("deals"), r.merge("", "zset", List.of()));
        // GET key không tồn tại (nil): không nhớ
        r.learn(List.of("GET", "typo"), List.of(C, K), "string", null);
        assertEquals(List.of(), r.merge("typo", null, List.of()));
        r.learn(List.of("FLUSHDB"), List.of(C), null, new RespClient.Simple("OK"));
        assertEquals(List.of(), r.merge("", null, List.of()));
    }
}
