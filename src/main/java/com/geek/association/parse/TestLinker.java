package com.geek.association.parse;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.source.FileSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Links test cases to production code: a test method "tests" every production method it calls
 * directly, and every production type it instantiates or references.
 */
public class TestLinker {

    private final FileSource source;
    private final CodeIndex index;
    private final MavenParser maven;

    public TestLinker(FileSource source, CodeIndex index, MavenParser maven) {
        this.source = source;
        this.index = index;
        this.maven = maven;
    }

    public void analyze() {
        List<Edge> pending = new ArrayList<>();
        Set<String> testModules = new LinkedHashSet<>();
        for (TypeInfo t : index.types.values()) {
            if (!t.test) {
                continue;
            }
            testModules.add(t.modulePath == null ? "" : t.modulePath);
            for (List<TypeInfo.MethodInfo> methods : t.methods.values()) {
                for (TypeInfo.MethodInfo m : methods) {
                    Sym testSym = index.symbol(m.key);
                    if (testSym == null) {
                        continue;
                    }
                    testSym.type = "TEST_CASE";
                    testSym.meta.put("testClass", t.qualifiedName);
                    for (Edge e : new ArrayList<>(index.outgoing.getOrDefault(m.key, List.of()))) {
                        if (!"CALLS".equals(e.relation) && !"INSTANTIATES".equals(e.relation)) {
                            continue;
                        }
                        Sym target = index.symbol(e.to);
                        if (target == null || target.test) {
                            continue;
                        }
                        Edge link = new Edge();
                        link.from = m.key;
                        link.to = e.to;
                        link.relation = "TESTS";
                        link.filePath = e.filePath;
                        link.line = e.line;
                        link.confidence = 0.8;
                        link.detail = "test calls production method";
                        pending.add(link);
                    }
                }
            }
        }
        for (TypeInfo t : index.types.values()) {
            if (t.test) {
                continue;
            }
            for (Edge e : new ArrayList<>(index.incoming.getOrDefault(t.key, List.of()))) {
                Sym from = index.symbol(e.from);
                if (from == null || !"TEST_CASE".equals(from.type)) {
                    continue;
                }
                Edge link = new Edge();
                link.from = from.key;
                link.to = t.key;
                link.relation = "TESTS";
                link.filePath = e.filePath;
                link.line = e.line;
                link.confidence = 0.6;
                link.detail = "test references production type";
                pending.add(link);
            }
        }
        for (Edge link : pending) {
            index.addEdge(link);
        }
    }
}