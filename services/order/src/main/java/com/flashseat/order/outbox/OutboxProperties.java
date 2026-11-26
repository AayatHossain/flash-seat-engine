package com.flashseat.order.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flashseat.outbox")
public record OutboxProperties(String topic,
                               int topicPartitions,
                               short topicReplicas,
                               int batchSize,
                               Duration sendTimeout,
                               Publisher publisher) {

    public record Publisher(boolean enabled, Duration interval) {
    }
}
