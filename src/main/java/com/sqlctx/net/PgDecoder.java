package com.sqlctx.net;

import com.sqlctx.util.I18n;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Giao thức PostgreSQL (v3): câu SQL (simple query 'Q', extended Parse/Bind/Execute), kết quả ('C' CommandComplete,
 * 'E' lỗi, đếm DataRow) và đăng nhập. Kết nối bật TLS (SSLRequest được trả 'S') thì chỉ báo là đã mã hoá.
 */
final class PgDecoder extends AppDecoder {

    private static final int SSL_REQUEST = 80877103;
    private static final int GSS_REQUEST = 80877104;
    private static final int PROTOCOL_V3 = 196608;

    /** 1 câu đang chờ kết quả. {@code simple}: 'Q' (có thể nhiều câu, kết thúc ở ReadyForQuery). */
    private static final class Pending {
        final String sql;
        final double time;
        final boolean simple;
        /** Thuộc lượt Sync thứ mấy - ReadyForQuery thứ n kết thúc mọi câu của lượt n. */
        final long sync;
        long rows;

        Pending(String sql, double time, boolean simple, long sync) {
            this.sql = sql;
            this.time = time;
            this.simple = simple;
            this.sync = sync;
        }
    }

    private final Deque<Pending> pending = new ArrayDeque<>();
    private final Map<String, String> statements = new HashMap<>();
    private String bound = "?";
    private long clientSyncs;
    private long serverSyncs;
    private boolean startupSeen;
    private boolean encryptionAsked;
    private boolean encrypted;
    private boolean ready;
    private String user;
    private String database;
    private String application;
    private String serverVersion;

    PgDecoder(Listener listener, boolean midStream) {
        super(listener);
        // bắt giữa chừng: không còn thấy startup, đọc thẳng các khung có kiểu
        startupSeen = ready = midStream;
    }

    /** Đầu luồng client có giống startup / SSLRequest của Postgres không. */
    static boolean looksLike(byte[] b) {
        if (b.length < 8) {
            return false;
        }
        int len = (int) Pcap.u32(b, 0);
        int code = (int) Pcap.u32(b, 4);
        return (code == SSL_REQUEST || code == GSS_REQUEST) && len == 8 || code == PROTOCOL_V3 && len > 8 && len < 10_000;
    }

    @Override
    String name() {
        return "SQL";
    }

    @Override
    String detail() {
        if (user == null) {
            return null;
        }
        return user + "@" + (database != null ? database : user);
    }

    @Override
    void onClient(double time) {
        StreamBuf b = client;
        while (!encrypted) {
            if (!startupSeen) {
                if (b.available() < 8) {
                    return;
                }
                int len = b.s32(0);
                int code = b.s32(4);
                if (code == SSL_REQUEST || code == GSS_REQUEST) {
                    encryptionAsked = true;
                    b.drop(len);
                    continue;
                }
                if (b.available() < len) {
                    return;
                }
                startup(b, len);
                startupSeen = true;
                b.drop(len);
                continue;
            }
            if (b.available() < 5) {
                return;
            }
            char type = (char) b.u8(0);
            int total = 1 + b.s32(1);
            if (total < 5) { // lạc nhịp
                b.clear();
                return;
            }
            if (type == 'S') {
                clientSyncs++;
            }
            if (type != 'Q' && type != 'P' && type != 'B' && type != 'E') {
                b.drop(total); // Sync, Describe, Flush, password, COPY data... không cần nội dung
                continue;
            }
            if (b.available() < total) {
                return;
            }
            switch (type) {
                case 'Q' -> add(new Pending(cstring(b, 5), time, true, clientSyncs++));
                case 'P' -> {
                    int nameEnd = b.indexOf(5, (byte) 0);
                    String stmt = text(b.bytes(5, nameEnd));
                    bound = cstring(b, nameEnd + 1);
                    statements.put(stmt, bound);
                }
                case 'B' -> {
                    int portalEnd = b.indexOf(5, (byte) 0);
                    bound = statements.getOrDefault(cstring(b, portalEnd + 1), bound);
                }
                default -> add(new Pending(bound, time, false, clientSyncs)); // 'E' Execute
            }
            b.drop(total);
        }
    }

    private void add(Pending p) {
        if (pending.size() > 1000) { // lạc nhịp (bắt giữa chừng, thiếu ReadyForQuery): đừng phình mãi
            pending.clear();
        }
        pending.add(p);
    }

    private void startup(StreamBuf b, int len) {
        int p = 8;
        while (p < len - 1) {
            int keyEnd = b.indexOf(p, (byte) 0);
            if (keyEnd < 0 || keyEnd >= len) {
                return;
            }
            int valEnd = b.indexOf(keyEnd + 1, (byte) 0);
            if (valEnd < 0 || valEnd >= len) {
                return;
            }
            String key = text(b.bytes(p, keyEnd));
            String value = text(b.bytes(keyEnd + 1, valEnd));
            switch (key) {
                case "user" -> user = value;
                case "database" -> database = value;
                case "application_name" -> application = value;
                default -> {
                }
            }
            p = valEnd + 1;
        }
    }

