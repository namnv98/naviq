package com.sqlctx.kafka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mô tả các tool kafka-*.sh mà chế độ Kafka nhận - tên, chế độ (--list/--describe..., hoặc lệnh con như
 * "cluster-id"), tuỳ chọn và loại giá trị - dùng chung cho gợi ý, kiểm tra cú pháp, tô màu và dòng hướng dẫn.
 * Tên và nghĩa tuỳ chọn theo đúng tool của Kafka 3.9 (và tool của Confluent cho Schema Registry) để dán lệnh từ
 * tài liệu vào là chạy; --bootstrap-server/--command-config được nhận nhưng bỏ qua vì kết nối lấy từ context.
 * "schema-registry" không phải tool có thật (Schema Registry chỉ có REST API) - đặt theo cùng kiểu cờ.
 */
public final class KafkaTools {

    private KafkaTools() {
    }

    public enum Arity {
        FLAG, VALUE, OPTIONAL_VALUE
    }

    /**
     * {@code kind}: loại giá trị (topic, group, int, config...) - quyết định gợi ý gì và kiểm tra ra sao.
     * {@code modes}: chỉ dùng được với các chế độ này (rỗng = mọi chế độ).
     */
    public record Opt(String name, Arity arity, String kind, String meta, String help, Set<String> modes,
                      boolean repeatable) {
    }

    /**
     * {@code modes}: chế độ loại trừ nhau (đúng 1); bắt đầu bằng "--" là cờ (--list), không thì là lệnh con đứng
     * ngay sau tên tool (cluster-id). {@code requires}: theo chế độ ("" = không có chế độ), mỗi nhóm cần ít nhất 1.
     * {@code writes}: các chế độ ghi/xoá (chặn ở context readonly); "*" = cả tool.
     */
    public record Tool(String name, String help, List<String> modes, Map<String, String> modeHelp, List<Opt> options,
                       Map<String, List<List<String>>> requires, Set<String> writes, Set<String> dangerous) {

        public Opt option(String name) {
            for (Opt o : options) {
                if (o.name().equals(name)) {
                    return o;
                }
            }
            return null;
        }

        public boolean hasModes() {
            return !modes.isEmpty();
        }

        public boolean positionalModes() {
            return hasModes() && !modes.get(0).startsWith("--");
        }
    }

    // ------------------------------------------------------------------ dựng bảng

    private static final class B {
        final String name;
        final String help;
        final List<String> modes = new ArrayList<>();
        final Map<String, String> modeHelp = new LinkedHashMap<>();
        final List<Opt> options = new ArrayList<>();
        final Map<String, List<List<String>>> requires = new LinkedHashMap<>();
        Set<String> writes = Set.of();
        Set<String> dangerous = Set.of();

        B(String name, String help) {
            this.name = name;
            this.help = help;
        }

        B mode(String mode, String help) {
            modes.add(mode);
            modeHelp.put(mode, help);
            return this;
        }

        B flag(String name, String help, String... modes) {
            options.add(new Opt(name, Arity.FLAG, null, null, help, Set.of(modes), false));
            return this;
        }

        B value(String name, String kind, String meta, String help, String... modes) {
            options.add(new Opt(name, Arity.VALUE, kind, meta, help, Set.of(modes), false));
            return this;
        }

        B values(String name, String kind, String meta, String help, String... modes) {
            options.add(new Opt(name, Arity.VALUE, kind, meta, help, Set.of(modes), true));
            return this;
        }

        B optionalValue(String name, String kind, String meta, String help, String... modes) {
            options.add(new Opt(name, Arity.OPTIONAL_VALUE, kind, meta, help, Set.of(modes), false));
            return this;
        }

        @SafeVarargs
        final B requires(String mode, List<String>... groups) {
            requires.put(mode, List.of(groups));
            return this;
        }

        B writes(String... modes) {
            writes = Set.of(modes);
            return this;
        }

        B dangerous(String... modes) {
            dangerous = Set.of(modes);
            return this;
        }

        /** --bootstrap-server/--command-config: nhận để dán lệnh từ tài liệu, nhưng kết nối lấy từ context. */
        B connection() {
            value("--bootstrap-server", "string", "host:port", "bỏ qua - sqlctx dùng kết nối của context");
            value("--command-config", "file", "file", "bỏ qua - cấu hình client đặt trong context (kafka.conf)");
            return this;
        }

