package com.sqlctx.redis;

import com.sqlctx.util.I18n;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sqlctx.redis.RespClient.Bulk;
import com.sqlctx.redis.RespClient.RespError;
import com.sqlctx.redis.RespClient.Simple;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Cây lệnh của server đang nối: COMMAND DOCS (cây tham số, mô tả) + COMMAND INFO (cờ write/blocking...), lấy lúc
 * kết nối nên luôn đúng phiên bản và module của server đó. Cache ở {@code ~/.sqlctx/redis-docs/} theo
 * tên-phiên bản-số lệnh (nạp module làm đổi số lệnh). Server không trả COMMAND DOCS (Redis < 7.0, ACL chặn
 * lệnh COMMAND) thì dùng bản đóng gói sẵn từ Valkey - xem tools/redis-fallback/update.sh.
 * <p>
 * Định dạng JSON (cache và bản đóng gói) giống {@code redis-cli --json COMMAND DOCS / COMMAND INFO}.
 */
public final class RedisCommandTree {

    public enum ArgType {
        KEY, STRING, INTEGER, DOUBLE, UNIX_TIME, PATTERN, PURE_TOKEN, ONEOF, BLOCK
    }

    /** 1 nút của cây tham số, đúng các trường của COMMAND DOCS. */
    public record Arg(String name, ArgType type, String token, boolean optional, boolean multiple,
                      boolean multipleToken, String displayText, String since, List<Arg> children) {
    }

    /** {@code name} viết hoa, lệnh con dạng "CONFIG GET"; {@code subcommands} theo tên lệnh con viết hoa ("GET"). */
    public record Command(String name, String group, String summary, String deprecatedSince, String replacedBy,
                          List<Arg> args, Map<String, Command> subcommands, Set<String> flags) {
    }

    private final String source;
    private final Map<String, Command> commands;
    private final Map<String, RedisCommandSpec> specs = new LinkedHashMap<>();
    // theo identity: Command là record lồng sâu, hashCode theo nội dung phải duyệt cả cây tham số mỗi lần tra
    private final Map<Command, RedisArgMatcher.Node> compiled =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private RedisCommandTree(String source, Map<String, Command> commands) {
        this.source = source;
        this.commands = commands;
        for (Command c : commands.values()) {
            specs.put(c.name(), RedisCommandSpec.of(c));
            for (Command sub : c.subcommands().values()) {
                specs.put(sub.name(), RedisCommandSpec.of(sub));
            }
        }
    }

    // ------------------------------------------------------------------ cây đang dùng

    private static volatile RedisCommandTree current;

    /** Cây của server đang nối; chưa nối (test, lúc khởi động) thì là bản đóng gói sẵn. */
    public static RedisCommandTree current() {
        RedisCommandTree t = current;
        return t != null ? t : Fallback.TREE;
    }

    public static void use(RedisCommandTree tree) {
        current = tree;
    }

    private static final class Fallback {
        static final RedisCommandTree TREE = loadFallback();
    }

    public static RedisCommandTree fallback() {
        return Fallback.TREE;
    }

    // ------------------------------------------------------------------ tra cứu

    public String source() {
        return source;
    }

    public Command command(String name) {
        return commands.get(name.toUpperCase(Locale.ROOT));
    }

    public Collection<Command> commands() {
        return commands.values();
    }

    public RedisCommandSpec spec(String fullName) {
        return specs.get(fullName.toUpperCase(Locale.ROOT));
    }

    public Collection<RedisCommandSpec> specs() {
        return specs.values();
    }

    /** Cây tham số đã dịch sang dạng đi được (dịch 1 lần mỗi lệnh, lúc cần). */
    RedisArgMatcher.Node compiled(Command command) {
        return compiled.computeIfAbsent(command, c -> RedisArgMatcher.compile(c.args(), c.name()));
    }

    // ------------------------------------------------------------------ nạp