    @Override
    void onServer(double time) {
        StreamBuf b = server;
        while (!encrypted) {
            if (encryptionAsked) {
                if (b.available() < 1) {
                    return;
                }
                encryptionAsked = false;
                if (b.u8(0) == 'S' || b.u8(0) == 'G') {
                    encrypted = true;
                    listener.note(time, I18n.t("net.pg.tls"), Status.INFO);
                    return;
                }
                b.drop(1); // 'N': không mã hoá, tiếp tục bình thường
                continue;
            }
            if (b.available() < 5) {
                return;
            }
            char type = (char) b.u8(0);
            int total = 1 + b.s32(1);
            if (total < 5) {
                b.clear();
                return;
            }
            if (type == 'D') { // DataRow: chỉ đếm, không giữ nội dung (có thể rất lớn)
                if (!pending.isEmpty()) {
                    pending.peek().rows++;
                }
                b.drop(total);
                continue;
            }
            if (type != 'R' && type != 'S' && type != 'E' && type != 'C' && type != 'Z' && type != 'I') {
                b.drop(total);
                continue;
            }
            if (b.available() < total) {
                return;
            }
            switch (type) {
                case 'S' -> {
                    int keyEnd = b.indexOf(5, (byte) 0);
                    if ("server_version".equals(text(b.bytes(5, keyEnd)))) {
                        serverVersion = cstring(b, keyEnd + 1);
                    }
                }
                case 'E' -> error(b, total, time);
                case 'C' -> complete(cstring(b, 5), time, Status.OK);
                case 'I' -> {
                    // câu rỗng: JDBC gửi để kiểm tra kết nối còn sống (isValid) - không đáng in
                    Pending p = pending.peek();
                    if (p != null && !p.sql.isBlank()) {
                        complete(I18n.t("net.pg.empty"), time, Status.OK);
                    } else if (p != null && !p.simple) {
                        pending.poll();
                    }
                }
                case 'Z' -> readyForQuery(time);
                default -> {
                }
            }
            b.drop(total);
        }
    }

    private void complete(String tag, double time, Status status) {
        Pending p = pending.peek();
        if (p == null) {
            return;
        }
        String result = tag;
        if (p.rows > 0 && !tag.matches("(SELECT|FETCH|COPY|INSERT|UPDATE|DELETE|MERGE) .*")) {
            result += " · " + I18n.t("net.pg.rows", p.rows);
        }
        listener.exchange(time, oneLine(p.sql, Integer.MAX_VALUE), result, status, time - p.time); // câu đầy đủ, bảng tự xuống dòng
        p.rows = 0;
        if (!p.simple) {
            pending.poll();
        }
    }

    private void error(StreamBuf b, int total, double time) {
        String severity = "";
        String code = "";
        String message = "";
        int p = 5;
        while (p < total - 1) {
            char field = (char) b.u8(p);
            int end = b.indexOf(p + 1, (byte) 0);
            if (field == 0 || end < 0) {
                break;
            }
            String value = text(b.bytes(p + 1, end));
            switch (field) {
                case 'V' -> severity = value;
                case 'C' -> code = value;
                case 'M' -> message = value;
                default -> {
                }
            }
            p = end + 1;
        }
        String text = (severity.isEmpty() ? "ERROR" : severity) + " " + code + ": " + message;
        if (!ready) {
            listener.note(time, I18n.t("net.pg.loginFailed", detailOrUnknown(), message), Status.ERROR);
            return;
        }
        if (pending.isEmpty()) {
            listener.note(time, text, Status.ERROR);
            return;
        }
        complete(text, time, Status.ERROR);
        // lỗi giữa chuỗi Execute: Postgres bỏ qua các câu còn lại tới Sync - ReadyForQuery sẽ dọn
    }

    private void readyForQuery(double time) {
        if (!ready) {
            ready = true;
            String app = application != null && !application.isEmpty() ? " (" + application + ")" : "";
            String version = serverVersion != null ? " · PostgreSQL " + serverVersion : "";
            listener.note(time, I18n.t("net.pg.login", detailOrUnknown()) + app + version, Status.OK);
            return; // ReadyForQuery của bước đăng nhập, không ứng với Sync nào
        }
        while (!pending.isEmpty() && pending.peek().sync <= serverSyncs) {
            pending.poll();
        }
        serverSyncs++;
    }

    private String detailOrUnknown() {
        String d = detail();
        return d != null ? d : "?";
    }

    private static String cstring(StreamBuf b, int from) {
        int end = b.indexOf(from, (byte) 0);
        return end < 0 ? "" : text(b.bytes(from, end));
    }
}
