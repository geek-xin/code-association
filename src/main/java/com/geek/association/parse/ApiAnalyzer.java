package com.geek.association.parse;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.source.FileSource;
import com.geek.association.util.Hashes;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the HTTP contract layer: Spring MVC endpoints on the provider side and
 * RestTemplate / WebClient / Feign consumers on the caller side. Providers and consumers are
 * joined on the normalised path template, which is what makes an API change in one module
 * visible as an impact in another module or another repository.
 */
public class ApiAnalyzer {

        private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}]+}");
    /** A placeholder after normalizePath has run, i.e. the empty form {@code {}}. */
    private static final Pattern NORMALIZED_VARIABLE = Pattern.compile("\\{[^}]*}");
    private static final Pattern URL_LITERAL = Pattern.compile("\"(/[^\"\\s]*)\"");
    /** 请求体参数的类型：原先在 requestBodyType 里每次调用都 compile 一遍。 */
    private static final Pattern REQUEST_BODY_TYPE = Pattern.compile(
            "@RequestBody\\s+(?:final\\s+)?([A-Za-z_][\\w.<>,\\[\\] ?]*)");
    /** Feign 注解里的 path 属性。 */
    private static final Pattern FEIGN_PATH = Pattern.compile("path\\s*=\\s*\"([^\"]*)\"");
    /** OpenAPI 文档里的顶层路径行。 */
    private static final Pattern OPENAPI_PATH = Pattern.compile("^\\s{2}(/[^:\\s]+)\\s*:", Pattern.MULTILINE);

    private final FileSource source;
    private final CodeIndex index;
    private final MavenParser maven;
    /**
     * 建一次、全程复用。
     *
     * TypeResolver 的构造函数会遍历全部类型重建继承体系，代价 O(类型数)；
     * 而类型链接在每个 controller 方法的每个参数/返回值上都要解析一次名字。
     * 之前每次 addTypeLink 都 new 一个，等于把"每处类型链接"变成 O(类型数)，
     * 在模块代码量大时是明确的平方级开销。继承体系在一次分析内不会变，共享实例是安全的。
     */
    private final TypeResolver typeResolver;
    /** 同一文件的按方法片段都从这里取行数组，避免每个方法重新切分整个文件。 */
    private final SourceLines lines = new SourceLines();
    /**
     * 注解取值用的正则按注解名缓存：注解名只有有限几个（6 个 Spring mapping + FeignClient），
     * 而这段代码在"每个 controller 方法"上都会跑一次，原先每次都重新编译。
     */
    private final Map<String, Pattern> annotationValuePatterns = new HashMap<>();

    public ApiAnalyzer(FileSource source, CodeIndex index, MavenParser maven) {
        this.source = source;
        this.index = index;
        this.maven = maven;
        this.typeResolver = new TypeResolver(index);
    }

    public void analyze() {
        for (TypeInfo t : index.types.values()) {
            if (t.isInterface && t.annotations.contains("FeignClient")) {
                feignClient(t);
            } else if (JavaIndexer.hasAnnotation(t.annotations, "RestController", "Controller")) {
                springController(t);
            }
        }
        linkConsumers();
        linkOpenApi();
    }

    // ------------------------------------------------------------- providers

    private void springController(TypeInfo type) {
        String content = source.read(type.filePath);
        if (content == null) {
            return;
        }
        String basePath = "";
        for (String annotation : JavaIndexer.SPRING_MAPPING_ANNOTATIONS) {
            if (!type.annotations.contains(annotation)) {
                continue;
            }
            String value = JavaIndexer.stripQuotes(firstAnnotationValue(type.filePath, content, annotation, type.startLine));
            basePath = normalizePath(value);
            break;
        }
        for (List<TypeInfo.MethodInfo> methods : type.methods.values()) {
            for (TypeInfo.MethodInfo m : methods) {
                String mapping = null;
                for (String annotation : m.annotations) {
                    if (JavaIndexer.SPRING_MAPPING_ANNOTATIONS.contains(annotation)) {
                        mapping = annotation;
                        break;
                    }
                }
                if (mapping == null) {
                    continue;
                }
                String httpMethod = httpMethodOf(mapping);
                String path = normalizePath(JavaIndexer.stripQuotes(firstAnnotationValue(type.filePath, content, mapping, m.line)));
                String full = joinPath(basePath, path);
                Sym endpoint = endpointSymbol(httpMethod, full, type.modulePath, type.filePath, m.line);
                endpoint.meta.put("controller", type.qualifiedName);
                endpoint.meta.put("handler", m.name);
                endpoint.meta.put("responseType", m.returnType);
                endpoint.meta.put("declaredPath", path);
                endpoint.meta.put("basePath", basePath);
                endpoint.meta.put("requestType", requestBodyType(type.filePath, content, m.line));

                Edge exposes = new Edge();
                exposes.from = m.key;
                exposes.to = endpoint.key;
                exposes.relation = "EXPOSES_API";
                exposes.filePath = type.filePath;
                exposes.line = m.line;
                exposes.confidence = 0.95;
                exposes.detail = httpMethod + " " + full;
                index.addEdge(exposes);

                addTypeLink(m.key, m.returnType, type, m.line, "api response");
                for (String p : m.paramTypes) {
                    addTypeLink(m.key, p, type, m.line, "api parameter");
                }
            }
        }
    }

    /**
     * The request payload type of a handler: the parameter annotated with {@code @RequestBody}.
     * Path variables and query parameters are not part of the payload contract, so they are skipped.
     */
    private String requestBodyType(String filePath, String content, int nearLine) {
        String[] fileLines = lines.lines(filePath, content);
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, nearLine - 2); i < Math.min(fileLines.length, nearLine + 8); i++) {
            sb.append(fileLines[i]).append('\n');
        }
        Matcher m = REQUEST_BODY_TYPE.matcher(sb.toString());
        return m.find() ? m.group(1).trim() : "";
    }

    private String httpMethodOf(String annotation) {
        switch (annotation) {
            case "GetMapping":
                return "GET";
            case "PostMapping":
                return "POST";
            case "PutMapping":
                return "PUT";
            case "DeleteMapping":
                return "DELETE";
            case "PatchMapping":
                return "PATCH";
            default:
                return "ANY";
        }
    }

    // -------------------------------------------------------------- consumers

    private void feignClient(TypeInfo type) {
        String content = source.read(type.filePath);
        if (content == null) {
            return;
        }
        String name = firstAnnotationValue(type.filePath, content, "FeignClient", type.startLine);
        String base = "";
        Matcher m = FEIGN_PATH.matcher(name == null ? "" : name);
        if (m.find()) {
            base = m.group(1);
        }
        for (List<TypeInfo.MethodInfo> methods : type.methods.values()) {
            for (TypeInfo.MethodInfo method : methods) {
                String mapping = null;
                for (String a : method.annotations) {
                    if (JavaIndexer.SPRING_MAPPING_ANNOTATIONS.contains(a)) {
                        mapping = a;
                        break;
                    }
                }
                String httpMethod = mapping == null ? "ANY" : httpMethodOf(mapping);
                String path = mapping == null ? "" : JavaIndexer.stripQuotes(firstAnnotationValue(type.filePath, content, mapping, method.line));
                String full = joinPath(normalizePath(base), normalizePath(path));
                if (full.isEmpty() || full.equals("/")) {
                    continue;
                }
                Sym endpoint = endpointSymbol(httpMethod, full, null, null, 0);
                Edge e = new Edge();
                e.from = method.key;
                e.to = endpoint.key;
                e.relation = "CALLS_API";
                e.filePath = type.filePath;
                e.line = method.line;
                e.confidence = 0.85;
                e.detail = "Feign " + (name == null ? "" : name) + " " + httpMethod + " " + full;
                index.addEdge(e);
                addTypeLink(method.key, method.returnType, type, method.line, "feign response");
            }
        }
    }

    private void linkConsumers() {
        for (TypeInfo t : index.types.values()) {
            String content = source.read(t.filePath);
            if (content == null) {
                continue;
            }
            if (!(content.contains("RestTemplate") || content.contains("WebClient") || content.contains("HttpClient")
                    || content.contains("HttpRequest"))) {
                continue;
            }
            for (List<TypeInfo.MethodInfo> methods : t.methods.values()) {
                for (TypeInfo.MethodInfo m : methods) {
                    String body = methodBody(t.filePath, content, m.line);
                    if (body.isEmpty()) {
                        continue;
                    }
                    Matcher literal = URL_LITERAL.matcher(body);
                    while (literal.find()) {
                        String raw = literal.group(1);
                        if (raw.length() < 2) {
                            continue;
                        }
                        String path = normalizePath(raw);
                        if (path.isEmpty()) {
                            continue;
                        }
                        Sym endpoint = findEndpointByPath(path);
                        if (endpoint == null) {
                            // The endpoint is not declared in this repository, so it belongs to
                            // another program. Recording the call anyway is what lets a later
                            // cross-program comparison match this consumer against the provider.
                            endpoint = remoteEndpointSymbol(httpMethodOfLiteral(body), path);
                        }
                        Edge e = new Edge();
                        e.from = m.key;
                        e.to = endpoint.key;
                        e.relation = "CALLS_API";
                        e.filePath = t.filePath;
                        e.line = m.line;
                        e.confidence = path.contains("{}") ? 0.65 : 0.8;
                        e.detail = "HTTP client " + raw;
                        index.addEdge(e);
                    }
                }
            }
        }
    }

    private String methodBody(String filePath, String content, int startLine) {
        String[] fileLines = lines.lines(filePath, content);
        if (startLine <= 0 || startLine > fileLines.length) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean started = false;
        for (int i = startLine - 1; i < Math.min(fileLines.length, startLine + 80); i++) {
            String line = fileLines[i];
            sb.append(line).append('\n');
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    depth++;
                    started = true;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (started && depth <= 0) {
                break;
            }
        }
        return sb.toString();
    }

    private Sym findEndpointByPath(String path) {
        String normalized = normalizePath(path);
        Sym exact = index.symbol("api:" + normalized);
        if (exact != null) {
            return exact;
        }
        for (Sym sym : index.symbols.values()) {
            if (!"API_ENDPOINT".equals(sym.type)) {
                continue;
            }
            if (pathsMatch(sym.meta.getOrDefault("path", ""), normalized)) {
                return sym;
            }
        }
        return null;
    }

    /**
     * Whether a concrete client path addresses a declared endpoint template.
     *
     * Three cases matter, because client code rarely spells the template out:
     * an exact hit; a template variable filled in ({@code /users/42} vs {@code /users/{id}});
     * and a path built by concatenation, where only the prefix survives the literal scan
     * ({@code "/users/" + id} leaves {@code /users}, which still identifies the endpoint).
     */
    public static boolean pathsMatch(String template, String concrete) {
        if (template == null || concrete == null || template.isEmpty() || concrete.isEmpty()) {
            return false;
        }
        if (template.equals(concrete)) {
            return true;
        }
        String regex = NORMALIZED_VARIABLE.matcher(template).replaceAll("[^/]+");
        try {
            if (concrete.matches(regex)) {
                return true;
            }
        } catch (RuntimeException e) {
            return false;
        }
        // Concatenated path: the literal stops at the last complete segment.
        if (!concrete.equals("/") && template.startsWith(concrete + "/")) {
            String rest = template.substring(concrete.length() + 1);
            return NORMALIZED_VARIABLE.matcher(rest).matches() || !rest.contains("/");
        }
        return false;
    }

    // --------------------------------------------------------------- openapi

    private void linkOpenApi() {
        for (String file : source.files()) {
            String lower = file.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith("openapi.yaml") || lower.endsWith("openapi.yml") || lower.endsWith("openapi.json")
                    || lower.endsWith("swagger.yaml") || lower.endsWith("swagger.json") || lower.endsWith("swagger.yml"))) {
                continue;
            }
            String content = source.read(file);
            if (content == null) {
                continue;
            }
            Matcher m = OPENAPI_PATH.matcher(content);
            while (m.find()) {
                String path = normalizePath(m.group(1));
                Sym endpoint = endpointSymbol("ANY", path, maven.moduleOf(file), file, lineOf(content, m.start()));
                endpoint.meta.put("openapi", "true");
                endpoint.meta.put("specFile", file);
            }
        }
    }

    private void addTypeLink(String fromKey, String typeName, TypeInfo scope, int line, String why) {
        if (typeName == null || typeName.isBlank() || typeName.equals("void")) {
            return;
        }
        List<TypeInfo> types = typeResolver.resolve(TypeResolver.stripGenerics(typeName), scope.imports, scope.packageName);
        for (TypeInfo t : types) {
            Edge e = new Edge();
            e.from = fromKey;
            e.to = t.key;
            e.relation = "REFERENCES_TYPE";
            e.filePath = scope.filePath;
            e.line = line;
            e.confidence = 0.8;
            e.detail = why + " : " + typeName;
            index.addEdge(e);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * An endpoint this repository calls but does not declare.
     *
     * Marked {@code remote=true} so the report never presents it as a local module, while the
     * CALLS_API edge still exists for cross-program matching.
     */
    private Sym remoteEndpointSymbol(String httpMethod, String path) {
        String normalized = normalizePath(path);
        String key = "api:" + normalized;
        Sym existing = index.symbol(key);
        if (existing != null) {
            return existing;
        }
        Sym sym = new Sym();
        sym.key = key;
        sym.type = "API_ENDPOINT";
        sym.qualifiedName = httpMethod + " " + normalized;
        sym.simpleName = normalized;
        sym.signatureHash = Hashes.sha1(httpMethod + ":" + normalized);
        sym.bodyHash = sym.signatureHash;
        sym.meta.put("path", normalized);
        sym.meta.put("httpMethod", httpMethod);
        sym.meta.put("remote", "true");
        index.addSymbol(sym);
        return sym;
    }

    /** Best-effort HTTP verb of a client call, from the surrounding method text. */
    private String httpMethodOfLiteral(String body) {
        if (body.contains("postForObject") || body.contains("postForEntity") || body.contains(".post()")) {
            return "POST";
        }
        if (body.contains("put(") || body.contains(".put()")) {
            return "PUT";
        }
        if (body.contains("delete(") || body.contains(".delete()")) {
            return "DELETE";
        }
        return "GET";
    }

    public Sym endpointSymbol(String httpMethod, String path, String modulePath, String file, int line) {
        String normalized = normalizePath(path);
        String key = "api:" + normalized;
        Sym existing = index.symbol(key);
        if (existing != null) {
            String existingMethod = existing.meta.getOrDefault("httpMethod", "ANY");
            if (!existingMethod.equals(httpMethod) && !"ANY".equals(httpMethod) && !"ANY".equals(existingMethod)) {
                existing.meta.put("httpMethods", existingMethod + "," + httpMethod);
            }
            if (existing.filePath == null && file != null) {
                existing.filePath = file;
                existing.modulePath = modulePath;
                existing.startLine = line;
            }
            return existing;
        }
        Sym sym = new Sym();
        sym.key = key;
        sym.type = "API_ENDPOINT";
        sym.qualifiedName = httpMethod + " " + normalized;
        sym.simpleName = normalized;
        sym.modulePath = modulePath;
        sym.filePath = file;
        sym.startLine = line;
        sym.endLine = line;
        sym.signatureHash = Hashes.sha1(httpMethod + ":" + normalized);
        sym.bodyHash = sym.signatureHash;
        sym.meta.put("path", normalized);
        sym.meta.put("httpMethod", httpMethod);
        index.addSymbol(sym);
        return sym;
    }

    public static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        if (p.isEmpty()) {
            return "";
        }
        if (p.startsWith("http://") || p.startsWith("https://")) {
            int idx = p.indexOf('/', p.indexOf("//") + 2);
            p = idx < 0 ? "/" : p.substring(idx);
        }
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        p = PATH_VARIABLE.matcher(p).replaceAll("{}");
        p = p.replace('\\', '/').replaceAll("/{2,}", "/");
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private String joinPath(String base, String path) {
        String b = base == null ? "" : base;
        String p = path == null ? "" : path;
        if (b.isEmpty()) {
            return normalizePath(p);
        }
        if (p.isEmpty()) {
            return normalizePath(b);
        }
        return normalizePath(b + "/" + p);
    }

    private String firstAnnotationValue(String filePath, String content, String annotation, int nearLine) {
        String[] fileLines = lines.lines(filePath, content);
        int from = Math.max(0, nearLine - 4);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < Math.min(fileLines.length, nearLine + 6); i++) {
            sb.append(fileLines[i]).append('\n');
        }
        Matcher m = annotationValuePatterns
                .computeIfAbsent(annotation, a -> Pattern.compile("@" + a + "\\s*\\(([^)]*)\\)"))
                .matcher(sb.toString());
        return m.find() ? m.group(1) : "";
    }

    private int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}