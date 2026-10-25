package com.sqlctx.shell;

import com.sqlctx.cli.anchor.AnchorStrategies;
import com.sqlctx.cli.terminal.TerminalMenu;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

import java.io.IOError;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Popup gợi ý tự hiện khi gõ của các chế độ không phải SQL (Redis, Kafka) - cùng giao diện và phím với
 * MenuCompleter bên SQL (dùng lại TerminalMenu để vẽ), nguồn gợi ý do chế độ đó truyền vào.
 * <ul>
 *   <li>Gõ / xoá: popup hiện (hoặc ẩn khi không còn gì để gợi ý), chưa chiếm phím nào.</li>
 *   <li>↓ / ↑ / Tab khi popup đang mở: vào chế độ chọn; Enter / Space điền mục đang chọn, Esc đóng.</li>
 *   <li>Tab khi chỉ có 1 gợi ý: điền luôn; Tab ở dòng trống: mọi lệnh. Enter khi chưa vào chế độ chọn: chạy lệnh.</li>
 * </ul>
 */
public final class CompletionMenu {

    // cùng bảng màu với MenuCompleter (các hằng bên đó là private)
    private static final int BG = 236;
    private static final AttributedStyle
            STYLE_SELECTED = AttributedStyle.DEFAULT.foreground(255).background(31).bold(),
            STYLE_NORMAL = AttributedStyle.DEFAULT.background(BG).foreground(252),
            STYLE_DETAIL = AttributedStyle.DEFAULT.background(BG).foreground(246),
            STYLE_TYPE = AttributedStyle.DEFAULT.foreground(109).background(BG),
            STYLE_HIGHLIGHT = AttributedStyle.DEFAULT.foreground(220).background(BG).bold(),
            STYLE_DIVIDER = AttributedStyle.DEFAULT.foreground(240).background(BG),
            STYLE_SCROLLBAR = AttributedStyle.DEFAULT.foreground(238).background(BG),
            STYLE_SCROLL_THUMB = AttributedStyle.DEFAULT.foreground(103).background(BG),
            STYLE_GHOST = AttributedStyle.DEFAULT.foreground(244);

    private static final int PAGE_SIZE = 10;
    private static final int MAX_VALUE_WIDTH = 48;
    private static final int MAX_DETAIL_WIDTH = 40;

    private final LineReaderImpl reader;
    private final TerminalMenu menu;
    private final java.util.function.BiFunction<String, Integer, List<MenuItem>> suggester;
    private boolean open;
    /** true: Tab chèn ký tự tab thật thay vì mở gợi ý (vd đang nhập message cho producer - tab là dấu ngăn key/value). */
    private java.util.function.BooleanSupplier literalTab = () -> false;
    private String lastGhost = "";

    /** {@code suggester}: (cả dòng, vị trí con trỏ) -> gợi ý, đã lọc theo chữ đang gõ. */
    public CompletionMenu(LineReaderImpl reader, java.util.function.BiFunction<String, Integer, List<MenuItem>> suggester) {
        this.reader = reader;
        this.menu = new TerminalMenu(reader);
        this.suggester = suggester;
    }

    /** Phần đang gõ dở ngay trước con trỏ (từ khoảng trắng gần nhất). */
    public static String currentWord(String beforeCursor) {
        int i = beforeCursor.length();
        while (i > 0 && !Character.isWhitespace(beforeCursor.charAt(i - 1))) {
            i--;
        }
        return beforeCursor.substring(i);
    }

