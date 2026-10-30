package com.sqlctx.net;

import com.sqlctx.ui.DataViewTable;
import com.sqlctx.util.I18n;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In sự kiện thành bảng có khung, cùng kiểu bảng kết quả SQL của sqlctx (viền bo tròn xám, tiêu đề cyan - xem
 * {@link DataViewTable}): giờ │ icon │ chương trình │ đích │ chuyện gì; bấm &lt; &gt; sang màn chi tiết kết nối (giao
 * thức, nguồn, IP đích, ↑ ↓, thời lượng, PID - xem {@link #VIEWS}). Bảng in dần theo thời gian
 * thực nên viền dưới chỉ in lúc kết thúc ({@link #close()}). Chữ dài (câu SQL...) xuống dòng ngay trong ô thay vì làm
 * vỡ khung.
 */
final class NetPrinter {

    enum Kind {
        OPEN("▶", NetFormat::green), CLOSE("■", NetFormat::dim), ERROR("✗", NetFormat::red), WARN("⚠", NetFormat::yellow),
        RETRY("…", NetFormat::yellow), REQUEST("◆", NetFormat::blue), DNS("◇", NetFormat::magenta),
        PING("●", NetFormat::green), LAN("◎", NetFormat::blue), INFO("·", NetFormat::dim), PACKET("›", NetFormat::dim);

        final String icon;
        final UnaryOperator<String> color;

        Kind(String icon, UnaryOperator<String> color) {
            this.icon = icon;
            this.color = color;
        }
    }

    /** Thông tin thêm của 1 sự kiện cho các cột chi tiết; số âm / 0 = không biết. */
    record Detail(String proto, String source, String dest, long up, long down, double age, int pid) {
    }

    /** Kiểu mũi tên của sơ đồ gói (chế độ chi tiết): gói điều khiển TCP, bắt tay TLS, bị cắt ngang. */
    enum Style { TCP, TLS, RESET }

    /**
     * 1 gói trong sơ đồ tuần tự của chế độ chi tiết: vẽ lúc in theo đúng bề ngang cột "chuyện gì" - trục trái là chương
     * trình (cột chương trình), trục phải là đích (cột đích). {@code up}: chương trình -&gt; đích; {@code gap}: thời gian
     * từ gói trước của cùng kết nối, null nếu là gói đầu.
     */
    record Arrow(boolean up, String label, String note, Style style, String gap) {
    }

    /** Cột sau giờ + icon: tên tiêu đề, bề ngang, căn phải. */
    private record Col(String key, int width, boolean right) {
    }

    static final int W_TIME = 12;
    static final int W_ICON = 1;
    static final int W_PROG = 14;
    private static final int PROG = 0;
    private static final int TARGET = 1;
    private static final int WHAT = 2;
    private static final int PROTO = 3;
    private static final int SOURCE = 4;
    private static final int DEST = 5;
    private static final int UP = 6;
    private static final int DOWN = 7;
    private static final int AGE = 8;
    private static final int PID = 9;
    private static final Col[] COLS = {new Col("net.col.program", W_PROG, false), new Col("net.col.target", 0, false),
            new Col("net.col.what", 0, false), new Col("net.col.proto", 12, false), new Col("net.col.source", 24, false),
            new Col("net.col.dest", 24, false), new Col("net.col.up", 9, true), new Col("net.col.down", 9, true),
            new Col("net.col.age", 9, true), new Col("net.col.pid", 7, true)};
    /**
     * 2 màn hình, phím &lt; &gt; để đổi: tổng quan (chuyện gì xảy ra) và chi tiết kết nối (mỗi sự kiện 1 dòng, không
     * ghi chú). Cột cuối của màn chi tiết (tên đích) lấy phần còn thừa, terminal hẹp thì bỏ bớt cột từ cuối.
     */
    private static final int[][] VIEWS = {{PROG, TARGET, WHAT}, {PROG, PROTO, SOURCE, DEST, UP, DOWN, AGE, PID, TARGET}};
    private static final String[] VIEW_NAMES = {"net.view.overview", "net.view.detail"};
    /** Viền + khoảng đệm của 5 cột màn tổng quan: "│ " + 4 × " │ " + " │". */
    private static final int CHROME = 2 + 4 * 3 + 2;
    private static final int HEADER_EVERY = 40;
    private static final int HISTORY = 3000;
    private static final Pattern SGR = Pattern.compile("\u001b\\[[0-9;]*m");
    private static final String RESET = "\u001b[0m";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final PrintStream out;
    private final int terminalWidth;
    private final int terminalHeight;
    private int view;                        // màn hình đang xem (phím < >)
    private int[] visible;                   // chỉ số trong COLS của các cột đang hiện
    private int[] widths;                    // bề ngang: giờ, icon, rồi từng cột đang hiện
    private boolean[] right;
    private int rowsSinceHeader;
    private boolean open;

    NetPrinter(PrintStream out, int terminalWidth) {
        this(out, terminalWidth, 40);
    }

    NetPrinter(PrintStream out, int terminalWidth, int terminalHeight) {
        this.out = out;
        this.terminalWidth = terminalWidth;
        this.terminalHeight = terminalHeight;
        layout();
    }

    /** Bề ngang các cột của màn hình đang xem cho vừa terminal. */
    private void layout() {
        int limit = Math.max(60, terminalWidth - 1);
        List<Integer> cols = new ArrayList<>();
        List<Integer> w = new ArrayList<>();
        if (view == 0) {
            int free = limit - CHROME - W_TIME - W_ICON - W_PROG;
            int target = Math.max(18, Math.min(34, free / 4));
            cols.addAll(List.of(PROG, TARGET, WHAT));
            w.addAll(List.of(W_PROG, target, Math.max(30, free - target)));
        } else {
            int[] all = VIEWS[1];
            for (int i = 0; i < all.length - 1; i++) {
                cols.add(all[i]);
                w.add(Math.max(COLS[all[i]].width(), width(I18n.t(COLS[all[i]].key()))));
            }
            int used = 1 + W_TIME + 3 + W_ICON + 3 + w.stream().mapToInt(x -> x + 3).sum();
            while (used > limit && cols.size() > 1) {
                used -= w.remove(w.size() - 1) + 3;
                cols.remove(cols.size() - 1);
            }
            if (cols.size() == all.length - 1 && limit - used >= 3 + 12) {
                cols.add(TARGET);
                w.add(limit - used - 3);
            }
        }
        visible = cols.stream().mapToInt(Integer::intValue).toArray();
        widths = new int[2 + w.size()];
        widths[0] = W_TIME;
        widths[1] = W_ICON;
        right = new boolean[widths.length];
        for (int i = 0; i < w.size(); i++) {
            widths[2 + i] = w.get(i);
            right[2 + i] = COLS[visible[i]].right();
        }
    }

    static String time(double epochSeconds) {
        return TIME.format(Instant.ofEpochMilli((long) (epochSeconds * 1000)));
    }

    /** 1 dòng sự kiện, giữ lại để lọc / in sau khi hết tạm dừng. */
    private record Row(double time, Kind kind, String program, String target, String what, String note, Detail detail,
                       Arrow arrow) {
        /** Chữ (không màu, không dấu, thường) của mọi ô - để so với bộ lọc. */
        String searchable() {
            String extra = detail == null ? "" : detail.proto() + " " + detail.source() + " " + detail.dest()
                    + (detail.pid() > 0 ? " " + detail.pid() : "");
            return fold(String.join(" ", program == null ? "" : program, target, what, note == null ? "" : note, extra));
        }
    }

    private static final int HOLD_LIMIT = 5000;

    // bộ lọc lúc đang chạy (phím /, e, l, p - xem key())
    private List<String> terms = List.of();
    private String filterText = "";
    private boolean errorsOnly;
    private boolean hideLan;
    private volatile boolean verbose;        // phím v: từng gói điều khiển TCP (NetAnalyzer chỉ tạo dòng khi bật)
    private boolean paused;
    private StringBuilder typing;            // khác null: đang gõ bộ lọc
    private final java.util.ArrayDeque<Row> held = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Row> history = new java.util.ArrayDeque<>();   // để vẽ lại khi trượt cột
    private int droppedWhileHeld;
    private int hidden;

    synchronized void emit(double time, Kind kind, String program, String target, String what, String note) {
        emit(time, kind, program, target, what, note, null);
    }

    synchronized void emit(double time, Kind kind, String program, String target, String what, String note, Detail detail) {
        emit(new Row(time, kind, program, target, what, note, detail, null));
    }

    /** 1 gói của sơ đồ tuần tự (chế độ chi tiết). */
    synchronized void arrow(double time, String program, String target, Arrow a, Detail detail) {
        String text = a.label() + (a.note() == null ? "" : " " + a.note());
        emit(new Row(time, Kind.PACKET, program, target, text, null, detail, a));
    }

    private void emit(Row r) {
        if (history.size() >= HISTORY) {
            history.poll();
        }
        history.add(r);
        if (!matches(r)) {
            hidden++;
            return;
        }
        if (paused || typing != null) {
            if (held.size() >= HOLD_LIMIT) {
                held.poll();
                droppedWhileHeld++;
            }
            held.add(r);
            return;
        }
        print(r);
    }

    /** Chế độ chi tiết đang bật: NetAnalyzer hỏi trước khi tạo dòng cho từng gói SYN/ACK/FIN. */
    boolean verbose() {
        return verbose;
    }

    void verbose(boolean on) {
        verbose = on;
    }

    private boolean matches(Row r) {
        if (!verbose && r.kind() == Kind.PACKET) {
            return false;
        }
        if (hideLan && r.kind() == Kind.LAN) {
            return false;
        }
        if (errorsOnly && r.kind() != Kind.ERROR && r.kind() != Kind.WARN && r.kind() != Kind.RETRY) {
            return false;
        }
        if (terms.isEmpty()) {
            return true;
        }
        String text = r.searchable();
        for (String t : terms) {
            boolean exclude = t.startsWith("-") && t.length() > 1;
            if (text.contains(exclude ? t.substring(1) : t) == exclude) {
                return false;
            }
        }
        return true;
    }

    /** Bỏ dấu, chữ thường: gõ "tu choi" vẫn khớp "TỪ CHỐI". */
    /** Bỏ mã màu ANSI. */
    static String plain(String s) {
        return SGR.matcher(s).replaceAll("");
    }

    static String fold(String s) {
        String plain = SGR.matcher(s).replaceAll("").replace('đ', 'd').replace('Đ', 'D');
        return java.text.Normalizer.normalize(plain, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
    }

    private void print(Row r) {
        if (!open) {
            out.println(border("╭", "┬", "╮", widths));
            header();
            open = true;
        } else if (rowsSinceHeader >= HEADER_EVERY) {
            out.println(border("├", "┼", "┤", widths));
            header();
        }
        rowsSinceHeader++;
        render(r).forEach(out::println);
        out.flush();
    }

    /** Các dòng terminal của 1 sự kiện theo cột đang hiện (chỉ cột "chuyện gì" xuống dòng, kèm ghi chú ↳). */
    private List<String> render(Row r) {
        String[] first = new String[widths.length];
        first[0] = NetFormat.dim(time(r.time()));
        first[1] = r.kind().color.apply(r.kind().icon);
        List<String> what = null;
        int whatAt = -1;
        for (int i = 0; i < visible.length; i++) {
            int w = widths[2 + i];
            if (visible[i] == WHAT && r.arrow() != null) {
                whatAt = 2 + i;
                what = new ArrayList<>(List.of(arrowLine(r.arrow(), w)));
            } else if (visible[i] == WHAT) {
                whatAt = 2 + i;
                what = wrap(r.what(), w);
                if (r.note() != null) {
                    for (String l : wrap("↳ " + r.note(), w)) {
                        what.add(NetFormat.dim(l));
                    }
                }
            } else {
                first[2 + i] = cell(visible[i], r, w);
            }
        }
        List<String> lines = new ArrayList<>();
        int count = what == null ? 1 : what.size();
        for (int l = 0; l < count; l++) {
            String[] cells = new String[widths.length];
            for (int c = 0; c < cells.length; c++) {
                cells[c] = c == whatAt ? what.get(l) : l == 0 ? first[c] : "";
            }
            lines.add(row(cells));
        }
        return lines;
    }

    /** Chỗ để thời gian "+235 ms" ở cuối dòng mũi tên. */
    private static final int GAP_WIDTH = 9;

    /**
     * Dòng mũi tên vừa đúng {@code w} ô: "│── SYN xin kết nối ──────▶│  +235 ms" (chương trình -&gt; đích) hoặc
     * "│◀────── SYN-ACK server đồng ý ──│" (đích -&gt; chương trình). Chật thì bỏ ghi chú, rồi cắt nhãn.
     */
    static String arrowLine(Arrow a, int w) {
        int gapW = w >= 30 ? GAP_WIDTH : 0;
        int inner = w - gapW - 2;                  // trừ 2 trục │ │
        char line = a.style() == Style.TLS ? '═' : '─';
        String label = a.label();
        String note = a.note();
        // phần cố định: đầu mũi tên + 2 đoạn ngắn + khoảng trắng quanh chữ = 6 ô, tối thiểu 2 ô thân mũi tên
        int room = inner - 6 - 2;
        if (note != null && width(label) + 1 + width(note) > room) {
            note = null;
        }
        if (width(label) > room) {
            label = fitPlain(label, Math.max(1, room));
        }
        int textW = width(label) + (note == null ? 0 : 1 + width(note));
        int fill = Math.max(2, inner - 6 - textW);
        java.util.function.UnaryOperator<String> paint = a.style() == Style.TLS ? NetFormat::blue
                : a.style() == Style.RESET ? NetFormat::red : NetFormat::dim;
        String text = NetFormat.bold(a.style() == Style.TCP ? label : paint.apply(label))
                + (note == null ? "" : " " + NetFormat.dim(note));
        String two = String.valueOf(line).repeat(2);
        String body = a.up()
                ? paint.apply(two + " ") + text + paint.apply(" " + String.valueOf(line).repeat(fill + 1) + "▶")
                : paint.apply("◀" + String.valueOf(line).repeat(fill + 1) + " ") + text + paint.apply(" " + two);
        String bar = NetFormat.dim("│");
        String gap = a.gap() == null || gapW == 0 ? " ".repeat(gapW)
                : NetFormat.dim(String.format("%" + gapW + "s", fitPlain(a.gap(), gapW)));
        return bar + body + bar + gap;
    }

    /** Nội dung 1 ô (1 dòng, cắt cho vừa) của cột không xuống dòng. */
    private static String cell(int col, Row r, int w) {
        if (col == PROG) {
            String program = r.program();
            String prog = fitPlain(program == null ? "·" : program, w);
            return program == null ? NetFormat.dim(prog)
                    : r.kind() == Kind.LAN && program.equals("LAN") ? NetFormat.blue(prog) : NetFormat.magenta(prog);
        }
        if (col == TARGET) {
            String tgt = fitPlain(r.target(), w);
            return r.kind() == Kind.LAN ? tgt : NetFormat.bold(tgt);
        }
        Detail d = r.detail();
        String text = d == null ? null : switch (col) {
            case PROTO -> d.proto();
            case SOURCE -> d.source();
            case DEST -> d.dest();
            case UP -> d.up() >= 0 ? NetFormat.bytes(d.up()) : null;
            case DOWN -> d.down() >= 0 ? NetFormat.bytes(d.down()) : null;
            case AGE -> d.age() > 0 ? NetFormat.duration(d.age()) : null;
            case PID -> d.pid() > 0 ? String.valueOf(d.pid()) : null;
            default -> null;
        };
        if (text == null) {
            return NetFormat.dim("·");
        }
        String fitted = fitPlain(text, w);
        return col == PROTO ? NetFormat.blue(fitted) : col == PID ? NetFormat.dim(fitted) : fitted;
    }

    /** Đổi màn hình (-1: trái, +1: phải) rồi vẽ lại các dòng gần nhất theo cột của màn mới. */
    private void switchView(int step) {
        int next = Math.max(0, Math.min(VIEWS.length - 1, view + step));
        if (next == view) {
            return;
        }
        if (NetFormat.stdoutIsTerminal()) {
            out.print("\u001b[H\u001b[2J");      // xoá màn hình (phần cũ vẫn còn trong lịch sử cuộn của terminal)
        } else if (open) {
            out.println(border("╰", "┴", "╯", widths));
        }
        view = next;
        layout();
        open = false;
        out.println(viewTabs());
        java.util.Set<Row> waiting = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        waiting.addAll(held);
        java.util.ArrayDeque<Row> recent = new java.util.ArrayDeque<>();
        int budget = Math.max(5, terminalHeight - 6);
        for (java.util.Iterator<Row> it = history.descendingIterator(); it.hasNext() && budget > 0; ) {
            Row r = it.next();
            if (!waiting.contains(r) && matches(r)) {
                budget -= render(r).size();
                if (budget >= 0) {
                    recent.addFirst(r);
                }
            }
        }
        recent.forEach(this::print);
        if (!open) {
            out.println(border("╭", "┬", "╮", widths));
            header();
            open = true;
        }
        out.flush();
    }

    /** Dòng tên các màn hình, màn đang xem in đậm: "▸ Tổng quan   Chi tiết kết nối   (< > đổi)". */
    private String viewTabs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < VIEW_NAMES.length; i++) {
            String name = I18n.t(VIEW_NAMES[i]);
            sb.append(i == view ? NetFormat.bold(NetFormat.blue("▸ " + name)) : NetFormat.dim("  " + name)).append("   ");
        }
        return sb.append(NetFormat.dim(I18n.t("net.view.hint"))).toString();
    }

    // ---------------------------------------------------------------- phím lúc đang chạy

    /**
     * Xử lý 1 phím: {@code /} gõ bộ lọc (các từ cách nhau bằng khoảng trắng đều phải khớp, {@code -từ} để loại, không
     * phân biệt hoa thường / dấu), {@code e} chỉ lỗi, {@code l} ẩn/hiện LAN, {@code p} tạm dừng. Trả false nếu là
     * phím thoát ({@code q}).
     */
    synchronized boolean key(String k) {
        if (typing != null) {
            switch (k) {
                case "\r", "\n" -> {
                    filterText = typing.toString().strip();
                    terms = filterText.isEmpty() ? List.of() : java.util.Arrays.stream(fold(filterText).split("\\s+")).toList();
                    typing = null;
                    hidden = 0;
                    statusRow(true);
                    release();
                }
                case "\u001b" -> {             // Esc: huỷ, giữ bộ lọc cũ
                    typing = null;
                    statusRow(true);
                    release();
                }
                case "\u007f", "\b" -> {
                    if (!typing.isEmpty()) {
                        typing.setLength(typing.offsetByCodePoints(typing.length(), -1));
                    }
                    promptRow();
                }
                default -> {
                    if (!k.startsWith("\u001b") && k.codePoints().allMatch(cp -> cp >= 32)) {
                        typing.append(k);
                        promptRow();
                    }
                }
            }
            return true;
        }
        switch (k) {
            case "/" -> {
                typing = new StringBuilder(filterText);
                spanBorder(true);
                promptRow();
            }
            case "e", "E" -> { errorsOnly = !errorsOnly; statusRow(false); }
            case "l", "L" -> { hideLan = !hideLan; statusRow(false); }
            case "v", "V" -> { verbose = !verbose; statusRow(false); }
            case "p", "P", " " -> {
                paused = !paused;
                statusRow(false);
                if (!paused) {
                    release();
                }
            }
            case "<", ",", "\u001b[D", "\u001bOD" -> switchView(-1);
            case ">", ".", "\u001b[C", "\u001bOC" -> switchView(1);
            case "q", "Q" -> {
                return false;
            }
            default -> {
            }
        }
        return true;
    }

    /** In các dòng đã giữ lại lúc tạm dừng / đang gõ (lọc lại theo bộ lọc mới). */
    private void release() {
        if (paused || typing != null) {
            return;
        }
        if (droppedWhileHeld > 0) {
            spanBorder(true);
            spanRow(NetFormat.yellow(I18n.t("net.key.dropped", droppedWhileHeld)));
            spanBorder(false);
            droppedWhileHeld = 0;
        }
        while (!held.isEmpty()) {
            Row r = held.poll();
            if (matches(r)) {
                print(r);
            }
        }
    }

    /** Dòng gộp cả bề ngang bảng cho biết bộ lọc đang bật. {@code afterPrompt}: ghi đè lên dòng đang gõ. */
    private void statusRow(boolean afterPrompt) {
        List<String> parts = new ArrayList<>();
        if (!filterText.isEmpty()) {
            parts.add(I18n.t("net.key.filter", NetFormat.bold(filterText)));
        }
        if (errorsOnly) {
            parts.add(I18n.t("net.key.errorsOnly"));
        }
        if (hideLan) {
            parts.add(I18n.t("net.key.lanHidden"));
        }
        if (verbose) {
            parts.add(I18n.t("net.key.verbose"));
        }
        if (paused) {
            parts.add(NetFormat.yellow(I18n.t("net.key.paused")));
        }
        String text = parts.isEmpty() ? I18n.t("net.key.showAll") : "⊘ " + String.join(" · ", parts);
        if (afterPrompt) {
            out.print("\r");
        } else {
            spanBorder(true);
        }
        spanRow(NetFormat.blue(text) + NetFormat.dim("   " + I18n.t("net.key.help")));
        spanBorder(false);
        out.flush();
    }

    /** Dòng đang gõ bộ lọc - vẽ lại tại chỗ (\r) sau mỗi phím. */
    private void promptRow() {
        String text = NetFormat.blue(I18n.t("net.key.prompt")) + " " + typing + "▏" + NetFormat.dim("   " + I18n.t("net.key.promptHelp"));
        out.print("\r" + spanLine(text));
        out.flush();
    }

    private void spanRow(String text) {
        out.println(spanLine(text));
    }

    private String spanLine(String text) {
        int inner = sum(widths) + (widths.length - 1) * 3;
        String fitted = width(text) > inner ? wrap(text, inner).get(0) : text;
        return bar("│") + " " + fitted + " ".repeat(Math.max(0, inner - width(fitted))) + " " + bar("│");
    }

    /** Viền quanh dòng gộp: {@code merge} = các cột nhập lại (├─┴─┤), ngược lại tách ra (├─┬─┤). */
    private void spanBorder(boolean merge) {
        if (!open) {
            out.println(border("╭", "┬", "╮", widths));
            header();
            open = true;
        }
        out.println(border("├", merge ? "┴" : "┬", "┤", widths));
    }

    /** Viền dưới của bảng (lúc thoát). */
    synchronized void close() {
        if (open) {
            out.println(border("╰", "┴", "╯", widths));
            open = false;
            out.flush();
        }
    }

    private void header() {
        rowsSinceHeader = 0;
        String[] cells = new String[widths.length];
        cells[0] = headerCell("net.col.time");
        cells[1] = "";
        for (int i = 0; i < visible.length; i++) {
            cells[2 + i] = headerCell(COLS[visible[i]].key());
        }
        out.println(row(cells));
        out.println(border("├", "┼", "┤", widths));
    }

    private static String headerCell(String key) {
        return NetFormat.color ? DataViewTable.HEADER_COLOR + I18n.t(key) + RESET : I18n.t(key);
    }

    private String row(String... cells) {
        StringBuilder sb = new StringBuilder(bar("│"));
        for (int i = 0; i < cells.length; i++) {
            String pad = " ".repeat(Math.max(0, widths[i] - width(cells[i])));
            sb.append(' ').append(right[i] ? pad + cells[i] : cells[i] + pad).append(' ').append(bar("│"));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- bảng tĩnh (tóm tắt lúc thoát)

    /** In 1 bảng nhỏ có khung; ô có thể chứa mã màu ANSI; {@code rightAligned}: cột số căn phải. */
    static void table(PrintStream out, String title, List<String> headers, List<List<String>> rows, int... rightAligned) {
        table(out, title, headers, rows, false, rightAligned);
    }

    /** Ô có "\n" thì in thành nhiều dòng; {@code rowLines}: kẻ ngang giữa các hàng (khi hàng cao nhiều dòng). */
    static void table(PrintStream out, String title, List<String> headers, List<List<String>> rows, boolean rowLines, int... rightAligned) {
        java.util.Set<Integer> right = java.util.Arrays.stream(rightAligned).boxed().collect(java.util.stream.Collectors.toSet());
        int[] w = new int[headers.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = width(headers.get(i));
            for (List<String> r : rows) {
                for (String line : r.get(i).split("\n", -1)) {
                    w[i] = Math.max(w[i], width(line));
                }
            }
        }
        String top = border("╭", "┬", "╮", w);
        if (title != null) {
            out.println(NetFormat.bold(title));
        }
        out.println(top);
        if (headers.stream().anyMatch(h -> !h.isEmpty())) {
            out.println(staticRow(headers.stream().map(h -> NetFormat.color ? DataViewTable.HEADER_COLOR + h + RESET : h).toList(), w, right));
            out.println(border("├", "┼", "┤", w));
        }
        for (int r = 0; r < rows.size(); r++) {
            if (rowLines && r > 0) {
                out.println(border("├", "┼", "┤", w));
            }
            List<String[]> cells = rows.get(r).stream().map(c -> c.split("\n", -1)).toList();
            int height = cells.stream().mapToInt(c -> c.length).max().orElse(1);
            for (int line = 0; line < height; line++) {
                int l = line;
                out.println(staticRow(cells.stream().map(c -> l < c.length ? c[l] : "").toList(), w, right));
            }
        }
        out.println(border("╰", "┴", "╯", w));
    }

    private static String staticRow(List<String> cells, int[] w, java.util.Set<Integer> right) {
        StringBuilder sb = new StringBuilder(bar("│"));
        for (int i = 0; i < cells.size(); i++) {
            String pad = " ".repeat(Math.max(0, w[i] - width(cells.get(i))));
            String cell = right.contains(i) ? pad + cells.get(i) : cells.get(i) + pad;
            sb.append(' ').append(cell).append(' ').append(bar("│"));
        }
        return sb.toString();
    }

    private static int sum(int[] w) {
        int s = 0;
        for (int x : w) {
            s += x;
        }
        return s;
    }

    // ---------------------------------------------------------------- vẽ / đo chữ

    private static String border(String left, String mid, String right, int[] w) {
        return border(left, mid, right, "─", w);
    }

    private static String border(String left, String mid, String right, String fill, int[] w) {
        boolean ascii = DataViewTable.ASCII;
        StringBuilder sb = new StringBuilder(ascii ? "+" : left);
        for (int i = 0; i < w.length; i++) {
            sb.append((ascii ? "-" : fill).repeat(w[i] + 2)).append(i < w.length - 1 ? (ascii ? "+" : mid) : (ascii ? "+" : right));
        }
        return bar(sb.toString());
    }

    /** 1 nhóm hàng trong {@link #groupedTable}: tiêu đề nhóm (đã tô màu) + các hàng. */
    record Group(String title, List<List<String>> rows) {
    }

    /**
     * Bảng chia nhóm: mọi nhóm chung 1 bộ độ rộng cột (thẳng hàng từ trên xuống), mỗi nhóm mở đầu bằng 1 dòng tiêu đề
     * trải hết bề ngang; giữa các hàng trong nhóm kẻ nét đứt mờ, giữa các nhóm kẻ liền. Ô có "\n" in nhiều dòng.
     */
    static void groupedTable(PrintStream out, List<String> headers, List<Group> groups) {
        int[] w = new int[headers.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = width(headers.get(i));
            for (Group g : groups) {
                for (List<String> r : g.rows()) {
                    for (String line : r.get(i).split("\n", -1)) {
                        w[i] = Math.max(w[i], width(line));
                    }
                }
            }
        }
        int inner = sum(w) + 3 * w.length - 1;
        java.util.Set<Integer> none = java.util.Set.of();
        out.println(border("╭", "┬", "╮", w));
        out.println(staticRow(headers.stream().map(h -> NetFormat.color ? DataViewTable.HEADER_COLOR + h + RESET : h).toList(), w, none));
        for (Group g : groups) {
            out.println(border("├", "┴", "┤", w));
            String title = " " + g.title();
            out.println(bar("│") + title + " ".repeat(Math.max(0, inner - width(title))) + bar("│"));
            out.println(border("├", "┬", "┤", w));
            for (int r = 0; r < g.rows().size(); r++) {
                if (r > 0) {
                    String dashed = border("├", "┼", "┤", "╌", w);
                    out.println(NetFormat.color ? NetFormat.dim(SGR.matcher(dashed).replaceAll("")) : dashed);
                }
                List<String[]> cells = g.rows().get(r).stream().map(c -> c.split("\n", -1)).toList();
                int height = cells.stream().mapToInt(c -> c.length).max().orElse(1);
                for (int line = 0; line < height; line++) {
                    int l = line;
                    out.println(staticRow(cells.stream().map(c -> l < c.length ? c[l] : "").toList(), w, none));
                }
            }
        }
        out.println(border("╰", "┴", "╯", w));
    }

    /** Khung bo tròn có tiêu đề nằm trên viền trên, các dòng bên trong. */
    static void box(PrintStream out, String title, List<String> lines) {
        int inner = Math.max(width(title) + 4, lines.stream().mapToInt(NetPrinter::width).max().orElse(0) + 2);
        boolean ascii = DataViewTable.ASCII;
        String h = ascii ? "-" : "─";
        out.println(bar(ascii ? "+" : "╭") + bar(h) + " " + title + " " + bar(h.repeat(Math.max(1, inner - width(title) - 3)) + (ascii ? "+" : "╮")));
        for (String l : lines) {
            out.println(bar("│") + " " + l + " ".repeat(Math.max(0, inner - 1 - width(l))) + bar("│"));
        }
        out.println(bar((ascii ? "+" : "╰") + h.repeat(inner) + (ascii ? "+" : "╯")));
    }

    private static String bar(String s) {
        if (DataViewTable.ASCII) {
            s = s.replace("│", "|");
        }
        return NetFormat.color ? DataViewTable.BORDER_COLOR + s + RESET : s;
    }

    /** Số ô terminal chữ chiếm (bỏ mã màu, chữ CJK/emoji tính 2). */
    static int width(String s) {
        return DataViewTable.displayWidth(SGR.matcher(s).replaceAll(""));
    }

    /** Cắt/đệm chữ thường (không màu) cho vừa {@code w} ô. */
    static String fitPlain(String s, int w) {
        if (width(s) <= w) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cw = width(new String(Character.toChars(cp)));
            if (used + cw > w - 1) {
                break;
            }
            sb.appendCodePoint(cp);
            used += cw;
            i += Character.charCount(cp);
        }
        return sb.append('…').toString();
    }

    /**
     * Xuống dòng cho vừa {@code w} ô, giữ màu: dòng bị ngắt giữa đoạn có màu thì đóng màu ở cuối dòng và mở lại ở
     * đầu dòng sau. Ưu tiên ngắt ở khoảng trắng.
     */
    static List<String> wrap(String s, int w) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String active = "";         // mã màu đang bật
        int used = 0;
        int lastSpace = -1;         // vị trí khoảng trắng gần nhất trong line
        String activeAtSpace = "";
        Matcher m = SGR.matcher(s);
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '\u001b' && m.find(i) && m.start() == i) {
                String code = m.group();
                line.append(code);
                active = code.equals(RESET) || code.equals("\u001b[m") ? "" : active + code;
                i = m.end();
                continue;
            }
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int cw = DataViewTable.displayWidth(ch);
            if (used + cw > w && cp == ' ') { // tràn đúng ở khoảng trắng: ngắt luôn tại đó, bỏ khoảng trắng
                lines.add(line + (active.isEmpty() ? "" : RESET));
                line = new StringBuilder(active);
                used = 0;
                lastSpace = -1;
                i++;
                continue;
            }
            if (used + cw > w) {
                String rest = "";
                String carry = active;
                if (lastSpace > 0 && width(line.substring(0, lastSpace)) >= w / 2) {
                    rest = line.substring(lastSpace + 1);      // phần sau khoảng trắng chuyển xuống dòng dưới
                    line.setLength(lastSpace);
                    carry = activeAtSpace;
                }
                lines.add(line + (carry.isEmpty() ? "" : RESET));
                line = new StringBuilder(carry).append(rest);
                used = width(rest);
                lastSpace = -1;
            }
            if (cp == ' ') {
                lastSpace = line.length();
                activeAtSpace = active;
            }
            line.append(ch);
            used += cw;
            i += Character.charCount(cp);
        }
        if (width(line.toString()) > 0 || lines.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
