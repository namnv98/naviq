package com.naviq.completion.syntacticv1;


import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Ngăn xếp các rule đang được gọi (đường đi từ rule ngoài cùng vào tới rule hiện
 * tại), dùng xuyên suốt quá trình duyệt ATN để biết "đang ở đâu" tại mỗi bước.
 * <p>
 * Cài đặt dạng linked-list bất biến (persistent, structural sharing):
 * <ul>
 *   <li>{@link #push} — O(1), tạo node mới trỏ tới node cũ, không đụng tới các
 *       bản sao khác đang tồn tại.</li>
 *   <li>{@link #copy} — O(1), chỉ chia sẻ tham chiếu {@code head}, không copy
 *       toàn bộ nội dung. Điều này quan trọng vì {@code copy()} được gọi ở hầu
 *       hết mọi bước của quá trình duyệt ATN.</li>
 * </ul>
 */
public final class RuleCallStack {
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
        this.head = null;
        this.size = 0;
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
        if (head == null) {
            throw new NoSuchElementException("RuleCallStack is empty");
        }
        RuleFrame f = head.frame;
        head = head.parent;
        size--;
        return f;
    }

    /** Rule này có đang active ở bất kỳ vị trí nào trong stack không (không quan tâm tokenIndex). */
    public boolean contains(int ruleId) {
        for (Node n = head; n != null; n = n.parent) {
            if (n.frame.ruleId() == ruleId) return true;
        }
        return false;
    }

    public int size() {
        return size;
    }

    /** Trả về danh sách frame theo thứ tự outer → inner (rule ngoài cùng ở index 0). */
    public List<RuleFrame> frames() {
        RuleFrame[] arr = new RuleFrame[size];
        int i = size - 1;
        for (Node n = head; n != null; n = n.parent) {
            arr[i--] = n.frame;
        }
        return Arrays.asList(arr);
    }

    /** Nối thêm toàn bộ frame của {@code other} vào cuối stack hiện tại (theo đúng thứ tự). */
    public void appendPath(RuleCallStack other) {
        for (RuleFrame f : other.frames()) {
            push(f.ruleId(), f.tokenIndex());
        }
    }

    /** O(1): chỉ chia sẻ con trỏ head, không copy nội dung. */
    public RuleCallStack copy() {
        return new RuleCallStack(head, size);
    }
}
