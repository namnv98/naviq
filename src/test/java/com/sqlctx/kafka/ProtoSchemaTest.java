package com.sqlctx.kafka;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtoSchemaTest {

    private static final String PROTO = """
            syntax = "proto3";
            package shop.v1;
            import "google/protobuf/descriptor.proto"; // bỏ qua: không dùng tới
            option java_package = "x";

            /* đơn hàng */
            message Order {
              int64 id = 1;
              string customer_name = 2;
              repeated int32 qty = 3;
              Status status = 4;
              Item first_item = 5;
              map<string, int32> tags = 6;
              oneof pay { string card = 7; bool cash = 8; }
              sint32 delta = 9;
              double price = 10 [json_name = "price"];
              bytes blob = 11;
              repeated Item items = 12;
              reserved 20 to 30;

              message Item { string sku = 1; fixed32 n = 2; }
            }

            enum Status { STATUS_UNKNOWN = 0; PAID = 1; SHIPPED = 2; }

            message Event { string name = 1; Order.Item item = 2; }
            """;

    @Test
    void đọcFileProto() throws IOException {
        ProtoSchema s = ProtoSchema.parse(PROTO);
        assertEquals("shop.v1.Order", s.message(null).fullName);
        assertEquals(List.of(0, 0), s.indexes(s.message("Item")));
        assertEquals(List.of(1), s.indexes(s.message("shop.v1.Event")));
        assertEquals("shop.v1.Order.Item", s.byIndexes(List.of(0, 0)).fullName);
        ProtoSchema.Field f = s.message("Order").byName("customerName");
        assertEquals("customer_name", f.name());
        assertTrue(s.message("Order").byName("qty").packed());
        assertEquals("shop.v1.Order.Item", s.message("Event").byName("item").type());
    }

    @Test
    void jsonQuaBinaryRồiNgượcLại() throws IOException {
        ProtoSchema s = ProtoSchema.parse(PROTO);
        String json = "{\"id\":\"42\",\"customerName\":\"An\",\"qty\":[1,2,300],\"status\":\"PAID\","
                + "\"firstItem\":{\"sku\":\"A1\",\"n\":7},\"tags\":{\"vip\":1},\"cash\":true,\"delta\":-3,"
                + "\"price\":9.5,\"blob\":\"AQI=\",\"items\":[{\"sku\":\"B\"},{\"sku\":\"C\"}]}";
        byte[] b = s.encode(s.message(null), JsonParser.parseString(json));
        assertEquals(JsonParser.parseString(json), s.decode(s.message(null), b, 0, b.length));
    }

    @Test
    void mãHoáĐúngNhưProtoc() throws IOException {
        ProtoSchema s = ProtoSchema.parse("syntax = \"proto3\"; message M { int32 a = 1; string b = 2; repeated int32 c = 4; sint32 d = 5; }");
        // ví dụ trong tài liệu encoding của protobuf: a=150 -> 08 96 01, b="testing", c packed [3, 270, 86942]
        byte[] b = s.encode(s.message(null), JsonParser.parseString("{\"a\":150,\"b\":\"testing\",\"c\":[3,270,86942],\"d\":-1}"));
        assertArrayEquals(new byte[]{0x08, (byte) 0x96, 0x01, 0x12, 0x07, 't', 'e', 's', 't', 'i', 'n', 'g',
                0x22, 0x06, 0x03, (byte) 0x8e, 0x02, (byte) 0x9e, (byte) 0xa7, 0x05, 0x28, 0x01}, b);
        // số âm int32 là varint 10 byte
        assertEquals(11, s.encode(s.message(null), JsonParser.parseString("{\"a\":-1}")).length);
    }

    @Test
    void proto2KhôngPackedMặcĐịnh() throws IOException {
        ProtoSchema s = ProtoSchema.parse("syntax = \"proto2\"; message M { repeated int32 c = 1; repeated int32 p = 2 [packed=true]; }");
        byte[] b = s.encode(s.message(null), JsonParser.parseString("{\"c\":[1,2],\"p\":[1,2]}"));
        assertArrayEquals(new byte[]{0x08, 0x01, 0x08, 0x02, 0x12, 0x02, 0x01, 0x02}, b);
        assertEquals(JsonParser.parseString("{\"c\":[1,2],\"p\":[1,2]}"), s.decode(s.message(null), b, 0, b.length));
    }

    @Test
    void lỗiRõRàng() throws IOException {
        assertThrows(IOException.class, () -> ProtoSchema.parse("syntax = \"proto3\"; message M { google.protobuf.Timestamp t = 1; }"));
        assertThrows(IOException.class, () -> ProtoSchema.parse("syntax = \"proto3\";"));
        ProtoSchema s = ProtoSchema.parse(PROTO);
        assertThrows(IOException.class, () -> s.encode(s.message(null), JsonParser.parseString("{\"nope\":1}")));
        assertThrows(IOException.class, () -> s.encode(s.message(null), JsonParser.parseString("{\"status\":\"LOST\"}")));
        assertThrows(IOException.class, () -> s.encode(s.message(null), JsonParser.parseString("{\"id\":\"abc\"}")));
        assertThrows(IOException.class, () -> s.message("Nope"));
    }

    @Test
    void chỉSốMessageKiểuConfluent() throws IOException {
        // [0] viết gọn thành 1 byte 0; [1, 0] -> số phần tử 2, rồi 1, 0 (đều zigzag)
        assertEquals(List.of(0), KafkaSerde.readIndexes(new byte[]{0}, new int[]{0}));
        assertEquals(List.of(1, 0), KafkaSerde.readIndexes(new byte[]{4, 2, 0}, new int[]{0}));
    }
}
