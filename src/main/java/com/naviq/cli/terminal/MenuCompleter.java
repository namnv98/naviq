package com.naviq.cli.terminal;

import com.naviq.completion.suggests.PostgresCompletionEngine;
import com.naviq.completion.model.Suggest;
import com.naviq.cli.anchor.AnchorStrategyUtil;
import com.naviq.cli.layout.TerminalMenu;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;

import java.io.IOError;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

public class MenuCompleter {

    private static boolean multiLine = false;

    public static void toggleMultiLine() {
        multiLine = !multiLine;
    }

    // ── Styles ─────────────────────────────────────────
    private static final AttributedStyle
        STYLE_SELECTED = AttributedStyle.DEFAULT.foreground(231).background(45).bold(),
        STYLE_NORMAL = AttributedStyle.DEFAULT.background(30).foreground(231),
        STYLE_TYPE = AttributedStyle.DEFAULT.foreground(231).background(66),
        STYLE_HIGHLIGHT = AttributedStyle.DEFAULT.foreground(9).background(30),
        STYLE_SCROLLBAR = AttributedStyle.DEFAULT.background(236),
        STYLE_SCROLL_THUMB = AttributedStyle.DEFAULT.background(250),
        STYLE_ICON = AttributedStyle.DEFAULT.foreground(242);

    private static final int PAGE_SIZE = 10;

    static volatile boolean autosuggestionOpen = false;

    private static TerminalMenu terminalMenu;
    private static LineReader reader;

    // ───────────────────────────────────────────────────
    public static void register(LineReader lineReader) {
        reader = lineReader;
        if (!(lineReader instanceof LineReaderImpl impl)) {
            throw new IllegalArgumentException("Need LineReaderImpl");
        }

        terminalMenu = new TerminalMenu(lineReader);

        KeyMap<Binding> map = impl.getKeyMaps().get(LineReader.MAIN);

        registerWidget(impl, "autosuggest", () -> {
            impl.callWidget(LineReader.SELF_INSERT);
            autosuggestion(impl);
            return true;
        });

        registerWidget(impl, "menu-complete", () -> {
            menuComplete(impl);
            return true;
        });

        impl.getWidgets().put("delete-autosuggestion", () -> {
            impl.callWidget(LineReader.BACKWARD_DELETE_CHAR);
            autosuggestion(impl);
            return true;
        });

        impl.getWidgets().put("enter-autosuggestion", () -> {
            hide();
            impl.callWidget(LineReader.ACCEPT_LINE);
            if (multiLine) {
                autosuggestion(impl);
            }
            return true;
        });

        registerWidget(impl, "down-autosuggestion", () -> {
            if (!autosuggestionOpen) {
                impl.callWidget(LineReader.DOWN_LINE_OR_HISTORY);
                return true;
            }
            menuComplete(impl);
            return true;
        });

        registerWidget(impl, "up-autosuggestion", () -> {
            if (!autosuggestionOpen) {
                impl.callWidget(LineReader.UP_LINE_OR_HISTORY);
                return true;
            }
            menuComplete(impl);
            return true;
        });

        registerWidget(impl, "clear-menu-right", () -> {
            hide();
            impl.callWidget(LineReader.FORWARD_CHAR);
            return true;
        });

        registerWidget(impl, "clear-menu-left", () -> {
            hide();
            impl.callWidget(LineReader.BACKWARD_CHAR);
            return true;
        });

        map.bind(
            new Reference("delete-autosuggestion"),
            "\u007f",      // DEL
            "\b",          // BACKSPACE
            "\u001b[3~"    // DELETE (ANSI escape)
        );
        map.bind(new Reference("menu-complete"), "\t");
        map.bind(new Reference("autosuggest"), " ");
        map.bind(new Reference("enter-autosuggestion"), "\r", "\n");
        map.bind(new Reference("up-autosuggestion"), KeyMap.key(impl.getTerminal(), Capability.key_up));
        map.bind(new Reference("down-autosuggestion"), KeyMap.key(impl.getTerminal(), Capability.key_down));
        map.bind(new Reference("menu-complete"), "\u0000");
        map.bind(new Reference("menu-complete"), KeyMap.key(impl.getTerminal(), Capability.tab));
        map.bind(new Reference("clear-menu-right"), KeyMap.key(impl.getTerminal(), Capability.key_right));
        map.bind(new Reference("clear-menu-left"), KeyMap.key(impl.getTerminal(), Capability.key_left));

        for (char c : "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.".toCharArray()) {
            map.bind(new Reference("autosuggest"), String.valueOf(c));
        }
    }

