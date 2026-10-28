package com.sqlctx.kafka;

import com.sqlctx.ui.Screen;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupTrendTest {

    /** 1 topic 1 partition, group "g" đã commit tới {@code committed}. */
    private static KafkaStats.Sample sample(long at, long end, long committed, int members) {
        return new KafkaStats.Sample(at, "c1", List.of(),
                List.of(new KafkaStats.Topic("t", false, 1, 1, end, end, 0, List.of(),
                        List.of(new KafkaStats.Part(0, 1, List.of(1), List.of(1), 0, end)))),
                List.of(new KafkaStats.Group("g", "Stable", members, end - committed, committed,
                        List.of(new KafkaStats.GroupPart("t", 0, committed, end, end - committed, "-")))),
                null);
    }

    @Test
    void etaKhiLagĐangGiảm() {
        // ghi 100 msg/s, đọc 200 msg/s: lag giảm 100 msg/s, còn 9500 -> 95 giây
        List<KafkaStats.Sample> h = new ArrayList<>();
        for (int i = 0; i <= 5; i++) {
            h.add(sample(i * 1000L, 10_000 + 100L * i, 200L * i, 1));
        }
        assertEquals(95.0, new GroupTrend(h).eta("g"), 0.01);
        // cũng tốc độ đó nhưng lag chỉ là vài giây ghi: đọc kịp
        List<KafkaStats.Sample> near = new ArrayList<>();
        for (int i = 0; i <= 5; i++) {
            near.add(sample(i * 1000L, 1000 + 100L * i, 200L * i, 1));
        }
        assertTrue(new GroupTrend(near).eta("g").isNaN());
        assertNull(new GroupTrend(h.subList(0, 3)).eta("g"), "chưa đủ 3 lần commit");
    }

    @Test
    void etaKhiĐọcKịpHoặcKhôngKịp() {
        // lag dao động quanh 100: đọc kịp
        List<KafkaStats.Sample> steady = new ArrayList<>();
        for (int i = 0; i <= 6; i++) {
            steady.add(sample(i * 1000L, 1000 + 100L * i, 900 + 100L * i + (i % 2 == 0 ? 0 : 20), 1));
        }
        assertTrue(new GroupTrend(steady).eta("g").isNaN());
        // lag tăng qua 6 lần commit: không bao giờ kịp
        List<KafkaStats.Sample> slow = new ArrayList<>();
        for (int i = 0; i <= 6; i++) {
            slow.add(sample(i * 1000L, 1000 + 100L * i, 900 + 50L * i, 1));
        }
        assertTrue(new GroupTrend(slow).eta("g").isInfinite());
        assertEquals(250, new GroupTrend(slow).slowBy("g"));
        assertEquals(0.0, new GroupTrend(List.of(sample(0, 10, 10, 1))).eta("g"));
    }

    @Test
    void chậmBaoLâuNộiSuyTừOffsetCuối() {
        // offset cuối: 0s=1000, 10s=2000, 20s=3000; group đứng ở 1500 -> message 1500 ghi lúc ~5s, giờ là 20s -> chậm 15s
        var h = List.of(sample(0, 1000, 1000, 1), sample(10_000, 2000, 1500, 1), sample(20_000, 3000, 1500, 1));
        assertEquals(new GroupTrend.TimeLag(15_000, false), new GroupTrend(h).timeLag("g"));
        // offset commit cũ hơn mọi mẫu đang giữ: chỉ biết là chậm hơn 20s
        var old = List.of(sample(0, 1000, 500, 1), sample(10_000, 2000, 500, 1), sample(20_000, 3000, 500, 1));
        assertEquals(new GroupTrend.TimeLag(20_000, true), new GroupTrend(old).timeLag("g"));
        assertEquals(new GroupTrend.TimeLag(0, false), new GroupTrend(List.of(sample(0, 10, 10, 1))).timeLag("g"));
    }

    @Test
    void kẹtKhiOffsetĐứngYên() {
        List<KafkaStats.Sample> h = new ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            h.add(sample(i * 5000L, 1000 + 100L * i, 900, 1));
        }
        assertEquals(40_000, new GroupTrend(h).stalledMs("g"));
        assertTrue(new GroupTrend(h).eta("g").isInfinite());
    }

    @Test
    void địnhDạngThờiGian() {
        assertEquals("45s", Screen.duration(45_000));
        assertEquals("3m05s", Screen.duration(185_000));
        assertEquals("2h05m", Screen.duration(7_500_000));
        assertEquals("3d04h", Screen.duration((3 * 86400 + 4 * 3600) * 1000L));
    }
}
