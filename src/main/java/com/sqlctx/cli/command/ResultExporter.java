package com.sqlctx.cli.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * \o — xuất kết quả câu lệnh ra file (csv/json xuất 1 lần, còn lại ghi log nối tiếp) - và tách tên bảng
 * bị ảnh hưởng bởi INSERT/UPDATE/DELETE để hiện kèm "OK" cho rõ.
 */
public final class ResultExporter {

    private ResultExporter() {
    }

    public static String formatFor(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".csv")) {
            return "csv";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        return "text";
    }

    public static void export(Path file, String format, List<String> headers, List<List<String>> rows)
            throws IOException {
        switch (format) {
            case "csv" -> Files.writeString(file, toCsv(headers, rows));
            case "json" -> Files.writeString(file, toJson(headers, rows));
            default -> Files.writeString(file, toPlainLog(headers, rows),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static String toCsv(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(headers.stream().map(ResultExporter::csvField).collect(Collectors.joining(","))).append("\n");
        for (List<String> row : rows) {
            sb.append(row.stream().map(ResultExporter::csvField).collect(Collectors.joining(","))).append("\n");
        }
        return sb.toString();
    }

    private static String csvField(String raw) {
        // "<null>" là sentinel hiển thị NULL trên bảng terminal, không nên xuất y nguyên chuỗi đó ra file dữ
        // liệu - quy ước CSV phổ biến cho NULL là để trống ô.
        String v = "<null>".equals(raw) ? "" : raw;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String toJson(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            sb.append("  {");
            for (int c = 0; c < headers.size(); c++) {
                String val = c < row.size() ? row.get(c) : null;
                sb.append("\"").append(jsonEscape(headers.get(c))).append("\":");
                sb.append(val == null || "<null>".equals(val) ? "null" : "\"" + jsonEscape(val) + "\"");
                if (c < headers.size() - 1) {
                    sb.append(",");
                }
            }
            sb.append("}").append(r < rows.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String toPlainLog(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(" | ", headers)).append("\n");
        for (List<String> row : rows) {
            sb.append(String.join(" | ", row)).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    // Chỉ tách bằng regex đơn giản (không phải parser SQL đầy đủ) - đủ dùng cho hình dạng INSERT
    // INTO/UPDATE/DELETE FROM thông thường, không cố xử lý CTE hay statement lồng nhau phức tạp.
    private static final Pattern INSERT_TABLE = Pattern.compile("(?i)insert\\s+into\\s+([\\w.\"]+)");
    private static final Pattern UPDATE_TABLE = Pattern.compile("(?i)update\\s+([\\w.\"]+)");
    private static final Pattern DELETE_TABLE = Pattern.compile("(?i)delete\\s+from\\s+([\\w.\"]+)");

    public static String extractDmlTableName(String sql) {
        for (Pattern p : List.of(INSERT_TABLE, UPDATE_TABLE, DELETE_TABLE)) {
            Matcher m = p.matcher(sql);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }
}
