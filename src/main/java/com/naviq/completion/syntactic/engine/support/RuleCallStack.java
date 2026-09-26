package com.naviq.completion.syntactic.engine.support;

import java.util.*;

/**
 * Đường gọi rule: "đang lồng trong những rule nào, mỗi rule được vào ở token index nào".
 * Là linked-list dùng chung phần đuôi (structural sharing): {@link #copy()} là O(1) và {@link #push}
 * trên bản copy không ảnh hưởng bản gốc.
 */
public final class RuleCallStack {

    public record RuleFrame(int ruleId, int tokenIndex) {
        /** Rule đến từ follow-set tính trước nên không có token index thật. */
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

    public RuleCallStack copy() {
        return new RuleCallStack(head, size);
    }
}
