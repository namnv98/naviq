package com.sqlctx.completion.ranking;

import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.model.SuggestionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lọc + xếp hạng danh sách Suggestion theo prefix người dùng đang gõ (hỗ trợ dot-mode
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


    public static List<Suggestion> filter(List<Suggestion> all, String prefix, boolean dot) {
        String m = matchPart(prefix, dot);
        String mLower = m.toLowerCase();

        // Lấy schema từ prefix: "public.d" → "public"
        String schemaPrefix = dot && prefix.contains(".") ? prefix.substring(0, prefix.lastIndexOf('.')).toLowerCase() : null;

        record Scored(Suggestion suggest, int tier, int habitBucket, int quality, int order, int demoted,
                      int scopeDistance, int historyWeight, int length) {

        }

        List<Scored> scored = new ArrayList<>();
        // Khử trùng (type, key) - nhiều nhánh của service cùng thêm 1 nguồn dữ liệu cho CÙNG vị trí
        // (vd hàm từ cả func_name lẫn columnref, 2 token khác nhau cùng hiển thị "operator") -
        // bug thật: mọi vị trí biểu thức từng hiện count/sum/avg/now 2 lần trên menu.
        Set<String> seen = new HashSet<>();
        for (Suggestion s : all) {
            if (!seen.add(s.getType().label() + "\u0000" + s.getKey().toLowerCase())) {
                continue;
            }
            if (schemaPrefix != null && !s.getKey().toLowerCase().startsWith(schemaPrefix + ".")) {
                continue;
            }
            // LUÔN so khớp trên phần SAU dấu chấm cuối của key (nếu có) - KHÔNG chỉ khi
            // dot=true như display() bên dưới. Key của 1 suggestion (table/column) có thể tự
            // mang tiền tố "schema."/"alias." để hiển thị đầy đủ ngay cả khi người dùng CHƯA
            // gõ dấu chấm (vd bảng "users" không alias vẫn có key "users.id" ở addColumnSuggestions),
            // nhưng người dùng đang gõ "i" thì rõ ràng đang match "id" chứ không phải "users.id".
            // Trước đây match thẳng trên display(key, dot) - khi dot=false thì display() trả
            // NGUYÊN key, khiến match prefix so với "users.id"/"public.users" luôn thất bại (vì
            // không bắt đầu bằng "i"/"us"), rớt xuống fuzzy tier và THUA cả keyword ("int",
            // "user"...) vốn match prefix thật - bug thật, phát hiện qua test đi ĐÚNG đường
            // production (suggests(PrepareCompletionInput), có filter) thay vì overload thô.
            String word = matchWord(s);
            Rank r = rank(word, m, mLower);
            if (r == null) {
                continue; // không match được (kể cả subsequence) -> loại
            }
            int hw = CompletionHistory.weight(s.getKey());
            int habitBucket = hw >= STRONG_HABIT_THRESHOLD ? 0 : 1;
            // giá trị (field của date_trunc, giá trị enum) giữ đúng thứ tự khai báo - sort ổn định, không phá hoà theo độ dài
            int length = s.getType() == SuggestionType.VALUE ? 0 : word.length();
            scored.add(new Scored(s, r.tier(), habitBucket, r.quality(), s.getOrder(), s.isDemoted() ? 1 : 0,
                    s.getScopeDistance(), hw, length));
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
        // 4a. demoted - cùng loại thì đối tượng hệ thống (pg_catalog...) sau đối tượng của người dùng.
        // 4b. scopeDistance - cùng loại thì cột của scope gần caret nhất (subquery đang gõ) trước cột
        //    của scope cha (correlated) - cột ngoài vẫn hợp lệ nhưng ít khi là thứ đang cần.
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
                .thenComparingInt(Scored::demoted)
                .thenComparingInt(Scored::scopeDistance)
                .thenComparingInt(sc -> -sc.historyWeight())
                .thenComparingInt(Scored::length));

        List<Suggestion> out = new ArrayList<>(scored.size());
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
     * Subsequence match + score. Trả Integer.MAX_VALUE nếu input không phải subsequence của word, hoặc ký tự ĐẦU
     * của input không rơi vào đầu 1 "từ" ({@link #isBoundary}) - giống VS Code/IntelliJ: gõ "us" không ra
     * "prod<b>u</b>ct<b>s</b>", gõ "id" vẫn ra "user_<b>id</b>", "ui" ra "<b>u</b>ser_<b>i</b>d".
     * <p>
     * Với mỗi vị trí đầu từ khớp ký tự đầu, phần còn lại khớp "leftmost greedy" (đúng cho kiểm tra subsequence),
     * lấy điểm tốt nhất: "valid_id" gõ "id" phải tính từ "_id" chứ không phải chữ "i" giữa "valid".
     *
     * @param word      bản GỐC (giữ nguyên hoa/thường) - cần để phát hiện ranh giới camelCase
     * @param wordLower bản đã lowercase của word (so khớp không phân biệt hoa/thường)
     * @param mLower    input đã lowercase, không rỗng
     */
    private static int fuzzyScoreOrMax(String word, String wordLower, String mLower) {
        int best = Integer.MAX_VALUE;
        char first = mLower.charAt(0);
        for (int start = wordLower.indexOf(first); start != -1; start = wordLower.indexOf(first, start + 1)) {
            if (isBoundary(word, start)) {
                best = Math.min(best, greedyScoreFrom(word, wordLower, mLower, start));
            }
        }
        return best;
    }

    /** Gõ thiếu tối đa bấy nhiêu ký tự giữa 2 ký tự liền nhau ("usrs" -> "users"); xa hơn phải là đầu 1 từ. */
    private static final int MAX_TYPO_GAP = 2;

    /**
     * Điểm (càng nhỏ càng tốt) khi ký tự đầu của input khớp tại {@code start}; MAX_VALUE nếu phần còn lại không khớp.
     * Mỗi ký tự sau lấy vị trí gần nhất mà: liền ngay ký tự trước, hoặc ở đầu 1 từ, hoặc chỉ cách
     * {@link #MAX_TYPO_GAP} ký tự - không thì "id" khớp cả "isclose<b>d</b>" (nhảy 6 ký tự giữa 1 từ).
     */
    private static int greedyScoreFrom(String word, String wordLower, String mLower, int start) {
        // ký tự đầu: phạt khoảng bỏ qua trước nó, thưởng nếu khớp ngay đầu chuỗi (3) và vì luôn ở đầu từ (4)
        int score = start - (start == 0 ? 3 : 0) - 4;
        int lastMatch = start;
        for (int k = 1; k < mLower.length(); k++) {
            char c = mLower.charAt(k);
            int idx = -1;
            for (int j = wordLower.indexOf(c, lastMatch + 1); j != -1; j = wordLower.indexOf(c, j + 1)) {
                if (j - lastMatch - 1 <= MAX_TYPO_GAP || isBoundary(word, j)) {
                    idx = j;
                    break;
                }
            }
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


    /**
     * Phần "đang gõ dở" thật sự cần match - nếu đang ở dot-mode và prefix có dấu
     * chấm (vd "alias.col", "schema.table"), chỉ phần SAU dấu chấm CUỐI mới là thứ
     * người dùng đang gõ (phần trước dấu chấm là alias/schema đã cố định).
     * <p>
     * Gom về 1 chỗ vì trước đây bị copy-paste y hệt ở 3 nơi (filter(), highlight(),
     * buildGhost()) - gộp lại để tránh 3 nơi lệch nhau khi sửa sau này.
     */
    /**
     * Phần Ý NGHĨA để so khớp của 1 key - luôn là phần SAU dấu chấm CUỐI nếu key có mang
     * tiền tố schema/alias (vd "users.id" -> "id", "public.users" -> "users"), BẤT KỂ người
     * dùng đã gõ dấu chấm hay chưa. Tách riêng khỏi {@link #display} (chi phối MENU HIỂN THỊ
     * gì, vẫn cần phân biệt dot/không-dot vì browse-mode không-dot muốn thấy rõ schema) - đây
     * chỉ quyết định KHỚP gì, 2 việc khác nhau.
     */
    private static String matchWord(Suggestion s) {
        String key = s.getKey();
        // điều kiện JOIN ("o.user_id = u.id") là 1 cụm, gõ "o" là đang gõ từ đầu cụm chứ không phải tên cột cuối
        if (s.getType() == SuggestionType.JOIN) {
            return key;
        }
        // thay cho "*" đã gõ (select *|); ở group by thì chưa gõ gì (prefix rỗng khớp mọi thứ)
        if (s.getType() == SuggestionType.COLUMNS) {
            return "*";
        }
        // giá trị chuỗi ('active'): khớp phần bên trong dấu nháy, kể cả khi giá trị có dấu chấm
        if (s.getType() == SuggestionType.VALUE) {
            return key.length() >= 2 && key.startsWith("'") && key.endsWith("'") ? key.substring(1, key.length() - 1) : key;
        }
        int i = key.lastIndexOf('.');
        String word = i == -1 ? key : key.substring(i + 1);
        // tên trong ngoặc kép ("OrderItems"): người dùng gõ "Or", không gõ dấu "
        return word.length() >= 2 && word.startsWith("\"") && word.endsWith("\"") ? word.substring(1, word.length() - 1) : word;
    }

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