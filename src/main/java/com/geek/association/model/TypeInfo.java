package com.geek.association.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Declaration-level view of a Java type, used for best-effort name resolution. */
public class TypeInfo {

    public String qualifiedName;
    public String simpleName;
    public String packageName = "";
    public String kind = "CLASS";
    public String modulePath;
    public String filePath;
    public int startLine;
    public int endLine;
    public String superclass;
    public List<String> interfaces = new ArrayList<>();
    /** Raw (unresolved) supertypes exactly as written in source. */
    public String rawSuperclass;
    public List<String> rawInterfaces = new ArrayList<>();
    public Map<String, String> imports = new LinkedHashMap<>();
    public Set<String> annotations = new LinkedHashSet<>();
    public Map<String, List<MethodInfo>> methods = new LinkedHashMap<>();
    public Map<String, FieldInfo> fields = new LinkedHashMap<>();
    public boolean test;
    public boolean isInterface;
    public boolean isEnum;
    public boolean isAbstract;
    public boolean isAnnotation;
    public boolean isRecord;
    public String key;
    public String bodyHash;
    public int methodCount;

    public static class MethodInfo {
        public String name;
        public String returnType;
        public List<String> paramTypes = new ArrayList<>();
        public Set<String> annotations = new LinkedHashSet<>();
        public String key;
        public int line;
        public boolean isConstructor;
        public String bodyHash;
        public String signatureHash;
        public Set<String> modifiers = new LinkedHashSet<>();
        public boolean isAbstract;
        public String owner;
    }

    public static class FieldInfo {
        public String name;
        public String type;
        public String key;
        public int line;
        public Set<String> annotations = new LinkedHashSet<>();
        public Set<String> modifiers = new LinkedHashSet<>();
        public String initializerHash;
        public String owner;
    }
}
