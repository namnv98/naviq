package com.naviq.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * JLine 3 — Interactive CLI with Autocomplete Menu
 *
 * Maven dependency:
 *   <dependency>
 *     <groupId>org.jline</groupId>
 *     <artifactId>jline</artifactId>
 *     <version>3.26.3</version>
 *   </dependency>
 *
 * Or Gradle:
 *   implementation 'org.jline:jline:3.26.3'
 */
public class JLineDemo {

    public static void main(String[] args) throws IOException {

        // 1. Force UTF-8 output on Windows (fix "Γ₧£ app Γ¥»" garbage)
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("stdout.encoding", "UTF-8");

        // 2. Build terminal — encoding(UTF-8) fixes Windows codepage (cp850/cp1252)
        Terminal terminal = TerminalBuilder.builder()
            .system(true)
            .encoding(StandardCharsets.UTF_8)
            .jansi(true)   // enables ANSI colours + UTF-8 on Windows
            .build();

        // 2. Define completers ------------------------------------------------

        // Top-level commands
        StringsCompleter commandCompleter = new StringsCompleter(
            "help", "exit", "quit",
            "file", "user", "config", "status", "deploy", "logs", "restart"
        );

        // Sub-command completers per top-level command
        ArgumentCompleter fileCompleter = new ArgumentCompleter(
            new StringsCompleter("file"),
            new StringsCompleter("list", "read", "write", "delete", "copy", "move"),
            NullCompleter.INSTANCE  // stop completing after the 2nd arg
        );

        ArgumentCompleter userCompleter = new ArgumentCompleter(
            new StringsCompleter("user"),
            new StringsCompleter("add", "remove", "list", "info", "password"),
            NullCompleter.INSTANCE
        );

        ArgumentCompleter configCompleter = new ArgumentCompleter(
            new StringsCompleter("config"),
            new StringsCompleter("get", "set", "reset", "list", "export", "import"),
            NullCompleter.INSTANCE
        );

        ArgumentCompleter deployCompleter = new ArgumentCompleter(
            new StringsCompleter("deploy"),
            new StringsCompleter("start", "stop", "rollback", "status", "history"),
            NullCompleter.INSTANCE
        );

        ArgumentCompleter logsCompleter = new ArgumentCompleter(
            new StringsCompleter("logs"),
            new StringsCompleter("--tail", "--follow", "--since", "--level"),
            NullCompleter.INSTANCE
        );

        // 3. Aggregate all completers with AggregateCompleter
        AggregateCompleter aggregateCompleter = new AggregateCompleter(
            fileCompleter,
            userCompleter,
            configCompleter,
            deployCompleter,
            logsCompleter,
            commandCompleter   // fallback for top-level completion
        );

        // 4. Build the LineReader -----------------------------------------------
        LineReader reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(aggregateCompleter)
            .parser(new DefaultParser())
            // Display completions as a visual popup menu (not inline list)
            .option(LineReader.Option.AUTO_MENU, true)
            // Show menu immediately on TAB (no need to press twice)
            .option(LineReader.Option.AUTO_LIST, true)
            // Case-insensitive completion matching
            .option(LineReader.Option.CASE_INSENSITIVE, true)
            // Auto-group candidates in the completion menu
            .option(LineReader.Option.GROUP, true)
            // Highlight matching prefix in completion candidates
            .option(LineReader.Option.AUTO_MENU_LIST, true)
            // History file
            .variable(LineReader.HISTORY_FILE, System.getProperty("user.home") + "/.jline_demo_history")
            .build();

        // 5. REPL loop ---------------------------------------------------------
        // ASCII-safe prompt — no Unicode arrows that break on Windows cp850/cp1252
        String prompt = "\u001B[32m>\u001B[0m \u001B[36mapp\u001B[0m \u001B[33m$\u001B[0m ";

        terminal.writer().println("JLine Autocomplete Demo  --  press TAB to see suggestions");
        terminal.writer().println("Type 'help' for commands, 'exit' to quit.");
        terminal.writer().println();

        while (true) {
            String line;
            try {
                line = reader.readLine(prompt);
            } catch (UserInterruptException e) {
                // Ctrl+C — clear line, keep running
                continue;
            } catch (EndOfFileException e) {
                // Ctrl+D — exit
                break;
            }

            if (line == null || line.isBlank()) continue;

            List<String> words = reader.getParser().parse(line, line.length()).words();
            String command = words.get(0).toLowerCase();

            switch (command) {
                case "exit", "quit" -> {
                    terminal.writer().println("Goodbye!");
                    break;
                }
                case "help" -> printHelp(terminal);
                case "file"   -> dispatch("file",   words, terminal);
                case "user"   -> dispatch("user",   words, terminal);
                case "config" -> dispatch("config", words, terminal);
                case "deploy" -> dispatch("deploy", words, terminal);
                case "logs"   -> dispatch("logs",   words, terminal);
                case "status" -> terminal.writer().println("  [OK] All systems operational");
                case "restart"-> terminal.writer().println("  [..] Restarting services...");
                default       -> terminal.writer().println("  Unknown command: " + command + " (try TAB)");
            }
            terminal.writer().println();
        }

        terminal.close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void dispatch(String cmd, List<String> words, Terminal t) {
        String sub = words.size() > 1 ? words.get(1) : "<no sub-command>";
        t.writer().printf("  [%s] executing sub-command: %s%n", cmd, sub);
    }

    private static void printHelp(Terminal t) {
        t.writer().println("""
                  Available commands (press TAB after any word for suggestions):

                    file   <list|read|write|delete|copy|move>
                    user   <add|remove|list|info|password>
                    config <get|set|reset|list|export|import>
                    deploy <start|stop|rollback|status|history>
                    logs   <--tail|--follow|--since|--level>
                    status
                    restart
                    help
                    exit / quit
                """);
    }
}