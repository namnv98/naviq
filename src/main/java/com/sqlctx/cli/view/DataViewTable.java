package com.sqlctx.cli.view;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jline.builtins.Less;
import org.jline.builtins.Options;
import org.jline.builtins.Source;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.WCWidth;

public class DataViewTable {

    private static final int DEFAULT_MAX_FIELD_WIDTH = 5000;

    private static final boolean ASCII =
        System.getProperty("os.name").toLowerCase().contains("win");

    public static void print(
            Terminal terminal,
            List<String> columns,
            List<List<String>> rows,
            Runnable afterScreenCleared) throws Exception {

        int termWidth = effectiveWidth(terminal);
        int[] widths = calcWidths(columns, rows, DEFAULT_MAX_FIELD_WIDTH);
        int tableWidth = calcTableWidth(widths);

        PrintWriter out = terminal.writer();


        if (tableWidth <= termWidth) {
            int termHeight = terminal.getHeight();
            // Border trên + header + border giữa + border dưới = 4 dòng khung, cộng số dòng dữ liệu.
            int contentLines = rows.size() + 4;
            if (termHeight > 0 && contentLines > termHeight) {
                paginate(terminal, render(columns, rows, widths));
                return;
            }
            out.print(render(columns, rows, widths));
            out.flush();
            return;
        }

        showTruncated(out, columns, rows, truncateWidths(widths, termWidth), termWidth);

        Attributes savedAttrs = terminal.enterRawMode();
        try {
            NonBlockingReader in = terminal.reader();
            int width = termWidth;
            int height = terminal.getHeight();
            // Chờ phím kiểu thăm dò để vẽ lại bảng khi đổi cỡ cửa sổ (terminal xếp lại làm bảng cũ bị vỡ).
            while (in.peek(150) == NonBlockingReader.READ_EXPIRED) {
                if (terminal.getWidth() == width && terminal.getHeight() == height) {
                    continue;
                }
                width = terminal.getWidth();
                height = terminal.getHeight();
                out.print("\u001b[H\u001b[2J");
                afterScreenCleared.run();
                if (calcTableWidth(widths) <= width) {
                    out.print(render(columns, rows, widths));
                    out.flush();
                    return;
                }
                showTruncated(out, columns, rows, truncateWidths(widths, width), width);
            }

            int ch = in.read();
            if (ch == 'f' || ch == 'F') {
                out.print("\r\u001b[2K");
                out.flush();
                paginate(terminal, "\n\n" + render(columns, rows, widths));
            } else if (ch == 27) {
                discardEscapeSequence(terminal);
            }
        } finally {
            terminal.setAttributes(savedAttrs);
            out.print("\r\u001b[2K");
            out.flush();
        }
    }

    private static void showTruncated(PrintWriter out, List<String> columns, List<List<String>> rows,
                                      int[] truncatedWidths, int termWidth) {
        out.print(render(columns, rows, truncatedWidths));
        out.print("\n");
        // Gợi ý phải nằm gọn trên 1 dòng, nếu không lúc xoá dòng gợi ý sẽ để lại phần bị xuống dòng.
        String hint = " [table truncated — press f to view full, any other key to skip]";
        if (hint.length() >= termWidth) {
            hint = " [truncated — f: full view, any key: skip]";
        }
        if (hint.length() >= termWidth) {
            hint = " [f: full, key: skip]";
        }
        out.print("\u001b[33m" + hint + "\u001b[0m");
        out.flush();
    }

    /**
     * In các dòng thô (không vẽ khung bảng) - dùng cho EXPLAIN/EXPLAIN ANALYZE, nội dung đã tự có cấu
     * trúc cây thụt lề riêng, bó vào khung ô vuông chỉ làm rối và cắt cụt dòng dài vô ích. Vẫn tự phân
     * trang qua "less" khi số dòng vượt chiều cao terminal, giống print() cho bảng thường.
     */
    public static void printPlain(Terminal terminal, List<String> lines) throws Exception {
        int termHeight = terminal.getHeight();
        String content = String.join("\n", lines) + "\n";
        if (termHeight > 0 && lines.size() > termHeight) {
            paginate(terminal, content);
            return;
        }
        terminal.writer().print(content);
        terminal.writer().flush();
    }

    /** Bản không hỏi phím/không phân trang của printPlain() - dùng cho batch (--file/--command), không có TTY thật. */
    public static void printPlainStatic(Terminal terminal, List<String> lines) {
        terminal.writer().print(String.join("\n", lines) + "\n");
        terminal.writer().flush();
    }

