package com.sqlctx.redis;

import com.sqlctx.redis.RedisCommandTree.Arg;
import com.sqlctx.redis.RedisCommandTree.ArgType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Đi trên cây tham số của COMMAND DOCS: giữ 1 TẬP "vị trí có thể đang đứng" (có nhánh tuỳ chọn nên cùng lúc có thể
 * ở nhiều chỗ), mỗi token gõ vào đẩy tập đó tiến lên. Tập rỗng = token đó sai; tại con trỏ, các nút đứng đầu của
 * tập vị trí là những gì được phép gõ tiếp (từ khoá, hoặc 1 tham số loại key/field/số...).
 * <p>
 * Khác cây gốc ở 1 chỗ: các tuỳ chọn bắt đầu bằng từ khoá đứng liền nhau ([NX|XX] [GET] [EX s|...]) được nhận theo
 * thứ tự bất kỳ, mỗi cái 1 lần (trừ cái có cờ multiple), vì server cũng nhận như vậy - đi đúng thứ tự trong docs
 * sẽ báo lỗi nhầm "SET k v EX 10 NX".
 */
final class RedisArgMatcher {

    private RedisArgMatcher() {
    }

    // ------------------------------------------------------------------ nút đã dịch
    // Lớp thường (so sánh theo identity), không phải record: tập vị trí băm các nút này ở mỗi token, record sẽ băm
    // theo nội dung - duyệt lại cả cây con mỗi lần.

    abstract static class Node {
    }

    /** Đúng 1 từ khoá. */
    static final class Keyword extends Node {
        final String text;

        Keyword(String text) {
            this.text = text;
        }
    }

    /** 1 tham số; {@code rule} là loại dùng cho gợi ý/tô màu: key, field, member, element, value, pattern... */
    static final class Value extends Node {
        final Arg arg;
        final String rule;
        /** Giá trị có nghĩa riêng nên gợi ý kèm giải thích ("$" = chỉ entry mới...): mỗi phần tử {giá trị, mô tả}. */
        final List<String[]> specials;

        Value(Arg arg, String rule) {
            this(arg, rule, List.of());
        }

        Value(Arg arg, String rule, List<String[]> specials) {
            this.arg = arg;
            this.rule = rule;
            this.specials = specials;
        }
    }

    static final class Seq extends Node {
        final List<Node> items;

        Seq(List<Node> items) {
            this.items = items;
        }
    }

    static final class OneOf extends Node {
        final List<Node> alts;

        OneOf(List<Node> alts) {
            this.alts = alts;
        }
    }

    static final class Opt extends Node {
        final Node inner;

        Opt(Node inner) {
            this.inner = inner;
        }
    }

    /** 1 lần trở lên. */
    static final class Many extends Node {
        final Node inner;
        final Opt more;

        Many(Node inner) {
            this.inner = inner;
            this.more = new Opt(this);
        }
    }

    /** Các tuỳ chọn thứ tự bất kỳ; {@code reusable[i]}: tuỳ chọn có cờ multiple, dùng lại được. */
    static final class Loose extends Node {
        final List<Node> alts;
        final boolean[] reusable;

        Loose(List<Node> alts, boolean[] reusable) {
            this.alts = alts;
            this.reusable = reusable;
        }
    }

    // ------------------------------------------------------------------ dịch cây COMMAND DOCS

    /** Tham số chuỗi -> loại theo tên (tên khác là "value"). */
    private static final Map<String, String> STRING_RULES = Map.of(
            "field", "field", "member", "member", "element", "element", "pivot", "element",
            "channel", "channel", "shardchannel", "channel",
            "parameter", "configParam", "section", "infoSection");

    /** {@code command}: tên đầy đủ ("XGROUP CREATE") - vài tham số chuỗi đổi nghĩa theo lệnh (xem {@link #streamArg}). */
    static Node compile(List<Arg> args, String command) {
        return sequence(args, command);
    }

    private static Node sequence(List<Arg> args, String cmd) {
        List<Node> parts = new ArrayList<>();
        List<Arg> run = new ArrayList<>();
        for (Arg a : args) {
            if (a.optional() && keywordLed(a)) {
                run.add(a);
            } else {
                flush(run, parts, cmd);
                parts.add(wrap(a, cmd));
            }
        }
        flush(run, parts, cmd);
        return parts.size() == 1 ? parts.get(0) : new Seq(List.copyOf(parts));
    }

