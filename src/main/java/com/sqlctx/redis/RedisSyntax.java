package com.sqlctx.redis;

import com.sqlctx.util.I18n;

import com.sqlctx.redis.RedisArgMatcher.Keyword;
import com.sqlctx.redis.RedisArgMatcher.Node;
import com.sqlctx.redis.RedisArgMatcher.Value;
import com.sqlctx.redis.RedisCommandTree.Command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tầng cú pháp của chế độ Redis, đi trên cây lệnh của server đang nối ({@link RedisCommandTree#current()}):
 * <ul>
 *   <li>{@link #analyze}: tại con trỏ, được gõ từ khoá nào và đang ở tham số loại gì (key, field, configParam...) -
 *   RedisCompletion dựa vào đó để gợi ý từ khoá hoặc lấy dữ liệu thật từ server.</li>
 *   <li>{@link #inspect}: vai trò từng token (để tô màu) và token sai đầu tiên.</li>
 * </ul>
 * Lệnh không có trong cây (module không khai báo docs) và lệnh con lạ đều được nhận, không gợi ý tham số.
 */
public final class RedisSyntax {

    private RedisSyntax() {
    }

    static final List<String> TYPE_NAMES = List.of("STRING", "LIST", "SET", "ZSET", "HASH", "STREAM");

    /** 1 token của dòng lệnh: chữ gốc (kể cả nháy), vị trí [start, end), giá trị đã bỏ nháy. */
    public record Tok(String raw, int start, int end, boolean quoted, String text) {
    }

    /** Tách như redis-cli nhưng chịu được gõ dở: nháy chưa đóng thì token kéo tới hết dòng. */
    public static List<Tok> tokenize(String line) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            if (Character.isWhitespace(line.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            char q = line.charAt(i);
            if (q == '"' || q == '\'') {
                i++;
                while (i < n && line.charAt(i) != q) {
                    i += line.charAt(i) == '\\' && i + 1 < n ? 2 : 1;
                }
                i = Math.min(n, i + 1);
                while (i < n && !Character.isWhitespace(line.charAt(i))) {
                    i++;
                }
                String raw = line.substring(start, i);
                out.add(new Tok(raw, start, i, true, unquote(raw)));
            } else {
                while (i < n && !Character.isWhitespace(line.charAt(i))) {
                    i++;
                }
                String raw = line.substring(start, i);
                out.add(new Tok(raw, start, i, false, raw));
            }
        }
        return out;
    }

    /** Các từ đã gõ, bỏ nháy - đủ để tìm tên lệnh/lệnh con và key đứng sau. */
    public static List<String> words(String text) {
        return tokenize(text).stream().map(Tok::text).toList();
    }

    static String unquote(String raw) {
        try {
            List<byte[]> args = RedisArgs.split(raw);
            return args.isEmpty() ? "" : RedisArgs.text(args.get(0));
        } catch (IllegalArgumentException e) {
            String s = raw.substring(1);
            return s.endsWith(raw.substring(0, 1)) ? s.substring(0, s.length() - 1) : s;
        }
    }

    // ------------------------------------------------------------------ dòng lệnh -> cây tham số cần đi

    /** Tham số của dòng lệnh tính từ đâu, đi trên cây nào; {@code root} null = lệnh/lệnh con lạ, nhận mọi thứ. */
    private record Target(Command command, Node root, int argsFrom, boolean expectsSubcommand) {
    }

    private static Target target(List<String> words) {
        RedisCommandTree tree = RedisCommandTree.current();
        Command cmd = tree.command(words.get(0));
        if (cmd == null) {
            return new Target(null, null, 1, false);
        }
        if (cmd.subcommands().isEmpty()) {
            return new Target(cmd, tree.compiled(cmd), 1, false);
        }
        if (words.size() == 1) {
            return new Target(cmd, null, 1, true);
        }
        Command sub = cmd.subcommands().get(words.get(1).toUpperCase(Locale.ROOT));
        return sub == null ? new Target(cmd, null, 2, false) : new Target(sub, tree.compiled(sub), 2, false);
    }

    // ------------------------------------------------------------------ gợi ý

    /**
     * Từ khoá hợp lệ tại con trỏ (viết hoa, đúng chữ cần gõ), loại tham số đang chờ, và giá trị đặc biệt của tham số
     * đó ({giá trị, mô tả}, vd "$" của XREAD).
     */
    public record Caret(List<String> keywords, Set<String> argRules, List<String[]> specials, boolean reachedCaret) {

        public Caret(List<String> keywords, Set<String> argRules, boolean reachedCaret) {
            this(keywords, argRules, List.of(), reachedCaret);
        }
    }

    /**
     * {@code text} là phần TRƯỚC từ đang gõ (kết thúc bằng khoảng trắng hoặc rỗng) - người gọi tự lọc theo phần đã
     * gõ, vì ở đây chỉ trả những gì đứng được ở vị trí token mới.
     */
    public static Caret analyze(String text) {
        List<String> words = words(text);
        RedisCommandTree tree = RedisCommandTree.current();
        if (words.isEmpty()) {
            return new Caret(tree.commands().stream().map(Command::name).sorted().toList(), Set.of(), true);
        }
        Target t = target(words);
        if (t.expectsSubcommand()) {
            return new Caret(List.copyOf(new TreeSet<>(t.command().subcommands().keySet())), Set.of(), true);
        }
        if (t.root() == null) {
            return new Caret(List.of(), Set.of("arg"), true);
        }
        RedisArgMatcher.Result r = RedisArgMatcher.match(t.root(), words.subList(t.argsFrom(), words.size()));
        if (r.failedAt() >= 0) {
            return new Caret(List.of(), Set.of(), false);
        }
        Set<String> keywords = new TreeSet<>();
        Set<String> rules = new LinkedHashSet<>();
        java.util.Map<String, String[]> specials = new java.util.LinkedHashMap<>();
        for (Node n : r.expected()) {
            if (n instanceof Keyword k) {
                keywords.add(k.text.toUpperCase(Locale.ROOT));
            } else if (n instanceof Value v) {
                if (v.rule.equals("typeName")) {
                    keywords.addAll(TYPE_NAMES);
                } else {
                    rules.add(v.rule);
                }
                for (String[] sp : v.specials) {
                    specials.putIfAbsent(sp[0], sp);
                }
            }
        }
        return new Caret(List.copyOf(keywords), rules, List.copyOf(specials.values()), true);
    }

    // ------------------------------------------------------------------ tô màu / lỗi

    public enum Role {
        COMMAND, UNKNOWN_COMMAND, KEYWORD, KEY, FIELD, VALUE, STRING, NUMBER
    }

    /**
     * Vai trò từng token (cùng thứ tự với {@code tokens}); {@code argRules}: loại tham số mà token đã khớp (key, group,
     * consumer...; null nếu là lệnh/từ khoá/không khớp). Lỗi = vị trí ký tự của token sai đầu tiên, -1 nếu không có.
     */
    public record Inspection(List<Tok> tokens, List<Role> roles, List<String> argRules, int errorOffset, String error,
                             boolean incomplete) {

        /** Token đầu tiên khớp loại tham số {@code rule} (vd key của lệnh, group đã gõ), null nếu chưa có. */
        public String first(String rule) {
            for (int i = 0; i < argRules.size() && i < tokens.size(); i++) {
                if (rule.equals(argRules.get(i))) {
                    return tokens.get(i).text();
                }
            }
            return null;
        }
    }

    public static Inspection inspect(String text) {
        List<Tok> tokens = tokenize(text);
        List<Role> roles = new ArrayList<>();
        List<String> argRules = new ArrayList<>();
        if (tokens.isEmpty()) {
            return new Inspection(tokens, roles, argRules, -1, null, false);
        }
        List<String> words = tokens.stream().map(Tok::text).toList();
        Target t = target(words);
        roles.add(t.command() == null ? Role.UNKNOWN_COMMAND : Role.COMMAND);
        if (t.argsFrom() == 2) {
            roles.add(Role.KEYWORD);
        }
        if (t.root() == null) {
            for (int i = roles.size(); i < tokens.size(); i++) {
                roles.add(plain(tokens.get(i)));
            }
            return new Inspection(tokens, roles, argRules, -1, null, t.expectsSubcommand());
        }
        List<String> args = words.subList(t.argsFrom(), words.size());
        RedisArgMatcher.Result r = RedisArgMatcher.match(t.root(), args);
        for (int i = 0; i < t.argsFrom(); i++) {
            argRules.add(null);
        }
        for (int i = 0; i < r.matchedLeaves().size(); i++) {
            Node leaf = r.matchedLeaves().get(i);
            roles.add(roleOf(leaf, tokens.get(t.argsFrom() + i)));
            argRules.add(leaf instanceof Value v ? v.rule : null);
        }
        if (r.failedAt() < 0) {
            return new Inspection(tokens, roles, argRules, -1, null, !r.complete());
        }
        int bad = t.argsFrom() + r.failedAt();
        Tok badTok = tokens.get(bad);
        boolean typingLast = bad == tokens.size() - 1 && badTok.end() == text.length();
        for (int i = bad; i < tokens.size(); i++) {
            roles.add(plain(tokens.get(i)));
        }
        if (typingLast && RedisArgMatcher.isPrefixOfExpected(r.expected(), badTok.text())) {
            return new Inspection(tokens, roles, argRules, -1, null, true); // đang gõ dở 1 từ khoá/số hợp lệ
        }
        String message = r.complete() && r.expected().isEmpty()
                ? I18n.t("redis.syntax.extra", badTok.raw())
                : I18n.t("redis.syntax.bad", badTok.raw());
        return new Inspection(tokens, roles, argRules, badTok.start(), message, false);
    }

    private static Role roleOf(Node leaf, Tok tok) {
        if (leaf instanceof Keyword) {
            return Role.KEYWORD;
        }
        String rule = ((Value) leaf).rule;
        return switch (rule) {
            case "key" -> Role.KEY;
            case "field", "member", "element", "group", "anyGroup", "consumer" -> tok.quoted() ? Role.STRING : Role.FIELD;
            case "integer", "number" -> Role.NUMBER;
            default -> plain(tok);
        };
    }

    private static Role plain(Tok tok) {
        if (tok.quoted()) {
            return Role.STRING;
        }
        return tok.text().matches("[+-]?\\d+(\\.\\d+)?") ? Role.NUMBER : Role.VALUE;
    }
}
