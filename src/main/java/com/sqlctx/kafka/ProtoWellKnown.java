package com.sqlctx.kafka;

import com.sqlctx.util.I18n;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

/**
 * Các kiểu chuẩn google/protobuf/*.proto: file .proto đóng gói sẵn (Schema Registry và serializer của Confluent cũng
 * tự có chúng, schema chỉ ghi {@code import} mà không có reference), và cách ánh xạ JSON riêng của proto3 cho chúng -
 * Timestamp là chuỗi RFC 3339, Duration là "1.5s", wrapper là giá trị trần, Struct/Value/ListValue là JSON tuỳ ý...
 * {@link #toCanonical} đổi JSON người dùng gõ sang dạng object theo field; {@link #fromCanonical} làm ngược lại.
 */
final class ProtoWellKnown {

    private ProtoWellKnown() {
    }

    static final Map<String, String> SOURCES = Map.of(
            "google/protobuf/timestamp.proto",
            "syntax = \"proto3\"; package google.protobuf; message Timestamp { int64 seconds = 1; int32 nanos = 2; }",
            "google/protobuf/duration.proto",
            "syntax = \"proto3\"; package google.protobuf; message Duration { int64 seconds = 1; int32 nanos = 2; }",
            "google/protobuf/empty.proto",
            "syntax = \"proto3\"; package google.protobuf; message Empty { }",
            "google/protobuf/field_mask.proto",
            "syntax = \"proto3\"; package google.protobuf; message FieldMask { repeated string paths = 1; }",
            "google/protobuf/any.proto",
            "syntax = \"proto3\"; package google.protobuf; message Any { string type_url = 1; bytes value = 2; }",
            "google/protobuf/struct.proto", """
                    syntax = "proto3"; package google.protobuf;
                    message Struct { map<string, Value> fields = 1; }
                    message Value { oneof kind { NullValue null_value = 1; double number_value = 2; string string_value = 3;
                      bool bool_value = 4; Struct struct_value = 5; ListValue list_value = 6; } }
                    enum NullValue { NULL_VALUE = 0; }
                    message ListValue { repeated Value values = 1; }
                    """,
            "google/protobuf/wrappers.proto", """
                    syntax = "proto3"; package google.protobuf;
                    message DoubleValue { double value = 1; } message FloatValue { float value = 1; }
                    message Int64Value { int64 value = 1; } message UInt64Value { uint64 value = 1; }
                    message Int32Value { int32 value = 1; } message UInt32Value { uint32 value = 1; }
                    message BoolValue { bool value = 1; } message StringValue { string value = 1; }
                    message BytesValue { bytes value = 1; }
                    """);

    private static final String P = "google.protobuf.";
    private static final Set<String> WRAPPERS = Set.of(P + "DoubleValue", P + "FloatValue", P + "Int64Value",
            P + "UInt64Value", P + "Int32Value", P + "UInt32Value", P + "BoolValue", P + "StringValue", P + "BytesValue");
    private static final Set<String> SPECIAL = Set.of(P + "Timestamp", P + "Duration", P + "Empty", P + "FieldMask",
            P + "Any", P + "Struct", P + "Value", P + "ListValue");

    static boolean special(String fullName) {
        return SPECIAL.contains(fullName) || WRAPPERS.contains(fullName);
    }

    // ------------------------------------------------------------------ JSON người dùng -> object theo field

