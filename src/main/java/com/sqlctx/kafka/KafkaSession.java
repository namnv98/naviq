package com.sqlctx.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.config.ConfigResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Kết nối của 1 phiên Kafka: Admin dùng chung (tạo lúc cần), config client cho producer/consumer, Schema Registry,
 * và dữ liệu gợi ý (topic, group, broker, tên config, subject) cache vài giây - popup gọi ở MỖI phím gõ.
 */
final class KafkaSession implements AutoCloseable, KafkaCompletion.Source {

    private static final long CACHE_MILLIS = 5000;

    final KafkaProfiles.Profile profile;
    private Admin admin;
    private SchemaRegistry schemaRegistry;
    volatile boolean running;
    volatile boolean cancelled;
    /** Ctrl+C khi đang chạy lệnh: vd consumer.wakeup() để thoát poll() đang chờ. */
    volatile Runnable onCancel;

    private record Cached(long at, List<String> values) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    KafkaSession(KafkaProfiles.Profile profile) {
        this.profile = profile;
    }

    /** Config chung + ghi đè (vd --consumer-property). Timeout ngắn hơn mặc định để REPL không treo lâu khi broker chết. */
    Properties clientProperties(Map<String, String> overrides) {
        Properties p = new Properties();
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "20000");
        p.putAll(profile.client());
        if (overrides != null) {
            p.putAll(overrides);
        }
        return p;
    }

    synchronized Admin admin() {
        if (admin == null) {
            admin = Admin.create(clientProperties(null));
        }
        return admin;
    }

    /** Schema Registry của context; {@code override}: --property schema.registry.url=... của lệnh. */
    synchronized SchemaRegistry schemaRegistry(String override) {
        String url = override != null ? override : profile.schemaRegistryUrl();
        if (url == null) {
            return null;
        }
        if (schemaRegistry == null || !schemaRegistry.url().equals(url.replaceAll("/$", ""))) {
            schemaRegistry = new SchemaRegistry(url, profile.schemaRegistryAuth());
        }
        return schemaRegistry;
    }

    void cancel() {
        cancelled = true;
        Runnable r = onCancel;
        if (r != null) {
            r.run();
        }
    }

    @Override
    public synchronized void close() {
        if (admin != null) {
            admin.close(java.time.Duration.ofSeconds(2));
            admin = null;
        }
    }

    // ------------------------------------------------------------------ dữ liệu gợi ý (lỗi gì cũng trả rỗng)

    private List<String> cached(String key, Supplier<List<String>> load) {
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < CACHE_MILLIS) {
            return c.values();
        }
        List<String> values;
        try {
            values = load.get();
        } catch (RuntimeException e) {
            values = List.of();
        }
        cache.put(key, new Cached(now, values));
        return values;
    }

    void invalidate() {
        cache.clear();
    }

    @Override
    public List<String> topics() {
        return cached("topics", () -> {
            try {
                return List.copyOf(new TreeSet<>(admin().listTopics(new ListTopicsOptions().listInternal(true))
                        .names().get(1500, TimeUnit.MILLISECONDS)));
            } catch (Exception e) {
                return List.of();
            }
        });
    }

    @Override
    public List<String> groups() {
        return cached("groups", () -> {
            try {
                TreeSet<String> out = new TreeSet<>();
                for (ConsumerGroupListing g : admin().listConsumerGroups().all().get(1500, TimeUnit.MILLISECONDS)) {
                    out.add(g.groupId());
                }
                return List.copyOf(out);
            } catch (Exception e) {
                return List.of();
            }
        });
    }

    @Override
    public List<String> transactionalIds() {
        return cached("transactionalIds", () -> {
            try {
                TreeSet<String> out = new TreeSet<>();
                for (var t : admin().listTransactions().all().get(1500, TimeUnit.MILLISECONDS)) {
                    out.add(t.transactionalId());
                }
                return List.copyOf(out);
            } catch (Exception e) {
                return List.of();
            }
        });
    }

    @Override
    public List<String> brokers() {
        return cached("brokers", () -> {
            try {
                List<String> out = new ArrayList<>();
                for (Node n : admin().describeCluster().nodes().get(1500, TimeUnit.MILLISECONDS)) {
                    out.add(String.valueOf(n.id()));
                }
                out.sort(null);
                return out;
            } catch (Exception e) {
                return List.of();
            }
        });
    }

    @Override
    public List<String> partitions(String topic) {
        return cached("partitions\0" + topic, () -> {
            try {
                int n = admin().describeTopics(List.of(topic)).allTopicNames().get(1500, TimeUnit.MILLISECONDS)
                        .get(topic).partitions().size();
                List<String> out = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    out.add(String.valueOf(i));
                }
                return out;
            } catch (Exception e) {
                return List.of();
            }
        });
    }

    /** Tên config của topic / broker (lấy từ 1 topic / broker có thật - danh sách đầy đủ kèm cả mặc định). */
    @Override
    public List<String> configNames(String entityType) {
        return cached("configs\0" + entityType, () -> {
            try {
                ConfigResource r;
                if ("brokers".equals(entityType)) {
                    List<String> b = brokers();
                    if (b.isEmpty()) {
                        return List.of();
                    }
                    r = new ConfigResource(ConfigResource.Type.BROKER, b.get(0));
                } else {
                    List<String> t = topics();
                    if (t.isEmpty()) {
                        return List.of();
                    }
                    r = new ConfigResource(ConfigResource.Type.TOPIC, t.get(0));
                }
                TreeSet<String> out = new TreeSet<>();
                admin().describeConfigs(List.of(r)).all().get(1500, TimeUnit.MILLISECONDS).get(r).entries()
                        .forEach(e -> out.add(e.name()));
                return List.copyOf(out);
            } catch (Exception e) {
                return List.of();
            }
        });
    }

    @Override
    public List<String> subjects() {
        return cached("subjects", () -> {
            SchemaRegistry sr = schemaRegistry(null);
            if (sr == null) {
                return List.of();
            }
            try {
                return sr.subjects(null, false);
            } catch (Exception e) {
                return List.of();
            }
        });
    }

    @Override
    public List<String> contexts() {
        return KafkaProfiles.loadAll().stream().map(KafkaProfiles.Profile::name).toList();
    }
}
