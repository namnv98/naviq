package com.sqlctx.cli.terminal;

import com.sqlctx.completion.ranking.CompletionHistory;
import com.sqlctx.completion.input.CompletionInputPreparer;
import com.sqlctx.completion.ranking.SuggestFilter;
import com.sqlctx.datasource.ConnectionProfileStore;
import com.sqlctx.dialect.DialectAdapters;
import com.sqlctx.schema.SchemaIndex;
import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.model.SuggestionType;
import com.sqlctx.cli.anchor.AnchorStrategies;
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
import org.jline.utils.NonBlockingReader;

import java.io.IOError;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Stream;

/**
 * CẬP NHẬT: logic lọc/xếp hạng fuzzy (filter/display/fuzzyMatch/fuzzyScore) đã tách sang
 * {@link SuggestFilter} (thuần, không đụng LineReader/Terminal) - xem javadoc bên đó cho
 * phần cải tiến (gộp match+score 1 lần quét, camelCase boundary, tie-break theo độ dài).
 * Phần còn lại (key-binding, render menu, ghost text) giữ nguyên tại đây.
 */
public class MenuCompleter {

    private static boolean multiLine = false;

    public static void toggleMultiLine() {
        multiLine = !multiLine;
    }

    // ── Styles ─────────────────────────────────────────
    // Nền trung tính (236) thay vì xanh dương đậm (30) cũ - làm nền cho các màu icon/badge nổi lên rõ hơn.
    private static final int BG = 236;
    private static final AttributedStyle
            STYLE_SELECTED = AttributedStyle.DEFAULT.foreground(255).background(31).bold(),
            STYLE_NORMAL = AttributedStyle.DEFAULT.background(BG).foreground(252),
            STYLE_TYPE = AttributedStyle.DEFAULT.foreground(109).background(BG),
            // Vàng gold đậm thay vì đỏ - đỏ thường mang nghĩa lỗi/cảnh báo, dễ hiểu lầm khi dùng để tô
            // phần ký tự khớp với từ khoá đang gõ.
            STYLE_HIGHLIGHT = AttributedStyle.DEFAULT.foreground(220).background(BG).bold(),
            STYLE_DIVIDER = AttributedStyle.DEFAULT.foreground(240).background(BG),
            STYLE_SCROLLBAR = AttributedStyle.DEFAULT.foreground(238).background(BG),
            STYLE_SCROLL_THUMB = AttributedStyle.DEFAULT.foreground(103).background(BG);

    /** Lệnh CLI (không phải SQL) và mô tả ngắn - hiện ở menu gợi ý khi gõ '\', giữ nguyên thứ tự khai báo. */
    private static final Map<String, String> COMMAND_DOCS = new LinkedHashMap<>();

    static {
        COMMAND_DOCS.put("\\l", "list databases");
        COMMAND_DOCS.put("\\c ", "switch database");
        COMMAND_DOCS.put("\\q", "exit");
        COMMAND_DOCS.put("\\?", "help");
        COMMAND_DOCS.put("\\dt", "list tables");
        COMMAND_DOCS.put("\\dn", "list schemas");
        COMMAND_DOCS.put("\\df", "list functions");
        COMMAND_DOCS.put("\\d ", "describe table");
        COMMAND_DOCS.put("\\i ", "run SQL script");
        COMMAND_DOCS.put("\\e", "edit in $EDITOR");
        COMMAND_DOCS.put("\\ctx ", "switch saved context (kubectx-style)");
        COMMAND_DOCS.put("\\du", "list users/roles");
        COMMAND_DOCS.put("\\o ", "export result (csv/json/text)");
    }

    private static final int PAGE_SIZE = 10;

    static volatile boolean autosuggestionOpen = false;

