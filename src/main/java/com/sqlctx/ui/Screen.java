package com.sqlctx.ui;

import org.jline.utils.WCWidth;

import java.util.ArrayList;
import java.util.List;

/**
 * Vẽ ô, bảng, thanh %, sparkline cho {@link Dashboard}. Mọi chuỗi có thể chứa mã màu ANSI: độ rộng tính theo ô
 * trên màn hình (bỏ mã màu, ký tự rộng 2 ô như chữ CJK tính 2), cắt/đệm không làm vỡ mã màu.
 */
public final class Screen {

    private Screen() {
    }

    public static final String RESET = "\u001b[0m";
    public static final String DIM = "\u001b[38;5;245m";
    public static final String BORDER = "\u001b[38;5;240m";
    public static final String FOCUS = "\u001b[36m";
    public static final String HEADER = "\u001b[1;36m";
    public static final String NUMBER = "\u001b[38;5;141m";
    public static final String GREEN = "\u001b[32m";
    public static final String YELLOW = "\u001b[33m";
    public static final String RED = "\u001b[31m";
    /** Dòng đang chọn của bảng đang được chọn: chữ trắng đậm trên nền xanh. */
    public static final String SELECTED = "\u001b[1;38;5;231;48;5;31m";
    /** Dòng đang chọn của bảng khác: chữ màu chọn, không tô nền - vẫn thấy con trỏ đang ở đâu. */
    public static final String SELECTED_DIM = "\u001b[1;38;5;75m";

    /** Tách chuỗi nhiều dòng (bảng của DataViewTable.renderFit) thành từng dòng, bỏ dòng trống cuối. */
    public static List<String> lines(String block) {
        return List.of(block.replace("\r", "").stripTrailing().split("\n"));
    }

