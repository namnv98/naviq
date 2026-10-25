package com.sqlctx.kafka;

import com.sqlctx.util.I18n;

import com.sqlctx.kafka.KafkaTools.Arity;
import com.sqlctx.kafka.KafkaTools.Opt;
import com.sqlctx.kafka.KafkaTools.Tool;
import com.sqlctx.shell.CompletionMenu;
import com.sqlctx.shell.MenuItem;

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

    /** Dữ liệu thật - KafkaSession cài, có cache vì gọi ở mỗi phím gõ. */
    public interface Source {
        List<String> topics();

        List<String> groups();

        List<String> transactionalIds();

        List<String> brokers();

        List<String> partitions(String topic);

        /** {@code entityType}: topics hoặc brokers. */
        List<String> configNames(String entityType);

        List<String> subjects();

        List<String> contexts();
    }

    static final List<String[]> META = List.of(
            new String[]{"\\ctx", "kafka.meta.ctx"},
            new String[]{"\\brokers", "kafka.meta.brokers"},
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
            case "topicConfig" -> keyValue(items, source.configNames("topics"), word);
            case "configList", "configNames" -> {
                String type = p.has("--broker") || p.has("--broker-defaults") ? "brokers"
                        : p.has("--topic") ? "topics" : p.get("--entity-type");
                if ("topics".equals(type) || "brokers".equals(type)) {
                    List<String> names = source.configNames(type);
                    if (kind.equals("configList")) {
                        keyValue(items, names, word);
                    } else {
                        listOf(items, names, word, "config");
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

    /** "retention.ms=|" chưa gõ "=": gợi ý tên config kèm "=" để gõ tiếp giá trị. */
    private static void keyValue(List<MenuItem> items, List<String> names, String word) {
        int comma = word.lastIndexOf(',');
        String part = word.substring(comma + 1);
        if (part.contains("=")) {
            return;
        }
        String done = comma < 0 ? "" : word.substring(0, comma + 1);
        for (String n : names) {
            if (n.startsWith(part)) {
                items.add(new MenuItem(done + n + "=", null, "config"));
            }
        }
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
