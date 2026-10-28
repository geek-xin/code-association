package com.geek.association.diff;

import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.FileChange;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.parse.JavaIndexer;
import com.geek.association.parse.TypeResolver;
import com.geek.association.util.ColumnTypeCompatibility;
import com.geek.association.util.Texts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a raw file diff into semantic change records.
 *
 * The detector never compares text alone: it matches symbols of the base index against the head
 * index by stable key (module + qualified name + signature), then classifies what actually changed
 * - signature, body, branch condition, field type, enum constant, API contract, SQL statement,
 * configuration value, DDL - because every class propagates differently.
 */
public class ChangeDetector {

    public static final String METHOD_ADDED = "METHOD_ADDED";
    public static final String METHOD_REMOVED = "METHOD_REMOVED";
    public static final String METHOD_SIGNATURE_CHANGED = "METHOD_SIGNATURE_CHANGED";
    public static final String METHOD_BODY_CHANGED = "METHOD_BODY_CHANGED";
    public static final String METHOD_CONDITION_CHANGED = "METHOD_CONDITION_CHANGED";
    public static final String METHOD_VISIBILITY_CHANGED = "METHOD_VISIBILITY_CHANGED";
    public static final String TYPE_ADDED = "TYPE_ADDED";
    public static final String TYPE_REMOVED = "TYPE_REMOVED";
    public static final String TYPE_HIERARCHY_CHANGED = "TYPE_HIERARCHY_CHANGED";
    public static final String FIELD_ADDED = "FIELD_ADDED";
    public static final String FIELD_REMOVED = "FIELD_REMOVED";
    public static final String FIELD_TYPE_CHANGED = "FIELD_TYPE_CHANGED";
    public static final String ENUM_CONSTANT_REMOVED = "ENUM_CONSTANT_REMOVED";
    public static final String ENUM_CONSTANT_ADDED = "ENUM_CONSTANT_ADDED";
    public static final String API_ADDED = "API_ADDED";
    public static final String API_REMOVED = "API_REMOVED";
    public static final String API_CONTRACT_CHANGED = "API_CONTRACT_CHANGED";
    public static final String SQL_CHANGED = "SQL_CHANGED";
    public static final String SQL_REMOVED = "SQL_REMOVED";
    public static final String SQL_ADDED = "SQL_ADDED";
    public static final String DDL_CHANGED = "DDL_CHANGED";
    public static final String CONFIG_CHANGED = "CONFIG_CHANGED";
    public static final String CONFIG_ADDED = "CONFIG_ADDED";
    public static final String CONFIG_REMOVED = "CONFIG_REMOVED";
    public static final String TEST_CHANGED = "TEST_CHANGED";
    public static final String BUILD_CHANGED = "BUILD_CHANGED";
    public static final String TOPIC_ADDED = "TOPIC_ADDED";
    public static final String TOPIC_REMOVED = "TOPIC_REMOVED";
    public static final String MESSAGE_ROUTE_CHANGED = "MESSAGE_ROUTE_CHANGED";
    public static final String MESSAGE_TOPOLOGY_REMOVED = "MESSAGE_TOPOLOGY_REMOVED";
    public static final String MESSAGE_GROUP_CHANGED = "MESSAGE_GROUP_CHANGED";
    public static final String MESSAGE_FILTER_CHANGED = "MESSAGE_FILTER_CHANGED";
    public static final String MESSAGE_SCHEMA_CHANGED = "MESSAGE_SCHEMA_CHANGED";
    public static final String MESSAGE_FIELD_ADDED = "MESSAGE_FIELD_ADDED";
    public static final String MESSAGE_FIELD_REMOVED = "MESSAGE_FIELD_REMOVED";
    public static final String MESSAGE_FIELD_TYPE_CHANGED = "MESSAGE_FIELD_TYPE_CHANGED";
    public static final String MESSAGE_REQUIRED_CHANGED = "MESSAGE_REQUIRED_CHANGED";
    public static final String MESSAGE_ENUM_CHANGED = "MESSAGE_ENUM_CHANGED";

    private final CodeIndex baseIndex;
    private final CodeIndex headIndex;

    public ChangeDetector(CodeIndex baseIndex, CodeIndex headIndex) {
        this.baseIndex = baseIndex;
        this.headIndex = headIndex;
    }

    public List<ChangeRecord> detect(List<FileChange> files) {
        List<ChangeRecord> out = new ArrayList<>();
        detectInto(files, out);
        out.removeIf(java.util.Objects::isNull);
        // A CREATE TABLE edit does not always produce a DDL record through the per-file comparison:
        // the statement key can move with the file/line, and the column definition is not the
        // statement's ddl metadata. Comparing the schema snapshots catches those changes.
        detectSchemaChanges(out);
        suppressMovedStatements(out);
        return out;
    }

    /**
     * A statement that moved to another file is not a contract change.
     *
     * SQL symbols are keyed by file and line, so renaming a mapper or moving an insert into a
     * shared XML fragment used to produce a SQL_REMOVED plus a SQL_ADDED. The removed side then
     * looked like "this program stopped writing orders.remark", which is exactly the kind of
     * confident-looking false positive the report cannot afford.
     *
     * Pairing is deliberately conservative: only an added and a removed statement with the same
     * normalized text are cancelled. If the text also changed, both records stay.
     */
    private void suppressMovedStatements(List<ChangeRecord> out) {
        Map<String, ChangeRecord> added = new LinkedHashMap<>();
        for (ChangeRecord cr : out) {
            if (SQL_ADDED.equals(cr.changeType)) {
                String sql = normalizeSql(cr.newValue);
                if (!sql.isBlank()) {
                    added.putIfAbsent(safe(cr.modulePath) + "|" + sql, cr);
                }
            }
        }
        if (added.isEmpty()) {
            return;
        }
        Set<ChangeRecord> cancelled = new LinkedHashSet<>();
        for (ChangeRecord cr : out) {
            if (!SQL_REMOVED.equals(cr.changeType)) {
                continue;
            }
            String sql = normalizeSql(cr.oldValue);
            if (sql.isBlank()) {
                continue;
            }
            ChangeRecord pair = added.remove(safe(cr.modulePath) + "|" + sql);
            if (pair != null) {
                cancelled.add(cr);
                cancelled.add(pair);
            }
        }
        out.removeAll(cancelled);
    }

