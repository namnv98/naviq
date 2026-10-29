package com.sqlctx.net;

import com.sqlctx.util.I18n;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gói "phát loa" cho cả mạng LAN (multicast/broadcast): SSDP, mDNS, LLMNR, NetBIOS, DHCP... Đọc nội dung SSDP/mDNS để
 * biết thiết bị nào đang quảng bá / tìm cái gì. Chữ để tiếng Việt, bản tiếng Anh trong i18n/texts_en.tsv.
 */
final class Lan {

    /** Cổng → "tên giao thức — giải thích". */
    static final Map<Integer, String> PROTOCOLS = Map.ofEntries(
            Map.entry(1900, "SSDP — dò thiết bị UPnP/DLNA (TV, router, Chromecast...)"),
            Map.entry(5353, "mDNS — dò thiết bị (máy in, AirPlay, .local)"),
            Map.entry(5355, "LLMNR — Windows hỏi tên máy trong LAN"),
            Map.entry(137, "NetBIOS — Windows hỏi tên máy"),
            Map.entry(138, "NetBIOS — Windows chia sẻ"),
            Map.entry(67, "DHCP — xin cấp IP"),
            Map.entry(68, "DHCP — cấp IP"),
            Map.entry(546, "DHCPv6 — cấp IPv6"),
            Map.entry(547, "DHCPv6 — xin cấp IPv6"),
            Map.entry(3702, "WS-Discovery — Windows/máy in dò thiết bị"),
            Map.entry(6343, "sFlow — thống kê traffic (Open vSwitch)"));

    private static final Map<String, String> SSDP_TYPES = Map.of(
            "MediaServer", "máy chủ media DLNA (chia sẻ phim/nhạc)",
            "MediaRenderer", "TV/loa nhận phát media",
            "InternetGatewayDevice", "router (cho phép app tự mở cổng)",
            "WANIPConnection", "router mở cổng (UPnP)",
            "WANPPPConnection", "router mở cổng (UPnP)",
            "dial", "cast lên TV (YouTube/Netflix)",
            "Basic", "thiết bị UPnP",
            "WFADevice", "Wi-Fi Direct",
            "PrinterBasic", "máy in");
    private static final Set<String> SSDP_SKIP = Set.of("ContentDirectory", "ConnectionManager",
            "X_MS_MediaReceiverRegistrar", "AVTransport", "RenderingControl", "Layer3Forwarding",
            "WANCommonInterfaceConfig", "WANDevice", "WANConnectionDevice");
    private static final Map<String, String> MDNS_TYPES = Map.ofEntries(
            Map.entry("_googlecast", "Chromecast/Google TV"),
            Map.entry("_airplay", "AirPlay (Apple TV/Mac)"),
            Map.entry("_raop", "loa AirPlay"),
            Map.entry("_companion-link", "thiết bị Apple (iPhone/iPad/Mac)"),
            Map.entry("_rdlink", "thiết bị Apple"),
            Map.entry("_asquic", "thiết bị Apple (AirDrop/Continuity)"),
            Map.entry("_appsvcprepair", "ghép nối thiết bị Apple"),
            Map.entry("_remotepairing", "ghép nối iPhone với Mac/Xcode"),
            Map.entry("_apple-mobdev2", "đồng bộ iPhone (Finder/iTunes)"),
            Map.entry("_ipp", "máy in"), Map.entry("_ipps", "máy in"), Map.entry("_printer", "máy in"),
            Map.entry("_pdl-datastream", "máy in"), Map.entry("_scanner", "máy scan"), Map.entry("_uscan", "máy scan"),
            Map.entry("_spotify-connect", "Spotify Connect"),
            Map.entry("_hap", "HomeKit"), Map.entry("_homekit", "HomeKit"),
            Map.entry("_smb", "chia sẻ file SMB"), Map.entry("_afpovertcp", "chia sẻ file Mac"),
            Map.entry("_ssh", "SSH"), Map.entry("_sftp-ssh", "SSH"),
            Map.entry("_matter", "Matter (nhà thông minh)"), Map.entry("_matterc", "Matter (nhà thông minh)"),
            Map.entry("_meshcop", "Thread (nhà thông minh)"),
            Map.entry("_adb", "ADB (debug Android qua Wi-Fi)"),
            Map.entry("_adb-tls-connect", "ADB (debug Android qua Wi-Fi)"),
            Map.entry("_adb-tls-pairing", "ADB (debug Android qua Wi-Fi)"),
            Map.entry("_androidtvremote2", "Android TV remote"),
            Map.entry("_amzn-wplay", "Fire TV"),
            Map.entry("_workstation", "máy tính"));
    private static final Set<String> MDNS_SKIP = Set.of("_services", "_device-info", "_dns-sd", "_udp", "_tcp");
    private static final Pattern MDNS_SERVICE = Pattern.compile("(_[\\w-]+)[\\x01-\\x3f]_(?:tcp|udp)");
    private static final Pattern MDNS_HOST = Pattern.compile("[\\x01-\\x3f]([A-Za-z0-9][\\w-]*)\\x05local\\x00");

