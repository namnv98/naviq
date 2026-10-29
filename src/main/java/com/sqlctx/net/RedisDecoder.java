package com.sqlctx.net;

import com.sqlctx.util.I18n;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** Giao thức Redis (RESP2/RESP3): lệnh client gửi và reply tương ứng (theo thứ tự, kể cả pipeline). */
final class RedisDecoder extends AppDecoder {

    private record Pending(String command, double time) {
    }

    private final Deque<Pending> pending = new ArrayDeque<>();

    RedisDecoder(Listener listener) {
        super(listener);
    }

    static boolean looksLike(byte[] b) {
        return b.length >= 4 && b[0] == '*' && Character.isDigit(b[1]) && new String(b, 0, Math.min(b.length, 16)).contains("\r\n$");
    }

    @Override
    String name() {
        return "REDIS";
    }

    @Override
    void onClient(double time) {
        while (client.available() > 0) {
            if (client.u8(0) != '*') { // lạc nhịp
                client.clear();
                return;
            }
            int end = scan(client, 0, 0);
            if (end < 0) {
                return;
            }
            List<String> args = new ArrayList<>();
            int count = (int) number(client, 0);
            int p = line(client, 0) + 2;
            for (int i = 0; i < count; i++) {
                int len = (int) number(client, p);
                int body = line(client, p) + 2;
                if (args.size() < 4) {
                    args.add(len < 0 ? "(nil)" : preview(client, body, len, 40));
                }
                p = body + Math.max(len, 0) + 2;
            }
            if (pending.size() > 1000) {
                pending.clear();
            }
            pending.add(new Pending(command(args, count), time));
            client.drop(end);
        }
    }

    /** Lệnh hiển thị: tên viết hoa + vài tham số đầu; mật khẩu của AUTH / HELLO ... AUTH bị che. */
    static String command(List<String> args, int count) {
        if (args.isEmpty()) {
            return "?";
        }
        List<String> shown = new ArrayList<>(args);
        String name = shown.get(0).toUpperCase(Locale.ROOT);
        shown.set(0, name);
        if (name.equals("AUTH") && shown.size() > 1) {
            shown.set(shown.size() - 1, "***");                   // AUTH [user] password
        }
        for (int i = 1; i < shown.size(); i++) {
            if (args.get(i - 1).equalsIgnoreCase("AUTH") && !name.equals("AUTH")) {
                int secret = name.equals("HELLO") ? i + 1 : i;    // HELLO 3 AUTH user password / MIGRATE ... AUTH password
                if (secret < shown.size()) {
                    shown.set(secret, "***");
                }
            }
        }
        if (name.equals("CONFIG") && shown.size() > 3 && shown.get(2).toLowerCase(Locale.ROOT).matches("requirepass|masterauth")) {
            shown.set(3, "***");
        }
        StringBuilder sb = new StringBuilder(name);
        for (int i = 1; i < shown.size(); i++) {
            String a = shown.get(i);
            sb.append(' ').append(a.contains(" ") || a.isEmpty() ? '"' + a + '"' : a);
        }
        if (count > args.size()) {
            sb.append(" …");
        }
        return sb.toString();
    }

    @Override
    void onServer(double time) {
        while (server.available() > 0) {
            if ("+-:$*_#,(=!%~>|".indexOf(server.u8(0)) < 0) {
                server.clear();
                pending.clear();
                return;
            }
            int end = scan(server, 0, 0);
            if (end < 0) {
                return;
            }
            Pending p = pending.poll();
            if (p != null && server.u8(0) != '>') {
                boolean error = server.u8(0) == '-' || server.u8(0) == '!';
                listener.exchange(time, p.command(), summary(server), error ? Status.ERROR : Status.OK, time - p.time());
            }
            server.drop(end);
        }
    }

    /** Tóm tắt 1 reply cho người đọc. */
    private static String summary(StreamBuf b) {
        char type = (char) b.u8(0);
        int lineEnd = line(b, 0);
        String head = text(b.bytes(1, lineEnd));
        return switch (type) {
            case '+' -> head;
            case '-', '!' -> type == '-' ? head : preview(b, lineEnd + 2, (int) number(b, 0), 100);
            case ':' -> "(integer) " + head;
            case ',' -> "(double) " + head;
            case '#' -> head.equals("t") ? "(true)" : "(false)";
            case '_' -> "(nil)";
            case '$', '=' -> {
                int len = (int) number(b, 0);
                yield len < 0 ? "(nil)" : '"' + preview(b, lineEnd + 2, len, 50) + '"'
                        + (len > 50 ? " · " + NetFormat.bytes(len) : "");
            }
            case '*', '~', '>' -> {
                long n = number(b, 0);
                yield n < 0 ? "(nil)" : I18n.t("net.redis.items", n);
            }
            case '%', '|' -> I18n.t("net.redis.pairs", number(b, 0));
            default -> head;
        };
    }

    private static String preview(StreamBuf b, int from, int len, int max) {
        String s = text(b.bytes(from, from + Math.min(len, max * 4)));
        s = s.replaceAll("[\\r\\n\\t]", " ");
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    /** Vị trí "\r" kết thúc dòng bắt đầu ở {@code from}; -1 nếu chưa đủ. */
    private static int line(StreamBuf b, int from) {
        int i = b.indexOf(from, (byte) '\n');
        return i < 0 ? -1 : i - 1;
    }

    /** Số ngay sau byte kiểu ở {@code from} (vd {@code *3\r\n} → 3). */
    private static long number(StreamBuf b, int from) {
        int end = line(b, from);
        try {
            return Long.parseLong(text(b.bytes(from + 1, end)).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Vị trí ngay sau giá trị RESP bắt đầu ở {@code from}; -1 nếu chưa nhận đủ. */
    static int scan(StreamBuf b, int from, int depth) {
        if (from >= b.available() || depth > 32) {
            return -1;
        }
        int end = line(b, from);
        if (end < 0) {
            return -1;
        }
        int next = end + 2;
        char type = (char) b.u8(from);
        switch (type) {
            case '$', '=', '!' -> {
                long len = number(b, from);
                if (len < 0) {
                    return next;
                }
                long after = next + len + 2;
                return after <= b.available() ? (int) after : -1;
            }
            case '*', '~', '>', '%', '|' -> {
                long n = number(b, from);
                if (type == '%' || type == '|') {
                    n *= 2;
                }
                int p = next;
                for (long i = 0; i < n; i++) {
                    p = scan(b, p, depth + 1);
                    if (p < 0) {
                        return -1;
                    }
                }
                return p;
            }
            default -> {
                return next;
            }
        }
    }
}
