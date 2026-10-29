package com.sqlctx.net;

import com.sqlctx.net.AppDecoder.Status;
import com.sqlctx.net.NetPrinter.Kind;
import com.sqlctx.net.Pcap.Endpoint;
import com.sqlctx.net.Pcap.Packet;
import com.sqlctx.util.I18n;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Theo dõi kết nối TCP/luồng UDP từ các gói tin và in thành sự kiện dễ hiểu: mở/đóng kết nối, bị từ chối, không ai
 * trả lời, gửi lại gói, DNS, ping, thiết bị LAN; và nội dung của các giao thức đọc được (câu SQL Postgres, lệnh Redis,
 * API Kafka, HTTP). Gọi từ 1 thread (synchronized để in tóm tắt lúc Ctrl+C an toàn).
 */
final class NetAnalyzer {

    static final double SYN_TIMEOUT = 3;
    static final double UDP_IDLE = 10;
    static final double DNS_TIMEOUT = 2;
    static final double LAN_BURST = 2;
    static final double LAN_QUIET = 60;
    /** Cùng 1 gói thấy lại trong khoảng này là bản trùng do bắt trên nhiều interface (gửi lại thật cần ≥ vài ms). */
    static final double DUPLICATE_WINDOW = 0.001;
    /** Bảng "kết nối còn đang mở" lúc thoát: chỉ liệt kê bấy nhiêu kết nối nhiều dữ liệu nhất. */
    static final int OPEN_LIMIT = 15;

    static final Map<Integer, String> PORTS = Map.ofEntries(Map.entry(22, "SSH"), Map.entry(53, "DNS"),
            Map.entry(80, "HTTP"), Map.entry(443, "HTTPS"), Map.entry(1521, "Oracle"), Map.entry(3306, "MySQL"),
            Map.entry(5432, "PostgreSQL"), Map.entry(6379, "Redis"), Map.entry(9092, "Kafka"),
            Map.entry(6443, "K8s API"), Map.entry(2379, "etcd"), Map.entry(10250, "kubelet"),
            Map.entry(51820, "WireGuard"), Map.entry(1194, "OpenVPN"), Map.entry(8080, "HTTP"),
            Map.entry(8081, "Schema Registry"), Map.entry(123, "NTP"), Map.entry(21116, "RustDesk"),
            Map.entry(6343, "sFlow"), Map.entry(4789, "VXLAN"), Map.entry(179, "BGP"));

    /** Giao thức đoán theo cổng (dùng khi bắt giữa chừng, không thấy đầu luồng để nhận dạng). */
    enum Proto { POSTGRES, REDIS, KAFKA, HTTP, ORACLE }

    private static final Map<Integer, Proto> DEFAULT_HINTS = Map.of(5432, Proto.POSTGRES, 6379, Proto.REDIS,
            9092, Proto.KAFKA, 80, Proto.HTTP, 8080, Proto.HTTP, 1521, Proto.ORACLE);

    final class Conn implements AppDecoder.Listener {
        final Endpoint client;
        final Endpoint server;
        final double start;
        final boolean midStream;
        double last;
        int syns;
        double synAck = -1;
        boolean handshakeAcked;  // đã thấy ACK cuối của bắt tay 3 bước (chế độ chi tiết)
        double lastStep = -1;    // gói điều khiển trước đó đã in ở chế độ chi tiết - để in khoảng cách
        boolean serverHello;     // đã thấy gói TLS đầu tiên của server (chế độ chi tiết)
        long up;
        long down;
        int retrans;
        boolean zeroWindow;
        boolean announced;
        boolean done;
        boolean detected;
        String label;            // HTTPS, PostgreSQL... cho dòng "mở"
        String sni;
        String program;
        int pid;
        int programTries;
        AppDecoder decoder;
        final StreamBuf hello = new StreamBuf();
        final long[] nextSeq = {-1, -1};      // 0: client→server, 1: server→client
        final Set<Long> seen = new HashSet<>();

        Conn(Endpoint client, Endpoint server, double start, boolean midStream) {
            this.client = client;
            this.server = server;
            this.start = this.last = start;
            this.midStream = midStream;
            this.handshakeAcked = midStream;
        }

