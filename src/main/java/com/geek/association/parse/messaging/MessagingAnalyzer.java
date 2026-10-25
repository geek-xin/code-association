package com.geek.association.parse.messaging;

import com.geek.association.model.CodeIndex;
import com.geek.association.parse.messaging.kafka.KafkaMessagingAdapter;
import com.geek.association.parse.messaging.rabbit.RabbitMessagingAdapter;
import com.geek.association.parse.messaging.rocketmq.RocketMqMessagingAdapter;
import com.geek.association.source.FileSource;

import java.util.List;

/**
 * Runs every middleware adapter over one revision. The adapters share one route vocabulary, so
 * the impact engine does not need to know which broker produced an edge.
 */
public class MessagingAnalyzer {

    private final List<MessagingAdapter> adapters = List.of(
            new KafkaMessagingAdapter(),
            new RabbitMessagingAdapter(),
            new RocketMqMessagingAdapter()
    );

    public void analyze(FileSource source, CodeIndex index) {
        MessagingSupport support = new MessagingSupport(source, index);
        for (MessagingAdapter adapter : adapters) {
            adapter.analyze(source, index, support);
        }
        new ExternalSchemaAnalyzer().analyze(source, index, support);
    }
}
