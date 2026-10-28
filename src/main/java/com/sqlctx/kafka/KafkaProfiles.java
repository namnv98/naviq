package com.sqlctx.kafka;

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
 * Context Kafka ở {@code ~/.sqlctx/kafka.conf} (INI như connections.conf, file riêng vì cùng lý do với redis.conf):
 * <pre>
 * [kafka-dev]
 * bootstrap.servers=localhost:19092
 * schema.registry.url=http://localhost:18081
 * readonly=false
 * # mọi khoá khác là config của client Kafka, vd SASL/SSL:
 * security.protocol=SASL_SSL
 * sasl.mechanism=SCRAM-SHA-512
 * sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="u" password="p";
 * </pre>
 * {@code schema.registry.basic.auth.user.info=user:pass} cho Schema Registry có xác thực.
 */
public final class KafkaProfiles {

    private KafkaProfiles() {
    }

    /** {@code client}: config truyền thẳng cho Admin/Producer/Consumer (gồm bootstrap.servers). */
    public record Profile(String name, Map<String, String> client, String schemaRegistryUrl, String schemaRegistryAuth,
                          boolean readonly) {

        public String bootstrap() {
            return client.getOrDefault("bootstrap.servers", "localhost:9092");
        }
    }

    static Path file() {
        return ConnectionProfileStore.configDir().resolve("kafka.conf");
    }

    public static List<Profile> loadAll() {
        Path file = file();
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Profile> out = new ArrayList<>();
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
                        out.add(toProfile(name, fields));
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
                out.add(toProfile(name, fields));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    public static Optional<Profile> find(String name) {
        return loadAll().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }

    public static void save(Profile p) throws IOException {
        List<Profile> all = new ArrayList<>(loadAll());
        all.removeIf(x -> x.name().equalsIgnoreCase(p.name()));
        all.add(p);
        StringBuilder sb = new StringBuilder();
        for (Profile x : all) {
            sb.append('[').append(x.name()).append("]\n");
            x.client().forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            if (x.schemaRegistryUrl() != null) {
                sb.append("schema.registry.url=").append(x.schemaRegistryUrl()).append('\n');
            }
            if (x.schemaRegistryAuth() != null) {
                sb.append("schema.registry.basic.auth.user.info=").append(x.schemaRegistryAuth()).append('\n');
            }
            sb.append("readonly=").append(x.readonly()).append("\n\n");
        }
        Path file = file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString());
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // không phải POSIX
        }
    }

    private static Profile toProfile(String name, Map<String, String> f) {
        Map<String, String> client = new LinkedHashMap<>();
        String sr = null;
        String srAuth = null;
        boolean readonly = false;
        for (Map.Entry<String, String> e : f.entrySet()) {
            switch (e.getKey()) {
                case "bootstrap", "bootstrap.servers" -> client.put("bootstrap.servers", e.getValue());
                case "schema.registry.url" -> sr = e.getValue();
                case "schema.registry.basic.auth.user.info" -> srAuth = e.getValue();
                case "readonly" -> readonly = Boolean.parseBoolean(e.getValue());
                default -> client.put(e.getKey(), e.getValue());
            }
        }
        return new Profile(name, client, sr, srAuth, readonly);
    }
}
