package com.flashseat.worker.ticket;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flashseat.tickets")
public record TicketProperties(String topic,
                               String deadLetterTopic,
                               int topicPartitions,
                               short topicReplicas,
                               String secret,
                               String fromAddress,
                               String recipientDomain,
                               int maxAttempts,
                               Duration retryBackoff) {
}
