package com.geek.association.parse;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.source.FileSource;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves every method call, object creation and type reference inside method bodies and records
 * how the callee's return value is consumed (condition, assignment, argument, return, ignored, log).
 *
 * The resolver is deliberately conservative: ambiguity produces several edges with lower confidence
 * instead of one guessed edge, and anything it cannot resolve is reported as an unresolved call so
 * the final report can state its blind spots.
 */
public class CallGraphBuilder {

    private static final Set<String> LOGGER_NAMES = Set.of("log", "logger", "LOG", "LOGGER");
    private static final Set<String> LOGGER_METHODS = Set.of(
            "info", "debug", "warn", "error", "trace", "log", "println", "printf");

    private final CodeIndex index;
    private final MavenParser maven;
    private final Map<String, CompilationUnit> units;
    private final TypeResolver resolver;
    private final Map<String, List<TypeInfo>> implCache = new HashMap<>();
    /** Every method name declared anywhere in the project - used to tell a real blind spot from a JDK call. */
    private final Set<String> projectMethodNames = new HashSet<>();

    public CallGraphBuilder(FileSource source, CodeIndex index, MavenParser maven,
                            Map<String, CompilationUnit> units, JavaParser parser) {
        this.index = index;
        this.maven = maven;
        this.units = units;
        this.resolver = new TypeResolver(index);
    }