    static JsonElement toCanonical(String type, JsonElement json) throws IOException {
        if (WRAPPERS.contains(type)) {
            return object("value", json);
        }
        switch (type.substring(P.length())) {
            case "Timestamp" -> {
                Instant t = timestamp(json.getAsString());
                JsonObject o = new JsonObject();
                o.addProperty("seconds", String.valueOf(t.getEpochSecond()));
                o.addProperty("nanos", t.getNano());
                return o;
            }
            case "Duration" -> {
                String s = json.getAsString();
                if (!s.endsWith("s")) {
                    throw new IOException(I18n.t("proto.badDuration", s));
                }
                BigDecimal d;
                try {
                    d = new BigDecimal(s.substring(0, s.length() - 1));
                } catch (NumberFormatException e) {
                    throw new IOException(I18n.t("proto.badDuration", s));
                }
                long seconds = d.longValue();
                JsonObject o = new JsonObject();
                o.addProperty("seconds", String.valueOf(seconds));
                o.addProperty("nanos", d.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).intValue());
                return o;
            }
            case "FieldMask" -> {
                JsonArray paths = new JsonArray();
                for (String p : json.getAsString().split(",")) {
                    if (!p.isBlank()) {
                        paths.add(snake(p.strip()));
                    }
                }
                return object("paths", paths);
            }
            case "Struct" -> {
                if (!json.isJsonObject()) {
                    throw new IOException(I18n.t("proto.needObject", type));
                }
                return object("fields", json);
            }
            case "ListValue" -> {
                if (!json.isJsonArray()) {
                    throw new IOException(I18n.t("proto.badValue", type, json));
                }
                return object("values", json);
            }
            case "Value" -> {
                if (json.isJsonNull()) {
                    return object("nullValue", new JsonPrimitive("NULL_VALUE"));
                }
                if (json.isJsonObject()) {
                    return object("structValue", json);
                }
                if (json.isJsonArray()) {
                    return object("listValue", json);
                }
                JsonPrimitive p = json.getAsJsonPrimitive();
                return p.isBoolean() ? object("boolValue", p) : p.isNumber() ? object("numberValue", p) : object("stringValue", p);
            }
            case "Any" -> throw new IOException(I18n.t("proto.unsupported", "google.protobuf.Any"));
            default -> {
                return json; // Empty: {}
            }
        }
    }

    /** "fooBar" -> "foo_bar": FieldMask trong JSON viết lowerCamelCase, trên dây là tên gốc. */
    private static String snake(String path) {
        StringBuilder sb = new StringBuilder();
        for (char c : path.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Instant timestamp(String s) throws IOException {
        try {
            return OffsetDateTime.parse(s).toInstant(); // 2024-01-01T10:00:00Z, ...+07:00, phần lẻ tới nano
        } catch (DateTimeParseException e) {
            throw new IOException(I18n.t("proto.badTimestamp", s));
        }
    }

    private static JsonObject object(String key, JsonElement value) {
        JsonObject o = new JsonObject();
        o.add(key, value);
        return o;
    }

    // ------------------------------------------------------------------ object theo field -> JSON như JsonFormat

    /** {@code o}: các field đã giải mã (kể cả giá trị mặc định); field lồng nhau đã được đổi trước. */
    static JsonElement fromCanonical(String type, JsonObject o) {
        if (WRAPPERS.contains(type)) {
            return o.has("value") ? o.get("value") : JsonNull.INSTANCE;
        }
        switch (type.substring(P.length())) {
            case "Timestamp" -> {
                return new JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(
                        Instant.ofEpochSecond(longOf(o, "seconds"), longOf(o, "nanos"))));
            }
            case "Duration" -> {
                long seconds = longOf(o, "seconds");
                long nanos = longOf(o, "nanos");
                String sign = seconds < 0 || nanos < 0 ? "-" : "";
                return new JsonPrimitive(sign + Math.abs(seconds) + fraction(Math.abs(nanos)) + "s");
            }
            case "FieldMask" -> {
                StringBuilder sb = new StringBuilder();
                if (o.has("paths")) {
                    for (JsonElement p : o.getAsJsonArray("paths")) {
                        sb.append(sb.isEmpty() ? "" : ",").append(ProtoSchema.jsonName(p.getAsString()));
                    }
                }
                return new JsonPrimitive(sb.toString());
            }
            case "Struct" -> {
                return o.has("fields") ? o.get("fields") : new JsonObject();
            }
            case "ListValue" -> {
                return o.has("values") ? o.get("values") : new JsonArray();
            }
            case "Value" -> {
                for (String k : new String[]{"numberValue", "stringValue", "boolValue", "structValue", "listValue"}) {
                    if (o.has(k)) {
                        return o.get(k);
                    }
                }
                return JsonNull.INSTANCE;
            }
            case "Any" -> {
                JsonObject a = new JsonObject();
                a.add("@type", o.has("typeUrl") ? o.get("typeUrl") : new JsonPrimitive(""));
                a.add("value", o.has("value") ? o.get("value") : new JsonPrimitive(""));
                return a;
            }
            default -> {
                return new JsonObject();
            }
        }
    }

    private static long longOf(JsonObject o, String key) {
        return o.has(key) ? Long.parseLong(o.get(key).getAsString()) : 0;
    }

    /** Như JsonFormat: 0, 3, 6 hoặc 9 chữ số lẻ. */
    private static String fraction(long nanos) {
        if (nanos == 0) {
            return "";
        }
        if (nanos % 1_000_000 == 0) {
            return String.format(".%03d", nanos / 1_000_000);
        }
        if (nanos % 1_000 == 0) {
            return String.format(".%06d", nanos / 1_000);
        }
        return String.format(".%09d", nanos);
    }
}
