package com.flashseat.order.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** Records an event in the outbox table. Must run inside the same transaction as the change it describes. */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final MeterRegistry metrics;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.json = json;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String aggregateType, String aggregateId, String eventType, Object payload) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                        VALUES (:id, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))
                        """)
                .param("id", eventId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", json.writeValueAsString(payload))
                .update();
        countAfterCommit(eventType);
        return eventId;
    }

    // Every business step of an order writes exactly one event, so counting committed events gives
    // orders created, paid, refunded and failed payments without touching the order code.
    private void countAfterCommit(String eventType) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.counter("flashseat.order.events", "type", eventType).increment();
            }
        });
    }
}
