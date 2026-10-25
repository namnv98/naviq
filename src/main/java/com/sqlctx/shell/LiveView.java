package com.sqlctx.shell;

import org.jline.terminal.Terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/**
 * Màn hình tự làm mới kiểu {@code top} (\stats): mỗi lượt lấy 1 khung mới rồi vẽ đè lên khung cũ ngay tại chỗ
 * (lùi con trỏ lên đầu khung cũ, xoá tới cuối màn hình) thay vì cuộn dài ra. Khung cao hơn màn hình thì cắt bớt -
 * lùi con trỏ không vượt được mép trên nên vẽ đè sẽ lệch.
 */
public final class LiveView {

    private LiveView() {
    }

    private static final long TICK_MS = 100;

    /**
     * Vẽ {@code frame} mỗi {@code intervalMs} tới khi {@code stop} đúng (Ctrl+C). {@code frame} chạy cả lúc đang
     * chờ mạng, nên kiểm tra {@code stop} cả sau khi lấy khung: Ctrl+C giữa chừng thì không vẽ khung dở.
     */
    public static void run(Terminal terminal, long intervalMs, BooleanSupplier stop, Callable<List<String>> frame)
            throws Exception {
        int previous = 0;
        while (!stop.getAsBoolean()) {
            List<String> lines = fit(frame.call(), terminal.getHeight());
            if (stop.getAsBoolean()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            if (previous > 0) {
                sb.append("\u001b[").append(previous).append('F').append("\u001b[J");
            }
            for (String line : lines) {
                sb.append(line).append("\u001b[K\n");
            }
            terminal.writer().print(sb);
            terminal.writer().flush();
            previous = lines.size();
            for (long waited = 0; waited < intervalMs && !stop.getAsBoolean(); waited += TICK_MS) {
                Thread.sleep(TICK_MS);
            }
        }
    }

    /** Chừa 2 dòng cho dấu nhắc và thanh trạng thái. */
    static List<String> fit(List<String> lines, int height) {
        if (height <= 0 || lines.size() <= height - 2) {
            return lines;
        }
        List<String> out = new ArrayList<>(lines.subList(0, Math.max(1, height - 3)));
        out.add("\u001b[38;5;245m… (" + (lines.size() - out.size()) + ")\u001b[0m");
        return out;
    }

    /** Tách chuỗi nhiều dòng (bảng của DataViewTable.renderFit) thành từng dòng, bỏ dòng trống cuối. */
    public static List<String> lines(String block) {
        return List.of(block.replace("\r", "").stripTrailing().split("\n"));
    }
}
