package com.naviq.completion.syntactic.engine.feature;

import com.naviq.completion.syntactic.engine.model.InputToken;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.List;

/**
 * FEATURE: cho engine "chịu lỗi" thay vì chết hẳn khi gặp token sai.
 * KHÔNG thuộc lõi thuật toán gốc — bỏ feature này đi, engine vẫn đúng như cũ,
 * chỉ là gặp input lỗi thì dừng gợi ý ngay tại chỗ lỗi (hành vi cũ).
 * <p>
 * Ý tưởng giống hệt {@code DefaultErrorStrategy} của ANTLR parser thật:
 * khi 1 rule KHÔNG THỂ hoàn thành (đi vào chết hết mọi nhánh), đừng để cái
 * chết lan ra ngoài — coi như rule đó "bỏ cuộc", NHẢY thẳng tới vị trí token
 * gần nhất mà CALLER (rule cha) chấp nhận được ngay sau lời gọi này, rồi tiếp
 * tục như thể rule con vừa kết thúc ở đó.
 * <p>
 * "Vị trí caller chấp nhận được" = {@code atn.nextTokens(followState)} —
 * tập token hợp lệ đứng ngay sau lời gọi rule, tính không phụ thuộc ngữ cảnh
 * ngoài (context-free FOLLOW). Đây là xấp xỉ, không tuyệt đối chính xác như
 * ANTLR thật (ANTLR còn cộng thêm follow-set của TOÀN BỘ stack rule cha ông),
 * nhưng đủ dùng cho mục đích completion: chỉ cần "đi tiếp được", không cần
 * cây lỗi đẹp.
 */
public final class RuleResyncSkipper {

    private final ATN atn;
    private final int maxLookahead;

    public RuleResyncSkipper(ATN atn, int maxLookahead) {
        this.atn = atn;
        this.maxLookahead = maxLookahead;
    }

    /**
     * Rule con vừa gọi tại {@code fromTokenIndex} đã CHẾT HẲN (exits rỗng).
     * Quét tới trước (giới hạn {@code maxLookahead} token) tìm vị trí đầu
     * tiên mà token thật khớp với follow-set tại {@code followState} — tức
     * "chỗ mà nếu rule con kết thúc ở đây thì caller vẫn đọc tiếp được bình
     * thường". Trả -1 nếu không tìm thấy trong giới hạn quét (input lỗi quá
     * nặng, không resync nổi — giữ nguyên hành vi chết như cũ).
     */
    public int findResyncPoint(ATNState followState, List<InputToken> tokens, int fromTokenIndex) {
        return scan(atn.nextTokens(followState), tokens, fromTokenIndex);
    }

    /**
     * PHIÊN BẢN AN TOÀN HƠN — dùng khi cái chết là 1 CỬA MẬT KHẨU ĐƠN LẺ nằm
     * phẳng ngay trong thân rule (không qua RuleTransition nào để resync ở
     * ranh giới rule), ví dụ nhiều alternative literal cùng nhánh ra từ 1
     * decision state mà KHÔNG thể tách thành subrule riêng (không được sửa
     * grammar). Khác {@link #findResyncPoint}: ở đây KHÔNG đoán "cái gì hợp
     * lệ sau khi xong" (follow-set, dễ đoán sai ngữ cảnh khi có nhiều literal
     * đứng gần nhau) — chỉ tìm ĐÚNG LOẠI TOKEN mà chính cửa này đang cần,
     * nên không bao giờ "nuốt nhầm" 1 token vào đúng vai trò khác của nó,
     * chỉ đơn thuần dịch vị trí khớp ra xa hơn để bỏ qua rác ở giữa — đúng
     * bản chất "single/multi-token deletion" mà DefaultErrorStrategy dùng.
     */
    public int findResyncPointForLabel(IntervalSet expectedLabel, List<InputToken> tokens, int fromTokenIndex) {
        return scan(expectedLabel, tokens, fromTokenIndex);
    }

    private int scan(IntervalSet expected, List<InputToken> tokens, int fromTokenIndex) {
        int limit = Math.min(tokens.size(), fromTokenIndex + 1 + maxLookahead);
        for (int i = fromTokenIndex; i < limit; i++) {
            if (expected.contains(tokens.get(i).type())) {
                return i;
            }
        }
        return -1;
    }
}