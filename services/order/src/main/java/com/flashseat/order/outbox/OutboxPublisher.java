package com.flashseat.order.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Copies committed outbox rows to Kafka and marks them as published.
 * <p>
 * Delivery is at least once: if the process dies after Kafka accepted a batch but before the rows were
 * marked, the batch is sent again. Consumers use the {@code event-id} header to ignore repeats.
 */
public class OutboxPublisher {

    public static final String EVENT_ID_HEADER = "event-id";
    public static final String EVENT_TYPE_HEADER = "event-type";

    // Any fixed number works, as long as every replica uses the same one.
    private static final long PUBLISHER_LOCK_KEY = 7_340_002L;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;

    public OutboxPublisher(JdbcClient jdbc, PlatformTransactionManager transactionManager,
                           KafkaTemplate<String, String> kafka, OutboxProperties properties) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.kafka = kafka;
        this.properties = properties;
    }

    /** Publishes everything that is waiting. Returns how many events were sent. */
    public int publishPending() {
        int total = 0;
        int publishedInBatch;
        do {
            publishedInBatch = transactions.execute(status -> publishOneBatch());
            total += publishedInBatch;
        } while (publishedInBatch == properties.batchSize());
        return total;
    }

    private int publishOneBatch() {
        // One publisher at a time keeps events in created_at order, so each order's events reach Kafka in sequence.
        boolean isLeader = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", PUBLISHER_LOCK_KEY)
                .query(Boolean.class)
                .single();
        if (!isLeader) {
            return 0;
        }

        List<OutboxEvent> batch = jdbc.sql("""
                        SELECT id, aggregate_id, event_type, payload::text AS payload
                        FROM outbox
                        WHERE published_at IS NULL
                        ORDER BY created_at, id
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("batchSize", properties.batchSize())
                .query((row, rowNum) -> new OutboxEvent(
                        row.getObject("id", UUID.class),
                        row.getString("aggregate_id"),
                        row.getString("event_type"),
                        row.getString("payload")))
                .list();
        if (batch.isEmpty()) {
            return 0;
        }

        List<CompletableFuture<SendResult<String, String>>> sends = batch.stream().map(this::send).toList();
        for (CompletableFuture<SendResult<String, String>> send : sends) {
            // If any send fails, this throws, the transaction rolls back and the whole batch is retried later.
            awaitAcknowledgement(send);
        }

        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id IN (:ids)")
                .param("ids", batch.stream().map(OutboxEvent::id).toList())
                .update();
        return batch.size();
    }

    private CompletableFuture<SendResult<String, String>> send(OutboxEvent event) {
        // The order id is the message key: all events of one order go to the same partition, in order.
        ProducerRecord<String, String> record = new ProducerRecord<>(properties.topic(), event.aggregateId(), event.payload());
        record.headers().add(EVENT_ID_HEADER, event.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EVENT_TYPE_HEADER, event.eventType().getBytes(StandardCharsets.UTF_8));
        try {
            return kafka.send(record);
        } catch (RuntimeException e) {
            throw new OutboxPublishException("Kafka rejected an outbox event", e);
        }
    }

    private void awaitAcknowledgement(CompletableFuture<SendResult<String, String>> send) {
        try {
            send.get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutboxPublishException("Interrupted while waiting for Kafka", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new OutboxPublishException("Kafka did not acknowledge an outbox event", e);
        }
    }

    private record OutboxEvent(UUID id, String aggregateId, String eventType, String payload) {
    }
}
