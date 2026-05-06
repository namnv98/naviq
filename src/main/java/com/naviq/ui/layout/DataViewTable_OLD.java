package com.naviq.ui.layout;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

public class DataViewTable_OLD {

    private static final int DEFAULT_MAX_FIELD_WIDTH = 5000;

    public static void print(
            Terminal terminal,
            List<String> columns,
            List<List<String>> rows) throws Exception {

        int termWidth = terminal.getWidth();
        int[] widths = calcWidths(columns, rows, DEFAULT_MAX_FIELD_WIDTH);
        int tableWidth = calcTableWidth(widths);

        PrintWriter out = terminal.writer();


        if (tableWidth <= termWidth) {
            out.print(render(columns, rows, widths));
            out.flush();
            return;
        }

        int[] truncatedWidths = truncateWidths(widths, termWidth);

        out.print(render(columns, rows, truncatedWidths));
        out.print("\n");
        out.print(" \u001b[33m[table truncated — press f to view full, any other key to skip]\u001b[0m");
        out.flush();

        Attributes savedAttrs = terminal.enterRawMode();
        try {
            while (true) {
                int ch = terminal.input().read(); // ← đổi reader() thành input()

                // debug tạm
                out.print("\r\u001b[2K[key=" + ch + "]");
                out.flush();

                if (ch == 'f' || ch == 'F') {
                    out.print("\r\u001b[2K");
                    out.flush();
                    paginate(terminal, "\n\n" + render(columns, rows, widths));
                    break;
                }
                if (ch == 'q' || ch == 'Q' || ch == 13 || ch == 3 || ch == 4 || ch < 0) {
                    break;
                }
            }
        } finally {
            terminal.setAttributes(savedAttrs);
            out.print("\r\u001b[2K");
            out.flush();
        }
    }

    // -------------------------------------------------------------------------
    // Pager
    // -------------------------------------------------------------------------

    private static void paginate(Terminal terminal, String content) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("less", "-S", "-R", "-X", "+1");
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process p = pb.start();
        try (OutputStream os = p.getOutputStream()) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        p.waitFor();
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    private static String render(
            List<String> columns,
            List<List<String>> rows,
            int[] widths) {

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        pw.println(borderTop(widths));
        pw.println(formatHeader(columns, widths));
        pw.println(borderMid(widths));

        for (List<String> row : rows) {
            pw.println(formatRow(row, widths));
        }

        pw.println(borderBot(widths));

        return sw.toString();
    }

    private static String borderTop(int[] widths) {
        StringBuilder sb = new StringBuilder().append("╭");
        for (int i = 0; i < widths.length; i++) {
            sb.append("─".repeat(widths[i] + 2));
            sb.append(i < widths.length - 1 ? "┬" : "╮");
        }
        return sb.toString();
    }

    private static String borderMid(int[] widths) {
        StringBuilder sb = new StringBuilder().append("├");
        for (int i = 0; i < widths.length; i++) {
            sb.append("─".repeat(widths[i] + 2));
            sb.append(i < widths.length - 1 ? "┼" : "┤");
        }
        return sb.toString();
    }

    private static String borderBot(int[] widths) {
        StringBuilder sb = new StringBuilder().append("╰");
        for (int i = 0; i < widths.length; i++) {
            sb.append("─".repeat(widths[i] + 2));
            sb.append(i < widths.length - 1 ? "┴" : "╯");
        }
        return sb.toString();
    }

    private static String formatRow(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder().append("│");

        for (int i = 0; i < widths.length; i++) {
            String raw = i < cells.size() ? clean(cells.get(i)) : "";
            String cell = truncate(raw, widths[i]);

            sb.append(" ").append(cell);

            int pad = widths[i] - displayWidth(cell);
            if (pad > 0) sb.append(" ".repeat(pad));

            sb.append(" │");
        }

        return sb.toString();
    }

    private static final String HEADER_COLOR = "\u001b[1;36m"; // bold cyan
    private static final String RESET = "\u001b[0m";

    private static String formatHeader(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder().append("│");

        for (int i = 0; i < widths.length; i++) {
            String raw  = i < cells.size() ? clean(cells.get(i)) : "";
            String cell = truncate(raw, widths[i]);

            // 🔥 bọc màu
            String colored = HEADER_COLOR + cell + RESET;

            sb.append(" ").append(colored);

            int pad = widths[i] - displayWidth(cell);
            if (pad > 0) sb.append(" ".repeat(pad));

            sb.append(" │");
        }

        return sb.toString();
    }
    // -------------------------------------------------------------------------
    // Width helpers
    // -------------------------------------------------------------------------

    private static int[] calcWidths(List<String> columns, List<List<String>> rows, int maxFieldWidth) {
        int cols = columns.size();
        int[] widths = new int[cols];

        for (int i = 0; i < cols; i++) {
            widths[i] = Math.min(displayWidth(clean(columns.get(i))), maxFieldWidth);
        }
        for (List<String> row : rows) {
            for (int i = 0; i < Math.min(cols, row.size()); i++) {
                widths[i] = Math.min(
                        Math.max(widths[i], displayWidth(clean(row.get(i)))),
                        maxFieldWidth
                );
            }
        }
        return widths;
    }

    private static int calcTableWidth(int[] widths) {
        int total = 1;
        for (int w : widths) {
            total += w + 3; // " " + content + " " + "|"
        }
        return total;
    }

    private static int[] truncateWidths(int[] widths, int termWidth) {
        int[] tw = widths.clone();

        // Bước 1: thu cột rộng nhất trước
        while (calcTableWidth(tw) > termWidth) {
            int maxIdx = 0;
            for (int i = 1; i < tw.length; i++) {
                if (tw[i] > tw[maxIdx]) maxIdx = i;
            }
            if (tw[maxIdx] <= 3) break;
            tw[maxIdx]--;
        }

        // Bước 2: nếu vẫn không vừa (quá nhiều cột) → cắt bớt cột từ cuối
        if (calcTableWidth(tw) > termWidth) {
            int visibleCols = tw.length;
            while (visibleCols > 1 && calcTableWidth(Arrays.copyOf(tw, visibleCols)) > termWidth) {
                visibleCols--;
            }
            tw = Arrays.copyOf(tw, visibleCols);
        }

        return tw;
    }
    // -------------------------------------------------------------------------
    // Clean
    // -------------------------------------------------------------------------

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", "");
    }

    // -------------------------------------------------------------------------
    // Truncate
    // -------------------------------------------------------------------------

    private static String truncate(String s, int maxWidth) {
        if (displayWidth(s) <= maxWidth) return s;

        StringBuilder sb = new StringBuilder();
        int w = 0;

        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cw = isFullWidth(cp) ? 2 : 1;

            if (w + cw > maxWidth - 1) {
                sb.append("…");
                break;
            }

            sb.appendCodePoint(cp);
            w += cw;
            i += Character.charCount(cp);
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Unicode display width
    // -------------------------------------------------------------------------

    private static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            width += isFullWidth(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return width;
    }

    private static boolean isFullWidth(int cp) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(cp);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES
                || b == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || (cp >= 0xFF01 && cp <= 0xFF60)
                || (cp >= 0x1F300 && cp <= 0x1F9FF);
    }
}