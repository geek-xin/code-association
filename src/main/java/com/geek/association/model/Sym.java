package com.geek.association.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A node of the code knowledge graph. */
public class Sym {

    public String key;
    public String type;
    public String qualifiedName;
    public String signature = "";
    public String simpleName;
    public String modulePath;
    public String filePath;
    public int startLine;
    public int endLine;
    public String returnType;
    public List<String> paramTypes = new ArrayList<>();
    public Set<String> modifiers = new LinkedHashSet<>();
    public Set<String> annotations = new LinkedHashSet<>();
    public String bodyHash;
    public String signatureHash;
    public String ownerKey;
    public boolean test;
    public Map<String, String> meta = new LinkedHashMap<>();

    public String display() {
        return signature == null || signature.isBlank() ? qualifiedName : qualifiedName + signature;
    }

    public String moduleOrRoot() {
        return modulePath == null || modulePath.isBlank() ? "(root)" : modulePath;
    }
}