    private static void registerWidget(LineReaderImpl impl, String name, Widget w) {
        impl.getWidgets().put(name, w);
    }

    // ───────────────────────────────────────────────────
    public record Ctx(String prefix, boolean dotMode, String sqlSearch, int csrSearch) {

    }

    public static Ctx buildCtx(String sql, int cursor) {
        String prefix = extractPrefix(sql, cursor);
        boolean dot = prefix.contains(".");

        String sqlSearch;
        int csrSearch;

        if (dot) {
            // cắt sql tại vị trí trước dấu chấm đầu tiên của prefix
            int dotPos = cursor - prefix.length() + prefix.indexOf('.');
            sqlSearch = sql.substring(0, dotPos + 1); // giữ lại "public."
            csrSearch = dotPos + 1;
        } else {
            sqlSearch = sql.substring(0, Math.max(0, cursor - prefix.length()));
            csrSearch = sqlSearch.length();
        }

        return new Ctx(prefix, dot, sqlSearch, csrSearch);
    }

    // ───────────────────────────────────────────────────
    private static void autosuggestion(LineReaderImpl reader) {
        String sql = reader.getBuffer().toString();
        int cursor = reader.getBuffer().cursor();

        if (sql.isEmpty()) {
            hide();
            return;
        }

        Ctx ctx = buildCtx(sql, cursor);

        List<Suggest> list = filter(
            PostgresCompletionEngine.suggests(extractCompletionPrefix(sql), cursor),
            ctx.prefix(),
            ctx.dotMode()
        );

        if (list.isEmpty()) {
            hide();
            return;
        }
        List<AttributedString> lines = render(list, -1, 0, ctx.prefix(), ctx.dotMode());
        terminalMenu.show(lines, AnchorStrategyUtil.smart(lines, PAGE_SIZE), 1, 1);
        autosuggestionOpen = true;
    }

    // ───────────────────────────────────────────────────
    private static void menuComplete(LineReaderImpl reader) {
        Terminal terminal = reader.getTerminal();

        String sql = reader.getBuffer().toString();
        int cursor = reader.getBuffer().cursor();
        Ctx ctx = buildCtx(sql, cursor);

        List<Suggest> all = filter(
            PostgresCompletionEngine.suggests(extractCompletionPrefix(sql), cursor),
            ctx.prefix(),
            ctx.dotMode()
        );
        if (all.isEmpty()) {
            return;
        }

        if (all.size() == 1) {
            hide();
            insert(reader, all.get(0), ctx.prefix(), ctx.dotMode());
            return;
        }

        int selected = 0;
        int scroll = 0;

        BindingReader br = new BindingReader(terminal.reader());
        KeyMap<String> km = keymap(terminal);

        try {
            while (true) {

                List<Suggest> filtered = filter(all, ctx.prefix(), ctx.dotMode());

                if (filtered.isEmpty()) {
                    selected = 0;
                } else {
                    selected = Math.min(selected, filtered.size() - 1);
                }

                if (selected < scroll) {
                    scroll = selected;
                }
                if (selected >= scroll + PAGE_SIZE) {
                    scroll = selected - PAGE_SIZE + 1;
                }

                List<AttributedString> lines = render(filtered, selected, scroll, ctx.prefix(),
                    ctx.dotMode());

                terminalMenu.show(lines, AnchorStrategyUtil.smart(lines, PAGE_SIZE + 1), 1, 1);

                Suggest current = filtered.isEmpty() ? null : filtered.get(selected);
                if (current != null) {
                    String ghost = buildGhost(current.getKey(), ctx.prefix(), ctx.dotMode());
                    renderGhost(reader, ghost);
                }

                String key = br.readBinding(km, null, true);
                if (key == null) {
                    break;
                }

                switch (key) {
                    case "up" -> selected = (selected <= 0) ? filtered.size() - 1 : selected - 1;
                    case "down" -> selected = (selected >= filtered.size() - 1) ? 0 : selected + 1;

                    case "enter", "space" -> {
                        hide();
                        if (!filtered.isEmpty()) {
                            insert(reader, filtered.get(selected), ctx.prefix(), ctx.dotMode());
                        }
                        return;
                    }
                    case "esc", "ctrlc" -> {
                        hide();
                        return;
                    }
                    case "bs" -> {
                        hide();
                        reader.callWidget(LineReader.BACKWARD_DELETE_CHAR);
                        return;
                    }
                    default -> {
                        if (key.length() != 1) {
                            return;
                        }
                        char ch = key.charAt(0);
                        hide();
                        if (!filtered.isEmpty() && isIdentifierChar(ch)) {
//                            insert(reader, filtered.get(selected), ctx.prefix(), ctx.dotMode());
                        }
                        reader.getBuffer().write(ch);
                        return;
                    }
                }
            }


        } catch (IOError ignored) {
        } finally {
            hide();
        }
    }

