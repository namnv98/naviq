package com.sqlctx.net;

import com.sqlctx.util.I18n;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code sqlctx --scan}: liệt kê mọi thiết bị đang bật trong LAN - IP, MAC, hãng, tên, loại đoán được (Windows, Linux,
 * iPhone, camera, máy chấm công...), cổng mở, dịch vụ quảng bá. Không cần root: ping cả dải để kernel điền bảng ARP, rồi
 * hỏi từng máy bằng {@link LanProbes}.
 * <pre>
 * sqlctx --scan                  interface của route mặc định
 * sqlctx --scan --iface=wlan0
 * sqlctx --scan --fast           bỏ quét cổng và trang web (nhanh hơn, ít manh mối hơn)
 * </pre>
 * Luôn in mọi thứ biết được, không cắt chữ: terminal thật thì mở danh sách toàn màn hình như kết quả SQL, thoát ra
 * (hoặc chạy qua pipe) thì in bảng chia nhóm đầy đủ.
 */
public final class LanScan {

    /** Loại thiết bị: biểu tượng, màu, khoá chữ (scan.kind.*). Thứ tự = thứ tự in trong bảng tổng hợp khi bằng số. */
    enum Kind {
        GATEWAY(NetFormat::blue), ROUTER(NetFormat::blue), IPHONE(NetFormat::bold),
        MAC(NetFormat::bold), APPLE_TV(NetFormat::bold), CAST(NetFormat::bold),
        PRINTER(NetFormat::yellow), CAMERA(NetFormat::red), ATTENDANCE(NetFormat::magenta),
        ANDROID(NetFormat::green), WINDOWS(NetFormat::blue), LINUX(NetFormat::yellow),
        PC_NO_PING(NetFormat::blue), PC(NetFormat::blue), IOT(NetFormat::magenta),
        PHONE_PRIVATE(s -> s), UNKNOWN(NetFormat::dim);

        final Function<String, String> color;

        Kind(Function<String, String> color) {
            this.color = color;
        }

        String label() {
            return I18n.t("scan.kind." + name().toLowerCase(Locale.ROOT));
        }

        Section section() {
            return switch (this) {
                case GATEWAY, ROUTER -> Section.NETWORK;
                case WINDOWS, LINUX, MAC, PC_NO_PING, PC -> Section.COMPUTER;
                case IPHONE, ANDROID, PHONE_PRIVATE -> Section.PHONE;
                case APPLE_TV, CAST, PRINTER, CAMERA, ATTENDANCE, IOT -> Section.DEVICE;
                case UNKNOWN -> Section.UNKNOWN;
            };
        }
    }

    /** Nhóm trong bảng, theo thứ tự in. */
    enum Section {
        NETWORK, COMPUTER, PHONE, DEVICE, UNKNOWN
    }

    /** Mọi thứ biết được về 1 máy. */
    static final class Host {
        String ip;
        String mac;
        String vendor = "";
        boolean randomMac;
        boolean gateway;
        int ttl = -1;
        double rtt;
        LanProbes.NetBios netbios;
        LanProbes.Mdns mdns;
        Map<String, String> upnp = Map.of();
        List<Integer> ports = List.of();
        List<LanProbes.Web> web = List.of();
        String ssh;
        String rtsp;
        LanProbes.Cert cert;
        LanProbes.Smb smb;
        LanProbes.Snmp snmp;
        LanProbes.WsdDevice wsd;
        List<String> ipv6 = List.of();

        /** Nguồn → tên, mạnh nhất trước; bỏ tên trùng (không phân biệt hoa thường). */
        Map<String, String> names() {
            Map<String, String> all = new LinkedHashMap<>();
            if (netbios != null) {
                all.put("NetBIOS", netbios.name());
            }
            if (smb != null) {
                all.put("SMB", smb.dnsName() != null ? smb.dnsName() : smb.netbiosName());
            }
            if (mdns != null) {
                all.put("mDNS", mdns.hostname);
                all.put("Bonjour", mdns.instance);
            }
            if (wsd != null) {
                all.put("WSD", wsd.name);
            }
            all.put("UPnP", upnp.get("friendlyName"));
            Map<String, String> out = new LinkedHashMap<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            all.forEach((src, n) -> {
                if (n != null && !n.isBlank() && seen.add(n.toLowerCase(Locale.ROOT))) {
                    out.put(src, n);
                }
            });
            return out;
        }

        String name() {
            return names().values().stream().findFirst().orElse("");
        }

        String os() {
            return ttl < 0 ? null : ttl <= 64 ? "unix" : ttl <= 128 ? "windows" : "embedded";
        }

        /** Mọi chữ biết về máy (chữ thường) để dò từ khoá. */
        String blob() {
            StringBuilder sb = new StringBuilder(vendor).append(' ').append(name());
            if (netbios != null) {
                sb.append(' ').append(netbios.workgroup()).append(' ').append(netbios.user());
            }
            if (mdns != null) {
                sb.append(' ').append(mdns.hostname).append(' ').append(mdns.instance).append(' ').append(mdns.model)
                        .append(' ').append(String.join(" ", mdns.services)).append(' ').append(String.join(" ", mdns.txt.values()));
            }
            upnp.values().forEach(v -> sb.append(' ').append(v));
            for (LanProbes.Web w : web) {
                sb.append(' ').append(w.title()).append(' ').append(w.server()).append(' ').append(w.realm());
            }
            if (ssh != null) {
                sb.append(' ').append(ssh);
            }
            if (rtsp != null) {
                sb.append(' ').append(rtsp);
            }
            if (cert != null) {
                sb.append(' ').append(cert.subject());
            }
            if (snmp != null) {
                sb.append(' ').append(snmp.descr()).append(' ').append(snmp.name());
            }
            if (wsd != null) {
                sb.append(' ').append(wsd.type).append(' ').append(wsd.name).append(' ').append(wsd.manufacturer).append(' ').append(wsd.model);
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        }
    }

