package com.geek.association.parse.messaging.rocketmq;

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
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.List;

/**
 * RocketMQ uses a topic plus a tag selector. Both are part of the route identity, because a
 * consumer of {@code inventory.changed:RESERVED} does not receive {@code inventory.changed:SOLD}.
 */
public class RocketMqMessagingAdapter implements MessagingAdapter {

    private final MessageSchemaAnalyzer schemas = new MessageSchemaAnalyzer();

    @Override
    public String type() {
        return "ROCKETMQ";
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
            if (content == null || (!content.contains("RocketMQTemplate")
                    && !content.contains("RocketMQMessageListener")
                    && !content.contains("DefaultMQProducer")
                    && !content.contains("DefaultMQPushConsumer"))) {
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
            inspectListener(type, owner, file, index, support);
            for (MethodDeclaration method : type.getMethods()) {
                String methodKey = methodKey(owner, method);
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    inspectSend(call, method, methodKey, owner, file, index, support);
                }
                for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
                    inspectNativeMessage(creation, methodKey, owner, file, index, support);
                }
            }
        }
    }

    private void inspectNativeMessage(ObjectCreationExpr creation, String methodKey, TypeInfo owner,
                                      String file, CodeIndex index, MessagingSupport support) {
        if (!support.typeSimpleName(creation.getType().asString()).equals("Message")
                || creation.getArguments().size() < 2) {
            return;
        }
        String topic = support.resolveDestination(creation.getArgument(0).toString(), file);
        String tag = support.resolveDestination(creation.getArgument(1).toString(), file);
        if (topic == null) {
            support.unresolved(file, line(creation), creation.toString(),
                    "dynamic messaging destination: native RocketMQ topic");
            return;
        }
        if (tag == null || tag.isBlank()) {
            tag = "*";
        }
        addRoute("rocketmq", topic, tag, methodKey, "PUBLISHES_TO", owner, file, line(creation),
                index, support, "native RocketMQ Message");
    }

    private void inspectListener(ClassOrInterfaceDeclaration type, TypeInfo owner, String file,
                                 CodeIndex index, MessagingSupport support) {
        for (AnnotationExpr annotation : type.getAnnotations()) {
            if (!annotation.getNameAsString().endsWith("RocketMQMessageListener")) {
                continue;
            }
            String rawTopic = annotationValue(annotation, "topic");
            String topic = support.resolveDestination(rawTopic, file);
            if (topic == null) {
                support.unresolved(file, line(annotation), annotation.toString(),
                        "dynamic messaging destination: RocketMQ topic");
                continue;
            }
            String tag = annotationValue(annotation, "selectorExpression");
            String selector = tag == null ? "*" : support.resolveDestination(tag, file);
            if (selector == null) {
                selector = "*";
            }
            String middleware = "rocketmq";
            String topicKey = support.topicKey(middleware, topic);
            String routeIdentity = topic + ":" + selector;
            String routeKey = support.routeKey(middleware, routeIdentity);
            Sym topicSym = support.ensureSymbol(topicKey, "MESSAGE_TOPIC", topic,
                    owner.modulePath, file, line(annotation));
            topicSym.meta.put("middleware", middleware);
            Sym route = support.ensureSymbol(routeKey, "MESSAGE_ROUTE", routeIdentity,
                    owner.modulePath, file, line(annotation));
            route.meta.put("middleware", middleware);
            route.meta.put("topic", topic);
            route.meta.put("tag", selector);
            support.edge(routeKey, topicKey, "ROUTES_TO", file, line(annotation), 0.9,
                    "RocketMQ " + topic + " tag=" + selector);
            String consumerMethod = owner.key;
            support.edge(consumerMethod, routeKey, "SUBSCRIBES_TO", file, line(annotation),
                    "*".equals(selector) ? 0.85 : 0.9, "RocketMQ listener " + routeIdentity);
            if (!"*".equals(selector)) {
                String filterKey = "mq:rocketmq:filter:" + selector;
                Sym filter = support.ensureSymbol(filterKey, "MESSAGE_FILTER", selector,
                        owner.modulePath, file, line(annotation));
                filter.meta.put("middleware", middleware);
                support.edge(consumerMethod, filterKey, "FILTERS_BY", file, line(annotation), 0.9,
                        "RocketMQ tag " + selector);
            }
            linkSchema(owner, index, support, middleware, file, line(annotation), consumerMethod);
        }
    }

    private void inspectSend(MethodCallExpr call, MethodDeclaration method, String methodKey,
                             TypeInfo owner, String file, CodeIndex index, MessagingSupport support) {
        String name = call.getNameAsString();
        if (name.equals("subscribe")) {
            inspectNativeSubscribe(call, methodKey, owner, file, support);
            return;
        }
        if (!name.equals("syncSend") && !name.equals("send") && !name.equals("convertAndSend")
                && !name.equals("asyncSend") && !name.equals("sendOneWay")) {
            return;
        }
        if (call.getArguments().isEmpty()) {
            return;
        }
        String raw = call.getArgument(0).toString();
        String destination = support.resolveDestination(raw, file);
        if (destination == null) {
            support.unresolved(file, line(call), call.toString(),
                    "dynamic messaging destination: RocketMQ topic or tag");
            return;
        }
        String topic = destination;
        String tag = "*";
        int colon = destination.indexOf(':');
        if (colon > 0) {
            topic = destination.substring(0, colon);
            tag = destination.substring(colon + 1);
        }
        String middleware = "rocketmq";
        String topicKey = support.topicKey(middleware, topic);
        String routeIdentity = topic + ":" + tag;
        String routeKey = support.routeKey(middleware, routeIdentity);
        Sym topicSym = support.ensureSymbol(topicKey, "MESSAGE_TOPIC", topic,
                owner.modulePath, file, line(call));
        topicSym.meta.put("middleware", middleware);
        Sym route = support.ensureSymbol(routeKey, "MESSAGE_ROUTE", routeIdentity,
                owner.modulePath, file, line(call));
        route.meta.put("middleware", middleware);
        route.meta.put("topic", topic);
        route.meta.put("tag", tag);
        support.edge(routeKey, topicKey, "ROUTES_TO", file, line(call), 0.9,
                "RocketMQ " + routeIdentity);
        support.edge(methodKey, routeKey, "PUBLISHES_TO", file, line(call), 0.9,
                "RocketMQTemplate " + routeIdentity);
        linkSchema(owner, method, methodKey, index, support, middleware, file, line(call));
    }

    private void inspectNativeSubscribe(MethodCallExpr call, String methodKey, TypeInfo owner,
                                        String file, MessagingSupport support) {
        if (call.getArguments().size() < 2) {
            return;
        }
        String topic = support.resolveDestination(call.getArgument(0).toString(), file);
        String tag = support.resolveDestination(call.getArgument(1).toString(), file);
        if (topic == null) {
            support.unresolved(file, line(call), call.toString(),
                    "dynamic messaging destination: native RocketMQ subscribe topic");
            return;
        }
        if (tag == null || tag.isBlank()) {
            tag = "*";
        }
        addRoute("rocketmq", topic, tag, methodKey, "SUBSCRIBES_TO", owner, file, line(call),
                null, support, "native RocketMQ subscribe");
    }

    private void addRoute(String middleware, String topic, String tag, String methodKey,
                          String relation, TypeInfo owner, String file, int line,
                          CodeIndex index, MessagingSupport support, String detail) {
        String topicKey = support.topicKey(middleware, topic);
        String routeIdentity = topic + ":" + tag;
        String routeKey = support.routeKey(middleware, routeIdentity);
        Sym topicSym = support.ensureSymbol(topicKey, "MESSAGE_TOPIC", topic,
                owner.modulePath, file, line);
        topicSym.meta.put("middleware", middleware);
        Sym route = support.ensureSymbol(routeKey, "MESSAGE_ROUTE", routeIdentity,
                owner.modulePath, file, line);
        route.meta.put("middleware", middleware);
        route.meta.put("topic", topic);
        route.meta.put("tag", tag);
        support.edge(routeKey, topicKey, "ROUTES_TO", file, line, 0.9,
                "RocketMQ " + routeIdentity);
        support.edge(methodKey, routeKey, relation, file, line, 0.9, detail + " " + routeIdentity);
    }

    private void linkSchema(TypeInfo owner, MethodDeclaration method, String methodKey, CodeIndex index,
                            MessagingSupport support, String middleware, String file, int line) {
        String typeName = method.getParameters().isEmpty()
                ? "unknown" : method.getParameter(method.getParameters().size() - 1).getType().asString();
        linkSchema(owner, index, support, middleware, file, line, methodKey, typeName);
    }

    private void linkSchema(TypeInfo owner, CodeIndex index, MessagingSupport support, String middleware,
                            String file, int line, String methodKey) {
        String typeName = "unknown";
        for (TypeInfo.MethodInfo m : methods(owner)) {
            if (m.key.equals(methodKey) && !m.paramTypes.isEmpty()) {
                typeName = m.paramTypes.get(m.paramTypes.size() - 1);
            }
        }
        linkSchema(owner, index, support, middleware, file, line, methodKey, typeName);
    }

    private void linkSchema(TypeInfo owner, CodeIndex index, MessagingSupport support, String middleware,
                            String file, int line, String methodKey, String typeName) {
        TypeInfo payloadType = support.resolveType(typeName, owner);
        if (payloadType == null) {
            Sym unknown = schemas.unknownSchema(middleware, typeName, owner.modulePath, file,
                    line, index, support);
            support.edge(methodKey, unknown.key, "USES_MESSAGE_SCHEMA", file, line, 0.4,
                    "unresolved RocketMQ payload");
            return;
        }
        Sym schema = schemas.ensureSchema(middleware, payloadType, owner.modulePath, index, support);
        support.edge(methodKey, schema.key, "USES_MESSAGE_SCHEMA", file, line, 0.9,
                "RocketMQ payload " + payloadType.qualifiedName);
    }

    private List<TypeInfo.MethodInfo> methods(TypeInfo owner) {
        List<TypeInfo.MethodInfo> out = new java.util.ArrayList<>();
        for (List<TypeInfo.MethodInfo> methods : owner.methods.values()) {
            out.addAll(methods);
        }
        return out;
    }

    private String methodKey(TypeInfo owner, MethodDeclaration method) {
        List<String> params = method.getParameters().stream()
                .map(p -> p.getType().asString()).toList();
        return com.geek.association.parse.JavaIndexer.methodKey(
                owner.modulePath, owner.qualifiedName, method.getNameAsString(), params);
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

    private int line(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }
}
