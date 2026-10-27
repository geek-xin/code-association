package com.geek.association.parse.messaging;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.parse.TypeResolver;
import com.geek.association.source.FileSource;
import com.geek.association.util.Hashes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared vocabulary for the three messaging adapters.
 *
 * Kafka, RabbitMQ and RocketMQ name their concepts differently, but impact propagation only needs
 * a stable route plus the producer/consumer symbols attached to it. Keeping that normalisation in
 * one place is what prevents three divergent scoring models.
 */
public final class MessagingSupport {

    private static final Pattern STRING_LITERAL = Pattern.compile("^\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern STATIC_STRING = Pattern.compile(
            "(?s)(?:static\\s+final|final\\s+static)\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern CONFIG_PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    private final FileSource source;
    private final CodeIndex index;
    private final TypeResolver resolver;
    private final Map<String, Map<String, String>> constantsByFile = new LinkedHashMap<>();
    /**
     * 裸常量名 -> (值 -> 出现文件数)。
     *
     * resolveConstant 在本地文件里查不到时，需要判断"这个名字在别的文件里是否唯一"。
     * 原先每个未命中都要遍历所有文件的常量表，代价 O(文件数 × 常量数)，
     * 而它在每个 topic/queue/routingKey 表达式上都会被调用一次。
     * 常量表在构造后不再变化，所以建一次索引、之后 O(1) 查询即可；
     * 用 "值 -> 次数" 而不是 "值 -> 第一个值"，是为了在多个不同值同名时依然返回 null（保持原语义）。
     */
    private final Map<String, Map<String, Integer>> constantsByName = new LinkedHashMap<>();
    private final Map<String, String> configValues = new LinkedHashMap<>();

    public MessagingSupport(FileSource source, CodeIndex index) {
        this.source = source;
        this.index = index;
        this.resolver = new TypeResolver(index);
        collectConstants();
        collectConfigValues();
    }

    public FileSource source() {
        return source;
    }

    public CodeIndex index() {
        return index;
    }

    public TypeResolver resolver() {
        return resolver;
    }

    public Sym symbol(String key) {
        return index.symbol(key);
    }

    public Sym ensureSymbol(String key, String type, String qualifiedName, String module,
                            String file, int line) {
        Sym existing = index.symbol(key);
        if (existing != null) {
            return existing;
        }
        Sym sym = new Sym();
        sym.key = key;
        sym.type = type;
        sym.qualifiedName = qualifiedName;
        sym.simpleName = qualifiedName;
        sym.modulePath = module;
        sym.filePath = file;
        sym.startLine = line;
        sym.endLine = line;
        sym.signatureHash = Hashes.sha1(type + ":" + qualifiedName);
        sym.bodyHash = sym.signatureHash;
        index.addSymbol(sym);
        return sym;
    }

    public void edge(String from, String to, String relation, String file, int line,
                     double confidence, String detail) {
        Edge e = new Edge();
        e.from = from;
        e.to = to;
        e.relation = relation;
        e.filePath = file;
        e.line = line;
        e.confidence = confidence;
        e.detail = detail;
        index.addEdge(e);
    }

    public String moduleOf(String file) {
        return index.fileToModule.getOrDefault(file, "");
    }

    public String topicKey(String middleware, String topic) {
        return "mq:" + middleware + ":topic:" + normalizeIdentity(topic);
    }

    public String routeKey(String middleware, String identity) {
        return "mq:" + middleware + ":route:" + normalizeIdentity(identity);
    }

    public String schemaKey(String middleware, String typeName) {
        return "mq:schema:" + middleware + ":" + normalizeIdentity(typeName);
    }

    /**
     * Resolves a destination expression to a literal, a project constant, a configuration value,
     * or {@code null} when it is genuinely dynamic. A null result is a blind spot, never a guess.
     */
    public String resolveDestination(String expression, String file) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        String value = expression.trim();
        Matcher literal = STRING_LITERAL.matcher(value);
        if (literal.matches()) {
            String inside = unescape(literal.group(1));
            if (CONFIG_PLACEHOLDER.matcher(inside).find()) {
                String resolved = resolvePlaceholder(inside);
                return resolved;
            }
            return inside;
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        String placeholderResolved = resolvePlaceholder(value);
        if (placeholderResolved != null) {
            return placeholderResolved;
        }
        String constant = resolveConstant(value, file);
        if (constant != null) {
            return constant;
        }
        return null;
    }

    private String resolvePlaceholder(String value) {
        Matcher placeholder = CONFIG_PLACEHOLDER.matcher(value);
        if (!placeholder.find()) {
            return null;
        }
        String key = placeholder.group(1).trim().toLowerCase(Locale.ROOT);
        String resolved = configValues.get(key);
        return resolved == null || resolved.isBlank() ? null : resolved;
    }

    public String resolveConstant(String name, String file) {
        String bare = name.trim();
        int dot = bare.lastIndexOf('.');
        if (dot >= 0) {
            bare = bare.substring(dot + 1);
        }
        Map<String, String> local = constantsByFile.get(file);
        if (local != null && local.containsKey(bare)) {
            return local.get(bare);
        }
        // 命中全局索引：只有恰好一个文件声明了这个裸名时才认，多个文件同名视为有歧义。
        Map<String, Integer> occurrences = constantsByName.get(bare);
        if (occurrences == null || occurrences.size() != 1) {
            return null;
        }
        Map.Entry<String, Integer> only = occurrences.entrySet().iterator().next();
        return only.getValue() == 1 ? only.getKey() : null;
    }

    public void unresolved(String file, int line, String expression, String reason) {
        CodeIndex.UnresolvedCall u = new CodeIndex.UnresolvedCall();
        u.filePath = file;
        u.line = line;
        u.expression = expression;
        u.reason = reason;
        u.modulePath = moduleOf(file);
        index.unresolved.add(u);
    }

    public TypeInfo resolveType(String raw, TypeInfo owner) {
        List<TypeInfo> found = resolver.resolve(raw, owner.imports, owner.packageName);
        return found.size() == 1 ? found.get(0) : null;
    }

    public String genericArgument(String raw) {
        if (raw == null) {
            return null;
        }
        int open = raw.indexOf('<');
        int close = raw.lastIndexOf('>');
        if (open < 0 || close <= open) {
            return null;
        }
        String inside = raw.substring(open + 1, close);
        List<String> args = splitGenericArguments(inside);
        if (args.size() < 2) {
            return args.isEmpty() ? null : args.get(0).trim();
        }
        return args.get(1).trim();
    }

    public String typeSimpleName(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.replaceAll("<.*>", "").replace("[]", "").trim();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private List<String> splitGenericArguments(String inside) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : inside.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString().trim());
        }
        return out;
    }

    private void collectConstants() {
        for (String file : source.files()) {
            if (!file.endsWith(".java")) {
                continue;
            }
            String content = source.read(file);
            if (content == null || !content.contains("String")) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            Matcher m = STATIC_STRING.matcher(content);
            while (m.find()) {
                values.put(m.group(1), m.group(2));
            }
            if (!values.isEmpty()) {
                constantsByFile.put(file, values);
                for (Map.Entry<String, String> e : values.entrySet()) {
                    constantsByName.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>())
                            .merge(e.getValue(), 1, Integer::sum);
                }
            }
        }
    }

    private void collectConfigValues() {
        for (String file : source.files()) {
            String lower = file.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties"))) {
                continue;
            }
            String content = source.read(file);
            if (content == null) {
                continue;
            }
            String[] lines = content.split("\\R", -1);
            List<Integer> indentStack = new ArrayList<>();
            List<String> nameStack = new ArrayList<>();
            for (String raw : lines) {
                if (raw.isBlank() || raw.trim().startsWith("#") || raw.trim().startsWith("---")) {
                    continue;
                }
                int indent = 0;
                while (indent < raw.length() && raw.charAt(indent) == ' ') {
                    indent++;
                }
                String line = raw.trim();
                int colon = line.indexOf(':');
                if (colon < 0) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        configValues.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                                line.substring(eq + 1).trim());
                    }
                    continue;
                }
                while (!indentStack.isEmpty() && indentStack.get(indentStack.size() - 1) >= indent) {
                    indentStack.remove(indentStack.size() - 1);
                    nameStack.remove(nameStack.size() - 1);
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                StringBuilder key = new StringBuilder();
                for (String parent : nameStack) {
                    key.append(parent).append('.');
                }
                key.append(name);
                if (value.isEmpty()) {
                    indentStack.add(indent);
                    nameStack.add(name);
                } else {
                    configValues.put(key.toString().toLowerCase(Locale.ROOT), cleanConfigValue(value));
                }
            }
        }
    }

    private static String normalizeIdentity(String value) {
        return value == null ? "" : value.trim().replace('\\', '/');
    }

    private static String cleanConfigValue(String value) {
        String v = value == null ? "" : value.trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t");
    }
}
