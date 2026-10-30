package com.sqlctx.net;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Các cách hỏi 1 thiết bị trong LAN "mày là ai" mà không cần root: ping (TTL đoán hệ điều hành), bảng ARP (MAC → hãng
 * qua danh sách OUI), NetBIOS NBSTAT (tên máy Windows), mDNS (tên .local, dịch vụ Bonjour, model máy Apple), SSDP/UPnP
 * (tên, hãng, model TV/camera/router), vài cổng hay gặp và tiêu đề trang web. Tự nói giao thức, không cần nmblookup/avahi.
 */
final class LanProbes {

    /** Cổng hay gặp → tên ngắn. Cổng nào mở cũng là manh mối loại thiết bị (62078: iPhone, 4370: máy chấm công...). */
    static final Map<Integer, String> PORTS = linked(
            22, "ssh", 23, "telnet", 53, "dns", 80, "http", 139, "netbios", 443, "https", 445, "smb", 548, "afp",
            554, "rtsp", 631, "ipp", 1883, "mqtt", 3389, "rdp", 4370, "zkteco", 5000, "upnp/nas", 5555, "adb",
            7000, "airplay", 8000, "hik-sdk", 8008, "googlecast", 8080, "http-alt", 8443, "https-alt",
            9100, "printer", 37777, "dahua", 49152, "upnp", 62078, "iphone-sync");
    /** Cổng dễ bị tấn công nếu lộ ra: in đỏ. */
    static final Set<Integer> RISKY_PORTS = Set.of(23, 445, 1883, 3389, 5555);

    private static final List<Path> OUI_FILES = List.of(Path.of("/usr/share/hwdata/oui.txt"),
            Path.of("/usr/share/ieee-data/oui.txt"), Path.of("/usr/share/misc/oui.txt"));
    private static final Pattern OUI_LINE = Pattern.compile("^([0-9A-F]{2})-([0-9A-F]{2})-([0-9A-F]{2})\\s+\\(hex\\)\\s+(.+)$");
    private static final Pattern PING = Pattern.compile("ttl=(\\d+) time=([\\d.]+)");
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private LanProbes() {
    }

