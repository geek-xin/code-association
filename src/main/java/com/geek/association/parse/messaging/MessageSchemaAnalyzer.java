package com.geek.association.parse.messaging;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.util.Hashes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Normalises a payload type into a middleware-neutral schema node.
 *
 * The fingerprint deliberately covers what breaks consumers (field names, types, requiredness and
 * enum values), not source formatting. A byte-for-byte source hash would report harmless edits as
 * contract changes.
 */
public class MessageSchemaAnalyzer {

    public Sym ensureSchema(String middleware, TypeInfo type, String module, CodeIndex index,
                            MessagingSupport support) {
        if (type == null) {
            return null;
        }
        String key = support.schemaKey(middleware, type.qualifiedName);
        Sym schema = index.symbol(key);
        if (schema == null) {
            schema = support.ensureSymbol(key, "MESSAGE_SCHEMA", type.qualifiedName,
                    module, type.filePath, type.startLine);
        }
        String format = formatOf(type);
        List<String> fields = new ArrayList<>();
        List<String> required = new ArrayList<>();
        List<String> enums = new ArrayList<>();
        for (TypeInfo.FieldInfo field : type.fields.values()) {
            String fieldType = normaliseType(field.type);
            fields.add(field.name + ":" + fieldType);
            if (isRequired(field)) {
                required.add(field.name);
            }
        }
        if (type.isEnum) {
            enums.addAll(type.fields.keySet());
        }
        fields.sort(Comparator.naturalOrder());
        required.sort(Comparator.naturalOrder());
        enums.sort(Comparator.naturalOrder());

        schema.meta.put("middleware", middleware);
        schema.meta.put("format", format);
        schema.meta.put("javaType", type.qualifiedName);
        schema.meta.put("fields", String.join(",", fields));
        schema.meta.put("required", String.join(",", required));
        schema.meta.put("enums", String.join(",", enums));
        schema.meta.put("schemaHash", Hashes.sha1(format + "|" + type.qualifiedName + "|"
                + String.join(",", fields) + "|" + String.join(",", required) + "|" + String.join(",", enums)));
        return schema;
    }

    public Sym unknownSchema(String middleware, String rawType, String module, String file,
                             int line, CodeIndex index, MessagingSupport support) {
        String name = rawType == null || rawType.isBlank() ? "unknown" : rawType.trim();
        String key = support.schemaKey(middleware, name);
        Sym schema = index.symbol(key);
        if (schema == null) {
            schema = support.ensureSymbol(key, "MESSAGE_SCHEMA", name, module, file, line);
        }
        schema.meta.put("middleware", middleware);
        schema.meta.put("format", "UNKNOWN");
        schema.meta.put("schemaHash", Hashes.sha1("UNKNOWN|" + name));
        return schema;
    }

    private String formatOf(TypeInfo type) {
        Set<String> annotations = type.annotations;
        if (annotations.contains("JsonSerialize") || annotations.contains("JsonProperty")
                || annotations.contains("JsonIgnoreProperties")) {
            return "JSON";
        }
        return "JSON";
    }

    private boolean isRequired(TypeInfo.FieldInfo field) {
        if (field.modifiers.contains("final")) {
            return true;
        }
        if (field.annotations.contains("NotNull") || field.annotations.contains("NonNull")
                || field.annotations.contains("NotBlank") || field.annotations.contains("NotEmpty")) {
            return true;
        }
        return !field.type.equals("java.lang.String") && !field.type.equals("String")
                && !isPrimitive(field.type);
    }

    private boolean isPrimitive(String type) {
        return Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char").contains(type);
    }

    private String normaliseType(String type) {
        if (type == null) {
            return "unknown";
        }
        return type.replace("java.lang.", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