    /** Gắn phím - cùng bộ phím với MenuCompleter.register. */
    public void register() {
        LineReaderImpl impl = reader;
        KeyMap<Binding> map = impl.getKeyMaps().get(LineReader.MAIN);
        impl.getWidgets().put("shell-menu-complete", () -> {
            choose();
            return true;
        });
        impl.getWidgets().put("shell-menu-down", () -> {
            if (open) {
                choose();
            } else {
                impl.callWidget(LineReader.DOWN_LINE_OR_HISTORY);
            }
            return true;
        });
        impl.getWidgets().put("shell-menu-up", () -> {
            if (open) {
                choose();
            } else {
                impl.callWidget(LineReader.UP_LINE_OR_HISTORY);
            }
            return true;
        });
        impl.getWidgets().put("shell-menu-hide", () -> {
            hide();
            return true;
        });
        impl.getWidgets().put("shell-menu-right", () -> {
            hide();
            impl.callWidget(LineReader.FORWARD_CHAR);
            return true;
        });
        impl.getWidgets().put("shell-menu-left", () -> {
            hide();
            impl.callWidget(LineReader.BACKWARD_CHAR);
            return true;
        });
        impl.getWidgets().put("shell-menu-delete", () -> {
            impl.callWidget(LineReader.DELETE_CHAR);
            refresh();
            return true;
        });
        Terminal t = impl.getTerminal();
        // Esc đứng 1 mình đóng popup; Esc cũng là đầu chuỗi phím mũi tên nên khai báo cả dạng ESC [ x và rút thời gian
        // chờ phân biệt xuống 100ms (cùng lý do với MenuCompleter)
        map.bind(new Reference("shell-menu-hide"), "\u001b");
        map.bind(new Reference("shell-menu-up"), "\u001b[A", KeyMap.key(t, Capability.key_up));
        map.bind(new Reference("shell-menu-down"), "\u001b[B", KeyMap.key(t, Capability.key_down));
        map.bind(new Reference("shell-menu-right"), "\u001b[C", KeyMap.key(t, Capability.key_right));
        map.bind(new Reference("shell-menu-left"), "\u001b[D", KeyMap.key(t, Capability.key_left));
        map.bind(new Reference(LineReader.BEGINNING_OF_LINE), "\u001b[H", "\u001bOH", "\u001b[1~");
        map.bind(new Reference(LineReader.END_OF_LINE), "\u001b[F", "\u001bOF", "\u001b[4~");
        map.bind(new Reference("shell-menu-delete"), "\u001b[3~");
        map.bind(new Reference("shell-menu-complete"), "\t", "\u0000", KeyMap.key(t, Capability.tab));
        impl.getKeyMaps().values().forEach(km -> km.setAmbiguousTimeout(100));
    }

    /** {@code typing}: gọi từ lúc đang gõ (popup tự hiện) - dòng trống thì không bày cả 250 lệnh ra; Tab thì có. */
    private List<MenuItem> items(boolean typing) {
        String line = reader.getBuffer().toString();
        int cursor = reader.getBuffer().cursor();
        String before = line.substring(0, cursor);
        if (typing && before.isBlank()) {
            return List.of();
        }
        List<MenuItem> items = suggester.apply(line, cursor);
        String word = currentWord(before);
        if (items.size() == 1 && items.get(0).value().equalsIgnoreCase(word)) {
            return List.of(); // đã gõ đủ đúng mục duy nhất
        }
        return items;
    }

    /**
     * Gọi sau mỗi lần gõ / xoá: hiện popup theo vị trí con trỏ mới, hoặc ẩn nếu không còn gì để gợi ý. Xoá về dòng
     * trống thì ẩn (Tab vẫn mở được danh sách đầy đủ).
     */
    public void refresh() {
        List<MenuItem> items;
        try {
            items = items(true);
        } catch (RuntimeException e) {
            items = List.of(); // gợi ý lỗi không được làm hỏng ô nhập
        }
        if (items.isEmpty()) {
            hide();
            return;
        }
        String word = currentWord(reader.getBuffer().upToCursor());
        List<AttributedString> lines = render(items, -1, 0, word);
        menu.show(lines, AnchorStrategies.smart(lines, PAGE_SIZE), 1, 1, List.of());
        open = true;
    }

    public void literalTab(java.util.function.BooleanSupplier when) {
        this.literalTab = when;
    }

    /** Chế độ chọn (↓/↑/Tab): tự đọc phím tới khi điền, đóng, hoặc gõ tiếp. */
    private void choose() {
        if (literalTab.getAsBoolean()) {
            reader.getBuffer().write('\t');
            reader.callWidget(LineReader.REDISPLAY);
            return;
        }
        List<MenuItem> items = items(false);
        if (items.isEmpty()) {
            return;
        }
        String word = currentWord(reader.getBuffer().upToCursor());
        if (items.size() == 1) {
            hide();
            insert(items.get(0), word);
            return;
        }
        Terminal terminal = reader.getTerminal();
        BindingReader br = new BindingReader(terminal.reader());
        KeyMap<String> km = keymap(terminal);
        int selected = 0;
        int scroll = 0;
        int widthAtOpen = terminal.getWidth();
        int heightAtOpen = terminal.getHeight();
        try {
            while (true) {
                if (selected < scroll) {
                    scroll = selected;
                }
                if (selected >= scroll + PAGE_SIZE) {
                    scroll = selected - PAGE_SIZE + 1;
                }
                List<AttributedString> lines = render(items, selected, scroll, word);
                menu.show(lines, AnchorStrategies.smart(lines, PAGE_SIZE + 1), 1, 1, List.of());
                open = true;
                renderGhost(ghost(items.get(selected).value(), word));
                // chờ phím kiểu thăm dò để thoát khi đổi cỡ cửa sổ (xem MenuCompleter.menuComplete)
                while (terminal.reader().peek(150) == NonBlockingReader.READ_EXPIRED) {
                    if (terminal.getWidth() != widthAtOpen || terminal.getHeight() != heightAtOpen) {
                        forget();
                        return;
                    }
                }
                String key = br.readBinding(km, null, true);
                if (key == null) {
                    return;
                }
                switch (key) {
                    case "up" -> selected = selected <= 0 ? items.size() - 1 : selected - 1;
                    case "down" -> selected = selected >= items.size() - 1 ? 0 : selected + 1;
                    case "enter", "space" -> {
                        hide();
                        insert(items.get(selected), word);
                        return;
                    }
                    case "esc", "ctrlc" -> {
                        hide();
                        return;
                    }
                    case "bs" -> {
                        hide();
                        reader.callWidget(LineReader.BACKWARD_DELETE_CHAR);
                        refresh();
                        return;
                    }
                    default -> {
                        hide();
                        reader.getBuffer().write(key);
                        refresh();
                        return;
                    }
                }
            }
        } catch (IOError | java.io.IOException e) {
            hide();
        }
    }

