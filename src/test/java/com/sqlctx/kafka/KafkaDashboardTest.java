package com.sqlctx.kafka;

import com.sqlctx.shell.Dashboard;
import com.sqlctx.shell.Screen;
import com.sqlctx.util.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaDashboardTest {

    @BeforeEach
    void english() {
        I18n.set(I18n.Lang.EN, false);
    }

    @AfterEach
    void vietnamese() {
        I18n.set(I18n.Lang.VI, false);
    }

    private static KafkaStats.Sample sample(long at, long end, long committed) {
        var parts = List.of(new KafkaStats.Part(0, 1, List.of(1, 2), List.of(1), 0, end));
        return new KafkaStats.Sample(at, "c1",
                List.of(new KafkaStats.Broker(1, "b1", 9092, null, true, 1, 2, 1000, 100),
                        new KafkaStats.Broker(2, "b2", 9092, "r2", false, 0, 1, -1, -1)),
                List.of(new KafkaStats.Topic("orders", false, 1, 2, end, end, 1, List.of(), parts)),
                List.of(new KafkaStats.Group("billing", "Stable", 1, end - committed, committed,
                        List.of(new KafkaStats.GroupPart("orders", 0, committed, end, end - committed, "c-1 /10.0.0.1")))),
                new KafkaStats.Quorum(1, 7, 0));
    }

    private static KafkaDashboard dashboard() {
        KafkaDashboard d = new KafkaDashboard(() -> null, "dev");
        d.add(sample(0, 1000, 900));
        d.add(sample(1000, 1500, 1300));
        d.add(sample(2000, 2000, 1700)); // ghi 500 msg/s, đọc 400 msg/s (tính giữa 2 lần commit thấy được)
        return d;
    }

    @Test
    void vừaMànHìnhỞMọiCỡ() {
        for (int width : new int[]{80, 140}) {
            Dashboard.State st = new Dashboard.State();
            st.intervalMs = 5000;
            List<String> screen = dashboard().render(width, 30, st);
            assertTrue(screen.size() <= 30, "tối đa chiều cao");
            screen.forEach(l -> assertTrue(Screen.width(l) <= width, width + ": " + Screen.width(l) + " " + l));
            String all = String.join("\n", screen).replaceAll("\u001b\\[[0-9;]*m", "");
            for (String expected : List.of("KAFKA", "Brokers 2", "controller 1", "Write msg/s", "Read msg/s", "Total lag",
                    "1 under", "Topic 1", "Consumer group 1", "orders", "billing", "500", "400", "Warnings", "q", "quit")) {
                assertTrue(all.contains(expected), width + " thiếu " + expected + "\n" + all);
            }
        }
    }

    @Test
    void chiTiếtTopicVàGroup() {
        Dashboard.State st = new Dashboard.State();
        st.intervalMs = 5000;
        st.detail = true;
        String topic = String.join("\n", dashboard().render(140, 30, st)).replaceAll("\u001b\\[[0-9;]*m", "");
        assertTrue(topic.contains("Topic orders · 1 partition · RF 2") && topic.contains("1,2"), topic);
        st.panel = 1;
        String group = String.join("\n", dashboard().render(140, 30, st)).replaceAll("\u001b\\[[0-9;]*m", "");
        assertTrue(group.contains("Group billing · Stable") && group.contains("c-1 /10.0.0.1") && group.contains("committed"), group);
        assertTrue(group.contains("Esc") && group.contains("back"), group);
    }

    @Test
    void chuỗiTốcĐộ() {
        var h = List.of(sample(0, 1000, 900), sample(2000, 1600, 1000));
        assertEquals(List.of(300.0), KafkaDashboard.produceSeries(h));
        assertEquals(List.of(300.0), KafkaDashboard.topicSeries(h, "orders"));
        // commit 5 giây 1 lần, lấy mẫu 2 giây 1 lần: tốc độ đọc tính giữa 2 lần commit, giữ nguyên ở giữa
        var c = List.of(sample(0, 0, 100), sample(2000, 0, 200), sample(4000, 0, 200), sample(6000, 0, 200),
                sample(8000, 0, 800), sample(10000, 0, 800), sample(12000, 0, 800), sample(14000, 0, 800),
                sample(16000, 0, 800), sample(18000, 0, 800), sample(20000, 0, 800), sample(22000, 0, 800),
                sample(24000, 0, 800), sample(26000, 0, 800), sample(28000, 0, 800), sample(30000, 0, 800));
        assertEquals(List.of(0.0, 0.0, 0.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                KafkaDashboard.consumeSeries(c), "chưa thấy 2 lần commit thì 0; quá 2 nhịp không commit thì về 0");
    }
}