    private static Map<Integer, String> linked(Object... kv) {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((Integer) kv[i], (String) kv[i + 1]);
        }
        return m;
    }

    // ---------------------------------------------------------------- ping, ARP, OUI

    /** {ttl, rtt ms} hoặc null nếu không trả lời (Windows mặc định chặn ping). */
    static double[] ping(String ip) {
        Matcher m = PING.matcher(run(List.of("ping", "-c1", "-W1", "-n", ip), 3));
        return m.find() ? new double[]{Integer.parseInt(m.group(1)), Double.parseDouble(m.group(2))} : null;
    }

    /** IP → MAC từ bảng hàng xóm của kernel (sau khi ping, mọi máy đang bật trong LAN đều có ở đây kể cả máy chặn ping). */
    static Map<String, String> arpTable(String iface) {
        Map<String, String> hosts = new HashMap<>();
        for (String line : run(List.of("ip", "-4", "neigh", "show", "dev", iface), 3).split("\n")) {
            String[] p = line.trim().split("\\s+");
            int at = List.of(p).indexOf("lladdr");
            if (at > 0 && at + 1 < p.length && !line.contains("FAILED")) {
                hosts.put(p[0], p[at + 1].toLowerCase(Locale.ROOT));
            }
        }
        return hosts;
    }

    /** "aa:bb:cc" → hãng, đọc từ danh sách OUI của IEEE có sẵn trong hệ thống (gói hwdata / ieee-data). */
    static Map<String, String> loadOui() {
        for (Path f : OUI_FILES) {
            try {
                Map<String, String> db = new HashMap<>();
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    Matcher m = OUI_LINE.matcher(line);
                    if (m.matches()) {
                        db.put((m.group(1) + ":" + m.group(2) + ":" + m.group(3)).toLowerCase(Locale.ROOT), m.group(4).strip());
                    }
                }
                return db;
            } catch (IOException ignored) {
            }
        }
        return Map.of();
    }

    /** Bit "locally administered" bật: MAC ngẫu nhiên (iOS/Android/Windows bật "địa chỉ riêng tư"), không tra được hãng. */
    static boolean randomMac(String mac) {
        return (Integer.parseInt(mac.substring(0, 2), 16) & 2) != 0;
    }

    // ---------------------------------------------------------------- NetBIOS

    record NetBios(String name, String workgroup, String user, boolean fileShare) {
    }

    /** Hỏi NBSTAT (UDP 137, tên "*"): máy Windows (và Samba) trả danh sách tên đã đăng ký. */
    static NetBios netbios(String ip) {
        byte[] q = new byte[50];
        q[0] = 0x13;
        q[1] = 0x37;
        q[5] = 1; // 1 câu hỏi
        q[12] = 0x20;
        byte[] name = new byte[16];
        name[0] = '*';
        for (int i = 0; i < 16; i++) {
            q[13 + 2 * i] = (byte) ('A' + ((name[i] >> 4) & 0xF));
            q[14 + 2 * i] = (byte) ('A' + (name[i] & 0xF));
        }
        q[47] = 0x21; // NBSTAT
        q[49] = 1;    // IN
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(1500);
            byte[] buf = new byte[2048];
            for (int attempt = 0; attempt < 2; attempt++) { // Windows hay lỡ gói đầu
                s.send(new DatagramPacket(q, q.length, InetAddress.getByName(ip), 137));
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    return parseNbstat(java.util.Arrays.copyOf(buf, p.getLength()));
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** Trả lời NBSTAT: header 12 + tên 34 + type/class/ttl/len 10, rồi số tên và mỗi tên 18 byte (15 chữ, loại, cờ). */
    static NetBios parseNbstat(byte[] r) {
        if (r.length < 57) {
            return null;
        }
        int count = r[56] & 0xFF;
        String name = null;
        String group = null;
        String user = null;
        boolean share = false;
        for (int i = 0, off = 57; i < count && off + 18 <= r.length; i++, off += 18) {
            String n = new String(r, off, 15, StandardCharsets.ISO_8859_1).strip();
            int suffix = r[off + 15] & 0xFF;
            boolean isGroup = (r[off + 16] & 0x80) != 0;
            if (suffix == 0x00 && !isGroup && name == null) {
                name = n;
            } else if (suffix == 0x00 && isGroup && group == null) {
                group = n;
            } else if (suffix == 0x20) {
                share = true;
            } else if (suffix == 0x03 && !isGroup && !n.equals(name)) {
                user = n;
            }
        }
        return name == null && group == null ? null : new NetBios(name, group, user, share);
    }

    // ---------------------------------------------------------------- mDNS

    static final class Mdns {
        String hostname;
        String instance;
        String model;
        final Set<String> services = new LinkedHashSet<>();
        /** Mọi cặp key=value trong bản ghi TXT (model, hãng, phiên bản, vị trí máy in...). */
        final Map<String, String> txt = new LinkedHashMap<>();
    }

    /**
     * Hỏi danh sách dịch vụ, {@code _device-info} (model máy Apple) và tên ngược (PTR in-addr.arpa); đồng thời nghe thụ
     * động mọi gói quảng bá mDNS của cả mạng để thấy hostname (bản ghi A/SRV) của máy không trả lời mình.
     * <p>
     * Hai socket tách nhau: 1 socket ephemeral hỏi chủ động và nhận trả lời unicast (chắc chắn, avahi không đụng tới);
     * 1 socket multicast tham gia nhóm 224.0.0.251:5353 chỉ để nghe gói các máy phát cho nhau. Nếu gộp vào 1 socket bind
     * cổng 5353 chung avahi thì kernel chia tải gói unicast, mất bớt trả lời - nên phải tách. Gom theo IP gửi.
     */
    static Map<String, Mdns> mdns(List<String> ips, int waitMs, String iface) {
        Map<String, Mdns> res = new ConcurrentHashMap<>();
        List<String[]> common = List.of(new String[]{"_services._dns-sd._udp.local", "12"},
                new String[]{"_device-info._tcp.local", "12"});
        Thread passive = Thread.ofVirtual().start(() -> listenPassiveMdns(res, iface, waitMs));
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(200);
            InetAddress group = InetAddress.getByName("224.0.0.251");
            byte[] q = dnsQuery(common);
            s.send(new DatagramPacket(q, q.length, group, 5353));
            for (String ip : ips) {
                List<String[]> qs = new ArrayList<>(common);
                qs.addFirst(new String[]{reverseName(ip), "12"});
                byte[] uq = dnsQuery(qs);
                try {
                    s.send(new DatagramPacket(uq, uq.length, InetAddress.getByName(ip), 5353));
                } catch (IOException ignored) {
                }
            }
            collectMdns(s, res, waitMs);
        } catch (IOException ignored) {
        }
        try {
            passive.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return res;
    }

    /** Socket multicast riêng, tham gia nhóm 5353 để nghe thụ động quảng bá của cả mạng; lặng lẽ bỏ qua nếu không mở được. */
    private static void listenPassiveMdns(Map<String, Mdns> res, String iface, int waitMs) {
        try (java.net.MulticastSocket m = new java.net.MulticastSocket(null)) {
            m.setReuseAddress(true);
            m.bind(new InetSocketAddress(5353));
            java.net.NetworkInterface nif = iface == null ? null : java.net.NetworkInterface.getByName(iface);
            m.joinGroup(new InetSocketAddress(InetAddress.getByName("224.0.0.251"), 5353), nif);
            m.setSoTimeout(200);
            collectMdns(m, res, waitMs);
        } catch (IOException ignored) {
        }
    }

    /** Đọc gói mDNS tới khi hết {@code waitMs}, gộp vào {@code res} theo IP gửi. */
    private static void collectMdns(DatagramSocket s, Map<String, Mdns> res, int waitMs) throws IOException {
        long end = System.currentTimeMillis() + waitMs;
        byte[] buf = new byte[9000];
        while (System.currentTimeMillis() < end) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            Mdns d = res.computeIfAbsent(p.getAddress().getHostAddress(), k -> new Mdns());
            synchronized (d) {
                try {
                    parseMdns(java.util.Arrays.copyOf(buf, p.getLength()), d);
                } catch (RuntimeException ignored) { // gói hỏng / lạ: bỏ qua
                }
            }
        }
    }

    static String reverseName(String ip) {
        String[] o = ip.split("\\.");
        return o[3] + "." + o[2] + "." + o[1] + "." + o[0] + ".in-addr.arpa";
    }

    static byte[] dnsQuery(List<String[]> questions) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.writeBytes(new byte[]{0, 0, 0, 0, 0, (byte) questions.size(), 0, 0, 0, 0, 0, 0});
        for (String[] q : questions) {
            for (String label : q[0].split("\\.")) {
                byte[] l = label.getBytes(StandardCharsets.UTF_8);
                b.write(l.length);
                b.writeBytes(l);
            }
            int type = Integer.parseInt(q[1]);
            b.writeBytes(new byte[]{0, (byte) (type >> 8), (byte) type, (byte) 0x80, 1}); // class IN + bit QU
        }
        return b.toByteArray();
    }

    /** Đọc các bản ghi PTR/TXT của 1 gói mDNS vào {@code d}. */
    static void parseMdns(byte[] buf, Mdns d) {
        int qd = u16(buf, 4);
        int records = u16(buf, 6) + u16(buf, 8) + u16(buf, 10);
        int[] off = {12};
        for (int i = 0; i < qd; i++) {
            name(buf, off);
            off[0] += 4;
        }
        for (int i = 0; i < records; i++) {
            String name = name(buf, off);
            int type = u16(buf, off[0]);
            int len = u16(buf, off[0] + 8);
            int data = off[0] + 10;
            off[0] = data + len;
            if (type == 12) {
                String val = name(buf, new int[]{data});
                if (name.endsWith(".in-addr.arpa")) {
                    d.hostname = strip(val);
                } else if (name.equals("_services._dns-sd._udp.local")) {
                    d.services.add(strip(val));
                } else if (val.contains("._tcp.local") && d.instance == null) {
                    d.instance = val.substring(0, val.indexOf("._"));
                }
            } else if ((type == 1 || type == 28) && name.endsWith(".local") && d.hostname == null) {
                d.hostname = strip(name); // bản ghi A/AAAA: tên máy tự phát (bắt được qua nghe thụ động)
            } else if (type == 33) {       // SRV: đích là hostname của máy chạy dịch vụ
                String target = name(buf, new int[]{data + 6});
                if (d.hostname == null && target.endsWith(".local")) {
                    d.hostname = strip(target);
                }
                if (d.instance == null && name.contains("._")) {
                    d.instance = name.substring(0, name.indexOf("._"));
                }
            } else if (type == 16) {
                for (int p = data; p < data + len && p < buf.length; p += (buf[p] & 0xFF) + 1) {
                    int l = buf[p] & 0xFF;
                    if (l == 0) {
                        continue;
                    }
                    String kv = new String(buf, p + 1, Math.min(l, buf.length - p - 1), StandardCharsets.UTF_8);
                    int eq = kv.indexOf('=');
                    if (eq > 0 && !kv.substring(eq + 1).isBlank()) {
                        d.txt.putIfAbsent(kv.substring(0, eq), kv.substring(eq + 1).strip());
                    }
                }
                // model máy ghi dưới nhiều khoá: Apple "model", Chromecast "md", máy in "ty"
                for (String k : List.of("model", "md", "ty")) {
                    if (d.model == null && d.txt.get(k) != null) {
                        d.model = d.txt.get(k);
                    }
                }
            }
        }
    }

    private static String strip(String name) {
        return name.endsWith(".local") ? name.substring(0, name.length() - 6) : name;
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) << 8 | b[i + 1] & 0xFF;
    }

    /** Tên DNS (có nén con trỏ 0xC0); {@code off[0]} tiến tới sau tên. */
    private static String name(byte[] buf, int[] off) {
        StringBuilder sb = new StringBuilder();
        int p = off[0];
        boolean jumped = false;
        for (int hops = 0; hops < 64; hops++) {
            int n = buf[p] & 0xFF;
            if ((n & 0xC0) == 0xC0) {
                if (!jumped) {
                    off[0] = p + 2;
                }
                jumped = true;
                p = (n & 0x3F) << 8 | buf[p + 1] & 0xFF;
                continue;
            }
            if (n == 0) {
                if (!jumped) {
                    off[0] = p + 1;
                }
                break;
            }
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(new String(buf, p + 1, n, StandardCharsets.UTF_8));
            p += n + 1;
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- SSDP / UPnP

    /** IP → {location, server} của các thiết bị trả lời M-SEARCH ssdp:all. */
    static Map<String, Map<String, String>> ssdp(int waitMs) {
        Map<String, Map<String, String>> res = new HashMap<>();
        byte[] msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                + "ST: ssdp:all\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(200);
            for (int i = 0; i < 2; i++) {
                s.send(new DatagramPacket(msg, msg.length, InetAddress.getByName("239.255.255.250"), 1900));
            }
            long end = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Map<String, String> d = res.computeIfAbsent(p.getAddress().getHostAddress(), k -> new HashMap<>());
                for (String line : new String(buf, 0, p.getLength(), StandardCharsets.UTF_8).split("\r?\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                        if (key.equals("location") || key.equals("server")) {
                            d.putIfAbsent(key, line.substring(colon + 1).strip());
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return res;
    }

    /** Đọc file mô tả UPnP (XML ở LOCATION): mọi thẻ nhận dạng thiết bị, kể cả trang quản trị (presentationURL). */
    static Map<String, String> upnpDescription(String location) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] got = httpGet(location, 65536);
        if (got == null) {
            return out;
        }
        String xml = got[1];
        for (String tag : List.of("friendlyName", "manufacturer", "manufacturerURL", "modelName", "modelNumber",
                "modelDescription", "serialNumber", "deviceType", "presentationURL", "UDN")) {
            Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
            if (m.find() && !m.group(1).isBlank()) {
                out.put(tag, m.group(1).strip());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- SSH, TLS, SMB

    /** Dòng chào của SSH server, vd "SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.5" (cho biết cả bản phân phối Linux). */
    static String sshBanner(String ip) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, 22), 1500);
            s.setSoTimeout(2500);
            // đọc tới hết dòng (banner chỉ ~40 byte, server đứng chờ client chào lại - đừng đợi đủ N byte)
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            for (int i = 0; i < 5; i++) { // RFC 4253: server được in vài dòng trước dòng SSH-
                String line = r.readLine();
                if (line == null) {
                    break;
                }
                if (line.startsWith("SSH-")) {
                    return line.strip();
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    record Cert(String subject, String issuer, List<String> names, String notAfter, boolean selfSigned, boolean expired) {
    }

    /** Chứng chỉ HTTPS của thiết bị (camera, router, NAS hay ghi model/hãng trong đó). */
    static Cert tlsCert(String ip, int port) {
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(ip, port), 1500);
            raw.setSoTimeout(2500);
            try (javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) TRUST_ALL.getSocketFactory().createSocket(raw, ip, port, false)) {
                s.startHandshake();
                X509Certificate c = (X509Certificate) s.getSession().getPeerCertificates()[0];
                List<String> names = new ArrayList<>();
                if (c.getSubjectAlternativeNames() != null) {
                    c.getSubjectAlternativeNames().forEach(san -> names.add(String.valueOf(san.get(1))));
                }
                String subject = c.getSubjectX500Principal().getName(javax.security.auth.x500.X500Principal.RFC2253);
                String issuer = c.getIssuerX500Principal().getName(javax.security.auth.x500.X500Principal.RFC2253);
                return new Cert(subject, issuer, names, c.getNotAfter().toInstant().toString().substring(0, 10),
                        subject.equals(issuer), c.getNotAfter().before(new java.util.Date()));
            }
        } catch (Exception e) {
            return null;
        }
    }

    record Smb(int major, int minor, int build, String netbiosName, String netbiosDomain, String dnsName, String dnsDomain) {

        /** "Windows 11 23H2 (10.0.22631)"; Samba báo build 0. */
        String os() {
            String v = major + "." + minor + "." + build;
            if (build == 0) {
                return "Samba/SMB " + v;
            }
            String name = WINDOWS_BUILDS.get(build);
            if (name == null) {
                name = major == 10 ? (build >= 22000 ? "Windows 11" : "Windows 10") : major == 6 && minor == 1 ? "Windows 7"
                        : major == 6 && minor == 3 ? "Windows 8.1" : "Windows";
            }
            return name + " (" + v + ")";
        }
    }

    private static final Map<Integer, String> WINDOWS_BUILDS = Map.ofEntries(
            Map.entry(10240, "Windows 10 1507"), Map.entry(14393, "Windows 10 1607 / Server 2016"),
            Map.entry(17763, "Windows 10 1809 / Server 2019"), Map.entry(19041, "Windows 10 2004"),
            Map.entry(19042, "Windows 10 20H2"), Map.entry(19043, "Windows 10 21H1"), Map.entry(19044, "Windows 10 21H2"),
            Map.entry(19045, "Windows 10 22H2"), Map.entry(20348, "Windows Server 2022"), Map.entry(22000, "Windows 11 21H2"),
            Map.entry(22621, "Windows 11 22H2"), Map.entry(22631, "Windows 11 23H2"), Map.entry(26100, "Windows 11 24H2 / Server 2025"),
            Map.entry(26200, "Windows 11 25H2"));

    /**
     * Hỏi SMB2 không đăng nhập (như nmap smb-os-discovery): NEGOTIATE rồi SESSION_SETUP mang NTLMSSP NEGOTIATE; server
     * trả NTLMSSP CHALLENGE chứa phiên bản Windows và tên máy/domain - có được cả khi máy đã tắt NetBIOS.
     */
    static Smb smb(String ip) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, 445), 1500);
            s.setSoTimeout(3000);
            java.io.OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            byte[] dialects = {0x02, 0x02, 0x10, 0x02, 0x00, 0x03, 0x02, 0x03}; // 2.0.2, 2.1, 3.0, 3.0.2
            java.io.ByteArrayOutputStream neg = new java.io.ByteArrayOutputStream();
            neg.writeBytes(le16(36));
            neg.writeBytes(le16(4));
            neg.writeBytes(le16(1));               // signing enabled
            neg.writeBytes(new byte[2 + 4 + 16 + 8]); // reserved, capabilities, client guid, start time
            neg.writeBytes(dialects);
            send(out, smb2Header(0, 0), neg.toByteArray());
            readSmb(in);

            byte[] token = spnego(ntlmNegotiate());
            java.io.ByteArrayOutputStream setup = new java.io.ByteArrayOutputStream();
            setup.writeBytes(le16(25));
            setup.write(0);                          // flags
            setup.write(1);                          // signing enabled
            setup.writeBytes(new byte[4 + 4]);       // capabilities, channel
            setup.writeBytes(le16(64 + 24));         // security buffer offset
            setup.writeBytes(le16(token.length));
            setup.writeBytes(new byte[8]);           // previous session id
            setup.writeBytes(token);
            send(out, smb2Header(1, 1), setup.toByteArray());
            return parseNtlmChallenge(readSmb(in));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] smb2Header(int command, long messageId) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{(byte) 0xFE, 'S', 'M', 'B'}).putShort((short) 64).putShort((short) 0).putInt(0)
                .putShort((short) command).putShort((short) 1).putInt(0).putInt(0).putLong(messageId);
        return b.array();
    }

    private static void send(java.io.OutputStream out, byte[] header, byte[] body) throws IOException {
        int len = header.length + body.length;
        out.write(new byte[]{0, (byte) (len >> 16), (byte) (len >> 8), (byte) len});
        out.write(header);
        out.write(body);
        out.flush();
    }

    private static byte[] readSmb(InputStream in) throws IOException {
        byte[] h = in.readNBytes(4);
        if (h.length < 4) {
            throw new IOException("closed");
        }
        int len = (h[1] & 0xFF) << 16 | (h[2] & 0xFF) << 8 | h[3] & 0xFF;
        return in.readNBytes(Math.min(len, 1 << 16));
    }

    private static byte[] le16(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }

    private static byte[] ntlmNegotiate() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(40).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("NTLMSSP\0".getBytes(StandardCharsets.US_ASCII)).putInt(1).putInt(0xE2088297) // unicode, NTLM, target info, version...
                .put(new byte[16])                                                          // domain, workstation: trống
                .put(new byte[]{6, 1, (byte) 0xB1, 0x1D, 0, 0, 0, 0x0F});                   // version client
        return b.array();
    }

    /** Bọc NTLMSSP trong SPNEGO NegTokenInit (DER) như Windows client gửi. */
    private static byte[] spnego(byte[] ntlm) {
        byte[] ntlmOid = {0x06, 0x0A, 0x2B, 0x06, 0x01, 0x04, 0x01, (byte) 0x82, 0x37, 0x02, 0x02, 0x0A};
        byte[] mechTypes = der(0xA0, der(0x30, ntlmOid));
        byte[] mechToken = der(0xA2, der(0x04, ntlm));
        byte[] init = der(0xA0, der(0x30, concat(mechTypes, mechToken)));
        byte[] spnegoOid = {0x06, 0x06, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x02};
        return der(0x60, concat(spnegoOid, init));
    }

    private static byte[] der(int tag, byte[] content) {
        int n = content.length;
        byte[] len = n < 128 ? new byte[]{(byte) n} : n < 256 ? new byte[]{(byte) 0x81, (byte) n}
                : new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        return concat(new byte[]{(byte) tag}, len, content);
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) {
            b.writeBytes(p);
        }
        return b.toByteArray();
    }

    /** Tìm NTLMSSP CHALLENGE (loại 2) ở bất kỳ đâu trong gói (có bọc SPNEGO hay không), đọc version + AV pairs. */
    static Smb parseNtlmChallenge(byte[] r) {
        byte[] sig = "NTLMSSP\0".getBytes(StandardCharsets.US_ASCII);
        int at = -1;
        for (int i = 0; i + 56 <= r.length && at < 0; i++) {
            if (java.util.Arrays.equals(r, i, i + 8, sig, 0, 8) && le32(r, i + 8) == 2) {
                at = i;
            }
        }
        if (at < 0) {
            return null;
        }
        int infoLen = le16(r, at + 40);
        int infoOff = at + le32(r, at + 44);
        String[] av = new String[6];
        for (int p = infoOff; p + 4 <= Math.min(r.length, infoOff + infoLen); ) {
            int id = le16(r, p);
            int len = le16(r, p + 2);
            if (id == 0) {
                break;
            }
            if (id <= 5 && p + 4 + len <= r.length) {
                av[id] = new String(r, p + 4, len, StandardCharsets.UTF_16LE);
            }
            p += 4 + len;
        }
        return new Smb(r[at + 48] & 0xFF, r[at + 49] & 0xFF, le16(r, at + 50), av[1], av[2], av[3], av[4]);
    }

    private static int le16(byte[] b, int i) {
        return b[i] & 0xFF | (b[i + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] b, int i) {
        return le16(b, i) | le16(b, i + 2) << 16;
    }

    // ---------------------------------------------------------------- cổng, web

    /**
     * Bị từ chối (RST) là đóng chắc chắn; hết giờ thì có thể chỉ là Wi-Fi nghẽn làm rơi SYN - thử lại 1 lần lâu hơn
     * (trước đây 1 lần 1.5s: lúc mạng chậm iPhone mất cổng 62078, gateway mất cả 80/443).
     */
    static boolean portOpen(String ip, int port) {
        for (int timeout : new int[]{1500, 3000}) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, port), timeout);
                return true;
            } catch (java.net.SocketTimeoutException e) {
                // thử lại
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    /** 1 trang web: cổng, tiêu đề, header Server, "realm" của ô đăng nhập (camera/router hay ghi model ở đây). */
    record Web(int port, String title, String server, String realm) {
    }

    private static final List<Integer> WEB_PORTS = List.of(80, 81, 443, 631, 5000, 7001, 8000, 8080, 8081, 8443, 9000);
    private static final Pattern REALM = Pattern.compile("realm=\"?([^\",]+)", Pattern.CASE_INSENSITIVE);

    /** Mọi cổng web đang mở đều hỏi, mỗi cổng 1 dòng; rỗng nếu không cổng nào trả lời. */
    static List<Web> web(String ip, List<Integer> open) {
        List<Web> out = new ArrayList<>();
        for (int port : WEB_PORTS) {
            if (!open.contains(port)) {
                continue;
            }
            String scheme = port == 443 || port == 8443 ? "https" : "http";
            String[] r = httpGet(scheme + "://" + ip + ":" + port + "/", 32768);
            if (r == null) {
                continue;
            }
            Matcher m = TITLE.matcher(r[1]);
            String title = m.find() ? m.group(1).replaceAll("\\s+", " ").strip() : "";
            if (!title.isEmpty() || !r[0].isEmpty() || !r[2].isEmpty()) {
                out.add(new Web(port, title, r[0], r[2]));
            }
        }
        return out;
    }

    /** Camera RTSP (cổng 554/8554): hỏi OPTIONS, đọc header Server (hãng/model đầu ghi); "RTSP" nếu trả lời mà không khai. */
    static String rtsp(String ip, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), 1500);
            s.setSoTimeout(2000);
            s.getOutputStream().write(("OPTIONS rtsp://" + ip + ":" + port + "/ RTSP/1.0\r\nCSeq: 1\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            String resp = new String(s.getInputStream().readNBytes(1024), StandardCharsets.UTF_8);
            for (String line : resp.split("\r?\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("server:")) {
                    return line.substring(7).strip();
                }
            }
            return resp.startsWith("RTSP/") ? "RTSP" : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** {header Server, body, realm}; null nếu không kết nối được. HTTPS chấp nhận mọi chứng chỉ (thiết bị LAN toàn tự ký). */
    static String[] httpGet(String url, int maxBytes) {
        return httpGet(url, maxBytes, 2);
    }

    private static String[] httpGet(String url, int maxBytes, int redirects) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            if (c instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(TRUST_ALL.getSocketFactory());
                https.setHostnameVerifier((h, s) -> true);
            }
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setInstanceFollowRedirects(true);
            String server = c.getHeaderField("Server");
            String location = c.getHeaderField("Location");
            String auth = c.getHeaderField("WWW-Authenticate");
            String realm = "";
            if (auth != null) {
                Matcher rm = REALM.matcher(auth);
                realm = rm.find() ? rm.group(1).strip() : "";
            }
            // HttpURLConnection không tự theo chuyển hướng http → https (router hay làm vậy)
            if (c.getResponseCode() / 100 == 3 && location != null && redirects > 0) {
                c.disconnect();
                String[] next = httpGet(URI.create(url).resolve(location).toString(), maxBytes, redirects - 1);
                if (next != null) {
                    return new String[]{next[0].isEmpty() && server != null ? server : next[0], next[1],
                            next[2].isEmpty() && !realm.isEmpty() ? realm : next[2]};
                }
            }
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : new String(in.readNBytes(maxBytes), StandardCharsets.UTF_8);
            c.disconnect();
            return new String[]{server == null ? "" : server, body, realm};
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- SNMP

    /** sysDescr (mô tả thiết bị, thường có hãng + firmware) và sysName (tên quản trị). */
    record Snmp(String descr, String name) {
    }

    private static final byte[] OID_DESCR = {0x2B, 6, 1, 2, 1, 1, 1, 0}; // 1.3.6.1.2.1.1.1.0
    private static final byte[] OID_NAME = {0x2B, 6, 1, 2, 1, 1, 5, 0};  // 1.3.6.1.2.1.1.5.0

    /** Hỏi SNMPv1 GET (community "public") sysDescr.0 + sysName.0; null nếu không bật SNMP. */
    static Snmp snmp(String ip) {
        byte[] varbinds = concat(
                der(0x30, concat(der(0x06, OID_DESCR), der(0x05, new byte[0]))),
                der(0x30, concat(der(0x06, OID_NAME), der(0x05, new byte[0]))));
        byte[] pdu = der(0xA0, concat(der(0x02, new byte[]{0x2A}), der(0x02, new byte[]{0}), der(0x02, new byte[]{0}),
                der(0x30, varbinds)));
        byte[] msg = der(0x30, concat(der(0x02, new byte[]{0}), der(0x04, "public".getBytes(StandardCharsets.US_ASCII)), pdu));
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(1200);
            s.send(new DatagramPacket(msg, msg.length, InetAddress.getByName(ip), 161));
            byte[] buf = new byte[4096];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            Map<String, String> vals = new LinkedHashMap<>();
            berWalk(java.util.Arrays.copyOf(buf, p.getLength()), 0, p.getLength(), vals);
            String descr = vals.get("1.3.6.1.2.1.1.1.0");
            String name = vals.get("1.3.6.1.2.1.1.5.0");
            boolean has = descr != null && !descr.isBlank() || name != null && !name.isBlank();
            return has ? new Snmp(descr, name) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Duyệt cây BER, gom mọi cặp (OID → giá trị ngay sau nó) - đủ để đọc các varbind của SNMP. */
    static void berWalk(byte[] b, int start, int end, Map<String, String> out) {
        int p = start;
        while (p + 2 <= end) {
            int tag = b[p] & 0xFF;
            int[] lh = berLen(b, p + 1);
            int content = p + 1 + lh[1];
            int next = content + lh[0];
            if (lh[0] < 0 || next > end) {
                break;
            }
            if (tag == 0x30 || tag == 0xA0 || tag == 0xA2) {
                berWalk(b, content, next, out);
            } else if (tag == 0x06 && next + 2 <= end) {
                String oid = berOid(b, content, lh[0]);
                int vtag = b[next] & 0xFF;
                int[] vl = berLen(b, next + 1);
                int vc = next + 1 + vl[1];
                if (vl[0] >= 0 && vc + vl[0] <= end) {
                    out.put(oid, vtag == 0x06 ? berOid(b, vc, vl[0])
                            : new String(b, vc, vl[0], StandardCharsets.UTF_8));
                }
            }
            p = next;
        }
    }

    private static int[] berLen(byte[] b, int p) {
        if (p >= b.length) {
            return new int[]{-1, 1};
        }
        int n = b[p] & 0xFF;
        if ((n & 0x80) == 0) {
            return new int[]{n, 1};
        }
        int count = n & 0x7F;
        int v = 0;
        for (int i = 0; i < count && p + 1 + i < b.length; i++) {
            v = v << 8 | b[p + 1 + i] & 0xFF;
        }
        return new int[]{v, 1 + count};
    }

    private static String berOid(byte[] b, int c, int l) {
        if (l == 0) {
            return "";
        }
        int first = b[c] & 0xFF;
        StringBuilder sb = new StringBuilder().append(first / 40).append('.').append(first % 40);
        long v = 0;
        for (int i = c + 1; i < c + l; i++) {
            int x = b[i] & 0xFF;
            v = v << 7 | x & 0x7F;
            if ((x & 0x80) == 0) {
                sb.append('.').append(v);
                v = 0;
            }
        }
        return sb.toString();
    }

    private static final SSLContext TRUST_ALL = trustAll();

    private static SSLContext trustAll() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- WS-Discovery (WSD)

    /** Thiết bị WS-Discovery: loại (Computer/Printer...), địa chỉ metadata, rồi tên/hãng/model lấy từ metadata. */
    static final class WsdDevice {
        String type = "";
        String xaddr = "";
        String name;
        String manufacturer;
        String model;
    }

    private static final Pattern WSD_TYPES = Pattern.compile("<[^>]*Types[^>]*>(.*?)</[^>]*Types>", Pattern.DOTALL);
    private static final Pattern WSD_XADDRS = Pattern.compile("<[^>]*XAddrs[^>]*>(.*?)</[^>]*XAddrs>", Pattern.DOTALL);

    /**
     * Gửi Probe WS-Discovery (multicast 239.255.255.250:3702). Máy Windows và máy in trả lời kể cả khi chặn ping và tắt
     * NetBIOS - cho biết đây là máy tính / máy in và địa chỉ (XAddrs) để hỏi tiếp tên, hãng, model qua {@link #wsdGet}.
     */
    static Map<String, WsdDevice> wsdiscover(int waitMs, String iface) {
        Map<String, WsdDevice> res = new ConcurrentHashMap<>();
        String probe = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" "
                + "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\"><s:Header>"
                + "<a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>"
                + "<a:MessageID>urn:uuid:" + java.util.UUID.randomUUID() + "</a:MessageID>"
                + "<a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To></s:Header>"
                + "<s:Body><d:Probe/></s:Body></s:Envelope>";
        byte[] msg = probe.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(200);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int i = 0; i < 2; i++) {
                s.send(new DatagramPacket(msg, msg.length, group, 3702));
            }
            long end = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[8192];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String xml = new String(buf, 0, p.getLength(), StandardCharsets.UTF_8);
                if (!xml.contains("ProbeMatch")) {
                    continue;
                }
                WsdDevice d = res.computeIfAbsent(p.getAddress().getHostAddress(), k -> new WsdDevice());
                Matcher t = WSD_TYPES.matcher(xml);
                if (t.find()) {
                    d.type = t.group(1).replaceAll("\\s+", " ").strip();
                }
                Matcher x = WSD_XADDRS.matcher(xml);
                if (x.find() && d.xaddr.isEmpty()) {
                    d.xaddr = x.group(1).strip().split("\\s+")[0];
                }
            }
        } catch (IOException ignored) {
        }
        return res;
    }

    private static final Pattern WSD_NAME = Pattern.compile("<[^>]*FriendlyName>(.*?)</[^>]*FriendlyName>", Pattern.DOTALL);
    private static final Pattern WSD_MFR = Pattern.compile("<[^>]*Manufacturer>(.*?)</[^>]*Manufacturer>", Pattern.DOTALL);
    private static final Pattern WSD_MODEL = Pattern.compile("<[^>]*ModelName>(.*?)</[^>]*ModelName>", Pattern.DOTALL);

    /** Hỏi metadata WSD (WS-Transfer Get, POST tới XAddrs): tên máy (= tên Windows), hãng, model. Null nếu không có. */
    static void wsdGet(WsdDevice d) {
        if (d.xaddr.isEmpty() || !d.xaddr.startsWith("http")) {
            return;
        }
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\"><s:Header>"
                + "<a:Action>http://schemas.xmlsoap.org/ws/2004/09/transfer/Get</a:Action>"
                + "<a:MessageID>urn:uuid:" + java.util.UUID.randomUUID() + "</a:MessageID>"
                + "<a:To>" + d.xaddr + "</a:To>"
                + "<a:ReplyTo><a:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>"
                + "</s:Header><s:Body/></s:Envelope>";
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(d.xaddr).toURL().openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2500);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            String xml = in == null ? "" : new String(in.readNBytes(65536), StandardCharsets.UTF_8);
            c.disconnect();
            Matcher n = WSD_NAME.matcher(xml);
            if (n.find() && !n.group(1).isBlank()) {
                d.name = n.group(1).strip();
            }
            Matcher m = WSD_MFR.matcher(xml);
            if (m.find() && !m.group(1).isBlank()) {
                d.manufacturer = m.group(1).strip();
            }
            Matcher md = WSD_MODEL.matcher(xml);
            if (md.find() && !md.group(1).isBlank()) {
                d.model = md.group(1).strip();
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    // ---------------------------------------------------------------- IPv6

    /** MAC → danh sách địa chỉ IPv6 từ bảng hàng xóm của kernel (thiết bị thường có cả IPv6 link-local và global). */
    static Map<String, List<String>> ipv6Table(String iface) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String line : run(List.of("ip", "-6", "neigh", "show", "dev", iface), 3).split("\n")) {
            String[] p = line.trim().split("\\s+");
            int at = List.of(p).indexOf("lladdr");
            if (at > 0 && at + 1 < p.length && !line.contains("FAILED") && p[0].contains(":")) {
                out.computeIfAbsent(p[at + 1].toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(p[0]);
            }
        }
        return out;
    }

    // ----------------------------------------------------------------

    /** Chạy lệnh, trả stdout ("" nếu lỗi / quá {@code timeoutSec}). */
    static String run(List<String> cmd, int timeoutSec) {
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
