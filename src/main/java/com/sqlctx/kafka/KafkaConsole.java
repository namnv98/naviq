package com.sqlctx.kafka;

import com.sqlctx.util.I18n;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * kafka-console-consumer / kafka-console-producer (và bản *-avro-*, *-json-schema-*, *-protobuf-* của Confluent).
 * Khác tool gốc ở 2 chỗ có chủ đích: không có --group thì đọc bằng assign() (không tạo group "console-consumer-xxx"
 * rác trên cluster); value/key không phải UTF-8 in dạng \xNN thay vì byte thô làm hỏng terminal.
 */
final class KafkaConsole {

    private final KafkaSession session;
    private final KafkaCommands.Out out;
    /** In 1 message (không qua bảng/pager - có thể tới liên tục). */
    private final Consumer<String> print;

    KafkaConsole(KafkaSession session, KafkaCommands.Out out, Consumer<String> print) {
        this.session = session;
        this.out = out;
        this.print = print;
    }

    /** "avro", "json-schema", "protobuf" hoặc "" theo tên tool. */
    static String flavor(String tool) {
        for (String f : List.of("avro", "json-schema", "protobuf")) {
            if (tool.startsWith("kafka-" + f + "-")) {
                return f;
            }
        }
        return "";
    }

    private static Map<String, String> props(List<String> pairs) {
        Map<String, String> m = new HashMap<>();
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new KafkaCommands.UsageException(I18n.t("kafka.err.propertyFormat", pair));
            }
            m.put(pair.substring(0, eq).strip(), pair.substring(eq + 1));
        }
        return m;
    }

    private static Map<String, String> configFile(String file) throws IOException {
        Map<String, String> m = new HashMap<>();
        if (file == null) {
            return m;
        }
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(Path.of(file))) {
            p.load(in);
        }
        p.forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
        return m;
    }

    /** "\t", "\n" trong giá trị --property là ký tự thật (tool gốc cũng hiểu như vậy). */
    private static String unescape(String s) {
        return s.replace("\\t", "\t").replace("\\n", "\n");
    }

    // ================================================================== consumer

    /** Trả số message đã đọc. Dừng khi đủ --max-messages, quá --timeout-ms không có message mới, hoặc Ctrl+C. */
    int consume(KafkaArgs.Parsed p, boolean readonly) throws Exception {
        String flavor = flavor(p.tool().name());
        Map<String, String> fmt = props(p.all("--property"));
        boolean printKey = Boolean.parseBoolean(fmt.getOrDefault("print.key", "false"));
        boolean printValue = Boolean.parseBoolean(fmt.getOrDefault("print.value", "true"));
        boolean printTimestamp = Boolean.parseBoolean(fmt.getOrDefault("print.timestamp", "false"));
        boolean printPartition = Boolean.parseBoolean(fmt.getOrDefault("print.partition", "false"));
        boolean printOffset = Boolean.parseBoolean(fmt.getOrDefault("print.offset", "false"));
        boolean printHeaders = Boolean.parseBoolean(fmt.getOrDefault("print.headers", "false"));
        boolean printSchemaIds = Boolean.parseBoolean(fmt.getOrDefault("print.schema.ids", "false"));
        String sep = unescape(fmt.getOrDefault("key.separator", "\t"));
        String headersSep = unescape(fmt.getOrDefault("headers.separator", ","));
        String nullLiteral = fmt.getOrDefault("null.literal", "null");
        SchemaRegistry sr = flavor.isEmpty() ? null : session.schemaRegistry(fmt.get("schema.registry.url"));
        if (!flavor.isEmpty() && sr == null) {
            throw new KafkaCommands.UsageException(I18n.t("kafka.err.needSr"));
        }

        String group = p.get("--group");
        Map<String, String> overrides = new HashMap<>(configFile(p.get("--consumer.config")));
        overrides.putAll(props(p.all("--consumer-property")));
        overrides.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        overrides.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        if (p.has("--isolation-level")) {
            overrides.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, p.get("--isolation-level"));
        }
        if (group != null) {
            if (p.has("--offset")) {
                throw new KafkaCommands.UsageException(I18n.t("kafka.err.offsetWithGroup"));
            }
            overrides.put(ConsumerConfig.GROUP_ID_CONFIG, group);
            overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, p.has("--from-beginning") ? "earliest" : "latest");
            // Tự commit sẽ commit vị trí của cả lô vừa poll() - với --max-messages, message đã lấy về mà chưa in
            // cũng bị coi là đã đọc. Commit tay đúng tới message đã in.
            overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            if (readonly) {
                out.info(I18n.t("kafka.readonlyNoCommit", group));
            }
        }
        if (p.has("--offset") && !p.has("--partition")) {
            throw new KafkaCommands.UsageException(I18n.t("kafka.err.offsetNeedsPartition"));
        }
        long max = p.has("--max-messages") ? Long.parseLong(p.get("--max-messages")) : Long.MAX_VALUE;
        long idleTimeout = p.has("--timeout-ms") ? Long.parseLong(p.get("--timeout-ms")) : Long.MAX_VALUE;
        boolean skipErrors = p.has("--skip-message-on-error");

        boolean commit = group != null && !readonly;
        Map<TopicPartition, OffsetAndMetadata> processed = new HashMap<>();
        int count = 0;
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(session.clientProperties(overrides))) {
            session.onCancel = consumer::wakeup;
            String include = p.has("--include") ? p.get("--include") : p.get("--whitelist");
            if (group != null) {
                if (include != null) {
                    consumer.subscribe(Pattern.compile(include));
                } else {
                    consumer.subscribe(List.of(p.get("--topic")));
                }
            } else {
                List<TopicPartition> tps = new ArrayList<>();
                Map<String, List<PartitionInfo>> all = include != null ? consumer.listTopics() : null;
                List<String> topics = include != null
                        ? all.keySet().stream().filter(t -> Pattern.compile(include).matcher(t).matches()).sorted().toList()
                        : List.of(p.get("--topic"));
                for (String t : topics) {
                    List<PartitionInfo> parts = consumer.partitionsFor(t);
                    if (parts == null || parts.isEmpty()) {
                        throw new KafkaCommands.UsageException(I18n.t("kafka.err.noTopic", t));
                    }
                    for (PartitionInfo pi : parts) {
                        if (!p.has("--partition") || pi.partition() == Integer.parseInt(p.get("--partition"))) {
                            tps.add(new TopicPartition(t, pi.partition()));
                        }
                    }
                }
                if (tps.isEmpty()) {
                    throw new KafkaCommands.UsageException(I18n.t("kafka.err.noPartitions"));
                }
                consumer.assign(tps);
                String offset = p.get("--offset");
                if (offset == null) {
                    if (p.has("--from-beginning")) {
                        consumer.seekToBeginning(tps);
                    } else {
                        consumer.seekToEnd(tps);
                    }
                } else if (offset.equals("earliest")) {
                    consumer.seekToBeginning(tps);
                } else if (offset.equals("latest")) {
                    consumer.seekToEnd(tps);
                } else {
                    long o = Long.parseLong(offset);
                    for (TopicPartition tp : tps) {
                        consumer.seek(tp, o);
                    }
                }
            }
            long lastMessage = System.currentTimeMillis();
            while (count < max && !session.cancelled) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(200));
                if (records.isEmpty()) {
                    if (System.currentTimeMillis() - lastMessage > idleTimeout) {
                        break;
                    }
                    continue;
                }
                lastMessage = System.currentTimeMillis();
                for (ConsumerRecord<byte[], byte[]> r : records) {
                    if (count >= max) {
                        break;
                    }
                    StringBuilder line = new StringBuilder();
                    if (printTimestamp) {
                        line.append(r.timestampType() == TimestampType.NO_TIMESTAMP_TYPE ? "NO_TIMESTAMP"
                                : r.timestampType() + ":" + r.timestamp()).append(sep);
                    }
                    if (printPartition) {
                        line.append("Partition:").append(r.partition()).append(sep);
                    }
                    if (printOffset) {
                        line.append("Offset:").append(r.offset()).append(sep);
                    }
                    if (printHeaders) {
                        List<String> hs = new ArrayList<>();
                        for (Header h : r.headers()) {
                            hs.add(h.key() + ":" + KafkaSerde.text(h.value(), nullLiteral));
                        }
                        line.append(hs.isEmpty() ? "NO_HEADERS" : String.join(headersSep, hs)).append(sep);
                    }
                    try {
                        if (printKey) {
                            line.append(render(r.key(), flavor, sr, nullLiteral, printSchemaIds)).append(sep);
                        }
                        if (printValue) {
                            line.append(render(r.value(), flavor, sr, nullLiteral, printSchemaIds));
                        }
                    } catch (IOException e) {
                        if (!skipErrors) {
                            throw new KafkaCommands.UsageException(I18n.t("kafka.err.cannotDecode", r.topic(), r.partition(),
                                    r.offset(), e.getMessage()));
                        }
                        continue;
                    }
                    String s = line.toString();
                    print.accept(s.endsWith(sep) && !printValue ? s.substring(0, s.length() - sep.length()) : s);
                    processed.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1));
                    count++;
                }
                if (commit && !processed.isEmpty()) {
                    consumer.commitAsync(new HashMap<>(processed), null);
                }
            }
            if (commit && !processed.isEmpty()) {
                consumer.commitSync(processed);
            }
        } catch (WakeupException ignored) {
            // Ctrl+C: consumer đã bị đánh thức, commit trên chính nó sẽ lại ném WakeupException
            if (commit && !processed.isEmpty()) {
                commitAfterWakeup(overrides, processed);
            }
        } finally {
            session.onCancel = null;
        }
        out.info("Processed a total of " + count + " messages");
        return count;
    }

    /** Sau Ctrl+C: commit phần đã in bằng 1 consumer tạm cùng group (consumer cũ đã đóng ở finally của try). */
    private void commitAfterWakeup(Map<String, String> overrides, Map<TopicPartition, OffsetAndMetadata> processed) {
        try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(session.clientProperties(overrides))) {
            c.commitSync(processed);
        } catch (RuntimeException e) {
            out.info(I18n.t("kafka.commitAfterStopFailed", e.getMessage()));
        }
    }

    private static String render(byte[] bytes, String flavor, SchemaRegistry sr, String nullLiteral, boolean ids)
            throws IOException {
        if (bytes == null) {
            return nullLiteral;
        }
        if (flavor.isEmpty() || KafkaSerde.schemaId(bytes) < 0) {
            return KafkaSerde.text(bytes, nullLiteral); // key thường không dùng schema: in như chữ
        }
        String decoded = KafkaSerde.decode(bytes, flavor, sr);
        return ids ? decoded + "\t" + KafkaSerde.schemaId(bytes) : decoded;
    }

    // ================================================================== producer

    /** Gửi từng dòng do {@code lines} trả về (null = hết: Ctrl+D hoặc hết stdin). Trả số message đã gửi. */
    int produce(KafkaArgs.Parsed p, Supplier<String> lines) throws Exception {
        String flavor = flavor(p.tool().name());
        String topic = p.get("--topic");
        Map<String, String> fmt = props(p.all("--property"));
        boolean parseKey = Boolean.parseBoolean(fmt.getOrDefault("parse.key", "false"));
        String keySep = unescape(fmt.getOrDefault("key.separator", "\t"));
        boolean parseHeaders = Boolean.parseBoolean(fmt.getOrDefault("parse.headers", "false"));
        String headersDelimiter = unescape(fmt.getOrDefault("headers.delimiter", "\t"));
        String headersSep = unescape(fmt.getOrDefault("headers.separator", ","));
        String headerKeySep = unescape(fmt.getOrDefault("headers.key.separator", ":"));
        String nullMarker = fmt.get("null.marker");
        boolean ignoreError = Boolean.parseBoolean(fmt.getOrDefault("ignore.error", "false"));

        SchemaRegistry sr = null;
        int valueSchemaId = -1;
        int keySchemaId = -1;
        if (!flavor.isEmpty()) {
            sr = session.schemaRegistry(fmt.get("schema.registry.url"));
            if (sr == null) {
                throw new KafkaCommands.UsageException(I18n.t("kafka.err.needSr"));
            }
            String type = switch (flavor) {
                case "avro" -> "AVRO";
                case "protobuf" -> "PROTOBUF";
                default -> "JSON";
            };
            valueSchemaId = schemaId(sr, topic + "-value", fmt, "value", type);
            if (valueSchemaId < 0) {
                throw new KafkaCommands.UsageException(I18n.t("kafka.err.needValueSchema"));
            }
            keySchemaId = schemaId(sr, topic + "-key", fmt, "key", type);
        }

        Map<String, String> overrides = new HashMap<>(configFile(p.get("--producer.config")));
        overrides.putAll(props(p.all("--producer-property")));
        overrides.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        overrides.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        if (p.has("--compression-codec")) {
            overrides.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, p.get("--compression-codec"));
        }
        if (p.has("--request-required-acks")) {
            overrides.put(ProducerConfig.ACKS_CONFIG, p.get("--request-required-acks"));
        }
        if (p.has("--batch-size")) {
            overrides.put(ProducerConfig.BATCH_SIZE_CONFIG, p.get("--batch-size"));
        }
        if (p.has("--timeout")) {
            overrides.put(ProducerConfig.LINGER_MS_CONFIG, p.get("--timeout"));
        }
        overrides.putIfAbsent(ProducerConfig.MAX_BLOCK_MS_CONFIG, "15000");
        boolean sync = p.has("--sync");
        int sent = 0;
        List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(session.clientProperties(overrides))) {
            String line;
            while (!session.cancelled && (line = lines.get()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                ProducerRecord<byte[], byte[]> record;
                try {
                    record = record(topic, line, parseKey, keySep, parseHeaders, headersDelimiter, headersSep, headerKeySep,
                            nullMarker, flavor, sr, keySchemaId, valueSchemaId, fmt.get("key.message"), fmt.get("value.message"));
                } catch (IOException | RuntimeException e) {
                    if (ignoreError) {
                        continue;
                    }
                    out.info(I18n.t("kafka.skippedLine", e.getMessage()));
                    continue;
                }
                if (sync) {
                    RecordMetadata m = producer.send(record).get();
                    print.accept("\u001b[38;5;245m→ " + m.topic() + "-" + m.partition() + "@" + m.offset() + "\u001b[0m");
                } else {
                    producer.send(record, (m, e) -> {
                        if (e != null) {
                            errors.add(e.getMessage());
                        }
                    });
                }
                sent++;
            }
            producer.flush();
        }
        for (String e : errors) {
            out.info(I18n.t("kafka.sendError", e));
        }
        out.info(I18n.t("kafka.sent", sent - errors.size(), topic));
        return sent;
    }

    /** id schema từ <prefix>.schema.id, hoặc đăng ký <prefix>.schema / .schema.file vào subject (như auto.register). */
    private static int schemaId(SchemaRegistry sr, String subject, Map<String, String> fmt, String prefix, String type)
            throws IOException {
        if (fmt.containsKey(prefix + ".schema.id")) {
            return Integer.parseInt(fmt.get(prefix + ".schema.id"));
        }
        String schema = fmt.containsKey(prefix + ".schema.file") ? Files.readString(Path.of(fmt.get(prefix + ".schema.file")))
                : fmt.get(prefix + ".schema");
        return schema == null ? -1 : sr.register(subject, schema, type, fmt.get(prefix + ".refs"));
    }

    private static ProducerRecord<byte[], byte[]> record(String topic, String line, boolean parseKey, String keySep,
                                                         boolean parseHeaders, String headersDelimiter, String headersSep,
                                                         String headerKeySep, String nullMarker, String flavor,
                                                         SchemaRegistry sr, int keySchemaId, int valueSchemaId,
                                                         String keyMessage, String valueMessage) throws IOException {
        List<Header> headers = new ArrayList<>();
        String rest = line;
        if (parseHeaders) {
            int d = rest.indexOf(headersDelimiter);
            if (d < 0) {
                throw new IOException(I18n.t("kafka.err.missingHeadersDelimiter"));
            }
            for (String h : rest.substring(0, d).split(Pattern.quote(headersSep))) {
                int k = h.indexOf(headerKeySep);
                headers.add(k < 0 ? new RecordHeader(h, null)
                        : new RecordHeader(h.substring(0, k), h.substring(k + headerKeySep.length()).getBytes(StandardCharsets.UTF_8)));
            }
            rest = rest.substring(d + headersDelimiter.length());
        }
        String key = null;
        if (parseKey) {
            int k = rest.indexOf(keySep);
            if (k < 0) {
                throw new IOException(I18n.t("kafka.err.missingKeySeparator"));
            }
            key = rest.substring(0, k);
            rest = rest.substring(k + keySep.length());
        }
        byte[] keyBytes = encode(key, nullMarker, flavor, sr, keySchemaId, keyMessage);
        byte[] valueBytes = encode(rest, nullMarker, flavor, sr, valueSchemaId, valueMessage);
        return new ProducerRecord<>(topic, null, keyBytes, valueBytes, headers);
    }

    /** {@code message}: message protobuf cần dùng (null = message đầu tiên của schema). */
    private static byte[] encode(String s, String nullMarker, String flavor, SchemaRegistry sr, int schemaId, String message)
            throws IOException {
        if (s == null || s.equals(nullMarker)) {
            return null;
        }
        if (flavor.isEmpty() || schemaId < 0) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        return switch (flavor) {
            case "avro" -> KafkaSerde.encodeAvro(s, schemaId, sr);
            case "protobuf" -> KafkaSerde.encodeProtobuf(s, schemaId, message, sr);
            default -> KafkaSerde.encodeJsonSchema(s, schemaId);
        };
    }
}
