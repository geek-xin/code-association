package com.geek.association.parse.messaging.kafka;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.Sym;
import com.geek.association.model.TypeInfo;
import com.geek.association.parse.messaging.MessageSchemaAnalyzer;
import com.geek.association.parse.messaging.MessagingAdapter;
import com.geek.association.parse.messaging.MessagingSupport;
import com.geek.association.source.FileSource;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.List;

/** Spring Kafka and the native Kafka client, normalised to topic routes. */
public class KafkaMessagingAdapter implements MessagingAdapter {

    private final MessageSchemaAnalyzer schemas = new MessageSchemaAnalyzer();

    @Override
    public String type() {
        return "KAFKA";
    }

    @Override
    public void analyze(FileSource source, CodeIndex index, MessagingSupport support) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        for (String file : source.files()) {
            if (!file.endsWith(".java")) {
                continue;
            }
            String content = source.read(file);
            if (content == null || (!content.contains("KafkaTemplate")
                    && !content.contains("KafkaProducer") && !content.contains("KafkaConsumer")
                    && !content.contains("KafkaListener")
                    && !content.contains("org.apache.kafka"))) {
                continue;
            }
            parser.parse(content).getResult().ifPresent(cu -> inspect(cu, file, index, support));
        }
    }

    private void inspect(CompilationUnit cu, String file, CodeIndex index, MessagingSupport support) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String qualified = pkg.isBlank() ? type.getNameAsString() : pkg + "." + type.getNameAsString();
            TypeInfo owner = index.types.get(qualified);
            if (owner == null) {
                continue;
            }
            for (MethodDeclaration method : type.getMethods()) {
                String methodKey = methodKey(owner, method);
                if (methodKey == null) {
                    continue;
                }
                inspectListener(method, methodKey, owner, file, index, support);
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    inspectSend(call, method, methodKey, owner, file, index, support);
                }
                for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
                    inspectProducerRecord(creation, methodKey, owner, file, index, support);
                }
            }
        }
    }

    private void inspectListener(MethodDeclaration method, String methodKey, TypeInfo owner,
                                 String file, CodeIndex index, MessagingSupport support) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            if (!annotation.getNameAsString().endsWith("KafkaListener")) {
                continue;
            }
            String raw = annotationValue(annotation, "topics");
            String topic = support.resolveDestination(raw, file);
            if (topic == null) {
                support.unresolved(file, line(method), annotation.toString(),
                        "dynamic messaging destination: Kafka listener topic");
                continue;
            }
            String middleware = "kafka";
            String topicKey = support.topicKey(middleware, topic);
            String routeKey = support.routeKey(middleware, topic);
            Sym topicSym = support.ensureSymbol(topicKey, "MESSAGE_TOPIC", topic,
                    owner.modulePath, file, line(method));
            topicSym.meta.put("middleware", middleware);
            Sym route = support.ensureSymbol(routeKey, "MESSAGE_ROUTE", topic,
                    owner.modulePath, file, line(method));
            route.meta.put("middleware", middleware);
            route.meta.put("topic", topic);
            support.edge(routeKey, topicKey, "ROUTES_TO", file, line(method), 0.95, "Kafka topic " + topic);
            support.edge(methodKey, routeKey, "SUBSCRIBES_TO", file, line(method), 0.95,
                    "@KafkaListener " + topic);
            String groupId = annotationValue(annotation, "groupId");
            if (groupId != null && !groupId.isBlank()) {
                String groupKey = "mq:kafka:group:" + support.resolveDestination(groupId, file);
                Sym group = support.ensureSymbol(groupKey, "MESSAGE_GROUP", groupId,
                        owner.modulePath, file, line(method));
                group.meta.put("middleware", middleware);
                support.edge(methodKey, groupKey, "USES_MESSAGE_GROUP", file, line(method), 0.9,
                        "Kafka consumer group " + groupId);
            }
            linkSchema(owner, method, methodKey, middleware, file, index, support, false);
        }
    }

    private void inspectSend(MethodCallExpr call, MethodDeclaration method, String methodKey,
                             TypeInfo owner, String file, CodeIndex index, MessagingSupport support) {
        String name = call.getNameAsString();
        if (name.equals("subscribe")) {
            inspectSubscribe(call, methodKey, owner, file, support);
            return;
        }
        if (!name.equals("send") && !name.equals("sendDefault")) {
            return;
        }
        if (call.getArguments().isEmpty()) {
            return;
        }
        if (call.getArgument(0).isObjectCreationExpr()
                && call.getArgument(0).asObjectCreationExpr().getType().asString().endsWith("ProducerRecord")) {
            return; // handled by inspectProducerRecord, which knows the record's topic
        }
        String raw = call.getArgument(0).toString();
        String topic = support.resolveDestination(raw, file);
        if (topic == null) {
            support.unresolved(file, line(call), call.toString(),
                    "dynamic messaging destination: Kafka producer topic");
            return;
        }
        String middleware = "kafka";
        String topicKey = support.topicKey(middleware, topic);
        String routeKey = support.routeKey(middleware, topic);
        Sym topicSym = support.ensureSymbol(topicKey, "MESSAGE_TOPIC", topic,
                owner.modulePath, file, line(call));
        topicSym.meta.put("middleware", middleware);
        Sym route = support.ensureSymbol(routeKey, "MESSAGE_ROUTE", topic,
                owner.modulePath, file, line(call));
        route.meta.put("middleware", middleware);
        route.meta.put("topic", topic);
        support.edge(routeKey, topicKey, "ROUTES_TO", file, line(call), 0.95, "Kafka topic " + topic);
        support.edge(methodKey, routeKey, "PUBLISHES_TO", file, line(call), 0.95,
                "KafkaTemplate.send " + topic);
        linkSendSchema(owner, method, methodKey, middleware, file, index, support, call);
    }

    private void inspectSubscribe(MethodCallExpr call, String methodKey, TypeInfo owner,
                                  String file, MessagingSupport support) {
        if (call.getArguments().isEmpty()) {
            return;
        }
        java.util.regex.Matcher literal = java.util.regex.Pattern
                .compile("\"([^\"]+)\"").matcher(call.getArgument(0).toString());
        boolean found = false;
        while (literal.find()) {
            found = true;
            addSubscription(literal.group(1), methodKey, owner, file, support);
        }
        if (!found) {
            support.unresolved(file, line(call), call.toString(),
                    "dynamic messaging destination: native Kafka subscribe topic");
        }
    }

    private void inspectProducerRecord(ObjectCreationExpr creation, String methodKey, TypeInfo owner,
                                       String file, CodeIndex index, MessagingSupport support) {
        String type = support.typeSimpleName(creation.getType().asString());
        if (!type.equals("ProducerRecord")
                || creation.getArguments().isEmpty()) {
            return;
        }
        String topic = support.resolveDestination(creation.getArgument(0).toString(), file);
        if (topic == null) {
            support.unresolved(file, line(creation), creation.toString(),
                    "dynamic messaging destination: native Kafka ProducerRecord topic");
            return;
        }
        addPublication(topic, methodKey, owner, file, index, support);
    }

    private void addSubscription(String topic, String methodKey, TypeInfo owner,
                                 String file, MessagingSupport support) {
        String topicKey = support.topicKey("kafka", topic);
        String routeKey = support.routeKey("kafka", topic);
        Sym topicSym = support.ensureSymbol(topicKey, "MESSAGE_TOPIC", topic,
                owner.modulePath, file, 0);
        topicSym.meta.put("middleware", "kafka");
        Sym route = support.ensureSymbol(routeKey, "MESSAGE_ROUTE", topic,
                owner.modulePath, file, 0);
        route.meta.put("middleware", "kafka");
        route.meta.put("topic", topic);
        support.edge(routeKey, topicKey, "ROUTES_TO", file, 0, 0.9, "Kafka topic " + topic);
        support.edge(methodKey, routeKey, "SUBSCRIBES_TO", file, 0, 0.85,
                "KafkaConsumer.subscribe " + topic);
    }

    private void addPublication(String topic, String methodKey, TypeInfo owner,
                                String file, CodeIndex index, MessagingSupport support) {
        String topicKey = support.topicKey("kafka", topic);
        String routeKey = support.routeKey("kafka", topic);
        Sym topicSym = support.ensureSymbol(topicKey, "MESSAGE_TOPIC", topic,
                owner.modulePath, file, 0);
        topicSym.meta.put("middleware", "kafka");
        Sym route = support.ensureSymbol(routeKey, "MESSAGE_ROUTE", topic,
                owner.modulePath, file, 0);
        route.meta.put("middleware", "kafka");
        route.meta.put("topic", topic);
        support.edge(routeKey, topicKey, "ROUTES_TO", file, 0, 0.9, "Kafka topic " + topic);
        support.edge(methodKey, routeKey, "PUBLISHES_TO", file, 0, 0.9,
                "ProducerRecord " + topic);
    }

    private void linkSchema(TypeInfo owner, MethodDeclaration method, String methodKey, String middleware,
                            String file, CodeIndex index, MessagingSupport support, boolean producer) {
        if (method.getParameters().isEmpty()) {
            return;
        }
        Parameter payload = method.getParameter(method.getParameters().size() - 1);
        String typeName = payload.getType().asString();
        if (isOpaque(typeName)) {
            Sym unknown = schemas.unknownSchema(middleware, typeName, owner.modulePath, file,
                    line(method), index, support);
            support.edge(methodKey, unknown.key, "USES_MESSAGE_SCHEMA", file, line(method), 0.4,
                    "opaque Kafka payload");
            return;
        }
        TypeInfo payloadType = support.resolveType(typeName, owner);
        if (payloadType == null) {
            Sym unknown = schemas.unknownSchema(middleware, typeName, owner.modulePath, file,
                    line(method), index, support);
            support.edge(methodKey, unknown.key, "USES_MESSAGE_SCHEMA", file, line(method), 0.45,
                    "unresolved Kafka payload");
            return;
        }
        Sym schema = schemas.ensureSchema(middleware, payloadType, owner.modulePath, index, support);
        support.edge(methodKey, schema.key, "USES_MESSAGE_SCHEMA", file, line(method), 0.9,
                "Kafka payload " + payloadType.qualifiedName);
    }

    private void linkSendSchema(TypeInfo owner, MethodDeclaration method, String methodKey, String middleware,
                                String file, CodeIndex index, MessagingSupport support, MethodCallExpr call) {
        if (call.getArguments().size() < 2) {
            return;
        }
        String typeName = support.genericArgument(kafkaTemplateType(method, owner, support));
        if (typeName == null) {
            typeName = "unknown";
        }
        TypeInfo payloadType = support.resolveType(typeName, owner);
        if (payloadType == null) {
            Sym unknown = schemas.unknownSchema(middleware, typeName, owner.modulePath, file,
                    line(call), index, support);
            support.edge(methodKey, unknown.key, "USES_MESSAGE_SCHEMA", file, line(call), 0.45,
                    "unresolved Kafka payload");
            return;
        }
        Sym schema = schemas.ensureSchema(middleware, payloadType, owner.modulePath, index, support);
        support.edge(methodKey, schema.key, "USES_MESSAGE_SCHEMA", file, line(call), 0.9,
                "Kafka payload " + payloadType.qualifiedName);
    }

    private String kafkaTemplateType(MethodDeclaration method, TypeInfo owner, MessagingSupport support) {
        for (Parameter p : method.getParameters()) {
            String type = p.getType().asString();
            if (type.contains("KafkaTemplate")) {
                return type;
            }
        }
        for (TypeInfo.FieldInfo field : owner.fields.values()) {
            if (field.type.contains("KafkaTemplate")) {
                return field.type;
            }
        }
        return "";
    }

    private String methodKey(TypeInfo owner, MethodDeclaration method) {
        List<String> params = method.getParameters().stream()
                .map(p -> p.getType().asString()).toList();
        String key = com.geek.association.parse.JavaIndexer.methodKey(
                owner.modulePath, owner.qualifiedName, method.getNameAsString(), params);
        return key;
    }

    private String annotationValue(AnnotationExpr annotation, String name) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals(name))
                    .map(p -> p.getValue().toString())
                    .findFirst().orElse(null);
        }
        return null;
    }


    private boolean isOpaque(String type) {
        return type.startsWith("Map") || type.startsWith("java.util.Map")
                || type.startsWith("byte[]") || type.equals("Object") || type.equals("java.lang.Object");
    }

    private int line(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }
}
