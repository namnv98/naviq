package com.sqlctx.cli.repl;

import com.sqlctx.util.I18n;

import com.sqlctx.cli.command.ContextManager;
import com.sqlctx.cli.command.EditorLauncher;
import com.sqlctx.cli.command.ResultExporter;
import com.sqlctx.cli.command.SchemaCommands;
import com.sqlctx.cli.command.StatementExecutor;
import com.sqlctx.cli.view.BottomStatusBar;
import com.sqlctx.datasource.ConnectionProfile;
import com.sqlctx.datasource.ConnectionProfileStore;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import org.jline.reader.LineReader;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;

import java.nio.file.Files;
import java.nio.file.Paths;

import static com.sqlctx.cli.session.SqlctxSession.*;

/**
 * Nhận diện + xử lý 1 dòng người dùng vừa Enter trong REPL: lệnh CLI (\ dt/\ctx/\o/\e/...) thì tự xử
 * lý xong tại đây (giữ nguyên dialect), câu SQL thường thì chuyển cho StatementExecutor. Trả về dialect
 * hiện tại (chỉ đổi khi "\ctx <tên>" nhảy sang 1 kết nối dialect khác).
 */
public final class CommandDispatcher {

    private CommandDispatcher() {
    }

    // Các lệnh CLI (không phải SQL) không cần ';' để chấp nhận dòng, kể cả khi đang multi-line.
    public static boolean isMetaCommand(String line) {
        String t = line.trim();
        String lower = t.toLowerCase();
        return t.equalsIgnoreCase("help") || t.equalsIgnoreCase("\\?")
                || t.equalsIgnoreCase("exit") || t.equalsIgnoreCase("\\q")
                || t.equalsIgnoreCase("\\l") || t.equalsIgnoreCase("\\list")
                || t.equalsIgnoreCase("\\dt") || t.equalsIgnoreCase("\\dn") || t.equalsIgnoreCase("\\df")
                || t.equalsIgnoreCase("\\du") || t.equalsIgnoreCase("\\refresh")
                || t.equalsIgnoreCase("\\lang") || lower.startsWith("\\lang ")
                || t.equalsIgnoreCase("\\o") || lower.startsWith("\\o ")
                || t.equalsIgnoreCase("\\e")
                || t.equalsIgnoreCase("\\ctx") || t.equalsIgnoreCase("\\context")
                || lower.startsWith("\\c ") || lower.startsWith("\\connect ")
                || lower.startsWith("\\d ") || lower.startsWith("\\i ")
                || lower.startsWith("\\ctx ") || lower.startsWith("\\context ")
                || t.isEmpty();
    }

    public static void printHelp(LineReader reader) {
        String text = I18n.t("sql.help");

        reader.printAbove(text);
    }

    /**
     * "SLQ>" tĩnh trước đây không cho biết đang ở db nào - đổi thành "dbname>", giống quy ước
     * psql/mycli/pgcli luôn hiện database hiện tại ngay trong prompt thay vì 1 chuỗi cố định chung
     * chung không mang thông tin gì. Tên context đã hiện riêng ở status bar (dbLabel()) rồi, không lặp
     * lại ở đây nữa. Đọc lại DB_DBNAME MỖI LẦN gọi (không cache) để tự cập nhật ngay sau \c/\ctx.
     */
    public static String buildPrompt() {
        String dbName = System.getProperty("DB_DBNAME", "?");
        return "\u001B[32m" + dbName + "> " + RESET;
    }

