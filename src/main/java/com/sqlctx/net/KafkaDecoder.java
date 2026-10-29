package com.sqlctx.net;

import com.sqlctx.util.I18n;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Giao thức Kafka: tên API của từng request (Produce, Fetch, Metadata...), ghép với response theo correlation id để ra
 * độ trễ. Client gửi Fetch/Heartbeat liên tục nên mặc định gộp theo API mỗi {@link #WINDOW} giây; API hiếm mà quan
 * trọng (JoinGroup, CreateTopics...) thì in riêng từng lần.
 */
final class KafkaDecoder extends AppDecoder {

    static final double WINDOW = 5;

    private static final String[] APIS = {"Produce", "Fetch", "ListOffsets", "Metadata", "LeaderAndIsr", "StopReplica",
            "UpdateMetadata", "ControlledShutdown", "OffsetCommit", "OffsetFetch", "FindCoordinator", "JoinGroup",
            "Heartbeat", "LeaveGroup", "SyncGroup", "DescribeGroups", "ListGroups", "SaslHandshake", "ApiVersions",
            "CreateTopics", "DeleteTopics", "DeleteRecords", "InitProducerId", "OffsetForLeaderEpoch",
            "AddPartitionsToTxn", "AddOffsetsToTxn", "EndTxn", "WriteTxnMarkers", "TxnOffsetCommit", "DescribeAcls",
            "CreateAcls", "DeleteAcls", "DescribeConfigs", "AlterConfigs", "AlterReplicaLogDirs", "DescribeLogDirs",
            "SaslAuthenticate", "CreatePartitions", "CreateDelegationToken", "RenewDelegationToken",
            "ExpireDelegationToken", "DescribeDelegationToken", "DeleteGroups", "ElectLeaders",
            "IncrementalAlterConfigs", "AlterPartitionReassignments", "ListPartitionReassignments", "OffsetDelete",
            "DescribeClientQuotas", "AlterClientQuotas", "DescribeUserScramCredentials", "AlterUserScramCredentials",
            "Vote", "BeginQuorumEpoch", "EndQuorumEpoch", "DescribeQuorum", "AlterPartition", "UpdateFeatures",
            "Envelope", "FetchSnapshot", "DescribeCluster", "DescribeProducers", "BrokerRegistration",
            "BrokerHeartbeat", "UnregisterBroker", "DescribeTransactions", "ListTransactions", "AllocateProducerIds",
            "ConsumerGroupHeartbeat", "ConsumerGroupDescribe", "ControllerRegistration", "GetTelemetrySubscriptions",
            "PushTelemetry", "AssignReplicasToDirs", "ListClientMetricsResources", "DescribeTopicPartitions"};

    /** API in riêng từng lần (thay đổi nhóm, topic, cấu hình, đăng nhập). */
    private static final Set<Integer> NOTABLE = Set.of(11, 13, 14, 17, 19, 20, 21, 22, 33, 36, 37, 42, 44);

    private record Request(int api, int version, double time, int size) {
    }

    private static final class Stat {
        int count;
        double latency;
        double maxLatency;
        long requestBytes;
        long responseBytes;
    }

    private final Map<Integer, Request> inFlight = new HashMap<>();
    private final Map<Integer, Stat> window = new LinkedHashMap<>();
    private double windowStart = -1;
    private String clientId;

    KafkaDecoder(Listener listener) {
        super(listener);
    }

    /** Đầu luồng client có giống 1 request Kafka không (độ dài, api key, version hợp lý). */
    static boolean looksLike(byte[] b) {
        if (b.length < 14) {
            return false;
        }
        long size = Pcap.u32(b, 0);
        int api = Pcap.u16(b, 4);
        int version = Pcap.u16(b, 6);
        int idLen = (short) Pcap.u16(b, 12);
        return size >= 10 && size < (100 << 20) && api < APIS.length && version < 30 && idLen >= -1 && idLen < 1000;
    }

    static String api(int key) {
        return key >= 0 && key < APIS.length ? APIS[key] : "API" + key;
    }

    @Override
    String name() {
        return "KAFKA";
    }

    @Override
    String detail() {
        return clientId;
    }

    @Override
    void onClient(double time) {
        while (client.available() >= 4) {
            int size = client.s32(0);
            if (size < 8 || size > (100 << 20)) {
                client.clear();
                return;
            }
            if (client.available() < 14) {
                return;
            }
            int api = client.s16(4);
            int version = client.s16(6);
            int correlation = client.s32(8);
            int idLen = client.s16(12);
            if (clientId == null && idLen > 0 && client.available() >= 14 + idLen) {
                clientId = text(client.bytes(14, 14 + idLen));
            }
            if (inFlight.size() > 10_000) {
                inFlight.clear();
            }
            inFlight.put(correlation, new Request(api, version, time, 4 + size)); // số byte trên đường truyền
            client.drop(4L + size);
        }
    }

    @Override
    void onServer(double time) {
        while (server.available() >= 8) {
            int size = server.s32(0);
            if (size < 4 || size > (512 << 20)) {
                server.clear();
                return;
            }
            Request r = inFlight.remove(server.s32(4));
            if (r != null) {
                record(r, 4 + size, time);
            }
            server.drop(4L + size);
        }
    }

    private void record(Request r, int responseSize, double time) {
        double latency = time - r.time();
        if (NOTABLE.contains(r.api())) {
            listener.exchange(time, api(r.api()) + " v" + r.version(), "↓ " + NetFormat.bytes(responseSize), Status.OK, latency);
            return;
        }
        if (windowStart < 0) {
            windowStart = time;
        }
        Stat s = window.computeIfAbsent(r.api(), k -> new Stat());
        s.count++;
        s.latency += latency;
        s.maxLatency = Math.max(s.maxLatency, latency);
        s.requestBytes += r.size();
        s.responseBytes += responseSize;
    }

    /** In gộp các API thường gặp nếu đã đủ 1 cửa sổ thời gian ({@code force}: in ngay, lúc thoát). */
    void flush(double now, boolean force) {
        if (window.isEmpty() || !force && now - windowStart < WINDOW) {
            return;
        }
        StringBuilder req = new StringBuilder();
        long up = 0;
        long down = 0;
        double worst = 0;
        for (Map.Entry<Integer, Stat> e : window.entrySet()) {
            Stat s = e.getValue();
            if (!req.isEmpty()) {
                req.append(", ");
            }
            req.append(api(e.getKey())).append(" ×").append(s.count)
                    .append(" (").append(NetFormat.duration(s.latency / s.count)).append(')');
            up += s.requestBytes;
            down += s.responseBytes;
            worst = Math.max(worst, s.maxLatency);
        }
        String result = "↑ " + NetFormat.bytes(up) + "  ↓ " + NetFormat.bytes(down)
                + " · " + I18n.t("net.kafka.window", NetFormat.duration(now - windowStart), NetFormat.duration(worst));
        listener.exchange(now, req.toString(), result, Status.OK, -1);
        window.clear();
        windowStart = -1;
    }
}
