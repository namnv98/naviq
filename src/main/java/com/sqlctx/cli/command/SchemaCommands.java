package com.sqlctx.cli.command;

import com.sqlctx.cli.view.BottomStatusBar;
import com.sqlctx.cli.view.DataViewTable;
import com.sqlctx.dialect.DialectAdapters;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.sqlctx.cli.session.SqlctxSession.RED;
import static com.sqlctx.cli.session.SqlctxSession.RESET;
import static com.sqlctx.cli.session.SqlctxSession.say;

/**
 * \l / \dt / \dn / \df / \du / \d <table> — xem cấu trúc, đọc từ SchemaIndex đã nạp sẵn (không hỏi lại
 * DB) trừ \d <table> (cần thêm khoá chính, SchemaIndex không lưu) và \du/\l (không cache, luôn hỏi DB).
 */
public final class SchemaCommands {

    private SchemaCommands() {
    }

    public static void listDatabases(LineReader reader, Dialect dialect) {
        try {
            var result = DialectAdapters.of(dialect).listDatabases();
            String note = result.note() != null ? result.note() : "";
            say(reader, note + String.join("\n", result.names()) + "\n");
        } catch (Exception e) {
            say(reader, RED + "ERROR: " + e.getMessage() + RESET);
        }
    }

    public static void describeTables(LineReader reader, Terminal terminal, BottomStatusBar statusBar) {
        List<String> headers = List.of("Schema", "Name", "Type");
        List<List<String>> rows = new ArrayList<>();
        for (var s : SchemaIndex.schemas) {
            for (var t : s.tables()) {
                rows.add(List.of(s.name(), t.name(), t.kind()));
            }
        }
        printSimpleTable(reader, terminal, statusBar, rows.isEmpty() ? "(không có bảng/view nào)\n" : null, headers, rows);
    }

    public static void describeSchemas(LineReader reader, Terminal terminal, BottomStatusBar statusBar) {
        List<String> headers = List.of("Schema", "Tables/views");
        List<List<String>> rows = new ArrayList<>();
        for (var s : SchemaIndex.schemas) {
            rows.add(List.of(s.name(), String.valueOf(s.tables().size())));
        }
        printSimpleTable(reader, terminal, statusBar, rows.isEmpty() ? "(không có schema nào)\n" : null, headers, rows);
    }

    public static void describeFunctions(LineReader reader, Terminal terminal, BottomStatusBar statusBar) {
        List<String> headers = List.of("Function");
        List<List<String>> rows = SchemaIndex.functions.stream().map(List::of).toList();
        printSimpleTable(reader, terminal, statusBar, rows.isEmpty() ? "(không có hàm nào)\n" : null, headers, rows);
    }

    /** \du - khác \dt/\dn/\df ở chỗ SchemaIndex không cache sẵn user/role, phải hỏi DB trực tiếp mỗi lần gọi. */
    public static void describeUsers(Dialect dialect, LineReader reader, Terminal terminal, BottomStatusBar statusBar) {
        try {
            var result = DialectAdapters.of(dialect).describeUsers();
            printSimpleTable(reader, terminal, statusBar, result.rows().isEmpty() ? "(không có user/role nào)\n" : null,
                    result.headers(), result.rows());
        } catch (Exception e) {
            say(reader, RED + "ERROR: " + e.getMessage() + RESET);
        }
    }

    public static void describeTable(String tableName, Dialect dialect, LineReader reader, Terminal terminal, BottomStatusBar statusBar) {
        var table = SchemaIndex.tableIndex.get(tableName.toLowerCase());
        if (table == null) {
            say(reader, RED + "ERROR: không thấy bảng/view \"" + tableName + "\"" + RESET);
            return;
        }
        Set<String> pk;
        try {
            pk = DialectAdapters.of(dialect).primaryKeyColumns(table.name());
        } catch (Exception e) {
            pk = Set.of(); // không lấy được PK (thiếu quyền, tên bảng lạ...) - vẫn hiện được cột, chỉ thiếu cột "Key"
        }
        List<String> headers = List.of("Column", "Type", "Nullable", "Key");
        List<List<String>> rows = new ArrayList<>();
        for (var c : table.columns()) {
            rows.add(List.of(c.name(), c.dataType(), c.notNull() ? "not null" : "", pk.contains(c.name().toLowerCase()) ? "PK" : ""));
        }
        say(reader, "Table \"" + table.fullName() + "\" (" + table.kind() + ")\n");
        printSimpleTable(reader, terminal, statusBar, null, headers, rows);
    }

    private static void printSimpleTable(LineReader reader, Terminal terminal, BottomStatusBar statusBar,
                                         String emptyMessage, List<String> headers, List<List<String>> rows) {
        if (rows.isEmpty() && emptyMessage != null) {
            say(reader, emptyMessage);
            return;
        }
        try {
            DataViewTable.print(terminal, headers, rows, statusBar::redraw);
        } catch (Exception e) {
            say(reader, RED + "ERROR: " + e.getMessage() + RESET);
        }
    }
}