    private static final Pattern MAC_NAME = Pattern.compile("macbook|imac|macmini|mac-mini|-mac\\b|mac\\d+,|_companion-link");
    private static final Pattern CAMERA = Pattern.compile("hikvision|dahua|ezviz|imou|ipcam|\\bnvr\\b|\\bdvr\\b");
    private static final Pattern ANDROID = Pattern.compile("android|realme|xiaomi|redmi|oppo|vivo|galaxy|huawei|honor");
    private static final Pattern WINDOWS_NAME = Pattern.compile("\\b(desktop|laptop)-[a-z0-9]{7}\\b");
    private static final Pattern ROUTER = Pattern.compile("tp-link|tplink|mikrotik|ubiquiti|router|access point");
    private static final Pattern IOT = Pattern.compile("espressif|tuya|_hap\\b");
    private static final Pattern PC_NIC = Pattern.compile("azurewave|intel corp|cloud network|liteon|realtek");
    /** Dịch vụ mDNS → tên ngắn dễ đọc; null = không đáng in. */
    private static final Map<String, String> SERVICES = new LinkedHashMap<>();

    static {
        String[][] s = {{"_airplay._tcp", "AirPlay"}, {"_raop._tcp", "AirPlay-audio"}, {"_companion-link._tcp", "Handoff"},
                {"_rfb._tcp", "ScreenShare"}, {"_remotepairing._tcp", "Xcode-pair"}, {"_apple-mobdev2._tcp", "iTunes-sync"},
                {"_googlecast._tcp", "Cast"}, {"_ipp._tcp", "IPP"}, {"_smb._tcp", "SMB"}, {"_ssh._tcp", "SSH"},
                {"_http._tcp", "HTTP"}, {"_asquic._udp", null}, {"_device-info._tcp", null}, {"_sleep-proxy._udp", null}};
        for (String[] e : s) {
            SERVICES.put(e[0], e[1]);
        }
    }

    private LanScan() {
    }

    public static boolean handles(String[] args) {
        return Arrays.asList(args).contains("--scan");
    }

    public static void run(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        String iface = System.getProperty("DB_IFACE");
        if (iface == null || iface.equals("any")) {
            Matcher m = Pattern.compile("\\bdev (\\S+)").matcher(LanProbes.run(List.of("ip", "-4", "route", "show", "default"), 3));
            iface = m.find() ? m.group(1) : "wlan0";
        }
        Matcher addr = Pattern.compile("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)/(\\d+)")
                .matcher(LanProbes.run(List.of("ip", "-4", "-o", "addr", "show", "dev", iface), 3));
        if (!addr.find()) {
            System.err.println(NetFormat.red(I18n.t("scan.noIface", iface)));
            System.exit(1);
        }
        String self = addr.group(1);
        int prefix = Math.max(Integer.parseInt(addr.group(2)), 22); // dải lớn hơn /22 thì chỉ quét quanh máy này
        int selfInt = toInt(self);
        int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
        int network = selfInt & mask;
        int size = 1 << (32 - prefix);
        String cidr = toIp(network) + "/" + prefix;
        Matcher gw = Pattern.compile("default via (\\S+)").matcher(LanProbes.run(List.of("ip", "-4", "route", "show", "default", "dev", iface), 3));
        String gateway = gw.find() ? gw.group(1) : null;
        boolean fast = Arrays.asList(args).contains("--fast");

        long started = System.nanoTime();
        status(I18n.t("scan.scanning", cidr, iface));
        Map<String, double[]> pings = new ConcurrentHashMap<>();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore limit = new Semaphore(64); // mỗi ping là 1 tiến trình: đừng mở 1000 cái cùng lúc
            for (int i = 1; i < size - 1; i++) {
                String ip = toIp(network + i);
                ex.submit(() -> {
                    limit.acquireUninterruptibly();
                    try {
                        double[] r = LanProbes.ping(ip);
                        if (r != null) {
                            pings.put(ip, r);
                        }
                    } finally {
                        limit.release();
                    }
                });
            }
        }

        Map<String, String> arp = LanProbes.arpTable(iface);
        arp.remove(self);
        List<String> ips = arp.keySet().stream().filter(ip -> (toInt(ip) & mask) == network)
                .sorted((a, b) -> Integer.compareUnsigned(toInt(a), toInt(b))).toList();
        Map<String, String> oui = LanProbes.loadOui();
        status(I18n.t("scan.probing", ips.size()));

