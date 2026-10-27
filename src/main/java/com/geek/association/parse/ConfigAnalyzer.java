package com.geek.association.parse;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.source.FileSource;
import com.geek.association.util.Hashes;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the configuration layer: every key declared in application.yml / .properties becomes a
 * CONFIG_KEY node, and every placeholder read (Value, ConfigurationProperties, ConditionalOnProperty)
 * becomes a USES_CONFIG edge. A default value change is therefore traceable to the code it steers.
 */
public class ConfigAnalyzer {

    private static final Pattern VALUE_PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    private static final Pattern PROPERTY_LINE = Pattern.compile("^\\s*([A-Za-z0-9_.\\-]+)\\s*[=:]\\s*(.*)$", Pattern.MULTILINE);

    private final FileSource source;
    private final CodeIndex index;
    private final MavenParser maven;
    /** 同一文件的按方法片段都从这里取行数组，避免每个方法重新切分整个文件。 */
    private final SourceLines lines = new SourceLines();
    public final Map<String, String> declaredKeys = new LinkedHashMap<>();
    public final Map<String, String> keyValues = new LinkedHashMap<>();

    public ConfigAnalyzer(FileSource source, CodeIndex index, MavenParser maven) {
        this.source = source;
        this.index = index;
        this.maven = maven;
    }

    public void analyze() {
        parseConfigFiles();
        linkJavaUsage();
    }