    private static String normalizeSql(String sql) {
        // Case is preserved: SQL string literals are case-sensitive, and conflating 'A' with 'a'
        // would cancel a real edit. Whitespace is the only difference a file move introduces.
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

    private void detectInto(List<FileChange> files, List<ChangeRecord> out) {
        for (FileChange fc : files) {
            String path = fc.path;
            if (path.endsWith("pom.xml")) {
                ChangeRecord cr = baseRecord(BUILD_CHANGED, "MODULE", path, "", 0, 0);
                cr.oldValue = fc.oldHash;
                cr.newValue = fc.newHash;
                cr.semanticSummary = "Maven 构建模型发生变化（" + statusText(fc.status) + "）";
                cr.riskSeed = 0.6;
                cr.symbolKey = "pom:" + path;
                out.add(cr);
                continue;
            }
            if (path.endsWith(".java")) {
                detectJava(fc, out);
            }
            if (path.endsWith(".xml") || path.endsWith(".sql")) {
                detectSql(fc, out);
            }
            if (path.endsWith(".yml") || path.endsWith(".yaml") || path.endsWith(".properties")) {
                detectConfig(fc, out);
            }
        }
        detectMessaging(out);
    }

    /**
     * Messaging contracts live in generated symbols rather than one obvious source line. Comparing
     * the normalised contract maps is what makes a topic deletion or schema change visible without
     * depending on the file that happens to contain the producer.
     */
    private void detectMessaging(List<ChangeRecord> out) {
        Map<String, Sym> oldTopics = messagingOf(baseIndex, "MESSAGE_TOPIC");
        Map<String, Sym> newTopics = messagingOf(headIndex, "MESSAGE_TOPIC");
        for (Map.Entry<String, Sym> e : newTopics.entrySet()) {
            if (!oldTopics.containsKey(e.getKey())) {
                Sym now = e.getValue();
                ChangeRecord cr = baseRecord(TOPIC_ADDED, "MESSAGE_TOPIC", now.filePath,
                        now.qualifiedName, now.startLine, now.endLine, now.modulePath, now.key,
                        "", now.qualifiedName, 0.25, "新增消息主题：" + now.qualifiedName);
                cr.tags.add("MESSAGE");
                cr.extra.put("middleware", now.meta.get("middleware"));
                out.add(cr);
            }
        }
        Map<String, Sym> oldExchanges = messagingOf(baseIndex, "MESSAGE_EXCHANGE");
        Map<String, Sym> newExchanges = messagingOf(headIndex, "MESSAGE_EXCHANGE");
        for (Map.Entry<String, Sym> e : oldExchanges.entrySet()) {
            if (!newExchanges.containsKey(e.getKey())) {
                Sym before = e.getValue();
                ChangeRecord cr = baseRecord(MESSAGE_TOPOLOGY_REMOVED, "MESSAGE_EXCHANGE", before.filePath,
                        before.qualifiedName, before.startLine, before.endLine, before.modulePath,
                        before.key, before.qualifiedName, "", 0.9,
                        "删除消息交换机：" + before.qualifiedName);
                cr.tags.add("MESSAGE");
                cr.tags.add("CONTRACT");
                cr.extra.put("middleware", before.meta.get("middleware"));
                out.add(cr);
            }
        }
        for (Map.Entry<String, Sym> e : oldTopics.entrySet()) {
            if (!newTopics.containsKey(e.getKey())) {
                Sym before = e.getValue();
                ChangeRecord cr = baseRecord(TOPIC_REMOVED, "MESSAGE_TOPIC", before.filePath,
                        before.qualifiedName, before.startLine, before.endLine, before.modulePath,
                        before.key, before.qualifiedName, "", 0.9,
                        "删除消息主题：" + before.qualifiedName);
                cr.tags.add("MESSAGE");
                cr.tags.add("CONTRACT");
                cr.extra.put("middleware", before.meta.get("middleware"));
                out.add(cr);
            }
        }
        Map<String, Sym> oldRoutes = messagingOf(baseIndex, "MESSAGE_ROUTE");
        Map<String, Sym> newRoutes = messagingOf(headIndex, "MESSAGE_ROUTE");
        for (Map.Entry<String, Sym> e : oldRoutes.entrySet()) {
            if (!newRoutes.containsKey(e.getKey())) {
                Sym before = e.getValue();
                ChangeRecord cr = baseRecord(MESSAGE_ROUTE_CHANGED, "MESSAGE_ROUTE", before.filePath,
                        before.qualifiedName, before.startLine, before.endLine, before.modulePath,
                        before.key, before.qualifiedName, "", 0.85,
                        "删除消息路由：" + before.qualifiedName);
                cr.tags.add("MESSAGE");
                cr.extra.put("middleware", before.meta.get("middleware"));
                out.add(cr);
            }
        }
        detectSchemas(out);
    }

    private void detectSchemas(List<ChangeRecord> out) {
        Map<String, Sym> oldSchemas = messagingOf(baseIndex, "MESSAGE_SCHEMA");
        Map<String, Sym> newSchemas = messagingOf(headIndex, "MESSAGE_SCHEMA");
        for (Map.Entry<String, Sym> e : newSchemas.entrySet()) {
            Sym now = e.getValue();
            Sym before = oldSchemas.get(e.getKey());
            if (before == null) {
                ChangeRecord cr = schemaRecord(MESSAGE_SCHEMA_CHANGED, now, "", now.meta.get("schemaHash"),
                        0.65, "新增消息契约：" + now.qualifiedName);
                out.add(cr);
                continue;
            }
            String oldHash = before.meta.get("schemaHash");
            String newHash = now.meta.get("schemaHash");
            if (Objects.equals(oldHash, newHash)) {
                continue;
            }
            java.util.Set<String> oldFields = fieldSet(before.meta.get("fields"));
            java.util.Set<String> newFields = fieldSet(now.meta.get("fields"));
            java.util.Set<String> added = new java.util.LinkedHashSet<>(newFields);
            added.removeAll(oldFields);
            java.util.Set<String> removed = new java.util.LinkedHashSet<>(oldFields);
            removed.removeAll(newFields);
            java.util.Set<String> oldRequired = fieldSet(before.meta.get("required"));
            java.util.Set<String> newRequired = fieldSet(now.meta.get("required"));
            boolean requiredAdded = !oldRequired.containsAll(newRequired);
            boolean sameFieldNames = sameFieldNames(oldFields, newFields);
            if (sameFieldNames && !oldFields.equals(newFields)) {
                String type = "PROTOBUF".equals(now.meta.get("format"))
                        ? MESSAGE_SCHEMA_CHANGED : MESSAGE_FIELD_TYPE_CHANGED;
                out.add(schemaRecord(type, now, before.meta.get("fields"),
                        now.meta.get("fields"), 0.9,
                        "消息线协议发生变化：" + now.qualifiedName));
            } else if (requiredAdded) {
                out.add(schemaRecord(MESSAGE_REQUIRED_CHANGED, now, before.meta.get("required"),
                        now.meta.get("required"), 0.85,
                        "消息必填字段发生变化：" + now.qualifiedName));
            } else if (!added.isEmpty()) {
                out.add(schemaRecord(MESSAGE_FIELD_ADDED, now, before.meta.get("fields"),
                        now.meta.get("fields"), 0.35,
                        "新增消息字段：" + now.qualifiedName));
            } else if (!removed.isEmpty()) {
                out.add(schemaRecord(MESSAGE_FIELD_REMOVED, now, before.meta.get("fields"),
                        now.meta.get("fields"), 0.85,
                        "删除消息字段：" + now.qualifiedName));
            } else if (!Objects.equals(before.meta.get("enums"), now.meta.get("enums"))) {
                out.add(schemaRecord(MESSAGE_ENUM_CHANGED, now, before.meta.get("enums"),
                        now.meta.get("enums"), 0.85,
                        "消息枚举值发生变化：" + now.qualifiedName));
            } else {
                out.add(schemaRecord(MESSAGE_FIELD_TYPE_CHANGED, now, before.meta.get("fields"),
                        now.meta.get("fields"), 0.9,
                        "消息字段类型发生变化：" + now.qualifiedName));
            }
        }
    }

    private ChangeRecord schemaRecord(String type, Sym now, String oldValue, String newValue,
                                      double risk, String summary) {
        ChangeRecord cr = baseRecord(type, "MESSAGE_SCHEMA", now.filePath, now.qualifiedName,
                now.startLine, now.endLine, now.modulePath, now.key, oldValue, newValue,
                risk, summary);
        cr.tags.add("MESSAGE");
        cr.tags.add("CONTRACT");
        cr.extra.put("middleware", now.meta.get("middleware"));
        cr.extra.put("schemaHash", now.meta.get("schemaHash"));
        return cr;
    }

    private Map<String, Sym> messagingOf(CodeIndex index, String type) {
        Map<String, Sym> out = new LinkedHashMap<>();
        for (Sym s : index.symbols.values()) {
            if (type.equals(s.type)) {
                out.put(s.key, s);
            }
        }
        return out;
    }

    private java.util.Set<String> fieldSet(String csv) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private boolean sameFieldNames(java.util.Set<String> left, java.util.Set<String> right) {
        return fieldNames(left).equals(fieldNames(right));
    }

    private java.util.Set<String> fieldNames(java.util.Set<String> fields) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String field : fields) {
            int colon = field.indexOf(':');
            out.add(colon < 0 ? field : field.substring(0, colon));
        }
        return out;
    }

    // ------------------------------------------------------------------ java

    private void detectJava(FileChange fc, List<ChangeRecord> out) {
        List<Sym> oldSyms = symbolsOf(baseIndex, fc.oldPath);
        List<Sym> newSyms = symbolsOf(headIndex, fc.path);
        Map<String, Sym> oldByKey = byKey(oldSyms);
        Map<String, Sym> newByKey = byKey(newSyms);

        for (Sym s : newSyms) {
            if (!isReportable(s)) {
                continue;
            }
            Sym old = oldByKey.get(s.key);
            if (old == null) {
                out.add(addedRecord(s));
            } else {
                ChangeRecord cr = compare(s, old);
                if (cr != null) {
                    out.add(cr);
                }
            }
        }
        for (Sym s : oldSyms) {
            if (!isReportable(s)) {
                continue;
            }
            if (!newByKey.containsKey(s.key)) {
                out.add(removedRecord(s));
            }
        }
        detectApiContract(fc, out);
    }

    private ChangeRecord compare(Sym now, Sym before) {
        String type = now.type;
        if ("METHOD".equals(type) || "CONSTRUCTOR".equals(type)) {
            boolean oldPublic = before.modifiers.contains("public");
            boolean newPublic = now.modifiers.contains("public");
            if (oldPublic && !newPublic) {
                return methodRecord(METHOD_VISIBILITY_CHANGED, now, before, 0.85,
                        "方法可见性收窄：public → "
                                + (now.modifiers.isEmpty() ? "package" : String.join(" ", now.modifiers)));
            }
            if (!Objects.equals(before.signatureHash, now.signatureHash)) {
                boolean returnTypeChanged = !Objects.equals(before.returnType, now.returnType);
                boolean paramsChanged = !Objects.equals(before.signature, now.signature);
                String summary;
                if (paramsChanged) {
                    summary = "方法参数列表变化：" + before.display() + " → " + now.display();
                } else if (returnTypeChanged) {
                    summary = "方法返回类型变化：" + before.returnType + " → " + now.returnType;
                } else {
                    summary = "方法签名变化：" + before.display() + " → " + now.display();
                }
                ChangeRecord cr = methodRecord(METHOD_SIGNATURE_CHANGED, now, before, 0.9, summary);
                cr.oldValue = before.signature;
                cr.newValue = now.signature;
                if (returnTypeChanged) {
                    cr.extra.put("oldReturnType", before.returnType);
                    cr.extra.put("newReturnType", now.returnType);
                    cr.tags.add("CONTRACT");
                }
                if (paramsChanged) {
                    cr.tags.add("COMPILE");
                }
                return cr;
            }
            if (!Objects.equals(before.bodyHash, now.bodyHash)) {
                String oldConditions = before.meta.getOrDefault("conditions", "");
                String newConditions = now.meta.getOrDefault("conditions", "");
                if (!oldConditions.equals(newConditions)) {
                    ChangeRecord cr = methodRecord(METHOD_CONDITION_CHANGED, now, before, 0.8,
                            "分支条件发生变化");
                    cr.oldValue = oldConditions;
                    cr.newValue = newConditions;
                    cr.tags.add("BEHAVIOR");
                    cr.tags.add("CONTROL_FLOW");
                    cr.extra.put("oldConditions", splitConditions(oldConditions));
                    cr.extra.put("newConditions", splitConditions(newConditions));
                    return cr;
                }
                ChangeRecord cr = methodRecord(METHOD_BODY_CHANGED, now, before, 0.55,
                        "方法实现发生变化，签名未变");
                cr.tags.add("BEHAVIOR");
                return cr;
            }
            return null;
        }
        if ("FIELD".equals(type) || "ENUM_CONSTANT".equals(type)) {
            if (!Objects.equals(before.signatureHash, now.signatureHash)) {
                ChangeRecord cr = fieldRecord(FIELD_TYPE_CHANGED, now, before, 0.85,
                        "字段类型变化：" + before.returnType + " → " + now.returnType);
                cr.oldValue = before.returnType;
                cr.newValue = now.returnType;
                mappedColumns(cr, now, before);
                return cr;
            }
            if (!Objects.equals(before.bodyHash, now.bodyHash) && now.bodyHash != null) {
                ChangeRecord cr = fieldRecord(METHOD_BODY_CHANGED, now, before, 0.4,
                        "字段初始化逻辑发生变化");
                cr.tags.add("BEHAVIOR");
                return cr;
            }
            return null;
        }
        if ("SQL_STATEMENT".equals(type)) {
            // A schema change is a different event than a statement edit: the first one breaks every
            // program that touches the column, the second one only matters when the write set moved.
            ChangeRecord ddl = ddlChange(now, before, 1.0);
            if (ddl != null) {
                return ddl;
            }
            if (!Objects.equals(before.bodyHash, now.bodyHash)) {
                return sqlChange(SQL_CHANGED, now, before, 0.7, "SQL 语句发生变化：" + now.qualifiedName);
            }
            return null;
        }
        if ("API_ENDPOINT".equals(type)) {
            return null;
        }
        if (isType(type)) {
            if (!Objects.equals(before.meta.get("extends"), now.meta.get("extends"))
                    || !Objects.equals(before.meta.get("implements"), now.meta.get("implements"))) {
                ChangeRecord cr = baseRecord(TYPE_HIERARCHY_CHANGED, type, now.filePath, now.qualifiedName,
                        now.startLine, now.endLine);
                cr.modulePath = now.modulePath;
                cr.symbolKey = now.key;
                cr.oldValue = safe(before.meta.get("extends")) + " / " + safe(before.meta.get("implements"));
                cr.newValue = safe(now.meta.get("extends")) + " / " + safe(now.meta.get("implements"));
                cr.semanticSummary = "类型继承关系发生变化：" + now.qualifiedName;
                cr.riskSeed = 0.75;
                cr.tags.add("CONTRACT");
                return cr;
            }
        }
        return null;
    }

    private ChangeRecord addedRecord(Sym s) {
        if ("METHOD".equals(s.type) || "CONSTRUCTOR".equals(s.type)) {
            return baseRecord(METHOD_ADDED, s.type, s.filePath, s.qualifiedName, s.startLine, s.endLine,
                    s.modulePath, s.key, s.signature, "", 0.2, "新增方法：" + s.display());
        }
        if ("FIELD".equals(s.type)) {
            ChangeRecord cr = baseRecord(FIELD_ADDED, s.type, s.filePath, s.qualifiedName, s.startLine, s.endLine,
                    s.modulePath, s.key, s.signature, "", 0.2, "新增字段：" + s.qualifiedName);
            mappedColumns(cr, s, null);
            return cr;
        }
        if ("ENUM_CONSTANT".equals(s.type)) {
            ChangeRecord cr = baseRecord(ENUM_CONSTANT_ADDED, s.type, s.filePath, s.qualifiedName, s.startLine,
                    s.endLine, s.modulePath, s.key, "", s.qualifiedName, 0.45, "新增枚举值：" + s.qualifiedName);
            cr.tags.add("CONTRACT");
            return cr;
        }
        if (isType(s.type)) {
            return baseRecord(TYPE_ADDED, s.type, s.filePath, s.qualifiedName, s.startLine, s.endLine,
                    s.modulePath, s.key, "", "", 0.15, "新增类型：" + s.qualifiedName);
        }
        if ("SQL_STATEMENT".equals(s.type)) {
            // A brand new script that alters a table is a schema change, not a new query: the other
            // program cares about the column, not about the file that introduced it.
            if (s.meta.get("ddl") != null) {
                ChangeRecord ddl = ddlChange(s, new Sym(), 1.0);
                if (ddl != null) {
                    return ddl;
                }
            }
            ChangeRecord cr = baseRecord(SQL_ADDED, s.type, s.filePath, s.qualifiedName, s.startLine, s.endLine,
                    s.modulePath, s.key, "", s.meta.get("sql"), 0.4, "新增 SQL 语句：" + s.qualifiedName);
            cr.tags.add("DATA");
            columnExtras(cr, null, s);
            return cr;
        }
        return null;
    }

    private ChangeRecord removedRecord(Sym s) {
        if ("METHOD".equals(s.type) || "CONSTRUCTOR".equals(s.type)) {
            ChangeRecord cr = baseRecord(METHOD_REMOVED, s.type, s.filePath, s.qualifiedName, s.startLine,
                    s.endLine, s.modulePath, s.key, s.signature, "", 0.9, "删除方法：" + s.display());
            cr.tags.add("COMPILE");
            return cr;
        }
        if ("FIELD".equals(s.type)) {
            ChangeRecord cr = baseRecord(FIELD_REMOVED, s.type, s.filePath, s.qualifiedName, s.startLine,
                    s.endLine, s.modulePath, s.key, s.signature, "", 0.85, "删除字段：" + s.qualifiedName);
            cr.tags.add("COMPILE");
            cr.tags.add("CONTRACT");
            // The removed field only exists in the base index, so its column mapping has to be
            // captured here; the peer engine cannot look it up afterwards.
            mappedColumns(cr, null, s);
            return cr;
        }
        if ("ENUM_CONSTANT".equals(s.type)) {
            ChangeRecord cr = baseRecord(ENUM_CONSTANT_REMOVED, s.type, s.filePath, s.qualifiedName, s.startLine,
                    s.endLine, s.modulePath, s.key, s.qualifiedName, "", 0.9, "删除枚举值：" + s.qualifiedName);
            cr.tags.add("COMPILE");
            cr.tags.add("CONTRACT");
            return cr;
        }
        if (isType(s.type)) {
            ChangeRecord cr = baseRecord(TYPE_REMOVED, s.type, s.filePath, s.qualifiedName, s.startLine, s.endLine,
                    s.modulePath, s.key, "", "", 0.9, "删除类型：" + s.qualifiedName);
            cr.tags.add("COMPILE");
            return cr;
        }
        if ("SQL_STATEMENT".equals(s.type)) {
            // Removing the script that added a column removes the column: report the schema change
            // rather than "a query disappeared", which no other program can act on.
            if (s.meta.get("ddl") != null) {
                ChangeRecord ddl = ddlChange(new Sym(), s, 1.0);
                if (ddl != null) {
                    return ddl;
                }
            }
            ChangeRecord cr = baseRecord(SQL_REMOVED, s.type, s.filePath, s.qualifiedName, s.startLine, s.endLine,
                    s.modulePath, s.key, s.meta.get("sql"), "", 0.75, "删除 SQL 语句：" + s.qualifiedName);
            cr.tags.add("DATA");
            columnExtras(cr, s, null);
            return cr;
        }
        return null;
    }

    // -------------------------------------------------------------------- API

    private void detectApiContract(FileChange fc, List<ChangeRecord> out) {
        Set<String> oldApis = endpointsOf(baseIndex, fc.oldPath);
        Set<String> newApis = endpointsOf(headIndex, fc.path);
        for (String api : newApis) {
            if (!oldApis.contains(api)) {
                ChangeRecord cr = baseRecord(API_ADDED, "API_ENDPOINT", fc.path, api, 0, 0);
                Sym endpoint = headIndex.symbol("api:" + api);
                cr.modulePath = endpoint == null ? null : endpoint.modulePath;
                cr.symbolKey = "api:" + api;
                cr.newValue = api;
                cr.semanticSummary = "新增 HTTP 接口：" + api;
                cr.riskSeed = 0.2;
                cr.tags.add("API");
                out.add(cr);
            }
        }
        for (String api : oldApis) {
            if (!newApis.contains(api)) {
                ChangeRecord cr = baseRecord(API_REMOVED, "API_ENDPOINT", fc.path, api, 0, 0);
                Sym endpoint = baseIndex.symbol("api:" + api);
                cr.modulePath = endpoint == null ? null : endpoint.modulePath;
                cr.symbolKey = "api:" + api;
                cr.oldValue = api;
                cr.semanticSummary = "删除 HTTP 接口：" + api;
                cr.riskSeed = 0.95;
                cr.tags.add("API");
                cr.tags.add("CONTRACT");
                out.add(cr);
            }
        }
        // response contract: same endpoint, different return type
        for (String api : newApis) {
            if (!oldApis.contains(api)) {
                continue;
            }
            Sym oldSym = baseIndex.symbol("api:" + api);
            Sym newSym = headIndex.symbol("api:" + api);
            if (oldSym == null || newSym == null) {
                continue;
            }
            String oldResp = oldSym.meta.getOrDefault("responseType", "");
            String newResp = newSym.meta.getOrDefault("responseType", "");
            String oldReq = oldSym.meta.getOrDefault("requestType", "");
            String newReq = newSym.meta.getOrDefault("requestType", "");
            boolean requestChanged = !oldReq.equals(newReq);
            if (!oldResp.equals(newResp) || requestChanged) {
                ChangeRecord cr = baseRecord(API_CONTRACT_CHANGED, "API_ENDPOINT", newSym.filePath, api,
                        newSym.startLine, newSym.endLine);
                cr.modulePath = newSym.modulePath;
                cr.symbolKey = "api:" + api;
                cr.oldValue = requestChanged ? oldReq + " / " + oldResp : oldResp;
                cr.newValue = requestChanged ? newReq + " / " + newResp : newResp;
                cr.semanticSummary = requestChanged
                        ? "接口请求类型变化：" + api + "（" + oldReq + " → " + newReq + "）"
                        : "接口响应类型变化：" + api + "（" + oldResp + " → " + newResp + "）";
                cr.riskSeed = 0.85;
                cr.tags.add("API");
                cr.tags.add("CONTRACT");
                out.add(cr);
            }
        }
    }

    private Set<String> endpointsOf(CodeIndex index, String file) {
        Set<String> out = new LinkedHashSet<>();
        if (file == null) {
            return out;
        }
        for (Sym s : index.symbols.values()) {
            if ("API_ENDPOINT".equals(s.type) && file.equals(s.filePath)) {
                out.add(s.meta.getOrDefault("path", s.simpleName));
            }
        }
        return out;
    }

    // -------------------------------------------------------------------- SQL

    /**
     * SQL statements, from MyBatis XML and from {@code .sql} scripts alike.
     *
     * Scripts used to be skipped entirely, which meant a migration script could add, drop or retype
     * a column and produce no change record at all - the schema change existed in git and nowhere in
     * the report. They are compared here like any other statement; what distinguishes them is that
     * the DDL comparison runs first.
     */
    private void detectSql(FileChange fc, List<ChangeRecord> out) {
        // A test schema or a test fixture is not a production contract. Its DDL must not become a
        // cross-program schema change, and its statements must not become production SQL changes.
        if (isTestPath(fc.path) || isTestPath(fc.oldPath)) {
            return;
        }
        List<Sym> oldSyms = sqlOf(baseIndex, fc.oldPath);
        List<Sym> newSyms = sqlOf(headIndex, fc.path);
        Map<String, Sym> oldByKey = byKey(oldSyms);
        Map<String, Sym> newByKey = byKey(newSyms);
        for (Sym s : newSyms) {
            Sym old = oldByKey.get(s.key);
            if (old == null) {
                out.add(addedRecord(s));
                continue;
            }
            ChangeRecord ddl = ddlChange(s, old, 1.0);
            if (ddl != null) {
                out.add(ddl);
                continue;
            }
            if (!Objects.equals(old.bodyHash, s.bodyHash)) {
                out.add(sqlChange(SQL_CHANGED, s, old, 0.7, "SQL 语句变化：" + s.qualifiedName));
            }
        }
        for (Sym s : oldSyms) {
            if (!newByKey.containsKey(s.key)) {
                out.add(removedRecord(s));
            }
        }
    }

    /**
     * Compares the schema snapshot of the two revisions.
     *
     * Per-file comparison sees a CREATE TABLE only when its symbol key stays put, and even then the
     * column definition is not part of the statement's DDL metadata. This pass compares the column
     * maps the SQL analyzer built from CREATE / ALTER statements, so a column added, dropped,
     * retyped or made NOT NULL is found regardless of which migration file or line it moved to.
     *
     * Only tables present on both sides are compared: a brand-new table cannot already be a
     * dependency of another program, and a dropped table is reported as DROP_TABLE instead.
     */
    private void detectSchemaChanges(List<ChangeRecord> out) {
        Map<String, Map<String, String>> baseTables = byTable(baseIndex.columnTypes);
        Map<String, Map<String, String>> headTables = byTable(headIndex.columnTypes);
        if (baseTables.isEmpty() || headTables.isEmpty()) {
            return;
        }
        Set<String> existing = new LinkedHashSet<>();
        for (ChangeRecord cr : out) {
            if (!DDL_CHANGED.equals(cr.changeType)) {
                continue;
            }
            String table = normalizeTable(cr.extra.get("table"));
            String column = safe(cr.extra.get("column")).toLowerCase(java.util.Locale.ROOT);
            String kind = safe(cr.extra.get("ddl"));
            String reason = safe(cr.extra.get("schemaChange"));
            if (!table.isBlank() && !column.isBlank()) {
                existing.add(table + "." + column + "|" + kind + "|" + reason);
            }
        }
        for (Map.Entry<String, Map<String, String>> tableEntry : headTables.entrySet()) {
            String table = tableEntry.getKey();
            Map<String, String> oldColumns = baseTables.get(table);
            if (oldColumns == null) {
                continue; // a new table: CREATE_TABLE already describes it, and it cannot be a dependency yet
            }
            Map<String, String> newColumns = tableEntry.getValue();
            for (Map.Entry<String, String> columnEntry : newColumns.entrySet()) {
                String column = columnEntry.getKey();
                String newType = columnEntry.getValue();
                if (!oldColumns.containsKey(column)) {
                    addSchemaRecord(out, existing, "ADD_COLUMN", table, column,
                            null, newType, null, null, "TYPE");
                    continue;
                }
                String oldType = oldColumns.get(column);
                String oldNullable = baseIndex.columnNullability.get(table + "." + column);
                String newNullable = headIndex.columnNullability.get(table + "." + column);
                String oldDefault = baseIndex.columnDefaults.get(table + "." + column);
                String newDefault = headIndex.columnDefaults.get(table + "." + column);
                // Type, nullability and default are independent contracts. A MODIFY can widen
                // the type and tighten NOT NULL at the same time; an else-if chain would report
                // the safe widening and silently drop the write-side break.
                if (!ColumnTypeCompatibility.compare(oldType, newType).equals(ColumnTypeCompatibility.Result.SAME)) {
                    addSchemaRecord(out, existing, "MODIFY_COLUMN", table, column,
                            oldType, newType, oldNullable, newNullable, "TYPE");
                }
                if (!Objects.equals(oldNullable, newNullable)) {
                    addSchemaRecord(out, existing, "MODIFY_COLUMN", table, column,
                            oldType, newType, oldNullable, newNullable, "NULLABILITY");
                }
                if (!Objects.equals(oldDefault, newDefault)) {
                    addSchemaRecord(out, existing, "MODIFY_COLUMN", table, column,
                            oldType, newType, oldNullable, newNullable, "DEFAULT");
                }
            }
            for (Map.Entry<String, String> columnEntry : oldColumns.entrySet()) {
                String column = columnEntry.getKey();
                if (newColumns.containsKey(column)) {
                    continue;
                }
                addSchemaRecord(out, existing, "DROP_COLUMN", table, column,
                        columnEntry.getValue(), null, null, null, "TYPE");
            }
        }
    }

    private void addSchemaRecord(List<ChangeRecord> out, Set<String> existing, String kind,
                                 String table, String column, String oldType, String newType,
                                 String oldNullable, String newNullable, String reason) {
        String key = table + "." + column + "|" + kind + "|" + reason;
        if (!existing.add(key)) {
            return;
        }
        Sym source = findSqlSymbol(table, kind);
        ChangeRecord cr = baseRecord(DDL_CHANGED, "SQL_STATEMENT",
                source == null ? null : source.filePath,
                source == null ? table + "." + column : source.qualifiedName + " " + table + "." + column,
                source == null ? 0 : source.startLine,
                source == null ? 0 : source.endLine);
        cr.modulePath = source == null ? null : source.modulePath;
        cr.symbolKey = source == null ? "sql:schema-diff#" + table + "." + column : source.key;
        cr.oldValue = safe(oldType);
        cr.newValue = safe(newType);
        cr.riskSeed = schemaRisk(kind, reason, oldType, newType, oldNullable, newNullable);
        cr.semanticSummary = schemaSummary(kind, reason, table, column, oldType, newType, oldNullable, newNullable);
        cr.tags.add("DATA");
        cr.tags.add("CONTRACT");
        cr.extra.put("ddl", kind);
        cr.extra.put("table", table);
        cr.extra.put("column", column);
        cr.extra.put("oldType", safe(oldType));
        cr.extra.put("newType", safe(newType));
        cr.extra.put("oldNullable", safe(oldNullable));
        cr.extra.put("newNullable", safe(newNullable));
        cr.extra.put("schemaChange", reason);
        cr.extra.put("schemaDiff", "true");
        cr.extra.put("ddlSchema", source == null ? "" : safe(source.meta.get("ddlSchema")));
        out.add(cr);
    }

    private double schemaRisk(String kind, String reason, String oldType, String newType,
                              String oldNullable, String newNullable) {
        if ("DROP_COLUMN".equals(kind)) {
            return 0.95;
        }
        if ("ADD_COLUMN".equals(kind)) {
            return 0.4;
        }
        if ("DEFAULT".equals(reason)) {
            return 0.3;
        }
        if ("NULLABILITY".equals(reason)) {
            return "true".equalsIgnoreCase(newNullable) ? 0.45 : 0.8;
        }
        ColumnTypeCompatibility.Result result = ColumnTypeCompatibility.compare(oldType, newType);
        return switch (result) {
            case SAME -> 0.3;
            case WIDENING -> 0.45;
            case NARROWING, INCOMPATIBLE -> 0.9;
            case UNKNOWN -> 0.7;
        };
    }

    private String schemaSummary(String kind, String reason, String table, String column,
                                 String oldType, String newType, String oldNullable, String newNullable) {
        if ("DROP_COLUMN".equals(kind)) {
            return "删除数据库字段：" + table + "." + column
                    + (safe(oldType).isBlank() ? "" : "（原类型 " + oldType + "）");
        }
        if ("ADD_COLUMN".equals(kind)) {
            return "新增数据库字段：" + table + "." + column
                    + (safe(newType).isBlank() ? "" : "（" + newType + "）");
        }
        if ("DEFAULT".equals(reason)) {
            return "修改数据库字段默认值：" + table + "." + column;
        }
        if ("NULLABILITY".equals(reason)) {
            return "修改数据库字段可空性：" + table + "." + column
                    + "（" + safe(oldNullable) + " → " + safe(newNullable) + "）";
        }
        return "修改数据库字段类型：" + table + "." + column
                + "（" + safe(oldType) + " → " + safe(newType) + "）";
    }

    private Sym findSqlSymbol(String table, String kind) {
        Sym best = null;
        Sym fallback = null;
        for (Sym sym : headIndex.symbols.values()) {
            if (!"SQL_STATEMENT".equals(sym.type) || sym.test || sym.meta.get("ddl") == null) {
                continue;
            }
            if (!table.equals(normalizeTable(sym.meta.get("ddlTable")))) {
                continue;
            }
            if (kind.equals(sym.meta.get("ddl"))) {
                if (best == null || earlier(sym, best)) {
                    best = sym;
                }
            } else if (fallback == null || earlier(sym, fallback)) {
                fallback = sym;
            }
        }
        return best != null ? best : fallback;
    }

    /** Deterministic pick when several statements describe the same column. */
    private boolean earlier(Sym left, Sym right) {
        String a = safe(left.filePath);
        String b = safe(right.filePath);
        int byFile = a.compareTo(b);
        return byFile != 0 ? byFile < 0 : left.startLine < right.startLine;
    }

    private Map<String, Map<String, String>> byTable(Map<String, String> columns) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            String key = entry.getKey();
            int dot = key.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            String table = key.substring(0, dot);
            String column = key.substring(dot + 1);
            out.computeIfAbsent(table, k -> new LinkedHashMap<>()).put(column, entry.getValue());
        }
        return out;
    }

    private static String normalizeTable(Object value) {
        return com.geek.association.parse.SqlAnalyzer.normalizeTable(value == null ? "" : String.valueOf(value));
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private ChangeRecord sqlChange(String changeType, Sym now, Sym before, double risk, String summary) {
        ChangeRecord cr = baseRecord(changeType, "SQL_STATEMENT", now.filePath, now.qualifiedName,
                now.startLine, now.endLine);
        cr.modulePath = now.modulePath;
        cr.symbolKey = now.key;
        cr.oldValue = before.meta.get("sql");
        cr.newValue = now.meta.get("sql");
        cr.semanticSummary = summary;
        cr.riskSeed = risk;
        cr.tags.add("DATA");
        cr.extra.put("sqlKind", now.meta.get("sqlKind"));
        columnExtras(cr, before, now);
        // A statement edit only reaches the other program when the write set moved, and that is a
        // sharper contract break than a body tweak: name it and raise the seed to match.
        //
        // Dynamic SQL is the exception: <if> / <include> / placeholder syntax means the write set
        // we parsed is a lower bound, not the truth. Treating "I could not see the columns" as
        // "the columns moved" is exactly how a dynamic mapper used to drag every reader of the
        // table into the report.
        boolean incomplete = "true".equals(before.meta.get("sqlIncomplete"))
                || "true".equals(now.meta.get("sqlIncomplete"));
        if (!incomplete && writeColumnsMoved(cr)) {
            cr.riskSeed = 0.85;
            cr.semanticSummary = summary + "（写入字段集合发生变化）";
        }
        return cr;
    }

    private boolean writeColumnsMoved(ChangeRecord cr) {
        Object before = cr.extra.get("oldWritesColumns");
        Object after = cr.extra.get("newWritesColumns");
        return !safe(String.valueOf(before)).equals(safe(String.valueOf(after)));
    }

    /**
     * Carries the before/after column sets on the record.
     *
     * The peer engine compares these two lists to find out which columns this program started or
     * stopped writing; that comparison is the whole difference between a noisy statement edit and a
     * database contract change, and it cannot be made from the statement symbol alone once the
     * statement is gone from the head revision.
     *
     * The {@code sqlIncomplete} flags travel with the record for the same reason: the peer engine
     * must not turn an unparseable write set into a table-level match.
     */
    private void columnExtras(ChangeRecord cr, Sym before, Sym now) {
        Sym b = before == null ? new Sym() : before;
        Sym n = now == null ? new Sym() : now;
        cr.extra.put("oldReadsColumns", safe(b.meta.get("readsColumns")));
        cr.extra.put("newReadsColumns", safe(n.meta.get("readsColumns")));
        cr.extra.put("oldWritesColumns", safe(b.meta.get("writesColumns")));
        cr.extra.put("newWritesColumns", safe(n.meta.get("writesColumns")));
        cr.extra.put("oldWritesTables", safe(b.meta.get("writesTables")));
        cr.extra.put("newWritesTables", safe(n.meta.get("writesTables")));
        cr.extra.put("oldReadsTables", safe(b.meta.get("readsTables")));
        cr.extra.put("newReadsTables", safe(n.meta.get("readsTables")));
        cr.extra.put("oldSqlIncomplete", safe(b.meta.get("sqlIncomplete")));
        cr.extra.put("newSqlIncomplete", safe(n.meta.get("sqlIncomplete")));
        cr.extra.put("oldSelectAll", safe(b.meta.get("selectAll")));
        cr.extra.put("newSelectAll", safe(n.meta.get("selectAll")));
    }

    /**
     * A schema change, or null when the statement carries no DDL or the DDL did not move.
     *
     * The record names the column, because that - not the script file - is what another program
     * depends on. A {@code CREATE TABLE} / {@code DROP TABLE} has no column and stays table-scoped.
     */
    private ChangeRecord ddlChange(Sym now, Sym before, double risk) {
        String newKind = now.meta.get("ddl");
        String oldKind = before.meta.get("ddl");
        String newTable = now.meta.get("ddlTable");
        String oldTable = before.meta.get("ddlTable");
        String newColumn = now.meta.get("ddlColumn");
        String oldColumn = before.meta.get("ddlColumn");
        String renamedTo = now.meta.get("ddlRenamedTo");
        if (newKind == null && oldKind == null) {
            return null;
        }
        // A statement whose key did not move can still have changed shape: an in-place MODIFY
        // (common when the table itself is managed outside the repository) must not degrade to a
        // plain SQL_CHANGED. Compare the DDL metadata, not only the identity.
        boolean same = Objects.equals(newKind, oldKind) && Objects.equals(newTable, oldTable)
                && Objects.equals(newColumn, oldColumn)
                && Objects.equals(now.meta.get("ddlType"), before.meta.get("ddlType"))
                && Objects.equals(now.meta.get("ddlNullable"), before.meta.get("ddlNullable"))
                && Objects.equals(now.meta.get("ddlDefault"), before.meta.get("ddlDefault"))
                && Objects.equals(now.meta.get("ddlRenamedTo"), before.meta.get("ddlRenamedTo"));
        if (same) {
            return null;
        }
        String table = newTable != null ? newTable : oldTable;
        String column = newColumn != null ? newColumn : oldColumn;
        String kind = newKind != null ? newKind : oldKind;
        boolean removed = newKind == null;
        String tableKey = com.geek.association.parse.SqlAnalyzer.normalizeTable(table);
        String columnKey = safe(column).toLowerCase(java.util.Locale.ROOT);
        String oldType = firstNonBlank(before.meta.get("ddlType"), baseIndex.columnTypes.get(tableKey + "." + columnKey));
        String newType = firstNonBlank(now.meta.get("ddlType"), headIndex.columnTypes.get(tableKey + "." + columnKey));
        String oldNullable = firstNonBlank(before.meta.get("ddlNullable"),
                baseIndex.columnNullability.get(tableKey + "." + columnKey));
        String newNullable = firstNonBlank(now.meta.get("ddlNullable"),
                headIndex.columnNullability.get(tableKey + "." + columnKey));
        String oldDefault = firstNonBlank(before.meta.get("ddlDefault"),
                baseIndex.columnDefaults.get(tableKey + "." + columnKey));
        String newDefault = firstNonBlank(now.meta.get("ddlDefault"),
                headIndex.columnDefaults.get(tableKey + "." + columnKey));
        if ("DROP_COLUMN".equals(kind)) {
            newType = "";
        }
        // A MODIFY can change three different things. Naming the one that moved is what lets the
        // peer engine apply the right rule: a type narrowing hits readers and writers, NOT NULL
        // hits writers, and a default change is invisible to named-column code.
        String schemaChange = "TYPE";
        if ("MODIFY_COLUMN".equals(kind)) {
            if (!oldType.isBlank() && !newType.isBlank() && !oldType.equalsIgnoreCase(newType)) {
                schemaChange = "TYPE";
            } else if (oldType.isBlank() || newType.isBlank()) {
                // The type is unknown, so a nullability/default difference cannot be promoted
                // over it: keep the conservative "type changed or unknown" path.
                schemaChange = "TYPE";
            } else if (!Objects.equals(oldNullable, newNullable)) {
                schemaChange = "NULLABILITY";
            } else if (!Objects.equals(oldDefault, newDefault)) {
                schemaChange = "DEFAULT";
            }
        }
        double seed = ddlRisk(kind, risk);
        if ("MODIFY_COLUMN".equals(kind)) {
            if ("DEFAULT".equals(schemaChange)) {
                seed = 0.3;
            } else if ("NULLABILITY".equals(schemaChange)) {
                seed = ColumnTypeCompatibility.safe(
                        ColumnTypeCompatibility.compareNullability(oldNullable, newNullable)) ? 0.45 : 0.8;
            } else if (!oldType.isBlank() && !newType.isBlank()) {
                seed = switch (ColumnTypeCompatibility.compare(oldType, newType)) {
                    case SAME -> 0.5;
                    case WIDENING -> 0.45;
                    case NARROWING, INCOMPATIBLE -> 0.9;
                    case UNKNOWN -> 0.9;
                };
            }
        }
        String verb = removed ? "移除" : "";
        String summary;
        if ("DROP_TABLE".equals(kind)) {
            summary = removed ? "移除删表脚本：" + table : "删除数据库表：" + table;
        } else if ("CREATE_TABLE".equals(kind)) {
            summary = removed ? "移除建表脚本：" + table : "新建数据库表：" + table;
        } else if ("DROP_COLUMN".equals(kind)) {
            if (renamedTo != null && !renamedTo.isBlank()) {
                summary = verb + "重命名数据库字段：" + table + "." + column + " → " + table + "." + renamedTo;
            } else {
                summary = verb + "删除数据库字段：" + table + "." + column
                        + (oldType.isBlank() ? "" : "（原类型 " + oldType + "）");
            }
        } else if ("ADD_COLUMN".equals(kind)) {
            summary = verb + "新增数据库字段：" + table + "." + column
                    + (newType.isBlank() ? "" : "（" + newType + "）");
        } else if ("MODIFY_COLUMN".equals(kind)) {
            if ("NULLABILITY".equals(schemaChange)) {
                summary = verb + "修改数据库字段可空性：" + table + "." + column
                        + "（" + safe(oldNullable) + " → " + safe(newNullable) + "）";
            } else if ("DEFAULT".equals(schemaChange)) {
                summary = verb + "修改数据库字段默认值：" + table + "." + column;
            } else if (!oldType.isBlank() && !newType.isBlank() && !oldType.equalsIgnoreCase(newType)) {
                summary = verb + "修改数据库字段类型：" + table + "." + column
                        + "（" + oldType + " → " + newType + "）";
            } else {
                summary = verb + "修改数据库字段：" + table + "." + column
                        + (oldColumn != null && !Objects.equals(oldColumn, newColumn) ? "（原 " + oldColumn + "）" : "");
            }
        } else {
            summary = "表结构发生变化：" + table;
        }
        ChangeRecord cr = baseRecord(DDL_CHANGED, "SQL_STATEMENT", now.filePath, now.qualifiedName,
                now.startLine, now.endLine);
        cr.modulePath = now.modulePath;
        cr.symbolKey = now.key;
        cr.oldValue = oldKind == null ? "" : oldKind + " " + safe(oldTable) + "." + safe(oldColumn)
                + (oldType.isBlank() ? "" : " " + oldType);
        cr.newValue = newKind == null ? "" : newKind + " " + safe(table) + "." + safe(column)
                + (newType.isBlank() ? "" : " " + newType);
        cr.semanticSummary = summary;
        cr.riskSeed = seed;
        cr.tags.add("DATA");
        cr.tags.add("CONTRACT");
        cr.extra.put("ddl", kind);
        cr.extra.put("oldDdl", safe(oldKind));
        cr.extra.put("table", safe(table));
        cr.extra.put("column", safe(column));
        cr.extra.put("oldColumn", safe(oldColumn));
        cr.extra.put("ddlSql", safe(now.meta.get("ddlSql")));
        cr.extra.put("oldType", oldType);
        cr.extra.put("newType", newType);
        cr.extra.put("oldNullable", oldNullable);
        cr.extra.put("newNullable", newNullable);
        cr.extra.put("oldDefault", oldDefault);
        cr.extra.put("newDefault", newDefault);
        cr.extra.put("schemaChange", schemaChange);
        cr.extra.put("renamedTo", safe(renamedTo));
        cr.extra.put("ddlSchema", firstNonBlank(now.meta.get("ddlSchema"), before.meta.get("ddlSchema")));
        if (removed) {
            // Deleting a migration script does not undo the migration in a real database. Keeping
            // the record explains the file change, but the peer engine must not treat it as a
            // schema rollback.
            cr.extra.put("ddlRemoved", "true");
        }
        return cr;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }

    /**
     * Risk of a schema change. Dropping is worse than adding: the other side keeps running and
     * silently reads nothing, while an added column is invisible to code that names its columns.
     */
    private double ddlRisk(String kind, double fallback) {
        return switch (kind == null ? "" : kind) {
            case "DROP_COLUMN" -> 0.95;
            case "DROP_TABLE" -> 0.95;
            case "MODIFY_COLUMN" -> 0.9;
            case "CREATE_TABLE" -> 0.6;
            case "ADD_COLUMN" -> 0.4;
            default -> fallback;
        };
    }

    private List<Sym> sqlOf(CodeIndex index, String file) {
        List<Sym> out = new ArrayList<>();
        if (file == null) {
            return out;
        }
        for (Sym s : index.symbols.values()) {
            if ("SQL_STATEMENT".equals(s.type) && file.equals(s.filePath)) {
                out.add(s);
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- config

    private void detectConfig(FileChange fc, List<ChangeRecord> out) {
        Map<String, Sym> oldKeys = configOf(baseIndex, fc.oldPath);
        Map<String, Sym> newKeys = configOf(headIndex, fc.path);
        for (Map.Entry<String, Sym> e : newKeys.entrySet()) {
            Sym now = e.getValue();
            Sym before = oldKeys.get(e.getKey());
            if (before == null) {
                ChangeRecord cr = baseRecord(CONFIG_ADDED, "CONFIG_KEY", fc.path, now.qualifiedName, now.startLine,
                        now.startLine, now.modulePath, now.key, "", now.meta.get("value"), 0.3,
                        "新增配置项：" + now.qualifiedName);
                cr.tags.add("CONFIG");
                out.add(cr);
            } else if (!Objects.equals(before.meta.get("value"), now.meta.get("value"))) {
                ChangeRecord cr = baseRecord(CONFIG_CHANGED, "CONFIG_KEY", fc.path, now.qualifiedName,
                        now.startLine, now.startLine, now.modulePath, now.key,
                        before.meta.get("value"), now.meta.get("value"), 0.65,
                        "配置值变化：" + now.qualifiedName);
                cr.tags.add("CONFIG");
                out.add(cr);
            }
        }
        for (Map.Entry<String, Sym> e : oldKeys.entrySet()) {
            if (!newKeys.containsKey(e.getKey())) {
                Sym before = e.getValue();
                ChangeRecord cr = baseRecord(CONFIG_REMOVED, "CONFIG_KEY", fc.path, before.qualifiedName,
                        before.startLine, before.startLine, before.modulePath, before.key,
                        before.meta.get("value"), "", 0.7, "删除配置项：" + before.qualifiedName);
                cr.tags.add("CONFIG");
                out.add(cr);
            }
        }
    }

    private Map<String, Sym> configOf(CodeIndex index, String file) {
        Map<String, Sym> out = new LinkedHashMap<>();
        if (file == null) {
            return out;
        }
        for (Sym s : index.symbols.values()) {
            if ("CONFIG_KEY".equals(s.type) && file.equals(s.filePath)) {
                out.put(s.key, s);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private List<Sym> symbolsOf(CodeIndex index, String file) {
        List<Sym> out = new ArrayList<>();
        if (file == null) {
            return out;
        }
        for (Sym s : index.symbols.values()) {
            if (file.equals(s.filePath)) {
                out.add(s);
            }
        }
        return out;
    }

    private Map<String, Sym> byKey(List<Sym> syms) {
        Map<String, Sym> out = new LinkedHashMap<>();
        for (Sym s : syms) {
            out.put(s.key, s);
        }
        return out;
    }

    private boolean isReportable(Sym s) {
        if ("CLASS".equals(s.type) || "INTERFACE".equals(s.type) || "ENUM".equals(s.type)
                || "RECORD".equals(s.type) || "ANNOTATION".equals(s.type)) {
            return false; // handled through hierarchy comparison, not as "type added/removed" noise
        }
        return true;
    }

    private boolean isType(String type) {
        return "CLASS".equals(type) || "INTERFACE".equals(type) || "ENUM".equals(type)
                || "RECORD".equals(type) || "ANNOTATION".equals(type);
    }

    private List<String> splitConditions(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(" \\| ")) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }

    private ChangeRecord methodRecord(String type, Sym now, Sym before, double risk, String summary) {
        ChangeRecord cr = baseRecord(type, now.type, now.filePath, now.qualifiedName, now.startLine, now.endLine);
        cr.modulePath = now.modulePath;
        cr.symbolKey = now.key;
        cr.signature = now.signature;
        cr.riskSeed = risk;
        cr.semanticSummary = summary;
        cr.tags.add(now.test ? "TEST" : "PRODUCTION");
        return cr;
    }

    /**
     * Records the database columns a field is mapped onto, as {@code table.column}.
     *
     * An entity field is a database contract only because of this mapping: the column is what the
     * other program reads. The edges live on the field symbol, so the head symbol is used for the
     * current state and the base symbol for a removal.
     */
    private void mappedColumns(ChangeRecord cr, Sym now, Sym before) {
        Set<String> columns = new LinkedHashSet<>();
        for (Sym sym : new Sym[]{now, before}) {
            if (sym == null || sym.key == null) {
                continue;
            }
            for (Edge e : headIndex.outgoing.getOrDefault(sym.key, List.of())) {
                addMappedColumn(columns, e);
            }
            for (Edge e : baseIndex.outgoing.getOrDefault(sym.key, List.of())) {
                addMappedColumn(columns, e);
            }
        }
        if (!columns.isEmpty()) {
            cr.extra.put("mappedColumns", String.join(",", columns));
        }
    }

    private void addMappedColumn(Set<String> columns, Edge e) {
        if ("MAPS_COLUMN".equals(e.relation) && e.to != null && e.to.startsWith("db:column:")) {
            columns.add(e.to.substring("db:column:".length()));
        }
    }

    private ChangeRecord fieldRecord(String type, Sym now, Sym before, double risk, String summary) {
        ChangeRecord cr = baseRecord(type, now.type, now.filePath, now.qualifiedName, now.startLine, now.endLine);
        cr.modulePath = now.modulePath;
        cr.symbolKey = now.key;
        cr.signature = now.signature;
        cr.riskSeed = risk;
        cr.semanticSummary = summary;
        return cr;
    }

    private ChangeRecord baseRecord(String changeType, String symbolType, String file, String qualified,
                                    int start, int end) {
        return baseRecord(changeType, symbolType, file, qualified, start, end, null, null, null, null, 0.3, "");
    }

    private ChangeRecord baseRecord(String changeType, String symbolType, String file, String qualified,
                                    int start, int end, String module, String symbolKey,
                                    String oldValue, String newValue, double risk, String summary) {
        ChangeRecord cr = new ChangeRecord();
        cr.changeType = changeType;
        cr.symbolType = symbolType;
        cr.qualifiedName = qualified;
        cr.filePath = file;
        cr.startLine = start;
        cr.endLine = end;
        cr.modulePath = module;
        cr.symbolKey = symbolKey;
        cr.oldValue = oldValue;
        cr.newValue = newValue;
        cr.riskSeed = risk;
        cr.semanticSummary = summary == null || summary.isBlank() ? changeType : summary;
        return cr;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isTestPath(String file) {
        return file != null && (file.contains("/src/test/") || file.startsWith("src/test/"));
    }

    private static String statusText(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case "ADDED" -> "新增";
            case "DELETED" -> "删除";
            case "MODIFIED" -> "修改";
            case "RENAMED" -> "重命名";
            default -> status;
        };
    }

    /** Reads the type of a changed symbol (owner type) for a change record. */
    public static TypeInfo ownerOf(CodeIndex index, Sym sym) {
        if (sym == null || sym.ownerKey == null) {
            return null;
        }
        for (TypeInfo t : index.types.values()) {
            if (t.key.equals(sym.ownerKey)) {
                return t;
            }
        }
        return null;
    }

    public static String shortName(String qualified) {
        return TypeResolver.simpleOf(qualified);
    }
}