        String program() {
            if (program == null && programTries < 3) {
                programTries++;
                ProcessMap.Owner owner = processes.owner("tcp", client, server);
                if (owner != null) {
                    program = owner.name();
                    pid = owner.pid();
                }
                if (program != null && processes.isLocal(server.ip()) && !processes.isLocal(client.ip())) {
                    program += " ⇠"; // kết nối đi VÀO máy này
                }
            }
            return program;
        }

        /** Cột chi tiết (phím &lt; &gt;): giao thức, 2 đầu IP:cổng, số byte, tuổi kết nối, PID. */
        NetPrinter.Detail detail(double at) {
            String proto = decoder != null ? decoder.name() : label != null ? label : PORTS.get(server.port());
            return new NetPrinter.Detail(proto == null ? "TCP" : "TCP " + proto, client.toString(), server.toString(),
                    up, down, at - start, pid);
        }

        String target() {
            String name = sni != null ? sni : names.get(server.ip());
            String base = name == null ? server.toString() : server.port() == 443 || server.port() == 80 ? name : name + ":" + server.port();
            String detail = decoder != null ? decoder.detail() : null;
            return detail != null ? detail + " " + base : base;
        }

        @Override
        public void exchange(double time, String request, String result, Status status, double latency) {
            appRequests++;
            if (status == Status.ERROR) {
                appErrors++;
            }
            String colored = switch (status) {
                case ERROR -> NetFormat.red(result);
                case WARN -> NetFormat.yellow(result);
                case OK -> NetFormat.green(result);
                case INFO -> NetFormat.dim(result);
            };
            Kind kind = status == Status.ERROR ? Kind.ERROR : Kind.REQUEST;
            String took = latency >= 0 ? NetFormat.dim(" · " + NetFormat.duration(latency)) : "";
            printer.emit(time, kind, program(), target(), NetFormat.blue(decoder.name()) + " " + request + " → " + colored + took, null, detail(time));
        }

        @Override
        public void note(double time, String text, Status status) {
            Kind kind = status == Status.ERROR ? Kind.ERROR : status == Status.WARN ? Kind.WARN : Kind.INFO;
            String colored = status == Status.ERROR ? NetFormat.red(text) : status == Status.OK ? NetFormat.green(text) : text;
            printer.emit(time, kind, program(), target(), NetFormat.blue(decoder.name()) + " " + colored, null, detail(time));
        }
    }

    static final class Flow {
        final Endpoint client;
        final Endpoint server;
        final double start;
        double last;
        long up;
        long down;
        int packets;
        String program;
        int pid;
        final Lan.Burst lan;

        Flow(Endpoint client, Endpoint server, double start, boolean lan) {
            this.client = client;
            this.server = server;
            this.start = this.last = start;
            this.lan = lan ? new Lan.Burst() : null;
        }

        NetPrinter.Detail detail(double at) {
            String proto = server.port() == 443 ? "QUIC" : PORTS.get(server.port());
            return new NetPrinter.Detail(proto == null ? "UDP" : "UDP " + proto, client.toString(), server.toString(),
                    up, down, at - start, pid);
        }
    }

    private record DnsQuery(String name, String type, double time, String asker, Endpoint client, Endpoint server) {
        NetPrinter.Detail detail() {
            return new NetPrinter.Detail("UDP DNS", client.toString(), server.toString(), -1, -1, -1, 0);
        }
    }

    /** Trả lời A và AAAA của cùng 1 tên miền thường tới cách nhau vài ms: gộp thành 1 dòng. */
    private static final class DnsAnswer {
        final String name;
        final String asker;
        final double time;
        final List<String> types = new ArrayList<>();
        final List<String> ips = new ArrayList<>();
        final List<String> cnames = new ArrayList<>();
        Integer error;
        double took;
        NetPrinter.Detail detail;

        DnsAnswer(String name, String asker, double time) {
            this.name = name;
            this.asker = asker;
            this.time = time;
        }
    }

