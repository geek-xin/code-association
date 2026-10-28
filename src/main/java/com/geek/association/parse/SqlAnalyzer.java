package com.geek.association.parse;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.source.FileSource;
import com.geek.association.util.Hashes;
import com.geek.association.util.Json;
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
    /** A DDL identifier as written: bare, backticked, bracket-quoted or double-quoted. */
    private static final String DDL_IDENT = "[\\w\\x60\\[\\]\"]+";
    /** A DDL table reference, possibly schema-qualified. */
    private static final String DDL_TABLE_REF = "[\\w.\\x60\\[\\]\"]+";
    /**
     * {@code ALTER TABLE t <action>[, <action>]...}: one statement can carry several actions
     * (MySQL especially), so the head is cut off here and the rest is split on top-level commas.
     */
    private static final Pattern ALTER_TABLE_HEAD = Pattern.compile(
            "^alter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?(" + DDL_TABLE_REF + ")\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** MySQL {@code RENAME TABLE old TO new [, old2 TO new2]}. */
    private static final Pattern RENAME_TABLE_HEAD = Pattern.compile(
            "^rename\\s+table\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RENAME_TABLE_PAIR = Pattern.compile(
            "^(" + DDL_TABLE_REF + ")\\s+to\\s+(" + DDL_TABLE_REF + ")$", Pattern.CASE_INSENSITIVE);
    /** One {@code ADD [COLUMN] [IF NOT EXISTS] c <definition>} action. */
    private static final Pattern ACTION_ADD = Pattern.compile(
            "^add\\s+(?:column\\s+)?(?:if\\s+not\\s+exists\\s+)?(" + DDL_IDENT + ")\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** One {@code DROP [COLUMN] [IF EXISTS] c} action. */
    private static final Pattern ACTION_DROP = Pattern.compile(
            "^drop\\s+(?:column\\s+)?(?:if\\s+exists\\s+)?(" + DDL_IDENT + ")\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** One MySQL {@code MODIFY [COLUMN] c <definition>} action. */
    private static final Pattern ACTION_MODIFY = Pattern.compile(
            "^modify\\s+(?:column\\s+)?(?:if\\s+exists\\s+)?(" + DDL_IDENT + ")\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** One standard {@code ALTER [COLUMN] c TYPE / SET NOT NULL / SET DEFAULT ...} action. */
    private static final Pattern ACTION_ALTER = Pattern.compile(
            "^alter\\s+(?:column\\s+)?(?:if\\s+exists\\s+)?(" + DDL_IDENT + ")\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** One MySQL {@code CHANGE [COLUMN] old new <definition>} action: a rename plus a redefinition. */
    private static final Pattern ACTION_CHANGE = Pattern.compile(
            "^change\\s+(?:column\\s+)?(" + DDL_IDENT + ")\\s+(" + DDL_IDENT + ")\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** {@code ALTER TABLE t RENAME [TO|AS] newTable}. */
    private static final Pattern ACTION_RENAME_TABLE = Pattern.compile(
            "^rename\\s+(?:to|as)\\s+(" + DDL_TABLE_REF + ")\\s*$", Pattern.CASE_INSENSITIVE);
    /** {@code ALTER TABLE t RENAME [COLUMN] old TO new}. */
    private static final Pattern ACTION_RENAME_COLUMN = Pattern.compile(
            "^rename\\s+(?:column\\s+)?(" + DDL_IDENT + ")\\s+(?:to|as)\\s+(" + DDL_IDENT + ")\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** Definitions where the database fills the column itself, so an INSERT may omit it. */
    private static final Pattern AUTO_POPULATED = Pattern.compile(
            "\\b(?:AUTO_INCREMENT|GENERATED|IDENTITY)\\b", Pattern.CASE_INSENSITIVE);
    /** Clauses whose tail cannot describe how a column's value is produced. */
    private static final Pattern DEFINITION_TAIL = Pattern.compile(
            "\\b(?:COMMENT|REFERENCES|CHECK|COLLATE)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_TABLE_HEAD = Pattern.compile(
            "\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([\\w.\\x60\\[\\]]+)\\s*\\(",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_TABLE_ANY = Pattern.compile(
            "\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([\\w.\\x60\\[\\]]+)",
            Pattern.CASE_INSENSITIVE);
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
    /** Compiled once: this used to be built for every DROP TABLE statement seen. */
    private static final Pattern DROP_TABLE = Pattern.compile(
            "DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([\\w.\\x60\\[\\]]+)",
            Pattern.CASE_INSENSITIVE);
    /** Words that follow a table reference but are not aliases. */
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "where", "set", "values", "select", "from", "join", "left", "right", "inner", "outer", "full",
            "cross", "on", "group", "order", "by", "having", "limit", "offset", "union", "and", "or",
            "as", "insert", "update", "delete", "into", "duplicate", "ignore", "returning", "for");
    /**
     * ALTER TABLE action words that name an object or a table option, not a column.
     *
     * {@code ADD INDEX}, {@code DROP CONSTRAINT}, {@code ADD PARTITION} and friends are real DDL but
     * must never become a column contract: a contract on a column that does not exist would match
     * nobody, and a contract on a table option would match everything.
     */
    private static final Set<String> NON_COLUMN_DDL = Set.of(
            "COLUMN", "COLUMNS", "INDEX", "KEY", "CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE", "CHECK",
            "FULLTEXT", "SPATIAL", "PARTITION", "ENGINE", "CONVERT", "DEFAULT", "CHARACTER", "CHARSET",
            "COLLATE", "AUTO_INCREMENT", "ALGORITHM", "LOCK", "FORCE", "ORDER", "TABLESPACE", "WITHOUT",
            "IMPORT", "DISCARD", "ENABLE", "DISABLE", "TRUNCATE", "EXCHANGE", "REORGANIZE", "REBUILD",
            "COALESCE", "ATTACH", "DETACH", "ANALYZE", "OPTIMIZE", "REPAIR", "VALIDATE", "UPGRADE",
            "IDENTITY", "EXPRESSION", "SET", "DROP");

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
                // A chunk starts right after the previous semicolon, so it usually begins with
                // the newline that separates the statements. Counting that newline as "before"
                // the chunk gave every statement of a multi-statement script the line of the
                // previous one; the key is file#line, so the later statements then overwrote the
                // earlier ones in the index and the change detector saw phantom removed actions.
                int from = chunk.start;
                while (from < content.length() && Character.isWhitespace(content.charAt(from))) {
                    from++;
                }
                int line = lineOf(content, from);
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
                // One parse feeds both the statement metadata and the snapshot: two parsers would
                // drift, and the snapshot must mirror exactly the actions the report describes.
                List<DdlAction> actions = ddlActions(sqlText);
                detectDdl(sym, sqlText, actions);
                // Test schema must not become the production schema snapshot: a testcontainers
                // DDL change says nothing about what other programs will see at runtime.
                if (!sym.test) {
                    applySchema(sqlText, actions);
                }
            }
        }
    }

    /**
     * Records the DDL a statement carries, including every action of a multi-action ALTER TABLE.
     *
     * The legacy {@code ddl*} keys keep the first action, so a reader written before this change
     * sees exactly what it saw before. The complete ordered list goes to {@code ddlActions} as
     * JSON: a single ALTER can add one column and drop another, and hiding the second action was
     * what made the report miss it. A statement with no column DDL writes neither, so
     * {@code ALTER TABLE ... ADD INDEX} stays a non-schema change.
     */
    private void detectDdl(Sym sym, String sqlText, List<DdlAction> actions) {
        String sql = sqlText.strip();
        String upper = sql.toUpperCase(Locale.ROOT);
        if (upper.startsWith("ALTER TABLE") || upper.startsWith("RENAME TABLE")) {
            sym.meta.put("ddlSql", Texts.truncate(JavaIndexer.normalize(sql), 500));
        }
        if (actions.isEmpty()) {
            return;
        }
        DdlAction first = actions.get(0);
        sym.meta.put("ddl", legacyKind(first.kind));
        sym.meta.put("ddlTable", first.rawTable);
        if ("RENAME_TABLE".equals(first.kind)) {
            sym.meta.put("ddlRenamedToTable", first.renamedTo);
        } else if (!first.column.isEmpty()) {
            sym.meta.put("ddlColumn", first.column);
        }
        if ("RENAME_COLUMN".equals(first.kind)) {
            // A column rename is a drop of the old name for every existing reader or writer; the
            // new name cannot already be a dependency of another program.
            sym.meta.put("ddlRenamedTo", first.renamedTo);
        }
        if (first.type != null && !first.type.isBlank()) {
            sym.meta.put("ddlType", first.type);
        }
        if (first.nullable != null) {
            sym.meta.put("ddlNullable", first.nullable);
        }
        if (first.defaultValue != null) {
            sym.meta.put("ddlDefault", first.defaultValue);
        }
        putSchemaMeta(sym, first.rawTable);
        sym.meta.put("ddlActions", Json.write(actionJson(actions)));
    }

    /** The pre-existing spelling of an action kind: a column rename has always been a column drop. */
    private static String legacyKind(String kind) {
        return "RENAME_COLUMN".equals(kind) ? "DROP_COLUMN" : kind;
    }

    /**
     * Parses every top-level action of one DDL statement, in statement order.
     *
     * {@code ADD a INT, ADD b INT} used to be read as {@code ADD a} only, and {@code ADD a, DROP b}
     * even carried the ADD type on the DROP record. Commas inside parentheses or string literals do
     * not split an action. Unsupported actions (indexes, constraints, table options) are skipped
     * instead of being guessed as columns.
     */
    private List<DdlAction> ddlActions(String sqlText) {
        String sql = sqlText == null ? "" : sqlText.strip();
        List<DdlAction> out = new ArrayList<>();
        Matcher create = CREATE_TABLE_HEAD.matcher(sql);
        if (create.lookingAt()) {
            out.add(newAction("CREATE_TABLE", create.group(1)));
            return out;
        }
        Matcher createAny = CREATE_TABLE_ANY.matcher(sql);
        if (createAny.lookingAt()) {
            out.add(newAction("CREATE_TABLE", createAny.group(1)));
            return out;
        }
        Matcher dropTable = DROP_TABLE.matcher(sql);
        if (dropTable.lookingAt()) {
            out.add(newAction("DROP_TABLE", dropTable.group(1)));
            return out;
        }
        Matcher renameTable = RENAME_TABLE_HEAD.matcher(sql);
        if (renameTable.matches()) {
            for (String part : splitTopLevel(renameTable.group(1))) {
                Matcher pair = RENAME_TABLE_PAIR.matcher(part.strip());
                if (!pair.matches()) {
                    continue;
                }
                DdlAction action = newAction("RENAME_TABLE", pair.group(1));
                action.renamedTo = normalizeTable(pair.group(2));
                out.add(action);
            }
            return out;
        }
        Matcher alter = ALTER_TABLE_HEAD.matcher(sql);
        if (!alter.matches()) {
            return out;
        }
        for (String part : splitTopLevel(alter.group(2))) {
            DdlAction action = parseAlterAction(alter.group(1), part.strip());
            if (action != null) {
                out.add(action);
            }
        }
        return out;
    }

    /** One {@code ADD / DROP / MODIFY / ALTER / CHANGE / RENAME} action, or null when it is not a column. */
    private DdlAction parseAlterAction(String tableRef, String part) {
        Matcher add = ACTION_ADD.matcher(part);
        if (add.matches()) {
            if (isNonColumnAction(add.group(1))) {
                return null;
            }
            DdlAction action = newAction("ADD_COLUMN", tableRef);
            action.column = stripIdent(add.group(1));
            putDefinition(action, alterDefinition(add.group(2)));
            return action;
        }
        Matcher drop = ACTION_DROP.matcher(part);
        if (drop.matches()) {
            if (isNonColumnAction(drop.group(1))) {
                return null;
            }
            DdlAction action = newAction("DROP_COLUMN", tableRef);
            action.column = stripIdent(drop.group(1));
            return action;
        }
        Matcher modify = ACTION_MODIFY.matcher(part);
        if (modify.matches()) {
            if (isNonColumnAction(modify.group(1))) {
                return null;
            }
            DdlAction action = newAction("MODIFY_COLUMN", tableRef);
            action.column = stripIdent(modify.group(1));
            putDefinition(action, alterDefinition(modify.group(2)));
            return action;
        }
        Matcher alter = ACTION_ALTER.matcher(part);
        if (alter.matches()) {
            if (isNonColumnAction(alter.group(1))) {
                return null;
            }
            return alterColumnAction(tableRef, alter.group(1), alter.group(2).strip());
        }
        Matcher change = ACTION_CHANGE.matcher(part);
        if (change.matches()) {
            DdlAction action = newAction("RENAME_COLUMN", tableRef);
            action.column = stripIdent(change.group(1));
            action.renamedTo = stripIdent(change.group(2));
            putDefinition(action, alterDefinition(change.group(3)));
            return action;
        }
        Matcher renameTable = ACTION_RENAME_TABLE.matcher(part);
        if (renameTable.matches()) {
            DdlAction action = newAction("RENAME_TABLE", tableRef);
            action.renamedTo = normalizeTable(renameTable.group(1));
            return action;
        }
        Matcher renameColumn = ACTION_RENAME_COLUMN.matcher(part);
        if (renameColumn.matches()) {
            DdlAction action = newAction("RENAME_COLUMN", tableRef);
            action.column = stripIdent(renameColumn.group(1));
            action.renamedTo = stripIdent(renameColumn.group(2));
            return action;
        }
        return null;
    }

    /**
     * {@code ALTER [COLUMN] c TYPE t / SET DATA TYPE t / SET NOT NULL / DROP NOT NULL / SET DEFAULT d
     * / DROP DEFAULT}. The property not named by the action stays unknown instead of being reset.
     */
    private DdlAction alterColumnAction(String tableRef, String rawColumn, String rest) {
        DdlAction action = newAction("MODIFY_COLUMN", tableRef);
        action.column = stripIdent(rawColumn);
        String upper = rest.toUpperCase(Locale.ROOT);
        if (upper.startsWith("SET DATA TYPE ")) {
            putDefinition(action, rest.substring("SET DATA TYPE ".length()).strip());
        } else if (upper.startsWith("TYPE ")) {
            putDefinition(action, rest.substring("TYPE ".length()).strip());
        } else if (upper.matches("(?s)SET\\s+NOT\\s+NULL")) {
            action.nullable = "false";
        } else if (upper.matches("(?s)DROP\\s+NOT\\s+NULL")) {
            action.nullable = "true";
        } else if (upper.startsWith("SET DEFAULT ")) {
            action.defaultValue = rest.substring("SET DEFAULT ".length()).strip();
        } else if (upper.matches("(?s)DROP\\s+DEFAULT")) {
            // The empty string is "no default", which is a real change; null means "not stated".
            action.defaultValue = "";
        } else {
            putDefinition(action, alterDefinition(rest));
        }
        return action;
    }

    /** One action, as written to {@code ddlActions}: the 8 fields are always present. */
    private static List<Map<String, Object>> actionJson(List<DdlAction> actions) {
        List<Map<String, Object>> out = new ArrayList<>(actions.size());
        for (DdlAction action : actions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", action.kind);
            item.put("table", action.table);
            item.put("column", action.column);
            item.put("type", action.type);
            item.put("nullable", action.nullable);
            item.put("default", action.defaultValue);
            item.put("schema", action.schema);
            item.put("renamedTo", action.renamedTo);
            out.add(item);
        }
        return out;
    }

    private DdlAction newAction(String kind, String rawTable) {
        DdlAction action = new DdlAction();
        action.kind = kind;
        action.rawTable = rawTable == null ? "" : rawTable.strip();
        action.table = normalizeTable(action.rawTable);
        String schema = schemaOf(action.rawTable);
        action.schema = schema == null ? "" : schema;
        return action;
    }

    /** Copies type / nullability / default out of a column definition clause. */
    private void putDefinition(DdlAction action, String definition) {
        String def = definition == null ? "" : definition.strip();
        if (def.isEmpty()) {
            return;
        }
        String type = typeOf(def);
        if (!type.isBlank()) {
            action.type = type;
        }
        Boolean nullable = nullableOf(def);
        // A column the database fills itself - MySQL AUTO_INCREMENT, SQL Server IDENTITY, standard
        // GENERATED ALWAYS AS - may be omitted by an INSERT even when it is NOT NULL. Reporting it
        // as NOT NULL would make the required-column rule claim a write-side break no caller hits.
        if (nullable != null && !(Boolean.FALSE.equals(nullable) && databaseFilled(def))) {
            action.nullable = nullable.toString();
        }
        String defaultValue = defaultValueOf(def);
        if (defaultValue != null) {
            action.defaultValue = defaultValue;
        }
    }

    /**
     * True when the database produces the column's value.
     *
     * Quoted text and the trailing COMMENT / REFERENCES / CHECK / COLLATE clauses are ignored, so
     * a default or a comment that merely mentions the word is not mistaken for a generated column.
     */
    private boolean databaseFilled(String definition) {
        StringBuilder stripped = new StringBuilder(definition.length());
        char quote = 0;
        for (int i = 0; i < definition.length(); i++) {
            char c = definition.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++; // the escaped character cannot close the literal
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '\u0060') {
                quote = c;
                stripped.append(' ');
                continue;
            }
            stripped.append(c);
        }
        String text = stripped.toString();
        Matcher tail = DEFINITION_TAIL.matcher(text);
        return AUTO_POPULATED.matcher(tail.find() ? text.substring(0, tail.start()) : text).find();
    }

    /** True when the token after ADD / DROP names an object rather than a column. */
    private boolean isNonColumnAction(String token) {
        if (token == null) {
            return false;
        }
        String raw = token.strip();
        // A quoted identifier is a name by construction, even when it spells a keyword:
        // ADD COLUMN "index" INT adds a column called index.
        if (raw.startsWith("\u0060") || raw.startsWith("\"") || raw.startsWith("[")) {
            return false;
        }
        return NON_COLUMN_DDL.contains(stripIdent(raw).toUpperCase(Locale.ROOT));
    }

    /** PostgreSQL/Oracle spell a type change as ALTER COLUMN c TYPE <type>; keep the type only. */
    private String alterDefinition(String definition) {
        String def = definition == null ? "" : definition.strip();
        if (def.regionMatches(true, 0, "TYPE ", 0, 5)) {
            return def.substring(5).strip();
        }
        return def;
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

    private void putSchemaMeta(Sym sym, String tableRef) {
        String schema = schemaOf(tableRef);
        if (schema != null) {
            sym.meta.put("ddlSchema", schema);
        }
    }

    /**
     * Applies one DDL statement to the schema snapshot of this revision, action by action.
     *
     * Every action of a multi-action ALTER is applied in statement order, mirroring the database:
     * {@code ADD a, DROP b} leaves both effects behind, and a rename moves the column's shape
     * instead of losing it. Only the parts needed for compatibility decisions are kept: type,
     * nullability and default. The snapshot is a static approximation, not a database: it lets a
     * MODIFY be compared with the column it is modifying, and lets a widened column be recognised
     * instead of reported as a break.
     */
    private void applySchema(String sqlText, List<DdlAction> actions) {
        String sql = sqlText == null ? "" : sqlText.strip();
        // DDL only counts at the start of a statement. A string literal such as
        // INSERT ... VALUES ('create table fake (x int)') must not pollute the schema snapshot.
        Matcher create = CREATE_TABLE_HEAD.matcher(sql);
        if (create.lookingAt()) {
            String table = normalizeTable(create.group(1));
            for (ColumnDef def : parseColumnDefs(parenthesesBody(sql, create.end() - 1))) {
                remember(table, def);
            }
            return;
        }
        for (DdlAction action : actions) {
            switch (action.kind) {
                case "ADD_COLUMN", "MODIFY_COLUMN" -> applyColumnShape(action);
                case "DROP_COLUMN" -> dropColumn(action);
                case "RENAME_COLUMN" -> renameColumn(action);
                case "RENAME_TABLE" -> renameTable(action.table, action.renamedTo);
                default -> {
                    // CREATE TABLE is handled above; DROP_TABLE adds nothing to the snapshot,
                    // exactly as before this change.
                }
            }
        }
    }

    /** Applies an ADD / MODIFY definition to the snapshot the same way a CREATE TABLE column does. */
    private void applyColumnShape(DdlAction action) {
        String column = action.column.toLowerCase(Locale.ROOT);
        if (action.type != null && !action.type.isBlank()) {
            remember(action.table, columnDef(column, action));
            return;
        }
        // ALTER COLUMN ... SET/DROP NOT NULL | DEFAULT states one property and leaves the rest
        // alone: resetting the others would invent a schema change the DDL never stated.
        String key = action.table + "." + column;
        if (action.nullable != null) {
            index.columnNullability.put(key, action.nullable);
        }
        if (action.defaultValue != null) {
            if (action.defaultValue.isEmpty()) {
                index.columnDefaults.remove(key);
            } else {
                index.columnDefaults.put(key, action.defaultValue);
            }
        }
    }

    private ColumnDef columnDef(String column, DdlAction action) {
        ColumnDef def = new ColumnDef();
        def.name = column;
        def.type = action.type;
        def.nullable = "true".equalsIgnoreCase(action.nullable) ? Boolean.TRUE
                : "false".equalsIgnoreCase(action.nullable) ? Boolean.FALSE : null;
        def.defaultValue = action.defaultValue;
        return def;
    }

    private void dropColumn(DdlAction action) {
        String key = action.table + "." + action.column.toLowerCase(Locale.ROOT);
        index.columnTypes.remove(key);
        index.columnNullability.remove(key);
        index.columnDefaults.remove(key);
    }

    /** A rename keeps the column's shape: dropping it would turn every reader into a false alarm. */
    private void renameColumn(DdlAction action) {
        String oldKey = action.table + "." + action.column.toLowerCase(Locale.ROOT);
        String newKey = action.table + "." + action.renamedTo.toLowerCase(Locale.ROOT);
        moveEntry(index.columnTypes, oldKey, newKey);
        moveEntry(index.columnNullability, oldKey, newKey);
        moveEntry(index.columnDefaults, oldKey, newKey);
        // MySQL CHANGE COLUMN carries a full new definition next to the new name.
        if (action.type != null && !action.type.isBlank()) {
            remember(action.table, columnDef(action.renamedTo.toLowerCase(Locale.ROOT), action));
        }
    }

    /** A table rename moves the whole shape: the new name is the same table, not a new one. */
    private void renameTable(String oldTable, String newTable) {
        if (oldTable.isEmpty() || newTable.isEmpty() || oldTable.equals(newTable)) {
            return;
        }
        moveTablePrefix(index.columnTypes, oldTable, newTable);
        moveTablePrefix(index.columnNullability, oldTable, newTable);
        moveTablePrefix(index.columnDefaults, oldTable, newTable);
    }

    private void moveTablePrefix(Map<String, String> columns, String oldTable, String newTable) {
        String prefix = oldTable + ".";
        List<String> keys = new ArrayList<>();
        for (String key : columns.keySet()) {
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        }
        for (String key : keys) {
            String value = columns.remove(key);
            if (value != null) {
                columns.put(newTable + "." + key.substring(prefix.length()), value);
            }
        }
    }

    private void moveEntry(Map<String, String> columns, String oldKey, String newKey) {
        if (oldKey.equals(newKey)) {
            return;
        }
        String value = columns.remove(oldKey);
        if (value != null) {
            columns.put(newKey, value);
        }
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

    /**
     * Splits on commas that are inside neither a parenthesis nor a quoted string.
     *
     * A definition such as {@code DEFAULT 'a,b'} carries a comma that must stay inside its action,
     * and the same scanner splits the column list of {@code CREATE TABLE t (...)}.
     */
    private List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean singleQuote = false;
        boolean doubleQuote = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (singleQuote) {
                if (c == '\\') {
                    i++; // the escaped character cannot close the string
                } else if (c == '\'') {
                    singleQuote = false;
                }
                continue;
            }
            if (doubleQuote) {
                if (c == '"') {
                    doubleQuote = false;
                }
                continue;
            }
            if (c == '\'') {
                singleQuote = true;
            } else if (c == '"') {
                doubleQuote = true;
            } else if (c == '(') {
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

    /**
     * One action of a DDL statement, in statement order, as written to {@code ddlActions}.
     *
     * {@code column} is empty for a table-level action, {@code type}/{@code nullable}/{@code default}
     * are null when the statement does not state them, and {@code defaultValue} is the empty string
     * for {@code DROP DEFAULT} (a stated removal) as opposed to null (not stated at all).
     */
    private static final class DdlAction {
        String kind;
        /** Normalised table name without schema: the snapshot key. */
        String table;
        /** The table reference as written, for the legacy {@code ddlTable} key. */
        String rawTable;
        String column = "";
        String type;
        String nullable;
        String defaultValue;
        String schema = "";
        String renamedTo = "";
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
                if (c == '#' && startsHashComment(next)) {
                    lineComment = true;
                    continue;
                }
            }
            if (singleQuote && c == '\\') {
                // MySQL lets a backslash escape the next character: without this, a value ending
                // in \' would look like a closed string and the rest of the statement would be
                // parsed as SQL.
                i++;
                continue;
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

    /**
     * MySQL starts a line comment with {@code #}. The same character is a JSON path operator in
     * PostgreSQL ({@code #>}, {@code #>>}, {@code #-}) and the prefix of a MyBatis placeholder
     * ({@code #{id}}), so only a bare {@code #} opens a comment.
     */
    private static boolean startsHashComment(char next) {
        return next != '{' && next != '>' && next != '-';
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
                if (c == '#' && startsHashComment(next)) {
                    lineComment = true;
                    continue;
                }
            }
            if (singleQuote && c == '\\') {
                // Same escape rule as the statement splitter: keep the pair, do not let the
                // escaped quote close the string.
                out.append(c);
                if (i + 1 < sql.length()) {
                    out.append(sql.charAt(i + 1));
                    i++;
                }
                continue;
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
