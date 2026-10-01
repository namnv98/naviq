package com.sqlctx.cli.view;

import static com.sqlctx.cli.view.DataViewTable.ASCII;
import static com.sqlctx.cli.view.DataViewTable.BORDER_COLOR;
import static com.sqlctx.cli.view.DataViewTable.HEADER_COLOR;
import static com.sqlctx.cli.view.DataViewTable.RESET;
import static com.sqlctx.cli.view.DataViewTable.border;
import static com.sqlctx.cli.view.DataViewTable.clean;
import static com.sqlctx.cli.view.DataViewTable.displayWidth;
import static com.sqlctx.cli.view.DataViewTable.styleForValue;
import static com.sqlctx.cli.view.DataViewTable.truncate;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

/**
 * Xem kết quả không vừa màn hình ở chế độ toàn màn hình (kiểu pspg): header dính trên cùng, cột đầu đứng yên,
 * cuộn từng dòng / từng cột, thanh trạng thái dưới cùng. Phím x chuyển sang xem 1 bản ghi dạng "cột │ giá trị"
 * (như psql \x), giá trị dài được xuống dòng thay vì cắt.
 * <p>
 * Trạng thái + vẽ ({@link #handle}, {@link #render}) tách khỏi phần đọc phím/terminal ({@link #show}) để test
 * được không cần TTY.
 */
final class GridView {

    /** Cột rất dài (text, json) bị cắt ở độ rộng này trong lưới; xem đủ ở chế độ bản ghi. */
    static final int MAX_COL_WIDTH = 50;
    private static final int MAX_NAME_WIDTH = 30;

    private static final String SELECTED = "\u001b[48;5;238m";
    private static final String STATUS = "\u001b[48;5;236;38;5;250m";
    private static final String MARKER = "\u001b[33m";
    private static final String H_LINE = ASCII ? "-" : "─";
    private static final String V_LINE = ASCII ? "|" : "│";
    private static final String CROSS = ASCII ? "+" : "┼";
    private static final String[] TOP = ASCII ? new String[]{"+", "+", "+"} : new String[]{"╭", "┬", "╮"};
    private static final String[] MID = ASCII ? new String[]{"+", "+", "+"} : new String[]{"├", CROSS, "┤"};
    private static final String[] BOTTOM = ASCII ? new String[]{"+", "+", "+"} : new String[]{"╰", "┴", "╯"};

    private final List<String> columns;
    private final List<List<String>> rows;
    private final int[] widths;
    private final boolean[] numeric;

    private int cursor;
    private int top;
    /** Cột cuộn được đầu tiên đang hiện; cột 0 luôn đứng yên khi có hơn 1 cột. */
    private int left;
    private boolean recordMode;
    private int recordScroll;

    GridView(List<String> columns, List<List<String>> rows) {
        this.columns = columns;
        this.rows = rows;
        this.widths = DataViewTable.calcWidths(columns, rows, MAX_COL_WIDTH);
        this.numeric = DataViewTable.computeNumericColumns(rows, columns.size());
        this.left = firstScrollable();
    }

    // -------------------------------------------------------------------------
    // Terminal
    // -------------------------------------------------------------------------

    /**
     * @return true nếu cửa sổ đã đổi cỡ trong lúc xem: terminal đã xếp lại (reflow) màn hình chính, nơi gọi phải
     *         xoá và vẽ lại. Không đổi cỡ thì thoát màn hình phụ trả lại nguyên màn hình cũ, không cần vẽ gì thêm.
     */
    static boolean show(Terminal terminal, List<String> columns, List<List<String>> rows) throws IOException {
        GridView view = new GridView(columns, rows);
        PrintWriter out = terminal.writer();
        Attributes saved = terminal.enterRawMode();
        terminal.puts(Capability.enter_ca_mode);
        terminal.puts(Capability.keypad_xmit);
        terminal.puts(Capability.cursor_invisible);
        try {
            BindingReader br = new BindingReader(terminal.reader());
            KeyMap<String> km = keymap(terminal);
            int width = terminal.getWidth();
            int height = terminal.getHeight();
            int widthAtOpen = width;
            int heightAtOpen = height;
            draw(out, view.render(width, height));
            while (true) {
                // Chờ phím kiểu thăm dò để vẽ lại khi đổi cỡ cửa sổ (giống MenuCompleter).
                if (terminal.reader().peek(150) == NonBlockingReader.READ_EXPIRED) {
                    if (terminal.getWidth() != width || terminal.getHeight() != height) {
                        width = terminal.getWidth();
                        height = terminal.getHeight();
                        view.handle("resize", width, height);
                        draw(out, view.render(width, height));
                    }
                    continue;
                }
                String op = br.readBinding(km, null, true);
                if (op == null || !view.handle(op, width, height)) {
                    break;
                }
                draw(out, view.render(width, height));
            }
            return width != widthAtOpen || height != heightAtOpen;
        } finally {
            terminal.puts(Capability.cursor_visible);
            terminal.puts(Capability.keypad_local);
            terminal.puts(Capability.exit_ca_mode);
            terminal.setAttributes(saved);
            terminal.flush();
        }
    }

