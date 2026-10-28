package com.sqlctx.redis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Key vừa dùng hoặc vừa thấy trong kết quả ở DB hiện tại (như iredis): đưa lên đầu danh sách gợi ý, trước kết quả SCAN
 * theo tiền tố - key hay dùng không bị chìm giữa hàng nghìn key cùng tiền tố, và vẫn gợi ý được khi SCAN dừng giữa chừng
 * trên keyspace lớn. Nhớ kèm kiểu nếu biết (lệnh theo kiểu như HGET, kết quả TYPE, SCAN ... TYPE), để gợi ý key cho
 * HGET không lẫn key string.
 */
final class RecentKeys {

    static final int MAX = 1000;

    /** key -> kiểu (null = chưa biết); thứ tự truy cập, mới nhất ở cuối. */
    private final LinkedHashMap<String, String> keys = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX;
        }
    };

    synchronized void touch(String key, String type) {
        String known = keys.get(key);
        keys.put(key, type != null ? type : known);
    }

    synchronized void forget(Collection<String> gone) {
        gone.forEach(keys::remove);
    }

    synchronized void clear() {
        keys.clear();
    }

    synchronized String typeOf(String key) {
        return keys.get(key);
    }

    /**
     * Gợi ý key: key gần đây khớp tiền tố trước (mới nhất trước), rồi tới {@code scanned}. Có {@code type} thì key gần
     * đây phải đúng kiểu đó, hoặc chưa rõ kiểu nhưng SCAN theo kiểu đã trả về nó.
     */
    synchronized List<String> merge(String prefix, String type, List<String> scanned) {
        Set<String> found = new HashSet<>(scanned);
        // đọc qua bản sao entry: get() trên map theo thứ tự truy cập sẽ đổi luôn thứ tự "gần đây"
        List<Map.Entry<String, String>> recent = new ArrayList<>(keys.entrySet());
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            String k = recent.get(i).getKey();
            String t = recent.get(i).getValue();
            if (k.startsWith(prefix) && (type == null || type.equals(t) || t == null && found.contains(k))) {
                out.add(k);
            }
        }
        out.addAll(scanned);
        return List.copyOf(out);
    }

    /**
     * Học từ 1 lệnh vừa chạy thành công: {@code words} lệnh đã tách, {@code roles} vai trò từng token (key nào là key),
     * {@code keyType} kiểu mà lệnh gắn với (HGET -> hash), {@code reply} kết quả.
     */
    void learn(List<String> words, List<RedisSyntax.Role> roles, String keyType, Object reply) {
        List<String> named = new ArrayList<>();
        for (int i = 0; i < words.size() && i < roles.size(); i++) {
            if (roles.get(i) == RedisSyntax.Role.KEY) {
                named.add(words.get(i));
            }
        }
        String cmd = words.isEmpty() ? "" : words.get(0).toUpperCase(Locale.ROOT);
        switch (cmd) {
            case "DEL", "UNLINK" -> forget(named);
            case "FLUSHDB", "FLUSHALL" -> clear();
            case "RENAME", "RENAMENX" -> {
                if (named.size() == 2 && !Long.valueOf(0).equals(reply)) {
                    String type = typeOf(named.get(0));
                    forget(List.of(named.get(0)));
                    touch(named.get(1), type);
                }
            }
            case "TYPE" -> {
                if (named.size() == 1) {
                    String t = RedisSession.text(reply);
                    if ("none".equals(t)) {
                        forget(named);
                    } else {
                        touch(named.get(0), t);
                    }
                }
            }
            case "KEYS" -> all(reply, null);
            case "RANDOMKEY" -> {
                if (reply != null) {
                    touch(RedisSession.text(reply), null);
                }
            }
            case "SCAN" -> {
                if (reply instanceof List<?> page && page.size() == 2) {
                    all(page.get(1), option(words, "TYPE"));
                }
            }
            default -> {
                // GET/HGETALL... key không tồn tại trả nil: không nhớ key gõ nhầm
                if (reply != null) {
                    named.forEach(k -> touch(k, keyType));
                }
            }
        }
    }

    private void all(Object reply, String type) {
        if (reply instanceof List<?> list) {
            for (Object o : list) {
                touch(RedisSession.text(o), type);
            }
        }
    }

    private static String option(List<String> words, String name) {
        for (int i = 1; i + 1 < words.size(); i++) {
            if (words.get(i).equalsIgnoreCase(name)) {
                return words.get(i + 1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }
}