    /** In bảng vừa khít terminal, không hỏi phím: dùng khi cần vẽ lại kết quả cũ (đổi cỡ cửa sổ). */
    public static void printStatic(Terminal terminal, List<String> columns, List<List<String>> rows) {
        int width = effectiveWidth(terminal);
        int[] widths = calcWidths(columns, rows, DEFAULT_MAX_FIELD_WIDTH);
        if (calcTableWidth(widths) > width) {
            widths = truncateWidths(widths, width);
        }
        terminal.writer().print(render(columns, rows, widths));
        terminal.writer().flush();
    }

    /**
     * terminal.getWidth() trả về 0 khi stdout không phải TTY thật (bị pipe/redirect ra file - đúng tình
     * huống của chế độ batch --file/--command) - không có gì để "vừa khít" cả, nên KHÔNG cắt bớt cột gì
     * hết thay vì lỡ hiểu 0 là "cực hẹp" và cắt trụi mọi cột thành "...".
     */
    private static int effectiveWidth(Terminal terminal) {
        int w = terminal.getWidth();
        return w > 0 ? w : Integer.MAX_VALUE;
    }

    /** Phím mũi tên / F-key gửi cả chuỗi ESC[...; bỏ phần đuôi để nó không lọt vào dòng lệnh kế tiếp. */
    private static void discardEscapeSequence(Terminal terminal) throws Exception {
        while (terminal.reader().peek(30) >= 0) {
            terminal.reader().read();
        }
    }


    private static void paginate(Terminal terminal, String content) throws Exception {
        Less less =
            new Less(
                terminal,
                Paths.get("."),
                Options.compile(Less.usage()).parse(List.of("--chop-long-lines")));

        Source source = new Source.InputStreamSource(
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
            true,
            "content"
        );

        List<Source> sources = new ArrayList<>();
        sources.add(source);

        less.run(sources);
    }



    private static String render(
        List<String> columns,
        List<List<String>> rows,
        int[] widths) {

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        pw.println(borderTop(widths));
        pw.println(formatHeader(columns, widths));
        pw.println(borderMid(widths));

        boolean[] numericCol = computeNumericColumns(rows, widths.length);
        for (List<String> row : rows) {
            pw.println(formatRow(row, widths, numericCol));
        }

        pw.println(borderBot(widths));

        return sw.toString();
    }

    // Viền bảng (khung + dấu "│" ngăn cột) - xám mờ, đứng lùi phía sau để dữ liệu/header nổi bật hơn thay vì
    // cạnh tranh cùng 1 màu trắng phẳng như trước.
    private static final String BORDER_COLOR = "\u001b[38;5;240m";

    private static String border(String glyph) {
        return BORDER_COLOR + glyph + RESET;
    }

    private static String borderTop(int[] widths) {
        StringBuilder sb = new StringBuilder().append(BORDER_COLOR).append(ASCII ? "+" : "╭");
        for (int i = 0; i < widths.length; i++) {
            sb.append((ASCII ? "-" : "─").repeat(widths[i] + 2));
            sb.append(i < widths.length - 1
                ? (ASCII ? "+" : "┬")
                : (ASCII ? "+" : "╮"));
        }
        return sb.append(RESET).toString();
    }

    private static String borderMid(int[] widths) {
        StringBuilder sb = new StringBuilder().append(BORDER_COLOR).append(ASCII ? "+" : "├");
        for (int i = 0; i < widths.length; i++) {
            sb.append((ASCII ? "-" : "─").repeat(widths[i] + 2));
            sb.append(i < widths.length - 1
                ? (ASCII ? "+" : "┼")
                : (ASCII ? "+" : "┤"));
        }
        return sb.append(RESET).toString();
    }

    private static String borderBot(int[] widths) {
        StringBuilder sb = new StringBuilder().append(BORDER_COLOR).append(ASCII ? "+" : "╰");
        for (int i = 0; i < widths.length; i++) {
            sb.append((ASCII ? "-" : "─").repeat(widths[i] + 2));
            sb.append(i < widths.length - 1
                ? (ASCII ? "+" : "┴")
                : (ASCII ? "+" : "╯"));
        }
        return sb.append(RESET).toString();
    }

    // -------------------------------------------------------------------------
    // Tô màu giá trị theo kiểu dữ liệu suy đoán từ chuỗi hiển thị (không cần biết kiểu SQL thật) - giống
    // cách pgcli/mycli làm nổi bật số/NULL/boolean giữa 1 rừng text trong bảng kết quả.
    // -------------------------------------------------------------------------

