package com.sqlctx.redis;

import com.sqlctx.util.I18n;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Client RESP tối giản, không thư viện ngoài: gửi lệnh dạng mảng bulk string, đọc reply RESP2 (và các kiểu
 * RESP3 nếu người dùng tự gõ HELLO 3). Lý do không dùng Jedis/Lettuce: REPL chỉ cần gửi nguyên văn lệnh gõ
 * vào và hiện nguyên văn reply, còn Ctrl+C cần đóng socket từ thread khác để thoát lệnh chặn (BLPOP,
 * SUBSCRIBE, MONITOR) - Redis không có lệnh huỷ như Statement.cancel() của JDBC.
 * <p>
 * Reply: {@code null} (nil), {@link Long}, {@link Simple}, {@link Bulk}, {@link RespError}, {@code List<Object>}.
 * Lỗi của server là 1 giá trị trả về, không phải exception - phần tử trong reply của EXEC cũng có thể là lỗi.
 */
public final class RespClient implements Closeable {

    /** Reply dạng "+OK" - in không có nháy, khác {@link Bulk}. */
    public record Simple(String text) {
    }

    public record Bulk(byte[] bytes) {
        public String utf8() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Bulk b && Arrays.equals(bytes, b.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "Bulk[" + utf8() + "]";
        }
    }

    public record RespError(String message) {
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private RespClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    /** Cho test: đọc reply từ 1 luồng byte có sẵn. */
    static Object parse(InputStream in) throws IOException {
        return readReply(in);
    }

    /**
     * Nối, đăng nhập (AUTH) và chọn DB. {@code readTimeoutMs} = 0 là chờ vô hạn (kết nối chính: BLPOP 0 hợp lệ);
     * kết nối phụ cho gợi ý dùng timeout ngắn để không bao giờ làm treo ô nhập.
     */
    public static RespClient connect(RedisProfile profile, int readTimeoutMs) throws IOException {
        Socket socket;
        if (profile.tls()) {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS"); // kiểm tra tên host trong chứng chỉ
            ssl.setSSLParameters(params);
            socket = ssl;
        } else {
            socket = new Socket();
        }
        try {
            socket.connect(new InetSocketAddress(profile.host(), profile.port()), 5000);
            socket.setSoTimeout(readTimeoutMs);
            socket.setTcpNoDelay(true);
            if (socket instanceof SSLSocket ssl) {
                ssl.startHandshake();
            }
            RespClient client = new RespClient(socket);
            String password = profile.resolvedPassword();
            if (password != null && !password.isEmpty()) {
                Object reply = profile.user() != null && !profile.user().isEmpty()
                        ? client.call("AUTH", profile.user(), password)
                        : client.call("AUTH", password);
                if (reply instanceof RespError e) {
                    client.close();
                    throw new IOException(e.message());
                }
            }
            if (profile.db() != 0) {
                Object reply = client.call("SELECT", String.valueOf(profile.db()));
                if (reply instanceof RespError e) {
                    client.close();
                    throw new IOException(e.message());
                }
            }
            return client;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    public Object call(String... args) throws IOException {
        List<byte[]> raw = new ArrayList<>(args.length);
        for (String a : args) {
            raw.add(a.getBytes(StandardCharsets.UTF_8));
        }
        return call(raw);
    }

    public synchronized Object call(List<byte[]> args) throws IOException {
        write(args);
        out.flush();
        return readReply(in);
    }

    /** Gửi cả lô rồi mới đọc - 1 lượt đi-về cho N lệnh (\dt lấy TYPE + PTTL của hàng trăm key). */
    public synchronized List<Object> pipeline(List<List<String>> commands) throws IOException {
        for (List<String> command : commands) {
            write(command.stream().map(s -> s.getBytes(StandardCharsets.UTF_8)).toList());
        }
        out.flush();
        List<Object> replies = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            replies.add(readReply(in));
        }
        return replies;
    }

    /** Chỉ gửi, không đọc reply - dùng cho SUBSCRIBE/MONITOR, sau đó đọc dần bằng {@link #read()}. */
    public synchronized void send(List<byte[]> args) throws IOException {
        write(args);
        out.flush();
    }

    /** Đọc 1 reply/tin nhắn kế tiếp (chặn tới khi có, hoặc tới khi socket bị đóng từ thread khác). */
    public Object read() throws IOException {
        return readReply(in);
    }

    private void write(List<byte[]> args) throws IOException {
        out.write(('*' + Integer.toString(args.size()) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (byte[] a : args) {
            out.write(('$' + Integer.toString(a.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(a);
            out.write('\r');
            out.write('\n');
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public boolean isClosed() {
        return socket.isClosed();
    }

    private static Object readReply(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new EOFException(I18n.t("resp.closed"));
        }
        String line = readLine(in);
        switch (type) {
            case '+':
                return new Simple(line);
            case '-':
                return new RespError(line);
            case ':':
                return Long.parseLong(line);
            case '$': {
                int len = Integer.parseInt(line);
                return len < 0 ? null : new Bulk(readBulk(in, len));
            }
            case '*':
            case '~': // RESP3 set
            case '>': { // RESP3 push
                int count = Integer.parseInt(line);
                if (count < 0) {
                    return null;
                }
                List<Object> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    items.add(readReply(in));
                }
                return items;
            }
            case '%': { // RESP3 map: trải phẳng thành field, value, field, value... giống RESP2
                int count = Integer.parseInt(line);
                List<Object> items = new ArrayList<>(count * 2);
                for (int i = 0; i < count * 2; i++) {
                    items.add(readReply(in));
                }
                return items;
            }
            case '|': { // RESP3 attribute: bỏ qua, đọc reply thật đi sau
                int count = Integer.parseInt(line);
                for (int i = 0; i < count * 2; i++) {
                    readReply(in);
                }
                return readReply(in);
            }
            case '_':
                return null;
            case ',': // RESP3 double
            case '(': // RESP3 big number
                return new Simple(line);
            case '#':
                return "t".equals(line) ? 1L : 0L;
            case '=': { // RESP3 verbatim string "txt:..."
                byte[] data = readBulk(in, Integer.parseInt(line));
                return new Bulk(data.length >= 4 ? Arrays.copyOfRange(data, 4, data.length) : data);
            }
            case '!':
                return new RespError(new String(readBulk(in, Integer.parseInt(line)), StandardCharsets.UTF_8));
            default:
                throw new IOException(I18n.t("resp.badReply", (char) type));
        }
    }

    private static byte[] readBulk(InputStream in, int len) throws IOException {
        byte[] data = in.readNBytes(len);
        if (data.length < len) {
            throw new EOFException(I18n.t("resp.closed"));
        }
        readLine(in); // \r\n sau dữ liệu
        return data;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException(I18n.t("resp.closed"));
            }
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') {
                    return buf.toString(StandardCharsets.UTF_8);
                }
                buf.write(b);
                if (next < 0) {
                    throw new EOFException(I18n.t("resp.closed"));
                }
                buf.write(next);
            } else {
                buf.write(b);
            }
        }
    }
}