        Map<String, List<String>> v6 = LanProbes.ipv6Table(iface);
        List<Host> hosts = new ArrayList<>();
        String ifaceName = iface;
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Map<String, LanProbes.Mdns>> mdns = ex.submit(() -> LanProbes.mdns(ips, 3000, ifaceName));
            Future<Map<String, Map<String, String>>> ssdp = ex.submit(() -> LanProbes.ssdp(3000));
            Future<Map<String, LanProbes.WsdDevice>> wsd = ex.submit(() -> LanProbes.wsdiscover(3000, ifaceName));
            Map<String, Future<LanProbes.NetBios>> nb = new LinkedHashMap<>();
            Map<String, Future<List<Integer>>> ports = new LinkedHashMap<>();
            for (String ip : ips) {
                nb.put(ip, ex.submit(() -> LanProbes.netbios(ip)));
                if (!fast) {
                    ports.put(ip, ex.submit(() -> openPorts(ex, ip)));
                }
            }
            Map<String, LanProbes.Mdns> mdnsRes = mdns.get();
            Map<String, Map<String, String>> ssdpRes = ssdp.get();
            Map<String, LanProbes.WsdDevice> wsdRes = wsd.get();
            // hỏi metadata WSD (tên máy Windows, model máy in) song song
            Map<String, Future<?>> wsdMeta = new LinkedHashMap<>();
            wsdRes.forEach((ip, d) -> wsdMeta.put(ip, ex.submit(() -> LanProbes.wsdGet(d))));
            for (Future<?> f : wsdMeta.values()) {
                f.get();
            }
            Map<String, Future<Map<String, String>>> upnp = new LinkedHashMap<>();
            ssdpRes.forEach((ip, d) -> upnp.put(ip, ex.submit(() -> d.containsKey("location")
                    ? LanProbes.upnpDescription(d.get("location")) : Map.<String, String>of())));
            Map<String, Future<?>> services = new LinkedHashMap<>();
            for (String ip : ips) {
                Host h = new Host();
                h.ip = ip;
                h.mac = arp.get(ip);
                h.randomMac = LanProbes.randomMac(h.mac);
                h.vendor = h.randomMac ? I18n.t("scan.randomMac") : oui.getOrDefault(h.mac.substring(0, 8), "?");
                h.gateway = ip.equals(gateway);
                double[] p = pings.get(ip);
                if (p != null) {
                    h.ttl = (int) p[0];
                    h.rtt = p[1];
                }
                h.netbios = nb.get(ip).get();
                h.mdns = mdnsRes.get(ip);
                h.wsd = wsdRes.get(ip);
                h.ipv6 = v6.getOrDefault(h.mac, List.of());
                if (!fast) {
                    h.ports = ports.get(ip).get();
                    services.put(ip, ex.submit(() -> probeServices(h)));
                }
                hosts.add(h);
            }
            for (Host h : hosts) {
                Map<String, String> u = new LinkedHashMap<>(ssdpRes.getOrDefault(h.ip, Map.of()));
                if (upnp.containsKey(h.ip)) {
                    u.putAll(upnp.get(h.ip).get());
                }
                u.remove("location");
                h.upnp = u;
                if (services.containsKey(h.ip)) {
                    services.get(h.ip).get();
                }
            }
        }
        status(null);
        double seconds = (System.nanoTime() - started) / 1e9;
        render(out, hosts, new String[]{iface, cidr, gateway, self}, seconds);
        System.exit(0);
    }

    /** Hỏi sâu các dịch vụ đang mở: trang web, banner SSH, chứng chỉ HTTPS, SMB (phiên bản Windows, tên máy). */
    private static void probeServices(Host h) {
        h.web = LanProbes.web(h.ip, h.ports);
        if (h.ports.contains(22)) {
            h.ssh = LanProbes.sshBanner(h.ip);
        }
        for (int port : List.of(443, 8443)) {
            if (h.cert == null && h.ports.contains(port)) {
                h.cert = LanProbes.tlsCert(h.ip, port);
            }
        }
        if (h.ports.contains(445)) {
            h.smb = LanProbes.smb(h.ip);
        }
        for (int port : List.of(554, 8554)) {
            if (h.rtsp == null && h.ports.contains(port)) {
                h.rtsp = LanProbes.rtsp(h.ip, port);
            }
        }
        h.snmp = LanProbes.snmp(h.ip); // UDP 161, không nằm trong quét cổng TCP nên luôn thử
    }

    private static List<Integer> openPorts(ExecutorService ex, String ip) throws Exception {
        Map<Integer, Future<Boolean>> probes = new LinkedHashMap<>();
        LanProbes.PORTS.keySet().forEach(port -> probes.put(port, ex.submit(() -> LanProbes.portOpen(ip, port))));
        List<Integer> open = new ArrayList<>();
        for (var e : probes.entrySet()) {
            if (e.getValue().get()) {
                open.add(e.getKey());
            }
        }
        return open;
    }

    /** Đoán loại thiết bị từ các manh mối, mạnh nhất trước (cổng đặc trưng, tên, dịch vụ), yếu nhất sau (hãng card mạng). */
    static Kind guess(Host h) {
        String b = h.blob();
        List<Integer> p = h.ports;
        boolean unix = "unix".equals(h.os());
        boolean pcNic = PC_NIC.matcher(b).find();
        if (h.gateway) {
            return Kind.GATEWAY;
        }
        if (ROUTER.matcher(b).find()) {
            return Kind.ROUTER;
        }
        if (p.contains(62078) || b.contains("iphone") || b.contains("ipad")) {
            return Kind.IPHONE;
        }
        if (MAC_NAME.matcher(b).find()) {
            return Kind.MAC;
        }
        if (b.contains("_airplay") || b.contains("appletv")) {
            return Kind.APPLE_TV;
        }
        if (p.contains(8008) || b.contains("_googlecast")) {
            return Kind.CAST;
        }
        if (p.contains(9100) || p.contains(631) || b.contains("_ipp") || b.contains("printer")
                || h.wsd != null && h.wsd.type.contains("Print")) {
            return Kind.PRINTER;
        }
        if (CAMERA.matcher(b).find() || p.contains(554) || p.contains(37777)) {
            return Kind.CAMERA;
        }
        if (p.contains(4370) || b.contains("zkteco")) {
            return Kind.ATTENDANCE;
        }
        if (p.contains(5555) || ANDROID.matcher(b).find()) {
            return Kind.ANDROID;
        }
        if (p.contains(3389) && !unix || "windows".equals(h.os()) || WINDOWS_NAME.matcher(b).find()
                || h.smb != null && h.smb.build() > 0 || h.wsd != null && h.wsd.type.contains("Computer")) {
            return Kind.WINDOWS;
        }
        if (IOT.matcher(b).find()) {
            return Kind.IOT;
        }
        if (pcNic && unix) {
            return Kind.LINUX;
        }
        if (pcNic && h.ttl < 0) {
            return Kind.PC_NO_PING;
        }
        if (pcNic) {
            return Kind.PC;
        }
        return h.randomMac ? Kind.PHONE_PRIVATE : Kind.UNKNOWN;
    }

    // ---------------------------------------------------------------- hiển thị

    /** {@code net}: interface, dải, gateway, máy này. */
    private static void render(PrintStream out, List<Host> hosts, String[] net, double seconds) {
        browse(hosts);
        // thoát màn hình xem thì in bảng đầy đủ ra màn hình chính để còn lại trong lịch sử terminal
        renderDetailed(out, hosts, net, seconds);
    }

    /** Terminal thật: mở danh sách toàn màn hình như kết quả SQL ({@link com.sqlctx.ui.DataViewTable#view}); qua pipe thì thôi. */
    private static void browse(List<Host> hosts) {
        if (hosts.isEmpty() || System.console() == null || !NetFormat.stdoutIsTerminal()) {
            return;
        }
        // ffm(false): lý do như SqlctxCli - provider FFM cài handler tín hiệu native làm JVM chết khi Ctrl+C
        try (org.jline.terminal.Terminal t = org.jline.terminal.TerminalBuilder.builder().system(true).ffm(false)
                .encoding(StandardCharsets.UTF_8).build()) {
            if (!org.jline.terminal.Terminal.TYPE_DUMB.equals(t.getType()) && !org.jline.terminal.Terminal.TYPE_DUMB_COLOR.equals(t.getType())) {
                com.sqlctx.ui.DataViewTable.view(t, gridColumns(), gridRows(hosts));
            }
        } catch (Exception e) {
            // không mở được màn hình xem: vẫn còn bảng đầy đủ in ngay sau
        }
    }

    /** Cột của danh sách: tóm tắt trước, sau đó mỗi nguồn tin 1 cột (phím x xem đủ 1 máy). */
    static List<String> gridColumns() {
        return List.of(I18n.t("scan.col.ip"), I18n.t("scan.col.name"), I18n.t("scan.col.type"), I18n.t("scan.col.vendorShort"),
                "MAC", I18n.t("scan.col.ping") + " ms", "TTL", I18n.t("scan.col.osShort"), I18n.t("scan.col.ports"),
                I18n.t("scan.col.details"), I18n.t("scan.col.otherNames"), "SMB", "NetBIOS", "mDNS", "mDNS TXT",
                "UPnP", "SSH", "Web", "RTSP", "SNMP", "WSD", "IPv6", "TLS");
    }

    /** 1 hàng chữ thường (không màu, không cắt) mỗi máy, theo {@link #gridColumns()}; thiếu thì ô rỗng. */
    static List<List<String>> gridRows(List<Host> hosts) {
        List<List<String>> rows = new ArrayList<>();
        for (Host h : hosts) {
            Kind kind = guess(h);
            Map<String, String> names = new LinkedHashMap<>(h.names());
            String name = names.isEmpty() ? "" : names.remove(names.keySet().iterator().next());
            String others = String.join(", ", names.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList());
            String ports = String.join(" ", h.ports.stream().map(p -> LanProbes.PORTS.get(p) + ":" + p).toList());

            String smb = "";
            if (h.smb != null) {
                smb = h.smb.os() + (h.smb.dnsDomain() == null ? "" : " · domain " + h.smb.dnsDomain());
            }
            String netbios = "";
            if (h.netbios != null) {
                List<String> bits = new ArrayList<>();
                for (String b : new String[]{h.netbios.workgroup(), h.netbios.fileShare() ? I18n.t("scan.fileShare") : null,
                        h.netbios.user() == null ? null : "user " + h.netbios.user()}) {
                    if (b != null) {
                        bits.add(b);
                    }
                }
                netbios = String.join(" · ", bits);
            }
            String mdns = h.mdns == null ? "" : ((h.mdns.model == null ? "" : h.mdns.model + " ") + String.join(", ", h.mdns.services)).strip();
            String mdnsTxt = h.mdns == null ? "" : h.mdns.txt.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(java.util.stream.Collectors.joining(" "));
            String upnp = String.join(" · ", h.upnp.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
            String ssh = h.ssh == null ? "" : h.ssh;
            String web = h.web.stream().map(w -> (":" + w.port() + " " + (w.title().isEmpty() ? "" : "\"" + w.title() + "\" ")
                    + w.server() + (w.realm().isEmpty() ? "" : " realm=\"" + w.realm() + "\"")).strip())
                    .collect(java.util.stream.Collectors.joining("  ·  "));
            String rtsp = h.rtsp == null ? "" : h.rtsp;
            String snmp = "";
            if (h.snmp != null) {
                snmp = java.util.stream.Stream.of(h.snmp.name(), h.snmp.descr())
                        .filter(v -> v != null && !v.isBlank()).collect(java.util.stream.Collectors.joining(" · "));
            }
            String wsd = "";
            if (h.wsd != null) {
                wsd = java.util.stream.Stream.of(h.wsd.name, h.wsd.manufacturer, h.wsd.model, wsdType(h.wsd.type))
                        .filter(v -> v != null && !v.isBlank()).collect(java.util.stream.Collectors.joining(" · "));
            }
            String ipv6 = String.join(" ", h.ipv6);
            String tls = "";
            if (h.cert != null) {
                LanProbes.Cert c = h.cert;
                tls = certFields(c.subject(), true) + (c.names().isEmpty() ? "" : " · SAN " + String.join(", ", c.names()))
                        + " · " + I18n.t(c.expired() ? "scan.cert.expired" : "scan.cert.until", c.notAfter())
                        + " · " + (c.selfSigned() ? I18n.t("scan.cert.selfSigned") : I18n.t("scan.cert.issuer", certFields(c.issuer(), false)));
            }
            rows.add(List.of(h.ip, name, typeText(h, kind), h.vendor,
                    h.mac == null ? "" : h.mac, h.ttl < 0 ? "" : String.format(Locale.ROOT, "%.0f", h.rtt),
                    h.ttl < 0 ? "" : String.valueOf(h.ttl), h.os() == null ? I18n.t("scan.noPing") : I18n.t("scan.os." + h.os()),
                    ports, NetPrinter.plain(clue(h, Integer.MAX_VALUE)), others, smb, netbios, mdns, mdnsTxt, upnp, ssh, web, rtsp, snmp, wsd, ipv6, tls));
        }
        return rows;
    }

    private static final Pattern UBUNTU_SSH = Pattern.compile("OpenSSH_([\\d.]+)p\\d+ Ubuntu");
    private static final Pattern DEBIAN_SSH = Pattern.compile("OpenSSH_([\\d.]+)p\\d+ Debian");
    /** Bản OpenSSH đi kèm từng bản Ubuntu/Debian (đọc từ banner SSH). */
    private static final Map<String, String> UBUNTU = Map.of("8.2", "Ubuntu 20.04", "8.9", "Ubuntu 22.04",
            "9.3", "Ubuntu 23.10", "9.6", "Ubuntu 24.04", "9.7", "Ubuntu 24.10", "9.9", "Ubuntu 25.04", "10.0", "Ubuntu 25.10");
    private static final Map<String, String> DEBIAN = Map.of("7.9", "Debian 10", "8.4", "Debian 11", "9.2", "Debian 12", "10.0", "Debian 13");

    /** Loại cụ thể nhất biết được: bản Windows (SMB), bản Linux (SSH), model Apple (mDNS); không thì tên loại. */
    static String typeText(Host h, Kind kind) {
        if (h.smb != null && h.smb.build() > 0) {
            return h.smb.os().replaceFirst(" \\(.*\\)$", "").replaceFirst(" / Server \\d+$", "");
        }
        if (h.ssh != null) {
            Matcher u = UBUNTU_SSH.matcher(h.ssh);
            if (u.find()) {
                return UBUNTU.getOrDefault(u.group(1), "Ubuntu");
            }
            Matcher d = DEBIAN_SSH.matcher(h.ssh);
            if (d.find()) {
                return DEBIAN.getOrDefault(d.group(1), "Debian");
            }
        }
        if (h.mdns != null && h.mdns.model != null) {
            return kind.label() + " (" + h.mdns.model + ")";
        }
        return kind.label();
    }

    /**
     * Bảng mặc định: những manh mối đáng đọc nhất của 1 máy gói vào 1 dòng ≤ {@code width} - tiêu đề + Server trang web,
     * chứng chỉ, banner SSH, workgroup/chia sẻ file, dịch vụ mDNS, model UPnP. Bỏ thứ đã hiện ở cột khác (tên, bản
     * Windows/Ubuntu ở cột LOẠI). Tô màu sau khi cắt để không cắt giữa mã màu.
     */
    static String clue(Host h, int width) {
        List<String[]> bits = new ArrayList<>(); // {chữ, màu: w=web t=tls s=ssh n=netbios m=mdns u=upnp g=snmp r=đỏ}
        if (!h.web.isEmpty()) {
            LanProbes.Web w = h.web.getFirst();
            String server = w.server().replaceAll("/[\\d.]+\\S*", "").strip(); // bỏ số phiên bản: "Microsoft-IIS/10.0" -> "Microsoft-IIS"
            String title = w.title().isBlank() ? "" : "\"" + w.title().strip() + "\"";
            String realm = w.realm().isBlank() ? "" : w.realm().strip();
            String text = (title + " " + (title.toLowerCase(Locale.ROOT).contains(server.toLowerCase(Locale.ROOT)) ? "" : server)).strip();
            bits.add(new String[]{text.isEmpty() ? realm : text, "w"});
        }
        if (h.snmp != null) {
            String s = h.snmp.name() != null && !h.snmp.name().isBlank() ? h.snmp.name() : h.snmp.descr();
            if (s != null && !s.isBlank()) {
                bits.add(new String[]{s.strip(), "g"});
            }
        }
        if (h.wsd != null) {
            String m = java.util.stream.Stream.of(h.wsd.manufacturer, h.wsd.model)
                    .filter(v -> v != null && !v.isBlank()).collect(java.util.stream.Collectors.joining(" "));
            if (!m.isBlank() && !h.name().toLowerCase(Locale.ROOT).contains(m.toLowerCase(Locale.ROOT))) {
                bits.add(new String[]{m, "g"});
            }
        }
        if (h.rtsp != null && !h.rtsp.equals("RTSP")) {
            bits.add(new String[]{h.rtsp, "s"});
        }
        if (h.cert != null) {
            String who = certFields(h.cert.subject(), false);
            if (!who.matches("[0-9a-f]{24,}")) { // CN chỉ là mã băm thì không có gì để đọc
                bits.add(new String[]{"TLS " + who, "t"});
            }
            if (h.cert.expired()) {
                bits.add(new String[]{I18n.t("scan.cert.expired", h.cert.notAfter()), "r"});
            }
        }
        if (h.ssh != null) {
            bits.add(new String[]{h.ssh.replaceFirst("^SSH-[\\d.]+-", "").replaceFirst(" .*$", ""), "s"});
        }
        if (h.smb != null && h.smb.dnsDomain() != null && !h.smb.dnsDomain().equalsIgnoreCase(h.smb.dnsName())) {
            bits.add(new String[]{"domain " + h.smb.dnsDomain(), "n"});
        }
        LanProbes.NetBios nb = h.netbios;
        if (nb != null) {
            if (nb.workgroup() != null) {
                bits.add(new String[]{nb.workgroup(), "n"});
            }
            if (nb.fileShare()) {
                bits.add(new String[]{I18n.t("scan.fileShare"), "n"});
            }
            if (nb.user() != null) {
                bits.add(new String[]{"user " + nb.user(), "n"});
            }
        }
        if (h.mdns != null) {
            List<String> svcs = h.mdns.services.stream().sorted()
                    .map(s -> SERVICES.containsKey(s) ? SERVICES.get(s) : s.replaceFirst("^_", "").replaceFirst("\\..*", ""))
                    .filter(java.util.Objects::nonNull).toList();
            if (h.mdns.model != null && !typeText(h, guess(h)).contains(h.mdns.model)) {
                bits.add(new String[]{h.mdns.model, "m"});
            }
            if (!svcs.isEmpty()) {
                bits.add(new String[]{String.join(" ", svcs), "m"});
            }
        }
        List<String> model = new ArrayList<>();
        for (String k : List.of("manufacturer", "modelName")) {
            String v = h.upnp.get(k);
            if (v != null && !v.isBlank() && !h.name().contains(v)) {
                model.add(v);
            }
        }
        if (!model.isEmpty()) {
            bits.add(new String[]{String.join(" ", model), "u"});
        }

        StringBuilder sb = new StringBuilder();
        int left = width;
        for (String[] b : bits) {
            int sep = sb.isEmpty() ? 0 : 3;
            if (b[0].isEmpty() || left - sep < 4) {
                continue;
            }
            String text = cut(b[0], left - sep);
            left -= sep + NetPrinter.width(text);
            sb.append(sep == 0 ? "" : NetFormat.dim(" · ")).append(switch (b[1]) {
                case "w", "s" -> NetFormat.green(text);
                case "t", "u" -> NetFormat.yellow(text);
                case "n", "g" -> NetFormat.blue(text);
                case "m" -> NetFormat.magenta(text);
                case "r" -> NetFormat.red(text);
                default -> text;
            });
        }
        return sb.toString();
    }

    /** Bảng chia nhóm, nhiều dòng mỗi máy, đủ mọi chi tiết, chữ dài xuống dòng chứ không cắt. */
    private static void renderDetailed(PrintStream out, List<Host> hosts, String[] net, double seconds) {
        Map<Host, Kind> kinds = new LinkedHashMap<>();
        hosts.forEach(h -> kinds.put(h, guess(h)));

        // ô thông tin mạng
        Host gw = hosts.stream().filter(h -> h.gateway).findFirst().orElse(null);
        String gwText = net[2] == null ? "—" : net[2] + (gw == null ? "" : NetFormat.dim(" · " + gw.vendor));
        String when = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));
        List<String> info = List.of(
                infoLine("scan.info.network", NetFormat.bold(net[1]) + NetFormat.dim(" · ") + net[0]),
                infoLine("scan.info.gateway", gwText),
                infoLine("scan.info.self", net[3]),
                infoLine("scan.info.result", NetFormat.bold(I18n.t("scan.info.devices", hosts.size()))
                        + NetFormat.dim(" · " + I18n.t("scan.info.took", String.format(Locale.ROOT, "%.1f", seconds)) + " · " + when)));
        NetPrinter.box(out, NetFormat.bold(I18n.t("scan.info.title")), info);
        out.println();

        // bảng: 5 cột đầu dựng trước để biết còn bao nhiêu chỗ cho cột chi tiết (vừa bề ngang terminal)
        List<String> headers = List.of(I18n.t("scan.col.ip"), I18n.t("scan.col.device"), I18n.t("scan.col.vendor"),
                I18n.t("scan.col.os"), I18n.t("scan.col.ports"), I18n.t("scan.col.details"));
        Map<Host, List<String>> cells = new LinkedHashMap<>();
        int columns = NetCli.terminalSize()[0];
        int[] w = new int[5];
        for (Host h : hosts) {
            List<String> c = List.of(NetFormat.bold(NetFormat.blue(h.ip)), device(h, kinds.get(h)), vendor(h), os(h), ports(h));
            for (int i = 0; i < 5; i++) {
                for (String line : c.get(i).split("\n")) {
                    w[i] = Math.max(w[i], Math.max(NetPrinter.width(line), NetPrinter.width(headers.get(i))));
                }
            }
            cells.put(h, c);
        }
        int used = Arrays.stream(w).sum() + 3 * 6 + 1;
        int detailWidth = Math.clamp(columns - used - 2, 26, 72);

        List<NetPrinter.Group> groups = new ArrayList<>();
        for (Section sec : Section.values()) {
            List<Host> in = hosts.stream().filter(h -> kinds.get(h).section() == sec)
                    .sorted(java.util.Comparator.comparing((Host h) -> kinds.get(h).ordinal()).thenComparing(h -> Integer.toUnsignedLong(toInt(h.ip))))
                    .toList();
            if (in.isEmpty()) {
                continue;
            }
            List<List<String>> rows = new ArrayList<>();
            for (Host h : in) {
                List<String> r = new ArrayList<>(cells.get(h));
                r.add(details(h, detailWidth));
                rows.add(r);
            }
            String title = NetFormat.bold(I18n.t("scan.section." + sec.name().toLowerCase(Locale.ROOT)).toUpperCase(Locale.ROOT))
                    + NetFormat.dim(" · " + in.size());
            groups.add(new NetPrinter.Group(title, rows));
        }
        NetPrinter.groupedTable(out, headers, groups);

        // tổng hợp: thanh ngang theo số lượng
        Map<Kind, Integer> counts = new LinkedHashMap<>();
        kinds.values().forEach(k -> counts.merge(k, 1, Integer::sum));
        int max = counts.values().stream().max(Integer::compare).orElse(1);
        int labelWidth = counts.keySet().stream().mapToInt(k -> NetPrinter.width(k.label())).max().orElse(0);
        out.println();
        out.println(NetFormat.bold(I18n.t("scan.summary")) + NetFormat.dim(" · " + I18n.t("scan.info.devices", hosts.size())));
        counts.entrySet().stream().sorted((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()))
                .forEach(e -> {
                    Kind k = e.getKey();
                    int len = Math.max(1, Math.round(e.getValue() * 28f / max));
                    String label = k.label() + " ".repeat(labelWidth - NetPrinter.width(k.label()));
                    out.println("  " + label + "  " + k.color.apply("█".repeat(len)) + " " + NetFormat.bold(String.valueOf(e.getValue())));
                });
        out.println();
        for (String note : I18n.t("scan.legend").split(" · ")) {
            out.println(NetFormat.dim("  • " + note));
        }
    }

    private static String infoLine(String key, String value) {
        String label = I18n.t(key);
        return NetFormat.dim(label + " ".repeat(Math.max(1, 10 - NetPrinter.width(label)))) + value;
    }

    /** Tên chính (đậm), mọi tên khác kèm nguồn, rồi loại. */
    private static String device(Host h, Kind kind) {
        Map<String, String> names = h.names();
        List<String> lines = new ArrayList<>();
        if (names.isEmpty()) {
            lines.add(NetFormat.dim(I18n.t("scan.noName")));
        }
        boolean first = true;
        for (var e : names.entrySet()) {
            if (first) {
                wrap(e.getValue(), 34).forEach(l -> lines.add(NetFormat.bold(l)));
            } else {
                wrap(e.getValue(), 34 - e.getKey().length() - 1).forEach(l -> lines.add(NetFormat.dim(e.getKey()) + " " + l));
            }
            first = false;
        }
        lines.add(kind.color.apply(kind.label()));
        return String.join("\n", lines);
    }

    private static String vendor(Host h) {
        List<String> lines = wrap(h.vendor, 30);
        String text = String.join("\n", lines.stream().map(l -> h.randomMac ? NetFormat.dim(l) : l).toList());
        return text + "\n" + NetFormat.dim(h.mac);
    }

    private static String os(Host h) {
        List<String> lines = new ArrayList<>();
        if (h.os() == null) {
            lines.add(NetFormat.dim(I18n.t("scan.noPing")));
        } else {
            String ms = String.format(Locale.ROOT, "%.0f ms", h.rtt);
            ms = h.rtt < 20 ? NetFormat.green(ms) : h.rtt < 100 ? NetFormat.yellow(ms) : NetFormat.red(ms);
            lines.add(I18n.t("scan.os." + h.os()));
            lines.add(ms + NetFormat.dim(" · ttl " + h.ttl));
        }
        return String.join("\n", lines);
    }

    /** Cổng mở, tối đa 2 cổng 1 dòng. */
    private static String ports(Host h) {
        if (h.ports.isEmpty()) {
            return NetFormat.dim("—");
        }
        List<String> lines = new ArrayList<>();
        int perLine = 2;
        for (int i = 0; i < h.ports.size(); i += perLine) {
            List<String> pair = new ArrayList<>();
            for (int port : h.ports.subList(i, Math.min(i + perLine, h.ports.size()))) {
                String n = LanProbes.PORTS.get(port);
                pair.add((LanProbes.RISKY_PORTS.contains(port) ? NetFormat.red(n) : NetFormat.green(n)) + NetFormat.dim(":" + port));
            }
            lines.add(String.join(" ", pair));
        }
        return String.join("\n", lines);
    }

    private static String details(Host h, int width) {
        List<String> lines = new ArrayList<>();
        if (h.smb != null) {
            List<String> bits = new ArrayList<>(List.of(h.smb.os()));
            if (h.smb.dnsDomain() != null && !h.smb.dnsDomain().equalsIgnoreCase(h.smb.dnsName())) {
                bits.add("domain " + h.smb.dnsDomain());
            }
            labeled(lines, NetFormat.blue("SMB"), String.join(" · ", bits), width);
        }
        LanProbes.NetBios nb = h.netbios;
        if (nb != null && (nb.workgroup() != null || nb.fileShare())) {
            List<String> bits = new ArrayList<>();
            if (nb.workgroup() != null) {
                bits.add(nb.workgroup());
            }
            if (nb.fileShare()) {
                bits.add(I18n.t("scan.fileShare"));
            }
            if (nb.user() != null) {
                bits.add("user " + nb.user());
            }
            labeled(lines, NetFormat.blue("NetBIOS"), String.join(" · ", bits), width);
        }
        if (h.mdns != null) {
            List<String> svcs = h.mdns.services.stream().sorted().toList();
            if (!svcs.isEmpty() || h.mdns.model != null) {
                labeled(lines, NetFormat.magenta("mDNS"), (h.mdns.model == null ? "" : h.mdns.model + " ") + String.join(", ", svcs), width);
            }
            if (!h.mdns.txt.isEmpty()) {
                labeled(lines, " ".repeat(4), h.mdns.txt.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining(" ")), width);
            }
        }
        if (!h.upnp.isEmpty()) {
            List<String> desc = new ArrayList<>();
            for (String k : List.of("friendlyName", "manufacturer", "modelName")) {
                if (h.upnp.containsKey(k)) {
                    desc.add(h.upnp.get(k));
                }
            }
            if (h.upnp.containsKey("server")) {
                desc.add(h.upnp.get("server"));
            }
            h.upnp.forEach((k, v) -> {
                if (!List.of("friendlyName", "manufacturer", "modelName", "server").contains(k)) {
                    desc.add(k + "=" + v);
                }
            });
            labeled(lines, NetFormat.yellow("UPnP"), String.join(" / ", desc), width);
        }
        if (h.ssh != null) {
            labeled(lines, NetFormat.green("SSH"), h.ssh.replaceFirst("^SSH-[\\d.]+-", ""), width);
        }
        for (LanProbes.Web w : h.web) {
            List<String> bits = new ArrayList<>();
            if (!w.title().isEmpty()) {
                bits.add("\"" + w.title() + "\"");
            }
            if (!w.server().isEmpty()) {
                bits.add(w.server());
            }
            if (!w.realm().isEmpty()) {
                bits.add("realm \"" + w.realm() + "\"");
            }
            labeled(lines, NetFormat.green("Web"), ":" + w.port() + " " + String.join(" ", bits), width);
        }
        if (h.rtsp != null) {
            labeled(lines, NetFormat.green("RTSP"), h.rtsp, width);
        }
        if (h.snmp != null) {
            if (h.snmp.name() != null && !h.snmp.name().isBlank()) {
                labeled(lines, NetFormat.blue("SNMP"), h.snmp.name(), width);
            }
            if (h.snmp.descr() != null && !h.snmp.descr().isBlank()) {
                labeled(lines, h.snmp.name() != null && !h.snmp.name().isBlank() ? " ".repeat(4) : NetFormat.blue("SNMP"),
                        h.snmp.descr(), width);
            }
        }
        if (h.wsd != null) {
            List<String> bits = new ArrayList<>();
            for (String b : new String[]{h.wsd.name, h.wsd.manufacturer, h.wsd.model, wsdType(h.wsd.type)}) {
                if (b != null && !b.isBlank()) {
                    bits.add(b);
                }
            }
            if (!bits.isEmpty()) {
                labeled(lines, NetFormat.blue("WSD"), String.join(" · ", bits), width);
            }
        }
        if (!h.ipv6.isEmpty()) {
            labeled(lines, NetFormat.dim("IPv6"), String.join(" ", h.ipv6), width);
        }
        if (h.cert != null) {
            LanProbes.Cert c = h.cert;
            String who = certFields(c.subject(), true);
            if (!c.names().isEmpty()) {
                who += " · SAN " + String.join(", ", c.names());
            }
            String state = c.expired() ? NetFormat.red(I18n.t("scan.cert.expired", c.notAfter())) : I18n.t("scan.cert.until", c.notAfter());
            if (c.selfSigned()) {
                state += " · " + I18n.t("scan.cert.selfSigned");
            } else {
                state += " · " + I18n.t("scan.cert.issuer", certFields(c.issuer(), false));
            }
            labeled(lines, NetFormat.yellow("TLS"), who, width);
            labeled(lines, " ".repeat(3), state, width);
        }
        return lines.isEmpty() ? NetFormat.dim("—") : String.join("\n", lines);
    }

    /** Gọn phần đuôi loại WSD: "wsdp:Device pub:Computer" → "Computer"; rỗng nếu chỉ là Device chung chung. */
    static String wsdType(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String best = "";
        for (String t : type.split("\\s+")) {
            String tail = t.contains(":") ? t.substring(t.lastIndexOf(':') + 1) : t;
            if (!tail.isEmpty() && !tail.equalsIgnoreCase("Device")) {
                best = tail;
            }
        }
        return best;
    }

    /** "CN=x,O=y,C=VN" → "x · y · VN"; {@code all}: thêm OU/L/ST/C. */
    static String certFields(String dn, boolean all) {
        List<String> keep = all ? List.of("CN", "O", "OU", "L", "ST", "C") : List.of("CN", "O");
        Map<String, String> f = new LinkedHashMap<>();
        for (String part : dn.split(",(?=[A-Za-z0-9.]+=)")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                f.putIfAbsent(part.substring(0, eq).strip().toUpperCase(Locale.ROOT), part.substring(eq + 1).strip());
            }
        }
        List<String> out = keep.stream().filter(f::containsKey).map(f::get).toList();
        return out.isEmpty() ? dn : String.join(" · ", out);
    }

    /** "NHÃN nội dung", nội dung dài thì xuống dòng (thụt vào cho thẳng sau nhãn). */
    private static void labeled(List<String> lines, String label, String text, int width) {
        int indent = NetPrinter.width(label) + 1;
        List<String> wrapped = wrap(text.strip(), width - indent);
        for (int i = 0; i < wrapped.size(); i++) {
            lines.add((i == 0 ? label + " " : " ".repeat(indent)) + wrapped.get(i));
        }
    }

    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            while (word.length() > width) {
                if (!line.isEmpty()) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                out.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            line.append(line.isEmpty() ? "" : " ").append(word);
        }
        if (!line.isEmpty() || out.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    private static volatile String statusText;
    private static Thread spinner;

    /** Dòng trạng thái trên stderr: terminal thì có spinner + đồng hồ chạy, ghi đè tại chỗ; null = xoá. */
    private static synchronized void status(String text) {
        if (!NetFormat.color) {
            if (text != null) {
                System.err.println(text);
            }
            return;
        }
        statusText = text;
        if (text == null && spinner != null) {
            spinner.interrupt();
            try {
                spinner.join(500);
            } catch (InterruptedException ignored) {
            }
            spinner = null;
            System.err.print("\r\u001b[K");
            System.err.flush();
        } else if (text != null && spinner == null) {
            long t0 = System.nanoTime();
            spinner = Thread.ofVirtual().start(() -> {
                String frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";
                for (int i = 0; !Thread.currentThread().isInterrupted(); i++) {
                    String s = statusText;
                    if (s != null) {
                        String secs = String.format(Locale.ROOT, "%.1fs", (System.nanoTime() - t0) / 1e9);
                        System.err.print("\r\u001b[K" + NetFormat.blue(String.valueOf(frames.charAt(i % frames.length())))
                                + " " + s + "… " + NetFormat.dim(secs));
                        System.err.flush();
                    }
                    try {
                        Thread.sleep(90);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        }
    }

    static int toInt(String ip) {
        int v = 0;
        for (String o : ip.split("\\.")) {
            v = v << 8 | Integer.parseInt(o);
        }
        return v;
    }

    static String toIp(int v) {
        return (v >>> 24) + "." + (v >>> 16 & 0xFF) + "." + (v >>> 8 & 0xFF) + "." + (v & 0xFF);
    }
}
