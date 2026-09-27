package com.sqlctx.cli.command;

import com.sqlctx.cli.view.BottomStatusBar;
import com.sqlctx.cli.view.DataViewTable;
import com.sqlctx.dialect.DialectAdapters;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.sqlctx.cli.session.SqlctxSession.RED;
import static com.sqlctx.cli.session.SqlctxSession.RESET;
import static com.sqlctx.cli.session.SqlctxSession.YELLOW;
import static com.sqlctx.cli.session.SqlctxSession.connection;
import static com.sqlctx.cli.session.SqlctxSession.lastOutput;
import static com.sqlctx.cli.session.SqlctxSession.lastStatement;
import static com.sqlctx.cli.session.SqlctxSession.outputFile;
import static com.sqlctx.cli.session.SqlctxSession.outputFormat;
import static com.sqlctx.cli.session.SqlctxSession.say;

/**
 * Tách + chạy statement (dùng chung cho REPL, \i, --file, --command) - và mọi thứ quanh việc đó: xác
 * nhận trước lệnh phá hoại, format kết quả (bảng/EXPLAIN/xuất file), báo lỗi.
 */
public final class StatementExecutor {

    private StatementExecutor() {
    }

    /**
     * Tách nhiều statement theo dấu ';' - tránh dấu ';' nằm TRONG chuỗi '...' (không xử lý '' escape bên
     * trong chuỗi) và TRONG dollar-quoting $$...$$/$tag$...$tag$ của Postgres (thân hàm CREATE FUNCTION
     * hay chứa ';' và '$$' bọc ngoài - không tránh thì tách vỡ ngay giữa thân hàm).
     */
    public static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        String dollarTag = null; // null = không ở trong dollar-quote; khác null = tag đang mở (vd "$$", "$body$")
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (dollarTag != null) {
                cur.append(c);
                if (c == '$' && sql.startsWith(dollarTag, i - dollarTag.length() + 1)) {
                    dollarTag = null; // vừa gặp lại ĐÚNG tag đã mở -> đóng dollar-quote
                }
                continue;
            }
            if (!inString && c == '$') {
                String tag = matchDollarTag(sql, i);
                if (tag != null) {
                    dollarTag = tag;
                    cur.append(tag);
                    i += tag.length() - 1;
                    continue;
                }
            }
            if (c == '\'' && dollarTag == null) {
                inString = !inString;
                cur.append(c);
            } else if (c == ';' && !inString) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * "$$" hoặc "$tag$" đứng tại vị trí i - trả về CHUỖI TAG đầy đủ (kèm 2 dấu $) nếu khớp, null nếu không.
     * Theo đúng quy tắc Postgres: tag rỗng ("$$") luôn hợp lệ; tag có tên phải bắt đầu bằng chữ/gạch dưới
     * (không phải chữ số) - để không nhầm với tham số vị trí kiểu "$1".
     */
    private static String matchDollarTag(String sql, int i) {
        int j = i + 1;
        if (j < sql.length() && sql.charAt(j) == '$') {
            return sql.substring(i, j + 1);
        }
        if (j < sql.length() && (Character.isLetter(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
            while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                j++;
            }
            if (j < sql.length() && sql.charAt(j) == '$') {
                return sql.substring(i, j + 1);
            }
        }
        return null;
    }

    /** @return 0 nếu tất cả statement chạy thành công, 1 nếu có statement lỗi (dừng ngay tại đó). */
    public static int runStatements(List<String> statements, Dialect dialect, LineReader impl, Terminal terminal,
                                     BottomStatusBar statusBar, boolean interactive) {
        for (String stmt : statements) {
            String trimmedStmt = stmt.strip();
            if (trimmedStmt.isEmpty()) {
                continue;
            }
            if (!executeStatement(trimmedStmt, dialect, impl, terminal, statusBar, interactive)) {
                return 1;
            }
        }
        return 0;
    }

    private static boolean isDestructive(String trimmedUpper) {
        return trimmedUpper.startsWith("DROP") || trimmedUpper.startsWith("TRUNCATE")
                || trimmedUpper.startsWith("DELETE") || trimmedUpper.startsWith("UPDATE")
                || trimmedUpper.startsWith("ALTER");
    }

    private static boolean isSchemaChanging(String trimmedUpper) {
        return trimmedUpper.startsWith("CREATE") || trimmedUpper.startsWith("DROP")
                || trimmedUpper.startsWith("ALTER") || trimmedUpper.startsWith("TRUNCATE");
    }

    /** Hỏi lại trước khi chạy lệnh có thể phá dữ liệu - đọc 1 phím thật từ terminal (y/Y mới chạy tiếp). */
    private static boolean confirmProceed(Terminal terminal, String sql) {
        try {
            terminal.writer().print(YELLOW + "⚠ Câu lệnh sau có thể THAY ĐỔI/XOÁ dữ liệu:\n" + RESET
                    + sql + "\n" + YELLOW + "Chạy không? [y/N] " + RESET);
            terminal.writer().flush();
            var saved = terminal.enterRawMode();
            try {
                int ch = terminal.reader().read();
                terminal.writer().print("\r\n");
                terminal.writer().flush();
                return ch == 'y' || ch == 'Y';
            } finally {
                terminal.setAttributes(saved);
            }
        } catch (Exception e) {
            return false; // đọc phím lỗi -> an toàn là huỷ, không chạy nhầm
        }
    }

    public static boolean executeStatement(String sql, Dialect dialect, LineReader impl, Terminal terminal,
                                            BottomStatusBar statusBar, boolean interactive) {
        lastStatement = sql;
        String trimmed = sql.stripLeading().toUpperCase();

        if (interactive && isDestructive(trimmed) && !confirmProceed(terminal, sql)) {
            say(impl, "Đã huỷ.\n");
            return true; // người dùng chủ động không chạy - không tính là lỗi
        }

        statusBar.setQueryStatus("RUNNING...");
        statusBar.render();

        try {
            // Mỗi dialect tự quyết định có cần sửa lại text câu lệnh trước khi gửi driver không (vd
            // Oracle/ojdbc không chấp nhận dấu ';' cuối câu - quy ước của SQL*Plus/CLI, không phải cú
            // pháp SQL thật; Postgres/pgjdbc thì chấp nhận nên không cần cắt).
            String execLine = DialectAdapters.of(dialect).prepareStatementText(sql);

            long startTime = System.currentTimeMillis();

            // Statement.execute() dùng chung cho MỌI loại câu lệnh (SELECT/DML/DDL/GRANT/MERGE...) - tự biết
            // có ResultSet hay không, tránh phải đoán bằng cách so khớp tiền tố (từng bỏ sót ALTER/CREATE
            // INDEX/GRANT... khiến các lệnh đó rơi vào nhánh executeQuery() và luôn báo lỗi "not a result set").
            try (Statement stmt = connection(dialect).createStatement()) {
                boolean hasResultSet = stmt.execute(execLine);
                // "Query" = tới lúc DB trả về xong (round-trip execute), TÁCH RIÊNG khỏi "Fetch" = thời gian
                // đọc hết ResultSet phía client - phân biệt được chậm do DB hay do fetch/network nhiều dòng.
                long queryElapsed = System.currentTimeMillis() - startTime;
                String timeSuffix = "\nTime: " + String.format("%.3f", queryElapsed / 1000.0) + "s\n";

                if (hasResultSet) {
                    long fetchStart = System.currentTimeMillis();
                    List<String> headers = new ArrayList<>();
                    List<List<String>> rows = new ArrayList<>();
                    try (ResultSet rs = stmt.getResultSet()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int columnCount = meta.getColumnCount();
                        for (int i = 1; i <= columnCount; i++) {
                            headers.add(meta.getColumnLabel(i));
                        }
                        while (rs.next()) {
                            List<String> row = new ArrayList<>();
                            for (int i = 1; i <= columnCount; i++) {
                                Object val = rs.getObject(i);
                                row.add(val == null ? "<null>" : String.valueOf(val));
                            }
                            rows.add(row);
                        }
                    }
                    long fetchElapsed = System.currentTimeMillis() - fetchStart;
                    String timingPart = "Query: " + String.format("%.3f", queryElapsed / 1000.0) + "s  ·  "
                            + "Fetch: " + String.format("%.3f", fetchElapsed / 1000.0) + "s  ·  "
                            + "Total: " + String.format("%.3f", (queryElapsed + fetchElapsed) / 1000.0) + "s";
                    String rowsColsPart = rows.size() + " row(s), " + headers.size() + " col(s)";
                    String timeLine = rowsColsPart + "\n" + timingPart + "\n";
                    // EXPLAIN/EXPLAIN ANALYZE (Postgres) và SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY) (Oracle)
                    // đều trả về đúng 1 cột text đã tự có cấu trúc cây thụt lề - bó khung ô vuông từng dòng
                    // chỉ làm rối và cắt cụt dòng dài (cost=/actual time=... thường rất dài).
                    boolean isExplainPlan = headers.size() == 1
                            && ("QUERY PLAN".equalsIgnoreCase(headers.get(0))
                                    || "PLAN_TABLE_OUTPUT".equalsIgnoreCase(headers.get(0)));
                    // EXPLAIN (FORMAT JSON/XML/YAML) vẫn đặt tên cột y hệt "QUERY PLAN" nhưng trả về ĐÚNG 1
                    // hàng chứa cả khối JSON/XML/YAML gộp thành 1 chuỗi (kèm '\n' nhúng bên trong, không
                    // phải nhiều hàng như FORMAT TEXT) - không phải cây text nên bộ tô màu theo cost=/->
                    // không khớp gì cả, chỉ nên in thô (không box) chứ không tô nhầm màu.
                    boolean looksLikeStructuredFormat = isExplainPlan && rows.size() == 1
                            && !rows.get(0).isEmpty() && rows.get(0).get(0) != null
                            && rows.get(0).get(0).stripLeading().matches("(?s)^[\\[{<].*");
                    List<String> explainLines = isExplainPlan
                            ? rows.stream()
                                    .map(r -> r.isEmpty() ? "" : r.get(0))
                                    .map(line -> looksLikeStructuredFormat ? line : ExplainFormatter.colorizeLine(line))
                                    .collect(Collectors.toList())
                            : null;
                    if (outputFile != null) {
                        try {
                            ResultExporter.export(outputFile, outputFormat, headers, rows);
                            say(impl, "Đã xuất " + rows.size() + " dòng, " + headers.size() + " cột ra \""
                                    + outputFile + "\".\n" + timingPart + "\n");
                        } catch (IOException e) {
                            say(impl, RED + "ERROR: không ghi được file \"" + outputFile + "\" - " + e.getMessage() + RESET);
                        }
                        if (!"text".equals(outputFormat)) {
                            // csv/json là xuất 1 lần cho câu lệnh này rồi tự tắt - không thể ghi nối tiếp
                            // nhiều kết quả vào 1 file mà vẫn giữ đúng cú pháp csv/json hợp lệ.
                            outputFile = null;
                            outputFormat = null;
                        }
                    } else if (isExplainPlan) {
                        // Batch (--file/--command) không có TTY thật để trả lời "press key to continue" -
                        // printPlainStatic() không bao giờ dừng lại chờ phím, chỉ printPlain() ở phiên có
                        // người ngồi gõ mới an toàn (giống cặp print()/printStatic() ở bảng thường).
                        if (interactive) {
                            DataViewTable.printPlain(terminal, explainLines);
                        } else {
                            DataViewTable.printPlainStatic(terminal, explainLines);
                        }
                        // "N row(s)" không hợp ngữ nghĩa cho 1 execution plan (đây là DÒNG PLAN, không phải
                        // dòng dữ liệu) - chỉ hiện phần thời gian, bỏ rowsColsPart.
                        say(impl, timingPart + "\n");
                    } else {
                        // Batch (--file/--command) không có TTY thật để trả lời "press key to continue" -
                        // printStatic() không bao giờ dừng lại chờ phím, chỉ print() ở phiên có người ngồi gõ mới an toàn.
                        if (interactive) {
                            DataViewTable.print(terminal, headers, rows, statusBar::redraw);
                        } else {
                            DataViewTable.printStatic(terminal, headers, rows);
                        }
                        say(impl, timeLine);
                    }
                    lastOutput = () -> {
                        if (isExplainPlan) {
                            DataViewTable.printPlainStatic(terminal, explainLines);
                            terminal.writer().print(timingPart + "\n");
                        } else {
                            DataViewTable.printStatic(terminal, headers, rows);
                            terminal.writer().print(timeLine);
                        }
                    };
                } else {
                    int affected = stmt.getUpdateCount();
                    String table = ResultExporter.extractDmlTableName(sql);
                    String tableTag = table != null ? table + " " : "";
                    String msg;
                    if (trimmed.startsWith("INSERT")) {
                        msg = "INSERT " + tableTag + "OK — " + affected + " row(s) inserted.";
                    } else if (trimmed.startsWith("UPDATE")) {
                        msg = "UPDATE " + tableTag + "OK — " + affected + " row(s) affected.";
                    } else if (trimmed.startsWith("DELETE")) {
                        msg = "DELETE " + tableTag + "OK — " + affected + " row(s) affected.";
                    } else {
                        msg = "OK.";
                    }
                    say(impl, msg + timeSuffix);
                }
            }
            // Reload SAU KHI đã chạy xong (không phải trước) - schema mới phải phản ánh đúng những gì
            // câu lệnh VỪA làm, không phải trạng thái cũ trước khi chạy.
            if (isSchemaChanging(trimmed)) {
                SchemaIndex.reload(dialect);
            }
            statusBar.setQueryStatus("IDLE");
            return true;
        } catch (Exception ex) {
            reportError(impl, sql, ex);
            statusBar.setQueryStatus("ERROR");
            return false;
        }
    }

    private static void reportError(LineReader impl, String sql, Exception ex) {
        if (ex instanceof org.postgresql.util.PSQLException psql) {
            org.postgresql.util.ServerErrorMessage msg = psql.getServerErrorMessage();
            if (msg != null) {
                StringBuilder err = new StringBuilder();
                err.append(RED).append("ERROR: ").append(msg.getMessage()).append("\n");

                if (msg.getDetail() != null) {
                    err.append("DETAIL: ").append(msg.getDetail()).append("\n");
                }

                if (msg.getPosition() > 0) {
                    int pos = msg.getPosition() - 1; // 0-based
                    String[] lines = sql.split("\n");

                    int lineNum = 1;
                    int col = pos;
                    for (String l : lines) {
                        if (col <= l.length()) {
                            break;
                        }
                        col -= (l.length() + 1); // +1 cho \n
                        lineNum++;
                    }

                    String errorLine = lines[lineNum - 1];
                    err.append("LINE ").append(lineNum).append(": ").append(errorLine).append("\n");
                    err.append(" ".repeat("LINE ".length() + String.valueOf(lineNum).length() + ": ".length() + col)).append("^\n").append(RESET);
                }

                say(impl, err.toString());
                return;
            }
        }
        say(impl, RED + "ERROR: " + ex.getMessage() + RESET);
    }
}
