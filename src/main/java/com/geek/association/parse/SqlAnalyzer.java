package com.geek.association.parse;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.source.FileSource;
import com.geek.association.util.Hashes;
import com.geek.association.util.Texts;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts database dependencies: MyBatis XML statements, MyBatis annotation SQL, JPA entities,
 * native SQL inside Query/Select annotations and plain .sql migration scripts.
 *
 * Every statement becomes a SQL_STATEMENT node linked to the tables and columns it reads or
 * writes, so a DDL change can be traced back to the Java code that depends on it.
 */
public class SqlAnalyzer {

    private static final Pattern TABLE_AFTER = Pattern.compile(
            "\\b(?:from|join|into|update|table)\\s+([a-zA-Z_][\\w.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_COLUMNS = Pattern.compile(
            "\\bselect\\s+(.*?)\\s+from\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALTER_ADD = Pattern.compile(
            "\\balter\\s+table\\s+([\\w.]+)\\s+add\\s+(?:column\\s+)?([\\w]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_DROP = Pattern.compile(
            "\\balter\\s+table\\s+([\\w.]+)\\s+drop\\s+(?:column\\s+)?([\\w]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_MODIFY = Pattern.compile(
            "\\balter\\s+table\\s+([\\w.]+)\\s+(?:modify|alter)\\s+(?:column\\s+)?([\\w]+)", Pattern.CASE_INSENSITIVE);
    /** A table reference with an optional alias: {@code from orders o}, {@code join order_item as i}. */
    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(?:from|join|into|update)\\s+([a-zA-Z_][\\w.]*)(?:\\s+(?:as\\s+)?([a-zA-Z_][\\w]*))?",
            Pattern.CASE_INSENSITIVE);
    /**
     * One MyBatis statement element. Compiled once: this used to be recompiled for every mapper
     * file, and a large repository has thousands of them.
     */
    private static final Pattern MYBATIS_STATEMENT = Pattern.compile(
            "<(select|insert|update|delete)\\s+[^>]*id\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** Statement-shape patterns of {@code linkTables}; compiled once, not per statement. */
    private static final Pattern INSERT_INTO = Pattern.compile(
            "\\binsert\\s+into\\s+([a-zA-Z_][\\w.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_TABLE = Pattern.compile(
            "\\bupdate\\s+([a-zA-Z_][\\w.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_FROM = Pattern.compile(
            "\\bdelete\\s+from\\s+([a-zA-Z_][\\w.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_JOIN = Pattern.compile(
            "\\b(?:from|join)\\s+([a-zA-Z_][\\w.]*)", Pattern.CASE_INSENSITIVE);
    /** Words that follow a table reference but are not aliases. */
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "where", "set", "values", "select", "from", "join", "left", "right", "inner", "outer", "full",
            "cross", "on", "group", "order", "by", "having", "limit", "offset", "union", "and", "or",
            "as", "insert", "update", "delete", "into", "duplicate", "ignore", "returning", "for");

    private final FileSource source;
    private final MavenParser maven;
    private final CodeIndex index;
    /** 行数组按文件缓存：methodSnippet 在每个方法上调用一次，重复切分整个文件是 O(方法数 × 文件大小)。 */
    private final SourceLines lines = new SourceLines();

    public SqlAnalyzer(FileSource source, MavenParser maven, CodeIndex index) {
        this.source = source;
        this.maven = maven;
        this.index = index;
    }

    public void analyze() {
        analyzeMyBatisXml();
        analyzeJavaSql();
        analyzeJpaEntities();
        analyzeSqlFiles();
    }

    // ------------------------------------------------------------- MyBatis XML

    private void analyzeMyBatisXml() {
        for (String file : source.files()) {
            if (!file.endsWith(".xml")) {
                continue;
            }
            String content = source.read(file);
            if (content == null || !content.contains("<mapper")) {
                continue;
            }
            String namespace = attr(content, "<mapper", "namespace");
            Matcher m = MYBATIS_STATEMENT.matcher(content);
            while (m.find()) {
                String kind = m.group(1).toLowerCase(Locale.ROOT);
                String id = m.group(2);
                String body = m.group(3);
                String resultType = attr(m.group(0), "<" + m.group(1), "resultType");
                String sqlText = stripTags(body);
                String key = "sql:" + file + "#" + (namespace == null ? "" : namespace + ".") + id;
                Sym sym = new Sym();
                sym.key = key;
                sym.type = "SQL_STATEMENT";
                sym.qualifiedName = (namespace == null ? "" : namespace + ".") + id;
                sym.simpleName = id;
                sym.modulePath = maven.moduleOf(file);
                sym.filePath = file;
                sym.startLine = lineOf(content, m.start());
                sym.endLine = lineOf(content, m.end());
                sym.bodyHash = Hashes.sha1(JavaIndexer.normalize(sqlText));
                sym.signatureHash = Hashes.sha1(kind + ":" + id);
                sym.meta.put("sqlKind", kind);
                sym.meta.put("sql", Texts.truncate(JavaIndexer.normalize(sqlText), 4000));
                sym.meta.put("mapper", namespace == null ? "" : namespace);
                sym.meta.put("resultType", resultType == null ? "" : resultType);
                index.addSymbol(sym);
                linkTables(sym, sqlText, kind);
                linkMapperMethod(sym, namespace, id);
            }
        }
    }

    private void linkMapperMethod(Sym sym, String namespace, String id) {
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        // index.types is keyed by qualified name, so the namespace is a direct lookup. Scanning
        // every type for it made each mapper statement cost O(types) on a large repository.
        TypeInfo t = index.types.get(namespace);
        if (t == null) {
            return;
        }
        List<TypeInfo.MethodInfo> methods = t.methods.get(id);
        if (methods == null) {
            return;
        }
        for (TypeInfo.MethodInfo method : methods) {
            Edge e = new Edge();
            e.from = method.key;
            e.to = sym.key;
            e.relation = "EXECUTES_SQL";
            e.filePath = t.filePath;
            e.line = method.line;
            e.confidence = 0.95;
            e.detail = "MyBatis " + id;
            index.addEdge(e);
        }
    }

    // ------------------------------------------------------- Java annotation SQL

    private void analyzeJavaSql() {
        Pattern annotation = Pattern.compile(
                "@(Select|Insert|Update|Delete|Query)\\s*\\(\\s*(?:value\\s*=\\s*)?\\{?\\s*((?:\"[^\"]*\"\\s*,?\\s*)+)\\}?\\s*\\)");
        for (TypeInfo t : index.types.values()) {
            String content = source.read(t.filePath);
            if (content == null || !(content.contains("@Select") || content.contains("@Insert")
                    || content.contains("@Update") || content.contains("@Delete") || content.contains("@Query"))) {
                continue;
            }
            for (Map.Entry<String, List<TypeInfo.MethodInfo>> e : t.methods.entrySet()) {
                for (TypeInfo.MethodInfo m : e.getValue()) {
                    String snippet = methodSnippet(content, t.filePath, m.line, 40);
                    Matcher matcher = annotation.matcher(snippet);
                    while (matcher.find()) {
                        String kind = matcher.group(1).toLowerCase(Locale.ROOT);
                        String sqlText = matcher.group(2).replaceAll("\"", "").replaceAll("\\s*,\\s*", " ");
                        Sym sym = new Sym();
                        sym.key = "sql:" + t.filePath + "#" + m.key;
                        sym.type = "SQL_STATEMENT";
                        sym.qualifiedName = t.qualifiedName + "#" + m.name + "()";
                        sym.simpleName = m.name;
                        sym.modulePath = t.modulePath;
                        sym.filePath = t.filePath;
                        sym.startLine = m.line;
                        sym.endLine = m.line;
                        sym.bodyHash = Hashes.sha1(JavaIndexer.normalize(sqlText));
                        sym.signatureHash = Hashes.sha1(kind + ":" + m.key);
                        String realKind = kind.equals("query") ? guessKind(sqlText) : kind;
                        sym.meta.put("sqlKind", realKind);
                        sym.meta.put("sql", Texts.truncate(JavaIndexer.normalize(sqlText), 4000));
                        sym.meta.put("mapper", t.qualifiedName);
                        index.addSymbol(sym);
                        linkTables(sym, sqlText, realKind);
                        Edge edge = new Edge();
                        edge.from = m.key;
                        edge.to = sym.key;
                        edge.relation = "EXECUTES_SQL";
                        edge.filePath = t.filePath;
                        edge.line = m.line;
                        edge.confidence = 0.9;
                        edge.detail = "@" + matcher.group(1);
                        index.addEdge(edge);
                    }
                }
            }
        }
    }

    /**
     * A window of lines around one method, for the annotation scan.
     *
     * The line array comes from {@link SourceLines}, which splits a file once and caches the
     * result. Splitting here made every method of a file re-split the whole file, so a large
     * class cost O(methods x file size) - the "large module" slowdown.
     */
    private String methodSnippet(String content, String file, int line, int window) {
        String[] fileLines = lines.lines(file, content);
        int from = Math.max(0, line - 2);
        int to = Math.min(fileLines.length, line + window);
        return String.join("\n", java.util.Arrays.copyOfRange(fileLines, from, to));
    }

    private String guessKind(String sqlText) {
        String s = sqlText.toLowerCase(Locale.ROOT).strip();
        if (s.startsWith("select")) {
            return "select";
        }
        if (s.startsWith("insert")) {
            return "insert";
        }
        if (s.startsWith("update")) {
            return "update";
        }
        if (s.startsWith("delete")) {
            return "delete";
        }
        return "query";
    }

    // ------------------------------------------------------------------- JPA

    private void analyzeJpaEntities() {
        for (TypeInfo t : index.types.values()) {
            if (!t.annotations.contains("Entity") && !t.annotations.contains("Table")
                    && !t.annotations.contains("MappedSuperclass")) {
                continue;
            }
            String content = source.read(t.filePath);
            if (content == null) {
                continue;
            }
            String table = annotationAttribute(content, "Table", "name");
            if (table == null) {
                table = camelToSnake(t.simpleName);
            }
            Sym tableSym = tableSymbol(table, t.modulePath, t.filePath, t.startLine);
            Edge e = new Edge();
            e.from = t.key;
            e.to = tableSym.key;
            e.relation = "USES_TABLE";
            e.filePath = t.filePath;
            e.line = t.startLine;
            e.confidence = 0.9;
            e.detail = "@Entity " + t.simpleName;
            index.addEdge(e);
            for (TypeInfo.FieldInfo f : t.fields.values()) {
                String column = annotationAttributeForField(content, f.name, "Column", "name");
                if (column == null) {
                    column = camelToSnake(f.name);
                }
                Sym colSym = columnSymbol(table, column, t.modulePath, t.filePath, f.line);
                Edge fe = new Edge();
                fe.from = f.key;
                fe.to = colSym.key;
                fe.relation = "MAPS_COLUMN";
                fe.filePath = t.filePath;
                fe.line = f.line;
                fe.confidence = 0.8;
                fe.detail = f.name + " -> " + table + "." + column;
                index.addEdge(fe);
            }
        }
    }

    // --------------------------------------------------------------- .sql files

    private void analyzeSqlFiles() {
        for (String file : source.files()) {
            if (!file.endsWith(".sql")) {
                continue;
            }
            String content = source.read(file);
            if (content == null) {
                continue;
            }
            int offset = 0;
            for (String raw : content.split(";")) {
                String sqlText = raw.strip();
                int line = lineOf(content, offset);
                offset += raw.length() + 1;
                if (sqlText.isEmpty()) {
                    continue;
                }
                String kind = guessKind(sqlText);
                Sym sym = new Sym();
                sym.key = "sql:" + file + "#" + line;
                sym.type = "SQL_STATEMENT";
                sym.qualifiedName = file + ":" + line;
                sym.simpleName = kind;
                sym.modulePath = maven.moduleOf(file);
                sym.filePath = file;
                sym.startLine = line;
                sym.endLine = line + countLines(sqlText);
                sym.bodyHash = Hashes.sha1(JavaIndexer.normalize(sqlText));
                sym.signatureHash = sym.bodyHash;
                sym.meta.put("sqlKind", kind);
                sym.meta.put("sql", Texts.truncate(JavaIndexer.normalize(sqlText), 4000));
                sym.meta.put("script", "true");
                index.addSymbol(sym);
                linkTables(sym, sqlText, kind);
                detectDdl(sym, sqlText);
            }
        }
    }

    private void detectDdl(Sym sym, String sqlText) {
        String upper = sqlText.toUpperCase(Locale.ROOT);
        if (upper.contains("ALTER TABLE")) {
            Matcher add = ALTER_ADD.matcher(sqlText);
            if (add.find()) {
                sym.meta.put("ddl", "ADD_COLUMN");
                sym.meta.put("ddlTable", add.group(1));
                sym.meta.put("ddlColumn", add.group(2));
            }
            Matcher drop = ALTER_DROP.matcher(sqlText);
            if (drop.find()) {
                sym.meta.put("ddl", "DROP_COLUMN");
                sym.meta.put("ddlTable", drop.group(1));
                sym.meta.put("ddlColumn", drop.group(2));
            }
            Matcher modify = ALTER_MODIFY.matcher(sqlText);
            if (modify.find()) {
                sym.meta.put("ddl", "MODIFY_COLUMN");
                sym.meta.put("ddlTable", modify.group(1));
                sym.meta.put("ddlColumn", modify.group(2));
            }
            sym.meta.put("ddlSql", Texts.truncate(JavaIndexer.normalize(sqlText), 500));
        }
        if (upper.startsWith("CREATE TABLE")) {
            Matcher create = Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.]+)",
                    Pattern.CASE_INSENSITIVE).matcher(sqlText);
            if (create.find()) {
                sym.meta.put("ddl", "CREATE_TABLE");
                sym.meta.put("ddlTable", create.group(1));
            }
        }
        if (upper.startsWith("DROP TABLE")) {
            Matcher dropTable = Pattern.compile("DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([\\w.]+)",
                    Pattern.CASE_INSENSITIVE).matcher(sqlText);
            if (dropTable.find()) {
                sym.meta.put("ddl", "DROP_TABLE");
                sym.meta.put("ddlTable", dropTable.group(1));
            }
        }
    }

    // ---------------------------------------------------------------- linking

    /**
     * Links a statement to the tables and columns it touches.
     *
     * Columns are attributed to their owning table instead of being attached to every table of the
     * statement: a join over three tables used to give each table every selected column, which made
     * a column-level contract indistinguishable from a table-level one.
     *
     * The two column lists are also written to metadata, because the change detector and the peer
     * engine need to know which columns a statement reads and writes without walking the graph.
     */
    private void linkTables(Sym sym, String sqlText, String kind) {
        String lower = sqlText.toLowerCase(Locale.ROOT);
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        Matcher insert = INSERT_INTO.matcher(sqlText);
        while (insert.find()) {
            writes.add(normalizeTable(insert.group(1)));
        }
        Matcher update = UPDATE_TABLE.matcher(sqlText);
        while (update.find()) {
            if (!lower.startsWith("select")) {
                writes.add(normalizeTable(update.group(1)));
            }
        }
        Matcher delete = DELETE_FROM.matcher(sqlText);
        while (delete.find()) {
            writes.add(normalizeTable(delete.group(1)));
        }
        Matcher from = FROM_JOIN.matcher(sqlText);
        while (from.find()) {
            reads.add(normalizeTable(from.group(1)));
        }
        if (reads.isEmpty()) {
            Matcher t = TABLE_AFTER.matcher(sqlText);
            while (t.find()) {
                reads.add(normalizeTable(t.group(1)));
            }
        }
        reads.removeAll(writes);
        Map<String, String> aliases = tableAliases(sqlText);
        Set<String> readColumns = new LinkedHashSet<>();
        Set<String> writeColumns = new LinkedHashSet<>();
        for (String table : reads) {
            link(sym, tableSymbol(table, sym.modulePath, sym.filePath, sym.startLine), "READS_TABLE", "select from " + table);
        }
        for (String ref : selectColumnRefs(sqlText)) {
            String table = ownerTable(ref, reads, aliases);
            if (table == null) {
                continue; // cannot tell which table the column belongs to; a blind spot, not a guess
            }
            String column = columnName(ref);
            readColumns.add(table + "." + column);
            link(sym, columnSymbol(table, column, sym.modulePath, sym.filePath, sym.startLine),
                    "READS_COLUMN", table + "." + column);
        }
        for (String table : writes) {
            link(sym, tableSymbol(table, sym.modulePath, sym.filePath, sym.startLine), "WRITES_TABLE", kind + " " + table);
        }
        for (String column : writtenColumns(sqlText)) {
            String table = ownerTable(column, writes, aliases);
            if (table == null) {
                continue;
            }
            writeColumns.add(table + "." + column);
            link(sym, columnSymbol(table, column, sym.modulePath, sym.filePath, sym.startLine),
                    "WRITES_COLUMN", table + "." + column);
        }
        sym.meta.put("readsTables", String.join(",", reads));
        sym.meta.put("writesTables", String.join(",", writes));
        sym.meta.put("readsColumns", String.join(",", readColumns));
        sym.meta.put("writesColumns", String.join(",", writeColumns));
    }

    /**
     * Alias table of one statement: {@code alias -> table}, plus each table mapped to itself.
     *
     * A column written as {@code o.status} can only be attributed when {@code o} is known to be an
     * alias of {@code orders}; without this the column would be dropped rather than guessed.
     */
    private Map<String, String> tableAliases(String sqlText) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        Matcher m = TABLE_REF.matcher(sqlText);
        while (m.find()) {
            String table = normalizeTable(m.group(1));
            out.put(table, table);
            String alias = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            if (!alias.isEmpty() && !SQL_KEYWORDS.contains(alias)) {
                out.put(alias, table);
            }
        }
        return out;
    }

    /** The table a column reference belongs to, or null when it cannot be determined. */
    private String ownerTable(String ref, Set<String> tables, Map<String, String> aliases) {
        if (tables.isEmpty()) {
            return null;
        }
        int dot = ref.lastIndexOf('.');
        if (dot > 0) {
            String owner = aliases.get(ref.substring(0, dot).toLowerCase(Locale.ROOT));
            return owner != null && tables.contains(owner) ? owner : null;
        }
        // An unqualified column is only attributable when the statement touches a single table.
        return tables.size() == 1 ? tables.iterator().next() : null;
    }

    private String columnName(String ref) {
        int dot = ref.lastIndexOf('.');
        return (dot < 0 ? ref : ref.substring(dot + 1)).toLowerCase(Locale.ROOT);
    }

    private void link(Sym from, Sym to, String relation, String detail) {
        if (to == null) {
            return;
        }
        Edge e = new Edge();
        e.from = from.key;
        e.to = to.key;
        e.relation = relation;
        e.filePath = from.filePath;
        e.line = from.startLine;
        e.confidence = 0.85;
        e.detail = detail;
        index.addEdge(e);
    }

    /**
     * Column references of the select list, table-qualified where the statement writes them that way.
     *
     * The reference is kept as written ({@code o.status}, {@code status}) so the caller can resolve
     * the owning table through the statement's aliases. A select list containing {@code *} returns
     * nothing: the column list is only known at runtime, which is a blind spot rather than an empty
     * read set.
     */
    private Set<String> selectColumnRefs(String sqlText) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = SELECT_COLUMNS.matcher(sqlText);
        if (!m.find()) {
            return out;
        }
        String columns = m.group(1);
        if (columns.contains("*")) {
            return out;
        }
        for (String part : columns.split(",")) {
            String c = part.strip();
            if (c.contains("(") || c.contains(" ")) {
                continue;
            }
            if (c.matches("[a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)?") && !SQL_KEYWORDS.contains(c.toLowerCase(Locale.ROOT))) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Columns a statement writes: the insert column list and the columns of an update's set clause.
     *
     * Only the names matter, so a qualified form ({@code o.status}) keeps its qualifier for the
     * owning-table lookup and is normalised by the caller.
     */
    private Set<String> writtenColumns(String sqlText) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\(([^()]*)\\)\\s*values\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(sqlText);
        if (m.find()) {
            for (String c : m.group(1).split(",")) {
                addColumnRef(out, c.strip());
            }
        }
        Matcher set = Pattern.compile("\\bset\\s+(.*?)(?:\\bwhere\\b|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sqlText);
        if (set.find()) {
            for (String part : set.group(1).split(",")) {
                addColumnRef(out, part.split("=")[0].strip());
            }
        }
        return out;
    }

    private void addColumnRef(Set<String> out, String raw) {
        if (raw.matches("[a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)?")
                && !SQL_KEYWORDS.contains(raw.toLowerCase(Locale.ROOT))) {
            out.add(raw.toLowerCase(Locale.ROOT));
        }
    }

    public Sym tableSymbol(String table, String modulePath, String file, int line) {
        String name = normalizeTable(table);
        String key = "db:table:" + name;
        Sym existing = index.symbol(key);
        if (existing != null) {
            return existing;
        }
        Sym sym = new Sym();
        sym.key = key;
        sym.type = "DATABASE_TABLE";
        sym.qualifiedName = name;
        sym.simpleName = name;
        sym.modulePath = modulePath;
        sym.filePath = file;
        sym.startLine = line;
        sym.endLine = line;
        index.addSymbol(sym);
        return sym;
    }

    public Sym columnSymbol(String table, String column, String modulePath, String file, int line) {
        String name = normalizeTable(table) + "." + column.toLowerCase(Locale.ROOT);
        String key = "db:column:" + name;
        Sym existing = index.symbol(key);
        if (existing != null) {
            return existing;
        }
        Sym sym = new Sym();
        sym.key = key;
        sym.type = "DATABASE_COLUMN";
        sym.qualifiedName = name;
        sym.simpleName = column.toLowerCase(Locale.ROOT);
        sym.modulePath = modulePath;
        sym.filePath = file;
        sym.startLine = line;
        sym.endLine = line;
        sym.meta.put("table", normalizeTable(table));
        index.addSymbol(sym);
        return sym;
    }

    public static String normalizeTable(String table) {
        String t = table == null ? "" : table.trim();
        int dot = t.lastIndexOf('.');
        if (dot >= 0) {
            t = t.substring(dot + 1);
        }
        t = t.replace("`", "").replace("\"", "").replace("]", "").replace("[", "");
        return t.toLowerCase(Locale.ROOT);
    }

    private String stripTags(String xml) {
        return xml.replaceAll("<[^>]+>", " ")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("&amp;", "&").replaceAll("&quot;", "\"");
    }

    private String attr(String xml, String tag, String name) {
        Matcher m = Pattern.compile(tag + "[^>]*\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private String annotationAttribute(String content, String annotation, String name) {
        Matcher m = Pattern.compile("@" + annotation + "\\s*\\(([^)]*)\\)").matcher(content);
        if (!m.find()) {
            return null;
        }
        String args = m.group(1);
        Matcher value = Pattern.compile("(?:^|,)\\s*(?:" + name + "\\s*=\\s*)?\"([^\"]+)\"").matcher(args);
        return value.find() ? value.group(1) : null;
    }

    private String annotationAttributeForField(String content, String fieldName, String annotation, String name) {
        Matcher m = Pattern.compile("@" + annotation + "\\s*\\(([^)]*)\\)\\s*(?:private|protected|public)?\\s+[\\w<>,\\[\\]]+\\s+"
                + Pattern.quote(fieldName) + "\\b").matcher(content);
        if (!m.find()) {
            return null;
        }
        String args = m.group(1);
        Matcher value = Pattern.compile("(?:^|,)\\s*(?:" + name + "\\s*=\\s*)?\"([^\"]+)\"").matcher(args);
        return value.find() ? value.group(1) : null;
    }

    public static String camelToSnake(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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

    private int countLines(String text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