    /**
     * Đặt con trỏ tường minh cho từng dòng thay vì nối bằng "\r\n": dòng nào lỡ rộng hơn màn hình (ký tự mà
     * terminal vẽ rộng 2 ô) chỉ tràn sang dòng dưới rồi bị vẽ đè, không làm cả màn hình cuộn lên mất dòng tên cột.
     */
    private static void draw(PrintWriter out, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append("\u001b[").append(i + 1).append(";1H").append(lines.get(i)).append("\u001b[K");
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
        bind(km, t, "left", Capability.key_left, "\u001b[D", "\u001bOD", "h");
        bind(km, t, "right", Capability.key_right, "\u001b[C", "\u001bOC", "l");
        bind(km, t, "pgup", Capability.key_ppage, "\u001b[5~", "b");
        bind(km, t, "pgdn", Capability.key_npage, "\u001b[6~", " ");
        bind(km, t, "home", Capability.key_home, "\u001b[H", "\u001bOH", "\u001b[1~", "g");
        bind(km, t, "end", Capability.key_end, "\u001b[F", "\u001bOF", "\u001b[4~", "G");
        km.bind("colHome", "0");
        km.bind("colEnd", "$");
        km.bind("record", "x", "X");
        km.bind("quit", "q", "Q", "\r", "\n", "\u001b", "\u0003");
        return km;
    }

    private static void bind(KeyMap<String> km, Terminal t, String op, Capability cap, String... keys) {
        String fromTerminal = KeyMap.key(t, cap);
        if (fromTerminal != null && !fromTerminal.isEmpty()) {
            km.bind(op, fromTerminal);
        }
        km.bind(op, keys);
    }

    // -------------------------------------------------------------------------
    // Trạng thái
    // -------------------------------------------------------------------------

    /** Áp 1 thao tác; false khi người dùng thoát. */
    boolean handle(String op, int width, int height) {
        int page = dataHeight(height);
        if (op.equals("quit")) {
            return false;
        }
        if (op.equals("record")) {
            recordMode = !recordMode;
            recordScroll = 0;
        } else if (recordMode) {
            handleRecord(op, width, page);
        } else {
            handleGrid(op, width, page);
        }
        clampCursor(page);
        return true;
    }

    private void handleGrid(String op, int width, int page) {
        switch (op) {
            case "up" -> cursor--;
            case "down" -> cursor++;
            case "pgup" -> cursor -= page;
            case "pgdn" -> cursor += page;
            case "home" -> cursor = 0;
            case "end" -> cursor = rows.size() - 1;
            case "left" -> left = Math.max(firstScrollable(), left - 1);
            case "right" -> {
                if (!lastColumnVisible(width)) {
                    left++;
                }
            }
            case "colHome" -> left = firstScrollable();
            case "colEnd" -> {
                while (!lastColumnVisible(width)) {
                    left++;
                }
            }
            default -> {
            }
        }
    }

    private void handleRecord(String op, int width, int page) {
        int maxScroll = Math.max(0, recordLines(width).size() - page);
        switch (op) {
            case "up" -> recordScroll--;
            case "down" -> recordScroll++;
            case "pgup" -> recordScroll -= page;
            case "pgdn" -> recordScroll += page;
            case "left" -> {
                cursor--;
                recordScroll = 0;
            }
            case "right" -> {
                cursor++;
                recordScroll = 0;
            }
            case "home" -> {
                cursor = 0;
                recordScroll = 0;
            }
            case "end" -> {
                cursor = rows.size() - 1;
                recordScroll = 0;
            }
            default -> {
            }
        }
        recordScroll = Math.max(0, Math.min(recordScroll, maxScroll));
    }

    private void clampCursor(int page) {
        cursor = Math.max(0, Math.min(cursor, rows.size() - 1));
        if (cursor < top) {
            top = cursor;
        }
        if (cursor >= top + page) {
            top = cursor - page + 1;
        }
        top = Math.max(0, Math.min(top, rows.size() - page));
    }

    private int firstScrollable() {
        return columns.size() > 1 ? 1 : 0;
    }

