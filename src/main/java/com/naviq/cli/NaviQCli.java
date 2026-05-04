package com.naviq.cli;

import com.naviq.completion.draft.PostgresCompletionEngine;
import com.naviq.datasource.PostgresDataSource;
import com.naviq.ui.layout.BottomStatusBar;
import com.naviq.ui.terminal.CustomHighlighter;
import com.naviq.ui.terminal.MenuCompleter;
import com.naviq.ui.layout.DataViewTable;
import org.jline.keymap.KeyMap;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class NaviQCli {

    private static boolean MULTI_LINE = false;

    private static final String RED = "\u001b[31m";
    private static final String RESET = "\u001b[0m";

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                String[] parts = arg.substring(2).split("=", 2);
                System.setProperty("DB_" + parts[0].toUpperCase(), parts[1]);
            }
        }

        String dbName = "SLQ";
        String visiblePrompt = dbName + "> ";
        String coloredPrompt = "\u001B[32m" + visiblePrompt + "\u001B[0m";

        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .streams(System.in, System.out)
                .encoding(StandardCharsets.UTF_8)
                .build();

        Highlighter highlighter = new CustomHighlighter();

        BottomStatusBar statusBar = new BottomStatusBar(terminal);
        Path historyFile = Paths.get("/home/namnv/Downloads/demo-antlr/sql_history.txt");

        LineReaderImpl impl = new LineReaderImpl(terminal, "SLQ", null) {
            @Override
            public boolean redisplay() {
                var result = super.redisplay();
                var c = getTerminal().getCursorPosition(null);
                if (c != null) statusBar.updateCursor(c.getY() + 1, c.getX() + 1);
                statusBar.render();
                return result;
            }
        };

        impl.setHighlighter(highlighter);
        impl.setVariable(LineReader.HISTORY_FILE, historyFile);
        impl.setVariable(LineReader.HISTORY_SIZE, 1000);
        impl.setVariable(LineReader.HISTORY_FILE_SIZE, 2000);
        impl.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, " ");
        impl.unsetOpt(LineReader.Option.HISTORY_BEEP);
        impl.setOpt(LineReader.Option.HISTORY_IGNORE_DUPS);
        impl.setOpt(LineReader.Option.HISTORY_IGNORE_SPACE);
        impl.setParser(new Parser() {
            @Override
            public ParsedLine parse(String line, int cursor, ParseContext context) throws SyntaxError {
                if (context == ParseContext.ACCEPT_LINE && MULTI_LINE) {
                    if (!line.trim().endsWith(";")) {
                        throw new EOFError(-1, cursor, "missing semicolon");
                    }
                }
                return new DefaultParser().parse(line, cursor, context);
            }
        });
        impl.setHistory(new org.jline.reader.impl.history.DefaultHistory(impl));

        MenuCompleter.register(impl);

        // =========================
        // KEYMAP
        // =========================
        KeyMap<Binding> keyMap = impl.getKeyMaps().get(LineReader.MAIN);

        keyMap.bind(new Reference("toggle-multiline"), "\u0014"); // Ctrl+T

        impl.getWidgets().put("toggle-multiline", () -> {
            MULTI_LINE = !MULTI_LINE;

            statusBar.toggleMultiLine();
            MenuCompleter.toggleMultiLine();
            statusBar.setQueryStatus("multi-line: " + (MULTI_LINE ? "ON" : "OFF"));
            statusBar.render();

            return true;
        });

        statusBar.setDbInfo("SQL > localhost/mydb");
        statusBar.setQueryStatus("idle");
        statusBar.render();

        terminal.handle(Terminal.Signal.WINCH, s -> statusBar.render());

        printStartupInfo(impl);


        while (true) {
            String line;

            try {
                line = impl.readLine(coloredPrompt);
            } catch (UserInterruptException e) {
                continue;
            } catch (EndOfFileException e) {
                break;
            }
            if (line == null || line.isEmpty()) {
                continue;
            }
            line = line.trim();

            if (line.equalsIgnoreCase("exit;") || line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("\\q")) {
                break;
            }

            if (line.equalsIgnoreCase("help") || line.equalsIgnoreCase("\\?")) {
                printHelp(impl);
                statusBar.render();
                continue;
            }

            if (line.isEmpty()) {
                statusBar.render();
                continue;
            }

            statusBar.setQueryStatus("RUNNING...");
            statusBar.render();

            try {
                String trimmed = line.stripLeading().toUpperCase();

                long startTime = System.currentTimeMillis();

                if (trimmed.startsWith("UPDATE") || trimmed.startsWith("DELETE")) {
                    try (Statement stmt = PostgresDataSource.get().createStatement()) {
                        int affected = stmt.executeUpdate(line);
                        long elapsed = System.currentTimeMillis() - startTime; // ← THÊM
                        String msg = "OK — " + affected + " row(s) affected.\nTime: " + String.format("%.3f", elapsed / 1000.0) + "s\n";
                        impl.printAbove(msg);
                    }
                    statusBar.setQueryStatus("IDLE");

                } else if (trimmed.startsWith("INSERT")) {
                    try (Statement stmt = PostgresDataSource.get().createStatement()) {
                        int affected = stmt.executeUpdate(line);
                        long elapsed = System.currentTimeMillis() - startTime; // ← THÊM
                        String msg = "INSERT OK — " + affected + " row(s) inserted.\nTime: " + String.format("%.3f", elapsed / 1000.0) + "s\n";
                        impl.printAbove(msg);
                    }
                    statusBar.setQueryStatus("IDLE");

                } else if (trimmed.startsWith("CREATE TABLE")
                        || trimmed.startsWith("DROP TABLE")
                        || trimmed.startsWith("TRUNCATE")) {
                    PostgresCompletionEngine.reloadDB();
                    try (Statement stmt = PostgresDataSource.get().createStatement()) {
                        stmt.execute(line);
                        long elapsed = System.currentTimeMillis() - startTime; // ← THÊM
                        String msg;
                        if (trimmed.startsWith("CREATE TABLE")) msg = "Table created.";
                        else if (trimmed.startsWith("DROP TABLE")) msg = "Table dropped.";
                        else msg = "Table truncated.";
                        impl.printAbove(msg + "\nTime: " + String.format("%.3f", elapsed / 1000.0) + "s\n");
                    }
                    statusBar.setQueryStatus("IDLE");

                } else {
                    List<String> headers = new ArrayList<>();
                    List<List<String>> rows = new ArrayList<>();

                    try (Statement stmt = PostgresDataSource.get().createStatement();
                         ResultSet rs = stmt.executeQuery(line)) {

                        ResultSetMetaData meta = rs.getMetaData();
                        int columnCount = meta.getColumnCount();
                        for (int i = 1; i <= columnCount; i++) headers.add(meta.getColumnLabel(i));

                        while (rs.next()) {
                            List<String> row = new ArrayList<>();
                            for (int i = 1; i <= columnCount; i++) {
                                Object val = rs.getObject(i);
                                row.add(val == null ? "<null>" : String.valueOf(val));
                            }
                            rows.add(row);
                        }
                    }

                    long elapsed = System.currentTimeMillis() - startTime; // ← THÊM
                    DataViewTable.print(terminal, headers, rows);
                    impl.printAbove("Time: " + String.format("%.3f", elapsed / 1000.0) + "s\n"); // ← THÊM
                    statusBar.setQueryStatus("IDLE");
                }
            } catch (Exception ex) {
                if (ex instanceof org.postgresql.util.PSQLException psql) {
                    org.postgresql.util.ServerErrorMessage msg = psql.getServerErrorMessage();
                    if (msg != null) {
                        StringBuilder err = new StringBuilder();
                        err.append(RED).append("ERROR: ").append(msg.getMessage()).append("\n");

                        if (msg.getDetail() != null)
                            err.append("DETAIL: ").append(msg.getDetail()).append("\n");

                        if (msg.getPosition() > 0) {
                            int pos = msg.getPosition() - 1; // 0-based
                            String[] lines = line.split("\n");

                            int lineNum = 1;
                            int col = pos;
                            for (String l : lines) {
                                if (col <= l.length()) break;
                                col -= (l.length() + 1); // +1 cho \n
                                lineNum++;
                            }

                            String errorLine = lines[lineNum - 1];
                            err.append("LINE ").append(lineNum).append(": ").append(errorLine).append("\n");
                            err.append(" ".repeat("LINE ".length() + String.valueOf(lineNum).length() + ": ".length() + col)).append("^\n").append(RESET);
                        }

                        impl.printAbove(err.toString());
                    } else {
                        impl.printAbove(RED + "ERROR: " + ex.getMessage() + RESET);
                    }
                } else {
                    impl.printAbove(RED + "ERROR: " + ex.getMessage() + RESET);
                }

                statusBar.setQueryStatus("ERROR");
            }
        }
    }

    private static void printHelp(LineReader reader) {
        String text =
                "\n╔══ Keys ═══════════════╗\n" +
                        "Ctrl+T   Toggle multi-line\n" +
                        "TAB      autocomplete\n" +
                        "\\q       exit\n" +
                        "╚═══════════════════════╝\n";

        reader.printAbove(text);
    }

    private static void printStartupInfo(LineReader reader) {

        String clientZone = java.time.ZoneId.systemDefault().toString();
        String serverZone = "Etc/UTC";
        String serverVersion = "PostgreSQL (unknown)";

        String info =
                "Using local time zone " + clientZone +
                        " (server uses " + serverZone + ")\n" +
                        "Server: " + serverVersion + "\n" +
                        "Version: 1.0.0\n" +
                        "Home: http://your-cli.dev\n";

        reader.printAbove(info);
    }
}