    private static TerminalMenu terminalMenu;
    private static LineReader reader;
    public static void register(LineReader lineReader) {
        reader = lineReader;
        if (!(lineReader instanceof LineReaderImpl impl)) {
            throw new IllegalArgumentException("Need LineReaderImpl");
        }

        terminalMenu = new TerminalMenu(lineReader);

        KeyMap<Binding> map = impl.getKeyMaps().get(LineReader.MAIN);

        registerWidget(impl, "menu-complete", () -> {
            menuComplete(impl);
            return true;
        });

        // Phím Delete (forward) - giữ hành vi cũ (xoá lùi 1 ký tự) thay vì forward-delete chuẩn.
        // KHÔNG rebind Backspace/DEL(0x7f) ở đây nữa - xem SqlctxCli#backwardDeleteChar()/selfInsert():
        // rebind trực tiếp qua keymap (thay vì override đúng hook JLine dùng để tự hỏi "phím này có
        // phải self-insert/backward-delete-char không") từng phá vỡ Ctrl+R (reverse-i-search) - JLine
        // nhận diện các phím đó bằng TÊN binding trong keymap, không phải bằng việc widget có chạy đúng
        // hành vi hay không, nên đổi tên binding (dù hành vi bên trong vẫn gọi lại đúng thao tác gốc)
        // đã khiến nó không còn được coi là ký tự "gõ vào ô tìm kiếm" nữa.
        impl.getWidgets().put("delete-autosuggestion", () -> {
            impl.callWidget(LineReader.BACKWARD_DELETE_CHAR);
            refreshAutosuggestion(impl);
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

        map.bind(new Reference("delete-autosuggestion"), "\u001b[3~");
        map.bind(new Reference("menu-complete"), "\t");
        map.bind(new Reference("up-autosuggestion"), KeyMap.key(impl.getTerminal(), Capability.key_up));
        map.bind(new Reference("down-autosuggestion"), KeyMap.key(impl.getTerminal(), Capability.key_down));
        map.bind(new Reference("menu-complete"), "\u0000");
        map.bind(new Reference("menu-complete"), KeyMap.key(impl.getTerminal(), Capability.tab));
        map.bind(new Reference("clear-menu-right"), KeyMap.key(impl.getTerminal(), Capability.key_right));
        map.bind(new Reference("clear-menu-left"), KeyMap.key(impl.getTerminal(), Capability.key_left));
    }

    private static void registerWidget(LineReaderImpl impl, String name, Widget w) {
        impl.getWidgets().put(name, w);
    }

    private static List<String> linesBelowCursor(LineReaderImpl reader, String sql, int cursor) {
        if (cursor < 0 || cursor > sql.length()) return List.of();
        String afterCursor = sql.substring(cursor);
        // dòng ĐẦU TIÊN sau cursor (cùng dòng với cursor, phần còn lại phía sau nó)
        // KHÔNG tính vào đây - nó đã được JLine tự vẽ lại đúng qua REDISPLAY bình
        // thường (chỉ những dòng SAU dấu '\n' - tức dòng continuation khác - mới bị
        // menu đè lên hoàn toàn và cần tự khôi phục).
        String[] parts = afterCursor.split("\n", -1);
        List<String> result = new ArrayList<>();
        var highlighter = reader.getHighlighter();
        // secondary prompt = " " (xem SqlctxCli: SECONDARY_PROMPT_PATTERN = " ") -
        // giữ nguyên PLAIN (không tô màu), chỉ tô màu phần nội dung SQL thật sự.
        for (int i = 1; i < parts.length; i++) {
            String lineText = parts[i];
            String ansi = highlighter != null
                    ? highlighter.highlight(reader, lineText).toAnsi()
                    : lineText;
            result.add(" " + ansi);
        }
        return result;
    }
    /** Tính và hiện lại gợi ý theo nội dung/con trỏ hiện tại. Gọi sau self-insert / backward-delete-char / accept-line. */
    private static List<Suggestion> suggest(CompletionInputPreparer.PrepareCompletionInput input) {
        return DialectAdapters.of(SchemaIndex.dialect).suggest(input);
    }

    /** Gói chung (input, suggests) cho 1 lần gợi ý - dùng cho cả đường SQL lẫn đường meta-command. */
    private record Completion(CompletionInputPreparer.PrepareCompletionInput input, List<Suggestion> suggests) {
    }

    /**
     * Buffer bắt đầu bằng '\' (\l, \c, \q, \?...) KHÔNG phải SQL - engine SQL không hiểu và sẽ trả rỗng
     * hoặc lỗi. Tách riêng ra đây: gõ dở tên lệnh thì gợi ý tên lệnh, gõ dở sau "\c " thì gợi ý tên
     * database thật (đọc trực tiếp từ server). Trả về null nếu buffer không phải meta-command, để caller
     * rơi về đúng đường gợi ý SQL bình thường như cũ.
     */
    private static Completion metaCompletion(String sql, int cursor) {
        String head = sql.substring(0, Math.min(cursor, sql.length()));
        String trimmed = head.stripLeading();
        if (!trimmed.startsWith("\\")) {
            return null;
        }

        int sp = trimmed.indexOf(' ');
        String prefix;
        List<Suggestion> suggests;
        if (sp < 0) {
            // Còn đang gõ dở TÊN lệnh (chưa có khoảng trắng) - gợi ý chính các lệnh, kèm mô tả ngắn ở cột giữa
            // (cột này bình thường hiện kiểu dữ liệu cột SQL - đang trống với lệnh, tận dụng luôn cho gọn).
            prefix = trimmed;
            suggests = COMMAND_DOCS.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(trimmed))
                    .map(e -> Suggestion.of(e.getKey(), SuggestionType.COMMAND, e.getValue()))
                    .toList();
        } else {
            String cmd = trimmed.substring(0, sp);
            prefix = trimmed.substring(sp + 1).stripLeading();
            if (cmd.equals("\\c") || cmd.equals("\\connect")) {
                suggests = fetchDatabaseNames().stream()
                        .filter(d -> d.toLowerCase().startsWith(prefix.toLowerCase()))
                        .map(d -> Suggestion.of(d, SuggestionType.DATABASE))
                        .toList();
            } else if (cmd.equals("\\d")) {
                String lower = prefix.toLowerCase();
                suggests = SchemaIndex.schemaTableIndex.values().stream()
                        .filter(t -> t.fullName().startsWith(lower) || t.name().startsWith(lower))
                        .map(t -> Suggestion.of(t.fullName(), SuggestionType.fromLabel(t.kind())))
                        .toList();
            } else if (cmd.equals("\\ctx") || cmd.equals("\\context")) {
                // "\ctx save <tên>" - đang gõ TÊN MỚI để lưu, không phải tên context có sẵn để chọn - không gợi ý.
                if (prefix.toLowerCase().startsWith("save ") || prefix.equalsIgnoreCase("save")) {
                    suggests = List.of();
                } else {
                    String lower = prefix.toLowerCase();
                    suggests = ConnectionProfileStore.loadAll().stream()
                            .filter(p -> p.name().toLowerCase().startsWith(lower))
                            .map(p -> Suggestion.of(p.name(), SuggestionType.DATABASE, p.dialect().toString()))
                            .toList();
                }
            } else {
                // Lệnh khác (\q, \?, \dt, \dn, \df, \i, \e) không có gì để gợi ý sau khoảng trắng - trả về
                // RỖNG (không phải null) để KHÔNG rơi xuống engine SQL: dù engine giờ đã tắt error-listener
                // của lexer (không còn in "token recognition error..." ra màn hình khi gặp '\'), gọi nó vẫn
                // là phí công vô ích cho 1 buffer đã biết chắc không phải SQL.
                suggests = List.of();
            }
        }

        var input = new CompletionInputPreparer.PrepareCompletionInput(sql, cursor, prefix, false, sql, cursor);
        return new Completion(input, suggests);
    }

    private static List<String> fetchDatabaseNames() {
        try {
            return DialectAdapters.of(SchemaIndex.dialect).listDatabases().names();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Completion completionFor(String sql, int cursor) {
        Completion meta = metaCompletion(sql, cursor);
        if (meta != null) {
            return meta;
        }
        var input = CompletionInputPreparer.buildInput(sql, cursor);
        return new Completion(input, suggest(input));
    }

    public static void refreshAutosuggestion(LineReaderImpl reader) {
        String sql = reader.getBuffer().toString();
        int cursor = reader.getBuffer().cursor();

        if (sql.isEmpty()) {
            hide();
            return;
        }

        Completion completion = completionFor(sql, cursor);
        CompletionInputPreparer.PrepareCompletionInput prepareCompletionInput = completion.input();
        List<Suggestion> suggests = completion.suggests();

        if (suggests.isEmpty()) {
            hide();
            return;
        }
        List<AttributedString> lines = render(suggests, -1, 0, prepareCompletionInput.prefix(), prepareCompletionInput.dotMode());
        terminalMenu.show(lines, AnchorStrategies.smart(lines, PAGE_SIZE), 1, 1, linesBelowCursor(reader, sql, cursor));
        autosuggestionOpen = true;
    }
    private static void menuComplete(LineReaderImpl reader) {
        Terminal terminal = reader.getTerminal();

        String sql = reader.getBuffer().toString();
        int cursor = reader.getBuffer().cursor();
        Completion completion = completionFor(sql, cursor);
        CompletionInputPreparer.PrepareCompletionInput prepareCompletionInput = completion.input();
        List<Suggestion> suggests = completion.suggests();

        if (suggests.isEmpty()) {
            return;
        }

        if (suggests.size() == 1) {
            hide();
            insert(reader, suggests.get(0), prepareCompletionInput.prefix(), prepareCompletionInput.dotMode());
            return;
        }

        int selected = 0;
        int scroll = 0;

        BindingReader br = new BindingReader(terminal.reader());
        KeyMap<String> km = keymap(terminal);
        int widthAtOpen = terminal.getWidth();
        int heightAtOpen = terminal.getHeight();

        try {
            while (true) {

                List<Suggestion> filtered = SuggestFilter.filter(suggests, prepareCompletionInput.prefix(), prepareCompletionInput.dotMode());

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

                List<AttributedString> lines = render(filtered, selected, scroll, prepareCompletionInput.prefix(),
                        prepareCompletionInput.dotMode());

                terminalMenu.show(lines, AnchorStrategies.smart(lines, PAGE_SIZE + 1), 1, 1,
                        linesBelowCursor(reader, sql, cursor));

                Suggestion current = filtered.isEmpty() ? null : filtered.get(selected);
                if (current != null) {
                    String ghost = buildGhost(current.getKey(), prepareCompletionInput.prefix(), prepareCompletionInput.dotMode());
                    renderGhost(reader, ghost);
                }

                // Chờ phím kiểu thăm dò để phát hiện đổi cỡ cửa sổ: thread xử lý SIGWINCH chỉ vẽ lại được sau khi
                // widget này kết thúc, nên phải tự thoát khỏi menu, nếu không màn hình trống tới phím kế tiếp.
                while (terminal.reader().peek(150) == NonBlockingReader.READ_EXPIRED) {
                    if (terminal.getWidth() != widthAtOpen || terminal.getHeight() != heightAtOpen) {
                        terminalMenu.forget();
                        autosuggestionOpen = false;
                        return;
                    }
                }
                String key = br.readBinding(km, null, true);
                if (key == null) {
                    break;
                }

                switch (key) {
                    case "up" -> selected = (selected <= 0) ? filtered.size() - 1 : selected - 1;
                    case "down" -> selected = (selected >= filtered.size() - 1) ? 0 : selected + 1;

                    case "enter" -> {
                        hide();
                        // Đã gõ xong 1 statement hoàn chỉnh (kết thúc bằng ';') - Enter phải CHẠY câu lệnh
                        // luôn, không phải chọn gợi ý đang hiển thị. Sau ')'/';' vẫn là vị trí hợp lệ để bắt
                        // đầu 1 statement mới nên bộ gợi ý thật sự có candidate (không rỗng) - nếu chỉ kiểm
                        // tra "rỗng thì mới submit" như trước sẽ vẫn nuốt mất Enter trong đúng tình huống
                        // hay gặp nhất (gõ xong ';' rồi Enter). Đây chính là bug đã gây ORA-03405: khi vòng
                        // lặp này "nuốt" Enter (return mà KHÔNG hề gọi ACCEPT_LINE), JLine coi như user vẫn
                        // đang soạn dở, dòng kế tiếp gõ vào bị NỐI THÊM vào cùng 1 buffer thay vì submit
                        // riêng, gửi cả 2 statement dính liền cho Oracle (chỉ nhận 1 statement/lần execute).
                        if (reader.getBuffer().upToCursor().stripTrailing().endsWith(";")) {
                            reader.callWidget(LineReader.ACCEPT_LINE);
                            return;
                        }
                        if (!filtered.isEmpty()) {
                            insert(reader, filtered.get(selected), prepareCompletionInput.prefix(), prepareCompletionInput.dotMode());
                        }
                        return;
                    }
                    case "space" -> {
                        hide();
                        if (!filtered.isEmpty()) {
                            insert(reader, filtered.get(selected), prepareCompletionInput.prefix(), prepareCompletionInput.dotMode());
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
                        reader.getBuffer().write(ch);
                        return;
                    }
                }
            }


        } catch (IOError | java.io.IOException ignored) {
        } finally {
            hide();
        }
    }
    private static List<AttributedString> render(
            List<Suggestion> items,
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

        // Menu không được rộng hơn terminal: dòng tràn sẽ xuống dòng và để rác ở hàng bên dưới.
        int termWidth = reader.getTerminal().getWidth();
        if (termWidth > 0) {
            int overhead = 1 + 2 + 3 + columnTypeW + 3 + typeW + 2; // leading, icon, gaps (kèm divider "│"), cột phụ, scrollbar
            valueW = Math.max(8, Math.min(valueW, termWidth - overhead - 1));
        }

        int visible = Math.min(PAGE_SIZE, items.size() - scroll);

        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            Suggestion s = items.get(idx);

            boolean sel = idx == selected;

            String key = SuggestFilter.display(s.getKey(), dot);
            if (key.length() > valueW) {
                key = key.substring(0, valueW - 1) + "…";
            }
            String type = s.getType().label();

            AttributedStringBuilder row = new AttributedStringBuilder();

            row.style(sel ? STYLE_SELECTED : STYLE_NORMAL).append(" ");

            // Icon tô màu riêng theo loại (bảng/view/hàm/từ khoá...) - dễ nhận diện bằng mắt hơn 1 màu xám duy nhất.
            row.style(sel ? STYLE_SELECTED : iconStyle(type)).append(typeIcon(type));

            if (sel) {
                row.append(pad(key, valueW));
            } else {
                highlight(row, pad(key, valueW), prefix, dot);
            }

            row.append(" ");
            row.style(sel ? STYLE_SELECTED : STYLE_DIVIDER).append("│");
            row.style(sel ? STYLE_SELECTED : STYLE_NORMAL).append(" ");
            row.style(sel ? STYLE_SELECTED : styleByColumnType(s.getColumnType()))
                    .append(pad(s.getColumnType(), columnTypeW));

            row.append(" ");
            row.style(sel ? STYLE_SELECTED : STYLE_DIVIDER).append("│");
            row.style(sel ? STYLE_SELECTED : STYLE_NORMAL).append(" ");
            row.style(sel ? STYLE_SELECTED : STYLE_TYPE)
                    .append(pad(type, typeW));

            // ── SCROLLBAR ── nét mảnh cho track, nét đậm cho thumb, cùng nền với hàng.
            row.append(" ");
            boolean thumb = isScrollThumb(i, visible, items.size(), scroll);
            row.style(thumb ? STYLE_SCROLL_THUMB : STYLE_SCROLLBAR).append(thumb ? "┃" : "│");

            out.add(row.toAttributedString());
        }
        // ── footer ────────────────────────────────
        int remaining = items.size() - scroll - visible;

        if (remaining > 0) {
            // 1(leading space) + valueW + gap (kèm divider) + typeW + 1(gap)
            int menuWidth = 3 + valueW + 3 + columnTypeW + 3 + typeW + 1;

            AttributedStringBuilder f = new AttributedStringBuilder();
            String text = " ↓ " + remaining + " more ";
            int pad = Math.max(0, menuWidth - text.length());
            // Nền nhạt hơn 1 chút so với thân menu (238 thay vì 236) - phân biệt đây là dòng trạng thái,
            // không phải 1 gợi ý thật, mà không lệch hẳn tông màu như trước (nền xanh dương 23 cũ).
            f.style(AttributedStyle.DEFAULT.background(238).foreground(220).bold())
                    .append(text)
                    .append(" ".repeat(pad));

            // cột scrollbar cuối — cùng dòng, khác style
            f.style(AttributedStyle.DEFAULT.background(238)).append(" ");

            out.add(f.toAttributedString());
        }
        return out;
    }

    /**
     * {@code t} là tên kiểu Postgres đã format đầy đủ (từ {@code pg_catalog.format_type}), ví dụ
     * "timestamp without time zone", "character varying(255)", "numeric(10,2)" - không phải tên ngắn
     * kiểu "int4"/"varchar" - nên so khớp theo TIỀN TỐ, không so khớp tuyệt đối cả chuỗi.
     */
    static AttributedStyle styleByColumnType(String t) {
        if (t == null) {
            return STYLE_NORMAL;
        }

        String lower = t.toLowerCase();
        if (lower.startsWith("interval")) {
            return AttributedStyle.DEFAULT.background(BG).foreground(109);
        }
        if (lower.startsWith("int") || lower.startsWith("numeric") || lower.startsWith("decimal")
                || lower.startsWith("real") || lower.startsWith("double") || lower.startsWith("serial")
                || lower.startsWith("money") || lower.startsWith("number") || lower.startsWith("binary_")) {
            return AttributedStyle.DEFAULT.background(BG).foreground(220);
        }
        if (lower.startsWith("text") || lower.startsWith("character") || lower.startsWith("varchar")
                || lower.startsWith("uuid") || lower.startsWith("json")
                || lower.startsWith("char") || lower.startsWith("clob") || lower.startsWith("long")) {
            return AttributedStyle.DEFAULT.background(BG).foreground(114);
        }
        if (lower.startsWith("timestamp") || lower.startsWith("date") || lower.startsWith("time")) {
            return AttributedStyle.DEFAULT.background(BG).foreground(109);
        }
        if (lower.startsWith("bool")) {
            return AttributedStyle.DEFAULT.background(BG).foreground(141);
        }
        return AttributedStyle.DEFAULT.background(BG).foreground(244);
    }
    private static void insert(LineReaderImpl reader, Suggestion s, String prefix, boolean dot) {
        CompletionHistory.record(s.getKey());
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
    private static void highlight(AttributedStringBuilder sb, String word, String match,
                                  boolean dot) {
        if (match.isEmpty()) {
            // KHÔNG dùng sb.append(word) trần - nó thừa kế style đang dang dở của builder (màu icon riêng
            // theo loại vừa append trước đó), làm chữ ăn theo màu icon thay vì màu chữ thường.
            sb.style(STYLE_NORMAL).append(word);
            return;
        }

        String m = SuggestFilter.matchPart(match, dot);

        // SuggestFilter chỉ khớp phần SAU dấu chấm cuối của key ("public.users" -> "users"), nên chỉ tô từ đó;
        // tô từ đầu sẽ bắt nhầm chữ "u" của "public".
        int start = word.lastIndexOf('.') + 1;
        int j = 0;
        for (int i = 0; i < word.length(); i++) {
            if (i >= start && j < m.length() &&
                    Character.toLowerCase(word.charAt(i)) == Character.toLowerCase(m.charAt(j))) {
                sb.style(STYLE_HIGHLIGHT).append(word.charAt(i));
                j++;
            } else {
                sb.style(STYLE_NORMAL).append(word.charAt(i));
            }
        }
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

    private static int[] calcWidth(List<Suggestion> items, boolean dot) {
        int valueW = 0;
        int typeW = 0;
        int columnTypeW = 0;

        for (Suggestion s : items) {
            valueW = Math.max(valueW, SuggestFilter.display(s.getKey(), dot).length());
            typeW = Math.max(typeW, s.getType().label().length());

            String colType = s.getColumnType() == null ? "" : s.getColumnType();
            columnTypeW = Math.max(columnTypeW, colType.length());
        }

        return new int[]{valueW, typeW, columnTypeW};
    }

    private static boolean isScrollThumb(int row, int visible, int total, int scroll) {
        if (total <= visible) {
            return false;
        }

        float ratio = (float) visible / total;
        int thumbSize = Math.max(1, Math.round(ratio * visible));

        float posRatio = (float) scroll / total;
        int thumbStart = Math.round(posRatio * visible);

        return row >= thumbStart && row < thumbStart + thumbSize;
    }

    /** Màu icon riêng theo loại gợi ý - giúp liếc mắt phân biệt bảng/view/hàm/từ khoá nhanh hơn 1 màu xám duy nhất. */
    private static AttributedStyle iconStyle(String type) {
        int fg = switch (type) {
            case "table" -> 75;
            case "view" -> 80;
            case "materialized view" -> 111;
            case "column" -> 114;
            case "function" -> 176;
            case "keyword" -> 214;
            case "datatype" -> 183;
            case "schema" -> 245;
            case "command" -> 220;
            case "database" -> 111;
            default -> 245;
        };
        return AttributedStyle.DEFAULT.foreground(fg).background(BG);
    }

    private static String buildGhost(String key, String prefix, boolean dot) {
        String display = SuggestFilter.display(key, dot);
        String match = SuggestFilter.matchPart(prefix, dot);

        if (display.toLowerCase().startsWith(match.toLowerCase())) {
            return display.substring(match.length());
        }

        return display;
    }

    private static String lastGhost = "";

    private static void renderGhost(LineReaderImpl reader, String ghost) {
        Terminal term = reader.getTerminal();
        PrintWriter out = term.writer();

        out.print("\u001b[s");

        // Xóa ghost cũ bằng cách in LẠI ĐÚNG TEXT THẬT đang nằm ngay sau cursor
        // (KHÔNG phải khoảng trắng) - nếu cursor không đứng cuối dòng (đang sửa giữa
        // câu), khoảng trắng sẽ xóa mất chính ký tự thật của câu SQL, và REDISPLAY
        // của JLine không biết vùng này đã bị ghi ANSI thô nên không tự phục hồi
        // được (cùng nguyên nhân với bug đã fix ở TerminalMenu.hide()).
        if (!lastGhost.isEmpty()) {
            String buf = reader.getBuffer().toString();
            int cursor = reader.getBuffer().cursor();
            // chỉ lấy phần còn lại TRÊN CÙNG DÒNG (tới '\n' đầu tiên nếu có), vì
            // ghost/cursor luôn nằm trên 1 dòng terminal duy nhất
            int nl = buf.indexOf('\n', cursor);
            String restOfLine = nl >= 0 ? buf.substring(cursor, nl) : buf.substring(cursor);
            if (restOfLine.length() >= lastGhost.length()) {
                out.print(restOfLine.substring(0, lastGhost.length()));
            } else {
                // buffer thật ngắn hơn ghost cũ (hiếm, nhưng phòng thủ) - phần dư
                // ra thật sự trống, an toàn để in khoảng trắng cho đúng phần đó
                out.print(restOfLine);
                out.print(" ".repeat(lastGhost.length() - restOfLine.length()));
            }
        }

        out.print("\u001b[u");

        // Vẽ ghost mới
        if (ghost != null && !ghost.isEmpty()) {
            out.print("\u001b[s");
            AttributedStringBuilder as = new AttributedStringBuilder();
            as.style(AttributedStyle.DEFAULT.foreground(244));
            as.append(ghost);
            out.print(as.toAnsi());
            out.print("\u001b[u");
        }

        lastGhost = ghost == null ? "" : ghost;
        out.flush();
    }

    public static void clearGhost(LineReaderImpl reader) {
        renderGhost(reader, "");
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
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
            case "command" -> "󰘳 "; // nf-md-console
            case "database" -> "󰆼 "; // nf-md-server
            default -> "󰞋 ";
        };
    }

    public static void forgetMenu() {
        autosuggestionOpen = false;
        if (terminalMenu != null) {
            terminalMenu.forget();
        }
    }

    public static void discardMenu(Terminal terminal) {
        autosuggestionOpen = false;
        terminalMenu.discard(terminal.writer());
    }

    public static void hide() {
        autosuggestionOpen = false;
        clearGhost((LineReaderImpl) reader);
        terminalMenu.hide();
    }

}