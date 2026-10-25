package com.sqlctx.kafka;

import com.sqlctx.util.I18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DescribeConfigsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.QuorumInfo;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.ReplicaInfo;
import org.apache.kafka.clients.admin.ScramCredentialInfo;
import org.apache.kafka.clients.admin.ScramMechanism;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.UserScramCredentialAlteration;
import org.apache.kafka.clients.admin.UserScramCredentialDeletion;
import org.apache.kafka.clients.admin.UserScramCredentialUpsertion;
import org.apache.kafka.clients.admin.UserScramCredentialsDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AccessControlEntryFilter;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.quota.ClientQuotaAlteration;
import org.apache.kafka.common.quota.ClientQuotaEntity;
import org.apache.kafka.common.quota.ClientQuotaFilter;
import org.apache.kafka.common.quota.ClientQuotaFilterComponent;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourcePatternFilter;
import org.apache.kafka.common.resource.ResourceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Các tool quản trị (mọi thứ trừ console consumer/producer): kafka-topics, kafka-consumer-groups, kafka-configs,
 * kafka-acls, kafka-get-offsets, kafka-delete-records, kafka-log-dirs, kafka-reassign-partitions,
 * kafka-leader-election, kafka-transactions, kafka-cluster, kafka-metadata-quorum, schema-registry. Kết quả in thành bảng thay vì văn bản căn cột của tool gốc, cùng cột và cùng tên.
 */
final class KafkaCommands {

    /** Nơi in kết quả - KafkaCli cài bằng khung bảng/pager dùng chung với phía SQL. */
    interface Out {
        void table(List<String> headers, List<List<String>> rows);

        void lines(List<String> lines);

        void info(String line);
    }

    /** Lỗi do người dùng (thiếu/sai tuỳ chọn, không có topic...) - in thông điệp, không in stack trace. */
    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    private final KafkaSession session;
    private final Out out;

    KafkaCommands(KafkaSession session, Out out) {
        this.session = session;
        this.out = out;
    }

    private Admin admin() {
        return session.admin();
    }

    void run(KafkaArgs.Parsed p) throws Exception {
        switch (p.tool().name()) {
            case "kafka-topics" -> topics(p);
            case "kafka-consumer-groups" -> groups(p);
            case "kafka-configs" -> configs(p);
            case "kafka-acls" -> acls(p);
            case "kafka-get-offsets" -> getOffsets(p);
            case "kafka-delete-records" -> deleteRecords(p);
            case "kafka-log-dirs" -> logDirs(p);
            case "kafka-reassign-partitions" -> reassign(p);
            case "kafka-leader-election" -> leaderElection(p);
            case "kafka-transactions" -> transactions(p);
            case "kafka-cluster" -> out.info("Cluster ID: " + admin().describeCluster().clusterId().get());
            case "kafka-metadata-quorum" -> quorum(p);
            case "schema-registry" -> schemaRegistry(p);
            default -> throw new UsageException(I18n.t("kafka.err.unsupported", p.tool().name()));
        }
        if (p.mode() != null && p.tool().writes().contains(p.mode())) {
            session.invalidate(); // topic/group mới, đã xoá... - gợi ý lấy lại ngay
        }
    }

    // ================================================================== kafka-topics

    /** Topic khớp --topic (regex, như tool gốc) hoặc mọi topic. */
    private List<String> matchTopics(String spec, boolean includeInternal) throws Exception {
        Set<String> all = admin().listTopics(new ListTopicsOptions().listInternal(includeInternal)).names().get();
        if (spec == null) {
            return all.stream().sorted().toList();
        }
        if (all.contains(spec)) {
            return List.of(spec);
        }
        Pattern re;
        try {
            re = Pattern.compile(spec);
        } catch (RuntimeException e) {
            return List.of();
        }
        return all.stream().filter(t -> re.matcher(t).matches()).sorted().toList();
    }