    private Lan() {
    }

    /** Multicast (224-239.x, ff..::) hoặc broadcast (.255). */
    static boolean isCast(String ip) {
        if (ip.contains(":")) {
            return ip.startsWith("ff");
        }
        int first = Integer.parseInt(ip.substring(0, ip.indexOf('.')));
        return first >= 224 && first <= 239 || ip.endsWith(".255");
    }

    /** 1 đợt phát của 1 thiết bị: hành động (quảng bá / đang tìm / trả lời), thứ được nhắc tới, tên thiết bị. */
    static final class Burst {
        final List<String> actions = new ArrayList<>();
        final List<String> items = new ArrayList<>();
        String device;
        int packets;

        void add(List<String> list, String item) {
            if (item != null && !list.contains(item)) {
                list.add(item);
            }
        }

        void ssdp(byte[] payload) {
            String text = new String(payload, StandardCharsets.UTF_8);
            String[] lines = text.split("\r\n");
            if (lines[0].startsWith("NOTIFY")) {
                add(actions, "quảng bá");
            } else if (lines[0].startsWith("M-SEARCH")) {
                add(actions, "đang tìm");
            } else if (lines[0].startsWith("HTTP/")) {
                add(actions, "trả lời");
            }
            String type = "";
            for (String l : lines) {
                int colon = l.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = l.substring(0, colon).strip().toUpperCase();
                String value = l.substring(colon + 1).strip();
                if ((key.equals("SERVER") || key.equals("USER-AGENT")) && device == null) {
                    device = value;
                } else if (key.equals("NT") || key.equals("ST")) {
                    type = value;
                }
            }
            if (type.equals("ssdp:all")) {
                add(items, "mọi thiết bị");
            } else if (type.startsWith("urn:")) {
                String[] parts = type.split(":");
                String kind = parts.length >= 2 ? parts[parts.length - 2] : type;
                if (!SSDP_SKIP.contains(kind)) {
                    add(items, SSDP_TYPES.getOrDefault(kind, kind));
                }
            }
        }

        void mdns(byte[] payload) {
            if (payload.length < 12) {
                return;
            }
            add(actions, (payload[2] & 0x80) != 0 ? "quảng bá" : "đang tìm");
            String raw = new String(payload, StandardCharsets.ISO_8859_1);
            Matcher m = MDNS_SERVICE.matcher(raw);
            while (m.find()) {
                String svc = m.group(1);
                if (!MDNS_SKIP.contains(svc)) {
                    add(items, MDNS_TYPES.getOrDefault(svc, svc.substring(1)));
                }
            }
            if (device == null) {
                Matcher h = MDNS_HOST.matcher(raw);
                if (h.find()) {
                    device = h.group(1) + ".local";
                }
            }
        }

        /** "quảng bá: máy in, AirPlay" (đã dịch theo ngôn ngữ). */
        String describe() {
            String act = actions.isEmpty() ? I18n.tr("phát") : String.join("/", actions.stream().map(I18n::tr).toList());
            List<String> shown = items.stream().limit(5).map(I18n::tr).toList();
            return act + (shown.isEmpty() ? "" : ": " + String.join(", ", shown) + (items.size() > 5 ? " …" : ""));
        }
    }
}
