package com.sqlctx.kafka;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Chạy với broker + Schema Registry thật của docker-compose.yml (localhost:19092, localhost:18081); không có thì bỏ qua.
 * Mỗi lần chạy dùng topic tên riêng và xoá đi ở cuối.
 */
class KafkaIntegrationTest {

    private static final String BOOTSTRAP = System.getProperty("sqlctx.it.kafka", "localhost:19092");
    private static final String REGISTRY = System.getProperty("sqlctx.it.schemaRegistry", "http://localhost:18081");
    private static final String TOPIC = "it-" + Long.toString(System.nanoTime(), 36);

    private static KafkaSession session;
    /** Giữ tham chiếu: Logger chỉ được giữ yếu, bị GC thì mất mức log đã đặt. */
    private static final java.util.logging.Logger KAFKA_LOG = java.util.logging.Logger.getLogger("org.apache.kafka");

    /** Gom kết quả thay cho khung bảng của REPL. */
    private static final class Captured implements KafkaCommands.Out {
        final List<List<String>> rows = new ArrayList<>();
        final List<String> text = new ArrayList<>();

        public void table(List<String> headers, List<List<String>> r) {
            rows.addAll(r);
        }

        public void lines(List<String> l) {
            text.addAll(l);
        }

        public void info(String line) {
            text.add(line);
        }

        String all() {
            return String.join("\n", text) + "\n" + rows;
        }
    }