        Tool build() {
            return new Tool(name, help, List.copyOf(modes), Map.copyOf(modeHelp), List.copyOf(options),
                    Map.copyOf(requires), writes, dangerous);
        }
    }

    private static List<String> one(String... names) {
        return List.of(names);
    }

    // ------------------------------------------------------------------ các tool

    public static final Map<String, Tool> TOOLS = build();

    public static Tool tool(String name) {
        String n = name.endsWith(".sh") ? name.substring(0, name.length() - 3) : name;
        return TOOLS.get(n);
    }

    private static Map<String, Tool> build() {
        Map<String, Tool> t = new LinkedHashMap<>();

        B topics = new B("kafka-topics", "Tạo, xoá, xem, sửa topic")
                .mode("--list", "liệt kê topic")
                .mode("--describe", "partition, leader, replica, ISR, config riêng của topic")
                .mode("--create", "tạo topic")
                .mode("--delete", "xoá topic")
                .mode("--alter", "tăng số partition")
                .connection()
                .value("--topic", "topic", "topic", "tên topic (--list/--describe/--delete/--alter nhận cả regex)")
                .value("--partitions", "int", "n", "số partition", "--create", "--alter")
                .value("--replication-factor", "int", "n", "số bản sao mỗi partition", "--create")
                .values("--config", "topicConfig", "name=value", "config của topic khi tạo", "--create")
                .value("--replica-assignment", "string", "b1:b2,b3:b4", "gán replica thủ công cho từng partition",
                        "--create", "--alter")
                .flag("--exclude-internal", "bỏ topic nội bộ (__consumer_offsets...)", "--list", "--describe")
                .flag("--if-exists", "không báo lỗi nếu topic không có", "--delete", "--alter", "--describe")
                .flag("--if-not-exists", "không báo lỗi nếu topic đã có", "--create")
                .flag("--topics-with-overrides", "chỉ topic có config riêng", "--describe")
                .flag("--under-replicated-partitions", "chỉ partition thiếu replica trong ISR", "--describe")
                .flag("--under-min-isr-partitions", "chỉ partition có ISR < min.insync.replicas", "--describe")
                .flag("--at-min-isr-partitions", "chỉ partition có ISR = min.insync.replicas", "--describe")
                .flag("--unavailable-partitions", "chỉ partition không có leader", "--describe")
                .requires("--create", one("--topic"))
                .requires("--delete", one("--topic"))
                .requires("--alter", one("--topic"), one("--partitions"))
                .writes("--create", "--delete", "--alter")
                .dangerous("--delete");
        t.put(topics.name, topics.build());

        for (String flavor : List.of("", "avro", "json-schema", "protobuf")) {
            String consumerName = flavor.isEmpty() ? "kafka-console-consumer" : "kafka-" + flavor + "-console-consumer";
            B consumer = new B(consumerName, flavor.isEmpty() ? "Đọc message của topic"
                    : "Đọc message " + flavor + " qua Schema Registry (tool của Confluent)")
                    .connection()
                    .value("--topic", "topic", "topic", "topic cần đọc")
                    .value("--include", "regex", "regex", "đọc mọi topic khớp regex")
                    .value("--whitelist", "regex", "regex", "(cũ) như --include")
                    .flag("--from-beginning", "đọc từ message đầu tiên (mặc định: chỉ message mới)")
                    .value("--partition", "partition", "n", "chỉ đọc 1 partition")
                    .value("--offset", "offset", "earliest|latest|n", "vị trí bắt đầu (cần --partition)")
                    .value("--max-messages", "int", "n", "dừng sau n message")
                    .value("--timeout-ms", "int", "ms", "dừng nếu không có message mới trong ms")
                    .value("--group", "group", "group", "đọc theo consumer group (commit offset)")
                    .value("--isolation-level", "isolation", "level", "read_committed: bỏ message của transaction bị huỷ")
                    .values("--property", flavor.isEmpty() ? "consumerProperty" : "srConsumerProperty", "name=value",
                            "định dạng in: print.key, print.timestamp, print.partition, print.offset, print.headers...")
                    .values("--consumer-property", "clientConfig", "name=value", "config của consumer")
                    .value("--consumer.config", "file", "file", "file config của consumer")
                    .flag("--skip-message-on-error", "bỏ qua message không giải mã được")
                    .value("--key-deserializer", "string", "class", "bỏ qua - sqlctx tự hiện key dạng chữ/hex")
                    .value("--value-deserializer", "string", "class", "bỏ qua - sqlctx tự hiện value dạng chữ/hex")
                    .value("--formatter", "string", "class", "bỏ qua - dùng định dạng mặc định")
                    .requires("", one("--topic", "--include", "--whitelist"));
            t.put(consumer.name, consumer.build());
        }

        for (String flavor : List.of("", "avro", "json-schema", "protobuf")) {
            String producerName = flavor.isEmpty() ? "kafka-console-producer" : "kafka-" + flavor + "-console-producer";
            B producer = new B(producerName, flavor.isEmpty() ? "Ghi message vào topic (mỗi dòng 1 message, Ctrl+D để dừng)"
                    : "Ghi message " + flavor + " qua Schema Registry, mỗi dòng 1 JSON (tool của Confluent)")
                    .connection()
                    .value("--topic", "topic", "topic", "topic cần ghi")
                    .values("--property", flavor.isEmpty() ? "producerProperty" : "srProducerProperty", "name=value",
                            "parse.key, key.separator, parse.headers..." + (flavor.isEmpty() ? "" : ", value.schema, value.schema.id"))
                    .values("--producer-property", "clientConfig", "name=value", "config của producer")
                    .value("--producer.config", "file", "file", "file config của producer")
                    .value("--compression-codec", "compression", "codec", "none, gzip, snappy, lz4, zstd")
                    .value("--request-required-acks", "acks", "acks", "-1/all, 0, 1")
                    .flag("--sync", "gửi từng message, chờ xác nhận")
                    .value("--batch-size", "int", "n", "số message mỗi lô")
                    .value("--timeout", "int", "ms", "linger.ms")
                    .requires("", one("--topic"))
                    .writes("*");
            t.put(producer.name, producer.build());
        }

        B groups = new B("kafka-consumer-groups", "Xem, reset offset, xoá consumer group")
                .mode("--list", "liệt kê group")
                .mode("--describe", "offset, lag, member của group")
                .mode("--reset-offsets", "đặt lại offset (mặc định chỉ xem trước, thêm --execute để áp dụng)")
                .mode("--delete", "xoá group")
                .mode("--delete-offsets", "xoá offset đã commit của group cho 1 topic")
                .connection()
                .values("--group", "group", "group", "consumer group")
                .flag("--all-groups", "mọi group", "--describe", "--reset-offsets", "--delete")
                .flag("--members", "liệt kê member", "--describe")
                .flag("--offsets", "offset và lag từng partition (mặc định)", "--describe")
                .optionalValue("--state", "groupState", "state", "trạng thái group (--list: lọc theo trạng thái)",
                        "--describe", "--list")
                .flag("--verbose", "thêm partition được gán cho từng member", "--describe")
                .values("--topic", "topicPartitions", "topic[:p,p]", "topic (và partition) áp dụng",
                        "--reset-offsets", "--delete-offsets")
                .flag("--all-topics", "mọi topic group đang có offset", "--reset-offsets")
                .flag("--to-earliest", "về offset đầu", "--reset-offsets")
                .flag("--to-latest", "tới offset cuối", "--reset-offsets")
                .flag("--to-current", "giữ offset hiện tại", "--reset-offsets")
                .value("--to-offset", "long", "n", "tới offset n", "--reset-offsets")
                .value("--shift-by", "long", "n", "dịch n offset (âm = lùi)", "--reset-offsets")
                .value("--to-datetime", "datetime", "YYYY-MM-DDTHH:mm:SS.sss", "tới message đầu tiên từ thời điểm này",
                        "--reset-offsets")
                .value("--by-duration", "duration", "PnDTnHnMnS", "lùi về trước 1 khoảng thời gian", "--reset-offsets")
                .flag("--dry-run", "chỉ xem trước (mặc định)", "--reset-offsets")
                .flag("--execute", "áp dụng thật", "--reset-offsets")
                .value("--timeout", "int", "ms", "thời gian chờ broker")
                .requires("--describe", one("--group", "--all-groups"))
                .requires("--delete", one("--group", "--all-groups"))
                .requires("--delete-offsets", one("--group"), one("--topic"))
                .requires("--reset-offsets", one("--group", "--all-groups"), one("--topic", "--all-topics"),
                        one("--to-earliest", "--to-latest", "--to-current", "--to-offset", "--shift-by",
                                "--to-datetime", "--by-duration"))
                .writes("--reset-offsets", "--delete", "--delete-offsets")
                .dangerous("--delete", "--delete-offsets", "--reset-offsets");
        t.put(groups.name, groups.build());

        B configs = new B("kafka-configs", "Xem, sửa config của topic, broker, logger, quota user/client")
                .mode("--describe", "xem config")
                .mode("--alter", "thêm/sửa/xoá config")
                .connection()
                .value("--entity-type", "entityType", "type", "topics, brokers, broker-loggers, users, clients")
                .value("--entity-name", "entityName", "name", "tên topic / id broker / tên user, client")
                .flag("--entity-default", "giá trị mặc định (broker/user/client)")
                .value("--topic", "topic", "topic", "= --entity-type topics --entity-name")
                .value("--broker", "broker", "id", "= --entity-type brokers --entity-name")
                .flag("--broker-defaults", "= --entity-type brokers --entity-default")
                .value("--broker-logger", "broker", "id", "= --entity-type broker-loggers --entity-name")
                .value("--user", "string", "user", "= --entity-type users --entity-name")
                .value("--client", "string", "client-id", "= --entity-type clients --entity-name")
                .flag("--all", "cả config đang để mặc định", "--describe")
                .values("--add-config", "configList", "k=v,k=[a,b]", "thêm/sửa config", "--alter")
                .values("--delete-config", "configNames", "k,k", "xoá config (về mặc định)", "--alter")
                .flag("--force", "bỏ qua kiểm tra", "--alter")
                .requires("--describe", one("--entity-type", "--topic", "--broker", "--broker-defaults", "--broker-logger",
                        "--user", "--client"))
                .requires("--alter", one("--entity-type", "--topic", "--broker", "--broker-defaults", "--broker-logger",
                        "--user", "--client"), one("--add-config", "--delete-config"))
                .writes("--alter")
                .dangerous("--alter");
        t.put(configs.name, configs.build());

        B acls = new B("kafka-acls", "Xem, thêm, xoá ACL (cần broker bật authorizer)")
                .mode("--list", "liệt kê ACL")
                .mode("--add", "thêm ACL")
                .mode("--remove", "xoá ACL")
                .connection()
                .values("--topic", "topic", "topic", "ACL trên topic ('*' = mọi topic)")
                .values("--group", "group", "group", "ACL trên consumer group")
                .flag("--cluster", "ACL trên cluster")
                .values("--transactional-id", "string", "id", "ACL trên transactional id")
                .value("--resource-pattern-type", "patternType", "type", "literal, prefixed (match/any để lọc)")
                .values("--allow-principal", "principal", "User:name", "principal được phép", "--add", "--remove")
                .values("--deny-principal", "principal", "User:name", "principal bị cấm", "--add", "--remove")
                .values("--allow-host", "string", "host", "host được phép (mặc định *)", "--add", "--remove")
                .values("--deny-host", "string", "host", "host bị cấm", "--add", "--remove")
                .values("--operation", "operation", "op", "Read, Write, Create, Delete, Alter, Describe, All...",
                        "--add", "--remove")
                .flag("--producer", "đủ quyền cho producer (Write, Describe, Create trên topic)", "--add", "--remove")
                .flag("--consumer", "đủ quyền cho consumer (Read, Describe trên topic và group)", "--add", "--remove")
                .flag("--idempotent", "kèm IdempotentWrite cho --producer", "--add", "--remove")
                .values("--principal", "principal", "User:name", "chỉ ACL của principal này", "--list")
                .flag("--force", "xoá không hỏi lại", "--remove")
                .requires("--add", one("--topic", "--group", "--cluster", "--transactional-id"),
                        one("--allow-principal", "--deny-principal"), one("--operation", "--producer", "--consumer"))
                .requires("--remove", one("--topic", "--group", "--cluster", "--transactional-id"))
                .writes("--add", "--remove")
                .dangerous("--remove");
        t.put(acls.name, acls.build());

        B offsets = new B("kafka-get-offsets", "Offset của partition tại 1 thời điểm (đầu, cuối, theo thời gian)")
                .connection()
                .value("--topic", "topic", "topic", "topic (regex được)")
                .value("--partitions", "string", "0,1", "chỉ các partition này")
                .value("--topic-partitions", "string", "t1:1,t2:0-3,t3", "danh sách topic[:partition|khoảng]")
                .value("--time", "time", "-1|-2|-3|ms", "-1/latest, -2/earliest, -3/max-timestamp, hoặc mốc thời gian (ms)")
                .flag("--exclude-internal-topics", "bỏ topic nội bộ");
        t.put(offsets.name, offsets.build());

        B deleteRecords = new B("kafka-delete-records", "Xoá message cũ hơn 1 offset (theo file JSON)")
                .connection()
                .value("--offset-json-file", "file", "file",
                        "{\"partitions\":[{\"topic\":\"t\",\"partition\":0,\"offset\":10}]} (offset -1 = tới cuối)")
                .requires("", one("--offset-json-file"))
                .writes("*")
                .dangerous("*");
        t.put(deleteRecords.name, deleteRecords.build());

        B logDirs = new B("kafka-log-dirs", "Dung lượng của từng partition trên đĩa của broker")
                .mode("--describe", "xem log dir")
                .connection()
                .value("--topic-list", "topicList", "t1,t2", "chỉ các topic này")
                .value("--broker-list", "string", "1,2", "chỉ các broker này");
        t.put(logDirs.name, logDirs.build());

        B reassign = new B("kafka-reassign-partitions", "Chuyển replica của partition sang broker khác")
                .mode("--generate", "đề xuất cách phân bổ replica lên các broker cho trước (chỉ in ra)")
                .mode("--execute", "bắt đầu chuyển theo file JSON")
                .mode("--verify", "kiểm tra đã chuyển xong chưa")
                .mode("--cancel", "huỷ các lần chuyển đang chạy trong file JSON")
                .mode("--list", "các lần chuyển đang chạy")
                .connection()
                .value("--topics-to-move-json-file", "file", "file", "{\"topics\":[{\"topic\":\"t\"}],\"version\":1}",
                        "--generate")
                .value("--broker-list", "string", "1,2,3", "các broker nhận replica", "--generate")
                .value("--reassignment-json-file", "file", "file",
                        "{\"partitions\":[{\"topic\":\"t\",\"partition\":0,\"replicas\":[1,2]}],\"version\":1}",
                        "--execute", "--verify", "--cancel")
                .flag("--disable-rack-aware", "bỏ qua - đề xuất không xét rack", "--generate")
                .value("--throttle", "long", "bytes/s", "giới hạn tốc độ sao chép replica giữa các broker trong lúc chuyển",
                        "--execute")
                .value("--replica-alter-log-dirs-throttle", "long", "bytes/s",
                        "giới hạn tốc độ chuyển replica giữa các log dir trên cùng broker", "--execute")
                .flag("--additional", "chạy thêm dù đang có lần chuyển khác", "--execute")
                .flag("--preserve-throttles", "giữ throttle (mặc định gỡ khi đã chuyển xong/huỷ)", "--verify", "--cancel")
                .value("--timeout", "int", "ms", "thời gian chờ broker")
                .requires("--generate", one("--topics-to-move-json-file"), one("--broker-list"))
                .requires("--execute", one("--reassignment-json-file"))
                .requires("--verify", one("--reassignment-json-file"))
                .requires("--cancel", one("--reassignment-json-file"))
                .writes("--execute", "--cancel")
                .dangerous("--execute", "--cancel");
        t.put(reassign.name, reassign.build());

        B streams = new B("kafka-streams-application-reset",
                "Đặt lại ứng dụng Kafka Streams: offset topic input về đầu, xoá topic nội bộ")
                .connection()
                .value("--application-id", "group", "id", "application.id của ứng dụng (cũng là tên consumer group)")
                .value("--input-topics", "topicList", "t1,t2", "topic input: đặt lại offset (mặc định về đầu)")
                .value("--intermediate-topics", "topicList", "t1,t2", "topic trung gian: nhảy tới cuối")
                .value("--internal-topics", "topicList", "t1,t2",
                        "topic nội bộ cần xoá (mặc định: <application-id>-...-changelog, -repartition)")
                .flag("--to-earliest", "về offset đầu (mặc định)")
                .flag("--to-latest", "tới offset cuối")
                .value("--to-offset", "long", "n", "tới offset n")
                .value("--shift-by", "long", "n", "dịch n offset (âm = lùi)")
                .value("--to-datetime", "datetime", "YYYY-MM-DDTHH:mm:SS.sss", "tới message đầu tiên từ thời điểm này")
                .value("--by-duration", "duration", "PnDTnHnMnS", "lùi về trước 1 khoảng thời gian")
                .value("--from-file", "file", "file", "CSV topic,partition,offset")
                .flag("--dry-run", "chỉ xem trước, không đổi gì")
                .flag("--force", "đá các member đang chạy ra khỏi group trước khi đặt lại")
                .requires("", one("--application-id"))
                .writes("*")
                .dangerous("*");
        t.put(streams.name, streams.build());

        B election = new B("kafka-leader-election", "Bầu lại leader cho partition (preferred hoặc unclean)")
                .connection()
                .value("--election-type", "electionType", "type", "PREFERRED: về replica ưu tiên; UNCLEAN: cho replica ngoài ISR làm leader")
                .value("--topic", "topic", "topic", "topic cần bầu lại leader")
                .value("--partition", "partition", "n", "partition (cần --topic)")
                .flag("--all-topic-partitions", "mọi partition của mọi topic")
                .value("--path-to-json-file", "file", "file", "{\"partitions\":[{\"topic\":\"t\",\"partition\":0}]}")
                .requires("", one("--election-type"), one("--topic", "--all-topic-partitions", "--path-to-json-file"))
                .writes("*");
        t.put(election.name, election.build());

        B tx = new B("kafka-transactions", "Xem, huỷ transaction của producer; tìm transaction bị treo")
                .mode("list", "liệt kê transactional id")
                .mode("describe", "trạng thái 1 transaction")
                .mode("describe-producers", "producer đang ghi vào 1 partition")
                .mode("find-hanging", "transaction mở quá lâu trên partition (treo, chặn read_committed)")
                .mode("abort", "huỷ transaction đang mở trên 1 partition")
                .connection()
                .value("--transactional-id", "transactionalId", "id", "transactional id", "describe")
                .value("--duration-filter", "long", "ms", "chỉ transaction chạy lâu hơn (ms)", "list")
                .value("--topic", "topic", "topic", "topic", "describe-producers", "find-hanging", "abort")
                .value("--partition", "partition", "n", "partition", "describe-producers", "find-hanging", "abort")
                .value("--broker-id", "broker", "id", "broker (describe-producers: hỏi replica này thay vì leader; "
                        + "find-hanging: mọi partition của broker)", "describe-producers", "find-hanging")
                .value("--max-transaction-timeout", "int", "phút", "mở lâu hơn chừng này phút là treo (mặc định 15)",
                        "find-hanging")
                .value("--start-offset", "long", "offset", "offset bắt đầu của transaction cần huỷ", "abort")
                .value("--producer-id", "long", "id", "producer id (thay cho --start-offset)", "abort")
                .value("--producer-epoch", "int", "epoch", "producer epoch", "abort")
                .value("--coordinator-epoch", "int", "epoch", "coordinator epoch", "abort")
                .requires("describe", one("--transactional-id"))
                .requires("describe-producers", one("--topic"), one("--partition"))
                .requires("find-hanging", one("--topic", "--broker-id"))
                .requires("abort", one("--topic"), one("--partition"), one("--start-offset", "--producer-id"))
                .writes("abort")
                .dangerous("abort");
        t.put(tx.name, tx.build());

        B cluster = new B("kafka-cluster", "Thông tin cluster")
                .mode("cluster-id", "id của cluster")
                .connection();
        t.put(cluster.name, cluster.build());

        B quorum = new B("kafka-metadata-quorum", "Trạng thái quorum controller (KRaft)")
                .mode("describe", "xem quorum")
                .connection()
                .value("--bootstrap-controller", "string", "host:port", "bỏ qua - dùng kết nối của context")
                .flag("--status", "leader, epoch, high watermark, voter/observer", "describe")
                .flag("--replication", "độ trễ của từng voter/observer", "describe")
                .flag("--human-readable", "thời gian dạng dễ đọc", "describe")
                .requires("describe", one("--status", "--replication"));
        t.put(quorum.name, quorum.build());

        B sr = new B("schema-registry", "Schema Registry (không phải tool có thật - Schema Registry chỉ có REST API)")
                .mode("--list", "liệt kê subject")
                .mode("--versions", "các version của 1 subject")
                .mode("--describe", "schema của subject (version mới nhất hoặc --version)")
                .mode("--register", "đăng ký schema mới cho subject")
                .mode("--delete", "xoá subject hoặc 1 version")
                .mode("--compatibility", "xem/đặt mức tương thích")
                .mode("--id", "schema theo id")
                .value("--subject", "subject", "subject", "subject (thường là <topic>-value / <topic>-key)")
                .value("--schema-id", "int", "id", "id của schema", "--id")
                .value("--version", "version", "n|latest", "version", "--describe", "--delete")
                .value("--schema", "string", "schema", "nội dung schema", "--register")
                .value("--schema-file", "file", "file", "đọc schema từ file", "--register")
                .value("--schema-type", "schemaType", "type", "AVRO (mặc định), JSON, PROTOBUF", "--register")
                .value("--level", "compatLevel", "level", "BACKWARD, FORWARD, FULL, NONE..., bỏ trống = xem", "--compatibility")
                .value("--prefix", "string", "prefix", "chỉ subject bắt đầu bằng", "--list")
                .flag("--deleted", "kèm subject đã xoá mềm", "--list", "--versions")
                .flag("--permanent", "xoá hẳn (phải xoá mềm trước)", "--delete")
                .requires("--versions", one("--subject"))
                .requires("--describe", one("--subject"))
                .requires("--register", one("--subject"), one("--schema", "--schema-file"))
                .requires("--delete", one("--subject"))
                .requires("--id", one("--schema-id"))
                .writes("--register", "--delete", "--compatibility")
                .dangerous("--delete");
        t.put(sr.name, sr.build());
        return t;
    }

