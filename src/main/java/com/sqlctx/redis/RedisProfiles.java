package com.sqlctx.redis;

import com.sqlctx.context.ConnectionProfileStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Context Redis lưu ở file riêng {@code ~/.sqlctx/redis.conf} (cùng định dạng INI với connections.conf):
 * <pre>
 * [redis-dev]
 * host=127.0.0.1
 * port=63790
 * db=0
 * password=x
 * tls=false
 * readonly=false
 * # Sentinel: host/port là của sentinel
 * sentinel-master=mymaster
 * sentinel-password=x
 * </pre>
 * Không ghi chung connections.conf: ConnectionProfileStore.save() viết lại cả file theo enum Dialect (chỉ có
 * POSTGRES/ORACLE), lưu 1 context SQL sẽ biến mọi section Redis thành "dialect=postgres".
 */
public final class RedisProfiles {

    private RedisProfiles() {
    }

    static Path file() {
        return ConnectionProfileStore.configDir().resolve("redis.conf");
    }

    public static List<RedisProfile> loadAll() {
        Path file = file();
        if (!Files.exists(file)) {
            return List.of();
        }
        List<RedisProfile> profiles = new ArrayList<>();
        String name = null;
        Map<String, String> fields = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    if (name != null) {
                        profiles.add(toProfile(name, fields));
                    }
                    name = line.substring(1, line.length() - 1).strip();
                    fields = new LinkedHashMap<>();
                } else {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        fields.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                    }
                }
            }
            if (name != null) {
                profiles.add(toProfile(name, fields));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return profiles;
    }

    public static Optional<RedisProfile> find(String name) {
        return loadAll().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }

    /** Ghi đè profile cùng tên hoặc thêm mới, giữ nguyên thứ tự các profile khác. File quyền 600 (có mật khẩu). */
    public static void save(RedisProfile profile) throws IOException {
        List<RedisProfile> profiles = new ArrayList<>(loadAll());
        profiles.removeIf(p -> p.name().equalsIgnoreCase(profile.name()));
        profiles.add(profile);
        StringBuilder sb = new StringBuilder();
        for (RedisProfile p : profiles) {
            sb.append('[').append(p.name()).append("]\n");
            sb.append("host=").append(p.host()).append('\n');
            sb.append("port=").append(p.port()).append('\n');
            sb.append("db=").append(p.db()).append('\n');
            if (p.user() != null && !p.user().isEmpty()) {
                sb.append("user=").append(p.user()).append('\n');
            }
            if (p.password() != null && !p.password().isEmpty()) {
                sb.append("password=").append(p.password()).append('\n');
            }
            sb.append("tls=").append(p.tls()).append('\n');
            if (p.sentinelMaster() != null) {
                sb.append("sentinel-master=").append(p.sentinelMaster()).append('\n');
            }
            if (p.sentinelPassword() != null && !p.sentinelPassword().isEmpty()) {
                sb.append("sentinel-password=").append(p.sentinelPassword()).append('\n');
            }
            sb.append("readonly=").append(p.readonly()).append("\n\n");
        }
        Path file = file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString());
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // không phải POSIX (Windows)
        }
    }

    private static RedisProfile toProfile(String name, Map<String, String> f) {
        return new RedisProfile(name,
                f.getOrDefault("host", "127.0.0.1"),
                parseInt(f.get("port"), 6379),
                f.get("user"),
                f.get("password"),
                parseInt(f.get("db"), 0),
                Boolean.parseBoolean(f.get("tls")),
                Boolean.parseBoolean(f.get("readonly")),
                f.get("sentinel-master"),
                f.get("sentinel-password"));
    }

    static int parseInt(String s, int fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
