package com.sqlctx.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisSyntaxTest {

    private static List<String> list(String spaceSeparated) {
        return spaceSeparated.isBlank() ? List.of() : Arrays.asList(spaceSeparated.split(" "));
    }

    @ParameterizedTest(name = "[{0}] -> keyword={1}, rule={2}")
    @CsvSource(delimiter = '|', value = {
            "SET           |                                     | key",
            "SET k         |                                     | value",
            "SET k v       | EX EXAT GET IFEQ KEEPTTL NX PX PXAT XX |",
            // nhóm tuỳ chọn đã dùng (EX) thì không gợi ý lại cả nhóm đó (PX, KEEPTTL...)
            "SET k v EX 10 | GET IFEQ NX XX                      |",
            "SET k v EX    |                                     | integer",
            "HGET          |                                     | key",
            "HGET k        |                                     | field",
            "GET a         |                                     |",
            "DEL a         |                                     | key",
            "CONFIG        | GET HELP RESETSTAT REWRITE SET      |",
            "CONFIG GET    |                                     | configParam",
            "SCAN 0        | COUNT MATCH TYPE                    |",
            "SCAN 0 TYPE   | HASH LIST SET STREAM STRING ZSET    |",
            "LINSERT k     | AFTER BEFORE                        |",
            "XINFO         | CONSUMERS GROUPS HELP STREAM        |",
            "INFO          |                                     | infoSection",
            "PUBLISH       |                                     | channel",
            "FOO           |                                     | arg",
            // chữ trùng từ khoá ở chỗ tham số vẫn là tham số: "get" là key, sau nó chờ value
            "SET get       |                                     | value",
            // chữ trùng từ khoá ở chỗ có tuỳ chọn thì là từ khoá (như server): MAXLEN không bị hiểu là id
            "XADD s MAXLEN | = ~                                 | value",
            "CLIENT KILL ID |                                    | integer",
            // lệnh trước đây chưa có trong grammar viết tay
            "GEOADD k      | CH NX XX                            | number",
            "HEXPIRE k 10  | FIELDS GT LT NX XX                  |",
            "SETBIT        |                                     | key",
            "ZUNIONSTORE d 2 a b | AGGREGATE WEIGHTS             | key",
    })
    void gợiÝTheoViTríTrongCâyLệnh(String before, String keywords, String rules) {
        RedisSyntax.Caret caret = RedisSyntax.analyze(before.strip() + " ");
        assertEquals(list(keywords == null ? "" : keywords.strip()), caret.keywords());
        assertEquals(rules == null ? Set.of() : Set.of(rules.strip()), caret.argRules());
    }

    @Test
    void đầuDòngGợiÝMọiLệnhCóTrongGrammar() {
        List<String> commands = RedisSyntax.analyze("").keywords();
        assertTrue(commands.containsAll(List.of("GET", "SET", "HGETALL", "ZADD", "XADD", "CONFIG", "SUBSCRIBE")));
        assertFalse(commands.contains("NX"), "tuỳ chọn không phải lệnh");
        assertFalse(commands.contains("WITHSCORES"));
    }

    private static String roles(String text) {
        return RedisSyntax.inspect(text).roles().stream().map(Enum::name).collect(java.util.stream.Collectors.joining(" "));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "SET k v NX EX 10       | COMMAND KEY VALUE KEYWORD KEYWORD NUMBER",
            "set get value          | COMMAND KEY VALUE",
            "HGET user:1 name       | COMMAND KEY FIELD",
            "ZADD z 1 a 2 \"b c\"   | COMMAND KEY NUMBER FIELD NUMBER STRING",
            "SET \"my key\" 'v'     | COMMAND KEY STRING",
            "JSON.GET doc $         | UNKNOWN_COMMAND VALUE VALUE",
    })
    void vaiTròTokenTheoGrammar(String text, String expected) {
        assertEquals(expected, roles(text));
    }

    @Test
    void thừaThamSốLàLỗiTạiĐúngToken() {
        RedisSyntax.Inspection inspection = RedisSyntax.inspect("GET a b");
        assertEquals(6, inspection.errorOffset());
        assertEquals("thừa tham số 'b'", inspection.error());
        assertFalse(inspection.incomplete());
    }

    @Test
    void gõChưaXongKhôngBịCoiLàLỗi() {
        RedisSyntax.Inspection inspection = RedisSyntax.inspect("SET k");
        assertEquals(-1, inspection.errorOffset());
        assertNull(inspection.error());
        assertTrue(inspection.incomplete());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // tuỳ chọn bắt đầu bằng từ khoá: server nhận theo thứ tự bất kỳ, grammar cũng vậy
            "SET k v EX 10 NX",
            "SET k v GET NX PX 5",
            "ZADD z CH NX 1 a",
            // lệnh con có trong server, trước đây bị gạch đỏ nhầm
            "ACL SETUSER alice on >pass ~* +@all",
            "CLIENT PAUSE 1000 WRITE",
            "XGROUP CREATECONSUMER s g c",
            "MEMORY PURGE",
            // lệnh con server khác có mà grammar không có
            "CONFIG SOMETHINGNEW a b",
            "GEOSEARCH places FROMLONLAT 105.8 21.0 BYRADIUS 10 km ASC COUNT 5 WITHDIST",
            "HSETNX user:1 name An",
    })
    void lệnhHợpLệKhôngBịBáoLỗi(String line) {
        assertNull(RedisSyntax.inspect(line).error(), line);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {"SET k v E", "SET k v EX -", "ZRANGEBYSCORE z (", "CONFIG"})
    void đangGõDởKhôngBịBáoLỗi(String line) {
        RedisSyntax.Inspection inspection = RedisSyntax.inspect(line);
        assertNull(inspection.error(), line);
        assertTrue(inspection.incomplete(), line);
    }

    @Test
    void cùngTuỳChọnHaiLầnLàLỗi() {
        assertEquals("không đúng cú pháp tại 'EX'", RedisSyntax.inspect("SET k v EX 10 EX 20").error());
    }

    @Test
    void lệnhLạKhôngBịBáoLỗi() {
        assertEquals(-1, RedisSyntax.inspect("FT.SEARCH idx \"@name:foo\" LIMIT 0 10").errorOffset());
    }

    @Test
    void đầuDòngGợiÝĐủMọiLệnhCủaCây() {
        assertEquals(RedisCommandTree.current().commands().size(), RedisSyntax.analyze("").keywords().size());
        assertTrue(RedisSyntax.analyze("").keywords().size() >= 250);
    }

    @Test
    void wordsBỏNháy() {
        assertEquals(List.of("HGET", "my key", "f"), RedisSyntax.words("HGET \"my key\" f"));
    }
}
