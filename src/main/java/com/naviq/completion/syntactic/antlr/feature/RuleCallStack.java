package com.naviq.completion.syntactic.antlr.feature;

import lombok.Getter;

import java.util.*;

/**
 * FEATURE: dữ liệu phụ trợ cho việc "gộp gợi ý về mê cung đặc biệt NGOÀI CÙNG"
 * (xem PreferredRuleResolver). KHÔNG thuộc lõi thuật toán — nếu bỏ hẳn feature
 * này đi, engine vẫn chạy đúng, chỉ là không gộp được các mê cung đặc biệt lồng
 * nhau về đúng cái ngoài cùng.
 * <p>
 * Ngăn xếp (bất biến) ghi lại "đang lồng trong những mê cung nào, tại vị trí
 * lời nói nào". Dùng linked-list chia sẻ (structural sharing) qua copy(): push()
 * ở nhánh này không ảnh hưởng nhánh khác.
 */
@Getter
public final class RuleCallStack {

    public record RuleFrame(int ruleId, int tokenIndex) {
        public static final int NO_TOKEN = -1;
    }

    private static final class Node {
        final RuleFrame frame;
        final Node parent;
        Node(RuleFrame frame, Node parent) {
            this.frame = frame;
            this.parent = parent;
        }
    }

    private Node head;
    private int size;

    public RuleCallStack() {
    }

    private RuleCallStack(Node head, int size) {
        this.head = head;
        this.size = size;
    }

    public void push(int ruleId, int tokenIndex) {
        head = new Node(new RuleFrame(ruleId, tokenIndex), head);
        size++;
    }

    public RuleFrame pop() {
        if (head == null) throw new NoSuchElementException("RuleCallStack is empty");
        RuleFrame f = head.frame;
        head = head.parent;
        size--;
        return f;
    }

    public boolean contains(int ruleId) {
        for (Node n = head; n != null; n = n.parent) {
            if (n.frame.ruleId() == ruleId) return true;
        }
        return false;
    }

    /** Danh sách frame theo thứ tự NGOÀI CÙNG trước, TRONG CÙNG sau. */
    public List<RuleFrame> frames() {
        RuleFrame[] arr = new RuleFrame[size];
        int i = size - 1;
        for (Node n = head; n != null; n = n.parent) arr[i--] = n.frame;
        return Arrays.asList(arr);
    }

    public void appendPath(RuleCallStack other) {
        for (RuleFrame f : other.frames()) push(f.ruleId(), f.tokenIndex());
    }

    /** "Nhân bản" nhẹ: chỉ chia sẻ lại con trỏ head hiện tại, O(1), không copy sâu. */
    public RuleCallStack copy() {
        return new RuleCallStack(head, size);
    }
}