    // ───────────────────────────────────────────────────
    private static List<AttributedString> render(
        List<Suggest> items,
        int selected,
        int scroll,
        String prefix,
        boolean dot
    ) {
        List<AttributedString> out = new ArrayList<>();

        int[] w = calcWidth(items, dot);
        int valueW = w[0];
        int typeW = w[1];
        int columnTypeW = w[2];

        int visible = Math.min(PAGE_SIZE, items.size() - scroll);

        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            Suggest s = items.get(idx);

            boolean sel = idx == selected;

            String key = display(s.getKey(), dot);
            String type = s.getType();

            AttributedStringBuilder row = new AttributedStringBuilder();

            row.style(sel ? STYLE_SELECTED : STYLE_NORMAL).append(" ");          // leading space

            row.style(sel ? STYLE_SELECTED : STYLE_NORMAL).append(typeIcon(type)); // icon đầu

            if (sel) {
                row.append(pad(key, valueW));
            } else {
                highlight(row, pad(key, valueW), prefix, dot);
            }

            row.append("  ");
            row.style(sel ? STYLE_SELECTED : AttributedStyle.DEFAULT.background(30).foreground(114))
                .append(pad(s.getColumnType(), columnTypeW));

            row.append("  ");
            row.style(sel ? STYLE_SELECTED : STYLE_TYPE)
                .append(pad(type, typeW));

            // ── SCROLLBAR ─────────────────────
            row.append(" ");
            row.style(scrollbarStyle(i, visible, items.size(), scroll)).append(" ");

            out.add(row.toAttributedString());
        }
        // ── footer ────────────────────────────────
        int remaining = items.size() - scroll - visible;

