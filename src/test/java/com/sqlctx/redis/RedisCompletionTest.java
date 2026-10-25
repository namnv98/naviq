package com.sqlctx.redis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisCompletionTest {

    /** Server giả: ghi lại câu hỏi để kiểm tra lọc theo kiểu/prefix có được truyền đúng không. */
    private static final class FakeSource implements RedisCompletion.Source {
        final List<String> calls = new ArrayList<>();

        @Override
        public List<String> keys(String prefix, String type) {
            calls.add("keys " + prefix + " " + type);
            return List.of("user:1", "user:2", "my key").stream().filter(k -> k.startsWith(prefix)).toList();
        }

        @Override
        public List<String> members(String key, String type, String prefix) {
            calls.add("members " + key + " " + type + " " + prefix);
            return List.of("name", "email");
        }

        @Override
        public List<String> configParams() {
            return List.of("maxmemory", "maxmemory-policy", "timeout");
        }

        @Override
        public List<String> channels() {
            return List.of("news");
        }

        @Override
        public List<String> contexts() {
            return List.of("redis-dev", "redis-prod");
        }

        @Override
        public List<String> groups(String key) {
            calls.add("groups " + key);
            return List.of("billing", "mailer");
        }

        @Override
        public List<String> consumers(String key, String group) {
            calls.add("consumers " + key + " " + group);
            return List.of("worker-1");
        }

        @Override
        public List<String> pendingIds(String key, String group) {
            calls.add("pending " + key + " " + group);
            return List.of("1-0");
        }

        @Override
        public List<String> entryIds(String key) {
            calls.add("entries " + key);
            return List.of("2-0", "1-0");
        }
    }

    private static List<String> values(String line, FakeSource source) {
        return RedisCompletion.suggest(line, line.length(), source).stream().map(RedisCompletion.Item::value).toList();
    }

    @Test
    void gõThườngThìGợiÝChữThường() {
        assertEquals(List.of("hget", "hgetall", "hgetex"), values("hget", new FakeSource()));
    }

    @Test
    void lệnhCóMôTảTừCommandsTsv() {
        var items = RedisCompletion.suggest("HGETA", 5, new FakeSource());
        assertEquals(1, items.size());
        assertEquals("Mọi field và giá trị", items.get(0).description());
    }

    @Test
    void keyChỉLấyĐúngKiểuCủaNhómLệnh() {
        FakeSource source = new FakeSource();
        assertEquals(List.of("user:1", "user:2"), values("HGETALL us", source));
        assertEquals(List.of("keys us hash"), source.calls);
    }

    @Test
    void lệnhKhôngGắnKiểuThìLấyMọiKey() {
        FakeSource source = new FakeSource();
        values("DEL ", source);
        assertEquals(List.of("keys  null"), source.calls);
    }

    @Test
    void keyCóKhoảngTrắngĐượcBọcNháy() {
        assertTrue(values("DEL ", new FakeSource()).contains("\"my key\""));
    }

    @Test
    void fieldLấyTừĐúngKeyĐãGõ() {
        FakeSource source = new FakeSource();
        assertEquals(List.of("name", "email"), values("HGET user:1 ", source));
        assertEquals(List.of("members user:1 hash "), source.calls);
    }

    @Test
    void thamSốCấuHình() {
        assertEquals(List.of("maxmemory", "maxmemory-policy"), values("CONFIG GET maxm", new FakeSource()));
    }

    @Test
    void lệnhGạchNgược() {
        assertEquals(List.of("\\ctx", "\\c"), values("\\c", new FakeSource()));
        assertEquals(List.of("redis-prod"), values("\\ctx redis-p", new FakeSource()));
    }

    @Test
    void trongNháyChưaĐóngKhôngGợiÝ() {
        assertEquals(List.of(), values("SET k \"hel", new FakeSource()));
    }

    @Test
    void cờLệnhSinhTừServer() {
        assertTrue(RedisCommandSpec.find(List.of("FUNCTION", "FLUSH")).isDangerous());
        assertTrue(RedisCommandSpec.find(List.of("SCRIPT", "FLUSH")).isDangerous());
        assertTrue(RedisCommandSpec.find(List.of("CONFIG", "SET")).isWrite(), "readonly phải chặn CONFIG SET");
        assertTrue(!RedisCommandSpec.find("INFO").isDangerous(), "INFO thuộc @dangerous của Redis nhưng chỉ đọc");
        assertTrue(!RedisCommandSpec.find(List.of("CONFIG", "GET")).isWrite());
        assertTrue(RedisCommandSpec.find("BZPOPMIN").isBlocking());
        assertTrue(RedisCommandSpec.find("SSUBSCRIBE").isSubscribe());
        assertEquals("zset", RedisCommandSpec.find("GEOADD").keyType());
        assertEquals("string", RedisCommandSpec.find("SETBIT").keyType());
    }

    @Test
    void môTảTiếngViệtĐèLênBảnSinh() {
        assertEquals("Mọi field và giá trị", RedisCommandSpec.find("HGETALL").summary());
        assertTrue(RedisCommandSpec.find("GEOADD").summary().startsWith("Adds"), "lệnh chưa dịch giữ tiếng Anh");
    }

    @Test
    void geoaddGợiÝKeyKiểuZset() {
        FakeSource source = new FakeSource();
        values("GEOADD ", source);
        assertEquals(List.of("keys  zset"), source.calls);
    }

    // ------------------------------------------------------------------ stream

    @Test
    void groupLấyTừĐúngStreamĐãGõ() {
        FakeSource source = new FakeSource();
        assertEquals(List.of("billing", "mailer"), values("XACK events ", source));
        assertEquals(List.of("groups events"), source.calls);
        assertEquals(List.of("billing"), values("XGROUP DESTROY events b", new FakeSource()));
    }

    @Test
    void tênGroupMớiKhôngGợiÝ() {
        FakeSource source = new FakeSource();
        assertEquals(List.of(), values("XGROUP CREATE events ", source));
        assertEquals(List.of(), source.calls);
    }

    @Test
    void xreadgroupChưaCóKeyThìLấyGroupCủaMọiStream() {
        FakeSource source = new FakeSource();
        values("XREADGROUP GROUP ", source);
        assertEquals(List.of("groups null"), source.calls);
    }

    @Test
    void consumerVàIdĐangChờTheoKeyVàGroupĐãGõ() {
        FakeSource source = new FakeSource();
        assertEquals(List.of("worker-1"), values("XGROUP DELCONSUMER events billing ", source));
        assertEquals(List.of("1-0"), values("XACK events billing ", source));
        assertEquals(List.of("consumers events billing", "pending events billing"), source.calls);
        assertEquals(List.of("2-0", "1-0"), values("XDEL events ", new FakeSource()));
    }

    @Test
    void idĐặcBiệtCóGiảiThích() {
        var items = RedisCompletion.suggest("XREAD STREAMS events ", 21, new FakeSource());
        assertTrue(items.stream().anyMatch(i -> i.value().equals("$") && i.description().contains("mới")), items.toString());
        assertTrue(values("XREADGROUP GROUP g c STREAMS events ", new FakeSource()).containsAll(List.of(">", "0")));
        assertEquals(List.of("-"), values("XRANGE events ", new FakeSource()));
        assertEquals(List.of("+"), values("XRANGE events - ", new FakeSource()));
    }

    @Test
    void tailGợiÝStreamVàTuỳChọn() {
        FakeSource source = new FakeSource();
        values("\\tail ", source);
        assertEquals(List.of("keys  stream"), source.calls);
        assertEquals(List.of("GROUP", "FROM"), values("\\tail events ", new FakeSource()));
        assertEquals(List.of("billing", "mailer"), values("\\tail events GROUP ", new FakeSource()));
        assertEquals(List.of("worker-1"), values("\\tail events GROUP billing ", new FakeSource()));
        assertEquals(List.of("FROM"), values("\\tail events GROUP billing w1 ", new FakeSource()));
        assertEquals(List.of("$", "0"), values("\\tail events FROM ", new FakeSource()));
    }
}