    private static final java.util.regex.Pattern NUMBER_PATTERN =
            java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final java.util.regex.Pattern DATE_PATTERN =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}([ T].*)?");
    private static final String NUM_COLOR = "\u001b[38;5;215m";
    private static final String NULL_COLOR = "\u001b[38;5;242m";
    private static final String BOOL_COLOR = "\u001b[38;5;140m";
    private static final String DATE_COLOR = "\u001b[38;5;108m";

    private static String styleForValue(String cell) {
        if ("<null>".equals(cell)) {
            return NULL_COLOR;
        }
        if (cell.isEmpty()) {
            return "";
        }
        if (cell.equalsIgnoreCase("true") || cell.equalsIgnoreCase("false")) {
            return BOOL_COLOR;
        }
        if (NUMBER_PATTERN.matcher(cell).matches()) {
            return NUM_COLOR;
        }
        if (DATE_PATTERN.matcher(cell).matches()) {
            return DATE_COLOR;
        }
        return "";
    }

    /** Cột toàn số (bỏ qua NULL/rỗng) -> căn phải, giống quy ước psql - dễ so sánh độ lớn giữa các dòng. */
    private static boolean[] computeNumericColumns(List<List<String>> rows, int cols) {
        boolean[] numeric = new boolean[cols];
        boolean[] sawAny = new boolean[cols];
        Arrays.fill(numeric, true);
        for (List<String> row : rows) {
            for (int i = 0; i < cols; i++) {
                String v = i < row.size() ? clean(row.get(i)) : "";
                if (v.isEmpty() || "<null>".equals(v)) {
                    continue;
                }
                sawAny[i] = true;
                if (!NUMBER_PATTERN.matcher(v).matches()) {
                    numeric[i] = false;
                }
            }
        }
        for (int i = 0; i < cols; i++) {
            if (!sawAny[i]) {
                numeric[i] = false;
            }
        }
        return numeric;
    }

    private static String formatRow(List<String> cells, int[] widths, boolean[] numericCol) {
        StringBuilder sb = new StringBuilder().append(border(ASCII ? "|" : "│"));

        for (int i = 0; i < widths.length; i++) {
            String raw = i < cells.size() ? clean(cells.get(i)) : "";
            String cell = truncate(raw, widths[i]);
            String style = styleForValue(cell);
            int pad = widths[i] - displayWidth(cell);
            boolean rightAlign = numericCol[i];

            sb.append(" ");
            if (rightAlign && pad > 0) {
                sb.append(" ".repeat(pad));
            }
            sb.append(style.isEmpty() ? cell : style + cell + RESET);
            if (!rightAlign && pad > 0) {
                sb.append(" ".repeat(pad));
            }
            sb.append(" ").append(border(ASCII ? "|" : "│"));
        }

        return sb.toString();
    }

    private static final String HEADER_COLOR = "\u001b[1;36m";
    private static final String RESET = "\u001b[0m";

    private static String formatHeader(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder().append(border(ASCII ? "|" : "│"));

        for (int i = 0; i < widths.length; i++) {
            String raw = i < cells.size() ? clean(cells.get(i)) : "";
            String cell = truncate(raw, widths[i]);

            String colored = HEADER_COLOR + cell + RESET;

            sb.append(" ").append(colored);

            int pad = widths[i] - displayWidth(cell);
            if (pad > 0) {
                sb.append(" ".repeat(pad));
            }

            sb.append(" ").append(border(ASCII ? "|" : "│"));
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
                if (tw[i] > tw[maxIdx]) {
                    maxIdx = i;
                }
            }
            if (tw[maxIdx] <= 3) {
                break;
            }
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

    /** Xuống dòng / tab -> 1 khoảng trắng; bỏ mọi ký tự điều khiển khác (kể cả ESC) để không phá vỡ bảng. */
    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t') {
                sb.append(' ');
            } else if (!Character.isISOControl(cp)) {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Truncate
    // -------------------------------------------------------------------------

    private static String truncate(String s, int maxWidth) {
        if (displayWidth(s) <= maxWidth) {
            return s;
        }

        StringBuilder sb = new StringBuilder();
        int w = 0;

        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cw = charWidth(cp);

            // 🔥 dùng ASCII "..." thay vì "…"
            if (w + cw > maxWidth - 3) {
                sb.append("...");
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
            width += charWidth(cp);
            i += Character.charCount(cp);
        }
        return width;
    }

    /** Số ô terminal mà 1 ký tự chiếm: 0 cho dấu kết hợp (ZWJ, dấu tiếng Việt tách rời), 2 cho CJK / emoji. */
    private static int charWidth(int cp) {
        return Math.max(0, WCWidth.wcwidth(cp));
    }
}