    private static void flush(List<Arg> run, List<Node> parts, String cmd) {
        if (run.size() == 1) {
            parts.add(wrap(run.get(0), cmd));
        } else if (run.size() > 1) {
            List<Node> alts = new ArrayList<>();
            boolean[] reusable = new boolean[run.size()];
            for (int i = 0; i < run.size(); i++) {
                Arg a = run.get(i);
                alts.add(wrap(new Arg(a.name(), a.type(), a.token(), false, a.multiple(), a.multipleToken(),
                        a.displayText(), a.since(), a.children()), cmd));
                reusable[i] = a.multiple();
            }
            if (run.size() > 63) {
                java.util.Arrays.fill(reusable, true); // không đủ bit để đánh dấu đã dùng - cho dùng lại hết
            }
            parts.add(new Loose(List.copyOf(alts), reusable));
        }
        run.clear();
    }

    /** Bắt đầu bằng 1 từ khoá cố định (để biết tuỳ chọn nào gộp được vào nhóm thứ tự bất kỳ). */
    static boolean keywordLed(Arg a) {
        if (a.token() != null && !a.token().isEmpty()) {
            return true;
        }
        return switch (a.type()) {
            case ONEOF -> !a.children().isEmpty() && a.children().stream().allMatch(RedisArgMatcher::keywordLed);
            case BLOCK -> !a.children().isEmpty() && keywordLed(a.children().get(0));
            default -> false;
        };
    }

    /** Tham số kèm cờ optional / multiple / multiple_token. */
    private static Node wrap(Arg a, String cmd) {
        Node node;
        if (a.multiple() && hasToken(a) && !a.multipleToken() && a.type() != ArgType.PURE_TOKEN) {
            // TOKEN v [v ...]: từ khoá 1 lần, giá trị lặp
            node = new Seq(List.of(new Keyword(a.token()), new Many(bare(a, cmd))));
        } else {
            node = withToken(a, cmd);
            if (a.multiple()) {
                node = new Many(node); // multiple_token: lặp cả cụm "TOKEN v"
            }
        }
        return a.optional() ? new Opt(node) : node;
    }

    private static boolean hasToken(Arg a) {
        return a.token() != null && !a.token().isEmpty();
    }

    private static Node withToken(Arg a, String cmd) {
        Node node = bare(a, cmd);
        if (hasToken(a) && a.type() != ArgType.PURE_TOKEN) {
            return new Seq(List.of(new Keyword(a.token()), node));
        }
        return node;
    }

    /** Tham số không tính token đứng trước và các cờ. */
    private static Node bare(Arg a, String cmd) {
        return switch (a.type()) {
            case PURE_TOKEN -> hasToken(a) ? new Keyword(a.token()) : new Value(a, "value"); // MIGRATE ... "" ...
            case ONEOF -> new OneOf(a.children().stream().map(c -> wrap(c, cmd)).toList());
            case BLOCK -> sequence(a.children(), cmd);
            case KEY -> new Value(a, "key");
            case PATTERN -> new Value(a, "pattern");
            case INTEGER, UNIX_TIME -> new Value(a, "integer");
            case DOUBLE -> new Value(a, "number");
            case STRING -> {
                Value stream = streamArg(a, cmd);
                yield stream != null ? stream : new Value(a, "TYPE".equalsIgnoreCase(a.token()) && "type".equals(a.name())
                        ? "typeName" : STRING_RULES.getOrDefault(a.name(), "value"));
            }
        };
    }