    private void topics(KafkaArgs.Parsed p) throws Exception {
        String topic = p.get("--topic");
        switch (p.mode()) {
            case "--list" -> out.lines(matchTopics(topic, !p.has("--exclude-internal")));
            case "--describe" -> describeTopics(p, topic);
            case "--create" -> {
                NewTopic nt;
                if (p.has("--replica-assignment")) {
                    nt = new NewTopic(topic, parseAssignment(p.get("--replica-assignment"), 0));
                } else {
                    nt = new NewTopic(topic, Optional.ofNullable(p.get("--partitions")).map(Integer::parseInt),
                            Optional.ofNullable(p.get("--replication-factor")).map(Short::parseShort));
                }
                nt.configs(keyValues(p.all("--config")));
                try {
                    admin().createTopics(List.of(nt)).all().get();
                    out.info("Created topic " + topic + ".");
                } catch (java.util.concurrent.ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException && p.has("--if-not-exists"))) {
                        throw e;
                    }
                    out.info(I18n.t("kafka.topicExists", topic));
                }
            }
            case "--delete" -> {
                List<String> names = matchTopics(topic, true);
                if (names.isEmpty()) {
                    if (p.has("--if-exists")) {
                        return;
                    }
                    throw new UsageException("Topic '" + topic + "' does not exist as expected");
                }
                admin().deleteTopics(names).all().get();
                names.forEach(n -> out.info("Deleted topic " + n + "."));
            }
            case "--alter" -> {
                List<String> names = matchTopics(topic, false);
                if (names.isEmpty() && !p.has("--if-exists")) {
                    throw new UsageException("Topic '" + topic + "' does not exist as expected");
                }
                int count = Integer.parseInt(p.get("--partitions"));
                Map<String, NewPartitions> req = new LinkedHashMap<>();
                for (String n : names) {
                    req.put(n, p.has("--replica-assignment")
                            ? NewPartitions.increaseTo(count, new ArrayList<>(parseAssignment(p.get("--replica-assignment"), 0).values()))
                            : NewPartitions.increaseTo(count));
                }
                admin().createPartitions(req).all().get();
                names.forEach(n -> out.info(I18n.t("kafka.partitionsChanged", n, count)));
            }
            default -> throw new UsageException(I18n.t("kafka.err.pickMode", String.join(", ", p.tool().modes())));
        }
    }

    /** "1:2,2:3" -> {0=[1,2], 1=[2,3]}: mỗi nhóm cách nhau dấu phẩy là 1 partition, broker cách nhau dấu ':'. */
    private static Map<Integer, List<Integer>> parseAssignment(String s, int firstPartition) {
        Map<Integer, List<Integer>> m = new LinkedHashMap<>();
        int partition = firstPartition;
        for (String group : s.split(",")) {
            List<Integer> brokers = new ArrayList<>();
            for (String b : group.split(":")) {
                brokers.add(Integer.parseInt(b.strip()));
            }
            m.put(partition++, brokers);
        }
        return m;
    }

    private void describeTopics(KafkaArgs.Parsed p, String topic) throws Exception {
        List<String> names = matchTopics(topic, !p.has("--exclude-internal"));
        if (names.isEmpty()) {
            if (topic != null && !p.has("--if-exists")) {
                throw new UsageException("Topic '" + topic + "' does not exist as expected");
            }
            out.info(I18n.t("kafka.noTopics"));
            return;
        }
        Map<String, TopicDescription> desc = new TreeMap<>(admin().describeTopics(names).allTopicNames().get());
        List<ConfigResource> resources = names.stream().map(n -> new ConfigResource(ConfigResource.Type.TOPIC, n)).toList();
        Map<ConfigResource, Config> configs = admin().describeConfigs(resources).all().get();
        Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        desc.values().forEach(d -> d.partitions().forEach(pi -> {
            earliest.put(new TopicPartition(d.name(), pi.partition()), OffsetSpec.earliest());
            latest.put(new TopicPartition(d.name(), pi.partition()), OffsetSpec.latest());
        }));
        Map<TopicPartition, ListOffsetsResultInfo> begin = admin().listOffsets(earliest).all().get();
        Map<TopicPartition, ListOffsetsResultInfo> end = admin().listOffsets(latest).all().get();

        boolean filterUnder = p.has("--under-replicated-partitions");
        boolean filterUnderMin = p.has("--under-min-isr-partitions");
        boolean filterAtMin = p.has("--at-min-isr-partitions");
        boolean filterUnavailable = p.has("--unavailable-partitions");
        boolean partitionFilter = filterUnder || filterUnderMin || filterAtMin || filterUnavailable;

        List<List<String>> topicRows = new ArrayList<>();
        List<List<String>> partRows = new ArrayList<>();
        for (TopicDescription d : desc.values()) {
            Config cfg = configs.get(new ConfigResource(ConfigResource.Type.TOPIC, d.name()));
            String overrides = cfg.entries().stream()
                    .filter(e -> e.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG)
                    .map(e -> e.name() + "=" + (e.isSensitive() ? "*****" : e.value()))
                    .collect(Collectors.joining(","));
            if (p.has("--topics-with-overrides") && overrides.isEmpty()) {
                continue;
            }
            int minIsr = Optional.ofNullable(cfg.get("min.insync.replicas")).map(e -> Integer.parseInt(e.value())).orElse(1);
            int rf = d.partitions().isEmpty() ? 0 : d.partitions().get(0).replicas().size();
            if (!partitionFilter) {
                topicRows.add(List.of(d.name(), String.valueOf(d.topicId()), String.valueOf(d.partitions().size()),
                        String.valueOf(rf), overrides));
            }
            for (TopicPartitionInfo pi : d.partitions()) {
                boolean unavailable = pi.leader() == null || pi.leader().isEmpty();
                int isr = pi.isr().size();
                if (filterUnder && isr >= pi.replicas().size() || filterUnderMin && isr >= minIsr
                        || filterAtMin && isr != minIsr || filterUnavailable && !unavailable) {
                    continue;
                }
                TopicPartition tp = new TopicPartition(d.name(), pi.partition());
                long b = begin.get(tp).offset();
                long e = end.get(tp).offset();
                partRows.add(List.of(d.name(), String.valueOf(pi.partition()), unavailable ? "none" : String.valueOf(pi.leader().id()),
                        ids(pi.replicas()), ids(pi.isr()), String.valueOf(b), String.valueOf(e), String.valueOf(e - b)));
            }
        }
        if (!topicRows.isEmpty()) {
            out.table(List.of("Topic", "TopicId", "PartitionCount", "ReplicationFactor", "Configs"), topicRows);
        }
        if (partRows.isEmpty() && partitionFilter) {
            out.info(I18n.t("kafka.noPartitionsMatch"));
        } else if (!partRows.isEmpty()) {
            out.table(List.of("Topic", "Partition", "Leader", "Replicas", "Isr", I18n.t("kafka.col.offsetStart"), I18n.t("kafka.col.offsetEnd"), "Messages"),
                    partRows);
        }
    }

    private static String ids(List<Node> nodes) {
        return nodes.stream().map(n -> String.valueOf(n.id())).collect(Collectors.joining(","));
    }

    /** ["a=1", "b=2,3"] -> {a=1, b=2,3}. */
    private static Map<String, String> keyValues(List<String> pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new UsageException(I18n.t("kafka.err.configFormat", pair));
            }
            m.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
        }
        return m;
    }

    // ================================================================== kafka-consumer-groups

    private List<String> groupNames(KafkaArgs.Parsed p) throws Exception {
        if (p.has("--all-groups")) {
            return admin().listConsumerGroups().all().get().stream().map(ConsumerGroupListing::groupId).sorted().toList();
        }
        return p.all("--group");
    }

    private void groups(KafkaArgs.Parsed p) throws Exception {
        switch (p.mode()) {
            case "--list" -> {
                ListConsumerGroupsOptions opts = new ListConsumerGroupsOptions();
                String state = p.get("--state");
                if (state != null) {
                    opts.inStates(Set.of(ConsumerGroupState.parse(state)));
                }
                List<ConsumerGroupListing> all = new ArrayList<>(admin().listConsumerGroups(opts).all().get());
                all.sort(java.util.Comparator.comparing(ConsumerGroupListing::groupId));
                if (p.has("--state")) {
                    out.table(List.of("GROUP", "STATE"), all.stream().map(g -> List.of(g.groupId(),
                            g.state().map(Object::toString).orElse("-"))).toList());
                } else {
                    out.lines(all.stream().map(ConsumerGroupListing::groupId).toList());
                }
            }
            case "--describe" -> describeGroups(p);
            case "--delete" -> {
                List<String> names = groupNames(p);
                admin().deleteConsumerGroups(names).all().get();
                out.info("Deletion of requested consumer groups (" + names.stream().map(n -> "'" + n + "'")
                        .collect(Collectors.joining(", ")) + ") was successful.");
            }
            case "--delete-offsets" -> {
                String group = p.get("--group");
                Set<TopicPartition> tps = new LinkedHashSet<>();
                for (String spec : p.all("--topic")) {
                    tps.addAll(topicPartitions(spec));
                }
                admin().deleteConsumerGroupOffsets(group, tps).all().get();
                out.table(List.of("TOPIC", "PARTITION", "STATUS"), tps.stream()
                        .map(tp -> List.of(tp.topic(), String.valueOf(tp.partition()), "Successful")).toList());
            }
            case "--reset-offsets" -> resetOffsets(p);
            default -> throw new UsageException(I18n.t("kafka.err.pickMode", String.join(", ", p.tool().modes())));
        }
    }

    /** "orders" -> mọi partition; "orders:0,2" -> partition 0 và 2. */
    private List<TopicPartition> topicPartitions(String spec) throws Exception {
        int colon = spec.indexOf(':');
        String topic = colon < 0 ? spec : spec.substring(0, colon);
        List<TopicPartition> out = new ArrayList<>();
        if (colon >= 0) {
            for (String part : spec.substring(colon + 1).split(",")) {
                out.add(new TopicPartition(topic, Integer.parseInt(part.strip())));
            }
            return out;
        }
        TopicDescription d = admin().describeTopics(List.of(topic)).allTopicNames().get().get(topic);
        for (TopicPartitionInfo pi : d.partitions()) {
            out.add(new TopicPartition(topic, pi.partition()));
        }
        return out;
    }

    private Map<TopicPartition, Long> offsets(Collection<TopicPartition> tps, OffsetSpec spec) throws Exception {
        Map<TopicPartition, OffsetSpec> req = new HashMap<>();
        tps.forEach(tp -> req.put(tp, spec));
        Map<TopicPartition, Long> out = new HashMap<>();
        if (req.isEmpty()) {
            return out;
        }
        admin().listOffsets(req).all().get().forEach((tp, info) -> out.put(tp, info.offset()));
        return out;
    }

    private void describeGroups(KafkaArgs.Parsed p) throws Exception {
        List<String> names = groupNames(p);
        Map<String, ConsumerGroupDescription> desc = admin().describeConsumerGroups(names).all().get();
        if (p.has("--state")) {
            out.table(List.of("GROUP", "COORDINATOR (ID)", "ASSIGNMENT-STRATEGY", "STATE", "#MEMBERS"),
                    names.stream().map(n -> {
                        ConsumerGroupDescription d = desc.get(n);
                        Node c = d.coordinator();
                        return List.of(n, c == null ? "-" : c.host() + ":" + c.port() + " (" + c.id() + ")",
                                d.partitionAssignor() == null || d.partitionAssignor().isEmpty() ? "-" : d.partitionAssignor(),
                                String.valueOf(d.state()), String.valueOf(d.members().size()));
                    }).toList());
            return;
        }
        if (p.has("--members")) {
            boolean verbose = p.has("--verbose");
            List<String> headers = new ArrayList<>(List.of("GROUP", "CONSUMER-ID", "HOST", "CLIENT-ID", "#PARTITIONS"));
            if (verbose) {
                headers.add("ASSIGNMENT");
            }
            List<List<String>> rows = new ArrayList<>();
            for (String n : names) {
                for (MemberDescription m : desc.get(n).members()) {
                    List<String> row = new ArrayList<>(List.of(n, m.consumerId(), m.host(), m.clientId(),
                            String.valueOf(m.assignment().topicPartitions().size())));
                    if (verbose) {
                        row.add(m.assignment().topicPartitions().stream()
                                .collect(Collectors.groupingBy(TopicPartition::topic, TreeMap::new,
                                        Collectors.mapping(tp -> String.valueOf(tp.partition()), Collectors.joining(","))))
                                .entrySet().stream().map(e -> e.getKey() + "(" + e.getValue() + ")").collect(Collectors.joining(" ")));
                    }
                    rows.add(row);
                }
                if (desc.get(n).members().isEmpty()) {
                    out.info("Consumer group '" + n + "' has no active members.");
                }
            }
            if (!rows.isEmpty()) {
                out.table(headers, rows);
            }
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (String n : names) {
            ConsumerGroupDescription d = desc.get(n);
            Map<TopicPartition, OffsetAndMetadata> committed = admin().listConsumerGroupOffsets(n)
                    .partitionsToOffsetAndMetadata().get();
            Map<TopicPartition, MemberDescription> owner = new HashMap<>();
            for (MemberDescription m : d.members()) {
                m.assignment().topicPartitions().forEach(tp -> owner.put(tp, m));
            }
            Set<TopicPartition> tps = new java.util.TreeSet<>(java.util.Comparator.comparing(TopicPartition::topic)
                    .thenComparingInt(TopicPartition::partition));
            tps.addAll(committed.keySet());
            tps.addAll(owner.keySet());
            // Khác tool gốc: thêm cả partition group chưa từng commit của các topic group đang đọc - tool gốc bỏ qua
            // chúng nên tổng lag trông nhỏ hơn thật. LAG của chúng tính từ offset đầu (số message sẽ phải đọc).
            Set<String> topics = tps.stream().map(TopicPartition::topic).collect(Collectors.toCollection(java.util.TreeSet::new));
            if (!topics.isEmpty()) {
                for (TopicDescription td : admin().describeTopics(topics).allTopicNames().get().values()) {
                    td.partitions().forEach(pi -> tps.add(new TopicPartition(td.name(), pi.partition())));
                }
            }
            Map<TopicPartition, Long> end = offsets(tps, OffsetSpec.latest());
            List<TopicPartition> uncommitted = tps.stream().filter(tp -> !committed.containsKey(tp)).toList();
            Map<TopicPartition, Long> start = offsets(uncommitted, OffsetSpec.earliest());
            if (d.members().isEmpty()) {
                out.info("Consumer group '" + n + "' has no active members.");
            }
            for (TopicPartition tp : tps) {
                OffsetAndMetadata c = committed.get(tp);
                Long e = end.get(tp);
                Long from = c != null ? Long.valueOf(c.offset()) : start.get(tp);
                MemberDescription m = owner.get(tp);
                rows.add(List.of(n, tp.topic(), String.valueOf(tp.partition()), c == null ? "-" : String.valueOf(c.offset()),
                        e == null ? "-" : String.valueOf(e), from == null || e == null ? "-" : String.valueOf(e - from),
                        m == null ? "-" : m.consumerId(), m == null ? "-" : m.host(), m == null ? "-" : m.clientId()));
            }
        }
        out.table(List.of("GROUP", "TOPIC", "PARTITION", "CURRENT-OFFSET", "LOG-END-OFFSET", "LAG", "CONSUMER-ID",
                "HOST", "CLIENT-ID"), rows);
    }

    private void resetOffsets(KafkaArgs.Parsed p) throws Exception {
        boolean execute = p.has("--execute");
        List<List<String>> rows = new ArrayList<>();
        for (String group : groupNames(p)) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin().listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get();
            List<TopicPartition> tps = new ArrayList<>();
            if (p.has("--all-topics")) {
                tps.addAll(committed.keySet());
            } else {
                for (String spec : p.all("--topic")) {
                    tps.addAll(topicPartitions(spec));
                }
            }
            tps.sort(java.util.Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition));
            Map<TopicPartition, Long> earliest = offsets(tps, OffsetSpec.earliest());
            Map<TopicPartition, Long> latest = offsets(tps, OffsetSpec.latest());
            Map<TopicPartition, Long> target = new LinkedHashMap<>();
            for (TopicPartition tp : tps) {
                long lo = earliest.get(tp);
                long hi = latest.get(tp);
                Long current = committed.containsKey(tp) ? committed.get(tp).offset() : null;
                long t;
                if (p.has("--to-earliest")) {
                    t = lo;
                } else if (p.has("--to-latest")) {
                    t = hi;
                } else if (p.has("--to-current")) {
                    t = current != null ? current : lo;
                } else if (p.has("--to-offset")) {
                    t = Long.parseLong(p.get("--to-offset"));
                } else if (p.has("--shift-by")) {
                    t = (current != null ? current : lo) + Long.parseLong(p.get("--shift-by"));
                } else {
                    long ts = p.has("--to-datetime") ? parseDatetime(p.get("--to-datetime"))
                            : System.currentTimeMillis() - Duration.parse(p.get("--by-duration")).toMillis();
                    Long byTime = offsets(List.of(tp), OffsetSpec.forTimestamp(ts)).get(tp);
                    t = byTime == null || byTime < 0 ? hi : byTime;
                }
                target.put(tp, Math.max(lo, Math.min(hi, t))); // như tool gốc: ép vào khoảng [đầu, cuối]
            }
            if (execute) {
                ConsumerGroupDescription d = admin().describeConsumerGroups(List.of(group)).all().get().get(group);
                if (d.state() != ConsumerGroupState.EMPTY && d.state() != ConsumerGroupState.DEAD) {
                    throw new UsageException("Assignments can only be reset if the group '" + group
                            + "' is inactive, but the current state is " + d.state() + ".");
                }
                Map<TopicPartition, OffsetAndMetadata> req = new LinkedHashMap<>();
                target.forEach((tp, o) -> req.put(tp, new OffsetAndMetadata(o)));
                admin().alterConsumerGroupOffsets(group, req).all().get();
            }
            target.forEach((tp, o) -> rows.add(List.of(group, tp.topic(), String.valueOf(tp.partition()), String.valueOf(o))));
        }
        out.table(List.of("GROUP", "TOPIC", "PARTITION", "NEW-OFFSET"), rows);
        if (!execute) {
            out.info(I18n.t("kafka.dryRun"));
        }
    }

    /** "2026-10-25T10:00:00", "2026-10-25T10:00:00.000", kèm múi giờ hay không (không có = giờ máy). */
    static long parseDatetime(String s) {
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            try {
                return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (RuntimeException e2) {
                throw new UsageException(I18n.t("kafka.err.datetime", s));
            }
        }
    }

    // ================================================================== kafka-configs

    private record Entity(String type, String name) {
    }

    private Entity entity(KafkaArgs.Parsed p) {
        if (p.has("--topic")) {
            return new Entity("topics", p.get("--topic"));
        }
        if (p.has("--broker")) {
            return new Entity("brokers", p.get("--broker"));
        }
        if (p.has("--broker-defaults")) {
            return new Entity("brokers", "");
        }
        if (p.has("--broker-logger")) {
            return new Entity("broker-loggers", p.get("--broker-logger"));
        }
        if (p.has("--user")) {
            return new Entity("users", p.get("--user"));
        }
        if (p.has("--client")) {
            return new Entity("clients", p.get("--client"));
        }
        String type = p.get("--entity-type");
        if (type == null) {
            throw new UsageException(I18n.t("kafka.err.needEntity"));
        }
        String name = p.has("--entity-default") ? "" : p.get("--entity-name");
        return new Entity(type, name);
    }

    private void configs(KafkaArgs.Parsed p) throws Exception {
        Entity e = entity(p);
        switch (e.type()) {
            case "topics", "brokers", "broker-loggers" -> resourceConfigs(p, e);
            case "users", "clients" -> quotas(p, e);
            default -> throw new UsageException(I18n.t("kafka.err.entityType"));
        }
    }

    private void resourceConfigs(KafkaArgs.Parsed p, Entity e) throws Exception {
        ConfigResource.Type type = switch (e.type()) {
            case "topics" -> ConfigResource.Type.TOPIC;
            case "brokers" -> ConfigResource.Type.BROKER;
            default -> ConfigResource.Type.BROKER_LOGGER;
        };
        List<String> names = new ArrayList<>();
        if (e.name() != null) {
            names.add(e.name());
        } else if (type == ConfigResource.Type.TOPIC) {
            names.addAll(matchTopics(null, false));
        } else {
            names.addAll(session.brokers());
        }
        if (p.mode().equals("--describe")) {
            List<ConfigResource> res = names.stream().map(n -> new ConfigResource(type, n)).toList();
            Map<ConfigResource, Config> cfg = admin().describeConfigs(res, new DescribeConfigsOptions().includeSynonyms(false))
                    .all().get();
            boolean all = p.has("--all");
            List<List<String>> rows = new ArrayList<>();
            for (ConfigResource r : res) {
                for (ConfigEntry ce : cfg.get(r).entries().stream().sorted(java.util.Comparator.comparing(ConfigEntry::name)).toList()) {
                    boolean dynamic = ce.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG
                            || ce.source() == ConfigEntry.ConfigSource.DYNAMIC_BROKER_CONFIG
                            || ce.source() == ConfigEntry.ConfigSource.DYNAMIC_DEFAULT_BROKER_CONFIG
                            || ce.source() == ConfigEntry.ConfigSource.DYNAMIC_BROKER_LOGGER_CONFIG
                            || ce.source() == ConfigEntry.ConfigSource.DYNAMIC_CLIENT_METRICS_CONFIG;
                    if (!all && !dynamic && type != ConfigResource.Type.BROKER_LOGGER) {
                        continue;
                    }
                    rows.add(List.of(r.name().isEmpty() ? "<default>" : r.name(), ce.name(),
                            ce.isSensitive() ? "*****" : String.valueOf(ce.value()), String.valueOf(ce.source()),
                            ce.isReadOnly() ? "read-only" : ""));
                }
            }
            if (rows.isEmpty()) {
                out.info(I18n.t("kafka.noConfigOverrides"));
            } else {
                out.table(List.of(e.type().equals("topics") ? "topic" : "broker", "name", "value", "source", ""), rows);
            }
            return;
        }
        List<AlterConfigOp> ops = new ArrayList<>();
        for (Map.Entry<String, String> kv : configList(p.all("--add-config")).entrySet()) {
            ops.add(new AlterConfigOp(new ConfigEntry(kv.getKey(), kv.getValue()), AlterConfigOp.OpType.SET));
        }
        for (String name : names(p.all("--delete-config"))) {
            ops.add(new AlterConfigOp(new ConfigEntry(name, null), AlterConfigOp.OpType.DELETE));
        }
        Map<ConfigResource, Collection<AlterConfigOp>> req = new LinkedHashMap<>();
        for (String n : names) {
            req.put(new ConfigResource(type, n), ops);
        }
        admin().incrementalAlterConfigs(req).all().get();
        names.forEach(n -> out.info("Completed updating config for " + e.type() + " " + (n.isEmpty() ? "<default>" : n) + "."));
    }

    /** "a=1,b=[x,y],c=3" -> {a=1, b=x,y, c=3} - dấu phẩy trong [...] thuộc giá trị (như tool gốc). */
    static Map<String, String> configList(List<String> args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String arg : args) {
            int depth = 0;
            StringBuilder cur = new StringBuilder();
            List<String> parts = new ArrayList<>();
            for (char c : arg.toCharArray()) {
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                }
                if (c == ',' && depth == 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            parts.add(cur.toString());
            for (String part : parts) {
                if (part.isBlank()) {
                    continue;
                }
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    throw new UsageException(I18n.t("kafka.err.configFormat", part));
                }
                String v = part.substring(eq + 1).strip();
                if (v.startsWith("[") && v.endsWith("]")) {
                    v = v.substring(1, v.length() - 1);
                }
                m.put(part.substring(0, eq).strip(), v);
            }
        }
        return m;
    }

    private static List<String> names(List<String> args) {
        List<String> out = new ArrayList<>();
        for (String a : args) {
            for (String n : a.split(",")) {
                if (!n.isBlank()) {
                    out.add(n.strip());
                }
            }
        }
        return out;
    }

    /** users/clients: quota (producer_byte_rate...) và với users cả SCRAM credential. */
    private void quotas(KafkaArgs.Parsed p, Entity e) throws Exception {
        String entityKey = e.type().equals("users") ? ClientQuotaEntity.USER : ClientQuotaEntity.CLIENT_ID;
        if (p.mode().equals("--describe")) {
            ClientQuotaFilterComponent c = e.name() == null ? ClientQuotaFilterComponent.ofEntityType(entityKey)
                    : e.name().isEmpty() ? ClientQuotaFilterComponent.ofDefaultEntity(entityKey)
                    : ClientQuotaFilterComponent.ofEntity(entityKey, e.name());
            Map<ClientQuotaEntity, Map<String, Double>> q = admin().describeClientQuotas(ClientQuotaFilter.contains(List.of(c)))
                    .entities().get();
            List<List<String>> rows = new ArrayList<>();
            q.forEach((ent, values) -> values.forEach((k, v) -> rows.add(List.of(
                    String.valueOf(ent.entries().getOrDefault(entityKey, "<default>")), k, String.valueOf(v)))));
            if (e.type().equals("users") && e.name() != null && !e.name().isEmpty()) {
                try {
                    Map<String, UserScramCredentialsDescription> scram = admin()
                            .describeUserScramCredentials(List.of(e.name())).all().get();
                    for (UserScramCredentialsDescription d : scram.values()) {
                        for (ScramCredentialInfo info : d.credentialInfos()) {
                            rows.add(List.of(e.name(), info.mechanism().mechanismName(), "iterations=" + info.iterations()));
                        }
                    }
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // user chưa có SCRAM credential
                }
            }
            if (rows.isEmpty()) {
                out.info(I18n.t("kafka.noQuotas"));
            } else {
                out.table(List.of(e.type().equals("users") ? "user" : "client-id", "name", "value"), rows);
            }
            return;
        }
        Map<String, String> add = configList(p.all("--add-config"));
        List<String> delete = names(p.all("--delete-config"));
        List<UserScramCredentialAlteration> scram = new ArrayList<>();
        List<ClientQuotaAlteration.Op> ops = new ArrayList<>();
        for (Map.Entry<String, String> kv : add.entrySet()) {
            if (kv.getKey().startsWith("SCRAM-SHA-")) {
                Map<String, String> params = keyValues(List.of(kv.getValue().split(",")));
                int iterations = Integer.parseInt(params.getOrDefault("iterations", "4096"));
                scram.add(new UserScramCredentialUpsertion(e.name(), new ScramCredentialInfo(
                        ScramMechanism.fromMechanismName(kv.getKey()), iterations), params.get("password")));
            } else {
                ops.add(new ClientQuotaAlteration.Op(kv.getKey(), Double.parseDouble(kv.getValue())));
            }
        }
        for (String name : delete) {
            if (name.startsWith("SCRAM-SHA-")) {
                scram.add(new UserScramCredentialDeletion(e.name(), ScramMechanism.fromMechanismName(name)));
            } else {
                ops.add(new ClientQuotaAlteration.Op(name, null));
            }
        }
        if (!ops.isEmpty()) {
            Map<String, String> ent = new HashMap<>();
            ent.put(entityKey, e.name() == null || e.name().isEmpty() ? null : e.name());
            admin().alterClientQuotas(List.of(new ClientQuotaAlteration(new ClientQuotaEntity(ent), ops))).all().get();
        }
        if (!scram.isEmpty()) {
            admin().alterUserScramCredentials(scram).all().get();
        }
        out.info("Completed updating config for " + e.type() + " " + (e.name() == null || e.name().isEmpty() ? "<default>" : e.name()) + ".");
    }

    // ================================================================== kafka-acls

    private record Resource(ResourceType type, String name) {
    }

    private List<Resource> resources(KafkaArgs.Parsed p) {
        List<Resource> r = new ArrayList<>();
        p.all("--topic").forEach(t -> r.add(new Resource(ResourceType.TOPIC, t)));
        p.all("--group").forEach(g -> r.add(new Resource(ResourceType.GROUP, g)));
        p.all("--transactional-id").forEach(t -> r.add(new Resource(ResourceType.TRANSACTIONAL_ID, t)));
        if (p.has("--cluster")) {
            r.add(new Resource(ResourceType.CLUSTER, "kafka-cluster"));
        }
        return r;
    }

    private static PatternType patternType(KafkaArgs.Parsed p, PatternType fallback) {
        String s = p.get("--resource-pattern-type");
        return s == null ? fallback : PatternType.fromString(s.toUpperCase(Locale.ROOT));
    }

    private static AclOperation operation(String s) {
        AclOperation op = AclOperation.fromString(s.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT));
        if (op == AclOperation.UNKNOWN) {
            throw new UsageException(I18n.t("kafka.err.operation", s));
        }
        return op;
    }

    /** Các (tài nguyên, thao tác) cần cấp theo --operation / --producer / --consumer. */
    private List<Map.Entry<Resource, AclOperation>> grants(KafkaArgs.Parsed p) {
        List<Map.Entry<Resource, AclOperation>> out = new ArrayList<>();
        List<Resource> res = resources(p);
        for (Resource r : res) {
            for (String op : p.all("--operation")) {
                out.add(Map.entry(r, operation(op)));
            }
            if (p.has("--producer")) {
                if (r.type() == ResourceType.TOPIC) {
                    List.of(AclOperation.WRITE, AclOperation.DESCRIBE, AclOperation.CREATE).forEach(o -> out.add(Map.entry(r, o)));
                } else if (r.type() == ResourceType.TRANSACTIONAL_ID) {
                    List.of(AclOperation.WRITE, AclOperation.DESCRIBE).forEach(o -> out.add(Map.entry(r, o)));
                }
            }
            if (p.has("--consumer")) {
                if (r.type() == ResourceType.TOPIC) {
                    List.of(AclOperation.READ, AclOperation.DESCRIBE).forEach(o -> out.add(Map.entry(r, o)));
                } else if (r.type() == ResourceType.GROUP) {
                    out.add(Map.entry(r, AclOperation.READ));
                }
            }
        }
        if (p.has("--producer") && p.has("--idempotent")) {
            out.add(Map.entry(new Resource(ResourceType.CLUSTER, "kafka-cluster"), AclOperation.IDEMPOTENT_WRITE));
        }
        return out;
    }

    private void acls(KafkaArgs.Parsed p) throws Exception {
        switch (p.mode()) {
            case "--list" -> {
                List<AclBindingFilter> filters = new ArrayList<>();
                String principal = p.get("--principal");
                AccessControlEntryFilter ace = new AccessControlEntryFilter(principal, null, AclOperation.ANY, AclPermissionType.ANY);
                List<Resource> res = resources(p);
                if (res.isEmpty()) {
                    filters.add(new AclBindingFilter(ResourcePatternFilter.ANY, ace));
                } else {
                    for (Resource r : res) {
                        filters.add(new AclBindingFilter(new ResourcePatternFilter(r.type(), r.name(),
                                patternType(p, PatternType.LITERAL)), ace));
                    }
                }
                List<List<String>> rows = new ArrayList<>();
                for (AclBindingFilter f : filters) {
                    for (AclBinding b : admin().describeAcls(f).values().get()) {
                        rows.add(aclRow(b));
                    }
                }
                if (rows.isEmpty()) {
                    out.info(I18n.t("kafka.noAcls"));
                } else {
                    out.table(List.of("resource", "name", "pattern", "principal", "host", "operation", "permission"), rows);
                }
            }
            case "--add" -> {
                List<AclBinding> bindings = new ArrayList<>();
                PatternType pt = patternType(p, PatternType.LITERAL);
                for (Map.Entry<Resource, AclOperation> g : grants(p)) {
                    ResourcePattern rp = new ResourcePattern(g.getKey().type(), g.getKey().name(), pt);
                    for (String principal : p.all("--allow-principal")) {
                        for (String host : hosts(p.all("--allow-host"))) {
                            bindings.add(new AclBinding(rp, new AccessControlEntry(principal, host, g.getValue(), AclPermissionType.ALLOW)));
                        }
                    }
                    for (String principal : p.all("--deny-principal")) {
                        for (String host : hosts(p.all("--deny-host"))) {
                            bindings.add(new AclBinding(rp, new AccessControlEntry(principal, host, g.getValue(), AclPermissionType.DENY)));
                        }
                    }
                }
                if (bindings.isEmpty()) {
                    throw new UsageException(I18n.t("kafka.err.nothingToAdd"));
                }
                admin().createAcls(bindings).all().get();
                out.table(List.of("resource", "name", "pattern", "principal", "host", "operation", "permission"),
                        bindings.stream().map(KafkaCommands::aclRow).toList());
                out.info(I18n.t("kafka.aclsAdded", bindings.size()));
            }
            case "--remove" -> {
                List<AclBindingFilter> filters = new ArrayList<>();
                PatternType pt = patternType(p, PatternType.LITERAL);
                List<String> allow = p.all("--allow-principal");
                List<String> deny = p.all("--deny-principal");
                List<Map.Entry<Resource, AclOperation>> grants = grants(p);
                if (grants.isEmpty()) { // không có thao tác: xoá mọi ACL của tài nguyên
                    resources(p).forEach(r -> grants.add(Map.entry(r, AclOperation.ANY)));
                }
                for (Map.Entry<Resource, AclOperation> g : grants) {
                    ResourcePatternFilter rf = new ResourcePatternFilter(g.getKey().type(), g.getKey().name(), pt);
                    if (allow.isEmpty() && deny.isEmpty()) {
                        filters.add(new AclBindingFilter(rf, new AccessControlEntryFilter(null, null, g.getValue(), AclPermissionType.ANY)));
                    }
                    for (String principal : allow) {
                        filters.add(new AclBindingFilter(rf, new AccessControlEntryFilter(principal, null, g.getValue(), AclPermissionType.ALLOW)));
                    }
                    for (String principal : deny) {
                        filters.add(new AclBindingFilter(rf, new AccessControlEntryFilter(principal, null, g.getValue(), AclPermissionType.DENY)));
                    }
                }
                Collection<AclBinding> deleted = admin().deleteAcls(filters).all().get();
                if (deleted.isEmpty()) {
                    out.info(I18n.t("kafka.noAclsToRemove"));
                } else {
                    out.table(List.of("resource", "name", "pattern", "principal", "host", "operation", "permission"),
                            deleted.stream().map(KafkaCommands::aclRow).toList());
                    out.info(I18n.t("kafka.aclsRemoved", deleted.size()));
                }
            }
            default -> throw new UsageException(I18n.t("kafka.err.pickMode", String.join(", ", p.tool().modes())));
        }
    }

    private static List<String> hosts(List<String> given) {
        return given.isEmpty() ? List.of("*") : given;
    }

    private static List<String> aclRow(AclBinding b) {
        return List.of(String.valueOf(b.pattern().resourceType()), b.pattern().name(), String.valueOf(b.pattern().patternType()),
                b.entry().principal(), b.entry().host(), String.valueOf(b.entry().operation()),
                String.valueOf(b.entry().permissionType()));
    }

    // ================================================================== kafka-get-offsets / delete-records / log-dirs

    private void getOffsets(KafkaArgs.Parsed p) throws Exception {
        List<TopicPartition> tps = new ArrayList<>();
        if (p.has("--topic-partitions")) {
            // như tool gốc: "t1:1,t2:0-3,t3,t4:5-,t5:-3" - mỗi mục 1 topic, partition là 1 số hoặc 1 khoảng
            for (String entry : p.get("--topic-partitions").split(",")) {
                String[] tp = entry.strip().split(":", 2);
                List<TopicPartition> all = topicPartitions(tp[0]);
                if (tp.length == 1) {
                    tps.addAll(all);
                    continue;
                }
                String range = tp[1];
                int dash = range.indexOf('-');
                int lo = dash < 0 ? Integer.parseInt(range) : dash == 0 ? 0 : Integer.parseInt(range.substring(0, dash));
                int hi = dash < 0 ? lo : dash == range.length() - 1 ? Integer.MAX_VALUE : Integer.parseInt(range.substring(dash + 1));
                all.stream().filter(x -> x.partition() >= lo && x.partition() <= hi).forEach(tps::add);
            }
        } else {
            Set<Integer> only = new LinkedHashSet<>();
            if (p.has("--partitions")) {
                for (String s : p.get("--partitions").split(",")) {
                    only.add(Integer.parseInt(s.strip()));
                }
            }
            for (String t : matchTopics(p.get("--topic"), !p.has("--exclude-internal-topics"))) {
                for (TopicPartition tp : topicPartitions(t)) {
                    if (only.isEmpty() || only.contains(tp.partition())) {
                        tps.add(tp);
                    }
                }
            }
        }
        String time = p.get("--time") == null ? "-1" : p.get("--time");
        OffsetSpec spec = switch (time.toLowerCase(Locale.ROOT)) {
            case "-1", "latest" -> OffsetSpec.latest();
            case "-2", "earliest" -> OffsetSpec.earliest();
            case "-3", "max-timestamp" -> OffsetSpec.maxTimestamp();
            default -> OffsetSpec.forTimestamp(Long.parseLong(time));
        };
        Map<TopicPartition, OffsetSpec> req = new HashMap<>();
        tps.forEach(tp -> req.put(tp, spec));
        Map<TopicPartition, ListOffsetsResultInfo> res = req.isEmpty() ? Map.of() : admin().listOffsets(req).all().get();
        tps.sort(java.util.Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition));
        out.table(List.of("topic", "partition", "offset", "timestamp"), tps.stream().map(tp -> {
            ListOffsetsResultInfo i = res.get(tp);
            return List.of(tp.topic(), String.valueOf(tp.partition()), String.valueOf(i.offset()),
                    i.timestamp() < 0 ? "" : Instant.ofEpochMilli(i.timestamp()).toString());
        }).toList());
    }

    private void deleteRecords(KafkaArgs.Parsed p) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(Path.of(p.get("--offset-json-file")))).getAsJsonObject();
        Map<TopicPartition, Long> wanted = new LinkedHashMap<>();
        for (JsonElement e : root.getAsJsonArray("partitions")) {
            JsonObject o = e.getAsJsonObject();
            wanted.put(new TopicPartition(o.get("topic").getAsString(), o.get("partition").getAsInt()), o.get("offset").getAsLong());
        }
        Map<TopicPartition, Long> latest = offsets(wanted.keySet(), OffsetSpec.latest());
        Map<TopicPartition, RecordsToDelete> req = new LinkedHashMap<>();
        wanted.forEach((tp, o) -> req.put(tp, RecordsToDelete.beforeOffset(o < 0 ? latest.get(tp) : o)));
        List<List<String>> rows = new ArrayList<>();
        for (var e : admin().deleteRecords(req).lowWatermarks().entrySet()) {
            rows.add(List.of(e.getKey().topic(), String.valueOf(e.getKey().partition()),
                    String.valueOf(e.getValue().get().lowWatermark())));
        }
        out.table(List.of("topic", "partition", "low_watermark"), rows);
    }

    private void logDirs(KafkaArgs.Parsed p) throws Exception {
        List<Integer> brokers = new ArrayList<>();
        if (p.has("--broker-list")) {
            for (String s : p.get("--broker-list").split(",")) {
                brokers.add(Integer.parseInt(s.strip()));
            }
        } else {
            for (Node n : admin().describeCluster().nodes().get()) {
                brokers.add(n.id());
            }
        }
        Set<String> topics = p.has("--topic-list") ? Set.of(p.get("--topic-list").split(",")) : null;
        List<List<String>> rows = new ArrayList<>();
        List<List<String>> dirs = new ArrayList<>();
        for (var b : new TreeMap<>(admin().describeLogDirs(brokers).allDescriptions().get()).entrySet()) {
            for (var d : b.getValue().entrySet()) {
                LogDirDescription desc = d.getValue();
                dirs.add(List.of(String.valueOf(b.getKey()), d.getKey(), human(desc.totalBytes().orElse(-1)),
                        human(desc.usableBytes().orElse(-1)), desc.error() == null ? "" : desc.error().getMessage()));
                for (var r : desc.replicaInfos().entrySet()) {
                    TopicPartition tp = r.getKey();
                    if (topics != null && !topics.contains(tp.topic())) {
                        continue;
                    }
                    ReplicaInfo ri = r.getValue();
                    rows.add(List.of(String.valueOf(b.getKey()), d.getKey(), tp.topic(), String.valueOf(tp.partition()),
                            human(ri.size()), String.valueOf(ri.offsetLag()), ri.isFuture() ? "future" : ""));
                }
            }
        }
        rows.sort(java.util.Comparator.comparing((List<String> r) -> r.get(2)).thenComparing(r -> Integer.parseInt(r.get(3))));
        out.table(List.of("broker", "log dir", "total", "usable", "error"), dirs);
        out.table(List.of("broker", "log dir", "topic", "partition", "size", "offset lag", ""), rows);
    }

    private static String human(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return u == 0 ? bytes + " B" : String.format("%.1f %s", v, units[u]);
    }

    // ================================================================== kafka-reassign-partitions

    private static JsonObject readJson(String file) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(Path.of(file))).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new UsageException(I18n.t("kafka.err.badJsonFile", file, e.getMessage()));
        }
    }

    /** {"partitions":[{"topic","partition","replicas":[..]}]} -> replica đích theo partition (giữ thứ tự file). */
    private static Map<TopicPartition, List<Integer>> reassignmentFile(String file) throws IOException {
        Map<TopicPartition, List<Integer>> out = new LinkedHashMap<>();
        for (JsonElement e : readJson(file).getAsJsonArray("partitions")) {
            JsonObject o = e.getAsJsonObject();
            List<Integer> replicas = new ArrayList<>();
            if (o.has("replicas")) {
                o.getAsJsonArray("replicas").forEach(r -> replicas.add(r.getAsInt()));
            }
            out.put(new TopicPartition(o.get("topic").getAsString(), o.get("partition").getAsInt()), replicas);
        }
        return out;
    }

    private static String reassignmentJson(Map<TopicPartition, List<Integer>> assignment) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        com.google.gson.JsonArray parts = new com.google.gson.JsonArray();
        assignment.forEach((tp, replicas) -> {
            JsonObject o = new JsonObject();
            o.addProperty("topic", tp.topic());
            o.addProperty("partition", tp.partition());
            com.google.gson.JsonArray r = new com.google.gson.JsonArray();
            replicas.forEach(r::add);
            o.add("replicas", r);
            com.google.gson.JsonArray dirs = new com.google.gson.JsonArray();
            replicas.forEach(x -> dirs.add("any"));
            o.add("log_dirs", dirs);
            parts.add(o);
        });
        root.add("partitions", parts);
        return root.toString();
    }

    /** Replica hiện tại của các partition (theo thứ tự topic, partition). */
    private Map<TopicPartition, List<Integer>> currentAssignment(Collection<String> topics) throws Exception {
        Map<TopicPartition, List<Integer>> out = new LinkedHashMap<>();
        for (var d : new TreeMap<>(admin().describeTopics(topics).allTopicNames().get()).entrySet()) {
            for (TopicPartitionInfo pi : d.getValue().partitions()) {
                out.put(new TopicPartition(d.getKey(), pi.partition()), pi.replicas().stream().map(Node::id).toList());
            }
        }
        return out;
    }

    private void reassign(KafkaArgs.Parsed p) throws Exception {
        switch (p.mode()) {
            case "--generate" -> {
                List<String> topics = new ArrayList<>();
                for (JsonElement e : readJson(p.get("--topics-to-move-json-file")).getAsJsonArray("topics")) {
                    topics.add(e.getAsJsonObject().get("topic").getAsString());
                }
                List<Integer> brokers = new ArrayList<>();
                for (String s : p.get("--broker-list").split(",")) {
                    brokers.add(Integer.parseInt(s.strip()));
                }
                Map<TopicPartition, List<Integer>> current = currentAssignment(topics);
                out.info(I18n.t("kafka.reassign.current"));
                out.lines(List.of(reassignmentJson(current), ""));
                out.info(I18n.t("kafka.reassign.proposed"));
                out.lines(List.of(reassignmentJson(proposeAssignment(current, brokers))));
            }
            case "--execute" -> {
                Map<TopicPartition, List<Integer>> target = reassignmentFile(p.get("--reassignment-json-file"));
                Set<String> topics = target.keySet().stream().map(TopicPartition::topic).collect(Collectors.toCollection(LinkedHashSet::new));
                Map<TopicPartition, List<Integer>> current = currentAssignment(topics);
                Map<TopicPartition, List<Integer>> rollback = new LinkedHashMap<>();
                target.keySet().forEach(tp -> {
                    if (!current.containsKey(tp)) {
                        throw new UsageException(I18n.t("kafka.err.noPartition", tp));
                    }
                    rollback.put(tp, current.get(tp));
                });
                out.info(I18n.t("kafka.reassign.rollback"));
                out.lines(List.of(reassignmentJson(rollback), ""));
                Map<TopicPartition, Optional<org.apache.kafka.clients.admin.NewPartitionReassignment>> req = new LinkedHashMap<>();
                target.forEach((tp, r) -> req.put(tp, Optional.of(new org.apache.kafka.clients.admin.NewPartitionReassignment(r))));
                List<List<String>> rows = new ArrayList<>();
                for (var e : admin().alterPartitionReassignments(req).values().entrySet()) {
                    rows.add(List.of(e.getKey().topic(), String.valueOf(e.getKey().partition()),
                            String.valueOf(target.get(e.getKey())), outcome(e.getValue(), I18n.t("kafka.reassign.started"))));
                }
                out.table(List.of("topic", "partition", "replicas", I18n.t("common.result")), rows);
            }
            case "--verify" -> {
                Map<TopicPartition, List<Integer>> target = reassignmentFile(p.get("--reassignment-json-file"));
                var running = admin().listPartitionReassignments(target.keySet()).reassignments().get();
                Map<TopicPartition, List<Integer>> current = currentAssignment(
                        target.keySet().stream().map(TopicPartition::topic).collect(Collectors.toCollection(LinkedHashSet::new)));
                List<List<String>> rows = new ArrayList<>();
                target.forEach((tp, r) -> {
                    String status = running.containsKey(tp) ? I18n.t("kafka.reassign.inProgress")
                            : r.equals(current.get(tp)) ? I18n.t("kafka.reassign.done")
                            : I18n.t("kafka.reassign.mismatch", current.get(tp));
                    rows.add(List.of(tp.topic(), String.valueOf(tp.partition()), String.valueOf(r), status));
                });
                out.table(List.of("topic", "partition", "replicas", I18n.t("common.status")), rows);
            }
            case "--cancel" -> {
                Map<TopicPartition, List<Integer>> target = reassignmentFile(p.get("--reassignment-json-file"));
                var running = admin().listPartitionReassignments(target.keySet()).reassignments().get();
                Map<TopicPartition, Optional<org.apache.kafka.clients.admin.NewPartitionReassignment>> req = new LinkedHashMap<>();
                target.keySet().stream().filter(running::containsKey).forEach(tp -> req.put(tp, Optional.empty()));
                if (req.isEmpty()) {
                    out.info(I18n.t("kafka.reassign.nothingToCancel"));
                    return;
                }
                List<List<String>> rows = new ArrayList<>();
                for (var e : admin().alterPartitionReassignments(req).values().entrySet()) {
                    rows.add(List.of(e.getKey().topic(), String.valueOf(e.getKey().partition()),
                            outcome(e.getValue(), I18n.t("kafka.reassign.cancelled"))));
                }
                out.table(List.of("topic", "partition", I18n.t("common.result")), rows);
            }
            case "--list" -> {
                List<List<String>> rows = new ArrayList<>();
                for (var e : byPartition(admin().listPartitionReassignments().reassignments().get())) {
                    rows.add(List.of(e.getKey().topic(), String.valueOf(e.getKey().partition()), String.valueOf(e.getValue().replicas()),
                            String.valueOf(e.getValue().addingReplicas()), String.valueOf(e.getValue().removingReplicas())));
                }
                if (rows.isEmpty()) {
                    out.info(I18n.t("kafka.reassign.none"));
                } else {
                    out.table(List.of("topic", "partition", "replicas", "adding", "removing"), rows);
                }
            }
            default -> throw new UsageException(I18n.t("kafka.err.pickMode", String.join(", ", p.tool().modes())));
        }
    }

    /**
     * Rải replica vòng tròn lên {@code brokers}, mỗi topic bắt đầu lệch 1 broker để leader không dồn vào 1 chỗ; giữ
     * nguyên số replica của từng partition. Không xét rack.
     */
    static Map<TopicPartition, List<Integer>> proposeAssignment(Map<TopicPartition, List<Integer>> current, List<Integer> brokers) {
        Map<TopicPartition, List<Integer>> out = new LinkedHashMap<>();
        Map<String, Integer> topicStart = new HashMap<>();
        int n = brokers.size();
        current.forEach((tp, replicas) -> {
            if (replicas.size() > n) {
                throw new UsageException(I18n.t("kafka.reassign.tooFewBrokers", tp, replicas.size(), n));
            }
            int start = topicStart.computeIfAbsent(tp.topic(), t -> topicStart.size() % n);
            List<Integer> r = new ArrayList<>();
            for (int j = 0; j < replicas.size(); j++) {
                r.add(brokers.get((start + tp.partition() + j) % n));
            }
            out.put(tp, r);
        });
        return out;
    }

    private static <V> List<Map.Entry<TopicPartition, V>> byPartition(Map<TopicPartition, V> m) {
        return m.entrySet().stream().sorted(Map.Entry.comparingByKey(
                java.util.Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))).toList();
    }

    private static String outcome(org.apache.kafka.common.KafkaFuture<Void> f, String ok) {
        try {
            f.get();
            return ok;
        } catch (Exception e) {
            return "ERROR: " + message(e);
        }
    }

    // ================================================================== kafka-leader-election

    private void leaderElection(KafkaArgs.Parsed p) throws Exception {
        org.apache.kafka.common.ElectionType type;
        try {
            type = org.apache.kafka.common.ElectionType.valueOf(p.get("--election-type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UsageException(I18n.t("kafka.err.electionType", p.get("--election-type")));
        }
        Set<TopicPartition> partitions = null; // null = mọi partition
        if (p.has("--path-to-json-file")) {
            partitions = new LinkedHashSet<>();
            for (JsonElement e : readJson(p.get("--path-to-json-file")).getAsJsonArray("partitions")) {
                JsonObject o = e.getAsJsonObject();
                partitions.add(new TopicPartition(o.get("topic").getAsString(), o.get("partition").getAsInt()));
            }
        } else if (p.has("--topic")) {
            partitions = new LinkedHashSet<>(p.has("--partition")
                    ? List.of(new TopicPartition(p.get("--topic"), Integer.parseInt(p.get("--partition"))))
                    : topicPartitions(p.get("--topic")));
        } else if (p.has("--partition")) {
            throw new UsageException(I18n.t("kafka.err.partitionNeedsTopic"));
        }
        Map<TopicPartition, Optional<Throwable>> result = admin().electLeaders(type, partitions).partitions().get();
        List<List<String>> rows = new ArrayList<>();
        for (var e : byPartition(result)) {
            String r = e.getValue().map(t -> t instanceof org.apache.kafka.common.errors.ElectionNotNeededException
                    ? I18n.t("kafka.election.notNeeded") : "ERROR: " + message(t)).orElse(I18n.t("kafka.election.done", type));
            rows.add(List.of(e.getKey().topic(), String.valueOf(e.getKey().partition()), r));
        }
        if (rows.isEmpty()) {
            out.info(I18n.t("kafka.election.nothing"));
        } else {
            out.table(List.of("topic", "partition", I18n.t("common.result")), rows);
        }
    }

    // ================================================================== kafka-transactions

    private void transactions(KafkaArgs.Parsed p) throws Exception {
        switch (p.mode()) {
            case "list" -> {
                var options = new org.apache.kafka.clients.admin.ListTransactionsOptions();
                if (p.has("--duration-filter")) {
                    options.filterOnDuration(Long.parseLong(p.get("--duration-filter")));
                }
                List<List<String>> rows = new ArrayList<>();
                for (var b : new TreeMap<>(admin().listTransactions(options).allByBrokerId().get()).entrySet()) {
                    for (var t : b.getValue()) {
                        rows.add(List.of(t.transactionalId(), String.valueOf(b.getKey()), String.valueOf(t.producerId()),
                                t.state().toString()));
                    }
                }
                rows.sort(java.util.Comparator.comparing(r -> r.get(0)));
                out.table(List.of("TransactionalId", "Coordinator", "ProducerId", "TransactionState"), rows);
            }
            case "describe" -> {
                String id = p.get("--transactional-id");
                var d = admin().describeTransactions(List.of(id)).all().get().get(id);
                long now = System.currentTimeMillis();
                String start = d.transactionStartTimeMs().isPresent() ? String.valueOf(d.transactionStartTimeMs().getAsLong()) : "None";
                String duration = d.transactionStartTimeMs().isPresent() ? String.valueOf(now - d.transactionStartTimeMs().getAsLong()) : "None";
                out.table(List.of("CoordinatorId", "TransactionalId", "ProducerId", "ProducerEpoch", "TransactionState",
                                "TransactionTimeoutMs", "CurrentTransactionStartTimeMs", "TransactionDurationMs", "TopicPartitions"),
                        List.of(List.of(String.valueOf(d.coordinatorId()), id, String.valueOf(d.producerId()),
                                String.valueOf(d.producerEpoch()), d.state().toString(), String.valueOf(d.transactionTimeoutMs()),
                                start, duration, d.topicPartitions().stream().map(TopicPartition::toString).sorted()
                                        .collect(Collectors.joining(",")))));
            }
            case "describe-producers" -> {
                TopicPartition tp = new TopicPartition(p.get("--topic"), Integer.parseInt(p.get("--partition")));
                List<List<String>> rows = new ArrayList<>();
                for (var s : producers(tp, p.get("--broker-id"))) {
                    rows.add(List.of(String.valueOf(s.producerId()), String.valueOf(s.producerEpoch()),
                            s.coordinatorEpoch().isPresent() ? String.valueOf(s.coordinatorEpoch().getAsInt()) : "None",
                            String.valueOf(s.lastSequence()), String.valueOf(s.lastTimestamp()),
                            s.currentTransactionStartOffset().isPresent() ? String.valueOf(s.currentTransactionStartOffset().getAsLong()) : "None"));
                }
                out.table(List.of("ProducerId", "ProducerEpoch", "LatestCoordinatorEpoch", "LastSequence", "LastTimestamp",
                        "CurrentTransactionStartOffset"), rows);
            }
            case "find-hanging" -> findHanging(p);
            case "abort" -> {
                TopicPartition tp = new TopicPartition(p.get("--topic"), Integer.parseInt(p.get("--partition")));
                org.apache.kafka.clients.admin.AbortTransactionSpec spec;
                if (p.has("--start-offset")) {
                    long startOffset = Long.parseLong(p.get("--start-offset"));
                    var s = producers(tp, null).stream()
                            .filter(x -> x.currentTransactionStartOffset().orElse(-1) == startOffset).findFirst()
                            .orElseThrow(() -> new UsageException(I18n.t("kafka.tx.noOpenAt", tp, startOffset)));
                    spec = new org.apache.kafka.clients.admin.AbortTransactionSpec(tp, s.producerId(), (short) s.producerEpoch(),
                            s.coordinatorEpoch().orElse(0));
                } else {
                    if (!p.has("--producer-epoch") || !p.has("--coordinator-epoch")) {
                        throw new UsageException(I18n.t("kafka.tx.needEpochs"));
                    }
                    spec = new org.apache.kafka.clients.admin.AbortTransactionSpec(tp, Long.parseLong(p.get("--producer-id")),
                            Short.parseShort(p.get("--producer-epoch")), Integer.parseInt(p.get("--coordinator-epoch")));
                }
                admin().abortTransaction(spec).all().get();
                out.info(I18n.t("kafka.tx.aborted", spec.producerId(), tp));
            }
            default -> throw new UsageException(I18n.t("kafka.err.pickMode", String.join(", ", p.tool().modes())));
        }
    }

    private List<org.apache.kafka.clients.admin.ProducerState> producers(TopicPartition tp, String brokerId) throws Exception {
        var options = new org.apache.kafka.clients.admin.DescribeProducersOptions();
        if (brokerId != null) {
            options.brokerId(Integer.parseInt(brokerId));
        }
        return admin().describeProducers(List.of(tp), options).partitionResult(tp).get().activeProducers();
    }

    /**
     * Như tool gốc: transaction đang mở trên partition lâu hơn --max-transaction-timeout mà coordinator không còn coi
     * là đang chạy trên partition đó (transactional id không còn, đã sang transaction khác, hoặc không chứa partition)
     * thì là treo - nó chặn consumer read_committed ở mãi offset bắt đầu. Huỷ bằng: abort --start-offset.
     */
    private void findHanging(KafkaArgs.Parsed p) throws Exception {
        long maxMs = Long.parseLong(p.has("--max-transaction-timeout") ? p.get("--max-transaction-timeout") : "15") * 60_000;
        List<TopicPartition> partitions = new ArrayList<>();
        String broker = p.get("--broker-id");
        if (p.has("--topic")) {
            partitions.addAll(p.has("--partition")
                    ? List.of(new TopicPartition(p.get("--topic"), Integer.parseInt(p.get("--partition"))))
                    : topicPartitions(p.get("--topic")));
        } else {
            int id = Integer.parseInt(broker);
            for (var d : admin().describeTopics(matchTopics(null, false)).allTopicNames().get().values()) {
                for (TopicPartitionInfo pi : d.partitions()) {
                    if (pi.replicas().stream().anyMatch(n -> n.id() == id)) {
                        partitions.add(new TopicPartition(d.name(), pi.partition()));
                    }
                }
            }
        }
        var options = new org.apache.kafka.clients.admin.DescribeProducersOptions();
        if (broker != null) {
            options.brokerId(Integer.parseInt(broker));
        }
        long now = System.currentTimeMillis();
        record Open(TopicPartition tp, org.apache.kafka.clients.admin.ProducerState s) {
        }
        List<Open> candidates = new ArrayList<>();
        for (var e : admin().describeProducers(partitions, options).all().get().entrySet()) {
            for (var s : e.getValue().activeProducers()) {
                if (s.currentTransactionStartOffset().isPresent() && now - s.lastTimestamp() > maxMs) {
                    candidates.add(new Open(e.getKey(), s));
                }
            }
        }
        Map<Long, org.apache.kafka.clients.admin.TransactionDescription> byProducer = new HashMap<>();
        if (!candidates.isEmpty()) {
            Set<Long> ids = candidates.stream().map(c -> c.s().producerId()).collect(Collectors.toSet());
            List<String> txIds = admin().listTransactions(new org.apache.kafka.clients.admin.ListTransactionsOptions()
                    .filterProducerIds(ids)).all().get().stream().map(org.apache.kafka.clients.admin.TransactionListing::transactionalId).toList();
            if (!txIds.isEmpty()) {
                admin().describeTransactions(txIds).all().get().values().forEach(d -> byProducer.put(d.producerId(), d));
            }
        }
        List<List<String>> rows = new ArrayList<>();
        for (Open c : candidates) {
            var d = byProducer.get(c.s().producerId());
            boolean stillRunning = d != null && d.producerEpoch() == c.s().producerEpoch()
                    && d.state() == org.apache.kafka.clients.admin.TransactionState.ONGOING && d.topicPartitions().contains(c.tp());
            if (stillRunning) {
                continue;
            }
            rows.add(List.of(c.tp().topic(), String.valueOf(c.tp().partition()), String.valueOf(c.s().producerId()),
                    String.valueOf(c.s().producerEpoch()),
                    c.s().coordinatorEpoch().isPresent() ? String.valueOf(c.s().coordinatorEpoch().getAsInt()) : "None",
                    String.valueOf(c.s().currentTransactionStartOffset().getAsLong()), String.valueOf(c.s().lastTimestamp()),
                    String.valueOf((now - c.s().lastTimestamp()) / 60_000)));
        }
        if (rows.isEmpty()) {
            out.info(I18n.t("kafka.tx.noHanging", partitions.size()));
        } else {
            out.table(List.of("Topic", "Partition", "ProducerId", "ProducerEpoch", "CoordinatorEpoch", "StartOffset",
                    "LastTimestamp", "Duration(min)"), rows);
        }
    }

    // ================================================================== kafka-metadata-quorum

    private void quorum(KafkaArgs.Parsed p) throws Exception {
        QuorumInfo q = admin().describeMetadataQuorum().quorumInfo().get();
        boolean human = p.has("--human-readable");
        if (p.has("--status")) {
            long leaderEnd = q.voters().stream().filter(v -> v.replicaId() == q.leaderId()).mapToLong(QuorumInfo.ReplicaState::logEndOffset)
                    .findFirst().orElse(-1);
            long maxLag = q.voters().stream().mapToLong(v -> leaderEnd - v.logEndOffset()).max().orElse(0);
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("ClusterId", String.valueOf(admin().describeCluster().clusterId().get())));
            rows.add(List.of("LeaderId", String.valueOf(q.leaderId())));
            rows.add(List.of("LeaderEpoch", String.valueOf(q.leaderEpoch())));
            rows.add(List.of("HighWatermark", String.valueOf(q.highWatermark())));
            rows.add(List.of("MaxFollowerLag", String.valueOf(maxLag)));
            rows.add(List.of("CurrentVoters", q.voters().stream().map(v -> String.valueOf(v.replicaId())).collect(Collectors.joining(",", "[", "]"))));
            rows.add(List.of("CurrentObservers", q.observers().stream().map(v -> String.valueOf(v.replicaId())).collect(Collectors.joining(",", "[", "]"))));
            out.table(List.of(I18n.t("common.property"), I18n.t("common.value")), rows);
        }
        if (p.has("--replication")) {
            long leaderEnd = q.voters().stream().filter(v -> v.replicaId() == q.leaderId()).mapToLong(QuorumInfo.ReplicaState::logEndOffset)
                    .findFirst().orElse(-1);
            List<List<String>> rows = new ArrayList<>();
            for (QuorumInfo.ReplicaState v : q.voters()) {
                rows.add(replicaRow(v, leaderEnd, v.replicaId() == q.leaderId() ? "Leader" : "Follower", human));
            }
            for (QuorumInfo.ReplicaState v : q.observers()) {
                rows.add(replicaRow(v, leaderEnd, "Observer", human));
            }
            out.table(List.of("NodeId", "LogEndOffset", "Lag", "LastFetchTimestamp", "LastCaughtUpTimestamp", "Status"), rows);
        }
    }

    private static List<String> replicaRow(QuorumInfo.ReplicaState v, long leaderEnd, String status, boolean human) {
        return List.of(String.valueOf(v.replicaId()), String.valueOf(v.logEndOffset()), String.valueOf(leaderEnd - v.logEndOffset()),
                ts(v.lastFetchTimestamp(), human), ts(v.lastCaughtUpTimestamp(), human), status);
    }

    private static String ts(java.util.OptionalLong t, boolean human) {
        if (t.isEmpty()) {
            return "-";
        }
        return human ? (System.currentTimeMillis() - t.getAsLong()) + " ms ago" : String.valueOf(t.getAsLong());
    }

    // ================================================================== schema-registry

    private void schemaRegistry(KafkaArgs.Parsed p) throws Exception {
        SchemaRegistry sr = session.schemaRegistry(null);
        if (sr == null) {
            throw new UsageException(I18n.t("kafka.err.noSr", KafkaProfiles.file()));
        }
        String subject = p.get("--subject");
        switch (p.mode()) {
            case "--list" -> out.lines(sr.subjects(p.get("--prefix"), p.has("--deleted")));
            case "--versions" -> out.lines(sr.versions(subject, p.has("--deleted")));
            case "--describe" -> {
                SchemaRegistry.Schema s = sr.schema(subject, p.get("--version"));
                out.table(List.of("subject", "version", "id", "type"), List.of(List.of(subject, String.valueOf(s.version()),
                        String.valueOf(s.id()), s.type())));
                out.lines(prettyJson(s.schema()).lines().toList());
            }
            case "--id" -> {
                SchemaRegistry.Schema s = sr.byId(Integer.parseInt(p.get("--schema-id")));
                out.lines(prettyJson(s.schema()).lines().toList());
            }
            case "--register" -> {
                String schema = p.has("--schema-file") ? Files.readString(Path.of(p.get("--schema-file"))) : p.get("--schema");
                int id = sr.register(subject, schema, p.get("--schema-type"));
                out.info(I18n.t("kafka.sr.registered", subject, id));
            }
            case "--delete" -> {
                List<String> deleted = sr.delete(subject, p.get("--version"), p.has("--permanent"));
                out.info(I18n.t("kafka.sr.deleted", I18n.t(p.has("--permanent") ? "kafka.sr.permanent" : "kafka.sr.soft"), subject, String.join(", ", deleted)));
            }
            case "--compatibility" -> {
                if (p.has("--level")) {
                    out.info((subject == null ? I18n.t("kafka.sr.global") : subject) + ": " + sr.setCompatibility(subject, p.get("--level")));
                } else {
                    out.info((subject == null ? I18n.t("kafka.sr.global") : subject) + ": " + sr.compatibility(subject));
                }
            }
            default -> throw new UsageException(I18n.t("kafka.err.pickMode", String.join(", ", p.tool().modes())));
        }
    }

    static String prettyJson(String s) {
        try {
            return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(s));
        } catch (RuntimeException e) {
            return s; // protobuf: không phải JSON
        }
    }

    /** Thông điệp gốc của lỗi Kafka (bỏ lớp ExecutionException bọc ngoài), kèm tên lỗi cho dễ tra. */
    static String message(Throwable e) {
        Throwable c = e;
        while ((c instanceof java.util.concurrent.ExecutionException || c instanceof java.util.concurrent.CompletionException)
                && c.getCause() != null) {
            c = c.getCause();
        }
        if (c instanceof UsageException || c instanceof IOException && c.getClass() == SchemaRegistry.SchemaRegistryException.class) {
            return c.getMessage();
        }
        if (c instanceof org.apache.kafka.common.errors.SecurityDisabledException) {
            return I18n.t("kafka.err.noAuthorizer", c.getMessage());
        }
        if (c instanceof NumberFormatException) {
            return I18n.t("kafka.err.notNumber", c.getMessage());
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
