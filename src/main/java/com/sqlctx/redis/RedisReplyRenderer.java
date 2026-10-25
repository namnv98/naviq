package com.sqlctx.redis;

import com.sqlctx.util.I18n;

import com.sqlctx.redis.RespClient.Bulk;
import com.sqlctx.redis.RespClient.RespError;
import com.sqlctx.redis.RespClient.Simple;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Đổi reply thành thứ in ra màn hình. Mặc định giống redis-cli ({@code (integer) 1}, {@code "chuỗi"},
 * {@code 1) ...}); reply là các cặp (HGETALL, CONFIG GET, ZRANGE ... WITHSCORES, XRANGE) thì thành bảng để
 * dùng chung khung bảng/GridView với phía SQL.
 */
public final class RedisReplyRenderer {

    private RedisReplyRenderer() {
    }

    public sealed interface Output permits Lines, Table, Many {
    }

    /** Nhiều phần in nối tiếp (XAUTOCLAIM: cursor kế tiếp, bảng entry, id đã bị xoá). */
    public record Many(List<Output> parts) implements Output {
    }

    public record Lines(List<String> lines, boolean error) implements Output {
    }

    public record Table(List<String> headers, List<List<String>> rows) implements Output {
    }

    private static final Set<String> FIELD_VALUE_PAIRS = Set.of("HGETALL", "CONFIG GET");
    private static final Set<String> MEMBER_SCORE = Set.of("ZRANGE", "ZRANGEBYSCORE", "ZREVRANGE", "ZREVRANGEBYSCORE",
            "ZPOPMIN", "ZPOPMAX", "ZRANDMEMBER");

    public static Output render(List<String> words, Object reply) {
        String cmd = words.isEmpty() ? "" : words.get(0).toUpperCase(Locale.ROOT);
        String cmdWithSub = words.size() > 1 ? cmd + " " + words.get(1).toUpperCase(Locale.ROOT) : cmd;
        boolean withScores = words.stream().anyMatch(w -> w.equalsIgnoreCase("WITHSCORES"))
                || cmd.equals("ZPOPMIN") || cmd.equals("ZPOPMAX");
        boolean withValues = words.stream().anyMatch(w -> w.equalsIgnoreCase("WITHVALUES"));

        if (reply instanceof List<?> list && !list.isEmpty()) {
            if (isFlatPairs(list)) {
                if (FIELD_VALUE_PAIRS.contains(cmd) || FIELD_VALUE_PAIRS.contains(cmdWithSub)
                        || (cmd.equals("HRANDFIELD") && withValues)) {
                    return pairs(list, cmd.equals("CONFIG") ? "parameter" : "field", "value");
                }
                if (MEMBER_SCORE.contains(cmd) && withScores) {
                    return pairs(list, "member", "score");
                }
            }
            Output stream = stream(cmd, cmdWithSub, words, list);
            if (stream != null) {
                return stream;
            }
        }
        if (reply instanceof RespError e) {
            return new Lines(List.of("(error) " + e.message()), true);
        }
        // INFO, CLIENT LIST, MEMORY DOCTOR...: 1 bulk nhiều dòng là văn bản cho người đọc - in thẳng, không bọc nháy
        if (reply instanceof Bulk b && isText(b.bytes()) && b.utf8().indexOf('\n') >= 0) {
            return new Lines(b.utf8().lines().toList(), false);
        }
        List<String> lines = new ArrayList<>();
        format(reply, "", lines);
        return new Lines(lines, false);
    }

    /** Dạng redis-cli, có thụt lề cho mảng lồng nhau (EXEC, XREAD, SCAN...). */
    static void format(Object reply, String indent, List<String> out) {
        if (!(reply instanceof List<?> list)) {
            out.add(indent + scalar(reply));
            return;
        }
        if (list.isEmpty()) {
            out.add(indent + "(empty array)");
            return;
        }
        int width = String.valueOf(list.size()).length();
        String pad = " ".repeat(width + 2);
        for (int i = 0; i < list.size(); i++) {
            String num = String.format("%" + width + "d) ", i + 1);
            List<String> sub = new ArrayList<>();
            format(list.get(i), "", sub);
            for (int j = 0; j < sub.size(); j++) {
                out.add((j == 0 ? (i == 0 ? indent : " ".repeat(indent.length())) + num
                        : " ".repeat(indent.length()) + pad) + sub.get(j));
            }
        }
    }

