package com.sqlctx.kafka;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaSerdeTest {

    private static byte[] bytes(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            out[i] = (byte) b[i];
        }
        return out;
    }

    @Test
    void chữThườngVàByteRác() {
        assertEquals("Xin chào", KafkaSerde.text("Xin chào".getBytes(StandardCharsets.UTF_8), "null"));
        assertEquals("a\\x00\\xff", KafkaSerde.text(bytes('a', 0, 0xff), "null"));
        assertEquals("null", KafkaSerde.text(null, "null"));
    }

    @Test
    void schemaIdTrong5ByteĐầu() {
        assertEquals(258, KafkaSerde.schemaId(bytes(0, 0, 0, 1, 2, 9)));
        assertEquals(-1, KafkaSerde.schemaId(bytes(1, 0, 0, 1, 2)));
        assertEquals(-1, KafkaSerde.schemaId("{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void protobufThô() throws IOException {
        // Event { id: 7, kind: "click", tags: ["a", "b"] }
        byte[] msg = bytes(0x08, 7, 0x12, 5, 'c', 'l', 'i', 'c', 'k', 0x1a, 1, 'a', 0x1a, 1, 'b');
        assertEquals("1: 7 2: \"click\" 3: \"a\" 3: \"b\"", KafkaSerde.decodeRaw(msg, 0, msg.length));
        // message lồng nhau: 4 { 1: 1 }
        byte[] nested = bytes(0x22, 2, 0x08, 1);
        assertEquals("4 { 1: 1 }", KafkaSerde.decodeRaw(nested, 0, nested.length));
    }

    @Test
    void protobufBịCắtBáoLỗi() {
        byte[] cut = bytes(0x12, 10, 'a');
        assertThrows(IOException.class, () -> KafkaSerde.decodeRaw(cut, 0, cut.length));
    }

    @Test
    void jsonSchemaThêmTiềnTốId() throws IOException {
        byte[] b = KafkaSerde.encodeJsonSchema("{\"a\":1}", 5);
        assertEquals(5, KafkaSerde.schemaId(b));
        assertEquals("{\"a\":1}", new String(b, 5, b.length - 5, StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> KafkaSerde.encodeJsonSchema("{a", 5));
    }
}
