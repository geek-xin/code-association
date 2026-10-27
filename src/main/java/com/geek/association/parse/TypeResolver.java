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
    /**
     * 「点后面的名字后缀 -> 类型」，只服务于 resolve 末尾那条兜底路径。
     *
     * 惰性构建：绝大多数名字在前面的分支就解析成功了，兜底很少走到；而 TypeResolver 实例
     * 创建得相当频繁（ApiAnalyzer 每次类型链接都会 new 一个），不该让每个实例都为一条冷路径
     * 先付一次全表遍历。
     */
    private Map<String, List<TypeInfo>> nestedSuffixes;
    /**
     * 继承体系索引是否已构建。
     *
     * resolve 完全不看继承关系，只有 supertypesOf / subtypesOf 用；而 TypeResolver 的实例创建得
     * 很频繁（ApiAnalyzer 每链接一个类型就 new 一个）。原来构造函数无条件遍历全部类型建这两张表，
     * 等于每次构造都白扫一遍类型表 —— 改成首次真正需要时再建。
     */
    private boolean hierarchyBuilt;

    public TypeResolver(CodeIndex index) {
        this.index = index;
    }

    private void ensureHierarchy() {
        if (hierarchyBuilt) {
            return;
        }
        hierarchyBuilt = true;
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
        // 原来这里对每个解析不到的名字都扫一遍全部类型做 endsWith；改成查一次惰性构建的后缀索引，
        // 结果集和顺序都不变，但每次未解析从 O(类型数) 降到 O(1)。
        return bySuffix(name);
    }

    public List<TypeInfo> byQualified(String qualified) {
        // index.types 就是以 qualifiedName 为 key 建的（JavaIndexer 里 types.put(qualified, info)），
        // 所以原来那个「get 未命中后再遍历全部类型比对 qualifiedName」的兜底循环永远不可能命中，
        // 只是把每次未命中变成 O(类型数) 的纯浪费 —— 删掉。
        TypeInfo t = index.types.get(qualified);
        return t != null ? List.of(t) : List.of();
    }

    /**
     * 等价于「qualifiedName 以 "." + name 结尾」的全表过滤，只是把扫描换成一次建索引。
     * 索引按 index.types 的迭代顺序追加，因此返回顺序与原实现完全一致。
     * 返回的列表是共享的，调用方只能读不能改（现有调用方都只做遍历 / get(0) / addAll）。
     */
    private List<TypeInfo> bySuffix(String name) {
        if (nestedSuffixes == null) {
            Map<String, List<TypeInfo>> built = new HashMap<>();
            for (TypeInfo t : index.types.values()) {
                String qualified = t.qualifiedName;
                for (int at = qualified.indexOf('.'); at >= 0; at = qualified.indexOf('.', at + 1)) {
                    built.computeIfAbsent(qualified.substring(at + 1), k -> new ArrayList<>()).add(t);
                }
            }
            nestedSuffixes = built;
        }
        List<TypeInfo> hit = nestedSuffixes.get(name);
        return hit == null ? List.of() : hit;
    }

    public Set<String> supertypesOf(String qualified) {
        ensureHierarchy();
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
        ensureHierarchy();
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