    static String scalar(Object reply) {
        if (reply == null) {
            return "(nil)";
        }
        if (reply instanceof Long n) {
            return "(integer) " + n;
        }
        if (reply instanceof Simple s) {
            return s.text();
        }
        if (reply instanceof RespError e) {
            return "(error) " + e.message();
        }
        if (reply instanceof Bulk b) {
            return quote(b.bytes());
        }
        return String.valueOf(reply);
    }

    /** Giá trị trong ô bảng: chữ đọc được để nguyên, chỉ thoát ký tự điều khiển / byte không phải UTF-8. */
    static String cell(Object value) {
        if (value instanceof Bulk b) {
            return escaped(b.bytes(), false);
        }
        if (value instanceof Long n) {
            return n.toString();
        }
        if (value == null) {
            return "<nil>";
        }
        return scalar(value);
    }

    /**
     * Bọc nháy kép như redis-cli. Khác redis-cli ở 1 chỗ: chuỗi UTF-8 hợp lệ giữ nguyên chữ (tiếng Việt, emoji)
     * thay vì in từng byte \xNN; chỉ ký tự điều khiển và byte rác mới bị thoát.
     */
    static String quote(byte[] bytes) {
        return '"' + escaped(bytes, true) + '"';
    }

    /** {@code inQuotes}: thoát cả \ và " (chuỗi nằm trong nháy kép); trong ô bảng thì không cần. */
    private static String escaped(byte[] bytes, boolean inQuotes) {
        StringBuilder sb = new StringBuilder();
        String text = decodeStrict(bytes);
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                escape(sb, text.charAt(i), inQuotes);
            }
        } else {
            for (byte value : bytes) {
                int b = value & 0xff;
                if (b >= 0x80) {
                    sb.append(String.format("\\x%02x", b));
                } else {
                    escape(sb, (char) b, inQuotes);
                }
            }
        }
        return sb.toString();
    }

    private static void escape(StringBuilder sb, char c, boolean inQuotes) {
        if (!inQuotes && (c == '\\' || c == '"')) {
            sb.append(c);
            return;
        }
        switch (c) {
            case '\\' -> sb.append("\\\\");
            case '"' -> sb.append("\\\"");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            case '\b' -> sb.append("\\b");
            case 7 -> sb.append("\\a");
            default -> {
                if (c < 0x20 || c == 0x7f) {
                    sb.append(String.format("\\x%02x", (int) c));
                } else {
                    sb.append(c);
                }
            }
        }
    }

    private static String decodeStrict(byte[] bytes) {
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static boolean isText(byte[] bytes) {
        return decodeStrict(bytes) != null;
    }

    private static boolean isFlatPairs(List<?> list) {
        if (list.size() % 2 != 0) {
            return false;
        }
        for (Object o : list) {
            if (o instanceof List<?>) {
                return false;
            }
        }
        return true;
    }

    private static Table pairs(List<?> list, String left, String right) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i + 1 < list.size(); i += 2) {
            rows.add(List.of(cell(list.get(i)), cell(list.get(i + 1))));
        }
        return new Table(List.of(left, right), rows);
    }

    // ------------------------------------------------------------------ stream

    /** Reply của lệnh stream thành bảng; null nếu không nhận ra dạng (để in kiểu redis-cli như thường). */
    private static Output stream(String cmd, String cmdWithSub, List<String> words, List<?> list) {
        switch (cmdWithSub) {
            case "XINFO GROUPS", "XINFO CONSUMERS" -> {
                return isListOfMaps(list) ? mapsTable(list) : null;
            }
            case "XINFO STREAM" -> {
                boolean full = words.stream().anyMatch(w -> w.equalsIgnoreCase("FULL"));
                return !full && isFlatMap(list) ? propertiesTable(list) : null;
            }
            default -> {
            }
        }
        return switch (cmd) {
            case "XRANGE", "XREVRANGE", "XCLAIM" -> isEntries(list) ? entriesTable(list) : null;
            case "XREAD", "XREADGROUP" -> readTable(list);
            case "XPENDING" -> pending(list);
            case "XAUTOCLAIM" -> autoclaim(list);
            default -> null;
        };
    }

    /** 1 entry: [id, [field, value, ...]]; field là nil nếu entry đã bị XDEL mà vẫn còn trong PEL. */
    private static boolean isEntry(Object o) {
        return o instanceof List<?> e && e.size() == 2 && !(e.get(0) instanceof List<?>)
                && (e.get(1) == null || e.get(1) instanceof List<?>);
    }

    private static boolean isEntries(List<?> list) {
        return list.stream().allMatch(RedisReplyRenderer::isEntry);
    }

    /** "order_id=1  status=paid" - field của 1 entry trên 1 dòng. */
    static String entryFields(Object fields) {
        if (!(fields instanceof List<?> f)) {
            return I18n.t("redis.deleted");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < f.size(); i += 2) {
            if (!sb.isEmpty()) {
                sb.append("  ");
            }
            sb.append(cell(f.get(i))).append('=').append(cell(f.get(i + 1)));
        }
        return sb.toString();
    }

    private static Table entriesTable(List<?> list) {
        List<List<String>> rows = new ArrayList<>();
        for (Object o : list) {
            List<?> entry = (List<?>) o;
            rows.add(List.of(cell(entry.get(0)), entryFields(entry.get(1))));
        }
        return new Table(List.of("id", "fields"), rows);
    }

    /** XREAD/XREADGROUP: [[stream, [entry...]], ...] (RESP2) hoặc stream, [entry...], ... (map RESP3 đã trải phẳng). */
    private static Output readTable(List<?> list) {
        List<List<String>> rows = new ArrayList<>();
        boolean nested = list.stream()
                .allMatch(o -> o instanceof List<?> p && p.size() == 2 && p.get(1) instanceof List<?>);
        int step = nested ? 1 : 2;
        if (!nested && list.size() % 2 != 0) {
            return null;
        }
        for (int i = 0; i < list.size(); i += step) {
            Object stream = nested ? ((List<?>) list.get(i)).get(0) : list.get(i);
            Object entries = nested ? ((List<?>) list.get(i)).get(1) : list.get(i + 1);
            if (stream instanceof List<?> || !(entries instanceof List<?> es) || !isEntries(es)) {
                return null;
            }
            for (Object o : es) {
                List<?> entry = (List<?>) o;
                rows.add(List.of(cell(stream), cell(entry.get(0)), entryFields(entry.get(1))));
            }
        }
        return new Table(List.of("stream", "id", "fields"), rows);
    }

    /** Mọi entry trong reply của XREAD/XREADGROUP (cả 2 dạng RESP2/RESP3); nil (hết BLOCK) = rỗng. */
    static List<List<?>> readEntries(Object reply) {
        List<List<?>> out = new ArrayList<>();
        if (!(reply instanceof List<?> list)) {
            return out;
        }
        boolean nested = list.stream()
                .allMatch(o -> o instanceof List<?> p && p.size() == 2 && p.get(1) instanceof List<?>);
        for (int i = nested ? 0 : 1; i < list.size(); i += nested ? 1 : 2) {
            Object entries = nested ? ((List<?>) list.get(i)).get(1) : list.get(i);
            if (entries instanceof List<?> es) {
                for (Object e : es) {
                    if (isEntry(e)) {
                        out.add((List<?>) e);
                    }
                }
            }
        }
        return out;
    }

    /**
     * XPENDING tóm tắt: [số entry chờ, id nhỏ nhất, id lớn nhất, [[consumer, số entry], ...]];
     * dạng chi tiết (có start end count): [[id, consumer, idle ms, số lần giao], ...].
     */
    private static Output pending(List<?> list) {
        if (list.size() == 4 && list.get(0) instanceof Long count) {
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("pending", count.toString()));
            rows.add(List.of(I18n.t("stream.minId"), cell(list.get(1))));
            rows.add(List.of(I18n.t("stream.maxId"), cell(list.get(2))));
            if (list.get(3) instanceof List<?> consumers) {
                for (Object c : consumers) {
                    if (c instanceof List<?> pair && pair.size() == 2) {
                        rows.add(List.of("consumer " + cell(pair.get(0)), cell(pair.get(1))));
                    }
                }
            }
            return new Table(List.of(I18n.t("common.property"), I18n.t("common.value")), rows);
        }
        List<List<String>> rows = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof List<?> e) || e.size() != 4) {
                return null;
            }
            rows.add(List.of(cell(e.get(0)), cell(e.get(1)), cell(e.get(2)), cell(e.get(3))));
        }
        return new Table(List.of("id", "consumer", "idle (ms)", I18n.t("stream.deliveries")), rows);
    }

    /** XAUTOCLAIM: [cursor kế tiếp, [entry...] (hoặc [id...] với JUSTID), [id đã bị xoá...] (từ 7.0)]. */
    private static Output autoclaim(List<?> list) {
        if (list.size() < 2 || !(list.get(1) instanceof List<?> claimed)) {
            return null;
        }
        List<Output> parts = new ArrayList<>();
        parts.add(new Lines(List.of(I18n.t("stream.nextCursor", cell(list.get(0)))
                + ("0-0".equals(cell(list.get(0))) ? I18n.t("stream.pelDone") : "")), false));
        if (claimed.isEmpty()) {
            parts.add(new Lines(List.of(I18n.t("stream.noneClaimed")), false));
        } else if (isEntries(claimed)) {
            parts.add(entriesTable(claimed));
        } else {
            parts.add(new Lines(claimed.stream().map(RedisReplyRenderer::cell).toList(), false));
        }
        if (list.size() > 2 && list.get(2) instanceof List<?> deleted && !deleted.isEmpty()) {
            parts.add(new Lines(List.of(I18n.t("stream.deletedFromPel",
                    String.join(" ", deleted.stream().map(RedisReplyRenderer::cell).toList()))), false));
        }
        return new Many(parts);
    }

    /** Map phẳng: số phần tử chẵn, khoá ở vị trí chẵn là chuỗi. */
    private static boolean isFlatMap(List<?> list) {
        if (list.isEmpty() || list.size() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < list.size(); i += 2) {
            if (!(list.get(i) instanceof Bulk || list.get(i) instanceof Simple)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isListOfMaps(List<?> list) {
        return list.stream().allMatch(o -> o instanceof List<?> m && isFlatMap(m));
    }

    /** XINFO GROUPS/CONSUMERS: mỗi phần tử 1 dòng, cột theo khoá (gộp khoá của mọi phần tử, giữ thứ tự gặp). */
    private static Table mapsTable(List<?> list) {
        List<String> headers = new ArrayList<>();
        for (Object o : list) {
            List<?> m = (List<?>) o;
            for (int i = 0; i < m.size(); i += 2) {
                String k = cell(m.get(i));
                if (!headers.contains(k)) {
                    headers.add(k);
                }
            }
        }
        List<List<String>> rows = new ArrayList<>();
        for (Object o : list) {
            List<?> m = (List<?>) o;
            List<String> row = new ArrayList<>(java.util.Collections.nCopies(headers.size(), ""));
            for (int i = 0; i + 1 < m.size(); i += 2) {
                row.set(headers.indexOf(cell(m.get(i))), cell(m.get(i + 1)));
            }
            rows.add(row);
        }
        return new Table(headers, rows);
    }

    /** XINFO STREAM: thuộc tính | giá trị, entry đầu/cuối gọn trên 1 dòng. */
    private static Table propertiesTable(List<?> list) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i + 1 < list.size(); i += 2) {
            Object v = list.get(i + 1);
            String value = isEntry(v) ? cell(((List<?>) v).get(0)) + "  " + entryFields(((List<?>) v).get(1))
                    : v instanceof List<?> l ? String.join(" ", l.stream().map(RedisReplyRenderer::cell).toList())
                    : cell(v);
            rows.add(List.of(cell(list.get(i)), value));
        }
        return new Table(List.of(I18n.t("common.property"), I18n.t("common.value")), rows);
    }
}
