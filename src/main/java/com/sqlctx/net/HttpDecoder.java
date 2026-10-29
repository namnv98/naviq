package com.sqlctx.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP/1.x không mã hoá: mỗi request 1 dòng kèm mã trả về. Chỉ đọc header, phần body bỏ qua theo Content-Length. */
final class HttpDecoder extends AppDecoder {

    private static final String[] METHODS = {"GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "PATCH ", "OPTIONS "};
    private static final Pattern HOST = Pattern.compile("(?im)^Host:\\s*(\\S+)");
    private static final Pattern LENGTH = Pattern.compile("(?im)^Content-Length:\\s*(\\d+)");

    private record Pending(String request, double time) {
    }

    private final Deque<Pending> pending = new ArrayDeque<>();

    HttpDecoder(Listener listener) {
        super(listener);
    }

    static boolean looksLike(byte[] b) {
        String head = new String(b, 0, Math.min(b.length, 8), StandardCharsets.US_ASCII);
        for (String m : METHODS) {
            if (head.startsWith(m)) {
                return true;
            }
        }
        return false;
    }

    @Override
    String name() {
        return "HTTP";
    }

    @Override
    void onClient(double time) {
        String head;
        while ((head = header(client)) != null) {
            String[] first = head.lines().findFirst().orElse("").split(" ");
            Matcher host = HOST.matcher(head);
            String url = (host.find() ? host.group(1) : "") + (first.length > 1 ? first[1] : "");
            pending.add(new Pending(first[0] + " " + oneLine(url, 100), time));
            dropMessage(client, head);
        }
    }

    @Override
    void onServer(double time) {
        String head;
        while ((head = header(server)) != null) {
            String status = head.lines().findFirst().orElse("");
            status = status.contains(" ") ? status.substring(status.indexOf(' ') + 1) : status;
            Matcher len = LENGTH.matcher(head);
            String size = len.find() ? " · " + NetFormat.bytes(Long.parseLong(len.group(1))) : "";
            Pending p = pending.poll();
            if (p != null) {
                Status s = status.startsWith("5") ? Status.ERROR : status.startsWith("4") ? Status.WARN : Status.OK;
                listener.exchange(time, p.request(), status + size, s, time - p.time());
            }
            // HEAD, 1xx, 204, 304 không có body dù header có Content-Length
            boolean bodyless = p != null && p.request().startsWith("HEAD ") || status.matches("(1\\d\\d|204|304)\\b.*");
            if (bodyless) {
                server.drop(head.length());
            } else {
                dropMessage(server, head);
            }
        }
    }

    /** Header đầy đủ ở đầu bộ đệm (tới dòng trống); null nếu chưa đủ hoặc không phải HTTP. */
    private static String header(StreamBuf b) {
        int n = Math.min(b.available(), 16384);
        if (n < 4) {
            return null;
        }
        String s = new String(b.bytes(0, n), StandardCharsets.ISO_8859_1);
        int end = s.indexOf("\r\n\r\n");
        if (end < 0) {
            if (n >= 16384) {
                b.clear();
            }
            return null;
        }
        if (!s.startsWith("HTTP/") && !looksLike(b.bytes(0, Math.min(n, 8)))) {
            b.clear();
            return null;
        }
        return s.substring(0, end + 4);
    }

    /** Bỏ header + body (theo Content-Length; không có thì coi như không body / chunked: bỏ tới khi gặp header kế). */
    private static void dropMessage(StreamBuf b, String head) {
        Matcher len = LENGTH.matcher(head);
        b.drop(head.length() + (len.find() ? Long.parseLong(len.group(1)) : 0));
    }
}
