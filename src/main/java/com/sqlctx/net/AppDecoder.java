package com.sqlctx.net;

import java.nio.charset.StandardCharsets;

/**
 * Giải mã giao thức ứng dụng trên 1 kết nối TCP: nhận dữ liệu từng chiều (đã đúng thứ tự), báo ra các cặp
 * yêu cầu → kết quả cho {@link Listener}.
 */
abstract class AppDecoder {

    enum Status { OK, WARN, ERROR, INFO }

    interface Listener {
        /** 1 yêu cầu đã có kết quả. {@code latency} tính bằng giây. */
        void exchange(double time, String request, String result, Status status, double latency);

        /** Sự kiện không phải yêu cầu (đăng nhập, chuyển sang TLS...). */
        void note(double time, String text, Status status);
    }

    final Listener listener;
    final StreamBuf client = new StreamBuf();
    final StreamBuf server = new StreamBuf();

    AppDecoder(Listener listener) {
        this.listener = listener;
    }

    /** Tên ngắn hiện ở cột chuyện gì (SQL, REDIS, KAFKA, HTTP). */
    abstract String name();

    /** Thông tin thêm cho cột đích (vd user@db của Postgres); null nếu không có. */
    String detail() {
        return null;
    }

    final void data(boolean fromClient, byte[] bytes, double time) {
        (fromClient ? client : server).append(bytes);
        if (fromClient) {
            onClient(time);
        } else {
            onServer(time);
        }
    }

    /** Mất dữ liệu (gói không bắt được): bỏ phần dở dang, bắt lại từ gói kế tiếp. */
    void gap(boolean fromClient) {
        (fromClient ? client : server).clear();
    }

    abstract void onClient(double time);

    abstract void onServer(double time);

    /** Gọn 1 câu cho vừa 1 dòng: gộp khoảng trắng, cắt bớt. */
    static String oneLine(String s, int max) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }

    static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
