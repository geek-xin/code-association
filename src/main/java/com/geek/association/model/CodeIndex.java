package com.geek.association.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory code knowledge graph for one revision of one repository. */
public class CodeIndex {

    public String revisionLabel = "";
    public String revisionId = "";
    public Map<String, Sym> symbols = new LinkedHashMap<>();
    public Map<String, TypeInfo> types = new LinkedHashMap<>();
    public Map<String, Set<String>> simpleNameIndex = new HashMap<>();
    public Map<String, List<Edge>> outgoing = new HashMap<>();
    public Map<String, List<Edge>> incoming = new HashMap<>();
    public Map<String, ModuleInfo> modules = new LinkedHashMap<>();
    /** module artifactId -> module path */
    public Map<String, String> artifactToModule = new HashMap<>();
    public List<UnresolvedCall> unresolved = new ArrayList<>();
    public Map<String, String> fileToModule = new HashMap<>();
    /** [fromModule, toModule, artifactId] triples derived from pom.xml dependencies. */
    public List<String[]> moduleDependencies = new ArrayList<>();
    public int parsedFiles;
    public long buildMillis;
    /**
     * The database this program declares in its configuration, when exactly one can be read.
     * Null means unknown or ambiguous - never a guess. See {@code DatabaseIdentity}.
     */
    public String databaseName;
    /** Default schema/catalog declared by the program, or null when unknown. */
    public String defaultSchema;
    /** {@code table.column -> SQL type}, from CREATE TABLE and ALTER statements. */
    public Map<String, String> columnTypes = new HashMap<>();
    /** {@code table.column -> "true"/"false"} when the DDL states nullability. */
    public Map<String, String> columnNullability = new HashMap<>();
    /** {@code table.column -> default expression} when the DDL states one. */
    public Map<String, String> columnDefaults = new HashMap<>();

    public static class UnresolvedCall {
        public String filePath;
        public int line;
        public String expression;
        public String reason;
        public String modulePath;
    }

    public void addSymbol(Sym sym) {
        symbols.put(sym.key, sym);
        if (sym.type.equals("CLASS") || sym.type.equals("INTERFACE") || sym.type.equals("ENUM")
                || sym.type.equals("RECORD") || sym.type.equals("ANNOTATION")) {
            simpleNameIndex.computeIfAbsent(sym.simpleName, k -> new java.util.LinkedHashSet<>()).add(sym.qualifiedName);
        }
    }

    public void addEdge(Edge edge) {
        if (edge.from == null || edge.to == null || edge.from.equals(edge.to)) {
            return;
        }
        outgoing.computeIfAbsent(edge.from, k -> new ArrayList<>()).add(edge);
        incoming.computeIfAbsent(edge.to, k -> new ArrayList<>()).add(edge);
    }

    public Sym symbol(String key) {
        return key == null ? null : symbols.get(key);
    }

    public List<Edge> callersOf(String key) {
        return incoming.getOrDefault(key, List.of());
    }

    public TypeInfo type(String qualifiedName) {
        return qualifiedName == null ? null : types.get(qualifiedName);
    }

    public List<TypeInfo> resolveSimple(String simpleName) {
        Set<String> qualified = simpleNameIndex.get(simpleName);
        if (qualified == null || qualified.isEmpty()) {
            return List.of();
        }
        List<TypeInfo> out = new ArrayList<>();
        for (String q : qualified) {
            TypeInfo t = types.get(q);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }
}