    /** Số ô màn hình mà {@code s} chiếm (bỏ qua mã ANSI). */
    public static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) == '\u001b') {
                i = skipEscape(s, i);
                continue;
            }
            int cp = s.codePointAt(i);
            w += Math.max(0, WCWidth.wcwidth(cp));
            i += Character.charCount(cp);
        }
        return w;
    }

    private static int skipEscape(String s, int i) {
        int j = i + 1;
        if (j < s.length() && s.charAt(j) == '[') {
            j++;
            while (j < s.length() && (s.charAt(j) < '@' || s.charAt(j) > '~')) {
                j++;
            }
            return Math.min(s.length(), j + 1);
        }
        return Math.min(s.length(), j + 1);
    }

    /** Cắt hoặc đệm khoảng trắng cho đúng {@code w} ô; bị cắt thì kết thúc bằng "…". Luôn đóng màu ở cuối. */
    public static String fit(String s, int w) {
        if (w <= 0) {
            return "";
        }
        int total = width(s);
        if (total <= w) {
            return s + RESET + " ".repeat(w - total);
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) == '\u001b') {
                int j = skipEscape(s, i);
                sb.append(s, i, j);
                i = j;
                continue;
            }
            int cp = s.codePointAt(i);
            int cw = Math.max(0, WCWidth.wcwidth(cp));
            if (used + cw > w - 1) {
                break;
            }
            sb.appendCodePoint(cp);
            used += cw;
            i += Character.charCount(cp);
        }
        return sb + "…" + RESET + " ".repeat(Math.max(0, w - used - 1));
    }

    /** Bỏ mọi mã ANSI. */
    public static String plain(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) == '\u001b') {
                i = skipEscape(s, i);
            } else {
                sb.append(s.charAt(i++));
            }
        }
        return sb.toString();
    }

    /** Căn phải trong {@code w} ô (số). */
    public static String right(String s, int w) {
        int total = width(s);
        return total >= w ? fit(s, w) : " ".repeat(w - total) + s + RESET;
    }

    /** Ô có viền, tiêu đề nằm trên viền trên; {@code focused}: viền sáng (bảng đang được chọn). Đúng {@code height} dòng. */
    public static List<String> box(String title, List<String> body, int width, int height, boolean focused) {
        List<String> out = new ArrayList<>();
        if (height < 2 || width < 4) {
            return out;
        }
        String color = focused ? FOCUS : BORDER;
        String head = title == null || title.isEmpty() ? "" : " " + title + " ";
        int headWidth = Math.min(width(head), width - 4);
        out.add(color + "╭─" + RESET + (headWidth > 0 ? (focused ? HEADER : TITLE) + fit(head, headWidth) + color : color)
                + "─".repeat(Math.max(0, width - 3 - headWidth)) + "╮" + RESET);
        for (int i = 0; i < height - 2; i++) {
            String line = i < body.size() ? body.get(i) : "";
            out.add(color + "│" + RESET + " " + fit(line, width - 4) + " " + color + "│" + RESET);
        }
        out.add(color + "╰" + "─".repeat(width - 2) + "╯" + RESET);
        return out;
    }

    /** Tiêu đề ô không được chọn: trắng đậm (viền mờ, chữ vẫn đọc rõ). */
    public static final String TITLE = "\u001b[1;38;5;252m";

    // ------------------------------------------------------------------ ô số liệu (KPI)

    /** 1 ô số liệu lớn: nhãn, giá trị (đã tô màu theo mức), dòng phụ; {@code trend} (có thể null): đường xu hướng bên phải giá trị. */
    public record Tile(String label, String value, String sub, List<Double> trend, String trendColor) {
        public Tile(String label, String value, String sub) {
            this(label, value, sub, null, null);
        }
    }

    /** Hàng ô số liệu chia đều bề ngang, mỗi ô 5 dòng (viền + nhãn + giá trị + dòng phụ + viền). */
    public static List<String> tiles(List<Tile> tiles, int width) {
        int n = tiles.size();
        List<String> out = new ArrayList<>(List.of("", "", "", "", ""));
        int used = 0;
        for (int i = 0; i < n; i++) {
            int w = i == n - 1 ? width - used : width / n;
            used += w;
            Tile t = tiles.get(i);
            String value = "\u001b[1m" + t.value();
            int sparkWidth = Math.min(16, (w - 4) / 2);
            if (t.trend() != null && w - 4 - width(value) >= sparkWidth + 2) {
                value = fit(value, w - 4 - sparkWidth) + spark(t.trend(), sparkWidth, t.trendColor());
            }
            List<String> box = box(t.label(), List.of(value, DIM + t.sub()), w, 4, false);
            for (int r = 0; r < 4; r++) {
                out.set(r, out.get(r) + box.get(r));
            }
        }
        out.remove(4);
        return out;
    }

    // ------------------------------------------------------------------ biểu đồ vùng

    private static final char[] EIGHTHS = {' ', '▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    /** Dải màu của biểu đồ vùng, từ đáy (đậm) lên đỉnh (sáng). */
    public static final int[] GREEN_RAMP = {22, 28, 34, 71, 77, 114, 120, 157};
    public static final int[] BLUE_RAMP = {24, 25, 26, 32, 33, 39, 75, 117};
    public static final int[] ORANGE_RAMP = {94, 130, 136, 172, 178, 214, 220, 228};
    public static final int[] PURPLE_RAMP = {54, 55, 56, 92, 98, 135, 141, 183};

    /**
     * Biểu đồ vùng cao {@code height} dòng (kể cả trục): trục Y bên trái ghi giá trị lớn nhất, giữa và 0, có đường lưới mờ;
     * mỗi cột 1 mẫu (mẫu mới nhất ở bên phải), độ cao chia 1/8 ô cho mịn, màu chuyển dần theo {@code ramp} từ đáy lên.
     * Chưa đủ mẫu thì phần bên trái để trống.
     */
    public static List<String> area(List<Double> values, int width, int height, int[] ramp) {
        return area(values, width, height, ramp, Screen::compact);
    }

    /** {@code label}: định dạng số trên trục Y (vd byte). */
    public static List<String> area(List<Double> values, int width, int height, int[] ramp,
                                    java.util.function.DoubleFunction<String> label) {
        double max = values.subList(Math.max(0, values.size() - width), values.size()).stream()
                .mapToDouble(Double::doubleValue).max().orElse(0);
        int mid = height >= 5 ? height / 2 : -1;
        String[] labels = new String[height];
        labels[0] = label.apply(max);
        if (mid > 0) {
            labels[mid] = label.apply(max * (height - 1 - mid) / (height - 1));
        }
        labels[height - 1] = "0";
        int axis = 0;
        for (String l : labels) {
            axis = Math.max(axis, l == null ? 0 : l.length());
        }
        int plot = width - axis - 2;
        List<Double> tail = values.subList(Math.max(0, values.size() - plot), values.size());
        int offset = plot - tail.size();
        List<String> out = new ArrayList<>();
        for (int row = 0; row < height; row++) {
            int levelBelow = (height - 1 - row) * 8; // số 1/8 ô nằm dưới dòng này
            boolean grid = labels[row] != null && row != height - 1;
            String color = "\u001b[38;5;" + ramp[(height - 1 - row) * (ramp.length - 1) / Math.max(1, height - 1)] + "m";
            StringBuilder sb = new StringBuilder(DIM).append(labels[row] == null ? " ".repeat(axis) : right(labels[row], axis))
                    .append(" ").append(BORDER).append(labels[row] == null ? "│" : "┤");
            String last = BORDER;
            for (int x = 0; x < plot; x++) {
                String cell;
                String cellColor;
                if (x < offset) {
                    cell = grid && x % 2 == 0 ? "·" : " ";
                    cellColor = BORDER;
                } else {
                    double v = tail.get(x - offset);
                    int level = max <= 0 ? 0 : (int) Math.round(v / max * height * 8);
                    int part = Math.max(0, Math.min(8, level - levelBelow));
                    if (row == height - 1 && level == 0) {
                        cell = "▁"; // đường 0: thấy được trục
                        cellColor = BORDER;
                    } else if (part == 0) {
                        cell = grid && x % 2 == 0 ? "·" : " ";
                        cellColor = BORDER;
                    } else {
                        cell = String.valueOf(EIGHTHS[part]);
                        cellColor = color;
                    }
                }
                if (!cellColor.equals(last)) {
                    sb.append(cellColor);
                    last = cellColor;
                }
                sb.append(cell);
            }
            out.add(sb.append(RESET).toString());
        }
        return out;
    }

    // ------------------------------------------------------------------ màu theo mức

    /** Xanh nếu {@code v} < warn, vàng nếu < bad, còn lại đỏ. */
    public static String level(double v, double warn, double bad) {
        return v >= bad ? RED : v >= warn ? YELLOW : GREEN;
    }

    /** Nút phím ở dòng cuối: " q " đảo màu + mô tả. {@code keys}: cặp phím, mô tả. */
    public static String keys(int width, String... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            sb.append(HEADER).append(pairs[i]).append(RESET).append(" ").append(DIM)
                    .append(pairs[i + 1]).append(RESET).append("   ");
        }
        return fit(sb.toString(), width);
    }

    /** Xếp các mục thành dòng vừa {@code width}, cách nhau {@code sep}; hết chỗ thì xuống dòng thay vì cắt mất chữ. */
    public static List<String> flow(List<String> parts, String sep, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int used = 0;
        for (String p : parts) {
            int w = width(p);
            if (used > 0 && used + width(sep) + w > width) {
                out.add(line.toString());
                line.setLength(0);
                used = 0;
            }
            if (used > 0) {
                line.append(sep);
                used += width(sep);
            }
            line.append(p);
            used += w;
        }
        if (used > 0) {
            out.add(line.toString());
        }
        return out;
    }

    /** Ghép 2 khối cạnh nhau theo từng dòng (khối ngắn hơn được đệm). */
    public static List<String> beside(List<String> a, int aWidth, List<String> b) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            out.add((i < a.size() ? a.get(i) : " ".repeat(aWidth)) + (i < b.size() ? b.get(i) : ""));
        }
        return out;
    }

    /** Thanh tỉ lệ [████░░░░]; xanh < {@code warn} <= vàng < {@code bad} <= đỏ. */
    public static String bar(double fraction, int width, double warn, double bad) {
        double f = Math.max(0, Math.min(1, fraction));
        int eighths = (int) Math.round(f * width * 8);
        int full = eighths / 8;
        String color = level(f, warn, bad);
        StringBuilder sb = new StringBuilder(color).append("█".repeat(full));
        if (full < width) {
            sb.append(eighths % 8 == 0 ? BORDER + "·" : PARTIAL[eighths % 8] + BORDER);
            sb.append("·".repeat(width - full - 1));
        }
        return sb.append(RESET).toString();
    }

    /** Khối 1/8 theo chiều ngang (trái sang phải). */
    private static final char[] PARTIAL = {' ', '▏', '▎', '▍', '▌', '▋', '▊', '▉'};

    /** Bit của chấm braille theo dòng (0 = trên cùng): cột trái, cột phải. */
    private static final int[] DOT_LEFT = {0x01, 0x02, 0x04, 0x40};
    private static final int[] DOT_RIGHT = {0x08, 0x10, 0x20, 0x80};

    /**
     * Đường xu hướng mảnh bằng chấm braille, mỗi ô 2 mẫu, 4 mức (0 nằm sát đáy, khác 0 luôn nhích lên 1 mức), cao theo
     * giá trị lớn nhất trong {@code 2 * width} mẫu cuối. Chưa đủ mẫu thì phần bên trái là chấm mờ ở đáy.
     */
    public static String spark(List<Double> values, int width) {
        return spark(values, width, GREEN);
    }

    public static String spark(List<Double> values, int width, String color) {
        int n = width * 2;
        List<Double> tail = values.subList(Math.max(0, values.size() - n), values.size());
        double max = tail.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        int offset = n - tail.size();
        StringBuilder sb = new StringBuilder();
        String last = "";
        for (int c = 0; c < width; c++) {
            int bits = 0;
            boolean empty = true;
            for (int k = 0; k < 2; k++) {
                int i = c * 2 + k - offset;
                int row;
                if (i < 0) {
                    row = 3;
                } else {
                    empty = false;
                    double v = tail.get(i);
                    row = v <= 0 || max <= 0 ? 3 : 2 - (int) Math.round(v / max * 2);
                }
                bits |= (k == 0 ? DOT_LEFT : DOT_RIGHT)[row];
            }
            String col = empty ? BORDER : color;
            if (!col.equals(last)) {
                sb.append(col);
                last = col;
            }
            sb.appendCodePoint(0x2800 + bits);
        }
        return sb.append(RESET).toString();
    }

    /** 19920 -> "19.9k", 1.2e6 -> "1.2M". */
    /** "45s", "3m05s", "2h05m", "3d04h". */
    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return String.format("%dm%02ds", s / 60, s % 60);
        }
        if (s < 86400) {
            return String.format("%dh%02dm", s / 3600, s % 3600 / 60);
        }
        return String.format("%dd%02dh", s / 86400, s % 86400 / 3600);
    }

    public static String compact(double v) {
        if (v >= 1e9) {
            return String.format("%.1fG", v / 1e9);
        }
        if (v >= 1e6) {
            return String.format("%.1fM", v / 1e6);
        }
        if (v >= 1e3) {
            return String.format("%.1fk", v / 1e3);
        }
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    /** {@code align}: 'l' trái, 'r' phải (số, tô màu số). */
    public record Column(String header, char align, int min) {
    }

    /**
     * Bảng không viền cho bên trong {@link #box}: dòng tiêu đề + các dòng, cột co cho vừa {@code width}; dòng
     * {@code selected} (-1 = không có) được tô cả dòng - đậm khi {@code focused}, nhạt khi không - và luôn được cuộn
     * cho hiện ra. Đúng tối đa {@code height} dòng.
     */
    public static List<String> table(List<Column> cols, List<List<String>> rows, int width, int height, int selected,
                                     boolean focused) {
        int n = cols.size();
        int[] w = new int[n];
        for (int c = 0; c < n; c++) {
            w[c] = Math.max(cols.get(c).min(), width(cols.get(c).header()));
            for (List<String> r : rows) {
                w[c] = Math.max(w[c], width(r.get(c)));
            }
        }
        int total = n - 1;
        for (int x : w) {
            total += x;
        }
        while (total > width) { // co cột rộng nhất tới khi vừa
            int widest = 0;
            for (int c = 1; c < n; c++) {
                if (w[c] > w[widest]) {
                    widest = c;
                }
            }
            if (w[widest] <= 3) {
                break;
            }
            w[widest]--;
            total--;
        }
        // còn dư chỗ: cột đầu (tên) thêm vài ô, rồi nới khoảng cách giữa các cột (tối đa 4 ô) - số liệu không bị dạt hết
        // sang mép phải; phần còn dư nữa để trống bên phải
        int gap = 1;
        if (total < width && n > 0) {
            int extra = width - total;
            int first = n == 1 ? extra : Math.min(extra, 4);
            w[0] += first;
            extra -= first;
            if (n > 1) {
                gap += Math.min(3, extra / (n - 1));
            }
        }
        String sep = " ".repeat(gap);
        List<String> out = new ArrayList<>();
        StringBuilder head = new StringBuilder();
        for (int c = 0; c < n; c++) {
            head.append(c == 0 ? "" : sep).append(HEADER)
                    .append(cols.get(c).align() == 'r' ? right(cols.get(c).header(), w[c]) : fit(cols.get(c).header(), w[c]));
        }
        out.add(head.toString());
        int visible = Math.max(0, height - 1);
        int first = selected < visible ? 0 : selected - visible + 1;
        int tableWidth = (n - 1) * gap;
        for (int x : w) {
            tableWidth += x;
        }
        for (int i = first; i < rows.size() && out.size() <= visible; i++) {
            StringBuilder line = new StringBuilder();
            if (i == selected) {
                // tô 1 lần cho cả dòng: chữ thuần (bỏ màu riêng của từng ô) căn đúng cột, đệm tới hết bề ngang bảng -
                // không có RESET giữa dòng nên vệt tô liền, không đứt ở khoảng trắng giữa các cột
                for (int c = 0; c < n; c++) {
                    String cell = plain(rows.get(i).get(c));
                    line.append(c == 0 ? "" : sep).append(plain(cols.get(c).align() == 'r' ? right(cell, w[c]) : fit(cell, w[c])));
                }
                int pad = Math.max(0, width - width(line.toString()));
                out.add((focused ? SELECTED : SELECTED_DIM) + line + " ".repeat(pad) + RESET);
                continue;
            }
            for (int c = 0; c < n; c++) {
                String cell = rows.get(i).get(c);
                boolean num = cols.get(c).align() == 'r';
                String colored = num && cell.matches("[-+]?[0-9.,]+[kMG%]?") ? NUMBER + cell : cell;
                line.append(c == 0 ? "" : sep).append(num ? right(colored, w[c]) : fit(colored, w[c]));
            }
            out.add(line + " ".repeat(Math.max(0, width - tableWidth)) + RESET);
        }
        return out;
    }
}
