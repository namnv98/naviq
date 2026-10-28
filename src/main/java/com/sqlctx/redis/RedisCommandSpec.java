package com.sqlctx.redis;

import com.sqlctx.util.I18n;

import com.sqlctx.redis.RedisCommandTree.Arg;
import com.sqlctx.redis.RedisCommandTree.ArgType;
import com.sqlctx.redis.RedisCommandTree.Command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mô tả 1 lệnh cho dòng gợi ý cú pháp, \h và các cờ hành vi - dựng từ cây lệnh của server đang nối
 * ({@link RedisCommandTree#current()}). Cờ write/blocking lấy từ COMMAND INFO; "dangerous" (hỏi [y/N]) là quy
 * tắc của sqlctx, không lấy từ server - xem {@link #DANGEROUS}.
 */
/**
 * {@code serverSummary}: mô tả tiếng Anh của server; {@link #summary()} chọn theo ngôn ngữ lúc gọi (\\lang đổi được giữa
 * chừng): tiếng Việt lấy commands.vi.tsv nếu có.
 */
public record RedisCommandSpec(String name, String group, Set<String> flags, String syntax, String serverSummary,
                               String deprecatedSince, String replacedBy, String complexity) {

    private static final java.util.regex.Pattern BIG_O = java.util.regex.Pattern.compile("O\\((?:[^()]|\\([^()]*\\))*\\)");

    /**
     * Độ phức tạp rút gọn cho dòng gợi ý: các ký hiệu O(...) trong docs ("O(1) for each element added, so O(N)..." ->
     * "O(1) · O(N)"), không có thì null. Câu giải thích đầy đủ xem trong \h.
     */
    public String complexityShort() {
        if (complexity == null || complexity.isBlank()) {
            return null;
        }
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = BIG_O.matcher(complexity);
        while (m.find() && found.size() < 3) {
            found.add(m.group().replace(" ", ""));
        }
        return found.isEmpty() ? null : String.join(" · ", found);
    }

    public String summary() {
        String base = I18n.vi() ? VIETNAMESE.getOrDefault(name, serverSummary) : serverSummary;
        if (deprecatedSince == null) {
            return base;
        }
        return I18n.t("redis.deprecated", deprecatedSince,
                replacedBy != null ? I18n.t("redis.deprecatedUse", replacedBy.replace("`", "")) : "") + base;
    }


    /**
     * Hỏi [y/N] trước khi chạy và chặn ở context readonly. Không dùng thẳng @dangerous của Redis: nhóm đó gồm cả lệnh
     * chỉ đọc như INFO, CLIENT LIST, CONFIG GET, ROLE - hỏi lại mỗi lần gõ chỉ làm người dùng quen bấm "y".
     */
    static final Set<String> DANGEROUS = Set.of(
            "DEL", "UNLINK", "FLUSHALL", "FLUSHDB", "SWAPDB", "MIGRATE", "KEYS", "SAVE", "SHUTDOWN", "DEBUG",
            "FAILOVER", "REPLICAOF", "SLAVEOF", "PFDEBUG",
            "CONFIG SET", "CONFIG REWRITE", "CONFIG RESETSTAT",
            "ACL SETUSER", "ACL DELUSER", "ACL LOAD", "ACL SAVE",
            "CLIENT KILL", "CLIENT PAUSE", "CLIENT NO-EVICT",
            "CLUSTER RESET", "CLUSTER FLUSHSLOTS", "CLUSTER FORGET", "CLUSTER FAILOVER", "CLUSTER DELSLOTS",
            "CLUSTER DELSLOTSRANGE", "CLUSTER SETSLOT", "CLUSTER MEET", "CLUSTER REPLICATE",
            "FUNCTION FLUSH", "FUNCTION DELETE", "FUNCTION RESTORE", "FUNCTION KILL",
            "SCRIPT FLUSH", "SCRIPT KILL",
            "MODULE LOAD", "MODULE LOADEX", "MODULE UNLOAD");

    /** Server đẩy tin liên tục sau lệnh này (không còn hỏi-đáp). */
    static final Set<String> SUBSCRIBE = Set.of("SUBSCRIBE", "PSUBSCRIBE", "SSUBSCRIBE", "MONITOR");

    private static final Map<String, String> VIETNAMESE = loadVietnamese();

    public boolean isWrite() {
        return flags.contains("write");
    }

    public boolean isDangerous() {
        return flags.contains("dangerous");
    }

    public boolean isBlocking() {
        return flags.contains("blocking");
    }

    /** SUBSCRIBE/PSUBSCRIBE/MONITOR: server đẩy tin liên tục, không còn là hỏi-đáp. */
    public boolean isSubscribe() {
        return flags.contains("subscribe");
    }

    /** Kiểu dữ liệu Redis tương ứng nhóm (cho SCAN ... TYPE), null nếu lệnh không gắn với 1 kiểu. */
    public String keyType() {
        return switch (group) {
            case "string", "hash", "list", "set", "stream" -> group;
            case "sorted-set", "geo" -> "zset";
            case "bitmap", "hyperloglog" -> "string"; // đều lưu trong key kiểu string
            default -> null;
        };
    }

    /** Lệnh con ("CONFIG SET") được ưu tiên hơn lệnh cha ("CONFIG") nếu có. */
    public static RedisCommandSpec find(List<String> words) {
        if (words.isEmpty()) {
            return null;
        }
        RedisCommandTree tree = RedisCommandTree.current();
        if (words.size() > 1) {
            RedisCommandSpec sub = tree.spec(words.get(0) + " " + words.get(1));
            if (sub != null) {
                return sub;
            }
        }
        return tree.spec(words.get(0));
    }

    public static RedisCommandSpec find(String name) {
        return RedisCommandTree.current().spec(name);
    }

    public static Collection<RedisCommandSpec> all() {
        return RedisCommandTree.current().specs();
    }

    static RedisCommandSpec of(Command c) {
        Set<String> flags = new LinkedHashSet<>();
        if (c.flags().contains("write") || DANGEROUS.contains(c.name())) {
            flags.add("write");
        }
        if (DANGEROUS.contains(c.name())) {
            flags.add("dangerous");
        }
        if (c.flags().contains("blocking")) {
            flags.add("blocking");
        }
        if (SUBSCRIBE.contains(c.name())) {
            flags.add("subscribe");
        }
        String syntax = c.subcommands().isEmpty()
                ? (c.name() + " " + c.args().stream().map(RedisCommandSpec::display).collect(Collectors.joining(" "))).strip()
                : c.name() + " " + String.join(" | ", c.subcommands().keySet());
        String summary = c.summary() == null ? "" : c.summary();
        return new RedisCommandSpec(c.name(), c.group() == null ? "" : c.group(), Set.copyOf(flags), syntax, summary,
                c.deprecatedSince(), c.replacedBy(), c.complexity());
    }

    /** Cú pháp hiển thị như trên redis.io: [tuỳ chọn], a | b, <nhóm bắt buộc>, [lặp ...]. */
    static String display(Arg a) {
        String s;
        if (a.type() == ArgType.PURE_TOKEN) {
            s = a.token() == null || a.token().isEmpty() ? "\"\"" : a.token();
        } else {
            if (a.type() == ArgType.ONEOF) {
                s = a.children().stream().map(RedisCommandSpec::display).collect(Collectors.joining(" | "));
            } else if (a.type() == ArgType.BLOCK) {
                s = a.children().stream().map(RedisCommandSpec::display).collect(Collectors.joining(" "));
            } else {
                s = a.displayText() != null ? a.displayText() : a.name();
            }
            if (a.token() != null && !a.token().isEmpty()) {
                s = a.token() + " " + (a.type() == ArgType.ONEOF ? "<" + s + ">" : s);
            }
        }
        if (a.multiple()) {
            String repeated = a.multipleToken() || a.token() == null || a.token().isEmpty()
                    ? s : s.substring(s.indexOf(' ') + 1);
            s = s + " [" + repeated + " ...]";
        }
        if (a.optional()) {
            return "[" + s + "]";
        }
        if ((a.type() == ArgType.ONEOF || a.type() == ArgType.BLOCK) && s.contains(" ")) {
            return "<" + s + ">";
        }
        return s;
    }

    /** commands.vi.tsv: tên lệnh TAB mô tả tiếng Việt - đè lên mô tả tiếng Anh của server. */
    private static Map<String, String> loadVietnamese() {
        Map<String, String> out = new HashMap<>();
        try (InputStream in = RedisCommandSpec.class.getResourceAsStream("/redis/commands.vi.tsv")) {
            if (in == null) {
                return out;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t", -1);
                if (cols.length >= 2) {
                    out.put(cols[0].toUpperCase(Locale.ROOT), cols[1]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
