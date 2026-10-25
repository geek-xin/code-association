package com.geek.association.parse.messaging.rabbit;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring AMQP and the native RabbitMQ client.
 *
 * A Rabbit message is not addressed by a single topic: the deliverable identity is the queue that
 * the exchange/routing-key pair resolves to. The adapter therefore materialises a route per
 * binding, and falls back to queue-only routes when no binding can be proven.
 */
public class RabbitMessagingAdapter implements MessagingAdapter {

    private record Binding(String queue, String exchange, String routingKey) {
    }

    private record Topology(List<Binding> bindings,
                            Map<String, String> queueNames,
                            Map<String, String> exchangeNames) {
    }

    private static final Pattern NEW_QUEUE = Pattern.compile(
            "(?:new\\s+Queue|QueueBuilder\\.\\w+\\s*\\([^)]*\\)\\.build)\\s*\\(?\\s*\"([^\"]+)\"");
    private static final Pattern NEW_EXCHANGE = Pattern.compile(
            "new\\s+(?:Topic|Direct|Fanout|Headers)Exchange\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern BINDING = Pattern.compile(
            "BindingBuilder\\.bind\\s*\\(([^)]*)\\)\\s*\\.to\\s*\\(([^)]*)\\)\\s*\\.with\\s*\\(\\s*\"([^\"]*)\"\\s*\\)",
            Pattern.DOTALL);

    private final MessageSchemaAnalyzer schemas = new MessageSchemaAnalyzer();

    @Override
    public String type() {
        return "RABBITMQ";
    }

    @Override
    public void analyze(FileSource source, CodeIndex index, MessagingSupport support) {
        Topology topology = topology(source, support);
        declareTopology(source, index, support);
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        for (String file : source.files()) {
            if (!file.endsWith(".java")) {
                continue;
            }
            String content = source.read(file);
            if (content == null || (!content.contains("RabbitTemplate")
                    && !content.contains("RabbitListener") && !content.contains("basicPublish")
                    && !content.contains("BindingBuilder"))) {
                continue;
            }
            parser.parse(content).getResult().ifPresent(cu -> inspect(cu, file, index, support, topology));
        }
    }

    /**
     * Topology declarations are contracts in their own right: deleting an exchange or queue must
     * be visible even when the publisher and listener live in other modules.
     */
    private void declareTopology(FileSource source, CodeIndex index, MessagingSupport support) {
        for (String file : source.files()) {
            if (!file.endsWith(".java")) {
                continue;
            }
            String content = source.read(file);
            if (content == null || !content.contains("org.springframework.amqp")) {
                continue;
            }
            String module = support.moduleOf(file);
            Matcher queues = NEW_QUEUE.matcher(content);
            while (queues.find()) {
                String name = queues.group(1);
                Sym queue = support.ensureSymbol("mq:rabbitmq:queue:" + name, "MESSAGE_QUEUE",
                        name, module, file, lineOf(content, queues.start()));
                queue.meta.put("middleware", "rabbitmq");
                String routeKey = support.routeKey("rabbitmq", name);
                Sym route = support.ensureSymbol(routeKey, "MESSAGE_ROUTE", name, module, file,
                        lineOf(content, queues.start()));
                route.meta.put("middleware", "rabbitmq");
                route.meta.put("queue", name);
                support.edge(routeKey, queue.key, "ROUTES_TO", file, lineOf(content, queues.start()),
                        0.9, "RabbitMQ queue " + name);
            }
            Matcher exchanges = NEW_EXCHANGE.matcher(content);
            while (exchanges.find()) {
                String name = exchanges.group(1);
                Sym exchange = support.ensureSymbol("mq:rabbitmq:exchange:" + name, "MESSAGE_EXCHANGE",
                        name, module, file, lineOf(content, exchanges.start()));
                exchange.meta.put("middleware", "rabbitmq");
            }
        }
    }

