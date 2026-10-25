package com.sqlctx.kafka;

import com.sqlctx.shell.Dashboard;
import com.sqlctx.shell.Screen;
import com.sqlctx.shell.Screen.Column;
import com.sqlctx.util.I18n;

import org.apache.kafka.clients.admin.Admin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * \stats toàn màn hình của chế độ Kafka: ô tổng quan (broker, partition, quorum, thanh % đĩa, sparkline msg/s ghi và
 * đọc của cả cluster), bảng Topic và Group (cạnh nhau nếu đủ rộng), ô cảnh báo. Enter: partition của topic / lag từng
 * partition của group. Tốc độ tính từ chênh lệch offset giữa các mẫu liên tiếp (giữ {@link #HISTORY} mẫu).
 */
final class KafkaDashboard implements Dashboard.View {

    static final int HISTORY = 120;
    private static final int TOPICS = 0;
    private static final int GROUPS = 1;

    private final Supplier<Admin> admin;
    private final String context;
    private final Deque<KafkaStats.Sample> history = new ArrayDeque<>();

    KafkaDashboard(Supplier<Admin> admin, String context) {
        this.admin = admin;
        this.context = context;
    }

    @Override
    public void sample(Dashboard.State state) throws Exception {
        KafkaStats.Sample s = KafkaStats.sample(admin.get());
        synchronized (history) {
            history.addLast(s);
            while (history.size() > HISTORY) {
                history.removeFirst();
            }
        }
    }

    /** Thêm mẫu từ ngoài (test). */
    void add(KafkaStats.Sample s) {
        synchronized (history) {
            history.addLast(s);
        }
    }

    @Override
    public int panels() {
        return 2;
    }

    @Override
    public int rows(int panel) {
        KafkaStats.Sample cur = current();
        if (cur == null) {
            return 0;
        }
        return panel == TOPICS ? topics(cur, previous()).size() : cur.groups().size();
    }

    private KafkaStats.Sample current() {
        synchronized (history) {
            return history.peekLast();
        }
    }

    private KafkaStats.Sample previous() {
        synchronized (history) {
            if (history.size() < 2) {
                return null;
            }
            var it = history.descendingIterator();
            it.next();
            return it.next();
        }
    }

    private List<KafkaStats.Sample> snapshot() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    // ------------------------------------------------------------------ chuỗi tốc độ theo thời gian

    private static double dt(KafkaStats.Sample a, KafkaStats.Sample b) {
        return Math.max(0.001, (b.at() - a.at()) / 1000.0);
    }

    /** msg/s ghi vào mọi topic (không tính topic nội bộ) giữa các mẫu liên tiếp. */
    static List<Double> produceSeries(List<KafkaStats.Sample> h) {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < h.size(); i++) {
            Map<String, Long> before = new HashMap<>();
            h.get(i - 1).topics().forEach(t -> before.put(t.name(), t.endSum()));
            double sum = 0;
            for (KafkaStats.Topic t : h.get(i).topics()) {
                if (!t.internal() && before.containsKey(t.name())) {
                    sum += Math.max(0, t.endSum() - before.get(t.name()));
                }
            }
            out.add(sum / dt(h.get(i - 1), h.get(i)));
        }
        return out;
    }

    /** msg/s mọi group đã đọc (tổng offset commit tăng thêm). */
    static List<Double> consumeSeries(List<KafkaStats.Sample> h) {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < h.size(); i++) {
            Map<String, Long> before = new HashMap<>();
            h.get(i - 1).groups().forEach(g -> before.put(g.id(), g.committedSum()));
            double sum = 0;
            for (KafkaStats.Group g : h.get(i).groups()) {
                if (before.containsKey(g.id())) {
                    sum += Math.max(0, g.committedSum() - before.get(g.id()));
                }
            }
            out.add(sum / dt(h.get(i - 1), h.get(i)));
        }
        return out;
    }

    static List<Double> topicSeries(List<KafkaStats.Sample> h, String topic) {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < h.size(); i++) {
            Long before = endOf(h.get(i - 1), topic);
            Long now = endOf(h.get(i), topic);
            out.add(before == null || now == null ? 0 : Math.max(0, now - before) / dt(h.get(i - 1), h.get(i)));
        }
        return out;
    }

    private static Long endOf(KafkaStats.Sample s, String topic) {
        return s.topics().stream().filter(t -> t.name().equals(topic)).map(KafkaStats.Topic::endSum).findFirst().orElse(null);
    }

    private static double last(List<Double> series) {
        return series.isEmpty() ? 0 : series.get(series.size() - 1);
    }

    /** Topic (không nội bộ) theo tốc độ ghi giảm dần, rồi theo số message. */
    private static List<KafkaStats.Topic> topics(KafkaStats.Sample cur, KafkaStats.Sample prev) {
        Map<String, Long> before = new HashMap<>();
        if (prev != null) {
            prev.topics().forEach(t -> before.put(t.name(), t.endSum()));
        }
        List<KafkaStats.Topic> out = new ArrayList<>(cur.topics().stream().filter(t -> !t.internal()).toList());
        out.sort(Comparator.comparingLong((KafkaStats.Topic t) -> -(t.endSum() - before.getOrDefault(t.name(), t.endSum())))
                .thenComparingLong(t -> -t.messages()).thenComparing(KafkaStats.Topic::name));
        return out;
    }

    private static List<KafkaStats.Group> groups(KafkaStats.Sample cur) {
        List<KafkaStats.Group> out = new ArrayList<>(cur.groups());
        out.sort(Comparator.comparingLong((KafkaStats.Group g) -> -g.lag()).thenComparing(KafkaStats.Group::id));
        return out;
    }

    // ------------------------------------------------------------------ vẽ

    @Override
    public List<String> render(int width, int height, Dashboard.State state) {
        List<KafkaStats.Sample> h = snapshot();
        List<String> out = new ArrayList<>();
        String right = Dashboard.status(state, I18n.t("stats.every", state.intervalMs / 1000), I18n.t("stats.paused"));
        KafkaStats.Sample cur = h.isEmpty() ? null : h.get(h.size() - 1);
        KafkaStats.Sample prev = h.size() < 2 ? null : h.get(h.size() - 2);
        String left = context;
        if (cur != null) {
            String controller = cur.brokers().stream().filter(KafkaStats.Broker::controller).map(b -> String.valueOf(b.id()))
                    .findFirst().orElse(cur.quorum() != null ? String.valueOf(cur.quorum().leader()) : Screen.RED + "?" + Screen.RESET);
            left += Screen.DIM + " · " + Screen.RESET + I18n.t("stats.kafka.brokers", cur.brokers().size()) + " " + Screen.GREEN + "●"
                    + Screen.RESET + Screen.DIM + " · controller " + Screen.RESET + controller
                    + (cur.quorum() == null ? "" : Screen.DIM + " · quorum " + Screen.RESET + Screen.GREEN + "ok" + Screen.RESET
                    + Screen.DIM + " (epoch " + cur.quorum().epoch() + ", lag " + cur.quorum().maxLag() + ")" + Screen.RESET)
                    + Screen.DIM + " · " + cur.clusterId() + Screen.RESET;
        }
        out.add(Dashboard.titleBar("KAFKA", "214", left, right, width));
        if (cur == null) {
            out.add(Screen.DIM + " " + I18n.t("stats.loading") + Screen.RESET);
            return out;
        }

        // hàng ô số liệu
        List<Double> produce = produceSeries(h);
        List<Double> consume = consumeSeries(h);
        long lag = cur.groups().stream().mapToLong(KafkaStats.Group::lag).sum();
        Long lagBefore = prev == null ? null : prev.groups().stream().mapToLong(KafkaStats.Group::lag).sum();
        int partitions = cur.topics().stream().mapToInt(KafkaStats.Topic::partitions).sum();
        int under = cur.topics().stream().mapToInt(KafkaStats.Topic::underReplicated).sum();
        long offline = cur.topics().stream().mapToLong(t -> t.offline().size()).sum();
        long internal = cur.topics().stream().filter(KafkaStats.Topic::internal).count();
        KafkaStats.Broker fullest = cur.brokers().stream().max(Comparator.comparingDouble(KafkaDashboard::diskUsed)).orElse(null);
        double disk = fullest == null ? 0 : diskUsed(fullest);
        List<Screen.Tile> tiles = new ArrayList<>(List.of(
                new Screen.Tile(I18n.t("stats.kafka.write") + " msg/s", Screen.GREEN + rate(produce),
                        I18n.t("stats.peak", Screen.compact(peak(produce)))),
                new Screen.Tile(I18n.t("stats.kafka.read") + " msg/s", "\u001b[38;5;75m" + rate(consume),
                        I18n.t("stats.peak", Screen.compact(peak(consume)))),
                new Screen.Tile(I18n.t("stats.kafka.totalLag"), (lag == 0 ? Screen.GREEN : lagBefore != null && lag > lagBefore
                        ? Screen.YELLOW : "\u001b[38;5;252m") + Screen.compact(lag),
                        lagBefore == null ? I18n.t("stats.kafka.groups", cur.groups().size()) : lag > lagBefore
                                ? Screen.YELLOW + "↑ +" + Screen.compact(lag - lagBefore) : lag < lagBefore
                                ? Screen.GREEN + "↓ " + Screen.compact(lagBefore - lag) : "= · " + I18n.t("stats.kafka.groups", cur.groups().size())),
                new Screen.Tile("Partition", (under + offline > 0 ? Screen.RED : "\u001b[38;5;252m") + partitions,
                        under + offline == 0 ? Screen.GREEN + "✓ " + I18n.t("stats.kafka.healthy")
                                : (offline > 0 ? Screen.RED + offline + " " + I18n.t("stats.kafka.offline") + Screen.DIM + " · " : "")
                                + (under > 0 ? Screen.YELLOW + under + " " + I18n.t("stats.kafka.under") : "")),
                new Screen.Tile(I18n.t("stats.kafka.disk"), Screen.level(disk, 0.8, 0.9) + Math.round(100 * disk) + "%",
                        fullest == null || fullest.totalBytes() <= 0 ? "-" : "b" + fullest.id() + " " + KafkaStats.diskBytes(fullest))));
        if (width < 90) {
            tiles.remove(4); // màn hẹp: bớt 1 ô cho mỗi ô đủ rộng
        }
        out.addAll(Screen.tiles(tiles, width));

        List<String> warnings = KafkaStats.warnings(prev, cur);
        int warnLines = Math.min(4, Math.max(1, warnings.size()));
        int fixed = out.size() + (warnLines + 2) + 1;
        boolean charts = height - fixed >= 6 + 8; // chừa ít nhất 8 dòng cho bảng
        if (charts) {
            int half = width / 2;
            List<String> w = Screen.box(I18n.t("stats.kafka.write") + " msg/s · " + Screen.compact(last(produce)),
                    Screen.area(produce, half - 4, 4, Screen.GREEN), half, 6, false);
            List<String> r = Screen.box(I18n.t("stats.kafka.read") + " msg/s · " + Screen.compact(last(consume)),
                    Screen.area(consume, width - half - 4, 4, "\u001b[38;5;75m"), width - half, 6, false);
            out.addAll(Screen.beside(w, half, r));
        }
        int panelHeight = Math.max(4, height - out.size() - (warnLines + 2) - 1);

        if (state.detail) {
            out.addAll(detail(cur, prev, state, width, panelHeight));
        } else {
            List<KafkaStats.Topic> topics = topics(cur, prev);
            List<List<String>> topicRows = new ArrayList<>();
            for (KafkaStats.Topic t : topics) {
                List<Double> series = topicSeries(h, t.name());
                topicRows.add(List.of(t.name(), String.valueOf(t.partitions()),
                        (t.replicationFactor() == 1 && cur.brokers().size() > 1 ? Screen.YELLOW : "") + t.replicationFactor(),
                        Screen.spark(series, 10), series.isEmpty() ? "…" : Screen.compact(last(series)), Screen.compact(t.messages())));
            }
            List<Column> topicCols = List.of(new Column("topic", 'l', 8), new Column("p", 'r', 2), new Column("RF", 'r', 2),
                    new Column(I18n.t("stats.trend"), 'l', 10), new Column("msg/s", 'r', 6), new Column("messages", 'r', 8));
            Map<String, Long> lagPrev = new HashMap<>();
            Map<String, Long> readPrev = new HashMap<>();
            if (prev != null) {
                prev.groups().forEach(g -> {
                    lagPrev.put(g.id(), g.lag());
                    readPrev.put(g.id(), g.committedSum());
                });
            }
            List<List<String>> groupRows = new ArrayList<>();
            for (KafkaStats.Group g : groups(cur)) {
                Long before = lagPrev.get(g.id());
                String trend = before == null ? "" : g.lag() > before ? Screen.YELLOW + "↑ +" + Screen.compact(g.lag() - before)
                        : g.lag() < before ? Screen.GREEN + "↓ " + Screen.compact(before - g.lag()) : Screen.DIM + "=";
                Long read = readPrev.get(g.id());
                String lagColor = g.lag() == 0 ? Screen.GREEN : g.members() == 0 || before != null && g.lag() > before ? Screen.YELLOW : "";
                groupRows.add(List.of(g.id(), stateColor(g.state()) + g.state(), String.valueOf(g.members()),
                        lagColor + Screen.compact(g.lag()), trend,
                        read == null ? "…" : Screen.compact(Math.max(0, g.committedSum() - read) / dt(prev, cur))));
            }
            List<Column> groupCols = List.of(new Column("group", 'l', 8), new Column("state", 'l', 5), new Column("mem", 'r', 3),
                    new Column("lag", 'r', 5), new Column("Δ", 'l', 6), new Column(I18n.t("stats.kafka.readShort"), 'r', 6));
            String topicTitle = "Topic " + Screen.DIM + topics.size() + " (+" + internal + " " + I18n.t("stats.kafka.internal") + ")";
            String groupTitle = "Consumer group " + Screen.DIM + groupRows.size();
            if (width >= 110) {
                int leftWidth = width / 2;
                List<String> a = Screen.box(topicTitle, Screen.table(topicCols, topicRows, leftWidth - 4, panelHeight - 2,
                        state.selected[TOPICS], state.panel == TOPICS), leftWidth, panelHeight, state.panel == TOPICS);
                List<String> b = Screen.box(groupTitle, Screen.table(groupCols, groupRows, width - leftWidth - 4, panelHeight - 2,
                        state.selected[GROUPS], state.panel == GROUPS), width - leftWidth, panelHeight, state.panel == GROUPS);
                out.addAll(Screen.beside(a, leftWidth, b));
            } else {
                int top = panelHeight / 2;
                out.addAll(Screen.box(topicTitle, Screen.table(topicCols, topicRows, width - 4, top - 2, state.selected[TOPICS],
                        state.panel == TOPICS), width, top, state.panel == TOPICS));
                out.addAll(Screen.box(groupTitle, Screen.table(groupCols, groupRows, width - 4, panelHeight - top - 2,
                        state.selected[GROUPS], state.panel == GROUPS), width, panelHeight - top, state.panel == GROUPS));
            }
        }

        out.addAll(Dashboard.warningsBox(warnings, warnLines, I18n.t("kafka.stats.ok"), width));
        out.add(Dashboard.footer(I18n.t(state.detail ? "stats.keysDetail" : "stats.keys"), width));
        return out.subList(0, Math.min(out.size(), height));
    }

    private static String stateColor(String state) {
        return switch (state) {
            case "Stable" -> Screen.GREEN;
            case "Empty" -> Screen.DIM;
            case "Dead" -> Screen.RED;
            case "PreparingRebalance", "CompletingRebalance", "Assigning", "Reconciling" -> Screen.YELLOW;
            default -> "";
        };
    }

    private static String rate(List<Double> series) {
        return series.isEmpty() ? "…" : Screen.compact(last(series));
    }

    private static double peak(List<Double> series) {
        return series.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    private static String count(long n) {
        return (n > 0 ? Screen.RED : Screen.GREEN) + n + Screen.RESET;
    }

    private static double diskUsed(KafkaStats.Broker b) {
        return b.totalBytes() <= 0 ? 0 : 1.0 - (double) b.usableBytes() / b.totalBytes();
    }

    private List<String> detail(KafkaStats.Sample cur, KafkaStats.Sample prev, Dashboard.State state, int width, int height) {
        if (state.panel == TOPICS) {
            List<KafkaStats.Topic> topics = topics(cur, prev);
            if (topics.isEmpty()) {
                return Screen.box("", List.of(), width, height, true);
            }
            KafkaStats.Topic t = topics.get(Math.min(state.selected[TOPICS], topics.size() - 1));
            List<List<String>> rows = new ArrayList<>();
            for (KafkaStats.Part p : t.parts()) {
                boolean underRep = p.isr().size() < p.replicas().size();
                rows.add(List.of(String.valueOf(p.partition()), p.leader() < 0 ? Screen.RED + "-" : String.valueOf(p.leader()),
                        join(p.replicas()), (underRep ? Screen.YELLOW : "") + join(p.isr()),
                        p.start() < 0 ? "-" : String.valueOf(p.start()), p.end() < 0 ? "-" : String.valueOf(p.end()),
                        p.end() < 0 ? "-" : String.valueOf(p.end() - Math.max(0, p.start()))));
            }
            return Screen.box("Topic " + t.name() + " · " + t.partitions() + " partition · RF " + t.replicationFactor(),
                    Screen.table(List.of(new Column("partition", 'r', 9), new Column("leader", 'r', 6), new Column("replicas", 'l', 8),
                            new Column("isr", 'l', 8), new Column(I18n.t("stats.kafka.start"), 'r', 6),
                            new Column(I18n.t("stats.kafka.end"), 'r', 6), new Column("messages", 'r', 8)), rows, width - 4, height - 2,
                            -1, false), width, height, true);
        }
        List<KafkaStats.Group> groups = groups(cur);
        if (groups.isEmpty()) {
            return Screen.box("", List.of(), width, height, true);
        }
        KafkaStats.Group g = groups.get(Math.min(state.selected[GROUPS], groups.size() - 1));
        List<List<String>> rows = new ArrayList<>();
        for (KafkaStats.GroupPart p : g.parts()) {
            rows.add(List.of(p.topic(), String.valueOf(p.partition()), p.committed() == null ? "-" : String.valueOf(p.committed()),
                    String.valueOf(p.end()), (p.lag() > 0 ? Screen.YELLOW : "") + p.lag(), p.consumer()));
        }
        return Screen.box("Group " + g.id() + " · " + g.state() + " · " + g.members() + " member · lag " + g.lag(),
                Screen.table(List.of(new Column("topic", 'l', 8), new Column("partition", 'r', 9), new Column(I18n.t("stats.kafka.committed"), 'r', 7),
                        new Column(I18n.t("stats.kafka.end"), 'r', 6), new Column("lag", 'r', 5), new Column("consumer", 'l', 8)),
                        rows, width - 4, height - 2, -1, false), width, height, true);
    }

    private static String join(List<Integer> ids) {
        return ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }
}
