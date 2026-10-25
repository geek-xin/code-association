package com.geek.association.parse.messaging;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Sym;
import com.geek.association.source.FileSource;
import com.geek.association.util.Hashes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schema-first messaging contracts. Avro and Protobuf carry field numbers/defaults that a Java DTO
 * cannot represent, so their files are normalised directly rather than inferred from Java source.
 */
public class ExternalSchemaAnalyzer {

    private static final Pattern PROTO_MESSAGE = Pattern.compile(
            "message\\s+(\\w+)\\s*\\{(.*?)\\}", Pattern.DOTALL);
    private static final Pattern PROTO_FIELD = Pattern.compile(
            "(?:optional|required|repeated)?\\s*([\\w.]+)\\s+(\\w+)\\s*=\\s*(\\d+)");

    private final ObjectMapper json = new ObjectMapper();

    public void analyze(FileSource source, CodeIndex index, MessagingSupport support) {
        for (String file : source.files()) {
            String lower = file.toLowerCase(Locale.ROOT);
            String content = source.read(file);
            if (content == null) {
                continue;
            }
            if (lower.endsWith(".avsc")) {
                avro(file, content, index, support);
            } else if (lower.endsWith(".proto")) {
                protobuf(file, content, index, support);
            }
        }
    }

    private void avro(String file, String content, CodeIndex index, MessagingSupport support) {
        try {
            JsonNode root = json.readTree(content);
            String name = root.path("name").asText("");
            String namespace = root.path("namespace").asText("");
            if (name.isBlank()) {
                return;
            }
            String qualified = namespace.isBlank() ? name : namespace + "." + name;
            List<String> fields = new ArrayList<>();
            List<String> required = new ArrayList<>();
            for (JsonNode field : root.path("fields")) {
                String fieldName = field.path("name").asText("");
                if (fieldName.isBlank()) {
                    continue;
                }
                fields.add(fieldName + ":" + avroType(field.path("type")));
                if (!field.has("default") && !field.path("nullable").asBoolean(false)) {
                    required.add(fieldName);
                }
            }
            schema(file, "AVRO", qualified, fields, required, index, support);
        } catch (Exception ignored) {
            // Malformed schema files are left to the blind-spot mechanism rather than guessed at.
        }
    }

    private void protobuf(String file, String content, CodeIndex index, MessagingSupport support) {
        String namespace = "";
        Matcher pkg = Pattern.compile("package\\s+([\\w.]+)\\s*;").matcher(content);
        if (pkg.find()) {
            namespace = pkg.group(1);
        }
        Matcher message = PROTO_MESSAGE.matcher(content);
        while (message.find()) {
            String name = message.group(1);
            String qualified = namespace.isBlank() ? name : namespace + "." + name;
            List<String> fields = new ArrayList<>();
            List<String> required = new ArrayList<>();
            Matcher field = PROTO_FIELD.matcher(message.group(2));
            while (field.find()) {
                String label = field.group(0).trim();
                String fieldName = field.group(2);
                fields.add(fieldName + ":" + field.group(1).toLowerCase(Locale.ROOT)
                        + "#" + field.group(3));
                if (label.startsWith("required")) {
                    required.add(fieldName);
                }
            }
            schema(file, "PROTOBUF", qualified, fields, required, index, support);
        }
    }

    private void schema(String file, String format, String qualified, List<String> fields,
                        List<String> required, CodeIndex index, MessagingSupport support) {
        fields.sort(Comparator.naturalOrder());
        required.sort(Comparator.naturalOrder());
        String key = support.schemaKey(format.toLowerCase(Locale.ROOT), qualified);
        Sym schema = support.ensureSymbol(key, "MESSAGE_SCHEMA", qualified,
                support.moduleOf(file), file, 1);
        schema.meta.put("middleware", "schema-registry");
        schema.meta.put("format", format);
        schema.meta.put("javaType", qualified);
        schema.meta.put("fields", String.join(",", fields));
        schema.meta.put("required", String.join(",", required));
        schema.meta.put("enums", "");
        schema.meta.put("schemaHash", Hashes.sha1(format + "|" + qualified + "|"
                + String.join(",", fields) + "|" + String.join(",", required)));
    }

    private String avroType(JsonNode type) {
        if (type.isTextual()) {
            return type.asText().toLowerCase(Locale.ROOT);
        }
        if (type.isArray()) {
            for (JsonNode option : type) {
                if (!"null".equals(option.asText())) {
                    return avroType(option);
                }
            }
        }
        if (type.isObject()) {
            return type.path("type").asText("record").toLowerCase(Locale.ROOT);
        }
        return "unknown";
    }
}