    private final NetPrinter printer;
    private final ProcessMap processes;
    private final Map<Integer, Proto> hints = new HashMap<>(DEFAULT_HINTS);
    private final Map<String, Conn> conns = new LinkedHashMap<>();
    private final Map<String, Flow> flows = new LinkedHashMap<>();
    private final Map<String, DnsQuery> dnsQueries = new HashMap<>();
    private final Map<String, DnsAnswer> dnsAnswers = new LinkedHashMap<>();
    private final Map<String, Double> pings = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();          // ip -> tên miền học từ DNS
    private final Map<String, Double> lanSeen = new HashMap<>();
    private final Set<String> lanExplained = new HashSet<>();
    private final Map<Long, Double> recent = new LinkedHashMap<>(4096, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Double> eldest) {
            return size() > 4096;
        }
    };
    private double now;
    private long wallAtNow = System.nanoTime();

    int ok;
    int refused;
    int noReply;
    int resets;
    int retransmits;
    int zeroWindows;
    int nxdomain;
    int appRequests;
    int appErrors;

    NetAnalyzer(NetPrinter printer, ProcessMap processes, Map<Integer, Proto> extraHints) {
        this.printer = printer;
        this.processes = processes;
        this.hints.putAll(extraHints);
    }

    synchronized void packet(Packet p) {
        // -i any bắt gói đi qua bridge (Docker, KVM) 2 lần: trên bridge và trên veth/tap - cùng nội dung, cách nhau vài
        // micro giây. Không bỏ bản trùng thì bị đếm nhầm thành "gói gửi lại" / "gọi lại lần 2".
        Double seenAt = recent.put(p.fingerprint(), p.time());
        if (seenAt != null && p.time() - seenAt < DUPLICATE_WINDOW) {
            return;
        }
        now = p.time();
        wallAtNow = System.nanoTime();
        switch (p.proto()) {
            case "tcp" -> tcp(p);
            case "udp" -> udp(p);
            case "icmp" -> icmp(p);
            case "arp" -> {
                if (p.arpOp() == 2) {
                    printer.emit(now, Kind.INFO, null, p.arpIp(), NetFormat.dim(I18n.t("net.arp", p.arpMac())), null,
                            new NetPrinter.Detail("ARP", p.arpMac(), p.arpIp(), -1, -1, -1, 0));
                }
            }
            default -> {
            }
        }
    }

    private static String key(Endpoint a, Endpoint b) {
        String x = a.toString();
        String y = b.toString();
        return x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
    }

    // ---------------------------------------------------------------- TCP

    private void tcp(Packet p) {
        String key = key(p.src(), p.dst());
        Conn c = conns.get(key);
        boolean syn = p.has(Packet.SYN);
        boolean ack = p.has(Packet.ACK);
        if (syn && !ack && (c == null || c.done)) {
            c = new Conn(p.src(), p.dst(), now, false);
            conns.put(key, c);
            c.program();
        } else if (c == null) {
            // bắt giữa chừng: đoán phía client là phía có cổng lớn hơn / không phải cổng dịch vụ
            boolean srcIsClient = PORTS.containsKey(p.dst().port()) || hints.containsKey(p.dst().port())
                    || !PORTS.containsKey(p.src().port()) && !hints.containsKey(p.src().port()) && p.src().port() > p.dst().port();
            c = srcIsClient ? new Conn(p.src(), p.dst(), now, true) : new Conn(p.dst(), p.src(), now, true);
            c.synAck = now;
            c.announced = true;
            conns.put(key, c);
            c.program();
        }
        if (c.done) {
            if (p.has(Packet.FIN) || p.has(Packet.RST)) {
                step(c, p.src().equals(c.client), p); // chiều còn lại đóng nốt: chỉ hiện ở chế độ chi tiết
            }
            return;
        }
        c.last = now;
        boolean up = p.src().equals(c.client);
        int dir = up ? 0 : 1;
        step(c, up, p);

        if (syn && !ack) {
            c.syns++;
            c.nextSeq[0] = (p.seq() + 1) & 0xFFFFFFFFL;
            if (c.syns == 2) {
                printer.emit(now, Kind.RETRY, c.program(), c.target(), NetFormat.yellow(I18n.t("net.tcp.retry", c.syns)), null, c.detail(now));
            }
            return;
        }
        if (syn) {
            c.synAck = now;
            c.nextSeq[1] = (p.seq() + 1) & 0xFFFFFFFFL;
            ok++;
            return;
        }
        if (p.has(Packet.RST)) {
            if (c.syns > 0 && c.synAck < 0) {
                refused++;
                c.done = true;
                printer.emit(now, Kind.ERROR, c.program(), c.target(), NetFormat.red(I18n.t("net.tcp.refused")),
                        I18n.t("net.tcp.refusedNote", c.server.ip(), c.server.port()), c.detail(now));
            } else {
                resets++;
                close(c, NetFormat.red(I18n.t(up ? "net.tcp.resetByClient" : "net.tcp.resetByServer")));
            }
            return;
        }
        if (p.window() == 0 && !c.zeroWindow) {
            zeroWindows++;
            c.zeroWindow = true;
            printer.emit(now, Kind.WARN, c.program(), c.target(), NetFormat.red(I18n.t("net.tcp.zeroWindow", p.src().ip())),
                    I18n.t("net.tcp.zeroWindowNote"), c.detail(now));
        }
        if (p.length() > 0) {
            long id = (p.seq() << 1) | dir;
            if (!c.seen.add(id)) {
                c.retrans++;
                retransmits++;
            } else {
                if (c.seen.size() > 20_000) {
                    c.seen.clear();
                }
                if (up) {
                    c.up += p.length();
                } else {
                    c.down += p.length();
                }
                stream(c, up, p);
            }
        }
        if (p.has(Packet.FIN)) {
            close(c, NetFormat.dim(I18n.t("net.tcp.closed")));
        }
    }

    /**
     * Chế độ chi tiết (phím v): mỗi gói điều khiển của TCP - SYN, SYN-ACK, ACK cuối của bắt tay, FIN, RST - thành 1 dòng
     * kèm thời gian từ gói điều khiển trước của cùng kết nối. Gói dữ liệu không in (đã có dòng yêu cầu SQL/Redis/HTTP).
     * Tắt chế độ này thì không tạo dòng nào.
     */
    private void step(Conn c, boolean up, Packet p) {
        if (!printer.verbose()) {
            return;
        }
        boolean syn = p.has(Packet.SYN);
        boolean ack = p.has(Packet.ACK);
        String name;
        String note;
        NetPrinter.Style style = NetPrinter.Style.TCP;
        if (syn && !ack) {
            name = "SYN";
            note = c.syns > 0 ? I18n.t("net.step.synAgain", c.syns + 1) : I18n.t("net.step.syn");
        } else if (syn) {
            name = "SYN-ACK";
            note = I18n.t("net.step.synAck");
        } else if (p.has(Packet.RST)) {
            name = "RST";
            style = NetPrinter.Style.RESET;
            note = I18n.t(up ? "net.step.rstClient" : "net.step.rstServer");
        } else if (p.has(Packet.FIN)) {
            name = "FIN";
            note = I18n.t(up ? "net.step.finClient" : "net.step.finServer");
        } else if (up && ack && c.synAck >= 0 && !c.handshakeAcked) {
            c.handshakeAcked = true;
            name = "ACK";
            note = I18n.t("net.step.ack");
        } else {
            return;
        }
        arrow(c, up, name, note, style);
    }

    /** 1 mũi tên của sơ đồ tuần tự, kèm thời gian từ gói trước đã vẽ của cùng kết nối. */
    private void arrow(Conn c, boolean up, String label, String note, NetPrinter.Style style) {
        String gap = c.lastStep < 0 ? null : "+" + NetFormat.duration(now - c.lastStep);
        c.lastStep = now;
        printer.arrow(now, c.program(), c.target(), new NetPrinter.Arrow(up, label, note, style, gap), c.detail(now));
    }

    /** Ghép dữ liệu theo thứ tự cho bộ giải mã; thiếu đoạn (gói không bắt được) thì báo gap. */
    private void stream(Conn c, boolean up, Packet p) {
        int dir = up ? 0 : 1;
        byte[] data = p.payload();
        long expected = c.nextSeq[dir];
        if (expected >= 0 && p.seq() != expected) {
            long diff = (p.seq() - expected) & 0xFFFFFFFFL;
            if (diff < 0x80000000L) {           // gói tới sau một khoảng trống: mất dữ liệu
                if (c.decoder != null) {
                    c.decoder.gap(up);
                }
            } else {                              // chồng lên phần đã nhận: cắt bỏ phần trùng
                long overlap = (expected - p.seq()) & 0xFFFFFFFFL;
                if (overlap >= data.length) {
                    return;
                }
                data = java.util.Arrays.copyOfRange(data, (int) overlap, data.length);
            }
        }
        c.nextSeq[dir] = (p.seq() + p.length()) & 0xFFFFFFFFL;
        if (!c.detected) {
            detect(c, up, data);
        }
        if (c.sni == null && up && c.label != null && c.label.equals("HTTPS") && !c.announced) {
            c.hello.append(data);
            byte[] buf = c.hello.bytes(0, c.hello.available());
            String sni = Tls.sni(buf, buf.length);
            if (sni != Tls.INCOMPLETE) {
                c.sni = sni;
                c.hello.clear();
                if (printer.verbose()) {
                    arrow(c, true, "TLS ClientHello", sni, NetPrinter.Style.TLS);
                }
                announce(c);
            }
        }
        if (!up && !c.serverHello && "HTTPS".equals(c.label) && Tls.looksLikeHandshake(data)) {
            c.serverHello = true;
            if (printer.verbose()) {
                arrow(c, false, "TLS ServerHello", I18n.t("net.step.serverHello"), NetPrinter.Style.TLS);
            }
        }
        if (c.decoder != null) {
            c.decoder.data(up, data, now);
            if (data.length < p.length()) {
                c.decoder.gap(up); // gói bị cắt bớt (snaplen)
            }
        }
    }

    /** Nhận dạng giao thức từ đoạn dữ liệu đầu tiên (hoặc theo cổng nếu bắt giữa chừng). */
    private void detect(Conn c, boolean up, byte[] data) {
        c.detected = true;
        Proto hint = hints.get(c.server.port());
        if (c.midStream) {
            if (hint != null) {
                attach(c, hint, true);
            }
            return;
        }
        if (!up) {
            // server nói trước (SSH, SMTP, MySQL...): không nhận dạng
            if (hint != null) {
                attach(c, hint, false);
            }
            announce(c);
            return;
        }
        if (Tls.looksLikeHandshake(data)) {
            c.label = "HTTPS"; // chờ đọc SNI rồi mới in dòng "mở" (announce); cổng khác 443 hiện là TLS
            return;
        }
        if (HttpDecoder.looksLike(data)) {
            attach(c, Proto.HTTP, false);
        } else if (PgDecoder.looksLike(data)) {
            attach(c, Proto.POSTGRES, false);
        } else if (RedisDecoder.looksLike(data)) {
            attach(c, Proto.REDIS, false);
        } else if (KafkaDecoder.looksLike(data) && (hint == null || hint == Proto.KAFKA)) {
            attach(c, Proto.KAFKA, false);
        } else if (hint == Proto.ORACLE) {
            c.label = "Oracle";
        }
        announce(c);
    }

    private void attach(Conn c, Proto proto, boolean midStream) {
        switch (proto) {
            case POSTGRES -> { c.decoder = new PgDecoder(c, midStream); c.label = "PostgreSQL"; }
            case REDIS -> { c.decoder = new RedisDecoder(c); c.label = "Redis"; }
            case KAFKA -> { c.decoder = new KafkaDecoder(c); c.label = "Kafka"; }
            case HTTP -> { c.decoder = new HttpDecoder(c); c.label = "HTTP"; }
            case ORACLE -> c.label = "Oracle";
        }
    }

    private void announce(Conn c) {
        if (c.announced) {
            return;
        }
        c.announced = true;
        String label = c.label;
        if ("HTTPS".equals(label) && c.server.port() != 443) {
            label = "TLS";
        }
        if (label == null) {
            label = PORTS.getOrDefault(c.server.port(), "TCP");
        }
        String handshake = c.synAck >= 0 && c.syns > 0 ? NetFormat.dim(" · " + I18n.t("net.tcp.handshake", NetFormat.duration(c.synAck - c.start))) : "";
        // chế độ chi tiết in từng gói theo giờ thật: dòng "mở" lấy giờ lúc in để không chen giờ cũ vào giữa
        double at = printer.verbose() ? Math.max(now, c.start) : c.start;
        printer.emit(at, Kind.OPEN, c.program(), c.target(), NetFormat.green(I18n.t("net.tcp.open", label)) + handshake, null, c.detail(c.start));
    }

    private void close(Conn c, String how) {
        if (c.done) {
            return;
        }
        c.done = true;
        if (c.decoder instanceof KafkaDecoder k) {
            k.flush(now, true);
        }
        if (c.midStream && c.up + c.down == 0) {
            return; // kết nối nền mở từ trước, chỉ thấy gói đóng: không có gì đáng in
        }
        String extra = c.retrans > 0 ? NetFormat.yellow(" · ⚠ " + I18n.t("net.tcp.retransmits", c.retrans)) : "";
        String mid = c.midStream ? " " + I18n.t("net.tcp.midStream") : "";
        printer.emit(now, Kind.CLOSE, c.program(), c.target(), transfer(c.up, c.down)
                + NetFormat.dim(" · " + NetFormat.duration(now - c.start) + mid + " · ") + how + extra, null, c.detail(now));
    }

    private static String transfer(long up, long down) {
        return String.format("↑%9s  ↓%9s", NetFormat.bytes(up), NetFormat.bytes(down));
    }

    // ---------------------------------------------------------------- UDP / DNS / LAN

    private void udp(Packet p) {
        Endpoint src = p.src();
        Endpoint dst = p.dst();
        if (src.port() == 53 || dst.port() == 53) {
            dns(p);
            return;
        }
        boolean lan = Lan.isCast(dst.ip()) || src.port() == 1900 || dst.port() == 1900;
        String key = lan ? "lan|" + src.ip() + "|" + dst : key(src, dst);
        Flow f = flows.get(key);
        if (f == null) {
            boolean srcIsClient = lan || PORTS.containsKey(dst.port()) || src.port() > dst.port();
            f = srcIsClient ? new Flow(src, dst, now, lan) : new Flow(dst, src, now, false);
            flows.put(key, f);
            ProcessMap.Owner owner = processes.owner("udp", f.client, f.server);
            if (owner != null) {
                f.program = owner.name();
                f.pid = owner.pid();
            }
            if (!lan) {
                String kind = f.server.port() == 443 ? "QUIC (HTTP/3)" : PORTS.getOrDefault(f.server.port(), "UDP");
                printer.emit(now, Kind.OPEN, f.program, flowTarget(f), NetFormat.green(I18n.t("net.tcp.open", kind)), null, f.detail(now));
            }
        }
        f.last = now;
        f.packets++;
        if (src.equals(f.client)) {
            f.up += p.length();
        } else {
            f.down += p.length();
        }
        if (lan) {
            if (src.port() == 1900 || dst.port() == 1900) {
                f.lan.ssdp(p.payload());
            } else if (dst.port() == 5353) {
                f.lan.mdns(p.payload());
            }
        }
    }

    private String flowTarget(Flow f) {
        String name = names.get(f.server.ip());
        return name != null ? name + ":" + f.server.port() : f.server.toString();
    }

    private void endFlow(String key, Flow f, String why) {
        if (f.lan != null) {
            endLan(f);
            return;
        }
        printer.emit(now, Kind.CLOSE, f.program, flowTarget(f), transfer(f.up, f.down)
                + NetFormat.dim(" · " + I18n.t("net.udp.packets", f.packets) + " · " + NetFormat.duration(f.last - f.start) + " · " + why), null, f.detail(now));
    }

    private void endLan(Flow f) {
        String what = Lan.PROTOCOLS.getOrDefault(f.server.port(), Lan.PROTOCOLS.getOrDefault(f.client.port(), null));
        String proto = what != null ? what.substring(0, what.indexOf(" — ")) : "UDP " + f.server.port();
        String explain = what != null ? I18n.tr(what.substring(what.indexOf(" — ") + 3)) : I18n.t("net.lan.generic");
        String describe = f.lan.describe();
        // bản IPv4/IPv6 của cùng 1 lần phát, hoặc cùng nguồn phát lại y hệt trong LAN_QUIET giây: không in lại
        String same = proto + "|" + describe + "|" + f.lan.device;
        String fromSource = f.client.ip() + "|" + proto + "|" + describe;
        if (now - lanSeen.getOrDefault(same, -1e9) < 3 || now - lanSeen.getOrDefault(fromSource, -1e9) < LAN_QUIET) {
            return;
        }
        lanSeen.put(same, now);
        lanSeen.put(fromSource, now);
        if (lanSeen.size() > 5000) {
            lanSeen.clear();
        }
        String note = lanExplained.add(proto) ? I18n.t("net.lan.note", proto, explain) : null;
        String who = f.client.ip() + (f.lan.device != null ? " " + f.lan.device : "");
        printer.emit(now, Kind.LAN, f.program != null ? f.program : "LAN", who,
                NetFormat.blue(proto) + " " + describe + NetFormat.dim(" · " + I18n.t("net.udp.packets", f.packets)), note,
                new NetPrinter.Detail(proto.startsWith("UDP") ? proto : "UDP " + proto, f.client.toString(), f.server.toString(), f.up, f.down, f.last - f.start, f.pid));
    }

    private void dns(Packet p) {
        Dns.Message m = Dns.parse(p.payload());
        if (m == null) {
            return;
        }
        if (!m.reply()) {
            String key = m.id() + "|" + p.src();
            if (!dnsQueries.containsKey(key)) {
                dnsQueries.put(key, new DnsQuery(m.name(), m.type(), now, processes.lookup("udp", p.src()), p.src(), p.dst()));
            }
            return;
        }
        DnsQuery q = dnsQueries.remove(m.id() + "|" + p.dst());
        if (q == null) {
            return;
        }
        m.ips().forEach(ip -> names.put(ip, q.name()));
        String key = q.name() + "|" + q.asker();
        DnsAnswer a = dnsAnswers.remove(key);
        boolean first = a == null;
        if (first) {
            a = new DnsAnswer(q.name(), q.asker(), now);
            a.detail = q.detail();
        }
        a.types.add(q.type());
        a.ips.addAll(m.ips());
        a.cnames.addAll(m.cnames());
        Integer error = m.rcode() == 0 ? null : m.rcode();
        a.error = first ? error : a.error != null && error != null ? error : null; // lỗi chỉ khi cả hai đều lỗi
        a.took = Math.max(a.took, now - q.time());
        if (first && (q.type().equals("A") || q.type().equals("AAAA"))) {
            dnsAnswers.put(key, a); // chờ chút xem có trả lời A/AAAA còn lại không
        } else {
            printDns(a);
        }
    }

    private void printDns(DnsAnswer a) {
        String types = String.join("+", a.types.stream().distinct().sorted().toList());
        String result;
        if (a.error != null && a.ips.isEmpty()) {
            if (a.error == Dns.NXDOMAIN) {
                nxdomain++;
            }
            result = NetFormat.red(switch (a.error) {
                case Dns.NXDOMAIN -> I18n.t("net.dns.nxdomain");
                case Dns.SERVFAIL -> I18n.t("net.dns.servfail");
                case Dns.REFUSED -> I18n.t("net.dns.refused");
                default -> "rcode " + a.error;
            });
        } else if (!a.ips.isEmpty()) {
            List<String> ips = a.ips.stream().distinct().sorted((x, y) -> Boolean.compare(x.contains(":"), y.contains(":"))).toList();
            result = NetFormat.green(String.join(", ", ips.subList(0, Math.min(4, ips.size())))
                    + (ips.size() > 4 ? " (+" + (ips.size() - 4) + ")" : ""));
        } else if (!a.cnames.isEmpty()) {
            result = NetFormat.dim(I18n.t("net.dns.alias", a.cnames.get(0)));
        } else {
            result = NetFormat.dim(I18n.t("net.dns.noRecord"));
        }
        printer.emit(a.time, Kind.DNS, a.asker, a.name, "DNS " + types + " → " + result + NetFormat.dim(" · " + NetFormat.duration(a.took)), null, a.detail);
    }

    // ---------------------------------------------------------------- ICMP

    private void icmp(Packet p) {
        String a = p.src().ip();
        String b = p.dst().ip();
        int type = p.icmpType();
        int code = p.icmpCode();
        boolean v6 = p.v6();
        NetPrinter.Detail detail = new NetPrinter.Detail(v6 ? "ICMPv6" : "ICMP", a, b, p.length(), -1, -1, 0);
        int request = v6 ? 128 : 8;
        int reply = v6 ? 129 : 0;
        if (type == request) {
            pings.put(a + "|" + b + "|" + p.icmpId() + "|" + p.icmpSeq(), now);
        } else if (type == reply) {
            Double t = pings.remove(b + "|" + a + "|" + p.icmpId() + "|" + p.icmpSeq());
            String took = t != null ? NetFormat.dim(" · " + NetFormat.duration(now - t)) : "";
            printer.emit(now, Kind.PING, null, names.getOrDefault(a, a), NetFormat.green(I18n.t("net.ping.alive")) + took, null, detail);
        } else if (v6 && type == 2 || !v6 && type == 3 && code == 4) {
            printer.emit(now, Kind.ERROR, null, a, NetFormat.red(I18n.t("net.icmp.tooBig")), I18n.t("net.icmp.tooBigNote"), detail);
        } else if (v6 && type == 1 || !v6 && type == 3) {
            String why = I18n.t((v6 ? "net.icmp6.unreach." : "net.icmp.unreach.") + code);
            if (why.startsWith("net.icmp")) {
                why = "code " + code;
            }
            printer.emit(now, Kind.ERROR, null, b, NetFormat.red(I18n.t("net.icmp.unreachable", why)), I18n.t("net.icmp.unreachableNote", a), detail);
        } else if (v6 && type == 3 || !v6 && type == 11) {
            printer.emit(now, Kind.WARN, null, a, NetFormat.yellow(I18n.t("net.icmp.ttl")), null, detail);
        }
    }

    // ---------------------------------------------------------------- định kỳ

    /** Kiểm tra hết giờ (không ai trả lời, DNS, luồng UDP im lặng...). {@code fin}: lúc thoát, kết thúc mọi thứ. */
    synchronized void tick(boolean fin) {
        double t = now + (fin ? 0 : (System.nanoTime() - wallAtNow) / 1e9);
        for (Iterator<Map.Entry<String, Conn>> it = conns.entrySet().iterator(); it.hasNext(); ) {
            Conn c = it.next().getValue();
            if (c.syns > 0 && c.synAck < 0 && !c.done && (fin || t - c.start > SYN_TIMEOUT)) {
                c.done = true;
                noReply++;
                printer.emit(t, Kind.ERROR, c.program(), c.target(),
                        NetFormat.red(I18n.t("net.tcp.noReply")) + NetFormat.dim(" · " + I18n.t("net.tcp.tries", c.syns)),
                        I18n.t("net.tcp.noReplyNote"), c.detail(t));
            } else if (!c.done && !c.announced && c.synAck >= 0 && (t - c.last > 0.3 || t - c.start > 1)) {
                announce(c);
            } else if (!c.done && c.decoder instanceof KafkaDecoder k) {
                k.flush(t, fin);
            } else if (c.done && t - c.last > 30) {
                it.remove();
            }
        }
        for (Iterator<Map.Entry<String, Flow>> it = flows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Flow> e = it.next();
            Flow f = e.getValue();
            if (fin || t - f.last > (f.lan != null ? LAN_BURST : UDP_IDLE)) {
                it.remove();
                endFlow(e.getKey(), f, fin ? I18n.t("net.udp.stillOpen") : I18n.t("net.udp.idle", (int) UDP_IDLE));
            }
        }
        for (Iterator<DnsAnswer> it = dnsAnswers.values().iterator(); it.hasNext(); ) {
            DnsAnswer a = it.next();
            if (fin || t - a.time > 0.3) {
                it.remove();
                printDns(a);
            }
        }
        for (Iterator<DnsQuery> it = dnsQueries.values().iterator(); it.hasNext(); ) {
            DnsQuery q = it.next();
            if (fin || t - q.time() > DNS_TIMEOUT) {
                it.remove();
                printer.emit(t, Kind.DNS, q.asker(), q.name(), "DNS " + q.type() + " → " + NetFormat.red(I18n.t("net.dns.noAnswer")), null, q.detail());
            }
        }
        if (pings.size() > 1000) {
            pings.clear();
        }
    }

    /** Kết nối còn mở lúc thoát (có dữ liệu): [chương trình, đích, ↑, ↓] cho bảng tóm tắt. */
    synchronized List<List<String>> openConnections() {
        List<List<String>> out = new ArrayList<>();
        List<Conn> open = conns.values().stream().filter(c -> !c.done && c.up + c.down > 0)
                .sorted((x, y) -> Long.compare(y.up + y.down, x.up + x.down)).toList();
        for (Conn c : open.subList(0, Math.min(OPEN_LIMIT, open.size()))) {
            String prog = c.program();
            out.add(List.of(prog != null ? NetFormat.magenta(prog) : NetFormat.dim("·"), c.target(),
                    NetFormat.bytes(c.up), NetFormat.bytes(c.down)));
        }
        if (open.size() > OPEN_LIMIT) {
            out.add(List.of("", NetFormat.dim(I18n.t("net.summary.more", open.size() - OPEN_LIMIT)), "", ""));
        }
        return out;
    }
}
