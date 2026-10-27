package com.geek.association.parse;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.ModuleInfo;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.source.FileSource;
import com.geek.association.util.Hashes;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Builds the declaration layer of the code index: types, methods, fields, signatures,
 * body hashes, annotations and imports. Call resolution is delegated to {@link CallGraphBuilder}
 * because it needs the complete type table first.
 */
public class JavaIndexer {

    public static final Set<String> SPRING_MAPPING_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping");

    private final FileSource source;
    private final MavenParser maven;
    private final int maxFiles;
    private final JavaParser parser;
    private final CodeIndex index;
    private final Map<String, List<String>> duplicateQualified = new HashMap<>();
    private final FileProgress onFile;

    public JavaIndexer(FileSource source, MavenParser maven, CodeIndex index, int maxFiles) {
        this(source, maven, index, maxFiles, (FileProgress) null);
    }

    public JavaIndexer(FileSource source, MavenParser maven, CodeIndex index, int maxFiles,
                       FileProgress onFile) {
        this.source = source;
        this.maven = maven;
        this.index = index;
        this.maxFiles = maxFiles;
        this.onFile = onFile;
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false);
        this.parser = new JavaParser(cfg);
    }

    public CodeIndex build() {
        long start = System.currentTimeMillis();
        index.modules = maven.modules;
        index.artifactToModule = maven.artifactToModule;
        index.fileToModule = maven.fileToModule;
        List<String> javaFiles = new ArrayList<>();
        for (String f : source.files()) {
            if (f.endsWith(".java") && !f.contains("/src/test/resources/")) {
                javaFiles.add(f);
            }
        }
        javaFiles.sort(String::compareTo);
        if (javaFiles.size() > maxFiles) {
            javaFiles = new ArrayList<>(javaFiles.subList(0, maxFiles));
        }
        Map<String, CompilationUnit> units = new LinkedHashMap<>();
        // Indexing does two passes over the same files: parsing, then building the call graph. One
        // pass's worth of files would make the bar run to 100% and then restart, so the unit is a
        // (pass, file) pair and there are two units per file.
        int passTotal = javaFiles.size() * 2;
        int seen = 0;
        for (String file : javaFiles) {
            if (onFile != null) {
                // 1-based: "the 500th of 1500" is what the UI shows, and reporting 0 for the first
                // file would read as "nothing done yet" on a run that has already started.
                onFile.file(file, ++seen, passTotal);
            }
            String content = source.read(file);
            if (content == null) {
                continue;
            }
            ParseResult<CompilationUnit> result = parser.parse(content);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                index.unresolved.add(unresolved(file, 1, "<parse>", "parse failed: "
                        + result.getProblems().stream().findFirst().map(p -> p.getMessage()).orElse("unknown")));
                continue;
            }
            CompilationUnit cu = result.getResult().get();
            units.put(file, cu);
            index.parsedFiles++;
            collectTypes(cu, file);
        }
        resolveSupertypes();
        // Second pass: continue the same unit count so the reported percentage keeps climbing
        // instead of falling back to the start of a new range. The offset is captured in a final
        // local because javaFiles is reassigned above and so cannot be used inside the lambda.
        final int graphOffset = javaFiles.size();
        FileProgress graphProgress = onFile == null ? null
                : (file, done, ignoredTotal) -> onFile.file(file, graphOffset + done, passTotal);
        new CallGraphBuilder(source, index, maven, units, parser, graphProgress).build();
        index.buildMillis = System.currentTimeMillis() - start;
        return index;
    }

    private CodeIndex.UnresolvedCall unresolved(String file, int line, String expr, String reason) {
        CodeIndex.UnresolvedCall u = new CodeIndex.UnresolvedCall();
        u.filePath = file;
        u.line = line;
        u.expression = expr;
        u.reason = reason;
        u.modulePath = maven.moduleOf(file);
        return u;
    }

    // ------------------------------------------------------------------ pass 1

    private void collectTypes(CompilationUnit cu, String file) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        Map<String, String> imports = new LinkedHashMap<>();
        Set<String> starImports = new LinkedHashSet<>();
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (imp.isAsterisk()) {
                starImports.add(name);
            } else {
                String simple = name.substring(name.lastIndexOf('.') + 1);
                imports.put(simple, name);
            }
        }
        boolean test = maven.moduleOf(file) != null && file.contains("/src/test/");
        for (TypeDeclaration<?> type : cu.getTypes()) {
            collectType(type, pkg, imports, starImports, file, test, null);
        }
    }

    private void collectType(TypeDeclaration<?> type, String pkg, Map<String, String> imports,
                             Set<String> starImports, String file, boolean test, String outerQualified) {
        String simple = type.getNameAsString();
        String qualified = outerQualified == null ? (pkg.isBlank() ? simple : pkg + "." + simple)
                : outerQualified + "." + simple;
        TypeInfo info = new TypeInfo();
        info.qualifiedName = qualified;
        info.simpleName = simple;
        info.packageName = pkg;
        info.modulePath = maven.moduleOf(file);
        info.filePath = file;
        info.startLine = type.getBegin().map(p -> p.line).orElse(0);
        info.endLine = type.getEnd().map(p -> p.line).orElse(0);
        info.test = test;
        info.imports = imports;
        info.annotations.addAll(annotationNames(type.getAnnotations()));
        info.key = typeKey(info.modulePath, qualified);
        info.bodyHash = Hashes.sha1(normalize(type.toString()));

        if (type instanceof ClassOrInterfaceDeclaration coid) {
            info.isInterface = coid.isInterface();
            info.isAbstract = coid.isAbstract() || coid.isInterface();
            info.kind = coid.isInterface() ? "INTERFACE" : "CLASS";
            coid.getExtendedTypes().forEach(t -> info.rawInterfaces.add(t.getNameWithScope()));
            if (!coid.isInterface()) {
                coid.getExtendedTypes().stream().findFirst().ifPresent(t -> info.rawSuperclass = t.getNameWithScope());
                if (info.rawSuperclass != null) {
                    info.rawInterfaces.remove(info.rawSuperclass);
                }
            }
            coid.getImplementedTypes().forEach(t -> info.rawInterfaces.add(t.getNameWithScope()));
            if (coid.isInterface()) {
                // interface extends another interface
                info.rawInterfaces.addAll(coid.getExtendedTypes().stream().map(ClassOrInterfaceType::getNameWithScope).toList());
            }
        } else if (type instanceof EnumDeclaration ed) {
            info.isEnum = true;
            info.kind = "ENUM";
            ed.getImplementedTypes().forEach(t -> info.rawInterfaces.add(t.getNameWithScope()));
        } else if (type instanceof RecordDeclaration rd) {
            info.isRecord = true;
            info.kind = "RECORD";
            rd.getImplementedTypes().forEach(t -> info.rawInterfaces.add(t.getNameWithScope()));
            for (Parameter p : rd.getParameters()) {
                TypeInfo.FieldInfo field = new TypeInfo.FieldInfo();
                field.name = p.getNameAsString();
                field.type = p.getType().asString();
                field.owner = qualified;
                field.key = fieldKey(info.modulePath, qualified, field.name);
                field.line = p.getBegin().map(pos -> pos.line).orElse(0);
                info.fields.put(field.name, field);
            }
        } else if (type instanceof AnnotationDeclaration) {
            info.isAnnotation = true;
            info.kind = "ANNOTATION";
        }

        if (index.types.containsKey(qualified)) {
            duplicateQualified.computeIfAbsent(qualified, k -> new ArrayList<>()).add(info.modulePath);
            // keep the first definition but record the collision; keys stay module-qualified
        }
        index.types.put(qualified, info);
        index.addSymbol(typeSymbol(info));

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof MethodDeclaration md) {
                collectMethod(info, md);
            } else if (member instanceof ConstructorDeclaration cd) {
                collectConstructor(info, cd);
            } else if (member instanceof FieldDeclaration fd) {
                for (VariableDeclarator v : fd.getVariables()) {
                    TypeInfo.FieldInfo field = new TypeInfo.FieldInfo();
                    field.name = v.getNameAsString();
                    field.type = fd.getElementType().asString();
                    field.line = v.getBegin().map(p -> p.line).orElse(0);
                    field.owner = qualified;
                    field.key = fieldKey(info.modulePath, qualified, field.name);
                    field.modifiers.addAll(fd.getModifiers().stream().map(m -> m.getKeyword().asString()).toList());
                    field.annotations.addAll(annotationNames(fd.getAnnotations()));
                    field.initializerHash = v.getInitializer().map(init -> Hashes.sha1(normalize(init.toString()))).orElse(null);
                    info.fields.put(field.name, field);
                    index.addSymbol(fieldSymbol(info, field));
                }
            } else if (member instanceof EnumConstantDeclaration ecd) {
                TypeInfo.FieldInfo field = new TypeInfo.FieldInfo();
                field.name = ecd.getNameAsString();
                field.type = qualified;
                field.line = ecd.getBegin().map(p -> p.line).orElse(0);
                field.owner = qualified;
                field.key = fieldKey(info.modulePath, qualified, field.name);
                info.fields.put(field.name, field);
                Sym sym = fieldSymbol(info, field);
                sym.type = "ENUM_CONSTANT";
                index.addSymbol(sym);
            } else if (member instanceof TypeDeclaration<?> nested) {
                collectType(nested, pkg, imports, starImports, file, test, qualified);
            }
        }
        info.methodCount = info.methods.values().stream().mapToInt(List::size).sum();
    }

    private void collectMethod(TypeInfo owner, MethodDeclaration md) {
        TypeInfo.MethodInfo m = new TypeInfo.MethodInfo();
        m.name = md.getNameAsString();
        m.returnType = md.getType().asString();
        for (Parameter p : md.getParameters()) {
            m.paramTypes.add(p.getType().asString());
        }
        m.annotations.addAll(annotationNames(md.getAnnotations()));
        m.line = md.getBegin().map(p -> p.line).orElse(0);
        m.isConstructor = false;
        m.isAbstract = md.isAbstract() || md.getBody().isEmpty();
        m.modifiers.addAll(md.getModifiers().stream().map(x -> x.getKeyword().asString()).toList());
        m.owner = owner.qualifiedName;
        m.bodyHash = md.getBody().map(b -> Hashes.sha1(normalize(b.toString()))).orElse("");
        m.signatureHash = Hashes.sha1(m.name + "(" + String.join(",", m.paramTypes) + "):" + m.returnType);
        m.key = methodKey(owner.modulePath, owner.qualifiedName, m.name, m.paramTypes);
        owner.methods.computeIfAbsent(m.name, k -> new ArrayList<>()).add(m);

        Sym sym = new Sym();
        sym.key = m.key;
        sym.type = "METHOD";
        sym.qualifiedName = owner.qualifiedName + "#" + m.name;
        sym.simpleName = m.name;
        sym.signature = "(" + String.join(", ", m.paramTypes) + ")";
        sym.modulePath = owner.modulePath;
        sym.filePath = owner.filePath;
        sym.startLine = m.line;
        sym.endLine = md.getEnd().map(p -> p.line).orElse(m.line);
        sym.returnType = m.returnType;
        sym.paramTypes = new ArrayList<>(m.paramTypes);
        sym.modifiers = new LinkedHashSet<>(m.modifiers);
        sym.annotations = new LinkedHashSet<>(m.annotations);
        sym.bodyHash = m.bodyHash;
        sym.signatureHash = m.signatureHash;
        sym.ownerKey = owner.key;
        sym.test = owner.test || m.annotations.contains("Test");
        sym.meta.put("abstract", String.valueOf(m.isAbstract));
        if (md.getBody().isPresent()) {
            ConditionFingerprint fp = ConditionFingerprint.of(md.getBody().get());
            sym.meta.put("conditions", String.join(" | ", fp.conditions));
            sym.meta.put("conditionsHash", fp.hash);
            sym.meta.put("conditionCount", String.valueOf(fp.conditions.size()));
            sym.meta.put("calls", String.join(",", fp.calls));
        }
        index.addSymbol(sym);
    }

    private void collectConstructor(TypeInfo owner, ConstructorDeclaration cd) {
        TypeInfo.MethodInfo m = new TypeInfo.MethodInfo();
        m.name = "<init>";
        m.returnType = owner.qualifiedName;
        for (Parameter p : cd.getParameters()) {
            m.paramTypes.add(p.getType().asString());
        }
        m.annotations.addAll(annotationNames(cd.getAnnotations()));
        m.line = cd.getBegin().map(p -> p.line).orElse(0);
        m.isConstructor = true;
        m.modifiers.addAll(cd.getModifiers().stream().map(x -> x.getKeyword().asString()).toList());
        m.owner = owner.qualifiedName;
        m.bodyHash = Hashes.sha1(normalize(cd.getBody().toString()));
        m.signatureHash = Hashes.sha1("<init>(" + String.join(",", m.paramTypes) + ")");
        m.key = methodKey(owner.modulePath, owner.qualifiedName, "<init>", m.paramTypes);
        owner.methods.computeIfAbsent("<init>", k -> new ArrayList<>()).add(m);

        Sym sym = new Sym();
        sym.key = m.key;
        sym.type = "CONSTRUCTOR";
        sym.qualifiedName = owner.qualifiedName + "#<init>";
        sym.simpleName = "<init>";
        sym.signature = "(" + String.join(", ", m.paramTypes) + ")";
        sym.modulePath = owner.modulePath;
        sym.filePath = owner.filePath;
        sym.startLine = m.line;
        sym.endLine = cd.getEnd().map(p -> p.line).orElse(m.line);
        sym.returnType = owner.qualifiedName;
        sym.paramTypes = new ArrayList<>(m.paramTypes);
        sym.modifiers = new LinkedHashSet<>(m.modifiers);
        sym.annotations = new LinkedHashSet<>(m.annotations);
        sym.bodyHash = m.bodyHash;
        sym.signatureHash = m.signatureHash;
        sym.ownerKey = owner.key;
        index.addSymbol(sym);
    }

    private Sym typeSymbol(TypeInfo info) {
        Sym sym = new Sym();
        sym.key = info.key;
        sym.type = info.kind;
        sym.qualifiedName = info.qualifiedName;
        sym.simpleName = info.simpleName;
        sym.signature = "";
        sym.modulePath = info.modulePath;
        sym.filePath = info.filePath;
        sym.startLine = info.startLine;
        sym.endLine = info.endLine;
        sym.annotations = new LinkedHashSet<>(info.annotations);
        sym.bodyHash = info.bodyHash;
        sym.signatureHash = Hashes.sha1(info.kind + ":" + info.qualifiedName);
        sym.test = info.test;
        if (info.rawSuperclass != null) {
            sym.meta.put("extends", info.rawSuperclass);
        }
        if (!info.rawInterfaces.isEmpty()) {
            sym.meta.put("implements", String.join(",", info.rawInterfaces));
        }
        return sym;
    }

    private Sym fieldSymbol(TypeInfo owner, TypeInfo.FieldInfo field) {
        Sym sym = new Sym();
        sym.key = field.key;
        sym.type = "FIELD";
        sym.qualifiedName = owner.qualifiedName + "#" + field.name;
        sym.simpleName = field.name;
        sym.signature = ":" + field.type;
        sym.modulePath = owner.modulePath;
        sym.filePath = owner.filePath;
        sym.startLine = field.line;
        sym.endLine = field.line;
        sym.returnType = field.type;
        sym.modifiers = new LinkedHashSet<>(field.modifiers);
        sym.annotations = new LinkedHashSet<>(field.annotations);
        sym.ownerKey = owner.key;
        sym.signatureHash = Hashes.sha1(field.name + ":" + field.type);
        sym.bodyHash = field.initializerHash;
        sym.test = owner.test;
        return sym;
    }

    // --------------------------------------------------------------- hierarchy

    private void resolveSupertypes() {
        TypeResolver resolver = new TypeResolver(index);
        for (TypeInfo info : index.types.values()) {
            if (info.rawSuperclass != null && !info.rawSuperclass.isBlank()) {
                List<TypeInfo> resolved = resolver.resolve(info.rawSuperclass, info.imports, info.packageName);
                if (!resolved.isEmpty()) {
                    info.superclass = resolved.get(0).qualifiedName;
                    Sym sym = index.symbol(info.key);
                    if (sym != null) {
                        com.geek.association.model.Edge e = new com.geek.association.model.Edge();
                        e.from = info.key;
                        e.to = resolved.get(0).key;
                        e.relation = "EXTENDS";
                        e.filePath = info.filePath;
                        e.line = info.startLine;
                        e.detail = info.rawSuperclass;
                        e.confidence = 0.95;
                        index.addEdge(e);
                    }
                } else {
                    info.superclass = info.rawSuperclass;
                }
            }
            for (String raw : info.rawInterfaces) {
                List<TypeInfo> resolved = resolver.resolve(raw, info.imports, info.packageName);
                if (!resolved.isEmpty()) {
                    TypeInfo target = resolved.get(0);
                    info.interfaces.add(target.qualifiedName);
                    com.geek.association.model.Edge e = new com.geek.association.model.Edge();
                    e.from = info.key;
                    e.to = target.key;
                    e.relation = "IMPLEMENTS";
                    e.filePath = info.filePath;
                    e.line = info.startLine;
                    e.detail = raw;
                    e.confidence = resolved.size() > 1 ? 0.7 : 0.95;
                    index.addEdge(e);
                } else {
                    info.interfaces.add(raw);
                }
            }
        }
        // rebuild resolver with resolved hierarchy, then link overriding methods
        TypeResolver resolvedResolver = new TypeResolver(index);
        for (TypeInfo info : index.types.values()) {
            for (List<TypeInfo.MethodInfo> methods : info.methods.values()) {
                for (TypeInfo.MethodInfo m : methods) {
                    if (m.isConstructor) {
                        continue;
                    }
                    for (TypeInfo parent : parents(resolvedResolver, info)) {
                        List<TypeInfo.MethodInfo> candidates = parent.methods.get(m.name);
                        if (candidates == null) {
                            continue;
                        }
                        for (TypeInfo.MethodInfo c : candidates) {
                            if (c.paramTypes.size() != m.paramTypes.size()) {
                                continue;
                            }
                            com.geek.association.model.Edge e = new com.geek.association.model.Edge();
                            e.from = m.key;
                            e.to = c.key;
                            e.relation = "OVERRIDES";
                            e.filePath = info.filePath;
                            e.line = m.line;
                            e.detail = parent.qualifiedName;
                            e.confidence = 0.9;
                            index.addEdge(e);
                        }
                    }
                }
            }
        }
    }

    private List<TypeInfo> parents(TypeResolver resolver, TypeInfo info) {
        List<TypeInfo> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> queue = new ArrayList<>();
        if (info.superclass != null) {
            queue.add(info.superclass);
        }
        queue.addAll(info.interfaces);
        while (!queue.isEmpty()) {
            String qn = queue.remove(0);
            if (!seen.add(qn)) {
                continue;
            }
            TypeInfo t = index.types.get(qn);
            if (t == null) {
                continue;
            }
            out.add(t);
            if (t.superclass != null) {
                queue.add(t.superclass);
            }
            queue.addAll(t.interfaces);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Normalised branch conditions and call expressions of one method body. */
    public static class ConditionFingerprint {
        public final java.util.List<String> conditions = new ArrayList<>();
        public final java.util.Set<String> calls = new java.util.LinkedHashSet<>();
        public String hash = "";

        /**
         * 一次遍历收集所有条件与调用名。
         *
         * 原来对同一个方法体做了 7 次 findAll（If/While/For/Conditional/Switch/Return/MethodCall），
         * 而条件表达式本身又各 toString() 一次 —— pretty-print 是这里最贵的一步。
         * 遍历顺序仍是 findAll 的深度优先顺序，所以 conditions 的排列顺序与原来一致，
         * conditionsHash（对外报告里用于判断「条件是否变了」）因此不会变。
         */
        public static ConditionFingerprint of(com.github.javaparser.ast.Node body) {
            ConditionFingerprint fp = new ConditionFingerprint();
            body.walk(node -> {
                if (node instanceof com.github.javaparser.ast.stmt.IfStmt ifStmt) {
                    fp.conditions.add("if(" + normalize(ifStmt.getCondition().toString()) + ")");
                } else if (node instanceof com.github.javaparser.ast.stmt.WhileStmt w) {
                    fp.conditions.add("while(" + normalize(w.getCondition().toString()) + ")");
                } else if (node instanceof com.github.javaparser.ast.stmt.ForStmt f) {
                    f.getCompare().ifPresent(c -> fp.conditions.add("for(" + normalize(c.toString()) + ")"));
                } else if (node instanceof com.github.javaparser.ast.expr.ConditionalExpr ce) {
                    fp.conditions.add("?(" + normalize(ce.getCondition().toString()) + ")");
                } else if (node instanceof com.github.javaparser.ast.stmt.SwitchStmt sw) {
                    fp.conditions.add("switch(" + normalize(sw.getSelector().toString()) + ")");
                } else if (node instanceof com.github.javaparser.ast.stmt.ReturnStmt ret) {
                    ret.getExpression().ifPresent(expr -> {
                        String text = normalize(expr.toString());
                        if (text.contains("&&") || text.contains("||") || text.contains("==") || text.contains("!=")
                                || text.contains("<") || text.contains(">") || text.contains("instanceof")
                                || text.contains("?")) {
                            fp.conditions.add("return(" + text + ")");
                        }
                    });
                } else if (node instanceof com.github.javaparser.ast.expr.MethodCallExpr call) {
                    fp.calls.add(call.getNameAsString());
                }
            });
            fp.hash = Hashes.sha1(String.join(";", fp.conditions));
            return fp;
        }
    }

    /**
     * normalize 是整条索引流水线上调用最频繁的字符串操作（每个类体 hash、每个方法体 hash、
     * 每个条件表达式都要过一遍）。原来用 String.replaceAll 每次调用都重新编译一遍正则，
     * 这里改成预编译的 Pattern —— 匹配语义与替换结果完全相同。
     */
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    public static String typeKey(String modulePath, String qualified) {
        return (modulePath == null || modulePath.isBlank() ? "" : modulePath) + "::" + qualified;
    }

    public static String methodKey(String modulePath, String owner, String name, List<String> params) {
        return typeKey(modulePath, owner) + "#" + name + "(" + String.join(",", params) + ")";
    }

    public static String fieldKey(String modulePath, String owner, String name) {
        return typeKey(modulePath, owner) + "#" + name;
    }

    public static Set<String> annotationNames(NodeList<AnnotationExpr> annotations) {
        Set<String> out = new LinkedHashSet<>();
        for (AnnotationExpr a : annotations) {
            String name = a.getNameAsString();
            int idx = name.lastIndexOf('.');
            out.add(idx < 0 ? name : name.substring(idx + 1));
        }
        return out;
    }

    public static String annotationValue(AnnotationExpr a) {
        if (a instanceof SingleMemberAnnotationExpr sm) {
            return stripQuotes(sm.getMemberValue().toString());
        }
        if (a instanceof NormalAnnotationExpr na) {
            for (com.github.javaparser.ast.expr.MemberValuePair pair : na.getPairs()) {
                if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                    return stripQuotes(pair.getValue().toString());
                }
            }
        }
        return "";
    }

    public static String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.replace("{\"", "").replace("\"}", "").replace("\"", "").trim();
    }

    public static boolean isPublic(Set<String> modifiers) {
        return modifiers.contains("public");
    }

    public static boolean isPrivate(Set<String> modifiers) {
        return modifiers.contains("private");
    }

    public static boolean isStatic(Set<String> modifiers) {
        return modifiers.contains("static");
    }

    public static boolean hasAnnotation(Set<String> annotations, String... names) {
        for (String n : names) {
            if (annotations.contains(n)) {
                return true;
            }
        }
        return false;
    }

    public static String nodeText(Node node) {
        return node == null ? "" : normalize(node.toString());
    }
}