    /** @return dialect hiện tại (giữ nguyên, trừ khi vừa "\ctx <tên>" sang 1 kết nối dialect khác). */
    public static Dialect dispatch(String line, Dialect dialect, LineReaderImpl impl, Terminal terminal,
                                    BottomStatusBar statusBar) {
        // Chỉ toàn khoảng trắng (sau khi trim ở ReplLoop thành rỗng) - không có gì để chạy, chỉ vẽ lại
        // status bar (vd sau khi trồi lên từ 1 trạng thái khác) rồi thôi.
        if (line.isEmpty()) {
            statusBar.render();
            return dialect;
        }

        if (line.equalsIgnoreCase("help") || line.equalsIgnoreCase("\\?")) {
            printHelp(impl);
            statusBar.render();
            return dialect;
        }

        if (line.equalsIgnoreCase("\\lang") || line.toLowerCase().startsWith("\\lang ")) {
            boolean[] ok = new boolean[1];
            String msg = I18n.langCommand(line.length() > 5 ? line.substring(5) : null, ok);
            impl.printAbove(ok[0] ? msg : RED + msg + RESET);
            statusBar.render();
            return dialect;
        }

        if (line.equalsIgnoreCase("\\refresh")) {
            // người khác đổi schema (hay DDL chạy ngoài sqlctx) thì gợi ý không tự biết - nạp lại theo yêu cầu
            try {
                SchemaIndex.reload(dialect);
                impl.printAbove(I18n.t("sql.schemaReloaded", SchemaIndex.schemaTableIndex.size()));
            } catch (Exception e) {
                impl.printAbove("\u001B[31mERROR: " + e.getMessage() + RESET);
            }
            statusBar.render();
            return dialect;
        }

        if (line.equalsIgnoreCase("\\l") || line.equalsIgnoreCase("\\list")) {
            SchemaCommands.listDatabases(impl, dialect);
            statusBar.render();
            return dialect;
        }

        if (line.toLowerCase().startsWith("\\c ") || line.toLowerCase().startsWith("\\connect ")) {
            String target = line.substring(line.indexOf(' ') + 1).trim();
            if (target.isEmpty()) {
                say(impl, RED + I18n.t("sql.err.missingDb") + RESET);
            } else {
                try {
                    statusBar.setQueryStatus("CONNECTING...");
                    statusBar.render();
                    ContextManager.switchDatabase(target, dialect);
                    statusBar.setDbInfo(ContextManager.dbLabel(dialect));
                    say(impl, I18n.t("sql.switchedDb", target));
                    statusBar.setQueryStatus("IDLE");
                } catch (Exception e) {
                    say(impl, RED + I18n.t("sql.err.switchDb", target, e.getMessage()) + RESET);
                    statusBar.setQueryStatus("ERROR");
                }
            }
            statusBar.render();
            return dialect;
        }

        if (line.equalsIgnoreCase("\\ctx") || line.equalsIgnoreCase("\\context")) {
            ContextManager.listContexts(impl);
            return dialect;
        }

        if (line.toLowerCase().startsWith("\\ctx save ") || line.toLowerCase().startsWith("\\context save ")) {
            String name = line.substring(line.toLowerCase().indexOf("save ") + 5).trim();
            if (name.isEmpty()) {
                say(impl, RED + I18n.t("sql.err.missingCtxName") + RESET);
            } else {
                try {
                    ConnectionProfileStore.save(new ConnectionProfile(name, dialect,
                            System.getProperty("DB_HOST"), System.getProperty("DB_PORT"),
                            System.getProperty("DB_DBNAME"), System.getProperty("DB_USER"),
                            System.getProperty("DB_PASSWORD")));
                    say(impl, I18n.t("sql.ctxSaved", name));
                } catch (Exception e) {
                    say(impl, RED + I18n.t("sql.err.save", e.getMessage()) + RESET);
                }
            }
            return dialect;
        }

        if (line.toLowerCase().startsWith("\\ctx ") || line.toLowerCase().startsWith("\\context ")) {
            Dialect result = dialect;
            String target = line.substring(line.indexOf(' ') + 1).trim();
            try {
                ConnectionProfile targetProfile;
                if (target.equals("-")) {
                    if (previousConnection == null) {
                        throw new IllegalStateException(I18n.t("sql.err.noPrevious"));
                    }
                    targetProfile = previousConnection;
                } else if (target.isEmpty()) {
                    throw new IllegalStateException(I18n.t("sql.err.missingCtx"));
                } else {
                    targetProfile = ConnectionProfileStore.find(target)
                            .orElseThrow(() -> new IllegalStateException(I18n.t("sql.err.unknownCtx", target)));
                }
                // Tên hiện ra trong lời xác nhận LUÔN lấy từ chính targetProfile (nơi SẮP chuyển TỚI) -
                // "(trước đó)" chỉ khi connection sắp quay lại (qua \ctx -) chưa từng được đặt tên.
                String label = targetProfile.name() != null ? targetProfile.name() : I18n.t("sql.previous");

                statusBar.setQueryStatus("CONNECTING...");
                statusBar.render();
                // Chụp lại connection ĐANG SỐNG (dù chưa từng có tên, vd lúc mới mở CLI bằng --host= tay)
                // TRƯỚC khi đổi - để "\ctx -" quay lại được đúng chỗ, không chỉ quay lại được context ĐÃ ĐẶT TÊN.
                previousConnection = new ConnectionProfile(currentContextName, dialect,
                        System.getProperty("DB_HOST"), System.getProperty("DB_PORT"),
                        System.getProperty("DB_DBNAME"), System.getProperty("DB_USER"),
                        System.getProperty("DB_PASSWORD"));
                result = ContextManager.switchContext(targetProfile, impl);
                currentContextName = targetProfile.name();
                // Chỉ nhớ lại khi context có TÊN THẬT - "\ctx -" quay về 1 connection tay chưa từng đặt
                // tên thì không có gì đáng nhớ để tự nối lại đúng chỗ đó ở lần chạy CLI kế tiếp.
                if (currentContextName != null) {
                    ConnectionProfileStore.setLastContextName(currentContextName);
                }
                statusBar.setDbInfo(ContextManager.dbLabel(result));
                say(impl, I18n.t("sql.switchedCtx", label, result, targetProfile.host(), targetProfile.port(), targetProfile.dbname()));
                statusBar.setQueryStatus("IDLE");
            } catch (Exception e) {
                say(impl, RED + I18n.t("sql.err.switchCtx", e.getMessage()) + RESET);
                statusBar.setQueryStatus("ERROR");
            }
            statusBar.render();
            return result;
        }

        if (line.equalsIgnoreCase("\\dt")) {
            SchemaCommands.describeTables(impl, terminal, statusBar);
            return dialect;
        }
        if (line.equalsIgnoreCase("\\dn")) {
            SchemaCommands.describeSchemas(impl, terminal, statusBar);
            return dialect;
        }
        if (line.equalsIgnoreCase("\\df")) {
            SchemaCommands.describeFunctions(impl, terminal, statusBar);
            return dialect;
        }
        if (line.equalsIgnoreCase("\\du")) {
            SchemaCommands.describeUsers(dialect, impl, terminal, statusBar);
            return dialect;
        }
        if (line.toLowerCase().startsWith("\\d ")) {
            SchemaCommands.describeTable(line.substring(3).trim(), dialect, impl, terminal, statusBar);
            return dialect;
        }

        if (line.toLowerCase().startsWith("\\i ")) {
            String path = line.substring(3).trim();
            try {
                int rc = StatementExecutor.runStatements(
                        StatementExecutor.splitStatements(Files.readString(Paths.get(path))),
                        dialect, impl, terminal, statusBar, true);
                if (rc != 0) {
                    say(impl, RED + I18n.t("sql.scriptStopped") + RESET);
                }
            } catch (Exception e) {
                say(impl, RED + I18n.t("sql.err.readFile", path, e.getMessage()) + RESET);
            }
            return dialect;
        }

        if (line.toLowerCase().startsWith("\\o ")) {
            String path = line.substring(3).trim();
            if (path.isEmpty()) {
                say(impl, RED + I18n.t("sql.err.missingFile") + RESET);
            } else {
                outputFile = Paths.get(path);
                outputFormat = ResultExporter.formatFor(path);
                if ("text".equals(outputFormat)) {
                    say(impl, I18n.t("sql.exportOnText", path));
                } else {
                    say(impl, I18n.t("sql.exportOnNext", path, outputFormat));
                }
            }
            return dialect;
        }
        if (line.equalsIgnoreCase("\\o")) {
            if (outputFile != null) {
                say(impl, I18n.t("sql.exportOff", outputFile));
                outputFile = null;
                outputFormat = null;
            } else {
                say(impl, I18n.t("sql.exportNone"));
            }
            return dialect;
        }

        if (line.equalsIgnoreCase("\\e")) {
            // ReplLoop đọc pendingBuffer sau khi dispatch trả về - dùng chung field static ở đây để
            // không phải phình chữ ký dispatch() chỉ vì 1 lệnh hiếm khi dùng.
            try {
                pendingEditorBuffer = EditorLauncher.openInEditor(terminal, lastStatement);
            } catch (Exception e) {
                say(impl, RED + I18n.t("sql.err.editor", e.getMessage()) + RESET);
            }
            return dialect;
        }

        // Người dùng có thể gõ/dán nhiều statement cách nhau bằng ';' trong CÙNG 1 lần Enter (paste
        // nhiều dòng, hoặc gõ ở chế độ multi-line) - tách ra chạy TỪNG câu một, giống hệt \i/--file/
        // --command đã làm từ trước. Thiếu bước này khiến Oracle (chỉ chấp nhận 1 statement/execute)
        // báo lỗi "ORA-03405: End of query reached" khi 2 statement dính liền nhau trong 1 chuỗi gửi
        // thẳng cho ojdbc.
        StatementExecutor.runStatements(StatementExecutor.splitStatements(line), dialect, impl, terminal, statusBar, true);
        return dialect;
    }

    /** \e nạp lại buffer đã sửa vào lần readLine() KẾ TIẾP - ReplLoop đọc rồi tự xoá field này. */
    public static volatile String pendingEditorBuffer;
}