    private void inspect(CompilationUnit cu, String file, CodeIndex index, MessagingSupport support,
                         Topology topology) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String qualified = pkg.isBlank() ? type.getNameAsString() : pkg + "." + type.getNameAsString();
            TypeInfo owner = index.types.get(qualified);
            if (owner == null) {
                continue;
            }
            for (MethodDeclaration method : type.getMethods()) {
                String methodKey = methodKey(owner, method);
                inspectListener(method, methodKey, owner, file, index, support, topology.bindings);
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    inspectSend(call, method, methodKey, owner, file, index, support, topology);
                }
            }
        }
    }

    private void inspectListener(MethodDeclaration method, String methodKey, TypeInfo owner,
                                 String file, CodeIndex index, MessagingSupport support,
                                 List<Binding> bindings) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            if (!annotation.getNameAsString().endsWith("RabbitListener")) {
                continue;
            }
            String rawQueues = annotationValue(annotation, "queues");
            if (rawQueues == null) {
                continue;
            }
            for (String rawQueue : rawQueues.split(",")) {
                String queue = support.resolveDestination(rawQueue, file);
                if (queue == null) {
                    support.unresolved(file, line(method), annotation.toString(),
                            "dynamic messaging destination: RabbitMQ queue");
                    continue;
                }
                List<String> routes = new ArrayList<>();
                for (Binding binding : bindings) {
                    if (binding.queue.equals(queue)) {
                        routes.add(binding.queue);
                    }
                }
                if (routes.isEmpty()) {
                    routes = List.of(queue);
                }
                for (String routeIdentity : routes) {
                    String routeKey = support.routeKey("rabbitmq", routeIdentity);
                    Sym route = support.ensureSymbol(routeKey, "MESSAGE_ROUTE", routeIdentity,
                            owner.modulePath, file, line(method));
                    route.meta.put("middleware", "rabbitmq");
                    route.meta.put("queue", queue);
                    support.edge(methodKey, routeKey, "SUBSCRIBES_TO", file, line(method),
                            routes.size() == 1 ? 0.9 : 0.65, "@RabbitListener " + queue);
                }
                linkSchema(owner, method, methodKey, file, index, support);
            }
        }
    }

    private void inspectSend(MethodCallExpr call, MethodDeclaration method, String methodKey, TypeInfo owner,
                             String file, CodeIndex index, MessagingSupport support,
                             Topology topology) {
        String name = call.getNameAsString();
        if (!name.equals("convertAndSend") && !name.equals("send") && !name.equals("basicPublish")) {
            return;
        }
        if (call.getArguments().size() < 2) {
            return;
        }
        String exchange = support.resolveDestination(call.getArgument(0).toString(), file);
        String routingKey = support.resolveDestination(call.getArgument(1).toString(), file);
        if (exchange == null || routingKey == null) {
            support.unresolved(file, line(call), call.toString(),
                    "dynamic messaging destination: RabbitMQ exchange or routing key");
            return;
        }
        String identity = exchange + ":" + routingKey;
        List<String> queues = queuesFor(exchange, routingKey, topology.bindings);
        if (queues.isEmpty()) {
            queues = List.of(identity);
        }
        for (String queue : queues) {
            String routeIdentity = queue.equals(identity) ? identity : queue;
            String routeKey = support.routeKey("rabbitmq", routeIdentity);
            Sym route = support.ensureSymbol(routeKey, "MESSAGE_ROUTE", routeIdentity,
                    owner.modulePath, file, line(call));
            route.meta.put("middleware", "rabbitmq");
            route.meta.put("exchange", exchange);
            route.meta.put("routingKey", routingKey);
            route.meta.put("queue", queue.equals(identity) ? "" : queue);
            support.edge(methodKey, routeKey, "PUBLISHES_TO", file, line(call),
                    queue.equals(identity) ? 0.6 : 0.9, "RabbitMQ " + identity);
        }
        linkSchema(owner, call, method, methodKey, file, index, support);
    }

    private Topology topology(FileSource source, MessagingSupport support) {
        List<Binding> out = new ArrayList<>();
        Map<String, String> queueNames = new LinkedHashMap<>();
        Map<String, String> exchangeNames = new LinkedHashMap<>();
        for (String file : source.files()) {
            if (!file.endsWith(".java")) {
                continue;
            }
            String content = source.read(file);
            if (content == null || !content.contains("org.springframework.amqp")) {
                continue;
            }
            Matcher declaredQueues = Pattern.compile(
                    "Queue\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{[^}]*?new\\s+Queue\\s*\\(\\s*\"([^\"]+)\"",
                    Pattern.DOTALL).matcher(content);
            while (declaredQueues.find()) {
                queueNames.put(declaredQueues.group(1), declaredQueues.group(2));
            }
            Matcher declaredExchanges = Pattern.compile(
                    "(?:Topic|Direct|Fanout|Headers)Exchange\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{[^}]*?new\\s+(?:Topic|Direct|Fanout|Headers)Exchange\\s*\\(\\s*\"([^\"]+)\"",
                    Pattern.DOTALL).matcher(content);
            while (declaredExchanges.find()) {
                exchangeNames.put(declaredExchanges.group(1), declaredExchanges.group(2));
            }
            if (!content.contains("BindingBuilder")) {
                continue;
            }
            Matcher m = BINDING.matcher(content);
            while (m.find()) {
                String queueRef = simpleArgument(m.group(1));
                String exchangeRef = simpleArgument(m.group(2));
                String queue = queueNames.getOrDefault(queueRef, queueRef);
                String exchange = exchangeNames.getOrDefault(exchangeRef, exchangeRef);
                String routingKey = m.group(3);
                out.add(new Binding(queue, exchange, routingKey));
                String queueKey = "mq:rabbitmq:queue:" + queue;
                String exchangeKey = "mq:rabbitmq:exchange:" + exchange;
                String routeKey = support.routeKey("rabbitmq", queue);
                String module = support.moduleOf(file);
                int line = lineOf(content, m.start());
                support.ensureSymbol(queueKey, "MESSAGE_QUEUE", queue, module, file, line);
                support.ensureSymbol(exchangeKey, "MESSAGE_EXCHANGE", exchange, module, file, line);
                support.ensureSymbol(routeKey, "MESSAGE_ROUTE", queue, module, file, line);
                support.edge(routeKey, queueKey, "ROUTES_TO", file, line, 0.9, "RabbitMQ queue " + queue);
                support.edge(routeKey, exchangeKey, "ROUTES_TO", file, line, 0.9,
                        "RabbitMQ " + exchange + " / " + routingKey);
                String declaringMethod = declaringMethod(content, m.start(), support);
                if (declaringMethod != null) {
                    support.edge(declaringMethod, routeKey, "ROUTES_TO", file, line, 0.9,
                            "RabbitMQ binding " + exchange + " / " + routingKey + " -> " + queue);
                }
            }
        }
        return new Topology(out, queueNames, exchangeNames);
    }

    private String declaringMethod(String content, int bindingOffset, MessagingSupport support) {
        int methodStart = content.lastIndexOf('{', bindingOffset);
        if (methodStart < 0) {
            return null;
        }
        String header = content.substring(Math.max(0, methodStart - 500), methodStart);
        Matcher m = Pattern.compile(
                "([A-Za-z_$][\\w$<>?, ]*)\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^()]*)\\)\\s*$",
                Pattern.DOTALL).matcher(header);
        if (!m.find()) {
            return null;
        }
        String name = m.group(2);
        List<String> params = new ArrayList<>();
        for (String raw : m.group(3).split(",")) {
            String param = raw.trim();
            if (param.isEmpty()) {
                continue;
            }
            String[] parts = param.split("\\s+");
            if (parts.length >= 2) {
                params.add(parts[0]);
            }
        }
        return findMethodKeyByName(support, name, params);
    }

    private String findMethodKeyByName(MessagingSupport support, String methodName, List<String> params) {
        for (TypeInfo type : support.index().types.values()) {
            List<TypeInfo.MethodInfo> methods = type.methods.get(methodName);
            if (methods == null) {
                continue;
            }
            for (TypeInfo.MethodInfo method : methods) {
                if (method.paramTypes.size() == params.size()) {
                    return method.key;
                }
            }
        }
        return null;
    }

    private List<String> queuesFor(String exchange, String routingKey, List<Binding> bindings) {
        List<String> out = new ArrayList<>();
        for (Binding binding : bindings) {
            if (binding.exchange.equals(exchange) && binding.routingKey.equals(routingKey)) {
                out.add(binding.queue);
            }
        }
        return out;
    }

    private void linkSchema(TypeInfo owner, MethodDeclaration method, String methodKey, String file,
                            CodeIndex index, MessagingSupport support) {
        if (method.getParameters().isEmpty()) {
            return;
        }
        String typeName = method.getParameter(method.getParameters().size() - 1).getType().asString();
        TypeInfo payloadType = support.resolveType(typeName, owner);
        if (payloadType == null) {
            Sym unknown = schemas.unknownSchema("rabbitmq", typeName, owner.modulePath, file,
                    line(method), index, support);
            support.edge(methodKey, unknown.key, "USES_MESSAGE_SCHEMA", file, line(method), 0.45,
                    "unresolved RabbitMQ payload");
            return;
        }
        Sym schema = schemas.ensureSchema("rabbitmq", payloadType, owner.modulePath, index, support);
        support.edge(methodKey, schema.key, "USES_MESSAGE_SCHEMA", file, line(method), 0.9,
                "RabbitMQ payload " + payloadType.qualifiedName);
    }

    private void linkSchema(TypeInfo owner, MethodCallExpr call, MethodDeclaration method, String methodKey,
                            String file,
                            CodeIndex index, MessagingSupport support) {
        if (call.getArguments().size() < 3) {
            return;
        }
        String typeName = call.getArgument(2).toString();
        if (typeName.startsWith("\"")) {
            Sym unknown = schemas.unknownSchema("rabbitmq", "String", owner.modulePath, file,
                    line(call), index, support);
            support.edge(methodKey, unknown.key, "USES_MESSAGE_SCHEMA", file, line(call), 0.35,
                    "RabbitMQ string payload");
            return;
        }
        String expressionType = expressionType(owner, method, typeName);
        TypeInfo payloadType = support.resolveType(expressionType, owner);
        if (payloadType == null) {
            Sym unknown = schemas.unknownSchema("rabbitmq", expressionType, owner.modulePath, file,
                    line(call), index, support);
            support.edge(methodKey, unknown.key, "USES_MESSAGE_SCHEMA", file, line(call), 0.4,
                    "unresolved RabbitMQ payload");
            return;
        }
        Sym schema = schemas.ensureSchema("rabbitmq", payloadType, owner.modulePath, index, support);
        support.edge(methodKey, schema.key, "USES_MESSAGE_SCHEMA", file, line(call), 0.9,
                "RabbitMQ payload " + payloadType.qualifiedName);
    }

    private String expressionType(TypeInfo owner, MethodDeclaration method, String expression) {
        String name = expression.trim();
        int dot = name.indexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        for (com.github.javaparser.ast.body.Parameter parameter : method.getParameters()) {
            if (parameter.getNameAsString().equals(name)) {
                return parameter.getType().asString();
            }
        }
        TypeInfo.FieldInfo field = owner.fields.get(name);
        if (field != null) {
            return field.type;
        }
        return name;
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

    private String simpleArgument(String value) {
        String v = value.trim();
        int dot = v.lastIndexOf('.');
        return dot < 0 ? v : v.substring(dot + 1);
    }

    private int line(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
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
