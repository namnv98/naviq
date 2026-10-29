package com.sqlctx.net;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Đọc luồng pcap nhị phân ({@code tcpdump -w -}) và giải mã từng gói tới lớp 4 (TCP/UDP/ICMP) hoặc ARP. Đọc nhị phân
 * thay vì chữ của tcpdump: tải file lớn thì bản chữ (-x, ~100 dòng hex mỗi gói) không kịp xử lý, tool trễ dần và báo
 * nhầm "DNS không trả lời".
 */
public final class Pcap {

    private static final int ETH_IPV4 = 0x0800;
    private static final int ETH_IPV6 = 0x86DD;
    private static final int ETH_ARP = 0x0806;

    private final DataInputStream in;
    private final boolean littleEndian;
    private final boolean nano;
    private final int linkType;
    private final byte[] recordHeader = new byte[16];

    public Pcap(InputStream stream) throws IOException {
        in = new DataInputStream(stream);
        byte[] header = new byte[24];
        in.readFully(header);
        int magic = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        switch (magic) {
            case 0xd4c3b2a1 -> { littleEndian = true; nano = false; }
            case 0x4d3cb2a1 -> { littleEndian = true; nano = true; }
            case 0xa1b2c3d4 -> { littleEndian = false; nano = false; }
            case 0xa1b23c4d -> { littleEndian = false; nano = true; }
            default -> throw new IOException("not a pcap stream (magic " + Integer.toHexString(magic) + ")");
        }
        linkType = order(ByteBuffer.wrap(header)).getInt(20) & 0xFFFF;
    }

    private ByteBuffer order(ByteBuffer b) {
        return b.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    }

    /** Gói kế tiếp đã giải mã; null khi hết luồng. Gói không hiểu được (giao thức khác, hỏng) thì bỏ qua. */
    public Packet next() throws IOException {
        while (true) {
            try {
                in.readFully(recordHeader);
            } catch (EOFException e) {
                return null;
            }
            ByteBuffer h = order(ByteBuffer.wrap(recordHeader));
            long sec = h.getInt(0) & 0xFFFFFFFFL;
            long frac = h.getInt(4) & 0xFFFFFFFFL;
            int captured = h.getInt(8);
            byte[] data = new byte[captured];
            try {
                in.readFully(data);
            } catch (EOFException e) {
                return null;
            }
            Packet p = decode(sec + frac / (nano ? 1e9 : 1e6), linkType, data);
            if (p != null) {
                return p;
            }
        }
    }

    /** Giải mã 1 gói theo kiểu lớp 2 của pcap; null nếu không phải thứ cần quan tâm. */
    static Packet decode(double time, int linkType, byte[] data) {
        Packet p = decodeLayers(time, linkType, data);
        if (p == null) {
            return null;
        }
        int l3 = switch (linkType) {
            case 1 -> 14;
            case 276 -> 20;
            case 113 -> 16;
            case 0, 108 -> 4;
            default -> 0;
        };
        int end = Math.min(data.length, l3 + 128);
        long fp = 31L * Arrays.hashCode(Arrays.copyOfRange(data, Math.min(l3, end), end)) + (data.length - l3);
        return p.withFingerprint(fp);
    }

