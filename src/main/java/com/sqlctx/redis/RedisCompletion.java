package com.sqlctx.redis;

import com.sqlctx.util.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gợi ý tại con trỏ: từ khoá lấy từ cây lệnh của server (qua {@link RedisSyntax#analyze}), dữ liệu thật (key, field,
 * member, tham số cấu hình, channel) lấy từ server qua {@link Source}. Tách khỏi JLine để test được.
 */
public final class RedisCompletion {

    /** Dữ liệu thật để gợi ý - RedisSession cài bằng 1 kết nối phụ (không chen vào MULTI/SUBSCRIBE của kết nối chính). */
    public interface Source {
        /** Key bắt đầu bằng {@code prefix}; {@code type} != null thì chỉ lấy key đúng kiểu đó. */
        List<String> keys(String prefix, String type);

        /** Field của hash / member của set, zset bắt đầu bằng {@code prefix}. */
        List<String> members(String key, String type, String prefix);

        List<String> configParams();

        List<String> channels();

        List<String> contexts();

        /** Consumer group của 1 stream; {@code key} null = group của mọi stream (XREADGROUP: key gõ sau group). */
        List<String> groups(String key);

        List<String> consumers(String key, String group);

        /** Id đang chờ XACK của group (XPENDING), mới nhất trước. */
        List<String> pendingIds(String key, String group);

        /** Id entry mới nhất trước (XREVRANGE). */
        List<String> entryIds(String key);
    }

    public enum Kind {
        COMMAND, KEYWORD, KEY, MEMBER, VALUE, META
    }

    public record Item(String value, String description, Kind kind) {
    }

    static final List<String> INFO_SECTIONS = List.of("server", "clients", "memory", "persistence", "stats",
            "replication", "cpu", "commandstats", "latencystats", "errorstats", "cluster", "keyspace",
            "modules", "all", "everything");

    static final List<String[]> META_COMMANDS = List.of(
            new String[]{"\\ctx", "redis.meta.ctx"},
            new String[]{"\\c", "redis.meta.c"},
            new String[]{"\\l", "redis.meta.l"},
            new String[]{"\\dt", "redis.meta.dt"},
            new String[]{"\\d", "redis.meta.d"},
            new String[]{"\\tail", "redis.meta.tail"},
            new String[]{"\\h", "redis.meta.h"},
            new String[]{"\\i", "redis.meta.i"},
            new String[]{"\\o", "redis.meta.o"},
            new String[]{"\\lang", "meta.lang"},
            new String[]{"\\q", "redis.meta.q"});

    private RedisCompletion() {
    }

    /** Phần đang gõ dở ngay trước con trỏ (từ khoảng trắng gần nhất). */
    static String currentWord(String beforeCursor) {
        int i = beforeCursor.length();
        while (i > 0 && !Character.isWhitespace(beforeCursor.charAt(i - 1))) {
            i--;
        }
        return beforeCursor.substring(i);
    }

    public static List<Item> suggest(String line, int cursor, Source source) {
        String before = line.substring(0, Math.min(cursor, line.length()));
        String word = currentWord(before);
        String head = before.substring(0, before.length() - word.length());

        if (before.stripLeading().startsWith("\\")) {
            return suggestMeta(head.strip(), word, source);
        }
        // đang trong dấu nháy chưa đóng: không gợi ý (giá trị tự do)
        if (word.startsWith("\"") || word.startsWith("'")) {
            return List.of();
        }

        RedisSyntax.Caret caret = RedisSyntax.analyze(head);
        List<String> words = RedisSyntax.words(head);
        boolean firstWord = words.isEmpty();
        boolean lower = !word.isEmpty() && word.equals(word.toLowerCase(Locale.ROOT));
        String upperWord = word.toUpperCase(Locale.ROOT);

        List<Item> items = new ArrayList<>();
        for (String kw : caret.keywords()) {
            if (!kw.startsWith(upperWord)) {
                continue;
            }
            String text = lower ? kw.toLowerCase(Locale.ROOT) : kw;
            if (firstWord) {
                RedisCommandSpec spec = RedisCommandSpec.find(kw);
                items.add(new Item(text, spec != null ? spec.summary() : null, Kind.COMMAND));
            } else {
                RedisCommandSpec sub = RedisCommandSpec.find(List.of(words.get(0), kw));
                String desc = words.size() == 1 && sub != null && !sub.name().equalsIgnoreCase(words.get(0))
                        ? sub.summary() : null;
                items.add(new Item(text, desc, Kind.KEYWORD));
            }
        }

        Set<String> rules = caret.argRules();
        RedisCommandSpec spec = RedisCommandSpec.find(words);
        String type = spec != null ? spec.keyType() : null;
        if (rules.contains("key")) {
            for (String k : source.keys(word, type)) {
                items.add(new Item(quoteIfNeeded(k), type, Kind.KEY));
            }
        }
        if ((rules.contains("field") || rules.contains("member")) && words.size() >= 2 && type != null) {
            for (String m : source.members(words.get(1), type, word)) {
                items.add(new Item(quoteIfNeeded(m), null, Kind.MEMBER));
            }
        }
        if (rules.contains("configParam")) {
            for (String p : source.configParams()) {
                if (p.startsWith(word.toLowerCase(Locale.ROOT))) {
                    items.add(new Item(p, null, Kind.VALUE));
                }
            }
        }
        if (rules.contains("infoSection")) {
            for (String s : INFO_SECTIONS) {
                if (s.startsWith(word.toLowerCase(Locale.ROOT))) {
                    items.add(new Item(s, null, Kind.VALUE));
                }
            }
        }
        RedisSyntax.Inspection typed = null;
        if (rules.contains("group") || rules.contains("anyGroup") || rules.contains("consumer")
                || rules.contains("pendingId") || rules.contains("entryId")) {
            typed = RedisSyntax.inspect(head);
        }
        if (typed != null) {
            String key = typed.first("key");
            String group = typed.first("group") != null ? typed.first("group") : typed.first("anyGroup");
            List<String> found = new ArrayList<>();
            if (rules.contains("group") && key != null) {
                found.addAll(source.groups(key));
            }
            if (rules.contains("anyGroup")) {
                found.addAll(source.groups(null));
            }
            if (rules.contains("consumer") && key != null && group != null) {
                found.addAll(source.consumers(key, group));
            }
            if (rules.contains("pendingId") && key != null && group != null) {
                found.addAll(source.pendingIds(key, group));
            }
            if (rules.contains("entryId") && key != null) {
                found.addAll(source.entryIds(key));
            }
            for (String f : found) {
                if (f.startsWith(word)) {
                    items.add(new Item(quoteIfNeeded(f), null, Kind.VALUE));
                }
            }
        }
        for (String[] special : caret.specials()) {
            if (special[0].startsWith(word)) {
                items.add(new Item(special[0], I18n.t(special[1]), Kind.VALUE));
            }
        }
        if (rules.contains("channel")) {
            for (String c : source.channels()) {
                if (c.startsWith(word)) {
                    items.add(new Item(quoteIfNeeded(c), null, Kind.VALUE));
                }
            }
        }
        return items;
    }

    private static List<Item> suggestMeta(String head, String word, Source source) {
        List<Item> items = new ArrayList<>();
        if (head.isEmpty()) {
            for (String[] m : META_COMMANDS) {
                if (m[0].startsWith(word)) {
                    items.add(new Item(m[0], I18n.t(m[1]), Kind.META));
                }
            }
            return items;
        }
        String cmd = head.split("\\s+")[0];
        switch (cmd) {
            case "\\ctx" -> {
                for (String c : source.contexts()) {
                    if (c.startsWith(word)) {
                        items.add(new Item(c, null, Kind.VALUE));
                    }
                }
            }
            case "\\d" -> {
                for (String k : source.keys(word, null)) {
                    items.add(new Item(quoteIfNeeded(k), null, Kind.KEY));
                }
            }
            case "\\tail" -> {
                // \tail KEY [GROUP group consumer] [FROM id]
                String[] parts = head.split("\\s+");
                String prev = parts[parts.length - 1].toUpperCase(Locale.ROOT);
                String prev2 = parts.length > 2 ? parts[parts.length - 2].toUpperCase(Locale.ROOT) : "";
                if (parts.length == 1) {
                    for (String k : source.keys(word, "stream")) {
                        items.add(new Item(quoteIfNeeded(k), null, Kind.KEY));
                    }
                } else if (prev.equals("GROUP")) {
                    for (String g : source.groups(parts[1])) {
                        if (g.startsWith(word)) {
                            items.add(new Item(quoteIfNeeded(g), null, Kind.VALUE));
                        }
                    }
                } else if (prev2.equals("GROUP")) {
                    for (String c : source.consumers(parts[1], parts[parts.length - 1])) {
                        if (c.startsWith(word)) {
                            items.add(new Item(quoteIfNeeded(c), I18n.t("redis.tail.existingConsumer"), Kind.VALUE));
                        }
                    }
                } else if (prev.equals("FROM")) {
                    for (String[] sp : new String[][]{{"$", I18n.t("redis.tail.fromNew")}, {"0", I18n.t("stream.fromFirst")}}) {
                        if (sp[0].startsWith(word)) {
                            items.add(new Item(sp[0], sp[1], Kind.VALUE));
                        }
                    }
                } else {
                    for (String opt : List.of("GROUP", "FROM")) {
                        boolean used = head.toUpperCase(Locale.ROOT).contains(" " + opt);
                        if (!used && opt.startsWith(word.toUpperCase(Locale.ROOT))) {
                            items.add(new Item(opt, opt.equals("GROUP") ? I18n.t("redis.tail.groupOpt")
                                    : I18n.t("redis.tail.fromOpt"), Kind.KEYWORD));
                        }
                    }
                }
            }
            default -> {
            }
        }
        return items;
    }

    /** Key có khoảng trắng/dấu nháy phải bọc nháy kép mới gõ lại được. */
    static String quoteIfNeeded(String s) {
        boolean plain = !s.isEmpty();
        for (int i = 0; i < s.length() && plain; i++) {
            char c = s.charAt(i);
            plain = !Character.isWhitespace(c) && c != '"' && c != '\'' && c >= 0x20;
        }
        if (plain) {
            return s;
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
