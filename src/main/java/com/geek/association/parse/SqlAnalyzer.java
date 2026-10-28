package com.geek.association.parse;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.source.FileSource;
import com.geek.association.util.Hashes;
import com.geek.association.util.Texts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
            "\\balter\\s+table\\s+([\\w.]+)\\s+add\\s+(?:column\\s+)?(?:if\\s+not\\s+exists\\s+)?([\\w]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_DROP = Pattern.compile(
            "\\balter\\s+table\\s+([\\w.]+)\\s+drop\\s+(?:column\\s+)?(?:if\\s+exists\\s+)?([\\w]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_MODIFY = Pattern.compile(
            "\\balter\\s+table\\s+([\\w.]+)\\s+(?:modify|alter)\\s+(?:column\\s+)?(?:if\\s+exists\\s+)?([\\w]+)",
            Pattern.CASE_INSENSITIVE);
    /** ALTER TABLE ... ADD [COLUMN] c <definition>; the definition carries type/nullability/default. */
    private static final Pattern ALTER_ADD_DEF = Pattern.compile(
            "\\balter\\s+table\\s+([\\w.\\x60\\[\\]]+)\\s+add\\s+(?:column\\s+)?(?:if\\s+not\\s+exists\\s+)?"
                    + "([\\w\\x60\\[\\]]+)\\s+([^;]+?)"
                    + "(?=,\\s*(?:add|drop|modify|alter|rename|change)\\b|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** ALTER TABLE ... MODIFY/ALTER [COLUMN] c <definition>. */
    private static final Pattern ALTER_MODIFY_DEF = Pattern.compile(
            "\\balter\\s+table\\s+([\\w.\\x60\\[\\]]+)\\s+(?:modify|alter)\\s+(?:column\\s+)?(?:if\\s+exists\\s+)?"
                    + "([\\w\\x60\\[\\]]+)\\s+([^;]+?)"
                    + "(?=,\\s*(?:add|drop|modify|alter|rename|change)\\b|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_TABLE_HEAD = Pattern.compile(
            "\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([\\w.\\x60\\[\\]]+)\\s*\\(",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_TABLE_ANY = Pattern.compile(
            "\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([\\w.\\x60\\[\\]]+)",
            Pattern.CASE_INSENSITIVE);
    /** PostgreSQL / standard SQL: ALTER TABLE t ALTER COLUMN c SET NOT NULL. */
    private static final Pattern ALTER_RENAME_COLUMN = Pattern.compile(
            "\\balter\\s+table\\s+([\\w.\\x60\\[\\]]+)\\s+rename\\s+(?:column\\s+)?"
                    + "([\\w\\x60\\[\\]]+)\\s+to\\s+([\\w\\x60\\[\\]]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALTER_SET_NOT_NULL = Pattern.compile(
            "\\balter\\s+table\\s+([\\w.\\x60\\[\\]]+)\\s+alter\\s+(?:column\\s+)?"
                    + "([\\w\\x60\\[\\]]+)\\s+set\\s+not\\s+null",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALTER_DROP_NOT_NULL = Pattern.compile(
            "\\balter\\s+table\\s+([\\w.\\x60\\[\\]]+)\\s+alter\\s+(?:column\\s+)?"
                    + "([\\w\\x60\\[\\]]+)\\s+drop\\s+not\\s+null",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALTER_SET_DEFAULT = Pattern.compile(
            "\\balter\\s+table\\s+([\\w.\\x60\\[\\]]+)\\s+alter\\s+(?:column\\s+)?"
                    + "([\\w\\x60\\[\\]]+)\\s+set\\s+default\\s+([^;]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALTER_DROP_DEFAULT = Pattern.compile(
            "\\balter\\s+table\\s+([\\w.\\x60\\[\\]]+)\\s+alter\\s+(?:column\\s+)?"
                    + "([\\w\\x60\\[\\]]+)\\s+drop\\s+default",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SELECT_STAR = Pattern.compile(
            "\\bselect\\s+(?:[a-zA-Z_][\\w]*\\s*\\.\\s*)?\\*", Pattern.CASE_INSENSITIVE);
    private static final Pattern DYNAMIC_SQL = Pattern.compile(
            "<\\s*(?:if|choose|when|otherwise|foreach|include|sql|bind|trim|where|set)\\b|\\$\\{",
            Pattern.CASE_INSENSITIVE);
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
            "(?:^|;)\\s*update\\s+([a-zA-Z_][\\w.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_FROM = Pattern.compile(
            "\\bdelete\\s+from\\s+([a-zA-Z_][\\w.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_JOIN = Pattern.compile(
            "\\b(?:from|join)\\s+([a-zA-Z_][\\w.]*)", Pattern.CASE_INSENSITIVE);
    /** Words that follow a table reference but are not aliases. */
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "where", "set", "values", "select", "from", "join", "left", "right", "inner", "outer", "full",
            "cross", "on", "group", "order", "by", "having", "limit", "offset", "union", "and", "or",
            "as", "insert", "update", "delete", "into", "duplicate", "ignore", "returning", "for");
    /** ALTER TABLE actions that are not a column and must never become a column contract. */
    private static final Set<String> NON_COLUMN_DDL = Set.of(
            "INDEX", "KEY", "CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "FULLTEXT", "SPATIAL",
            "SET", "DROP");

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
                String sqlText = stripSqlComments(stripTags(body));
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
                markStatementShape(sym, body, sqlText);
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
                "@(Select|Insert|Update|Delete|Query)\\s*\\(\\s*(?:value\\s*=\\s*)?\\{?\\s*((?:\"[^\"]*\"\\s*,?\\s*)+)\\}?[^)]*\\)");
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
                        String sqlText = stripSqlComments(matcher.group(2).replaceAll("\"", "").replaceAll("\\s*,\\s*", " "));
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
                        markStatementShape(sym, sqlText, sqlText);
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
                // @Transient and static fields are not columns. A collection is a relation
                // (@OneToMany / @ManyToMany), not a scalar column mapping.
                if (f.annotations.contains("Transient") || f.modifiers.contains("static")
                        || f.annotations.contains("OneToMany") || f.annotations.contains("ManyToMany")
                        || f.annotations.contains("ElementCollection") || isCollectionType(f.type)) {
                    continue;
                }
                String explicit = annotationAttributeForField(content, f.name, "Column", "name");
                if (explicit == null) {
                    explicit = annotationAttributeForField(content, f.name, "JoinColumn", "name");
                }
                String column = explicit == null ? camelToSnake(f.name) : explicit;
                Sym colSym = columnSymbol(table, column, t.modulePath, t.filePath, f.line);
                Edge fe = new Edge();
                fe.from = f.key;
                fe.to = colSym.key;
                fe.relation = "MAPS_COLUMN";
                fe.filePath = t.filePath;
                fe.line = f.line;
                // An explicit @Column(name=...) is evidence; a naming-strategy guess is a hint.
                // The peer matcher uses this to lower confidence instead of pretending both are equal.
                fe.confidence = explicit == null ? 0.6 : 0.9;
                fe.detail = f.name + " -> " + table + "." + column
                        + (explicit == null ? "（隐式命名）" : "");
                index.addEdge(fe);
            }
        }
    }

    // --------------------------------------------------------------- .sql files

    private void analyzeSqlFiles() {
        List<String> files = new ArrayList<>();
        for (String file : source.files()) {
            if (file.endsWith(".sql")) {
                files.add(file);
            }
        }
        // Migration order matters for the schema snapshot: V1 creates, V2 alters. Numeric version
        // order keeps V10 after V2; plain string order used to put it before.
        files.sort(Comparator.comparingInt(SqlAnalyzer::migrationVersion)
                .thenComparing(Comparator.naturalOrder()));
        for (String file : files) {
            String content = source.read(file);
            if (content == null) {
                continue;
            }
            for (SqlChunk chunk : splitSqlStatements(content)) {
                String sqlText = stripSqlComments(chunk.text.strip());
                if (sqlText.isEmpty()) {
                    continue;
                }
                int line = lineOf(content, chunk.start);
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
                markStatementShape(sym, sqlText, sqlText);
                index.addSymbol(sym);
                linkTables(sym, sqlText, kind);
                detectDdl(sym, sqlText);
                // Test schema must not become the production schema snapshot: a testcontainers
                // DDL change says nothing about what other programs will see at runtime.
                if (!sym.test) {
                    applySchema(sqlText);
                }
            }
        }
    }

    private void detectDdl(Sym sym, String sqlText) {
        sqlText = sqlText.strip();
        String upper = sqlText.toUpperCase(Locale.ROOT);
        if (upper.startsWith("ALTER TABLE")) {
            Matcher add = ALTER_ADD_DEF.matcher(sqlText);
            boolean addColumn = false;
            if (add.find() && !isNonColumnDefinition(add.group(2)) && !isNonColumnDefinition(add.group(3))) {
                sym.meta.put("ddl", "ADD_COLUMN");
                sym.meta.put("ddlTable", add.group(1));
                sym.meta.put("ddlColumn", stripIdent(add.group(2)));
                putDefinitionMeta(sym, alterDefinition(add.group(3)));
                putSchemaMeta(sym, add.group(1));
                addColumn = true;
            }
            if (!addColumn) {
                Matcher fallback = ALTER_ADD.matcher(sqlText);
                if (fallback.find() && !isNonColumnDefinition(fallback.group(2))) {
                    sym.meta.put("ddl", "ADD_COLUMN");
                    sym.meta.put("ddlTable", fallback.group(1));
                    sym.meta.put("ddlColumn", stripIdent(fallback.group(2)));
                    putSchemaMeta(sym, fallback.group(1));
                }
            }
            Matcher drop = ALTER_DROP.matcher(sqlText);
            if (drop.find() && !isNonColumnDefinition(drop.group(2))) {
                sym.meta.put("ddl", "DROP_COLUMN");
                sym.meta.put("ddlTable", drop.group(1));
                sym.meta.put("ddlColumn", stripIdent(drop.group(2)));
                putSchemaMeta(sym, drop.group(1));
            }
            Matcher modify = ALTER_MODIFY_DEF.matcher(sqlText);
            boolean modifyColumn = false;
            if (modify.find() && !isNonColumnDefinition(modify.group(2)) && !isNonColumnDefinition(modify.group(3))) {
                sym.meta.put("ddl", "MODIFY_COLUMN");
                sym.meta.put("ddlTable", modify.group(1));
                sym.meta.put("ddlColumn", stripIdent(modify.group(2)));
                putDefinitionMeta(sym, alterDefinition(modify.group(3)));
                putSchemaMeta(sym, modify.group(1));
                modifyColumn = true;
            }
            if (!modifyColumn) {
                Matcher fallback = ALTER_MODIFY.matcher(sqlText);
                if (fallback.find() && !isNonColumnDefinition(fallback.group(2))) {
                    sym.meta.put("ddl", "MODIFY_COLUMN");
                    sym.meta.put("ddlTable", fallback.group(1));
                    sym.meta.put("ddlColumn", stripIdent(fallback.group(2)));
                    putSchemaMeta(sym, fallback.group(1));
                }
            }
            // A rename is a drop of the old name plus an add of the new one. Reporting the old
            // name as DROP_COLUMN is what protects existing readers/writers; the new name cannot
            // already be a dependency of another program.
            Matcher rename = ALTER_RENAME_COLUMN.matcher(sqlText);
            if (rename.find()) {
                sym.meta.put("ddl", "DROP_COLUMN");
                sym.meta.put("ddlTable", rename.group(1));
                sym.meta.put("ddlColumn", stripIdent(rename.group(2)));
                sym.meta.put("ddlRenamedTo", stripIdent(rename.group(3)));
                putSchemaMeta(sym, rename.group(1));
            }
            // PostgreSQL / standard SQL: ALTER COLUMN c SET/DROP NOT NULL / DEFAULT.
            // The generic MODIFY pattern deliberately rejects SET/DROP as an action, so these
            // forms need their own branch; without it a NOT NULL tightening silently disappears.
            Matcher setNotNull = ALTER_SET_NOT_NULL.matcher(sqlText);
            Matcher dropNotNull = ALTER_DROP_NOT_NULL.matcher(sqlText);
            Matcher setDefault = ALTER_SET_DEFAULT.matcher(sqlText);
            Matcher dropDefault = ALTER_DROP_DEFAULT.matcher(sqlText);
            if (setNotNull.find()) {
                sym.meta.put("ddl", "MODIFY_COLUMN");
                sym.meta.put("ddlTable", setNotNull.group(1));
                sym.meta.put("ddlColumn", stripIdent(setNotNull.group(2)));
                sym.meta.put("ddlNullable", "false");
                putSchemaMeta(sym, setNotNull.group(1));
            } else if (dropNotNull.find()) {
                sym.meta.put("ddl", "MODIFY_COLUMN");
                sym.meta.put("ddlTable", dropNotNull.group(1));
                sym.meta.put("ddlColumn", stripIdent(dropNotNull.group(2)));
                sym.meta.put("ddlNullable", "true");
                putSchemaMeta(sym, dropNotNull.group(1));
            } else if (setDefault.find()) {
                sym.meta.put("ddl", "MODIFY_COLUMN");
                sym.meta.put("ddlTable", setDefault.group(1));
                sym.meta.put("ddlColumn", stripIdent(setDefault.group(2)));
                sym.meta.put("ddlDefault", setDefault.group(3).strip());
                putSchemaMeta(sym, setDefault.group(1));
            } else if (dropDefault.find()) {
                sym.meta.put("ddl", "MODIFY_COLUMN");
                sym.meta.put("ddlTable", dropDefault.group(1));
                sym.meta.put("ddlColumn", stripIdent(dropDefault.group(2)));
                sym.meta.put("ddlDefault", "");
                putSchemaMeta(sym, dropDefault.group(1));
            }
            sym.meta.put("ddlSql", Texts.truncate(JavaIndexer.normalize(sqlText), 500));
        }
        Matcher create = CREATE_TABLE_HEAD.matcher(sqlText);
        if (create.lookingAt()) {
            sym.meta.put("ddl", "CREATE_TABLE");
            sym.meta.put("ddlTable", create.group(1));
            putSchemaMeta(sym, create.group(1));
            return;
        }
        Matcher any = CREATE_TABLE_ANY.matcher(sqlText);
        if (any.lookingAt()) {
            sym.meta.put("ddl", "CREATE_TABLE");
            sym.meta.put("ddlTable", any.group(1));
            putSchemaMeta(sym, any.group(1));
            return;
        }
        if (upper.startsWith("DROP TABLE")) {
            Matcher dropTable = Pattern.compile("DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([\\w.\\x60\\[\\]]+)",
                    Pattern.CASE_INSENSITIVE).matcher(sqlText);
            if (dropTable.find()) {
                sym.meta.put("ddl", "DROP_TABLE");
                sym.meta.put("ddlTable", dropTable.group(1));
                putSchemaMeta(sym, dropTable.group(1));
            }
        }
    }

    /** True for ADD INDEX / ADD CONSTRAINT / DROP KEY ...: a DDL action, but not a column. */
    /** PostgreSQL/Oracle spell a type change as ALTER COLUMN c TYPE <type>; keep the type only. */
    private String alterDefinition(String definition) {
        String def = definition == null ? "" : definition.strip();
        if (def.regionMatches(true, 0, "TYPE ", 0, 5)) {
            return def.substring(5).strip();
        }
        return def;
    }

    private boolean isNonColumnDefinition(String definition) {
        if (definition == null || definition.isBlank()) {
            return false;
        }
        String first = firstToken(definition.strip()).toUpperCase(Locale.ROOT);
        return NON_COLUMN_DDL.contains(first);
    }

    /** Records the shape of a statement that field-level matching has to treat as a blind spot. */
    private void markStatementShape(Sym sym, String raw, String sqlText) {
        if (raw != null && DYNAMIC_SQL.matcher(raw).find()) {
            sym.meta.put("sqlIncomplete", "true");
        }
        if (sqlText != null && SELECT_STAR.matcher(sqlText).find()) {
            sym.meta.put("selectAll", "true");
        }
        if (isTestPath(sym.filePath)) {
            sym.test = true;
        }
    }

    private void putDefinitionMeta(Sym sym, String definition) {
        String def = definition == null ? "" : definition.strip();
        String type = typeOf(def);
        if (!type.isBlank()) {
            sym.meta.put("ddlType", type);
        }
        Boolean nullable = nullableOf(def);
        if (nullable != null) {
            sym.meta.put("ddlNullable", nullable.toString());
        }
        String defaultValue = defaultValueOf(def);
        if (defaultValue != null) {
            sym.meta.put("ddlDefault", defaultValue);
        }
    }

    private void putSchemaMeta(Sym sym, String tableRef) {
        String schema = schemaOf(tableRef);
        if (schema != null) {
            sym.meta.put("ddlSchema", schema);
        }
    }

    /**
     * Applies CREATE / ALTER statements to the schema snapshot of this revision.
     *
     * Only the parts needed for compatibility decisions are kept: type, nullability and default.
     * The snapshot is a static approximation, not a database: it lets a MODIFY be compared with the
     * column it is modifying, and lets a widened column be recognised instead of reported as a break.
     */
    private void applySchema(String sqlText) {
        sqlText = sqlText.strip();
        // DDL only counts at the start of a statement. A string literal such as
        // INSERT ... VALUES ('create table fake (x int)') must not pollute the schema snapshot.
        String upper = sqlText.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("CREATE TABLE") && !upper.startsWith("ALTER TABLE")
                && !upper.startsWith("DROP TABLE")) {
            return;
        }
        Matcher rename = ALTER_RENAME_COLUMN.matcher(sqlText);
        if (rename.find()) {
            String oldKey = columnKey(rename.group(1), rename.group(2));
            String newKey = columnKey(rename.group(1), rename.group(3));
            String type = index.columnTypes.remove(oldKey);
            String nullable = index.columnNullability.remove(oldKey);
            String defaultValue = index.columnDefaults.remove(oldKey);
            if (type != null) {
                index.columnTypes.put(newKey, type);
            }
            if (nullable != null) {
                index.columnNullability.put(newKey, nullable);
            }
            if (defaultValue != null) {
                index.columnDefaults.put(newKey, defaultValue);
            }
            return;
        }
        Matcher setNotNull = ALTER_SET_NOT_NULL.matcher(sqlText);
        Matcher dropNotNull = ALTER_DROP_NOT_NULL.matcher(sqlText);
        Matcher setDefault = ALTER_SET_DEFAULT.matcher(sqlText);
        Matcher dropDefault = ALTER_DROP_DEFAULT.matcher(sqlText);
        if (setNotNull.find()) {
            index.columnNullability.put(columnKey(setNotNull.group(1), setNotNull.group(2)), "false");
            return;
        }
        if (dropNotNull.find()) {
            index.columnNullability.put(columnKey(dropNotNull.group(1), dropNotNull.group(2)), "true");
            return;
        }
        if (setDefault.find()) {
            index.columnDefaults.put(columnKey(setDefault.group(1), setDefault.group(2)), setDefault.group(3).strip());
            return;
        }
        if (dropDefault.find()) {
            index.columnDefaults.remove(columnKey(dropDefault.group(1), dropDefault.group(2)));
            return;
        }
        Matcher create = CREATE_TABLE_HEAD.matcher(sqlText);
        if (create.lookingAt()) {
            String table = normalizeTable(create.group(1));
            String body = parenthesesBody(sqlText, create.end() - 1);
            for (ColumnDef def : parseColumnDefs(body)) {
                remember(table, def);
            }
            return;
        }
        Matcher add = ALTER_ADD_DEF.matcher(sqlText);
        if (add.find()) {
            if (!isNonColumnDefinition(add.group(2)) && !isNonColumnDefinition(add.group(3))) {
                ColumnDef def = parseColumnDef(add.group(2), alterDefinition(add.group(3)));
                if (def != null) {
                    remember(normalizeTable(add.group(1)), def);
                }
            }
            return;
        }
        Matcher modify = ALTER_MODIFY_DEF.matcher(sqlText);
        if (modify.find()) {
            if (!isNonColumnDefinition(modify.group(2)) && !isNonColumnDefinition(modify.group(3))) {
                ColumnDef def = parseColumnDef(modify.group(2), alterDefinition(modify.group(3)));
                if (def != null) {
                    remember(normalizeTable(modify.group(1)), def);
                }
            }
            return;
        }
        Matcher drop = ALTER_DROP.matcher(sqlText);
        if (drop.find() && !isNonColumnDefinition(drop.group(2))) {
            String key = columnKey(drop.group(1), drop.group(2));
            index.columnTypes.remove(key);
            index.columnNullability.remove(key);
            index.columnDefaults.remove(key);
        }
    }

    private String columnKey(String tableRef, String column) {
        return normalizeTable(tableRef) + "." + stripIdent(column).toLowerCase(Locale.ROOT);
    }

    private void remember(String table, ColumnDef def) {
        String key = table + "." + def.name;
        index.columnTypes.put(key, def.type);
        // A definition that omits nullability/default does not preserve the previous value: the
        // column's shape is now unknown, and keeping the old value would hide a later tightening.
        if (def.nullable != null) {
            index.columnNullability.put(key, def.nullable.toString());
        } else {
            index.columnNullability.remove(key);
        }
        if (def.defaultValue != null) {
            index.columnDefaults.put(key, def.defaultValue);
        } else {
            index.columnDefaults.remove(key);
        }
    }

    private List<ColumnDef> parseColumnDefs(String body) {
        List<ColumnDef> out = new ArrayList<>();
        for (String part : splitTopLevel(body)) {
            String def = part.strip();
            if (def.isEmpty()) {
                continue;
            }
            String lower = def.toLowerCase(Locale.ROOT);
            if (lower.startsWith("primary ") || lower.startsWith("unique ") || lower.startsWith("key ")
                    || lower.startsWith("index ") || lower.startsWith("constraint ") || lower.startsWith("foreign ")
                    || lower.startsWith("check ") || lower.startsWith("fulltext ") || lower.startsWith("spatial ")) {
                continue;
            }
            String name = firstToken(def);
            if (name.isEmpty()) {
                continue;
            }
            ColumnDef column = parseColumnDef(name, def.substring(name.length()).strip());
            if (column != null) {
                out.add(column);
            }
        }
        return out;
    }

    private ColumnDef parseColumnDef(String rawName, String definition) {
        String name = stripIdent(rawName);
        if (name.isEmpty()) {
            return null;
        }
        String def = definition == null ? "" : definition.strip();
        String type = typeOf(def);
        if (type.isBlank()) {
            return null;
        }
        ColumnDef out = new ColumnDef();
        out.name = name.toLowerCase(Locale.ROOT);
        out.type = type;
        out.nullable = nullableOf(def);
        out.defaultValue = defaultValueOf(def);
        return out;
    }

    private String typeOf(String def) {
        String upper = def.toUpperCase(Locale.ROOT);
        int cut = upper.length();
        for (String keyword : List.of(" NOT ", " NULL", " DEFAULT ", " PRIMARY ", " UNIQUE ",
                " AUTO_INCREMENT", " COMMENT ", " REFERENCES ", " CHECK ", " COLLATE ",
                " GENERATED ", " ON ", " AS ")) {
            int idx = upper.indexOf(keyword);
            if (idx >= 0) {
                cut = Math.min(cut, idx);
            }
        }
        return def.substring(0, cut).strip().replaceAll("\\s+", " ");
    }

    private Boolean nullableOf(String def) {
        String upper = def.toUpperCase(Locale.ROOT);
        if (upper.matches("(?s).*\\bPRIMARY\\s+KEY\\b.*") || upper.matches("(?s).*\\bNOT\\s+NULL\\b.*")) {
            return Boolean.FALSE;
        }
        if (upper.matches("(?s).*\\bNULL\\b.*")) {
            return Boolean.TRUE;
        }
        return null;
    }

    private String defaultValueOf(String def) {
        Matcher m = Pattern.compile(
                "(?i)\\bDEFAULT\\s+(.+?)(?=\\s+(?:NOT|NULL|PRIMARY|UNIQUE|AUTO_INCREMENT|COMMENT|REFERENCES|CHECK|COLLATE|GENERATED|ON)\\b|$)",
                Pattern.DOTALL).matcher(def);
        if (!m.find()) {
            return null;
        }
        String value = m.group(1).strip();
        return value.isEmpty() ? null : value;
    }

    private List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(body.substring(start, i));
                start = i + 1;
            }
        }
        out.add(body.substring(start));
        return out;
    }

    private String parenthesesBody(String text, int openIndex) {
        if (openIndex < 0 || openIndex >= text.length() || text.charAt(openIndex) != '(') {
            return "";
        }
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(openIndex + 1, i);
                }
            }
        }
        return text.substring(openIndex + 1);
    }

    private String firstToken(String text) {
        int i = 0;
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return text.substring(0, i);
    }

    private String stripIdent(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.strip().replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
    }

    private String schemaOf(String tableRef) {
        String ref = stripIdent(tableRef);
        int dot = ref.lastIndexOf('.');
        return dot > 0 ? ref.substring(0, dot).toLowerCase(Locale.ROOT) : null;
    }

    private static int migrationVersion(String file) {
        String name = file == null ? "" : file.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        Matcher flyway = Pattern.compile("(?i)^V(\\d+)__").matcher(name);
        if (flyway.find()) {
            try {
                return Integer.parseInt(flyway.group(1));
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }
        Matcher numeric = Pattern.compile("^(\\d+)[-_]").matcher(name);
        if (numeric.find()) {
            try {
                return Integer.parseInt(numeric.group(1));
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private boolean isTestPath(String file) {
        return file != null && (file.contains("/src/test/") || file.startsWith("src/test/"));
    }

    private static final class ColumnDef {
        String name;
        String type;
        Boolean nullable;
        String defaultValue;
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
        // "ON DUPLICATE KEY UPDATE remark = ..." is an assignment, not an UPDATE statement.
        // Removing the clause from the table scan avoids inventing a table called "remark".
        String parseText = sqlText.replaceAll("(?is)\\bon\\s+duplicate\\s+key\\s+update\\b", " ");
        String lower = parseText.toLowerCase(Locale.ROOT);
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        Matcher insert = INSERT_INTO.matcher(parseText);
        while (insert.find()) {
            writes.add(normalizeTable(insert.group(1)));
        }
        Matcher update = UPDATE_TABLE.matcher(parseText);
        while (update.find()) {
            if (!lower.startsWith("select")) {
                writes.add(normalizeTable(update.group(1)));
            }
        }
        Matcher delete = DELETE_FROM.matcher(parseText);
        while (delete.find()) {
            writes.add(normalizeTable(delete.group(1)));
        }
        Matcher from = FROM_JOIN.matcher(parseText);
        while (from.find()) {
            reads.add(normalizeTable(from.group(1)));
        }
        if (reads.isEmpty()) {
            Matcher t = TABLE_AFTER.matcher(parseText);
            while (t.find()) {
                reads.add(normalizeTable(t.group(1)));
            }
        }
        reads.removeAll(writes);
        Map<String, String> aliases = tableAliases(parseText);
        Set<String> readColumns = new LinkedHashSet<>();
        Set<String> writeColumns = new LinkedHashSet<>();
        for (String table : reads) {
            link(sym, tableSymbol(table, sym.modulePath, sym.filePath, sym.startLine), "READS_TABLE", "select from " + table);
        }
        for (String ref : selectColumnRefs(parseText)) {
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
        // Explicit schema qualifiers are evidence of which database the statement addresses.
        // Without them the program-level JDBC URL is the only identity; both are compared by the
        // peer matcher before a table/column name is allowed to become a cross-program contract.
        Map<String, String> tableSchemas = new LinkedHashMap<>();
        Matcher ref = TABLE_REF.matcher(parseText);
        while (ref.find()) {
            String table = normalizeTable(ref.group(1));
            String schema = schemaOf(ref.group(1));
            if (schema != null) {
                tableSchemas.putIfAbsent(table, schema);
            }
        }
        if (!tableSchemas.isEmpty()) {
            StringBuilder encoded = new StringBuilder();
            for (Map.Entry<String, String> entry : tableSchemas.entrySet()) {
                if (encoded.length() > 0) {
                    encoded.append(',');
                }
                encoded.append(entry.getKey()).append('=').append(entry.getValue());
            }
            sym.meta.put("tableSchemas", encoded.toString());
        }
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
        // INSERT INTO t (a, b) SELECT ... has its target column list before SELECT, not before
        // VALUES. Without this it looked like a columnless write and fell back to a table contract.
        Matcher insertSelect = Pattern.compile(
                "\\binsert\\s+into\\s+[\\w.]+\\s*\\(([^()]*)\\)\\s*select\\b",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sqlText);
        if (insertSelect.find()) {
            for (String c : insertSelect.group(1).split(",")) {
                addColumnRef(out, c.strip());
            }
        }
        // ON DUPLICATE KEY UPDATE remark = ... writes remark even though it has no SET keyword.
        Matcher duplicate = Pattern.compile(
                "\\bon\\s+duplicate\\s+key\\s+update\\s+(.*?)(?:\\bwhere\\b|$)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sqlText);
        if (duplicate.find()) {
            for (String part : duplicate.group(1).split(",")) {
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

    /**
     * Splits a script on semicolons that are outside quotes and comments.
     *
     * {@code String.split(";")} cuts a DEFAULT 'a;b' in half and also splits inside block
     * comments. This scanner keeps the original offsets so line numbers remain correct.
     */
    private List<SqlChunk> splitSqlStatements(String content) {
        List<SqlChunk> out = new ArrayList<>();
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            char next = i + 1 < content.length() ? content.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (!singleQuote && !doubleQuote) {
                if (c == '-' && next == '-') {
                    lineComment = true;
                    i++;
                    continue;
                }
                if (c == '/' && next == '*') {
                    blockComment = true;
                    i++;
                    continue;
                }
            }
            if (c == '\'' && !doubleQuote) {
                singleQuote = !singleQuote;
            } else if (c == '"' && !singleQuote) {
                doubleQuote = !doubleQuote;
            } else if (c == ';' && !singleQuote && !doubleQuote) {
                out.add(new SqlChunk(content.substring(start, i), start));
                start = i + 1;
            }
        }
        out.add(new SqlChunk(content.substring(start), start));
        return out;
    }

    private static final class SqlChunk {
        final String text;
        final int start;

        SqlChunk(String text, int start) {
            this.text = text;
            this.start = start;
        }
    }

    /**
     * Removes SQL line and block comments while respecting quoted strings.
     *
     * A commented-out {@code -- ALTER TABLE orders DROP COLUMN remark} used to be parsed as a real
     * schema change and matched every reader of the column. Comment-only edits also changed the
     * statement hash, producing a SQL_CHANGED with no semantic difference.
     */
    private String stripSqlComments(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(sql.length());
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (!singleQuote && !doubleQuote) {
                if (c == '-' && next == '-') {
                    lineComment = true;
                    i++;
                    continue;
                }
                if (c == '/' && next == '*') {
                    blockComment = true;
                    i++;
                    continue;
                }
            }
            if (c == '\'' && !doubleQuote) {
                singleQuote = !singleQuote;
            } else if (c == '"' && !singleQuote) {
                doubleQuote = !doubleQuote;
            }
            out.append(c);
        }
        return out.toString();
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
        // Another annotation may sit between @Column and the field:
        //   @Column(name = "created_at")
        //   @JsonFormat(...)
        //   private Instant createdAt;
        // Requiring the field to be adjacent fell back to camelToSnake and produced a wrong column.
        String between = "(?:\\s*@[\\w.]+(?:\\s*\\([^)]*\\))?)*";
        Matcher m = Pattern.compile("@" + annotation + "\\s*\\(([^)]*)\\)" + between
                + "\\s*(?:private|protected|public)?\\s+[\\w<>,\\[\\].]+\\s+"
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
        // Hibernate's implicit naming splits on lower->upper and acronym->word boundaries, so
        // userID is user_id (not user_i_d) and URLPath is url_path. The old per-character loop
        // guessed the wrong column for every acronym, which is a false mapping either way.
        String snake = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        return snake.toLowerCase(Locale.ROOT);
    }

    private boolean isCollectionType(String type) {
        if (type == null) {
            return false;
        }
        String t = type.trim();
        return t.contains("List<") || t.contains("Set<") || t.contains("Collection<")
                || t.contains("Map<") || t.endsWith("[]");
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