    /** Thay phần đang gõ dở bằng gợi ý, thêm 1 khoảng trắng để gợi ý tiếp cho tham số sau. */
    private void insert(MenuItem item, String word) {
        for (int i = 0; i < word.length(); i++) {
            reader.getBuffer().backspace();
        }
        reader.getBuffer().write(item.value());
        // "retention.ms=", "User:", "a,b," là nửa chừng - gõ tiếp ngay sau, không chèn khoảng trắng
        boolean partial = item.value().endsWith("=") || item.value().endsWith(":") || item.value().endsWith(",");
        if (!partial && reader.getBuffer().cursor() == reader.getBuffer().length()) {
            reader.getBuffer().write(' ');
        }
        reader.callWidget(LineReader.REDISPLAY);
        refresh();
    }

    public void hide() {
        open = false;
        renderGhost("");
        menu.hide();
    }

    /** Màn hình vừa bị xoá / đổi cỡ: popup cũ không còn trên màn hình, chỉ quên trạng thái. */
    public void forget() {
        open = false;
        lastGhost = "";
        menu.forget();
    }

    /** readLine đã kết thúc (Ctrl+C): không gọi được widget nữa, chỉ xoá phần đã vẽ. */
    public void discard() {
        open = false;
        lastGhost = "";
        menu.discard(reader.getTerminal().writer());
    }

    // ------------------------------------------------------------------ vẽ

