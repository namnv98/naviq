package com.sqlctx.shell;

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Màn hình theo dõi toàn màn hình kiểu htop/k9s cho \stats: vẽ trên màn hình phụ (thoát ra thì màn hình chính còn
 * nguyên), thread nền lấy mẫu mỗi chu kỳ để phím vẫn nhạy khi broker/server trả lời chậm. Phím: q/Esc/Ctrl+C thoát
 * (Esc ở màn chi tiết thì quay lại), Tab đổi bảng, ↑↓ chọn, Enter chi tiết, +/- đổi chu kỳ, p tạm dừng.
 */
public final class Dashboard {

    private Dashboard() {
    }

    /** Trạng thái điều khiển bằng phím - {@link View} đọc khi vẽ (và khi lấy mẫu: chi tiết của dòng đang chọn). */
    public static final class State {
        public volatile int panel;
        public final int[] selected = new int[4];
        public volatile boolean detail;
        public volatile boolean paused;
        public volatile long intervalMs;
        public volatile String error;
        public volatile long sampledAt;
    }

    public interface View {
        /** Lấy 1 mẫu mới (thread nền). Lỗi thì ném - khung giữ mẫu cũ và hiện lỗi trên tiêu đề. */
        void sample(State state) throws Exception;

        /** Cả màn hình, tối đa {@code height} dòng. */
        List<String> render(int width, int height, State state);

        /** Số bảng chọn được bằng Tab. */
        int panels();

        /** Số dòng của bảng {@code panel} (giới hạn ↑↓). */
        int rows(int panel);
    }

    /** Terminal "dumb" (không có điều khiển con trỏ) hoặc không biết kích thước thì không vẽ toàn màn hình được. */
    public static boolean supported(Terminal terminal) {
        return !Terminal.TYPE_DUMB.equals(terminal.getType()) && !Terminal.TYPE_DUMB_COLOR.equals(terminal.getType())
                && terminal.getWidth() > 0 && terminal.getHeight() > 0;
    }

    private static final long MIN_INTERVAL = 1000;
    private static final long MAX_INTERVAL = 60_000;

