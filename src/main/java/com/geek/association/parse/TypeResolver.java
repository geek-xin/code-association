package com.geek.association.parse;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.TypeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort Java name resolution on top of the parsed type table.
 * It is deliberately conservative: when a name is ambiguous we return every candidate and
 * let the impact engine lower the confidence instead of silently picking one.
 */
public class TypeResolver {

    private final CodeIndex index;
    /** qualified type name -> directly known supertypes */
    private final Map<String, Set<String>> supertypes = new HashMap<>();
    private final Map<String, Set<String>> subtypes = new HashMap<>();

    public TypeResolver(CodeIndex index) {
        this.index = index;
        buildHierarchy();
    }

    private void buildHierarchy() {
        for (TypeInfo t : index.types.values()) {
            Set<String> sup = new LinkedHashSet<>();
            if (t.superclass != null && !t.superclass.isBlank()) {
                sup.add(t.superclass);
            }
            sup.addAll(t.interfaces);
            supertypes.put(t.qualifiedName, sup);
            for (String s : sup) {
                subtypes.computeIfAbsent(s, k -> new LinkedHashSet<>()).add(t.qualifiedName);
            }
        }
    }

    public List<TypeInfo> resolve(String rawName, Map<String, String> imports, String packageName) {
        if (rawName == null || rawName.isBlank()) {
            return List.of();
        }
        String name = stripGenerics(rawName).replace("[]", "").trim();
        if (name.isEmpty()) {
            return List.of();
        }
        List<TypeInfo> direct = byQualified(name);
        if (!direct.isEmpty()) {
            return direct;
        }
        String first = name;
        String rest = "";
        int dot = name.indexOf('.');
        if (dot > 0) {
            first = name.substring(0, dot);
            rest = name.substring(dot + 1);
        }
        String qualifiedByImport = imports.get(first);
        if (qualifiedByImport != null) {
            List<TypeInfo> viaImport = byQualified(rest.isEmpty() ? qualifiedByImport : qualifiedByImport + "." + rest);
            if (!viaImport.isEmpty()) {
                return viaImport;
            }
            viaImport = byQualified(qualifiedByImport);
            if (!viaImport.isEmpty()) {
                return viaImport;
            }
        }
        if (!packageName.isBlank()) {
            List<TypeInfo> samePkg = byQualified(packageName + "." + name);
            if (!samePkg.isEmpty()) {
                return samePkg;
            }
        }
        List<TypeInfo> simple = index.resolveSimple(first);
        if (!simple.isEmpty()) {
            if (!rest.isEmpty()) {
                List<TypeInfo> nested = new ArrayList<>();
                for (TypeInfo t : simple) {
                    nested.addAll(byQualified(t.qualifiedName + "." + rest));
                }
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
            return simple;
        }
        List<TypeInfo> lang = byQualified("java.lang." + first);
        if (!lang.isEmpty()) {
            return lang;
        }
        List<TypeInfo> nested = new ArrayList<>();
        for (TypeInfo t : index.types.values()) {
            if (t.qualifiedName.endsWith("." + name)) {
                nested.add(t);
            }
        }
        return nested;
    }

    public List<TypeInfo> byQualified(String qualified) {
        TypeInfo t = index.types.get(qualified);
        if (t != null) {
            return List.of(t);
        }
        List<TypeInfo> out = new ArrayList<>();
        for (TypeInfo candidate : index.types.values()) {
            if (candidate.qualifiedName.equals(qualified)) {
                out.add(candidate);
            }
        }
        return out;
    }

    public Set<String> supertypesOf(String qualified) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> direct = supertypes.get(qualified);
        if (direct == null) {
            return out;
        }
        for (String s : direct) {
            if (out.add(s)) {
                out.addAll(supertypesOf(s));
            }
        }
        return out;
    }

    public Set<String> subtypesOf(String qualified) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> direct = subtypes.get(qualified);
        if (direct == null) {
            return out;
        }
        for (String s : direct) {
            if (out.add(s)) {
                out.addAll(subtypesOf(s));
            }
        }
        return out;
    }

    public static String stripGenerics(String name) {
        if (name == null) {
            return "";
        }
        String v = name;
        int idx = v.indexOf('<');
        if (idx > 0) {
            v = v.substring(0, idx);
        }
        return v.replace("...", "").trim();
    }

    public static String simpleOf(String qualified) {
        if (qualified == null) {
            return "";
        }
        int idx = qualified.lastIndexOf('.');
        return idx < 0 ? qualified : qualified.substring(idx + 1);
    }
}