    /**
     * Lấy cây từ server (qua cache nếu đã có). Mọi lỗi (mất kết nối, ACL chặn, server cũ) đều rơi về bản đóng gói,
     * vì thiếu gợi ý tốt hơn là không mở được REPL.
     */
    public static RedisCommandTree load(RespClient client, Path cacheDir) {
        try {
            Map<String, String> server = infoServer(client);
            boolean valkey = "valkey".equalsIgnoreCase(server.get("server_name"));
            String version = valkey ? server.getOrDefault("valkey_version", "?") : server.getOrDefault("redis_version", "?");
            String name = (valkey ? "valkey" : "redis") + " " + version;
            Object count = client.call("COMMAND", "COUNT");
            if (!(count instanceof Long)) {
                return fallback(); // ACL chặn lệnh COMMAND
            }
            String cacheName = name.replace(' ', '-') + "-" + count + ".json";
            Path cacheFile = cacheDir.resolve(cacheName);
            if (Files.exists(cacheFile)) {
                try {
                    return fromJson(JsonParser.parseString(Files.readString(cacheFile)).getAsJsonObject(), name + " (cache)");
                } catch (RuntimeException e) {
                    Files.deleteIfExists(cacheFile); // cache hỏng: lấy lại từ server
                }
            }
            Object docs = client.call("COMMAND", "DOCS");
            if (!(docs instanceof List<?> docsList) || docsList.isEmpty()) {
                return fallbackFor(client, name);
            }
            Object info = client.call("COMMAND", "INFO");
            if (!(info instanceof List<?> infoList)) {
                return fallbackFor(client, name);
            }
            JsonObject root = new JsonObject();
            root.addProperty("source", name);
            root.add("docs", docsToJson(docsList));
            root.add("info", generic(infoList));
            try {
                Files.createDirectories(cacheDir);
                Files.writeString(cacheFile, new Gson().toJson(root));
            } catch (IOException ignored) {
                // không ghi được cache thì lần sau lấy lại, không sao
            }
            return fromJson(root, name);
        } catch (IOException | RuntimeException e) {
            return fallback();
        }
    }

    /** Server có lệnh COMMAND nhưng không có COMMAND DOCS: dùng cây đóng gói, chỉ giữ lệnh server thật sự có. */
    private static RedisCommandTree fallbackFor(RespClient client, String name) {
        RedisCommandTree base = fallback();
        try {
            String version = name.substring(name.indexOf(' ') + 1);
            Object reply = client.call("COMMAND");
            if (reply instanceof List<?> entries && !entries.isEmpty()) {
                Set<String> names = new HashSet<>();
                for (Object e : entries) {
                    if (e instanceof List<?> entry && !entry.isEmpty()) {
                        names.add(RedisSession.text(entry.get(0)).toUpperCase(Locale.ROOT));
                    }
                }
                Map<String, Command> kept = new TreeMap<>();
                for (Command c : base.commands.values()) {
                    if (names.contains(c.name())) {
                        kept.put(c.name(), olderThan(c, version));
                    }
                }
                return new RedisCommandTree(I18n.t("redis.tree.noDocs", name, base.source), kept);
            }
        } catch (IOException ignored) {
        }
        return base;
    }

    /**
     * Bản đóng gói mới hơn server: bỏ tuỳ chọn và lệnh con ra đời sau phiên bản server (trường "since" của docs), để
     * không gợi ý SET ... IFEQ cho Redis 6.2. Chỉ bỏ được thứ không bắt buộc - tham số bắt buộc luôn cũ bằng lệnh.
     */
    static Command olderThan(Command c, String version) {
        Map<String, Command> subs = new TreeMap<>();
        for (Map.Entry<String, Command> e : c.subcommands().entrySet()) {
            subs.put(e.getKey(), olderThan(e.getValue(), version));
        }
        return new Command(c.name(), c.group(), c.summary(), c.deprecatedSince(), c.replacedBy(),
                olderArgs(c.args(), version, false), subs, c.flags());
    }

    private static List<Arg> olderArgs(List<Arg> args, String version, boolean inOneOf) {
        List<Arg> kept = new ArrayList<>();
        for (Arg a : args) {
            boolean droppable = a.optional() || inOneOf;
            if (droppable && a.since() != null && compareVersions(a.since(), version) > 0) {
                continue;
            }
            kept.add(new Arg(a.name(), a.type(), a.token(), a.optional(), a.multiple(), a.multipleToken(),
                    a.displayText(), a.since(), olderArgs(a.children(), version, a.type() == ArgType.ONEOF)));
        }
        return kept;
    }

    /** "6.2.0" so với "7.4.11" theo từng số; phần không phải số coi là 0. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(num(x, i), num(y, i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int num(String[] parts, int i) {
        try {
            return i < parts.length ? Integer.parseInt(parts[i].replaceAll("\\D.*", "")) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, String> infoServer(RespClient client) throws IOException {
        Map<String, String> values = new TreeMap<>();
        Object reply = client.call("INFO", "server");
        if (reply instanceof Bulk b) {
            for (String line : b.utf8().split("\r?\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    values.put(line.substring(0, colon), line.substring(colon + 1).strip());
                }
            }
        }
        return values;
    }

    private static RedisCommandTree loadFallback() {
        try (InputStream raw = RedisCommandTree.class.getResourceAsStream("/redis/fallback-commands.json.gz")) {
            if (raw == null) {
                return new RedisCommandTree(I18n.t("redis.tree.none"), Map.of());
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return fromJson(root, I18n.t("redis.tree.bundled", root.get("source").getAsString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ JSON -> cây

    static RedisCommandTree fromJson(JsonObject root, String source) {
        Map<String, Set<String>> flags = new java.util.HashMap<>();
        for (JsonElement e : root.getAsJsonArray("info")) {
            collectFlags(e.getAsJsonArray(), flags);
        }
        Map<String, Command> commands = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("docs").entrySet()) {
            Command c = command(e.getKey().toUpperCase(Locale.ROOT), e.getValue().getAsJsonObject(), flags);
            commands.put(c.name(), c);
        }
        return new RedisCommandTree(source, commands);
    }

    /** COMMAND INFO: [tên, arity, [cờ], first, last, step, [acl], [tips], [key specs], [lệnh con]]. */
    private static void collectFlags(JsonArray entry, Map<String, Set<String>> out) {
        String name = entry.get(0).getAsString().toUpperCase(Locale.ROOT).replace('|', ' ');
        Set<String> flags = new HashSet<>();
        if (entry.size() > 2 && entry.get(2).isJsonArray()) {
            for (JsonElement f : entry.get(2).getAsJsonArray()) {
                flags.add(f.getAsString());
            }
        }
        out.put(name, flags);
        if (entry.size() > 9 && entry.get(9).isJsonArray()) {
            for (JsonElement sub : entry.get(9).getAsJsonArray()) {
                collectFlags(sub.getAsJsonArray(), out);
            }
        }
    }

