package com.sqlctx.kafka;

import com.sqlctx.ui.MenuItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaCompletionTest {

    private static final KafkaCompletion.Source SOURCE = new KafkaCompletion.Source() {
        public List<String> topics() {
            return List.of("orders", "payments", "__consumer_offsets");
        }

        public List<String> groups() {
            return List.of("billing", "mailer");
        }

        public List<String> transactionalIds() {
            return List.of("orders-tx-1", "payments-tx");
        }

        public List<String> brokers() {
            return List.of("1", "2");
        }

        public List<String> partitions(String topic) {
            return topic.equals("orders") ? List.of("0", "1", "2") : List.of();
        }

        public List<String> configNames(String entityType) {
            return entityType.equals("topics") ? List.of("cleanup.policy", "retention.ms") : List.of("log.retention.hours");
        }

        public List<String> subjects() {
            return List.of("orders-value");
        }

        public List<String> contexts() {
            return List.of("kafka-dev", "kafka-prod");
        }
    };

    private static List<String> values(String line) {
        return KafkaCompletion.suggest(line, line.length(), SOURCE).stream().map(MenuItem::value).toList();
    }

    @Test
    void tênTool() {
        assertEquals(List.of("kafka-console-consumer", "kafka-console-producer", "kafka-consumer-groups", "kafka-configs",
                "kafka-cluster"), values("kafka-c"));
    }

    @Test
    void chưaChọnChếĐộThìGợiÝChếĐộTrước() {
        List<String> v = values("kafka-topics ");
        assertEquals(List.of("--list", "--describe", "--create", "--delete", "--alter"), v.subList(0, 5));
        assertFalse(v.contains("--partitions"), "tuỳ chọn riêng của --create chưa hiện khi chưa chọn chế độ");
        assertFalse(v.contains("--bootstrap-server"), "tuỳ chọn bị bỏ qua thì không gợi ý");
    }

    @Test
    void tuỳChọnTheoChếĐộVàKhôngLặpLại() {
        List<String> v = values("kafka-topics --create --topic t ");
        assertTrue(v.containsAll(List.of("--partitions", "--replication-factor", "--config")), v.toString());
        assertFalse(v.contains("--topic"));
        assertFalse(v.contains("--list"), "đã chọn chế độ");
        assertTrue(values("kafka-topics --create --config a=1 ").contains("--config"), "--config lặp lại được");
    }

    @Test
    void giáTrịTheoLoại() {
        assertEquals(List.of("orders"), values("kafka-topics --describe --topic or"));
        assertEquals(List.of("billing", "mailer"), values("kafka-consumer-groups --describe --group "));
        assertEquals(List.of("0", "1", "2"), values("kafka-console-consumer --topic orders --partition "));
        assertEquals(List.of("1", "2"), values("kafka-configs --describe --broker "));
        assertEquals(List.of("retention.ms="), values("kafka-topics --create --topic t --config ret"));
        assertEquals(List.of("a=1,cleanup.policy="), values("kafka-configs --alter --topic t --add-config a=1,cle"));
        assertEquals(List.of("log.retention.hours="), values("kafka-configs --alter --broker 1 --add-config log"));
        assertEquals(List.of("Read"), values("kafka-acls --add --operation R"));
        assertEquals(List.of("print.key=true"), values("kafka-console-consumer --property print.k"));
        assertEquals(List.of("orders-value"), values("schema-registry --describe --subject "));
    }

    @Test
    void entityNameTheoEntityType() {
        assertTrue(values("kafka-configs --describe --entity-type topics --entity-name ").contains("orders"));
        assertEquals(List.of("1", "2"), values("kafka-configs --describe --entity-type brokers --entity-name "));
    }

    @Test
    void lệnhConVàLệnhGạchNgược() {
        assertEquals(List.of("cluster-id"), values("kafka-cluster "));
        assertEquals(List.of("\\ctx"), values("\\ct"));
        assertEquals(List.of("kafka-prod"), values("\\ctx kafka-p"));
    }

    @Test
    void giáTrịTựDoKhôngGợiÝTuỳChọn() {
        assertEquals(List.of(), values("kafka-topics --list or"));
    }

    @Test
    void toolQuảnTrịPartitionVàTransaction() {
        assertEquals(List.of("list", "describe", "describe-producers", "find-hanging", "abort"), values("kafka-transactions "));
        List<String> abort = values("kafka-transactions abort ");
        assertTrue(abort.containsAll(List.of("--topic", "--partition", "--start-offset", "--producer-id")), abort.toString());
        assertFalse(abort.contains("--transactional-id"), "tuỳ chọn của describe không hiện ở abort");
        assertEquals(List.of("0", "1", "2"), values("kafka-transactions describe-producers --topic orders --partition "));
        assertEquals(List.of("PREFERRED", "UNCLEAN"), values("kafka-leader-election --election-type "));
        assertEquals(List.of("orders-tx-1"), values("kafka-transactions describe --transactional-id or"));
        assertEquals(List.of("billing"), values("kafka-streams-application-reset --application-id b"));
        assertEquals(List.of("--generate", "--execute", "--verify", "--cancel", "--list"),
                values("kafka-reassign-partitions ").subList(0, 5));
        assertFalse(values("kafka-reassign-partitions --list ").contains("--reassignment-json-file"));
    }
}
