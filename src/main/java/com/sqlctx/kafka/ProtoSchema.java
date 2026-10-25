package com.sqlctx.kafka;

import com.sqlctx.util.I18n;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Đọc file .proto (proto3, proto2 cơ bản) đủ để chuyển JSON <-> protobuf binary mà không cần protoc: message lồng
 * nhau, enum, oneof, map, repeated (packed/không packed), import sang file khác ({@link Imports}: reference của
 * Schema Registry; google/protobuf/*.proto có sẵn trong {@link ProtoWellKnown}). Không hỗ trợ extension, group của
 * proto2 - gặp kiểu không tìm thấy thì báo lỗi rõ ràng. JSON theo cách ánh xạ chuẩn của proto3 (JsonFormat): tên
 * field dạng lowerCamelCase (nhận cả tên gốc), số 64 bit là chuỗi, enum là tên, bytes là base64; khi đọc ra in cả
 * field mang giá trị mặc định như console consumer của Confluent (trừ field có presence: oneof, optional, message).
 */
final class ProtoSchema {

    /** {@code presence}: không giá trị thì không in mặc định (oneof, {@code optional}, proto2, kiểu message). */
    record Field(String name, String jsonName, int number, String type, boolean repeated, boolean packed,
                 String keyType, String valueType, boolean presence) {

        boolean isMap() {
            return keyType != null;
        }
    }

    static final class Message {
        final String fullName;
        final List<Field> fields = new ArrayList<>();
        final List<Message> nested = new ArrayList<>();
        final boolean proto3;

        Message(String fullName, boolean proto3) {
            this.fullName = fullName;
            this.proto3 = proto3;
        }

        Field byNumber(int n) {
            for (Field f : fields) {
                if (f.number() == n) {
                    return f;
                }
            }
            return null;
        }

        Field byName(String n) {
            for (Field f : fields) {
                if (f.name().equals(n) || f.jsonName().equals(n)) {
                    return f;
                }
            }
            return null;
        }
    }

    record EnumType(String fullName, Map<String, Integer> values) {

        String name(int number) {
            for (var e : values.entrySet()) {
                if (e.getValue() == number) {
                    return e.getKey();
                }
            }
            return null;
        }
    }

    private static final Set<String> SCALARS = Set.of("double", "float", "int32", "int64", "uint32", "uint64", "sint32",
            "sint64", "fixed32", "fixed64", "sfixed32", "sfixed64", "bool", "string", "bytes");
    private static final Set<String> PACKABLE = Set.of("double", "float", "int32", "int64", "uint32", "uint64", "sint32",
            "sint64", "fixed32", "fixed64", "sfixed32", "sfixed64", "bool");
    private static final Set<String> SIXTY_FOUR = Set.of("int64", "uint64", "sint64", "fixed64", "sfixed64");

    final List<Message> topLevel = new ArrayList<>();
    final Map<String, Message> messages = new LinkedHashMap<>();
    final Map<String, EnumType> enums = new LinkedHashMap<>();
    private String pkg = "";
    private boolean proto3;
    private final List<String> imports = new ArrayList<>();

    private ProtoSchema() {
    }

    // ================================================================== đọc file .proto

    private List<String> toks;
    private int at;

    /** Nội dung file được import theo đường dẫn trong {@code import "..."}; null nếu không có. */
    interface Imports {
        String read(String path) throws IOException;
    }

    static ProtoSchema parse(String text) throws IOException {
        return parse(text, path -> null);
    }

    /** {@code text}: file chính (chỉ số message kiểu Confluent tính trong file này); file import đọc qua {@code imports}. */
    static ProtoSchema parse(String text, Imports imports) throws IOException {
        ProtoSchema s = new ProtoSchema();
        List<String> missing = new ArrayList<>();
        try {
            s.parseFile(text, true);
            java.util.Set<String> done = new java.util.HashSet<>();
            for (int i = 0; i < s.imports.size(); i++) { // danh sách dài thêm khi file import lại import tiếp
                String path = s.imports.get(i);
                if (!done.add(path)) {
                    continue;
                }
                String content = imports.read(path);
                if (content == null) {
                    content = ProtoWellKnown.SOURCES.get(path);
                }
                if (content == null) {
                    missing.add(path);
                } else {
                    s.parseFile(content, false);
                }
            }
            s.resolve(missing);
        } catch (IndexOutOfBoundsException e) {
            throw new IOException(I18n.t("proto.unexpectedEnd"));
        }
        if (s.topLevel.isEmpty()) {
            throw new IOException(I18n.t("proto.noMessage"));
        }
        s.toks = null;
        return s;
    }

    private boolean main;

    private void parseFile(String text, boolean mainFile) throws IOException {
        toks = tokenize(text);
        at = 0;
        pkg = "";
        proto3 = false; // không có dòng syntax thì protoc coi là proto2
        main = mainFile;
        file();
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (s.startsWith("//", i)) {
                int e = s.indexOf('\n', i);
                i = e < 0 ? n : e + 1;
            } else if (s.startsWith("/*", i)) {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                out.add(s.substring(i, Math.min(n, j + 1)));
                i = j + 1;
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '+') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '.')) {
                    j++;
                }
                out.add(s.substring(i, j));
                i = j;
            } else {
                out.add(String.valueOf(c));
                i++;
            }
        }
        return out;
    }

    private String peek() {
        return at < toks.size() ? toks.get(at) : "";
    }

    private String next() {
        return toks.get(at++);
    }

    private void expect(String t) throws IOException {
        String got = next();
        if (!got.equals(t)) {
            throw new IOException(I18n.t("proto.expected", t, got));
        }
    }

    /** Bỏ tới hết câu (;) hoặc hết khối {...} cân bằng. */
    private void skipStatement() {
        int depth = 0;
        while (at < toks.size()) {
            String t = next();
            if (t.equals("{")) {
                depth++;
            } else if (t.equals("}")) {
                if (--depth <= 0) {
                    return;
                }
            } else if (t.equals(";") && depth == 0) {
                return;
            }
        }
    }

    private void file() throws IOException {
        while (at < toks.size()) {
            String t = next();
            switch (t) {
                case "syntax", "edition" -> {
                    expect("=");
                    proto3 = !next().contains("proto2");
                    expect(";");
                }
                case "package" -> {
                    pkg = next();
                    expect(";");
                }
                case "message" -> {
                    Message m = parseMessage(pkg);
                    if (main) {
                        topLevel.add(m);
                    }
                }
                case "import" -> {
                    String path = next();
                    if (path.equals("public") || path.equals("weak")) {
                        path = next();
                    }
                    imports.add(path.substring(1, path.length() - 1));
                    expect(";");
                }
                case "enum" -> enumType(pkg);
                case ";" -> {
                }
                default -> skipStatement(); // option, service, extend
            }
        }
    }

    private static String join(String scope, String name) {
        return scope.isEmpty() ? name : scope + "." + name;
    }

    private Message parseMessage(String scope) throws IOException {
        Message m = new Message(join(scope, next()), proto3);
        messages.put(m.fullName, m);
        expect("{");
        body(m);
        return m;
    }

    private void body(Message m) throws IOException {
        while (!peek().equals("}")) {
            String t = peek();
            switch (t) {
                case "message" -> {
                    next();
                    m.nested.add(parseMessage(m.fullName));
                }
                case "enum" -> {
                    next();
                    enumType(m.fullName);
                }
                case "oneof" -> {
                    next();
                    next(); // tên oneof: trên dây các field của oneof là field thường
                    expect("{");
                    while (!peek().equals("}")) {
                        if (peek().equals("option")) {
                            skipStatement();
                        } else {
                            field(m, false, true);
                        }
                    }
                    next();
                }
                case "option", "reserved", "extensions", "extend" -> skipStatement();
                case ";" -> next();
                case "group" -> throw new IOException(I18n.t("proto.unsupported", "group"));
                default -> {
                    boolean repeated = false;
                    boolean optional = false;
                    if (t.equals("repeated") || t.equals("optional") || t.equals("required")) {
                        String label = next();
                        repeated = label.equals("repeated");
                        optional = label.equals("optional");
                    }
                    if (peek().equals("group")) {
                        throw new IOException(I18n.t("proto.unsupported", "group"));
                    }
                    field(m, repeated, optional || !proto3);
                }
            }
        }
        next();
    }

    /** {@code presence}: oneof, {@code optional} hoặc proto2 - xem {@link Field}. */
    private void field(Message m, boolean repeated, boolean presence) throws IOException {
        String type = next();
        String keyType = null;
        String valueType = null;
        if (type.equals("map")) {
            expect("<");
            keyType = next();
            expect(",");
            valueType = next();
            expect(">");
            repeated = true;
        }
        String name = next();
        expect("=");
        int number = Integer.parseInt(next());
        Boolean packedOption = null;
        if (peek().equals("[")) {
            next();
            while (!peek().equals("]")) {
                String o = next();
                if (o.equals("packed")) {
                    expect("=");
                    packedOption = next().equals("true");
                }
            }
            next();
        }
        expect(";");
        boolean packed = repeated && keyType == null && PACKABLE.contains(type)
                && (packedOption != null ? packedOption : proto3);
        m.fields.add(new Field(name, jsonName(name), number, type, repeated, packed, keyType, valueType, presence && !repeated));
    }

    private void enumType(String scope) throws IOException {
        String name = join(scope, next());
        expect("{");
        Map<String, Integer> values = new LinkedHashMap<>();
        while (!peek().equals("}")) {
            String t = next();
            if (t.equals("option") || t.equals("reserved")) {
                at--;
                skipStatement();
                continue;
            }
            if (t.equals(";")) {
                continue;
            }
            expect("=");
            values.put(t, Integer.decode(next()));
            if (peek().equals("[")) {
                while (!next().equals("]")) {
                    // bỏ tuỳ chọn của giá trị enum
                }
            }
            expect(";");
        }
        next();
        enums.put(name, new EnumType(name, values));
    }

    /** "user_id" -> "userId" như protoc. */
    static String jsonName(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    /** Đổi tên kiểu trong field thành tên đầy đủ, theo luật tìm kiếm của protoc (từ trong ra ngoài). */
    private void resolve(List<String> missingImports) throws IOException {
        for (Message m : messages.values()) {
            for (int i = 0; i < m.fields.size(); i++) {
                Field f = m.fields.get(i);
                String type = f.isMap() ? f.type() : resolveType(f.type(), m.fullName, missingImports);
                String valueType = f.isMap() ? resolveType(f.valueType(), m.fullName, missingImports) : null;
                m.fields.set(i, new Field(f.name(), f.jsonName(), f.number(), type, f.repeated(),
                        f.packed() || f.repeated() && !f.isMap() && m.proto3 && enums.containsKey(type),
                        f.keyType(), valueType, f.presence() || !f.repeated() && messages.containsKey(type)));
            }
        }
    }

    private String resolveType(String type, String scope, List<String> missingImports) throws IOException {
        if (SCALARS.contains(type)) {
            return type;
        }
        if (type.startsWith(".")) {
            String full = type.substring(1);
            if (messages.containsKey(full) || enums.containsKey(full)) {
                return full;
            }
        } else {
            String s = scope;
            while (true) {
                String candidate = join(s, type);
                if (messages.containsKey(candidate) || enums.containsKey(candidate)) {
                    return candidate;
                }
                if (s.isEmpty()) {
                    break;
                }
                int dot = s.lastIndexOf('.');
                s = dot < 0 ? "" : s.substring(0, dot);
            }
        }
        throw new IOException(missingImports.isEmpty() ? I18n.t("proto.unknownType", type)
                : I18n.t("proto.missingImport", type, String.join(", ", missingImports)));
    }

    // ================================================================== chọn message, chỉ số kiểu Confluent

    /** Theo tên ngắn hoặc đầy đủ; null = message đầu tiên của file (như serializer của Confluent). */
    Message message(String name) throws IOException {
        if (name == null || name.isEmpty()) {
            return topLevel.get(0);
        }
        if (messages.containsKey(name)) {
            return messages.get(name);
        }
        for (Message m : messages.values()) {
            if (m.fullName.endsWith("." + name)) {
                return m;
            }
        }
        throw new IOException(I18n.t("proto.noSuchMessage", name, String.join(", ", messages.keySet())));
    }

    /** Đường đi tới message: chỉ số trong file, rồi trong message cha... ([0] = message đầu tiên). */
    List<Integer> indexes(Message target) {
        List<Integer> path = new ArrayList<>();
        find(topLevel, target, path);
        return path;
    }

    private static boolean find(List<Message> level, Message target, List<Integer> path) {
        for (int i = 0; i < level.size(); i++) {
            path.add(i);
            if (level.get(i) == target || find(level.get(i).nested, target, path)) {
                return true;
            }
            path.remove(path.size() - 1);
        }
        return false;
    }

    Message byIndexes(List<Integer> idx) throws IOException {
        List<Message> level = topLevel;
        Message m = null;
        for (int i : idx) {
            if (i < 0 || i >= level.size()) {
                throw new IOException(I18n.t("proto.badIndex", idx));
            }
            m = level.get(i);
            level = m.nested;
        }
        return m == null ? topLevel.get(0) : m;
    }

    // ================================================================== JSON -> binary

    byte[] encode(Message m, JsonElement json) throws IOException {
        if (ProtoWellKnown.special(m.fullName)) {
            json = ProtoWellKnown.toCanonical(m.fullName, json);
        }
        return encodePlain(m, json);
    }

    private byte[] encodePlain(Message m, JsonElement json) throws IOException {
        if (!json.isJsonObject()) {
            throw new IOException(I18n.t("proto.needObject", m.fullName));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (var e : json.getAsJsonObject().entrySet()) {
            Field f = m.byName(e.getKey());
            if (f == null) {
                throw new IOException(I18n.t("proto.unknownField", m.fullName, e.getKey()));
            }
            JsonElement v = e.getValue();
            if (v.isJsonNull()) {
                continue;
            }
            try {
                writeField(out, f, v);
            } catch (NumberFormatException | IllegalStateException | UnsupportedOperationException | ArithmeticException e2) {
                throw new IOException(I18n.t("proto.badValue", m.fullName + "." + f.name(), v));
            }
        }
        return out.toByteArray();
    }

    private void writeField(ByteArrayOutputStream out, Field f, JsonElement v) throws IOException {
        if (f.isMap()) {
            if (!v.isJsonObject()) {
                throw new IOException(I18n.t("proto.badValue", f.name(), v));
            }
            for (var e : v.getAsJsonObject().entrySet()) {
                ByteArrayOutputStream entry = new ByteArrayOutputStream();
                writeSingle(entry, 1, f.keyType(), new JsonPrimitive(e.getKey()));
                if (!e.getValue().isJsonNull()) {
                    writeSingle(entry, 2, f.valueType(), e.getValue());
                }
                tag(out, f.number(), 2);
                varint(out, entry.size());
                out.writeBytes(entry.toByteArray());
            }
            return;
        }
        if (f.repeated()) {
            JsonArray items = v.isJsonArray() ? v.getAsJsonArray() : single(v);
            if (f.packed()) {
                ByteArrayOutputStream packed = new ByteArrayOutputStream();
                for (JsonElement item : items) {
                    writeValue(packed, f.type(), item);
                }
                tag(out, f.number(), 2);
                varint(out, packed.size());
                out.writeBytes(packed.toByteArray());
            } else {
                for (JsonElement item : items) {
                    writeSingle(out, f.number(), f.type(), item);
                }
            }
            return;
        }
        writeSingle(out, f.number(), f.type(), v);
    }

    private static JsonArray single(JsonElement v) {
        JsonArray a = new JsonArray();
        a.add(v);
        return a;
    }

    private int wireType(String type) {
        return switch (type) {
            case "double", "fixed64", "sfixed64" -> 1;
            case "float", "fixed32", "sfixed32" -> 5;
            case "string", "bytes" -> 2;
            default -> messages.containsKey(type) ? 2 : 0;
        };
    }

    private void writeSingle(ByteArrayOutputStream out, int number, String type, JsonElement v) throws IOException {
        tag(out, number, wireType(type));
        writeValue(out, type, v);
    }

    private void writeValue(ByteArrayOutputStream out, String type, JsonElement v) throws IOException {
        switch (type) {
            case "int32", "int64", "uint32", "uint64" -> varint(out, integer(v));
            case "sint32", "sint64" -> {
                long x = integer(v);
                varint(out, (x << 1) ^ (x >> 63));
            }
            case "bool" -> varint(out, v.getAsBoolean() ? 1 : 0);
            case "fixed64", "sfixed64" -> out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(integer(v)).array());
            case "fixed32", "sfixed32" -> out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) integer(v)).array());
            case "double" -> out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(real(v)).array());
            case "float" -> out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat((float) real(v)).array());
            case "string" -> lengthDelimited(out, v.getAsString().getBytes(StandardCharsets.UTF_8));
            case "bytes" -> {
                String s = v.getAsString();
                lengthDelimited(out, s.contains("-") || s.contains("_") ? Base64.getUrlDecoder().decode(s) : Base64.getDecoder().decode(s));
            }
            default -> {
                EnumType en = enums.get(type);
                if (en != null) {
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                        varint(out, v.getAsInt());
                    } else {
                        Integer n = en.values().get(v.getAsString());
                        if (n == null) {
                            throw new IOException(I18n.t("proto.badEnum", v.getAsString(), type, String.join(", ", en.values().keySet())));
                        }
                        varint(out, n);
                    }
                } else {
                    lengthDelimited(out, encode(messages.get(type), v));
                }
            }
        }
    }

    private static long integer(JsonElement v) {
        String s = v.getAsString();
        return s.contains(".") || s.contains("e") || s.contains("E") ? (long) Double.parseDouble(s) : Long.parseLong(s);
    }

    private static double real(JsonElement v) {
        return switch (v.getAsString()) {
            case "NaN" -> Double.NaN;
            case "Infinity" -> Double.POSITIVE_INFINITY;
            case "-Infinity" -> Double.NEGATIVE_INFINITY;
            default -> v.getAsDouble();
        };
    }

    private static void tag(ByteArrayOutputStream out, int number, int wire) {
        varint(out, ((long) number << 3) | wire);
    }

    static void varint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7fL) != 0) {
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static void lengthDelimited(ByteArrayOutputStream out, byte[] b) {
        varint(out, b.length);
        out.writeBytes(b);
    }

    // ================================================================== binary -> JSON

    /** Như JsonFormat của protobuf: field theo thứ tự khai báo, kiểu chuẩn (Timestamp...) theo cách viết riêng của chúng. */
    JsonElement decode(Message m, byte[] b, int from, int to) throws IOException {
        JsonObject o = decodePlain(m, b, from, to);
        JsonObject ordered = new JsonObject();
        for (Field f : m.fields) {
            if (o.has(f.jsonName())) {
                ordered.add(f.jsonName(), o.get(f.jsonName()));
            } else if (!f.presence()) {
                ordered.add(f.jsonName(), f.isMap() ? new JsonObject() : f.repeated() ? new JsonArray() : defaultValue(f.type()));
            }
        }
        return ProtoWellKnown.special(m.fullName) ? ProtoWellKnown.fromCanonical(m.fullName, ordered) : ordered;
    }

    private JsonObject decodePlain(Message m, byte[] b, int from, int to) throws IOException {
        JsonObject o = new JsonObject();
        int[] pos = {from};
        while (pos[0] < to) {
            long key = KafkaSerde.readVarint(b, pos);
            int number = (int) (key >>> 3);
            int wire = (int) (key & 7);
            Field f = m.byNumber(number);
            if (f == null) {
                skip(b, pos, to, wire, number); // field không có trong schema: JsonFormat cũng bỏ qua
                continue;
            }
            if (f.isMap()) {
                int len = length(b, pos, to, number);
                Message entry = new Message(f.name(), true);
                entry.fields.add(new Field("key", "key", 1, f.keyType(), false, false, null, null, true));
                entry.fields.add(new Field("value", "value", 2, f.valueType(), false, false, null, null, true));
                JsonObject e = decodePlain(entry, b, pos[0], pos[0] + len);
                pos[0] += len;
                if (!o.has(f.jsonName())) {
                    o.add(f.jsonName(), new JsonObject());
                }
                JsonElement k = e.get("key");
                JsonElement val = e.has("value") ? e.get("value") : defaultValue(f.valueType());
                o.getAsJsonObject(f.jsonName()).add(k == null ? defaultKey(f.keyType()) : k.getAsString(), val);
                continue;
            }
            if (f.repeated()) {
                if (!o.has(f.jsonName())) {
                    o.add(f.jsonName(), new JsonArray());
                }
                JsonArray arr = o.getAsJsonArray(f.jsonName());
                if (wire == 2 && PACKABLE.contains(f.type()) || wire == 2 && enums.containsKey(f.type())) {
                    int len = length(b, pos, to, number);
                    int end = pos[0] + len;
                    while (pos[0] < end) {
                        arr.add(readValue(f.type(), b, pos, end, number));
                    }
                } else {
                    arr.add(readValue(f.type(), b, pos, to, number));
                }
                continue;
            }
            o.add(f.jsonName(), readValue(f.type(), b, pos, to, number));
        }
        return o;
    }

    private static String defaultKey(String type) {
        return type.equals("bool") ? "false" : type.equals("string") ? "" : "0";
    }

    private JsonElement defaultValue(String type) {
        if (messages.containsKey(type)) {
            return new JsonObject();
        }
        EnumType en = enums.get(type);
        if (en != null) {
            String n = en.name(0);
            return n == null ? new JsonPrimitive(0) : new JsonPrimitive(n);
        }
        return switch (type) {
            case "string", "bytes" -> new JsonPrimitive("");
            case "bool" -> new JsonPrimitive(false);
            default -> SIXTY_FOUR.contains(type) ? new JsonPrimitive("0") : new JsonPrimitive(0);
        };
    }

    private JsonElement readValue(String type, byte[] b, int[] pos, int to, int number) throws IOException {
        switch (type) {
            case "int32" -> {
                return new JsonPrimitive((int) KafkaSerde.readVarint(b, pos));
            }
            case "uint32" -> {
                return new JsonPrimitive(KafkaSerde.readVarint(b, pos) & 0xffffffffL);
            }
            case "int64" -> {
                return new JsonPrimitive(String.valueOf(KafkaSerde.readVarint(b, pos)));
            }
            case "uint64" -> {
                return new JsonPrimitive(Long.toUnsignedString(KafkaSerde.readVarint(b, pos)));
            }
            case "sint32" -> {
                long v = KafkaSerde.readVarint(b, pos);
                return new JsonPrimitive((int) ((v >>> 1) ^ -(v & 1)));
            }
            case "sint64" -> {
                long v = KafkaSerde.readVarint(b, pos);
                return new JsonPrimitive(String.valueOf((v >>> 1) ^ -(v & 1)));
            }
            case "bool" -> {
                return new JsonPrimitive(KafkaSerde.readVarint(b, pos) != 0);
            }
            case "fixed64", "sfixed64", "double" -> {
                need(pos[0], 8, to, number);
                ByteBuffer bb = ByteBuffer.wrap(b, pos[0], 8).order(ByteOrder.LITTLE_ENDIAN);
                pos[0] += 8;
                return type.equals("double") ? number(bb.getDouble())
                        : new JsonPrimitive(type.equals("fixed64") ? Long.toUnsignedString(bb.getLong()) : String.valueOf(bb.getLong()));
            }
            case "fixed32", "sfixed32", "float" -> {
                need(pos[0], 4, to, number);
                ByteBuffer bb = ByteBuffer.wrap(b, pos[0], 4).order(ByteOrder.LITTLE_ENDIAN);
                pos[0] += 4;
                return type.equals("float") ? number(bb.getFloat())
                        : new JsonPrimitive(type.equals("fixed32") ? bb.getInt() & 0xffffffffL : bb.getInt());
            }
            case "string", "bytes" -> {
                int len = length(b, pos, to, number);
                byte[] slice = java.util.Arrays.copyOfRange(b, pos[0], pos[0] + len);
                pos[0] += len;
                return new JsonPrimitive(type.equals("string") ? new String(slice, StandardCharsets.UTF_8)
                        : Base64.getEncoder().encodeToString(slice));
            }
            default -> {
                EnumType en = enums.get(type);
                if (en != null) {
                    int v = (int) KafkaSerde.readVarint(b, pos);
                    String n = en.name(v);
                    return n == null ? new JsonPrimitive(v) : new JsonPrimitive(n);
                }
                int len = length(b, pos, to, number);
                JsonElement nested = decode(messages.get(type), b, pos[0], pos[0] + len);
                pos[0] += len;
                return nested;
            }
        }
    }

    private static JsonElement number(double d) {
        if (Double.isNaN(d)) {
            return new JsonPrimitive("NaN");
        }
        if (Double.isInfinite(d)) {
            return new JsonPrimitive(d > 0 ? "Infinity" : "-Infinity");
        }
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? new JsonPrimitive((long) d) : new JsonPrimitive(d);
    }

    private static int length(byte[] b, int[] pos, int to, int number) throws IOException {
        long len = KafkaSerde.readVarint(b, pos);
        if (len < 0 || pos[0] + len > to) {
            throw new IOException(I18n.t("serde.fieldTooLong", number));
        }
        return (int) len;
    }

    private static void need(int pos, int len, int to, int number) throws IOException {
        if (pos + len > to) {
            throw new IOException(I18n.t("serde.fieldCut", number));
        }
    }

    private static void skip(byte[] b, int[] pos, int to, int wire, int number) throws IOException {
        switch (wire) {
            case 0 -> KafkaSerde.readVarint(b, pos);
            case 1 -> {
                need(pos[0], 8, to, number);
                pos[0] += 8;
            }
            case 5 -> {
                need(pos[0], 4, to, number);
                pos[0] += 4;
            }
            case 2 -> pos[0] += length(b, pos, to, number);
            default -> throw new IOException(I18n.t("serde.badWire", wire, number));
        }
    }
}