    // ------------------------------------------------------------------ giá trị cố định

    static final Map<String, List<String[]>> ENUMS = Map.ofEntries(
            Map.entry("entityType", List.of(new String[]{"topics", "config của topic"},
                    new String[]{"brokers", "config của broker"}, new String[]{"broker-loggers", "mức log của broker"},
                    new String[]{"users", "quota theo user"}, new String[]{"clients", "quota theo client-id"})),
            Map.entry("isolation", List.of(new String[]{"read_committed", "bỏ message của transaction chưa commit/bị huỷ"},
                    new String[]{"read_uncommitted", "đọc mọi message (mặc định)"})),
            Map.entry("operation", List.of(new String[]{"Read", null}, new String[]{"Write", null},
                    new String[]{"Create", null}, new String[]{"Delete", null}, new String[]{"Alter", null},
                    new String[]{"Describe", null}, new String[]{"ClusterAction", null},
                    new String[]{"DescribeConfigs", null}, new String[]{"AlterConfigs", null},
                    new String[]{"IdempotentWrite", null}, new String[]{"All", "mọi thao tác"})),
            Map.entry("patternType", List.of(new String[]{"literal", "đúng tên (mặc định)"},
                    new String[]{"prefixed", "mọi tên bắt đầu bằng"}, new String[]{"match", "(lọc) khớp theo cả 2 cách"},
                    new String[]{"any", "(lọc) mọi kiểu"})),
            Map.entry("offset", List.of(new String[]{"earliest", "đầu partition"}, new String[]{"latest", "cuối partition"})),
            Map.entry("time", List.of(new String[]{"-1", "latest: offset cuối"}, new String[]{"-2", "earliest: offset đầu"},
                    new String[]{"-3", "max-timestamp: message có timestamp lớn nhất"})),
            Map.entry("compression", List.of(new String[]{"none", null}, new String[]{"gzip", null},
                    new String[]{"snappy", null}, new String[]{"lz4", null}, new String[]{"zstd", null})),
            Map.entry("acks", List.of(new String[]{"all", "chờ mọi ISR (= -1)"}, new String[]{"1", "chỉ chờ leader"},
                    new String[]{"0", "không chờ"})),
            Map.entry("groupState", List.of(new String[]{"Stable", null}, new String[]{"Empty", null},
                    new String[]{"PreparingRebalance", null}, new String[]{"CompletingRebalance", null},
                    new String[]{"Dead", null})),
            Map.entry("electionType", List.of(new String[]{"PREFERRED", "về replica ưu tiên (replica đầu tiên)"},
                    new String[]{"UNCLEAN", "cho replica ngoài ISR làm leader - có thể mất dữ liệu"})),
            Map.entry("schemaType", List.of(new String[]{"AVRO", null}, new String[]{"JSON", null},
                    new String[]{"PROTOBUF", null})),
            Map.entry("compatLevel", List.of(new String[]{"BACKWARD", null}, new String[]{"BACKWARD_TRANSITIVE", null},
                    new String[]{"FORWARD", null}, new String[]{"FORWARD_TRANSITIVE", null}, new String[]{"FULL", null},
                    new String[]{"FULL_TRANSITIVE", null}, new String[]{"NONE", null})),
            Map.entry("version", properties("latest", "version mới nhất")),
            Map.entry("consumerProperty", properties("print.key=true", "in key", "print.value=false", "không in value",
                    "print.timestamp=true", "in thời điểm", "print.partition=true", "in partition",
                    "print.offset=true", "in offset", "print.headers=true", "in header", "key.separator=",
                    "giữa các cột (mặc định tab)", "headers.separator=", "giữa các header (mặc định ,)",
                    "null.literal=", "chữ in cho null (mặc định null)")),
            Map.entry("srConsumerProperty", properties("print.key=true", "in key", "print.timestamp=true", "in thời điểm",
                    "print.partition=true", "in partition", "print.offset=true", "in offset",
                    "print.headers=true", "in header", "print.schema.ids=true", "in schema id",
                    "key.separator=", "giữa các cột (mặc định tab)", "schema.registry.url=", "mặc định lấy từ context")),
            Map.entry("producerProperty", properties("parse.key=true", "dòng có key: key<tab>value",
                    "key.separator=", "giữa key và value (mặc định tab)", "parse.headers=true",
                    "dòng có header: h1:v1,h2:v2<tab>...", "headers.delimiter=", "giữa header và phần còn lại",
                    "headers.separator=", "giữa các header", "headers.key.separator=", "giữa tên và giá trị header",
                    "null.marker=", "chữ coi là null", "ignore.error=true", "bỏ qua dòng sai định dạng")),
            Map.entry("srProducerProperty", properties("value.schema=", "schema của value (JSON)",
                    "value.schema.id=", "dùng schema đã đăng ký", "value.schema.file=", "đọc schema từ file",
                    "key.schema=", "schema của key", "parse.key=true", "dòng có key: key<tab>value",
                    "key.separator=", "giữa key và value (mặc định tab)", "schema.registry.url=", "mặc định lấy từ context",
                    "value.message=", "protobuf: message dùng cho value (mặc định message đầu tiên)",
                    "value.refs=", "schema được tham chiếu: [{\"name\":\"x.proto\",\"subject\":\"s\",\"version\":1}]",
                    "key.message=", "protobuf: message dùng cho key")));