    private static boolean reachable(String hostPort) {
        String[] hp = hostPort.replaceFirst("^https?://", "").split(":");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @BeforeAll
    static void connect() throws Exception {
        assumeTrue(reachable(BOOTSTRAP), "không có broker ở " + BOOTSTRAP);
        KAFKA_LOG.setLevel(java.util.logging.Level.WARNING);
        session = new KafkaSession(new KafkaProfiles.Profile("it", Map.of("bootstrap.servers", BOOTSTRAP),
                reachable(REGISTRY) ? REGISTRY : null, null, false));
        run("kafka-topics --create --topic " + TOPIC + " --partitions 2 --replication-factor 1");
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (session != null) {
            run("kafka-topics --delete --topic " + TOPIC + " --if-exists");
            run("kafka-topics --delete --topic " + TOPIC + "-proto --if-exists");
            run("kafka-topics --delete --topic " + TOPIC + "-lag --if-exists");
            session.close();
        }
    }

    private static KafkaArgs.Parsed parse(String line) {
        KafkaArgs.Parsed p = KafkaArgs.parse(line);
        assertTrue(p.problems().isEmpty(), p.problems().toString());
        assertTrue(p.missing().isEmpty(), p.missing().toString());
        return p;
    }

    private static Captured run(String line) throws Exception {
        Captured out = new Captured();
        new KafkaCommands(session, out).run(parse(line));
        return out;
    }

    private static Supplier<String> linesOf(String... lines) {
        Iterator<String> it = List.of(lines).iterator();
        return () -> it.hasNext() ? it.next() : null;
    }

    private static int produce(String line, String... messages) throws Exception {
        return new KafkaConsole(session, new Captured(), s -> { }).produce(parse(line), linesOf(messages));
    }

    private static List<String> consume(String line) throws Exception {
        List<String> printed = new ArrayList<>();
        new KafkaConsole(session, new Captured(), printed::add).consume(parse(line), false);
        return printed;
    }

    @Test
    void topicVàConfig() throws Exception {
        assertTrue(run("kafka-topics --list").text.contains(TOPIC));
        Captured d = run("kafka-topics --describe --topic " + TOPIC);
        assertTrue(d.all().contains(TOPIC), d.all());
        run("kafka-configs --alter --topic " + TOPIC + " --add-config retention.ms=3600000");
        Captured c = run("kafka-configs --describe --topic " + TOPIC);
        assertTrue(c.rows.stream().anyMatch(r -> r.contains("retention.ms") && r.contains("3600000")), c.all());
    }

    @Test
    void ghiĐọcTheoGroupRồiXemLag() throws Exception {
        String topic = TOPIC + "-lag"; // riêng: test khác ghi thêm vào TOPIC làm sai số lag
        run("kafka-topics --create --topic " + topic + " --partitions 2 --replication-factor 1");
        assertEquals(3, produce("kafka-console-producer --topic " + topic + " --property parse.key=true --property key.separator=:",
                "a:1", "b:2", "c:3"));
        String group = TOPIC + "-g";
        List<String> got = consume("kafka-console-consumer --topic " + topic + " --group " + group
                + " --from-beginning --max-messages 3 --timeout-ms 10000 --property print.key=true");
        assertEquals(3, got.size(), got.toString());
        assertTrue(got.stream().anyMatch(l -> l.startsWith("b\t2")), got.toString());
        Captured lag = run("kafka-consumer-groups --describe --group " + group);
        long total = lag.rows.stream().filter(r -> r.size() > 5 && r.get(5).matches("\\d+")).mapToLong(r -> Long.parseLong(r.get(5))).sum();
        assertEquals(0, total, lag.all());
        run("kafka-consumer-groups --reset-offsets --group " + group + " --topic " + topic + " --to-earliest --execute");
        Captured after = run("kafka-consumer-groups --describe --group " + group);
        long lagAfter = after.rows.stream().filter(r -> r.size() > 5 && r.get(5).matches("\\d+")).mapToLong(r -> Long.parseLong(r.get(5))).sum();
        assertEquals(3, lagAfter, after.all());
        run("kafka-consumer-groups --delete --group " + group);
    }

    @Test
    void offsetVàXoáRecord() throws Exception {
        produce("kafka-console-producer --topic " + TOPIC, "x", "y");
        Captured o = run("kafka-get-offsets --topic " + TOPIC + " --time -1");
        assertEquals(2, o.rows.size(), o.all());
        Path f = Files.createTempFile("delete-records", ".json");
        Files.writeString(f, "{\"partitions\":[{\"topic\":\"" + TOPIC + "\",\"partition\":0,\"offset\":-1}]}");
        Captured d = run("kafka-delete-records --offset-json-file " + f);
        assertEquals(1, d.rows.size(), d.all());
        Files.delete(f);
    }

    @Test
    void partitionLeaderVàTransaction() throws Exception {
        Path f = Files.createTempFile("move", ".json");
        Files.writeString(f, "{\"topics\":[{\"topic\":\"" + TOPIC + "\"}],\"version\":1}");
        Captured g = run("kafka-reassign-partitions --generate --topics-to-move-json-file " + f + " --broker-list "
                + session.brokers().get(0));
        assertTrue(g.all().contains("\"partition\":1"), g.all());
        Files.writeString(f, "{\"partitions\":[{\"topic\":\"" + TOPIC + "\",\"partition\":0,\"replicas\":["
                + session.brokers().get(0) + "]}],\"version\":1}");
        run("kafka-reassign-partitions --execute --reassignment-json-file " + f);
        Captured v = run("kafka-reassign-partitions --verify --reassignment-json-file " + f);
        assertEquals(1, v.rows.size(), v.all());
        Files.delete(f);

        Captured e = run("kafka-leader-election --election-type preferred --topic " + TOPIC);
        assertEquals(2, e.rows.size(), e.all());

        run("kafka-transactions list");
        Captured p = run("kafka-transactions describe-producers --topic " + TOPIC + " --partition 0");
        assertTrue(p.text.isEmpty(), p.all());
        Captured h = run("kafka-transactions find-hanging --topic " + TOPIC);
        assertTrue(h.all().contains("2"), h.all());
    }

    @Test
    void protobufQuaSchemaRegistry() throws Exception {
        assumeTrue(session.schemaRegistry(null) != null, "không có Schema Registry ở " + REGISTRY);
        String topic = TOPIC + "-proto";
        run("kafka-topics --create --topic " + topic + " --partitions 1 --replication-factor 1");
        String schema = "syntax = \\\"proto3\\\"; message Click { string page = 1; int32 n = 2; Kind kind = 3; } enum Kind { VIEW = 0; BUY = 1; }";
        assertEquals(2, produce("kafka-protobuf-console-producer --topic " + topic + " --property \"value.schema=" + schema + "\"",
                "{\"page\":\"/home\",\"n\":3}", "{\"page\":\"/cart\",\"kind\":\"BUY\"}"));
        List<String> got = consume("kafka-protobuf-console-consumer --topic " + topic + " --from-beginning --max-messages 2 --timeout-ms 10000");
        assertEquals(List.of("{\"page\":\"/home\",\"n\":3}", "{\"page\":\"/cart\",\"kind\":\"BUY\"}"), got);
        run("schema-registry --delete --subject " + topic + "-value");
        run("schema-registry --delete --subject " + topic + "-value --permanent");
    }
}
