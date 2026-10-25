package com.sqlctx.kafka;

import com.sqlctx.util.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaStatsTest {

    @BeforeEach
    void english() {
        I18n.set(I18n.Lang.EN, false);
    }

    @AfterEach
    void vietnamese() {
        I18n.set(I18n.Lang.VI, false);
    }

    private static KafkaStats.Broker broker(int id, long total, long usable) {
        return new KafkaStats.Broker(id, "b" + id, 9092, null, id == 1, 3, 6, total, usable);
    }

    private static KafkaStats.Sample sample(long at, long ordersEnd, long lag, int members) {
        return new KafkaStats.Sample(at, "c1", List.of(broker(1, 100, 50), broker(2, 100, 5)),
                List.of(new KafkaStats.Topic("orders", false, 3, 2, ordersEnd, ordersEnd, 0, List.of(), List.of()),
                        new KafkaStats.Topic("logs", false, 1, 1, 10, 10, 1, List.of("logs-0"), List.of()),
                        new KafkaStats.Topic("__consumer_offsets", true, 50, 1, 0, 0, 0, List.of(), List.of())),
                List.of(new KafkaStats.Group("billing", "Stable", members, lag, ordersEnd - lag, List.of())),
                new KafkaStats.Quorum(1, 3, 0));
    }

    @Test
    void tốcĐộTừChênhLệchOffset() {
        List<String> first = KafkaStats.frame("t", null, sample(0, 1000, 10, 1), 0);
        assertTrue(first.stream().anyMatch(l -> l.contains("orders") && l.contains("…")), "lần đầu chưa có tốc độ\n" + first);
        // 2 giây sau: ghi thêm 400 message (200 msg/s), group đọc thêm 380 (190 msg/s), lag tăng 20
        List<String> next = KafkaStats.frame("t", sample(0, 1000, 10, 1), sample(2000, 1400, 30, 1), 0);
        String all = String.join("\n", next);
        assertTrue(next.stream().anyMatch(l -> l.contains("orders") && l.contains("200")), all);
        assertTrue(next.stream().anyMatch(l -> l.contains("billing") && l.contains("↑ +20") && l.contains("190")), all);
        assertTrue(all.contains("topics: 2 (+1 internal) · partitions: 54 · under-replicated: 1 · offline: 1"), all);
        assertTrue(all.contains("quorum: leader 1, epoch 3, lag 0"), all);
    }

    @Test
    void cảnhBáo() {
        String all = String.join("\n", KafkaStats.warnings(sample(0, 1000, 10, 1), sample(2000, 1400, 30, 1)));
        for (String expected : List.of("1 partitions have no leader (cannot read/write): logs-0", "under-replicated partitions in: logs",
                "broker 2: disk 95% used", "1 topics have a single replica", "group billing: lag is growing (+20, now 30)")) {
            assertTrue(all.contains(expected), expected + "\n" + all);
        }
        String idle = String.join("\n", KafkaStats.warnings(null, sample(0, 1000, 10, 0)));
        assertTrue(idle.contains("group billing: lag 10 but no consumer is running"), idle);
        assertEquals("50 B / 100 B (50%)", KafkaStats.disk(broker(1, 100, 50)));
        assertEquals("-", KafkaStats.disk(broker(1, -1, -1)));
    }
}
