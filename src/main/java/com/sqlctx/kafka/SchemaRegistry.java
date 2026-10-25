package com.sqlctx.kafka;

import com.sqlctx.util.I18n;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client REST tối giản của Schema Registry (Confluent, Karapace, Apicurio chế độ tương thích Confluent). */
public final class SchemaRegistry {

    /** Lỗi do Schema Registry trả về (error_code, message) hoặc không gọi được. */
    public static final class SchemaRegistryException extends IOException {
        public SchemaRegistryException(String message) {
            super(message);
        }
    }

    /** Schema khác mà schema này dùng (Protobuf: {@code name} là đường dẫn trong {@code import "..."}). */
    public record Reference(String name, String subject, int version) {
    }

    /** {@code type}: AVRO, JSON, PROTOBUF (REST bỏ trống nghĩa là AVRO). */
    public record Schema(int id, String subject, Integer version, String type, String schema, List<Reference> references) {
    }

    private static List<Reference> references(JsonObject o) {
        List<Reference> out = new ArrayList<>();
        if (o.has("references") && o.get("references").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("references")) {
                JsonObject r = e.getAsJsonObject();
                out.add(new Reference(r.get("name").getAsString(), r.get("subject").getAsString(), r.get("version").getAsInt()));
            }
        }
        return out;
    }

    private final String baseUrl;
    private final String auth;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<Integer, Schema> byId = new ConcurrentHashMap<>();

    public SchemaRegistry(String baseUrl, String userInfo) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.auth = userInfo == null || userInfo.isEmpty() ? null
                : "Basic " + Base64.getEncoder().encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
    }

    public String url() {
        return baseUrl;
    }

    private JsonElement call(String method, String path, JsonObject body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.schemaregistry.v1+json, application/json");
        if (auth != null) {
            b.header("Authorization", auth);
        }
        if (body != null) {
            b.header("Content-Type", "application/vnd.schemaregistry.v1+json");
            b.method(method, HttpRequest.BodyPublishers.ofString(new Gson().toJson(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SchemaRegistryException(I18n.t("sr.cancelled"));
        } catch (IOException e) {
            throw new SchemaRegistryException(I18n.t("sr.unreachable", baseUrl, e.getMessage()));
        }
        JsonElement json;
        try {
            json = r.body().isBlank() ? new JsonObject() : JsonParser.parseString(r.body());
        } catch (RuntimeException e) {
            throw new SchemaRegistryException("HTTP " + r.statusCode() + ": " + r.body());
        }
        if (r.statusCode() >= 300) {
            String msg = json.isJsonObject() && json.getAsJsonObject().has("message")
                    ? json.getAsJsonObject().get("message").getAsString() : r.body();
            throw new SchemaRegistryException("HTTP " + r.statusCode() + ": " + msg);
        }
        return json;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                out.add(x.getAsString());
            }
        }
        return out;
    }

    public List<String> subjects(String prefix, boolean deleted) throws IOException {
        StringBuilder q = new StringBuilder("/subjects?deleted=").append(deleted);
        if (prefix != null) {
            q.append("&subjectPrefix=").append(enc(prefix));
        }
        List<String> out = strings(call("GET", q.toString(), null));
        out.sort(null);
        return out;
    }

    public List<String> versions(String subject, boolean deleted) throws IOException {
        return strings(call("GET", "/subjects/" + enc(subject) + "/versions?deleted=" + deleted, null));
    }

    public Schema schema(String subject, String version) throws IOException {
        JsonObject o = call("GET", "/subjects/" + enc(subject) + "/versions/" + enc(version == null ? "latest" : version),
                null).getAsJsonObject();
        Schema s = new Schema(o.get("id").getAsInt(), subject, o.get("version").getAsInt(),
                o.has("schemaType") ? o.get("schemaType").getAsString() : "AVRO", o.get("schema").getAsString(), references(o));
        byId.putIfAbsent(s.id(), s);
        return s;
    }

    public Schema byId(int id) throws IOException {
        Schema cached = byId.get(id);
        if (cached != null) {
            return cached;
        }
        JsonObject o = call("GET", "/schemas/ids/" + id, null).getAsJsonObject();
        Schema s = new Schema(id, null, null, o.has("schemaType") ? o.get("schemaType").getAsString() : "AVRO",
                o.get("schema").getAsString(), references(o));
        byId.put(id, s);
        return s;
    }

    /** Đăng ký (hoặc lấy id nếu schema đã có ở subject) - như auto.register.schemas của serializer Confluent. */
    public int register(String subject, String schema, String type) throws IOException {
        return register(subject, schema, type, null);
    }

    /** {@code references}: mảng JSON [{"name","subject","version"}] như value.refs của tool Confluent; null = không có. */
    public int register(String subject, String schema, String type, String references) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("schema", schema);
        if (type != null && !type.equalsIgnoreCase("AVRO")) {
            body.addProperty("schemaType", type.toUpperCase());
        }
        if (references != null && !references.isBlank()) {
            try {
                body.add("references", JsonParser.parseString(references).getAsJsonArray());
            } catch (RuntimeException e) {
                throw new SchemaRegistryException(I18n.t("sr.badRefs", references));
            }
        }
        int id = call("POST", "/subjects/" + enc(subject) + "/versions", body).getAsJsonObject().get("id").getAsInt();
        byId.putIfAbsent(id, new Schema(id, subject, null, type == null ? "AVRO" : type.toUpperCase(), schema, references(body)));
        return id;
    }

    /** Version đã xoá. {@code version} null = cả subject. */
    public List<String> delete(String subject, String version, boolean permanent) throws IOException {
        String path = "/subjects/" + enc(subject) + (version != null ? "/versions/" + enc(version) : "")
                + (permanent ? "?permanent=true" : "");
        JsonElement r = call("DELETE", path, null);
        if (r.isJsonPrimitive()) {
            return List.of(r.getAsString());
        }
        return strings(r);
    }

    /** Mức tương thích của subject (hoặc toàn cục nếu {@code subject} null). */
    public String compatibility(String subject) throws IOException {
        JsonObject o = call("GET", subject == null ? "/config" : "/config/" + enc(subject) + "?defaultToGlobal=true", null)
                .getAsJsonObject();
        return o.has("compatibilityLevel") ? o.get("compatibilityLevel").getAsString()
                : o.has("compatibility") ? o.get("compatibility").getAsString() : o.toString();
    }

    public String setCompatibility(String subject, String level) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("compatibility", level.toUpperCase());
        JsonObject o = call("PUT", subject == null ? "/config" : "/config/" + enc(subject), body).getAsJsonObject();
        return o.has("compatibility") ? o.get("compatibility").getAsString() : o.toString();
    }

    public List<String> types() throws IOException {
        JsonElement e = call("GET", "/schemas/types", null);
        return e instanceof JsonArray ? strings(e) : List.of("AVRO");
    }
}
