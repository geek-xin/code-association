package com.geek.association.impact;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.Sym;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Union of the base and head knowledge graphs, oriented for reverse traversal.
 *
 * Both revisions are merged because a removed symbol only exists in the base graph while its
 * callers only exist (in their current form) in the head graph. When the same edge exists in both,
 * the head edge wins, so the report always points at the current code location.
 */
public class ImpactGraph {

    public final Map<String, Sym> symbols = new LinkedHashMap<>();
    public final Map<String, List<Edge>> incoming = new HashMap<>();
    public final Map<String, List<Edge>> outgoing = new HashMap<>();
    private final CodeIndex head;
    private final CodeIndex base;

    public ImpactGraph(CodeIndex base, CodeIndex head) {
        this.base = base;
        this.head = head;
        for (Sym s : base.symbols.values()) {
            symbols.put(s.key, s);
        }
        for (Sym s : head.symbols.values()) {
            symbols.put(s.key, s);
        }
        Map<String, Edge> merged = new LinkedHashMap<>();
        for (Edge e : base.outgoing.values().stream().flatMap(List::stream).toList()) {
            merged.put(edgeId(e), e);
        }
        for (Edge e : head.outgoing.values().stream().flatMap(List::stream).toList()) {
            merged.put(edgeId(e), e);
        }
        for (Edge e : merged.values()) {
            incoming.computeIfAbsent(e.to, k -> new ArrayList<>()).add(e);
            outgoing.computeIfAbsent(e.from, k -> new ArrayList<>()).add(e);
        }
    }

    private String edgeId(Edge e) {
        return e.from + "|" + e.to + "|" + e.relation + "|" + (e.detail == null ? "" : e.detail);
    }

    public Sym symbol(String key) {
        return symbols.get(key);
    }

    public List<Edge> incomingOf(String key) {
        return incoming.getOrDefault(key, List.of());
    }

    public List<Edge> outgoingOf(String key) {
        return outgoing.getOrDefault(key, List.of());
    }

    public CodeIndex head() {
        return head;
    }

    public CodeIndex base() {
        return base;
    }

    public List<Edge> testsOf(String key) {
        List<Edge> out = new ArrayList<>();
        for (Edge e : incomingOf(key)) {
            if ("TESTS".equals(e.relation)) {
                out.add(e);
            }
        }
        return out;
    }

    /** All test cases that transitively cover the given symbol (direct TESTS edges only). */
    public Set<String> coveringTests(String key) {
        Set<String> out = new LinkedHashSet<>();
        for (Edge e : testsOf(key)) {
            out.add(e.from);
        }
        return out;
    }
}
