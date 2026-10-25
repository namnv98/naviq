package com.sqlctx.kafka;

import com.sqlctx.cli.view.DataViewTable;
import com.sqlctx.shell.LiveView;
import com.sqlctx.util.I18n;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.QuorumInfo;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * \stats của chế độ Kafka: broker (đĩa, số partition), sức khoẻ partition (thiếu replica, mất leader), quorum,
 * topic ghi nhiều nhất và consumer group lag nhiều nhất, kèm cảnh báo. Tốc độ (msg/s ghi vào topic, msg/s group đọc)
 * không có trong Admin API (chỉ có qua JMX) nên tính từ chênh lệch offset giữa 2 lần lấy mẫu. Lấy mẫu
 * ({@link #sample}) tách khỏi dựng khung ({@link #frame}) để dựng khung test được không cần broker.
 */
final class KafkaStats {

    private KafkaStats() {
    }

    private static final String DIM = "\u001b[38;5;245m";
    private static final String YELLOW = "\u001b[33m";
    private static final String RED = "\u001b[31m";
    private static final String GREEN = "\u001b[32m";
    private static final String RESET = "\u001b[0m";
    private static final long TIMEOUT_S = 10;
    static final int TOP = 8;

    /** {@code totalBytes}/{@code usableBytes}: tổng của mọi log dir, -1 nếu broker không báo. */
    record Broker(int id, String host, int port, String rack, boolean controller, int leaders, int replicas,
                  long totalBytes, long usableBytes) {
    }

    /** {@code endSum}: tổng offset cuối của mọi partition; {@code messages}: số message đang giữ (cuối - đầu). */
    record Topic(String name, boolean internal, int partitions, int replicationFactor, long endSum, long messages,
                 int underReplicated, List<String> offline) {
    }

    /** {@code committedSum}: tổng offset đã commit - tăng bao nhiêu là group đã đọc bấy nhiêu message. */
    record Group(String id, String state, int members, long lag, long committedSum) {
    }

    record Quorum(int leader, long epoch, long maxLag) {
    }

    record Sample(long at, String clusterId, List<Broker> brokers, List<Topic> topics, List<Group> groups, Quorum quorum) {
    }

    // ================================================================== lấy mẫu

    static Sample sample(Admin admin) throws Exception {
        long at = System.currentTimeMillis();
        DescribeClusterResult cluster = admin.describeCluster();
        List<Node> nodes = new ArrayList<>(cluster.nodes().get(TIMEOUT_S, TimeUnit.SECONDS));
        nodes.sort(Comparator.comparingInt(Node::id));
        Node controller = cluster.controller().get(TIMEOUT_S, TimeUnit.SECONDS);
        String clusterId = cluster.clusterId().get(TIMEOUT_S, TimeUnit.SECONDS);

        Set<String> names = admin.listTopics(new ListTopicsOptions().listInternal(true)).names().get(TIMEOUT_S, TimeUnit.SECONDS);
        Map<String, TopicDescription> described = names.isEmpty() ? Map.of()
                : new TreeMap<>(admin.describeTopics(names).allTopicNames().get(TIMEOUT_S, TimeUnit.SECONDS));
        Map<Integer, int[]> perBroker = new HashMap<>(); // id -> [leaders, replicas]
        List<TopicPartition> online = new ArrayList<>();
        for (TopicDescription d : described.values()) {
            for (TopicPartitionInfo p : d.partitions()) {
                if (p.leader() != null && !p.leader().isEmpty()) {
                    perBroker.computeIfAbsent(p.leader().id(), k -> new int[2])[0]++;
                    online.add(new TopicPartition(d.name(), p.partition()));
                }
                for (Node r : p.replicas()) {
                    perBroker.computeIfAbsent(r.id(), k -> new int[2])[1]++;
                }
            }
        }
        Map<TopicPartition, Long> latest = offsets(admin, online, OffsetSpec.latest());
        Map<TopicPartition, Long> earliest = offsets(admin, online, OffsetSpec.earliest());

        List<Topic> topics = new ArrayList<>();
        for (TopicDescription d : described.values()) {
            long end = 0;
            long held = 0;
            int under = 0;
            int rf = Integer.MAX_VALUE;
            List<String> offline = new ArrayList<>();
            for (TopicPartitionInfo p : d.partitions()) {
                TopicPartition tp = new TopicPartition(d.name(), p.partition());
                rf = Math.min(rf, p.replicas().size());
                if (p.leader() == null || p.leader().isEmpty()) {
                    offline.add(tp.toString());
                }
                if (p.isr().size() < p.replicas().size()) {
                    under++;
                }
                Long e = latest.get(tp);
                if (e != null) {
                    end += e;
                    held += e - earliest.getOrDefault(tp, e);
                }
            }
            topics.add(new Topic(d.name(), d.isInternal(), d.partitions().size(), rf == Integer.MAX_VALUE ? 0 : rf, end, held,
                    under, offline));
        }

        List<Broker> brokers = new ArrayList<>();
        Map<Integer, long[]> disks = new HashMap<>();
        try {
            var all = admin.describeLogDirs(nodes.stream().map(Node::id).toList()).allDescriptions().get(TIMEOUT_S, TimeUnit.SECONDS);
            all.forEach((id, dirs) -> {
                long total = 0;
                long usable = 0;
                boolean known = false;
                for (LogDirDescription dir : dirs.values()) {
                    if (dir.totalBytes().isPresent() && dir.usableBytes().isPresent()) {
                        total += dir.totalBytes().getAsLong();
                        usable += dir.usableBytes().getAsLong();
                        known = true;
                    }
                }
                disks.put(id, known ? new long[]{total, usable} : new long[]{-1, -1});
            });
        } catch (Exception e) {
            // ACL không cho DescribeLogDirs: thiếu cột đĩa, phần còn lại vẫn hiện
        }
        for (Node n : nodes) {
            int[] counts = perBroker.getOrDefault(n.id(), new int[2]);
            long[] disk = disks.getOrDefault(n.id(), new long[]{-1, -1});
            brokers.add(new Broker(n.id(), n.host(), n.port(), n.rack(), controller != null && controller.id() == n.id(),
                    counts[0], counts[1], disk[0], disk[1]));
        }

        Map<String, Integer> partitionsOf = new HashMap<>();
        described.forEach((t, d) -> partitionsOf.put(t, d.partitions().size()));
        List<Group> groups = groups(admin, latest, earliest, partitionsOf);
        Quorum quorum = null;
        try {
            QuorumInfo q = admin.describeMetadataQuorum().quorumInfo().get(TIMEOUT_S, TimeUnit.SECONDS);
            long leaderEnd = q.voters().stream().filter(v -> v.replicaId() == q.leaderId())
                    .mapToLong(QuorumInfo.ReplicaState::logEndOffset).findFirst().orElse(0);
            long maxLag = q.voters().stream().mapToLong(v -> leaderEnd - v.logEndOffset()).max().orElse(0);
            quorum = new Quorum(q.leaderId(), q.leaderEpoch(), maxLag);
        } catch (Exception e) {
            // cluster ZooKeeper không có quorum KRaft
        }
        return new Sample(at, clusterId, brokers, topics, groups, quorum);
    }

    /** Partition nào lỗi (đang đổi leader...) thì bỏ qua, không làm hỏng cả lần lấy mẫu. */
    private static Map<TopicPartition, Long> offsets(Admin admin, List<TopicPartition> tps, OffsetSpec spec) {
        Map<TopicPartition, Long> out = new HashMap<>();
        if (tps.isEmpty()) {
            return out;
        }
        Map<TopicPartition, OffsetSpec> req = new HashMap<>();
        tps.forEach(tp -> req.put(tp, spec));
        ListOffsetsResult r = admin.listOffsets(req);
        for (TopicPartition tp : tps) {
            try {
                out.put(tp, r.partitionResult(tp).get(TIMEOUT_S, TimeUnit.SECONDS).offset());
            } catch (Exception e) {
                // bỏ partition này
            }
        }
        return out;
    }

    /**
     * Lag như kafka-consumer-groups --describe của sqlctx: mọi partition của topic group đã commit (và partition đang
     * được gán), partition chưa commit thì tính từ offset đầu.
     */
    private static List<Group> groups(Admin admin, Map<TopicPartition, Long> latest, Map<TopicPartition, Long> earliest,
                                      Map<String, Integer> partitionsOf) throws Exception {
        List<String> ids = admin.listConsumerGroups().all().get(TIMEOUT_S, TimeUnit.SECONDS).stream()
                .map(ConsumerGroupListing::groupId).sorted().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, ConsumerGroupDescription> desc = admin.describeConsumerGroups(ids).all().get(TIMEOUT_S, TimeUnit.SECONDS);
        Map<String, ListConsumerGroupOffsetsSpec> specs = new HashMap<>();
        ids.forEach(id -> specs.put(id, new ListConsumerGroupOffsetsSpec()));
        Map<String, Map<TopicPartition, OffsetAndMetadata>> committed = admin.listConsumerGroupOffsets(specs).all()
                .get(TIMEOUT_S, TimeUnit.SECONDS);
        List<Group> out = new ArrayList<>();
        for (String id : ids) {
            ConsumerGroupDescription d = desc.get(id);
            Map<TopicPartition, OffsetAndMetadata> offsets = committed.getOrDefault(id, Map.of());
            Set<TopicPartition> parts = new java.util.HashSet<>();
            offsets.forEach((tp, o) -> {
                if (o != null) {
                    for (int i = 0; i < partitionsOf.getOrDefault(tp.topic(), 0); i++) {
                        parts.add(new TopicPartition(tp.topic(), i));
                    }
                }
            });
            if (d != null) {
                for (MemberDescription m : d.members()) {
                    parts.addAll(m.assignment().topicPartitions());
                }
            }
            long lag = 0;
            long sum = 0;
            for (TopicPartition tp : parts) {
                Long end = latest.get(tp);
                if (end == null) {
                    continue;
                }
                OffsetAndMetadata o = offsets.get(tp);
                long pos = o != null ? o.offset() : earliest.getOrDefault(tp, end);
                lag += Math.max(0, end - pos);
                sum += o != null ? o.offset() : 0;
            }
            out.add(new Group(id, d == null ? "?" : d.state().toString(), d == null ? 0 : d.members().size(), lag, sum));
        }
        return out;
    }

    // ================================================================== dựng khung

    /** {@code prev}: lần lấy mẫu trước (null ở lần đầu: chưa có tốc độ). */
    static List<String> frame(String title, Sample prev, Sample cur, int width) {
        double dt = prev == null ? 0 : (cur.at() - prev.at()) / 1000.0;
        Map<String, Topic> prevTopics = prev == null ? Map.of()
                : prev.topics().stream().collect(Collectors.toMap(Topic::name, t -> t));
        Map<String, Group> prevGroups = prev == null ? Map.of()
                : prev.groups().stream().collect(Collectors.toMap(Group::id, g -> g));
        List<String> out = new ArrayList<>();
        out.add(title);

        List<List<String>> brokerRows = new ArrayList<>();
        for (Broker b : cur.brokers()) {
            brokerRows.add(List.of(String.valueOf(b.id()), b.host() + ":" + b.port(), b.rack() == null ? "" : b.rack(),
                    b.controller() ? "controller" : "", String.valueOf(b.leaders()), String.valueOf(b.replicas()), disk(b)));
        }
        out.addAll(LiveView.lines(DataViewTable.renderFit(List.of("broker", "host", "rack", "", "leaders", "replicas",
                I18n.t("kafka.stats.disk")), brokerRows, width)));

        int partitions = cur.topics().stream().mapToInt(Topic::partitions).sum();
        int under = cur.topics().stream().mapToInt(Topic::underReplicated).sum();
        List<String> offline = cur.topics().stream().flatMap(t -> t.offline().stream()).toList();
        long internal = cur.topics().stream().filter(Topic::internal).count();
        String quorum = cur.quorum() == null ? "" : " · " + I18n.t("kafka.stats.quorum", cur.quorum().leader(), cur.quorum().epoch(),
                cur.quorum().maxLag());
        out.add(I18n.t("kafka.stats.summary", cur.topics().size() - internal, internal, partitions, under, offline.size()) + quorum);

        List<Topic> topics = new ArrayList<>(cur.topics().stream().filter(t -> !t.internal()).toList());
        topics.sort(Comparator.comparingDouble((Topic t) -> -rate(t.endSum(), prevTopics.containsKey(t.name())
                ? prevTopics.get(t.name()).endSum() : null, dt)).thenComparingLong(t -> -t.messages()));
        List<List<String>> topicRows = new ArrayList<>();
        for (Topic t : topics.subList(0, Math.min(TOP, topics.size()))) {
            Topic p = prevTopics.get(t.name());
            topicRows.add(List.of(t.name(), String.valueOf(t.partitions()), String.valueOf(t.replicationFactor()),
                    rateText(t.endSum(), p == null ? null : p.endSum(), dt), String.valueOf(t.messages())));
        }
        if (!topicRows.isEmpty()) {
            out.addAll(LiveView.lines(DataViewTable.renderFit(List.of("topic", "partitions", "RF", I18n.t("kafka.stats.produced"),
                    "messages"), topicRows, width)));
        }

        List<Group> groups = new ArrayList<>(cur.groups());
        groups.sort(Comparator.comparingLong((Group g) -> -g.lag()).thenComparing(Group::id));
        List<List<String>> groupRows = new ArrayList<>();
        for (Group g : groups.subList(0, Math.min(TOP, groups.size()))) {
            Group p = prevGroups.get(g.id());
            String trend = p == null ? "" : g.lag() > p.lag() ? "↑ +" + (g.lag() - p.lag()) : g.lag() < p.lag() ? "↓ " + (g.lag() - p.lag()) : "=";
            groupRows.add(List.of(g.id(), g.state(), String.valueOf(g.members()), String.valueOf(g.lag()), trend,
                    rateText(g.committedSum(), p == null ? null : p.committedSum(), dt)));
        }
        if (!groupRows.isEmpty()) {
            out.addAll(LiveView.lines(DataViewTable.renderFit(List.of("group", "state", "members", "lag", "Δ lag",
                    I18n.t("kafka.stats.consumed")), groupRows, width)));
        }
        if (cur.topics().size() - internal > TOP || cur.groups().size() > TOP) {
            out.add(DIM + I18n.t("kafka.stats.top", TOP) + RESET);
        }

        List<String> warnings = warnings(prev, cur);
        out.add(warnings.isEmpty() ? GREEN + "✓ " + I18n.t("kafka.stats.ok") + RESET : "");
        out.addAll(warnings);
        return out;
    }

    private static double rate(long now, Long before, double dt) {
        return before == null || dt <= 0 ? 0 : Math.max(0, now - before) / dt;
    }

    private static String rateText(long now, Long before, double dt) {
        if (before == null || dt <= 0) {
            return "…";
        }
        double r = rate(now, before, dt);
        return r == 0 ? "0" : r < 10 ? String.format("%.1f", r) : String.format("%.0f", r);
    }

    static String disk(Broker b) {
        if (b.totalBytes() <= 0) {
            return "-";
        }
        long used = b.totalBytes() - b.usableBytes();
        return human(used) + " / " + human(b.totalBytes()) + " (" + Math.round(100.0 * used / b.totalBytes()) + "%)";
    }

    private static String human(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return u == 0 ? bytes + " B" : String.format("%.1f %s", v, units[u]);
    }

    /** Đỏ (✖) = đang có sự cố; vàng (⚠) = nên xem lại. */
    static List<String> warnings(Sample prev, Sample cur) {
        List<String> red = new ArrayList<>();
        List<String> yellow = new ArrayList<>();
        if (cur.brokers().stream().noneMatch(Broker::controller) && cur.quorum() == null) {
            red.add(I18n.t("kafka.stats.noController"));
        }
        List<String> offline = cur.topics().stream().flatMap(t -> t.offline().stream()).toList();
        if (!offline.isEmpty()) {
            red.add(I18n.t("kafka.stats.offline", offline.size(), String.join(", ", offline.subList(0, Math.min(5, offline.size())))
                    + (offline.size() > 5 ? ", …" : "")));
        }
        List<String> under = cur.topics().stream().filter(t -> t.underReplicated() > 0).map(Topic::name).toList();
        if (!under.isEmpty()) {
            yellow.add(I18n.t("kafka.stats.underReplicated", String.join(", ", under.subList(0, Math.min(5, under.size())))));
        }
        for (Broker b : cur.brokers()) {
            if (b.totalBytes() > 0) {
                double used = 1.0 - (double) b.usableBytes() / b.totalBytes();
                if (used >= 0.9) {
                    red.add(I18n.t("kafka.stats.diskFull", b.id(), Math.round(100 * used)));
                } else if (used >= 0.8) {
                    yellow.add(I18n.t("kafka.stats.diskFull", b.id(), Math.round(100 * used)));
                }
            }
        }
        if (cur.brokers().size() > 1) {
            List<String> single = cur.topics().stream().filter(t -> !t.internal() && t.replicationFactor() == 1).map(Topic::name).toList();
            if (!single.isEmpty()) {
                yellow.add(I18n.t("kafka.stats.rf1", single.size(), String.join(", ", single.subList(0, Math.min(5, single.size())))));
            }
        }
        if (cur.quorum() != null && cur.quorum().maxLag() > 1000) {
            yellow.add(I18n.t("kafka.stats.quorumLag", cur.quorum().maxLag()));
        }
        Map<String, Group> before = prev == null ? Map.of() : prev.groups().stream().collect(Collectors.toMap(Group::id, g -> g));
        for (Group g : cur.groups()) {
            Group p = before.get(g.id());
            if (g.lag() > 0 && g.members() == 0) {
                yellow.add(I18n.t("kafka.stats.lagNoConsumer", g.id(), g.lag()));
            } else if (p != null && g.members() > 0 && g.lag() > p.lag()) {
                yellow.add(I18n.t("kafka.stats.lagGrowing", g.id(), g.lag() - p.lag(), g.lag()));
            }
        }
        List<String> out = new ArrayList<>();
        red.forEach(w -> out.add(RED + "✖ " + w + RESET));
        yellow.forEach(w -> out.add(YELLOW + "⚠ " + w + RESET));
        return out;
    }

    static String title(String context, Sample s, long intervalSeconds, boolean live) {
        String clock = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        return "\u001b[1;36mKafka\u001b[0m · " + context + " · cluster " + s.clusterId() + " · " + clock
                + (live ? DIM + " · " + I18n.t("common.statsEvery", intervalSeconds) + RESET : "");
    }
}
