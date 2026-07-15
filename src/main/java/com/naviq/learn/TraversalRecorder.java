package com.naviq.learn;

import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.Transition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class TraversalRecorder {

    public record Node(int state, int tokenIndex) {
    }

    public record Edge(
            Node from,
            Node to,
            String label
    ) {
    }

    private final Set<Node> nodes = new LinkedHashSet<>();
    private final List<Edge> edges = new ArrayList<>();

    public Node visit(ATNState state, int tokenIndex) {
        Node n = new Node(state.stateNumber, tokenIndex);
        nodes.add(n);
        return n;
    }

    public void edge(
            ATNState from,
            int fromToken,
            Transition t,
            int toToken,
            String label) {

        Node a = visit(from, fromToken);
        Node b = visit(t.target, toToken);

        edges.add(new Edge(a, b, label));
    }

    public Set<Node> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    public void clear() {
        nodes.clear();
        edges.clear();
    }
}