package com.sqlctx.redis;

import com.sqlctx.redis.RespClient.Bulk;
import com.sqlctx.redis.RespClient.RespError;
import com.sqlctx.redis.RespClient.Simple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tách tham số kiểu redis-cli, đọc reply RESP, và hiển thị reply. */
class RedisProtocolTest {

    private static Object parse(String raw) throws IOException {
        return RespClient.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static Bulk bulk(String s) {
        return new Bulk(s.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> split(String line) {
        return RedisArgs.split(line).stream().map(RedisArgs::text).toList();
    }

    @Test
    void táchThamSốNhưRedisCli() {
        assertEquals(List.of("SET", "my key", "a\"b\n", "it's"), split("SET \"my key\" \"a\\\"b\\n\" 'it\\'s'"));
        assertEquals(List.of("SET", "k", "Xin chào 👋"), split("  SET   k \"Xin chào 👋\"  "));
    }

    @Test
    void thoátHexTạoByteThô() {
        assertArrayEquals(new byte[]{0, (byte) 0xff}, RedisArgs.split("\"\\x00\\xff\"").get(0));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"SET k \"abc", "SET k \"a\"b", "SET k 'x"})
    void nháyKhôngHợpLệBịTừChối(String line) {
        assertThrows(IllegalArgumentException.class, () -> RedisArgs.split(line));
    }

    @Test
    void tách_command_theoChấmPhẩyNgoàiNháy() {
        assertEquals(List.of("SET a \"x;y\"", "GET a"), RedisArgs.splitCommands("SET a \"x;y\"; GET a\n"));
    }

    @Test
    void đọcCácKiểuReplyRESP2() throws IOException {
        assertEquals(new Simple("OK"), parse("+OK\r\n"));
        assertEquals(new RespError("ERR x"), parse("-ERR x\r\n"));
        assertEquals(42L, parse(":42\r\n"));
        assertNull(parse("$-1\r\n"));
        assertEquals(bulk("a\r\nb"), parse("$4\r\na\r\nb\r\n"));
        assertEquals(List.of(bulk("a"), 1L, List.of()), parse("*3\r\n$1\r\na\r\n:1\r\n*0\r\n"));
    }

    @Test
    void mapRESP3TrảiPhẳngNhưRESP2() throws IOException {
        assertEquals(List.of(bulk("f"), bulk("v")), parse("%1\r\n$1\r\nf\r\n$1\r\nv\r\n"));
        assertEquals(new Simple("1.5"), parse(",1.5\r\n"));
        assertNull(parse("_\r\n"));
    }

    private static List<String> lines(List<String> words, Object reply) {
        return ((RedisReplyRenderer.Lines) RedisReplyRenderer.render(words, reply)).lines();
    }

    @Test
    void hiểnThịKiểuRedisCli() {
        assertEquals(List.of("(nil)"), lines(List.of("GET"), null));
        assertEquals(List.of("(integer) 3"), lines(List.of("INCR"), 3L));
        assertEquals(List.of("\"Xin chào\""), lines(List.of("GET"), bulk("Xin chào")));
        assertEquals(List.of("\"\\xff\\x00\""), lines(List.of("GET"), new Bulk(new byte[]{(byte) 0xff, 0})));
        assertEquals(List.of("(empty array)"), lines(List.of("KEYS"), List.of()));
    }

    @Test
    void mảngLồngNhauThụtLề() {
        Object scan = List.of(bulk("0"), List.of(bulk("a"), bulk("b")));
        assertEquals(List.of("1) \"0\"", "2) 1) \"a\"", "   2) \"b\""), lines(List.of("SCAN"), scan));
    }

    @Test
    void bulkNhiềuDòngInThẳng() {
        assertEquals(List.of("# Server", "redis_version:7.4.1"), lines(List.of("INFO"), bulk("# Server\r\nredis_version:7.4.1\r\n")));
    }

    @Test
    void hgetallThànhBảng() {
        var out = RedisReplyRenderer.render(List.of("hgetall", "user:1"), List.of(bulk("name"), bulk("An \"A\"")));
        assertEquals(new RedisReplyRenderer.Table(List.of("field", "value"), List.of(List.of("name", "An \"A\""))), out);
    }

    @Test
    void zrangeChỉThànhBảngKhiCóWithscores() {
        Object reply = List.of(bulk("a"), bulk("1"), bulk("b"), bulk("2"));
        assertEquals(RedisReplyRenderer.Table.class,
                RedisReplyRenderer.render(Arrays.asList("ZRANGE", "z", "0", "-1", "withscores"), reply).getClass());
        assertEquals(RedisReplyRenderer.Lines.class,
                RedisReplyRenderer.render(Arrays.asList("ZRANGE", "z", "0", "-1"), reply).getClass());
    }

    @Test
    void lỗiĐánhDấuĐỏ() {
        var out = (RedisReplyRenderer.Lines) RedisReplyRenderer.render(List.of("GET"), new RespError("WRONGTYPE x"));
        assertEquals(List.of("(error) WRONGTYPE x"), out.lines());
        assertEquals(true, out.error());
    }

    // ------------------------------------------------------------------ stream

    private static List<Object> entry(String id, String... fv) {
        List<Object> fields = new java.util.ArrayList<>();
        for (String x : fv) {
            fields.add(bulk(x));
        }
        return List.of(bulk(id), fields);
    }

    private static RedisReplyRenderer.Table table(List<String> words, Object reply) {
        return (RedisReplyRenderer.Table) RedisReplyRenderer.render(words, reply);
    }

    @Test
    void xreadThànhBảngCảHaiDạngRESP() {
        List<Object> entries = List.of(entry("1-0", "a", "1"), entry("2-0", "a", "2"));
        var expected = List.of(List.of("s", "1-0", "a=1"), List.of("s", "2-0", "a=2"));
        assertEquals(expected, table(List.of("XREAD"), List.of(List.of(bulk("s"), entries))).rows()); // RESP2
        assertEquals(expected, table(List.of("XREADGROUP"), List.of(bulk("s"), entries)).rows());     // map RESP3
        assertEquals(2, RedisReplyRenderer.readEntries(List.of(List.of(bulk("s"), entries))).size());
        assertEquals(List.of(), RedisReplyRenderer.readEntries(null));
    }

    @Test
    void entryĐãXoáVẫnHiện() {
        List<Object> deleted = new java.util.ArrayList<>(List.of(bulk("3-0")));
        deleted.add(null);
        assertEquals(List.of(List.of("3-0", "<đã xoá>")), table(List.of("XCLAIM"), List.of(deleted)).rows());
    }

    @Test
    void xinfoGroupsMỗiGroup1Dòng() {
        Object reply = List.of(List.of(bulk("name"), bulk("g1"), bulk("pending"), 2L),
                List.of(bulk("name"), bulk("g2"), bulk("pending"), 0L));
        var t = table(List.of("XINFO", "GROUPS", "s"), reply);
        assertEquals(List.of("name", "pending"), t.headers());
        assertEquals(List.of(List.of("g1", "2"), List.of("g2", "0")), t.rows());
    }

    @Test
    void xinfoStreamGọnEntryĐầuCuối() {
        Object reply = List.of(bulk("length"), 3L, bulk("first-entry"), entry("1-0", "a", "1"));
        assertEquals(List.of(List.of("length", "3"), List.of("first-entry", "1-0  a=1")),
                table(List.of("XINFO", "STREAM", "s"), reply).rows());
    }

    @Test
    void xpendingTómTắtVàChiTiết() {
        Object summary = List.of(2L, bulk("1-0"), bulk("2-0"), List.of(List.of(bulk("c1"), bulk("2"))));
        assertEquals(List.of("consumer c1", "2"), table(List.of("XPENDING", "s", "g"), summary).rows().get(3));
        Object detail = List.of(List.of(bulk("1-0"), bulk("c1"), 500L, 1L));
        var t = table(List.of("XPENDING", "s", "g", "-", "+", "10"), detail);
        assertEquals(List.of(List.of("1-0", "c1", "500", "1")), t.rows());
    }

    @Test
    void xautoclaimNhiềuPhần() {
        Object reply = List.of(bulk("0-0"), List.of(entry("1-0", "a", "1")), List.of(bulk("5-0")));
        var parts = ((RedisReplyRenderer.Many) RedisReplyRenderer.render(List.of("XAUTOCLAIM"), reply)).parts();
        assertEquals(3, parts.size());
        assertTrue(((RedisReplyRenderer.Lines) parts.get(0)).lines().get(0).contains("đã quét hết"));
        assertEquals(RedisReplyRenderer.Table.class, parts.get(1).getClass());
    }
}