    private void parseConfigFiles() {
        for (String file : source.files()) {
            String lower = file.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties"))) {
                continue;
            }
            if (lower.contains("/node_modules/") || lower.contains("/target/")) {
                continue;
            }
            String content = source.read(file);
            if (content == null) {
                continue;
            }
            if (lower.endsWith(".properties")) {
                Matcher m = PROPERTY_LINE.matcher(content);
                while (m.find()) {
                    String key = m.group(1).trim();
                    if (key.startsWith("#") || key.startsWith("!")) {
                        continue;
                    }
                    declare(key, m.group(2).trim(), file, lineOf(content, m.start()));
                }
            } else {
                parseYaml(content, file);
            }
        }
    }

    /** Flattens a YAML document into dotted keys without pulling in a full YAML model. */
    private void parseYaml(String content, String file) {
        String[] lines = content.split("\\R", -1);
        Deque<Integer> indentStack = new ArrayDeque<>();
        Deque<String> nameStack = new ArrayDeque<>();
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.isBlank() || raw.trim().startsWith("#") || raw.trim().startsWith("---")) {
                continue;
            }
            int indent = 0;
            while (indent < raw.length() && raw.charAt(indent) == ' ') {
                indent++;
            }
            String line = raw.trim();
            while (!indentStack.isEmpty() && indentStack.peek() >= indent) {
                indentStack.pop();
                nameStack.pop();
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            // nameStack is a stack: iterate from the outermost parent down to the direct parent
            StringBuilder keyBuilder = new StringBuilder();
            java.util.Iterator<String> it = nameStack.descendingIterator();
            while (it.hasNext()) {
                keyBuilder.append(it.next()).append('.');
            }
            keyBuilder.append(name);
            String key = keyBuilder.toString();
            if (value.isEmpty()) {
                indentStack.push(indent);
                nameStack.push(name);
            } else {
                declare(key, value, file, i + 1);
            }
        }
    }

    private void declare(String key, String value, String file, int line) {
        String normalized = key.toLowerCase(Locale.ROOT);
        String modulePath = maven.moduleOf(file);
        String symKey = "config:" + normalized;
        Sym sym = index.symbol(symKey);
        if (sym == null) {
            sym = new Sym();
            sym.key = symKey;
            sym.type = "CONFIG_KEY";
            sym.qualifiedName = normalized;
            sym.simpleName = normalized;
            sym.modulePath = modulePath;
            sym.filePath = file;
            sym.startLine = line;
            sym.endLine = line;
            sym.signatureHash = Hashes.sha1(normalized + "=" + value);
            sym.bodyHash = sym.signatureHash;
            sym.meta.put("declared", "true");
            index.addSymbol(sym);
        }
        sym.meta.put("value", value);
        sym.meta.put("declaredIn", file);
        sym.meta.put("module", modulePath == null ? "" : modulePath);
        declaredKeys.put(normalized, value);
        keyValues.put(normalized, value);
    }

    private void linkJavaUsage() {
        Pattern valuePattern = Pattern.compile("@Value\\s*\\(\\s*\"([^\"]*)\"");
        for (TypeInfo t : index.types.values()) {
            String content = source.read(t.filePath);
            if (content == null || !content.contains("$")) {
                continue;
            }
            for (Map.Entry<String, List<TypeInfo.MethodInfo>> e : t.methods.entrySet()) {
                for (TypeInfo.MethodInfo m : e.getValue()) {
                    Matcher matcher = valuePattern.matcher(snippet(t.filePath, content, m.line, 12));
                    while (matcher.find()) {
                        for (String key : placeholders(matcher.group(1))) {
                            linkKey(m.key, key, t.filePath, m.line, "@Value");
                        }
                    }
                }
            }
            // @Value on a field is at least as common as on a constructor parameter, and the
            // field is what the code reads later; skipping it hid whole consumers from the graph.
            for (TypeInfo.FieldInfo f : t.fields.values()) {
                Matcher matcher = valuePattern.matcher(snippet(t.filePath, content, f.line, 4));
                while (matcher.find()) {
                    for (String key : placeholders(matcher.group(1))) {
                        linkKey(f.key == null ? t.key : f.key, key, t.filePath, f.line, "@Value");
                    }
                }
            }
            if (t.annotations.contains("ConfigurationProperties")) {
                String prefix = prefixOf(t.filePath, content, t.startLine);
                if (prefix != null) {
                    String normalized = prefix.toLowerCase(Locale.ROOT);
                    for (String key : declaredKeys.keySet()) {
                        if (key.startsWith(normalized + ".") || key.equals(normalized)) {
                            linkKey(t.key, key, t.filePath, t.startLine, "@ConfigurationProperties(" + prefix + ")");
                        }
                    }
                    Sym holder = index.symbol("config:" + normalized);
                    if (holder == null) {
                        holder = new Sym();
                        holder.key = "config:" + normalized;
                        holder.type = "CONFIG_KEY";
                        holder.qualifiedName = normalized;
                        holder.simpleName = normalized;
                        holder.modulePath = t.modulePath;
                        holder.filePath = t.filePath;
                        holder.startLine = t.startLine;
                        holder.endLine = t.startLine;
                        holder.meta.put("declared", "false");
                        index.addSymbol(holder);
                    }
                    holder.meta.put("prefix", "true");
                    Edge e = new Edge();
                    e.from = t.key;
                    e.to = holder.key;
                    e.relation = "USES_CONFIG";
                    e.filePath = t.filePath;
                    e.line = t.startLine;
                    e.confidence = 0.85;
                    e.detail = "ConfigurationProperties prefix " + prefix;
                    index.addEdge(e);
                }
            }
            if (t.annotations.contains("ConditionalOnProperty")) {
                String name = firstAttribute(t.filePath, content, "ConditionalOnProperty", "name", t.startLine);
                if (name == null) {
                    name = firstAttribute(t.filePath, content, "ConditionalOnProperty", "value", t.startLine);
                }
                if (name != null) {
                    linkKey(t.key, name, t.filePath, t.startLine, "@ConditionalOnProperty");
                }
            }
        }
    }

    private void linkKey(String fromKey, String rawKey, String file, int line, String how) {
        String key = rawKey.toLowerCase(Locale.ROOT).trim();
        if (key.isEmpty()) {
            return;
        }
        Sym target = index.symbol("config:" + key);
        if (target == null) {
            target = new Sym();
            target.key = "config:" + key;
            target.type = "CONFIG_KEY";
            target.qualifiedName = key;
            target.simpleName = key;
            target.modulePath = maven.moduleOf(file);
            target.filePath = file;
            target.startLine = line;
            target.endLine = line;
            target.meta.put("declared", "false");
            index.addSymbol(target);
        }
        Edge e = new Edge();
        e.from = fromKey;
        e.to = target.key;
        e.relation = "USES_CONFIG";
        e.filePath = file;
        e.line = line;
        e.confidence = 0.9;
        e.detail = how + " " + key;
        index.addEdge(e);
    }

    private Set<String> placeholders(String raw) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = VALUE_PLACEHOLDER.matcher(raw);
        while (m.find()) {
            out.add(m.group(1));
        }
        if (out.isEmpty() && raw.matches("[A-Za-z0-9_.\\-]+")) {
            out.add(raw);
        }
        return out;
    }

    private String prefixOf(String filePath, String content, int line) {
        Matcher m = Pattern.compile("@ConfigurationProperties\\s*\\(([^)]*)\\)")
                .matcher(snippet(filePath, content, line, 8));
        if (!m.find()) {
            return null;
        }
        Matcher value = Pattern.compile("(?:prefix\\s*=\\s*)?\"([^\"]+)\"").matcher(m.group(1));
        return value.find() ? value.group(1) : null;
    }

    private String firstAttribute(String filePath, String content, String annotation, String attribute, int line) {
        Matcher m = Pattern.compile("@" + annotation + "\\s*\\(([^)]*)\\)")
                .matcher(snippet(filePath, content, line, 8));
        if (!m.find()) {
            return null;
        }
        Matcher value = Pattern.compile("(?:^|,)\\s*(?:" + attribute + "\\s*=\\s*)?\"([^\"]+)\"")
                .matcher(m.group(1));
        return value.find() ? value.group(1) : null;
    }

    /**
     * 注解附近的代码片段。
     *
     * 行数组按文件缓存（{@link SourceLines}）：@Value / @ConfigurationProperties /
     * @ConditionalOnProperty 都是按方法、按字段调用的，原先每次调用都把整个文件 split 一遍，
     * 单个大文件里就是 O(方法数 × 文件大小)。
     */
    private String snippet(String filePath, String content, int line, int window) {
        String[] fileLines = lines.lines(filePath, content);
        int from = Math.max(0, line - 3);
        int to = Math.min(fileLines.length, line + window);
        return String.join("\n", java.util.Arrays.copyOfRange(fileLines, from, to));
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