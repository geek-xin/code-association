package com.geek.association;

import com.geek.association.model.CodeIndex;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.Edge;
import com.geek.association.model.ImpactResult;
import com.geek.association.model.Sym;
import com.geek.association.diff.ChangeDetector;
import com.geek.association.diff.FileDiffCollector;
import com.geek.association.impact.ImpactEngine;
import com.geek.association.impact.ImpactGraph;
import com.geek.association.impact.PeerImpactEngine;
import com.geek.association.model.PeerImpact;
import com.geek.association.parse.CodeIndexer;
import com.geek.association.source.DirSource;
import com.geek.association.source.RevisionPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The message layer must join producers and consumers through a middleware-neutral route,
 * regardless of whether the code uses Kafka, RabbitMQ or RocketMQ.
 */
class MessagingAnalysisTest {

    private static final int MAX_FILES = 5000;

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void pom(Path root) throws IOException {
        write(root, "pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>messaging</artifactId><version>1.0</version>
                </project>
                """);
    }

    private static CodeIndex index(Path root) {
        return new CodeIndexer(MAX_FILES)
                .index(new DirSource(root, "test", MAX_FILES), "head", "abc");
    }

    private static Sym symbol(CodeIndex index, String type, String qualifiedSuffix) {
        return index.symbols.values().stream()
                .filter(s -> type.equals(s.type) && s.qualifiedName.endsWith(qualifiedSuffix))
                .findFirst()
                .orElse(null);
    }

    private static boolean hasEdge(CodeIndex index, String relation, String fromSuffix, String toKey) {
        return index.outgoing.values().stream()
                .flatMap(List::stream)
                .filter(e -> relation.equals(e.relation))
                .filter(e -> {
                    Sym from = index.symbol(e.from);
                    String identity = from == null ? e.from : from.qualifiedName;
                    return identity.endsWith(fromSuffix);
                })
                .anyMatch(e -> e.to.equals(toKey));
    }

    @Test
    void kafkaProducerAndListenerJoinTheSameTopicRoute(@TempDir Path tmp) throws IOException {
        pom(tmp);
        write(tmp, "src/main/java/com/example/OrderEvent.java", """
                package com.example;
                public record OrderEvent(String orderId, long amount) {}
                """);
        write(tmp, "src/main/java/com/example/OrderProducer.java", """
                package com.example;
                import org.springframework.kafka.core.KafkaTemplate;
                public class OrderProducer {
                    private final KafkaTemplate<String, OrderEvent> kafka;
                    public OrderProducer(KafkaTemplate<String, OrderEvent> kafka) { this.kafka = kafka; }
                    public void publish(OrderEvent event) { kafka.send("order.created", event); }
                }
                """);
        write(tmp, "src/main/java/com/example/OrderConsumer.java", """
                package com.example;
                import org.springframework.kafka.annotation.KafkaListener;
                public class OrderConsumer {
                    @KafkaListener(topics = "order.created", groupId = "billing")
                    public void consume(OrderEvent event) {}
                }
                """);

        CodeIndex index = index(tmp);
        Sym topic = symbol(index, "MESSAGE_TOPIC", "order.created");
        Sym route = symbol(index, "MESSAGE_ROUTE", "order.created");
        Sym schema = index.symbols.values().stream()
                .filter(s -> "MESSAGE_SCHEMA".equals(s.type) && s.qualifiedName.endsWith("OrderEvent"))
                .findFirst().orElse(null);
        assertNotNull(topic, "Kafka topic must be indexed");
        assertNotNull(route, "producer and consumer must meet on a route");
        assertNotNull(schema, "the message payload type must become a schema node");
        assertTrue(hasEdge(index, "PUBLISHES_TO", "OrderProducer#publish", route.key));
        assertTrue(hasEdge(index, "SUBSCRIBES_TO", "OrderConsumer#consume", route.key));
        assertTrue(hasEdge(index, "USES_MESSAGE_SCHEMA", "OrderProducer#publish", schema.key));
        assertTrue(hasEdge(index, "USES_MESSAGE_SCHEMA", "OrderConsumer#consume", schema.key));
    }

    @Test
    void rabbitBindingJoinsExchangeRoutingKeyAndQueue(@TempDir Path tmp) throws IOException {
        pom(tmp);
        write(tmp, "src/main/java/com/example/PaymentEvent.java", """
                package com.example;
                public class PaymentEvent { public String paymentId; }
                """);
        write(tmp, "src/main/java/com/example/PaymentPublisher.java", """
                package com.example;
                import org.springframework.amqp.rabbit.core.RabbitTemplate;
                public class PaymentPublisher {
                    private final RabbitTemplate rabbit;
                    public PaymentPublisher(RabbitTemplate rabbit) { this.rabbit = rabbit; }
                    public void publish(PaymentEvent event) {
                        rabbit.convertAndSend("payments", "payment.created", event);
                    }
                }
                """);
        write(tmp, "src/main/java/com/example/PaymentTopology.java", """
                package com.example;
                import org.springframework.amqp.core.*;
                import org.springframework.context.annotation.Bean;
                public class PaymentTopology {
                    @Bean Queue paymentQueue() { return new Queue("payment.queue"); }
                    @Bean TopicExchange payments() { return new TopicExchange("payments"); }
                    @Bean Binding paymentBinding(Queue paymentQueue, TopicExchange payments) {
                        return BindingBuilder.bind(paymentQueue).to(payments).with("payment.created");
                    }
                }
                """);
        write(tmp, "src/main/java/com/example/PaymentConsumer.java", """
                package com.example;
                import org.springframework.amqp.rabbit.annotation.RabbitListener;
                public class PaymentConsumer {
                    @RabbitListener(queues = "payment.queue")
                    public void consume(PaymentEvent event) {}
                }
                """);

        CodeIndex index = index(tmp);
        Sym exchange = symbol(index, "MESSAGE_EXCHANGE", "payments");
        Sym queue = symbol(index, "MESSAGE_QUEUE", "payment.queue");
        Sym route = symbol(index, "MESSAGE_ROUTE", "payment.queue");
        assertNotNull(exchange);
        assertNotNull(queue);
        assertNotNull(route, "RabbitMQ binding must materialise a deliverable route");
        assertTrue(hasEdge(index, "PUBLISHES_TO", "PaymentPublisher#publish", route.key));
        assertTrue(hasEdge(index, "SUBSCRIBES_TO", "PaymentConsumer#consume", route.key));
        assertTrue(hasEdge(index, "ROUTES_TO", "PaymentTopology#paymentBinding", route.key));
        assertFalse(hasEdge(index, "ROUTES_TO", "PaymentTopology#paymentQueue", route.key),
                "the queue declaration is not the binding method");
    }

    @Test
    void rocketMqTopicAndTagFilterAreBothPartOfTheRoute(@TempDir Path tmp) throws IOException {
        pom(tmp);
        write(tmp, "src/main/java/com/example/InventoryEvent.java", """
                package com.example;
                public record InventoryEvent(String sku, int delta) {}
                """);
        write(tmp, "src/main/java/com/example/InventoryProducer.java", """
                package com.example;
                import org.apache.rocketmq.spring.core.RocketMQTemplate;
                public class InventoryProducer {
                    private final RocketMQTemplate rocket;
                    public InventoryProducer(RocketMQTemplate rocket) { this.rocket = rocket; }
                    public void publish(InventoryEvent event) {
                        rocket.syncSend("inventory.changed:RESERVED", event);
                    }
                }
                """);
        write(tmp, "src/main/java/com/example/InventoryConsumer.java", """
                package com.example;
                import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
                @RocketMQMessageListener(topic = "inventory.changed", selectorExpression = "RESERVED")
                public class InventoryConsumer {
                    public void consume(InventoryEvent event) {}
                }
                """);

        CodeIndex index = index(tmp);
        Sym topic = symbol(index, "MESSAGE_TOPIC", "inventory.changed");
        Sym route = symbol(index, "MESSAGE_ROUTE", "RESERVED");
        Sym filter = symbol(index, "MESSAGE_FILTER", "RESERVED");
        assertNotNull(topic);
        assertNotNull(route, "RocketMQ topic + tag must create a filtered route");
        assertNotNull(filter);
        assertTrue(hasEdge(index, "PUBLISHES_TO", "InventoryProducer#publish", route.key));
        assertTrue(hasEdge(index, "SUBSCRIBES_TO", "InventoryConsumer", route.key));
        assertTrue(hasEdge(index, "FILTERS_BY", "InventoryConsumer", filter.key));
    }

    @Test
    void dynamicTopicIsRecordedAsABlindSpotInsteadOfAGuess(@TempDir Path tmp) throws IOException {
        pom(tmp);
        write(tmp, "src/main/java/com/example/DynamicProducer.java", """
                package com.example;
                import org.springframework.kafka.core.KafkaTemplate;
                public class DynamicProducer {
                    private final KafkaTemplate<String, String> kafka;
                    public DynamicProducer(KafkaTemplate<String, String> kafka) { this.kafka = kafka; }
                    public void publish(String topic, String event) { kafka.send(topic, event); }
                }
                """);

        CodeIndex index = index(tmp);
        assertTrue(index.unresolved.stream().anyMatch(u -> u.reason.startsWith("dynamic messaging destination")),
                "a runtime topic must be reported as a blind spot");
        assertFalse(index.symbols.values().stream()
                        .anyMatch(s -> "MESSAGE_ROUTE".equals(s.type)),
                "the analyser must not invent a concrete route");
    }

    @Test
    void schemaFingerprintTracksRequiredFieldsAndTypes(@TempDir Path tmp) throws IOException {
        pom(tmp);
        write(tmp, "src/main/java/com/example/OrderEvent.java", """
                package com.example;
                public class OrderEvent {
                    private final String orderId;
                    private long amount;
                    public OrderEvent(String orderId) { this.orderId = orderId; }
                }
                """);
        write(tmp, "src/main/java/com/example/OrderConsumer.java", """
                package com.example;
                import org.springframework.kafka.annotation.KafkaListener;
                public class OrderConsumer {
                    @KafkaListener(topics = "order.created")
                    public void consume(OrderEvent event) {}
                }
                """);

        CodeIndex index = index(tmp);
        Sym schema = symbol(index, "MESSAGE_SCHEMA", "OrderEvent");
        assertNotNull(schema);
        assertEquals("JSON", schema.meta.get("format"));
        assertTrue(schema.meta.get("fields").contains("orderId:string"));
        assertTrue(schema.meta.get("required").contains("orderId"));
    }

    @Test
    void removedTopicPropagatesToEveryProducerAndConsumer(@TempDir Path base, @TempDir Path head)
            throws IOException {
        writeMessaging(base, true);
        writeMessaging(head, false);

        RevisionPair revisions = revisions(base, head);
        CodeIndex baseIndex = indexSource(revisions.base);
        CodeIndex headIndex = indexSource(revisions.head);
        List<ChangeRecord> changes = new ChangeDetector(baseIndex, headIndex)
                .detect(new FileDiffCollector(revisions.base, revisions.head).collect());
        assertTrue(changes.stream().anyMatch(c -> "TOPIC_REMOVED".equals(c.changeType)
                        && c.qualifiedName.contains("order.created")),
                "removing a topic must be a semantic change");

        List<ImpactResult> impacts = new ImpactEngine(new com.geek.association.config.AppProperties())
                .analyze(new ImpactGraph(baseIndex, headIndex), changes,
                        ImpactEngine.options(new com.geek.association.config.AppProperties(), false));
        assertTrue(impacts.stream().anyMatch(i -> i.targetQualifiedName.contains("OrderProducer#publish")),
                "the producer must be reported as impacted");
        assertTrue(impacts.stream().anyMatch(i -> i.targetQualifiedName.contains("OrderConsumer#consume")),
                "the consumer must be reported as impacted");
        assertTrue(impacts.stream().anyMatch(i -> "MESSAGE_CONTRACT".equals(i.impactType)));
    }

    @Test
    void newRequiredPayloadFieldReachesTheConsumer(@TempDir Path base, @TempDir Path head)
            throws IOException {
        writeSchema(base, false);
        writeSchema(head, true);

        RevisionPair revisions = revisions(base, head);
        CodeIndex baseIndex = indexSource(revisions.base);
        CodeIndex headIndex = indexSource(revisions.head);
        List<ChangeRecord> changes = new ChangeDetector(baseIndex, headIndex)
                .detect(new FileDiffCollector(revisions.base, revisions.head).collect());
        assertTrue(changes.stream().anyMatch(c -> "MESSAGE_REQUIRED_CHANGED".equals(c.changeType)),
                "adding a required field must be classified as a requiredness change");

        List<ImpactResult> impacts = new ImpactEngine(new com.geek.association.config.AppProperties())
                .analyze(new ImpactGraph(baseIndex, headIndex), changes,
                        ImpactEngine.options(new com.geek.association.config.AppProperties(), false));
        assertTrue(impacts.stream().anyMatch(i -> i.targetQualifiedName.contains("OrderConsumer#consume")
                        && "MESSAGE_CONTRACT".equals(i.impactType)),
                "the consumer must be impacted by the schema change");
    }

    @Test
    void changedTopicMatchesTheConsumerInAnotherProgram(@TempDir Path own, @TempDir Path peer)
            throws IOException {
        writeMessaging(own, true);
        pom(peer);
        write(peer, "src/main/java/com/example/OrderEvent.java", """
                package com.example;
                public record OrderEvent(String orderId) {}
                """);
        write(peer, "src/main/java/com/example/OrderConsumer.java", """
                package com.example;
                import org.springframework.kafka.annotation.KafkaListener;
                public class OrderConsumer {
                    @KafkaListener(topics = "order.created")
                    public void consume(OrderEvent event) { handle(event); }
                    void handle(OrderEvent event) {}
                }
                """);

        CodeIndex ownIndex = index(own);
        CodeIndex peerIndex = index(peer);
        ChangeRecord change = new ChangeRecord();
        change.id = 1;
        change.changeType = ChangeDetector.TOPIC_REMOVED;
        change.symbolKey = ownIndex.symbols.values().stream()
                .filter(s -> "MESSAGE_TOPIC".equals(s.type) && s.qualifiedName.equals("order.created"))
                .map(s -> s.key).findFirst().orElseThrow();
        change.qualifiedName = "order.created";
        change.semanticSummary = "message topic removed: order.created";
        change.riskSeed = 0.9;

        PeerImpact info = new PeerImpact();
        info.peerSourceName = "peer";
        PeerImpact result = new PeerImpactEngine(new com.geek.association.config.AppProperties())
                .analyze(List.of(change), ownIndex, peerIndex, info);

        assertTrue(peerIndex.outgoing.values().stream().flatMap(List::stream)
                        .anyMatch(e -> "CALLS".equals(e.relation)
                                && e.from.contains("OrderConsumer#consume")
                                && e.to.contains("OrderConsumer#handle")),
                "the fixture must contain consume -> handle");
        assertTrue(result.matches.stream().anyMatch(m -> "MQ_TOPIC".equals(m.kind)
                        && "order.created".equals(m.contract)),
                "the same topic must be a shared contract");
        assertTrue(result.impacts.stream().anyMatch(i -> i.targetQualifiedName.contains("OrderConsumer#consume")),
                "the peer consumer must be impacted");
        assertTrue(result.impacts.stream().anyMatch(i -> i.targetQualifiedName.contains("OrderConsumer#handle")),
                "the impact must continue through the peer call graph");
        assertTrue(result.impacts.stream().anyMatch(i -> i.targetQualifiedName.contains("OrderConsumer#consume")
                        && i.path.stream().anyMatch(step -> "SUBSCRIBES_TO".equals(step.relation))),
                "the consumer path must explain the message subscription");
    }

    @Test
    void ordinaryDtoWithoutMessagingUseIsNotIndexedAsAMessageSchema(@TempDir Path tmp)
            throws IOException {
        pom(tmp);
        write(tmp, "src/main/java/com/example/UserView.java", """
                package com.example;
                public class UserView {
                    private String name;
                }
                """);

        CodeIndex index = index(tmp);
        assertFalse(index.symbols.values().stream()
                        .anyMatch(s -> "MESSAGE_SCHEMA".equals(s.type)
                                && s.qualifiedName.endsWith("UserView")),
                "a DTO that no messaging code uses is not a message contract");
    }

    @Test
    void avroSchemaFileBecomesANormalisedMessageContract(@TempDir Path tmp) throws IOException {
        pom(tmp);
        write(tmp, "src/main/avro/OrderCreated.avsc", """
                {
                  "type": "record",
                  "name": "OrderCreated",
                  "namespace": "com.example",
                  "fields": [
                    {"name": "orderId", "type": "string"},
                    {"name": "amount", "type": "long", "default": 0}
                  ]
                }
                """);

        CodeIndex index = index(tmp);
        Sym schema = symbol(index, "MESSAGE_SCHEMA", "com.example.OrderCreated");
        assertNotNull(schema, "an Avro schema must be indexed");
        assertEquals("AVRO", schema.meta.get("format"));
        assertTrue(schema.meta.get("fields").contains("orderId:string"));
        assertTrue(schema.meta.get("required").contains("orderId"));
        assertFalse(schema.meta.get("required").contains("amount"),
                "an Avro field with a default is optional");
    }

    @Test
    void removedRabbitQueueIsReportedAsATopologyChange(@TempDir Path base, @TempDir Path head)
            throws IOException {
        rabbitTopology(base, true);
        rabbitTopology(head, false);

        RevisionPair revisions = revisions(base, head);
        CodeIndex baseIndex = indexSource(revisions.base);
        CodeIndex headIndex = indexSource(revisions.head);
        List<ChangeRecord> changes = new ChangeDetector(baseIndex, headIndex)
                .detect(new FileDiffCollector(revisions.base, revisions.head).collect());

        assertTrue(changes.stream().anyMatch(c -> "MESSAGE_ROUTE_CHANGED".equals(c.changeType)
                        && c.qualifiedName.contains("payment.queue")),
                "a removed Rabbit queue must invalidate its route");
    }

    @Test
    void removedRabbitExchangeIsReportedAsATopologyChange(@TempDir Path base, @TempDir Path head)
            throws IOException {
        rabbitExchange(base, "payments");
        rabbitExchange(head, "billing");

        RevisionPair revisions = revisions(base, head);
        CodeIndex baseIndex = indexSource(revisions.base);
        CodeIndex headIndex = indexSource(revisions.head);
        List<ChangeRecord> changes = new ChangeDetector(baseIndex, headIndex)
                .detect(new FileDiffCollector(revisions.base, revisions.head).collect());

        assertTrue(changes.stream().anyMatch(c -> "MESSAGE_TOPOLOGY_REMOVED".equals(c.changeType)
                        && c.qualifiedName.contains("payments")),
                "a removed Rabbit exchange is a topology contract change");
    }

    @Test
    void nativeKafkaProducerAndConsumerShareATopic(@TempDir Path tmp) throws IOException {
        pom(tmp);
        write(tmp, "src/main/java/com/example/NativeKafkaProducer.java", """
                package com.example;
                import org.apache.kafka.clients.producer.*;
                public class NativeKafkaProducer {
                    private final KafkaProducer<String, String> producer;
                    public NativeKafkaProducer(KafkaProducer<String, String> producer) { this.producer = producer; }
                    public void publish(String value) {
                        producer.send(new ProducerRecord<>("native.orders", value));
                    }
                }
                """);
        write(tmp, "src/main/java/com/example/NativeKafkaConsumer.java", """
                package com.example;
                import org.apache.kafka.clients.consumer.KafkaConsumer;
                public class NativeKafkaConsumer {
                    private final KafkaConsumer<String, String> consumer;
                    public NativeKafkaConsumer(KafkaConsumer<String, String> consumer) { this.consumer = consumer; }
                    public void subscribe() { consumer.subscribe(java.util.List.of("native.orders")); }
                }
                """);

        CodeIndex index = index(tmp);
        Sym route = symbol(index, "MESSAGE_ROUTE", "native.orders");
        assertNotNull(route, "native Kafka topic must become a route");
        assertTrue(index.outgoing.values().stream().flatMap(List::stream)
                        .anyMatch(e -> "PUBLISHES_TO".equals(e.relation)
                                && index.symbol(e.from) != null
                                && index.symbol(e.from).qualifiedName.contains("NativeKafkaProducer#publish")),
                "native producer edge must exist");
        assertTrue(index.outgoing.values().stream().flatMap(List::stream)
                        .anyMatch(e -> "SUBSCRIBES_TO".equals(e.relation)
                                && index.symbol(e.from) != null
                                && index.symbol(e.from).qualifiedName.contains("NativeKafkaConsumer#subscribe")),
                "native consumer edge must exist");
        assertTrue(hasEdge(index, "PUBLISHES_TO", "NativeKafkaProducer#publish", route.key));
        assertTrue(hasEdge(index, "SUBSCRIBES_TO", "NativeKafkaConsumer#subscribe", route.key));
    }

    @Test
    void topicFromSpringConfigurationPropertyIsResolved(@TempDir Path tmp) throws IOException {
        pom(tmp);
        write(tmp, "src/main/resources/application.yml", """
                app:
                  topics:
                    orders: configured.orders
                """);
        write(tmp, "src/main/java/com/example/ConfiguredConsumer.java", """
                package com.example;
                import org.springframework.kafka.annotation.KafkaListener;
                public class ConfiguredConsumer {
                    @KafkaListener(topics = "${app.topics.orders}")
                    public void consume(String event) {}
                }
                """);

        CodeIndex index = index(tmp);
        assertTrue(index.unresolved.stream().noneMatch(u -> u.reason.contains("Kafka listener topic")),
                "the configured topic must resolve, not become a blind spot: " + index.unresolved);
        assertNotNull(symbol(index, "MESSAGE_TOPIC", "configured.orders"),
                "a configuration-backed topic must resolve to its declared value");
    }

    @Test
    void nativeRocketMqProducerAndConsumerJoinTopicAndTag(@TempDir Path tmp) throws IOException {
        pom(tmp);
        write(tmp, "src/main/java/com/example/NativeRocketProducer.java", """
                package com.example;
                import org.apache.rocketmq.client.producer.DefaultMQProducer;
                import org.apache.rocketmq.common.message.Message;
                public class NativeRocketProducer {
                    private final DefaultMQProducer producer;
                    public NativeRocketProducer(DefaultMQProducer producer) { this.producer = producer; }
                    public void publish() throws Exception {
                        Message message = new Message("native.inventory", "RESERVED", new byte[0]);
                        producer.send(message);
                    }
                }
                """);
        write(tmp, "src/main/java/com/example/NativeRocketConsumer.java", """
                package com.example;
                import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
                public class NativeRocketConsumer {
                    private final DefaultMQPushConsumer consumer;
                    public NativeRocketConsumer(DefaultMQPushConsumer consumer) { this.consumer = consumer; }
                    public void subscribe() throws Exception {
                        consumer.subscribe("native.inventory", "RESERVED");
                    }
                }
                """);

        CodeIndex index = index(tmp);
        Sym route = symbol(index, "MESSAGE_ROUTE", "RESERVED");
        assertNotNull(route, "native RocketMQ topic + tag must become a route");
        assertTrue(hasEdge(index, "PUBLISHES_TO", "NativeRocketProducer#publish", route.key));
        assertTrue(hasEdge(index, "SUBSCRIBES_TO", "NativeRocketConsumer#subscribe", route.key));
    }

    @Test
    void protobufFieldNumberChangeIsASchemaChange(@TempDir Path base, @TempDir Path head)
            throws IOException {
        proto(base, "1");
        proto(head, "2");

        RevisionPair revisions = revisions(base, head);
        CodeIndex baseIndex = indexSource(revisions.base);
        CodeIndex headIndex = indexSource(revisions.head);
        Sym baseProto = symbol(baseIndex, "MESSAGE_SCHEMA", "com.example.OrderCreated");
        Sym headProto = symbol(headIndex, "MESSAGE_SCHEMA", "com.example.OrderCreated");
        assertNotNull(baseProto, "base protobuf schema must be indexed");
        assertNotNull(headProto, "head protobuf schema must be indexed");
        assertTrue(baseProto.meta.get("fields").contains("#1"));
        assertTrue(headProto.meta.get("fields").contains("#2"));
        List<ChangeRecord> changes = new ChangeDetector(baseIndex, headIndex)
                .detect(new FileDiffCollector(revisions.base, revisions.head).collect());

        assertTrue(changes.stream().anyMatch(c -> "MESSAGE_SCHEMA_CHANGED".equals(c.changeType)
                        && c.qualifiedName.contains("OrderCreated")),
                "a protobuf wire-contract change must be reported");
    }

    private static void rabbitTopology(Path root, boolean withQueue) throws IOException {
        pom(root);
        write(root, "src/main/java/com/example/PaymentTopology.java", """
                package com.example;
                import org.springframework.amqp.core.*;
                import org.springframework.context.annotation.Bean;
                public class PaymentTopology {
                    @Bean Queue paymentQueue() { return new Queue("%s"); }
                    @Bean TopicExchange payments() { return new TopicExchange("payments"); }
                }
                """.formatted(withQueue ? "payment.queue" : "payment.other"));
    }

    private static void rabbitExchange(Path root, String exchange) throws IOException {
        pom(root);
        write(root, "src/main/java/com/example/PaymentTopology.java", """
                package com.example;
                import org.springframework.amqp.core.*;
                import org.springframework.context.annotation.Bean;
                public class PaymentTopology {
                    @Bean TopicExchange payments() { return new TopicExchange("%s"); }
                }
                """.formatted(exchange));
    }

    private static void proto(Path root, String fieldNumber) throws IOException {
        pom(root);
        write(root, "src/main/proto/order.proto", """
                syntax = "proto3";
                package com.example;
                message OrderCreated {
                  string orderId = %s;
                }
                """.formatted(fieldNumber));
    }

    private static void writeMessaging(Path root, boolean withTopic) throws IOException {
        pom(root);
        write(root, "src/main/java/com/example/OrderEvent.java", """
                package com.example;
                public record OrderEvent(String orderId) {}
                """);
        if (!withTopic) {
            write(root, "src/main/java/com/example/OrderProducer.java", """
                    package com.example;
                    import org.springframework.kafka.core.KafkaTemplate;
                    public class OrderProducer {
                        private final KafkaTemplate<String, OrderEvent> kafka;
                        public OrderProducer(KafkaTemplate<String, OrderEvent> kafka) { this.kafka = kafka; }
                        public void publish(OrderEvent event) { kafka.send("order.other", event); }
                    }
                    """);
            write(root, "src/main/java/com/example/OrderConsumer.java", """
                    package com.example;
                    import org.springframework.kafka.annotation.KafkaListener;
                    public class OrderConsumer {
                        @KafkaListener(topics = "order.other")
                        public void consume(OrderEvent event) {}
                    }
                    """);
            return;
        }
        write(root, "src/main/java/com/example/OrderProducer.java", """
                package com.example;
                import org.springframework.kafka.core.KafkaTemplate;
                public class OrderProducer {
                    private final KafkaTemplate<String, OrderEvent> kafka;
                    public OrderProducer(KafkaTemplate<String, OrderEvent> kafka) { this.kafka = kafka; }
                    public void publish(OrderEvent event) { kafka.send("order.created", event); }
                }
                """);
        write(root, "src/main/java/com/example/OrderConsumer.java", """
                package com.example;
                import org.springframework.kafka.annotation.KafkaListener;
                public class OrderConsumer {
                    @KafkaListener(topics = "order.created")
                    public void consume(OrderEvent event) {}
                }
                """);
    }

    private static void writeSchema(Path root, boolean withRequiredField) throws IOException {
        pom(root);
        write(root, "src/main/java/com/example/OrderEvent.java", withRequiredField
                ? """
                  package com.example;
                  public class OrderEvent {
                      private final String orderId;
                      private final String tenantId;
                      public OrderEvent(String orderId, String tenantId) {
                          this.orderId = orderId;
                          this.tenantId = tenantId;
                      }
                  }
                  """
                : """
                  package com.example;
                  public class OrderEvent {
                      private final String orderId;
                      public OrderEvent(String orderId) { this.orderId = orderId; }
                  }
                  """);
        write(root, "src/main/java/com/example/OrderConsumer.java", """
                package com.example;
                import org.springframework.kafka.annotation.KafkaListener;
                public class OrderConsumer {
                    @KafkaListener(topics = "order.created")
                    public void consume(OrderEvent event) {}
                }
                """);
    }

    private static RevisionPair revisions(Path base, Path head) {
        RevisionPair revisions = new RevisionPair();
        revisions.mode = "FIXTURE";
        revisions.base = new DirSource(base, "base", MAX_FILES);
        revisions.head = new DirSource(head, "head", MAX_FILES);
        return revisions;
    }

    private static CodeIndex indexSource(com.geek.association.source.FileSource source) {
        return new CodeIndexer(MAX_FILES).index(source, source.label(), source.label());
    }
}
