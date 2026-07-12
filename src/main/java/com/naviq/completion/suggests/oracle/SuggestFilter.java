package com.naviq.completion.suggests.oracle;

import com.naviq.model.Suggest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lọc + xếp hạng danh sách Suggest theo prefix người dùng đang gõ (hỗ trợ dot-mode
 * "alias.col" và schema-qualified "schema.table"), cộng fuzzy match/score khi không
 * match kiểu prefix/equals trực tiếp. Tách riêng khỏi MenuCompleter vì đây là logic
 * THUẦN (không đụng LineReader/Terminal/rendering gì cả) - dễ test độc lập.
 */
public final class SuggestFilter {

    private SuggestFilter() {
    }

    /**
     * Ngưỡng "đã thành thói quen rõ ràng" - weight từ CompletionHistory đạt mức này trở
     * lên mới đủ để ĐƯỢC PHÉP thắng cả "order" (ưu tiên loại) - xem giải thích ở filter().
     * Với công thức weight hiện tại (min(count,10)*3, decay theo 14 ngày), ngưỡng 15
     * tương ứng khoảng 5 lần chọn gần đây (5×3=15, chưa suy giảm đáng kể).
     */
    private static final int STRONG_HABIT_THRESHOLD = 15;


    public static List<Suggest> filter(List<Suggest> all, String prefix, boolean dot) {
        String m = matchPart(prefix, dot);
        String mLower = m.toLowerCase();

        // Lấy schema từ prefix: "public.d" → "public"
        String schemaPrefix = dot && prefix.contains(".") ? prefix.substring(0, prefix.lastIndexOf('.')).toLowerCase() : null;

        record Scored(Suggest suggest, int tier, int habitBucket, int quality, int order,
                      int historyWeight, int length) {

        }

        List<Scored> scored = new ArrayList<>();
        for (Suggest s : all) {
            if (schemaPrefix != null && !s.getKey().toLowerCase().startsWith(schemaPrefix + ".")) {
                continue;
            }
            String word = display(s.getKey(), dot);
            Rank r = rank(word, m, mLower);
            if (r == null) {
                continue; // không match được (kể cả subsequence) -> loại
            }
            int hw = CompletionHistory.weight(s.getKey());
            int habitBucket = hw >= STRONG_HABIT_THRESHOLD ? 0 : 1;
            scored.add(new Scored(s, r.tier(), habitBucket, r.quality(), s.getOrder(), hw,
                    word.length()));
        }

        // Chuỗi tiêu chí TUẦN TỰ (mirror cách fzf làm qua --tiebreak, KHÔNG cộng dồn 1
        // công thức như bản trước) - mỗi bậc chỉ được xét khi bậc TRƯỚC bằng tuyệt đối:
        //
        // 1. tier        - exact=0 < prefix=1 < fuzzy=2 (KHÔNG thứ gì đổi được cái này)
        // 2. habitBucket - "đã thành thói quen rõ ràng" (weight >= ngưỡng) được phép
        //    thắng CẢ order lẫn quality - đúng ý ban đầu (chọn INSERT 10 lần thì nó phải
        //    lên trước invoices dù invoices là table). Chỉ áp dụng khi lịch sử đủ MẠNH.
        // 3. quality     - độ khít fuzzy THẬT (gap penalty/boundary bonus) - CHỈ có ý
        //    nghĩa phân biệt trong tier=2 (fuzzy); tier 0/1 luôn quality=0 vì exact/prefix
        //    "khít tuyệt đối", không có khái niệm gap. Tách RIÊNG khỏi "length" (khác bản
        //    trước gộp chung) vì đây là 2 tín hiệu khác nhau: quality = "khớp có sát
        //    không", length = "tên có ngắn gọn không" - gộp chung khiến tên dài nhưng
        //    match rất khít bị đánh giá sai ngang với tên dài match lỏng lẻo.
        // 4. order        - ưu tiên loại (alias < column < table < keyword < ...).
        // 5. -historyWeight - trong CÙNG loại, ai được dùng gần đây/nhiều hơn (dù chưa đạt
        //    ngưỡng "thói quen rõ ràng") vẫn nhỉnh hơn 1 chút.
        // 6. length       - CUỐI CÙNG mới so độ dài TOÀN BỘ tên, và CHỈ khi mọi thứ trên
        //    bằng nhau hết - trước đây length bị cộng thẳng vào cùng công thức với
        //    order/history nên tên dài (vd "contract_template" so với "users") LẤN ÁT cả
        //    2 tín hiệu kia dù chúng quan trọng hơn nhiều. Giờ length chỉ là tiêu chí phá
        //    hoà cuối cùng, không còn cạnh tranh trực tiếp với order/history/quality nữa.
        scored.sort(Comparator.comparingInt(Scored::tier)
                .thenComparingInt(Scored::habitBucket)
                .thenComparingInt(Scored::quality)
                .thenComparingInt(Scored::order)
                .thenComparingInt(sc -> -sc.historyWeight())
                .thenComparingInt(Scored::length));

        List<Suggest> out = new ArrayList<>(scored.size());
        for (Scored sc : scored) {
            out.add(sc.suggest());
        }
        return out;
    }

    private record Rank(int tier, int quality) {

    }

