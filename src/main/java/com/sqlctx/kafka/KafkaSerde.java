package com.sqlctx.kafka;

import com.sqlctx.util.I18n;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hiển thị và tạo nội dung message. Định dạng trên dây của Schema Registry (Confluent): byte 0, 4 byte schema id
 * (big-endian), rồi dữ liệu - Avro binary; JSON Schema: chữ JSON; Protobuf: dãy chỉ số message (varint zigzag) rồi
 * protobuf binary. Protobuf đọc schema .proto bằng {@link ProtoSchema}; schema dùng thứ nó không hiểu (import...) thì
 * giải mã thô (số field: giá trị, như protoc --decode_raw).
 */
public final class KafkaSerde {

    private KafkaSerde() {
    }

    private static final Map<Integer, Schema> AVRO = new ConcurrentHashMap<>();
    private static final Map<Integer, ProtoSchema> PROTO = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ chữ thường

    /** UTF-8 hợp lệ thì in nguyên chữ (ký tự điều khiển thoát \xNN), không thì từng byte \xNN; null -> {@code nullLiteral}. */
    public static String text(byte[] bytes, String nullLiteral) {
        if (bytes == null) {
            return nullLiteral;
        }
        StringBuilder sb = new StringBuilder();
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            for (char c : s.toCharArray()) {
                if (c < 0x20 && c != '\t' || c == 0x7f) {
                    sb.append(String.format("\\x%02x", (int) c));
                } else {
                    sb.append(c);
                }
            }
        } catch (CharacterCodingException e) {
            for (byte b : bytes) {
                int v = b & 0xff;
                sb.append(v >= 0x20 && v < 0x7f ? String.valueOf((char) v) : String.format("\\x%02x", v));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Schema Registry

    /** Schema id trong 5 byte đầu, -1 nếu không phải định dạng Schema Registry. */
    public static int schemaId(byte[] bytes) {
        if (bytes == null || bytes.length < 5 || bytes[0] != 0) {
            return -1;
        }
        return ByteBuffer.wrap(bytes, 1, 4).getInt();
    }

    /** Giải mã theo schema trên Schema Registry; {@code flavor}: avro, json-schema, protobuf. */
    public static String decode(byte[] bytes, String flavor, SchemaRegistry sr) throws IOException {
        int id = schemaId(bytes);
        if (id < 0) {
            throw new IOException(I18n.t("serde.notSr"));
        }
        int offset = 5;
        switch (flavor) {
            case "avro" -> {
                Schema schema = avroSchema(id, sr);
                GenericDatumReader<Object> reader = new GenericDatumReader<>(schema);
                Object datum = reader.read(null, DecoderFactory.get().binaryDecoder(bytes, offset, bytes.length - offset, null));
                return GenericData.get().toString(datum);
            }
            case "json-schema" -> {
                return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
            }
            case "protobuf" -> {
                int[] pos = {offset};
                List<Integer> indexes = readIndexes(bytes, pos);
                ProtoSchema proto;
                try {
                    proto = protoSchema(id, sr);
                } catch (IOException e) {
                    return decodeRaw(bytes, pos[0], bytes.length); // schema không đọc được: vẫn hiện được nội dung
                }
                return proto.decode(proto.byIndexes(indexes), bytes, pos[0], bytes.length).toString();
            }
            default -> throw new IOException(I18n.t("serde.unknownFormat", flavor));
        }
    }

    private static Schema avroSchema(int id, SchemaRegistry sr) throws IOException {
        Schema cached = AVRO.get(id);
        if (cached != null) {
            return cached;
        }
        SchemaRegistry.Schema s = sr.byId(id);
        if (!"AVRO".equalsIgnoreCase(s.type())) {
            throw new IOException(I18n.t("serde.notAvro", id, s.type()));
        }
        Schema parsed = new Schema.Parser().parse(s.schema());
        AVRO.put(id, parsed);
        return parsed;
    }

    /** Avro: 1 dòng JSON (theo cách mã hoá JSON của Avro: union viết {"type": giá trị}) -> byte có tiền tố id. */
    public static byte[] encodeAvro(String json, int id, SchemaRegistry sr) throws IOException {
        Schema schema = avroSchema(id, sr);
        Object datum;
        try {
            datum = new GenericDatumReader<>(schema).read(null, DecoderFactory.get().jsonDecoder(schema, json));
        } catch (IOException | RuntimeException e) {
            throw new IOException(I18n.t("serde.schemaMismatch", e.getMessage()));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeHeader(out, id);
        BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
        new GenericDatumWriter<>(schema).write(datum, enc);
        enc.flush();
        return out.toByteArray();
    }

    private static ProtoSchema protoSchema(int id, SchemaRegistry sr) throws IOException {
        ProtoSchema cached = PROTO.get(id);
        if (cached != null) {
            return cached;
        }
        SchemaRegistry.Schema s = sr.byId(id);
        if (!"PROTOBUF".equalsIgnoreCase(s.type())) {
            throw new IOException(I18n.t("serde.notProtobuf", id, s.type()));
        }
        ProtoSchema parsed = ProtoSchema.parse(s.schema());
        PROTO.put(id, parsed);
        return parsed;
    }

    /** Chỉ số message kiểu Confluent: 1 byte 0 = [0] (message đầu tiên), không thì số phần tử rồi từng chỉ số. */
    static List<Integer> readIndexes(byte[] bytes, int[] pos) throws IOException {
        int count = (int) zigzag(readVarint(bytes, pos));
        if (count == 0) {
            return List.of(0);
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add((int) zigzag(readVarint(bytes, pos)));
        }
        return out;
    }

    private static void writeIndexes(ByteArrayOutputStream out, List<Integer> indexes) {
        if (indexes.equals(List.of(0))) {
            out.write(0);
            return;
        }
        ProtoSchema.varint(out, (long) indexes.size() << 1);
        for (int i : indexes) {
            ProtoSchema.varint(out, ((long) i << 1) ^ (i >> 31));
        }
    }

    /** Protobuf: 1 dòng JSON (ánh xạ JSON chuẩn của proto3) -> byte có tiền tố id; {@code message} null = message đầu tiên. */
    public static byte[] encodeProtobuf(String json, int id, String message, SchemaRegistry sr) throws IOException {
        ProtoSchema proto = protoSchema(id, sr);
        ProtoSchema.Message m = proto.message(message);
        com.google.gson.JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IOException(I18n.t("serde.badJson", e.getMessage()));
        }
        byte[] body = proto.encode(m, parsed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeHeader(out, id);
        writeIndexes(out, proto.indexes(m));
        out.writeBytes(body);
        return out.toByteArray();
    }

    /** JSON Schema: kiểm tra là JSON hợp lệ (không kiểm tra theo schema) rồi thêm tiền tố id. */
    public static byte[] encodeJsonSchema(String json, int id) throws IOException {
        try {
            com.google.gson.JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IOException(I18n.t("serde.badJson", e.getMessage()));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeHeader(out, id);
        out.writeBytes(json.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static void writeHeader(ByteArrayOutputStream out, int id) {
        out.write(0);
        out.writeBytes(ByteBuffer.allocate(4).putInt(id).array());
    }

    // ------------------------------------------------------------------ protobuf thô

    static long readVarint(byte[] b, int[] pos) throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= b.length) {
                throw new IOException(I18n.t("serde.varintCut"));
            }
            int x = b[pos[0]++] & 0xff;
            v |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) {
                return v;
            }
        }
        throw new IOException(I18n.t("serde.varintLong"));
    }

    private static long zigzag(long v) {
        return (v >>> 1) ^ -(v & 1);
    }

    private static void need(int pos, int len, int to, int field) throws IOException {
        if (pos + len > to) {
            throw new IOException(I18n.t("serde.fieldCut", field));
        }
    }

    /** "1: 42 2: "abc" 3 { 1: 1 }" - như protoc --decode_raw, viết trên 1 dòng. */
    static String decodeRaw(byte[] b, int from, int to) throws IOException {
        StringBuilder sb = new StringBuilder();
        int[] pos = {from};
        while (pos[0] < to) {
            long key = readVarint(b, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            switch (wire) {
                case 0 -> sb.append(field).append(": ").append(readVarint(b, pos));
                case 1 -> {
                    need(pos[0], 8, to, field);
                    sb.append(field).append(": 0x").append(String.format("%016x", ByteBuffer.wrap(b, pos[0], 8).getLong()));
                    pos[0] += 8;
                }
                case 5 -> {
                    need(pos[0], 4, to, field);
                    sb.append(field).append(": 0x").append(String.format("%08x", ByteBuffer.wrap(b, pos[0], 4).getInt()));
                    pos[0] += 4;
                }
                case 2 -> {
                    int len = (int) readVarint(b, pos);
                    if (len < 0 || pos[0] + len > to) {
                        throw new IOException(I18n.t("serde.fieldTooLong", field));
                    }
                    String nested = null;
                    try {
                        nested = len > 0 ? decodeRaw(b, pos[0], pos[0] + len) : null;
                    } catch (IOException | RuntimeException ignored) {
                        // không phải message lồng nhau: là chuỗi/byte
                    }
                    byte[] slice = java.util.Arrays.copyOfRange(b, pos[0], pos[0] + len);
                    String asText = text(slice, "");
                    boolean printable = !asText.contains("\\x");
                    if (nested != null && !printable) {
                        sb.append(field).append(" { ").append(nested).append(" }");
                    } else {
                        sb.append(field).append(": \"").append(asText.replace("\"", "\\\"")).append('"');
                    }
                    pos[0] += len;
                }
                default -> throw new IOException(I18n.t("serde.badWire", wire, field));
            }
        }
        return sb.toString();
    }
}
