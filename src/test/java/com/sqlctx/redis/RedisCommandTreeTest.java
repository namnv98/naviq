package com.sqlctx.redis;

import com.sqlctx.redis.RespClient.Bulk;
import com.sqlctx.redis.RespClient.Simple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reply RESP của COMMAND DOCS / COMMAND INFO -> cây lệnh, như lúc kết nối server thật (không cần server). */
class RedisCommandTreeTest {

    @AfterEach
    void trảLạiCâyMặcĐịnh() {
        RedisCommandTree.use(null);
    }

    private static Bulk b(String s) {
        return new Bulk(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Server giả chỉ có 1 lệnh: MYSET key value [NX | XX] [TTL seconds] - đúng dạng mảng phẳng của RESP2. */
    private static RedisCommandTree treeFromResp() {
        List<Object> nx = List.of(b("name"), b("nx"), b("type"), b("pure-token"), b("token"), b("NX"));
        List<Object> xx = List.of(b("name"), b("xx"), b("type"), b("pure-token"), b("token"), b("XX"));
        List<Object> docs = List.of(b("myset"), List.of(
                b("summary"), b("Sets a value"), b("group"), b("string"),
                b("arguments"), List.of(
                        List.of(b("name"), b("key"), b("type"), b("key")),
                        List.of(b("name"), b("value"), b("type"), b("string")),
                        List.of(b("name"), b("condition"), b("type"), b("oneof"), b("flags"), List.of(new Simple("optional")),
                                b("arguments"), List.of(nx, xx)),
                        List.of(b("name"), b("seconds"), b("type"), b("integer"), b("token"), b("TTL"),
                                b("flags"), List.of(new Simple("optional"))))));
        List<Object> info = List.of(List.of(b("myset"), -3L, List.of(new Simple("write")), 1L, 1L, 1L,
                List.of(), List.of(), List.of(), List.of()));
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("docs", RedisCommandTree.docsToJson(docs));
        root.add("info", RedisCommandTree.generic(info));
        return RedisCommandTree.fromJson(root, "test");
    }

    @Test
    void lệnhCủaServerĐượcGợiÝVàKiểmTra() {
        RedisCommandTree.use(treeFromResp());
        assertEquals(List.of("MYSET"), RedisSyntax.analyze("").keywords());
        assertEquals(java.util.Set.of("key"), RedisSyntax.analyze("MYSET ").argRules());
        assertEquals(List.of("NX", "TTL", "XX"), RedisSyntax.analyze("MYSET k v ").keywords());
        assertNull(RedisSyntax.inspect("MYSET k v TTL 5 NX").error(), "tuỳ chọn từ khoá: thứ tự bất kỳ");
        assertEquals("không đúng cú pháp tại 'abc'", RedisSyntax.inspect("MYSET k v TTL abc").error());
    }

    @Test
    void cờVàCúPhápLấyTừServer() {
        RedisCommandTree.use(treeFromResp());
        RedisCommandSpec spec = RedisCommandSpec.find("MYSET");
        assertTrue(spec.isWrite());
        assertEquals("MYSET key value [NX | XX] [TTL seconds]", spec.syntax());
        assertEquals("string", spec.keyType());
    }

    @Test
    void lệnhServerKhôngCóThìKhôngGợiÝ() {
        RedisCommandTree.use(treeFromResp());
        assertEquals(List.of(), RedisSyntax.analyze("GET ").keywords());
        assertNull(RedisSyntax.inspect("GET a b c").error(), "lệnh lạ: không báo lỗi");
    }

    @Test
    void bảnĐóngGóiCóĐủLệnhVàLệnhCon() {
        RedisCommandTree fallback = RedisCommandTree.fallback();
        assertTrue(fallback.commands().size() >= 250, fallback.commands().size() + " lệnh");
        assertTrue(fallback.command("CONFIG").subcommands().containsKey("SET"));
        assertTrue(fallback.source().startsWith("valkey"));
    }

    @Test
    void serverCũBỏTuỳChọnMớiHơnServer() {
        RedisCommandTree.Command set = RedisCommandTree.fallback().command("SET");
        RedisCommandTree.use(RedisCommandTree.fallback());
        String all = String.join(" ", RedisSyntax.analyze("SET k v ").keywords());
        assertTrue(all.contains("IFEQ") && all.contains("GET"), all);

        RedisCommandTree.Command old = RedisCommandTree.olderThan(set, "6.0.0");
        String syntax = RedisCommandSpec.of(old).syntax();
        assertTrue(!syntax.contains("IFEQ") && !syntax.contains("GET") && !syntax.contains("EXAT"), syntax);
        assertTrue(syntax.contains("KEEPTTL"), "KEEPTTL có từ 6.0.0: " + syntax);
    }

    @Test
    void soSánhPhiênBản() {
        assertTrue(RedisCommandTree.compareVersions("6.2.0", "7.4.11") < 0);
        assertTrue(RedisCommandTree.compareVersions("7.10.0", "7.9.9") > 0);
        assertEquals(0, RedisCommandTree.compareVersions("7.0", "7.0.0"));
    }

    @Test
    void độPhứcTạpRútGọn() {
        java.util.function.Function<String, String> shortOf = c -> new RedisCommandSpec("X", "generic", java.util.Set.of(), "X", "",
                null, null, c).complexityShort();
        assertEquals("O(1)", shortOf.apply("O(1)"));
        assertEquals("O(1) · O(N)", shortOf.apply("O(1) for each element added, so O(N) to add N elements when the command is called with multiple arguments."));
        assertEquals("O(log(N)+M)", shortOf.apply("O(log(N)+M) with N being the number of elements in the sorted set"));
        assertEquals(null, shortOf.apply("Depends on subcommand."));
        assertEquals(null, shortOf.apply(null));
    }
}