    /** Cho test (I18nTest): mọi mô tả giá trị cố định đều phải có bản tiếng Anh. */
    public static final Map<String, List<String[]>> ENUMS_FOR_TEST = ENUMS;

    private static List<String[]> properties(String... pairs) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.add(new String[]{pairs[i], pairs[i + 1]});
        }
        return out;
    }

    /** Dòng hướng dẫn ngắn của tool (theo chế độ nếu đã chọn). */
    public static String usage(Tool tool, String mode) {
        StringBuilder sb = new StringBuilder(tool.name());
        if (tool.hasModes()) {
            sb.append(' ').append(mode != null ? mode : String.join(" | ", tool.modes()));
        }
        for (Opt o : tool.options()) {
            if (o.name().equals("--bootstrap-server") || o.name().equals("--command-config")
                    || o.help().startsWith("bỏ qua") || o.help().startsWith("(cũ)")) {
                continue;
            }
            if (mode != null && !o.modes().isEmpty() && !o.modes().contains(mode)) {
                continue;
            }
            if (mode == null && !o.modes().isEmpty()) {
                continue;
            }
            boolean required = tool.requires().getOrDefault(mode == null ? "" : mode, List.of()).stream()
                    .anyMatch(g -> g.size() == 1 && g.get(0).equals(o.name()));
            String part = o.name() + (o.arity() == Arity.FLAG ? "" : " <" + o.meta() + ">");
            sb.append(' ').append(required ? part : "[" + part + "]");
        }
        return sb.toString();
    }
}