    /**
     * Xếp hạng 1 candidate thành (tier, quality) - xem giải thích ở filter() vì sao tách
     * riêng khỏi "length". Trả về null nếu KHÔNG match (kể cả subsequence).
     */
    private static Rank rank(String word, String m, String mLower) {
        if (word.equalsIgnoreCase(m)) {
            return new Rank(0, 0);
        }
        String wordLower = word.toLowerCase();
        if (mLower.isEmpty() || wordLower.startsWith(mLower)) {
            // prefix match (hoặc chưa gõ gì) - LUÔN "khít tuyệt đối" (không gap, vì theo
            // định nghĩa prefix match liền mạch từ đầu) -> quality = 0 cho mọi candidate,
            // không phân biệt gì thêm ở bậc này (length sẽ phá hoà ở CUỐI nếu cần).
            return new Rank(1, 0);
        }
        int score = fuzzyScoreOrMax(word, wordLower, mLower);
        return score == Integer.MAX_VALUE ? null : new Rank(2, score);
    }

    /**
     * Subsequence match + score trong CÙNG 1 lần quét (gộp fuzzyMatch + fuzzyScore cũ -
     * trước đây 2 hàm riêng biệt phải quét lại y hệt thuật toán 2 lần cho mỗi candidate:
     * 1 lần chỉ để biết CÓ match không, 1 lần để tính điểm). Trả Integer.MAX_VALUE nếu
     * không match được dạng subsequence (input không phải subsequence của word).
     * <p>
     * Thuật toán "leftmost greedy": với mỗi ký tự của input, tìm vị trí xuất hiện SỚM
     * NHẤT trong word kể từ sau vị trí match trước đó - đây là thuật toán ĐÚNG cho việc
     * check subsequence (không cần thử mọi tổ hợp vị trí).
     *
     * @param word      bản GỐC (giữ nguyên hoa/thường) - cần để phát hiện ranh giới
     *                  camelCase (chữ thường -> chữ hoa), việc lowercase làm mất thông
     *                  tin này nên phải giữ lại bản gốc riêng.
     * @param wordLower bản đã lowercase của word (so khớp không phân biệt hoa/thường)
     * @param mLower    input đã lowercase
     */
    private static int fuzzyScoreOrMax(String word, String wordLower, String mLower) {
        int score = 0;
        int lastMatch = -1;
        for (int k = 0; k < mLower.length(); k++) {
            char c = mLower.charAt(k);
            int idx = wordLower.indexOf(c, lastMatch + 1);
            if (idx == -1) {
                return Integer.MAX_VALUE;
            }
            score += (idx - lastMatch - 1); // phạt gap
            if (idx == lastMatch + 1) {
                score -= 3; // thưởng liên tiếp
            }
            if (isBoundary(word, idx)) {
                score -= 4; // thưởng word boundary
            }
            lastMatch = idx;
        }
        return score;
    }

    /**
     * Ranh giới "từ" trong 1 identifier - đầu chuỗi, ngay sau '_' (snake_case), hoặc
     * ngay sau chữ thường -> chữ hoa (camelCase, vd "userId" ranh giới trước "I").
     * Trước đây CHỈ nhận '_' làm ranh giới, bỏ sót camelCase - vd gõ "id" để tìm
     * "userId" không được ưu tiên dù "I" rõ ràng là 1 ranh giới từ hợp lý. Dùng bản
     * GỐC (chưa lowercase) vì camelCase cần phân biệt hoa/thường thật sự.
     */
    private static boolean isBoundary(String word, int idx) {
        if (idx == 0) {
            return true;
        }
        char prev = word.charAt(idx - 1);
        if (prev == '_') {
            return true;
        }
        char cur = word.charAt(idx);
        return Character.isLowerCase(prev) && Character.isUpperCase(cur);
    }

    // ───────────────────────────────────────────────────

    /**
     * Phần "đang gõ dở" thật sự cần match - nếu đang ở dot-mode và prefix có dấu
     * chấm (vd "alias.col", "schema.table"), chỉ phần SAU dấu chấm CUỐI mới là thứ
     * người dùng đang gõ (phần trước dấu chấm là alias/schema đã cố định).
     * <p>
     * Gom về 1 chỗ vì trước đây bị copy-paste y hệt ở 3 nơi (filter(), highlight(),
     * buildGhost()) - gộp lại để tránh 3 nơi lệch nhau khi sửa sau này.
     */
    public static String matchPart(String prefixOrKey, boolean dot) {
        return dot && prefixOrKey.contains(".")
                ? prefixOrKey.substring(prefixOrKey.lastIndexOf('.') + 1)
                : prefixOrKey;
    }

    /**
     * Phần hiển thị của 1 key trong menu - nếu dot-mode, chỉ hiện phần SAU dấu chấm
     * CUỐI (nhất quán với matchPart() - trước đây display() dùng indexOf (dấu chấm
     * ĐẦU) trong khi filter() dùng lastIndexOf (dấu chấm CUỐI) để tính schemaPrefix/m,
     * lệch nhau nên với key có >= 2 dấu chấm (vd "schema.table.column") sẽ hiện dư
     * "table.column" thay vì chỉ "column" - đã thống nhất về lastIndexOf.
     */
    public static String display(String key, boolean dot) {
        return (dot && key.contains(".")) ? key.substring(key.lastIndexOf('.') + 1) : key;
    }
}