package com.sqlctx.shell;

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
    public static final String SELECTED = "\u001b[7m";

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
        out.add(color + "┌─" + RESET + (headWidth > 0 ? (focused ? HEADER : "") + fit(head, headWidth) + color : color)
                + "─".repeat(Math.max(0, width - 3 - headWidth)) + "┐" + RESET);
        for (int i = 0; i < height - 2; i++) {
            String line = i < body.size() ? body.get(i) : "";
            out.add(color + "│" + RESET + " " + fit(line, width - 4) + " " + color + "│" + RESET);
        }
        out.add(color + "└" + "─".repeat(width - 2) + "┘" + RESET);
        return out;
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
        int full = (int) Math.round(f * width);
        String color = f >= bad ? RED : f >= warn ? YELLOW : GREEN;
        return BORDER + "[" + RESET + color + "█".repeat(full) + RESET + BORDER + "░".repeat(width - full) + "]" + RESET;
    }

    private static final char[] SPARK = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    /** {@code width} giá trị cuối, cao theo giá trị lớn nhất trong khoảng đó (toàn 0 thì vạch thấp nhất). */
    public static String spark(List<Double> values, int width) {
        List<Double> tail = values.subList(Math.max(0, values.size() - width), values.size());
        double max = tail.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        StringBuilder sb = new StringBuilder(BORDER);
        sb.append(String.valueOf(SPARK[0]).repeat(width - tail.size())).append(GREEN); // chưa đủ mẫu: vạch mờ
        for (double v : tail) {
            sb.append(max <= 0 ? SPARK[0] : SPARK[(int) Math.round(v / max * (SPARK.length - 1))]);
        }
        return sb.append(RESET).toString();
    }

    /** 19920 -> "19.9k", 1.2e6 -> "1.2M". */
    public static String compact(double v) {
        if (v >= 1e9) {
            return String.format("%.1fG", v / 1e9);
        }
        if (v >= 1e6) {
            return String.format("%.1fM", v / 1e6);
        }
        if (v >= 1e4) {
            return String.format("%.1fk", v / 1e3);
        }
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    /** {@code align}: 'l' trái, 'r' phải (số, tô màu số). */
    public record Column(String header, char align, int min) {
    }

    /**
     * Bảng không viền cho bên trong {@link #box}: dòng tiêu đề + các dòng, cột co cho vừa {@code width}; dòng
     * {@code selected} đảo màu khi {@code focused}, cuộn sao cho dòng đó luôn hiện. Đúng tối đa {@code height} dòng.
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
        if (total < width && n > 0) {
            w[0] += width - total; // cột đầu (tên) lấy phần dư
        }
        List<String> out = new ArrayList<>();
        StringBuilder head = new StringBuilder();
        for (int c = 0; c < n; c++) {
            head.append(c == 0 ? "" : " ").append(HEADER)
                    .append(cols.get(c).align() == 'r' ? right(cols.get(c).header(), w[c]) : fit(cols.get(c).header(), w[c]));
        }
        out.add(head.toString());
        int visible = Math.max(0, height - 1);
        int first = selected < visible ? 0 : selected - visible + 1;
        for (int i = first; i < rows.size() && out.size() <= visible; i++) {
            boolean sel = focused && i == selected;
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < n; c++) {
                String cell = rows.get(i).get(c);
                boolean num = cols.get(c).align() == 'r';
                String colored = sel ? cell : num && cell.matches("[-+]?[0-9.,]+[kMG%]?") ? NUMBER + cell : cell;
                line.append(c == 0 ? "" : " ").append(sel ? SELECTED : "")
                        .append(num ? right(colored, w[c]) : fit(colored, w[c]));
                if (sel) {
                    line.append(SELECTED);
                }
            }
            out.add(line + RESET);
        }
        return out;
    }
}