    private List<AttributedString> render(List<MenuItem> items, int selected, int scroll, String word) {
        int valueW = 0;
        int detailW = 0;
        int typeW = 0;
        for (MenuItem it : items) {
            valueW = Math.max(valueW, it.value().length());
            detailW = Math.max(detailW, it.description() == null ? 0 : it.description().length());
            typeW = Math.max(typeW, it.type().length());
        }
        valueW = Math.min(valueW, MAX_VALUE_WIDTH);
        detailW = Math.min(detailW, MAX_DETAIL_WIDTH);
        int termWidth = reader.getTerminal().getWidth();
        if (termWidth > 0) {
            int overhead = 1 + 2 + 3 + detailW + 3 + typeW + 2;
            valueW = Math.max(8, Math.min(valueW, termWidth - overhead - 1));
        }
        List<AttributedString> out = new ArrayList<>();
        int visible = Math.min(PAGE_SIZE, items.size() - scroll);
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            MenuItem it = items.get(idx);
            boolean sel = idx == selected;
            AttributedStringBuilder row = new AttributedStringBuilder();
            row.style(sel ? STYLE_SELECTED : STYLE_NORMAL).append(" ");
            row.style(sel ? STYLE_SELECTED : AttributedStyle.DEFAULT.foreground(iconColor(it.type())).background(BG))
                    .append(icon(it.type()));
            String value = pad(it.value(), valueW);
            if (sel) {
                row.append(value);
            } else {
                highlight(row, value, word);
            }
            row.style(sel ? STYLE_SELECTED : STYLE_NORMAL).append(" ");
            if (detailW > 0) { // không mục nào có mô tả (vd chỉ có từ khoá) thì bỏ hẳn cột này
                row.style(sel ? STYLE_SELECTED : STYLE_DIVIDER).append("│");
                row.style(sel ? STYLE_SELECTED : STYLE_DETAIL).append(" ").append(pad(it.description(), detailW));
                row.style(sel ? STYLE_SELECTED : STYLE_NORMAL).append(" ");
            }
            row.style(sel ? STYLE_SELECTED : STYLE_DIVIDER).append("│");
            row.style(sel ? STYLE_SELECTED : STYLE_TYPE).append(" ").append(pad(it.type(), typeW));
            row.style(sel ? STYLE_SELECTED : STYLE_NORMAL).append(" ");
            boolean thumb = isScrollThumb(i, visible, items.size(), scroll);
            row.style(thumb ? STYLE_SCROLL_THUMB : STYLE_SCROLLBAR).append(thumb ? "┃" : "│");
            out.add(row.toAttributedString());
        }
        int remaining = items.size() - scroll - visible;
        if (remaining > 0) {
            int menuWidth = 3 + valueW + (detailW > 0 ? 3 + detailW : 0) + 3 + typeW + 1;
            String text = " ↓ " + remaining + " more ";
            AttributedStringBuilder f = new AttributedStringBuilder();
            f.style(AttributedStyle.DEFAULT.background(238).foreground(220).bold())
                    .append(text).append(" ".repeat(Math.max(0, menuWidth - text.length())));
            f.style(AttributedStyle.DEFAULT.background(238)).append(" ");
            out.add(f.toAttributedString());
        }
        return out;
    }

    /** Tô phần đầu trùng với chữ đang gõ (không phân biệt hoa thường). */
    private static void highlight(AttributedStringBuilder sb, String value, String word) {
        int n = word.length() <= value.length() && value.regionMatches(true, 0, word, 0, word.length())
                ? word.length() : 0;
        sb.style(STYLE_HIGHLIGHT).append(value.substring(0, n));
        sb.style(STYLE_NORMAL).append(value.substring(n));
    }

    // icon Nerd Font và màu giống MenuCompleter.typeIcon/iconStyle bên SQL (bảng cam, cột xanh ngọc, từ khoá xanh dương)
    private static String icon(String type) {
        return switch (type) {
            case "command", "sqlctx", "tool" -> "󰘳 ";
            case "keyword", "option" -> "󰬴 ";
            case "key", "topic", "subject" -> "󰓫 ";
            case "field", "group", "consumer", "partition" -> "󰠵 ";
            case "config" -> "󰒓 ";
            case "broker" -> "󰆼 ";
            default -> "󰮪 ";
        };
    }

    private static int iconColor(String type) {
        return switch (type) {
            case "command", "sqlctx", "tool" -> 220;
            case "keyword", "option" -> 75;
            case "key", "topic", "subject" -> 214;
            case "field", "group", "consumer", "partition" -> 152;
            case "config" -> 183;
            case "broker" -> 109;
            default -> 114;
        };
    }

    private static String pad(String s, int w) {
        if (s == null) {
            s = "";
        }
        if (s.length() > w) {
            return w > 1 ? s.substring(0, w - 1) + "…" : s.substring(0, w);
        }
        return s + " ".repeat(w - s.length());
    }

    private static boolean isScrollThumb(int row, int visible, int total, int scroll) {
        if (total <= visible) {
            return false;
        }
        int thumbSize = Math.max(1, Math.round((float) visible / total * visible));
        int thumbStart = Math.round((float) scroll / total * visible);
        return row >= thumbStart && row < thumbStart + thumbSize;
    }

    private static KeyMap<String> keymap(Terminal t) {
        KeyMap<String> km = new KeyMap<>();
        km.bind("up", KeyMap.key(t, Capability.key_up), "\u001b[A");
        km.bind("down", KeyMap.key(t, Capability.key_down), "\u001b[B", "\t");
        km.bind("enter", "\r");
        km.bind("esc", "\u001b");
        km.bind("ctrlc", "\u0003");
        km.bind("space", " ");
        km.bind("bs", "\u007f", "\u0008");
        for (char c = 0x21; c < 0x7f; c++) {
            km.bind(String.valueOf(c), String.valueOf(c)); // gõ tiếp: thoát chế độ chọn, chèn ký tự
        }
        return km;
    }

    // ------------------------------------------------------------------ chữ mờ phần còn lại của mục đang chọn

    private static String ghost(String value, String word) {
        return value.regionMatches(true, 0, word, 0, word.length()) ? value.substring(word.length()) : "";
    }

    /** Cùng cách MenuCompleter.renderGhost: xoá chữ mờ cũ bằng cách in lại đúng chữ thật đang nằm sau con trỏ. */
    private void renderGhost(String ghost) {
        PrintWriter out = reader.getTerminal().writer();
        out.print("\u001b[s");
        if (!lastGhost.isEmpty()) {
            String buf = reader.getBuffer().toString();
            int cursor = reader.getBuffer().cursor();
            int nl = buf.indexOf('\n', cursor);
            String rest = nl >= 0 ? buf.substring(cursor, nl) : buf.substring(cursor);
            out.print(rest.length() >= lastGhost.length() ? rest.substring(0, lastGhost.length())
                    : rest + " ".repeat(lastGhost.length() - rest.length()));
        }
        out.print("\u001b[u");
        if (!ghost.isEmpty()) {
            out.print("\u001b[s");
            out.print(new AttributedStringBuilder().style(STYLE_GHOST).append(ghost).toAnsi());
            out.print("\u001b[u");
        }
        lastGhost = ghost;
        out.flush();
    }
}