    private static Command command(String name, JsonObject doc, Map<String, Set<String>> flags) {
        Map<String, Command> subs = new TreeMap<>();
        if (doc.has("subcommands") && doc.get("subcommands").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject("subcommands").entrySet()) {
                String raw = e.getKey();
                String subName = raw.substring(raw.indexOf('|') + 1).toUpperCase(Locale.ROOT);
                subs.put(subName, command(name + " " + subName, e.getValue().getAsJsonObject(), flags));
            }
        }
        return new Command(name, str(doc, "group"), str(doc, "summary"), str(doc, "deprecated_since"),
                str(doc, "replaced_by"), args(doc.get("arguments")), subs, flags.getOrDefault(name, Set.of()));
    }

    private static List<Arg> args(JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            return List.of();
        }
        List<Arg> args = new ArrayList<>();
        for (JsonElement a : e.getAsJsonArray()) {
            JsonObject o = a.getAsJsonObject();
            Set<String> f = new HashSet<>();
            if (o.has("flags") && o.get("flags").isJsonArray()) {
                for (JsonElement x : o.getAsJsonArray("flags")) {
                    f.add(x.getAsString());
                }
            }
            ArgType type;
            try {
                type = ArgType.valueOf(str(o, "type").toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException | NullPointerException ex) {
                type = ArgType.STRING; // kiểu mới ở server mới hơn: coi như chuỗi tự do
            }
            args.add(new Arg(str(o, "name"), type, o.has("token") ? o.get("token").getAsString() : null,
                    f.contains("optional"), f.contains("multiple"), f.contains("multiple_token"),
                    str(o, "display_text"), str(o, "since"), args(o.get("arguments"))));
        }
        return List.copyOf(args);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    // ------------------------------------------------------------------ reply RESP -> JSON (cùng dạng redis-cli --json)

    /** Reply của COMMAND DOCS: [tên, {map}, tên, {map}...] - map trong RESP2 là mảng phẳng khoá, giá trị. */
    static JsonObject docsToJson(List<?> flat) {
        JsonObject out = new JsonObject();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            if (flat.get(i + 1) instanceof List<?> entry) {
                out.add(RedisSession.text(flat.get(i)), docEntry(entry));
            }
        }
        return out;
    }

    private static JsonObject docEntry(List<?> map) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < map.size(); i += 2) {
            String key = RedisSession.text(map.get(i));
            Object value = map.get(i + 1);
            switch (key) {
                case "arguments" -> o.add(key, argsToJson(value));
                case "subcommands" -> o.add(key, value instanceof List<?> l ? docsToJson(l) : new JsonObject());
                default -> o.add(key, generic(value));
            }
        }
        return o;
    }

    private static JsonArray argsToJson(Object value) {
        JsonArray out = new JsonArray();
        if (value instanceof List<?> list) {
            for (Object a : list) {
                if (a instanceof List<?> map) {
                    JsonObject o = new JsonObject();
                    for (int i = 0; i + 1 < map.size(); i += 2) {
                        String key = RedisSession.text(map.get(i));
                        o.add(key, key.equals("arguments") ? argsToJson(map.get(i + 1)) : generic(map.get(i + 1)));
                    }
                    out.add(o);
                }
            }
        }
        return out;
    }

    static JsonElement generic(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Long n) {
            return new JsonPrimitive(n);
        }
        if (value instanceof List<?> list) {
            JsonArray a = new JsonArray();
            for (Object o : list) {
                a.add(generic(o));
            }
            return a;
        }
        if (value instanceof Bulk || value instanceof Simple) {
            return new JsonPrimitive(RedisSession.text(value));
        }
        if (value instanceof RespError e) {
            return new JsonPrimitive(e.message());
        }
        return new JsonPrimitive(String.valueOf(value));
    }
}
