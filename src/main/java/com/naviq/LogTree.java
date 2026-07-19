package com.naviq;

import com.naviq.antlr4.oracle.PlSqlLexer;
import com.naviq.antlr4.oracle.PlSqlParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;

public class LogTree {

    private static final int SPACING = 2; // khoảng trống tối thiểu giữa 2 subtree anh em

    public static void main(String[] args) {
//        String sql = "select * from public.users u left join public.orders o on u.id = o.customer_id where o.";
        String sql = "select * from orders where ";

        CharStream input = CharStreams.fromString(sql);
        PlSqlLexer lexer = new PlSqlLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(tokenStream);
        parser.removeErrorListeners();
        ParserRuleContext root = parser.unit_statement();

        printTreeTopDown(root, parser);

//        org.antlr.v4.gui.Trees.inspect(root, parser);
    }

    // ── Node nội bộ để tính layout, tách khỏi ParseTree cho gọn ──────────
    private static final class Node {
        final String label;
        final List<Node> children = new ArrayList<>();
        int width;   // measure()
        int center;  // assign()

        Node(String label) {
            this.label = label;
        }
    }

    private static String nodeText(ParseTree node, Parser parser) {
        if (node instanceof RuleNode rule) {
            return parser.getRuleNames()[rule.getRuleContext().getRuleIndex()];
        } else if (node instanceof ErrorNode) {
            return "<ERROR> " + node.getText();
        } else if (node instanceof TerminalNode terminal) {
            String tokenTypeName = parser.getVocabulary().getSymbolicName(terminal.getSymbol().getType());
            String tokenValue = terminal.getText();
           return tokenTypeName + ": '" + tokenValue+"'";
        } else {
            return node.getText();
        }
    }

    private static Node build(ParseTree tree, Parser parser) {
        Node n = new Node(nodeText(tree, parser));
        for (int i = 0; i < tree.getChildCount(); i++) {
            n.children.add(build(tree.getChild(i), parser));
        }
        return n;
    }

    // Bottom-up: bề rộng subtree = max(bề rộng nhãn, tổng bề rộng các con + khoảng cách)
    private static void measure(Node n) {
        if (n.children.isEmpty()) {
            n.width = n.label.length();
            return;
        }
        int childrenTotal = 0;
        for (Node c : n.children) {
            measure(c);
            childrenTotal += c.width;
        }
        childrenTotal += SPACING * (n.children.size() - 1);
        n.width = Math.max(n.label.length(), childrenTotal);
    }

    // Top-down: gán cột center cho từng node, các con được căn giữa trong n.width
    // (nếu nhãn cha dài hơn tổng bề rộng con thì phần dư chia đều 2 bên).
    private static void assign(Node n, int startCol) {
        if (n.children.isEmpty()) {
            n.center = startCol + n.width / 2;
            return;
        }
        int childrenTotal = 0;
        for (Node c : n.children) childrenTotal += c.width;
        childrenTotal += SPACING * (n.children.size() - 1);

        int extra = n.width - childrenTotal;
        int x = startCol + extra / 2;
        for (Node c : n.children) {
            assign(c, x);
            x += c.width + SPACING;
        }
        n.center = (n.children.get(0).center + n.children.get(n.children.size() - 1).center) / 2;
    }

    private static int maxDepth(Node n, int depth) {
        int max = depth;
        for (Node c : n.children) max = Math.max(max, maxDepth(c, depth + 1));
        return max;
    }

    public static void printTreeTopDown(ParseTree tree, Parser parser) {
        Node root = build(tree, parser);
        measure(root);
        assign(root, 0);

        int depth = maxDepth(root, 0);
        int width = root.width;
        int rowCount = depth * 2 + 1; // dòng nhãn xen kẽ dòng nối
        char[][] canvas = new char[rowCount][width];
        for (char[] row : canvas) java.util.Arrays.fill(row, ' ');

        render(root, 0, canvas);

        for (char[] row : canvas) {
            String s = new String(row);
            // bỏ khoảng trắng thừa bên phải cho gọn khi in
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == ' ') end--;
            System.out.println(s.substring(0, end));
        }
    }

    private static void render(Node n, int depth, char[][] canvas) {
        placeLabel(canvas[depth * 2], n.label, n.center);

        if (n.children.isEmpty()) return;

        char[] connectorRow = canvas[depth * 2 + 1];
        int leftMost = n.children.get(0).center;
        int rightMost = n.children.get(n.children.size() - 1).center;

        if (leftMost == rightMost) {
            connectorRow[leftMost] = '│';
        } else {
            for (int col = leftMost; col <= rightMost; col++) connectorRow[col] = '─';
            for (Node c : n.children) connectorRow[c.center] = '┬';

            if (n.center >= leftMost && n.center <= rightMost) {
                connectorRow[n.center] = (connectorRow[n.center] == '┬') ? '┼' : '┴';
            } else if (n.center < leftMost) {
                for (int col = n.center; col < leftMost; col++) connectorRow[col] = '─';
                connectorRow[n.center] = '┴';
            } else {
                for (int col = rightMost + 1; col <= n.center; col++) connectorRow[col] = '─';
                connectorRow[n.center] = '┴';
            }
        }

        for (Node c : n.children) render(c, depth + 1, canvas);
    }

    private static void placeLabel(char[] row, String label, int center) {
        int start = Math.max(0, center - label.length() / 2);
        for (int i = 0; i < label.length() && start + i < row.length; i++) {
            row[start + i] = label.charAt(i);
        }
    }
}