package com.sqlctx.kafka;

import com.sqlctx.util.I18n;

import com.sqlctx.kafka.KafkaTools.Arity;
import com.sqlctx.kafka.KafkaTools.Opt;
import com.sqlctx.kafka.KafkaTools.Tool;
import com.sqlctx.ui.CompletionMenu;
import com.sqlctx.ui.MenuItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gợi ý tại con trỏ: tên tool, chế độ, tuỳ chọn hợp lệ với chế độ đang chọn, và giá trị theo loại của tuỳ chọn
 * (topic, group, broker, partition, tên config... lấy từ cluster qua {@link Source}). Tách khỏi JLine để test được.
 */
public final class KafkaCompletion {

    private KafkaCompletion() {
    }

    /** {@code bool}: config kiểu BOOLEAN; {@code defaultValue}: giá trị topic/broker mới sẽ nhận (null nếu không biết). */
    public record ConfigMeta(boolean bool, String defaultValue) {
    }

    /** Dữ liệu thật - KafkaSession cài, có cache vì gọi ở mỗi phím gõ. */
    public interface Source {
        List<String> topics();

        List<String> groups();

        List<String> transactionalIds();

        List<String> brokers();

        List<String> partitions(String topic);

        /** {@code entityType}: topics hoặc brokers. */
        List<String> configNames(String entityType);

        /** Kiểu và giá trị mặc định của 1 config (null nếu không biết) - để gợi ý giá trị sau "tên=". */
        default ConfigMeta config(String entityType, String name) {
            return null;
        }

        List<String> subjects();

        List<String> contexts();
    }

    static final List<String[]> META = List.of(
            new String[]{"\\ctx", "kafka.meta.ctx"},
            new String[]{"\\brokers", "kafka.meta.brokers"},
            new String[]{"\\stats", "kafka.meta.stats"},
            new String[]{"\\h", "kafka.meta.h"},
            new String[]{"\\i", "kafka.meta.i"},
            new String[]{"\\lang", "meta.lang"},
            new String[]{"\\q", "kafka.meta.q"});

    /** Tuỳ chọn có nhưng không gợi ý: sqlctx bỏ qua chúng, gợi ý chỉ làm rối. */
    private static boolean hidden(Opt o) {
        return o.help().startsWith("bỏ qua") || o.help().startsWith("(cũ)");
    }

    public static List<MenuItem> suggest(String line, int cursor, Source source) {
        String before = line.substring(0, Math.min(cursor, line.length()));
        String word = CompletionMenu.currentWord(before);
        String head = before.substring(0, before.length() - word.length());
        List<MenuItem> items = new ArrayList<>();
        if (before.stripLeading().startsWith("\\")) {
            return meta(head.strip(), word, source);
        }
        KafkaArgs.Parsed p = KafkaArgs.parse(head);
        if (p.tokens().isEmpty()) {
            for (Tool t : KafkaTools.TOOLS.values()) {
                if (t.name().startsWith(word)) {
                    items.add(new MenuItem(t.name(), I18n.tr(t.help()), "tool"));
                }
            }
            return items;
        }
        Tool tool = p.tool();
        if (tool == null) {
            return items;
        }
        // đang ở chỗ giá trị của 1 tuỳ chọn
        if (p.pending() != null) {
            values(p.pending(), p, word, source, items);
            if (p.pending().arity() != Arity.OPTIONAL_VALUE) {
                return items;
            }
        }
        // lệnh con đứng ngay sau tên tool (kafka-cluster cluster-id)
        if (tool.positionalModes() && p.mode() == null && p.tokens().size() == 1) {
            for (String m : tool.modes()) {
                if (m.startsWith(word)) {
                    items.add(new MenuItem(m, I18n.tr(tool.modeHelp().get(m)), "option"));
                }
            }
            return items;
        }
        if (!word.isEmpty() && !word.startsWith("-")) {
            return items; // giá trị tự do không đi sau tuỳ chọn nào: không có gì để gợi ý
        }
        if (tool.hasModes() && p.mode() == null && !tool.positionalModes()) {
            for (String m : tool.modes()) {
                if (m.startsWith(word)) {
                    items.add(new MenuItem(m, I18n.tr(tool.modeHelp().get(m)), "option"));
                }
            }
        }
        boolean modeChosen = !tool.hasModes() || p.mode() != null;
        for (Opt o : tool.options()) {
            if (hidden(o) || !o.name().startsWith(word)) {
                continue;
            }
            if (!o.modes().isEmpty() && (!modeChosen || !o.modes().contains(p.mode()))) {
                continue;
            }
            if (p.has(o.name()) && !o.repeatable()) {
                continue;
            }
            items.add(new MenuItem(o.name(), (o.meta() != null ? "<" + o.meta() + "> " : "") + I18n.tr(o.help()), "option"));
        }
        return items;
    }

