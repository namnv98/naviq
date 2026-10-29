package com.sqlctx.net;

import java.util.Arrays;

/**
 * Dữ liệu 1 chiều của kết nối TCP, đã nối theo thứ tự. Khung lớn mà không cần nội dung (DataRow của Postgres, Fetch của
 * Kafka...) thì {@link #drop} bỏ qua luôn phần chưa tới thay vì giữ trong bộ nhớ.
 */
final class StreamBuf {

    /** Quá cỡ này mà vẫn chưa đủ 1 khung thì coi như lạc nhịp (bắt giữa chừng, hiểu sai giao thức): xoá. */
    static final int MAX = 8 << 20;

    private byte[] a = new byte[4096];
    private int start;
    private int end;
    private long skip;

    void append(byte[] data) {
        int off = 0;
        if (skip > 0) {
            int n = (int) Math.min(skip, data.length);
            skip -= n;
            off = n;
        }
        int n = data.length - off;
        if (n == 0) {
            return;
        }
        if (end + n > a.length) {
            int live = end - start;
            if (live + n > a.length) {
                a = Arrays.copyOf(a, Math.max(a.length * 2, live + n));
            }
            System.arraycopy(a, start, a, 0, live);
            start = 0;
            end = live;
        }
        System.arraycopy(data, off, a, end, n);
        end += n;
        if (end - start > MAX) {
            clear();
        }
    }

    int available() {
        return end - start;
    }

    int u8(int i) {
        return a[start + i] & 0xFF;
    }

    int u16(int i) {
        return (u8(i) << 8) | u8(i + 1);
    }

    int s16(int i) {
        return (short) u16(i);
    }

    int s32(int i) {
        return (u8(i) << 24) | (u8(i + 1) << 16) | (u8(i + 2) << 8) | u8(i + 3);
    }

    byte[] bytes(int from, int to) {
        return Arrays.copyOfRange(a, start + from, start + to);
    }

    /** Vị trí byte {@code b} đầu tiên tính từ {@code from}; -1 nếu chưa có. */
    int indexOf(int from, byte b) {
        for (int i = start + from; i < end; i++) {
            if (a[i] == b) {
                return i - start;
            }
        }
        return -1;
    }

    /** Bỏ {@code n} byte; phần chưa tới sẽ bị bỏ khi tới. */
    void drop(long n) {
        if (n <= available()) {
            start += (int) n;
        } else {
            skip = n - available();
            start = end = 0;
        }
    }

    void clear() {
        start = end = 0;
        skip = 0;
    }
}
