package com.geek.association.diff;

import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.Edge;
import com.geek.association.model.FileChange;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.parse.JavaIndexer;
import com.geek.association.parse.TypeResolver;
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
        return out;
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
            if (!Objects.equals(before.bodyHash, now.bodyHash)) {
                ChangeRecord cr = baseRecord(SQL_CHANGED, "SQL_STATEMENT", now.filePath, now.qualifiedName,
                        now.startLine, now.endLine);
                cr.modulePath = now.modulePath;
                cr.symbolKey = now.key;
                cr.oldValue = before.meta.get("sql");
                cr.newValue = now.meta.get("sql");
                cr.semanticSummary = "SQL 语句发生变化：" + now.qualifiedName;
                cr.riskSeed = 0.7;
                cr.tags.add("DATA");
                cr.extra.put("sqlKind", now.meta.get("sqlKind"));
                return cr;
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
            return baseRecord(FIELD_ADDED, s.type, s.filePath, s.qualifiedName, s.startLine, s.endLine,
                    s.modulePath, s.key, s.signature, "", 0.2, "新增字段：" + s.qualifiedName);
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
            ChangeRecord cr = baseRecord(SQL_ADDED, s.type, s.filePath, s.qualifiedName, s.startLine, s.endLine,
                    s.modulePath, s.key, "", s.meta.get("sql"), 0.4, "新增 SQL 语句：" + s.qualifiedName);
            cr.tags.add("DATA");
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
            ChangeRecord cr = baseRecord(SQL_REMOVED, s.type, s.filePath, s.qualifiedName, s.startLine, s.endLine,
                    s.modulePath, s.key, s.meta.get("sql"), "", 0.75, "删除 SQL 语句：" + s.qualifiedName);
            cr.tags.add("DATA");
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

    private void detectSql(FileChange fc, List<ChangeRecord> out) {
        if (fc.path.endsWith(".sql")) {
            return; // handled as symbols by detectJava-independent pass below
        }
        List<Sym> oldSyms = sqlOf(baseIndex, fc.oldPath);
        List<Sym> newSyms = sqlOf(headIndex, fc.path);
        Map<String, Sym> oldByKey = byKey(oldSyms);
        Map<String, Sym> newByKey = byKey(newSyms);
        for (Sym s : newSyms) {
            Sym old = oldByKey.get(s.key);
            if (old == null) {
                out.add(addedRecord(s));
            } else if (!Objects.equals(old.bodyHash, s.bodyHash)) {
                ChangeRecord cr = baseRecord(SQL_CHANGED, "SQL_STATEMENT", s.filePath, s.qualifiedName,
                        s.startLine, s.endLine);
                cr.modulePath = s.modulePath;
                cr.symbolKey = s.key;
                cr.oldValue = old.meta.get("sql");
                cr.newValue = s.meta.get("sql");
                cr.semanticSummary = "SQL 语句变化：" + s.qualifiedName;
                cr.riskSeed = 0.7;
                cr.tags.add("DATA");
                out.add(cr);
            }
        }
        for (Sym s : oldSyms) {
            if (!newByKey.containsKey(s.key)) {
                out.add(removedRecord(s));
            }
        }
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