    private static void values(Opt opt, KafkaArgs.Parsed p, String word, Source source, List<MenuItem> items) {
        String kind = opt.kind();
        switch (kind) {
            case "topic" -> add(items, source.topics(), word, "topic");
            case "group" -> add(items, source.groups(), word, "group");
            case "transactionalId" -> add(items, source.transactionalIds(), word, "value");
            case "broker" -> add(items, source.brokers(), word, "broker");
            case "subject" -> add(items, source.subjects(), word, "subject");
            case "partition" -> {
                String topic = p.get("--topic");
                if (topic != null) {
                    add(items, source.partitions(topic), word, "partition");
                }
            }
            case "topicPartitions" -> add(items, source.topics(), word, "topic");
            case "topicList" -> listOf(items, source.topics(), word, "topic");
            case "entityName" -> {
                String type = p.get("--entity-type");
                if ("topics".equals(type)) {
                    add(items, source.topics(), word, "topic");
                } else if ("brokers".equals(type) || "broker-loggers".equals(type)) {
                    add(items, source.brokers(), word, "broker");
                }
            }
            case "topicConfig" -> keyValue(items, source, "topics", word, false);
            case "configList", "configNames" -> {
                String type = p.has("--broker") || p.has("--broker-defaults") ? "brokers"
                        : p.has("--topic") ? "topics" : p.get("--entity-type");
                if ("topics".equals(type) || "brokers".equals(type)) {
                    if (kind.equals("configList")) {
                        keyValue(items, source, type, word, true);
                    } else {
                        listOf(items, source.configNames(type), word, "config");
                    }
                }
            }
            case "principal" -> {
                if ("User:".startsWith(word) || word.isEmpty()) {
                    items.add(new MenuItem("User:", I18n.t("kafka.principalHint"), "value"));
                }
            }
            default -> {
                for (String[] e : KafkaTools.ENUMS.getOrDefault(kind, List.of())) {
                    if (e[0].toLowerCase(Locale.ROOT).startsWith(word.toLowerCase(Locale.ROOT))) {
                        items.add(new MenuItem(e[0], I18n.tr(e[1]), "value"));
                    }
                }
            }
        }
    }

    private static void add(List<MenuItem> items, List<String> values, String word, String type) {
        for (String v : values) {
            if (v.startsWith(word)) {
                items.add(new MenuItem(v, null, type));
            }
        }
    }

    /** "a,b,|": gợi ý phần sau dấu phẩy cuối, giữ nguyên phần trước. */
    private static void listOf(List<MenuItem> items, List<String> values, String word, String type) {
        int comma = word.lastIndexOf(',');
        String done = comma < 0 ? "" : word.substring(0, comma + 1);
        String part = word.substring(comma + 1);
        for (String v : values) {
            if (v.startsWith(part)) {
                items.add(new MenuItem(done + v, null, type));
            }
        }
    }

    /**
     * "retention.ms|": gợi ý tên config kèm "=" để gõ tiếp giá trị; "cleanup.policy=|": gợi ý giá trị - danh sách giá
     * trị hợp lệ của config dạng liệt kê ({@link KafkaTools#CONFIG_VALUES}), true/false cho config BOOLEAN, và giá trị
     * mặc định cluster đang áp dụng. {@code list}: nhiều config cách nhau dấu phẩy (kafka-configs --add-config), giá trị
     * có dấu phẩy phải bọc [ ].
     */
    private static void keyValue(List<MenuItem> items, Source source, String entityType, String word, boolean list) {
        int comma = list ? lastTopLevelComma(word) : -1;
        String part = word.substring(comma + 1);
        String done = comma < 0 ? "" : word.substring(0, comma + 1);
        int eq = part.indexOf('=');
        if (eq < 0) {
            for (String n : source.configNames(entityType)) {
                if (n.startsWith(part)) {
                    items.add(new MenuItem(done + n + "=", null, "config"));
                }
            }
            return;
        }
        String name = part.substring(0, eq);
        String typed = part.substring(eq + 1);
        String prefix = done + name + "=";
        ConfigMeta meta = source.config(entityType, name);
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        for (String[] v : KafkaTools.CONFIG_VALUES.getOrDefault(name, List.of())) {
            values.put(list && v[0].contains(",") ? "[" + v[0] + "]" : v[0], I18n.tr(v[1]));
        }
        if (meta != null && meta.bool()) {
            values.putIfAbsent("true", null);
            values.putIfAbsent("false", null);
        }
        if (meta != null && meta.defaultValue() != null && !meta.defaultValue().isEmpty()) {
            String def = list && meta.defaultValue().contains(",") ? "[" + meta.defaultValue() + "]" : meta.defaultValue();
            String note = I18n.t("kafka.configDefault");
            values.merge(def, note, (old, n) -> old == null ? n : old + " · " + n);
        }
        values.forEach((v, help) -> {
            if (v.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT))) {
                items.add(new MenuItem(prefix + v, help, "value"));
            }
        });
    }

    /** Dấu phẩy cuối cùng không nằm trong [ ] (giá trị dạng danh sách của kafka-configs). */
    private static int lastTopLevelComma(String word) {
        int depth = 0;
        int last = -1;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                last = i;
            }
        }
        return last;
    }

    private static List<MenuItem> meta(String head, String word, Source source) {
        List<MenuItem> items = new ArrayList<>();
        if (head.isEmpty()) {
            for (String[] m : META) {
                if (m[0].startsWith(word)) {
                    items.add(new MenuItem(m[0], I18n.t(m[1]), "sqlctx"));
                }
            }
            return items;
        }
        String cmd = head.split("\\s+")[0];
        if (cmd.equals("\\ctx")) {
            add(items, source.contexts(), word, "value");
        } else if (cmd.equals("\\h")) {
            for (Tool t : KafkaTools.TOOLS.values()) {
                if (t.name().startsWith(word)) {
                    items.add(new MenuItem(t.name(), I18n.tr(t.help()), "tool"));
                }
            }
        }
        return items;
    }
}
