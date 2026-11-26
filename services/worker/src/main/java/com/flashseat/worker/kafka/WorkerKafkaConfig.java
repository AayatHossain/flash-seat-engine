package com.flashseat.worker.kafka;

import com.flashseat.worker.ticket.InvalidEventException;
import com.flashseat.worker.ticket.TicketProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class WorkerKafkaConfig {

    /**
     * A failing message is retried a few times with a pause, then parked on the dead-letter topic so it
     * cannot block the messages behind it. Messages that can never succeed skip the retries.
     */
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> kafka, TicketProperties properties) {
        DeadLetterPublishingRecoverer toDeadLetterTopic = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(properties.deadLetterTopic(), record.partition()));
        FixedBackOff backOff = new FixedBackOff(properties.retryBackoff().toMillis(), properties.maxAttempts() - 1);

        DefaultErrorHandler handler = new DefaultErrorHandler(toDeadLetterTopic, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }

    @Bean
    NewTopic orderEventsTopic(TicketProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.topicPartitions())
                .replicas(properties.topicReplicas())
                .build();
    }

    // Same partition count as the main topic: a failed message keeps its partition number on the dead-letter topic.
    @Bean
    NewTopic deadLetterTopic(TicketProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(properties.topicPartitions())
                .replicas(properties.topicReplicas())
                .build();
    }
}
