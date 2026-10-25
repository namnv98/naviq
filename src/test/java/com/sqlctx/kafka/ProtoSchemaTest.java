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
        // Item lồng trong items in thêm n = 0 (giá trị mặc định), card (oneof) để trống thì không in
        String expected = json.replace("{\"sku\":\"B\"}", "{\"sku\":\"B\",\"n\":0}").replace("{\"sku\":\"C\"}", "{\"sku\":\"C\",\"n\":0}");
        assertEquals(JsonParser.parseString(expected), s.decode(s.message(null), b, 0, b.length));
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

    @Test
    void inGiáTrịMặcĐịnhNhưConfluent() throws IOException {
        // field thường để trống: in mặc định; oneof, optional và kiểu message để trống: không in
        ProtoSchema s = ProtoSchema.parse("""
                syntax = "proto3";
                message M { string a = 1; int64 b = 2; E e = 3; repeated int32 r = 4; map<string, int32> m = 5;
                  optional int32 o = 6; N n = 7; oneof x { string c = 8; } bool f = 9; }
                message N { int32 v = 1; }
                enum E { E_ZERO = 0; E_ONE = 1; }
                """);
        byte[] b = s.encode(s.message(null), JsonParser.parseString("{\"f\":true,\"a\":\"x\"}"));
        assertEquals("{\"a\":\"x\",\"b\":\"0\",\"e\":\"E_ZERO\",\"r\":[],\"m\":{},\"f\":true}",
                s.decode(s.message(null), b, 0, b.length).toString());
    }

    @Test
    void kiểuChuẩnCủaGoogle() throws IOException {
        ProtoSchema s = ProtoSchema.parse("""
                syntax = "proto3";
                import "google/protobuf/timestamp.proto";
                import "google/protobuf/duration.proto";
                import "google/protobuf/wrappers.proto";
                import "google/protobuf/struct.proto";
                import "google/protobuf/field_mask.proto";
                import "google/protobuf/empty.proto";
                message Ev { google.protobuf.Timestamp at = 1; google.protobuf.Duration took = 2;
                  google.protobuf.StringValue note = 3; google.protobuf.Int64Value big = 4; google.protobuf.Struct meta = 5;
                  google.protobuf.FieldMask mask = 6; google.protobuf.Empty nothing = 7; repeated google.protobuf.Value vs = 8; }
                """);
        String json = "{\"at\":\"2024-01-31T10:00:00.123Z\",\"took\":\"-1.500s\",\"note\":\"hi\",\"big\":\"9007199254740993\","
                + "\"meta\":{\"k\":[1,\"two\",true,null,{\"x\":1.5}]},\"mask\":\"userId,name\",\"nothing\":{},"
                + "\"vs\":[null,3,\"s\"]}";
        byte[] b = s.encode(s.message(null), JsonParser.parseString(json));
        assertEquals(JsonParser.parseString(json), s.decode(s.message(null), b, 0, b.length));
        // múi giờ khác Z đổi về UTC; giây không có phần lẻ
        byte[] t = s.encode(s.message(null), JsonParser.parseString("{\"at\":\"2024-01-31T17:00:00+07:00\",\"took\":\"3s\"}"));
        String back = s.decode(s.message(null), t, 0, t.length).toString();
        assertTrue(back.contains("\"at\":\"2024-01-31T10:00:00Z\"") && back.contains("\"took\":\"3s\""), back);
        assertThrows(IOException.class, () -> s.encode(s.message(null), JsonParser.parseString("{\"took\":\"3 phút\"}")));
        assertThrows(IOException.class, () -> s.encode(s.message(null), JsonParser.parseString("{\"at\":\"hôm qua\"}")));
    }

    @Test
    void importFileKhácQuaReference() throws IOException {
        java.util.Map<String, String> files = java.util.Map.of(
                "common/money.proto", "syntax = \"proto3\"; package common; import \"common/currency.proto\"; message Money { int64 units = 1; Currency cur = 2; }",
                "common/currency.proto", "syntax = \"proto3\"; package common; enum Currency { VND = 0; USD = 1; }");
        ProtoSchema s = ProtoSchema.parse("syntax = \"proto3\"; package shop; import \"common/money.proto\"; "
                + "message Order { string id = 1; common.Money total = 2; }", files::get);
        assertEquals(List.of(0), s.indexes(s.message(null)), "message của file import không tính vào chỉ số");
        assertEquals("shop.Order", s.message(null).fullName);
        byte[] b = s.encode(s.message(null), JsonParser.parseString("{\"id\":\"o1\",\"total\":{\"units\":\"5\",\"cur\":\"USD\"}}"));
        assertEquals("{\"id\":\"o1\",\"total\":{\"units\":\"5\",\"cur\":\"USD\"}}", s.decode(s.message(null), b, 0, b.length).toString());
        IOException e = assertThrows(IOException.class, () -> ProtoSchema.parse(
                "syntax = \"proto3\"; import \"x/y.proto\"; message A { x.Y y = 1; }"));
        assertTrue(e.getMessage().contains("x/y.proto"), e.getMessage());
    }
}
