package com.sqlctx.net;

import com.sqlctx.net.AppDecoder.Status;
import com.sqlctx.util.I18n;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Giải mã gói tin và giao thức ứng dụng của sqlctx --net, trên dữ liệu nhị phân dựng tay. */
class NetDecodersTest {

    @BeforeAll
    static void tiếngViệt() {
        I18n.set(I18n.Lang.VI, false);
        NetFormat.color = false;
    }

    /** Ghi lại sự kiện bộ giải mã báo ra, dạng "request → result [STATUS]". */
    private static final class Events implements AppDecoder.Listener {
        final List<String> lines = new ArrayList<>();

        @Override
        public void exchange(double time, String request, String result, Status status, double latency) {
            lines.add(request + " → " + result + " [" + status + "]");
        }

        @Override
        public void note(double time, String text, Status status) {
            lines.add(text + " [" + status + "]");
        }
    }

    private static byte[] bytes(Object... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object p : parts) {
            byte[] b = p instanceof String s ? s.getBytes(StandardCharsets.UTF_8) : p instanceof byte[] a ? a
                    : p instanceof Integer i ? ByteBuffer.allocate(4).putInt(i).array()
                    : p instanceof Short s ? ByteBuffer.allocate(2).putShort(s).array()
                    : new byte[]{(Byte) p};
            out.writeBytes(b);
        }
        return out.toByteArray();
    }

    /** Khung Postgres: kiểu (1 byte) + độ dài (gồm chính nó) + nội dung. */
    private static byte[] pg(char type, Object... body) {
        byte[] b = bytes(body);
        return bytes((byte) type, b.length + 4, b);
    }

    private static final byte Z = 0;

    // ---------------------------------------------------------------- Pcap / DNS / TLS

    @Test
    void giảiMãGóiTcpIpv4QuaLinuxCookedV2() {
        byte[] sll2 = bytes((short) 0x0800, new byte[18]);
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] ip = bytes((byte) 0x45, (byte) 0, (short) (20 + 20 + payload.length), new byte[4], (byte) 64, (byte) 6, (short) 0,
                new byte[]{10, 0, 0, 5}, new byte[]{93, (byte) 184, (byte) 216, 34});
        byte[] tcp = bytes((short) 51234, (short) 443, 1000, 0, (byte) 0x50, (byte) 0x18, (short) 502, (short) 0, (short) 0);
        Pcap.Packet p = Pcap.decode(1.5, 276, bytes(sll2, ip, tcp, payload));
        assertNotNull(p);
        assertEquals("tcp", p.proto());
        assertEquals(new Pcap.Endpoint("10.0.0.5", 51234), p.src());
        assertEquals(new Pcap.Endpoint("93.184.216.34", 443), p.dst());
        assertEquals(1000, p.seq());
        assertEquals(5, p.length());
        assertTrue(p.has(Pcap.Packet.ACK));
        assertFalse(p.has(Pcap.Packet.SYN));
        assertEquals("hello", new String(p.payload(), StandardCharsets.UTF_8));
    }

    @Test
    void rútGọnIpv6NhưLệnhIp() {
        assertEquals("::1", NetFormat.compactIp("0:0:0:0:0:0:0:1"));
        assertEquals("fe80::1:2:3:4", NetFormat.compactIp("fe80:0:0:0:1:2:3:4"));
        assertEquals("2001:db8::", NetFormat.compactIp("2001:db8:0:0:0:0:0:0"));
        assertEquals("2001:db8:0:1::1", NetFormat.compactIp("2001:0db8:0:1:0:0:0:1"));
        assertEquals("10.0.0.1", NetFormat.compactIp("10.0.0.1"));
    }

    @Test
    void đọcCâuHỏiVàTrảLờiDnsCóNénTên() {
        byte[] question = bytes(new byte[]{6}, "github", new byte[]{3}, "com", Z, (short) 1, (short) 1);
        byte[] query = bytes((short) 0x1234, (short) 0x0100, (short) 1, (short) 0, (short) 0, (short) 0, question);
        Dns.Message q = Dns.parse(query);
        assertNotNull(q);
        assertFalse(q.reply());
        assertEquals("github.com", q.name());
        assertEquals("A", q.type());

        byte[] answer = bytes((short) 0xC00C, (short) 1, (short) 1, 60, (short) 4, new byte[]{20, (byte) 205, (byte) 243, (byte) 166});
        byte[] reply = bytes((short) 0x1234, (short) 0x8180, (short) 1, (short) 1, (short) 0, (short) 0, question, answer);
        Dns.Message r = Dns.parse(reply);
        assertTrue(r.reply());
        assertEquals(0, r.rcode());
        assertEquals(List.of("20.205.243.166"), r.ips());

        byte[] nx = bytes((short) 0x1234, (short) 0x8183, (short) 1, (short) 0, (short) 0, (short) 0, question);
        assertEquals(Dns.NXDOMAIN, Dns.parse(nx).rcode());
    }

    @Test
    void đọcSniTrongClientHello() {
        byte[] host = "example.com".getBytes(StandardCharsets.US_ASCII);
        byte[] sniExt = bytes((short) 0, (short) (host.length + 5), (short) (host.length + 3), (byte) 0, (short) host.length, host);
        byte[] body = bytes((short) 0x0303, new byte[32], (byte) 0, (short) 2, (short) 0x1301, (byte) 1, (byte) 0,
                (short) sniExt.length, sniExt);
        byte[] handshake = bytes((byte) 1, (byte) 0, (short) body.length, body);
        byte[] record = bytes((byte) 0x16, (short) 0x0301, (short) handshake.length, handshake);
        assertTrue(Tls.looksLikeHandshake(record));
        assertEquals("example.com", Tls.sni(record, record.length));
        assertEquals(Tls.INCOMPLETE, Tls.sni(record, 20)); // ClientHello chia nhiều gói: chờ thêm
        assertNull(Tls.sni(bytes("GET / HTTP/1.1\r\n"), 16));
    }

    // ---------------------------------------------------------------- PostgreSQL

    @Test
    void postgresĐăngNhậpRồiTruyVấnĐơnGiản() {
        Events ev = new Events();
        PgDecoder d = new PgDecoder(ev, false);
        byte[] startup = bytes(196608, "user", Z, "tester", Z, "database", Z, "naviq", Z, "application_name", Z, "psql", Z, Z);
        byte[] startupMsg = bytes(startup.length + 4, startup);
        assertTrue(PgDecoder.looksLike(startupMsg));
        d.data(true, startupMsg, 0);
        d.data(false, bytes(pg('R', 0), pg('S', "server_version", Z, "16.2", Z), pg('Z', (byte) 'I')), 0.01);
        assertEquals("đăng nhập tester@naviq (psql) · PostgreSQL 16.2 [OK]", ev.lines.get(0));
        assertEquals("tester@naviq", d.detail());

        d.data(true, pg('Q', "select id,\n  name from users", Z), 1);
        d.data(false, bytes(pg('T', (short) 0), pg('D', (short) 0), pg('D', (short) 0), pg('C', "SELECT 2", Z), pg('Z', (byte) 'I')), 1.003);
        assertEquals("select id, name from users → SELECT 2 [OK]", ev.lines.get(1));
    }

    @Test
    void postgresExtendedQueryDùngLạiPreparedStatementVàBáoLỗi() {
        Events ev = new Events();
        PgDecoder d = new PgDecoder(ev, true); // bắt giữa chừng
        d.data(true, bytes(pg('P', "S_1", Z, "select * from orders where id = $1", Z, (short) 0),
                pg('B', Z, "S_1", Z, (short) 0, (short) 0, (short) 0), pg('E', Z, 0), pg('S')), 0);
        d.data(false, bytes(pg('1'), pg('2'), pg('D', (short) 0), pg('C', "SELECT 1", Z), pg('Z', (byte) 'I')), 0.002);
        // lần sau chỉ Bind tới statement đã có tên
        d.data(true, bytes(pg('B', Z, "S_1", Z, (short) 0, (short) 0, (short) 0), pg('E', Z, 0), pg('S')), 1);
        d.data(false, bytes(pg('E', (byte) 'S', "ERROR", Z, (byte) 'V', "ERROR", Z, (byte) 'C', "42P01", Z,
                (byte) 'M', "relation \"orders\" does not exist", Z, Z), pg('Z', (byte) 'I')), 1.001);
        assertEquals(List.of(
                "select * from orders where id = $1 → SELECT 1 [OK]",
                "select * from orders where id = $1 → ERROR 42P01: relation \"orders\" does not exist [ERROR]"), ev.lines);
    }

    @Test
    void postgresBậtTlsThìChỉBáoĐãMãHoá() {
        Events ev = new Events();
        PgDecoder d = new PgDecoder(ev, false);
        byte[] sslRequest = bytes(8, 80877103);
        assertTrue(PgDecoder.looksLike(sslRequest));
        d.data(true, sslRequest, 0);
        d.data(false, bytes((byte) 'S'), 0.001);
        d.data(true, bytes((byte) 0x16, (byte) 3, (byte) 1, "...rác mã hoá..."), 0.002);
        assertEquals(List.of("kết nối bật TLS — nội dung đã mã hoá, không đọc được câu SQL [INFO]"), ev.lines);
    }

    @Test
    void postgresĐăngNhậpSaiMậtKhẩu() {
        Events ev = new Events();
        PgDecoder d = new PgDecoder(ev, false);
        byte[] startup = bytes(196608, "user", Z, "app", Z, Z);
        d.data(true, bytes(startup.length + 4, startup), 0);
        d.data(false, pg('E', (byte) 'S', "FATAL", Z, (byte) 'C', "28P01", Z, (byte) 'M', "password authentication failed for user \"app\"", Z, Z), 0.01);
        assertEquals("đăng nhập app@app THẤT BẠI: password authentication failed for user \"app\" [ERROR]", ev.lines.get(0));
    }

    // ---------------------------------------------------------------- Redis

    private static String resp(String... args) {
        StringBuilder sb = new StringBuilder("*" + args.length + "\r\n");
        for (String a : args) {
            sb.append('$').append(a.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(a).append("\r\n");
        }
        return sb.toString();
    }

    @Test
    void redisPipelineGhépĐúngReplyVàCheMậtKhẩu() {
        Events ev = new Events();
        RedisDecoder d = new RedisDecoder(ev);
        byte[] req = bytes(resp("AUTH", "default", "s3cret"), resp("get", "user:1"), resp("HGETALL", "h"), resp("GET", "missing"), resp("incr", "x"));
        assertTrue(RedisDecoder.looksLike(req));
        // chia đôi giữa chừng 1 lệnh: phải chờ đủ
        d.data(true, java.util.Arrays.copyOfRange(req, 0, 30), 0);
        d.data(true, java.util.Arrays.copyOfRange(req, 30, req.length), 0);
        d.data(false, bytes("+OK\r\n$3\r\nabc\r\n*2\r\n$1\r\na\r\n$1\r\nb\r\n$-1\r\n-WRONGTYPE Operation against a key\r\n"), 0.001);
        assertEquals(List.of(
                "AUTH default *** → OK [OK]",
                "GET user:1 → \"abc\" [OK]",
                "HGETALL h → (2 phần tử) [OK]",
                "GET missing → (nil) [OK]",
                "INCR x → WRONGTYPE Operation against a key [ERROR]"), ev.lines);
        assertFalse(String.join("\n", ev.lines).contains("s3cret"));
    }

    @Test
    void redisCheMậtKhẩuTrongHelloVàConfigSet() {
        assertEquals("HELLO 3 AUTH default ***", RedisDecoder.command(List.of("HELLO", "3", "AUTH", "default", "pw"), 5));
        // chỉ giữ 4 tham số đầu: mật khẩu ở vị trí thứ 5 không bao giờ tới được màn hình
        assertEquals("HELLO 3 AUTH default …", RedisDecoder.command(List.of("HELLO", "3", "AUTH", "default"), 5));
        assertEquals("AUTH ***", RedisDecoder.command(List.of("auth", "pw"), 2));
        assertEquals("CONFIG SET requirepass ***", RedisDecoder.command(List.of("config", "SET", "requirepass", "pw"), 4));
    }

    // ---------------------------------------------------------------- Kafka

    private static byte[] kafkaRequest(int api, int version, int correlation, String clientId, int bodyLen) {
        byte[] header = bytes((short) api, (short) version, correlation, (short) clientId.length(), clientId, new byte[bodyLen]);
        return bytes(header.length, header);
    }

    @Test
    void kafkaGhépResponseTheoCorrelationIdVàGộpApiThườngGặp() {
        Events ev = new Events();
        KafkaDecoder d = new KafkaDecoder(ev);
        byte[] join = kafkaRequest(11, 9, 7, "consumer-1", 20);
        assertTrue(KafkaDecoder.looksLike(join));
        d.data(true, bytes(kafkaRequest(1, 13, 5, "consumer-1", 40), join, kafkaRequest(1, 13, 6, "consumer-1", 40)), 0);
        // response tới không theo thứ tự request
        d.data(false, bytes(12, 7, new byte[8], 104, 5, new byte[100], 104, 6, new byte[100]), 0.5);
        assertEquals("consumer-1", d.detail());
        assertEquals(List.of("JoinGroup v9 → ↓ 16 B [OK]"), ev.lines); // API quan trọng: in ngay
        d.flush(1, false);
        assertEquals(1, ev.lines.size()); // chưa đủ cửa sổ 5s
        d.flush(6, false);
        assertTrue(ev.lines.get(1).startsWith("Fetch ×2 (500 ms) → ↑ "), ev.lines.get(1));
    }

    // ---------------------------------------------------------------- HTTP

    @Test
    void httpMỗiRequestMộtDòngBỏQuaBody() {
        Events ev = new Events();
        HttpDecoder d = new HttpDecoder(ev);
        d.data(true, bytes("GET /x HTTP/1.1\r\nHost: example.com\r\n\r\nHEAD /y HTTP/1.1\r\nHost: example.com\r\n\r\n"), 0);
        d.data(false, bytes("HTTP/1.1 404 Not Found\r\nContent-Length: 5\r\n\r\nnope!"
                + "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n"), 0.1);
        assertEquals(List.of("GET example.com/x → 404 Not Found · 5 B [WARN]",
                "HEAD example.com/y → 200 OK · 1000 B [OK]"), ev.lines);
    }

    @Test
    void streamBufBỏQuaKhungLớnChưaTới() {
        StreamBuf b = new StreamBuf();
        b.append(new byte[]{1, 2, 3});
        b.drop(10); // khung dài 10, mới có 3 byte
        b.append(new byte[]{4, 5, 6, 7, 8, 9, 10, 42});
        assertEquals(1, b.available());
        assertEquals(42, b.u8(0));
    }
}