    public void build() {
        for (TypeInfo type : index.types.values()) {
            projectMethodNames.addAll(type.methods.keySet());
        }
        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String file = entry.getKey();
            CompilationUnit cu = entry.getValue();
            for (TypeDeclaration<?> type : cu.getTypes()) {
                walkType(type, file);
            }
        }
    }

    private void walkType(TypeDeclaration<?> type, String file) {
        String pkg = units.get(file).getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String qualified = qualifiedName(type, pkg, null);
        TypeInfo info = index.types.get(qualified);
        if (info != null) {
            recordTypeReferences(info);
        }
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof MethodDeclaration md) {
                analyzeMethod(info, md.getNameAsString(), md.getParameters(), md.getBody().orElse(null), file, false);
            } else if (member instanceof ConstructorDeclaration cd) {
                analyzeMethod(info, "<init>", cd.getParameters(), cd.getBody(), file, true);
            } else if (member instanceof TypeDeclaration<?> nested) {
                walkType(nested, file);
            } else if (member instanceof FieldDeclaration fd && info != null) {
                int line = fd.getBegin().map(p -> p.line).orElse(0);
                for (VariableDeclarator v : fd.getVariables()) {
                    v.getInitializer().ifPresent(init -> {
                        Edge e = typeRefEdge(info.key, init, info, file, line);
                        if (e != null) {
                            index.addEdge(e);
                        }
                    });
                }
            }
        }
    }

    private String qualifiedName(TypeDeclaration<?> type, String pkg, String outer) {
        String simple = type.getNameAsString();
        if (outer != null) {
            return outer + "." + simple;
        }
        return pkg.isBlank() ? simple : pkg + "." + simple;
    }

    /** Field types, parameter types, return types and thrown exceptions create type references. */
    private void recordTypeReferences(TypeInfo info) {
        for (TypeInfo.FieldInfo field : info.fields.values()) {
            addTypeRef(info, field.type, info.filePath, field.line, "field " + field.name);
        }
        for (List<TypeInfo.MethodInfo> methods : info.methods.values()) {
            for (TypeInfo.MethodInfo m : methods) {
                addTypeRef(info, m.returnType, info.filePath, m.line, "return of " + m.name);
                for (String p : m.paramTypes) {
                    addTypeRef(info, p, info.filePath, m.line, "parameter of " + m.name);
                }
            }
        }
        if (info.rawSuperclass != null) {
            addTypeRef(info, info.rawSuperclass, info.filePath, info.startLine, "extends");
        }
        for (String i : info.rawInterfaces) {
            addTypeRef(info, i, info.filePath, info.startLine, "implements");
        }
    }

    private void addTypeRef(TypeInfo owner, String typeName, String file, int line, String why) {
        if (typeName == null || typeName.isBlank()) {
            return;
        }
        List<TypeInfo> targets = resolver.resolve(typeName, owner.imports, owner.packageName);
        for (TypeInfo t : targets) {
            if (t.qualifiedName.equals(owner.qualifiedName)) {
                continue;
            }
            Edge e = new Edge();
            e.from = owner.key;
            e.to = t.key;
            e.relation = "REFERENCES_TYPE";
            e.filePath = file;
            e.line = line;
            e.confidence = targets.size() > 1 ? 0.6 : 0.85;
            e.detail = why + " : " + typeName;
            index.addEdge(e);
        }
    }

    private void analyzeMethod(TypeInfo owner, String methodName, NodeList<Parameter> params,
                               BlockStmt block, String file, boolean constructor) {
        if (owner == null) {
            return;
        }
        List<String> paramTypes = new ArrayList<>();
        for (Parameter p : params) {
            paramTypes.add(p.getType().asString());
        }
        String key = JavaIndexer.methodKey(owner.modulePath, owner.qualifiedName, methodName, paramTypes);
        Sym methodSym = index.symbol(key);
        if (methodSym == null) {
            return;
        }
        Scope scope = new Scope(owner, file, key);
        for (Parameter p : params) {
            scope.vars.put(p.getNameAsString(), p.getType().asString());
        }
        if (block == null) {
            return;
        }
        for (VariableDeclarator v : block.findAll(VariableDeclarator.class)) {
            scope.vars.put(v.getNameAsString(), v.getType().asString());
        }
        for (MethodCallExpr call : block.findAll(MethodCallExpr.class)) {
            resolveCall(call, scope, methodSym);
        }
        for (ObjectCreationExpr oce : block.findAll(ObjectCreationExpr.class)) {
            resolveCreation(oce, scope, methodSym);
        }
        for (MethodReferenceExpr ref : block.findAll(MethodReferenceExpr.class)) {
            resolveMethodReference(ref, scope, methodSym);
        }
        refineUsage(block, methodSym);
    }

    // ------------------------------------------------------------------ calls

    private void resolveCall(MethodCallExpr call, Scope scope, Sym caller) {
        String name = call.getNameAsString();
        int line = call.getBegin().map(p -> p.line).orElse(0);
        List<TypeInfo> receivers = receiverTypes(call, scope, 0);
        String usage = usageOf(call);
        String assignedVar = assignedVariable(call);
        if (receivers.isEmpty()) {
            List<TypeInfo> global = globalCandidates(name);
            if (!global.isEmpty()) {
                addCallEdges(global, call, scope, caller, name, line, usage, assignedVar, 0.5);
            } else {
                recordUnresolved(scope.file, line, call.toString(), "receiver type could not be resolved");
            }
            return;
        }
        double base = receivers.size() > 1 ? 0.6 : 0.9;
        addCallEdges(receivers, call, scope, caller, name, line, usage, assignedVar, base);
    }

    private void addCallEdges(List<TypeInfo> receivers, MethodCallExpr call, Scope scope, Sym caller,
                              String name, int line, String usage, String assignedVar, double baseConfidence) {
        boolean resolvedAny = false;
        for (TypeInfo receiver : receivers) {
            List<TypeInfo> targets = new ArrayList<>();
            targets.add(receiver);
            targets.addAll(parentTypes(receiver));
            List<TypeInfo.MethodInfo> matches = new ArrayList<>();
            for (TypeInfo t : targets) {
                List<TypeInfo.MethodInfo> methods = t.methods.get(name);
                if (methods == null) {
                    continue;
                }
                for (TypeInfo.MethodInfo m : methods) {
                    if (m.paramTypes.size() == call.getArguments().size()) {
                        matches.add(m);
                    }
                }
                if (!matches.isEmpty()) {
                    break;
                }
            }
            if (matches.isEmpty()) {
                continue;
            }
            resolvedAny = true;
            double confidence = matches.size() > 1 ? Math.min(baseConfidence, 0.6) : baseConfidence;
            for (TypeInfo.MethodInfo m : matches) {
                Edge e = new Edge();
                e.from = caller.key;
                e.to = m.key;
                e.relation = "CALLS";
                e.filePath = scope.file;
                e.line = line;
                e.usage = usage;
                e.returnValueUsed = isValueUsed(usage);
                e.assignedVar = assignedVar;
                e.confidence = confidence;
                e.detail = shorten(call.toString(), 240);
                index.addEdge(e);
                for (TypeInfo impl : implementationsOf(m.key)) {
                    if (impl.qualifiedName.equals(m.owner)) {
                        continue;
                    }
                    Edge ie = new Edge();
                    ie.from = caller.key;
                    ie.to = JavaIndexer.methodKey(impl.modulePath, impl.qualifiedName, name, m.paramTypes);
                    ie.relation = "CALLS";
                    ie.filePath = scope.file;
                    ie.line = line;
                    ie.usage = usage;
                    ie.returnValueUsed = e.returnValueUsed;
                    ie.assignedVar = assignedVar;
                    ie.confidence = Math.min(confidence, 0.7);
                    ie.evidence = "DISPATCH";
                    ie.detail = "dispatch of " + m.owner + "#" + name;
                    index.addEdge(ie);
                }
            }
        }
        if (!resolvedAny) {
            recordUnresolved(scope.file, line, call.toString(),
                    "no matching method " + name + "/" + call.getArguments().size()
                            + " on " + receivers.get(0).qualifiedName);
        }
    }

    private void resolveCreation(ObjectCreationExpr oce, Scope scope, Sym caller) {
        List<TypeInfo> types = resolver.resolve(oce.getType().asString(), scope.owner.imports, scope.owner.packageName);
        int line = oce.getBegin().map(p -> p.line).orElse(0);
        String usage = usageOf(oce);
        if (types.isEmpty()) {
            recordUnresolved(scope.file, line, "new " + oce.getTypeAsString(), "constructor type unresolved");
            return;
        }
        for (TypeInfo t : types) {
            List<TypeInfo.MethodInfo> ctors = t.methods.get("<init>");
            boolean matched = false;
            if (ctors != null) {
                for (TypeInfo.MethodInfo c : ctors) {
                    if (c.paramTypes.size() == oce.getArguments().size()) {
                        Edge e = new Edge();
                        e.from = caller.key;
                        e.to = c.key;
                        e.relation = "INSTANTIATES";
                        e.filePath = scope.file;
                        e.line = line;
                        e.usage = usage;
                        e.confidence = 0.85;
                        e.detail = "new " + t.simpleName;
                        index.addEdge(e);
                        matched = true;
                    }
                }
            }
            if (!matched) {
                Edge e = new Edge();
                e.from = caller.key;
                e.to = t.key;
                e.relation = "INSTANTIATES";
                e.filePath = scope.file;
                e.line = line;
                e.confidence = 0.7;
                e.detail = "new " + t.simpleName + " (no matching constructor)";
                index.addEdge(e);
            }
        }
    }

    private void resolveMethodReference(MethodReferenceExpr ref, Scope scope, Sym caller) {
        String name = ref.getIdentifier();
        int line = ref.getBegin().map(p -> p.line).orElse(0);
        List<TypeInfo> types = new ArrayList<>();
        if (ref.getScope() instanceof NameExpr ne) {
            types = resolver.resolve(ne.getNameAsString(), scope.owner.imports, scope.owner.packageName);
        } else if (ref.getScope() instanceof ThisExpr) {
            types.add(scope.owner);
        }
        for (TypeInfo t : types) {
            List<TypeInfo.MethodInfo> ms = t.methods.get(name);
            if (ms == null) {
                continue;
            }
            for (TypeInfo.MethodInfo m : ms) {
                Edge e = new Edge();
                e.from = caller.key;
                e.to = m.key;
                e.relation = "CALLS";
                e.filePath = scope.file;
                e.line = line;
                e.confidence = 0.6;
                e.evidence = "METHOD_REFERENCE";
                e.detail = shorten(ref.toString(), 200);
                index.addEdge(e);
            }
        }
    }

    private Edge typeRefEdge(String fromKey, Expression expr, TypeInfo scope, String file, int line) {
        if (expr instanceof ObjectCreationExpr oce) {
            List<TypeInfo> types = resolver.resolve(oce.getType().asString(), scope.imports, scope.packageName);
            if (!types.isEmpty()) {
                Edge e = new Edge();
                e.from = fromKey;
                e.to = types.get(0).key;
                e.relation = "REFERENCES_TYPE";
                e.filePath = file;
                e.line = line;
                e.confidence = 0.85;
                e.detail = oce.getTypeAsString();
                return e;
            }
        }
        return null;
    }

    // --------------------------------------------------------------- receiver

    private List<TypeInfo> receiverTypes(MethodCallExpr call, Scope scope, int depth) {
        if (depth > 3) {
            return List.of();
        }
        Optional<Expression> scopeExpr = call.getScope();
        if (scopeExpr.isEmpty()) {
            return List.of(scope.owner);
        }
        Expression s = unwrap(scopeExpr.get());
        if (s instanceof ThisExpr) {
            return List.of(scope.owner);
        }
        if (s instanceof SuperExpr) {
            List<TypeInfo> out = new ArrayList<>();
            if (scope.owner.superclass != null) {
                TypeInfo t = index.types.get(scope.owner.superclass);
                if (t != null) {
                    out.add(t);
                }
            }
            for (String p : scope.owner.interfaces) {
                TypeInfo t = index.types.get(p);
                if (t != null) {
                    out.add(t);
                }
            }
            return out;
        }
        if (s instanceof NameExpr ne) {
            String name = ne.getNameAsString();
            String declared = scope.vars.get(name);
            if (declared != null) {
                List<TypeInfo> byVar = resolver.resolve(declared, scope.owner.imports, scope.owner.packageName);
                if (!byVar.isEmpty()) {
                    return byVar;
                }
            }
            TypeInfo.FieldInfo field = findField(scope.owner, name);
            if (field != null) {
                List<TypeInfo> byField = resolver.resolve(field.type, scope.owner.imports, scope.owner.packageName);
                if (!byField.isEmpty()) {
                    return byField;
                }
            }
            return resolver.resolve(name, scope.owner.imports, scope.owner.packageName);
        }
        if (s instanceof ObjectCreationExpr oce) {
            return resolver.resolve(oce.getType().asString(), scope.owner.imports, scope.owner.packageName);
        }
        if (s instanceof MethodCallExpr inner) {
            List<TypeInfo> out = new ArrayList<>();
            for (TypeInfo t : receiverTypes(inner, scope, depth + 1)) {
                for (TypeInfo ti : withParents(t)) {
                    List<TypeInfo.MethodInfo> ms = ti.methods.get(inner.getNameAsString());
                    if (ms == null) {
                        continue;
                    }
                    for (TypeInfo.MethodInfo m : ms) {
                        if (m.paramTypes.size() != inner.getArguments().size()) {
                            continue;
                        }
                        out.addAll(resolver.resolve(m.returnType, t.imports, t.packageName));
                    }
                }
            }
            return out;
        }
        if (s instanceof FieldAccessExpr fa) {
            for (TypeInfo t : resolver.resolve(fa.getScope().toString(), scope.owner.imports, scope.owner.packageName)) {
                TypeInfo.FieldInfo f = findField(t, fa.getNameAsString());
                if (f != null) {
                    List<TypeInfo> out = resolver.resolve(f.type, t.imports, t.packageName);
                    if (!out.isEmpty()) {
                        return out;
                    }
                }
            }
            return List.of();
        }
        if (s instanceof ConditionalExpr ce) {
            List<TypeInfo> out = new ArrayList<>();
            out.addAll(receiverTypesOf(ce.getThenExpr(), scope, depth));
            out.addAll(receiverTypesOf(ce.getElseExpr(), scope, depth));
            return out;
        }
        if (s instanceof com.github.javaparser.ast.expr.CastExpr cast) {
            return resolver.resolve(cast.getType().asString(), scope.owner.imports, scope.owner.packageName);
        }
        if (s instanceof NameExpr) {
            return List.of();
        }
        return List.of();
    }

    private List<TypeInfo> receiverTypesOf(Expression expr, Scope scope, int depth) {
        if (expr instanceof MethodCallExpr mc) {
            return receiverTypes(mc, scope, depth + 1);
        }
        return List.of();
    }

    private Expression unwrap(Expression e) {
        Expression cur = e;
        while (cur instanceof EnclosedExpr en) {
            cur = en.getInner();
        }
        return cur;
    }

    private TypeInfo.FieldInfo findField(TypeInfo type, String name) {
        TypeInfo t = type;
        Set<String> seen = new HashSet<>();
        while (t != null && seen.add(t.qualifiedName)) {
            TypeInfo.FieldInfo f = t.fields.get(name);
            if (f != null) {
                return f;
            }
            t = t.superclass == null ? null : index.types.get(t.superclass);
        }
        return null;
    }

    private List<TypeInfo> parentTypes(TypeInfo info) {
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

    private List<TypeInfo> withParents(TypeInfo info) {
        List<TypeInfo> out = new ArrayList<>();
        out.add(info);
        out.addAll(parentTypes(info));
        return out;
    }

    private List<TypeInfo> implementationsOf(String methodKey) {
        List<TypeInfo> cached = implCache.get(methodKey);
        if (cached != null) {
            return cached;
        }
        List<TypeInfo> out = new ArrayList<>();
        for (Edge e : index.incoming.getOrDefault(methodKey, List.of())) {
            if (!"OVERRIDES".equals(e.relation)) {
                continue;
            }
            Sym impl = index.symbol(e.from);
            if (impl == null || impl.ownerKey == null) {
                continue;
            }
            for (TypeInfo t : index.types.values()) {
                if (t.key.equals(impl.ownerKey)) {
                    out.add(t);
                    break;
                }
            }
        }
        implCache.put(methodKey, out);
        return out;
    }

    private List<TypeInfo> globalCandidates(String name) {
        List<TypeInfo> out = new ArrayList<>();
        for (TypeInfo t : index.types.values()) {
            List<TypeInfo.MethodInfo> ms = t.methods.get(name);
            if (ms != null && !ms.isEmpty() && JavaIndexer.isStatic(ms.get(0).modifiers)) {
                out.add(t);
            }
        }
        return out.size() > 3 ? List.of() : out;
    }

    // ------------------------------------------------------------------ usage

    private boolean isValueUsed(String usage) {
        return !("IGNORED".equals(usage) || "LOG_ONLY".equals(usage) || "NONE".equals(usage));
    }

    private String usageOf(Expression call) {
        Optional<Node> parent = call.getParentNode();
        if (parent.isEmpty()) {
            return "NONE";
        }
        Node p = parent.get();
        if (p instanceof ExpressionStmt) {
            return isLogging(call) ? "LOG_ONLY" : "IGNORED";
        }
        if (p instanceof IfStmt ifStmt) {
            return contains(ifStmt.getCondition(), call) ? "CONDITION" : "IGNORED";
        }
        if (p instanceof WhileStmt w) {
            return contains(w.getCondition(), call) ? "CONDITION" : "IGNORED";
        }
        if (p instanceof DoStmt d) {
            return contains(d.getCondition(), call) ? "CONDITION" : "IGNORED";
        }
        if (p instanceof ForStmt f) {
            return f.getCompare().map(c -> contains(c, call)).orElse(false) ? "CONDITION" : "IGNORED";
        }
        if (p instanceof SwitchStmt) {
            return "CONDITION";
        }
        if (p instanceof ConditionalExpr ce) {
            return ce.getCondition() == call ? "CONDITION" : "ASSIGNMENT";
        }
        if (p instanceof BinaryExpr be) {
            return isConditionalContext(be) ? "CONDITION" : "ARGUMENT";
        }
        if (p instanceof UnaryExpr ue) {
            return isConditionalContext(ue) ? "CONDITION" : "ASSIGNMENT";
        }
        if (p instanceof VariableDeclarator || p instanceof AssignExpr) {
            return "ASSIGNMENT";
        }
        if (p instanceof ReturnStmt) {
            return "RETURN";
        }
        if (p instanceof ThrowStmt) {
            return "ARGUMENT";
        }
        if (p instanceof MethodCallExpr || p instanceof ObjectCreationExpr || p instanceof LambdaExpr) {
            return "ARGUMENT";
        }
        return "NONE";
    }

    private boolean contains(Node container, Node node) {
        if (container == null || node == null) {
            return false;
        }
        Node cur = node;
        while (cur != null) {
            if (cur == container) {
                return true;
            }
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean isConditionalContext(Node node) {
        Node cur = node;
        while (cur.getParentNode().isPresent()) {
            Node parent = cur.getParentNode().get();
            if (parent instanceof IfStmt || parent instanceof WhileStmt || parent instanceof DoStmt
                    || parent instanceof ForStmt || parent instanceof ConditionalExpr) {
                return true;
            }
            if (parent instanceof ExpressionStmt || parent instanceof ReturnStmt || parent instanceof BlockStmt) {
                return false;
            }
            cur = parent;
        }
        return false;
    }

    private boolean isLogging(Expression call) {
        if (!(call instanceof MethodCallExpr mc)) {
            return false;
        }
        if (!LOGGER_METHODS.contains(mc.getNameAsString())) {
            return false;
        }
        if (mc.getScope().isEmpty()) {
            return false;
        }
        String scopeText = mc.getScope().get().toString();
        if (LOGGER_NAMES.contains(scopeText)) {
            return true;
        }
        String lower = scopeText.toLowerCase();
        return lower.endsWith("log") || lower.endsWith("logger");
    }

    private String assignedVariable(Expression expr) {
        Optional<Node> parent = expr.getParentNode();
        if (parent.isEmpty()) {
            return null;
        }
        Node p = parent.get();
        if (p instanceof VariableDeclarator v) {
            return v.getNameAsString();
        }
        if (p instanceof AssignExpr a) {
            return a.getTarget().toString();
        }
        return null;
    }

    /**
     * Refines the consumption of a callee's return value after the whole body is known:
     *
     *  - assigned to a variable that later appears in a branch condition  -> CONDITION (control dependency)
     *  - assigned to a variable that only ends up in a logging call        -> LOG_ONLY (weak impact)
     *  - assigned to a variable that is otherwise unused                   -> IGNORED
     */
    private void refineUsage(BlockStmt block, Sym caller) {
        Set<String> logVars = new LinkedHashSet<>();
        Set<String> usedVars = new LinkedHashSet<>();
        for (MethodCallExpr call : block.findAll(MethodCallExpr.class)) {
            Set<String> names = nameUsages(call);
            if (isLogging(call)) {
                logVars.addAll(names);
            } else {
                usedVars.addAll(names);
            }
        }
        for (Edge e : index.outgoing.getOrDefault(caller.key, List.of())) {
            if (!"CALLS".equals(e.relation) || e.assignedVar == null) {
                continue;
            }
            if (!logVars.contains(e.assignedVar)) {
                continue;
            }
            if (usedVars.contains(e.assignedVar)) {
                continue;
            }
            e.usage = "LOG_ONLY";
            e.returnValueUsed = false;
        }
        markFlowsIntoCondition(block, caller);
    }

    /**
     * If a callee's return value is stored in a variable that later appears inside a branch
     * condition, the call carries a control dependency: a behaviour change of the callee can
     * change which branch the caller takes.
     */
    private void markFlowsIntoCondition(BlockStmt block, Sym caller) {
        Set<String> conditionVars = new LinkedHashSet<>();
        for (IfStmt ifStmt : block.findAll(IfStmt.class)) {
            conditionVars.addAll(nameUsages(ifStmt.getCondition()));
        }
        for (WhileStmt w : block.findAll(WhileStmt.class)) {
            conditionVars.addAll(nameUsages(w.getCondition()));
        }
        for (DoStmt d : block.findAll(DoStmt.class)) {
            conditionVars.addAll(nameUsages(d.getCondition()));
        }
        for (ForStmt f : block.findAll(ForStmt.class)) {
            f.getCompare().ifPresent(c -> conditionVars.addAll(nameUsages(c)));
        }
        for (ConditionalExpr ce : block.findAll(ConditionalExpr.class)) {
            conditionVars.addAll(nameUsages(ce.getCondition()));
        }
        for (SwitchStmt sw : block.findAll(SwitchStmt.class)) {
            conditionVars.addAll(nameUsages(sw.getSelector()));
        }
        for (Edge e : index.outgoing.getOrDefault(caller.key, List.of())) {
            if (!"CALLS".equals(e.relation) || e.assignedVar == null) {
                continue;
            }
            if (conditionVars.contains(e.assignedVar)) {
                e.flowsIntoCondition = true;
                e.usage = "CONDITION";
                e.returnValueUsed = true;
            }
        }
    }

    private Set<String> nameUsages(Node node) {
        Set<String> out = new LinkedHashSet<>();
        if (node == null) {
            return out;
        }
        for (NameExpr n : node.findAll(NameExpr.class)) {
            out.add(n.getNameAsString());
        }
        return out;
    }

    /**
     * Records a call site only when it could plausibly be an in-project call.
     *
     * Calls into the JDK or a third party library (String.format, System.out.println, JUnit asserts,
     * Spring Data repositories...) are not blind spots of this analysis - they are simply outside
     * its scope. Recording them would bury the handful of sites that really matter.
     */
    private void recordUnresolved(String file, int line, String expr, String reason) {
        String methodName = expr.contains("(") ? expr.substring(0, expr.indexOf('(')) : expr;
        int dot = methodName.lastIndexOf('.');
        if (dot >= 0) {
            methodName = methodName.substring(dot + 1);
        }
        methodName = methodName.replaceAll("[^A-Za-z0-9_$]", "").trim();
        if (methodName.isEmpty() || !projectMethodNames.contains(methodName)) {
            return;
        }
        if (index.unresolved.size() >= 5000) {
            return;
        }
        CodeIndex.UnresolvedCall u = new CodeIndex.UnresolvedCall();
        u.filePath = file;
        u.line = line;
        u.expression = shorten(expr, 200);
        u.reason = reason;
        u.modulePath = maven.moduleOf(file);
        index.unresolved.add(u);
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Local variable / parameter type environment of one method body. */
    private static final class Scope {
        final TypeInfo owner;
        final String file;
        final String methodKey;
        final Map<String, String> vars = new LinkedHashMap<>();

        Scope(TypeInfo owner, String file, String methodKey) {
            this.owner = owner;
            this.file = file;
            this.methodKey = methodKey;
        }
    }
}