package com.naviq.completion.suggests.oracle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Frecency" (frequency + recency) - lưu lại NGƯỜI DÙNG ĐÃ TỪNG CHỌN gợi ý nào, để lần
 * sau gõ trùng ngữ cảnh, gợi ý đó được ưu tiên hiển thị cao hơn - mirror cách DataGrip mô
 * tả (ML completion "orders elements... data not exposed anywhere, collected locally").
 * Ở đây đơn giản hơn nhiều - không ML, chỉ đếm số lần chọn + thời điểm chọn gần nhất, suy
 * giảm dần theo thời gian (thuật toán "frecency" kinh điển, dùng trong Firefox address
 * bar/zoxide/autojump). Lưu trong 1 file nhỏ ở home directory, KHÔNG gửi đi đâu.
 */
public final class CompletionHistory {

    private CompletionHistory() {
    }

    /**
     * Nửa chu kỳ suy giảm - sau đúng 14 ngày kể từ lần chọn gần nhất, "độ quen thuộc" của
     * 1 gợi ý giảm còn 1 nửa; sau 28 ngày còn 1/4; v.v. Tự phai dần theo thời gian, không
     * cần dọn dẹp thủ công định kỳ.
     */
    private static final double HALF_LIFE_DAYS = 14.0;

    private static final Path FILE = Path.of(
            System.getProperty("user.home", "."), ".naviq", "completion_history.properties");

    private record Entry(int count, long lastUsedMillis) {

    }

    private static final ConcurrentHashMap<String, Entry> stats = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    /**
     * Gọi khi người dùng THẬT SỰ chọn 1 gợi ý (Enter/click trong menu) - KHÔNG gọi khi chỉ
     * đang duyệt qua (up/down) hay khi menu tự hiện lúc gõ, vì đó chưa phải "lựa chọn".
     */
    public static synchronized void record(String key) {
        ensureLoaded();
        long now = System.currentTimeMillis();
        stats.compute(key, (k, e) -> e == null ? new Entry(1, now) : new Entry(e.count() + 1, now));
        save();
    }

    /**
     * Điểm "quen thuộc" của 1 key - dùng để CỘNG THÊM vào công thức xếp hạng chung ở
     * SuggestFilter (trừ đi khỏi fineScore, vì fineScore nhỏ hơn = thắng). 0 nếu chưa từng
     * chọn qua bao giờ.
     */
    public static int weight(String key) {
        ensureLoaded();
        Entry e = stats.get(key);
        if (e == null) {
            return 0;
        }
        double ageDays = (System.currentTimeMillis() - e.lastUsedMillis()) / 86_400_000.0;
        double decay = Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
        // count không giới hạn trên, nhưng chặn weight tối đa để 1 key được chọn hàng trăm
        // lần trước đây không override HẲN mọi thứ khác mãi mãi - vẫn chỉ là 1 tín hiệu
        // "gợi ý", không phải luật tuyệt đối.
        double raw = e.count() * decay;
        return (int) Math.round(Math.min(raw, 10) * 3); // 0..30 điểm
    }

    // ───────────────────────────────────────────────────
    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (CompletionHistory.class) {
            if (loaded) {
                return;
            }
            loadQuietly();
            loaded = true;
        }
    }

    private static void loadQuietly() {
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (IOException e) {
            return; // không có lịch sử cũng không sao - completion vẫn hoạt động bình thường
        }
        for (String key : p.stringPropertyNames()) {
            String[] parts = p.getProperty(key).split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            try {
                int count = Integer.parseInt(parts[0]);
                long lastUsed = Long.parseLong(parts[1]);
                stats.put(key, new Entry(count, lastUsed));
            } catch (NumberFormatException ignored) {
                // dòng hỏng - bỏ qua, không làm hỏng cả file
            }
        }
    }

    private static void save() {
        Properties p = new Properties();
        stats.forEach((k, e) -> p.setProperty(k, e.count() + ":" + e.lastUsedMillis()));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "naviq completion history - local only, not sent anywhere");
            }
        } catch (IOException ignored) {
            // ghi thất bại (disk full, permission...) - không phải lỗi nghiêm trọng, completion
            // vẫn hoạt động bình thường ở phiên hiện tại, chỉ là không nhớ được cho lần sau.
        }
    }
}