package com.sqlctx.kafka;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Diễn biến của consumer group qua lịch sử mẫu của \stats, theo cách của Burrow (LinkedIn) và kafka-lag-exporter.
 * Giữa 2 lần commit lag luôn tăng (offset cuối chạy, offset commit đứng yên) rồi tụt xuống khi commit, nên so 2 mẫu
 * liền nhau dễ báo nhầm; ở đây lag chỉ xét tại các lần commit thấy được ({@link #commits}):
 * <ul>
 *   <li>{@link #slowBy}: offset vẫn tiến nhưng lag ở các lần commit không giảm lần nào (Burrow: consumer chậm);</li>
 *   <li>{@link #stalledMs}: còn lag mà offset đứng yên bao lâu (Burrow: stopped/stalled);</li>
 *   <li>{@link #eta}: thời gian đuổi kịp = lag / tốc độ lag giảm (hồi quy qua các lần commit);</li>
 *   <li>{@link #timeLag}: chậm bao lâu - message đầu tiên chưa đọc được ghi lúc nào, nội suy từ offset cuối của
 *   partition qua các mẫu (kafka-lag-exporter).</li>
 * </ul>
 */
final class GroupTrend {

    /** Số lần commit gần nhất được xét (Burrow mặc định 10). */
    static final int COMMITS = 10;
    /** Cần ít nhất ngần này lần commit mới kết luận lag tăng. */
    static final int SLOW_MIN_COMMITS = 5;
    /** Còn lag mà offset đứng yên quá ngần này là kẹt (6 nhịp commit mặc định). */
    static final long STALL_MS = 30_000;
    /** Chậm không quá 2 nhịp commit mặc định là đang đọc kịp. */
    static final long KEEPING_UP_MS = 10_000;
    /** ETA dài hơn thế coi như không giảm đáng kể (dao động quanh 1 mức). */
    private static final double MAX_ETA_S = 24 * 3600;

    record Point(long at, long lag) {
    }

    /** {@code atLeast}: lịch sử chưa đủ dài để thấy lúc message đó được ghi - thực tế còn chậm hơn. */
    record TimeLag(long ms, boolean atLeast) {
    }

    private final List<KafkaStats.Sample> h;
    private final List<Map<String, KafkaStats.Group>> groups = new ArrayList<>();
    private List<Map<String, Long>> ends;

    GroupTrend(List<KafkaStats.Sample> history) {
        this.h = history;
        for (KafkaStats.Sample s : history) {
            Map<String, KafkaStats.Group> m = new HashMap<>();
            s.groups().forEach(g -> m.put(g.id(), g));
            groups.add(m);
        }
    }

    private KafkaStats.Group at(int i, String id) {
        return groups.get(i).get(id);
    }

    private KafkaStats.Group current(String id) {
        return h.isEmpty() ? null : at(h.size() - 1, id);
    }

    /** Tối đa {@link #COMMITS} lần commit gần nhất: mẫu đầu tiên thấy tổng offset commit đổi, kèm lag lúc đó. */
    List<Point> commits(String id) {
        List<Point> out = new ArrayList<>();
        for (int i = 1; i < h.size(); i++) {
            KafkaStats.Group before = at(i - 1, id);
            KafkaStats.Group g = at(i, id);
            if (before != null && g != null && g.committedSum() != before.committedSum()) {
                out.add(new Point(h.get(i).at(), g.lag()));
            }
        }
        return out.size() > COMMITS ? out.subList(out.size() - COMMITS, out.size()) : out;
    }

    /**
     * Lag tăng thêm bao nhiêu qua các lần commit đang xét, nếu group đang đọc mà không theo kịp: đủ
     * {@link #SLOW_MIN_COMMITS} lần commit, lag ở mỗi lần không thấp hơn lần trước, và từ lần đầu tới giờ lag chưa lúc nào
     * về 0. Không thì null.
     */
    Long slowBy(String id) {
        List<Point> c = commits(id);
        if (c.size() < SLOW_MIN_COMMITS) {
            return null;
        }
        for (int i = 1; i < c.size(); i++) {
            if (c.get(i).lag() < c.get(i - 1).lag()) {
                return null;
            }
        }
        for (int i = 0; i < h.size(); i++) {
            KafkaStats.Group g = at(i, id);
            if (h.get(i).at() >= c.get(0).at() && g != null && g.lag() == 0) {
                return null;
            }
        }
        long grow = c.get(c.size() - 1).lag() - c.get(0).lag();
        return grow > 0 ? grow : null;
    }

    /** Khoảng thời gian (ms) của các lần commit đang xét. */
    long commitSpanMs(String id) {
        List<Point> c = commits(id);
        return c.size() < 2 ? 0 : c.get(c.size() - 1).at() - c.get(0).at();
    }

    /** Offset commit đứng yên trong khi còn lag đã bao lâu (ms) - tính trong lịch sử đang giữ; 0 nếu đang tiến hoặc hết lag. */
    long stalledMs(String id) {
        KafkaStats.Group g = current(id);
        if (g == null || g.lag() == 0) {
            return 0;
        }
        long now = h.get(h.size() - 1).at();
        long since = now;
        for (int i = h.size() - 1; i >= 0; i--) {
            KafkaStats.Group p = at(i, id);
            if (p == null || p.committedSum() != g.committedSum() || p.lag() == 0) {
                break;
            }
            since = h.get(i).at();
        }
        return now - since;
    }

    /**
     * Số giây tới khi hết lag: 0 nếu đã hết lag; {@link Double#POSITIVE_INFINITY} nếu không có consumer, kẹt hoặc lag
     * đang tăng ({@link #slowBy}); {@link Double#NaN} nếu đọc kịp tốc độ ghi (chậm không quá {@link #KEEPING_UP_MS} hoặc
     * lag dao động quanh 1 mức); null nếu chưa
     * đủ 3 lần commit để ước lượng.
     */
    Double eta(String id) {
        KafkaStats.Group g = current(id);
        if (g == null) {
            return null;
        }
        if (g.lag() == 0) {
            return 0.0;
        }
        if (g.members() == 0 || stalledMs(id) >= STALL_MS || slowBy(id) != null) {
            return Double.POSITIVE_INFINITY;
        }
        // chỉ chậm vài nhịp commit: lag chỉ là răng cưa giữa 2 lần commit, ước lượng xu hướng từ đó toàn nhiễu
        TimeLag behind = timeLag(id);
        if (behind != null && !behind.atLeast() && behind.ms() <= KEEPING_UP_MS) {
            return Double.NaN;
        }
        List<Point> c = commits(id);
        if (c.size() < 3) {
            return null;
        }
        // hồi quy tuyến tính lag theo thời gian: đỡ nhiễu hơn lấy 2 đầu
        double mt = c.stream().mapToDouble(Point::at).average().orElse(0);
        double ml = c.stream().mapToDouble(Point::lag).average().orElse(0);
        double num = 0;
        double den = 0;
        for (Point p : c) {
            num += (p.at() - mt) * (p.lag() - ml);
            den += (p.at() - mt) * (p.at() - mt);
        }
        double perSec = den == 0 ? 0 : -num / den * 1000;
        double eta = perSec > 0 ? g.lag() / perSec : Double.NaN;
        return eta <= MAX_ETA_S ? eta : Double.NaN;
    }

    /**
     * Group chậm bao lâu: với mỗi partition còn lag, tìm lúc offset cuối của partition vượt qua offset đã commit (message
     * đầu tiên chưa đọc được ghi vào) bằng nội suy giữa 2 mẫu kề nhau; lấy partition chậm nhất. Partition chưa commit
     * tính từ chỗ group sẽ bắt đầu đọc (offset cuối - lag), như lag.
     */
    TimeLag timeLag(String id) {
        KafkaStats.Group g = current(id);
        if (g == null) {
            return null;
        }
        if (g.lag() == 0) {
            return new TimeLag(0, false);
        }
        if (ends == null) {
            ends = new ArrayList<>();
            for (KafkaStats.Sample s : h) {
                Map<String, Long> m = new HashMap<>();
                s.topics().forEach(t -> t.parts().forEach(p -> m.put(t.name() + "#" + p.partition(), p.end())));
                ends.add(m);
            }
        }
        long now = h.get(h.size() - 1).at();
        TimeLag worst = null;
        for (KafkaStats.GroupPart p : g.parts()) {
            if (p.lag() == 0) {
                continue;
            }
            long c = p.end() - p.lag();
            String key = p.topic() + "#" + p.partition();
            long newerAt = now;
            long newerEnd = p.end();
            Long produced = null;
            for (int i = h.size() - 2; i >= 0; i--) {
                Long e = ends.get(i).get(key);
                if (e == null || e < 0) {
                    break;
                }
                long t = h.get(i).at();
                if (e <= c) {
                    produced = t + (newerEnd == e ? 0 : (c - e) * (newerAt - t) / (newerEnd - e));
                    break;
                }
                newerAt = t;
                newerEnd = e;
            }
            TimeLag lag = produced != null ? new TimeLag(now - produced, false) : new TimeLag(now - newerAt, true);
            if (worst == null || lag.ms() > worst.ms() || lag.ms() == worst.ms() && lag.atLeast()) {
                worst = lag;
            }
        }
        return worst;
    }
}