        if (remaining > 0) {
            // 1(leading space) + valueW + 2(gap) + typeW + 1(gap)
            int menuWidth = 3 + valueW + 2 + columnTypeW + 2 + typeW + 1;

            AttributedStringBuilder f = new AttributedStringBuilder();
            String text = " ↓ [" + remaining + "] MORE ";
            int pad = Math.max(0, menuWidth - text.length());
            f.style(AttributedStyle.DEFAULT.background(23).foreground(231))
                .append(text)
                .append(" ".repeat(pad));

            // cột scrollbar cuối — cùng dòng, khác style
            f.style(STYLE_SCROLLBAR).append(" ");

            out.add(f.toAttributedString());
        }
        return out;
    }

    static AttributedStyle styleByColumnType(String t) {
        if (t == null) {
            return STYLE_NORMAL;
        }

        return switch (t) {
            case "int", "int4", "int8", "numeric" ->
                AttributedStyle.DEFAULT.background(30).foreground(220); // vàng
            case "text", "varchar" ->
                AttributedStyle.DEFAULT.background(30).foreground(114); // xanh lá
            case "timestamp", "date" ->
                AttributedStyle.DEFAULT.background(30).foreground(109); // cyan
            case "bool" -> AttributedStyle.DEFAULT.background(30).foreground(141); // tím
            default -> AttributedStyle.DEFAULT.background(30).foreground(244);
        };
    }

    // ───────────────────────────────────────────────────
    private static void insert(LineReaderImpl reader, Suggest s, String prefix, boolean dot) {
        reader.getBuffer().move(-prefix.length());
        for (int i = 0; i < prefix.length(); i++) {
            reader.getBuffer().delete();
        }

        if (dot && prefix.contains(".")) {
            String alias = prefix.substring(0, prefix.lastIndexOf('.'));
            String col = s.getKey().contains(".")
                ? s.getKey().substring(s.getKey().lastIndexOf('.') + 1)
                : s.getKey();
            reader.getBuffer().write(alias + "." + col);
        } else {
            reader.getBuffer().write(s.getKey());
        }
    }

    // ───────────────────────────────────────────────────
    private static void highlight(AttributedStringBuilder sb, String word, String match,
        boolean dot) {
        if (match.isEmpty()) {
            sb.append(word);
            return;
        }

        String m = dot && match.contains(".")
            ? match.substring(match.lastIndexOf('.') + 1)
            : match;

        int j = 0;
        for (int i = 0; i < word.length(); i++) {
            if (j < m.length() &&
                Character.toLowerCase(word.charAt(i)) == Character.toLowerCase(m.charAt(j))) {
                sb.style(STYLE_HIGHLIGHT).append(word.charAt(i));
                j++;
            } else {
                sb.style(STYLE_NORMAL).append(word.charAt(i));
            }
        }
    }

    private static boolean isIdentifier(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    private static String extractPrefix(String s, int cursor) {
        int i = cursor - 1;
        while (i >= 0 && isIdentifier(s.charAt(i))) {
            i--;
        }
        return s.substring(i + 1, cursor);
    }

    static String extractCompletionPrefix(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        // Chỉ 1 từ → không cần prefix
        if (input.trim().split("\\s+").length == 1 && !input.endsWith(" ")) {
            return "";
        }

        return input;
    }

    private static KeyMap<String> keymap(Terminal t) {
        KeyMap<String> km = new KeyMap<>();
        km.bind("up", KeyMap.key(t, Capability.key_up));
        km.bind("down", KeyMap.key(t, Capability.key_down));
        km.bind("down", KeyMap.key(t, Capability.tab));
        km.bind("enter", "\r");
        km.bind("esc", "\u001b");
        km.bind("ctrlc", "\u0003");
        km.bind("space", " ");
        km.bind("bs", "\u007f", "\u0008");
        for (char c : "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.".toCharArray()) {
            km.bind(String.valueOf(c), String.valueOf(c));
        }

        return km;
    }

    private static String pad(String s, int w) {
        if (s == null) {
            s = "";
        }
        if (s.length() >= w) {
            return s.substring(0, w);
        }
        return s + " ".repeat(w - s.length());
    }

    private static int[] calcWidth(List<Suggest> items, boolean dot) {
        int valueW = 0;
        int typeW = 0;
        int columnTypeW = 0;

        for (Suggest s : items) {
            valueW = Math.max(valueW, display(s.getKey(), dot).length());
            typeW = Math.max(typeW, s.getType().length());

            String colType = s.getColumnType() == null ? "" : s.getColumnType();
            columnTypeW = Math.max(columnTypeW, colType.length());
        }

        return new int[]{valueW, typeW, columnTypeW};
    }

    private static AttributedStyle scrollbarStyle(
        int row,
        int visible,
        int total,
        int scroll
    ) {
        if (total <= visible) {
            return STYLE_NORMAL;
        }

        float ratio = (float) visible / total;
        int thumbSize = Math.max(1, Math.round(ratio * visible));

        float posRatio = (float) scroll / total;
        int thumbStart = Math.round(posRatio * visible);

        return (row >= thumbStart && row < thumbStart + thumbSize)
            ? STYLE_SCROLL_THUMB
            : STYLE_SCROLLBAR;
    }

    private static String buildGhost(String key, String prefix, boolean dot) {
        String display = display(key, dot);

        String match = dot && prefix.contains(".")
            ? prefix.substring(prefix.lastIndexOf('.') + 1)
            : prefix;

        if (display.toLowerCase().startsWith(match.toLowerCase())) {
            return display.substring(match.length());
        }

        return display;
    }

    private static String lastGhost = "";

    private static void renderGhost(LineReaderImpl reader, String ghost) {
        Terminal term = reader.getTerminal();
        PrintWriter out = term.writer();

        out.print("\u001b[s"); // save cursor

        // Xóa ghost cũ bằng cách in spaces đè lên
        if (!lastGhost.isEmpty()) {
            out.print(" ".repeat(lastGhost.length()));
        }

        out.print("\u001b[u"); // restore cursor (về vị trí cursor thật)

        // Vẽ ghost mới
        if (ghost != null && !ghost.isEmpty()) {
            out.print("\u001b[s"); // save lại
            AttributedStringBuilder as = new AttributedStringBuilder();
            as.style(AttributedStyle.DEFAULT.foreground(244));
            as.append(ghost);
            out.print(as.toAnsi());
            out.print("\u001b[u"); // restore
        }

        lastGhost = ghost == null ? "" : ghost;
        out.flush();
    }

    // Gọi khi đóng menu hoặc insert
    public static void clearGhost(LineReaderImpl reader) {
        renderGhost(reader, "");
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    // ───────────────────────────────────────────────────
    public static List<Suggest> filter(List<Suggest> all, String prefix, boolean dot) {
        String m = dot && prefix.contains(".")
            ? prefix.substring(prefix.lastIndexOf('.') + 1)
            : prefix;

        // Lấy schema từ prefix: "public.d" → "public"
        String schemaPrefix = dot && prefix.contains(".")
            ? prefix.substring(0, prefix.lastIndexOf('.')).toLowerCase()
            : null;

        return all.stream()
            .map(s -> new AbstractMap.SimpleEntry<>(s, display(s.getKey(), dot)))
            .filter(e -> {
                // Nếu có schema prefix → chỉ giữ item cùng schema
                if (schemaPrefix != null) {
                    String key = e.getKey().getKey().toLowerCase();
                    if (!key.startsWith(schemaPrefix + ".")) {
                        return false;
                    }
                }
                return fuzzyMatch(e.getValue(), m);
            })
            .sorted(Comparator.comparingInt(e -> {
                // thứ tự tiên equals -> prefix -> fuzzy
                String w = e.getValue();
                if (w.equalsIgnoreCase(m)) {
                    return -1000;
                }
                if (w.toLowerCase().startsWith(m.toLowerCase())) {
                    return -100;
                }
                return fuzzyScore(w, m);
            }))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    private static String display(String key, boolean dot) {
        return (dot && key.contains(".")) ? key.substring(key.indexOf('.') + 1) : key;
    }

    static boolean fuzzyMatch(String word, String input) {
        if (input == null || input.isEmpty()) {
            return true;
        }

        word = word.toLowerCase();
        input = input.toLowerCase();

        int i = 0; // pointer word
        int j = 0; // pointer input

        while (i < word.length() && j < input.length()) {
            if (word.charAt(i) == input.charAt(j)) {
                j++;
            }
            i++;
        }

        return j == input.length();
    }

    static int fuzzyScore(String word, String input) {
        if (input.isEmpty()) {
            return 0;
        }
        word = word.toLowerCase();
        input = input.toLowerCase();

        // prefix thắng tất
        if (word.startsWith(input)) {
            return -input.length() * 10;
        }

        int score = 0;
        int lastMatch = -1;

        for (char c : input.toCharArray()) {
            int idx = word.indexOf(c, lastMatch + 1);
            if (idx == -1) {
                return Integer.MAX_VALUE;
            }

            score += (idx - lastMatch - 1);   // phạt gap
            if (idx == lastMatch + 1) {
                score -= 3; // thưởng liên tiếp
            }
            if (idx == 0 || word.charAt(idx - 1) == '_') {
                score -= 4; // thưởng word boundary
            }

            lastMatch = idx;
        }

        return score;
    }

    static String typeIcon(String type) {
        return switch (type) {
            case "table" -> "󰓫 "; // nf-md-table_large
            case "view" -> "󰒉 "; // nf-md-file_eye
            case "materialized view" -> "󰆧 "; // nf-md-cube_outline
            case "column" -> "󰠵 "; // nf-md-form_textbox
            case "function" -> "󰡱 "; // nf-md-lambda
            case "keyword" -> "󰬴 "; // nf-md-key_variant
            case "datatype" -> "󰅩 "; // nf-md-alpha_t_box_outline
            case "schema" -> "󰉋 "; // nf-md-database_outline
            default -> "󰞋 ";
        };
    }

    public static void hide() {
        autosuggestionOpen = false;
        clearGhost((LineReaderImpl) reader); // xóa ghost khi đóng menu
        terminalMenu.hide();
    }

}