    /** @return true nếu cửa sổ đã đổi cỡ trong lúc xem (nơi gọi xoá và vẽ lại màn hình chính, như GridView). */
    public static boolean show(Terminal terminal, View view, long intervalMs) throws IOException {
        State state = new State();
        state.intervalMs = intervalMs;
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean dirty = new AtomicBoolean(true);
        Object wake = new Object();
        Thread sampler = new Thread(() -> {
            while (running.get()) {
                if (!state.paused) {
                    try {
                        view.sample(state);
                        state.error = null;
                        state.sampledAt = System.currentTimeMillis();
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        state.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    }
                    dirty.set(true);
                }
                synchronized (wake) {
                    try {
                        wake.wait(state.intervalMs);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "stats-sampler");
        sampler.setDaemon(true);

        PrintWriter out = terminal.writer();
        Attributes saved = terminal.enterRawMode();
        terminal.puts(Capability.enter_ca_mode);
        terminal.puts(Capability.keypad_xmit);
        terminal.puts(Capability.cursor_invisible);
        int widthAtOpen = terminal.getWidth();
        int heightAtOpen = terminal.getHeight();
        sampler.start();
        try {
            BindingReader br = new BindingReader(terminal.reader());
            KeyMap<String> km = keymap(terminal);
            int width = widthAtOpen;
            int height = heightAtOpen;
            while (true) {
                if (terminal.getWidth() != width || terminal.getHeight() != height) {
                    width = terminal.getWidth();
                    height = terminal.getHeight();
                    out.print("\u001b[2J");
                    dirty.set(true);
                }
                if (dirty.getAndSet(false)) {
                    draw(out, view.render(width, height, state), height);
                }
                if (terminal.reader().peek(150) == NonBlockingReader.READ_EXPIRED) {
                    continue;
                }
                String op = br.readBinding(km, null, true);
                if (op == null) {
                    break;
                }
                boolean quit = switch (op) {
                    case "quit" -> true;
                    case "back" -> {
                        if (!state.detail) {
                            yield true;
                        }
                        state.detail = false;
                        yield false;
                    }
                    case "tab" -> {
                        if (!state.detail && view.panels() > 1) {
                            state.panel = (state.panel + 1) % view.panels();
                        }
                        yield false;
                    }
                    case "up" -> {
                        state.selected[state.panel] = Math.max(0, state.selected[state.panel] - 1);
                        yield false;
                    }
                    case "down" -> {
                        state.selected[state.panel] = Math.max(0, Math.min(view.rows(state.panel) - 1, state.selected[state.panel] + 1));
                        yield false;
                    }
                    case "enter" -> {
                        state.detail = !state.detail && view.rows(state.panel) > 0;
                        synchronized (wake) {
                            wake.notifyAll(); // chi tiết cần dữ liệu riêng (vd INFO commandstats): lấy ngay
                        }
                        yield false;
                    }
                    case "faster", "slower" -> {
                        long next = op.equals("faster") ? state.intervalMs / 2 : state.intervalMs * 2;
                        state.intervalMs = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, next));
                        synchronized (wake) {
                            wake.notifyAll();
                        }
                        yield false;
                    }
                    case "pause" -> {
                        state.paused = !state.paused;
                        synchronized (wake) {
                            wake.notifyAll();
                        }
                        yield false;
                    }
                    default -> false;
                };
                if (quit) {
                    break;
                }
                dirty.set(true);
            }
            return terminal.getWidth() != widthAtOpen || terminal.getHeight() != heightAtOpen;
        } finally {
            running.set(false);
            sampler.interrupt();
            terminal.puts(Capability.cursor_visible);
            terminal.puts(Capability.keypad_local);
            terminal.puts(Capability.exit_ca_mode);
            terminal.setAttributes(saved);
            terminal.flush();
        }
    }

    /** Đặt con trỏ từng dòng (như GridView): dòng lỡ rộng quá chỉ bị vẽ đè, không làm cuộn màn hình. */
    private static void draw(PrintWriter out, List<String> lines, int height) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < height; i++) {
            sb.append("\u001b[").append(i + 1).append(";1H").append(i < lines.size() ? lines.get(i) : "").append("\u001b[K");
        }
        out.print(sb);
        out.flush();
    }

    private static KeyMap<String> keymap(Terminal t) {
        KeyMap<String> km = new KeyMap<>();
        km.setNomatch("noop");
        km.setAmbiguousTimeout(100);
        bind(km, t, "up", Capability.key_up, "\u001b[A", "\u001bOA", "k");
        bind(km, t, "down", Capability.key_down, "\u001b[B", "\u001bOB", "j");
        km.bind("tab", "\t");
        km.bind("enter", "\r", "\n");
        km.bind("faster", "+", "=");
        km.bind("slower", "-", "_");
        km.bind("pause", "p", "P", " ");
        km.bind("quit", "q", "Q", "\u0003");
        km.bind("back", "\u001b");
        return km;
    }

    private static void bind(KeyMap<String> km, Terminal t, String op, Capability cap, String... keys) {
        String fromTerminal = KeyMap.key(t, cap);
        if (fromTerminal != null && !fromTerminal.isEmpty()) {
            km.bind(op, fromTerminal);
        }
        km.bind(op, keys);
    }

    /** Dòng tiêu đề đảo màu trải hết bề ngang: bên trái {@code left}, bên phải {@code right}. */
    public static String titleBar(String left, String right, int width) {
        int space = Math.max(1, width - Screen.width(left) - Screen.width(right) - 2);
        return "\u001b[1;7;36m " + left + " ".repeat(space) + right + " " + Screen.RESET;
    }

    /** Chuỗi phím ở dòng cuối. */
    public static String footer(String keys, int width) {
        return Screen.fit(Screen.DIM + keys, width);
    }

    /** Dòng tiêu đề bên phải: tạm dừng / lỗi lấy mẫu / chu kỳ. */
    public static String status(State s, String every, String paused) {
        if (s.error != null) {
            return "\u001b[1;7;31m " + s.error + " \u001b[1;7;36m";
        }
        String clock = s.sampledAt == 0 ? "--:--:--" : java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(s.sampledAt),
                java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        return (s.paused ? paused + " · " : "") + clock + " · " + every;
    }
}