    private static Packet decodeLayers(double time, int linkType, byte[] data) {
        try {
            int etherType;
            int off;
            switch (linkType) {
                case 1 -> { // Ethernet (+ VLAN)
                    etherType = u16(data, 12);
                    off = 14;
                    while ((etherType == 0x8100 || etherType == 0x88A8) && data.length >= off + 4) {
                        etherType = u16(data, off + 2);
                        off += 4;
                    }
                }
                case 276 -> { etherType = u16(data, 0); off = 20; }  // Linux cooked v2 (-i any)
                case 113 -> { etherType = u16(data, 14); off = 16; } // Linux cooked v1
                case 101, 12, 14 -> {                                 // IP thô (tun0, wg0...)
                    int v = data.length > 0 ? (data[0] & 0xFF) >> 4 : 0;
                    etherType = v == 4 ? ETH_IPV4 : v == 6 ? ETH_IPV6 : 0;
                    off = 0;
                }
                case 0, 108 -> { etherType = data[0] == 2 || data[3] == 2 ? ETH_IPV4 : ETH_IPV6; off = 4; } // loopback BSD
                default -> { return null; }
            }
            if (etherType == ETH_ARP && data.length >= off + 28) {
                int op = u16(data, off + 6);
                String ip = ip(data, off + 14, 4);
                StringBuilder mac = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    mac.append(i == 0 ? "" : ":").append(String.format("%02x", data[off + 8 + i] & 0xFF));
                }
                return Packet.arp(time, op, ip, mac.toString());
            }
            String src;
            String dst;
            int proto;
            int l4;
            int l4len;
            boolean v6;
            if (etherType == ETH_IPV4 && data.length >= off + 20) {
                int ihl = (data[off] & 0x0F) * 4;
                int total = u16(data, off + 2);
                if ((u16(data, off + 6) & 0x1FFF) != 0) {
                    return null; // mảnh IP không phải mảnh đầu: không có header lớp 4
                }
                proto = data[off + 9] & 0xFF;
                src = ip(data, off + 12, 4);
                dst = ip(data, off + 16, 4);
                l4 = off + ihl;
                l4len = (total > 0 ? total : data.length - off) - ihl;
                v6 = false;
            } else if (etherType == ETH_IPV6 && data.length >= off + 40) {
                proto = data[off + 6] & 0xFF;
                src = ip(data, off + 8, 16);
                dst = ip(data, off + 24, 16);
                l4 = off + 40;
                l4len = u16(data, off + 4);
                while ((proto == 0 || proto == 43 || proto == 60) && data.length >= l4 + 8) { // extension header
                    int n = ((data[l4 + 1] & 0xFF) + 1) * 8;
                    proto = data[l4] & 0xFF;
                    l4 += n;
                    l4len -= n;
                }
                v6 = true;
            } else {
                return null;
            }
            if (proto == 6 && data.length >= l4 + 20) {
                int flags = data[l4 + 13] & 0xFF;
                int hdr = ((data[l4 + 12] & 0xFF) >> 4) * 4;
                int length = Math.max(l4len - hdr, 0);
                return Packet.tcp(time, v6, new Endpoint(src, u16(data, l4)), new Endpoint(dst, u16(data, l4 + 2)),
                        u32(data, l4 + 4), flags, u16(data, l4 + 14), length, slice(data, l4 + hdr, length));
            }
            if (proto == 17 && data.length >= l4 + 8) {
                int length = Math.max(u16(data, l4 + 4) - 8, 0);
                return Packet.udp(time, v6, new Endpoint(src, u16(data, l4)), new Endpoint(dst, u16(data, l4 + 2)),
                        length, slice(data, l4 + 8, length));
            }
            if ((proto == 1 || proto == 58) && data.length >= l4 + 8) {
                return Packet.icmp(time, v6, src, dst, data[l4] & 0xFF, data[l4 + 1] & 0xFF,
                        u16(data, l4 + 4), u16(data, l4 + 6));
            }
            return null;
        } catch (ArrayIndexOutOfBoundsException | UnknownHostException e) {
            return null;
        }
    }

    /** Phần dữ liệu đã bắt được (có thể ngắn hơn {@code length} nếu bị cắt bởi snaplen). */
    private static byte[] slice(byte[] data, int from, int length) {
        int end = Math.min(data.length, from + length);
        return from >= end ? new byte[0] : Arrays.copyOfRange(data, from, end);
    }

    private static String ip(byte[] data, int off, int len) throws UnknownHostException {
        return NetFormat.compactIp(InetAddress.getByAddress(Arrays.copyOfRange(data, off, off + len)).getHostAddress());
    }

    static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** Địa chỉ + cổng (cổng 0 với ICMP/ARP). */
    public record Endpoint(String ip, int port) {
        @Override
        public String toString() {
            return ip.contains(":") ? "[" + ip + "]:" + port : ip + ":" + port;
        }
    }

    /** 1 gói đã giải mã. {@code length}: độ dài dữ liệu thật (theo header IP), {@code payload}: phần bắt được. */
    public record Packet(double time, String proto, boolean v6, Endpoint src, Endpoint dst, long seq, int flags,
                         int window, int length, byte[] payload, int icmpType, int icmpCode, int icmpId, int icmpSeq,
                         int arpOp, String arpIp, String arpMac, long fingerprint) {

        static final int FIN = 1;
        static final int SYN = 2;
        static final int RST = 4;
        static final int ACK = 16;

        static Packet tcp(double t, boolean v6, Endpoint s, Endpoint d, long seq, int flags, int win, int len, byte[] p) {
            return new Packet(t, "tcp", v6, s, d, seq, flags, win, len, p, 0, 0, 0, 0, 0, null, null, 0);
        }

        static Packet udp(double t, boolean v6, Endpoint s, Endpoint d, int len, byte[] p) {
            return new Packet(t, "udp", v6, s, d, 0, 0, 0, len, p, 0, 0, 0, 0, 0, null, null, 0);
        }

        static Packet icmp(double t, boolean v6, String s, String d, int type, int code, int id, int seq) {
            return new Packet(t, "icmp", v6, new Endpoint(s, 0), new Endpoint(d, 0), 0, 0, 0, 0, new byte[0],
                    type, code, id, seq, 0, null, null, 0);
        }

        static Packet arp(double t, int op, String ip, String mac) {
            return new Packet(t, "arp", false, null, null, 0, 0, 0, 0, new byte[0], 0, 0, 0, 0, op, ip, mac, 0);
        }

        /** Dấu vân tay nội dung lớp 3 - để nhận ra cùng 1 gói bị bắt 2 lần trên 2 interface. */
        Packet withFingerprint(long fp) {
            return new Packet(time, proto, v6, src, dst, seq, flags, window, length, payload, icmpType, icmpCode, icmpId,
                    icmpSeq, arpOp, arpIp, arpMac, fp);
        }

        boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }
}
