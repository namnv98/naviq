package com.sqlctx.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaArgsTest {

    private static List<String> words(String line) {
        return KafkaArgs.tokenize(line).stream().map(KafkaArgs.Token::text).toList();
    }

    @Test
    void táchNhưShell() {
        assertEquals(List.of("kafka-configs", "--add-config", "a=1,b=[x,y]", "it's", "a\"b"),
                words("kafka-configs --add-config 'a=1,b=[x,y]' \"it's\" a\\\"b"));
        assertEquals(List.of("kafka-topics", "--list"), words("kafka-topics \\\n  --list"));
        assertEquals(List.of("x", "chưa đóng"), words("x 'chưa đóng"));
    }

    @Test
    void chếĐộVàGiáTrị() {
        KafkaArgs.Parsed p = KafkaArgs.parse("kafka-topics --create --topic orders --partitions 3 --config a=1 --config b=2");
        assertEquals("--create", p.mode());
        assertEquals("orders", p.get("--topic"));
        assertEquals(List.of("a=1", "b=2"), p.all("--config"));
        assertTrue(p.problems().isEmpty());
        assertTrue(p.missing().isEmpty());
    }

    @Test
    void dạngBằngVàBootstrapServerBịBỏQua() {
        KafkaArgs.Parsed p = KafkaArgs.parse("kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic=orders");
        assertEquals("orders", p.get("--topic"));
        assertTrue(p.problems().isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "kafka-topicz --list                       | không có tool",
            "kafka-topics --lst                        | không có tuỳ chọn --lst",
            "kafka-topics --list --partitions 3        | --partitions không dùng với --list",
            "kafka-topics --partitions 3 --list        | --partitions không dùng với --list",
            "kafka-topics --list --describe            | chỉ chọn 1 trong",
            "kafka-topics --create --topic --partitions 1 | thiếu giá trị cho --topic",
            "kafka-topics --list orders                | thừa",
            "kafka-cluster cluster-idd                 | lệnh con phải là",
    })
    void lỗiCúPháp(String line, String message) {
        KafkaArgs.Parsed p = KafkaArgs.parse(line);
        assertTrue(!p.problems().isEmpty() && p.problems().get(0).message().contains(message),
                p.problems().toString());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "kafka-topics                                       | --list",
            "kafka-topics --create                              | --topic",
            "kafka-consumer-groups --reset-offsets --group g    | --topic",
            "kafka-consumer-groups --reset-offsets --group g --topic t | --to-earliest",
            "kafka-acls --add --topic t --allow-principal User:a | --operation",
            "kafka-console-consumer                             | --topic",
            "kafka-metadata-quorum describe                     | --status",
    })
    void thiếuTuỳChọnBắtBuộc(String line, String expected) {
        List<List<String>> missing = KafkaArgs.parse(line).missing();
        assertTrue(!missing.isEmpty() && missing.get(0).contains(expected), missing.toString());
    }

    @Test
    void lệnhConĐứngSauTool() {
        KafkaArgs.Parsed p = KafkaArgs.parse("kafka-metadata-quorum describe --status");
        assertEquals("describe", p.mode());
        assertTrue(p.problems().isEmpty());
        assertTrue(p.missing().isEmpty());
    }

    @Test
    void stateCóGiáTrịTuỳChọn() {
        assertEquals("Stable", KafkaArgs.parse("kafka-consumer-groups --list --state Stable").get("--state"));
        KafkaArgs.Parsed p = KafkaArgs.parse("kafka-consumer-groups --describe --state --group g");
        assertTrue(p.has("--state"));
        assertNull(p.get("--state"));
        assertEquals("g", p.get("--group"));
    }

    @Test
    void gộpDòngNốiVàBỏComment() {
        assertEquals(List.of("kafka-topics --describe --topic t", "kafka-topics --list"),
                KafkaCli.lines("# comment\nkafka-topics --describe \\\n  --topic t\n\nkafka-topics --list\n"));
    }

    @Test
    void danhSáchConfigCóNgoặc() {
        assertEquals(java.util.Map.of("a", "1", "cleanup.policy", "compact,delete"),
                KafkaCommands.configList(List.of("a=1,cleanup.policy=[compact,delete]")));
    }

    @Test
    void thờiĐiểmCóVàKhôngCóMúiGiờ() {
        assertEquals(0L, KafkaCommands.parseDatetime("1970-01-01T00:00:00Z"));
        assertEquals(1000L, KafkaCommands.parseDatetime("1970-01-01T07:00:01+07:00"));
    }

    @Test
    void topicNộiBộCủaKafkaStreams() {
        assertTrue(KafkaCommands.internalTopic("app", "app-store-changelog"));
        assertTrue(KafkaCommands.internalTopic("app", "app-KSTREAM-AGGREGATE-0000000003-repartition"));
        assertTrue(KafkaCommands.internalTopic("app", "app-KTABLE-FK-JOIN-SUBSCRIPTION-REGISTRATION-0000000006-subscription-registration-topic"));
        assertFalse(KafkaCommands.internalTopic("app", "app-input"), "topic input của chính ứng dụng không bị xoá");
        assertFalse(KafkaCommands.internalTopic("app", "app2-store-changelog"), "ứng dụng khác có tên bắt đầu giống");
    }

    @Test
    void đềXuấtPhânBổReplicaVòngTròn() {
        var current = new java.util.LinkedHashMap<org.apache.kafka.common.TopicPartition, List<Integer>>();
        current.put(new org.apache.kafka.common.TopicPartition("a", 0), List.of(1, 2));
        current.put(new org.apache.kafka.common.TopicPartition("a", 1), List.of(1, 2));
        current.put(new org.apache.kafka.common.TopicPartition("b", 0), List.of(3, 1));
        var proposed = KafkaCommands.proposeAssignment(current, List.of(4, 5, 6));
        assertEquals(List.of(List.of(4, 5), List.of(5, 6), List.of(5, 6)), List.copyOf(proposed.values()));
        assertThrows(KafkaCommands.UsageException.class, () -> KafkaCommands.proposeAssignment(current, List.of(4)));
    }
}