    /**
     * Tham số chuỗi của lệnh stream có nghĩa theo lệnh: tên group/consumer đã có (lấy từ XINFO), id đang chờ ACK
     * (XPENDING), id entry, và các id đặc biệt hay quên ("$", ">", "-", "+"). Tên group ở XGROUP CREATE và consumer
     * ở XREADGROUP/XGROUP CREATECONSUMER là tên MỚI người dùng tự đặt - không gợi ý.
     */
    /** Cặp giá trị, khoá I18n của mô tả, giá trị, khoá... (dịch lúc hiển thị - \\lang đổi được giữa chừng). */
    private static List<String[]> specials(String... pairs) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.add(new String[]{pairs[i], pairs[i + 1]});
        }
        return out;
    }

    private static Value streamArg(Arg a, String cmd) {
        String name = a.name();
        return switch (cmd + " " + name) {
            case "XRANGE start", "XREVRANGE start", "XPENDING start" ->
                    new Value(a, "value", specials("-", "stream.fromFirst"));
            case "XRANGE end", "XREVRANGE end", "XPENDING end" ->
                    new Value(a, "value", specials("+", "stream.toLast"));
            case "XAUTOCLAIM start" -> new Value(a, "value", specials("0-0", "stream.pelStart"));
            case "XREAD id" -> new Value(a, "value", specials(
                    "$", "stream.onlyNew",
                    "0", "stream.fromFirst"));
            case "XREADGROUP id" -> new Value(a, "value", specials(
                    ">", "stream.undelivered",
                    "0", "stream.pendingOwn"));
            case "XGROUP CREATE id", "XGROUP SETID id" ->
                    new Value(a, "value", specials("0", "stream.fromFirstOld"));
            case "XACK id", "XCLAIM id" -> new Value(a, "pendingId");
            case "XDEL id" -> new Value(a, "entryId");
            case "XACK group", "XCLAIM group", "XAUTOCLAIM group", "XPENDING group", "XGROUP SETID group",
                 "XGROUP DESTROY group", "XGROUP CREATECONSUMER group", "XGROUP DELCONSUMER group",
                 "XINFO CONSUMERS group" -> new Value(a, "group");
            case "XREADGROUP group" -> new Value(a, "anyGroup"); // key nằm sau STREAMS, chưa gõ tới
            case "XCLAIM consumer", "XAUTOCLAIM consumer", "XPENDING consumer", "XGROUP DELCONSUMER consumer" ->
                    new Value(a, "consumer");
            default -> null;
        };
    }

    // ------------------------------------------------------------------ đi cây

    /** "Còn phải khớp gì": END = lệnh đã có thể kết thúc ở đây. */
    sealed interface Cont permits End, Then, LooseAt {
    }

    record End() implements Cont {
    }

    static final End END = new End();

    record Then(Node node, Cont rest) implements Cont {
    }

    /** Đang trong nhóm tuỳ chọn thứ tự bất kỳ; {@code used}: bit các tuỳ chọn đã dùng. */
    record LooseAt(Loose loose, long used, Cont rest) implements Cont {
    }

    /** 1 nút lá (Keyword/Value) có thể khớp token kế tiếp, và vị trí sau khi khớp. */
    record Step(Node leaf, Cont after) {
    }

    /** Tập vị trí sau 1 dãy token; {@code failedAt} = index token đầu tiên không khớp được, -1 nếu khớp hết. */
    record Result(Set<Cont> positions, List<Node> matchedLeaves, int failedAt) {

        /** Các nút lá được phép ngay sau dãy token đã khớp. */
        List<Node> expected() {
            List<Node> out = new ArrayList<>();
            for (Step s : steps(positions)) {
                out.add(s.leaf());
            }
            return out;
        }

        /** Dừng ở đây được không (đủ tham số bắt buộc). */
        boolean complete() {
            return accepts(positions);
        }
    }

    static Result match(Node root, List<String> tokens) {
        Set<Cont> positions = new LinkedHashSet<>();
        positions.add(new Then(root, END));
        List<Node> leaves = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            // Khớp được cả từ khoá lẫn giá trị tự do ("XADD s MAXLEN ..." - id nhận chuỗi bất kỳ) thì chỉ giữ cách
            // hiểu là từ khoá, như server: bộ đọc tham số của Redis thử từ khoá trước ở vị trí có tuỳ chọn.
            Set<Cont> asKeyword = new LinkedHashSet<>();
            Set<Cont> asValue = new LinkedHashSet<>();
            Node keyword = null;
            Node value = null;
            for (Step s : steps(positions)) {
                if (accepts(s.leaf(), token)) {
                    if (s.leaf() instanceof Keyword) {
                        asKeyword.add(s.after());
                        keyword = s.leaf();
                    } else {
                        asValue.add(s.after());
                        value = value == null ? s.leaf() : value;
                    }
                }
            }
            if (asKeyword.isEmpty() && asValue.isEmpty()) {
                return new Result(positions, leaves, i);
            }
            leaves.add(asKeyword.isEmpty() ? value : keyword);
            positions = asKeyword.isEmpty() ? asValue : asKeyword;
        }
        return new Result(positions, leaves, -1);
    }

    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");
    // điểm/timeout: số thực, inf, và dạng khoảng mở "(1.5" của ZRANGEBYSCORE
    private static final Pattern NUMBER = Pattern.compile("\\(?[+-]?(inf|\\d+(\\.\\d*)?([eE][+-]?\\d+)?|\\.\\d+)",
            Pattern.CASE_INSENSITIVE);

    static boolean accepts(Node leaf, String token) {
        if (leaf instanceof Keyword k) {
            return k.text.equalsIgnoreCase(token);
        }
        Value v = (Value) leaf;
        return switch (v.rule) {
            case "integer" -> INTEGER.matcher(token).matches();
            case "number" -> NUMBER.matcher(token).matches();
            default -> true;
        };
    }

    /** Đang gõ dở 1 token hợp lệ (vd "E" của EX, "-" của số âm) - không tính là sai. */
    static boolean isPrefixOfExpected(List<Node> expected, String partial) {
        String upper = partial.toUpperCase(Locale.ROOT);
        for (Node n : expected) {
            if (n instanceof Keyword k && k.text.toUpperCase(Locale.ROOT).startsWith(upper)) {
                return true;
            }
            if (n instanceof Value v && (v.rule.equals("integer") || v.rule.equals("number"))
                    && partial.matches("\\(?[+-]?\\.?")) {
                return true;
            }
        }
        return false;
    }

    static List<Step> steps(Set<Cont> positions) {
        List<Step> out = new ArrayList<>();
        Set<Cont> seen = new HashSet<>();
        for (Cont c : positions) {
            expand(c, out, seen);
        }
        return out;
    }

    private static boolean accepts(Set<Cont> positions) {
        Set<Cont> seen = new HashSet<>();
        for (Cont c : positions) {
            if (canEnd(c, seen)) {
                return true;
            }
        }
        return false;
    }

    /** Mở mọi nhánh không tiêu token (tuỳ chọn bỏ qua, chọn nhánh oneof, vào nhóm...) tới các nút lá. */
    private static void expand(Cont c, List<Step> out, Set<Cont> seen) {
        if (!seen.add(c)) {
            return; // vòng lặp qua nhánh rỗng (Many của nút rỗng được)
        }
        if (c instanceof Then t) {
            Node n = t.node();
            if (n instanceof Keyword || n instanceof Value) {
                out.add(new Step(n, t.rest()));
            } else if (n instanceof Seq s) {
                expand(chain(s.items, 0, t.rest()), out, seen);
            } else if (n instanceof OneOf o) {
                for (Node alt : o.alts) {
                    expand(new Then(alt, t.rest()), out, seen);
                }
            } else if (n instanceof Opt opt) {
                expand(new Then(opt.inner, t.rest()), out, seen);
                expand(t.rest(), out, seen);
            } else if (n instanceof Many m) {
                expand(new Then(m.inner, new Then(m.more, t.rest())), out, seen);
            } else if (n instanceof Loose l) {
                expand(new LooseAt(l, 0, t.rest()), out, seen);
            }
        } else if (c instanceof LooseAt la) {
            expand(la.rest(), out, seen);
            for (int i = 0; i < la.loose().alts.size(); i++) {
                if ((la.used() & (1L << i)) == 0) {
                    long used = la.loose().reusable[i] ? la.used() : la.used() | (1L << i);
                    expand(new Then(la.loose().alts.get(i), new LooseAt(la.loose(), used, la.rest())), out, seen);
                }
            }
        }
    }

    private static boolean canEnd(Cont c, Set<Cont> seen) {
        if (c instanceof End) {
            return true;
        }
        if (!seen.add(c)) {
            return false;
        }
        if (c instanceof Then t) {
            Node n = t.node();
            if (n instanceof Keyword || n instanceof Value) {
                return false;
            }
            if (n instanceof Seq s) {
                return canEnd(chain(s.items, 0, t.rest()), seen);
            }
            if (n instanceof OneOf o) {
                for (Node alt : o.alts) {
                    if (canEnd(new Then(alt, t.rest()), seen)) {
                        return true;
                    }
                }
                return false;
            }
            if (n instanceof Opt opt) {
                return canEnd(t.rest(), seen) || canEnd(new Then(opt.inner, t.rest()), seen);
            }
            if (n instanceof Many m) {
                return canEnd(new Then(m.inner, new Then(m.more, t.rest())), seen);
            }
            if (n instanceof Loose) {
                return canEnd(t.rest(), seen);
            }
        }
        if (c instanceof LooseAt la) {
            return canEnd(la.rest(), seen);
        }
        return false;
    }

    private static Cont chain(List<Node> items, int from, Cont rest) {
        Cont c = rest;
        for (int i = items.size() - 1; i >= from; i--) {
            c = new Then(items.get(i), c);
        }
        return c;
    }
}
