package com.sqlctx.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Đọc gói DNS (câu hỏi + bản ghi A/AAAA/CNAME trong câu trả lời). Dùng cho cả mDNS. */
final class Dns {

    static final int NXDOMAIN = 3;
    static final int SERVFAIL = 2;
    static final int REFUSED = 5;

    private static final Map<Integer, String> TYPES = Map.ofEntries(Map.entry(1, "A"), Map.entry(2, "NS"),
            Map.entry(5, "CNAME"), Map.entry(6, "SOA"), Map.entry(12, "PTR"), Map.entry(15, "MX"), Map.entry(16, "TXT"),
            Map.entry(28, "AAAA"), Map.entry(33, "SRV"), Map.entry(64, "SVCB"), Map.entry(65, "HTTPS"));

    private Dns() {
    }

    /** {@code ips}: địa chỉ A/AAAA trả về; {@code cnames}: bí danh. */
    record Message(int id, boolean reply, int rcode, String name, String type, List<String> ips, List<String> cnames) {
    }

    static Message parse(byte[] b) {
        if (b.length < 12) {
            return null;
        }
        try {
            int id = Pcap.u16(b, 0);
            int flags = Pcap.u16(b, 2);
            int questions = Pcap.u16(b, 4);
            int answers = Pcap.u16(b, 6);
            int[] pos = {12};
            String name = "";
            String type = "";
            if (questions > 0) {
                name = name(b, pos);
                int t = Pcap.u16(b, pos[0]);
                type = TYPES.getOrDefault(t, String.valueOf(t));
                pos[0] += 4;
            }
            List<String> ips = new ArrayList<>();
            List<String> cnames = new ArrayList<>();
            for (int i = 0; i < answers && pos[0] + 10 <= b.length; i++) {
                name(b, pos);
                int t = Pcap.u16(b, pos[0]);
                int rdlen = Pcap.u16(b, pos[0] + 8);
                int rd = pos[0] + 10;
                if (rd + rdlen > b.length) {
                    break;
                }
                if (t == 1 && rdlen == 4 || t == 28 && rdlen == 16) {
                    ips.add(NetFormat.compactIp(InetAddress.getByAddress(Arrays.copyOfRange(b, rd, rd + rdlen)).getHostAddress()));
                } else if (t == 5) {
                    cnames.add(name(b, new int[]{rd}));
                }
                pos[0] = rd + rdlen;
            }
            return new Message(id, (flags & 0x8000) != 0, flags & 0xF, name, type, ips, cnames);
        } catch (ArrayIndexOutOfBoundsException | UnknownHostException e) {
            return null;
        }
    }

    /** Tên dạng nhãn ({@code \x06github\x03com\x00}), có con trỏ nén; {@code pos[0]} dời tới sau tên. */
    static String name(byte[] b, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0];
        int end = -1;
        int jumps = 0;
        while (p < b.length) {
            int n = b[p] & 0xFF;
            if (n == 0) {
                p++;
                break;
            }
            if ((n & 0xC0) == 0xC0) {
                if (p + 1 >= b.length || ++jumps > 10) {
                    break;
                }
                if (end < 0) {
                    end = p + 2;
                }
                p = ((n & 0x3F) << 8) | (b[p + 1] & 0xFF);
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(new String(b, p + 1, Math.min(n, b.length - p - 1), StandardCharsets.UTF_8));
            p += 1 + n;
        }
        pos[0] = end >= 0 ? end : p;
        return sb.toString();
    }
}