    private int dataHeight(int height) {
        // Lưới: khung trên + tên cột + vạch ngăn + khung dưới + thanh trạng thái. Bản ghi: tiêu đề + vạch + thanh trạng thái.
        return Math.max(1, height - (recordMode ? 3 : 5));
    }

    /** Cột cuối hiện đủ (không bị cắt), hoặc đã cuộn tới cột cuối - khi đó không cuộn phải thêm được nữa. */
    private boolean lastColumnVisible(int width) {
        if (left >= columns.size() - 1) {
            return true;
        }
        List<int[]> vis = visibleColumns(width);
        int[] last = vis.isEmpty() ? null : vis.get(vis.size() - 1);
        return last == null || (last[0] == columns.size() - 1 && last[1] == widths[last[0]]);
    }

    /** Các cột hiện được ({chỉ số cột, độ rộng}): cột đứng yên trước, rồi các cột từ {@link #left} tới khi hết chỗ. */
    List<int[]> visibleColumns(int width) {
        List<int[]> out = new ArrayList<>();
        int used = 2; // cạnh khung trái và phải (chỗ hiện ◀ ▶ khi còn cột bị ẩn)
        if (firstScrollable() == 1) {
            int w = Math.min(widths[0], Math.max(8, width / 3));
            out.add(new int[]{0, w});
            used += w + 2;
        }
        for (int c = left; c < columns.size(); c++) {
            int sep = out.isEmpty() ? 0 : 1;
            int need = sep + widths[c] + 2;
            if (used + need <= width) {
                out.add(new int[]{c, widths[c]});
                used += need;
                continue;
            }
            // Cột không vừa hết: cắt cho vừa phần còn lại thay vì bỏ trống mép phải (và để luôn thấy ít nhất 1 cột).
            int w = width - used - sep - 2;
            if (w >= 3) {
                out.add(new int[]{c, w});
            }
            break;
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Vẽ
    // -------------------------------------------------------------------------

    /** Đúng {@code height} dòng (ít hơn chỉ khi terminal thấp hơn 4 dòng). */
    List<String> render(int width, int height) {
        int page = dataHeight(height);
        List<String> lines = new ArrayList<>();
        if (recordMode) {
            renderRecord(lines, width, page);
        } else {
            renderGrid(lines, width, page);
        }
        return lines.size() > height ? lines.subList(lines.size() - height, lines.size()) : lines;
    }

    private void renderGrid(List<String> lines, int width, int page) {
        List<int[]> vis = visibleColumns(width);
        boolean moreLeft = left > firstScrollable();
        boolean moreRight = !lastColumnVisible(width);

        lines.add(borderLine(vis, TOP));

        String leftEdge = moreLeft ? MARKER + (ASCII ? "<" : "◀") + RESET : border(V_LINE);
        String rightEdge = moreRight ? MARKER + (ASCII ? ">" : "▶") + RESET : border(V_LINE);
        StringBuilder header = new StringBuilder(leftEdge);
        for (int j = 0; j < vis.size(); j++) {
            int[] col = vis.get(j);
            if (j > 0) {
                header.append(border(V_LINE));
            }
            String name = truncate(clean(columns.get(col[0])), col[1]);
            header.append(' ').append(HEADER_COLOR).append(name).append(RESET)
                    .append(" ".repeat(col[1] - displayWidth(name))).append(' ');
        }
        lines.add(header.append(rightEdge).toString());
        lines.add(borderLine(vis, MID));

        // Ít dòng hơn 1 trang thì khung dưới đặt sát dòng cuối, phần còn lại để trống.
        int shown = Math.max(0, Math.min(page, rows.size() - top));
        for (int i = 0; i < shown; i++) {
            int r = top + i;
            lines.add(rowLine(rows.get(r), vis, r == cursor));
        }
        lines.add(borderLine(vis, BOTTOM));
        for (int i = shown; i < page; i++) {
            lines.add("");
        }
        lines.add(statusLine(gridStatus(vis), width));
    }

    /** Vạch ngang của khung: {@code glyphs} = góc trái, chỗ giao với vạch đứng giữa các cột, góc phải. */
    private static String borderLine(List<int[]> vis, String[] glyphs) {
        StringBuilder sb = new StringBuilder(BORDER_COLOR).append(glyphs[0]);
        for (int j = 0; j < vis.size(); j++) {
            if (j > 0) {
                sb.append(glyphs[1]);
            }
            sb.append(H_LINE.repeat(vis.get(j)[1] + 2));
        }
        return sb.append(glyphs[2]).append(RESET).toString();
    }

    private String rowLine(List<String> row, List<int[]> vis, boolean selected) {
        StringBuilder sb = new StringBuilder(border(V_LINE)).append(selected ? SELECTED : "");
        for (int j = 0; j < vis.size(); j++) {
            int[] col = vis.get(j);
            if (j > 0) {
                sb.append(selected ? V_LINE : border(V_LINE));
            }
            String raw = col[0] < row.size() ? clean(row.get(col[0])) : "";
            String cell = truncate(raw, col[1]);
            String pad = " ".repeat(col[1] - displayWidth(cell));
            // Dòng đang chọn không tô màu theo kiểu giá trị: RESET của màu chữ sẽ xoá luôn nền highlight.
            String style = selected ? "" : styleForValue(cell);
            String shownCell = style.isEmpty() ? cell : style + cell + RESET;
            sb.append(' ').append(numeric[col[0]] ? pad + shownCell : shownCell + pad).append(' ');
        }
        if (selected) {
            sb.append(RESET);
        }
        return sb.append(border(V_LINE)).toString();
    }

    private String gridStatus(List<int[]> vis) {
        String rowPart = rows.isEmpty() ? "0 rows" : "row " + (cursor + 1) + " of " + rows.size();
        int last = vis.isEmpty() ? 0 : vis.get(vis.size() - 1)[0];
        String colPart;
        if (firstScrollable() == 0 || left == firstScrollable()) {
            colPart = "cols 1-" + (last + 1);
        } else {
            colPart = "cols 1, " + (left + 1) + "-" + (last + 1);
        }
        colPart += " of " + columns.size();
        // Chỉ ASCII: mũi tên, "·", "–" có terminal vẽ rộng 2 ô, làm dòng cuối tràn và cuộn cả màn hình.
        return " " + rowPart + " | " + colPart + " | arrows PgUp/PgDn g/G 0/$ | x record | q quit";
    }

    private void renderRecord(List<String> lines, int width, int page) {
        String title = rows.isEmpty() ? "No rows" : "Record " + (cursor + 1) + " of " + rows.size();
        lines.add(" " + HEADER_COLOR + title + RESET);
        lines.add(" " + BORDER_COLOR + H_LINE.repeat(Math.max(0, width - 2)) + RESET);
        List<String> body = recordLines(width);
        for (int i = 0; i < page; i++) {
            int k = recordScroll + i;
            lines.add(k < body.size() ? body.get(k) : "");
        }
        lines.add(statusLine(" left/right prev/next record | up/down PgUp/PgDn scroll | x grid | q quit", width));
    }

    /** Thân của bản ghi đang chọn: mỗi cột 1 dòng "tên │ giá trị", giá trị dài xuống dòng theo độ rộng màn hình. */
    private List<String> recordLines(int width) {
        int nameW = 0;
        for (String c : columns) {
            nameW = Math.max(nameW, Math.min(MAX_NAME_WIDTH, displayWidth(clean(c))));
        }
        int valueW = Math.max(10, width - nameW - 4); // " " + tên + " │ "
        List<String> out = new ArrayList<>();
        if (rows.isEmpty()) {
            return out;
        }
        List<String> row = rows.get(cursor);
        for (int c = 0; c < columns.size(); c++) {
            String name = truncate(clean(columns.get(c)), nameW);
            String value = c < row.size() ? clean(row.get(c)) : "";
            String style = styleForValue(value);
            List<String> chunks = wrap(value, valueW);
            for (int k = 0; k < chunks.size(); k++) {
                String label = k == 0
                        ? HEADER_COLOR + name + RESET + " ".repeat(nameW - displayWidth(name))
                        : " ".repeat(nameW);
                String chunk = chunks.get(k);
                out.add(" " + label + " " + border(V_LINE) + " " + (style.isEmpty() ? chunk : style + chunk + RESET));
            }
        }
        return out;
    }

    /** Cắt theo độ rộng hiển thị (ký tự CJK/emoji rộng 2 ô); luôn trả về ít nhất 1 phần. */
    private static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cw = displayWidth(new String(Character.toChars(cp)));
            if (w + cw > width && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
                w = 0;
            }
            cur.appendCodePoint(cp);
            w += cw;
            i += Character.charCount(cp);
        }
        out.add(cur.toString());
        return out;
    }

    /** Chừa trống ô cuối: viết vào góc dưới-phải làm nhiều terminal tự xuống dòng và cuộn mất dòng tên cột. */
    private static String statusLine(String text, int width) {
        int w = Math.max(0, width - 1);
        String t = truncate(text, w);
        return STATUS + t + " ".repeat(Math.max(0, w - displayWidth(t))) + RESET;
    }
}
