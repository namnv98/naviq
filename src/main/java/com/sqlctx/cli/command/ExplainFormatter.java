package com.sqlctx.cli.command;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tô màu output của EXPLAIN/EXPLAIN ANALYZE (Postgres) và DBMS_XPLAN (Oracle) - thay vì bó khung bảng
 * ô vuông từng dòng, vốn làm rối và cắt cụt dòng dài (cost=/actual time=... thường rất dài).
 */
public final class ExplainFormatter {

    private ExplainFormatter() {
    }

    // "(cost=0.00..1.10 rows=10 width=40)" hoặc "(actual time=0.010..0.015 rows=10 loops=1)".
    private static final Pattern EXPLAIN_METRICS = Pattern.compile("\\((?:cost=|actual time=)[^)]*\\)");
    // Dòng chi tiết dạng "Nhãn: giá trị" (Filter:, Index Cond:, Buffers:, Planning Time:, ...).
    private static final Pattern EXPLAIN_LABEL_LINE = Pattern.compile("^(\\s*)([A-Za-z][A-Za-z0-9 ]*):(\\s.*)?$");
    private static final Pattern EXPLAIN_NEVER_EXECUTED = Pattern.compile("\\(never executed\\)");

    private static final String YELLOW = "\u001b[33m";
    private static final String RESET = "\u001b[0m";

    public static String colorizeLine(String line) {
        Matcher label = EXPLAIN_LABEL_LINE.matcher(line);
        if (label.matches()) {
            String indent = label.group(1);
            String key = label.group(2);
            String value = label.group(3) == null ? "" : label.group(3);
            // "Planning Time"/"Execution Time" là 2 dòng tổng kết quan trọng nhất - nổi bật hơn hẳn các
            // dòng chi tiết còn lại (Filter/Index Cond/Buffers/...).
            boolean summary = key.equalsIgnoreCase("Planning Time") || key.equalsIgnoreCase("Execution Time");
            String keyColor = summary ? "\u001b[1;35m" : YELLOW;
            return indent + keyColor + key + ":" + RESET + value;
        }
        // Oracle DBMS_XPLAN.DISPLAY tự vẽ sẵn khung ASCII ("| Id | Operation | ... |", dòng viền toàn
        // "-"/"+") - khác hẳn kiểu cây "->" của Postgres. Khung này đã đủ rõ ràng, tô 1 màu đồng loạt lên
        // cả bảng (kể cả viền) chỉ làm rối chứ không giúp đọc dễ hơn - để nguyên không màu.
        String stripped = line.strip();
        if (!stripped.isEmpty() && (stripped.contains("|") || stripped.chars().allMatch(c -> c == '-' || c == '+'))) {
            return line;
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = EXPLAIN_METRICS.matcher(line);
        int last = 0;
        while (m.find()) {
            sb.append(colorizeNodeText(line.substring(last, m.start())));
            sb.append("\u001b[90m").append(m.group()).append(RESET); // cost/actual time - dim gray, phụ trợ
            last = m.end();
        }
        sb.append(colorizeNodeText(line.substring(last)));
        return EXPLAIN_NEVER_EXECUTED.matcher(sb.toString())
                .replaceAll("\u001b[1;31m(never executed)" + RESET);
    }

    /** Tô đậm/cyan tên node (Seq Scan, Hash Join, ...); mũi tên cây "->" giữ mờ để tên node nổi bật hơn. */
    private static String colorizeNodeText(String text) {
        int i = 0;
        while (i < text.length() && text.charAt(i) == ' ') {
            i++;
        }
        String indent = text.substring(0, i);
        String rest = text.substring(i);
        if (rest.isEmpty()) {
            return text;
        }
        if (rest.startsWith("->")) {
            return indent + "\u001b[2m->" + RESET + " \u001b[1;36m" + rest.substring(2).stripLeading() + RESET;
        }
        return indent + "\u001b[1;36m" + rest + RESET;
    }
}
