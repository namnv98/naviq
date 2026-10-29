package com.sqlctx.net;

import java.nio.charset.StandardCharsets;

/** Tên miền (SNI) trong TLS ClientHello - phần duy nhất của HTTPS còn đọc được vì chưa mã hoá. */
final class Tls {

    /** ClientHello còn thiếu (bị chia nhiều gói): chờ thêm dữ liệu. */
    static final String INCOMPLETE = "\0incomplete";

    private Tls() {
    }

    static boolean looksLikeHandshake(byte[] b) {
        return b.length >= 3 && b[0] == 0x16 && b[1] == 0x03;
    }

    /** SNI; null nếu không phải ClientHello hay không có SNI; {@link #INCOMPLETE} nếu cần thêm dữ liệu. */
    static String sni(byte[] b, int len) {
        if (len < 5) {
            return INCOMPLETE;
        }
        if (b[0] != 0x16) {
            return null;
        }
        int recordLen = Pcap.u16(b, 3);
        if (len < 5 + recordLen && len < 16384) {
            return INCOMPLETE;
        }
        try {
            if (b[5] != 1) {
                return null;
            }
            int p = 5 + 4 + 2 + 32;
            p += 1 + (b[p] & 0xFF);          // session id
            p += 2 + Pcap.u16(b, p);         // cipher suites
            p += 1 + (b[p] & 0xFF);          // compression
            int end = Math.min(p + 2 + Pcap.u16(b, p), len);
            p += 2;
            while (p + 4 <= end) {
                int type = Pcap.u16(b, p);
                int extLen = Pcap.u16(b, p + 2);
                p += 4;
                if (type == 0) {
                    int n = Pcap.u16(b, p + 3);
                    return new String(b, p + 5, n, StandardCharsets.US_ASCII);
                }
                p += extLen;
            }
        } catch (ArrayIndexOutOfBoundsException | StringIndexOutOfBoundsException e) {
            return null;
        }
        return null;
    }
}
